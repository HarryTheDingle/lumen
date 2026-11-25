package com.notdxbi.dxbi.tv.lumen

import com.notdxbi.dxbi.tv.BuildConfig
import com.notdxbi.dxbi.tv.phone.Title
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

    private val logos = ConcurrentHashMap<String, Any>()

    /**
     * The sharpest logo TMDB has for a title (its name as drawn on the poster), at the size it was uploaded: Metahub's and the
     * catalogue's are small, and blur on a 4K screen. The English ones first, then the best liked, then the widest (a
     * vector one isn't taken: the picture loader can't draw it). null without a key, a match, or a logo
     */
    suspend fun logo(imdb: String): String? {
        logos[imdb]?.let { return if (it === MISS) null else it as String }
        val (k, id) = find(imdb) ?: return null
        val r = get("/$k/$id/images", "&include_image_language=en,null") ?: return null
        val best = r.optJSONArray("logos").objects()
            .filter { it.str("file_path")?.endsWith(".svg", ignoreCase = true) == false }
            .sortedWith(
                compareByDescending<JSONObject> { it.optString("iso_639_1") == "en" }
                    .thenByDescending { it.optDouble("vote_average", 0.0) }
                    .thenByDescending { it.optInt("width") },
            )
            .firstOrNull()
        val url = best?.str("file_path")?.let { "$IMAGES/original$it" }
        logos[imdb] = url ?: MISS
        return url
    }

    /**
     * The whole Cast & Crew row as TMDB has it: [{name, role, photo, id}], the directors first, then the writers, then the cast in
     * billing order with their parts (the catalogue's own list is three or four names). null without a key or a match, or with no one
     */
    suspend fun credits(imdb: String): List<JSONObject>? {
        val (k, id) = find(imdb) ?: return null
        val c = get("/$k/$id/credits") ?: return null
        fun person(o: JSONObject, role: String) = JSONObject().put("name", o.optString("name")).put("role", role)
            .put("photo", face(o.str("profile_path")) ?: JSONObject.NULL)
            .put("id", o.optInt("id").takeIf { it > 0 }?.toString() ?: JSONObject.NULL)
        val crew = c.optJSONArray("crew").objects()
        val writing = setOf("Screenplay", "Writer", "Story", "Novel", "Author", "Creator", "Teleplay")
        val directors = crew.filter { it.optString("job") == "Director" }.map { it to "Director" }.take(3)
        val writers = crew.filter { it.optString("job") in writing }.map { it to "Writer" }.take(3)
        val cast = c.optJSONArray("cast").objects().sortedBy { it.optInt("order", 999) }.take(20).map { it to it.str("character").orEmpty() }
        val seen = HashSet<Int>()
        val out = (directors + writers + cast).filter { (o, _) -> o.str("name") != null && seen.add(o.optInt("id")) }.map { (o, role) -> person(o, role) }
        return out.ifEmpty { null }
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

    // ---------------------------------------------------------------------------------------------
    // Onboarding's picks

    /** TMDB's genre ids as the names Cinemeta uses (what a chosen title's genres are kept as, so "more like it" finds its catalog) */
    private val GENRE_NAMES = mapOf(
        28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime", 99 to "Documentary", 18 to "Drama",
        10751 to "Family", 14 to "Fantasy", 36 to "History", 27 to "Horror", 9648 to "Mystery", 10749 to "Romance", 878 to "Sci-Fi",
        53 to "Thriller", 10752 to "War", 37 to "Western", 10759 to "Action", 10765 to "Sci-Fi", 10768 to "War",
    )

    private val picksCache = ConcurrentHashMap<String, List<Title>>()

    /**
     * Well-known titles for "pick a few you've enjoyed": for each genre asked (TMDB's ids, films or series) the most
     * popular and the best loved of those with plenty of votes (so they're recognisable, and from every decade, not only
     * this year's), taking turns between genres, films and series so no one kind crowds the rest. Each comes with its
     * IMDb id (Lumos's ids) and TMDB's own poster; empty without a key or the network, when the caller falls back.
     * [movieGenres] and [seriesGenres] are one list per chosen genre (TMDB lets a list of ids stand for "any of these").
     */
    suspend fun picks(movieGenres: List<List<Int>>, seriesGenres: List<List<Int>>, limit: Int = 20): List<Title> {
        if (BuildConfig.TMDB_KEY.isBlank()) return emptyList()
        val cacheKey = "m$movieGenres s$seriesGenres $limit"
        picksCache[cacheKey]?.let { return it }
        class Raw(val tmdb: Int, val kind: String, val o: JSONObject)
        val lists = coroutineScope {
            val jobs = ArrayList<kotlinx.coroutines.Deferred<List<Raw>>>()
            for ((kind, groups) in listOf("movie" to movieGenres, "tv" to seriesGenres)) {
                // (no genre chosen at all: one list of each kind with no filter)
                for (g in groups.ifEmpty { listOf(emptyList()) }) {
                    // (a series needs fewer votes than a film to be as well known)
                    val votes = if (kind == "movie") 3000 else 1200
                    val genre = if (g.isEmpty()) "" else "&with_genres=" + g.joinToString("|")
                    for ((sort, extra) in listOf("popularity.desc" to "&vote_count.gte=$votes", "vote_average.desc" to "&vote_count.gte=${votes * 2}&vote_average.gte=7.4")) {
                        for (page in 1..2) jobs.add(async {
                            get("/discover/$kind", "&sort_by=$sort&include_adult=false&page=$page$genre$extra")
                                ?.optJSONArray("results").objects()
                                .filter { it.str("poster_path") != null }
                                .map { Raw(it.optInt("id"), kind, it) }
                        })
                    }
                }
            }
            jobs.awaitAll()
        }
        // (turns across every list, a film then a series from each genre; the most popular and best loved of one genre alternate)
        val seen = HashSet<Int>()
        val pool = ArrayList<Raw>()
        for (i in 0 until (lists.maxOfOrNull { it.size } ?: 0)) for (l in lists) if (i < l.size && seen.add(l[i].tmdb * 2 + if (l[i].kind == "movie") 0 else 1)) pool.add(l[i])
        val titles = coroutineScope {
            pool.take(limit + limit / 2).map { r ->
                async {
                    val imdb = get("/${r.kind}/${r.tmdb}/external_ids")?.str("imdb_id")?.takeIf { it.startsWith("tt") } ?: return@async null
                    val date = r.o.str("release_date") ?: r.o.str("first_air_date")
                    // (their genres, the chosen ones come first only by chance of TMDB's order: the first is what "Because you liked" looks up)
                    val genres = r.o.optJSONArray("genre_ids")?.let { a -> (0 until a.length()).mapNotNull { GENRE_NAMES[a.optInt(it)] } }.orEmpty().distinct()
                    Title(id = imdb, type = if (r.kind == "movie") "movie" else "series", name = r.o.str("title") ?: r.o.str("name") ?: return@async null, poster = face(r.o.str("poster_path")), year = date?.take(4), genres = genres)
                }
            }.awaitAll().filterNotNull()
        }
        val out = titles.distinctBy { it.id }.take(limit)
        if (out.size >= 8) picksCache[cacheKey] = out
        return out
    }
}
