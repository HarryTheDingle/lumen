package lumos.iptv

/** Channel display metadata parsed from an XMLTV `<channel>` element. */
data class XmltvChannelMeta(
    val id: String,
    val displayNames: List<String>,
    val iconUrl: String? = null,
)

/** Complete result of parsing an XMLTV document. */
data class XmltvParseResult(
    val channels: List<XmltvChannelMeta>,
    val programmes: List<EpgProgramme>,
)

/**
 * Pure-Kotlin streaming XMLTV parser supporting `<channel>`, `<programme>`, CDATA sections,
 * numeric/named XML entities, and timezone-offset timestamps.
 */
object XmltvParser {
    /** Parses [xml] in a single forward pass into channels and [EpgProgramme] listings. */
    fun parse(xml: String): XmltvParseResult {
        val channels = mutableListOf<XmltvChannelMeta>()
        val programmes = mutableListOf<EpgProgramme>()

        val n = xml.length
        var pos = 0

        var inChannel = false
        var channelId = ""
        val channelNames = mutableListOf<String>()
        var channelIcon: String? = null

        var inProgramme = false
        var progStartRaw = ""
        var progStopRaw = ""
        var progChannel = ""
        var progTitle: String? = null
        var progSubtitle: String? = null
        var progDesc: String? = null
        var progCategory: String? = null
        var progIcon: String? = null

        while (pos < n) {
            val lt = xml.indexOf('<', pos)
            if (lt < 0) break

            if (xml.startsWith("<!--", lt)) {
                val endComment = xml.indexOf("-->", lt + 4)
                pos = if (endComment >= 0) endComment + 3 else n
                continue
            }
            if (xml.startsWith("<![CDATA[", lt)) {
                val endCdata = xml.indexOf("]]>", lt + 9)
                pos = if (endCdata >= 0) endCdata + 3 else n
                continue
            }
            if (xml.startsWith("<?", lt) || xml.startsWith("<!", lt)) {
                val gt = findTagEnd(xml, lt + 2)
                pos = if (gt >= 0) gt + 1 else n
                continue
            }

            val gt = findTagEnd(xml, lt + 1)
            if (gt < 0) break
            pos = gt + 1

            val tagBody = xml.substring(lt + 1, gt).trim()
            if (tagBody.isEmpty()) continue

            val isClosing = tagBody.startsWith('/')
            val selfClosing = !isClosing && tagBody.endsWith('/')
            val cleanBody = tagBody
                .removePrefix("/")
                .removeSuffix("/")
                .trim()
            val tagName = extractTagName(cleanBody).lowercase()

            if (isClosing) {
                when (tagName) {
                    "channel" -> if (inChannel) {
                        if (channelId.isNotBlank()) {
                            channels.add(XmltvChannelMeta(channelId, channelNames.toList(), channelIcon))
                        }
                        inChannel = false
                    }
                    "programme" -> if (inProgramme) {
                        buildProgramme(
                            channelId = progChannel,
                            startRaw = progStartRaw,
                            stopRaw = progStopRaw,
                            title = progTitle,
                            subtitle = progSubtitle,
                            description = progDesc,
                            category = progCategory,
                            iconUrl = progIcon,
                        )?.let(programmes::add)
                        inProgramme = false
                    }
                }
                continue
            }

            val attrPart = cleanBody.substring(tagName.length).trim()
            when {
                tagName == "channel" -> {
                    channelId = extractAttr(attrPart, "id")?.let(::unescapeXml)?.trim().orEmpty()
                    channelNames.clear()
                    channelIcon = null
                    if (selfClosing) {
                        if (channelId.isNotBlank()) channels.add(XmltvChannelMeta(channelId, emptyList(), null))
                        inChannel = false
                    } else {
                        inChannel = true
                    }
                }

                tagName == "programme" -> {
                    progStartRaw = extractAttr(attrPart, "start").orEmpty()
                    progStopRaw = extractAttr(attrPart, "stop").orEmpty()
                    progChannel = extractAttr(attrPart, "channel")?.let(::unescapeXml)?.trim().orEmpty()
                    progTitle = null
                    progSubtitle = null
                    progDesc = null
                    progCategory = null
                    progIcon = null
                    inProgramme = !selfClosing
                }

                inChannel && tagName == "display-name" && !selfClosing -> {
                    val (text, nextPos) = readElementText(xml, pos, "display-name")
                    pos = nextPos
                    text.trim().takeIf { it.isNotEmpty() }?.let(channelNames::add)
                }

                inChannel && tagName == "icon" -> {
                    if (channelIcon == null) {
                        channelIcon = extractAttr(attrPart, "src")?.let(::unescapeXml)?.trim()?.takeIf { it.isNotEmpty() }
                    }
                    if (!selfClosing) {
                        val (_, nextPos) = readElementText(xml, pos, "icon")
                        pos = nextPos
                    }
                }

                inProgramme && tagName == "title" && !selfClosing -> {
                    val (text, nextPos) = readElementText(xml, pos, "title")
                    pos = nextPos
                    if (progTitle == null) progTitle = text.trim().takeIf { it.isNotEmpty() }
                }

                inProgramme && tagName == "sub-title" && !selfClosing -> {
                    val (text, nextPos) = readElementText(xml, pos, "sub-title")
                    pos = nextPos
                    if (progSubtitle == null) progSubtitle = text.trim().takeIf { it.isNotEmpty() }
                }

                inProgramme && tagName == "desc" && !selfClosing -> {
                    val (text, nextPos) = readElementText(xml, pos, "desc")
                    pos = nextPos
                    if (progDesc == null) progDesc = text.trim().takeIf { it.isNotEmpty() }
                }

                inProgramme && tagName == "category" && !selfClosing -> {
                    val (text, nextPos) = readElementText(xml, pos, "category")
                    pos = nextPos
                    if (progCategory == null) progCategory = text.trim().takeIf { it.isNotEmpty() }
                }

                inProgramme && tagName == "icon" -> {
                    if (progIcon == null) {
                        progIcon = extractAttr(attrPart, "src")?.let(::unescapeXml)?.trim()?.takeIf { it.isNotEmpty() }
                    }
                    if (!selfClosing) {
                        val (_, nextPos) = readElementText(xml, pos, "icon")
                        pos = nextPos
                    }
                }
            }
        }

        return XmltvParseResult(channels = channels, programmes = programmes)
    }

