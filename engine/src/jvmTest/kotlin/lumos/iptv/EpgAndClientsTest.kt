package lumos.iptv

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpgAndClientsTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val xmlHeaders = headersOf(HttpHeaders.ContentType, "application/xml")

    @Test
    fun utcTimeCalendarMathXmltvIsoAndBase64() {
        val leapEpoch = fromUtcFields(2024, 2, 29, 23, 45, 12)
        assertEquals(UtcFields(2024, 2, 29, 23, 45, 12), toUtcFields(leapEpoch))
        assertEquals("2024-02-29:23-45", formatXtreamTimestamp(leapEpoch))

        val y2kEpoch = fromUtcFields(2000, 2, 29, 0, 0, 0)
        assertEquals(UtcFields(2000, 2, 29, 0, 0, 0), toUtcFields(y2kEpoch))

        // Pre-1970 negative epoch
        val preEpoch = fromUtcFields(1969, 12, 31, 12, 0, 0)
        assertTrue(preEpoch < 0L)
        assertEquals(UtcFields(1969, 12, 31, 12, 0, 0), toUtcFields(preEpoch))

        // XMLTV offsets
        assertEquals(fromUtcFields(2026, 11, 21, 22, 30, 0), parseXmltvTime("20261121223000 +0000"))
        assertEquals(fromUtcFields(2026, 11, 22, 3, 30, 0), parseXmltvTime("20261121223000 -0500"))
        assertEquals(fromUtcFields(2026, 11, 21, 22, 30, 0), parseXmltvTime("202611212230"))
        assertEquals(fromUtcFields(2026, 11, 21, 22, 30, 0), parseXmltvTime("20261121223000"))
        assertNull(parseXmltvTime("20250229120000 +0000")) // 2025 not leap year
        assertNull(parseXmltvTime("20261301120000")) // invalid month

        // ISO & SQL timestamps
        assertEquals(fromUtcFields(2026, 11, 21, 22, 30, 15), parseIsoUtcMillis("2026-11-21T22:30:15Z"))
        assertEquals(fromUtcFields(2026, 11, 21, 22, 30, 15), parseIsoUtcMillis("2026-11-21 22:30:15"))
        assertEquals(fromUtcFields(2026, 11, 22, 3, 30, 0), parseIsoUtcMillis("2026-11-21T22:30:00-05:00"))

        // Base64 (padded, unpadded, plain text fallback, invalid safely handled)
        assertEquals("BBC News", decodeBase64Utf8("QkJDIE5ld3M="))
        assertEquals("BBC News", decodeBase64Utf8("QkJDIE5ld3M"))
        assertEquals("Match of the Day", decodeBase64Utf8("Match of the Day"))
        assertEquals("", decodeBase64Utf8("   "))
        assertEquals("!!!", decodeBase64Utf8("!!!"))

        assertEquals("a%20b%26c%3D1", urlEncode("a b&c=1"))
    }

    @Test
    fun epgProgrammeHelpersAndXmltvAndStoreSearchMerge() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="bbc1.uk">
                <display-name>BBC One</display-name>
                <icon src="https://img/bbc1.png"/>
              </channel>
              <channel id="skysports.main">
                <display-name>Sky Sports Main Event</display-name>
              </channel>
              <programme start="20261121190000 +0000" stop="20261121200000 +0000" channel="bbc1.uk">
                <title>Six Nations &amp; Live Rugby</title>
                <sub-title>England v Ireland</sub-title>
                <desc><![CDATA[Live from Twickenham <HD>]]> &#x2014; full coverage.</desc>
                <category>Live Sport</category>
              </programme>
              <programme start="20261121200000 +0000" stop="20261121210000 +0000" channel="bbc1.uk">
                <title>BBC News at Eight</title>
                <category>News</category>
              </programme>
              <programme start="20261121170000 +0000" stop="20261121180000 +0000" channel="skysports.main">
                <title>Premier League Highlights</title>
                <category>Sports</category>
              </programme>
              <programme start="20261121190000 +0000" stop="20261121210000 +0000" channel="skysports.main">
                <title>Live Premier League: Arsenal vs Chelsea</title>
                <category>Football</category>
              </programme>
            </tv>
        """.trimIndent()

        val parsed = XmltvParser.parse(xml)
        assertEquals(2, parsed.channels.size)
        assertEquals(4, parsed.programmes.size)
        assertEquals("Six Nations & Live Rugby", parsed.programmes[0].title)
        assertTrue(parsed.programmes[0].description!!.contains("<HD>"))
        assertTrue(parsed.programmes[0].description!!.contains("—"))
        assertTrue(parsed.programmes[0].isLiveSport)
        assertEquals("Rugby", parsed.programmes[0].sportTag)
        assertFalse(parsed.programmes[2].isLiveSport) // Highlights excluded
        assertTrue(parsed.programmes[3].isLiveSport)
        assertEquals("Football", parsed.programmes[3].sportTag)

        val store = EpgStore()
        store.putXmltv(parsed)
        // Built-in UK EPG overrides overlapping slot title while preserving provider subtitle/desc
        val t19 = fromUtcFields(2026, 11, 21, 19, 0, 0)
        val t20 = fromUtcFields(2026, 11, 21, 20, 0, 0)
        store.putBuiltInUkEpg(
            listOf(
                EpgProgramme(
                    id = "uk1",
                    channelId = "uk.bbc.one",
                    title = "Live Six Nations Rugby: England vs Ireland",
                    startMs = t19,
                    endMs = t20,
                    isLiveSport = true,
                    sportTag = "Rugby",
                )
            )
        )

        val bbcChannel = CanonicalChannel(
            id = "uk.bbc.one",
            number = 101,
            name = "BBC One",
            category = ChannelCategory.TERRESTRIAL,
            logoUrl = null,
            epgId = "bbc1.uk",
            streams = listOf(
                StreamSource(
                    id = "b1", sourceId = "s1", sourceName = "S1", rawName = "BBC One",
                    groupTitle = "UK", tvgId = "bbc1.uk", logoUrl = null, streamUrl = "http://x/1.ts",
                    quality = StreamQuality(1080),
                    catchup = CatchupConfig(CatchupMode.XTREAM, 7),
                )
            ),
            hasCatchup = true,
            maxCatchupDays = 7,
        )
        val skySportsChannel = CanonicalChannel(
            id = "uk.sky.sports.main",
            number = 301,
            name = "Sky Sports Main Event",
            category = ChannelCategory.SPORTS,
            logoUrl = null,
            epgId = "skysports.main",
            streams = listOf(
                StreamSource(
                    id = "s1", sourceId = "s1", sourceName = "S1", rawName = "Sky Sports Main Event",
                    groupTitle = "Sports", tvgId = "skysports.main", logoUrl = null, streamUrl = "http://x/2.ts",
                    quality = StreamQuality(1080, fps = 50),
                    catchup = CatchupConfig(CatchupMode.XTREAM, 7),
                )
            ),
            hasCatchup = true,
            maxCatchupDays = 7,
        )

        val nowMs = fromUtcFields(2026, 11, 21, 19, 15, 0)
        val (nowProg, nextProg) = store.nowAndNext(bbcChannel, nowMs)
        assertNotNull(nowProg)
        assertEquals("Live Six Nations Rugby: England vs Ireland", nowProg.title)
        assertEquals("England v Ireland", nowProg.subtitle) // enriched from XMLTV!
        assertEquals(0.25f, nowProg.progressAt(nowMs), 0.001f)
        assertEquals(45, nowProg.remainingMinutesAt(nowMs))
        assertEquals("BBC News at Eight", nextProg?.title)

        val liveSports = store.liveSportsNow(listOf(bbcChannel, skySportsChannel), nowMs)
        assertEquals(2, liveSports.size)

        val catchup = store.catchupProgrammes(skySportsChannel, nowMs)
        assertEquals(1, catchup.size)
        assertEquals("Premier League Highlights", catchup[0].title)
        assertTrue(catchup[0].isCatchupEligible(nowMs, 7))
        assertFalse(catchup[0].isCatchupEligible(nowMs, 0))

        val searchResults = store.search("Arsenal", listOf(bbcChannel, skySportsChannel), nowMs)
        assertEquals(1, searchResults.onNow.size)
        assertEquals("Live Premier League: Arsenal vs Chelsea", searchResults.onNow[0].second.title)
    }

    @Test
    fun xtreamAndStalkerClientsAndRepositoryEndToEnd() = runTest {
        var handshakeCalls = 0
        var seenUserAgent: String? = null

        val http = HttpClient(MockEngine { req ->
            val url = req.url.toString()
            if (url.contains("xtream.test")) {
                seenUserAgent = req.headers[HttpHeaders.UserAgent]
            }
            when {
                url == "https://xtream.test/player_api.php?username=user1&password=pass1" ->
                    respond(
                        """{"user_info":{"status":"Active","auth":1,"active_cons":"1","max_connections":"2","exp_date":"1800000000"},"server_info":{"timezone":"Europe/London"}}""",
                        HttpStatusCode.OK, jsonHeaders,
                    )
                url.contains("action=get_live_categories") ->
                    respond("""[{"category_id":"10","category_name":"UK Sports"}]""", HttpStatusCode.OK, jsonHeaders)
                url.contains("action=get_live_streams") ->
                    respond(
                        """[{"stream_id":777,"name":"UK | Sky Sports Main Event FHD 50FPS","category_id":"10","epg_channel_id":"skysportsmainevent.uk","stream_icon":"http://img/ss.png","tv_archive":1,"tv_archive_duration":7}]""",
                        HttpStatusCode.OK, jsonHeaders,
                    )
                url.contains("action=get_short_epg") ->
                    respond(
                        """{"epg_listings":[{"id":"e1","title":"U2t5IFNwb3J0cyBMaXZl","description":"UHJlbWllciBMZWFndWU","start_timestamp":1795284000,"stop_timestamp":1795287600,"has_archive":1}]}""",
                        HttpStatusCode.OK, jsonHeaders,
                    )
                url.contains("action=get_simple_data_table") ->
                    respond(
                        """{"epg_listings":[{"id":"e2","title":"UGFzdCBNYXRjaA==","start":"2026-11-21 15:00:00","end":"2026-11-21 17:00:00","has_archive":1}]}""",
                        HttpStatusCode.OK, jsonHeaders,
                    )
                url.contains("stalker.test/portal.php") && url.contains("action=handshake") -> {
                    handshakeCalls++
                    val tok = if (handshakeCalls == 1) "tok-1" else "tok-2"
                    respond("""{"js":{"token":"$tok"}}""", HttpStatusCode.OK, jsonHeaders)
                }
                url.contains("stalker.test/portal.php") && url.contains("action=get_genres") ->
                    respond("""{"js":[{"id":"1","title":"UK TV"}]}""", HttpStatusCode.OK, jsonHeaders)
                url.contains("stalker.test/portal.php") && url.contains("action=get_all_channels") ->
                    respond(
                        """{"js":{"data":[{"id":"501","name":"BBC One 4K","cmd":"ffmpeg http://stb.stream/501","tv_genre_id":"1","enable_tv_archive":1,"tv_archive_duration":5}]}}""",
                        HttpStatusCode.OK, jsonHeaders,
                    )
                url.contains("stalker.test/portal.php") && url.contains("type=itv&action=create_link") -> {
                    val auth = req.headers[HttpHeaders.Authorization]
                    if (auth == "Bearer tok-1") {
                        respond("Authorization failed.", HttpStatusCode.Unauthorized, jsonHeaders)
                    } else {
                        respond("""{"js":{"cmd":"ffmpeg http://resolved.stalker/live/501.ts"}}""", HttpStatusCode.OK, jsonHeaders)
                    }
                }
                url.contains("stalker.test/portal.php") && url.contains("type=tv_archive&action=create_link") ->
                    respond("""{"js":{"cmd":"auto http://resolved.stalker/archive/501.ts"}}""", HttpStatusCode.OK, jsonHeaders)
                url.contains("stalker.test/portal.php") && url.contains("action=get_epg_info") ->
                    respond(
                        """{"js":{"data":{"501":[{"id":"st1","ch_id":"501","name":"Match of the Day","time":"2026-11-21 22:30:00","time_to":"2026-11-21 23:45:00","mark_archive":1}]}}}""",
                        HttpStatusCode.OK, jsonHeaders,
                    )
                url == "https://epg.test/uk.xml" ->
                    respond(
                        """<tv><programme start="20261121180000 +0000" stop="20261121190000 +0000" channel="bbc1.uk"><title>Pointless</title></programme></tv>""",
                        HttpStatusCode.OK, xmlHeaders,
                    )
                url == "https://broken.test/list.m3u" ->
                    respond("down", HttpStatusCode.InternalServerError)
                else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders)
            }
        })

        val xtreamSrc = IptvSource.Xtream(
            id = "xt1", name = "Xtream", serverUrl = "https://xtream.test",
            username = "user1", password = "pass1", outputFormat = "m3u8", userAgent = "LumosTV/1.0",
        )
        val stalkerSrc = IptvSource.Stalker(
            id = "st1", name = "Stalker", portalUrl = "https://stalker.test/c/", macAddress = "00:1A:79:AA:BB:CC",
        )
        val m3uInline = IptvSource.M3u(
            id = "m3u1", name = "Inline M3U",
            inlineContent = "#EXTM3U\n#EXTINF:-1 tvg-id=\"bbc1.uk\",BBC One HD\nhttps://m3u.live/bbc1.ts",
            epgUrl = "https://epg.test/uk.xml",
        )
        val brokenM3u = IptvSource.M3u(
            id = "bad", name = "Broken M3U", url = "https://broken.test/list.m3u",
        )

        // Direct Stalker portal normalization checks
        val stalkerClient = StalkerClient(http)
        assertEquals("https://stalker.test/portal.php", stalkerClient.portalEndpoint("https://stalker.test/c/"))
        assertEquals("https://h.test/stalker_portal/server/load.php", stalkerClient.portalEndpoint("https://h.test/stalker_portal/c/"))
        assertEquals("https://h.test/server/load.php", stalkerClient.portalEndpoint("https://h.test/server/load.php"))

        // Repository parallel load with fault isolation
        val repo = IptvRepository(http)
        val load = repo.loadSources(listOf(xtreamSrc, stalkerSrc, m3uInline, brokenM3u))

        assertEquals("LumosTV/1.0", seenUserAgent)
        assertEquals(4, load.reports.size)
        assertTrue(load.reports.first { it.sourceId == "xt1" }.success)
        assertEquals("Active", load.reports.first { it.sourceId == "xt1" }.accountInfo?.status)
        assertFalse(load.reports.first { it.sourceId == "bad" }.success)

        // BBC One merged M3U + Stalker (4K Stalker stream ranked above HD M3U)
        val bbc = load.channels.first { it.id == "uk.bbc.one" }
        assertEquals(2, bbc.streams.size)
        assertTrue(bbc.streams[0].requiresResolve)

        // Resolving Stalker live stream automatically refreshes expired token tok-1 -> tok-2
        val resolvedLive = repo.resolveLiveStream(bbc.streams[0])
        assertEquals("http://resolved.stalker/live/501.ts", resolvedLive.url)
        assertEquals(2, handshakeCalls)

        // Subsequent catchup resolution reuses cached tok-2 without another handshake
        val startMs = fromUtcFields(2026, 11, 21, 18, 0, 0)
        val endMs = fromUtcFields(2026, 11, 21, 19, 0, 0)
        val resolvedCatchup = repo.resolveCatchupStream(bbc.streams[0], startMs, endMs, nowMs = endMs + 60_000L)
        assertEquals("http://resolved.stalker/archive/501.ts", resolvedCatchup?.url)
        assertEquals(true, resolvedCatchup?.isCatchup)
        assertEquals(2, handshakeCalls)

        // Xtream short + archive EPG + XMLTV + UK JSON EPG
        val sky = load.channels.first { it.id == "uk.sky.sports.main" }
        val shortEpg = repo.loadShortEpg(sky)
        assertEquals(1, shortEpg.size)
        assertEquals("Sky Sports Live", shortEpg[0].title)
        assertEquals("Premier League", shortEpg[0].description)

        val archEpg = repo.loadArchiveEpg(sky)
        assertEquals(1, archEpg.size)
        assertEquals("Past Match", archEpg[0].title)
        assertTrue(archEpg[0].hasCatchup)

        val ingestedProvider = repo.loadProviderEpg()
        assertTrue(ingestedProvider >= 2)

        val ukCount = repo.loadUkEpgJson(
            """{"channels":{"uk.bbc.one":[{"title":"BBC Breakfast","startMs":1795240800000,"endMs":1795248000000}]}}"""
        )
        assertEquals(1, ukCount)

        // Warm snapshot serialize/restore + polymorphic IptvSource & LivePlaybackState
        val snapshot = repo.exportWarmSnapshot(nowMs = 999L)
        val coldRepo = IptvRepository(http)
        assertTrue(coldRepo.restoreWarmSnapshot(snapshot))
        assertEquals(load.channels.size, coldRepo.currentChannels().size)

        val encodedSource = iptvJson.encodeToString<IptvSource>(xtreamSrc)
        val decodedSource = iptvJson.decodeFromString<IptvSource>(encodedSource)
        assertEquals(xtreamSrc, decodedSource)

        val state: LivePlaybackState = LivePlaybackState.Playing(bbc, bbc.streams[0], isCatchup = false)
        assertTrue(state is LivePlaybackState.Playing)
    }

    @Test
    fun xtreamFetchLiveStreamsSkipsForeignCategoriesAndRawNamesBeforeAllocating() = runTest {
        val http = HttpClient(MockEngine { req ->
            val url = req.url.toString()
            when {
                url.contains("action=get_live_categories") -> respond(
                    """
                    [
                      {"category_id":"10","category_name":"UK Sports"},
                      {"category_id":"20","category_name":"FR | Cinema"},
                      {"category_id":"30","category_name":"Arabic"},
                      {"category_id":"40","category_name":"CA | Sports"},
                      {"category_id":"50","category_name":"USA Entertainment"}
                    ]
                    """.trimIndent(),
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
                url.contains("action=get_live_streams") -> respond(
                    """
                    [
                      {"stream_id":1,"name":"UK | Sky Sports Main Event FHD","category_id":"10"},
                      {"stream_id":2,"name":"Canal+ Cinema","category_id":"20"},
                      {"stream_id":3,"name":"MBC 1","category_id":"30"},
                      {"stream_id":4,"name":"DE | Sky Sport 1","category_id":"10"},
                      {"stream_id":5,"name":"CA | TSN 1 HD","category_id":"40"},
                      {"stream_id":6,"name":"US | ESPN HD","category_id":"50"}
                    ]
                    """.trimIndent(),
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
                else -> respond("[]", HttpStatusCode.OK, jsonHeaders)
            }
        })

        val client = XtreamClient(http)
        val source = IptvSource.Xtream(
            id = "xt", name = "Xtream", serverUrl = "https://xtream.test",
            username = "u", password = "p",
        )

        val ukFiltered = client.fetchLiveStreams(source, skipForeign = true)
        assertEquals(listOf("xt:xtream:1", "xt:xtream:5", "xt:xtream:6"), ukFiltered.map { it.id })

        val allStreams = client.fetchLiveStreams(source, skipForeign = false)
        assertEquals(6, allStreams.size)
    }

    @Test
    fun repositorySkipsXtreamAutoXmltvAndRejectsOversizeXmltvGracefully() = runTest {
        val requestedUrls = mutableListOf<String>()
        val hugePayload = ByteArray(IptvRepository.MAX_XMLTV_BYTES.toInt() + 2048) { ' '.code.toByte() }

        val http = HttpClient(MockEngine { req ->
            val url = req.url.toString()
            requestedUrls += url
            when {
                url.contains("action=get_live_categories") ->
                    respond("""[{"category_id":"1","category_name":"UK TV"}]""", HttpStatusCode.OK, jsonHeaders)
                url.contains("action=get_live_streams") ->
                    respond("""[{"stream_id":101,"name":"BBC One HD","category_id":"1"}]""", HttpStatusCode.OK, jsonHeaders)
                url.contains("player_api.php") ->
                    respond("""{"user_info":{"status":"Active","auth":1}}""", HttpStatusCode.OK, jsonHeaders)
                url == "https://epg.test/explicit.xml" ->
                    respond(
                        """<tv><programme start="20261121190000 +0000" stop="20261121200000 +0000" channel="bbc1.uk"><title>Explicit Show</title></programme></tv>""",
                        HttpStatusCode.OK, xmlHeaders,
                    )
                url == "https://epg.test/m3u-header.xml" ->
                    respond(
                        """<tv><programme start="20261121200000 +0000" stop="20261121210000 +0000" channel="itv1.uk"><title>Header Show</title></programme></tv>""",
                        HttpStatusCode.OK, xmlHeaders,
                    )
                url == "https://epg.test/oversize-header.xml" ->
                    respond(
                        content = "<tv></tv>",
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentType to listOf("application/xml"),
                            HttpHeaders.ContentLength to listOf((IptvRepository.MAX_XMLTV_BYTES + 1L).toString()),
                        ),
                    )
                url == "https://epg.test/oversize-stream.xml" ->
                    respond(
                        content = hugePayload,
                        status = HttpStatusCode.OK,
                        headers = xmlHeaders,
                    )
                else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders)
            }
        })

        val xtreamNoEpg = IptvSource.Xtream(
            id = "xt-noepg", name = "Xtream No EPG",
            serverUrl = "https://xtream-noepg.test", username = "u", password = "p", epgUrl = null,
        )
        val xtreamBlankEpg = IptvSource.Xtream(
            id = "xt-blank", name = "Xtream Blank EPG",
            serverUrl = "https://xtream-blank.test", username = "u", password = "p", epgUrl = "   ",
        )
        val xtreamExplicitEpg = IptvSource.Xtream(
            id = "xt-explicit", name = "Xtream Explicit EPG",
            serverUrl = "https://xtream-exp.test", username = "u", password = "p",
            epgUrl = "https://epg.test/explicit.xml",
        )
        val m3uWithHeader = IptvSource.M3u(
            id = "m3u-hdr", name = "M3U Header",
            inlineContent = "#EXTM3U url-tvg=\"https://epg.test/m3u-header.xml\"\n#EXTINF:-1,ITV1 HD\nhttps://cdn.test/itv1.ts",
        )

        val repo = IptvRepository(http)
        repo.loadSources(listOf(xtreamNoEpg, xtreamBlankEpg, xtreamExplicitEpg, m3uWithHeader))
        val ingested = repo.loadProviderEpg()

        assertEquals(2, ingested)
        assertFalse(requestedUrls.any { it.contains("xmltv.php") })
        assertTrue("https://epg.test/explicit.xml" in requestedUrls)
        assertTrue("https://epg.test/m3u-header.xml" in requestedUrls)

        // Both Content-Length > 10 MB and streamed body > 10 MB return 0 gracefully without OOM
        assertEquals(0, repo.loadXmltv("https://epg.test/oversize-header.xml"))
        assertEquals(0, repo.loadXmltv("https://epg.test/oversize-stream.xml"))
    }

    @Test
    fun epgStoreBinarySearchNowAndNextAndChannelCacheInvalidation() {
        val store = EpgStore()
        val baseMs = fromUtcFields(2026, 11, 21, 0, 0, 0)
        val slotMs = 30 * 60_000L

        // Build 1000 half-hour programmes with a deliberate gap at index 500
        val largeList = (0 until 1000).mapNotNull { i ->
            if (i == 500) return@mapNotNull null
            val start = baseMs + i * slotMs
            EpgProgramme(
                id = "p-$i",
                channelId = "test.uk",
                title = "Show $i",
                startMs = start,
                endMs = start + slotMs,
            )
        }

        // Before first programme
        val (beforeNow, beforeNext) = store.nowAndNextFromList(largeList, baseMs - 1000L)
        assertNull(beforeNow)
        assertEquals("Show 0", beforeNext?.title)

        // Exact start and midpoint of programme 250
        val t250 = baseMs + 250 * slotMs
        val (exactNow, exactNext) = store.nowAndNextFromList(largeList, t250)
        assertEquals("Show 250", exactNow?.title)
        assertEquals("Show 251", exactNext?.title)

        val (midNow, midNext) = store.nowAndNextFromList(largeList, t250 + 60_000L)
        assertEquals("Show 250", midNow?.title)
        assertEquals("Show 251", midNext?.title)

        // Inside gap at slot 500
        val t500 = baseMs + 500 * slotMs
        val (gapNow, gapNext) = store.nowAndNextFromList(largeList, t500 + 60_000L)
        assertNull(gapNow)
        assertEquals("Show 501", gapNext?.title)

        // After last programme
        val tEnd = baseMs + 1000 * slotMs
        val (afterNow, afterNext) = store.nowAndNextFromList(largeList, tEnd + 60_000L)
        assertNull(afterNow)
        assertNull(afterNext)

        // Overlapping long block + short bulletin queried after short bulletin ended
        val overlapping = listOf(
            EpgProgramme("long", "ov.uk", "Long Block", startMs = baseMs, endMs = baseMs + 4 * slotMs),
            EpgProgramme("short", "ov.uk", "Short Bulletin", startMs = baseMs + slotMs, endMs = baseMs + 2 * slotMs),
            EpgProgramme("after", "ov.uk", "Later Show", startMs = baseMs + 5 * slotMs, endMs = baseMs + 6 * slotMs),
        )
        val (ovNow, ovNext) = store.nowAndNextFromList(overlapping, baseMs + 3 * slotMs)
        assertEquals("Long Block", ovNow?.title)
        assertEquals("Later Show", ovNext?.title)

        // channelCache invalidation via putBuiltInUkEpg, putProviderEpg, putXmltv, and clear
        val bbc = CanonicalChannel(
            id = "uk.bbc.one",
            number = 101,
            name = "BBC One",
            category = ChannelCategory.TERRESTRIAL,
            logoUrl = null,
            epgId = "bbc1.uk",
            streams = emptyList(),
        )
        val custom = CanonicalChannel(
            id = "custom.local",
            number = null,
            name = "Local TV One",
            category = ChannelCategory.OTHER,
            logoUrl = null,
            epgId = null,
            streams = emptyList(),
        )

        // Populate channelCache with empty misses first
        assertEquals(null to null, store.nowAndNext(bbc, baseMs + 10_000L))
        assertEquals(null to null, store.nowAndNext(custom, baseMs + 10_000L))

        // ingestInto via putBuiltInUkEpg invalidates channelCache
        store.putBuiltInUkEpg(
            listOf(EpgProgramme("b1", "uk.bbc.one", "BBC Breakfast", startMs = baseMs, endMs = baseMs + slotMs))
        )
        assertEquals("BBC Breakfast", store.nowAndNext(bbc, baseMs + 10_000L).first?.title)

        // ingestInto via putProviderEpg invalidates channelCache
        store.putProviderEpg(
            listOf(EpgProgramme("b2", "bbc1.uk", "BBC Next Show", startMs = baseMs + slotMs, endMs = baseMs + 2 * slotMs))
        )
        assertEquals("BBC Next Show", store.nowAndNext(bbc, baseMs + 10_000L).second?.title)

        // putXmltv invalidates channelCache including display-name alias resolution
        store.putXmltv(
            XmltvParseResult(
                channels = listOf(XmltvChannelMeta(id = "local.alias.id", displayNames = listOf("Local TV One"))),
                programmes = listOf(
                    EpgProgramme("c1", "local.alias.id", "Local News", startMs = baseMs, endMs = baseMs + slotMs)
                ),
            )
        )
        assertEquals("Local News", store.nowAndNext(custom, baseMs + 10_000L).first?.title)

        // clear() invalidates channelCache
        store.clear()
        assertEquals(null to null, store.nowAndNext(bbc, baseMs + 10_000L))
        assertEquals(null to null, store.nowAndNext(custom, baseMs + 10_000L))
    }

    @Test
    fun repositoryPreFiltersM3uAndStalkerBeforeNormalizationWhenShowNonUkFalse() = runTest {
        val http = HttpClient(MockEngine { req ->
            val url = req.url.toString()
            when {
                url.contains("action=handshake") ->
                    respond("""{"js":{"token":"tok-1"}}""", HttpStatusCode.OK, jsonHeaders)
                url.contains("action=get_genres") ->
                    respond("""{"js":[{"id":"1","title":"UK TV"},{"id":"2","title":"FR | Sport"}]}""", HttpStatusCode.OK, jsonHeaders)
                url.contains("action=get_all_channels") ->
                    respond(
                        """{"js":{"data":[
                            {"id":"10","name":"BBC Two HD","cmd":"ffmpeg http://s/10","tv_genre_id":"1"},
                            {"id":"11","name":"Canal+ Foot","cmd":"ffmpeg http://s/11","tv_genre_id":"2"},
                            {"id":"12","name":"DE | ZDF HD","cmd":"ffmpeg http://s/12","tv_genre_id":"1"}
                        ]}}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders)
            }
        })

        val m3u = IptvSource.M3u(
            id = "m3u-pre", name = "M3U",
            inlineContent = """
                #EXTM3U url-tvg="https://epg.test/guide.xml"
                #EXTINF:-1 group-title="UK TV",BBC One HD
                https://m3u.test/bbc1.ts
                #EXTINF:-1 group-title="FR | Cinema",Canal+
                https://m3u.test/canal.ts
                #EXTINF:-1 group-title="General",AR | MBC 1
                https://m3u.test/mbc1.ts
            """.trimIndent(),
        )
        val stalker = IptvSource.Stalker(
            id = "st-pre", name = "Stalker", portalUrl = "https://st.test/c/", macAddress = "00:1A:79:11:22:33",
        )

        val repo = IptvRepository(http)
        val result = repo.loadSources(
            sources = listOf(m3u, stalker),
            prefs = IptvProfilePrefs(showNonUk = false),
        )

        assertEquals(1, result.reports.first { it.sourceId == "m3u-pre" }.streamCount)
        assertEquals(listOf("https://epg.test/guide.xml"), result.reports.first { it.sourceId == "m3u-pre" }.discoveredEpgUrls)
        assertEquals(1, result.reports.first { it.sourceId == "st-pre" }.streamCount)
        assertEquals(listOf("uk.bbc.one", "uk.bbc.two"), result.channels.map { it.id })
    }

    @Test
    fun xtreamFetchLiveStreamsHandlesPhpMixedTypesIsAdultAndInvalidBodies() = runTest {
        var catPayload = """
            [
              {"category_id":10,"category_name":"UK Entertainment"},
              {"category_id":"20","category_name":"(HI) Hindi"}
            ]
        """.trimIndent()
        var streamPayload = """
            [
              {"stream_id":101,"name":"BBC One HD","category_id":10,"epg_channel_id":false,"stream_icon":"http://img/bbc1.png","tv_archive":true,"tv_archive_duration":"7","is_adult":"0"},
              {"stream_id":"102","name":"ITV1 HD","category_id":"10","epg_channel_id":999,"stream_icon":false,"tv_archive":"1","tv_archive_duration":5,"is_adult":false},
              {"stream_id":103,"name":"Channel 4 HD","category_id":false,"category_name":"UK TV","epg_channel_id":null,"tv_archive":1,"tv_archive_duration":false,"is_adult":0},
              {"stream_id":104,"name":"Adult Channel A","category_id":10,"is_adult":"1"},
              {"stream_id":105,"name":"Adult Channel B","category_id":10,"is_adult":"TRUE"},
              {"stream_id":106,"name":"Adult Channel C","category_id":10,"is_adult":true},
              {"stream_id":107,"name":"24/7-AR MOHAMMED ABDO","category_id":10,"is_adult":"0"},
              {"stream_id":108,"name":"(HI) AAPKA COLORS","category_id":20,"is_adult":"0"}
            ]
        """.trimIndent()

        val http = HttpClient(MockEngine { req ->
            val url = req.url.toString()
            when {
                url.contains("action=get_live_categories") -> respond(catPayload, HttpStatusCode.OK, jsonHeaders)
                url.contains("action=get_live_streams") -> respond(streamPayload, HttpStatusCode.OK, jsonHeaders)
                else -> respond("[]", HttpStatusCode.OK, jsonHeaders)
            }
        })

        val client = XtreamClient(http)
        val source = IptvSource.Xtream(
            id = "xt-mix", name = "Xtream Mixed", serverUrl = "https://xtream.test",
            username = "u", password = "p",
        )

        val filtered = client.fetchLiveStreams(source, skipForeign = true)
        assertEquals(listOf("xt-mix:xtream:101", "xt-mix:xtream:102", "xt-mix:xtream:103"), filtered.map { it.id })

        val bbc = filtered[0]
        assertNull(bbc.tvgId)
        assertEquals("UK Entertainment", bbc.groupTitle)
        assertEquals(CatchupConfig(CatchupMode.XTREAM, days = 7, streamId = "101"), bbc.catchup)

        val itv = filtered[1]
        assertEquals("999", itv.tvgId)
        assertNull(itv.logoUrl)
        assertEquals(CatchupConfig(CatchupMode.XTREAM, days = 5, streamId = "102"), itv.catchup)

        val ch4 = filtered[2]
        assertEquals("UK TV", ch4.groupTitle)
        assertNull(ch4.tvgId)
        assertNull(ch4.catchup)

        // With skipForeign = false, is_adult and foreign streams are still decoded cleanly
        val unfiltered = client.fetchLiveStreams(source, skipForeign = false)
        assertEquals(8, unfiltered.size)

        // Blank and non-array bodies return emptyList without throwing
        catPayload = "   "
        streamPayload = ""
        assertEquals(emptyList(), client.fetchLiveStreams(source, skipForeign = true))

        catPayload = """{"error":"invalid"}"""
        streamPayload = """{"user_info":{"auth":0}}"""
        assertEquals(emptyList(), client.fetchLiveStreams(source, skipForeign = true))
    }

    @Test
    fun xtreamFetchLiveStreamsStreamsChunkedUtf8BomEscapedStringsAndLargeCounts() = runTest {
        val bigUnusedExtra = "x".repeat(70_000)
        val specialItems = listOf(
            """{"stream_id":1,"name":"Sky \"Sports\" {HD} — UK","category_id":"10","epg_channel_id":"skysports.uk"}""",
            """{"stream_id":2,"name":"BBC \\ One [4K] 📺","category_id":"10","extra":{"nested":{"brace":"}"}},"tags":["uk","hd"]}""",
            """{"stream_id":3,"name":"ITV1 {Oversized} — UK","category_id":"10","padding":"$bigUnusedExtra"}""",
        )
        val bulkCount = 5_000
        val payloadBytes = buildString(bulkCount * 96 + 8_192) {
            append('\uFEFF')
            append("  \n\t [")
            specialItems.forEachIndexed { idx, item ->
                if (idx > 0) append(',')
                append(item)
            }
            for (i in 1..bulkCount) {
                append(',')
                val cat = when {
                    i % 5 == 0 -> "10" // UK Sports
                    else -> "20"       // Foreign category
                }
                val adult = if (i % 20 == 0) "1" else "0"
                append("""{"stream_id":${100 + i},"name":"Channel $i — Stream","category_id":"$cat","is_adult":"$adult"}""")
            }
            append(']')
        }.encodeToByteArray()

        val http = HttpClient(MockEngine { req ->
            val url = req.url.toString()
            when {
                url.contains("action=get_live_categories") -> respond(
                    """[{"category_id":"10","category_name":"UK Sports"},{"category_id":"20","category_name":"FR | Cinema"}]""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
                url.contains("action=get_live_streams") -> {
                    val streamed = this@runTest.writer(autoFlush = false) {
                        var offset = 0
                        while (offset < payloadBytes.size) {
                            val chunkLen = when {
                                offset < 24 -> 1       // Split UTF-8 BOM & leading whitespace byte-by-byte
                                offset < 400 -> 7      // Split multi-byte UTF-8 chars & escaped quotes/braces
                                else -> 4_093          // Prime size to cross 64KB scanner read boundaries
                            }
                            val count = minOf(chunkLen, payloadBytes.size - offset)
                            channel.writeFully(payloadBytes, offset, offset + count)
                            channel.flush()
                            offset += count
                            yield()
                        }
                    }.channel
                    respond(streamed, HttpStatusCode.OK, jsonHeaders)
                }
                else -> respond("[]", HttpStatusCode.OK, jsonHeaders)
            }
        })

        val client = XtreamClient(http)
        val source = IptvSource.Xtream(
            id = "xt-stream", name = "Xtream Stream", serverUrl = "https://xtream.test",
            username = "u", password = "p",
        )

        val expectedUkBulkCount = (1..bulkCount).count { i -> i % 5 == 0 && i % 20 != 0 }
        val filtered = client.fetchLiveStreams(source, skipForeign = true)
        assertEquals(3 + expectedUkBulkCount, filtered.size)
        assertEquals("""Sky "Sports" {HD} — UK""", filtered[0].rawName)
        assertEquals("skysports.uk", filtered[0].tvgId)
        assertEquals("UK Sports", filtered[0].groupTitle)
        assertEquals("""BBC \ One [4K] 📺""", filtered[1].rawName)
        assertEquals("ITV1 {Oversized} — UK", filtered[2].rawName)
        assertEquals("Channel 5 — Stream", filtered[3].rawName)

        val unfiltered = client.fetchLiveStreams(source, skipForeign = false)
        assertEquals(3 + bulkCount, unfiltered.size)
    }

    @Test
    fun xtreamFetchLiveStreamsHandlesMalformedObjectsTruncatedTailAndHttpErrors() = runTest {
        var status = HttpStatusCode.OK
        var streamPayload = """[{"stream_id":1,"name":"BBC One HD","category_id":"1"},{not_valid_json},{"stream_id":2,"name":"ITV1 HD","category_id":"1"}]"""

        val http = HttpClient(MockEngine { req ->
            val url = req.url.toString()
            when {
                url.contains("action=get_live_categories") ->
                    respond("""[{"category_id":"1","category_name":"UK TV"}]""", HttpStatusCode.OK, jsonHeaders)
                url.contains("action=get_live_streams") ->
                    respond(streamPayload, status, jsonHeaders)
                else -> respond("[]", HttpStatusCode.OK, jsonHeaders)
            }
        })

        val client = XtreamClient(http)
        val source = IptvSource.Xtream(
            id = "xt-err", name = "Xtream Err", serverUrl = "https://xtream.test",
            username = "u", password = "p",
        )

        val recovered = client.fetchLiveStreams(source, skipForeign = true)
        assertEquals(listOf("xt-err:xtream:1", "xt-err:xtream:2"), recovered.map { it.id })

        streamPayload = """[{"stream_id":10,"name":"BBC Two HD","category_id":"1"},{"stream_id":11,"name":"Truncated"""
        val truncated = client.fetchLiveStreams(source, skipForeign = true)
        assertEquals(listOf("xt-err:xtream:10"), truncated.map { it.id })

        status = HttpStatusCode.InternalServerError
        val failure = assertFailsWith<IllegalStateException> {
            client.fetchLiveStreams(source, skipForeign = true)
        }
        assertEquals("Xtream HTTP 500", failure.message)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun flexStringSerializerSupportsNonJsonDecoderFallback() {
        fun decodeWithScalar(value: String?): String? {
            val decoder = object : AbstractDecoder() {
                override val serializersModule: SerializersModule = EmptySerializersModule()
                override fun decodeElementIndex(descriptor: SerialDescriptor): Int = CompositeDecoder.DECODE_DONE
                override fun decodeNotNullMark(): Boolean = value != null
                override fun decodeNull(): Nothing? = null
                override fun decodeString(): String = value!!
            }
            return FlexStringSerializer.deserialize(decoder)
        }

        assertEquals("abc", decodeWithScalar("abc"))
        assertNull(decodeWithScalar(null))
    }

    @Test
    fun fetchTextAndXtreamClientSendDefaultPlayerUserAgentAndRetryBlockedStatuses() = runTest {
        val m3uUserAgents = mutableListOf<String?>()
        val xtreamUserAgents = mutableListOf<String?>()
        var retryStatuses = listOf(884, 403)

        val http = HttpClient(MockEngine { req ->
            val url = req.url.toString()
            val ua = req.headers[HttpHeaders.UserAgent]
            when {
                url == "https://m3u.test/playlist.m3u" -> {
                    val attemptIdx = m3uUserAgents.size
                    m3uUserAgents += ua
                    if (attemptIdx < retryStatuses.size) {
                        respond("blocked", HttpStatusCode(retryStatuses[attemptIdx], "Blocked"))
                    } else {
                        respond("#EXTM3U\n#EXTINF:-1,BBC One HD\nhttps://cdn.test/bbc1.ts", HttpStatusCode.OK)
                    }
                }
                url == "https://m3u.test/always884.m3u" -> {
                    m3uUserAgents += ua
                    respond("blocked", HttpStatusCode(884, "Blocked"))
                }
                url == "https://xtream.test/player_api.php?username=u&password=p" -> {
                    xtreamUserAgents += ua
                    respond("""{"user_info":{"status":"Active","auth":1,"active_cons":"0","max_connections":"1"}}""", HttpStatusCode.OK, jsonHeaders)
                }
                url.contains("action=get_live_categories") -> {
                    xtreamUserAgents += ua
                    respond("[]", HttpStatusCode.OK, jsonHeaders)
                }
                url.contains("action=get_live_streams") -> {
                    xtreamUserAgents += ua
                    respond("[]", HttpStatusCode.OK, jsonHeaders)
                }
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        })

        val repo = IptvRepository(http)
        val body = repo.fetchText("https://m3u.test/playlist.m3u", userAgent = null)
        assertTrue(body.contains("BBC One HD"))
        assertEquals(listOf<String?>("IPTVSmartersPlayer", "VLC/3.0.20", "TiviMate/4.7.0"), m3uUserAgents)

        m3uUserAgents.clear()
        retryStatuses = listOf(406, 458)
        val blankUaBody = repo.fetchText("https://m3u.test/playlist.m3u", userAgent = "   ")
        assertTrue(blankUaBody.contains("BBC One HD"))
        assertEquals(listOf<String?>("IPTVSmartersPlayer", "VLC/3.0.20", "TiviMate/4.7.0"), m3uUserAgents)

        m3uUserAgents.clear()
        val customFailure = assertFailsWith<IllegalStateException> {
            repo.fetchText("https://m3u.test/always884.m3u", userAgent = "CustomPlayer/1.0")
        }
        assertEquals(IptvRepository.HTTP_884_ERROR_MESSAGE, customFailure.message)
        assertEquals(listOf<String?>("CustomPlayer/1.0"), m3uUserAgents)

        val xtream = XtreamClient(http)
        val xtSource = IptvSource.Xtream(
            id = "xt-default-ua", name = "Xtream", serverUrl = "https://xtream.test",
            username = "u", password = "p", userAgent = "  ",
        )
        xtream.authenticate(xtSource)
        xtream.fetchLiveStreams(xtSource)
        assertEquals(3, xtreamUserAgents.size)
        assertTrue(xtreamUserAgents.all { it == "IPTVSmartersPlayer" })
    }

    @Test
    fun m3uGetPhpUrlFallsBackToXtreamOnHttp884AndUpdatesSourcesForCatchupAndEpg() = runTest {
        val getPhpAgents = mutableListOf<String?>()
        val requestedUrls = mutableListOf<String>()

        val http = HttpClient(MockEngine { req ->
            val url = req.url.toString()
            requestedUrls += url
            when {
                url.startsWith("https://panel.test:8080/portal/get.php") -> {
                    getPhpAgents += req.headers[HttpHeaders.UserAgent]
                    respond("blocked", HttpStatusCode(884, "Blocked"))
                }
                url == "https://panel.test:8080/portal/player_api.php?username=alice&password=p%40ss" ->
                    respond(
                        """{"user_info":{"status":"Active","auth":1,"active_cons":"2","max_connections":"2","exp_date":"1800000000"},"server_info":{"timezone":"Europe/London"}}""",
                        HttpStatusCode.OK, jsonHeaders,
                    )
                url == "https://panel.test:8080/portal/player_api.php?username=alice&password=p%40ss&action=get_live_categories" ->
                    respond("""[{"category_id":"10","category_name":"UK Sports"}]""", HttpStatusCode.OK, jsonHeaders)
                url == "https://panel.test:8080/portal/player_api.php?username=alice&password=p%40ss&action=get_live_streams" ->
                    respond(
                        """[{"stream_id":777,"name":"UK | Sky Sports Main Event FHD","category_id":"10","epg_channel_id":"skysportsmainevent.uk","tv_archive":1,"tv_archive_duration":7}]""",
                        HttpStatusCode.OK, jsonHeaders,
                    )
                url == "https://panel.test:8080/portal/player_api.php?username=alice&password=p%40ss&action=get_short_epg&stream_id=777&limit=6" ->
                    respond(
                        """{"epg_listings":[{"id":"e1","title":"U2t5IFNwb3J0cyBMaXZl","start_timestamp":1795284000,"stop_timestamp":1795287600,"has_archive":1}]}""",
                        HttpStatusCode.OK, jsonHeaders,
                    )
                url == "https://epg.test/provider.xml" ->
                    respond(
                        """<tv><programme start="20261121180000 +0000" stop="20261121190000 +0000" channel="skysportsmainevent.uk"><title>Match Preview</title></programme></tv>""",
                        HttpStatusCode.OK, xmlHeaders,
                    )
                else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders)
            }
        })

        val m3uSource = IptvSource.M3u(
            id = "m3u-xt",
            name = "Provider M3U",
            enabled = true,
            maxConnections = 2,
            userAgent = null,
            epgUrl = "https://epg.test/provider.xml",
            priority = 5,
            url = "https://panel.test:8080/portal/get.php?username=alice&password=p%40ss&type=m3u_plus&output=ts",
        )

        val repo = IptvRepository(http)
        val result = repo.loadSources(listOf(m3uSource))

        assertEquals(listOf<String?>("IPTVSmartersPlayer", "VLC/3.0.20", "TiviMate/4.7.0"), getPhpAgents)
        assertEquals(1, result.reports.size)
        assertTrue(result.reports[0].success)
        assertEquals(1, result.reports[0].streamCount)
        assertEquals("Active", result.reports[0].accountInfo?.status)
        assertEquals(2, repo.accountInfo("m3u-xt")?.maxConnections)

        val derived = repo.currentSources().single() as? IptvSource.Xtream
        assertNotNull(derived)
        assertEquals("m3u-xt", derived.id)
        assertEquals("Provider M3U", derived.name)
        assertEquals(2, derived.maxConnections)
        assertNull(derived.userAgent)
        assertEquals("https://epg.test/provider.xml", derived.epgUrl)
        assertEquals(5, derived.priority)
        assertTrue(derived.enabled)
        assertEquals("https://panel.test:8080/portal", derived.serverUrl)
        assertEquals("alice", derived.username)
        assertEquals("p@ss", derived.password)
        assertEquals("ts", derived.outputFormat)

        val sky = result.channels.single()
        val stream = sky.streams.single()
        assertEquals("https://panel.test:8080/portal/live/alice/p%40ss/777.ts", stream.streamUrl)
        assertEquals("777", stream.xtreamStreamId)

        val startMs = fromUtcFields(2026, 11, 21, 18, 0, 0)
        val endMs = fromUtcFields(2026, 11, 21, 19, 0, 0)
        val catchup = repo.resolveCatchupStream(stream, startMs, endMs, nowMs = endMs + 60_000L)
        assertNotNull(catchup)
        assertEquals("https://panel.test:8080/portal/timeshift/alice/p%40ss/60/2026-11-21:18-00/777.ts", catchup.url)

        val conflictFromOriginal = repo.checkXtreamConnectionConflict(m3uSource, ownSourceActiveRecently = false)
        assertNotNull(conflictFromOriginal)
        assertEquals(2, conflictFromOriginal.activeConnections)
        assertEquals(2, conflictFromOriginal.maxConnections)

        val shortEpg = repo.loadShortEpg(sky)
        assertEquals(1, shortEpg.size)
        assertEquals("Sky Sports Live", shortEpg[0].title)

        val epgBatch = repo.fetchProviderEpgBatch()
        assertEquals(1, epgBatch.totalProgrammes)
        assertTrue("https://epg.test/provider.xml" in requestedUrls)
        assertFalse(requestedUrls.any { it.contains("xmltv.php") })
    }

    @Test
    fun parseXtreamGetPhpSourceHandlesOutputFormatsAndRejectsNonGetPhpUrls() = runTest {
        val hls = IptvRepository.parseXtreamGetPhpSource(
            IptvSource.M3u(
                id = "s1", name = "HLS",
                url = "http://panel.test/get.php?username=u1&password=p1&output=hls",
            )
        )
        assertEquals("m3u8", hls?.outputFormat)
        assertEquals("http://panel.test", hls?.serverUrl)

        val mpegts = IptvRepository.parseXtreamGetPhpSource(
            IptvSource.M3u(
                id = "s2", name = "MPEGTS",
                url = "https://panel.test/sub/get.php?username=u1&password=p1&output=mpegts",
            )
        )
        assertEquals("ts", mpegts?.outputFormat)
        assertEquals("https://panel.test/sub", mpegts?.serverUrl)

        val defaultOut = IptvRepository.parseXtreamGetPhpSource(
            IptvSource.M3u(
                id = "s3", name = "Default",
                url = "http://panel.test:8000/get.php?username=u1&password=p1",
            )
        )
        assertEquals("ts", defaultOut?.outputFormat)

        assertNull(
            IptvRepository.parseXtreamGetPhpSource(
                IptvSource.M3u(id = "bad1", name = "Not get.php", url = "https://panel.test/playlist.m3u?username=u&password=p")
            )
        )
        assertNull(
            IptvRepository.parseXtreamGetPhpSource(
                IptvSource.M3u(id = "bad2", name = "Suffix trap", url = "https://panel.test/notget.php?username=u&password=p")
            )
        )
        assertNull(
            IptvRepository.parseXtreamGetPhpSource(
                IptvSource.M3u(id = "bad3", name = "Host trap", url = "https://get.php?username=u&password=p")
            )
        )
        assertNull(
            IptvRepository.parseXtreamGetPhpSource(
                IptvSource.M3u(id = "bad4", name = "Blank user", url = "https://panel.test/get.php?username=&password=p")
            )
        )
        assertNull(
            IptvRepository.parseXtreamGetPhpSource(
                IptvSource.M3u(id = "bad5", name = "Missing pass", url = "https://panel.test/get.php?username=u")
            )
        )

        for (blockedCode in listOf(403, 406, 458)) {
            val fallbackHttp = HttpClient(MockEngine { req ->
                val url = req.url.toString()
                when {
                    url.startsWith("https://fallback.test/get.php") ->
                        respond("blocked", HttpStatusCode(blockedCode, "Blocked"))
                    url == "https://fallback.test/player_api.php?username=u1&password=p1" ->
                        respond("""{"user_info":{"status":"Active","auth":1,"active_cons":"0","max_connections":"1"}}""", HttpStatusCode.OK, jsonHeaders)
                    url == "https://fallback.test/player_api.php?username=u1&password=p1&action=get_live_categories" ->
                        respond("""[{"category_id":"1","category_name":"UK TV"}]""", HttpStatusCode.OK, jsonHeaders)
                    url == "https://fallback.test/player_api.php?username=u1&password=p1&action=get_live_streams" ->
                        respond("""[{"stream_id":101,"name":"BBC One HD","category_id":"1"}]""", HttpStatusCode.OK, jsonHeaders)
                    else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders)
                }
            })
            val fallbackRepo = IptvRepository(fallbackHttp)
            val m3u = IptvSource.M3u(
                id = "m3u-$blockedCode",
                name = "Fallback $blockedCode",
                url = "https://fallback.test/get.php?username=u1&password=p1&output=hls",
            )
            val res = fallbackRepo.loadSources(listOf(m3u))
            assertTrue(res.reports.single().success)
            val derived = fallbackRepo.currentSources().single() as? IptvSource.Xtream
            assertNotNull(derived)
            assertEquals("m3u8", derived.outputFormat)
            assertEquals("https://fallback.test/live/u1/p1/101.m3u8", res.channels.single().streams.single().streamUrl)
        }
    }

    @Test
    fun nonXtreamM3uHttp884AndFailedXtreamFallbackThrowClearErrorMessage() = runTest {
        val http = HttpClient(MockEngine { req ->
            val url = req.url.toString()
            when {
                url == "https://cdn.test/playlist.m3u" -> respond("blocked", HttpStatusCode(884, "Blocked"))
                url.startsWith("https://deadpanel.test/get.php") -> respond("blocked", HttpStatusCode(884, "Blocked"))
                url.startsWith("https://deadpanel.test/player_api.php") -> respond("blocked", HttpStatusCode(884, "Blocked"))
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        })

        val repo = IptvRepository(http)
        val expectedMsg = "Provider blocked M3U download (HTTP 884) — try adding this source using Xtream Codes login instead"

        val thrown = assertFailsWith<IllegalStateException> {
            repo.fetchText("https://cdn.test/playlist.m3u", userAgent = null)
        }
        assertEquals(expectedMsg, thrown.message)

        val nonXtream = IptvSource.M3u(id = "plain-884", name = "Plain M3U", url = "https://cdn.test/playlist.m3u")
        val failedGetPhp = IptvSource.M3u(
            id = "dead-getphp", name = "Dead Panel",
            url = "https://deadpanel.test/get.php?username=u&password=p&output=ts",
        )
        val load = repo.loadSources(listOf(nonXtream, failedGetPhp))
        assertEquals(2, load.reports.size)
        assertFalse(load.reports[0].success)
        assertEquals(expectedMsg, load.reports[0].error)
        assertFalse(load.reports[1].success)
        assertEquals(expectedMsg, load.reports[1].error)
        assertTrue(repo.currentSources().all { it is IptvSource.M3u })
    }

    @Test
    fun warmSnapshotRejectsV1AndRestoresV2AndExposesIptvCodecJson() = runTest {
        val http = HttpClient(MockEngine { respond("[]", HttpStatusCode.OK, jsonHeaders) })
        val repo = IptvRepository(http)
        val m3u = IptvSource.M3u(
            id = "m3u-snap",
            name = "Snap M3U",
            inlineContent = "#EXTM3U\n#EXTINF:-1 group-title=\"UK| GENERAL ᴴᴰ/ᴿᴬᵂ\",UK: BBC 1 HD ◉\nhttps://live.test/bbc1.ts",
        )
        repo.loadSources(listOf(m3u))
        repo.health.recordSuccess("m3u-snap:m3u:1", nowMs = 5_000L, startLatencyMs = 180L)

        val v2Snapshot = repo.buildWarmSnapshot(nowMs = 9_999L)
        assertEquals(IptvWarmSnapshot.CURRENT_VERSION, v2Snapshot.version)
        assertEquals(2, IptvWarmSnapshot.CURRENT_VERSION)
        assertEquals(1, v2Snapshot.channels.size)

        // Legacy v1 snapshot objects and JSON strings must be rejected without mutating state
        val v1Snapshot = v2Snapshot.copy(version = 1)
        val v1Json = IptvCodec.json.encodeToString(v1Snapshot)
        val coldFromV1Obj = IptvRepository(http)
        assertFalse(coldFromV1Obj.restoreWarmSnapshot(v1Snapshot))
        assertTrue(coldFromV1Obj.currentChannels().isEmpty())

        val coldFromV1Json = IptvRepository(http)
        assertFalse(coldFromV1Json.restoreWarmSnapshot(v1Json))
        assertTrue(coldFromV1Json.currentChannels().isEmpty())

        // Current v2 snapshot restores cleanly from both object and JSON overloads
        val coldFromV2Obj = IptvRepository(http)
        assertTrue(coldFromV2Obj.restoreWarmSnapshot(v2Snapshot))
        assertEquals(1, coldFromV2Obj.currentChannels().size)
        assertEquals("uk.bbc.one", coldFromV2Obj.currentChannels().single().id)
        assertEquals(180L, coldFromV2Obj.health.get("m3u-snap:m3u:1")?.averageStartMs)

        val v2Json = IptvCodec.json.encodeToString(v2Snapshot)
        val coldFromV2Json = IptvRepository(http)
        assertTrue(coldFromV2Json.restoreWarmSnapshot(v2Json))
        assertEquals(1, coldFromV2Json.currentChannels().size)
    }

    @Test
    fun xtreamFetchLiveStreamsDropsNigmaTvBloatAndForeignBouquetsWhileKeepingSuperscriptUk() = runTest {
        val http = HttpClient(MockEngine { req ->
            val url = req.url.toString()
            when {
                url.contains("action=get_live_categories") -> respond(
                    """
                    [
                      {"category_id":"1","category_name":"UK| GENERAL ᴴᴰ/ᴿᴬᵂ"},
                      {"category_id":"2","category_name":"UK| UKRAINE HD/4K"},
                      {"category_id":"3","category_name":"CA| GENERAL FR"},
                      {"category_id":"4","category_name":"CA| GENERAL EN"},
                      {"category_id":"5","category_name":"US| SPORT ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ"},
                      {"category_id":"6","category_name":"US| ESPN+ PPV ⱽᴵᴾ"},
                      {"category_id":"7","category_name":"UK| 24/7 ᴴᴰ/ᴿᴬᵂ"},
                      {"category_id":"8","category_name":"8K| SPORT ON AIR ⁸ᴷ"}
                    ]
                    """.trimIndent(),
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
                url.contains("action=get_live_streams") -> respond(
                    """
                    [
                      {"stream_id":10,"name":"UK: BBC 1 HD ◉","category_id":"1"},
                      {"stream_id":20,"name":"Ukraine Channel","category_id":"2"},
                      {"stream_id":30,"name":"RDS HD","category_id":"3"},
                      {"stream_id":40,"name":"CA: TSN 1 HD","category_id":"4"},
                      {"stream_id":50,"name":"US: ESPN ᴴᴰ","category_id":"5"},
                      {"stream_id":60,"name":"ESPN+ 1","category_id":"6"},
                      {"stream_id":70,"name":"24/7 Comedy","category_id":"7"},
                      {"stream_id":80,"name":"Sport On Air 1","category_id":"8"}
                    ]
                    """.trimIndent(),
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
                else -> respond("[]", HttpStatusCode.OK, jsonHeaders)
            }
        })

        val client = XtreamClient(http)
        val source = IptvSource.Xtream(
            id = "nigma",
            name = "NigmaTV",
            serverUrl = "https://nigma.test",
            username = "u",
            password = "p",
        )

        val filtered = client.fetchLiveStreams(source, skipForeign = true)
        assertEquals(listOf("nigma:xtream:10", "nigma:xtream:40", "nigma:xtream:50"), filtered.map { it.id })

        val unfiltered = client.fetchLiveStreams(source, skipForeign = false)
        assertEquals(8, unfiltered.size)
    }

    @Test
    fun sanitizeSourceErrorRedactsTimeoutsUrlsAndCredentialsWhileKeepingHostContext() = runTest {
        val timeoutErr = IllegalStateException(
            "Connect timeout has expired [url=http://host/player_api.php?username=abc&password=xyz&action=get_live_streams, connect_timeout=6000 ms]"
        )
        val timeoutSanitized = IptvRepository.sanitizeSourceError("Alex", timeoutErr)
        assertEquals("Alex: Connection timed out (check internet or VPN)", timeoutSanitized)
        assertFalse(timeoutSanitized.contains("http://"))
        assertFalse(timeoutSanitized.contains("abc"))
        assertFalse(timeoutSanitized.contains("xyz"))

        assertEquals(
            "Alex: Connection timed out (check internet or VPN)",
            IptvRepository.sanitizeSourceError("Alex", IllegalStateException("Request timeout has expired")),
        )
        assertEquals(
            "Alex: Connection timed out (check internet or VPN)",
            IptvRepository.sanitizeSourceError("Alex", IllegalStateException("SOCKET TIMEOUT reached")),
        )

        val httpWithUrl = IllegalStateException(
            "HTTP 502 from https://panel.test:8080/player_api.php?username=alice&password=secret123&action=get_live_streams"
        )
        val sanitizedUrl = IptvRepository.sanitizeSourceError("Alex", httpWithUrl)
        assertEquals("HTTP 502 from panel.test:8080", sanitizedUrl)
        assertFalse(sanitizedUrl.contains("player_api.php"))
        assertFalse(sanitizedUrl.contains("alice"))
        assertFalse(sanitizedUrl.contains("secret123"))

        val ioWithParams = IllegalStateException(
            "Handshake failed on http://portal.test/portal.php?mac=00:1A:79:AA:BB:CC&token=tok-secret [token=tok-secret, mac=00:1A:79:AA:BB:CC, api_key=k99]"
        )
        val sanitizedParams = IptvRepository.sanitizeSourceError("Stalker", ioWithParams)
        assertEquals("Handshake failed on portal.test [token=***, mac=***, api_key=***]", sanitizedParams)
        assertFalse(sanitizedParams.contains("tok-secret"))
        assertFalse(sanitizedParams.contains("00:1A:79:AA:BB:CC"))
        assertFalse(sanitizedParams.contains("k99"))

        assertEquals("Failed to load Alex", IptvRepository.sanitizeSourceError("Alex", IllegalStateException()))
        assertEquals("Failed to load Alex", IptvRepository.sanitizeSourceError("Alex", IllegalStateException("   ")))

        val timeoutHttp = HttpClient(MockEngine { throw timeoutErr })
        val repo = IptvRepository(timeoutHttp)
        val load = repo.loadSources(
            listOf(IptvSource.M3u(id = "alex", name = "Alex", url = "http://host/get.php?username=abc&password=xyz"))
        )
        assertFalse(load.reports.single().success)
        assertEquals("Alex: Connection timed out (check internet or VPN)", load.reports.single().error)
    }
}
