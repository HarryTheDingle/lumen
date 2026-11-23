package lumos.iptv

private val NON_LIVE_SPORT_REGEX = Regex(
    """(?i)\b(highlights?|replay|repeat|classic|documentary|news|roundup|review|preview|countdown|podcast|magazine|gossip)\b"""
)

private val LIVE_MARKER_REGEX = Regex(
    """(?i)(?:\blive\b|\bvs\.?\b|\bv\s+[A-Z0-9]|\bgrand\s*prix\b|\bufc\s*\d+\b|\bfight\s*night\b|\bsuper\s*sunday\b|\bmonday\s*night\s*football\b|\bfriday\s*night\s*football\b|\btest\s*match\b|\bsix\s*nations\b|\bmatchday\s*live\b)"""
)

private val SPORT_CATEGORIES = listOf(
    "Football" to Regex("""(?i)\b(football|soccer|premier\s*league|champions\s*league|europa\s*league|fa\s*cup|carabao|efl|spfl|la\s*liga|bundesliga|serie\s*a|super\s*sunday)\b"""),
    "Motorsport" to Regex("""(?i)\b(formula\s*1|f1|motorsport|motogp|grand\s*prix|nascar|indycar|rally)\b"""),
    "Cricket" to Regex("""(?i)\b(cricket|test\s*match|t20|odi|ipl|the\s*ashes|hundred)\b"""),
    "Rugby" to Regex("""(?i)\b(rugby|six\s*nations|premiership\s*rugby|super\s*league|champions\s*cup)\b"""),
    "Tennis" to Regex("""(?i)\b(tennis|wimbledon|atp|wta|roland\s*garros|us\s*open\s*tennis|australian\s*open)\b"""),
    "Golf" to Regex("""(?i)\b(golf|pga|dp\s*world|ryder\s*cup|the\s*open|masters)\b"""),
    "Boxing" to Regex("""(?i)\b(boxing|heavyweight|title\s*fight|matchroom|queensberry)\b"""),
    "UFC / MMA" to Regex("""(?i)\b(ufc|mma|bellator|cage\s*warriors)\b"""),
    "Darts" to Regex("""(?i)\b(darts|pdc|premier\s*league\s*darts|world\s*darts)\b"""),
    "Snooker" to Regex("""(?i)\b(snooker|crucible|world\s*snooker)\b"""),
    "Horse Racing" to Regex("""(?i)\b(horse\s*racing|racing|cheltenham|ascot|grand\s*national)\b"""),
    "Basketball" to Regex("""(?i)\b(basketball|nba|wnba|euroleague)\b"""),
    "American Football" to Regex("""(?i)\b(nfl|american\s*football|super\s*bowl)\b"""),
    "Cycling" to Regex("""(?i)\b(cycling|tour\s*de\s*france|giro|vuelta)\b"""),
    "Athletics" to Regex("""(?i)\b(athletics|diamond\s*league|olympics)\b"""),
)

private val GENERIC_SPORT_REGEX = Regex("""(?i)\b(live\s*sport|sports?)\b""")

/** Detects live sport status and normalized sport discipline tag from EPG text fields. */
internal fun detectSportTag(
    title: String,
    category: String? = null,
    subtitle: String? = null,
): Pair<Boolean, String?> {
    val combined = listOfNotNull(title, category, subtitle).joinToString(" ")
    if (NON_LIVE_SPORT_REGEX.containsMatchIn(title) ||
        (category != null && NON_LIVE_SPORT_REGEX.containsMatchIn(category))
    ) {
        return false to null
    }

    var detectedTag: String? = null
    for ((tag, regex) in SPORT_CATEGORIES) {
        if (regex.containsMatchIn(combined)) {
            detectedTag = tag
            break
        }
    }
    if (detectedTag == null && category != null && GENERIC_SPORT_REGEX.containsMatchIn(category)) {
        detectedTag = "Sport"
    }
    if (detectedTag == null) return false to null

    val hasLiveIndicator = LIVE_MARKER_REGEX.containsMatchIn(title) ||
        (category != null && (category.contains("live", ignoreCase = true) || LIVE_MARKER_REGEX.containsMatchIn(category))) ||
        (subtitle != null && LIVE_MARKER_REGEX.containsMatchIn(subtitle))

    return hasLiveIndicator to if (hasLiveIndicator) detectedTag else null
}

/**
 * In-memory EPG guide merging curated built-in UK guide data with provider XMLTV / Xtream / Stalker
 * listings, providing Now/Next, window queries, catch-up filtering, live sport discovery, and search.
 */
