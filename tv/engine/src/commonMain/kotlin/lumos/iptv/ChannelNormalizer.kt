package lumos.iptv

/**
 * Cleans provider stream names, parses technical quality metadata, detects live event channels,
 * filters non-UK/foreign bouquets when requested, merges multi-source duplicates into
 * [CanonicalChannel]s, and ranks stream variants.
 */
object ChannelNormalizer {
    private const val DECORATIVE_SYMBOLS = "|★☆●•►▶▪◆»/◉◎○☼⚽▼▲"

    private val PREFIX_24_7_REGEX = Regex(
        """^(?i)24[/x]7-([A-Za-z]{2,3})(?=$|[\s|:•●★☆►▶▪◆»/-])\s*(?:[|:•●★☆►▶▪◆»/-]\s*)?"""
    )

    private val PREFIX_TAG_REGEX = Regex(
        """^(?:\[\s*([A-Za-z0-9+_-]{1,12})\s*\]|\(\s*([A-Za-z0-9+_-]{1,12})\s*\)|([A-Za-z0-9+_-]{1,12})\s*(?:[|:•●★☆►▶▪◆»/]|-(?=\s|$)))\s*"""
    )

    private val KNOWN_PREFIX_TOKENS = setOf(
        "uk", "gb", "us", "usa", "ie", "irl", "en", "eng", "ca", "can", "au", "aus", "nz",
        "vip", "raw", "4k", "8k", "uhd", "fhd", "hd", "sd", "hevc", "h265", "50fps", "60fps",
        "live", "ppv", "prime", "sky", "bt", "tnt", "uktv", "eu", "int",
    )

    private val FOREIGN_CODES = setOf(
        "fr", "de", "es", "it", "pt", "br", "nl", "be", "pl", "tr", "ar", "ru",
        "ro", "al", "gr", "in", "pk", "cn", "jp", "kr", "se", "no", "dk", "fi",
        "cz", "hu", "bg", "hr", "rs", "ua", "latam", "afr", "af", "mx", "ar2",
        "hi", "pa", "zh", "ta", "te", "ml", "kn", "mr", "gu", "bn", "ur", "fa",
        "ku", "ps", "so", "sw", "tl", "vi", "ms", "sr", "fil", "sk", "si", "ba",
        "mk", "ch", "at", "za", "il", "ir", "iq", "sa", "eg", "ma", "tn", "dz",
        "lb", "ae", "ph", "id", "my", "sg", "th", "vn", "tw", "hk", "bd", "lk",
        "am", "ge", "az", "kz", "lt", "lv", "ee", "is", "mt", "cy", "co", "cl",
        "pe", "ve", "ec", "uy", "py", "bo", "cr", "do", "pr", "cu", "gt", "exyu",
        "balkan", "nordic", "scandi", "arab", "kurd", "fra", "ger", "deu", "esp",
        "ita", "por", "bra", "ned", "pol", "tur", "rus", "gre", "rom", "alb",
        "hun", "cze", "bul", "cro", "srb", "swe", "nor", "den", "fin", "mex",
        "ind", "pak", "latino",
        "su", "la", "cg", "ag", "asia", "rx", "me", "kw", "qa", "om", "bh", "jo",
        "sy", "ye", "ly", "sd", "et", "ng", "ke", "gh", "tz", "ug", "cm", "sn",
        "ci", "mz", "ao", "zw", "zm", "bw", "na", "mw", "rw", "bi", "bf", "ne",
        "td", "ga", "cd", "gn", "sl", "lr", "tg", "bj", "gm", "cv", "sc", "mu",
        "mg", "km", "dj", "er",
    )

