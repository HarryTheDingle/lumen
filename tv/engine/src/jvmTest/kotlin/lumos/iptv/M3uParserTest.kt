package lumos.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class M3uParserTest {
    private fun m3uSource(userAgent: String? = null) = IptvSource.M3u(
        id = "m3u-1",
        name = "Test M3U",
        url = "https://example.com/list.m3u",
        userAgent = userAgent,
    )

    @Test
    fun parsesHeaderTvgUrlsAndAttributes() {
        val playlist = """
            #EXTM3U url-tvg="https://epg.one/a.xml, https://epg.two/b.xml" x-tvg-url="https://epg.two/b.xml,https://epg.three/c.xml"
            #EXTINF:-1 tvg-id="bbc1.uk" tvg-name="BBC One" tvg-logo="https://img/bbc1.png" group-title="UK, Terrestrial",UK | BBC One HD, London
            https://cdn.example.com/live/bbc1.m3u8
            #EXTINF:-1 tvg-id='itv1.uk' tvg-name='ITV1' group-title=UK_TV,ITV1 FHD
            https://cdn.example.com/live/itv1.ts
        """.trimIndent()

        val result = M3uParser.parse(playlist, m3uSource())
        assertEquals(
            listOf("https://epg.one/a.xml", "https://epg.two/b.xml", "https://epg.three/c.xml"),
            result.headerEpgUrls,
        )
        assertEquals(2, result.streams.size)

        val bbc = result.streams[0]
        assertEquals("bbc1.uk", bbc.tvgId)
        assertEquals("https://img/bbc1.png", bbc.logoUrl)
        assertEquals("UK, Terrestrial", bbc.groupTitle)
        assertEquals("UK | BBC One HD, London", bbc.rawName)
        assertEquals("https://cdn.example.com/live/bbc1.m3u8", bbc.streamUrl)
        assertEquals(720, bbc.quality.height)

        val itv = result.streams[1]
        assertEquals("itv1.uk", itv.tvgId)
        assertEquals("UK_TV", itv.groupTitle)
        assertEquals("ITV1 FHD", itv.rawName)
        assertEquals(1080, itv.quality.height)
    }

    @Test
    fun parsesVlcOptKodiPropExtGrpAndInlinePipeHeaders() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="sky.main",Sky Sports Main Event FHD
            #EXTGRP:UK Sports
            #EXTVLCOPT:http-user-agent=VLC/3.0
            #EXTVLCOPT:http-referrer=https://ref.example.com/
            #KODIPROP:inputstream.adaptive.stream_headers=Origin=https://origin.example.com&X-Token=abc123
            https://cdn.example.com/sports/main.m3u8|Cookie=session=xyz
            #EXTINF:-1,Plain Channel
            https://cdn.example.com/plain/stream
        """.trimIndent()

        val result = M3uParser.parse(playlist, m3uSource(userAgent = "SourceDefaultUA"))
        assertEquals(2, result.streams.size)

        val sports = result.streams[0]
        assertEquals("UK Sports", sports.groupTitle)
        assertEquals("VLC/3.0", sports.headers["User-Agent"])
        assertEquals("https://ref.example.com/", sports.headers["Referer"])
        assertEquals("https://origin.example.com", sports.headers["Origin"])
        assertEquals("abc123", sports.headers["X-Token"])
        assertEquals("session=xyz", sports.headers["Cookie"])
        assertEquals("https://cdn.example.com/sports/main.m3u8", sports.streamUrl)

        val plain = result.streams[1]
        assertEquals("SourceDefaultUA", plain.headers["User-Agent"])
        assertEquals(null, plain.headers["Referer"])
    }

    @Test
    fun mapsAllCatchupModesAndTvgRecTimeshift() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 catchup="default" catchup-days="7" catchup-source="https://c.test/{utc}/{duration}.ts",Default Ch
            https://live.test/1.ts
            #EXTINF:-1 catchup="append" catchup-days="5" catchup-source="?utc={utc}&lutc={lutc}",Append Ch
            https://live.test/2.ts
            #EXTINF:-1 catchup="shift" catchup-days="3",Shift Ch
            https://live.test/3.ts
            #EXTINF:-1 catchup="flussonic" catchup-days="7",Flussonic Ch
            https://live.test/4/index.m3u8
            #EXTINF:-1 catchup="xc" catchup-days="4",XC Ch
            https://live.test/u/p/44.ts
            #EXTINF:-1 tvg-rec="6",TvgRec Ch
            https://live.test/5.ts
            #EXTINF:-1 timeshift="2",Timeshift Ch
            https://live.test/6.ts
        """.trimIndent()

        val streams = M3uParser.parse(playlist, m3uSource()).streams
        assertEquals(7, streams.size)

        assertEquals(CatchupMode.DEFAULT, streams[0].catchup?.mode)
        assertEquals(7, streams[0].catchup?.days)
        assertEquals("https://c.test/{utc}/{duration}.ts", streams[0].catchup?.sourceTemplate)

        assertEquals(CatchupMode.APPEND, streams[1].catchup?.mode)
        assertEquals(5, streams[1].catchup?.days)

        assertEquals(CatchupMode.SHIFT, streams[2].catchup?.mode)
        assertEquals(3, streams[2].catchup?.days)

        assertEquals(CatchupMode.FLUSSONIC, streams[3].catchup?.mode)
        assertEquals(7, streams[3].catchup?.days)

        assertEquals(CatchupMode.XC, streams[4].catchup?.mode)
        assertEquals(4, streams[4].catchup?.days)

        assertEquals(CatchupMode.SHIFT, streams[5].catchup?.mode)
        assertEquals(6, streams[5].catchup?.days)

        assertEquals(CatchupMode.SHIFT, streams[6].catchup?.mode)
        assertEquals(2, streams[6].catchup?.days)
    }

    @Test
    fun skipsVodUrlsAndRetainsLiveEndpointsWithoutLeakingMetadata() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 group-title="Movies VOD" tvg-id="vod1",Some Movie
            #EXTVLCOPT:http-referrer=https://vod.ref/
            https://cdn.example.com/movie/u/p/1001.mp4
            #EXTINF:-1 group-title="Series VOD",Episode 1
            https://cdn.example.com/series/u/p/1002.mkv
            #EXTINF:-1,Another Movie
            https://cdn.example.com/files/film.avi
            #EXTINF:-1,Quick Clip
            https://cdn.example.com/files/clip.mov
            #EXTINF:-1 group-title="Live",Extensionless Live
            https://cdn.example.com/live/u/p/999
        """.trimIndent()

        val streams = M3uParser.parse(playlist, m3uSource()).streams
        assertEquals(1, streams.size)
        val live = streams[0]
        assertEquals("Extensionless Live", live.rawName)
        assertEquals("Live", live.groupTitle)
        assertNull(live.headers["Referer"])
    }

    @Test
    fun guaranteesNonBlankRawNameFallbackOrder() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-name="Fallback Tvg Name",
            https://cdn.example.com/live/stream1.m3u8
            #EXTINF:-1 tvg-name="  ",   
            https://cdn.example.com/live/bbc_two.ts?token=abc
            #EXTINF:-1,
            https://cdn.example.com/
            https://cdn.example.com
        """.trimIndent()

        val streams = M3uParser.parse(playlist, m3uSource()).streams
        assertEquals(4, streams.size)
        assertEquals("Fallback Tvg Name", streams[0].rawName)
        assertEquals("bbc_two.ts", streams[1].rawName)
        assertEquals("Channel 3", streams[2].rawName)
        assertEquals("Channel 4", streams[3].rawName)
        streams.forEach { assertTrue(it.rawName.isNotBlank()) }
        assertNotNull(streams[0].id)
    }
}
