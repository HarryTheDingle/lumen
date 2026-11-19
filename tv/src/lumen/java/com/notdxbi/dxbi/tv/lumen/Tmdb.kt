package com.notdxbi.dxbi.tv.lumen

import com.notdxbi.dxbi.tv.BuildConfig
import com.notdxbi.dxbi.tv.phone.objects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * The cast and people pages' data, straight from TMDB with the key built in (BuildConfig.TMDB_KEY). Lumen asked its
 * engine plugin for these (phone.cast, phone.person); Lumos's engine doesn't answer them, which is why the photos
 * never came and a person's page was never reached. Every call returns null (or empty) rather than throwing.
 */
object Tmdb {
    private const val API = "https://api.themoviedb.org/3"
    private const val IMAGES = "https://image.tmdb.org/t/p"

    private suspend fun get(path: String, query: String = ""): JSONObject? = withContext(Dispatchers.IO) {
        val key = BuildConfig.TMDB_KEY.takeIf { it.isNotBlank() } ?: return@withContext null
        runCatching {
            val c = URL("$API$path?api_key=$key&language=en-US$query").openConnection() as HttpURLConnection
            c.connectTimeout = 8_000; c.readTimeout = 12_000
            try { if (c.responseCode == 200) JSONObject(c.inputStream.bufferedReader().readText()) else null } finally { c.disconnect() }
        }.getOrNull()
    }

    private val found = ConcurrentHashMap<String, Any>()
    private val MISS = Any()

    private fun JSONObject.str(k: String): String? = optString(k).takeIf { it.isNotBlank() && it != "null" }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun face(path: String?) = path?.let { "$IMAGES/w342$it" }
    private fun kind(type: String) = if (type == "movie") "movie" else "tv"

    /** TMDB's own id for an IMDb one: (the kind, the id) */
    @Suppress("UNCHECKED_CAST")
    internal suspend fun find(imdb: String): Pair<String, Int>? {
        found[imdb]?.let { return if (it === MISS) null else it as Pair<String, Int> }
        val r = get("/find/$imdb", "&external_source=imdb_id") ?: return null
        val hit = r.optJSONArray("movie_results").objects().firstOrNull()?.let { "movie" to it.optInt("id") }
            ?: r.optJSONArray("tv_results").objects().firstOrNull()?.let { "tv" to it.optInt("id") }
        found[imdb] = hit ?: MISS
        return hit
    }

    /** The cast row: [{name, role, photo, id}], the directors first then the cast (a name TMDB doesn't list keeps no photo). null without a key or a match */
    suspend fun cast(type: String, imdb: String, names: JSONArray): List<JSONObject>? {
        val (k, id) = find(imdb) ?: return null
        val credits = get("/$k/$id/credits") ?: return null
        val actors = credits.optJSONArray("cast").objects().associateBy { it.optString("name").lowercase() }
        val crew = credits.optJSONArray("crew").objects().filter { it.optString("job") == "Director" }.associateBy { it.optString("name").lowercase() }
        return names.objects().map { n ->
            val name = n.optString("name")
            val role = n.optString("role")
            val hit = (if (role == "Director") crew[name.lowercase()] ?: actors[name.lowercase()] else actors[name.lowercase()] ?: crew[name.lowercase()])
            JSONObject().put("name", name)
                .put("role", role.ifEmpty { hit?.str("character") ?: "" })
                .put("photo", face(hit?.str("profile_path")) ?: JSONObject.NULL)
                .put("id", hit?.optInt("id")?.takeIf { it > 0 }?.toString() ?: JSONObject.NULL)
        }
    }

    /**
     * A series' episodes as TMDB has them, for the seasons asked: "season:episode" → {title, thumbnail, overview}, for a
     * show whose addon gives only "Episode 1", "Episode 2" and the show's own picture. Empty when TMDB hasn't it
     */
    suspend fun episodes(imdb: String, seasons: Collection<Int>): Map<String, JSONObject> {
        val (k, id) = find(imdb) ?: return emptyMap()
        if (k != "tv") return emptyMap()
        val out = HashMap<String, JSONObject>()
        for (season in seasons.distinct().take(12)) {
            val j = get("/tv/$id/season/$season") ?: continue
            for (e in j.optJSONArray("episodes").objects()) {
                val n = e.optInt("episode_number").takeIf { it > 0 } ?: continue
                out["$season:$n"] = JSONObject()
                    .put("title", e.str("name") ?: JSONObject.NULL)
                    .put("thumbnail", e.str("still_path")?.let { "$IMAGES/w500$it" } ?: JSONObject.NULL)
                    .put("overview", e.str("overview") ?: JSONObject.NULL)
            }
        }
        return out
    }