    private val FOREIGN_GROUP_KEYWORDS = Regex(
        """(?i)\b(france|french|germany|german|deutsch|spain|spanish|espa[nñ]a|italy|italian|italia|portugal|portuguese|brazil|brazilian|brasil|turkey|turkish|t[uü]rk|t[uü]rkiye|haber|diziler|belgesel|arabic|arab|poland|polish|polska|netherlands|dutch|nederland|algemeen|kinderen|documentaire|muziek|vermaak|belgium|romania|romanian|albania|albanian|greece|greek|russia|russian|ukraine|ukrainian|suriname|montenegro|afghanistan|india|indian|hindi|punjabi|tamil|pakistan|urdu|bengali|yupp|latino|latam|mexico|mexican|argentina|colombia|chile|peru|venezuela|ecuador|bolivia|paraguay|uruguay|africa|scandinavia|sweden|swedish|norway|norwegian|denmark|danish|finland|finnish|czech|hungary|hungarian|bulgaria|croatia|serbia|balkan|ex-?yu|china|chinese|japan|japanese|korea|korean|vietnam|thai|israel|hebrew|persian|iran|kurdish|filipino|philippines|philipines|tagalog|gujarati|malayalam|telugu|marathi|cantonese|mandarin|generale|bambini|regionali|dokumentar|relax|radio\s*mix|radio\s*live|adult|xxx|18\+)\b"""
    )

    private val TRAILING_FR_GROUP_REGEX = Regex("""(?i)(?:\s+fr)$""")

    private val BLOAT_NON_LINEAR_GROUP_REGEX = Regex(
        """(?i)\b(24[/x]7|series|replay|reality\s+show\s+tv|sky\s+store|sky\s+mix\s+docs|vod|on\s*demand)\b"""
    )

    private val BLOAT_OVERFLOW_GROUP_REGEX = Regex(
        """(?i)(?:^(?:\[\s*)?(?:US|USA)(?:\s*\])?\s*[|:/-]\s*(?:ROKU|AT&T|PRIME|NBC|FOX|CBS|ABC|CW|TV\b|CINEMA\s+TV\s+SHOWS|ESPN\+\s*PPV|FLO\b|DISNEY\+\s*PPV)|^8K\s*[|:/-]\s*SPORT\s+ON\s+AIR)"""
    )

    private val BRACKETED_TECH_REGEX = Regex(
        """(?i)[\[(]\s*(?:8K|UHD|4K|3840[PI]?|2160P?|FHD|FULL\s*HD|1080[PI]?|HD|720[PI]?|576[PI]?|480[PI]?|SD|RAW|DIRECT|BACKUP|BKP|BK|MIRROR|FAILOVER|HEVC|H\.?265|H\.?264|x265|x264|AVC|AV1|HDR10\+?|HDR|HLG|DOLBY\s*AUDIO|DOLBY|50\s*FPS|60\s*FPS|25\s*FPS|30\s*FPS|50P|60P|HFR|VIP|UK|US|USA|IE|IRL|EN|MULTI-?SUB|MULTISUB|MULTI\s*AUDIO|LOW|ALT)\s*[\])]"""
    )

    private val STANDALONE_TECH_REGEX = Regex(
        """(?i)(?:^|\s)(?:8K|UHD|4K|3840[PI]?|2160[PI]?(?:50|60)?|FHD|FULL\s*HD|1080[PI]?(?:50|60)?|720[PI]?(?:50|60)?|576[PI]?|480[PI]?|HD|SD|RAW|BACKUP|BKP|MIRROR|FAILOVER|HEVC|H\.?265|H\.?264|x265|x264|AVC|AV1|HDR10\+?|HDR|HLG|DOLBY\s*AUDIO|DOLBY|50\s*FPS|60\s*FPS|25\s*FPS|30\s*FPS|50HZ|60HZ|50P|60P|HFR|VIP|MULTI-?SUB|MULTISUB|MULTI\s*AUDIO)(?=\s|$)"""
    )

    private val EVENT_NAME_REGEX = Regex(
        """(?i)\b(?:PPV|PAY\s*PER\s*VIEW|LIVE\s*EVENT|EVENT\s*\d+|MATCH\s*\d+|EPL\s*\d+|SPFL\s*\d+|UCL\s*\d+|UFC\s*\d+|FIGHT\s*NIGHT|BOX\s*OFFICE|RED\s*BUTTON|IFOLLOW|HUB\s*\d+|STREAM\s*\d+|VS\.?|V\.)\b"""
    )

    private val EVENT_GROUP_REGEX = Regex(
        """(?i)\b(?:PPV|EVENTS?|LIVE\s*EVENTS?|MATCHDAY|MATCH\s*DAY|IFOLLOW|EPL\s*PASS|EPL|BOX\s*OFFICE|FIXTURES|CLUB\s*PPV|FOLLOW\s*YOUR\s*TEAM)\b"""
    )

