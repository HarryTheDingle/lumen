package com.notdxbi.dxbi.tv.phone

import java.net.HttpURLConnection
import java.net.URL

/**
 * A look at a web stream before it's played: some addons' servers (ElfHosted's "Couldn't fetch this release")
 * answer with an error, or with a few seconds of video saying so, where the file should be. Asks for the first
 * bytes only. Anything that can't be told (a slow server, an odd answer) counts as fine: the player decides.
 */
object StreamProbe {
    /** Smaller than this, for a stream listed as large (or not listed), isn't a film or an episode */
    private const val TOO_SMALL = 30L * 1024 * 1024
    private val ERROR_FILE = Regex("(fail|error|unavail|not[_-]?found|denied|expired|blocked)[^/]*\\.(mp4|mkv|webm|ts)$", RegexOption.IGNORE_CASE)

    /** Why it isn't a video, or null when it looks like one */
    fun check(address: String, headers: Map<String, String>, listedBytes: Double): String? {
        var url = address
        try {
            for (hop in 0 until 6) {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 4000
                c.readTimeout = 5000
                c.instanceFollowRedirects = false
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                c.setRequestProperty("Range", "bytes=0-2047")
                try {
                    val code = c.responseCode
                    if (code in 300..399) {
                        val to = c.getHeaderField("Location") ?: return null
                        url = URL(URL(url), to).toString()
                        continue
                    }
                    if (code >= 400) return "The server answered $code"
                    val type = c.contentType.orEmpty().lowercase()
                    if (type.startsWith("text/") || type.contains("json") || type.contains("xml")) return "It isn't a video ($type)"
                    val name = URL(url).path.substringAfterLast('/')
                    if (ERROR_FILE.containsMatchIn(name)) return "The server sent an error video ($name)"
                    val total = c.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                        ?: c.contentLengthLong.takeIf { code == 200 && it > 0 }
                    if (total != null && total < TOO_SMALL && (listedBytes <= 0 || listedBytes > total * 10)) {
                        return "It's only ${total / 1024 / 1024} MB: an error clip"
                    }
                    return null
                } finally {
                    c.disconnect()
                }
            }
        } catch (_: Exception) { /* can't tell */ }
        return null
    }
}
