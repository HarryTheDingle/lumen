package com.notdxbi.dxbi.tv.phone

import org.json.JSONArray
import org.json.JSONObject

// What the native screens show, read from the JSON dxbi-engine.js sends (see its views)

fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { opt(it) as? String }

/** Names come with their HTML escapes ("Ed O&apos;Neill") */
fun unescaped(s: String): String = s.replace("&apos;", "'").replace("&#39;", "'").replace("&quot;", "\"").replace("&amp;", "&")

/** A string that's really there: null for missing, JSON null and "" */
fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

data class Title(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val wide: Boolean = false,
    val year: String? = null,
    val runtime: String? = null,
    val imdb: String? = null,
    val genres: List<String> = emptyList(),
    val description: String? = null,
    val inLibrary: Boolean = false,
    val watched: Boolean = false,
    val trailer: String? = null,
) {
    /** Art for a card: the poster, or for titles without one the backdrop */
    val art: String? get() = poster ?: background ?: if (id.startsWith("tt")) "https://images.metahub.space/poster/small/$id/img" else null
    val backdrop: String? get() = background ?: if (id.startsWith("tt")) "https://images.metahub.space/background/medium/$id/img" else poster
    /** The title's logo (its name as drawn on the poster), else Metahub's for its IMDb id, as the iPad does */
    val logoUrl: String? get() = logo ?: if (id.startsWith("tt")) "https://images.metahub.space/logo/medium/$id/img" else null

    companion object {
        fun from(o: JSONObject) = Title(
            id = o.optString("id"),
            type = o.optString("type"),
            name = o.optString("name"),
            poster = o.text("poster"),
            background = o.text("background"),
            logo = o.text("logo"),
            wide = o.optString("shape") == "landscape",
            year = o.text("year"),
            runtime = o.text("runtime"),
            imdb = o.text("imdb"),
            genres = o.optJSONArray("genres").strings(),
            description = o.text("description"),
            inLibrary = o.optBoolean("inLibrary"),
            watched = o.optBoolean("watched"),
            trailer = o.text("trailer"),
        )
    }
}

enum class Load { None, Loading, Ready, Err }

private fun load(o: JSONObject, key: String = "state") = when (o.optString(key)) {
    "Ready" -> Load.Ready
    "Loading" -> Load.Loading
    "Err" -> Load.Err
    else -> Load.None
}

data class Row(val index: Int, val key: String, val name: String, val type: String, val addon: String, val discover: String?, val state: Load, val items: List<Title>) {
    /** Its name with its kind, so two "Popular" rows read "Popular Movies" and "Popular Series" */
    val heading: String get() {
        val kind = when (type) { "movie" -> "Movies"; "series" -> "Series"; else -> type.replaceFirstChar { it.uppercase() } }
        return if (kind.isEmpty() || name.contains(kind, ignoreCase = true)) name else "$name $kind"
    }

    companion object {
        fun from(o: JSONObject) = Row(
            index = o.optInt("index"),
            key = o.optString("key"),
            name = o.optString("name"),
            type = o.optString("type"),
            addon = o.optString("addon"),
            discover = o.text("discover"),
            state = load(o),
            items = o.optJSONArray("items").objects().map(Title::from),
        )

        fun list(state: JSONObject?) = state?.optJSONArray("catalogs").objects().map(::from)
    }
}

data class Continue(val title: Title, val progress: Float, val videoId: String?, val newEpisodes: Int) {
    companion object {
        fun list(state: JSONObject?) = state?.optJSONArray("items").objects().map { o ->
            Continue(Title.from(o), (o.optDouble("progress", 0.0) / 100.0).toFloat().coerceIn(0f, 1f), o.text("videoId"), o.optInt("notifications"))
        }
    }
}

data class Video(
    val id: String,
    val title: String,
    val season: Int?,
    val episode: Int?,
    val thumbnail: String?,
    val released: String?,
    val overview: String?,
    val watched: Boolean,
    val progress: Float?,
    val upcoming: Boolean,
) {
    companion object {
        fun from(o: JSONObject) = Video(
            id = o.optString("id"),
            title = o.optString("title"),
            season = if (o.isNull("season")) null else o.optInt("season"),
            episode = if (o.isNull("episode")) null else o.optInt("episode"),
            thumbnail = o.text("thumbnail"),
            released = o.text("released"),
            overview = o.text("overview"),
            watched = o.optBoolean("watched"),
            progress = if (o.isNull("progress")) null else (o.optDouble("progress", 0.0) / 100.0).toFloat(),
            upcoming = o.optBoolean("upcoming"),
        )
    }
}