    private val FPS_REGEX = Regex("""(?i)(?:\b(50|60|25|30)\s*(?:fps|hz)\b|\b(50|60)p\b|(?:2160|1080|720)[pi]?(50|60)\b)""")

    private val HEIGHT_2160_REGEX = Regex("""(?i)\b(3840x2160|3840[pi]?|2160[pi]?(?:50|60)?|8K|4K|UHD)\b""")
    private val HEIGHT_1080_REGEX = Regex("""(?i)\b(1920x1080|1080[pi]?(?:50|60)?|FHD|FULL\s*HD|FULLHD)\b""")
    private val HEIGHT_720_REGEX = Regex("""(?i)\b(1280x720|720[pi]?(?:50|60)?|HD)\b""")
    private val HEIGHT_576_REGEX = Regex("""(?i)\b(576[pi]?|SD)\b""")
    private val HEIGHT_480_REGEX = Regex("""(?i)\b(480[pi]?)\b""")
    private val HFR_REGEX = Regex("""(?i)\bHFR\b""")
    private val CODEC_HEVC_REGEX = Regex("""(?i)\b(HEVC|H\.?265|x265)\b""")
    private val CODEC_H264_REGEX = Regex("""(?i)\b(H\.?264|AVC|x264)\b""")
    private val CODEC_AV1_REGEX = Regex("""(?i)\bAV1\b""")
    private val HDR_WORD_REGEX = Regex("""(?i)\b(HDR10\+?|HDR|HLG|DOLBY\s*VISION)\b""")
    private val HDR_DV_BRACKET_REGEX = Regex("""(?i)[\[(]\s*DV\s*[\])]""")
    private val RAW_STREAM_REGEX = Regex("""(?i)\b(RAW|DIRECT)\b""")
    private val BACKUP_WORD_REGEX = Regex("""(?i)\b(BACKUP|BKP|MIRROR|FAILOVER)\b""")
    private val BACKUP_ALT_BRACKET_REGEX = Regex("""(?i)[\[(]\s*(?:ALT|BK)\s*[\])]""")

    private val PIPE_SEPARATOR_REGEX = Regex("""\s*\|\s*|\s+/\s+""")
    private val INLINE_DECORATIVE_REGEX = Regex("""[\u25C9\u25CE\u25CB\u263C\u26BD\u25BC\u25B2][\uFE0E\uFE0F]?""")
    private val LEADING_PUNCT_REGEX = Regex("""^\s*[-:|/•●★☆►▶▪◆»◉◎○☼⚽▼▲]+\s*""")
    private val TRAILING_PUNCT_REGEX = Regex("""\s*[-:|/•●★☆►▶▪◆»◉◎○☼⚽▼▲]+\s*$""")
    private val MULTI_SPACE_REGEX = Regex("""\s+""")

    private val REGION_PREFIX_CODES = setOf("uk", "ie", "irl", "us", "usa", "ca", "au", "nz")
    private val REGION_PATTERNS = listOf(
        Regex("""\blondon\b""") to "London",
        Regex("""\bscotland\b""") to "Scotland",
        Regex("""\bwales\b""") to "Wales",
        Regex("""\bnorthern ireland\b""") to "Northern Ireland",
        Regex("""\bmidlands\b""") to "Midlands",
        Regex("""\byorkshire\b""") to "Yorkshire",
        Regex("""\bgranada\b""") to "Granada",
    )

    private val CAT_IRELAND_REGEX = Regex("""\b(ireland|irish|ie|rte|tg4)\b""")
    private val CAT_US_REGEX = Regex("""\b(us|usa|united states|american)\b""")
    private val CAT_SPORTS_REGEX = Regex("""\b(sport|sports|football|soccer|racing|golf|cricket|f1|ufc|boxing|tennis|rugby|darts)\b""")
    private val CAT_MOVIES_REGEX = Regex("""\b(movie|movies|cinema|film|films)\b""")
    private val CAT_NEWS_REGEX = Regex("""\b(news|parliament)\b""")
    private val CAT_DOC_REGEX = Regex("""\b(doc|documentary|documentaries|discovery|history|nature|science|wildlife)\b""")
    private val CAT_KIDS_REGEX = Regex("""\b(kids|children|cartoon|junior|nick|cbeebies|cbbc)\b""")
    private val CAT_MUSIC_REGEX = Regex("""\b(music|mtv|hits|rock|clubland|radio)\b""")
    private val CAT_TERRESTRIAL_REGEX = Regex("""\b(terrestrial|freeview|uk tv|general)\b""")
    private val CAT_ENTERTAINMENT_REGEX = Regex("""\b(entertainment|drama|comedy|lifestyle)\b""")

