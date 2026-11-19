package lumos.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PickTest {
    private fun s(
        source: String,
        name: String,
        title: String? = null,
        url: String? = "https://x/$source/$name",
        group: String? = null,
        cached: Boolean? = null,
        hash: String? = null,
        size: Long? = null,
    ) = Stream(
        source = source,
        name = name,
        title = title,
        url = url,
        infoHash = hash,
        cached = cached,
        bingeGroup = group,
        size = size,
    )

    private fun lumosWith(
        streams: List<Stream>,
        videoId: String = "tt1160419",
        type: String = "movie",
        settled: Boolean = true,
        speed: Double = 0.0,
        netCap: Int = 0,
        meta: Meta? = null,
        configure: Lumos.() -> Unit = {},
    ): Pair<Lumos, List<Pair<Stream, Described>>> {
        val lumos = Lumos("https://sync.test").apply(configure)
        lumos.phone.seedStreams(videoId, streams, type = type, settled = settled, speed = speed, netCap = netCap, meta = meta)
        return lumos to lumos.phone.described()
    }

    private val baseStreams = listOf(
        s("Torz", "4K HDR", title = "Dune.2160p.HDR.BluRay", group = "torz|4k"),
        s("Torz", "1080p", title = "Dune.1080p.WEB-DL", group = "torz|1080"),
        s("Comet", "1080p WEB", title = "Dune.1080p.WEB-DL"),
        s("P2P", "2160p", title = "Dune.2160p.Remux", url = null, hash = "abc"),
    )

    @Test fun listAndPickShareUnifiedRanking() {
        val (lumos, list) = lumosWith(baseStreams)
        val pick = lumos.phone.pick(list)
        assertNotNull(pick)
        // Ready streams rank above unready torrents in both list order and pick
        assertEquals(0, pick.index)
        assertEquals(2160, list[pick.index].second.res)
        assertEquals("Torz", list[pick.index].first.source)
        assertNull(list.last().first.url)
    }

    @Test fun dvBeatsHdrAndRemuxBeatsBluRay() {
        val streams = listOf(
            s("Torz", "4K HDR BluRay", title = "Movie.2160p.HDR.BluRay"),
            s("Torz", "4K DV BluRay", title = "Movie.2160p.DV.BluRay"),
            s("Torz", "4K DV Remux", title = "Movie.2160p.DV.Remux"),
        )
        val (lumos, list) = lumosWith(streams)
        assertEquals(listOf("4K DV Remux", "4K DV BluRay", "4K HDR BluRay"), list.map { it.first.name })
        val pick = lumos.phone.pick(list)!!
        assertEquals("4K DV Remux", list[pick.index].first.name)
    }

    @Test fun qualityCapAndNetworkCap() {
        val (lumos, list) = lumosWith(baseStreams) { qualityCap = 1080 }
        val pick = lumos.phone.pick(list)!!
        assertEquals(1080, list[pick.index].second.res)
        assertEquals(Triple(2160, 1080, "cap"), pick.lowered)

        val (lumosNet, listNet) = lumosWith(baseStreams, netCap = 1080)
        val netPick = lumosNet.phone.pick(listNet)!!
        assertEquals(1080, listNet[netPick.index].second.res)
        assertEquals(Triple(2160, 1080, "cap"), netPick.lowered)
    }

    @Test fun speedMatchingLowersToFittingStream() {
        // 4K guesses 20 Mbps, 1080p guesses 8 Mbps; at 15 Mbps * 0.75 = 11.25 Mbps, 1080p fits
        val (lumos, list) = lumosWith(baseStreams, speed = 15.0)
        val pick = lumos.phone.pick(list)!!
        assertEquals(1080, list[pick.index].second.res)
        assertEquals(Triple(2160, 1080, "speed"), pick.lowered)
    }

    @Test fun preferImaxWinsAtSameResolution() {
        val streams = listOf(
            s("Torz", "4K DV Remux", title = "Dune.2160p.DV.Remux"),
            s("Torz", "4K IMAX WEB", title = "Dune.2160p.IMAX.WEB-DL"),
        )
        val (lumosOff, listOff) = lumosWith(streams) { preferImax = false }
        assertEquals("4K DV Remux", listOff[lumosOff.phone.pick(listOff)!!.index].first.name)

        val (lumosOn, listOn) = lumosWith(streams) { preferImax = true }
        // Both list order and pick honor preferImax
        assertEquals("4K IMAX WEB", listOn.first().first.name)
        assertEquals("4K IMAX WEB", listOn[lumosOn.phone.pick(listOn)!!.index].first.name)
    }

    @Test fun preferredAddon() {
        val streams = listOf(
            s("Torz", "4K BluRay", title = "Movie.2160p.BluRay"),
            s("Comet", "1080p WEB", title = "Movie.1080p.WEB-DL"),
        )
        val (lumos, list) = lumosWith(streams) { preferredAddon = "comet" }
        val pick = lumos.phone.pick(list)!!
        assertEquals("Comet", list[pick.index].first.source)
    }

    @Test fun remembersBingeGroupAndAddon() {
        val (lumos1, list1) = lumosWith(baseStreams) { rememberChoice("tt1160419", "Torz|1080|torz|1080") }
        val pick1 = lumos1.phone.pick(list1)!!
        assertEquals("same", pick1.how)
        assertEquals("torz|1080", list1[pick1.index].first.bingeGroup)

        val (lumos2, list2) = lumosWith(baseStreams) { rememberChoice("tt1160419", "Comet|1080|") }
        val pick2 = lumos2.phone.pick(list2)!!
        assertEquals("same", pick2.how)
        assertEquals("Comet", list2[pick2.index].first.source)

        val (lumosAsk, listAsk) = lumosWith(baseStreams) {
            rememberChoice("tt1160419", "GoneAddon|2160|gone")
            fallbackAsk = true
        }
        assertNull(lumosAsk.phone.pick(listAsk))
    }

    @Test fun nothingReadyReturnsNull() {
        val (lumos, list) = lumosWith(listOf(s("P2P", "1080p", title = "Movie.1080p", url = null, hash = "h")))
        assertNull(lumos.phone.pick(list))
    }

    @Test fun finishedThresholdAndEpisodeOrder() {
        val lumos = Lumos("https://sync.test")
        lumos.saveProgress("tt0903747", "series", "tt0903747:1:1", "Breaking Bad", null, 91.0, 100.0, 1L)
        assertNotNull(lumos.resumeOf("tt0903747"))
        lumos.saveProgress("tt0903747", "series", "tt0903747:1:1", "Breaking Bad", null, 92.0, 100.0, 2L)
        assertNull(lumos.resumeOf("tt0903747"))
        assertTrue("tt0903747:1:1" in lumos.watchedIds())

        val meta = Meta(
            preview = MetaPreview("tt0903747", "series", "Breaking Bad"),
            runtime = "47 min",
            cast = emptyList(),
            director = emptyList(),
            trailers = emptyList(),
            videos = listOf(
                Video("tt0903747:0:1", "Special", 0, 1, null, null, null),
                Video("tt0903747:1:2", "Ep 2", 1, 2, null, null, null),
                Video("tt0903747:1:1", "Ep 1", 1, 1, null, null, null),
                Video("tt0903747:2:1", "S2E1", 2, 1, null, null, null),
            ),
        )
        assertEquals("tt0903747:1:2", lumos.phone.guessVideo("series", "tt0903747", meta))
    }
}