data class Stream(
    val name: String,
    val description: String,
    val url: String?,
    val torrent: Boolean,
    val trailer: Boolean,
    val externalUrl: String?,
    val filename: String?,
    val headers: Map<String, String>,
    val play: String?,
) {
    companion object {
        fun from(o: JSONObject) = Stream(
            name = o.optString("name"),
            description = o.optString("description"),
            url = o.text("url"),
            torrent = o.text("infoHash") != null,
            trailer = o.text("ytId") != null,
            externalUrl = o.text("externalUrl"),
            filename = o.text("filename"),
            headers = o.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { h.optString(it) } } ?: emptyMap(),
            play = o.text("play"),
        )
    }
}

/** A stream as dx.bi's stream list reads it (api.phone.streams: describeStream in the plugin) */
data class StreamOption(
    val index: Int,
    val play: String,
    val addon: String,
    val filename: String,
    val label: String,
    val torrent: Boolean,
    val provider: String,
    val site: String,
    val codec: String,
    val instant: Boolean,
    val pending: Boolean,
    val lesser: Boolean,
    val res: Int,
    val hdr: Boolean,
    val dv: Boolean,
    val size: Double,
    val seeds: Int,
    val source: String,
    val langs: List<String>,
    /** Formats the file name claims: IMAX, Dolby Atmos, HDR10+, Open Matte, 3D */
    val imax: Boolean = false,
    val atmos: Boolean = false,
    val hdr10plus: Boolean = false,
    val openMatte: Boolean = false,
    val threeD: Boolean = false,
    /** The stream's bitrate in Mbit/s (0 when unknown), and the quality its name claimed when it has played lower (0 otherwise) */
    val mbps: Double = 0.0,
    val claimed: Int = 0,
    /** Dolby Vision with no HDR10 under it: wrong colours on a TV without Dolby Vision */
    val dvOnly: Boolean = false,
) {
    val bitrateText: String? get() = if (mbps > 0) "%.0f Mbps".format(mbps) else null
    val claimNote: String? get() = if (claimed > res && res > 0) "Named ${qualityOf(claimed)}, plays ${qualityOf(res)}" else null
    /** The formats it offers, for the Format filter */
    val formats: List<String> get() = listOfNotNull(
        "IMAX".takeIf { imax }, "Dolby Vision".takeIf { dv }, "HDR".takeIf { hdr }, "Atmos".takeIf { atmos },
        "Remux".takeIf { source == "Remux" }, "Open Matte".takeIf { openMatte }, "3D".takeIf { threeD },
    )
    val quality: String get() = qualityOf(res)
    private fun qualityOf(r: Int) = when (r) { 2160 -> "4K"; 1440 -> "1440p"; 1080 -> "1080p"; 720 -> "720p"; 0 -> "?"; else -> "SD" }
    val sizeText: String? get() = if (size <= 0) null else if (size >= 1e9) "%.1f GB".format(size / 1e9) else "%.0f MB".format(size / 1e6)

    /**
     * The picture's quality, as dx.bi's pick scores it (streamChoice.rank): resolution, then HDR (Dolby Vision alone last),
     * the source (a remux over an encode over a web copy) and a point for every 10 Mbit/s, so the fullest picture leads
     */
    val score: Double get() = res * 10.0 + (if (dvOnly) -2 else if (hdr) 2 else 0) + (SOURCE_RANK[source] ?: 0) + minOf(10.0, mbps / 10)

    companion object {
        val FORMAT_ORDER = listOf("IMAX", "Dolby Vision", "HDR", "Atmos", "Remux", "Open Matte", "3D")
        private val SOURCE_RANK = mapOf("Remux" to 6, "BluRay" to 4, "WEB" to 2, "HDTV" to 1, "CAM" to -10)

        /** Best quality first; of the same quality, ready to play, then most seeds, then largest */
        val BEST: Comparator<StreamOption> = compareByDescending<StreamOption> { it.score }
            .thenByDescending { it.instant }.thenByDescending { it.seeds }.thenByDescending { it.size }
        fun from(i: Int, o: JSONObject) = StreamOption(
            i, o.optString("play"), o.optString("addon"), o.optString("filename"), o.optString("label"), o.optBoolean("torrent"), o.optString("provider"), o.optString("site"),
            o.optString("codec"), o.optBoolean("instant"), o.optBoolean("pending"), o.optBoolean("lesser"), o.optInt("res"), o.optBoolean("hdr"),
            o.optBoolean("dv"), o.optDouble("size", 0.0), o.optInt("seeds", -1), o.optString("source"), o.optJSONArray("langs").strings(),
            o.optBoolean("imax"), o.optBoolean("atmos"), o.optBoolean("hdr10plus"), o.optBoolean("openMatte"), o.optBoolean("threeD"),
            o.optDouble("mbps", 0.0), o.optInt("claimed", 0), o.optBoolean("dvOnly"),
        )
    }
}

