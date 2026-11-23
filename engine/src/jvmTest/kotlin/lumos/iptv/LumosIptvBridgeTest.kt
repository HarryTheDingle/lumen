package lumos.iptv

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import lumos.engine.Lumos
import lumos.engine.Session
import lumos.engine.Sync
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LumosIptvBridgeTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    @Test
    fun syncUkEpgAndStartIptvSetupEndpointsWorkWithBearerToken() = runTest {
        var seenAuth = ""
        var seenEpgBody = ""
        val mockHttp = HttpClient(MockEngine { req ->
            seenAuth = req.headers[HttpHeaders.Authorization].orEmpty()
            val bodyText = (req.body as? TextContent)?.text.orEmpty()
            when (req.url.encodedPath) {
                "/epg/uk" -> {
                    seenEpgBody = bodyText
                    respond(
                        """{"updatedAt":1700,"fromMs":1000,"toMs":2000,"channels":[],"programmes":[]}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }
                "/iptv/setup/start" -> respond(
                    """{"code":"3467-9ACD","path":"/iptv?code=3467-9ACD","expires":1700600000}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
                else -> respond("""{"error":"not found"}""", HttpStatusCode.NotFound, jsonHeaders)
            }
        })

        val sync = Sync("https://sync.example", mockHttp, Session("tok-123", "acc-1"))
        val epgJson = sync.ukEpg(1000L, 2000L)
        assertEquals("Bearer tok-123", seenAuth)
        val parsedReq = Json.parseToJsonElement(seenEpgBody).jsonObject
        assertEquals(1000L, parsedReq["fromMs"]?.jsonPrimitive?.longOrNull)
        assertEquals(2000L, parsedReq["toMs"]?.jsonPrimitive?.longOrNull)
        assertTrue(epgJson.contains("\"updatedAt\":1700"))

        val setup = sync.startIptvSetup()
        assertEquals("3467-9ACD", setup["code"]?.jsonPrimitive?.content)
        assertEquals("/iptv?code=3467-9ACD", setup["path"]?.jsonPrimitive?.content)
        assertEquals(1700600000L, setup["expires"]?.jsonPrimitive?.longOrNull)
    }

    @Test
    fun lumosIptvSourcesAndPrefsHandleBothJsonShapesAndViewerScoping() = runTest {
        val lumos = Lumos("https://sync.example")
        assertNull(lumos.iptvSourcesJson())
        assertNull(lumos.iptvPrefsJson())

        val src = IptvSource.Xtream(
            id = "iptv_1",
            name = "UK Main",
            serverUrl = "http://tv.example:8080",
            username = "u",
            password = "p",
        )
        val arrayJson = IptvCodec.encodeSources(listOf(src))
        lumos.setIptvSourcesJson(arrayJson, now = 100L)
        val roundtrip = lumos.iptvSourcesJson()
        assertNotNull(roundtrip)
        assertEquals(listOf(src), IptvCodec.decodeSources(roundtrip))

        // Restore into fresh Lumos instance preserves wrapped sources and viewer prefs
        val prefs = IptvProfilePrefs(favourites = listOf("uk.bbc.one"), showNonUk = false)
        lumos.setIptvPrefsJson(IptvCodec.encodePrefs(prefs), now = 105L)
        lumos.viewer = "kids"
        assertNull(lumos.iptvPrefsJson())
        lumos.viewer = "main"
        assertEquals(prefs, IptvCodec.decodePrefs(lumos.iptvPrefsJson()!!))

        val savedState = lumos.state()
        val restored = Lumos("https://sync.example").apply { restore(savedState) }
        assertEquals(listOf(src), IptvCodec.decodeSources(restored.iptvSourcesJson()!!))
        assertEquals(prefs, IptvCodec.decodePrefs(restored.iptvPrefsJson()!!))
        assertNotNull(restored.newIptvRepository())
    }

    @Test
    fun iptvCodecFiltersInvalidEntriesAndRoundtripsEpgCache() {
        val mixed = """
            {"sources":[
              {"type":"m3u","id":"m1","name":"Valid M3U","url":"https://example.com/list.m3u"},
              {"type":"xtream","id":"bad","name":"","serverUrl":"","username":"","password":""},
              {"type":"stalker","id":"s1","name":"Portal","portalUrl":"http://p.example/c/","macAddress":"00:1A:79:11:22:33"}
            ]}
        """.trimIndent()
        val decoded = IptvCodec.decodeSources(mixed)
        assertEquals(2, decoded.size)
        assertEquals("m1", decoded[0].id)
        assertEquals("s1", decoded[1].id)

        val prog = EpgProgramme(
            id = "uk.bbc.one:1000",
            channelId = "uk.bbc.one",
            title = "Match of the Day",
            startMs = 1_000_000L,
            endMs = 4_600_000L,
            isLiveSport = false,
            hasCatchup = true,
        )
        val snap = EpgCacheSnapshot(
            updatedAtMs = 999L,
            builtIn = listOf(prog),
            provider = emptyList(),
            aliases = mapOf("bbcone" to "uk.bbc.one"),
        )
        val decodedSnap = IptvCodec.decodeEpgCache(IptvCodec.encodeEpgCache(snap))
        assertEquals(snap, decodedSnap)
    }
}
