package lumos.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamRankerTest {
    private fun stream(
        id: String,
        sourceId: String = "src1",
        height: Int = 1080,
        fps: Int? = null,
        hdr: Boolean = false,
        isRaw: Boolean = false,
        isBackup: Boolean = false,
        hasCatchup: Boolean = false,
    ) = StreamSource(
        id = id,
        sourceId = sourceId,
        sourceName = sourceId,
        rawName = id,
        groupTitle = "UK",
        tvgId = null,
        logoUrl = null,
        streamUrl = "https://stream.test/$id.ts",
        quality = StreamQuality(
            height = height,
            fps = fps,
            hdr = hdr,
            isRaw = isRaw,
            isBackup = isBackup,
        ),
        catchup = if (hasCatchup) CatchupConfig(CatchupMode.XTREAM, 7) else null,
    )

    @Test
    fun healthTrackerAppliesExponentialBackoffAndPersistsSnapshot() {
        val tracker = StreamHealthTracker()
        val t0 = 1_000_000L

        val f1 = tracker.recordFailure("s1", t0)
        assertEquals(1, f1.failures)
        assertEquals(t0 + 30_000L, f1.cooldownUntilMs)
        assertTrue(tracker.isOnCooldown("s1", t0 + 29_999L))
        assertFalse(tracker.isOnCooldown("s1", t0 + 30_000L))

        val f2 = tracker.recordFailure("s1", t0 + 40_000L)
        assertEquals(t0 + 40_000L + 120_000L, f2.cooldownUntilMs)

        val f3 = tracker.recordFailure("s1", t0 + 200_000L)
        assertEquals(t0 + 200_000L + 600_000L, f3.cooldownUntilMs)

        val f4 = tracker.recordFailure("s1", t0 + 900_000L)
        assertEquals(t0 + 900_000L + 1_800_000L, f4.cooldownUntilMs)

        val f5 = tracker.recordFailure("s1", t0 + 3_000_000L)
        assertEquals(t0 + 3_000_000L + 1_800_000L, f5.cooldownUntilMs)

        val json = tracker.toJson()
        val restored = StreamHealthTracker.fromJson(json)
        assertTrue(restored.isOnCooldown("s1", t0 + 3_100_000L))

        val ok = restored.recordSuccess("s1", t0 + 3_200_000L, startLatencyMs = 420L)
        assertEquals(0, ok.failures)
        assertEquals(0L, ok.cooldownUntilMs)
        assertEquals(420L, ok.averageStartMs)
        assertFalse(restored.isOnCooldown("s1", t0 + 3_200_000L))
    }

    @Test
    fun ranksByPinHealthDisplayCapBandwidthAndSportsFps() {
        val now = 100_000L
        val tracker = StreamHealthTracker()
        tracker.recordFailure("fhd-cooled", now)

        val s4k = stream("uhd", height = 2160, fps = 50)
        val sFhdCooled = stream("fhd-cooled", height = 1080, fps = 50)
        val sHd50 = stream("hd50", height = 720, fps = 50)
        val sFhd25 = stream("fhd25", height = 1080, fps = 25)
        val sPinned = stream("pinned-sd", height = 576)

        // Pinned wins first; cooled-down ranks below healthy; maxHeight=1080 puts 4K below 1080p/720p;
        // Sports boosts 720p 50fps above 1080p 25fps!
        val sportsRanked = StreamRanker.rank(
            streams = listOf(s4k, sFhdCooled, sFhd25, sHd50, sPinned),
            category = ChannelCategory.SPORTS,
            health = tracker,
            preferredStreamId = "pinned-sd",
            nowMs = now,
            maxHeight = 1080,
        )
        assertEquals(
            listOf("pinned-sd", "hd50", "fhd25", "uhd", "fhd-cooled"),
            sportsRanked.map { it.id },
        )

        // For non-sports (Entertainment), 1080p 25fps beats 720p 50fps
        val entRanked = StreamRanker.rank(
            streams = listOf(sHd50, sFhd25),
            category = ChannelCategory.ENTERTAINMENT,
            nowMs = now,
            maxHeight = 1080,
        )
        assertEquals(listOf("fhd25", "hd50"), entRanked.map { it.id })

        // Bandwidth cap (6 Mbps) prefers 1080p (5 Mbps) over 4K (18 Mbps) even when maxHeight = 2160
        val bwRanked = StreamRanker.rank(
            streams = listOf(s4k, sFhd25),
            category = ChannelCategory.ENTERTAINMENT,
            nowMs = now,
            maxHeight = 2160,
            bandwidthBps = 6_000_000L,
        )
        assertEquals(listOf("fhd25", "uhd"), bwRanked.map { it.id })
    }

    @Test
    fun ranksNonBackupRawCatchupAndSourcePriority() {
        val backup4k = stream("bkp", height = 2160, isBackup = true)
        val raw1080 = stream("raw", height = 1080, isRaw = true, sourceId = "low")
        val std1080Catchup = stream("std-catchup", height = 1080, hasCatchup = true, sourceId = "low")
        val std1080Low = stream("std-low", height = 1080, sourceId = "low")
        val std1080High = stream("std-high", height = 1080, sourceId = "high")

        val ranked = StreamRanker.rank(
            streams = listOf(backup4k, std1080Low, std1080High, std1080Catchup, raw1080),
            category = ChannelCategory.TERRESTRIAL,
            sourcePriorities = mapOf("high" to 10, "low" to 1),
        )
        assertEquals(
            listOf("raw", "std-catchup", "std-high", "std-low", "bkp"),
            ranked.map { it.id },
        )
    }
}
