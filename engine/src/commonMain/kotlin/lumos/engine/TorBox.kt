package lumos.engine

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.forms.formData
import kotlinx.serialization.json.JsonObject

/**
 * TorBox spoken to directly, for playing a torrent an addon found: add it (only if TorBox already
 * has it), pick the file, ask for its link. (TorBox's own search was retired in 2026: addons such as
 * StremThru Torz do the finding.)
 */
class TorBox(private val apiKey: String, private val http: HttpClient) {

    /**
     * A playable link for [stream] (a torrent): added to TorBox only if it's cached there, then the
     * file wanted (by [fileIdx], else the episode's, else the largest video) is asked for.
     * Null when TorBox doesn't have it ready.
     */
    suspend fun resolve(stream: Stream, season: Int? = null, episode: Int? = null): String? {
        val hash = stream.infoHash ?: return stream.url
        val added = body(http.submitFormWithBinaryData(
            url = "$API/torrents/createtorrent",
            formData = formData {
                append("magnet", "magnet:?xt=urn:btih:$hash")
                append("add_only_if_cached", "true")
                append("allow_zip", "false")
            },
        ) { bearerAuth(apiKey) })?.get("data").obj() ?: return null
        val torrentId = added.int("torrent_id") ?: return null

        val info = body(http.get("$API/torrents/mylist") {
            bearerAuth(apiKey)
            parameter("id", torrentId)
            parameter("bypass_cache", true)
        })?.get("data").obj() ?: return null
        val files = info["files"].arr().mapNotNull { it.obj() }.mapNotNull { f ->
            File(f.int("id") ?: return@mapNotNull null, f.str("short_name") ?: f.str("name") ?: "", f.long("size") ?: 0, f.str("mimetype"))
        }
        val file = pickFile(files, stream.fileIdx, season, episode, stream.filename) ?: return null

        val link = body(http.get("$API/torrents/requestdl") {
            parameter("token", apiKey)
            parameter("torrent_id", torrentId)
            parameter("file_id", file.id)
            parameter("zip_link", false)
            parameter("redirect", false)
        })
        return link?.str("data")
    }

    internal data class File(val id: Int, val name: String, val size: Long, val mime: String?) {
        val isVideo get() = mime?.startsWith("video/") == true || VIDEO.containsMatchIn(name)
    }

    private suspend fun body(r: HttpResponse): JsonObject? =
        runCatching { json.parseToJsonElement(r.bodyAsText()).obj() }.getOrNull()?.takeIf { it.bool("success") != false }

    companion object {
        private const val API = "https://api.torbox.app/v1/api"
        private val VIDEO = Regex("""\.(mkv|mp4|m4v|avi|mov|ts|webm)$""", RegexOption.IGNORE_CASE)

        /**
         * The file to play. The one the addon named (by file name, exact) first; for an episode, the file tagged with it
         * (S02E03, 2x03, or for anime with no season, "- 05"/"E05"), else the addon's file number if that file names no
         * other episode, else the only video: never a guess, which played another episode. A film: the addon's file, else
         * the largest video.
         */
        internal fun pickFile(files: List<File>, fileIdx: Int?, season: Int?, episode: Int?, filename: String? = null): File? {
            val videos = files.filter { it.isVideo && !Regex("""\bsample\b""", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }
            val base = filename?.substringAfterLast('/')?.lowercase()
            if (!base.isNullOrBlank()) videos.firstOrNull { it.name.substringAfterLast('/').lowercase() == base }?.let { return it }
            val byIndex = fileIdx?.let { i -> videos.firstOrNull { it.id == i } }
            if (episode != null) {
                val tag = if (season != null) Regex("""(?i)(s0*$season[ ._-]?e0*$episode(?!\d))|(\b0*${season}x0*$episode(?!\d))""")
                else Regex("""(?i)(e0*$episode(?!\d))|([ ._\[-]0*$episode(v\d)?[ ._\])-](?!\d))|(^0*$episode[ ._-])""")
                videos.filter { tag.containsMatchIn(it.name.substringAfterLast('/')) }.maxByOrNull { it.size }?.let { return it }
                val anyEpisode = Regex("""(?i)s\d{1,2}[ ._-]?e\d{1,3}|\b\d{1,2}x\d{2,3}\b""")
                if (byIndex != null && !anyEpisode.containsMatchIn(byIndex.name)) return byIndex
                return videos.singleOrNull()
            }
            return byIndex ?: videos.maxByOrNull { it.size }
        }
    }
}
