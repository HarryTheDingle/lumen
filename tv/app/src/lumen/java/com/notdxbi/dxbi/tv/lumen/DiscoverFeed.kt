package com.notdxbi.dxbi.tv.lumen

import android.text.Html
import android.util.Base64
import androidx.compose.ui.graphics.Color
import com.notdxbi.dxbi.tv.phone.Title
import com.notdxbi.dxbi.tv.phone.objects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * Discover's rows, from the internet directly (not through Stremio's page), as the iPad has them
 * (ios/Dxbi/Engine/DiscoverFeed.swift): what's on each streaming service (the free Streaming Catalogs
 * addon, for this country), Cinemeta's popular and genre catalogs, and the films out on digital this year
 * (dvdsreleasedates.com, which links each to IMDb). Kept for a while on the TV so Discover opens at once.
 */
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
        private const val HOUR = 3_600_000L
        private const val DAY = 24 * HOUR
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

    /** A later season, part or preview of an anime ("… Season 2", "… 3", "… Final Season", "… PV"): the lists show each show once */
    fun isSequel(name: String): Boolean = SEQUEL.containsMatchIn(name)

    /** One of the Kitsu addon's anime catalogs (kitsu-anime-trending, -airing, -popular, -rating) */
    suspend fun anime(catalog: String): List<Item> =
        catalog("https://anime-kitsu.strem.fun/catalog/anime/$catalog.json", 6 * HOUR)

    /** Cinemeta's best rated titles, of one genre (IMDb's rating) */
    suspend fun topRated(type: String, genre: String): List<Item> =
        catalog("$CINEMETA/catalog/$type/imdbRating/genre=" + URLEncoder.encode(genre, "UTF-8").replace("+", "%20") + ".json", 6 * HOUR, max = 60)

    /** [max]: only the first so many titles are made (a genre's catalog is hundreds, and the TV's slow to build them) */
    private suspend fun catalog(url: String, keep: Long, max: Int = Int.MAX_VALUE): List<Item> = withContext(Dispatchers.IO) {
        memory[url]?.let { (at, items) -> if (System.currentTimeMillis() - at < keep) return@withContext items }
        val body = load(url, keep) ?: fetch(url)?.also { save(url, it) } ?: return@withContext memory[url]?.second.orEmpty()
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val items = runCatching { JSONObject(body).optJSONArray("metas").objects() }.getOrDefault(emptyList())
            .filter { it.optString("type") in WATCHABLE }
            .take(max)
            .map { meta -> Item(Title.from(meta), meta.optString("released").takeIf { it.isNotEmpty() }?.let { runCatching { iso.parse(it)?.time }.getOrNull() }) }
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
        val name = SimpleDateFormat("MMMM", Locale.US).format(Calendar.getInstance().apply { set(year, month - 1, 1) }.time).lowercase()
        val html = fetch("https://www.dvdsreleasedates.com/digital-releases/$year/$month/digital-hd-releases-$name-$year") ?: return@withContext emptyList()
        val releases = parseDigital(html)
        if (releases.isNotEmpty()) {
            save(key, JSONArray(releases.map { JSONObject().put("id", it.id).put("name", it.name).put("date", it.date) }).toString())
        }
        releases
    }

    /** The site's page: dates as headings ("Tuesday November 3, 2026"), then each film with its IMDb link */
    private fun parseDigital(html: String): List<Release> {
        val dateFormat = SimpleDateFormat("EEEE MMMM d, yyyy", Locale.US)
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
                date = runCatching { dateFormat.parse(day)?.time }.getOrNull()
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