    private fun buildProgramme(
        channelId: String,
        startRaw: String,
        stopRaw: String,
        title: String?,
        subtitle: String?,
        description: String?,
        category: String?,
        iconUrl: String?,
    ): EpgProgramme? {
        if (channelId.isBlank() || title.isNullOrBlank()) return null
        val startMs = parseXmltvTime(startRaw) ?: return null
        val endMs = parseXmltvTime(stopRaw) ?: return null
        if (endMs <= startMs) return null

        val (isLiveSport, sportTag) = detectSportTag(title, category = category, subtitle = subtitle)
        return EpgProgramme(
            id = "$channelId:$startMs",
            channelId = channelId,
            title = title,
            subtitle = subtitle,
            description = description,
            category = category,
            startMs = startMs,
            endMs = endMs,
            iconUrl = iconUrl,
            isLiveSport = isLiveSport,
            sportTag = sportTag,
            hasCatchup = false,
        )
    }

    private fun findTagEnd(xml: String, start: Int): Int {
        var quote: Char? = null
        var i = start
        val n = xml.length
        while (i < n) {
            val ch = xml[i]
            when {
                quote != null -> if (ch == quote) quote = null
                ch == '"' || ch == '\'' -> quote = ch
                ch == '>' -> return i
            }
            i++
        }
        return -1
    }

    private fun extractTagName(cleanBody: String): String {
        var end = 0
        while (end < cleanBody.length && !cleanBody[end].isWhitespace()) end++
        return cleanBody.substring(0, end)
    }

