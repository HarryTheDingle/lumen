package lumos.engine

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One catalog an addon offers (a Home or Discover row) */
data class Catalog(val type: String, val id: String, val name: String, val extras: List<String>, val requiredExtras: List<String>)

/** What an addon says it can do, from its manifest.json */
data class Manifest(
    val id: String,
    val name: String,
    val version: String?,
    val types: List<String>,
    val idPrefixes: List<String>,
    val catalogs: List<Catalog>,
    /** resource name → the types and id prefixes it answers for (empty: the manifest's own) */
    val resources: Map<String, Pair<List<String>, List<String>>>,
) {
    /** Whether this addon can answer [resource] for a title of [type] with [id] */
    fun answers(resource: String, type: String, id: String): Boolean {
        val (types, prefixes) = resources[resource] ?: return false
        val t = types.ifEmpty { this.types }
        val p = prefixes.ifEmpty { idPrefixes }
        return (t.isEmpty() || type in t) && (p.isEmpty() || p.any { id.startsWith(it) })
    }

    companion object {
        fun parse(o: JsonObject): Manifest {
            val resources = mutableMapOf<String, Pair<List<String>, List<String>>>()
            for (r in o["resources"].arr()) when (r) {
                is JsonPrimitive -> resources[r.content] = emptyList<String>() to emptyList()
                is JsonObject -> r.str("name")?.let { resources[it] = r.strings("types") to r.strings("idPrefixes") }
                else -> {}
            }
            val catalogs = o["catalogs"].arr().mapNotNull { it.obj() }.mapNotNull { c ->
                val extra = c["extra"].arr().mapNotNull { it.obj() }
                Catalog(
                    type = c.str("type") ?: return@mapNotNull null,
                    id = c.str("id") ?: return@mapNotNull null,
                    name = c.str("name") ?: c.str("id")!!,
                    extras = extra.mapNotNull { it.str("name") } + c.strings("extraSupported"),
                    requiredExtras = extra.filter { it.bool("isRequired") == true }.mapNotNull { it.str("name") } + c.strings("extraRequired"),
                )
            }
            return Manifest(
                id = o.str("id") ?: "",
                name = o.str("name") ?: o.str("id") ?: "Addon",
                version = o.str("version"),
                types = o.strings("types"),
                idPrefixes = o.strings("idPrefixes"),
                catalogs = catalogs,
                resources = resources,
            )
        }
    }
}

/**
 * An addon spoken to directly over HTTP: the Stremio addon format (manifest, catalog, meta, stream),
 * with no Stremio app in between.
 *
 * [url] is the addon's manifest link as people paste it (`https://…/manifest.json` or `stremio://…`).
 */
class Addon(url: String, private val http: HttpClient) {
    val base: String = url.trim()
        .let { if (it.startsWith("stremio://")) "https://" + it.removePrefix("stremio://") else it }
        .removeSuffix("/manifest.json").trimEnd('/')

    private var manifest: Manifest? = null

    suspend fun manifest(): Manifest = manifest ?: Manifest.parse(get("$base/manifest.json") ?: error("No manifest at $base")).also { manifest = it }

    /** A catalog's titles; [extra] like `"skip" to "100"`, `"genre" to "Action"`, `"search" to "dune"` */
    suspend fun catalog(type: String, id: String, extra: Map<String, String> = emptyMap()): List<MetaPreview> =
        get(resourceUrl("catalog", type, id, extra))?.get("metas").arr().mapNotNull { it.obj()?.let(::preview) }

    suspend fun meta(type: String, id: String): Meta? {
        val m = get(resourceUrl("meta", type, id))?.get("meta").obj() ?: return null
        val p = preview(m) ?: return null
        return Meta(
            preview = p,
            runtime = m.str("runtime"),
            cast = m.strings("cast"),
            director = m.strings("director"),
            trailers = m["trailerStreams"].arr().mapNotNull { it.obj()?.str("ytId") } + m["trailers"].arr().mapNotNull { it.obj()?.str("source") },
            videos = m["videos"].arr().mapNotNull { it.obj() }.mapNotNull { v ->
                Video(
                    id = v.str("id") ?: return@mapNotNull null,
                    title = v.str("title") ?: v.str("name"),
                    season = v.int("season"),
                    episode = v.int("episode") ?: v.int("number"),
                    released = v.str("released") ?: v.str("firstAired"),
                    thumbnail = v.str("thumbnail"),
                    overview = v.str("overview") ?: v.str("description"),
                )
            },
        )
    }

    /** [id] is the title's for a movie, the episode's (`tt0944947:1:1`) for a series */
    suspend fun streams(type: String, id: String): List<Stream> {
        val name = manifest?.name ?: base
        return get(resourceUrl("stream", type, id))?.get("streams").arr().mapNotNull { it.obj() }.mapNotNull { s ->
            val hints = s["behaviorHints"].obj()
            val headers = hints?.get("proxyHeaders").obj()?.get("request").obj()
                ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { k to it } }?.toMap().orEmpty()
            Stream(
                source = name,
                name = s.str("name"),
                title = s.str("title") ?: s.str("description"),
                url = s.str("url"),
                infoHash = s.str("infoHash")?.lowercase(),
                fileIdx = s.int("fileIdx"),
                externalUrl = s.str("externalUrl"),
                filename = hints?.str("filename"),
                size = hints?.long("videoSize"),
                headers = headers,
                bingeGroup = hints?.str("bingeGroup"),
                subtitles = subtitleList(s),
            ).takeIf { it.url != null || it.infoHash != null || it.externalUrl != null }
        }
    }

    /** A subtitle addon's files for [id] (the episode's for a series) */
    suspend fun subtitles(type: String, id: String): List<Subtitle> = subtitleList(get(resourceUrl("subtitles", type, id)))

    private fun subtitleList(o: JsonObject?): List<Subtitle> =
        o?.get("subtitles").arr().mapNotNull { it.obj() }.mapNotNull { x -> x.str("url")?.let { Subtitle(it, x.str("lang") ?: "") } }

    private fun resourceUrl(resource: String, type: String, id: String, extra: Map<String, String> = emptyMap()): String {
        val tail = if (extra.isEmpty()) "" else "/" + extra.entries.joinToString("&") { (k, v) -> "${k.encodeURLPathPart()}=${v.encodeURLPathPart()}" }
        return "$base/$resource/${type.encodeURLPathPart()}/${id.encodeURLPathPart()}$tail.json"
    }

    private suspend fun get(url: String): JsonObject? {
        val r = http.get(url)
        if (!r.status.isSuccess()) return null
        return json.parseToJsonElement(r.bodyAsText()).obj()
    }

    private fun preview(m: JsonObject): MetaPreview? {
        val id = m.str("id") ?: m.str("imdb_id") ?: return null
        val name = m.str("name") ?: return null
        return preview(m, id, name)
    }

    private fun preview(m: JsonObject, id: String, name: String) = MetaPreview(
        id = id,
        type = m.str("type") ?: "movie",
        name = name,
        poster = m.str("poster"),
        background = m.str("background"),
        logo = m.str("logo"),
        description = m.str("description"),
        releaseInfo = m.str("releaseInfo") ?: m.str("year"),
        imdbRating = m.str("imdbRating"),
        genres = m.strings("genres").ifEmpty { m.strings("genre") },
    )
}
