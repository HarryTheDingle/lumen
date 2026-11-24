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

/** Versions other than the released one are named (Describe.edition) */
class EditionTest {
    private fun edition(text: String) = Describe.stream("Torrentio", text).edition

    @Test fun editionsAreNamed() {
        kotlin.test.assertEquals("Encore", edition("Avengers.Endgame.2019.Encore.1080p.WEB-DL"))
        kotlin.test.assertEquals("Extended", edition("The.Hobbit.2012.EXTENDED.2160p.UHD.BluRay"))
        kotlin.test.assertEquals("Director's Cut", edition("Blade.Runner.Directors.Cut.1080p"))
        kotlin.test.assertEquals(null, edition("Avengers.Endgame.2019.2160p.WEB-DL.DV"))
    }
}
