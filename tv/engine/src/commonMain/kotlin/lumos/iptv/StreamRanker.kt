package lumos.iptv

/**
 * Ranks stream variants by pinned preference, health cooldown, display height cap, bandwidth fit,
 * sports high-FPS priority, quality/RAW/backup/catch-up attributes, and source priority.
 */
object StreamRanker {
    private const val BITRATE_4K_BPS = 18_000_000L
    private const val BITRATE_1080_HFR_BPS = 8_000_000L
    private const val BITRATE_1080_BPS = 5_000_000L
    private const val BITRATE_720_BPS = 2_500_000L
    private const val BITRATE_SD_BPS = 1_200_000L

    /** Estimates required sustained download bandwidth in bits per second for [quality]. */
    fun estimatedBitrateBps(quality: StreamQuality): Long = when {
        quality.height >= 2160 -> BITRATE_4K_BPS
        quality.height >= 1080 && ((quality.fps ?: 0) >= 50 || quality.isRaw) -> BITRATE_1080_HFR_BPS
        quality.height >= 1080 -> BITRATE_1080_BPS
        quality.height >= 720 -> BITRATE_720_BPS
        else -> BITRATE_SD_BPS
    }

    /** Returns [streams] ordered from best candidate to fallback. */
    fun rank(
        streams: List<StreamSource>,
        category: ChannelCategory,
        isEventChannel: Boolean = false,
        health: StreamHealthTracker? = null,
        preferredStreamId: String? = null,
        sourcePriorities: Map<String, Int> = emptyMap(),
        nowMs: Long = 0L,
        maxHeight: Int = 2160,
        bandwidthBps: Long? = null,
    ): List<StreamSource> {
        if (streams.size <= 1) return streams

        val preferHighFps = category == ChannelCategory.SPORTS || isEventChannel

        return streams.withIndex().sortedWith { a, b ->
            compareIndexedStreams(
                a = a,
                b = b,
                preferHighFps = preferHighFps,
                health = health,
                preferredStreamId = preferredStreamId,
                sourcePriorities = sourcePriorities,
                nowMs = nowMs,
                maxHeight = maxHeight,
                bandwidthBps = bandwidthBps,
            )
        }.map { it.value }
    }

    private fun compareIndexedStreams(
        a: IndexedValue<StreamSource>,
        b: IndexedValue<StreamSource>,
        preferHighFps: Boolean,
        health: StreamHealthTracker?,
        preferredStreamId: String?,
        sourcePriorities: Map<String, Int>,
        nowMs: Long,
        maxHeight: Int,
        bandwidthBps: Long?,
    ): Int {
        val sa = a.value
        val sb = b.value

        // 1. Pinned user preference
        val pinCmp = boolRank(sb.id == preferredStreamId).compareTo(boolRank(sa.id == preferredStreamId))
        if (pinCmp != 0) return pinCmp

        // 2. Cooldown status (healthy before cooled-down)
        val healthyA = health?.isOnCooldown(sa.id, nowMs) != true
        val healthyB = health?.isOnCooldown(sb.id, nowMs) != true
        val healthCmp = boolRank(healthyB).compareTo(boolRank(healthyA))
        if (healthCmp != 0) return healthCmp

        // 3. Display height cap
        val fitsCapA = sa.quality.height <= maxHeight
        val fitsCapB = sb.quality.height <= maxHeight
        val capCmp = boolRank(fitsCapB).compareTo(boolRank(fitsCapA))
        if (capCmp != 0) return capCmp

        // 4. Bandwidth fit
        if (bandwidthBps != null && bandwidthBps > 0L) {
            val fitsBwA = estimatedBitrateBps(sa.quality) <= bandwidthBps
            val fitsBwB = estimatedBitrateBps(sb.quality) <= bandwidthBps
            val bwCmp = boolRank(fitsBwB).compareTo(boolRank(fitsBwA))
            if (bwCmp != 0) return bwCmp
        }

        // 5. 50/60fps boost for Sports / Live Events
        if (preferHighFps) {
            val hfrA = (sa.quality.fps ?: 0) >= 50
            val hfrB = (sb.quality.fps ?: 0) >= 50
            val hfrCmp = boolRank(hfrB).compareTo(boolRank(hfrA))
            if (hfrCmp != 0) return hfrCmp
        }

        // 6. Primary vs backup, resolution, RAW, FPS, HDR, catch-up
        val primaryCmp = boolRank(!sb.quality.isBackup).compareTo(boolRank(!sa.quality.isBackup))
        if (primaryCmp != 0) return primaryCmp

        val heightCmp = sb.quality.height.compareTo(sa.quality.height)
        if (heightCmp != 0) return heightCmp

        val rawCmp = boolRank(sb.quality.isRaw).compareTo(boolRank(sa.quality.isRaw))
        if (rawCmp != 0) return rawCmp

        val fpsCmp = (sb.quality.fps ?: 0).compareTo(sa.quality.fps ?: 0)
        if (fpsCmp != 0) return fpsCmp

        val hdrCmp = boolRank(sb.quality.hdr).compareTo(boolRank(sa.quality.hdr))
        if (hdrCmp != 0) return hdrCmp

        val catchupCmp = boolRank(sb.catchup != null && sb.catchup.days > 0)
            .compareTo(boolRank(sa.catchup != null && sa.catchup.days > 0))
        if (catchupCmp != 0) return catchupCmp

        // 7. Source priority (higher wins)
        val prioA = sourcePriorities[sa.sourceId] ?: 0
        val prioB = sourcePriorities[sb.sourceId] ?: 0
        val prioCmp = prioB.compareTo(prioA)
        if (prioCmp != 0) return prioCmp

        // 8. Observed start latency (faster wins when both measured)
        val latA = health?.get(sa.id)?.averageStartMs ?: Long.MAX_VALUE
        val latB = health?.get(sb.id)?.averageStartMs ?: Long.MAX_VALUE
        val latCmp = latA.compareTo(latB)
        if (latCmp != 0) return latCmp

        return a.index.compareTo(b.index)
    }

    private fun boolRank(flag: Boolean): Int = if (flag) 1 else 0
}
