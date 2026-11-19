package com.notdxbi.dxbi.tv.lumen

import android.text.Html
import android.util.Base64
import androidx.compose.ui.graphics.Color
import com.notdxbi.dxbi.tv.phone.Christmas
import com.notdxbi.dxbi.tv.phone.Dates
import com.notdxbi.dxbi.tv.phone.ListRow
import com.notdxbi.dxbi.tv.phone.Title
import com.notdxbi.dxbi.tv.phone.objects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Discover's rows, from the internet directly (not through Stremio's page), as the iPad has them
 * (ios/Dxbi/Engine/DiscoverFeed.swift): what's on each streaming service (the free Streaming Catalogs
 * addon, for this country), Cinemeta's popular and genre catalogs, and the films out on digital this year
 * (dvdsreleasedates.com, which links each to IMDb). Kept for a while on the TV so Discover opens at once.
 */
/** The feed, for pages that need its dates (a film's page: whether it's out on digital yet) */
val LocalDiscoverFeed = androidx.compose.runtime.staticCompositionLocalOf<DiscoverFeed?> { null }

class DiscoverFeed(cacheDir: File) {

    /** A streaming service: its catalog's code at the addon, and its colours for Discover's look */
    data class Platform(val id: String, val name: String, val colour: Color, val deep: Color) {
        /** Its app icon, from JustWatch (where the catalogs come from) */
        val icon: String? get() = ICONS[id]?.let { "https://images.justwatch.com/icon/$it.png" }
    }

    /** A catalog's title, with when it came out (for "New on…") */
    data class Item(val title: Title, val released: Long?)

    /** A film out (or coming) on digital, with its date */
    data class Release(val id: String, val name: String, val date: Long) {
        val title: Title get() = Title(id = id, type = "movie", name = name, poster = "https://images.metahub.space/poster/medium/$id/img")
    }