data class StreamChoices(
    val justPlay: Boolean, val settled: Boolean, val videoId: String?, val pick: Int, val sameAsLastTime: Boolean, val items: List<StreamOption>,
    /** The connection's speed in Mbps, when dx.bi's Match my internet speed measured it (else 0) */
    val speed: Double = 0.0,
    /** Why a lighter stream than the best was picked: "cap" (the quality set for this network) or "speed", with both qualities */
    val lowered: Lowered? = null,
) {
    data class Lowered(val from: Int, val to: Int, val why: String) {
        private fun name(r: Int) = when (r) { 2160 -> "4K"; 1440 -> "1440p"; 1080 -> "1080p"; 720 -> "720p"; else -> "SD" }
        val note: String get() = "${name(to)} rather than ${name(from)}: " + if (why == "cap") "this network's quality is capped" else "the connection's speed"
    }
    val picked: StreamOption? get() = items.getOrNull(pick)

    /**
     * The same choices, with a Dolby Vision-only stream (no HDR10 under it: a profile 5 file, which comes out green and purple
     * without a Dolby Vision decoder) swapped for the best other one, when this device can't decode it
     */
    fun withoutDolbyVisionOnly(decoderPresent: Boolean): StreamChoices {
        val p = picked ?: return this
        if (!p.dvOnly || decoderPresent) return this
        val next = items.filter { it.index != p.index && !it.dvOnly && !it.pending && it.source != "CAM" && !it.lesser && (speed <= 0 || it.mbps <= 0 || it.mbps <= speed * 0.8) }
            .sortedWith(compareByDescending<StreamOption> { it.instant }.thenComparing(StreamOption.BEST)).firstOrNull() ?: return this
        return copy(pick = items.indexOf(next))
    }

    companion object {
        fun from(o: JSONObject) = StreamChoices(
            o.optBoolean("justPlay"), o.optBoolean("settled"), o.text("videoId"), o.optInt("pick", -1), o.optString("how") == "same",
            o.optJSONArray("items").objects().mapIndexed(StreamOption::from),
            o.optDouble("speed", 0.0),
            o.optJSONObject("lowered")?.let { Lowered(it.optInt("from"), it.optInt("to"), it.optString("why")) },
        )
    }
}

data class StreamGroup(val addon: String, val state: Load, val streams: List<Stream>)

data class Details(
    val state: Load,
    val metaId: String?,
    val videoId: String?,
    val title: Title?,
    val cast: List<String>,
    val directors: List<String>,
    val videos: List<Video>,
    val resumeVideoId: String?,
    // the episode Play starts (the engine works it out: dxbi-engine.js nextEpisode)
    val nextVideoId: String? = null,
    val resumeMs: Long,
    val durationMs: Long,
    val streams: List<StreamGroup>,
) {
    val seasons: List<Int> get() = videos.mapNotNull { it.season }.distinct().sortedWith(compareBy({ it == 0 }, { it }))

    /** A random released episode for Shuffle (specials and unreleased skipped; not the one last played, unless it's the only one) */
    fun shuffled(): Video? {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val out = videos.filter { (it.season ?: 0) > 0 && !it.upcoming && (it.released.isNullOrEmpty() || it.released.take(10) <= today) }
        return out.filter { it.id != resumeVideoId }.randomOrNull() ?: out.randomOrNull()
    }

    companion object {
        fun from(o: JSONObject?): Details? {
            if (o == null) return null
            val meta = o.optJSONObject("meta")
            val selected = o.optJSONObject("selected")
            val resume = o.optJSONObject("resume")
            return Details(
                state = load(o),
                metaId = selected?.optJSONObject("metaPath")?.text("id"),
                videoId = selected?.optJSONObject("streamPath")?.text("id"),
                title = meta?.let(Title::from),
                cast = meta?.optJSONArray("cast").strings().map(::unescaped),
                directors = meta?.optJSONArray("directors").strings().map(::unescaped),
                videos = meta?.optJSONArray("videos").objects().map(Video::from),
                resumeVideoId = resume?.text("videoId"),
                nextVideoId = o.text("next"),
                resumeMs = resume?.optLong("timeOffset") ?: 0,
                durationMs = resume?.optLong("duration") ?: 0,
                streams = o.optJSONArray("streams").objects().map { g ->
                    StreamGroup(g.optString("addon"), load(g), g.optJSONArray("items").objects().map(Stream::from))
                },
            )
        }
    }
}

