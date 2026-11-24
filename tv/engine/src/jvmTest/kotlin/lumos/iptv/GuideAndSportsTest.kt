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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lumos.engine.Lumos
import lumos.engine.Session
import lumos.engine.Sync
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuideAndSportsTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun makeStream(
        id: String,
        rawName: String,
        group: String = "UK Sports",
        height: Int = 1080,
        fps: Int? = 50,
        logoUrl: String? = "https://provider.img/$id.png",
        catchupDays: Int = 0,
    ) = StreamSource(
        id = id,
        sourceId = "src-1",
        sourceName = "Main IPTV",
        rawName = rawName,
        groupTitle = group,
        tvgId = null,
        logoUrl = logoUrl,
        streamUrl = "https://stream.example/$id.ts",
        quality = StreamQuality(height = height, fps = fps),
        catchup = if (catchupDays > 0) CatchupConfig(CatchupMode.XTREAM, catchupDays) else null,
    )

    @Test
    fun registryIncludesUsSportsChannelsAndSkySportsPlus() {
        assertEquals("uk.sky.sports.plus", UkChannelRegistry.match("Sky Sports+ Main")?.id)
        assertEquals("us.espn", UkChannelRegistry.match("ESPN", rawGroup = "US Sports")?.id)
        assertEquals("us.espn2", UkChannelRegistry.match("ESPN2")?.id)
        assertEquals("us.fs1", UkChannelRegistry.match("Fox Sports 1")?.id)
        assertEquals("us.tnt", UkChannelRegistry.match("TNT US", rawGroup = "US")?.id)
        assertEquals("us.cbssn", UkChannelRegistry.match("CBSSN")?.id)
        assertEquals("us.cbssn", UkChannelRegistry.match("CBS Sports Network")?.id)
        assertEquals("us.usa.network", UkChannelRegistry.match("USA Network")?.id)
        assertEquals("us.nfl.network", UkChannelRegistry.match("NFL Network")?.id)
        assertEquals("us.nba.tv", UkChannelRegistry.match("NBA TV")?.id)
        assertEquals("us.nhl.network", UkChannelRegistry.match("NHL Network")?.id)
        assertEquals("us.mlb.network", UkChannelRegistry.match("MLB Network")?.id)

        val usGuide = UkChannelRegistry.curatedUsSportsGuideChannels()
        assertEquals(9, usGuide.size)
        assertTrue(usGuide.all { it.sportsGuide && !it.mainGuide && it.id.startsWith("us.") })
    }

    @Test
    fun profilePrefsAndEpgSnapshotBackwardsCompatibleSerialization() {
        val legacyPrefsJson = """{"favourites":["uk.bbc.one"],"showNonUk":true}"""
        val decodedLegacy = IptvCodec.decodePrefs(legacyPrefsJson)
        assertTrue(decodedLegacy.showNonUk)
        assertTrue(decodedLegacy.favouriteTeams.isEmpty())
        assertFalse(decodedLegacy.hideSportsScores)

        val team = SavedSportsTeam(
            teamId = "359",
            sport = "Football",
            league = "eng.1",
            leagueName = "Premier League",
            name = "Arsenal",
            shortName = "Arsenal",
            logoUrl = "https://img/arsenal.png",
        )
        val modernPrefs = IptvProfilePrefs(
            favourites = listOf("uk.sky.sports.pl"),
            favouriteTeams = listOf(team),
            hideSportsScores = true,
        )
        val roundTrippedPrefs = IptvCodec.decodePrefs(IptvCodec.encodePrefs(modernPrefs))
        assertEquals(modernPrefs, roundTrippedPrefs)

        val legacySnapshotJson = """{"version":1,"updatedAtMs":500,"builtIn":[],"provider":[],"aliases":{}}"""
        val decodedLegacySnap = IptvCodec.decodeEpgCache(legacySnapshotJson)
        assertNotNull(decodedLegacySnap)
        assertTrue(decodedLegacySnap.guideChannels.isEmpty())

        val guide = GuideChannel(
            id = "uk.bbc.one",
            name = "BBC One",
            category = "Entertainment",
            number = 101,
            logoUrl = "https://cdn.sky/bbc1.png",
            mainGuide = true,
            sportsGuide = false,
        )
        val modernSnap = EpgCacheSnapshot(updatedAtMs = 999L, guideChannels = listOf(guide))
        assertEquals(modernSnap, IptvCodec.decodeEpgCache(IptvCodec.encodeEpgCache(modernSnap)))
    }

    @Test
    fun repositoryParsesGuideChannelsAndEpgStoreBinaryRangeQueries() {
        val http = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK, jsonHeaders) })
        val repo = IptvRepository(http)

        val epgPayload = """
            {
              "updatedAt": 1700000000000,
              "channels": [
                {"id": "uk.sky.sports.pl", "name": "Sky Sports Premier League", "category": "Sports", "number": 302, "logo": "https://sky.cdn/4010.png", "mainGuide": true, "sportsGuide": true},
                {"id": "uk.bbc.one", "name": "BBC One", "category": "Entertainment", "number": 101, "logo": "https://sky.cdn/3000.png"}
              ],
              "programmes": [
                {"channelId": "uk.sky.sports.pl", "startMs": 1000, "endMs": 2000, "title": "Super Sunday: Arsenal v Chelsea", "isSport": true, "sportTag": "Football"},
                {"channelId": "uk.bbc.one", "startMs": 1000, "endMs": 2000, "title": "BBC News"}
              ]
            }
        """.trimIndent()

        val count = repo.loadUkEpgJson(epgPayload)
        assertEquals(2, count)
        val guide = repo.epg.guideChannels()
        assertEquals(2, guide.size)
        assertEquals("https://sky.cdn/4010.png", guide[0].logoUrl)
        assertTrue(guide[0].mainGuide && guide[0].sportsGuide)
        // Legacy fallback when mainGuide/sportsGuide absent
        assertTrue(guide[1].mainGuide && !guide[1].sportsGuide)

        val plChan = CanonicalChannel(
            id = "uk.sky.sports.pl",
            number = 302,
            name = "Sky Sports Premier League",
            category = ChannelCategory.SPORTS,
            logoUrl = null,
            epgId = "uk.sky.sports.pl",
            streams = emptyList(),
        )
        assertTrue(repo.epg.nowAndNext(plChan, 1500L).first?.isLiveSport == true)

        // Binary-search programmesInRange across large non-overlapping & overlapping lists
        val store = EpgStore()
        val base = 1_000_000L
        val slot = 1000L
        val progs = (0 until 500).map { i ->
            EpgProgramme(
                id = "p-$i",
                channelId = "uk.bbc.one",
                title = "Show $i",
                startMs = base + i * slot,
                endMs = base + (i + 1) * slot,
            )
        }
        store.putBuiltInUkEpg(progs)

        val exactRange = store.programmesInRange("uk.bbc.one", base + 10 * slot, base + 13 * slot)
        assertEquals(listOf("Show 10", "Show 11", "Show 12"), exactRange.map { it.title })

        val partialBoundary = store.programmesInRange("uk.bbc.one", base + 10 * slot + 500L, base + 12 * slot + 1L)
        assertEquals(listOf("Show 10", "Show 11", "Show 12"), partialBoundary.map { it.title })

        assertTrue(store.programmesInRange("uk.bbc.one", base + 20 * slot, base + 20 * slot).isEmpty())

        // Overlapping schedule branch
        store.putBuiltInUkEpg(
            listOf(
                EpgProgramme("ov-long", "ov.uk", "Long Block", startMs = 0L, endMs = 400L),
                EpgProgramme("ov-short", "ov.uk", "Short Slot", startMs = 100L, endMs = 200L),
                EpgProgramme("ov-after", "ov.uk", "After Slot", startMs = 500L, endMs = 600L),
            )
        )
        val ovRange = store.programmesInRange("ov.uk", 250L, 450L)
        assertEquals(listOf("Long Block"), ovRange.map { it.title })
    }

    @Test
    fun guideStreamLinkerBuildsMainGuideAndSportsLiveWithPreferredLogosAndBadges() {
        val guideChannels = listOf(
            GuideChannel("uk.sky.sports.pl", "Sky Sports Premier League", "Sports", 302, "https://sky.cdn/4010.png", mainGuide = true, sportsGuide = true),
            GuideChannel("uk.bbc.one", "BBC One", "Entertainment", 101, "https://sky.cdn/3000.png", mainGuide = true, sportsGuide = false),
            GuideChannel("uk.sky.cinema.premiere", "Sky Cinema Premiere", "Movies", 401, "", mainGuide = true, sportsGuide = false),
            GuideChannel("ie.rte.one", "RTÉ One", "Entertainment", 901, "https://sky.cdn/rte.png", mainGuide = false, sportsGuide = false),
        )

        val canonical = listOf(
            CanonicalChannel(
                id = "uk.bbc.one",
                number = 101,
                name = "BBC One",
                category = ChannelCategory.TERRESTRIAL,
                logoUrl = "https://provider.img/bbc1.png",
                epgId = "bbc1.uk",
                streams = listOf(
                    makeStream("bbc-hd", "BBC One HD", height = 720, fps = 25, catchupDays = 7),
                    makeStream("bbc-fhd", "BBC One FHD", height = 1080, fps = 25, catchupDays = 7),
                ),
                hasCatchup = true,
                maxCatchupDays = 7,
            ),
            CanonicalChannel(
                id = "uk.sky.sports.pl",
                number = 302,
                name = "Sky Sports Premier League",
                category = ChannelCategory.SPORTS,
                logoUrl = "https://provider.img/sspl.png",
                epgId = "skysportspl.uk",
                streams = listOf(
                    makeStream("pl-fhd25", "Sky Sports PL FHD", height = 1080, fps = 25),
                    makeStream("pl-hd50", "Sky Sports PL HD 50fps", height = 720, fps = 50),
                ),
            ),
            CanonicalChannel(
                id = "us.espn",
                number = 955,
                name = "ESPN",
                category = ChannelCategory.US_ENGLISH,
                logoUrl = "https://provider.img/espn.png",
                epgId = "espn.us",
                streams = listOf(makeStream("espn-1", "US | ESPN FHD 60fps", group = "US Sports", height = 1080, fps = 60)),
            ),
        )

        val mainRows = GuideStreamLinker.mainGuide(guideChannels, canonical)
        assertEquals(listOf("uk.bbc.one", "uk.sky.sports.pl", "uk.sky.cinema.premiere"), mainRows.map { it.guideChannel.id })
        assertEquals("https://sky.cdn/3000.png", mainRows[0].logoUrl)
        assertTrue(mainRows[0].hasCatchup)
        assertTrue(mainRows[0].isPlayable)
        assertEquals("1080p · 25fps", mainRows[0].qualityBadge)
        assertEquals("bbc-fhd", mainRows[0].bestStream?.id)
        assertFalse(mainRows[2].isPlayable)
        assertNull(mainRows[2].bestStream)

        val sportsRows = GuideStreamLinker.sportsLive(guideChannels, canonical)
        assertEquals("uk.sky.sports.pl", sportsRows[0].guideChannel.id)
        assertEquals("pl-hd50", sportsRows[0].bestStream?.id) // 50fps boosted for sports
        assertEquals("HD · 50fps", sportsRows[0].qualityBadge)
        val espnRow = sportsRows.first { it.guideChannel.id == "us.espn" }
        assertTrue(espnRow.isPlayable)
        assertEquals("https://provider.img/espn.png", espnRow.logoUrl)
        assertEquals("1080p · 60fps", espnRow.qualityBadge)
    }

    @Test
    fun sportsStreamResolverOrdersUkBroadcasterUsBroadcasterAndDirectEvent() {
        val fixture = SportsFixture(
            id = "701001",
            sport = "Football",
            league = "eng.1",
            leagueName = "Premier League",
            name = "Arsenal vs Chelsea",
            startMs = 1_000_000L,
            status = FixtureStatus.LIVE,
            home = SportsTeamSide(id = "359", name = "Arsenal", shortName = "Arsenal", abbreviation = "ARS"),
            away = SportsTeamSide(id = "363", name = "Chelsea", shortName = "Chelsea", abbreviation = "CHE"),
            broadcasts = listOf("NBC", "Peacock", "Sky Sports Main Event", "USA Network"),
            ukChannelId = "uk.sky.sports.pl",
            ukChannelName = "Sky Sports Premier League",
        )

        val channels = listOf(
            CanonicalChannel(
                id = "event.epl.01",
                number = null,
                name = "EPL 01: Arsenal vs Chelsea",
                category = ChannelCategory.EVENTS,
                logoUrl = null,
                epgId = null,
                streams = listOf(makeStream("ev-1", "EPL 01: Arsenal vs Chelsea", group = "Live Events", height = 1080, fps = 50)),
                isEventChannel = true,
            ),
            CanonicalChannel(
                id = "us.nbc",
                number = 953,
                name = "NBC",
                category = ChannelCategory.US_ENGLISH,
                logoUrl = null,
                epgId = "nbc.us",
                streams = listOf(makeStream("nbc-1", "US | NBC FHD 60fps", group = "US", height = 1080, fps = 60)),
            ),
            CanonicalChannel(
                id = "us.usa.network",
                number = 972,
                name = "USA Network",
                category = ChannelCategory.US_ENGLISH,
                logoUrl = null,
                epgId = "usanetwork.us",
                streams = listOf(makeStream("usa-1", "US | USA Network HD", group = "US", height = 720, fps = 60)),
            ),
            CanonicalChannel(
                id = "uk.sky.sports.main",
                number = 301,
                name = "Sky Sports Main Event",
                category = ChannelCategory.SPORTS,
                logoUrl = null,
                epgId = "skysportsmainevent.uk",
                streams = listOf(makeStream("ssme-1", "UK | Sky Sports Main Event FHD", height = 1080, fps = 50)),
            ),
            CanonicalChannel(
                id = "uk.sky.sports.pl",
                number = 302,
                name = "Sky Sports Premier League",
                category = ChannelCategory.SPORTS,
                logoUrl = null,
                epgId = "skysportspl.uk",
                streams = listOf(makeStream("sspl-1", "UK | Sky Sports Premier League FHD", height = 1080, fps = 50)),
            ),
        )

        val resolved = SportsStreamResolver.resolve(fixture, channels)
        assertEquals(
            listOf("uk.sky.sports.pl", "uk.sky.sports.main", "us.nbc", "us.usa.network", "event.epl.01"),
            resolved.map { it.channel.id },
        )
        assertEquals(
            listOf(
                MatchReason.UK_BROADCASTER,
                MatchReason.UK_BROADCASTER,
                MatchReason.US_BROADCASTER,
                MatchReason.US_BROADCASTER,
                MatchReason.DIRECT_EVENT,
            ),
            resolved.map { it.reason },
        )

        // Direct event alias matching ("Man Utd v Spurs") without broadcasters
        val aliasFixture = SportsFixture(
            id = "701002",
            sport = "Football",
            league = "eng.1",
            leagueName = "Premier League",
            name = "Tottenham Hotspur at Manchester United",
            startMs = 2_000_000L,
            status = FixtureStatus.UPCOMING,
            home = SportsTeamSide(id = "360", name = "Manchester United", shortName = "Man Utd"),
            away = SportsTeamSide(id = "367", name = "Tottenham Hotspur", shortName = "Tottenham"),
        )
        val ppvChan = CanonicalChannel(
            id = "event.ppv.2",
            number = null,
            name = "PPV 02: Man Utd v Spurs",
            category = ChannelCategory.EVENTS,
            logoUrl = null,
            epgId = null,
            streams = listOf(makeStream("ppv-2", "PPV 02: Man Utd v Spurs", group = "PPV", height = 1080, fps = 50)),
            isEventChannel = true,
        )
        val aliasResolved = SportsStreamResolver.resolve(aliasFixture, listOf(ppvChan))
        assertEquals(1, aliasResolved.size)
        assertEquals(MatchReason.DIRECT_EVENT, aliasResolved[0].reason)
    }

    @Test
    fun sportsStreamResolverBatchMatchesSingleResolveResults() {
        val fixtures = listOf(
            SportsFixture(
                id = "fx-1",
                sport = "Football",
                league = "eng.1",
                leagueName = "Premier League",
                name = "Arsenal vs Chelsea",
                startMs = 1_000_000L,
                status = FixtureStatus.LIVE,
                home = SportsTeamSide(id = "359", name = "Arsenal", shortName = "Arsenal"),
                away = SportsTeamSide(id = "363", name = "Chelsea", shortName = "Chelsea"),
                broadcasts = listOf("NBC", "Sky Sports Main Event"),
                ukChannelId = "uk.sky.sports.pl",
                ukChannelName = "Sky Sports Premier League",
            ),
            SportsFixture(
                id = "fx-2",
                sport = "UFC",
                league = "ufc",
                leagueName = "UFC",
                name = "UFC 300: Pereira vs Hill",
                startMs = 2_000_000L,
                status = FixtureStatus.UPCOMING,
            ),
            SportsFixture(
                id = "fx-3",
                sport = "Formula 1",
                league = "f1",
                leagueName = "Formula 1",
                name = "Formula 1 Monaco Grand Prix",
                startMs = 3_000_000L,
                status = FixtureStatus.UPCOMING,
                broadcasts = listOf("Sky Sports F1"),
            ),
            SportsFixture(
                id = "fx-4",
                sport = "Tennis",
                league = "atp",
                leagueName = "Wimbledon",
                name = "Wimbledon Final",
                startMs = 4_000_000L,
                status = FixtureStatus.UPCOMING,
            ),
            SportsFixture(
                id = "fx-5",
                sport = "Golf",
                league = "pga",
                leagueName = "PGA Tour",
                name = "PGA Tour",
                startMs = 5_000_000L,
                status = FixtureStatus.UPCOMING,
            ),
        )

        val channels = listOf(
            CanonicalChannel(
                id = "uk.sky.sports.pl",
                number = 302,
                name = "Sky Sports Premier League",
                category = ChannelCategory.SPORTS,
                logoUrl = null,
                epgId = null,
                streams = listOf(makeStream("sspl-1", "UK | Sky Sports Premier League FHD")),
            ),
            CanonicalChannel(
                id = "uk.sky.sports.main",
                number = 301,
                name = "Sky Sports Main Event",
                category = ChannelCategory.SPORTS,
                logoUrl = null,
                epgId = null,
                streams = listOf(makeStream("ssme-1", "UK | Sky Sports Main Event FHD")),
            ),
            CanonicalChannel(
                id = "uk.sky.sports.f1",
                number = 306,
                name = "Sky Sports F1",
                category = ChannelCategory.SPORTS,
                logoUrl = null,
                epgId = null,
                streams = listOf(makeStream("ssf1-1", "UK | Sky Sports F1 FHD")),
            ),
            CanonicalChannel(
                id = "us.nbc",
                number = 953,
                name = "NBC",
                category = ChannelCategory.US_ENGLISH,
                logoUrl = null,
                epgId = null,
                streams = listOf(makeStream("nbc-1", "US | NBC FHD")),
            ),
            CanonicalChannel(
                id = "event.epl.01",
                number = null,
                name = "EPL 01: Arsenal vs Chelsea",
                category = ChannelCategory.EVENTS,
                logoUrl = null,
                epgId = null,
                streams = listOf(makeStream("ev-1", "EPL 01: Arsenal vs Chelsea", group = "Live Events")),
                isEventChannel = true,
            ),
            CanonicalChannel(
                id = "event.ufc.300",
                number = null,
                name = "PPV 01: UFC 300 Main Card",
                category = ChannelCategory.EVENTS,
                logoUrl = null,
                epgId = null,
                streams = listOf(makeStream("ev-ufc", "PPV 01: UFC 300 Main Card", group = "PPV")),
                isEventChannel = true,
            ),
            CanonicalChannel(
                id = "event.f1.monaco",
                number = null,
                name = "Event 05: F1 Monaco GP",
                category = ChannelCategory.EVENTS,
                logoUrl = null,
                epgId = null,
                streams = listOf(makeStream("ev-f1", "Event 05: F1 Monaco GP", group = "Live Events")),
                isEventChannel = true,
            ),
            CanonicalChannel(
                id = "event.tennis.01",
                number = null,
                name = "Event 08: Wimbledon Centre Court",
                category = ChannelCategory.EVENTS,
                logoUrl = null,
                epgId = null,
                streams = listOf(makeStream("ev-wim", "Event 08: Wimbledon Centre Court", group = "Live Events")),
                isEventChannel = true,
            ),
        )

        val batch = SportsStreamResolver.resolveBatch(fixtures, channels)
        val expected = fixtures.associate { f -> f.id to SportsStreamResolver.resolve(f, channels) }
        assertEquals(expected, batch)
        assertEquals(listOf("uk.sky.sports.pl", "uk.sky.sports.main", "us.nbc", "event.epl.01"), batch["fx-1"]?.map { it.channel.id })
        assertEquals(listOf("event.ufc.300"), batch["fx-2"]?.map { it.channel.id })
        assertEquals(listOf("uk.sky.sports.f1", "event.f1.monaco"), batch["fx-3"]?.map { it.channel.id })
        assertEquals(listOf("event.tennis.01"), batch["fx-4"]?.map { it.channel.id })
        assertTrue(batch["fx-5"].orEmpty().isEmpty())
    }

    @Test
    fun syncAndLumosSportsAndEnrichmentEndpointsRoundTrip() = runTest {
        val requests = mutableListOf<Pair<String, String>>()
        val mockHttp = HttpClient(MockEngine { req ->
            val path = req.url.encodedPath
            val bodyText = (req.body as? TextContent)?.text.orEmpty()
            requests.add(path to bodyText)
            when (path) {
                "/iptv/sports/fixtures" -> respond(
                    """{"updatedAt":1700,"fixtures":[{"id":"701001","sport":"Football","league":"eng.1","leagueName":"Premier League","name":"Arsenal vs Chelsea","startMs":1000,"status":"LIVE","clock":"62'","broadcasts":["NBC"],"ukChannelId":"uk.sky.sports.pl","ukChannelName":"Sky Sports Premier League"}]}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
                "/iptv/sports/teams/search" -> respond(
                    """{"teams":[{"teamId":"359","sport":"Football","league":"eng.1","leagueName":"Premier League","name":"Arsenal","shortName":"Arsenal","logoUrl":"https://img/ars.png"}]}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
                "/iptv/sports/teams/schedule" -> respond(
                    """{"updatedAt":1700,"schedules":[{"team":{"teamId":"359","sport":"Football","league":"eng.1","leagueName":"Premier League","name":"Arsenal","shortName":"Arsenal"},"live":[],"past":[],"upcoming":[{"id":"701003","sport":"Football","league":"eng.1","leagueName":"Premier League","name":"Arsenal vs Liverpool","startMs":5000,"status":"UPCOMING","broadcasts":["Sky Sports"]}]}]}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
                "/epg/enrich" -> respond(
                    """{"backdrop":"https://image.tmdb.org/t/p/w1280/abc.jpg","description":"A drama.","tmdbId":42,"mediaType":"tv"}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
                else -> respond("""{"error":"not found"}""", HttpStatusCode.NotFound, jsonHeaders)
            }
        })

        val sync = Sync("https://sync.example", mockHttp, Session("tok-sports", "acc-1"))
        val fixtures = sync.sportsFixtures(sport = "Football", date = "2026-11-22")
        assertEquals(1, fixtures.size)
        assertEquals(FixtureStatus.LIVE, fixtures[0].status)
        assertEquals("uk.sky.sports.pl", fixtures[0].ukChannelId)

        val teams = sync.searchSportsTeams("ars")
        assertEquals(1, teams.size)
        assertEquals("Arsenal", teams[0].name)

        // Blank search and empty teams list short-circuit without HTTP calls
        assertTrue(sync.searchSportsTeams("   ").isEmpty())
        assertTrue(sync.savedTeamsSchedule(emptyList()).isEmpty())

        val schedules = sync.savedTeamsSchedule(teams)
        assertEquals(1, schedules.size)
        assertEquals("Arsenal vs Liverpool", schedules[0].upcoming[0].name)
        val schedReqObj = Json.parseToJsonElement(requests.last().second).jsonObject
        assertEquals("359", schedReqObj["teams"]?.jsonArray?.get(0)?.jsonObject?.get("teamId")?.jsonPrimitive?.content)

        val enriched = sync.enrichEpgProgramme("Doctor Who", channelId = "uk.bbc.one", category = "Entertainment")
        assertEquals("https://image.tmdb.org/t/p/w1280/abc.jpg", enriched.backdrop)
        assertEquals("A drama.", enriched.description)
        assertEquals(42, enriched.tmdbId)

        // Verify Lumos facade delegates cleanly
        val lumos = Lumos("https://sync.example").apply { useSession("tok", "acc") }
        assertTrue(lumos.searchSportsTeams("").isEmpty())
        assertTrue(lumos.savedTeamsSchedule(emptyList()).isEmpty())
    }

    @Test
    fun checkXtreamConnectionConflictHandlesSlotsGracePeriodAndFailures() = runTest {
        var activeCons = "1"
        var maxCons = "1"
        var statusCode = HttpStatusCode.OK
        var xtreamHits = 0

        val mockHttp = HttpClient(MockEngine { _ ->
            xtreamHits++
            if (statusCode != HttpStatusCode.OK) {
                respond("server error", statusCode, jsonHeaders)
            } else {
                respond(
                    """{"user_info":{"auth":1,"status":"Active","active_cons":"$activeCons","max_connections":"$maxCons"},"server_info":{}}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            }
        })

        val repo = IptvRepository(mockHttp)
        val xtream = IptvSource.Xtream(
            id = "xt-1",
            name = "Main Xtream",
            serverUrl = "https://xt.example",
            username = "u",
            password = "p",
        )
        val m3u = IptvSource.M3u(id = "m3u-1", name = "Backup M3U", url = "https://m3u.example/list.m3u")
        val stalker = IptvSource.Stalker(id = "stk-1", name = "Portal", portalUrl = "https://portal.example/c", macAddress = "00:1A:79:00:00:01")

        // Ignores M3U and Stalker without making HTTP calls
        assertNull(repo.checkXtreamConnectionConflict(m3u, ownSourceActiveRecently = false))
        assertNull(repo.checkXtreamConnectionConflict(stalker, ownSourceActiveRecently = false))
        assertEquals(0, xtreamHits)

        // 1/1 when idle flags conflict
        val idleConflict = repo.checkXtreamConnectionConflict(xtream, ownSourceActiveRecently = false)
        assertNotNull(idleConflict)
        assertEquals("xt-1", idleConflict.sourceId)
        assertEquals("Main Xtream", idleConflict.sourceName)
        assertEquals(1, idleConflict.activeConnections)
        assertEquals(1, idleConflict.maxConnections)
        assertTrue(idleConflict.reason.contains("1/1"))

        // 1/1 when ownSourceActiveRecently=true suppresses false alarm
        assertNull(repo.checkXtreamConnectionConflict(xtream, ownSourceActiveRecently = true))

        // 2/1 flags conflict even when ownSourceActiveRecently=true
        activeCons = "2"
        val twoOfOneConflict = repo.checkXtreamConnectionConflict(xtream, ownSourceActiveRecently = true)
        assertNotNull(twoOfOneConflict)
        assertEquals(2, twoOfOneConflict.activeConnections)
        assertEquals(1, twoOfOneConflict.maxConnections)

        // Fails soft on HTTP 500
        statusCode = HttpStatusCode.InternalServerError
        assertNull(repo.checkXtreamConnectionConflict(xtream, ownSourceActiveRecently = false))
    }

    @Test
    fun iptvPresenceRoundTripAndDecodePresenceOthers() = runTest {
        val requests = mutableListOf<Pair<String, String>>()
        var failNext = false
        val mockHttp = HttpClient(MockEngine { req ->
            if (failNext) {
                return@MockEngine respond("""{"error":"offline"}""", HttpStatusCode.ServiceUnavailable, jsonHeaders)
            }
            val bodyText = (req.body as? TextContent)?.text.orEmpty()
            requests.add(req.url.encodedPath to bodyText)
            val reqObj = Json.parseToJsonElement(bodyText).jsonObject
            when (reqObj["action"]?.jsonPrimitive?.content) {
                "start" -> respond("""{"ok":true,"others":[]}""", HttpStatusCode.OK, jsonHeaders)
                "check" -> respond(
                    """{"others":[{"deviceName":"Living Room TV","channelName":"Sky Sports PL","at":1700000000000}]}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
                "stop" -> respond("""{"ok":true}""", HttpStatusCode.OK, jsonHeaders)
                else -> respond("""{"error":"bad action"}""", HttpStatusCode.BadRequest, jsonHeaders)
            }
        })

        val sync = Sync("https://sync.example", mockHttp, Session("tok-pres", "acc-1"))
        val startJson = sync.iptvPresence("start", "xt-1", "BBC One")
        assertNotNull(startJson)
        assertTrue(IptvCodec.decodePresenceOthers(startJson).isEmpty())

        val checkJson = sync.iptvPresence("check", "xt-1")
        assertNotNull(checkJson)
        val others = IptvCodec.decodePresenceOthers(checkJson)
        assertEquals(1, others.size)
        assertEquals("Living Room TV", others[0].deviceName)
        assertEquals("Sky Sports PL", others[0].channelName)
        assertEquals(1700000000000L, others[0].at)

        val stopJson = sync.iptvPresence("stop", "xt-1")
        assertNotNull(stopJson)
        assertEquals(3, requests.size)
        assertTrue(requests.all { it.first == "/iptv/presence" })

        // Fails soft when server errors or unsigned
        failNext = true
        assertNull(sync.iptvPresence("heartbeat", "xt-1", "BBC One"))
        val unsignedLumos = Lumos("https://sync.example")
        assertNull(unsignedLumos.iptvPresence("check", "xt-1"))

        // decodePresenceOthers fails soft on blank/malformed JSON
        assertTrue(IptvCodec.decodePresenceOthers("").isEmpty())
        assertTrue(IptvCodec.decodePresenceOthers("not json {{{").isEmpty())
        assertTrue(IptvCodec.decodePresenceOthers("""{"others":"invalid"}""").isEmpty())
    }
}