    companion object {
        private val SEQUEL = Regex("""(\bseason\s*\d+|\b\d+(st|nd|rd|th)\s+season|\bpart\s*\d+|\bfinal season|\bcour\s*\d+|\bPV\b|\s\d{1,2}$|\s(II|III|IV|V|VI)$)""", RegexOption.IGNORE_CASE)
        private val ICONS = mapOf(
            "nfx" to "207360008/s166/netflix",
            "amp" to "322992749/s166/amazonprime",
            "dnp" to "313118777/s166/disneyplus",
            "atp" to "338367329/s166/appletvplus",
            "hbm" to "332884837/s166/max",
            "pmp" to "242706661/s166/paramountplus",
            "sgo" to "346742420/s166/skygo",
            "bbc" to "694553/s166/bbc",
            "itv" to "302120301/s166/itv",
            "al4" to "306402519/s166/all4",
        )

        private fun rgb(r: Double, g: Double, b: Double) = Color(r.toFloat(), g.toFloat(), b.toFloat())

        /** UK services first (Lumen's at home there): the addon has each one for the country asked */
        val platforms = listOf(
            Platform("nfx", "Netflix", rgb(0.898, 0.035, 0.078), rgb(0.25, 0.0, 0.02)),
            Platform("amp", "Prime Video", rgb(0.0, 0.66, 0.88), rgb(0.02, 0.13, 0.24)),
            Platform("dnp", "Disney+", rgb(0.13, 0.36, 0.95), rgb(0.03, 0.07, 0.27)),
            Platform("atp", "Apple TV+", rgb(0.85, 0.85, 0.85), rgb(0.12, 0.12, 0.12)),
            Platform("hbm", "HBO Max", rgb(0.45, 0.25, 0.95), rgb(0.1, 0.04, 0.25)),
            Platform("pmp", "Paramount+", rgb(0.0, 0.39, 1.0), rgb(0.0, 0.09, 0.26)),
            Platform("sgo", "Sky", rgb(0.0, 0.55, 0.85), rgb(0.2, 0.05, 0.3)),
            Platform("bbc", "BBC iPlayer", rgb(1.0, 0.3, 0.6), rgb(0.25, 0.03, 0.15)),
            Platform("itv", "ITVX", rgb(0.87, 0.92, 0.32), rgb(0.12, 0.15, 0.04)),
            Platform("al4", "Channel 4", rgb(0.2, 0.8, 0.75), rgb(0.03, 0.15, 0.15)),
        )

        private const val ADDON = "https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club"
        private const val CINEMETA = "https://v3-cinemeta.strem.io"
        private val WATCHABLE = setOf("movie", "series", "anime")
        private val NON_ALNUM = Regex("[^a-z0-9]+")
        private const val HOUR = 3_600_000L
        private const val DAY = 24 * HOUR

        /** How long Christmas at Home is kept, in memory and on disk */
        private const val CHRISTMAS_KEEP = 6 * HOUR

        /** The finished Christmas page for this session (the feed may be made again; the page needn't be) */
        @Volatile private var christmasMemory: Pair<Long, List<ListRow>>? = null

        /** A TMDB-made row: [filter] (given this year) narrows the Christmas-keyword films, [sort] orders them */
        private class TmdbRow(val name: String, val sort: String, val filter: (Int) -> String)

        private val CHRISTMAS_TMDB = listOf(
            // (this year's and last year's; popularity keeps unwatched shorts out, the vote floor is low as they're new)
            TmdbRow("New Christmas Movies", "popularity.desc") { y ->
                "primary_release_date.gte=${y - 1}-01-01&primary_release_date.lte=$y-12-31&vote_count.gte=3"
            },
            // (the Hallmark kind: Christmas, romance, made for TV; 10749 Romance and 10770 TV Movie, both required)
            TmdbRow("Hallmark-Style Christmas Romances", "popularity.desc") { _ -> "with_genres=10749,10770&vote_count.gte=5" },
            TmdbRow("Family Christmas", "popularity.desc") { _ -> "with_genres=10751&vote_count.gte=150" },
            TmdbRow("Christmas Comedies", "popularity.desc") { _ -> "with_genres=35&vote_count.gte=150" },
            TmdbRow("More Christmas", "popularity.desc") { _ -> "vote_count.gte=150" },
        )

        private data class CuratedList(val name: String, val type: String, val titles: List<Pair<String, Int?>>)

        private val CHRISTMAS_LISTS = listOf(
            CuratedList(
                "Christmas Classics",
                "movie",
                listOf(
                    "Elf" to 2003,
                    "Home Alone" to 1990,
                    "Home Alone 2: Lost in New York" to 1992,
                    "Love Actually" to 2003,
                    "Die Hard" to 1988,
                    "National Lampoon's Christmas Vacation" to 1989,
                    "Miracle on 34th Street" to 1994,
                    "It's a Wonderful Life" to 1946,
                    "White Christmas" to 1954,
                    "A Christmas Story" to 1983,
                    "Scrooged" to 1988,
                    "The Santa Clause" to 1994,
                ),
            ),
            CuratedList(
                "Family & Animated",
                "movie",
                listOf(
                    "The Polar Express" to 2004,
                    "Klaus" to 2019,
                    "How the Grinch Stole Christmas" to 2000,
                    "The Grinch" to 2018,
                    "Arthur Christmas" to 2011,
                    "The Muppet Christmas Carol" to 1992,
                    "The Nightmare Before Christmas" to 1993,
                    "Fred Claus" to 2007,
                    "Rise of the Guardians" to 2012,
                    "Nativity!" to 2009,
                    "A Charlie Brown Christmas" to 1965,
                ),
            ),
            CuratedList(
                "Romance & Comedy",
                "movie",
                listOf(
                    "The Holiday" to 2006,
                    "Last Christmas" to 2019,
                    "Four Christmases" to 2008,
                    "Bad Santa" to 2003,
                    "Deck the Halls" to 2006,
                    "The Family Stone" to 2005,
                    "Christmas with the Kranks" to 2004,
                    "Jingle All the Way" to 1996,
                    "Love Hard" to 2021,
                    "Single All the Way" to 2021,
                    "Spirited" to 2022,
                    "Violent Night" to 2022,
                    "Red One" to 2024,
                ),
            ),
            CuratedList(
                "Series for Christmas",
                "series",
                listOf(
                    "Friends" to null,
                    "The Office" to 2005,
                    "Modern Family" to null,
                    "Doctor Who" to null,
                    "Brooklyn Nine-Nine" to null,
                    "Parks and Recreation" to null,
                    "Gilmore Girls" to null,
                    "The Simpsons" to null,
                    "Community" to null,
                    "How I Met Your Mother" to null,
                ),
            ),
        )
    }

    private val folder = File(cacheDir, "discover").apply { mkdirs() }
    private val memory = ConcurrentHashMap<String, Pair<Long, List<Item>>>()