    /** Folds Unicode superscripts, Phonetic Extensions, and modifier letters into ASCII equivalents. */
    internal fun foldSuperscripts(text: String): String {
        var firstChanged = -1
        for (i in text.indices) {
            val ch = text[i]
            if (ch.code >= 0x00B2 && foldSuperscriptChar(ch) != ch) {
                firstChanged = i
                break
            }
        }
        if (firstChanged < 0) return text
        return buildString(text.length) {
            append(text, 0, firstChanged)
            for (i in firstChanged until text.length) {
                append(foldSuperscriptChar(text[i]))
            }
        }
    }

    private fun foldSuperscriptChar(ch: Char): Char = when (ch) {
        '\u1D2C', '\u1D00' -> 'A'
        '\u1D2E', '\u0299' -> 'B'
        '\uA7F2', '\u1D04' -> 'C'
        '\u1D30', '\u1D05' -> 'D'
        '\u1D31', '\u1D07' -> 'E'
        '\uA7F3', '\uA730' -> 'F'
        '\u1D33', '\u0262' -> 'G'
        '\u1D34', '\u029C' -> 'H'
        '\u1D35', '\u1DA6', '\u026A' -> 'I'
        '\u1D36', '\u1D0A' -> 'J'
        '\u1D37', '\u1D0B' -> 'K'
        '\u1D38', '\u1DAB', '\u029F' -> 'L'
        '\u1D39', '\u1D0D' -> 'M'
        '\u1D3A', '\u1DB0', '\u0274' -> 'N'
        '\u1D3C', '\u1D0F' -> 'O'
        '\u1D3E', '\u1D18' -> 'P'
        '\uA7F4', '\uA7AF' -> 'Q'
        '\u1D3F', '\u0280' -> 'R'
        '\uA731' -> 'S'
        '\u1D40', '\u1D1B' -> 'T'
        '\u1D41', '\u1DB8', '\u1D1C' -> 'U'
        '\u2C7D', '\u1D20' -> 'V'
        '\u1D42', '\u1D21' -> 'W'
        '\u028F' -> 'Y'
        '\u1D22' -> 'Z'
        '\u1D43' -> 'a'
        '\u1D47' -> 'b'
        '\u1D9C' -> 'c'
        '\u1D48' -> 'd'
        '\u1D49' -> 'e'
        '\u1DA0' -> 'f'
        '\u1D4D' -> 'g'
        '\u02B0' -> 'h'
        '\u2071' -> 'i'
        '\u02B2' -> 'j'
        '\u1D4F' -> 'k'
        '\u02E1' -> 'l'
        '\u1D50' -> 'm'
        '\u207F' -> 'n'
        '\u1D52' -> 'o'
        '\u1D56' -> 'p'
        '\u02B3' -> 'r'
        '\u02E2' -> 's'
        '\u1D57' -> 't'
        '\u1D58' -> 'u'
        '\u1D5B' -> 'v'
        '\u02B7' -> 'w'
        '\u02E3' -> 'x'
        '\u02B8' -> 'y'
        '\u1DBB' -> 'z'
        '\u2070' -> '0'
        '\u00B9' -> '1'
        '\u00B2' -> '2'
        '\u00B3' -> '3'
        '\u2074' -> '4'
        '\u2075' -> '5'
        '\u2076' -> '6'
        '\u2077' -> '7'
        '\u2078' -> '8'
        '\u2079' -> '9'
        '\u207D' -> '('
        '\u207E' -> ')'
        else -> ch
    }

