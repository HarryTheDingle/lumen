package lumos.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CatchupUrlBuilderTest {
    private val startMs = fromUtcFields(2026, 11, 21, 18, 30, 0) // 2026-11-21 18:30:00Z
    private val endMs = fromUtcFields(2026, 11, 21, 19, 30, 0)   // 60 min later
    private val nowMs = fromUtcFields(2026, 11, 22, 10, 0, 0)    // Next morning

    private val xtreamSource = IptvSource.Xtream(
        id = "xt1",
        name = "Xtream",
        serverUrl = "https://tv.example.com:8443",
        username = "alice",
        password = "p@ss",
        outputFormat = "m3u8",
    )

    private fun streamWithCatchup(
        url: String,
        catchup: CatchupConfig?,
        xtreamStreamId: String? = null,
    ) = StreamSource(
        id = "s1",
        sourceId = "xt1",
        sourceName = "Test",
        rawName = "BBC One",
        groupTitle = "UK",
        tvgId = "bbc1.uk",
        logoUrl = null,
        streamUrl = url,
        quality = StreamQuality(1080),
        catchup = catchup,
        xtreamStreamId = xtreamStreamId,
    )

    @Test
    fun buildsXtreamTimeshiftUrlFromSourceAndFallbackUrl() {
        val stream = streamWithCatchup(
            url = "https://tv.example.com:8443/live/alice/p%40ss/4512.m3u8",
            catchup = CatchupConfig(CatchupMode.XTREAM, days = 7, streamId = "4512"),
            xtreamStreamId = "4512",
        )
        val url = CatchupUrlBuilder.buildM3uOrXtreamCatchupUrl(stream, xtreamSource, startMs, endMs, nowMs)
        assertEquals(
            "https://tv.example.com:8443/timeshift/alice/p%40ss/60/2026-11-21:18-30/4512.m3u8",
            url,
        )

        val fallback = CatchupUrlBuilder.buildM3uOrXtreamCatchupUrl(
            stream.copy(
                streamUrl = "http://panel.local/live/u/pw/99.ts",
                catchup = CatchupConfig(CatchupMode.XTREAM, 7),
                xtreamStreamId = null,
            ),
            source = null,
            startMs = startMs,
            endMs = endMs,
            nowMs = nowMs,
        )
        assertEquals("http://panel.local/timeshift/u/pw/60/2026-11-21:18-30/99.ts", fallback)
    }

    @Test
    fun buildsDefaultAppendShiftXcAndFlussonicUrls() {
        val startSec = startMs / 1000L
        val endSec = endMs / 1000L
        val nowSec = nowMs / 1000L

        val defStream = streamWithCatchup(
            url = "http://cdn.test/live/1.ts",
            catchup = CatchupConfig(
                CatchupMode.DEFAULT,
                days = 7,
                sourceTemplate = "http://cdn.test/archive/{Y}-{m}-{d}/{utc}-{duration}.m3u8?end={utcend}",
            ),
        )
        assertEquals(
            "http://cdn.test/archive/2026-11-21/$startSec-3600.m3u8?end=$endSec",
            CatchupUrlBuilder.buildM3uOrXtreamCatchupUrl(defStream, null, startMs, endMs, nowMs),
        )

        val appendStream = streamWithCatchup(
            url = "http://cdn.test/live/1.ts?token=abc",
            catchup = CatchupConfig(CatchupMode.APPEND, days = 7, sourceTemplate = "?utc={utc}&lutc={lutc}"),
        )
        assertEquals(
            "http://cdn.test/live/1.ts?token=abc&utc=$startSec&lutc=$nowSec",
            CatchupUrlBuilder.buildM3uOrXtreamCatchupUrl(appendStream, null, startMs, endMs, nowMs),
        )

        val shiftStream = streamWithCatchup(
            url = "http://cdn.test/live/2.m3u8",
            catchup = CatchupConfig(CatchupMode.SHIFT, days = 7),
        )
        assertEquals(
            "http://cdn.test/live/2.m3u8?utc=$startSec&lutc=$nowSec",
            CatchupUrlBuilder.buildM3uOrXtreamCatchupUrl(shiftStream, null, startMs, endMs, nowMs),
        )

        val xcStream = streamWithCatchup(
            url = "http://xc.test/u1/p1/888.ts",
            catchup = CatchupConfig(CatchupMode.XC, days = 7),
        )
        assertEquals(
            "http://xc.test/timeshift/u1/p1/60/2026-11-21:18-30/888.ts",
            CatchupUrlBuilder.buildM3uOrXtreamCatchupUrl(xcStream, null, startMs, endMs, nowMs),
        )

        val flussonicStream = streamWithCatchup(
            url = "https://fs.test/bbc1/index.m3u8?token=xyz",
            catchup = CatchupConfig(CatchupMode.FLUSSONIC, days = 7),
        )
        assertEquals(
            "https://fs.test/bbc1/archive-$startSec-3600.m3u8?token=xyz",
            CatchupUrlBuilder.buildM3uOrXtreamCatchupUrl(flussonicStream, null, startMs, endMs, nowMs),
        )
    }

    @Test
    fun returnsNullForStalkerFutureOrExpiredProgrammes() {
        val stalkerStream = streamWithCatchup(
            url = "ffmpeg http://portal/ch1",
            catchup = CatchupConfig(CatchupMode.STALKER, days = 7, streamId = "1"),
        )
        assertNull(CatchupUrlBuilder.buildM3uOrXtreamCatchupUrl(stalkerStream, null, startMs, endMs, nowMs))

        val expiredStream = streamWithCatchup(
            url = "http://cdn.test/live/1.ts",
            catchup = CatchupConfig(CatchupMode.SHIFT, days = 2),
        )
        val tooOldStart = nowMs - 4L * 86_400_000L
        val tooOldEnd = tooOldStart + 3_600_000L
        assertNull(CatchupUrlBuilder.buildM3uOrXtreamCatchupUrl(expiredStream, null, tooOldStart, tooOldEnd, nowMs))

        val futureStart = nowMs + 60_000L
        val futureEnd = futureStart + 3_600_000L
        assertNull(CatchupUrlBuilder.buildM3uOrXtreamCatchupUrl(expiredStream, null, futureStart, futureEnd, nowMs))
    }
}
