package lumos.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PickTest {
    private fun s(source: String, name: String, url: String? = "https://x", group: String? = null, cached: Boolean? = null, hash: String? = null) =
        Stream(source = source, name = name, title = null, url = url, infoHash = hash, cached = cached, bingeGroup = group)

    private val list = Lumos.rank(listOf(
        s("Torz", "4K HDR", group = "torz|4k"),
        s("Torz", "1080p", group = "torz|1080"),
        s("Easynews+", "1080p WEB"),
        s("P2P", "2160p", url = null, hash = "abc"),
    ))

    @Test fun bestReadyFirst() = assertEquals("4K", Lumos.pick(list, null, "")!!.quality)
    @Test fun qualityCap() = assertEquals("1080p", Lumos.pick(list, null, "1080p")!!.quality)
    @Test fun remembersTheBingeGroup() = assertEquals("torz|1080", Lumos.pick(list, "Torz|1080p|torz|1080", "")!!.stream.bingeGroup)
    @Test fun remembersTheAddon() = assertEquals("Easynews+", Lumos.pick(list, "Easynews+|1080p|", "")!!.stream.source)
    @Test fun nothingReady() = assertNull(Lumos.pick(Lumos.rank(listOf(s("P2P", "1080p", url = null, hash = "h"))), null, ""))
}
