package lumos.iptv

/** Constructs catch-up / timeshift playback URLs for M3U and Xtream streams. */
object CatchupUrlBuilder {
    private const val MILLIS_PER_DAY = 86_400_000L

    private val XTREAM_PATH_REGEX = Regex(
        """^(https?://[^/]+)(?:/live)?/([^/]+)/([^/]+)/([^/.]+)(?:\.([^/?#]+))?"""
    )

    /**
     * Returns a catch-up playback URL for [stream] covering `[startMs, endMs]`,
     * or `null` if [stream] lacks catch-up support, uses [CatchupMode.STALKER], or falls outside retention.
     */
    fun buildM3uOrXtreamCatchupUrl(
        stream: StreamSource,
        source: IptvSource?,
        startMs: Long,
        endMs: Long,
        nowMs: Long,
    ): String? {
        val catchup = stream.catchup ?: return null
        if (catchup.mode == CatchupMode.STALKER) return null
        if (catchup.days <= 0 || endMs <= startMs) return null
        if (startMs > nowMs) return null
        if (endMs < nowMs - catchup.days.toLong() * MILLIS_PER_DAY) return null

        val startSec = startMs / 1000L
        val endSec = endMs / 1000L
        val durationSec = ((endMs - startMs) / 1000L).coerceAtLeast(1L)
        val durationMin = (durationSec / 60L).coerceAtLeast(1L)
        val offsetSec = ((nowMs - startMs) / 1000L).coerceAtLeast(0L)

        return when (catchup.mode) {
            CatchupMode.XTREAM -> buildXtreamTimeshiftUrl(stream, source, catchup, startMs, durationMin)
            CatchupMode.XC -> {
                val tpl = catchup.sourceTemplate?.takeIf { it.isNotBlank() }
                if (tpl != null) {
                    val expanded = expandTemplate(tpl, startMs, endMs, nowMs, startSec, endSec, durationSec, offsetSec)
                    joinTemplate(stream.streamUrl, expanded)
                } else {
                    buildXtreamTimeshiftUrl(stream, source, catchup, startMs, durationMin)
                }
            }
            CatchupMode.DEFAULT -> {
                val tpl = catchup.sourceTemplate?.takeIf { it.isNotBlank() }
                if (tpl != null) {
                    val expanded = expandTemplate(tpl, startMs, endMs, nowMs, startSec, endSec, durationSec, offsetSec)
                    if (expanded.contains("://")) expanded else joinTemplate(stream.streamUrl, expanded)
                } else {
                    appendQuery(stream.streamUrl, "utc=$startSec&lutc=${nowMs / 1000L}")
                }
            }
            CatchupMode.APPEND -> {
                val tpl = catchup.sourceTemplate?.takeIf { it.isNotBlank() } ?: "?utc={utc}&lutc={lutc}"
                val expanded = expandTemplate(tpl, startMs, endMs, nowMs, startSec, endSec, durationSec, offsetSec)
                joinTemplate(stream.streamUrl, expanded)
            }
            CatchupMode.SHIFT -> {
                val tpl = catchup.sourceTemplate?.takeIf { it.isNotBlank() }
                if (tpl != null) {
                    val expanded = expandTemplate(tpl, startMs, endMs, nowMs, startSec, endSec, durationSec, offsetSec)
                    joinTemplate(stream.streamUrl, expanded)
                } else {
                    appendQuery(stream.streamUrl, "utc=$startSec&lutc=${nowMs / 1000L}")
                }
            }
            CatchupMode.FLUSSONIC -> {
                val tpl = catchup.sourceTemplate?.takeIf { it.isNotBlank() }
                if (tpl != null) {
                    val expanded = expandTemplate(tpl, startMs, endMs, nowMs, startSec, endSec, durationSec, offsetSec)
                    if (expanded.contains("://")) expanded else joinTemplate(stream.streamUrl, expanded)
                } else {
                    buildFlussonicUrl(stream.streamUrl, startSec, durationSec)
                }
            }
            CatchupMode.STALKER -> null
        }
    }

    private fun buildXtreamTimeshiftUrl(
        stream: StreamSource,
        source: IptvSource?,
        catchup: CatchupConfig,
        startMs: Long,
        durationMin: Long,
    ): String? {
        val xtream = source as? IptvSource.Xtream
        val timestamp = formatXtreamTimestamp(startMs)
        if (xtream != null) {
            val base = xtream.serverUrl.trim().trimEnd('/')
            val user = urlEncode(xtream.username)
            val pass = urlEncode(xtream.password)
            val streamId = catchup.streamId ?: stream.xtreamStreamId ?: extractStreamIdFromUrl(stream.streamUrl) ?: return null
            val rawExt = xtream.outputFormat.trim().lowercase().removePrefix(".")
            val ext = if (rawExt == "m3u8" || rawExt == "hls") "m3u8" else if (rawExt.isBlank() || rawExt == "raw" || rawExt == "none") "ts" else rawExt
            return "$base/timeshift/$user/$pass/$durationMin/$timestamp/$streamId.$ext"
        }

        val match = XTREAM_PATH_REGEX.find(stream.streamUrl) ?: return null
        val base = match.groupValues[1]
        val user = match.groupValues[2]
        val pass = match.groupValues[3]
        val streamId = catchup.streamId ?: stream.xtreamStreamId ?: match.groupValues[4]
        val ext = match.groupValues[5].ifBlank { "ts" }
        return "$base/timeshift/$user/$pass/$durationMin/$timestamp/$streamId.$ext"
    }