    private val country: String get() = Locale.getDefault().country.ifEmpty { "GB" }

    /** The addon's settings, in its address: the services, no poster key, this country */
    private val config: String get() =
        Base64.encodeToString("${platforms.joinToString(",") { it.id }}::$country".toByteArray(), Base64.NO_WRAP)

    // ---------------------------------------------------------------------------------------------
    // Catalogs

    /** What's on a service: popular first */
    suspend fun platform(p: Platform, type: String): List<Item> = catalog("$ADDON/$config/catalog/$type/${p.id}.json", 6 * HOUR)

    /** Cinemeta's popular titles, of one genre or all */
    suspend fun popular(type: String, genre: String? = null): List<Item> {
        val extra = genre?.let { "/genre=" + URLEncoder.encode(it, "UTF-8").replace("+", "%20") } ?: ""
        return catalog("$CINEMETA/catalog/$type/top$extra.json", 6 * HOUR, max = 60)
    }

    /** A film's dates: in cinemas (Cinemeta's release date) and out on digital (dvdsreleasedates.com), each null when not known */
    data class ReleaseDates(val cinema: Long?, val digital: Long?) {
        /**
         * The tag a film's page shows while it isn't out on digital ("In Cinemas", "Coming 21 Nov", "Digital 18 Nov"),
         * and what the streams list says about it; null once it's out (or too old to be worth saying)
         */
        fun notice(now: Long = System.currentTimeMillis()): Pair<String, String>? {
            if (digital != null && digital <= now) return null
            val cam = "Not on digital yet: streams now are likely cinema recordings (CAM)"
            return when {
                digital != null && cinema != null && cinema <= now -> "In Cinemas · Digital ${Dates.formatShortDay(digital)}" to cam
                digital != null -> "Digital ${Dates.formatShortDay(digital)}" to cam
                cinema == null -> null
                cinema > now -> "Not Released · Cinemas ${Dates.formatShortDay(cinema)}" to "Not out yet: any streams are likely fakes or trailers"
                now - cinema < 150 * DAY -> "In Cinemas" to cam
                else -> null
            }
        }
    }

    @Volatile private var digitalIndex: Pair<Long, Map<String, Long>>? = null

    /** When a film comes out in cinemas and on digital: the digital months from eight back to three ahead, and Cinemeta's date */
    suspend fun releaseDates(id: String): ReleaseDates {
        // TMDB's dates (the UK's first) from the sync server, the same the cards' labels go by: Cinemeta's is America's
        // cinema date, which had Madden "not out" for a week after it came out here, Play hidden over real WEB-DLs
        Labels.facts(id)?.takeIf { it.optString("kind") == "movie" }?.let { f ->
            fun day(k: String) = f.optString(k).takeIf { it.length >= 10 && it != "null" }?.let(Dates::parseUtcDay)
            val digital = listOfNotNull(day("digital"), day("physical")).minOrNull()
            val cinema = day("cinema") ?: day("release")
            if (cinema != null || digital != null) return ReleaseDates(cinema, digital)
        }
        return olderReleaseDates(id)
    }