    /** Extracts resolution, frame rate, codec, HDR, RAW, backup, and region flags from [rawName]. */
    fun parseQuality(rawName: String): StreamQuality {
        val folded = foldSuperscripts(rawName)
        val height = when {
            HEIGHT_2160_REGEX.containsMatchIn(folded) -> 2160
            HEIGHT_1080_REGEX.containsMatchIn(folded) -> 1080
            HEIGHT_720_REGEX.containsMatchIn(folded) -> 720
            HEIGHT_576_REGEX.containsMatchIn(folded) -> 576
            HEIGHT_480_REGEX.containsMatchIn(folded) -> 480
            else -> 0
        }

        val fpsMatch = FPS_REGEX.find(folded)
        val fps = fpsMatch?.let { m ->
            (m.groupValues[1].ifEmpty { m.groupValues[2].ifEmpty { m.groupValues[3] } }).toIntOrNull()
        } ?: if (HFR_REGEX.containsMatchIn(folded)) 50 else null

        val codec = when {
            CODEC_HEVC_REGEX.containsMatchIn(folded) -> "HEVC"
            CODEC_H264_REGEX.containsMatchIn(folded) -> "H.264"
            CODEC_AV1_REGEX.containsMatchIn(folded) -> "AV1"
            else -> null
        }

        val hdr = HDR_WORD_REGEX.containsMatchIn(folded) ||
            HDR_DV_BRACKET_REGEX.containsMatchIn(folded)
        val isRaw = RAW_STREAM_REGEX.containsMatchIn(folded)
        val isBackup = BACKUP_WORD_REGEX.containsMatchIn(folded) ||
            BACKUP_ALT_BRACKET_REGEX.containsMatchIn(folded)
        val region = extractRegion(folded)

        return StreamQuality(
            height = height,
            fps = fps,
            codec = codec,
            hdr = hdr,
            isRaw = isRaw,
            isBackup = isBackup,
            region = region,
        )
    }

    /** Strips country/provider prefixes and technical quality tags while preserving `+1` and channel names. */
    fun cleanChannelTitle(rawName: String): String {
        val folded = foldSuperscripts(rawName).trim()
        if (folded.isEmpty()) return "Unknown Channel"
        var current = folded

        while (true) {
            val (token, endIdx) = extractPrefixMatch(current) ?: break
            if (token in KNOWN_PREFIX_TOKENS || token in FOREIGN_CODES) {
                current = current.substring(endIdx).trim()
            } else {
                break
            }
        }

        val afterDeco = skipLeadingDecorations(current, startIndex = 0, skipFlagPairs = true)
        if (afterDeco > 0) {
            current = current.substring(afterDeco)
        }

        current = INLINE_DECORATIVE_REGEX.replace(current, " ")
        current = PIPE_SEPARATOR_REGEX.replace(current, " ")
        current = BRACKETED_TECH_REGEX.replace(current, " ")
        current = STANDALONE_TECH_REGEX.replace(current, " ")
        current = current
            .replace(LEADING_PUNCT_REGEX, "")
            .replace(TRAILING_PUNCT_REGEX, "")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()

        return current.ifBlank { folded.ifBlank { "Unknown Channel" } }
    }

    /** True when [rawName] or [groupTitle] represents a PPV or live sports event slot rather than a fixed linear channel. */
    fun detectEventChannel(rawName: String, groupTitle: String): Boolean {
        val foldedGroup = foldSuperscripts(groupTitle)
        if (EVENT_GROUP_REGEX.containsMatchIn(foldedGroup)) return true
        val foldedRaw = foldSuperscripts(rawName)
        val cleaned = cleanChannelTitle(foldedRaw)
        if (EVENT_NAME_REGEX.containsMatchIn(cleaned) || EVENT_NAME_REGEX.containsMatchIn(foldedRaw)) {
            val registryHit = UkChannelRegistry.match(cleaned)
            if (registryHit == null || registryHit.id.contains("box.office")) return true
        }
        return false
    }