    private fun extractAttr(attrText: String, attrName: String): String? {
        var i = 0
        val n = attrText.length
        while (i < n) {
            while (i < n && attrText[i].isWhitespace()) i++
            if (i >= n) break
            val keyStart = i
            while (i < n && attrText[i] != '=' && !attrText[i].isWhitespace()) i++
            if (i >= n || attrText[i] != '=') {
                while (i < n && !attrText[i].isWhitespace()) i++
                continue
            }
            val key = attrText.substring(keyStart, i).trim()
            i++
            while (i < n && attrText[i].isWhitespace()) i++
            if (i >= n) break

            val value = when (val q = attrText[i]) {
                '"', '\'' -> {
                    i++
                    val vs = i
                    while (i < n && attrText[i] != q) i++
                    val s = attrText.substring(vs, i)
                    if (i < n && attrText[i] == q) i++
                    s
                }
                else -> {
                    val vs = i
                    while (i < n && !attrText[i].isWhitespace()) i++
                    attrText.substring(vs, i)
                }
            }
            if (key.equals(attrName, ignoreCase = true)) return value
        }
        return null
    }

    private fun readElementText(xml: String, startPos: Int, tagName: String): Pair<String, Int> {
        val sb = StringBuilder()
        var pos = startPos
        val n = xml.length
        val closeMarker = "</$tagName"

        while (pos < n) {
            val lt = xml.indexOf('<', pos)
            if (lt < 0) {
                sb.append(unescapeXml(xml.substring(pos)))
                return sb.toString() to n
            }
            if (lt > pos) {
                sb.append(unescapeXml(xml.substring(pos, lt)))
            }
            if (xml.startsWith("<![CDATA[", lt)) {
                val endCdata = xml.indexOf("]]>", lt + 9)
                if (endCdata < 0) {
                    sb.append(xml.substring(lt + 9))
                    return sb.toString() to n
                }
                sb.append(xml.substring(lt + 9, endCdata))
                pos = endCdata + 3
                continue
            }
            if (xml.startsWith(closeMarker, lt, ignoreCase = true)) {
                val afterName = lt + closeMarker.length
                val gt = findTagEnd(xml, afterName)
                val nextPos = if (gt >= 0) gt + 1 else n
                // Ensure it isn't a longer tag name sharing a prefix (e.g. `</title-extra>`).
                if (afterName < n && (xml[afterName] == '>' || xml[afterName].isWhitespace())) {
                    return sb.toString() to nextPos
                }
            }
            val gt = findTagEnd(xml, lt + 1)
            pos = if (gt >= 0) gt + 1 else n
        }
        return sb.toString() to n
    }

    /** Unescapes standard XML named entities and decimal/hex character references. */
    internal fun unescapeXml(raw: String): String {
        if (!raw.contains('&')) return raw
        val sb = StringBuilder(raw.length)
        var i = 0
        val n = raw.length
        while (i < n) {
            val ch = raw[i]
            if (ch != '&') {
                sb.append(ch)
                i++
                continue
            }
            val semi = raw.indexOf(';', i + 1)
            if (semi < 0 || semi - i > 12) {
                sb.append(ch)
                i++
                continue
            }
            val entity = raw.substring(i + 1, semi)
            val decoded = decodeEntity(entity)
            if (decoded != null) {
                sb.append(decoded)
                i = semi + 1
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }

    private fun decodeEntity(entity: String): String? = when (entity) {
        "amp" -> "&"
        "lt" -> "<"
        "gt" -> ">"
        "quot" -> "\""
        "apos" -> "'"
        "nbsp" -> " "
        else -> if (entity.startsWith("#")) {
            val code = if (entity.length > 2 && (entity[1] == 'x' || entity[1] == 'X')) {
                entity.substring(2).toIntOrNull(16)
            } else {
                entity.substring(1).toIntOrNull(10)
            }
            code?.let { cp ->
                when {
                    cp in 1..0xFFFF -> cp.toChar().toString()
                    cp in 0x10000..0x10FFFF -> {
                        val u = cp - 0x10000
                        val high = (0xD800 + (u ushr 10)).toChar()
                        val low = (0xDC00 + (u and 0x3FF)).toChar()
                        "$high$low"
                    }
                    else -> null
                }
            }
        } else {
            null
        }
    }
}
