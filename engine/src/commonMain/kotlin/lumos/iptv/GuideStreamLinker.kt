package lumos.iptv

/**
 * Links curated Cloudflare EPG [GuideChannel] entries against the user's normalized IPTV
 * [CanonicalChannel] lineup and selects the top playable [StreamSource] via [StreamRanker].
 */
object GuideStreamLinker {
    /**
     * Links [guideChannels] onto [canonicalChannels], preferring Cloudflare [GuideChannel.logoUrl]
     * when non-blank and ranking available streams per channel.
     */
    fun link(
        guideChannels: List<GuideChannel>,
        canonicalChannels: List<CanonicalChannel>,
        health: StreamHealthTracker? = null,
        prefs: IptvProfilePrefs = IptvProfilePrefs(),
        sourcePriorities: Map<String, Int> = emptyMap(),
        nowMs: Long = 0L,
        maxHeight: Int = 2160,
        bandwidthBps: Long? = null,
    ): List<GuideChannelRow> {
        if (guideChannels.isEmpty()) return emptyList()
        val byId = canonicalChannels.associateBy { it.id }
        val byNameKey = buildMap<String, CanonicalChannel> {
            for (ch in canonicalChannels) {
                val key = UkChannelRegistry.normalizeNameKey(ch.name)
                if (key.isNotEmpty() && key !in this) {
                    put(key, ch)
                }
            }
        }

        return guideChannels.map { guide ->
            val linked = findLinkedChannel(guide, byId, byNameKey)
            val bestStream = if (linked != null && linked.streams.isNotEmpty()) {
                val rankCategory = if (guide.sportsGuide) ChannelCategory.SPORTS else linked.category
                StreamRanker.rank(
                    streams = linked.streams,
                    category = rankCategory,
                    isEventChannel = linked.isEventChannel,
                    health = health,
                    preferredStreamId = prefs.preferredStreamByChannel[linked.id],
                    sourcePriorities = sourcePriorities,
                    nowMs = nowMs,
                    maxHeight = maxHeight,
                    bandwidthBps = bandwidthBps,
                ).firstOrNull()
            } else {
                null
            }
            val preferredLogo = guide.logoUrl?.takeIf { it.isNotBlank() } ?: linked?.logoUrl

            GuideChannelRow(
                guideChannel = guide,
                linkedChannel = linked,
                bestStream = bestStream,
                logoUrl = preferredLogo,
                hasCatchup = linked?.hasCatchup == true,
                qualityBadge = bestStream?.quality?.badge,
                isPlayable = bestStream != null,
            )
        }
    }

    /** Returns Main Guide rows (`mainGuide == true`) sorted by guide number and name. */
    fun mainGuide(
        guideChannels: List<GuideChannel>,
        canonicalChannels: List<CanonicalChannel>,
        health: StreamHealthTracker? = null,
        prefs: IptvProfilePrefs = IptvProfilePrefs(),
        sourcePriorities: Map<String, Int> = emptyMap(),
        nowMs: Long = 0L,
        maxHeight: Int = 2160,
        bandwidthBps: Long? = null,
    ): List<GuideChannelRow> {
        val main = guideChannels
            .asSequence()
            .filter { it.mainGuide }
            .distinctBy { it.id }
            .sortedWith(GUIDE_ORDER)
            .toList()
        return link(main, canonicalChannels, health, prefs, sourcePriorities, nowMs, maxHeight, bandwidthBps)
    }

    /** Returns Sports Live rows combining Cloudflare UK Sports (`sportsGuide == true`) and curated US Sports. */
    fun sportsLive(
        guideChannels: List<GuideChannel>,
        canonicalChannels: List<CanonicalChannel>,
        health: StreamHealthTracker? = null,
        prefs: IptvProfilePrefs = IptvProfilePrefs(),
        sourcePriorities: Map<String, Int> = emptyMap(),
        nowMs: Long = 0L,
        maxHeight: Int = 2160,
        bandwidthBps: Long? = null,
    ): List<GuideChannelRow> {
        val ukSports = guideChannels.filter { it.sportsGuide }
        val combined = buildList {
            addAll(ukSports)
            addAll(UkChannelRegistry.curatedUsSportsGuideChannels())
        }
            .distinctBy { it.id }
            .sortedWith(GUIDE_ORDER)
        return link(combined, canonicalChannels, health, prefs, sourcePriorities, nowMs, maxHeight, bandwidthBps)
    }

    private fun findLinkedChannel(
        guide: GuideChannel,
        byId: Map<String, CanonicalChannel>,
        byNameKey: Map<String, CanonicalChannel>,
    ): CanonicalChannel? {
        byId[guide.id]?.let { return it }
        UkChannelRegistry.match(guide.name, tvgId = guide.id)?.id?.let { matchedId ->
            byId[matchedId]?.let { return it }
        }
        val key = UkChannelRegistry.normalizeNameKey(guide.name)
        return if (key.isNotEmpty()) byNameKey[key] else null
    }

    private val GUIDE_ORDER = compareBy<GuideChannel>(
        { it.number ?: Int.MAX_VALUE },
        { it.name.lowercase() },
    )
}
