package com.notdxbi.dxbi.tv.phone

import android.content.Context
import com.notdxbi.dxbi.tv.Phone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stremio as the phone app's screens see it: what to show (kept current, and remembered between
 * launches so the app opens on the last Home it showed) and what they can do. All of it through [Engine].
 */
class Stremio(context: Context, val engine: Engine, private val scope: CoroutineScope) {

    private val app = context.applicationContext

    private val cache = context.getSharedPreferences("phone-cache", Context.MODE_PRIVATE)

    // The last of each, from this launch or the one before
    private fun remembered(field: String): StateFlow<JSONObject?> {
        val saved = cache.getString(field, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val flow = MutableStateFlow(saved)
        scope.launch {
            engine.state(field).collect { live ->
                if (live == null) return@collect
                flow.value = live
                cache.edit().putString(field, live.toString()).apply()
            }
        }
        return flow
    }

    private val ctxState = remembered("ctx")
    private val boardState = remembered("board")
    private val continueState = remembered("continue_watching_preview")

    val ready: StateFlow<Boolean> = engine.ready
    val account: StateFlow<Account?> = ctxState.map(Account::from).stateIn(scope, SharingStarted.Eagerly, Account.from(ctxState.value))
    val continueWatching: StateFlow<List<Continue>> = continueState.map { Continue.list(it).orEmpty() }.stateIn(scope, SharingStarted.Eagerly, Continue.list(continueState.value).orEmpty())

    // dx.bi's own Home pieces, asked for from the plugin and remembered the same way
    private companion object {
        /** Where the page waits, out of sight: a screen Stremio has nothing on */
        const val PARKED = "#/dxbi"
    }

    private fun saved(key: String) = cache.getString(key, null)?.let { runCatching { JSONArray(it) }.getOrNull() }
    val billboard = MutableStateFlow(saved("x.billboard").objects().map(Title::from))
    val myList = MutableStateFlow(saved("x.myList").objects().map { ListRow(it.optString("name"), it.optJSONArray("items").objects().map(Title::from)) })
    val forYou = MutableStateFlow<ListRow?>(saved("x.forYou").objects().firstOrNull()?.let { ListRow(it.optString("name"), it.optJSONArray("items").objects().map(Title::from)) })
    val airing = MutableStateFlow(saved("x.airing").objects().map(Airing::from))
    /** The story, genres, year and length of the Continue Watching titles (Stremio's library keeps none), by id */
    val described = MutableStateFlow<Map<String, JSONObject>>(emptyMap())
    val layout = MutableStateFlow(HomeLayout.from(cache.getString("x.layout", null)?.let { runCatching { JSONObject(it) }.getOrNull() }))
    val viewers = MutableStateFlow(saved("x.viewers").objects().map(::viewerOf))
    val viewer = MutableStateFlow(viewers.value.let { list -> list.firstOrNull { it.id == cache.getString("x.viewer", null) } ?: list.firstOrNull() })
    /** Top Picks for You, then Because you watched … (the plugin's recommender) */
    val picks = MutableStateFlow(saved("x.picks").objects().map { ListRow(it.optString("name"), it.optJSONArray("items").objects().map(Title::from)) }.filter { it.items.isNotEmpty() })
    /** Who's watching? has been answered since the app opened (or there's only one to be) */
    // (also when the app's screen was closed while the player had the TV, and comes back as the player ends: asked once already)
    val profileChosen = MutableStateFlow(cache.getLong("x.playerUntil", 0L) > System.currentTimeMillis())
    /** Who's watching? was skipped only because there was one person to be: when the account's other profiles arrive (synced), it's asked after all */
    private var profileAutoChosen = false
    private var profileWatch: Job? = null
    /** The recommender answered that this person has nothing watched to recommend from (not just that it hasn't answered yet) */
    val noHistory = MutableStateFlow(false)

    // The accounts kept on this device, the one in use (null: none, or browsing without one), and swapping to another
    private val vault = AccountVault(app)
    val accounts = MutableStateFlow(vault.all())
    val currentAccountId = MutableStateFlow(cache.getString("accounts.current", null))
    val swapping = MutableStateFlow(false)
    /** Add Account is on: the sign-in screen has Cancel */
    val addingAccount = MutableStateFlow(false)
    private var lastRemembered = 0L
    private var returnTo: String? = null

    private fun viewerOf(it: JSONObject) = Viewer(it.optString("id"), it.optString("name"), it.text("colour"), it.optBoolean("kids"))

    /** Home's catalog rows, in the viewer's order */
    val rows: StateFlow<List<Row>> = combine(boardState, layout) { board, layout -> layout.arrange(Row.list(board).orEmpty()) }
        .stateIn(scope, SharingStarted.Eagerly, layout.value.arrange(Row.list(boardState.value).orEmpty()))

    /** Every catalog Home could show, before the viewer's order and hiding (for Customise Home) */
    val allRows: StateFlow<List<Row>> = boardState.map { Row.list(it).orEmpty() }.stateIn(scope, SharingStarted.Eagerly, Row.list(boardState.value).orEmpty())

    /** Stremio's own settings (ctx.profile.settings): languages, subtitles, streaming server… */
    val profileSettings: StateFlow<JSONObject> = ctxState.map { it?.optJSONObject("settings") ?: JSONObject() }
        .stateIn(scope, SharingStarted.Eagerly, ctxState.value?.optJSONObject("settings") ?: JSONObject())

    val installedAddons: StateFlow<List<Addon>> = engine.state("installed_addons").map { it?.optJSONArray("items").objects().map(Addon::from) }
        .map { it.orEmpty() }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    val remoteAddons: StateFlow<JSONObject?> = engine.state("remote_addons")
    val library: StateFlow<List<Title>> = engine.state("library").map { it?.optJSONArray("items").objects().map(Title::from) }
        .map { it.orEmpty() }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    val streamingServer: StateFlow<JSONObject?> = engine.state("streaming_server")

    val details: StateFlow<Details?> = engine.state("meta_details").map(Details::from).stateIn(scope, SharingStarted.Eagerly, null)
    val search: StateFlow<List<Row>> = engine.state("search").map { Row.list(it).orEmpty() }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    val discover: StateFlow<Discover?> = engine.state("discover").map(Discover::from).stateIn(scope, SharingStarted.Eagerly, null)

    init {
        // Every time the page (re)starts: Home's rows, then dx.bi's pieces
        scope.launch {
            engine.ready.collect { up ->
                if (!up) return@collect
                // The page itself waits on an empty screen: its own Home (billboard, rows, posters) would
                // keep working away out of sight. First, so leaving its Home doesn't unload ours.
                ask("go", JSONObject().put("hash", PARKED))
                delay(400)
                loadHome()
                swapping.value = false
                refreshExtras()
            }
        }
        // The library changing (watched something, added to My List, another device) changes dx.bi's rows
        scope.launch {
            var last: String? = null
            continueState.collect { now ->
                val sig = now?.toString()
                if (last != null && sig != last && engine.ready.value) refreshExtras()
                last = sig
            }
        }
    }

    private suspend fun ask(op: String, args: JSONObject = JSONObject()): Any? = try {
        engine.call(op, args)
    } catch (e: Exception) {
        android.util.Log.w("dxbi", "engine $op failed: ${e.message}")
        null
    }

    /** A title from the library, with what it didn't keep filled in (for the billboard) */
    fun filledIn(title: Title): Title {
        val d = described.value[title.id] ?: return title
        return title.copy(
            description = title.description ?: d.optString("description").takeIf { it.isNotEmpty() && it != "null" },
            genres = title.genres.ifEmpty { d.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty() },
            year = title.year ?: d.optString("year").takeIf { it.isNotEmpty() && it != "null" },
            runtime = title.runtime ?: d.optString("runtime").takeIf { it.isNotEmpty() && it != "null" },
            // (anime from the Kitsu addon: its own logo and art, which the Library doesn't keep)
            logo = title.logo ?: d.optString("logo").takeIf { it.isNotEmpty() && it != "null" },
            background = title.background ?: d.optString("background").takeIf { it.isNotEmpty() && it != "null" },
        )
    }

    private suspend fun dx(method: String, vararg args: Any?): Any? =
        ask("dx", JSONObject().put("method", method).put("args", JSONArray(args.toList())))

    private suspend fun loadHome() {
        ask("load", JSONObject().put("field", "board").put("model", "CatalogsWithExtra").put("args", JSONObject().put("extra", JSONArray())))
        ask("loadRange", JSONObject().put("field", "board").put("start", 0).put("end", 30))
    }

    private var extrasJob: Job? = null

    fun refreshExtras() {
        extrasJob?.cancel()
        extrasJob = scope.launch {
            (dx("phone.viewers") as? JSONObject)?.let { v ->
                val list = v.optJSONArray("list").objects().map(::viewerOf)
                viewers.value = list
                viewer.value = list.firstOrNull { it.id == v.optString("current") } ?: list.firstOrNull()
                cache.edit().putString("x.viewers", v.optJSONArray("list").toString()).putString("x.viewer", viewer.value?.id).apply()
                // (one person to be: nothing to ask, but the account's other profiles may be on their way from another device)
                if (list.size < 2) {
                    profileChosen.value = true
                    profileAutoChosen = true
                    watchForProfiles()
                } else if (profileAutoChosen) {
                    profileAutoChosen = false
                    profileChosen.value = false
                }
            }
            rememberAccount()
            (dx("phone.layout") as? JSONObject)?.let {
                layout.value = HomeLayout.from(it)
                cache.edit().putString("x.layout", it.toString()).apply()
            }
            (dx("phone.myList") as? JSONArray)?.let {
                myList.value = it.objects().map { r -> ListRow(r.optString("name"), r.optJSONArray("items").objects().map(Title::from)) }
                cache.edit().putString("x.myList", it.toString()).apply()
            }
            // what's watched, everywhere the library knows of (a card's ✓, whichever row it's in)
            (dx("phone.marks") as? JSONObject)?.optJSONArray("watched")?.let { a ->
                watchedIds.value = (0 until a.length()).map { a.optString(it) }.toSet()
            }
            (dx("phone.describe", JSONArray(continueWatching.value.take(12).map { JSONArray(listOf(it.title.type, it.title.id)) })) as? JSONObject)?.let { o ->
                described.value = o.keys().asSequence().mapNotNull { k -> o.optJSONObject(k)?.let { k to it } }.toMap()
            }
            (dx("phone.airing") as? JSONArray)?.let {
                airing.value = it.objects().map(Airing::from)
                cache.edit().putString("x.airing", it.toString()).apply()
            }
            // The billboard and Because you watched fetch from the internet: they can take a few seconds
            (dx("phone.billboard") as? JSONArray)?.let {
                if (it.length() > 0 || billboard.value.isEmpty()) {
                    billboard.value = it.objects().map(Title::from)
                    cache.edit().putString("x.billboard", it.toString()).apply()
                }
            }
            // Top Picks and Because you watched: made on the device, so the first answer can take a few seconds
            // (a stale one is the last answer while a fresh one is made)
            var tries = 0
            while (tries++ < 14) {
                val answer = dx("phone.picks")
                if (answer is JSONObject) {
                    val rows = answer.optJSONArray("rows").objects().map { r -> ListRow(r.optString("name"), r.optJSONArray("items").objects().map(Title::from)) }.filter { r -> r.items.isNotEmpty() }
                    // (changed only when there's something new: swapped every time, the rows on Home kept vanishing and coming back)
                    if (rows != picks.value && (rows.isNotEmpty() || !answer.optBoolean("stale"))) picks.value = rows
                    cache.edit().putString("x.picks", answer.optJSONArray("rows").toString()).apply()
                    if (!answer.optBoolean("stale")) {
                        noHistory.value = rows.isEmpty()
                        break
                    }
                }
                delay(1500)
            }
            if (picks.value.isNotEmpty()) {
                forYou.value = null
                return@launch
            }
            // nothing watched yet to go on: popular titles in the genres of what was saved last
            repeat(10) {
                val answer = dx("phone.forYou")
                if (answer is JSONObject) {
                    val items = answer.optJSONArray("items").objects()
                    forYou.value = if (items.isEmpty()) null else ListRow(answer.optString("name"), items.map(Title::from))
                    cache.edit().putString("x.forYou", JSONArray().put(answer).toString()).apply()
                    return@launch
                }
                delay(1500)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Details

    private fun path(resource: String, type: String, id: String) =
        JSONObject().put("resource", resource).put("type", type).put("id", id).put("extra", JSONArray())

    /** A title's details, and the streams for [videoId] (a film's streams are its own; a series' wait for an episode) */
    fun open(type: String, id: String, videoId: String? = null) {
        val streamsFor = videoId ?: if (type != "series") id else null
        val args = JSONObject()
            .put("metaPath", path("meta", type, id))
            .put("streamPath", streamsFor?.let { path("stream", type, it) } ?: JSONObject.NULL)
            .put("guessStream", streamsFor == null && type != "series")
        scope.launch { ask("load", JSONObject().put("field", "meta_details").put("model", "MetaDetails").put("args", args)) }
    }

    fun setInList(add: Boolean) = scope.launch {
        ask("library", JSONObject().put("add", add))
        refreshExtras()
    }

    /** Every title watched (from the library: a card shows ✓ for these, whatever row it came in) */
    val watchedIds = MutableStateFlow<Set<String>>(emptySet())

    /** Titles added to or taken out of the Library here, before Stremio's own lists catch up */
    val listMarks = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** Adds a title that isn't open (Home's +): Stremio wants the whole title, so it's opened first */
    fun setInList(title: Title, add: Boolean) {
        listMarks.value = listMarks.value + (title.id to add)
        billboard.value = billboard.value.map { if (it.id == title.id) it.copy(inLibrary = add) else it }
        withOpened(title) { ask("library", JSONObject().put("add", add)) }
    }

    /**
     * Opens [title] to change it (Stremio changes the title open in details), then opens again what
     * was open before
     */
    private fun withOpened(title: Title, change: suspend () -> Unit) = scope.launch {
        val before = details.value?.let { d -> d.metaId?.let { Triple(d.title?.type.orEmpty(), it, d.videoId) } }
        open(title.type, title.id)
        for (attempt in 0 until 40) {
            val d = details.value
            if (d?.metaId == title.id && d.state == Load.Ready) break
            delay(150)
        }
        if (details.value?.metaId != title.id) return@launch
        change()
        if (before != null && before.second != title.id && before.first.isNotEmpty()) open(before.first, before.second, before.third)
        delay(1000)
        refreshExtras()
    }

    fun setWatched(watched: Boolean) = scope.launch {
        details.value?.metaId?.let { id -> watchedIds.value = if (watched) watchedIds.value + id else watchedIds.value - id }
        ask("watched", JSONObject().put("value", watched))
    }

    /** One video of the open title watched or not (a film: its own id); [upTo]: and the episodes before it in its season */
    fun setWatched(videoId: String, watched: Boolean, upTo: Boolean = false) = scope.launch {
        ask("videoWatched", JSONObject().put("id", videoId).put("value", watched).put("upTo", upTo))
    }

    /**
     * Report a problem, as dx.bi does everywhere: what they typed, with dx.bi's version, the phone,
     * settings, addon names and recent errors (no passwords, keys or stream links). The report's code
     * to post in dx.bi's Discord, or what went wrong
     */
    suspend fun report(description: String): Result<String> = runCatching {
        engine.call("dx", JSONObject().put("method", "report.send").put("args", JSONArray().put(description)), 30_000) as? String ?: ""
    }

    fun removeFromContinue(id: String) = scope.launch { ask("dismiss", JSONObject().put("id", id)) }

    private var christmasKept: Pair<Long, Christmas>? = null

    /**
     * Christmas at Home: the plugin looks up ~50 titles, so the first call takes a few seconds; the answer is
     * kept for an hour (null when it couldn't be had)
     */
    /** The Calendar's episodes: every dated episode of the Library's shows, last month to three months ahead (null: couldn't ask) */
    suspend fun calendar(): List<CalendarEpisode>? = (dx("phone.calendar") as? JSONArray)?.objects()?.map(CalendarEpisode::from)

    suspend fun christmas(): Christmas? {
        christmasKept?.let { (at, c) -> if (System.currentTimeMillis() - at < 3_600_000L) return c }
        val o = dx("phone.christmas") as? JSONObject ?: return christmasKept?.second
        val c = Christmas(
            daysToChristmas = o.optInt("daysToChristmas"),
            inSeason = o.optBoolean("inSeason"),
            rows = o.optJSONArray("rows").objects().map { r -> ListRow(r.optString("name"), r.optJSONArray("items").objects().map(Title::from)) }.filter { it.items.isNotEmpty() },
        )
        christmasKept = System.currentTimeMillis() to c
        return c
    }

    /** An anime's TV seasons in order (the plugin asks Kitsu: a few seconds the first time; none when it has one season or can't tell) */
    suspend fun animeSeasons(id: String): List<AnimeSeason> {
        val a = dx("phone.animeSeasons", id) as? JSONArray ?: return emptyList()
        return a.objects().map { AnimeSeason(Title.from(it), it.optString("label"), it.optInt("episodes"), it.optBoolean("current")) }
    }

    /** Whether the connection is metered (mobile data, or a hotspot): a stream chosen on it isn't remembered */
    fun onMeteredNetwork(): Boolean =
        (app.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager)?.isActiveNetworkMetered == true

    /** Whether this device decodes Dolby Vision (else a Dolby Vision-only file shows green and purple) */
    private val hasDolbyVisionDecoder: Boolean by lazy {
        runCatching {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { c -> !c.isEncoder && c.supportedTypes.any { it.equals("video/dolby-vision", true) } }
        }.getOrDefault(false)
    }

    /** The open title's streams, described by dx.bi, with Just press Play's pick */
    suspend fun streams(): StreamChoices? = (dx("phone.streams") as? JSONObject)?.let(StreamChoices::from)?.withoutDolbyVisionOnly(hasDolbyVisionDecoder)

    /**
     * Plays one of [streams] (dx.bi keeps the next best ready in case it won't play): in dx.bi's
     * player, or wherever the app sends it instead ([Phone.route]: the phone app's other apps, Chromecast, downloads)
     */
    fun play(option: StreamOption, target: Phone.Target = Phone.Target.Player) = scope.launch {
        Phone.route(app, option, target, playInOtherApp.value)
        val link = dx("phone.play", option.index, onMeteredNetwork()) as? String ?: option.play
        // (for Play or Resume on this film or episode again: straight back to this stream, PlayedLinks)
        if (target == Phone.Target.Player) com.notdxbi.dxbi.tv.PlayedLinks.put(app, link)
        ask("play", JSONObject().put("link", link))
    }

    /** The stream last played of [videoId], when it was in the last few hours (PlayedLinks) */
    fun playedLink(videoId: String): String? = com.notdxbi.dxbi.tv.PlayedLinks.get(app, videoId)
    fun forgetPlayed(videoId: String) = com.notdxbi.dxbi.tv.PlayedLinks.forget(app, videoId)

    /** Plays a player link as it is (one played before: PlayedLinks) */
    fun playLink(link: String) = scope.launch { ask("play", JSONObject().put("link", link)) }

    /** Plays through Stremio's player route, which dxbi-tv.js hands to the native player */
    fun play(stream: Stream) = scope.launch {
        stream.play?.let { ask("play", JSONObject().put("link", it)) }
    }

    // ---------------------------------------------------------------------------------------------
    // Search and See All

    fun search(query: String) = scope.launch {
        val extra = JSONArray().put(JSONArray().put("search").put(query))
        ask("load", JSONObject().put("field", "search").put("model", "CatalogsWithExtra").put("args", JSONObject().put("extra", extra)))
        ask("loadRange", JSONObject().put("field", "search").put("start", 0).put("end", 20))
    }

    /** A Discover link (a row's See All: #/discover/<addon>/<type>/<catalog>?genre=…) */
    fun discover(link: String) {
        val m = Regex("""#/discover/([^/]+)/([^/]+)/([^/?]+)(?:\?(.*))?""").find(link) ?: return
        val (base, type, catalog, query) = m.destructured
        val extra = JSONArray()
        query.split('&').filter { it.contains('=') }.forEach { pair ->
            val (k, v) = pair.split('=', limit = 2)
            extra.put(JSONArray().put(android.net.Uri.decode(k)).put(android.net.Uri.decode(v)))
        }
        discover(android.net.Uri.decode(base), android.net.Uri.decode(type), android.net.Uri.decode(catalog), extra)
    }

    fun discover(base: String, type: String, catalog: String, extra: JSONArray = JSONArray()) = scope.launch {
        val request = JSONObject().put("base", base).put("path", JSONObject().put("resource", "catalog").put("type", type).put("id", catalog).put("extra", extra))
        ask("load", JSONObject().put("field", "discover").put("model", "CatalogWithFilters").put("args", JSONObject().put("request", request)))
    }

    /** The same catalog with another genre (null: all) */
    fun discoverGenre(genre: String?) {
        val request = discover.value?.request ?: return
        val path = request.optJSONObject("path") ?: return
        val extra = JSONArray()
        path.optJSONArray("extra")?.let { old -> (0 until old.length()).mapNotNull { old.optJSONArray(it) }.filter { it.optString(0) != "genre" }.forEach { extra.put(it) } }
        if (genre != null) extra.put(JSONArray().put("genre").put(genre))
        discover(request.optString("base"), path.optString("type"), path.optString("id"), extra)
    }

    fun discoverMore() = scope.launch {
        ask("dispatch", JSONObject().put("field", "discover").put("action", JSONObject().put("action", "CatalogWithFilters").put("args", JSONObject().put("action", "LoadNextPage"))))
    }

    // ---------------------------------------------------------------------------------------------
    // Account and viewers

    /** Signs in; null when it worked, else what went wrong */
    suspend fun signIn(email: String, password: String): String? {
        ask("signIn", JSONObject().put("email", email).put("password", password))
        val answer = withTimeoutOrNull(20_000) {
            engine.events.filter { it.first == "UserAuthenticated" || it.first == "Error" }.first()
        } ?: return "Stremio didn't answer. Check your connection and try again."
        if (answer.first == "UserAuthenticated") {
            didSignIn()
            return null
        }
        return answer.second?.optJSONObject("error")?.text("message") ?: "That email and password didn't work."
    }

    fun signOut() = scope.launch {
        guest.value = false
        cache.edit().putBoolean("guest", false).apply()
        addingAccount.value = false
        // (the session ends for good: the kept copy would be a dead key)
        currentAccountId.value?.let { vault.remove(it) }
        setCurrent(null)
        accounts.value = vault.all()
        ask("signOut")
    }

    private fun setCurrent(id: String?) {
        currentAccountId.value = id
        cache.edit().putString("accounts.current", id).apply()
    }

    /** Keeps the signed-in account (its session and people) so it can be switched back to without a password */
    suspend fun rememberAccount(force: Boolean = false) {
        if (swapping.value) return
        val a = account.value
        if (a?.signedIn == true) {
            if (!force && System.currentTimeMillis() - lastRemembered < 300_000) return
            val export = ask("accountExport") as? JSONObject ?: return
            val profile = export.text("profile") ?: return
            val id = export.optString("id").ifEmpty { return }
            val data = dx("phone.accountData") as? JSONObject ?: JSONObject()
            vault.save(SavedAccount(id, export.optString("email"), export.text("avatar"), profile, data.toString()))
            setCurrent(id)
        } else if (guest.value) {
            val data = dx("phone.accountData") as? JSONObject ?: JSONObject()
            vault.save(SavedAccount(SavedAccount.GUEST, "", null, "", data.toString()))
            setCurrent(null)
        } else return
        lastRemembered = System.currentTimeMillis()
        accounts.value = vault.all()
    }

    /** Switches to a kept account, or (null) to browsing without one: nothing to type */
    fun switchAccount(id: String?) {
        if (swapping.value || (id == currentAccountId.value && (id != null || guest.value))) return
        if (id != null && vault.load(id) == null) {
            accounts.value = vault.all()
            return
        }
        addingAccount.value = false
        swap(if (id == null) vault.load(SavedAccount.GUEST) else vault.load(id), signedOut = false, toGuest = id == null)
    }

    /** Add Account: the sign-in screen, with Cancel to come back to this one */
    fun beginAddAccount() {
        if (swapping.value) return
        returnTo = currentAccountId.value
        addingAccount.value = true
        swap(null, signedOut = true, toGuest = false)
    }

    fun cancelAddAccount() {
        addingAccount.value = false
        val back = returnTo?.let { vault.load(it) }
        if (back != null) swap(back, signedOut = false, toGuest = false) else swap(vault.load(SavedAccount.GUEST), signedOut = false, toGuest = true)
    }

    /** Takes a kept account off this device (the account itself, and its Stremio session elsewhere, stay) */
    fun forgetAccount(id: String) {
        if (id == currentAccountId.value) { signOut(); return }
        vault.remove(id)
        accounts.value = vault.all()
    }

    /** Signed in a new account: kept from now on */
    private fun didSignIn() {
        addingAccount.value = false
        scope.launch { rememberAccount(force = true) }
    }

    /**
     * The swap: the account being left is kept first, then its data and session are replaced by [target]'s
     * (nothing: the sign-in screen, or guest) and Stremio's page starts again to read them.
     */
    private fun swap(target: SavedAccount?, signedOut: Boolean, toGuest: Boolean) {
        scope.launch {
            rememberAccount(force = true)
            swapping.value = true
            clearScreens()
            dx("phone.useAccountData", target?.dataObject ?: JSONObject())
            guest.value = toGuest
            cache.edit().putBoolean("guest", toGuest).apply()
            setCurrent(if (signedOut || toGuest) null else target?.id)
            lastRemembered = 0
            // (so the page saying it's ready again is noticed as a change)
            engine.ready.value = false
            ask("accountImport", JSONObject().put("profile", if (signedOut || toGuest) JSONObject.NULL else target?.profile ?: JSONObject.NULL))
            // (the page says it's ready when it has started again; if it never does, the screen doesn't stay)
            delay(30_000)
            swapping.value = false
        }
    }

    /** Everything on show belongs to the account being left: gone while the page starts again with the other */
    private fun clearScreens() {
        billboard.value = emptyList(); myList.value = emptyList(); forYou.value = null; picks.value = emptyList(); airing.value = emptyList()
        viewers.value = emptyList(); viewer.value = null
        profileChosen.value = false; noHistory.value = false
        (ctxState as MutableStateFlow).value = null
        (boardState as MutableStateFlow).value = null
        (continueState as MutableStateFlow).value = null
        cache.edit().remove("ctx").remove("board").remove("continue_watching_preview")
            .remove("x.billboard").remove("x.myList").remove("x.forYou").remove("x.picks").remove("x.airing").remove("x.layout").remove("x.viewers").remove("x.viewer").apply()
    }

    fun addViewer(name: String, colour: String) = scope.launch { profileAutoChosen = false; dx("viewers.add", name, colour); refreshExtras() }

    /** A new device with one person to be: looks for the account's profiles (the plugin pulls them from the sync) for a minute or so */
    private fun watchForProfiles() {
        if (profileWatch?.isActive == true || account.value?.signedIn != true) return
        profileWatch = scope.launch {
            repeat(12) {
                delay(5000)
                if ((dx("phone.viewers") as? JSONObject)?.optJSONArray("list")?.length().let { (it ?: 0) > 1 }) {
                    refreshExtras()
                    return@launch
                }
            }
        }
    }
    fun editViewer(id: String, name: String, colour: String) = scope.launch {
        dx("viewers.rename", id, name)
        dx("viewers.recolour", id, colour)
        refreshExtras()
    }
    fun removeViewer(id: String) = scope.launch { dx("viewers.remove", id); refreshExtras() }

    /** dx.bi settings → Play videos in: another app (VLC, mpv…) rather than dx.bi's player */
    val playInOtherApp = MutableStateFlow(cache.getBoolean("playInOtherApp", false))

    fun setPlayInOtherApp(on: Boolean) {
        playInOtherApp.value = on
        cache.edit().putBoolean("playInOtherApp", on).apply()
    }

    /** Browsing without an account (Stremio allows it: no sync, no library on other devices) */
    val guest = MutableStateFlow(cache.getBoolean("guest", false))

    fun continueAsGuest() {
        guest.value = true
        cache.edit().putBoolean("guest", true).apply()
    }

    // ---------------------------------------------------------------------------------------------
    // Addons

    fun loadAddons() = scope.launch {
        ask("load", JSONObject().put("field", "installed_addons").put("model", "InstalledAddonsWithFilters").put("args", JSONObject().put("request", JSONObject().put("type", JSONObject.NULL))))
    }

    /** Stremio's addon lists: "official" or "community" */
    fun loadRemoteAddons(catalog: String) = scope.launch {
        val request = JSONObject().put("base", "https://v3-cinemeta.strem.io/manifest.json")
            .put("path", JSONObject().put("resource", "addon_catalog").put("type", "all").put("id", catalog).put("extra", JSONArray()))
        ask("load", JSONObject().put("field", "remote_addons").put("model", "CatalogWithFilters").put("args", JSONObject().put("request", request)))
    }

    /** Each returns null when it worked, else what went wrong */
    suspend fun install(addon: Addon): String? = attempt("addonInstall", JSONObject().put("transportUrl", addon.transportUrl)) { loadHome() }
    suspend fun uninstall(addon: Addon): String? = attempt("addonUninstall", JSONObject().put("transportUrl", addon.transportUrl)) { loadHome() }
    suspend fun installFromLink(url: String): String? = attempt("addonFromUrl", JSONObject().put("url", url)) { loadHome() }

    private suspend fun attempt(op: String, args: JSONObject, after: suspend () -> Unit): String? = try {
        engine.call(op, args)
        after()
        null
    } catch (e: Exception) {
        e.message ?: "That didn't work"
    }

    // ---------------------------------------------------------------------------------------------
    // Speed per network (Settings → Networks)

    suspend fun networks(): List<NetworkInfo> = (dx("phone.networks") as? JSONArray).objects().map(NetworkInfo::from)

    suspend fun setNetwork(id: String, name: String? = null, cap: Int? = null, relearn: Boolean = false) {
        val changes = JSONObject()
        name?.let { changes.put("name", it) }
        cap?.let { changes.put("cap", it) }
        if (relearn) changes.put("relearn", true)
        dx("phone.setNetwork", id, changes)
    }

    suspend fun forgetNetwork(id: String) {
        dx("phone.forgetNetwork", id)
    }

    // ---------------------------------------------------------------------------------------------
    // Library (My List's See All)

    fun loadLibrary(type: String?) = scope.launch {
        val request = JSONObject().put("type", type ?: JSONObject.NULL).put("sort", "lastwatched")
        ask("load", JSONObject().put("field", "library").put("model", "LibraryWithFilters").put("args", JSONObject().put("request", request)))
    }

    fun libraryMore() = scope.launch {
        ask("dispatch", JSONObject().put("field", "library").put("action", JSONObject().put("action", "LibraryWithFilters").put("args", JSONObject().put("action", "LoadNextPage"))))
    }

    // ---------------------------------------------------------------------------------------------
    // Stremio's settings, Customise Home, reviews

    fun updateSettings(changes: JSONObject) = scope.launch { ask("updateSettings", JSONObject().put("changes", changes)) }

    fun saveLayout(layout: HomeLayout) = scope.launch {
        val data = JSONObject()
            .put("order", JSONArray(layout.order))
            .put("hidden", JSONArray(layout.hidden.toList()))
            .put("names", JSONObject(layout.names))
        this@Stremio.layout.value = layout
        dx("homeLayout.save", data)
        refreshExtras()
    }

    /** The cast with their photos (TMDB, with a key in dx.bi settings), or null */
    suspend fun cast(type: String, imdb: String, names: JSONArray): List<JSONObject>? =
        (engine.runCatching { call("dx", JSONObject().put("method", "phone.cast").put("args", JSONArray().put(type).put(imdb).put(names)), 25_000) }.getOrNull() as? JSONArray)?.objects()

    /** A title's age rating, countries and languages (TMDB, with a key in dx.bi settings), or null: the title page's Information */
    suspend fun facts(type: String, imdb: String): JSONObject? =
        engine.runCatching { call("dx", JSONObject().put("method", "phone.facts").put("args", JSONArray().put(type).put(imdb)), 20_000) }.getOrNull() as? JSONObject

    /**
     * The intro, recap and credits times for a video, fetched ahead (the plugin keeps them): the player's start waited for
     * them, up to 4 seconds, before it opened
     */
    fun prefetchSkips(type: String, metaId: String, videoId: String) {
        if (!skipsAsked.add(videoId)) return
        scope.launch { runCatching { dx("skipSegments", type, metaId, videoId) } }
    }
    private val skipsAsked = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** This profile's own score for a title, 1 to 10 (null: not rated). The iPad's Rate, shared through the plugin (phone.rating) */
    suspend fun rating(id: String): Int? = (runCatching { dx("phone.rating", id) }.getOrNull() as? Number)?.toInt()?.takeIf { it in 1..10 }

    /** Rates a title (null: takes the rating away); its name and kind go with it for the suggestions, as the iPad sends them */
    fun setRating(title: Title, score: Int?) = scope.launch {
        val meta = JSONObject().put("type", if (title.type == "movie") "movie" else "series").put("name", title.name)
            .put("year", title.year?.take(4)?.toIntOrNull() ?: 0).put("genres", JSONArray(title.genres.take(4)))
        dx("phone.setRating", title.id, score ?: 0, meta)
    }

    /** Someone's page (TMDB, with a key in dx.bi settings): by TMDB's id from the cast list, else by name. null without a key */
    suspend fun person(id: String?, name: String): JSONObject? =
        engine.runCatching { call("dx", JSONObject().put("method", "phone.person").put("args", JSONArray().put(id ?: "").put(name)), 40_000) }.getOrNull() as? JSONObject

    /** Titles like this one, for its page's Related row */
    suspend fun related(type: String, id: String, genres: List<String>): List<Title> =
        (engine.runCatching { call("dx", JSONObject().put("method", "phone.related").put("args", JSONArray().put(type).put(id).put(JSONArray(genres))), 25_000) }.getOrNull() as? JSONArray)
            .objects().map(Title::from)

    /** The catalog browser's first catalog (films, if there are any), unless one's already open */
    fun discoverStart() {
        if (discover.value?.request != null) return
        val rows = allRows.value
        (rows.firstOrNull { it.type == "movie" }?.discover ?: rows.firstOrNull()?.discover)?.let { discover(it) }
    }

    /** A title's scores and reviews (dx.bi's Reviews: MDBList, Wikidata, Wikipedia, TMDB with the keys set) */
    suspend fun reviews(type: String, id: String): Reviews? =
        (engine.runCatching { call("dx", JSONObject().put("method", "phone.reviews").put("args", JSONArray().put(type).put(id)), 40_000) }.getOrNull() as? JSONObject)?.let(Reviews::from)

    // ---------------------------------------------------------------------------------------------
    // Watch party (dx.bi's own: end-to-end encrypted, everyone streams from their own addons)

    suspend fun party(): JSONObject? = dx("phone.party.state") as? JSONObject

    /** Each returns null when it worked, else what went wrong */
    suspend fun startParty(name: String): String? = attempt("dx", JSONObject().put("method", "phone.party.start").put("args", JSONArray().put(name))) {}
    suspend fun joinParty(code: String, name: String): String? = attempt("dx", JSONObject().put("method", "phone.party.join").put("args", JSONArray().put(code).put(name))) {}
    fun rejoinParty() = scope.launch { dx("phone.party.rejoin") }
    fun leaveParty() = scope.launch { dx("phone.party.leave") }
    fun sayInParty(text: String) = scope.launch { dx("phone.party.say", text) }

    /** dx.bi settings, as the TV lists them */
    suspend fun settings(): List<Setting> = (ask("settings") as? JSONArray).objects().map(Setting::from)

    /** dx.bi settings → Blur unwatched episodes: episode pictures hidden until they're watched or started */
    suspend fun blurUnwatched(): Boolean = settings().firstOrNull { it.key == "blurUnwatched" }?.value == true

    /** dx.bi settings → Play Trailers (on unless switched off): a title's page fades its trailer in */
    suspend fun playTrailers(): Boolean = settings().firstOrNull { it.key == "playTrailers" }?.value != false

    /**
     * A look at a web stream before it's played: why it isn't a video (the addon's server errored, or sent a clip
     * saying so), or null when it looks fine or can't be told. Torrents aren't looked at.
     */
    suspend fun whyNotVideo(option: StreamOption): String? {
        val ahead = probes.remove(option.play)
        if (ahead != null) return kotlinx.coroutines.withTimeoutOrNull(8_000) { ahead.await() }
        return kotlinx.coroutines.withTimeoutOrNull(8_000) { probe(option) }
    }

    /** Checks the likely pick while the addons are still answering, so Play doesn't wait for it (and the debrid server has the file ready) */
    fun probeAhead(option: StreamOption) {
        if (probes.containsKey(option.play)) return
        if (probes.size > 20) probes.clear()
        probes[option.play] = scope.async { probe(option) }
    }

    private val probes = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<String?>>()

    private suspend fun probe(option: StreamOption): String? {
        val p = dx("phone.probe", option.index) as? JSONObject ?: return null
        val headers = p.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { h.optString(it) } }.orEmpty()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { StreamProbe.check(p.optString("url"), headers, p.optDouble("size", 0.0)) }
    }

    /** That stream isn't a video: the page won't pick it again by itself for a month */
    suspend fun reject(option: StreamOption, why: String) {
        dx("phone.reject", option.index, why)
    }

    /** Shuffle: [metaId]'s next episodes are random ones ([on]), until anything else is played (an ordinary Play clears it) */
    fun shuffling(metaId: String, on: Boolean) = scope.launch {
        dx("phone.shuffle", metaId, on)
    }

    /** Start from the Beginning: the next player for [videoId] starts at 0 ([on]), or not (an ordinary Play clears it) */
    fun startFromBeginning(videoId: String?, on: Boolean) = scope.launch {
        ask("fromStart", JSONObject().put("videoId", videoId ?: JSONObject.NULL).put("on", on))
    }

    /** Your stats (dx.bi's Watch stats), or null when they're switched off */
    suspend fun stats(): JSONObject? = dx("phone.stats") as? JSONObject

    suspend fun saveSetting(key: String, value: Any?) {
        ask("saveSetting", JSONObject().put("key", key).put("value", value ?: JSONObject.NULL))
        refreshExtras()
    }

    /** What a person said they like (Lumen's setup), kept by the plugin with the profiles and synced with the account */
    suspend fun taste(viewerId: String): JSONObject? = dx("phone.taste", viewerId) as? JSONObject
    suspend fun setTaste(viewerId: String, value: JSONObject) { dx("phone.setTaste", viewerId, value) }

    /** Who's watching: their own Continue Watching, watched marks and picks follow */
    fun chooseViewer(id: String) = scope.launch {
        profileChosen.value = true
        viewers.value.firstOrNull { it.id == id }?.let { viewer.value = it }
        dx("viewers.select", id)
        picks.value = emptyList()
        forYou.value = null
        noHistory.value = false
        refreshExtras()
    }
}
