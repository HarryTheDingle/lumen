package lumos.engine

import kotlinx.coroutines.runBlocking
import kotlin.test.Test

class LiveStreamsTest {
    /** Real network: what a public torrent addon lists for an episode and a film, and what Matching drops. -Dlive=true */
    @Test fun liveMatching() = runBlocking {
        if (System.getProperty("live") != "true") return@runBlocking
        val http = Engine.defaultHttp()
        for (url in listOf("https://torrentio.strem.fun/manifest.json", "https://torrentsdb.com/manifest.json")) {
            val a = Addon(url, http)
            for ((type, id, year) in listOf(Triple("series", "tt0903747:2:3", 2008), Triple("movie", "tt1160419", 2021), Triple("series", "tt0944947:1:1", 2011))) {
                val got = runCatching { a.streams(type, id) }
                val list = got.getOrNull() ?: run { println("$url $id: ${got.exceptionOrNull()?.message}"); null } ?: continue
                val dropped = list.filter { !Matching.fits(it, id, year) }
                println("$url $id: ${list.size} listed, ${dropped.size} dropped")
                dropped.take(5).forEach { println("   dropped: " + it.text.replace("\n", " | ").take(160)) }
            }
        }
    }
}