    /**
     * True when [groupTitle] belongs to a non-English/foreign bouquet or adult group based on
     * prefix country codes, bouquet keywords, or non-Latin scripts.
     */
    fun isForeignGroup(groupTitle: String): Boolean {
        val trimmed = foldSuperscripts(groupTitle).trim()
        if (trimmed.isEmpty()) return false
        if (hasForeignPrefixTag(trimmed)) return true
        val start = skipLeadingDecorations(trimmed, startIndex = 0, skipFlagPairs = true)
        val standalone = trimmed.substring(start)
            .trimEnd { it.isWhitespace() || it in DECORATIVE_SYMBOLS || it == '\uFE0E' || it == '\uFE0F' }
            .lowercase()
        if (standalone in FOREIGN_CODES) return true
        if (TRAILING_FR_GROUP_REGEX.containsMatchIn(trimmed)) return true
        if (FOREIGN_GROUP_KEYWORDS.containsMatchIn(trimmed)) return true
        if (containsNonLatinScript(trimmed)) return true
        return false
    }

    /**
     * True when [groupTitle] is a non-linear 24/7, VOD/replay, or massive US/8K overflow bouquet
     * that should be excluded when filtering down to live UK/English linear TV.
     */
    fun isBloatNonLinearGroup(groupTitle: String): Boolean {
        val trimmed = foldSuperscripts(groupTitle).trim()
        if (trimmed.isEmpty()) return false
        return BLOAT_NON_LINEAR_GROUP_REGEX.containsMatchIn(trimmed) ||
            BLOAT_OVERFLOW_GROUP_REGEX.containsMatchIn(trimmed)
    }

    /**
     * True when [rawName] starts with a foreign country prefix tag or contains non-Latin script.
     * Deliberately avoids keyword checks so English channels like "France 24 English" are preserved.
     */
    fun isForeignRawName(rawName: String): Boolean {
        val trimmed = foldSuperscripts(rawName).trim()
        if (trimmed.isEmpty()) return false
        if (hasForeignPrefixTag(trimmed)) return true
        if (containsNonLatinScript(trimmed)) return true
        return false
    }

    internal fun isFilteredGroup(groupTitle: String): Boolean =
        isForeignGroup(groupTitle) || isBloatNonLinearGroup(groupTitle)

    internal fun isForeignEntry(
        rawName: String,
        groupTitle: String,
        groupMemo: MutableMap<String, Boolean>,
    ): Boolean {
        val groupForeign = groupMemo.getOrPut(groupTitle) { isFilteredGroup(groupTitle) }
        return groupForeign || isForeignRawName(rawName)
    }

