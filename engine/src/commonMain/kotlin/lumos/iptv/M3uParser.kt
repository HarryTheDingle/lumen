package lumos.iptv

/** Result of parsing an M3U/M3U8 live IPTV playlist. */
data class M3uParseResult(
    val streams: List<StreamSource>,
    val headerEpgUrls: List<String>,
)

/** Streaming line-by-line parser for M3U/M3U8 IPTV playlists. */
object M3uParser {
    private val VOD_EXTENSIONS = listOf(".mp4", ".mkv", ".avi", ".mov")

    /** Parses [content] for [source], extracting live [StreamSource]s and header XMLTV URLs. */
    fun parse(
        content: String,
        source: IptvSource.M3u,
        skipForeign: Boolean = false,
    ): M3uParseResult {
        val streams = mutableListOf<StreamSource>()
        val headerEpgUrls = linkedSetOf<String>()
        val foreignGroupMemo = if (skipForeign) HashMap<String, Boolean>() else null

        var pendingAttrs: Map<String, String>? = null
        var pendingTitle: String? = null
        var pendingExtGrp: String? = null
        val pendingHeaders = linkedMapOf<String, String>()
        var itemIndex = 0

        for (rawLine in content.lineSequence()) {
            val line = rawLine.trim().trimStart('\uFEFF')
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attrs = parseAttributes(line.substring(7))
                    collectHeaderUrls(attrs["url-tvg"], headerEpgUrls)
                    collectHeaderUrls(attrs["x-tvg-url"], headerEpgUrls)
                }

                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    val body = line.substring(8)
                    val commaIdx = findUnquotedComma(body)
                    val attrSection = if (commaIdx >= 0) body.substring(0, commaIdx) else body
                    val titleSection = if (commaIdx >= 0) body.substring(commaIdx + 1).trim() else ""
                    pendingAttrs = parseAttributes(attrSection)
                    pendingTitle = titleSection
                }

                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    pendingExtGrp = line.substring(8).trim().takeIf { it.isNotEmpty() }
                }

                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    parseVlcOpt(line.substring(11), pendingHeaders)
                }

                line.startsWith("#KODIPROP:", ignoreCase = true) -> {
                    parseKodiProp(line.substring(10), pendingHeaders)
                }

                line.startsWith("#") -> Unit

                else -> {
                    itemIndex++
                    val (cleanUrl, inlineHeaders) = splitInlinePipeHeaders(line)
                    val attrs = pendingAttrs ?: emptyMap()
                    val groupTitle = attrs["group-title"]?.takeIf { it.isNotBlank() }
                        ?: pendingExtGrp
                        ?: ""
                    val titleCandidate = pendingTitle?.trim()?.takeIf { it.isNotBlank() }
                    val extGrp = pendingExtGrp

                    val mergedHeaders = linkedMapOf<String, String>()
                    source.userAgent?.takeIf { it.isNotBlank() }?.let { mergedHeaders["User-Agent"] = it }
                    mergedHeaders.putAll(pendingHeaders)
                    mergedHeaders.putAll(inlineHeaders)

                    // Clear per-entry metadata before checking VOD skip so skipped lines never leak state.
                    pendingAttrs = null
                    pendingTitle = null
                    pendingExtGrp = null
                    pendingHeaders.clear()

                    if (!isVodUrl(cleanUrl)) {
                        val rawName = resolveRawName(
                            extInfTitle = titleCandidate,
                            tvgName = attrs["tvg-name"],
                            url = cleanUrl,
                            index = itemIndex,
                        )
                        val effectiveGroup = groupTitle.ifBlank { extGrp ?: "" }
                        if (foreignGroupMemo != null &&
                            ChannelNormalizer.isForeignEntry(rawName, effectiveGroup, foreignGroupMemo)
                        ) {
                            continue
                        }
                        val catchup = parseCatchupConfig(attrs)
                        streams.add(
                            StreamSource(
                                id = "${source.id}:m3u:$itemIndex",
                                sourceId = source.id,
                                sourceName = source.name,
                                rawName = rawName,
                                groupTitle = effectiveGroup,
                                tvgId = attrs["tvg-id"]?.trim()?.takeIf { it.isNotBlank() },
                                logoUrl = attrs["tvg-logo"]?.trim()?.takeIf { it.isNotBlank() },
                                streamUrl = cleanUrl,
                                headers = mergedHeaders,
                                quality = ChannelNormalizer.parseQuality(rawName),
                                catchup = catchup,
                                requiresResolve = false,
                            )
                        )
                    }
                }
            }
        }

        return M3uParseResult(
            streams = streams,
            headerEpgUrls = headerEpgUrls.toList(),
        )
    }

    private fun collectHeaderUrls(raw: String?, out: MutableSet<String>) {
        if (raw.isNullOrBlank()) return
        for (part in raw.split(',')) {
            val url = part.trim()
            if (url.isNotEmpty()) out.add(url)
        }
    }

    private fun findUnquotedComma(text: String): Int {
        var quote: Char? = null
        for (i in text.indices) {
            val ch = text[i]
            when {
                quote != null -> if (ch == quote) quote = null
                ch == '"' || ch == '\'' -> quote = ch
                ch == ',' -> return i
            }
        }
        return -1
    }

    internal fun parseAttributes(text: String): Map<String, String> {
        val attrs = linkedMapOf<String, String>()
        var i = 0
        val n = text.length
        while (i < n) {
            while (i < n && (text[i].isWhitespace() || text[i] == ',')) i++
            if (i >= n) break

            val keyStart = i
            while (i < n && text[i] != '=' && !text[i].isWhitespace()) i++
            if (i >= n || text[i] != '=') {
                while (i < n && !text[i].isWhitespace()) i++
                continue
            }
            val key = text.substring(keyStart, i).trim().lowercase()
            i++ // skip '='
            while (i < n && text[i].isWhitespace()) i++
            if (i >= n) break

            val value = when (val q = text[i]) {
                '"', '\'' -> {
                    i++
                    val valStart = i
                    while (i < n && text[i] != q) i++
                    val extracted = text.substring(valStart, i)
                    if (i < n && text[i] == q) i++
                    extracted
                }
                else -> {
                    val valStart = i
                    while (i < n && !text[i].isWhitespace() && text[i] != ',') i++
                    text.substring(valStart, i)
                }
            }
            if (key.isNotEmpty()) attrs[key] = value.trim()
        }
        return attrs
    }

    private fun parseVlcOpt(opt: String, headers: MutableMap<String, String>) {
        val eq = opt.indexOf('=')
        if (eq <= 0) return
        val key = opt.substring(0, eq).trim().lowercase()
        val value = opt.substring(eq + 1).trim()
        if (value.isEmpty()) return
        when (key) {
            "http-user-agent" -> headers["User-Agent"] = value
            "http-referrer", "http-referer" -> headers["Referer"] = value
            "http-origin" -> headers["Origin"] = value
        }
    }

    private fun parseKodiProp(prop: String, headers: MutableMap<String, String>) {
        val eq = prop.indexOf('=')
        if (eq <= 0) return
        val key = prop.substring(0, eq).trim().lowercase()
        val rawVal = prop.substring(eq + 1).trim()
        if (key == "inputstream.adaptive.stream_headers" && rawVal.isNotEmpty()) {
            parseHeaderQueryPairs(rawVal, headers)
        }
    }

    private fun splitInlinePipeHeaders(urlLine: String): Pair<String, Map<String, String>> {
        val pipeIdx = urlLine.indexOf('|')
        if (pipeIdx <= 0) return urlLine to emptyMap()
        val tail = urlLine.substring(pipeIdx + 1)
        if (!tail.contains('=')) return urlLine to emptyMap()
        val headers = linkedMapOf<String, String>()
        parseHeaderQueryPairs(tail, headers)
        return urlLine.substring(0, pipeIdx).trim() to headers
    }

    private fun parseHeaderQueryPairs(raw: String, out: MutableMap<String, String>) {
        for (pair in raw.split('&', '|')) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            val k = pair.substring(0, idx).trim()
            val v = pair.substring(idx + 1).trim()
            if (k.isNotEmpty() && v.isNotEmpty()) out[k] = v
        }
    }

    private fun isVodUrl(url: String): Boolean {
        val pathOnly = url.substringBefore('?').substringBefore('#').lowercase()
        if (pathOnly.contains("/movie/") || pathOnly.contains("/series/")) return true
        return VOD_EXTENSIONS.any { pathOnly.endsWith(it) }
    }

    private fun resolveRawName(
        extInfTitle: String?,
        tvgName: String?,
        url: String,
        index: Int,
    ): String {
        extInfTitle?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        tvgName?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        val withoutQuery = url.substringBefore('?').substringBefore('#')
        val schemeIdx = withoutQuery.indexOf("://")
        val pathOnly = if (schemeIdx >= 0) {
            val slashAfterHost = withoutQuery.indexOf('/', schemeIdx + 3)
            if (slashAfterHost >= 0) withoutQuery.substring(slashAfterHost) else ""
        } else {
            withoutQuery
        }
        val pathSegment = pathOnly.trimEnd('/').substringAfterLast('/').trim()
        if (pathSegment.isNotBlank()) return pathSegment
        return "Channel $index"
    }

    private fun parseCatchupConfig(attrs: Map<String, String>): CatchupConfig? {
        val rawMode = attrs["catchup"]?.trim()?.lowercase()
        if (rawMode == "none" || rawMode == "0") return null

        val catchupSource = attrs["catchup-source"]?.trim()?.takeIf { it.isNotBlank() }
        val explicitDays = attrs["catchup-days"]?.trim()?.toIntOrNull()
        val tvgRecDays = attrs["tvg-rec"]?.trim()?.toIntOrNull()
        val timeshiftDays = attrs["timeshift"]?.trim()?.toIntOrNull()

        if (explicitDays != null && explicitDays <= 0 && rawMode == null) return null
        if (explicitDays == 0) return null

        val days = explicitDays
            ?: tvgRecDays?.takeIf { it > 0 }
            ?: timeshiftDays?.takeIf { it > 0 }

        val mode = when (rawMode) {
            "default" -> CatchupMode.DEFAULT
            "append" -> CatchupMode.APPEND
            "shift" -> CatchupMode.SHIFT
            "flussonic", "flussonic-ts", "flussonic-hls", "fs" -> CatchupMode.FLUSSONIC
            "xc" -> CatchupMode.XC
            "xtream" -> CatchupMode.XTREAM
            null -> when {
                catchupSource != null -> if (catchupSource.startsWith("?") || catchupSource.startsWith("&")) {
                    CatchupMode.APPEND
                } else {
                    CatchupMode.DEFAULT
                }
                days != null && days > 0 -> CatchupMode.SHIFT
                else -> return null
            }
            else -> return null
        }

        val resolvedDays = (days ?: 7).coerceAtLeast(1)
        return CatchupConfig(
            mode = mode,
            days = resolvedDays,
            sourceTemplate = catchupSource,
        )
    }
}
