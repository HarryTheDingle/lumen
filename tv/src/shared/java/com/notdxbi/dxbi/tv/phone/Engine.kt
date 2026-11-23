package com.notdxbi.dxbi.tv.phone

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import lumos.engine.Lumos
import lumos.engine.PendingSignIn
import lumos.engine.Session
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

/**
 * Lumos's stand-in for the page under Lumen TV's screens (Lumen's Engine.kt spoke to Stremio's web app and dxbi-engine.js).
 * The same calls and the same answers, made by the Lumos engine instead: Lumen's Stremio.kt and every screen above it
 * are unchanged copies. No web page, no Stremio. (The iPhone's twin is ios/Lumos/Engine/Page.swift.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Engine(context: Context) {

    class Failure(message: String) : Exception(message)

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("lumos", Context.MODE_PRIVATE)
    private val stateFile = File(app.filesDir, "lumos-state.json")
    /** Just where the player is (a few hundred bytes), written every report: an app closed by accident loses seconds */
    private val placeFile = File(app.filesDir, "lumos-place.json")

    /** Everything that touches the engine's state runs here, one thing at a time */
    private val one = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + one)

    /** The Lumos engine: addons, TorBox, sync */
    val core = Lumos(SYNC)

    private val states = HashMap<String, MutableStateFlow<JSONObject?>>()

    /** Up at once: there's no page to wait for */
    val ready = MutableStateFlow(false)

    private var details: JSONObject? = null
    private var lastBoard: JSONObject? = null
    private val streamsPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private var lastLoggedTimings = ""
    private var fromStart: String? = null
    private var lastPickLog = ""

    /** What's playing (from the play link), for saving its place */
    private var playing: JSONObject? = null

    /** Lumos's sign-in: whether this TV has a session (Lumen's screens see it as ctx.signedIn) */
    val signedIn = MutableStateFlow(false)

    /** Whether the TV still needs its stream addons (it has none, and wasn't told to go without) */
    val needsAddons = MutableStateFlow(false)

    init {
        core.clock = { System.currentTimeMillis() }
        core.phone.onStreamsChanged = { onStreamsChanged() }
        if (stateFile.exists()) runCatching { core.restore(stateFile.readText()) }
        takeBackPlace()
        val token = prefs.getString("token", null)
        val account = prefs.getString("account", null)
        if (token != null && account != null) core.useSession(token, account)
        signedIn.value = core.signedIn
        checkAddons()
        core.useStremio(prefs.getString("stremio", null))
        configure()
        applySettings()
        // (Match my internet speed: this network's speed read in, and a fresh test a little after the start)
        core.phone.warmSpeed()
        scope.launch {
            tell("ctx", ctx())
            tell("viewers", obj(core.phone.viewers()))
            ready.value = true
            android.util.Log.i("lumen-boot", "engine ready ${android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()} ms after launch")
            syncNow()
        }
        // (every ten minutes: what other devices, and Lumen while it's still in use, have watched)
        scope.launch { while (true) { delay(10 * 60_000L); syncNow() } }
        // (and each time Lumos comes back on screen: the loop above stands still while the TV sleeps, so after a night
        // in Stremio or Lumen, Home would have waited up to ten minutes to catch up)
        (context as? androidx.lifecycle.LifecycleOwner)?.lifecycle?.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            private var first = true
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                if (first) { first = false; return }
                scope.launch { syncNow() }
            }
        })
    }

    // ---- Lumos's own: sign-in, addons, sync ----

    var addons: List<String>
        get() = prefs.getString("addons", null)?.let { s -> JSONArray(s).let { a -> (0 until a.length()).map { a.optString(it) } } }.orEmpty()
        set(v) { prefs.edit().putString("addons", JSONArray(v).toString()).apply(); scope.launch { configure(); refreshAll() } }

    var torBoxKey: String
        get() = prefs.getString("torbox", "").orEmpty()
        set(v) { prefs.edit().putString("torbox", v.trim()).apply(); scope.launch { configure() } }

    /** Lumen's settings into the engine (Just press Play, quality, speed, IMAX, preferred addon, fallback) */
    private fun applySettings() {
        core.justPressPlay = LumosSettings.on(prefs, "quickPlay")
        core.qualityCap = LumosSettings.text(prefs, "quickPlayQuality").toIntOrNull() ?: 0
        core.matchSpeed = LumosSettings.on(prefs, "quickPlaySpeed")
        core.preferImax = LumosSettings.on(prefs, "preferImax")
        core.preferredAddon = LumosSettings.text(prefs, "quickPlayAddon")
        core.fallbackAsk = LumosSettings.text(prefs, "quickPlayFallback") == "ask"
    }

    /**
     * A problem report: the last 30 minutes of this TV's log (secrets out) to the sync server, for Harry. Its reference,
     * or an exception (not signed in, offline)
     */
    suspend fun sendReport(context: Context, note: String?, crash: Boolean): String = withContext(Dispatchers.IO) {
        val gz = com.notdxbi.dxbi.tv.LogKeeper.report(context)
        core.sendLogs(gz, "${Build.MANUFACTURER} ${Build.MODEL}", com.notdxbi.dxbi.tv.BuildConfigVersion.name(context), note, crash)
    }

    /** Settings → Profiles & Account: whether Stremio is kept in step (while Lumen's still used) */
    val keepsStremio: Boolean get() = core.keepsStremio

    /** Stops keeping Stremio in step: its session is forgotten on this TV */
    fun stopKeepingStremio() = scope.launch {
        prefs.edit().remove("stremio").apply()
        core.useStremio(null)
    }

    /** Settings → Bring from Stremio: Stremio's link code screen again (addons and history) */
    fun bringFromStremio() { needsAddons.value = true }

    /** The addons, as Settings lists them: link and name */
    suspend fun addonList(): List<Pair<String, String>> = withContext(one) {
        addons.map { url ->
            val name = runCatching { core.phone.addonName(url) }.getOrNull() ?: android.net.Uri.parse(url).host ?: url
            url to name
        }
    }

    fun removeAddon(url: String) { addons = addons - url }

    private fun configure() {
        val list = listOf(CINEMETA) + addons.filter { it.isNotBlank() && !it.contains("v3-cinemeta") }
        core.configure(list, torBoxKey.ifBlank { null })
    }

    /** Asks the sync server for a sign-in code for this TV (approved on a signed-in device) */
    suspend fun startSignIn(): PendingSignIn = withContext(one) { core.startSignIn("${Build.MANUFACTURER} ${Build.MODEL}".trim()) }

    /** The session once the code's been approved, else null (still waiting) */
    suspend fun pollSignIn(pending: PendingSignIn): Session? = withContext(one) { core.pollSignIn(pending) }

    fun signedIn(session: Session) = scope.launch {
        prefs.edit().putString("token", session.token).putString("account", session.account).apply()
        core.useSession(session.token, session.account)
        signedIn.value = true
        checkAddons()
        syncNow()
        refreshAll()
    }

    /**
     * Sign in with Stremio (Stremio's link code, finished on a phone): this TV joins the Lumos account of that Stremio
     * account (made for it the first time), and its addons and history come across in the same step. The number of
     * titles and addons brought, or an exception (its message fit to show)
     */
    suspend fun signInWithStremio(key: String): Pair<Int, Int> {
        val session = withContext(one) { core.signInWithStremio(key, "${Build.MANUFACTURER} ${Build.MODEL}".trim()) }
        prefs.edit().putString("token", session.token).putString("account", session.account).apply()
        core.useSession(session.token, session.account)
        val got = runCatching { importStremio(key) }.getOrDefault(0 to 0)
        signedIn(session)
        return got
    }

    private fun checkAddons() { needsAddons.value = core.signedIn && addons.isEmpty() && !prefs.getBoolean("addonsSkipped", false) }

    fun skipAddons() { prefs.edit().putBoolean("addonsSkipped", true).apply(); checkAddons() }

    /**
     * Stremio's history and stream addons into Lumos, with the key a Stremio link code gave (finished on a phone), and
     * Stremio kept in step from now on. The number of titles and addons, or an exception
     */
    suspend fun importStremio(key: String): Pair<Int, Int> = withContext(one) {
        val got = core.importStremio(key)
        prefs.edit().putString("stremio", key).apply()
        val new = got.addons.filter { it !in addons && !it.contains("v3-cinemeta") }
        prefs.edit().putString("addons", JSONArray(addons + new).toString()).apply()
        configure()
        save()
        checkAddons()
        refreshAll()
        got.titles to new.size
    }

    fun signOut() = scope.launch {
        prefs.edit().remove("token").remove("account").apply()
        stateFile.delete()
        signedIn.value = false
        tell("ctx", ctx())
    }

    private suspend fun syncNow() {
        if (core.keepsStremio) prefs.getString("stremio", null)?.let {
            val pulled = runCatching { core.pullFromStremio(now(), it) }
            pulled.exceptionOrNull()?.let { e -> android.util.Log.w("lumos-sync", "Stremio's history didn't come: ${e.javaClass.simpleName}") }
            if (pulled.getOrNull() == true) android.util.Log.i("lumos-sync", "Stremio's history brought in")
        } ?: android.util.Log.i("lumos-sync", "Stremio isn't kept in step on this TV")
        runCatching { core.syncNow() }.exceptionOrNull()?.let { e -> android.util.Log.w("lumos-sync", "sync failed: ${e.javaClass.simpleName}") }
        save()
        tell("viewers", obj(core.phone.viewers()))
        tell("continue_watching_preview", obj(core.phone.continueWatching()))
        picksAnswer()
    }

    /** A title's watched mark or My List change to Stremio too, while it's kept in step (in the background) */
    private fun tellStremioMark(meta: JSONObject, watched: Boolean?, inList: Boolean?) {
        if (!core.keepsStremio) return
        scope.launch {
            val told = runCatching { core.tellStremioMark(meta.optString("id"), meta.optString("type"), meta.optString("name"), meta.text("poster"), watched, inList, now()) }.getOrDefault(false)
            android.util.Log.i("lumos-sync", "Stremio told ${meta.optString("id")} ${if (watched != null) "watched=$watched" else "in My List=$inList"}: $told")
        }
    }

    // ---- Top Picks (worked out on the sync server: sync/picks.js) ----

    private val picksMemo = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, JSONObject>>()
    private val picksAsking = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var picksFailed = 0L

    /**
     * Lumen's phone.picks: the rows kept for whoever's watching, stale while newer ones are asked for (Home asks again
     * every second and a half for a while). Settled and empty only when there's truly nothing watched: Lumen reads that
     * as a new profile and asks what they like
     */
    private fun picksAnswer(): JSONObject {
        val v = core.viewer
        val memo = picksMemo[v]
        val hasRatings = (obj(core.value("ratings:$v"))?.length() ?: 0) > 0
        val history = core.watchedIds().isNotEmpty() || core.continueWatching().isNotEmpty() || core.value("taste:$v") != null || hasRatings
        if (!history && memo == null) {
            val empty = JSONObject().put("rows", JSONArray())
            tell("picks", empty)
            return empty
        }
        val fresh = memo != null && now() - memo.first < 30 * 60_000L && !memo.second.optBoolean("partial")
        // (after the server failed, not again for five minutes)
        val resting = now() - picksFailed < 5 * 60_000L
        if (!fresh && !resting && core.signedIn && picksAsking.add(v)) scope.launch {
            try {
                val answer = JSONObject(core.picks(v))
                // (a partial answer with fewer rows doesn't replace a whole one)
                val had = memo?.second?.optJSONArray("rows")?.length() ?: 0
                val stored = if (!answer.optBoolean("partial") || (answer.optJSONArray("rows")?.length() ?: 0) >= had) answer
                else JSONObject(memo!!.second.toString()).put("partial", true)
                picksMemo[v] = now() to stored
                if (v == core.viewer) {
                    tell("picks", JSONObject().put("rows", stored.optJSONArray("rows") ?: JSONArray()).put("stale", stored.optBoolean("partial")))
                }
                android.util.Log.i("lumos-picks", "picks: ${answer.optJSONArray("rows")?.length() ?: 0} rows${if (answer.optBoolean("partial")) ", more to come" else ""}${if (answer.optBoolean("ai")) ", curated" else ""}")
            } catch (e: Exception) {
                picksFailed = now()
                android.util.Log.w("lumos-picks", "picks didn't come: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                picksAsking.remove(v)
            }
        }
        val current = JSONObject().put("rows", memo?.second?.optJSONArray("rows") ?: JSONArray()).put("stale", !fresh)
        tell("picks", current)
        return current
    }

    private fun save() {
        runCatching { stateFile.writeText(core.state()) }
    }

    private suspend fun refreshAll() {
        tell("ctx", ctx())
        tell("continue_watching_preview", obj(core.phone.continueWatching()))
        runCatching { core.phone.board() }.getOrNull()?.let { lastBoard = obj(it); tell("board", lastBoard) }
    }

    private fun ctx() = JSONObject()
        .put("signedIn", core.signedIn)
        .put("user", JSONObject().put("email", "Lumos"))
        .put("settings", JSONObject())
        .put("addons", JSONArray())

    // ---- The page's interface ----

    /** A model's state as the screens need it, kept current; null until it arrives */
    fun state(field: String): StateFlow<JSONObject?> = synchronized(states) { states.getOrPut(field) { MutableStateFlow(null) } }

    private fun tell(field: String, data: JSONObject?) {
        data ?: return
        (state(field) as MutableStateFlow).value = if (field == "meta_details") withEpisodes(data) else data
    }

    // ---- Episodes the addon knows little about (a new show: "Episode 1", the show's own picture), filled from TMDB ----

    private val episodeFill = java.util.concurrent.ConcurrentHashMap<String, Map<String, JSONObject>>()

    /** [data] with its episodes' names, pictures and words from TMDB when the addon's are thin (looked up once a title) */
    private fun withEpisodes(data: JSONObject): JSONObject {
        val meta = data.optJSONObject("meta") ?: return data
        if (meta.optString("type") != "series") return data
        val id = meta.optString("id").takeIf { Regex("""^tt\d+$""").matches(it) } ?: return data
        val videos = meta.optJSONArray("videos") ?: return data
        val list = (0 until videos.length()).mapNotNull { videos.optJSONObject(it) }.filter { it.optInt("season") > 0 }
        if (list.isEmpty()) return data
        val backdrop = meta.optString("background")
        fun thin(v: JSONObject) = v.optString("title").let { it.isBlank() || Regex("""(?i)^episode\s*\d+$""").matches(it.trim()) } ||
            v.optString("thumbnail").let { it.isBlank() || it == backdrop }
        if (list.count(::thin) * 2 < list.size) return data
        val fill = episodeFill[id]
        if (fill == null) {
            episodeFill[id] = emptyMap()
            scope.launch {
                val got = runCatching { com.notdxbi.dxbi.tv.lumen.Tmdb.episodes(id, list.map { it.optInt("season") }.toSet()) }.getOrDefault(emptyMap())
                if (got.isEmpty()) return@launch
                episodeFill[id] = got
                // (the page shows them as soon as they come, if it's still this title's)
                details?.takeIf { it.optJSONObject("meta")?.optString("id") == id }?.let { tell("meta_details", it) }
            }
            return data
        }
        if (fill.isEmpty()) return data
        val copy = JSONObject(data.toString())
        val cv = copy.optJSONObject("meta")?.optJSONArray("videos") ?: return data
        for (i in 0 until cv.length()) {
            val v = cv.optJSONObject(i) ?: continue
            val f = fill["${v.optInt("season")}:${v.optInt("episode")}"] ?: continue
            if (!thin(v)) continue
            f.optString("title").takeIf { it.isNotBlank() && it != "null" && !Regex("""(?i)^episode\s*\d+$""").matches(it) }?.let { v.put("title", it) }
            f.optString("thumbnail").takeIf { it.isNotBlank() && it != "null" }?.let { v.put("thumbnail", it) }
            if (v.optString("overview").isBlank()) f.optString("overview").takeIf { it.isNotBlank() && it != "null" }?.let { v.put("overview", it) }
        }
        return copy
    }

    /** Asks for something and waits for the answer (a JSONObject, JSONArray, string, number… or null) */
    suspend fun call(op: String, args: JSONObject = JSONObject(), timeoutMs: Long = 20_000): Any? =
        withTimeoutOrNull(timeoutMs) { withContext(one) { answer(op, args) } }

    private suspend fun answer(op: String, args: JSONObject): Any? {
        when (op) {
            "load" -> return load(args)
            "get" -> return when (args.optString("field")) {
                "continue_watching_preview" -> obj(core.phone.continueWatching())
                "board" -> lastBoard
                else -> null
            }
            "library" -> {
                val meta = details?.optJSONObject("meta") ?: return null
                core.setInMyList(meta.optString("id"), meta.optString("type"), meta.optString("name"), meta.text("poster"), args.optBoolean("add"), now())
                changed()
                tellStremioMark(meta, watched = null, inList = args.optBoolean("add"))
            }
            "watched" -> {
                val id = args.text("id") ?: details?.optJSONObject("meta")?.text("id") ?: return null
                val on = args.optBoolean("value")
                // (unwatched also puts the place back to 0: Stremio rewinds it too, so Lumen elsewhere starts it again)
                val had = if (on) null else core.continueWatching().firstOrNull { it.id == id }
                core.setWatched(id, on, now())
                changed()
                val meta = details?.optJSONObject("meta")?.takeIf { it.optString("id") == id }
                    ?: args.text("type")?.let { JSONObject().put("id", id).put("type", it).put("name", args.optString("name")).put("poster", args.text("poster") ?: JSONObject.NULL) }
                meta?.let { tellStremioMark(it, watched = on, inList = null) }
                if (had != null && core.keepsStremio) scope.launch {
                    runCatching { core.tellStremioRemoved(had.id, had.type, had.name, had.poster, now()) }
                }
            }
            "videoWatched" -> {
                // (up to here: the episode and all before it; the one marked alone otherwise)
                if (args.optBoolean("upTo") && args.optBoolean("value")) core.phone.markWatchedUpTo(args.optString("id"), now())
                else {
                    core.setWatched(args.optString("id"), args.optBoolean("value"), now())
                    if (args.optBoolean("value")) core.phone.moveOn(setOf(args.optString("id")), now())
                }
                changed()
            }
            "notInterested" -> {
                // (kept as Lumen did, "hidden:<profile>": [{id, type, name, at}], synced; the server's picks leave them out)
                val key = "hidden:${core.viewer}"
                val list = runCatching { JSONArray(core.value(key) ?: "[]") }.getOrDefault(JSONArray())
                val id = args.optString("id")
                if ((0 until list.length()).none { list.optJSONObject(it)?.optString("id") == id }) {
                    list.put(JSONObject().put("id", id).put("type", args.optString("type")).put("name", args.optString("name")).put("at", now()))
                    while (list.length() > 300) list.remove(0)
                    core.setValue(key, list.toString(), now())
                }
                // (the picks kept here lose it now; the server's are made again with it left out)
                picksMemo[core.viewer]?.let { (at, a) ->
                    val rows = a.optJSONArray("rows") ?: JSONArray()
                    for (i in 0 until rows.length()) rows.optJSONObject(i)?.optJSONArray("items")?.let { items ->
                        for (j in items.length() - 1 downTo 0) if (items.optJSONObject(j)?.optString("id") == id) items.remove(j)
                    }
                    picksMemo[core.viewer] = at to a
                    tell("picks", JSONObject().put("rows", a.optJSONArray("rows") ?: JSONArray()).put("stale", a.optBoolean("partial")))
                }
                changed()
            }
            "dismiss" -> {
                // (Stremio's own Remove rewinds the title: done there too, so Lumen elsewhere drops it as well)
                val gone = core.continueWatching().firstOrNull { it.id == args.optString("id") }
                if (gone != null && core.keepsStremio) scope.launch {
                    val told = runCatching { core.tellStremioRemoved(gone.id, gone.type, gone.name, gone.poster, now()) }.getOrDefault(false)
                    android.util.Log.i("lumos-sync", "Stremio told ${gone.id} left Continue Watching: $told")
                }
                core.forget(args.optString("id"), now())
                changed()
            }
            "fromStart" -> fromStart = if (args.optBoolean("on")) args.text("videoId") else null
            "play" -> play(args.optString("link"))
            "dx" -> return dx(args.optString("method"), args.optJSONArray("args") ?: JSONArray())
            "signOut" -> signOut()
            "settings" -> return LumosSettings.list(prefs, runCatching { core.phone.addonNames() }.getOrDefault(emptyList()))
            "saveSetting" -> {
                LumosSettings.save(prefs, args.optString("key"), args.opt("value").takeUnless { it == JSONObject.NULL })
                applySettings()
            }
            // (Stremio's own: nothing to do without it)
            else -> {}
        }
        return null
    }

    private suspend fun load(args: JSONObject): Any? {
        val a = args.optJSONObject("args") ?: JSONObject()
        when (args.optString("field")) {
            "board" -> {
                tell("ctx", ctx())
                tell("continue_watching_preview", obj(core.phone.continueWatching()))
                // (the board can take a few seconds: Stremio.kt goes on meanwhile)
                scope.launch { runCatching { core.phone.board() }.getOrNull()?.let { lastBoard = obj(it); tell("board", lastBoard) } }
            }
            "meta_details" -> {
                val meta = a.optJSONObject("metaPath") ?: return null
                val type = meta.text("type") ?: return null
                val id = meta.text("id") ?: return null
                // The streams are asked for at once, with the details, not after them (they don't need them): a film's
                // from its id, a show's episode from where it was left; for a new show, the episode as soon as the details say
                core.phone.prepare(type, id)
                var video = a.optJSONObject("streamPath")?.text("id") ?: core.phone.guessVideo(type, id)
                if (video != null) {
                    core.phone.loadStreams(type, video, a.optBoolean("again"))
                }
                details = obj(runCatching { core.phone.open(type, id) }.getOrDefault("{}"))
                tell("meta_details", details)
                if (video == null) {
                    video = details?.optString("next")?.takeIf { it.isNotEmpty() }
                    if (video != null) {
                        core.phone.loadStreams(type, video, a.optBoolean("again"))
                    }
                }
            }
            "search" -> {
                val extra = a.optJSONArray("extra") ?: JSONArray()
                var query = ""
                for (i in 0 until extra.length()) extra.optJSONArray(i)?.let { if (it.optString(0) == "search") query = it.optString(1) }
                scope.launch { runCatching { core.phone.search(query) }.getOrNull()?.let { tell("search", obj(it)) } }
            }
            "library" -> tell("library", JSONObject().put("items", myListTitles()))
            "installed_addons" -> tell("installed_addons", JSONObject().put("items", JSONArray(addons.map {
                JSONObject().put("transportUrl", it).put("id", it).put("name", android.net.Uri.parse(it).host ?: it).put("installed", true)
            })))
        }
        return null
    }

    /** The stream list read once for each answer: the engine hands back the same string while nothing has changed */
    private var lastStreams: Pair<String, JSONObject?>? = null
    private fun streamsAnswer(raw: String): JSONObject? {
        lastStreams?.let { (text, parsed) -> if (text === raw) return parsed }
        return obj(raw).also { lastStreams = raw to it }
    }

    /** Coalesced push update when any addon answers or all streams settle */
    private fun onStreamsChanged() {
        if (!streamsPending.compareAndSet(false, true)) return
        scope.launch {
            delay(50)
            streamsPending.set(false)
            details = obj(core.phone.details())
            tell("meta_details", details)
            val groups = details?.optJSONArray("streams") ?: JSONArray()
            if (groups.length() > 0 && (0 until groups.length()).all { groups.optJSONObject(it)?.optString("state") != "Loading" }) {
                val timingsLine = core.phone.timings.joinToString("; ")
                if (timingsLine.isNotEmpty() && timingsLine != lastLoggedTimings) {
                    lastLoggedTimings = timingsLine
                    android.util.Log.i("lumos-streams", timingsLine)
                }
            }
            com.notdxbi.dxbi.tv.PlaybackBus.notifyStreamsChanged()
        }
    }

    private suspend fun dx(method: String, args: JSONArray): Any? {
        fun num(i: Int) = args.opt(i).let { (it as? Number)?.toDouble() ?: it?.toString()?.toDoubleOrNull() }
        return when (method) {
            "phone.viewers" -> obj(core.phone.viewers())
            "viewers.select" -> {
                core.chooseProfile(args.optString(0))
                save()
                tell("viewers", obj(core.phone.viewers()))
                tell("continue_watching_preview", obj(core.phone.continueWatching()))
                picksAnswer()
                // (Home's cards carry My List and watched marks: made again for this profile)
                scope.launch { runCatching { core.phone.board() }.getOrNull()?.let { lastBoard = obj(it); tell("board", lastBoard) } }
                null
            }
            "phone.marks" -> JSONObject().put("library", JSONArray(myListTitles().let { l -> (0 until l.length()).map { l.optJSONObject(it).optString("id") } }))
                .put("watched", JSONArray(core.watchedIds().toList()))
            "phone.myList" -> myListTitles().let { if (it.length() == 0) JSONArray() else JSONArray().put(JSONObject().put("name", "My List").put("items", it)) }
            "viewers.add" -> { core.addProfile(args.optString(0), args.optString(1), now()); tell("viewers", obj(core.phone.viewers())); changed(); null }
            "viewers.rename" -> { core.renameProfile(args.optString(0), args.optString(1), now()); tell("viewers", obj(core.phone.viewers())); changed(); null }
            "viewers.recolour" -> { core.recolourProfile(args.optString(0), args.optString(1), now()); tell("viewers", obj(core.phone.viewers())); changed(); null }
            "viewers.remove" -> { core.removeProfile(args.optString(0), now()); tell("viewers", obj(core.phone.viewers())); changed(); null }
            "phone.taste" -> obj(core.value("taste:" + args.optString(0)))
            "phone.setTaste" -> { args.optJSONObject(1)?.let { core.setValue("taste:" + args.optString(0), it.toString(), now()); changed() }; null }
            "phone.layout" -> obj(core.value("homeLayout:" + core.viewer))
            "homeLayout.save" -> { args.optJSONObject(0)?.let { core.setValue("homeLayout:" + core.viewer, it.toString(), now()); changed() }; null }
            "phone.picks" -> picksAnswer()
            "phone.aiSearch" -> {
                val q = args.optString(0).trim()
                if (q.length < 3) return JSONArray()
                if (!core.signedIn) return null
                runCatching { JSONObject(core.ask(core.viewer, q)).optJSONArray("titles") }.getOrNull()
            }
            "phone.billboard" -> {
                // (Lumen's billboard came from its picks: until they're ported, the first row's titles with art)
                val rows = lastBoard?.optJSONArray("catalogs") ?: JSONArray()
                val first = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.firstOrNull { (it.optJSONArray("items")?.length() ?: 0) > 0 }
                val items = first?.optJSONArray("items") ?: JSONArray()
                // (My List and watched marks for whoever's watching now: the board may have been made for another profile)
                JSONArray((0 until minOf(8, items.length())).mapNotNull { items.optJSONObject(it) }.map { t ->
                    JSONObject(t.toString()).put("inLibrary", core.inMyList(t.optString("id"))).put("watched", t.optString("id") in core.watchedIds())
                })
            }
            "phone.airing", "phone.related", "phone.animeSeasons", "phone.calendar" -> JSONArray()
            "phone.networks" -> JSONArray(runCatching { core.phone.networks() }.getOrDefault("[]"))
            "phone.networkSample" -> { num(0)?.let { core.phone.networkSample(it) }; true }
            "phone.setNetwork" -> {
                val c = args.optJSONObject(1) ?: JSONObject()
                core.phone.setNetwork(args.optString(0), if (c.has("name")) c.optString("name") else null, if (c.has("cap")) c.optInt("cap") else -1, c.optBoolean("relearn"))
                save(); true
            }
            "phone.forgetNetwork" -> { core.phone.forgetNetwork(args.optString(0)); save(); true }
            "phone.describe" -> JSONObject()
            "phone.ratings" -> obj(core.value("ratings:${core.viewer}")) ?: JSONObject()
            "phone.rating" -> {
                val id = args.optString(0)
                obj(core.value("ratings:${core.viewer}"))?.optInt(id, 0)?.takeIf { it in 1..10 }
            }
            "phone.setRating" -> {
                val id = args.optString(0)
                val score = num(1)?.toInt() ?: 0
                if (id.isNotEmpty()) {
                    val map = obj(core.value("ratings:${core.viewer}")) ?: JSONObject()
                    if (score in 1..10) map.put(id, score) else map.remove(id)
                    core.setValue("ratings:${core.viewer}", map.toString(), now())
                    picksMemo.remove(core.viewer)
                    picksAnswer()
                    changed()
                }
                null
            }
            "phone.streams" -> streamsAnswer(core.phone.streams())?.also { c ->
                if (c.optBoolean("settled")) {
                    val p = c.optJSONArray("items")?.optJSONObject(c.optInt("pick", -1))
                    val line = "pick ${p?.optString("label")} ${p?.optDouble("mbps")} Mbps from ${p?.optString("addon")} (${c.optString("how")}), speed ${c.optDouble("speed")} Mbps, lowered ${c.optJSONObject("lowered")}"
                    if (line != lastPickLog) { lastPickLog = line; android.util.Log.i("lumos-streams", line) }
                }
            }
            "phone.play" -> num(0)?.toInt()?.let { link(it) }
            "phone.source" -> num(0)?.toInt()?.let { i -> runCatching { core.phone.source(i) }.getOrNull()?.let(::obj) }
            "phone.alternatives" -> obj(core.phone.alternatives(num(0) ?: 0.0))
            "phone.lighter" -> JSONArray(core.phone.lighter(num(0) ?: 0.0, num(1) ?: 0.0))
            "phone.previewSource" -> core.phone.previewSource(args.optString(0))?.let(::obj)
            "phone.versions" -> runCatching { JSONArray(core.phone.versions()) }.getOrDefault(JSONArray())
            "phone.chooseVersion" -> { core.phone.chooseVersion(args.optString(0).ifEmpty { null }); true }
            "phone.probe" -> num(0)?.toInt()?.let { core.phone.probe(it) }?.let(::obj)
            "phone.reject" -> num(0)?.toInt()?.let { core.phone.reject(it) }
            "skipSegments" -> JSONArray(runCatching { core.phone.skipSegments(args.optString(0), args.optString(1), args.optString(2)) }.getOrDefault("[]"))
            else -> null
        }
    }

    // ---- Playing ----

    /** A link that carries the stream itself (Lumen's PlayedLinks keeps it to play the same stream again) */
    private suspend fun link(index: Int): String? {
        val source = runCatching { core.phone.source(index) }.getOrNull()?.let(::obj) ?: return null
        val meta = details?.optJSONObject("meta")
        source.put("videoId", details?.optJSONObject("selected")?.optJSONObject("streamPath")?.text("id") ?: meta?.text("id") ?: "")
        source.put("metaId", meta?.text("id") ?: "")
        source.put("type", meta?.text("type") ?: "movie")
        source.put("name", meta?.text("name") ?: "")
        source.put("poster", meta?.text("poster") ?: JSONObject.NULL)
        source.put("background", meta?.text("background") ?: JSONObject.NULL)
        return "lumos:" + android.util.Base64.encodeToString(source.toString().toByteArray(), android.util.Base64.NO_WRAP)
    }

    /** Where the player goes: MainActivity starts PlayerActivity with it */
    @Volatile
    var onPlay: ((String) -> Unit)? = null

    private suspend fun play(link: String) {
        if (!link.startsWith("lumos:")) return
        val source = runCatching { JSONObject(String(android.util.Base64.decode(link.removePrefix("lumos:"), android.util.Base64.NO_WRAP))) }.getOrNull() ?: return
        val video = source.optString("videoId")
        val metaId = source.optString("metaId")
        val type = source.optString("type")
        val meta = details?.optJSONObject("meta")?.takeIf { it.optString("id") == metaId }
        val videos = meta?.optJSONArray("videos")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
        val now = videos.indexOfFirst { it.optString("id") == video }
        val episode = videos.getOrNull(now)
        // (the next episode that's out: one airing later isn't offered)
        val next = if (now >= 0) videos.drop(now + 1).firstOrNull { it.optInt("season") > 0 && released(it) } else null
        var start = 0.0
        if (fromStart != video) core.resumeOf(metaId)?.takeIf { it.video == video }?.let { start = it.position * 1000 }
        fromStart = null
        val payload = JSONObject()
            .put("url", source.optString("url"))
            .put("headers", source.optJSONObject("headers") ?: JSONObject())
            .put("title", source.optString("name"))
            .put("videoId", video)
            // (the player reads which title, episode and stream from this address, as Stremio's)
            .put("hash", source.text("play") ?: metaId)
            .put("startMs", start.toLong())
            .put("torrent", false)
            .put("clock", LumosSettings.on(prefs, "playerClock"))
            .put("autoSkip", LumosSettings.text(prefs, "autoSkip"))
            .put("upNext", LumosSettings.text(prefs, "upNext"))
            .put("audioLanguage", "")
            // (Lumen TV starts subtitles off; "On, in English" starts them)
            .put("subtitleLanguage", LumosSettings.text(prefs, "subtitlesStart").takeIf { it != "off" } ?: "")
            .put("subtitlesOn", LumosSettings.text(prefs, "subtitlesStart") != "off")
            .put("subtitleStyle", LumosSettings.subtitleStyle(prefs))
            .put("background", source.text("background") ?: source.text("poster") ?: "")
            .put("poster", source.text("poster") ?: "")
        episode?.let { e ->
            payload.put("subtitle", episodeLabel(e.optInt("season"), e.optInt("episode"), e.optString("title"), sep = " "))
        }
        next?.let { n ->
            payload.put("next", JSONObject().put("id", n.optString("id")).put("thumbnail", n.optString("thumbnail"))
                .put("title", episodeLabel(n.optInt("season"), n.optInt("episode"), n.optString("title"), sep = " · ")))
        }
        // Skip Intro and the credits (1.5 s at most), and subtitles (2.5 s), fetched side by side
        val skips = scope.async(Dispatchers.Default) { withTimeoutOrNull(1500) { runCatching { core.phone.skipSegments(type, metaId, video) }.getOrNull() } ?: "[]" }
        val subtitles = scope.async(Dispatchers.Default) { withTimeoutOrNull(2500) { runCatching { core.phone.subtitles(type, video, SUBTITLES) }.getOrNull() } ?: "[]" }
        payload.put("skips", if (LumosSettings.on(prefs, "skipButtons")) JSONArray(skips.await()) else JSONArray())
        payload.put("subtitles", JSONArray(subtitles.await()))
        if (LumosSettings.on(prefs, "seekPreviews")) core.phone.previewSource(video)?.let { payload.put("preview", obj(it)) }
        playing = JSONObject().put("metaId", metaId).put("type", type).put("name", source.optString("name"))
            .put("poster", source.text("poster") ?: JSONObject.NULL).put("videoId", video)
        // (a new video: its first save in 15 s, its first word to Stremio in 3 minutes)
        now().let { savedAt = it; syncedAt = it; stremioAt = it }
        wasPaused = false
        val json = payload.toString()
        withContext(Dispatchers.Main) { onPlay?.invoke(json) }
    }

    private fun released(video: JSONObject): Boolean {
        val r = video.text("released") ?: return true
        // (Cinemeta's dates: 2026-11-18T00:00:00.000Z; the day is enough. java.time isn't on Android 7)
        val at = Dates.parseUtcDay(r.take(10)) ?: return true
        return at <= now()
    }

    // When the place was last written to the TV, synced, and told to Stremio, while playing
    private var savedAt = 0L
    private var syncedAt = 0L
    private var stremioAt = 0L
    private var wasPaused = false

    /**
     * The player's place, every five seconds. Kept as it goes, not only when the player closes, so an app closed by
     * accident (or a crash, or the TV switched off) loses at most a few seconds: written to the TV every 15 s, synced to
     * the server every minute, told to Stremio every 3 minutes, and all three at once as soon as it's paused (leaving the
     * app pauses it too)
     */
    fun progress(positionMs: Long, durationMs: Long, paused: Boolean = false) = scope.launch {
        keep(positionMs / 1000.0, durationMs / 1000.0)
        val p = playing ?: return@launch
        val now = now()
        val justPaused = paused && !wasPaused
        wasPaused = paused
        // (the full state once a minute: the place has its own small file every report)
        if (justPaused || now - savedAt > 60_000) { savedAt = now; save() }
        if (justPaused || now - syncedAt > 60_000) {
            syncedAt = now
            runCatching { core.syncNow() }
            save()
        }
        val position = positionMs / 1000.0
        val duration = durationMs / 1000.0
        if (core.keepsStremio && duration > 0 && position > 5 && (justPaused || now - stremioAt > 3 * 60_000)) {
            stremioAt = now
            val told = runCatching { core.tellStremio(p.optString("metaId"), p.optString("type"), p.optString("videoId"), p.optString("name"), p.text("poster"), position, duration, now) }.getOrDefault(false)
            android.util.Log.i("lumos-sync", "Stremio told where ${p.optString("videoId")} is (playing): $told")
        }
    }

    private fun keep(position: Double, duration: Double) {
        val p = playing ?: return
        if (duration <= 0 || position <= 5) return
        val at = now()
        core.saveProgress(p.optString("metaId"), p.optString("type"), p.optString("videoId"), p.optString("name"), p.text("poster"), position, duration, at)
        // (the place alone, to its own small file: written whole to a spare then swapped in, so a cut mid-write leaves the last one)
        runCatching {
            val tmp = File(placeFile.path + ".tmp")
            tmp.writeText(JSONObject(p.toString()).put("viewer", core.viewer).put("position", position).put("duration", duration).put("at", at).toString())
            tmp.renameTo(placeFile)
        }
    }

    /**
     * After a start: the player's last place from its small file, when it's newer than what the full state kept (the app
     * was closed, or crashed, between full saves), put back for the profile it was, and synced on as usual
     */
    private fun takeBackPlace() {
        val o = runCatching { JSONObject(placeFile.readText()) }.getOrNull() ?: return
        val id = o.optString("metaId").takeIf { it.isNotEmpty() } ?: return
        val viewer = o.optString("viewer").ifEmpty { core.viewer }
        val was = core.viewer
        core.viewer = viewer
        val at = o.optLong("at")
        if ((core.placeAt(id) ?: 0L) < at) {
            core.saveProgress(id, o.optString("type"), o.optString("videoId"), o.optString("name"), o.text("poster"), o.optDouble("position"), o.optDouble("duration"), at)
            android.util.Log.i("lumos-sync", "place put back: ${o.optString("videoId")} at ${o.optDouble("position").toInt()} s")
            save()
        }
        core.viewer = was
        placeFile.delete()
    }

    /** The player closed: its place is kept, synced, and told to Stremio (while Lumen's in use) */
    fun closed(positionMs: Long, durationMs: Long) = scope.launch {
        val p = playing ?: return@launch
        playing = null
        val position = positionMs / 1000.0
        val duration = durationMs / 1000.0
        keep(position, duration)
        // (an episode finished: on to the next one, as Up Next)
        if (p.optString("type") == "series" && duration > 0 && position / duration >= Lumos.FINISHED) runCatching { core.phone.moveOn(setOf(p.optString("videoId")), now()) }
        save()
        tell("continue_watching_preview", obj(core.phone.continueWatching()))
        details?.optJSONObject("meta")?.let { m -> m.text("type")?.let { t -> details = obj(core.phone.details()); tell("meta_details", details) } }
        syncNow()
        if (core.keepsStremio && duration > 0 && position > 5) {
            val told = runCatching { core.tellStremio(p.optString("metaId"), p.optString("type"), p.optString("videoId"), p.optString("name"), p.text("poster"), position, duration, now()) }.getOrDefault(false)
            android.util.Log.i("lumos-sync", "Stremio told where ${p.optString("videoId")} got to: $told")
        }
    }

    // ----

    private fun myListTitles(): JSONArray = obj(core.phone.myList())?.optJSONArray("items") ?: JSONArray()

    private suspend fun changed() {
        save()
        details?.optJSONObject("meta")?.let { m ->
            val type = m.text("type") ?: return@let
            details = obj(runCatching { core.phone.open(type, m.optString("id")) }.getOrDefault("{}"))
            tell("meta_details", details)
        }
        tell("continue_watching_preview", obj(core.phone.continueWatching()))
        scope.launch { runCatching { core.syncNow() }; save() }
    }

    private fun obj(text: String?): JSONObject? = text?.let { runCatching { JSONTokener(it).nextValue() as? JSONObject }.getOrNull() }

    // ---- Live TV sync helpers ----

    val syncServerUrl: String get() = SYNC

    /** Syncs IPTV sources and preferences under [one] and persists engine state to disk. */
    suspend fun syncIptvNow(): Boolean = withContext(one) {
        val synced = runCatching { core.syncNow() }.getOrDefault(false)
        save()
        synced
    }

    /** Executes [block] against [core] serialized on [one] to protect mutable state maps. */
    suspend fun <T> withIptvCore(block: suspend (Lumos) -> T): T = withContext(one) {
        block(core)
    }

    /** Fetches live and upcoming UK/US sports fixtures with UK TV broadcaster cross-matches. */
    suspend fun sportsFixtures(
        sport: String? = null,
        date: String? = null,
    ): List<lumos.iptv.SportsFixture> = withContext(Dispatchers.IO) {
        core.sportsFixtures(sport, date)
    }

    /** One match's page (score, stats, goals and cards, line-ups) as JSON; null when it can't be had */
    suspend fun sportsMatch(league: String, eventId: String): org.json.JSONObject? = withContext(Dispatchers.IO) {
        runCatching { org.json.JSONObject(core.sportsMatch(league, eventId)).optJSONObject("match") }.getOrNull()
    }

    /** Formula 1's standings as JSON ({season, drivers, constructors}); null when it can't be had */
    suspend fun f1Standings(): org.json.JSONObject? = withContext(Dispatchers.IO) {
        runCatching { org.json.JSONObject(core.f1Standings()) }.getOrNull()
    }

    /** Searches UK Football and US pro sports team directories on the sync server. */
    suspend fun searchSportsTeams(query: String): List<lumos.iptv.SavedSportsTeam> = withContext(Dispatchers.IO) {
        core.searchSportsTeams(query)
    }

    /** Fetches live, recent past, and upcoming schedules for saved [teams]. */
    suspend fun savedTeamsSchedule(
        teams: List<lumos.iptv.SavedSportsTeam>,
    ): List<lumos.iptv.TeamScheduleGroup> = withContext(Dispatchers.IO) {
        core.savedTeamsSchedule(teams)
    }

    /** Enriches a TV guide programme with TMDB 16:9 backdrop and description when missing. */
    suspend fun enrichEpgProgramme(
        title: String,
        channelId: String? = null,
        category: String? = null,
        description: String? = null,
        imdb: Boolean = false,
    ): lumos.iptv.EpgEnrichment = withContext(Dispatchers.IO) {
        core.enrichEpgProgramme(title, channelId, category, description, imdb)
    }

    /** Reports or checks concurrent IPTV viewer presence for [sourceId]. */
    suspend fun iptvPresence(
        action: String,
        sourceId: String,
        channelName: String? = null,
    ): String? = withContext(Dispatchers.IO) {
        core.iptvPresence(action, sourceId, channelName)
    }

    companion object {
        const val SYNC = "https://lumos-sync.hedigital.workers.dev"
        const val CINEMETA = "https://v3-cinemeta.strem.io/manifest.json"
        /** The subtitle language a video starts in (Lumen took Stremio's, English); it shows only when the audio isn't in it */
        const val SUBTITLES = "eng"
        fun now() = System.currentTimeMillis()
    }
}
