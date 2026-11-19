package lumos.engine

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MatchingTest {
    private fun s(name: String) = Stream(source = "Test", name = "Test 1080p", title = name, url = "https://x/y")

    @Test fun episodes() {
        val v = "tt0903747:2:3"
        assertTrue(Matching.fits(s("Breaking.Bad.S02E03.1080p.BluRay.x264"), v, 2008))
        assertFalse(Matching.fits(s("Breaking.Bad.S02E04.1080p.BluRay.x264"), v, 2008))
        assertFalse(Matching.fits(s("Breaking.Bad.S03E03.720p"), v, 2008))
        assertTrue(Matching.fits(s("Breaking Bad 2x03 720p"), v, 2008))
        assertTrue(Matching.fits(s("Breaking.Bad.S02E01-E05.1080p"), v, 2008))
        assertTrue(Matching.fits(s("Breaking Bad [02x01-05 of 13] (2009) WEB-DL"), v, 2008))
        assertFalse(Matching.fits(s("Breaking Bad [02x04-05 of 13] (2009) WEB-DL"), v, 2008))
        // a season pack names no episode: TorBox picks the file
        assertTrue(Matching.fits(s("Breaking.Bad.S02.COMPLETE.1080p"), v, 2008))
        assertTrue(Matching.fits(s("Breaking Bad complete series"), v, 2008))
    }

    @Test fun anime() {
        assertTrue(Matching.fits(s("[Sub] Death Note - S01E05 1080p"), "kitsu:1376:5", null))
        assertFalse(Matching.fits(s("[Sub] Death Note - S01E06 1080p"), "kitsu:1376:5", null))
    }

    @Test fun films() {
        assertTrue(Matching.fits(s("Dune.2021.2160p.WEB-DL"), "tt1160419", 2021))
        assertTrue(Matching.fits(s("Dune.2160p.WEB-DL"), "tt1160419", 2021))
        assertFalse(Matching.fits(s("Dune.1984.1080p.BluRay"), "tt1160419", 2021))
        // (a 2022 release of a late-2021 film)
        assertTrue(Matching.fits(s("Dune.2022.Remux"), "tt1160419", 2021))
    }

    @Test fun searchAddonsName() {
        // Easynews finds by name: Dark Matter (2015) is not Dark Matter (2024)
        assertTrue(Matching.named(s("Dark.Matter.2024.S02E06.1080p.WEB.h264"), "Dark Matter", 2024))
        assertTrue(Matching.named(s("Dark.Matter.S02E06.2160p.ATVP.WEB-DL"), "Dark Matter", 2024))
        assertFalse(Matching.named(s("Dark.Matter.2015.S02E06.720p.HDTV"), "Dark Matter", 2024))
        assertFalse(Matching.named(s("Dark.Matter.Reloaded.S02E06.1080p"), "Dark Matter", 2024))
        assertFalse(Matching.named(s("The.Dark.Side.S02E06.1080p"), "Dark Matter", 2024))
        assertTrue(Matching.named(s("The.Office.US.S02E03.1080p"), "The Office", 2005))
        assertTrue(Matching.named(s("Blade.Runner.2049.2017.2160p.UHD"), "Blade Runner 2049", 2017))
        assertTrue(Matching.named(s("Dune.Part.Two.2024.EXTENDED.2160p"), "Dune: Part Two", 2024))
        assertTrue(Matching.named(s("Fast & Furious 6 2013 1080p"), "Fast & Furious 6", 2013))
        assertTrue(Matching.searchBased("Easynews++"))
        assertFalse(Matching.searchBased("StremThru Torz"))
    }
}