    private fun extractStreamIdFromUrl(url: String): String? {
        val last = url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        return last.substringBeforeLast('.').takeIf { it.isNotBlank() }
    }

    private fun buildFlussonicUrl(streamUrl: String, startSec: Long, durationSec: Long): String {
        val queryIdx = streamUrl.indexOf('?')
        val basePath = if (queryIdx >= 0) streamUrl.substring(0, queryIdx) else streamUrl
        val query = if (queryIdx >= 0) streamUrl.substring(queryIdx) else ""

        val rewritten = when {
            basePath.endsWith("/index.m3u8", ignoreCase = true) ->
                basePath.dropLast("/index.m3u8".length) + "/archive-$startSec-$durationSec.m3u8"
            basePath.endsWith("/mono.m3u8", ignoreCase = true) ->
                basePath.dropLast("/mono.m3u8".length) + "/archive-$startSec-$durationSec.m3u8"
            basePath.endsWith(".m3u8", ignoreCase = true) ->
                basePath.substringBeforeLast('/') + "/archive-$startSec-$durationSec.m3u8"
            basePath.endsWith("/mpegts", ignoreCase = true) ->
                basePath.dropLast("/mpegts".length) + "/archive-$startSec-$durationSec.ts"
            basePath.endsWith(".ts", ignoreCase = true) ->
                basePath.substringBeforeLast('/') + "/archive-$startSec-$durationSec.ts"
            else ->
                basePath.trimEnd('/') + "/archive-$startSec-$durationSec.m3u8"
        }
        return rewritten + query
    }

    private fun expandTemplate(
        template: String,
        startMs: Long,
        endMs: Long,
        nowMs: Long,
        startSec: Long,
        endSec: Long,
        durationSec: Long,
        offsetSec: Long,
    ): String {
        val s = toUtcFields(startMs)
        val e = toUtcFields(endMs)
        val nowSec = nowMs / 1000L

        return template
            .replace("\${start}", startSec.toString())
            .replace("{start}", startSec.toString())
            .replace("{utc}", startSec.toString())
            .replace("\${end}", endSec.toString())
            .replace("{end}", endSec.toString())
            .replace("{utcend}", endSec.toString())
            .replace("\${timestamp}", nowSec.toString())
            .replace("{lutc}", nowSec.toString())
            .replace("\${duration}", durationSec.toString())
            .replace("{duration}", durationSec.toString())
            .replace("\${offset}", offsetSec.toString())
            .replace("{offset}", offsetSec.toString())
            .replace("\${(b)Y}", pad4(s.year)).replace("\${Y}", pad4(s.year)).replace("{Y}", pad4(s.year))
            .replace("\${(b)m}", pad2(s.month)).replace("\${m}", pad2(s.month)).replace("{m}", pad2(s.month))
            .replace("\${(b)d}", pad2(s.day)).replace("\${d}", pad2(s.day)).replace("{d}", pad2(s.day))
            .replace("\${(b)H}", pad2(s.hour)).replace("\${H}", pad2(s.hour)).replace("{H}", pad2(s.hour))
            .replace("\${(b)M}", pad2(s.minute)).replace("\${M}", pad2(s.minute)).replace("{M}", pad2(s.minute))
            .replace("\${(b)S}", pad2(s.second)).replace("\${S}", pad2(s.second)).replace("{S}", pad2(s.second))
            .replace("\${(e)Y}", pad4(e.year)).replace("{endY}", pad4(e.year))
            .replace("\${(e)m}", pad2(e.month)).replace("{endm}", pad2(e.month))
            .replace("\${(e)d}", pad2(e.day)).replace("{endd}", pad2(e.day))
            .replace("\${(e)H}", pad2(e.hour)).replace("{endH}", pad2(e.hour))
            .replace("\${(e)M}", pad2(e.minute)).replace("{endM}", pad2(e.minute))
            .replace("\${(e)S}", pad2(e.second)).replace("{endS}", pad2(e.second))
    }

    private fun joinTemplate(baseUrl: String, suffix: String): String {
        val cleanSuffix = suffix.trim()
        return when {
            cleanSuffix.startsWith("?") -> appendQuery(baseUrl, cleanSuffix.substring(1))
            cleanSuffix.startsWith("&") -> appendQuery(baseUrl, cleanSuffix.substring(1))
            cleanSuffix.startsWith("/") -> baseUrl.trimEnd('/') + cleanSuffix
            cleanSuffix.contains("=") && !cleanSuffix.contains("/") -> appendQuery(baseUrl, cleanSuffix)
            else -> baseUrl + cleanSuffix
        }
    }

    private fun appendQuery(url: String, queryPart: String): String {
        if (queryPart.isBlank()) return url
        val separator = if (url.contains('?')) '&' else '?'
        return "$url$separator$queryPart"
    }
}
