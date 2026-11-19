package lumos.engine

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/** One part of a video the player offers to skip: "intro", "recap", "credits" or "preview"; [end] null: to the end */
data class Skip(val type: String, val start: Double, val end: Double?)

/**
 * Skip Intro, Skip Recap and the credits' start, as Lumen's plugin found them (skip.fetchSegments): TheIntroDB for
 * IMDb and TMDB ids, AniSkip for anime (Kitsu ids, through their MyAnimeList number). Kept per video once found.
 */
class Skips(private val http: HttpClient) {
    private val found = mutableMapOf<String, List<Skip>>()
    private val malIds = mutableMapOf<String, Int?>()

    suspend fun of(type: String, metaId: String, videoId: String): List<Skip> {
        found[videoId]?.let { return it }
        val list = withTimeoutOrNull(10_000) { runCatching { fetch(type, metaId, videoId) }.getOrNull() } ?: return emptyList()
        found[videoId] = list
        return list
    }

    private suspend fun fetch(type: String, id: String, videoId: String): List<Skip> {
        val parts = videoId.split(":")
        Regex("""^kitsu:(\d+)$""").find(id)?.let { m ->
            val mal = malId(m.groupValues[1]) ?: return emptyList()
            return aniSkip(mal, parts.getOrNull(2)?.toIntOrNull() ?: 1)
        }
        var query = when {
            Regex("""^tt\d+$""").matches(id) -> "imdb_id=$id"
            Regex("""^tmdb:\d+$""").matches(id) -> "tmdb_id=${id.removePrefix("tmdb:")}"
            else -> return emptyList()
        }
        if (type == "series") {
            // (series video ids end in :season:episode: tt0903747:1:2, tmdb:1396:1:2)
            val season = parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: return emptyList()
            val episode = parts.lastOrNull()?.toIntOrNull() ?: return emptyList()
            query += "&season=$season&episode=$episode"
        }
        val o = getJson("https://api.theintrodb.org/v3/media?$query") ?: return emptyList()
        val out = mutableListOf<Skip>()
        for (kind in listOf("intro", "recap", "credits", "preview")) {
            for (seg in o[kind].arr().mapNotNull { it.obj() }) {
                val start = (seg.double("start_ms") ?: 0.0) / 1000
                val end = seg.double("end_ms")?.div(1000)
                if (end == null || end > start + 1) out += Skip(kind, start, end)
            }
        }
        return out
    }

    private suspend fun malId(kitsu: String): Int? {
        if (kitsu in malIds) return malIds[kitsu]
        val o = getJson("https://kitsu.app/api/edge/anime/$kitsu/mappings?filter[externalSite]=myanimelist/anime") ?: return null
        val mal = o["data"].arr().firstOrNull()?.obj()?.get("attributes").obj()?.str("externalId")?.toIntOrNull()
        malIds[kitsu] = mal
        return mal
    }

    private suspend fun aniSkip(mal: Int, episode: Int): List<Skip> {
        val types = listOf("op", "ed", "recap", "mixed-op", "mixed-ed").joinToString("&") { "types[]=$it" }
        // (404: nobody has sent times for this episode yet)
        val o = getJson("https://api.aniskip.com/v2/skip-times/$mal/$episode?$types&episodeLength=0") ?: return emptyList()
        val kind = mapOf("op" to "intro", "mixed-op" to "intro", "ed" to "credits", "mixed-ed" to "credits", "recap" to "recap")
        return o["results"].arr().mapNotNull { it.obj() }.mapNotNull { x ->
            val k = kind[x.str("skipType")] ?: return@mapNotNull null
            val i = x["interval"].obj() ?: return@mapNotNull null
            val start = i.double("startTime") ?: return@mapNotNull null
            val end = i.double("endTime") ?: return@mapNotNull null
            Skip(k, start, end).takeIf { end > start + 1 }
        }
    }

    private suspend fun getJson(url: String): JsonObject? {
        val r = http.get(url)
        if (!r.status.isSuccess()) return null
        return json.parseToJsonElement(r.bodyAsText()).obj()
    }
}