class EpgStore {
    private val builtInByChannel = linkedMapOf<String, MutableList<EpgProgramme>>()
    private val providerByChannel = linkedMapOf<String, MutableList<EpgProgramme>>()
    private val mergedCache = linkedMapOf<String, ChannelSchedule>()
    private val channelCache = linkedMapOf<String, CachedChannelSchedule>()
    private val displayNameToId = linkedMapOf<String, String>()
    private val guideChannelsList = mutableListOf<GuideChannel>()

    /** Stores/replaces curated TV guide channel metadata parsed from `/epg/uk`. */
    fun putGuideChannels(channels: List<GuideChannel>) {
        guideChannelsList.clear()
        guideChannelsList.addAll(channels)
    }

    /** Returns stored curated [GuideChannel] definitions in guide order. */
    fun guideChannels(): List<GuideChannel> = guideChannelsList.toList()

    /** Exports curated [GuideChannel] metadata for warm disk caching. */
    fun exportGuideChannels(): List<GuideChannel> = guideChannelsList.toList()

    /** Stores/replaces built-in UK EPG listings (taking precedence on overlapping time slots). */
    fun putBuiltInUkEpg(programmes: List<EpgProgramme>) {
        ingestInto(programmes, builtInByChannel)
    }

    /** Stores provider EPG listings (XMLTV, Xtream short/archive EPG, Stalker EPG). */
    fun putProviderEpg(programmes: List<EpgProgramme>) {
        ingestInto(programmes, providerByChannel)
    }

    /** Stores parsed XMLTV channel aliases and programmes into the provider layer. */
    fun putXmltv(result: XmltvParseResult) {
        for (ch in result.channels) {
            val normalizedId = ch.id.trim().lowercase()
            if (normalizedId.isEmpty()) continue
            for (name in ch.displayNames) {
                val key = UkChannelRegistry.normalizeNameKey(name)
                if (key.isNotEmpty() && key !in displayNameToId) {
                    displayNameToId[key] = normalizedId
                }
            }
        }
        channelCache.clear()
        putProviderEpg(result.programmes)
    }

    fun clear() {
        builtInByChannel.clear()
        providerByChannel.clear()
        mergedCache.clear()
        channelCache.clear()
        displayNameToId.clear()
        guideChannelsList.clear()
    }

    /** Exports built-in UK EPG programmes for warm disk caching. */
    fun exportBuiltInProgrammes(): List<EpgProgramme> = builtInByChannel.values.flatten()

    /** Exports provider EPG programmes for warm disk caching. */
    fun exportProviderProgrammes(): List<EpgProgramme> = providerByChannel.values.flatten()

    /** Exports XMLTV channel display-name aliases for warm disk caching. */
    fun exportChannelAliases(): Map<String, String> = displayNameToId.toMap()

    /** Restores XMLTV channel display-name aliases from a warm cache snapshot. */
    fun restoreChannelAliases(aliases: Map<String, String>) {
        var changed = false
        for ((k, v) in aliases) {
            if (k.isNotBlank() && v.isNotBlank() && k !in displayNameToId) {
                displayNameToId[k] = v
                changed = true
            }
        }
        if (changed) channelCache.clear()
    }

    /** Returns `(nowProgramme, nextProgramme)` for [channel] at [nowMs]. */
    fun nowAndNext(channel: CanonicalChannel, nowMs: Long): Pair<EpgProgramme?, EpgProgramme?> =
        nowAndNextFromSchedule(channelSchedule(channel), nowMs)

    /** Returns `(nowProgramme, nextProgramme)` for raw [channelId] at [nowMs]. */
    fun nowAndNext(channelId: String, nowMs: Long): Pair<EpgProgramme?, EpgProgramme?> =
        nowAndNextFromSchedule(scheduleForKey(channelId), nowMs)

    /** Returns programmes overlapping `[fromMs, toMs)` for [channel] via binary search on sorted schedules. */
    fun programmesInRange(channel: CanonicalChannel, fromMs: Long, toMs: Long): List<EpgProgramme> =
        programmesInRangeFromSchedule(channelSchedule(channel), fromMs, toMs)

    /** Returns programmes overlapping `[fromMs, toMs)` for [channelId] via binary search on sorted schedules. */
    fun programmesInRange(channelId: String, fromMs: Long, toMs: Long): List<EpgProgramme> =
        programmesInRangeFromSchedule(scheduleForKey(channelId), fromMs, toMs)

