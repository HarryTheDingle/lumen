package lumos.engine

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class StremioBridgeTest {
    @Test fun writesDatesLikeStremio() {
        assertEquals("1970-01-01T00:00:00.000Z", StremioBridge.iso(0))
        assertEquals("2026-11-17T15:04:05.123Z", StremioBridge.iso(1_794_927_845_123))
        assertEquals("2024-02-29T23:59:59.999Z", StremioBridge.iso(1_709_251_199_999))
    }

    @Test fun updatesTheLibraryItem() = runBlocking {
        val sent = mutableListOf<Pair<String, String>>()
        val existing = """{"result":[{"_id":"tt1","name":"Film","type":"movie","removed":false,"temp":false,"_ctime":"2026-01-01T00:00:00.000Z","_mtime":"2026-01-01T00:00:00.000Z",
            "state":{"timeOffset":600000,"duration":7200000,"video_id":"tt1","timesWatched":0,"timeWatched":600000,"overallTimeWatched":600000}}]}"""
        val http = HttpClient(MockEngine { req: HttpRequestData ->
            sent += req.url.encodedPath to (req.body as TextContent).text
            respond(if (req.url.encodedPath.endsWith("datastoreGet")) existing else """{"result":{"success":true}}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        StremioBridge("key", http).progress("tt1", "movie", "Film", null, "tt1", 1800.0, 7200.0, 1_794_927_845_123, finished = false)
        val put = json.parseToJsonElement(sent.last().second).obj()!!["changes"].arr().single().obj()!!
        val state = put["state"].obj()!!
        assertEquals(1_800_000L, state.long("timeOffset"))
        assertEquals(1_800_000L, state.long("timeWatched"))
        assertEquals("2026-11-17T15:04:05.123Z", put.str("_mtime"))
        assertEquals(false, put.bool("removed"))

        StremioBridge("key", http).progress("tt1", "movie", "Film", null, "tt1", 7100.0, 7200.0, 1_794_927_900_000, finished = true)
        val done = json.parseToJsonElement(sent.last().second).obj()!!["changes"].arr().single().obj()!!["state"].obj()!!
        assertEquals(0L, done.long("timeOffset"))
        assertEquals(1L, done.long("timesWatched"))
        assertEquals(1L, done.long("flaggedWatched"))
    }
}
