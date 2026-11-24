package lumos.engine

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class SkipsTest {
    @Test fun languages() {
        assertTrue(Languages.same("eng", "en"))
        assertTrue(Languages.same("English", "eng"))
        assertTrue(!Languages.same("eng", "spa"))
    }

    /** Real network: TheIntroDB for an episode, AniSkip for an anime one. Run with -Dlive=true */
    @Test fun liveSkips() = runBlocking {
        if (System.getProperty("live") != "true") return@runBlocking
        val skips = Skips(Engine.defaultHttp())
        val show = skips.of("series", "tt0903747", "tt0903747:1:2")
        println("Breaking Bad 1x2: $show")
        val anime = skips.of("series", "kitsu:1376", "kitsu:1376:1:5")
        println("Death Note 5: $anime")
        assertTrue(show.isNotEmpty() || anime.isNotEmpty())
    }
}
