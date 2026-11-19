package lumos.engine

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Streams not in English are told apart (Describe.foreign), so the list can leave them out */
class ForeignTest {
    private fun foreign(text: String) = Describe.stream("Torrentio", text).foreign

    @Test fun englishAndUnsaidAreKept() {
        assertFalse(foreign("Spider-Man.Brand.New.Day.2026.2160p.WEB-DL.DV.HDR.DDP5.1.Atmos.H.265"))
        assertFalse(foreign("Movie 2026 1080p BluRay x264 🇬🇧"))
        assertFalse(foreign("Movie 2026 1080p MULTi 🇷🇺 🇬🇧"))
        assertFalse(foreign("Movie 2026 1080p Dual Audio Hindi English"))
    }

    @Test fun otherLanguagesAndScriptsGo() {
        assertTrue(foreign("Movie 2026 1080p WEB-DL 🇷🇺"))
        assertTrue(foreign("Movie 2026 1080p iTALiAN WEB-DL italiano"))
        assertTrue(foreign("Человек-паук Совершенно новый день 2026 WEB-DL 1080p"))
        assertTrue(foreign("蜘蛛侠 全新的一天 2026 1080p"))
    }
}