    /**
     * TMDB's id for someone known only by name: the one whose known-for list has this title (when its IMDb id is
     * given), else the most popular of that name. null when there's no one of that name
     */
    suspend fun personId(name: String, imdb: String?): String? {
        val results = get("/search/person", "&query=${enc(name)}")?.optJSONArray("results").objects()
        if (results.isEmpty()) return null
        val here = imdb?.let { find(it)?.second }
        val exact = results.filter { it.optString("name").equals(name, ignoreCase = true) }.ifEmpty { results }
        val known = here?.let { t -> exact.firstOrNull { p -> p.optJSONArray("known_for").objects().any { it.optInt("id") == t } } }
        return (known ?: exact.first()).optInt("id").toString()
    }

    /**
     * Someone's page as PersonPage reads it: {name, photo, bio, department, born, died, place, age, known, films, shows},
     * the three lists being titles ({id, type, name, poster, year}) by their IMDb ids (the ones TMDB can't give one are left out).
     * By TMDB's id, else by name (the first match); null when nobody's found
     */
    suspend fun person(id: String?, name: String): JSONObject? {
        val pid = id?.takeIf { it.isNotBlank() } ?: personId(name, null) ?: return null
        val p = get("/person/$pid") ?: return null
        val credits = get("/person/$pid/combined_credits")
        val out = JSONObject()
            .put("name", p.str("name") ?: name)
            .put("photo", face(p.str("profile_path")) ?: JSONObject.NULL)
            .put("bio", p.str("biography") ?: JSONObject.NULL)
            .put("department", p.str("known_for_department") ?: JSONObject.NULL)
            .put("born", p.str("birthday") ?: JSONObject.NULL)
            .put("died", p.str("deathday") ?: JSONObject.NULL)
            .put("place", p.str("place_of_birth") ?: JSONObject.NULL)
        p.str("birthday")?.takeIf { p.str("deathday") == null }?.let { b ->
            runCatching {
                val born = java.time.LocalDate.parse(b)
                out.put("age", java.time.Period.between(born, java.time.LocalDate.now()).years)
            }
        }
        // (the most talked-about first; a person can have hundreds of credits, so only the top few get an IMDb lookup)
        val credited = credits?.let { c -> c.optJSONArray("cast").objects() + c.optJSONArray("crew").objects().filter { it.optString("job") == "Director" } }.orEmpty()
            .filter { it.str("media_type") == "movie" || it.str("media_type") == "tv" }
            .filter { it.str("poster_path") != null }
            // (what they're in, not where they turned up as themselves: talk shows, news, reality, award shows, a voice
            // cameo as "Himself": Daniel Radcliffe's Known For was The Kelly Clarkson Show and The Daily Show)
            .filter { c -> c.optJSONArray("genre_ids").let { g -> (0 until (g?.length() ?: 0)).none { g!!.optInt(it) in setOf(10767, 10763, 10764, 99) } } }
            .filter { c -> !Regex("""(?i)^(self|himself|herself|themselves|narrator|host|guest)\b""").containsMatchIn(c.optString("character")) }
            .filter { c -> c.optString("media_type") != "tv" || c.optInt("episode_count", 99) >= 3 || c.optString("job") == "Director" }
            .distinctBy { it.optString("media_type") + it.optInt("id") }
            // (what most people know them for: well voted and popular, so a long-running guest spot doesn't lead)
            .sortedByDescending { it.optDouble("popularity", 0.0) * kotlin.math.ln(2.0 + it.optInt("vote_count", 0)) }
            .take(36)
        val titles = coroutineScope {
            credited.map { c ->
                async {
                    val k = c.optString("media_type")
                    val imdb = get("/$k/${c.optInt("id")}/external_ids")?.str("imdb_id") ?: return@async null
                    val date = c.str("release_date") ?: c.str("first_air_date")
                    JSONObject().put("id", imdb).put("type", if (k == "movie") "movie" else "series")
                        .put("name", c.str("title") ?: c.str("name") ?: "")
                        .put("poster", face(c.str("poster_path")) ?: JSONObject.NULL)
                        .put("year", date?.take(4) ?: JSONObject.NULL)
                }
            }.awaitAll().filterNotNull()
        }
        fun arr(l: List<JSONObject>) = JSONArray().also { a -> l.forEach { a.put(it) } }
        return out.put("known", arr(titles.take(10)))
            .put("films", arr(titles.filter { it.optString("type") == "movie" }))
            .put("shows", arr(titles.filter { it.optString("type") != "movie" }))
    }
}
