package lumos.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChannelNormalizerTest {
    private val xtreamSrc = IptvSource.Xtream(
        id = "src-xtream",
        name = "Xtream Prime",
        priority = 10,
        serverUrl = "https://xtream.test",
        username = "u",
        password = "p",
    )
    private val m3uSrc = IptvSource.M3u(
        id = "src-m3u",
        name = "Backup M3U",
        priority = 1,
        url = "https://m3u.test/list.m3u",
    )

    private fun rawStream(
        id: String,
        source: IptvSource = xtreamSrc,
        rawName: String,
        group: String = "UK TV",
        tvgId: String? = null,
        url: String = "https://stream.test/$id.ts",
        catchup: CatchupConfig? = null,
    ) = StreamSource(
        id = id,
        sourceId = source.id,
        sourceName = source.name,
        rawName = rawName,
        groupTitle = group,
        tvgId = tvgId,
        logoUrl = "https://logo.test/$id.png",
        streamUrl = url,
        quality = ChannelNormalizer.parseQuality(rawName),
        catchup = catchup,
    )

    @Test
    fun parsesQualityAndComputesBadges() {
        val q4k = ChannelNormalizer.parseQuality("UK | TNT Sports Ultimate 4K UHD 50FPS HDR HEVC RAW")
        assertEquals(2160, q4k.height)
        assertEquals(50, q4k.fps)
        assertEquals("HEVC", q4k.codec)
        assertTrue(q4k.hdr)
        assertTrue(q4k.isRaw)
        assertFalse(q4k.isBackup)
        assertEquals("UK", q4k.region)
        assertEquals("4K · 50fps · HDR", q4k.badge)

        val q1080 = ChannelNormalizer.parseQuality("Sky Sports Main Event FHD 50FPS")
        assertEquals(1080, q1080.height)
        assertEquals(50, q1080.fps)
        assertEquals("1080p · 50fps", q1080.badge)

        val qHd = ChannelNormalizer.parseQuality("BBC Two HD (Backup) [H.264]")
        assertEquals(720, qHd.height)
        assertEquals("H.264", qHd.codec)
        assertTrue(qHd.isBackup)
        assertEquals("HD", qHd.badge)

        val qSd = ChannelNormalizer.parseQuality("Channel 4 SD")
        assertEquals(576, qSd.height)
        assertEquals("SD", qSd.badge)
    }

    @Test
    fun cleansChannelTitlesPreservingPlusOneAndChannelNumbers() {
        assertEquals("BBC One", ChannelNormalizer.cleanChannelTitle("UK | RAW | BBC One FHD 50FPS"))
        assertEquals("ITV1 +1", ChannelNormalizer.cleanChannelTitle("UK: ITV1 +1 HD"))
        assertEquals("ITV1 +1", ChannelNormalizer.cleanChannelTitle("ITV1 HD +1"))
        assertEquals("Channel 4", ChannelNormalizer.cleanChannelTitle("[UK] Channel 4 (1080p) [HEVC]"))
        assertEquals("Sky Sports+", ChannelNormalizer.cleanChannelTitle("UK | Sky Sports+ 4K"))
        assertEquals("Crime + Investigation", ChannelNormalizer.cleanChannelTitle("Crime + Investigation HD"))
        assertEquals("NOW 80s", ChannelNormalizer.cleanChannelTitle("UK: NOW 80s SD"))
        assertTrue(ChannelNormalizer.cleanChannelTitle("UK | HD").isNotBlank())
    }

    @Test
    fun detectsEventChannelsWithoutFalsePositives() {
        assertTrue(ChannelNormalizer.detectEventChannel("EPL 01: Arsenal vs Chelsea", "UK Sports"))
        assertTrue(ChannelNormalizer.detectEventChannel("UFC 310 PPV Main Event", "Live Events"))
        assertTrue(ChannelNormalizer.detectEventChannel("Club Stream 4", "iFollow"))
        assertFalse(ChannelNormalizer.detectEventChannel("Sky Sports Premier League FHD", "UK Sports"))
        assertFalse(ChannelNormalizer.detectEventChannel("BBC One HD", "UK TV"))
    }

    @Test
    fun registryMatchesCanonicalIdsAndAliasesAndPlusOne() {
        assertTrue(UkChannelRegistry.entries.size >= 170)
        assertEquals(UkChannelRegistry.entries.size, UkChannelRegistry.entries.map { it.id }.toSet().size)

        assertEquals("uk.bbc.one", UkChannelRegistry.match("BBC 1")?.id)
        assertEquals("uk.bbc.one", UkChannelRegistry.match("BBC One London")?.id)
        assertEquals("uk.bbc.scotland", UkChannelRegistry.match("BBC Scotland")?.id)
        assertEquals("uk.tnt.sports.1", UkChannelRegistry.match("BT Sport 1")?.id)
        assertEquals("uk.sky.sports.pl", UkChannelRegistry.match("Sky Sports PL")?.id)
        assertEquals("uk.sky.sports.plus", UkChannelRegistry.match("Sky Sports+")?.id)
        assertEquals("uk.ch4", UkChannelRegistry.match("Ch 4")?.id)
        assertEquals("uk.dave", UkChannelRegistry.match("U&Dave")?.id)
        assertEquals("uk.dave", UkChannelRegistry.match("Dave")?.id)
        assertEquals("uk.drama", UkChannelRegistry.match("U&Drama")?.id)
        assertEquals("uk.sky.showcase", UkChannelRegistry.match("Sky One")?.id)
        assertEquals("uk.premier.sports.1", UkChannelRegistry.match("Viaplay Sports 1")?.id)
        assertEquals("ie.rte.one", UkChannelRegistry.match("RTÉ One")?.id)

        assertEquals("uk.itv.one", UkChannelRegistry.match("ITV1")?.id)
        assertEquals("uk.itv.one.plus1", UkChannelRegistry.match("ITV1 +1")?.id)
        assertEquals("uk.ch4.plus1", UkChannelRegistry.match("Channel 4 +1")?.id)
        assertEquals("uk.dave.plus1", UkChannelRegistry.match("Dave +1")?.id)
        assertEquals("uk.e4.extra", UkChannelRegistry.match("E4 Extra")?.id)

        assertEquals("us.cnn", UkChannelRegistry.match("CNN", tvgId = "cnn.us", rawGroup = "USA News")?.id)
        assertEquals("uk.cnn.uk", UkChannelRegistry.match("CNN International", rawGroup = "UK News")?.id)
        assertNotNull(UkChannelRegistry.findById("uk.sky.sports.main"))

        val requiredIds = listOf(
            "uk.bbc.one", "uk.bbc.two", "uk.itv.one", "uk.ch4", "uk.ch5", "uk.itv.two", "uk.bbc.three",
            "uk.bbc.four", "uk.itv.three", "uk.itv.four", "uk.e4", "uk.more4", "uk.film4", "uk.itvbe",
            "uk.5star", "uk.5usa", "uk.5action", "uk.5select", "uk.dave", "uk.drama", "uk.yesterday",
            "uk.w", "uk.alibi", "uk.gold", "uk.eden", "uk.bbc.scotland", "uk.stv", "uk.utv", "uk.s4c",
            "uk.bbc.alba", "uk.4seven", "uk.e4.extra", "uk.really", "uk.quest", "uk.quest.red",
            "uk.blaze", "uk.challenge", "uk.sky.mix", "uk.sky.arts", "uk.great.tv", "uk.great.movies",
            "uk.great.action", "uk.talking.pictures", "uk.legend",
            "uk.itv.one.plus1", "uk.ch4.plus1", "uk.ch5.plus1", "uk.itv.two.plus1", "uk.itv.three.plus1",
            "uk.itv.four.plus1", "uk.e4.plus1", "uk.more4.plus1", "uk.film4.plus1", "uk.dave.plus1",
            "uk.sky.showcase", "uk.sky.max", "uk.sky.atlantic", "uk.sky.witness", "uk.sky.comedy",
            "uk.sky.scifi", "uk.sky.crime", "uk.sky.replay", "uk.comedy.central", "uk.comedy.central.extra",
            "uk.mtv.uk", "uk.e.entertainment", "uk.tlc", "uk.crime.investigation",
            "uk.sky.sports.main", "uk.sky.sports.pl", "uk.sky.sports.football", "uk.sky.sports.cricket",
            "uk.sky.sports.golf", "uk.sky.sports.f1", "uk.sky.sports.action", "uk.sky.sports.arena",
            "uk.sky.sports.tennis", "uk.sky.sports.racing", "uk.sky.sports.news", "uk.sky.sports.mix",
            "uk.sky.sports.plus", "uk.tnt.sports.1", "uk.tnt.sports.2", "uk.tnt.sports.3",
            "uk.tnt.sports.4", "uk.tnt.sports.ultimate", "uk.premier.sports.1", "uk.premier.sports.2",
            "uk.premier.sports.rugby", "uk.eurosport.1", "uk.eurosport.2", "uk.racing.tv", "uk.mutv",
            "uk.lfctv", "uk.dazn.uk",
            "uk.sky.cinema.premiere", "uk.sky.cinema.hits", "uk.sky.cinema.greats", "uk.sky.cinema.action",
            "uk.sky.cinema.comedy", "uk.sky.cinema.family", "uk.sky.cinema.thriller", "uk.sky.cinema.drama",
            "uk.sky.cinema.scifi", "uk.sky.cinema.animation", "uk.sky.cinema.select", "uk.tcm.movies",
            "uk.sony.movies",
            "uk.bbc.news", "uk.bbc.parliament", "uk.sky.news", "uk.gb.news", "uk.talktv", "uk.cnn.uk",
            "uk.bloomberg.uk", "uk.cnbc.uk", "uk.al.jazeera.en", "uk.euronews.en", "uk.france24.en",
            "uk.discovery", "uk.discovery.history", "uk.discovery.science", "uk.discovery.turbo",
            "uk.natgeo", "uk.natgeo.wild", "uk.sky.documentaries", "uk.sky.nature", "uk.sky.history",
            "uk.sky.history.2", "uk.animal.planet", "uk.pbs.america", "uk.smithsonian",
            "uk.cbeebies", "uk.cbbc", "uk.milkshake", "uk.nickelodeon", "uk.nick.jr", "uk.nicktoons",
            "uk.cartoon.network", "uk.boomerang", "uk.cartoonito", "uk.pop", "uk.tiny.pop", "uk.sky.kids",
            "uk.mtv.music", "uk.mtv.hits", "uk.mtv.80s", "uk.mtv.90s", "uk.now.70s", "uk.now.80s",
            "uk.now.90s", "uk.now.rock", "uk.clubland.tv", "uk.club.mtv", "uk.the.box", "uk.4music",
            "uk.kiss", "uk.magic", "uk.kerrang",
            "ie.rte.one", "ie.rte.two", "ie.virgin.media.one", "ie.virgin.media.two",
            "ie.virgin.media.three", "ie.tg4",
            "us.abc", "us.cbs", "us.nbc", "us.fox", "us.espn", "us.espn2", "us.hbo", "us.cnn",
            "us.fox.news", "us.nfl.network", "us.nba.tv",
        )
        for (id in requiredIds) {
            assertNotNull(UkChannelRegistry.findById(id), "Missing canonical channel ID: $id")
        }
    }

    @Test
    fun mergesMultiAccountStreamsSeparatesPlusOneDeduplicatesAndFiltersForeign() {
        val raw = listOf(
            rawStream("s1", xtreamSrc, "UK | BBC 1 HD", url = "https://x.test/bbc1.ts",
                catchup = CatchupConfig(CatchupMode.XTREAM, 7)),
            rawStream("s2", m3uSrc, "BBC One FHD", url = "https://m.test/bbc1.m3u8"),
            rawStream("s3", m3uSrc, "BBC One Duplicate", url = "https://x.test/bbc1.ts"),
            rawStream("s4", xtreamSrc, "UK | ITV1 HD", url = "https://x.test/itv1.ts"),
            rawStream("s5", xtreamSrc, "UK | ITV1 +1 HD", url = "https://x.test/itv1p1.ts"),
            rawStream("s6", xtreamSrc, "Custom Local", group = "Regional", url = "https://x.test/local.ts"),
            rawStream("s7", xtreamSrc, "Custom Local +1", group = "Regional", url = "https://x.test/localp1.ts"),
            rawStream("s8", m3uSrc, "FR | TF1 FHD", group = "France", url = "https://m.test/tf1.ts"),
            rawStream("s9", m3uSrc, "قناة الجزيرة", group = "Arabic", url = "https://m.test/aj.ts"),
            rawStream("s10", m3uSrc, "   ", group = "Entertainment", url = "https://m.test/noname.ts"),
        )

        val ukOnly = ChannelNormalizer.normalizeAndMerge(
            sources = listOf(xtreamSrc, m3uSrc),
            rawStreams = raw,
            prefs = IptvProfilePrefs(showNonUk = false),
        )

        // TF1 and Arabic channel excluded; s3 deduplicated against s1
        assertFalse(ukOnly.any { it.name.contains("TF1") })
        assertFalse(ukOnly.any { it.streams.any { s -> s.id == "s9" } })

        val bbc1 = ukOnly.first { it.id == "uk.bbc.one" }
        assertEquals(101, bbc1.number)
        assertEquals("BBC One", bbc1.name)
        assertEquals(2, bbc1.streams.size) // s1 + s2 (s3 deduped)
        assertEquals("s2", bbc1.streams[0].id) // 1080p beats 720p
        assertTrue(bbc1.hasCatchup)
        assertEquals(7, bbc1.maxCatchupDays)

        val itv1 = ukOnly.first { it.id == "uk.itv.one" }
        val itv1Plus1 = ukOnly.first { it.id == "uk.itv.one.plus1" }
        assertNotEquals(itv1.id, itv1Plus1.id)

        val custom = ukOnly.first { it.name == "Custom Local" }
        val customPlus1 = ukOnly.first { it.name == "Custom Local +1" }
        assertNotEquals(custom.id, customPlus1.id)

        ukOnly.forEach { assertTrue(it.name.isNotBlank()) }

        val withNonUk = ChannelNormalizer.normalizeAndMerge(
            sources = listOf(xtreamSrc, m3uSrc),
            rawStreams = raw,
            prefs = IptvProfilePrefs(showNonUk = true),
        )
        assertTrue(withNonUk.any { it.name.contains("TF1") })
        assertTrue(withNonUk.any { it.streams.any { s -> s.id == "s9" } })
    }

    @Test
    fun filtersForeignGroupPrefixesWhilePreservingUkIeUsCaAuNz() {
        // Foreign prefixes, standalone codes, keywords, and non-Latin group titles
        assertTrue(ChannelNormalizer.isForeignGroup("FR | Sport"))
        assertTrue(ChannelNormalizer.isForeignGroup("DE: Kino"))
        assertTrue(ChannelNormalizer.isForeignGroup("[ES] Deportes"))
        assertTrue(ChannelNormalizer.isForeignGroup("(IT) Calcio"))
        assertTrue(ChannelNormalizer.isForeignGroup("NL - Algemeen"))
        assertTrue(ChannelNormalizer.isForeignGroup("VIP | TR | Ulusal"))
        assertTrue(ChannelNormalizer.isForeignGroup("FR"))
        assertTrue(ChannelNormalizer.isForeignGroup("Arabic"))
        assertTrue(ChannelNormalizer.isForeignGroup("18+ Adult"))
        assertTrue(ChannelNormalizer.isForeignGroup("قنوات عربية"))

        // Non-foreign English/UK/IE/US/CA/AU/NZ groups
        assertFalse(ChannelNormalizer.isForeignGroup("UK TV"))
        assertFalse(ChannelNormalizer.isForeignGroup("UK | Sports"))
        assertFalse(ChannelNormalizer.isForeignGroup("US | Entertainment"))
        assertFalse(ChannelNormalizer.isForeignGroup("USA News"))
        assertFalse(ChannelNormalizer.isForeignGroup("IE | Sport"))
        assertFalse(ChannelNormalizer.isForeignGroup("CA | Sports"))
        assertFalse(ChannelNormalizer.isForeignGroup("AU | Foxtel"))
        assertFalse(ChannelNormalizer.isForeignGroup("NZ | Sky Sport"))
        assertFalse(ChannelNormalizer.isForeignGroup(""))

        // Raw name foreign prefix vs English names mentioning countries
        assertTrue(ChannelNormalizer.isForeignRawName("FR | TF1 FHD"))
        assertTrue(ChannelNormalizer.isForeignRawName("[DE] Das Erste"))
        assertTrue(ChannelNormalizer.isForeignRawName("VIP | PL | Canal+"))
        assertTrue(ChannelNormalizer.isForeignRawName("قناة الجزيرة"))
        assertFalse(ChannelNormalizer.isForeignRawName("UK | France 24 English"))
        assertFalse(ChannelNormalizer.isForeignRawName("France 24 English"))
        assertFalse(ChannelNormalizer.isForeignRawName("CA | TSN 1 HD"))
        assertFalse(ChannelNormalizer.isForeignRawName("AU | Fox Sports 501"))
        assertFalse(ChannelNormalizer.isForeignRawName("NZ | Sky Sport 1"))

        val raw = listOf(
            rawStream("fr-grp", xtreamSrc, "Sport 1 HD", group = "FR | Sport", url = "https://x.test/fr1.ts"),
            rawStream("fr-name", xtreamSrc, "DE | Sport 1 HD", group = "Sports", url = "https://x.test/de1.ts"),
            rawStream("ca-ok", xtreamSrc, "CA | TSN 1 HD", group = "CA | Sports", url = "https://x.test/ca1.ts"),
            rawStream("uk-fr24", xtreamSrc, "UK | France 24 English", group = "UK News", url = "https://x.test/fr24.ts"),
        )
        val filtered = ChannelNormalizer.normalizeAndMerge(
            sources = listOf(xtreamSrc),
            rawStreams = raw,
            prefs = IptvProfilePrefs(showNonUk = false),
        )
        assertFalse(filtered.any { it.streams.any { s -> s.id == "fr-grp" || s.id == "fr-name" } })
        assertTrue(filtered.any { it.streams.any { s -> s.id == "ca-ok" } })
        assertTrue(filtered.any { it.id == "uk.france24.en" })
    }

    @Test
    fun filtersDecorativePipeEmoji247AndExpandedForeignCodesWhileKeepingEnglish() {
        // Leading decorative symbols, pipes, bullets, slashes, and flag emojis
        assertTrue(ChannelNormalizer.isForeignGroup("|FR|"))
        assertTrue(ChannelNormalizer.isForeignGroup("|AR|"))
        assertTrue(ChannelNormalizer.isForeignGroup("★ DE |"))
        assertTrue(ChannelNormalizer.isForeignGroup("● TR ●"))
        assertTrue(ChannelNormalizer.isForeignGroup("FR /"))
        assertTrue(ChannelNormalizer.isForeignGroup("FR •"))
        assertTrue(ChannelNormalizer.isForeignGroup("► PL | Sport"))
        assertTrue(ChannelNormalizer.isForeignGroup("▶ ES | Cine"))
        assertTrue(ChannelNormalizer.isForeignGroup("▪ IT ▪ Sport"))
        assertTrue(ChannelNormalizer.isForeignGroup("◆ NL ◆ Algemeen"))
        assertTrue(ChannelNormalizer.isForeignGroup("» PT » Geral"))
        assertTrue(ChannelNormalizer.isForeignGroup("☆ SE ☆ Film"))
        assertTrue(ChannelNormalizer.isForeignGroup("🇫🇷 FR | Cinema"))
        assertTrue(ChannelNormalizer.isForeignGroup("🇩🇪 | Bundesliga"))

        assertTrue(ChannelNormalizer.isForeignRawName("|FR| TF1 FHD"))
        assertTrue(ChannelNormalizer.isForeignRawName("|AR| MBC 1"))
        assertTrue(ChannelNormalizer.isForeignRawName("★ DE | Sky Sport 1"))
        assertTrue(ChannelNormalizer.isForeignRawName("● TR ● Show TV"))
        assertTrue(ChannelNormalizer.isForeignRawName("FR / France 2"))
        assertTrue(ChannelNormalizer.isForeignRawName("FR • M6 HD"))
        assertTrue(ChannelNormalizer.isForeignRawName("🇫🇷 TF1"))
        assertTrue(ChannelNormalizer.isForeignRawName("🇩🇪 DE | ZDF HD"))
        assertFalse(ChannelNormalizer.isForeignRawName("★ UK | BBC One HD"))
        assertFalse(ChannelNormalizer.isForeignRawName("🇬🇧 UK | BBC One HD"))
        assertFalse(ChannelNormalizer.isForeignRawName("🇬🇧 | ITV1 HD"))
        assertFalse(ChannelNormalizer.isForeignGroup("★ UK | Entertainment"))

        assertEquals("TF1", ChannelNormalizer.cleanChannelTitle("|FR| TF1 FHD"))
        assertEquals("Sky Sport 1", ChannelNormalizer.cleanChannelTitle("★ DE | Sky Sport 1 HD"))
        assertEquals("Show TV", ChannelNormalizer.cleanChannelTitle("● TR ● Show TV"))
        assertEquals("France 2", ChannelNormalizer.cleanChannelTitle("FR / France 2"))
        assertEquals("M6", ChannelNormalizer.cleanChannelTitle("FR • M6 HD"))
        assertEquals("BBC One", ChannelNormalizer.cleanChannelTitle("★ UK | ● BBC One HD"))
        assertEquals("BBC One", ChannelNormalizer.cleanChannelTitle("🇬🇧 UK | BBC One FHD"))

        // 24/7-XX and 24x7-XX foreign prefixes vs English 24/7 channels
        assertTrue(ChannelNormalizer.isForeignRawName("24/7-AR MOHAMMED ABDO"))
        assertTrue(ChannelNormalizer.isForeignRawName("24x7-AR MOHAMMED ABDO"))
        assertTrue(ChannelNormalizer.isForeignGroup("24/7-TR Movies"))
        assertTrue(ChannelNormalizer.isForeignRawName("★ 24/7-IND Bollywood"))
        assertEquals("MOHAMMED ABDO", ChannelNormalizer.cleanChannelTitle("24/7-AR MOHAMMED ABDO"))

        assertFalse(ChannelNormalizer.isForeignRawName("24/7 Only Fools and Horses"))
        assertFalse(ChannelNormalizer.isForeignRawName("24/7: The Office"))
        assertFalse(ChannelNormalizer.isForeignRawName("24/7 - My Family"))
        assertFalse(ChannelNormalizer.isForeignRawName("24/7-UK Sitcoms"))
        assertFalse(ChannelNormalizer.isForeignRawName("24x7 Classic Movies"))
        assertEquals("24/7 Only Fools and Horses", ChannelNormalizer.cleanChannelTitle("24/7 Only Fools and Horses"))

        // Bracketed expanded codes and group keywords
        assertTrue(ChannelNormalizer.isForeignRawName("(HI) AAPKA COLORS"))
        assertTrue(ChannelNormalizer.isForeignRawName("(PA) ATN PUNJABI"))
        assertTrue(ChannelNormalizer.isForeignRawName("(PH) GMA PINOY TV"))
        assertTrue(ChannelNormalizer.isForeignRawName("(ZH) CGTN"))
        assertTrue(ChannelNormalizer.isForeignRawName("[FRA] TF1"))
        assertTrue(ChannelNormalizer.isForeignRawName("EXYU | Arena Sport 1"))
        assertEquals("AAPKA COLORS", ChannelNormalizer.cleanChannelTitle("(HI) AAPKA COLORS"))

        for (group in listOf("Filipino", "Tagalog TV", "Gujarati", "Malayalam", "Telugu", "Marathi", "Cantonese", "Mandarin")) {
            assertTrue(ChannelNormalizer.isForeignGroup(group), "Expected foreign group: $group")
        }

        // Preserve UK, IE, US, CA, AU, NZ, France 24 English, and ID
        for (name in listOf(
            "UK | BBC One", "IE | RTE One", "IRL | TG4", "US | ESPN", "USA | NBC",
            "EN | Euronews", "ENG | Sky News", "CA | TSN 1", "CAN | Sportsnet",
            "AU | Foxtel", "AUS | 9News", "NZ | Sky Sport", "France 24 English",
            "UK | France 24 English", "ID HD",
        )) {
            assertFalse(ChannelNormalizer.isForeignRawName(name), "Expected preserved: $name")
        }
    }

    @Test
    fun foldsNigmaTvSuperscriptsAndStripsDecorativeSymbols() {
        assertEquals(
            "HD RAW hevc VIP DOLBy AUDIO UHD 3840P 60fps (BK) 8K",
            ChannelNormalizer.foldSuperscripts("ᴴᴰ ᴿᴬᵂ ʰᵉᵛᶜ ⱽᴵᴾ ᴰᴼᴸᴮʸ ᴬᵁᴰᴵᴼ ᵁᴴᴰ ³⁸⁴⁰ᴾ ⁶⁰ᶠᵖˢ ⁽ᴮᴷ⁾ ⁸ᴷ"),
        )
        assertEquals(
            "ABDEGHIJKLMNOPRTUVW abcdefghijklmnoprstuvwxyz 0123456789()",
            ChannelNormalizer.foldSuperscripts("ᴬᴮᴰᴱᴳᴴᴵᴶᴷᴸᴹᴺᴼᴾᴿᵀᵁⱽᵂ ᵃᵇᶜᵈᵉᶠᵍʰⁱʲᵏˡᵐⁿᵒᵖʳˢᵗᵘᵛʷˣʸᶻ ⁰¹²³⁴⁵⁶⁷⁸⁹⁽⁾"),
        )

        val qUhd = ChannelNormalizer.parseQuality("UK: TNT SPORTS ᵁᴴᴰ ³⁸⁴⁰ᴾ ⁶⁰ᶠᵖˢ ʰᵉᵛᶜ ᴿᴬᵂ ᴰᴼᴸᴮʸ ᴬᵁᴰᴵᴼ ⁽ᴮᴷ⁾")
        assertEquals(2160, qUhd.height)
        assertEquals(60, qUhd.fps)
        assertEquals("HEVC", qUhd.codec)
        assertTrue(qUhd.isRaw)
        assertTrue(qUhd.isBackup)
        assertEquals("UK", qUhd.region)
        assertEquals("4K · 60fps", qUhd.badge)

        val q8k = ChannelNormalizer.parseQuality("8K: SKY SPORTS MAIN EVENT ⁸ᴷ")
        assertEquals(2160, q8k.height)

        assertEquals("TNT SPORTS 4", ChannelNormalizer.cleanChannelTitle("PRIME: TNT SPORTS 4 ᴿᴬᵂ"))
        assertEquals("SKY ATLANTIC", ChannelNormalizer.cleanChannelTitle("8K: SKY ATLANTIC ⁸ᴷ ᴰᴼᴸᴮʸ ᴬᵁᴰᴵᴼ"))
        assertEquals("BBC ONE LONDON", ChannelNormalizer.cleanChannelTitle("UK: BBC ONE LONDON ◉ ◎ ○ ☼ ⚽ ▼ ▲"))
        assertEquals("BBC 3 CBBC", ChannelNormalizer.cleanChannelTitle("UK: BBC 3 / CBBC HD ◉"))
        assertEquals("BBC 4 CBEEBIES", ChannelNormalizer.cleanChannelTitle("UK: BBC 4 / CBEEBIES 4K ◉"))
        assertEquals("TNT SPORTS (EVENT)", ChannelNormalizer.cleanChannelTitle("4K: TNT SPORTS ᵁᴴᴰ ³⁸⁴⁰ᴾ (EVENT)"))
    }

    @Test
    fun linksNigmaTvUkChannelsAndStripsExpandedRegions() {
        val raw = listOf(
            rawStream("bbc-lon", xtreamSrc, "UK: BBC 1 HD ◉", group = "UK| GENERAL ᴴᴰ/ᴿᴬᵂ", url = "https://x.test/1.ts"),
            rawStream("bbc-eyl", xtreamSrc, "UK: BBC ONE EAST YORKSHIRE & LINCOLNSHIRE ◉", group = "UK| GENERAL ᴴᴰ/ᴿᴬᵂ", url = "https://x.test/2.ts"),
            rawStream("bbc-nec", xtreamSrc, "UK: BBC ONE NORTH EAST& CAM ◉", group = "UK| GENERAL ᴴᴰ/ᴿᴬᵂ", url = "https://x.test/3.ts"),
            rawStream("itv-1", xtreamSrc, "UK: ITV 1 4K ◉", group = "UK| GENERAL ʰᵉᵛᶜ", url = "https://x.test/4.ts"),
            rawStream("ch-4", xtreamSrc, "UK: CHANNEL 4 HEVC 4K", group = "UK| GENERAL ʰᵉᵛᶜ", url = "https://x.test/5.ts"),
            rawStream("ch-5", xtreamSrc, "UK: CHANNEL 5 4K ◉", group = "UK| GENERAL ᴴᴰ/ᴿᴬᵂ", url = "https://x.test/6.ts"),
            rawStream("bbc-3", xtreamSrc, "UK: BBC 3 / CBBC HD ◉", group = "UK| GENERAL ᴴᴰ/ᴿᴬᵂ", url = "https://x.test/7.ts"),
            rawStream("bbc-4", xtreamSrc, "UK: BBC 4 / CBEEBIES 4K ◉", group = "UK| GENERAL ᴴᴰ/ᴿᴬᵂ", url = "https://x.test/8.ts"),
            rawStream("sky-atl", xtreamSrc, "UK: SKY ATLANTIC 4K ◉", group = "UK| ENTERTAINMENT", url = "https://x.test/9.ts"),
            rawStream("tnt-4", xtreamSrc, "PRIME: TNT SPORTS 4 ᴿᴬᵂ", group = "UK| TNT SPORT ᴿᴬᵂ ⱽᴵᴾ ᴰᴼᴸᴮʸ ᴬᵁᴰᴵᴼ", url = "https://x.test/10.ts"),
            rawStream("bbc-scot", xtreamSrc, "UK: BBC ONE SCOTLAND HD ◉", group = "UK| GENERAL ᴴᴰ/ᴿᴬᵂ", url = "https://x.test/11.ts"),
            rawStream("itv-quiz", xtreamSrc, "UK : ITV QUIZ 4K", group = "UK| ENTERTAINMENT", url = "https://x.test/12.ts"),
        )

        val merged = ChannelNormalizer.normalizeAndMerge(
            sources = listOf(xtreamSrc),
            rawStreams = raw,
            prefs = IptvProfilePrefs(showNonUk = false),
        )
        val byStreamId = merged.associateBy { ch -> ch.streams.map { it.id } }
        fun channelFor(streamId: String): CanonicalChannel =
            merged.first { ch -> ch.streams.any { it.id == streamId } }

        assertEquals(12, byStreamId.values.sumOf { it.streams.size })
        assertEquals("uk.bbc.one", channelFor("bbc-lon").id)
        assertEquals("uk.bbc.one", channelFor("bbc-eyl").id)
        assertEquals("uk.bbc.one", channelFor("bbc-nec").id)
        assertEquals("uk.itv.one", channelFor("itv-1").id)
        assertEquals("uk.ch4", channelFor("ch-4").id)
        assertEquals("uk.ch5", channelFor("ch-5").id)
        assertEquals("uk.bbc.three", channelFor("bbc-3").id)
        assertEquals("uk.bbc.four", channelFor("bbc-4").id)
        assertEquals("uk.sky.atlantic", channelFor("sky-atl").id)
        assertEquals("uk.tnt.sports.4", channelFor("tnt-4").id)
        assertEquals("uk.bbc.scotland", channelFor("bbc-scot").id)
        assertEquals("custom.itv.quiz", channelFor("itv-quiz").id)

        assertEquals("uk.bbc.three", UkChannelRegistry.match("BBC Three CBBC")?.id)
        assertEquals("uk.bbc.four", UkChannelRegistry.match("BBC Four CBeebies")?.id)
        assertEquals("uk.tnt.sports.ultimate", UkChannelRegistry.match("TNT Sports UHD")?.id)

        for (bbcRegion in listOf(
            "BBC One South West",
            "BBC One Westcountry South West",
            "BBC One East Midlands",
            "BBC One North East Cumbria",
            "BBC One Scot",
            "BBC One Yorks",
            "BBC One North",
            "BBC One East W",
            "BBC One YK LI",
        )) {
            assertEquals("uk.bbc.one", UkChannelRegistry.match(bbcRegion)?.id, "Failed for $bbcRegion")
        }
        for (itvRegion in listOf(
            "ITV1 Anglia TV West",
            "ITV1 Anglia East",
            "ITV1 Central West",
            "ITV1 Central East",
            "ITV1 Border Scotland",
            "ITV1 Channel Island",
            "ITV1 Westcountry West",
        )) {
            assertEquals("uk.itv.one", UkChannelRegistry.match(itvRegion)?.id, "Failed for $itvRegion")
        }
    }

    @Test
    fun keepsNigmaTvSuperscriptUkGroupsAndFiltersForeignAndBloatBouquets() {
        val keptGroups = listOf(
            "UK| GENERAL ᴴᴰ/ᴿᴬᵂ",
            "UK| GENERAL ʰᵉᵛᶜ",
            "UK| BBC IPLAYER ᴿᴬᵂ",
            "UK| NOW TV SPORT ᵁᴴᴰ ³⁸⁴⁰ᴾ",
            "UK| TNT SPORT ᴿᴬᵂ ⱽᴵᴾ ᴰᴼᴸᴮʸ ᴬᵁᴰᴵᴼ",
            "4K| ᵁᴴᴰ ³⁸⁴⁰ᴾ",
            "CA| GENERAL EN",
            "CA| SPORTS EN",
            "US| SPORT ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ",
        )
        for (group in keptGroups) {
            assertFalse(ChannelNormalizer.isForeignGroup(group), "Expected non-foreign: $group")
            assertFalse(ChannelNormalizer.isBloatNonLinearGroup(group), "Expected non-bloat: $group")
        }

        val foreignGroups = listOf(
            "UK| UKRAINE HD/4K",
            "SU| SURINAME 4K",
            "LA| GENERAL",
            "CG| MONTENEGRO/CMA GORA ᴿᴬᵂ",
            "AG| AFGHANISTAN HD/4K",
            "ASIA| UK",
            "CA| GENERAL FR",
            "CA| SPORTS FR",
            "RU| ТНТ",
            "قناة الجزيرة",
        )
        for (group in foreignGroups) {
            assertTrue(ChannelNormalizer.isForeignGroup(group), "Expected foreign group: $group")
        }

        val bloatGroups = listOf(
            "US| ESPN+ PPV ⱽᴵᴾ",
            "US| ROKU ᴿᴬᵂ ⁶⁰ᶠᵖˢ",
            "UK| 24/7 ᴴᴰ/ᴿᴬᵂ",
            "8K| SPORT ON AIR ⁸ᴷ",
            "UK| SKY STORE",
            "UK| SKY MIX DOCS",
            "UK| REALITY SHOW TV",
            "UK| REPLAY",
        )
        for (group in bloatGroups) {
            assertFalse(ChannelNormalizer.isForeignGroup(group), "isForeignGroup stays pure for: $group")
            assertTrue(ChannelNormalizer.isBloatNonLinearGroup(group), "Expected bloat group: $group")
        }

        val streams = listOf(
            rawStream("uk-ok", xtreamSrc, "UK: BBC 1 HD ◉", group = "UK| GENERAL ᴴᴰ/ᴿᴬᵂ", url = "https://x.test/uk1.ts"),
            rawStream("ca-en", xtreamSrc, "CA: TSN 1 ᴴᴰ", group = "CA| GENERAL EN", url = "https://x.test/ca-en.ts"),
            rawStream("us-sport", xtreamSrc, "US: ESPN ᴴᴰ", group = "US| SPORT ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ", url = "https://x.test/us-sport.ts"),
            rawStream("drop-ua", xtreamSrc, "1+1 HD", group = "UK| UKRAINE HD/4K", url = "https://x.test/ua.ts"),
            rawStream("drop-su", xtreamSrc, "ATV 4K", group = "SU| SURINAME 4K", url = "https://x.test/su.ts"),
            rawStream("drop-ca-fr", xtreamSrc, "RDS HD", group = "CA| GENERAL FR", url = "https://x.test/ca-fr.ts"),
            rawStream("drop-espn-ppv", xtreamSrc, "ESPN+ 101", group = "US| ESPN+ PPV ⱽᴵᴾ", url = "https://x.test/ppv.ts"),
            rawStream("drop-roku", xtreamSrc, "Roku Channel 1", group = "US| ROKU ᴿᴬᵂ ⁶⁰ᶠᵖˢ", url = "https://x.test/roku.ts"),
            rawStream("drop-247", xtreamSrc, "24/7 Friends", group = "UK| 24/7 ᴴᴰ/ᴿᴬᵂ", url = "https://x.test/247.ts"),
            rawStream("drop-8k", xtreamSrc, "Sport Event 1", group = "8K| SPORT ON AIR ⁸ᴷ", url = "https://x.test/8k.ts"),
            rawStream("drop-ar", xtreamSrc, "قناة الجزيرة", group = "UK| GENERAL ᴴᴰ/ᴿᴬᵂ", url = "https://x.test/ar.ts"),
            rawStream("drop-ru", xtreamSrc, "RU| ТНТ", group = "UK| GENERAL ᴴᴰ/ᴿᴬᵂ", url = "https://x.test/ru.ts"),
        )
        val filtered = ChannelNormalizer.normalizeAndMerge(
            sources = listOf(xtreamSrc),
            rawStreams = streams,
            prefs = IptvProfilePrefs(showNonUk = false),
        )
        val keptIds = filtered.flatMap { ch -> ch.streams.map { it.id } }.toSet()
        assertEquals(setOf("uk-ok", "ca-en", "us-sport"), keptIds)
    }
}
