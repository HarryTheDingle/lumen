package lumos.engine

import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Lumen's phone API (what dxbi-engine.js and the plugin answered Lumen's iOS screens with), answered by the
 * Lumos engine instead: the same JSON shapes, so Lumen's Models.swift (and later the TV's Models.kt) read
 * them unchanged. Everything here answers JSON text.
 */
class Phone(private val lumos: Lumos) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()

    // ---- Home ----

    private var lists: List<JsonObject> = emptyList()
    private var listsAt = 0L

    /** The sync server's Home lists, asked again every 10 minutes (they're remade every 15); the last ones when it can't answer */
    private suspend fun homeLists(): List<JsonObject> {
        val now = lumos.clock()
        if (lists.isNotEmpty() && now - listsAt < 10 * 60_000) return lists
        if (!lumos.signedIn) return lists
        withTimeoutOrNull(8000) { runCatching { lumos.sync.lists() }.getOrNull() }?.takeIf { it.isNotEmpty() }?.let { lists = it; listsAt = now }
        return lists
    }

    /**
     * Home's rows: Lumos's own lists, as the sync server makes them (in its order, "lumos:<id>" keys, the first numbered),
     * so Home no longer depends on what catalogs the addons have. Only when there are none (signed out, or the server
     * can't be reached the first time), the addons' catalogs, as Stremio's board had them.
     */
    @Throws(Exception::class)
    suspend fun board(): String {
        val own = homeLists()
        if (own.isNotEmpty()) return buildJsonObject {
            putJsonArray("catalogs") {
                own.forEachIndexed { i, l ->
                    add(buildJsonObject {
                        put("index", i)
                        put("key", "lumos:${l.str("id")}")
                        put("name", l.str("name") ?: "")
                        put("type", "")
                        put("addon", "Lumos")
                        put("numbered", l.bool("numbered") == true)
                        l.str("service")?.let { put("service", it) }
                        put("future", l.bool("future") == true)
                        put("state", "Ready")
                        putJsonArray("items") {
                            l["items"].arr().mapNotNull { it.obj() }.forEach { t ->
                                val id = t.str("id") ?: return@forEach
                                add(buildJsonObject {
                                    put("id", id); put("type", t.str("type") ?: "movie"); put("name", t.str("name") ?: "")
                                    t.str("poster")?.let { put("poster", it) }; t.str("background")?.let { put("background", it) }; t.str("logo")?.let { put("logo", it) }
                                    t.str("year")?.let { put("year", it) }; t.str("description")?.let { put("description", it) }
                                    put("genres", JsonArray(t.strings("genres").map(::JsonPrimitive)))
                                    put("inLibrary", lumos.inMyList(id))
                                    put("watched", id in lumos.watchedIds())
                                })
                            }
                        }
                    })
                }
            }
        }.toString()
        val rows = lumos.engine.homeCatalogs()
        val answers: List<List<MetaPreview>?> = coroutineScope {
            rows.map { (a, c) -> async { withTimeoutOrNull(Engine.CATALOG_MS) { runCatching { a.catalog(c.type, c.id) }.getOrNull() } } }.awaitAll()
        }
        val names = rows.map { (a, _) -> a.name() }
        // (rows keyed as Lumen keyed them, addon id/type/catalog, so the Home order and hiding synced from Lumen apply)
        val ids = rows.map { (a, _) -> runCatching { a.manifest().id }.getOrDefault(a.base) }
        return buildJsonObject {
            putJsonArray("catalogs") {
                rows.forEachIndexed { i, (a, c) ->
                    val items = answers[i]
                    add(buildJsonObject {
                        put("index", i)
                        put("key", "${ids[i]}/${c.type}/${c.id}")
                        put("name", c.name)
                        put("type", c.type)
                        put("addon", names[i])
                        put("discover", "#/discover/${enc(a.base + "/manifest.json")}/${c.type}/${enc(c.id)}")
                        put("state", if (items == null) "Err" else "Ready")
                        putJsonArray("items") { items.orEmpty().take(30).forEach { add(title(it)) } }
                    })
                }
            }
        }.toString()
    }

    /** Continue Watching (Stremio's continue_watching_preview): this profile's resume points, latest first */
    fun continueWatching(): String = buildJsonObject {
        putJsonArray("items") {
            for (r in lumos.continueWatching()) add(buildJsonObject {
                put("id", r.id); put("type", r.type); put("name", r.name); r.poster?.let { put("poster", it) }
                put("progress", r.fraction * 100); put("videoId", r.video); put("notifications", 0)
            })
        }
    }.toString()

    /** Search: every catalog that searches, as rows */
    @Throws(Exception::class)
    suspend fun search(query: String): String {
        val found = mutableListOf<JsonObject>()
        var i = 0
        for ((a, m) in lumos.engine.manifests()) for (c in m.catalogs) if ("search" in c.extras) {
            val items = withTimeoutOrNull(Engine.CATALOG_MS) { runCatching { a.catalog(c.type, c.id, mapOf("search" to query)) }.getOrNull() }
            found += buildJsonObject {
                put("index", i++); put("key", "${a.base}|${c.type}|${c.id}|search"); put("name", c.name); put("type", c.type)
                put("addon", m.name); put("state", if (items == null) "Err" else "Ready")
                putJsonArray("items") { items.orEmpty().forEach { add(title(it)) } }
            }
        }
        return buildJsonObject { put("catalogs", JsonArray(found)) }.toString()
    }

    // ---- A title (Stremio's meta_details) ----

    private var openMeta: Meta? = null
    private var openType = ""
    private var openId = ""

    /** The title's details, with where it was left (resume) and the episode Play starts (next) */
    @Throws(Exception::class)
    suspend fun open(type: String, id: String): String {
        prepare(type, id)
        if (openMeta == null) openMeta = cachedMeta(type, id)
        return details()
    }

    /**
     * Makes [id] the open title before its details have come (the last one's streams go with it, or its list was offered
     * for this one), so its streams can be asked for at once: they don't need the details
     */
    fun prepare(type: String, id: String) {
        if (openId == id) return
        clearStreams()
        openMeta = null
        openType = type
        openId = id
    }

    // ---- Kept a little while: a title opened again (or warmed by Home: Launcher.warm) opens with its streams there ----

    private val metas = HashMap<String, Pair<Long, Meta?>>()
    private val warmed = HashMap<String, Pair<Long, List<Stream>>>()

    /** A title's details, kept ten minutes (opened again, or warmed ahead) */
    private suspend fun cachedMeta(type: String, id: String): Meta? {
        val now = lumos.clock()
        lock.withLock { metas[id]?.takeIf { now - it.first < 10 * 60_000 && it.second != null }?.let { return it.second } }
        val m = lumos.engine.meta(type, id)
        lock.withLock { metas[id] = now to m; if (metas.size > 60) metas.keys.take(20).forEach(metas::remove) }
        return m
    }

    /**
     * The episode Play would start, without the show's details when it can: the one part-way through or up next, else
     * (given the details) the one after the last watched, else the first. A film: itself
     */
    fun guessVideo(type: String, id: String, meta: Meta? = null): String? {
        if (type != "series") return id
        lumos.continueWatching().firstOrNull { it.id == id }?.video?.takeIf { it.startsWith("$id:") }?.let { return it }
        // (nothing of it watched yet: its first episode, before the details have said so)
        if (meta == null && lumos.watchedIds().none { it.startsWith("$id:") }) return "$id:1:1"
        meta ?: return null
        val watched = lumos.watchedIds()
        val eps = meta.videos.filter { (it.season ?: 0) > 0 }.sortedWith(compareBy({ it.season }, { it.episode }))
        val last = eps.indexOfLast { it.id in watched }
        return (if (last >= 0) eps.getOrNull(last + 1) else null)?.id ?: eps.firstOrNull()?.id
    }


    /** The open title's details now (streams as they've answered so far) */
    fun details(): String {
        val meta = openMeta
        val resume = lumos.resumeOf(openId)
        val watched = lumos.watchedIds()
        return buildJsonObject {
            put("state", if (meta == null) "Err" else "Ready")
            putJsonObject("selected") {
                putJsonObject("metaPath") { put("id", openId) }
                streamsVideo?.let { v -> putJsonObject("streamPath") { put("id", v) } }
            }
            if (meta != null) put("meta", buildJsonObject {
                titleFields(meta.preview)
                meta.runtime?.let { put("runtime", it) }
                put("cast", JsonArray(meta.cast.map(::JsonPrimitive)))
                put("directors", JsonArray(meta.director.map(::JsonPrimitive)))
                meta.trailers.firstOrNull()?.let { put("trailer", it) }
                put("inLibrary", lumos.inMyList(openId))
                put("watched", openId in watched)
                putJsonArray("videos") {
                    for (v in meta.videos) add(buildJsonObject {
                        put("id", v.id); put("title", v.title ?: ""); put("season", v.season); put("episode", v.episode)
                        v.thumbnail?.let { put("thumbnail", it) }; v.released?.let { put("released", it) }; v.overview?.let { put("overview", it) }
                        put("watched", v.id in watched)
                        put("progress", if (resume?.video == v.id) JsonPrimitive(resume.fraction * 100) else JsonNull)
                        put("upcoming", false)
                    })
                }
            })
            resume?.let { r -> putJsonObject("resume") { put("videoId", r.video); put("timeOffset", r.position * 1000); put("duration", r.duration * 1000) } }
            nextVideo(meta, resume, watched)?.let { put("next", it) }
            putJsonArray("streams") {
                for ((name, state) in addonStates) add(buildJsonObject { put("addon", name); put("state", state); putJsonArray("items") {} })
            }
        }.toString()
    }

    /** An episode of the open series and every one before it watched: earlier ones of its season, and all of earlier seasons (specials, season 0, left alone) */
    fun markWatchedUpTo(videoId: String, now: Long) {
        val meta = openMeta ?: return
        val at = meta.videos.firstOrNull { it.id == videoId } ?: return
        val season = at.season ?: return
        val episode = at.episode ?: return
        if (season <= 0) return
        val ids = meta.videos.filter { v ->
            val s = v.season ?: return@filter false
            val e = v.episode ?: return@filter false
            s > 0 && (s < season || (s == season && e <= episode))
        }.map { it.id }
        lumos.setWatchedAll(ids, true, now)
        moveOn(ids.toSet(), now)
    }

    /**
     * The open show's place moves on when its episode is finished or marked watched: to the next episode not watched yet
     * ("Up Next", as Lumen and Stremio keep it), or out of Continue Watching when there's none. Nothing when the place is
     * on another episode
     */
    fun moveOn(videos: Set<String>, now: Long) {
        val meta = openMeta ?: return
        if (openType != "series") return
        val place = lumos.placeVideo(openId) ?: return
        if (place !in videos) return
        val watched = lumos.watchedIds()
        val eps = meta.videos.filter { (it.season ?: 0) > 0 }.sortedWith(compareBy({ it.season }, { it.episode }))
        val next = eps.dropWhile { it.id != place }.drop(1).firstOrNull { it.id !in watched && it.id !in videos }
        if (next == null) lumos.forget(openId, now)
        else lumos.setUpNext(openId, next.id, meta.preview.name, meta.preview.poster, now)
    }

    /** The episode Play starts: the one left part-way, else the one after the last watched, else the first */
    private fun nextVideo(meta: Meta?, resume: Resume?, watched: Set<String>): String? {
        meta ?: return null
        if (openType != "series") return openId
        if (resume != null && resume.video.startsWith("$openId:")) return resume.video
        // (a show moved on to its next episode, here or in Lumen/Stremio: that one)
        lumos.continueWatching().firstOrNull { it.id == openId }?.video?.takeIf { it.startsWith("$openId:") }?.let { return it }
        val eps = meta.videos.filter { (it.season ?: 0) > 0 }.sortedWith(compareBy({ it.season }, { it.episode }))
        val last = eps.indexOfLast { it.id in watched }
        return (if (last >= 0) eps.getOrNull(last + 1) else null)?.id ?: eps.firstOrNull()?.id
    }

    // ---- Streams for one video, as they come ----

    /** Goes up each time the open video's streams change (an addon answered, all have): the page is rebuilt only then */
    var streamsChanged = 0
        private set

    private var streamsVideo: String? = null
    private var streamsJob: Job? = null
    private var found = listOf<Stream>()
    private var addonStates = listOf<Pair<String, String>>()
    private var settled = false

    /** The speed the pick fits streams to (0: not matched) and this network's own quality cap (0: none) */
    private var fitSpeed = 0.0
    private var networkCap = 0

    /** How long each addon took for the last video asked, in words (for the app's log: names only, never links) */
    var timings = listOf<String>()
        private set

    private fun clearStreams() {
        streamsJob?.cancel()
        streamsJob = null
        streamsVideo = null
        found = emptyList()
        shown = emptyList()
        playing = null
        addonStates = emptyList()
        settled = false
    }

    /** Starts asking every addon for [videoId]'s streams (a film's is its own id); answers come in over time */
    /** [again]: ask every addon afresh even for the video already open (Look Again, on a title with nothing to play) */
    fun loadStreams(type: String, videoId: String, again: Boolean = false) {
        if (!again && streamsVideo == videoId && streamsJob != null) return
        streamsJob?.cancel()
        streamsVideo = videoId
        found = emptyList()
        settled = false
        val addons = lumos.engine.addons
        // (fetched ahead while the remote rested on it: there at once)
        val ready = if (again) null else warmed[videoId]?.takeIf { lumos.clock() - it.first < WARM_MS }?.second
        if (ready != null) {
            streamsChanged++
            found = ready
            addonStates = addons.map { it.base to "Ready" }
            settled = true
            timings = listOf("fetched ahead: ${ready.size}")
            if (lumos.matchSpeed) scope.launch { fitSpeed = runCatching { lumos.speed.get() }.getOrDefault(0.0); networkCap = runCatching { lumos.speed.cap() }.getOrDefault(0) }
            streamsJob = Job().also { it.complete() }
            return
        }
        addonStates = addons.map { it.base to "Loading" }
        // (the connection's speed, read while the addons answer: never waited for when there's a reading from before)
        if (lumos.matchSpeed) scope.launch { fitSpeed = runCatching { lumos.speed.get() }.getOrDefault(0.0); networkCap = runCatching { lumos.speed.cap() }.getOrDefault(0) }
        else fitSpeed = 0.0
                val began = kotlin.time.TimeSource.Monotonic.markNow()
        timings = emptyList()
        streamsJob = scope.launch {
            val jobs = addons.mapIndexed { i, a ->
                launch {
                    val m = withTimeoutOrNull(Engine.MANIFEST_MS) { runCatching { a.manifest() }.getOrNull() }
                    val list = if (m != null && m.answers("stream", type, videoId)) {
                        withTimeoutOrNull(Engine.STREAMS_MS) { runCatching { a.streams(type, videoId) }.getOrNull() }
                    } else emptyList()
                    lock.withLock {
                        if (streamsVideo != videoId) return@withLock
                        streamsChanged++
                        found = found + list.orEmpty()
                        addonStates = addonStates.toMutableList().also { it[i] = (m?.name ?: a.base) to if (list == null) "Err" else "Ready" }
                        timings = timings + "${m?.name ?: "addon ${i + 1}"}: ${list?.size ?: "no answer"} in ${began.elapsedNow().inWholeMilliseconds} ms"
                    }
                }
            }
            jobs.forEach { it.join() }
            lock.withLock {
                if (streamsVideo == videoId) {
                    settled = true
                    streamsChanged++
                    // (kept, so the page opened again soon has them at once)
                    warmed[videoId] = lumos.clock() to found
                }
            }
        }
    }

    /** Lumen's stream list for the video being loaded (StreamChoices), with Just press Play's pick */
    fun streams(): String {
        val video = streamsVideo
        val list = described()
        // (the list as shown: the indexes Play, the player's Streams button and the checks before Play name are into this one)
        shown = list
        val choice = pick(list)
        lastPick = choice?.index ?: -1
        return buildJsonObject {
            put("justPlay", lumos.justPressPlay)
            put("settled", settled)
            video?.let { put("videoId", it) }
            put("pick", choice?.index ?: -1)
            put("how", choice?.how ?: "best")
            put("speed", round1(fitSpeed))
            choice?.lowered?.let { (from, to, why) -> putJsonObject("lowered") { put("from", from); put("to", to); put("why", why) } }
            putJsonArray("items") { list.forEach { (s, d) -> add(option(s, d)) } }
        }.toString()
    }

    /** The stream at [index] of [streams], ready for the player: its link (TorBox's for a torrent), headers and label */
    @Throws(Exception::class)
    suspend fun source(index: Int): String? {
        val (s, d) = shown.getOrNull(index) ?: return null
        val video = streamsVideo ?: return null
        val parts = video.split(":")
        // (tt…:season:episode; kitsu:id:episode has no season)
        val episode = if (parts.size >= 3) parts.last().toIntOrNull() else null
        val season = if (parts.size >= 3 && !video.startsWith("kitsu:")) parts[parts.size - 2].toIntOrNull() else null
        val url = s.url ?: lumos.engine.torBox?.resolve(s, season, episode) ?: return null
        // (one chosen from the list, not Lumos's own pick: remembered for this title; Lumen's way)
        if (index != lastPick) lumos.rememberChoice(openId, memory(s, d))
        playing = s
        return buildJsonObject {
            put("url", url)
            putJsonObject("headers") { s.headers.forEach { (k, v) -> put(k, v) } }
            put("label", label(d))
            put("play", playLink(s))
        }.toString()
    }

    /** Ranked as Lumen's list shows them: best picture first; of the same, ready, then seeds, then size. Upscales and samples left out */
    private fun described(): List<Pair<Stream, Described>> {
        val video = streamsVideo
        val year = openMeta?.preview?.releaseInfo?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }
        val name = openMeta?.preview?.name
        val all = found.filter { video != null && Matching.fits(it, video, year) }
            // (a search-based addon's must be named as this title: Easynews finds others called the same)
            .filter { !Matching.searchBased(it.source) || name == null || Matching.named(it, name, year) }
            .map { s -> s to Describe.stream(listOfNotNull(s.source, s.name).joinToString("\n"), s.text) }
        // (never cinema recordings; low-res rips only when nothing in HD is there, as for an old show that was only on DVD)
        val keep = all.filter { (_, d) -> !d.sample && d.source != "CAM" }.let { l -> l.filter { !it.second.upscaled }.ifEmpty { l } }
            .let { l -> if (l.any { it.second.res >= 720 }) l.filter { it.second.res == 0 || it.second.res >= 720 } else l }
        return keep.sortedWith { (sa, a), (sb, b) ->
            val sa1 = score(a); val sb1 = score(b)
            when {
                sa1 != sb1 -> sb1 - sa1
                ready(sa, a) != ready(sb, b) -> if (ready(sa, a)) -1 else 1
                a.seeds != b.seeds -> b.seeds - a.seeds
                else -> b.size.compareTo(a.size)
            }
        }
    }

    private fun ready(s: Stream, d: Described) = s.url != null || d.instant || s.cached == true
    private fun score(d: Described) = d.res * 10 + (if (d.dv) 3 else if (d.hdr) 2 else 0) +
        (mapOf("Remux" to 4, "BluRay" to 3, "WEB" to 2, "HDTV" to 1, "CAM" to -10)[d.source] ?: 0)

    private fun memory(s: Stream, d: Described) = listOf(s.source, d.res.toString(), s.bingeGroup ?: "").joinToString("|")

    /** The index Just press Play picked in the list last shown */
    private var lastPick = -1

    /** How many of the main addons' streams must fail before a search-based addon's is picked while theirs are left */
    private val LAST_RESORT_AFTER = 3

    private companion object { const val WARM_MS = 5 * 60_000L }

    private data class Pick(val index: Int, val how: String, val lowered: Triple<Int, Int, String>?)

    /**
     * Just press Play (Lumen's streamChoice.choose): the stream chosen for this title before (kept even when the speed says
     * it's heavy: chosen by hand), else of the ready ones those that fit the connection (at most 75% of its speed, or the
     * lightest when none does), under the quality cap (the setting's and this network's), the best by Lumen's ranking
     */
    private fun pick(list: List<Pair<Stream, Described>>): Pick? {
        val candidates = list.withIndex().filter { (_, p) ->
            !p.second.pending && !p.second.sample && p.second.source != "CAM" && ready(p.first, p.second) && key(p.first) !in rejected
        }
        if (candidates.isEmpty()) return null
        // Search-based addons (Easynews) are a last resort: picked only once every addon has answered and none of the
        // others' plays, or after several of theirs turned out not to (Easynews has played another show's episode)
        val main = candidates.filter { !Matching.searchBased(it.value.first.source) }
        val failed = list.count { (s, _) -> !Matching.searchBased(s.source) && key(s) in rejected }
        val usable = when {
            main.isNotEmpty() && failed < LAST_RESORT_AFTER -> main
            main.isNotEmpty() -> candidates
            settled || failed >= LAST_RESORT_AFTER -> candidates
            else -> return null
        }
        lumos.rememberedChoice(openId)?.let { m ->
            val (source, res, group) = (m.split("|") + listOf("", "", "")).take(3)
            if (group.isNotEmpty()) usable.firstOrNull { it.value.first.bingeGroup == group }?.let { return Pick(it.index, "same", null) }
            usable.firstOrNull { it.value.first.source == source && it.value.second.res.toString() == res }?.let { return Pick(it.index, "same", null) }
            // (the one chosen by hand is gone: "Show me the list" leaves it to them)
            if (lumos.fallbackAsk) return null
        }
        val rate = { x: IndexedValue<Pair<Stream, Described>> -> mbps(x.value.first, x.value.second) }
        var fit = usable
        if (fitSpeed > 0) {
            val fits = usable.filter { rate(it) <= fitSpeed * Speed.HEADROOM }
            val lightest = usable.minOf(rate)
            fit = fits.ifEmpty { usable.filter { rate(it) == lightest } }
        }
        // (the preferred addon's, when it has any that suit)
        val want = lumos.preferredAddon.trim().lowercase()
        if (want.isNotEmpty()) fit.filter { it.value.first.source.lowercase().contains(want) }.takeIf { it.isNotEmpty() }?.let { fit = it }
                val limit = listOf(lumos.qualityCap, networkCap).filter { it > 0 }.minOrNull() ?: Int.MAX_VALUE
        val allowed = fit.filter { it.value.second.res <= limit }
        val lowest = fit.minOf { it.value.second.res }
        val pool = allowed.ifEmpty { fit.filter { it.value.second.res == lowest } }
        val chosen = best(pool, 1).first()
        val top = best(usable, 1).first()
        val lowered = if (chosen.value.second.res < top.value.second.res)
            Triple(top.value.second.res, chosen.value.second.res, if (limit < top.value.second.res) "cap" else "speed") else null
        return Pick(chosen.index, "best", lowered)
    }

    /**
     * The stream as a Stremio player address (#/player/<stream>/<addon>/<addon>/<type>/<id>/<video>), as Lumen's player and
     * stream list read it: which title, episode and stream is playing
     */
    private fun playLink(s: Stream): String {
        val key = (s.url ?: "${s.infoHash}:${s.fileIdx ?: -1}").encodeURLParameter()
        return "#/player/$key/lumos/lumos/$openType/${openId.encodeURLParameter()}/${(streamsVideo ?: openId).encodeURLParameter()}"
    }

    /** Lumen's label (quickPlay.label): "4K · Dolby Vision · Blu-ray" */
    private fun label(d: Described) = listOfNotNull(
        qualityName(d.res),
        "IMAX".takeIf { d.imax },
        if (d.dv) "Dolby Vision" else if (d.hdr) "HDR" else null,
        mapOf("Remux" to "Disc quality", "BluRay" to "Blu-ray", "WEB" to "Web", "HDTV" to "TV broadcast", "CAM" to "Cinema recording", "Other" to "Video")[d.source],
    ).joinToString(" · ")
    private fun qualityName(r: Int) = when (r) { 2160 -> "4K"; 1440 -> "1440p"; 1080 -> "1080p"; 720 -> "720p"; 0 -> "Unknown"; else -> "SD" }

    private fun option(s: Stream, d: Described) = buildJsonObject {
        put("play", playLink(s))
        put("addon", s.source)
        put("filename", s.filename ?: s.title?.lineSequence()?.firstOrNull() ?: "")
        put("label", label(d))
        put("torrent", s.url == null && s.infoHash != null)
        put("provider", d.provider); put("site", d.site); put("codec", d.codec)
        put("instant", ready(s, d)); put("pending", d.pending); put("lesser", d.lesser)
        put("res", d.res); put("hdr", d.hdr); put("dv", d.dv); put("imax", d.imax); put("atmos", d.atmos)
        put("hdr10plus", d.hdr10plus); put("openMatte", d.openMatte); put("threeD", d.threeD)
        put("size", (s.size?.toDouble() ?: d.size)); put("seeds", d.seeds); put("source", d.source)
        put("langs", JsonArray(d.langs.map(::JsonPrimitive))); put("mbps", round1(mbps(s, d))); put("claimed", 0)
    }


    // ---- In the player: other streams of what's playing ----

    /** [streams] as last shown, and the one playing (set by [source]) */
    private var shown = listOf<Pair<Stream, Described>>()
    private var playing: Stream? = null
    /** Streams that turned out not to be videos (an addon's error page or clip): never picked again by themselves */
    private val rejected = mutableSetOf<String>()

    private fun key(s: Stream) = s.url ?: "${s.infoHash}:${s.fileIdx}"
    private fun same(a: Stream, b: Stream) = if (b.url != null) a.url == b.url else a.infoHash == b.infoHash && a.fileIdx == b.fileIdx

    /** The open title's length in minutes, from its runtime ("2h 15min", "136 min"), 0 if unknown */
    private val minutes: Int get() {
        val r = openMeta?.runtime ?: return 0
        val h = Regex("""(\d+)\s*h""").find(r)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val m = Regex("""(\d+)\s*m""").find(r)?.groupValues?.get(1)?.toIntOrNull() ?: if (h == 0) Regex("""\d+""").find(r)?.value?.toIntOrNull() ?: 0 else 0
        return h * 60 + m
    }

    private fun size(s: Stream, d: Described) = s.size?.toDouble() ?: d.size

    /** The bitrate a stream needs: its size over the title's length, else a guess from its quality (streamChoice.mbps) */
    private fun mbps(s: Stream, d: Described): Double {
        val min = minutes
        val bytes = size(s, d)
        if (bytes > 0 && min > 0) return bytes * 8 / (min * 60) / 1e6
        return mapOf(2160 to 20.0, 1440 to 12.0, 1080 to 8.0, 720 to 4.0, 480 to 2.0)[d.res] ?: 8.0
    }

    /** Lumen's ranking (streamChoice.rank): ready, then resolution, HDR, source, bitrate */
    private fun rank(s: Stream, d: Described): Double {
        val range = if (d.hdr) 2 else 0
        val rate = if (size(s, d) > 0 && minutes > 0) minOf(10.0, mbps(s, d) / 10) else 0.0
        val source = mapOf("Remux" to 6, "BluRay" to 4, "WEB" to 2, "HDTV" to 1)[d.source] ?: 0
        val imax = if (d.imax && lumos.preferImax) 20 else 0
        return (if (ready(s, d)) 1e5 else 0.0) + d.res * 10 + range + source + rate + imax - (if (d.lesser) 15000 else 0)
    }

    private fun best(list: List<IndexedValue<Pair<Stream, Described>>>, n: Int) =
        list.sortedWith(compareByDescending<IndexedValue<Pair<Stream, Described>>> { rank(it.value.first, it.value.second) }.thenByDescending { it.value.second.seeds }).take(n)

    /** What can stand in for the stream playing: not it, not a sample, a cinema recording or a debrid download not ready */
    private fun pool(): List<IndexedValue<Pair<Stream, Described>>> {
        val now = playing ?: return emptyList()
        return shown.withIndex().filter { (_, p) -> !same(p.first, now) && !p.second.sample && p.second.source != "CAM" && !p.second.pending && ready(p.first, p.second) }
    }

    private fun shownOption(x: IndexedValue<Pair<Stream, Described>>) = buildJsonObject {
        option(x.value.first, x.value.second).forEach { (k, v) -> put(k, v) }
        put("index", x.index)
    }

    /** The player's Streams button: other streams of what's playing, grouped by what they change ([nowMbps]: the playing file's bitrate, 0 if unknown) */
    fun alternatives(nowMbps: Double): String {
        val now = playing?.let { p -> shown.firstOrNull { same(it.first, p) } }
        val groups = buildJsonArray {
            if (now != null) {
                val pool = pool()
                val d = now.second
                val current = if (nowMbps > 0) nowMbps else mbps(now.first, d)
                fun group(title: String, list: List<IndexedValue<Pair<Stream, Described>>>) {
                    if (list.isNotEmpty()) add(buildJsonObject { put("title", title); putJsonArray("options") { list.forEach { add(shownOption(it)) } } })
                }
                val lower = pool.map { it.value.second.res }.filter { it in 1 until d.res }.distinct().sortedDescending()
                    .flatMap { r -> best(pool.filter { it.value.second.res == r }, 1) }
                group("Lower resolution", lower.take(3))
                group("Lighter at ${qualityName(d.res)}", best(pool.filter { it.value.second.res == d.res && mbps(it.value.first, it.value.second) <= current * 0.8 }, 3))
                group(if (d.hdr) "Not HDR" else "HDR", best(pool.filter { if (d.hdr) !it.value.second.hdr && it.value.second.res >= minOf(d.res, 1080) else it.value.second.hdr && it.value.second.res >= d.res }, 2))
                group("All streams", best(pool, 25))
            }
        }
        return buildJsonObject { put("groups", groups) }.toString()
    }

    /** It's buffering: up to three lighter streams that fit the speed the player's getting ([mbps]) */
    fun lighter(mbps: Double, nowMbps: Double): String {
        // (what the player really got while buffering: the next pick fits it)
        if (mbps > 0) scope.launch { lumos.speed.add(mbps, "stall") }
        val now = playing?.let { p -> shown.firstOrNull { same(it.first, p) } } ?: return "[]"
        val current = if (nowMbps > 0) nowMbps else mbps(now.first, now.second)
        val cap = minOf(current * 0.8, if (mbps > 0) mbps * 0.75 else Double.MAX_VALUE)
        val list = best(pool().filter { mbps(it.value.first, it.value.second) <= cap }, 3)
        return buildJsonArray { list.forEach { add(shownOption(it)) } }.toString()
    }

    /** The seek bar's previews: the lightest web stream of [videoId] (720p, else 1080p, else what there is) */
    fun previewSource(videoId: String): String? {
        if (videoId != streamsVideo) return null
        val web = shown.filter { (s, d) -> s.url?.startsWith("http") == true && !d.sample && !d.pending && d.source != "CAM" && d.res >= 480 }
        val pick = listOf(720, 1080, 480, 1440, 2160).map { r -> web.filter { it.second.res == r } }.firstOrNull { it.isNotEmpty() } ?: return null
        val (s, d) = pick.minBy { size(it.first, it.second).takeIf { v -> v > 0 } ?: Double.MAX_VALUE }
        return buildJsonObject {
            put("url", s.url!!); putJsonObject("headers") { s.headers.forEach { (k, v) -> put(k, v) } }; put("label", label(d))
        }.toString()
    }

    /** A web stream's address, headers and listed size, to look at before it's played (null for a torrent) */
    fun probe(index: Int): String? {
        val (s, d) = shown.getOrNull(index) ?: return null
        val url = s.url?.takeIf { it.startsWith("http") } ?: return null
        return buildJsonObject { put("url", url); putJsonObject("headers") { s.headers.forEach { (k, v) -> put(k, v) } }; put("size", size(s, d)) }.toString()
    }

    /** A stream that turned out not to be a video: Just press Play passes it over from now on */
    fun reject(index: Int): Boolean {
        val (s, _) = shown.getOrNull(index) ?: return false
        rejected += key(s)
        return true
    }

    /** An addon's own name, from its manifest (by its link) */
    @Throws(Exception::class)
    suspend fun addonName(url: String): String? {
        val base = Addon(url, lumos.http).base
        return lumos.engine.addons.firstOrNull { it.base == base }?.let { runCatching { it.manifest().name }.getOrNull() }
    }

    /** The stream addons' names (Settings → Preferred addon), Cinemeta left out */
    @Throws(Exception::class)
    suspend fun addonNames(): List<String> = lumos.engine.addons.mapNotNull { a ->
        runCatching { a.manifest() }.getOrNull()?.takeIf { m -> m.resources.containsKey("stream") }?.name
    }.distinct()

    // ---- Speed per network (Settings → Networks) ----

    /** As the app starts: a speed test a little later, so the first Play has a speed to go on */
    fun warmSpeed() { if (lumos.matchSpeed) lumos.speed.warm() }

    /** What the player got filling its buffer on this network */
    fun networkSample(mbps: Double) { if (mbps > 0) scope.launch { lumos.speed.add(mbps, "fill") } }

    @Throws(Exception::class)
    suspend fun networks(): String = lumos.speed.list()

    /** [cap]: 0, 2160, 1080 or 720 (-1: unchanged); [name] null: unchanged */
    fun setNetwork(id: String, name: String?, cap: Int, relearn: Boolean) = lumos.speed.set(id, name, cap.takeIf { it >= 0 }, relearn)

    fun forgetNetwork(id: String) = lumos.speed.forget(id)

    // ---- What the player needs besides the stream ----

    private val skips = Skips(lumos.http)

    /** Skip Intro, Recap and the credits' start for a video (JSON: [{type, start, end}]) */
    @Throws(Exception::class)
    suspend fun skipSegments(type: String, metaId: String, videoId: String): String = buildJsonArray {
        skips.of(type, metaId, videoId).forEach { k -> add(buildJsonObject { put("type", k.type); put("start", k.start); put("end", k.end?.let(::JsonPrimitive) ?: JsonNull) }) }
    }.toString()

    /**
     * Subtitles for the player (pickSubtitles): the playing stream's own, then the subtitle addons' (2 s at most), up to
     * three per language with [language] and English first; the first in [language] is marked to start with the video
     */
    @Throws(Exception::class)
    suspend fun subtitles(type: String, videoId: String, language: String): String {
        val own = playing?.subtitles.orEmpty().map { it to "stream" }
        val addons = lumos.engine.addons
        val fromAddons = withTimeoutOrNull(2000) {
            coroutineScope {
                addons.map { a ->
                    async {
                        val m = withTimeoutOrNull(Engine.MANIFEST_MS) { runCatching { a.manifest() }.getOrNull() }
                        if (m != null && m.answers("subtitles", type, videoId)) runCatching { a.subtitles(type, videoId) }.getOrNull().orEmpty() else emptyList()
                    }
                }.awaitAll().flatten()
            }
        }.orEmpty().map { it to "addon" }
        fun rank(lang: String) = if (language.isNotEmpty() && Languages.same(lang, language)) 0 else if (Languages.same(lang, "eng")) 1 else 2
        val sorted = (own + fromAddons).sortedWith(compareBy({ rank(it.first.lang) }, { if (it.second == "stream") 0 else 1 }))
        val count = mutableMapOf<String, Int>()
        var started = false
        return buildJsonArray {
            var n = 0
            for ((sub, source) in sorted) {
                val c = (count[sub.lang] ?: 0) + 1
                count[sub.lang] = c
                if (c > 3) continue
                add(buildJsonObject {
                    put("url", sub.url); put("lang", sub.lang); put("source", source)
                    put("label", Languages.name(sub.lang) + if (c > 1) " $c" else "")
                    if (language.isNotEmpty() && !started && Languages.same(sub.lang, language)) { put("start", true); started = true }
                })
                if (++n >= 45) break
            }
        }.toString()
    }

    private fun round1(x: Double) = kotlin.math.round(x * 10) / 10

    /** This profile's My List (Lumen's Library), latest added first */
    fun myList(): String = buildJsonObject {
        putJsonArray("items") {
            lumos.myListEntries().forEach { (id, e) ->
                add(buildJsonObject {
                    put("id", id); put("type", e.str("type") ?: "movie"); put("name", e.str("name") ?: "")
                    e.str("poster")?.let { put("poster", it) }; put("inLibrary", true); put("watched", id in lumos.watchedIds())
                })
            }
        }
    }.toString()

    // ---- Profiles ----

    fun viewers(): String = buildJsonObject {
        putJsonArray("list") {
            lumos.profiles().forEach { p -> add(buildJsonObject { put("id", p.id); put("name", p.name); p.colour?.let { put("colour", it) }; put("kids", p.kids) }) }
        }
        put("current", lumos.viewer)
    }.toString()

    // ----

    private fun title(m: MetaPreview) = buildJsonObject { titleFields(m) }

    private fun JsonObjectBuilder.titleFields(m: MetaPreview) {
        put("id", m.id); put("type", m.type); put("name", m.name)
        m.poster?.let { put("poster", it) }; m.background?.let { put("background", it) }; m.logo?.let { put("logo", it) }
        m.releaseInfo?.let { put("year", it) }; m.imdbRating?.let { put("imdb", it) }
        m.description?.let { put("description", it) }
        put("genres", JsonArray(m.genres.map(::JsonPrimitive)))
        put("inLibrary", lumos.inMyList(m.id))
        put("watched", m.id in lumos.watchedIds())
    }

    private fun enc(s: String) = s.encodeURLParameter()
}

private suspend fun Addon.name(): String = runCatching { manifest().name }.getOrDefault(base)