    /** Returns programmes overlapping `[fromMs, toMs)` for [channel], sorted by start time. */
    fun programmesInWindow(channel: CanonicalChannel, fromMs: Long, toMs: Long): List<EpgProgramme> =
        programmesInRange(channel, fromMs, toMs)

    /** Returns programmes overlapping `[fromMs, toMs)` for [channelId], sorted by start time. */
    fun programmesInWindow(channelId: String, fromMs: Long, toMs: Long): List<EpgProgramme> =
        programmesInRange(channelId, fromMs, toMs)

    /** Returns finished programmes eligible for catch-up on [channel] at [nowMs], newest first. */
    fun catchupProgrammes(channel: CanonicalChannel, nowMs: Long): List<EpgProgramme> {
        if (!channel.hasCatchup || channel.maxCatchupDays <= 0) return emptyList()
        return channelProgrammes(channel)
            .filter { it.isCatchupEligible(nowMs, channel.maxCatchupDays) }
            .sortedByDescending { it.startMs }
    }

    /** Returns all currently airing live sport programmes across [channels] at [nowMs]. */
    fun liveSportsNow(channels: List<CanonicalChannel>, nowMs: Long): List<Pair<CanonicalChannel, EpgProgramme>> {
        val out = mutableListOf<Pair<CanonicalChannel, EpgProgramme>>()
        for (ch in channels) {
            val current = nowAndNext(ch, nowMs).first ?: continue
            if (current.isLiveSport) {
                out.add(ch to current)
            }
        }
        return out
    }

    /** Searches channels plus current, upcoming, and catch-up programmes matching [query]. */
    fun search(
        query: String,
        channels: List<CanonicalChannel>,
        nowMs: Long,
        futureHorizonMs: Long = 48L * 3_600_000L,
    ): LiveSearchResults {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            return LiveSearchResults(emptyList(), emptyList(), emptyList(), emptyList())
        }

        val matchingChannels = channels.filter { ch ->
            ch.name.lowercase().contains(q) ||
                ch.streams.any { it.rawName.lowercase().contains(q) }
        }

        val onNow = mutableListOf<Pair<CanonicalChannel, EpgProgramme>>()
        val upcoming = mutableListOf<Pair<CanonicalChannel, EpgProgramme>>()
        val catchup = mutableListOf<Pair<CanonicalChannel, EpgProgramme>>()

        for (ch in channels) {
            val progs = channelProgrammes(ch)
            for (p in progs) {
                if (!matchesProgramme(p, q)) continue
                when {
                    p.isOnAt(nowMs) -> onNow.add(ch to p)
                    p.startMs > nowMs && p.startMs <= nowMs + futureHorizonMs -> upcoming.add(ch to p)
                    ch.hasCatchup && p.isCatchupEligible(nowMs, ch.maxCatchupDays) -> catchup.add(ch to p)
                }
            }
        }