    /**
     * Normalizes, filters, deduplicates, merges, and ranks raw streams into [CanonicalChannel]s.
     */
    fun normalizeAndMerge(
        sources: List<IptvSource>,
        rawStreams: List<StreamSource>,
        health: StreamHealthTracker? = null,
        prefs: IptvProfilePrefs = IptvProfilePrefs(),
        nowMs: Long = 0L,
        maxHeight: Int = 2160,
        bandwidthBps: Long? = null,
    ): List<CanonicalChannel> {
        val sourceById = sources.associateBy { it.id }
        val sourcePriorities = sources.associate { it.id to it.priority }
        val foreignGroupMemo = if (!prefs.showNonUk) HashMap<String, Boolean>() else null

        val deduped = linkedMapOf<String, StreamSource>()
        for (stream in rawStreams) {
            val source = sourceById[stream.sourceId]
            if (source != null && !source.enabled) continue
            if (foreignGroupMemo != null && isForeignEntry(stream.rawName, stream.groupTitle, foreignGroupMemo)) {
                continue
            }

            val dedupKey = if (stream.requiresResolve && !stream.stalkerCmd.isNullOrBlank()) {
                "stalker:${stream.sourceId}:${stream.stalkerCmd.trim()}"
            } else {
                stream.streamUrl.trim()
            }
            val existing = deduped[dedupKey]
            if (existing == null) {
                deduped[dedupKey] = stream
            } else {
                val existingPrio = sourcePriorities[existing.sourceId] ?: 0
                val newPrio = sourcePriorities[stream.sourceId] ?: 0
                if (newPrio > existingPrio) {
                    deduped[dedupKey] = stream
                }
            }
        }

        val groups = linkedMapOf<String, ChannelAccumulator>()
        for (stream in deduped.values) {
            val cleaned = cleanChannelTitle(stream.rawName)
            val entry = UkChannelRegistry.match(cleaned, stream.tvgId, stream.groupTitle)
            val isEvent = if (entry != null) {
                entry.id.contains("box.office")
            } else {
                detectEventChannel(stream.rawName, stream.groupTitle)
            }

            val canonicalId = entry?.id ?: buildCustomChannelId(cleaned, isEvent)
            val category = entry?.category ?: inferCategory(stream.groupTitle, cleaned, isEvent)

            if (canonicalId in prefs.hiddenChannels) continue
            if (category.name in prefs.hiddenCategories || category.label in prefs.hiddenCategories) continue

            val acc = groups.getOrPut(canonicalId) {
                ChannelAccumulator(
                    id = canonicalId,
                    number = entry?.number,
                    name = entry?.name ?: cleaned,
                    category = category,
                    defaultEpgId = entry?.epgIds?.firstOrNull(),
                    isEventChannel = isEvent,
                )
            }
            acc.streams.add(stream)
        }

        return groups.values.map { acc ->
            val ranked = StreamRanker.rank(
                streams = acc.streams,
                category = acc.category,
                isEventChannel = acc.isEventChannel,
                health = health,
                preferredStreamId = prefs.preferredStreamByChannel[acc.id],
                sourcePriorities = sourcePriorities,
                nowMs = nowMs,
                maxHeight = maxHeight,
                bandwidthBps = bandwidthBps,
            )
            val logoUrl = ranked.firstNotNullOfOrNull { it.logoUrl?.takeIf(String::isNotBlank) }
            val streamTvgId = ranked.firstNotNullOfOrNull { it.tvgId?.takeIf(String::isNotBlank) }
            val epgId = prefs.epgOverrides[acc.id] ?: acc.defaultEpgId ?: streamTvgId
            val maxCatchupDays = ranked.mapNotNull { it.catchup?.takeIf { c -> c.days > 0 }?.days }.maxOrNull() ?: 0

            CanonicalChannel(
                id = acc.id,
                number = acc.number,
                name = acc.name,
                category = acc.category,
                logoUrl = logoUrl,
                epgId = epgId,
                streams = ranked,
                isEventChannel = acc.isEventChannel,
                hasCatchup = maxCatchupDays > 0,
                maxCatchupDays = maxCatchupDays,
            )
        }.sortedWith(
            compareBy<CanonicalChannel>(
                { it.category.order },
                { it.number ?: Int.MAX_VALUE },
                { it.name.lowercase() },
            )
        )
    }

    private fun isRegionalIndicatorAt(text: String, idx: Int): Boolean =
        idx + 1 < text.length && text[idx] == '\uD83C' && text[idx + 1] in '\uDDE6'..'\uDDFF'

    private fun skipLeadingDecorations(
        text: String,
        startIndex: Int = 0,
        skipFlagPairs: Boolean = true,
    ): Int {
        var i = startIndex
        val len = text.length
        while (i < len) {
            val ch = text[i]
            if (ch.isWhitespace() || ch in DECORATIVE_SYMBOLS || ch == '\uFE0E' || ch == '\uFE0F') {
                i++
            } else if (skipFlagPairs && isRegionalIndicatorAt(text, i)) {
                i += 2
            } else {
                break
            }
        }
        return i
    }

    private fun extractPrefixMatch(text: String): Pair<String, Int>? {
        var i = skipLeadingDecorations(text, startIndex = 0, skipFlagPairs = false)
        if (i >= text.length) return null

        if (isRegionalIndicatorAt(text, i) && isRegionalIndicatorAt(text, i + 2)) {
            val c1 = 'a' + (text[i + 1].code - 0xDDE6)
            val c2 = 'a' + (text[i + 3].code - 0xDDE6)
            val flagCode = "$c1$c2"
            var end = i + 4
            while (end < text.length && (text[end].isWhitespace() || text[end] == '\uFE0E' || text[end] == '\uFE0F')) {
                end++
            }
            if (flagCode in FOREIGN_CODES || flagCode in KNOWN_PREFIX_TOKENS) {
                return flagCode to end
            }
            i = skipLeadingDecorations(text, startIndex = end, skipFlagPairs = true)
        } else if (isRegionalIndicatorAt(text, i)) {
            i = skipLeadingDecorations(text, startIndex = i, skipFlagPairs = true)
        }

        if (i >= text.length) return null
        val sub = if (i == 0) text else text.substring(i)

        PREFIX_24_7_REGEX.find(sub)?.let { m ->
            val token = m.groupValues[1].lowercase()
            return token to (i + m.range.last + 1)
        }

        val m = PREFIX_TAG_REGEX.find(sub) ?: return null
        val token = m.groupValues[1].ifEmpty { m.groupValues[2].ifEmpty { m.groupValues[3] } }.lowercase()
        return token to (i + m.range.last + 1)
    }

