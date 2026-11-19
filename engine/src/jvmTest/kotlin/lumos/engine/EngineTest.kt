package lumos.engine

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.measureTime

class EngineTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun mock(routes: Map<String, String>) = HttpClient(MockEngine { req ->
        val body = routes[req.url.toString()]
        if (body == null) respond("{}", HttpStatusCode.NotFound, jsonHeaders) else respond(body, HttpStatusCode.OK, jsonHeaders)
    })

    private val manifest = """
        {"id":"x","name":"Torz","types":["movie","series"],"idPrefixes":["tt"],
         "resources":["stream",{"name":"catalog","types":["movie"]}],
         "catalogs":[{"type":"movie","id":"top","name":"Top"},{"type":"movie","id":"s","name":"Search","extra":[{"name":"search","isRequired":true}]}]}
    """

    @Test fun readsManifestAndAnswers() = runTest {
        val a = Addon("stremio://addon.test/abc/manifest.json", mock(mapOf("https://addon.test/abc/manifest.json" to manifest)))
        val m = a.manifest()
        assertEquals("Torz", m.name)
        assertTrue(m.answers("stream", "series", "tt0944947:1:1"))
        assertTrue(!m.answers("stream", "movie", "kitsu:1"))
        assertEquals(listOf("search"), m.catalogs[1].requiredExtras)
    }

    @Test fun readsStreams() = runBlocking {
        val http = mock(mapOf(
            "https://addon.test/manifest.json" to manifest,
            "https://addon.test/stream/series/tt0944947:1:1.json" to """
                {"streams":[
                  {"name":"TB 2160p","title":"Game.of.Thrones.S01E01.2160p","url":"https://cdn/x.mkv","behaviorHints":{"filename":"x.mkv","videoSize":12345,"proxyHeaders":{"request":{"Referer":"r"}}}},
                  {"name":"P2P","infoHash":"ABCDEF","fileIdx":3},
                  {"name":"nothing playable"}
                ]}""",
        ))
        val e = Engine(listOf("https://addon.test/manifest.json"), http = http)
        val s = e.streams("series", "tt0944947:1:1").all()
        assertEquals(2, s.size)
        assertEquals(12345L, s[0].size)
        assertEquals("r", s[0].headers["Referer"])
        assertEquals("abcdef", s[1].infoHash)
    }

    @Test fun picksTheEpisodeFile() {
        val files = listOf(
            TorBox.File(1, "Show.S01E01.1080p.mkv", 2_000, null),
            TorBox.File(2, "Show.S01E02.1080p.mkv", 1_900, null),
            TorBox.File(3, "Show.S01E10.1080p.mkv", 2_100, null),
            TorBox.File(4, "sample.mkv", 50, null),
            TorBox.File(5, "readme.txt", 1, null),
        )
        assertEquals(2, TorBox.pickFile(files, null, 1, 2)?.id)
        assertEquals(1, TorBox.pickFile(files, null, 1, 1)?.id)
        assertEquals(3, TorBox.pickFile(files, null, null, null)?.id)
        // (the addon's file number doesn't win over the episode's own tag: TorBox numbers files its own way)
        assertEquals(2, TorBox.pickFile(files, 1, 1, 2)?.id)
        // an episode that isn't in the pack: nothing, rather than another episode
        assertEquals(null, TorBox.pickFile(files, null, 1, 5))
        // the addon's file name, exactly
        assertEquals(3, TorBox.pickFile(files, null, 1, 2, "Show.S01E10.1080p.mkv")?.id)
        // anime: no season, the episode by number
        val anime = listOf(TorBox.File(1, "[Sub] Death Note - 04 [1080p].mkv", 900, null), TorBox.File(2, "[Sub] Death Note - 05 [1080p].mkv", 900, null),
            TorBox.File(3, "[Sub] Death Note - 15 [1080p].mkv", 900, null))
        assertEquals(2, TorBox.pickFile(anime, null, null, 5)?.id)
        // a film: the largest video
        assertEquals(3, TorBox.pickFile(files, null, null, null)?.id)
    }

    /** Real network: Cinemeta's Home rows and a title, timed. Run with -Dlive=true */
    @Test fun liveCinemeta() {
        if (System.getProperty("live") != "true") return
        runBlocking {
            val e = Engine(listOf("https://v3-cinemeta.strem.io/manifest.json"))
            lateinit var rows: List<Pair<Catalog, List<MetaPreview>>>
            val t = measureTime { rows = e.home() }
            println("Cinemeta Home: ${rows.size} rows, ${rows.sumOf { it.second.size }} titles in $t")
            assertTrue(rows.isNotEmpty())
            val meta = e.meta("series", "tt0944947")
            assertNotNull(meta)
            println("Game of Thrones: ${meta.videos.size} episodes")
        }
    }
}