        return LiveSearchResults(
            channels = matchingChannels,
            onNow = onNow,
            upcoming = upcoming.sortedBy { it.second.startMs },
            catchup = catchup.sortedByDescending { it.second.startMs },
        )
    }

    /** Returns the merged, chronologically sorted programme schedule for [channel]. */
    fun channelProgrammes(channel: CanonicalChannel): List<EpgProgramme> =
        channelSchedule(channel).programmes

    private fun channelSchedule(channel: CanonicalChannel): ChannelSchedule {
        channelCache[channel.id]?.let { cached ->
            if (cached.epgId == channel.epgId) return cached.schedule
        }

        val keys = resolveCandidateKeys(channel)
        val schedule = when {
            keys.isEmpty() -> EMPTY_SCHEDULE
            keys.size == 1 -> scheduleForKey(keys[0])
            else -> {
                val builtIn = keys.flatMap { builtInByChannel[it].orEmpty() }
                val provider = keys.flatMap { providerByChannel[it].orEmpty() }
                buildSchedule(mergeSchedules(builtIn, provider))
            }
        }
        channelCache[channel.id] = CachedChannelSchedule(channel.epgId, schedule)
        return schedule
    }

    private fun ingestInto(
        programmes: List<EpgProgramme>,
        target: MutableMap<String, MutableList<EpgProgramme>>,
    ) {
        if (programmes.isEmpty()) return
        val touchedKeys = mutableSetOf<String>()
        for (p in programmes) {
            if (p.endMs <= p.startMs || p.title.isBlank()) continue
            val key = p.channelId.trim().lowercase()
            if (key.isEmpty()) continue
            val list = target.getOrPut(key) { mutableListOf() }
            list.add(p)
            touchedKeys.add(key)
        }
        if (touchedKeys.isEmpty()) return
        for (key in touchedKeys) {
            mergedCache.remove(key)
            val list = target[key]
            if (list != null && list.size > 1 && !isSortedByStart(list)) {
                list.sortBy { it.startMs }
            }
        }
        channelCache.clear()
    }

    private fun resolveCandidateKeys(channel: CanonicalChannel): List<String> {
        val keys = linkedSetOf<String>()
        channel.epgId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let(keys::add)
        keys.add(channel.id.lowercase())
        UkChannelRegistry.findById(channel.id)?.epgIds?.forEach { keys.add(it.lowercase()) }
        for (stream in channel.streams) {
            stream.tvgId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let(keys::add)
        }
        if (displayNameToId.isNotEmpty()) {
            val nameKey = UkChannelRegistry.normalizeNameKey(channel.name)
            displayNameToId[nameKey]?.let(keys::add)
        }

        return keys.filter { it in builtInByChannel || it in providerByChannel }
    }

    private fun programmesForKey(channelId: String): List<EpgProgramme> =
        scheduleForKey(channelId).programmes

    private fun scheduleForKey(channelId: String): ChannelSchedule {
        val key = channelId.trim().lowercase()
        mergedCache[key]?.let { return it }
        val merged = mergeSchedules(
            builtIn = builtInByChannel[key].orEmpty(),
            provider = providerByChannel[key].orEmpty(),
        )
        val schedule = buildSchedule(merged)
        mergedCache[key] = schedule
        return schedule
    }

    private fun mergeSchedules(
        builtIn: List<EpgProgramme>,
        provider: List<EpgProgramme>,
    ): List<EpgProgramme> {
        if (builtIn.isEmpty() && provider.isEmpty()) return emptyList()
        val dedupBuiltIn = dedupWithinLayer(builtIn)
        val dedupProvider = dedupWithinLayer(provider)
        if (dedupBuiltIn.isEmpty()) return dedupProvider
        if (dedupProvider.isEmpty()) return dedupBuiltIn

        val result = dedupBuiltIn.toMutableList()
        for (prov in dedupProvider) {
            val overlapIdx = result.indexOfFirst { existing -> overlapsSignificantly(existing, prov) }
            if (overlapIdx >= 0) {
                val base = result[overlapIdx]
                result[overlapIdx] = enrichProgramme(primary = base, fallback = prov)
            } else {
                result.add(prov)
            }
        }
        return if (isSortedByStart(result)) result else result.sortedBy { it.startMs }
    }

    private fun dedupWithinLayer(list: List<EpgProgramme>): List<EpgProgramme> {
        if (list.size <= 1) return list
        val sorted = if (isSortedByStart(list)) list else list.sortedBy { it.startMs }
        val out = mutableListOf<EpgProgramme>()
        for (p in sorted) {
            val last = out.lastOrNull()
            if (last != null && last.startMs == p.startMs && last.endMs == p.endMs) {
                out[out.lastIndex] = enrichProgramme(primary = p, fallback = last)
            } else {
                out.add(p)
            }
        }
        return out
    }

    private fun isSortedByStart(list: List<EpgProgramme>): Boolean {
        for (i in 1 until list.size) {
            if (list[i].startMs < list[i - 1].startMs) return false
        }
        return true
    }

    private fun buildSchedule(programmes: List<EpgProgramme>): ChannelSchedule {
        if (programmes.isEmpty()) return EMPTY_SCHEDULE
        var maxDur = 0L
        var hasOverlaps = false
        var prevEnd = Long.MIN_VALUE
        for (p in programmes) {
            val dur = p.endMs - p.startMs
            if (dur > maxDur) maxDur = dur
            if (p.startMs < prevEnd) hasOverlaps = true
            if (p.endMs > prevEnd) prevEnd = p.endMs
        }
        return ChannelSchedule(programmes, maxDur, hasOverlaps)
    }

    private fun overlapsSignificantly(a: EpgProgramme, b: EpgProgramme): Boolean {
        val overlapStart = maxOf(a.startMs, b.startMs)
        val overlapEnd = minOf(a.endMs, b.endMs)
        val overlap = overlapEnd - overlapStart
        if (overlap <= 0L) return false
        val minDur = minOf(a.endMs - a.startMs, b.endMs - b.startMs)
        return overlap * 2L >= minDur
    }

    private fun enrichProgramme(primary: EpgProgramme, fallback: EpgProgramme): EpgProgramme =
        primary.copy(
            subtitle = primary.subtitle ?: fallback.subtitle,
            description = primary.description ?: fallback.description,
            category = primary.category ?: fallback.category,
            iconUrl = primary.iconUrl ?: fallback.iconUrl,
            isLiveSport = primary.isLiveSport || fallback.isLiveSport,
            sportTag = primary.sportTag ?: fallback.sportTag,
            hasCatchup = primary.hasCatchup || fallback.hasCatchup,
        )

    internal fun nowAndNextFromList(
        list: List<EpgProgramme>,
        nowMs: Long,
    ): Pair<EpgProgramme?, EpgProgramme?> {
        if (list.isEmpty()) return null to null
        val sorted = if (isSortedByStart(list)) list else list.sortedBy { it.startMs }
        return nowAndNextFromSchedule(buildSchedule(sorted), nowMs)
    }

    private fun programmesInRangeFromSchedule(
        schedule: ChannelSchedule,
        fromMs: Long,
        toMs: Long,
    ): List<EpgProgramme> {
        val list = schedule.programmes
        if (list.isEmpty() || fromMs >= toMs) return emptyList()

        var hiLo = 0
        var hi = list.size
        while (hiLo < hi) {
            val mid = (hiLo + hi) ushr 1
            if (list[mid].startMs < toMs) {
                hiLo = mid + 1
            } else {
                hi = mid
            }
        }
        if (hi == 0) return emptyList()

        if (!schedule.hasOverlaps) {
            var lo = 0
            var r = hi
            while (lo < r) {
                val mid = (lo + r) ushr 1
                if (list[mid].endMs <= fromMs) {
                    lo = mid + 1
                } else {
                    r = mid
                }
            }
            return if (lo < hi) list.subList(lo, hi).toList() else emptyList()
        }

        val minStart = if (fromMs > Long.MIN_VALUE + schedule.maxDurationMs) {
            fromMs - schedule.maxDurationMs
        } else {
            Long.MIN_VALUE
        }
        var lo = 0
        var r = hi
        while (lo < r) {
            val mid = (lo + r) ushr 1
            if (list[mid].startMs <= minStart) {
                lo = mid + 1
            } else {
                r = mid
            }
        }
        if (lo >= hi) return emptyList()
        val out = ArrayList<EpgProgramme>(hi - lo)
        for (i in lo until hi) {
            val p = list[i]
            if (p.endMs > fromMs) out.add(p)
        }
        return out
    }

    private fun nowAndNextFromSchedule(
        schedule: ChannelSchedule,
        nowMs: Long,
    ): Pair<EpgProgramme?, EpgProgramme?> {
        val list = schedule.programmes
        if (list.isEmpty()) return null to null
        var lo = 0
        var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].startMs <= nowMs) {
                lo = mid + 1
            } else {
                hi = mid
            }
        }

        val next = list.getOrNull(lo)
        var onNow: EpgProgramme? = null
        if (lo > 0) {
            val prev = list[lo - 1]
            if (prev.isOnAt(nowMs)) {
                onNow = prev
            } else if (schedule.hasOverlaps) {
                var idx = lo - 2
                while (idx >= 0) {
                    val candidate = list[idx]
                    if (nowMs - candidate.startMs >= schedule.maxDurationMs) break
                    if (candidate.isOnAt(nowMs)) {
                        onNow = candidate
                        break
                    }
                    idx--
                }
            }
        }
        return onNow to next
    }

    private fun matchesProgramme(p: EpgProgramme, lowerQuery: String): Boolean =
        p.title.lowercase().contains(lowerQuery) ||
            (p.subtitle?.lowercase()?.contains(lowerQuery) == true) ||
            (p.description?.lowercase()?.contains(lowerQuery) == true) ||
            (p.category?.lowercase()?.contains(lowerQuery) == true) ||
            (p.sportTag?.lowercase()?.contains(lowerQuery) == true)

    private class ChannelSchedule(
        val programmes: List<EpgProgramme>,
        val maxDurationMs: Long,
        val hasOverlaps: Boolean,
    )

    private class CachedChannelSchedule(
        val epgId: String?,
        val schedule: ChannelSchedule,
    )

    companion object {
        private val EMPTY_SCHEDULE = ChannelSchedule(emptyList(), 0L, false)
    }
}