    private fun hasForeignPrefixTag(trimmed: String): Boolean {
        var current = trimmed
        while (true) {
            val (token, endIdx) = extractPrefixMatch(current) ?: return false
            if (token in FOREIGN_CODES) return true
            if (token in KNOWN_PREFIX_TOKENS) {
                current = current.substring(endIdx).trim()
            } else {
                return false
            }
        }
    }

    private fun extractRegion(rawName: String): String? {
        val prefix = extractPrefixMatch(rawName.trim())
        if (prefix != null && prefix.first in REGION_PREFIX_CODES) {
            return when (prefix.first) {
                "irl" -> "IE"
                "usa" -> "US"
                else -> prefix.first.uppercase()
            }
        }
        val lower = rawName.lowercase()
        for ((regex, label) in REGION_PATTERNS) {
            if (regex.containsMatchIn(lower)) return label
        }
        return null
    }

    private fun isForeignStream(stream: StreamSource): Boolean =
        isForeignRawName(stream.rawName) || isForeignGroup(stream.groupTitle)

    private fun containsNonLatinScript(text: String): Boolean {
        for (ch in text) {
            val code = ch.code
            // Exclude Phonetic Extensions (0x1D00..0x1DBF), Combining Diacritical Marks Supplement
            // (0x1DC0..0x1DFF), and Latin Extended Additional (0x1E00..0x1EFF) used for superscript quality tags.
            val isForeignScript = code in 0x0370..0x1CFF ||
                code in 0x1F00..0x1FFF ||
                code in 0x2E80..0xD7AF ||
                code in 0xF900..0xFAFF ||
                code in 0xFB1D..0xFDFF ||
                code in 0xFE70..0xFEFF
            if (isForeignScript) return true
        }
        return false
    }

    private fun buildCustomChannelId(cleanedName: String, isEvent: Boolean): String {
        val key = UkChannelRegistry.normalizeNameKey(cleanedName)
        val slug = key.replace(' ', '.').ifBlank { "channel" }
        return if (isEvent) "event.$slug" else "custom.$slug"
    }

    private fun inferCategory(groupTitle: String, cleanedName: String, isEvent: Boolean): ChannelCategory {
        if (isEvent) return ChannelCategory.EVENTS
        val g = groupTitle.lowercase()
        val n = cleanedName.lowercase()
        val combined = "$g $n"
        return when {
            CAT_IRELAND_REGEX.containsMatchIn(g) -> ChannelCategory.IRELAND
            CAT_US_REGEX.containsMatchIn(g) -> ChannelCategory.US_ENGLISH
            CAT_SPORTS_REGEX.containsMatchIn(combined) -> ChannelCategory.SPORTS
            CAT_MOVIES_REGEX.containsMatchIn(combined) -> ChannelCategory.MOVIES
            CAT_NEWS_REGEX.containsMatchIn(combined) -> ChannelCategory.NEWS
            CAT_DOC_REGEX.containsMatchIn(combined) -> ChannelCategory.DOCUMENTARY
            CAT_KIDS_REGEX.containsMatchIn(combined) -> ChannelCategory.KIDS
            CAT_MUSIC_REGEX.containsMatchIn(combined) -> ChannelCategory.MUSIC
            CAT_TERRESTRIAL_REGEX.containsMatchIn(g) -> ChannelCategory.TERRESTRIAL
            CAT_ENTERTAINMENT_REGEX.containsMatchIn(g) -> ChannelCategory.ENTERTAINMENT
            else -> ChannelCategory.OTHER
        }
    }

    private class ChannelAccumulator(
        val id: String,
        val number: Int?,
        val name: String,
        val category: ChannelCategory,
        val defaultEpgId: String?,
        val isEventChannel: Boolean,
    ) {
        val streams = mutableListOf<StreamSource>()
    }
}