    /** Where there are no TMDB dates: Cinemeta's release date and dvdsreleasedates.com's digital one */
    private suspend fun olderReleaseDates(id: String): ReleaseDates = coroutineScope {
        val cinema = async(Dispatchers.IO) {
            val key = "movie-$id"
            val text = load(key, DAY) ?: fetch("$CINEMETA/meta/movie/$id.json")?.also { save(key, it) }
            runCatching {
                val released = JSONObject(text ?: "").optJSONObject("meta")?.optString("released").orEmpty()
                Dates.parseUtcIso(released)
            }.getOrNull()
        }
        val index = digitalIndex?.takeIf { System.currentTimeMillis() - it.first < 6 * HOUR }?.second ?: run {
            val months = (-8..3).map { d -> Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, d) } }
            val all = months.map { c -> async { runCatching { digital(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1) }.getOrDefault(emptyList()) } }.awaitAll().flatten()
            val map = HashMap<String, Long>()
            for (r in all) map[r.id] = minOf(map[r.id] ?: Long.MAX_VALUE, r.date)
            digitalIndex = System.currentTimeMillis() to map
            map
        }
        ReleaseDates(cinema.await(), index[id])
    }

    /** A dated episode of a show (the Calendar's popular shows) */
    data class Episode(val season: Int, val episode: Int, val time: Long)

    /** A show's dated episodes, from Cinemeta (kept a day): for the popular shows the Calendar shows as well as the Library's */
    suspend fun episodes(id: String): List<Episode> = withContext(Dispatchers.IO) {
        val key = "episodes-$id"
        val text = load(key, DAY) ?: fetch("$CINEMETA/meta/series/$id.json")?.also { save(key, it) } ?: return@withContext emptyList()
        runCatching {
            JSONObject(text).optJSONObject("meta")?.optJSONArray("videos").objects().mapNotNull { v ->
                val s = v.optInt("season")
                val released = v.optString("released").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val t = Dates.parseUtcIso(released) ?: return@mapNotNull null
                if (s > 0) Episode(s, v.optInt("episode"), t) else null
            }
        }.getOrDefault(emptyList())
    }

    /** A later season, part or preview of an anime ("… Season 2", "… 3", "… Final Season", "… PV"): the lists show each show once */
    fun isSequel(name: String): Boolean = SEQUEL.containsMatchIn(name)

    /** One of the Kitsu addon's anime catalogs (kitsu-anime-trending, -airing, -popular, -rating) */
    suspend fun anime(catalog: String): List<Item> =
        catalog("https://anime-kitsu.strem.fun/catalog/anime/$catalog.json", 6 * HOUR)

    /** Cinemeta's best rated titles, of one genre (IMDb's rating) */
    suspend fun topRated(type: String, genre: String): List<Item> =
        catalog("$CINEMETA/catalog/$type/imdbRating/genre=" + URLEncoder.encode(genre, "UTF-8").replace("+", "%20") + ".json", 6 * HOUR, max = 60)

    /**
     * Titles like this one (a "More like …" row). Lumos's engine has no Related of its own (it answers empty), so it's made here:
     * TMDB's recommendations when there's a key (each turned into its IMDb id), else, or when that gives nothing, Cinemeta's
     * popular titles of the same genres, the likeliest first. Never throws: empty only when the internet is down.
     */
    suspend fun related(type: String, id: String, genres: List<String>): List<Title> = withContext(Dispatchers.IO) {
        val kind = if (type == "movie") "movie" else "series"
        val viaTmdb = if (id.startsWith("tt")) runCatching { tmdbRecommendations(kind, id) }.getOrDefault(emptyList()) else emptyList()
        if (viaTmdb.size >= 6) return@withContext viaTmdb.filter { it.id != id }.take(20)
        val own = genres.take(3)
        val pool = runCatching {
            coroutineScope {
                // (the first genre's popular and best rated, and a second genre's popular: enough to pick from, few requests)
                listOfNotNull(
                    async { popular(kind, own.firstOrNull()) },
                    own.firstOrNull()?.let { g -> async { topRated(kind, g) } },
                    own.getOrNull(1)?.let { g -> async { popular(kind, g) } },
                ).awaitAll().flatten()
            }
        }.getOrDefault(emptyList()).map { it.title }.distinctBy { it.id }.filter { it.id != id }
        // (most genres in common first; the ties start at a place that depends on the title, so two titles of a genre don't give the same row)
        val ranked = pool.sortedByDescending { t -> t.genres.count { it in own } }
        val start = if (ranked.size > 20) Math.floorMod(id.hashCode(), ranked.size - 20) else 0
        val picked = ranked.drop(start).take(40).sortedByDescending { t -> t.genres.count { it in own } }.take(20)
        (viaTmdb.filter { it.id != id } + picked).distinctBy { it.id }.take(20)
    }

    /** TMDB's recommendations for an IMDb title, each as a Title with its IMDb id (kept a week); empty without a key */
    private suspend fun tmdbRecommendations(kind: String, imdb: String): List<Title> = coroutineScope {
        val key = com.notdxbi.dxbi.tv.BuildConfig.TMDB_KEY
        if (key.isEmpty()) return@coroutineScope emptyList()
        val cacheKey = "related-$kind-$imdb"
        val kindPath = if (kind == "movie") "movie" else "tv"
        val body = load(cacheKey, 7 * DAY) ?: run {
            val (foundKind, tmdbId) = Tmdb.find(imdb) ?: return@coroutineScope emptyList()
            if (foundKind != kindPath) return@coroutineScope emptyList()
            val recs = fetch("https://api.themoviedb.org/3/$kindPath/$tmdbId/recommendations?api_key=$key") ?: return@coroutineScope emptyList()
            val results = JSONObject(recs).optJSONArray("results").objects().take(18)
            // (a recommendation comes with TMDB's id: Lumos's ids are IMDb's, so each is looked up)
            val rows = results.map { r ->
                async {
                    val ext = fetch("https://api.themoviedb.org/3/$kindPath/${r.optInt("id")}/external_ids?api_key=$key") ?: return@async null
                    val tt = JSONObject(ext).optString("imdb_id").takeIf { it.startsWith("tt") } ?: return@async null
                    JSONObject().put("id", tt).put("name", r.optString(if (kind == "movie") "title" else "name")).put("year", (r.optString(if (kind == "movie") "release_date" else "first_air_date")).take(4))
                }
            }.awaitAll().filterNotNull()
            val text = JSONArray(rows).toString()
            // (a short answer isn't kept: it may only be a bad moment on the network)
            if (rows.size >= 6) save(cacheKey, text)
            text
        }
        JSONArray(body).objects().map { o ->
            Title(id = o.optString("id"), type = kind, name = o.optString("name"), poster = "https://images.metahub.space/poster/medium/${o.optString("id")}/img", year = o.optString("year").takeIf { it.isNotEmpty() })
        }
    }

    /** [max]: only the first so many titles are made (a genre's catalog is hundreds, and the TV's slow to build them) */
    private suspend fun catalog(url: String, keep: Long, max: Int = Int.MAX_VALUE): List<Item> = withContext(Dispatchers.IO) {
        memory[url]?.let { (at, items) -> if (System.currentTimeMillis() - at < keep) return@withContext items }
        val body = load(url, keep) ?: fetch(url)?.also { save(url, it) } ?: return@withContext memory[url]?.second.orEmpty()
        val items = runCatching { JSONObject(body).optJSONArray("metas").objects() }.getOrDefault(emptyList())
            .filter { it.optString("type") in WATCHABLE }
            .take(max)
            .map { meta -> Item(Title.from(meta), meta.optString("released").takeIf { it.isNotEmpty() }?.let(Dates::parseUtcIso)) }
        memory[url] = System.currentTimeMillis() to items
        items
    }

    private val trailers = ConcurrentHashMap<String, String>()

    /**
     * A title's trailer (its YouTube id), from Cinemeta's full details: catalog rows often leave it out.
     * Null when it has none.
     */
    suspend fun trailer(type: String, id: String): String? = withContext(Dispatchers.IO) {
        if (!id.startsWith("tt")) return@withContext null
        trailers["$type/$id"]?.let { return@withContext it.ifEmpty { null } }
        val key = "trailer-$type-$id"
        val body = load(key, 7 * DAY) ?: fetch("$CINEMETA/meta/$type/$id.json")?.also { save(key, it) } ?: return@withContext null
        val meta = runCatching { JSONObject(body).optJSONObject("meta") }.getOrNull()
        val found = meta?.optJSONArray("trailerStreams")?.optJSONObject(0)?.optString("ytId")?.takeIf { it.isNotEmpty() }
            ?: meta?.optJSONArray("trailers").objects().firstOrNull { it.optString("type") == "Trailer" }?.optString("source")?.takeIf { it.isNotEmpty() }
        trailers["$type/$id"] = found.orEmpty()
        found
    }

    /** Every trailer Cinemeta has for a title: its YouTube id and name (the first is the one Home plays) */
    suspend fun trailerList(type: String, id: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        if (!id.startsWith("tt")) return@withContext emptyList()
        val key = "trailer-$type-$id"
        val body = load(key, 7 * DAY) ?: fetch("$CINEMETA/meta/$type/$id.json")?.also { save(key, it) } ?: return@withContext emptyList()
        val meta = runCatching { JSONObject(body).optJSONObject("meta") }.getOrNull() ?: return@withContext emptyList()
        val out = LinkedHashMap<String, String>()
        meta.optJSONArray("trailerStreams").objects().forEach { o ->
            val yt = o.optString("ytId").takeIf { it.isNotEmpty() } ?: return@forEach
            out.putIfAbsent(yt, o.optString("title").takeIf { it.isNotEmpty() } ?: "Trailer")
        }
        meta.optJSONArray("trailers").objects().forEach { o ->
            val yt = o.optString("source").takeIf { it.isNotEmpty() } ?: return@forEach
            out.putIfAbsent(yt, o.optString("type").takeIf { it.isNotEmpty() } ?: "Trailer")
        }
        out.map { it.key to it.value }
    }

    // ---------------------------------------------------------------------------------------------
    // Digital releases

    /**
     * A month of films out on digital (in date order). Read from the site's month page: each film sits
     * under its date and links to IMDb, so there's no guessing which film it is.
     */
    suspend fun digital(year: Int, month: Int): List<Release> = withContext(Dispatchers.IO) {
        val key = "digital-$year-$month"
        val now = Calendar.getInstance()
        val past = year * 12 + month < now.get(Calendar.YEAR) * 12 + now.get(Calendar.MONTH) + 1
        // (a past month doesn't change: kept a week; this one and later ones a day)
        load(key, if (past) 7 * DAY else DAY)?.let { saved ->
            return@withContext runCatching { JSONArray(saved).objects().map { Release(it.optString("id"), it.optString("name"), it.optLong("date")) } }.getOrDefault(emptyList())
        }
        val name = Dates.formatUsMonth(Calendar.getInstance().apply { set(year, month - 1, 1) }.time).lowercase()
        val html = fetch("https://www.dvdsreleasedates.com/digital-releases/$year/$month/digital-hd-releases-$name-$year") ?: return@withContext emptyList()
        val releases = parseDigital(html)
        if (releases.isNotEmpty()) {
            save(key, JSONArray(releases.map { JSONObject().put("id", it.id).put("name", it.name).put("date", it.date) }).toString())
        }
        releases
    }

    /** The site's page: dates as headings ("Tuesday November 3, 2026"), then each film with its IMDb link */
    private fun parseDigital(html: String): List<Release> {
        val token = Regex(
            """class='reldate[^']*'>(?:<a[^>]*></a>)?([A-Za-z]+ [A-Za-z]+ \d{1,2}, \d{4})|<a style='color:#000;' href='/movies/\d+/[^']*'>([^<]+)</a>.{0,400}?title/(tt\d+)/""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val out = mutableListOf<Release>()
        val seen = mutableSetOf<String>()
        var date: Long? = null
        for (m in token.findAll(html)) {
            val day = m.groups[1]?.value
            if (day != null) {
                date = Dates.parseUsFullDate(day)
                continue
            }
            val name = m.groups[2]?.value ?: continue
            val id = m.groups[3]?.value ?: continue
            val d = date ?: continue
            if (seen.add(id)) out += Release(id, Html.fromHtml(name, Html.FROM_HTML_MODE_LEGACY).toString(), d)
        }
        return out
    }

    /** This year's releases so far (and the next month's), newest first */
    suspend fun digitalYear(): List<Release> = coroutineScope {
        val now = Calendar.getInstance()
        val year = now.get(Calendar.YEAR)
        val month = now.get(Calendar.MONTH) + 1
        // (the coming month too: its films are "Coming to Digital")
        val all = (1..minOf(month + 1, 12)).map { m -> async { digital(year, m) } }.awaitAll().flatten()
        val seen = mutableSetOf<String>()
        all.sortedByDescending { it.date }.filter { seen.add(it.id) }
    }

    // ---------------------------------------------------------------------------------------------
    // Christmas at Home

    /** Nov 15 through Dec 26 inclusive */
    fun christmasInSeason(now: Calendar = Calendar.getInstance()): Boolean {
        val m = now.get(Calendar.MONTH)
        val d = now.get(Calendar.DAY_OF_MONTH)
        return (m == Calendar.NOVEMBER && d >= 15) || (m == Calendar.DECEMBER && d <= 26)
    }

    private fun daysToChristmas(now: Calendar = Calendar.getInstance()): Int {
        val today = Calendar.getInstance().apply {
            timeInMillis = now.timeInMillis
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val target = Calendar.getInstance().apply {
            set(today.get(Calendar.YEAR), Calendar.DECEMBER, 25, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis < today.timeInMillis) add(Calendar.YEAR, 1)
        }
        return maxOf(0, kotlin.math.ceil((target.timeInMillis - today.timeInMillis) / 86_400_000.0).toInt())
    }

    private fun normTitle(s: String): String = s.lowercase(Locale.US).replace(NON_ALNUM, "")

    private fun cinemetaTitle(m: JSONObject, type: String): Title {
        val id = m.optString("imdb_id").ifEmpty { m.optString("id") }
        val genres = (m.optJSONArray("genres") ?: m.optJSONArray("genre"))?.let { a ->
            (0 until a.length()).mapNotNull { a.optString(it).takeIf { g -> g.isNotEmpty() } }.take(3)
        }.orEmpty()
        return Title(
            id = id,
            type = type,
            name = m.optString("name"),
            poster = m.optString("poster").takeIf { it.isNotEmpty() && it != "null" },
            background = m.optString("background").takeIf { it.isNotEmpty() && it != "null" },
            description = m.optString("description").takeIf { it.isNotEmpty() && it != "null" },
            year = m.optString("releaseInfo").takeIf { it.isNotEmpty() && it != "null" },
            genres = genres,
            imdb = m.optString("imdbRating").takeIf { it.isNotEmpty() && it != "null" },
        )
    }

    private suspend fun findCinemeta(type: String, name: String, year: Int?): Title? = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val body = fetch("$CINEMETA/catalog/$type/top/search=$q.json") ?: return@withContext null
        val metas = runCatching { JSONObject(body).optJSONArray("metas").objects() }.getOrDefault(emptyList())
        val want = normTitle(name)
        val same = metas.filter { normTitle(it.optString("name")) == want }
        val hit = (year?.let { y -> same.firstOrNull { it.optString("releaseInfo").startsWith(y.toString()) } }) ?: same.firstOrNull() ?: return@withContext null
        cinemetaTitle(hit, type).takeIf { it.id.isNotEmpty() }
    }

    /** TMDB's own "christmas" keyword id, looked up once a session (207317 is its long-standing id, the fallback) */
    @Volatile private var christmasKeyword: Int? = null

    private fun christmasKeywordId(key: String): Int {
        christmasKeyword?.let { return it }
        val id = fetch("https://api.themoviedb.org/3/search/keyword?query=christmas&page=1&api_key=$key")?.let {
            runCatching {
                JSONObject(it).optJSONArray("results").objects().firstOrNull { k -> k.optString("name").equals("christmas", ignoreCase = true) }?.optInt("id")
            }.getOrNull()
        } ?: 207317
        christmasKeyword = id
        return id
    }

    /**
     * A TMDB discover of Christmas films ([extra] adds its filters, [sort] orders them), each turned into a Cinemeta-style
     * title through its IMDb id (the engine knows titles by that). The ids are asked for at once, not one after another.
     */
    private suspend fun tmdbChristmas(extra: String, sort: String, limit: Int): List<Title> = coroutineScope {
        val key = com.notdxbi.dxbi.tv.BuildConfig.TMDB_KEY.trim()
        if (key.isEmpty()) return@coroutineScope emptyList()
        val kwId = christmasKeywordId(key)
        val discBody = fetch("https://api.themoviedb.org/3/discover/movie?with_keywords=$kwId&sort_by=$sort&include_adult=false&language=en-US&page=1&api_key=$key&$extra")
            ?: return@coroutineScope emptyList()
        val picks = runCatching { JSONObject(discBody).optJSONArray("results").objects() }.getOrDefault(emptyList()).take(limit)
        picks.map { m ->
            async(Dispatchers.IO) {
                val tt = imdbOf(key, m.optInt("id")) ?: return@async null
                Title(
                    id = tt,
                    type = "movie",
                    name = m.optString("title").ifEmpty { m.optString("name") },
                    poster = "https://images.metahub.space/poster/medium/$tt/img",
                    background = "https://images.metahub.space/background/medium/$tt/img",
                    description = m.optString("overview").takeIf { it.isNotEmpty() },
                    year = m.optString("release_date").take(4).takeIf { it.isNotEmpty() },
                )
            }
        }.awaitAll().filterNotNull().distinctBy { it.id }
    }

    /** A TMDB film's IMDb id (kept a week on disk: the same few dozen films come up in every row, and every visit) */
    private fun imdbOf(key: String, tmdbId: Int): String? {
        val cacheKey = "tmdb-imdb-$tmdbId"
        val body = load(cacheKey, 30 * DAY) ?: fetch("https://api.themoviedb.org/3/movie/$tmdbId/external_ids?api_key=$key")?.also { save(cacheKey, it) } ?: return null
        return runCatching { JSONObject(body).optString("imdb_id") }.getOrDefault("").takeIf { it.startsWith("tt") }
    }

    private fun serializeRows(rows: List<ListRow>): String = JSONArray(rows.map { r ->
        JSONObject().put("name", r.name).put("items", JSONArray(r.items.map { t ->
            JSONObject().put("id", t.id).put("type", t.type).put("name", t.name)
                .put("poster", t.poster ?: JSONObject.NULL)
                .put("background", t.background ?: JSONObject.NULL)
                .put("description", t.description ?: JSONObject.NULL)
                .put("year", t.year ?: JSONObject.NULL)
                .put("imdb", t.imdb ?: JSONObject.NULL)
                .put("genres", JSONArray(t.genres))
        }))
    }).toString()

    private fun parseRows(saved: String): List<ListRow> = runCatching {
        JSONArray(saved).objects().map { r -> ListRow(r.optString("name"), r.optJSONArray("items").objects().map(Title::from)) }.filter { it.items.isNotEmpty() }
    }.getOrDefault(emptyList())

    /**
     * Christmas at Home, each row handed over as soon as it's in ([onUpdate] gets every row so far, in page order, with the
     * empty ones left out), all the rows asked for at once. The finished page is kept for the session and for six hours on
     * disk, so the next visit is instant. Returns false when nothing at all came back.
     */
    suspend fun christmasRows(onUpdate: (Christmas) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val now = Calendar.getInstance()
        val inSeason = christmasInSeason(now)
        val days = daysToChristmas(now)
        fun page(rows: List<ListRow>) = Christmas(daysToChristmas = days, inSeason = inSeason, rows = rows)

        christmasMemory?.let { (at, rows) ->
            if (System.currentTimeMillis() - at < CHRISTMAS_KEEP) { onUpdate(page(rows)); return@withContext true }
        }
        load("christmas-rows-v2", CHRISTMAS_KEEP)?.let(::parseRows)?.takeIf { it.isNotEmpty() }?.let { rows ->
            christmasMemory = System.currentTimeMillis() to rows
            onUpdate(page(rows))
            return@withContext true
        }

        val year = now.get(Calendar.YEAR)
        // (the page's order: this and last year's films first, then the romances, then the curated lists and the rest)
        val slots = arrayOfNulls<ListRow>(CHRISTMAS_LISTS.size + CHRISTMAS_TMDB.size)
        val lock = Any()
        fun publish() {
            // (a title shows once, in the first row that has it; a row it empties is dropped)
            val seen = mutableSetOf<String>()
            val rows = synchronized(lock) { slots.filterNotNull() }.mapNotNull { r ->
                val items = r.items.filter { seen.add(it.id) }
                if (items.isEmpty()) null else ListRow(r.name, items)
            }
            if (rows.isNotEmpty()) onUpdate(page(rows))
        }
        coroutineScope {
            CHRISTMAS_TMDB.forEachIndexed { i, spec ->
                launch {
                    val items = runCatching { tmdbChristmas(spec.filter(year), spec.sort, 20) }.getOrDefault(emptyList())
                    synchronized(lock) { slots[i] = ListRow(spec.name, items) }
                    publish()
                }
            }
            CHRISTMAS_LISTS.forEachIndexed { i, list ->
                launch {
                    val items = list.titles.map { (name, y) -> async { findCinemeta(list.type, name, y) } }.awaitAll().filterNotNull()
                    synchronized(lock) { slots[CHRISTMAS_TMDB.size + i] = ListRow(list.name, items) }
                    publish()
                }
            }
        }
        val rows = synchronized(lock) { slots.filterNotNull() }
        val seen = mutableSetOf<String>()
        val final = rows.mapNotNull { r -> r.items.filter { seen.add(it.id) }.takeIf { it.isNotEmpty() }?.let { ListRow(r.name, it) } }
        if (final.isEmpty()) return@withContext false
        christmasMemory = System.currentTimeMillis() to final
        save("christmas-rows-v2", serializeRows(final))
        true
    }

    // ---------------------------------------------------------------------------------------------
    // The internet, and what's kept on the TV (the cache folder: Android may clear it, and it's fetched again)

    private fun fetch(url: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV; Lumen)")
        try {
            if (c.responseCode !in 200..299) null else c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    private fun file(key: String) = File(folder, key.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("").takeLast(120) + ".json")

    private fun save(key: String, text: String) {
        runCatching { file(key).writeText(text) }
    }

    private fun load(key: String, keep: Long): String? {
        val f = file(key)
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > keep) return null
        return runCatching { f.readText() }.getOrNull()
    }
}