/** Something Discover can switch to: a type or a catalog, with the link that loads it */
data class Pickable(val name: String, val selected: Boolean, val link: String?)

data class Discover(
    val request: JSONObject?, val types: List<Pickable>, val catalogs: List<Pickable>, val genres: List<String>,
    val state: Load, val items: List<Title>, val nextPage: Boolean,
) {
    val genre: String? get() = request?.optJSONObject("path")?.optJSONArray("extra")?.let { extra ->
        (0 until extra.length()).mapNotNull { extra.optJSONArray(it) }.firstOrNull { it.optString(0) == "genre" }?.optString(1)
    }

    companion object {
        fun from(o: JSONObject?) = o?.let {
            Discover(
                it.optJSONObject("request"),
                it.optJSONArray("types").objects().map { t -> Pickable(t.optString("type"), t.optBoolean("selected"), t.text("link")) },
                it.optJSONArray("catalogs").objects().map { c ->
                    val addon = c.optString("addon")
                    Pickable(if (addon.isNotEmpty() && addon != "Cinemeta") "${c.optString("name")} · $addon" else c.optString("name"), c.optBoolean("selected"), c.text("link"))
                },
                it.optJSONArray("genres").strings(),
                load(it),
                it.optJSONArray("items").objects().map(Title::from),
                it.optBoolean("nextPage"),
            )
        }
    }
}

data class Account(val signedIn: Boolean, val email: String?, val avatar: String?, val addons: List<String>) {
    companion object {
        fun from(o: JSONObject?) = o?.let {
            val user = it.optJSONObject("user")
            Account(it.optBoolean("signedIn"), user?.text("email"), user?.text("avatar"), it.optJSONArray("addons").objects().map { a -> a.optString("name") })
        }
    }
}

/** dx.bi's own Home rows (window.__dxbi.phone) */
data class ListRow(val name: String, val items: List<Title>)

/** Christmas at Home (from the plugin): the days left, whether it's the season, and the rows of titles */
data class Christmas(val daysToChristmas: Int, val inSeason: Boolean, val rows: List<ListRow>)

/** One season of an anime (Kitsu keeps each as its own show): its title, "Season 2", how many episodes, and whether it's the one open */
data class AnimeSeason(val title: Title, val label: String, val episodes: Int, val current: Boolean)

data class Airing(
    val id: String, val name: String, val background: String?, val still: String?, val season: Int, val episode: Int,
    val title: String, val more: Int, val time: Long, val whenText: String, val countdown: String, val out: Boolean,
) {
    companion object {
        fun from(o: JSONObject) = Airing(
            o.optString("id"), o.optString("name"), o.text("background"), o.text("still"), o.optInt("season"), o.optInt("episode"),
            o.optString("title"), o.optInt("more"), o.optLong("time"), o.optString("when"), o.optString("countdown"), o.optBoolean("out"),
        )
    }
}

/** An episode of a Library show on the Calendar: the show, which episode, and the day it comes out */
data class CalendarEpisode(
    val id: String, val name: String, val poster: String?, val background: String?, val season: Int, val episode: Int, val time: Long,
) {
    companion object {
        fun from(o: JSONObject) = CalendarEpisode(
            o.optString("id"), o.optString("name"), o.text("poster"), o.text("background"), o.optInt("season"), o.optInt("episode"), o.optLong("time"),
        )
    }
}

data class Addon(
    val transportUrl: String, val id: String, val name: String, val version: String, val description: String, val logo: String?,
    val types: List<String>, val official: Boolean, val protected: Boolean, val configurable: Boolean, val configurationRequired: Boolean, val installed: Boolean,
) {
    /** Where the addon lets you set it up (its own web page) */
    val configureUrl: String get() = transportUrl.replace(Regex("""manifest\.json$"""), "configure")

    companion object {
        fun from(o: JSONObject) = Addon(
            o.optString("transportUrl"), o.optString("id"), o.optString("name"), o.optString("version"), o.optString("description"), o.text("logo"),
            o.optJSONArray("types").strings(), o.optBoolean("official"), o.optBoolean("protected"), o.optBoolean("configurable"),
            o.optBoolean("configurationRequired"), o.optBoolean("installed"),
        )
    }
}

/** An internet connection Lumen has learnt the speed of (Settings → Networks) */
data class NetworkInfo(val id: String, val name: String, val cap: Int, val current: Boolean, val trusted: Boolean, val mbps: Double, val readings: Int) {
    /** Its own name, or where it's from */
    val title: String get() = name.ifEmpty { if (current) "This network" else "Network ${id.takeLast(4)}" }

    companion object {
        fun from(o: JSONObject) = NetworkInfo(o.optString("id"), o.optString("name"), o.optInt("cap"), o.optBoolean("current"), o.optBoolean("trusted"), o.optDouble("mbps"), o.optInt("readings"))
    }
}

data class Score(val source: String, val name: String, val note: String, val value: String, val votes: Int, val url: String?)
data class Review(val author: String, val rating: Double?, val date: String, val text: String, val url: String?)

data class Reviews(
    val scores: List<Score>, val scoresFrom: String?, val problem: String?,
    val critics: List<String>, val criticsUrl: String?,
    val audience: List<Review>, val audienceTotal: Int, val audienceUrl: String?, val audienceProblem: String?,
    val missing: List<String>,
) {
    companion object {
        fun from(o: JSONObject): Reviews {
            val critics = o.optJSONObject("critics")
            val audience = o.optJSONObject("audience")
            return Reviews(
                scores = o.optJSONArray("scores").objects().map { Score(it.optString("source"), it.optString("name"), it.optString("note"), it.optString("value"), it.optInt("votes"), it.text("url")) },
                scoresFrom = o.text("scoresFrom"),
                problem = o.text("mdbError"),
                critics = critics?.optJSONArray("text").strings(),
                criticsUrl = critics?.text("url"),
                audience = audience?.optJSONArray("reviews").objects().map {
                    Review(it.optString("author"), if (it.isNull("rating")) null else it.optDouble("rating"), it.optString("when"), it.optString("text"), it.text("url"))
                },
                audienceTotal = audience?.optInt("total") ?: 0,
                audienceUrl = audience?.text("url"),
                audienceProblem = audience?.text("error"),
                missing = o.optJSONArray("missing").strings(),
            )
        }
    }
}

data class Setting(val key: String, val type: String, val label: String, val description: String, val group: String, val options: List<Pair<Any?, String>>, val value: Any?) {
    companion object {
        fun from(o: JSONObject) = Setting(
            o.optString("key"), o.optString("type"), o.optString("label"), o.optString("description"), o.optString("group"),
            o.optJSONArray("options").objects().map { (if (it.isNull("value")) null else it.opt("value")) to it.optString("label") },
            if (o.isNull("value")) null else o.opt("value"),
        )
    }
}

data class Viewer(val id: String, val name: String, val colour: String?, val kids: Boolean)

data class HomeLayout(val order: List<String>, val hidden: Set<String>, val names: Map<String, String>) {
    companion object {
        val NONE = HomeLayout(emptyList(), emptySet(), emptyMap())
        fun from(o: JSONObject?) = o?.let {
            val names = it.optJSONObject("names")
            HomeLayout(it.optJSONArray("order").strings(), it.optJSONArray("hidden").strings().toSet(), names?.keys()?.asSequence()?.associateWith { k -> names.optString(k) } ?: emptyMap())
        } ?: NONE
    }

    /** Home's rows as the viewer arranged them: their order first, anything new after, hidden ones left out */
    fun arrange(rows: List<Row>): List<Row> {
        val visible = rows.filter { it.key !in hidden }.map { r -> names[r.key]?.let { r.copy(name = it) } ?: r }
        if (order.isEmpty()) return visible
        val place = order.withIndex().associate { (i, k) -> k to i }
        return visible.sortedBy { place[it.key] ?: (order.size + it.index) }
    }
}
