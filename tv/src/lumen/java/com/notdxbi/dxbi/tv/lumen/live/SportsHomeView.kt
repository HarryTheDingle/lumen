package com.notdxbi.dxbi.tv.lumen.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.notdxbi.dxbi.tv.live.LiveReminders
import com.notdxbi.dxbi.tv.live.LiveTvStore
import com.notdxbi.dxbi.tv.lumen.Dx
import com.notdxbi.dxbi.tv.lumen.centredWhenFocused
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import lumos.iptv.FixtureStatus
import lumos.iptv.SportsFixture
import lumos.iptv.SportsTeamSide
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The racing series on the Formula 1 page: ESPN's league code, and the Worker's sport name */
internal class MotorData(val coming: List<SportsFixture>, val rounds: List<LiveTvStore.MotorRound>, val standings: org.json.JSONObject?, val at: Long)
private object MotorCache { val map = HashMap<Series, MotorData>() }

private enum class Series(val label: String, val code: String, val sport: String) {
    F1("Formula 1", "f1", "f1"), INDY("IndyCar", "irl", "indycar"), NASCAR("NASCAR", "nascar-premier", "nascar")
}

private fun SportsFixture.isRacing() =
    sport.contains("racing", true) || league.equals("f1", true) || league.equals("irl", true) ||
        league.startsWith("nascar", true) || leagueName.contains("formula", true)

/** A sport's tile: the sport, its words, its icon. [ALL] is the whole hub (every sport's matches) */
internal enum class SportTile(val label: String, val filter: SportFilter?, val icon: ImageVector) {
    /** Everything on the TV guide that's live sport right now (cricket, rugby, WSL and the rest, which the match feed doesn't carry) */
    LIVE("Live now", null, Icons.Filled.LiveTv),
    FOOTBALL("Football", SportFilter.FOOTBALL, Icons.Filled.SportsSoccer),
    F1("Motorsports", SportFilter.F1, Icons.Filled.SportsMotorsports),
    NBA("NBA", SportFilter.NBA, Icons.Filled.SportsBasketball),
    NHL("NHL", SportFilter.NHL, Icons.Filled.SportsHockey),
    UFC("UFC", SportFilter.UFC, Icons.Filled.SportsMma),
    GOLF("Golf", SportFilter.GOLF, Icons.Filled.SportsGolf),
    TENNIS("Tennis", SportFilter.TENNIS, Icons.Filled.SportsTennis),
}

/** The condensed face for scores and countdowns, as Apple Sports' numerals */
internal val Condensed = androidx.compose.ui.text.font.FontFamily(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD))

/** A team's wash on a match panel: from its name, steady between runs */
private val PANEL_TINTS = listOf(
    Color(0xFF1B3A8A), Color(0xFF9A1B25), Color(0xFF1F6B3A), Color(0xFF6A3A7A),
    Color(0xFFB36A12), Color(0xFF0F6F7A), Color(0xFF7A1F5A), Color(0xFF3A4A5A),
)
internal fun tintFor(name: String): Color = PANEL_TINTS[kotlin.math.abs(name.hashCode()) % PANEL_TINTS.size]

/**
 * Sports: a row of large glass tiles to choose the sport (favourites first, hold OK to favourite), then that sport's
 * matches as glass panels in each side's colours, your teams first and marked. Formula 1 has its own page: the next
 * race, the championships, the weekends.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SportsHomeView(
    store: LiveTvStore,
    hideSportsScores: Boolean,
    contentFocusRequester: FocusRequester,
    onMoveToRail: () -> Unit,
    nav: com.notdxbi.dxbi.tv.lumen.Nav,
) {
    val context = LocalContext.current
    val prefs by store.prefs.collectAsState()
    val schedules by store.teamSchedules.collectAsState()
    val rawFixtures by store.sportsFixtures.collectAsState()
    // (fighters come with ESPN's athlete ids: their headshots are on ESPN's open image host)
    val fixtures = remember(rawFixtures) {
        fun side(x: SportsTeamSide?): SportsTeamSide? {
            val id = x?.id
            return if (x != null && x.logoUrl.isNullOrBlank() && id != null && id.length >= 5 && id.all { it.isDigit() }) x.copy(logoUrl = "https://a.espncdn.com/i/headshots/mma/players/full/$id.png") else x
        }
        rawFixtures.map { f -> if (Regex("ufc|mma", RegexOption.IGNORE_CASE).containsMatchIn(f.sport + " " + f.league + " " + f.leagueName)) f.copy(home = side(f.home), away = side(f.away)) else f }
    }
    val reminders by LiveReminders.all.collectAsState()
    val lists = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var addingTeam by remember { mutableStateOf(false) }
    var openMatch by remember { mutableStateOf<SportsFixture?>(null) }
    var series by remember { mutableStateOf(Series.F1) }
    var standings by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var rounds by remember { mutableStateOf<List<LiveTvStore.MotorRound>>(emptyList()) }
    var comingRaces by remember { mutableStateOf<List<SportsFixture>>(emptyList()) }
    var focusedMatch by remember { mutableStateOf<SportsFixture?>(null) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val sportPrefs = remember { context.getSharedPreferences("lumen_sports", android.content.Context.MODE_PRIVATE) }
    var favRacers by remember { mutableStateOf(sportPrefs.getStringSet("racers", null)?.toSet() ?: emptySet()) }
    var favouriteSports by remember {
        mutableStateOf(sportPrefs.getStringSet("favourites", null)?.toSet() ?: setOf(SportTile.FOOTBALL.name, SportTile.F1.name))
    }
    var tile by remember {
        mutableStateOf(
            sportPrefs.getString("start", null)?.let { n -> SportTile.entries.firstOrNull { it.name == n } }
                ?: SportTile.LIVE,
        )
    }
    LaunchedEffect(Unit) { sportPrefs.edit().remove("start").apply() }
    val tiles = remember(favouriteSports) {
        // (Live now always first, then the favourites)
        listOf(SportTile.LIVE) + SportTile.entries.filter { it != SportTile.LIVE }.sortedByDescending { it.name in favouriteSports }
    }
    // What's live sport on the TV guide right now
    val guideRows by store.mainGuideRows.collectAsState()
    val tvLive = remember(guideRows, nowMs / 60_000L) {
        guideRows.mapNotNull { row ->
            val ch = row.linkedChannel ?: return@mapNotNull null
            if (!row.isPlayable) return@mapNotNull null
            val p = store.nowAndNext(ch, nowMs).first
            if (p != null && p.isLiveSport && p.isOnAt(nowMs)) row to p else null
        }
            // (the same programme on several channels, e.g. Sky Sports' main and its football channel, or an HD and an SD
            // copy: one tile, on the best of them)
            .groupBy { (_, p) -> simpleNorm(p.title.removePrefix("Live:").removePrefix("LIVE:")) + "@" + p.startMs / 60_000L }
            .values.map { same -> same.maxBy { (row, _) -> qualityScore(row) } }
            .take(40)
    }
    // Your teams first, then your favourite sports, then the rest
    val favTeamWords = remember(prefs.favouriteTeams) { prefs.favouriteTeams.flatMap { listOfNotNull(it.name, it.shortName) }.map(::simpleNorm).filter { it.length >= 3 } }
    val favTiles = remember(favouriteSports) { SportTile.entries.filter { it.name in favouriteSports && it != SportTile.LIVE } }
    val tvRanked = remember(tvLive, favTeamWords, favTiles) {
        tvLive.sortedBy { (row, p) ->
            val t = " ${simpleNorm(p.title)} "
            // (your teams, then real matches and races, then your sports' other programmes, then the rest)
            val match = Regex("\\b(v|vs)\\b|grand prix|race|final", RegexOption.IGNORE_CASE).containsMatchIn(p.title)
            when {
                favTeamWords.any { t.contains(" $it ") } -> 0
                match && favTiles.any { tvForTile(it, row, p) } -> 1
                match -> 2
                favTiles.any { tvForTile(it, row, p) } -> 3
                else -> 4
            }
        }
    }
    val seen = remember(tile) { HashSet<String>() }
    var focusedTv by remember { mutableStateOf<Pair<lumos.iptv.GuideChannelRow, lumos.iptv.EpgProgramme>?>(null) }

    LaunchedEffect(Unit) {
        LiveReminders.load(context)
        store.refreshSavedTeamSchedules()
        while (true) {
            delay(30_000L)
            nowMs = System.currentTimeMillis()
        }
    }
    LaunchedEffect(tile) { focusedTv = null; focusedMatch = null }
    // The match feed: all sports at once (so moving between tabs never reloads it, or narrows it), refreshed now and then
    val sportsLoading by store.sportsLoading.collectAsState()
    var feedReady by remember { mutableStateOf(fixtures.isNotEmpty()) }
    LaunchedEffect(Unit) {
        while (true) {
            store.loadSportsFixtures()
            delay(90_000L)
        }
    }
    LaunchedEffect(sportsLoading, fixtures.size) {
        if (!sportsLoading && fixtures.isNotEmpty()) feedReady = true
        else if (!feedReady) { delay(4_000L); feedReady = true }
    }
    // Formula 1 and the other series: their extras (the next races, the standings, the replays) come before the page shows, so
    // it arrives in one piece, from the top, rather than the lower half first
    var motorReady by remember { mutableStateOf(false) }
    // (the page waits for the series' extras only when you arrive on the tab; moving along the series pills keeps the page, and the
    // pills, where they are, and swaps what's under them when it's ready)
    LaunchedEffect(tile) { if (tile == SportTile.F1) motorReady = false }
    // (the three series' extras are fetched together as the page opens and kept for a few minutes, so the Motorsports tab is
    // there at once when the remote gets to it, and moving along its pills never waits)
    suspend fun motorData(sr: Series): MotorData {
        MotorCache.map[sr]?.takeIf { System.currentTimeMillis() - it.at < 300_000L }?.let { return it }
        return kotlinx.coroutines.coroutineScope {
            val a = async { runCatching { store.upcomingRaces(sr.sport, sr.code) }.getOrDefault(emptyList()) }
            val b = async { if (sr == Series.F1) runCatching { store.motorReplays() }.getOrDefault(emptyList()) else emptyList() }
            val c = async { runCatching { store.f1Standings(sr.code) }.getOrNull() }
            MotorData(a.await(), b.await(), c.await(), System.currentTimeMillis()).also { MotorCache.map[sr] = it }
        }
    }
    LaunchedEffect(Unit) {
        Series.entries.forEach { sr -> launch { motorData(sr) } }
    }
    LaunchedEffect(tile, series) {
        if (tile != SportTile.F1) return@LaunchedEffect
        val d = motorData(series)
        comingRaces = d.coming; if (series == Series.F1) rounds = d.rounds; standings = d.standings
        motorReady = true
    }
    val remindedIds = remember(reminders) { reminders.mapTo(HashSet()) { it.id } }
    val favTeamIds = remember(prefs.favouriteTeams) { prefs.favouriteTeams.mapTo(HashSet()) { it.teamId } }

    fun toggleReminder(f: SportsFixture) {
        android.widget.Toast.makeText(context, if ("fixture:${f.id}" in remindedIds) "Reminder removed" else "We'll remind you before ${fixtureTitle(f)} starts", android.widget.Toast.LENGTH_SHORT).show()
        LiveReminders.toggle(
            context,
            LiveReminders.Reminder(
                id = "fixture:${f.id}",
                title = fixtureTitle(f),
                startMs = f.startMs,
                channelId = null,
                channelName = f.ukChannelName,
                fixtureId = f.id,
            ),
        )
    }
    fun playMotor(r: LiveTvStore.MotorRound, session: LiveTvStore.MotorSession) {
        com.notdxbi.dxbi.tv.live.LumenHandoff.startMsOnce = null
        com.notdxbi.dxbi.tv.live.LumenHandoff.returnTo = null
        com.notdxbi.dxbi.tv.live.LumenHandoff.pending.value = com.notdxbi.dxbi.tv.live.LumenHandoff.Request(
            id = r.feedId, type = r.feedType, name = "${r.place} ${session.label}", background = null,
            videoId = session.videoId, channelId = "", channelName = "",
        )
    }
    fun toggleRacer(key: String) {
        favRacers = if (key in favRacers) favRacers - key else favRacers + key
        sportPrefs.edit().putStringSet("racers", favRacers).apply()
    }
    fun toggleFavouriteSport(t: SportTile) {
        favouriteSports = if (t.name in favouriteSports) favouriteSports - t.name else favouriteSports + t.name
        sportPrefs.edit().putStringSet("favourites", favouriteSports).apply()
    }

    // ---- What's on the page for the chosen sport (worked out here, so the page is one list of rows)
    val f1 = remember(fixtures, series) {
        fixtures.filter { it.isRacing() && it.league.equals(series.code, true) }.sortedBy { it.startMs }
    }
    val nextRace = f1.firstOrNull { it.status == FixtureStatus.LIVE } ?: (f1.filter { it.status == FixtureStatus.UPCOMING } + comingRaces).distinctBy { it.id }.minByOrNull { it.startMs }
    val mine = remember(schedules) { schedules.flatMap { it.live + it.upcoming + it.past } }
    val sportFilter = tile.filter
    val pool = remember(fixtures, mine, tile) {
        (fixtures + mine).distinctBy { it.id }.filter { sportFilter == null || matchesSport(it, sportFilter) }
    }
    fun isMine(f: SportsFixture) = f.home?.id in favTeamIds || f.away?.id in favTeamIds
    val live = pool.filter { it.status == FixtureStatus.LIVE }.sortedWith(compareBy({ !isMine(it) }, { it.startMs }))
    val upcoming = pool.filter { it.status == FixtureStatus.UPCOMING }.sortedWith(compareBy({ !isMine(it) }, { it.startMs })).take(12)
    val past = pool.filter { it.status == FixtureStatus.FINAL }.sortedWith(compareBy({ !isMine(it) }, { -it.startMs })).take(10)
    // (golf and the like have no two sides: an event, not a match)
    fun sided(f: SportsFixture) = !f.home?.name.isNullOrBlank() && !f.away?.name.isNullOrBlank()
    val events = pool.filterNot { sided(it) }
    // (the Live now page has only what is live: no event ten days off, however big)
    val heroEvent = events.firstOrNull { it.status == FixtureStatus.LIVE }
        ?: if (tile == SportTile.LIVE) null else (events.filter { it.status == FixtureStatus.UPCOMING }.minByOrNull { it.startMs }
            ?: events.filter { it.status == FixtureStatus.FINAL }.maxByOrNull { it.startMs })
    // (a live match the IPTV has a stream for comes before one it hasn't: only those get a Watch button)
    val resolved by store.resolvedFixtureStreams.collectAsState()
    fun hasStream(f: SportsFixture) = resolved[f.id]?.isNotEmpty() == true
    val detailFor = focusedMatch?.takeIf { f -> pool.any { it.id == f.id } }
        ?: (if (tile == SportTile.LIVE) live else live + upcoming).sortedBy { if (it.status == FixtureStatus.LIVE && hasStream(it)) 0 else 1 }.firstOrNull { sided(it) }
    val tvShown = if (tile == SportTile.LIVE) tvRanked else tvRanked.filter { tvForTile(tile, it.first, it.second) }
    // The billboard: the match or programme the remote is on; before it's on one, a live match (yours first), else what's
    // live on TV, else the next match
    val tvTop = focusedTv?.takeIf { f -> tvShown.any { it.second.title == f.second.title && it.first.guideChannel.id == f.first.guideChannel.id } }
        ?: if (detailFor == null || (tile == SportTile.LIVE && detailFor.status != FixtureStatus.LIVE)) tvShown.firstOrNull() else null
    val billboardMatch = if (tvTop != null && focusedTv != null) null else detailFor?.takeIf { tile != SportTile.LIVE || it.status == FixtureStatus.LIVE }
    // the glow behind the top of the page: the chosen match's two sides' colours (Formula 1: red)
    var glow by remember { mutableStateOf(Color(0xFF1B3A8A) to Color(0xFF9A1B25)) }
    LaunchedEffect(tile, detailFor?.id, tvTop?.second?.title, billboardMatch?.id) {
        glow = when {
            tile == SportTile.F1 -> Color(0xFFE10600) to Color(0xFF4A0A0A)
            billboardMatch != null -> tintFor(billboardMatch.home?.name ?: billboardMatch.name) to tintFor(billboardMatch.away?.name ?: billboardMatch.leagueName)
            tvTop != null -> tintFor(tvTop.second.title) to tintFor(tvTop.first.guideChannel.name)
            else -> tintFor(tile.label) to tintFor(tile.label + "x")
        }
    }
    // (Up from a sport's page lands back on that sport's pill, not on whichever pill is above the remote)
    val chosenTile = remember { FocusRequester() }
    // (Down from the Motorsports pill goes into the page, as Down does from a film's buttons; the series pills are met on the way back up)
    val heroFocus = remember { FocusRequester() }
    val seriesFocus = remember { FocusRequester() }
    val tileRow = androidx.compose.foundation.lazy.rememberLazyListState()
    val pageScroller = remember(lists) { com.notdxbi.dxbi.tv.lumen.PageScroller(lists, scope) }
    // a new sport starts at the top of its page
    LaunchedEffect(tile) { lists.scrollToItem(0) }

    // Back from inside a sport's page: first up to its pill along the top (the page glides back to its top); only a Back
    // from the pills goes on to Lumen's menu
    var tilesFocused by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = !tilesFocused && openMatch == null && !addingTeam && !nav.menuOpen) {
        pageScroller.toTop()
        scope.launch {
            val idx = tiles.indexOf(tile)
            if (idx >= 0 && tileRow.layoutInfo.visibleItemsInfo.none { it.index == idx }) tileRow.scrollToItem(idx)
            // (the pills come back into the page as it glides up: try each frame until the chosen one's there)
            repeat(90) {
                if (runCatching { chosenTile.requestFocus() }.isSuccess && tilesFocused) return@launch
                androidx.compose.runtime.withFrameNanos { }
            }
        }
    }

    // ---- The extras: Formula 1's weekend, the football tables, and the teams' pictures (all through the Worker, kept a while)
    var weekend by remember { mutableStateOf<F1Weekend?>(null) }
    var weekendDone by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val j = runCatching { store.f1Weekend() }.getOrNull()
        weekend = parseWeekend(j); weekendDone = true
    }
    var ufcCard by remember { mutableStateOf<UfcCard?>(null) }
    var tennis by remember { mutableStateOf<Tennis?>(null) }
    var golf by remember { mutableStateOf<Golf?>(null) }
    LaunchedEffect(Unit) {
        launch { ufcCard = parseUfc(runCatching { store.sportsPage("ufc") }.getOrNull()) }
        launch { tennis = parseTennis(runCatching { store.sportsPage("tennis") }.getOrNull()) }
        launch { golf = parseGolf(runCatching { store.sportsPage("golf") }.getOrNull()) }
    }
    fun remindAt(id: String, title: String, at: Long) {
        val on = id in remindedIds
        android.widget.Toast.makeText(context, if (on) "Reminder removed" else "We'll remind you before $title starts", android.widget.Toast.LENGTH_SHORT).show()
        LiveReminders.toggle(context, LiveReminders.Reminder(id = id, title = title, startMs = at, channelId = null, channelName = null))
    }
    val footballTeams = remember(prefs.favouriteTeams) { prefs.favouriteTeams.filter { it.sport.contains("football", true) || it.sport.contains("soccer", true) || it.league.startsWith("eng") || it.league.contains(".") } }
    val tableLeagues = remember(footballTeams) { (listOf("eng.1") + footballTeams.map { it.league }).distinct() }
    var tables by remember { mutableStateOf<Map<String, LeagueTable>>(emptyMap()) }
    LaunchedEffect(tableLeagues) {
        tableLeagues.forEach { l -> launch { parseTable(runCatching { store.sportsTable(l) }.getOrNull())?.let { t -> tables = tables + (l to t) } } }
    }
    var art by remember { mutableStateOf<Map<String, org.json.JSONObject>>(emptyMap()) }
    fun fanart(name: String?, pick: Int = 0): String? = name?.let { n ->
        art[n]?.optJSONArray("fanart")?.let { a -> if (a.length() == 0) null else a.optString(pick % a.length()) }?.takeIf { it.isNotBlank() }
    }
    // (TheSportsDB's league pictures, for when a team has none: ESPN's league codes to TheSportsDB's)
    val sportsDbLeague = mapOf(
        "eng.1" to "4328", "eng.2" to "4329", "eng.3" to "4396", "uefa.champions" to "4480", "uefa.europa" to "4481",
        "eng.fa" to "4482", "eng.league_cup" to "4570", "sco.1" to "4330", "f1" to "4370",
        "irl" to "4373", "nascar-premier" to "4393", "ufc" to "4443", "atp" to "4464", "wta" to "4517", "pga" to "4425",
    )
    fun leagueArt(league: String?, pick: Int = 0): String? = league?.let { sportsDbLeague[it] }?.let { fanart("league:$it", pick) }
    val footballMineIds = remember(footballTeams) { footballTeams.mapTo(HashSet()) { it.teamId } }
    val footballSchedules = schedules.filter { it.team.teamId in footballMineIds }
    val teamCards = footballTeams.map { t ->
        val g = footballSchedules.firstOrNull { it.team.teamId == t.teamId }
        val tbl = tables[t.league]
        TeamCardData(
            team = t,
            row = tbl?.rows?.firstOrNull { it.id == t.teamId },
            leagueName = tbl?.name ?: t.leagueName,
            form = g?.past?.sortedByDescending { it.startMs }?.take(5)?.reversed()?.mapNotNull { resultFor(it, t.teamId) } ?: emptyList(),
            next = g?.let { (it.live + it.upcoming).minByOrNull { f -> f.startMs } },
            last = g?.past?.maxByOrNull { it.startMs },
        )
    }

    // ---- The billboard for the sport on show: one picture, its words, a countdown to what's next, and what OK does
    fun fixtureBillboard(f: SportsFixture, accent: Color): BillboardData {
        val live = f.status == FixtureStatus.LIVE
        val score = if (hideSportsScores || f.home?.score == null) null else "${f.home?.score} – ${f.away?.score ?: "0"}"
        val reminded = "fixture:${f.id}" in remindedIds
        val mineSide = listOfNotNull(f.home, f.away).firstOrNull { it.id in favTeamIds }
        val racing = f.isRacing()
        return BillboardData(
            key = "fx:${f.id}",
            // (your team's own picture for its matches; the competition's action photos for anyone else's)
            art = fanart(mineSide?.name) ?: leagueArt(f.league, kotlin.math.abs(f.id.hashCode())) ?: fanart(f.home?.name) ?: fanart(f.away?.name),
            kicker = listOfNotNull(f.leagueName.takeIf { it.isNotBlank() }, f.venue).joinToString("  ·  "),
            title = fixtureTitle(f),
            line = when (f.status) {
                FixtureStatus.LIVE -> listOfNotNull("Live", f.clock, score, f.ukChannelName, if (hasStream(f)) null else "not on your IPTV").joinToString("  ·  ")
                FixtureStatus.FINAL -> listOfNotNull(if (racing) "Finished" else "Full time", score).joinToString("  ·  ")
                else -> listOfNotNull(SimpleDateFormat("EEE d MMM", Locale.UK).format(Date(f.startMs)), timeLabel(f.startMs), f.ukChannelName).joinToString("  ·  ")
            },
            home = f.home, away = f.away, live = live,
            countdownTo = if (f.status == FixtureStatus.UPCOMING) f.startMs else null,
            countdownLabel = if (f.isRacing()) "Starts in" else "Kick-off in",
            primary = when {
                live && hasStream(f) -> "Watch live"
                f.status == FixtureStatus.UPCOMING -> if (reminded) "Reminder set" else "Remind me"
                racing -> null
                else -> "Match details"
            },
            primaryIcon = when { live && hasStream(f) -> Icons.Filled.PlayArrow; f.status == FixtureStatus.UPCOMING -> if (reminded) Icons.Filled.NotificationsActive else Icons.Filled.Notifications; else -> Icons.Filled.Info },
            onPrimary = { when { live && hasStream(f) -> store.playFixture(context, f); f.status == FixtureStatus.UPCOMING -> toggleReminder(f); else -> openMatch = f } },
            secondary = if (f.status != FixtureStatus.FINAL && !racing && f.home != null && (live && hasStream(f) || f.status == FixtureStatus.UPCOMING)) "Match details" else null,
            secondaryIcon = Icons.Filled.Info,
            onSecondary = { openMatch = f },
            accent = accent,
        )
    }
    fun tvBillboard(row: lumos.iptv.GuideChannelRow, p: lumos.iptv.EpgProgramme, zap: List<String>, art: String? = null) = BillboardData(
        key = "tv:${row.guideChannel.id}:${p.startMs}",
        art = p.iconUrl?.takeIf { it.isNotBlank() } ?: art,
        kicker = row.guideChannel.name,
        title = p.title,
        line = "Live  ·  until ${timeLabel(p.endMs)}",
        live = true,
        primary = "Watch live",
        onPrimary = { store.playGuideRow(context, row, zapChannelIds = zap) },
        accent = tintFor(p.title),
    )
    /** UFC's main event, the next (or live) slam, or golf's event this week: their own billboards */
    fun sportBillboard(): BillboardData? {
        val tvNow = tvShown.firstOrNull()
        val watch: (() -> Unit)? = tvNow?.let { (r, _) -> { store.playGuideRow(context, r, zapChannelIds = tvShown.mapNotNull { it.first.linkedChannel?.id }) } }
        return when (tile) {
            SportTile.UFC -> ufcCard?.let { c ->
                val m = c.bouts.first()
                val id = "ufc:${c.startMs}"
                val on = m.state == "LIVE" || c.bouts.any { it.state == "LIVE" }
                BillboardData(
                    key = "ufc:${c.name}",
                    art = leagueArt("ufc", 0),
                    kicker = listOfNotNull(c.name, c.startMs?.let { SimpleDateFormat("EEE d MMM", Locale.UK).format(Date(it)) }, c.venue).joinToString("  ·  "),
                    title = "${m.red.name.substringAfterLast(' ')} v ${m.blue.name.substringAfterLast(' ')}",
                    line = listOfNotNull(m.weight.takeIf { it.isNotBlank() }, listOfNotNull(m.red.record, m.blue.record).takeIf { it.size == 2 }?.joinToString(" v ")).joinToString("  ·  "),
                    live = on,
                    countdownTo = c.startMs?.takeIf { !on && it > nowMs },
                    countdownLabel = "The card starts in",
                    primary = when { on && watch != null -> "Watch live"; on -> null; id in remindedIds -> "Reminder set"; else -> "Remind me" },
                    primaryIcon = if (on) Icons.Filled.PlayArrow else if (id in remindedIds) Icons.Filled.NotificationsActive else Icons.Filled.Notifications,
                    onPrimary = { if (on) watch?.invoke() else c.startMs?.let { remindAt(id, c.name, it) } },
                    accent = Color(0xFFD20A0A),
                    cutouts = m.red.cutout to m.blue.cutout,
                )
            }
            SportTile.TENNIS -> tennis?.let { t ->
                val liveEvent = t.events.firstOrNull { it.state == "LIVE" }
                val slam = t.slams.firstOrNull { it.state == "LIVE" } ?: t.slams.firstOrNull { (it.startMs ?: 0L) > nowMs }
                when {
                    liveEvent != null -> BillboardData(
                        key = "tennis:${liveEvent.name}", art = leagueArt("atp", 1),
                        kicker = listOfNotNull(if (liveEvent.major) "Grand Slam" else "Tennis", spanLabel(liveEvent.startMs, liveEvent.endMs)).joinToString("  ·  "),
                        title = liveEvent.name,
                        line = listOfNotNull("On now", liveEvent.draws.firstOrNull()?.rounds?.lastOrNull { r -> r.matches.any { it.state != "FINAL" } }?.name).joinToString("  ·  "),
                        live = true,
                        primary = if (watch != null) "Watch live" else null, onPrimary = { watch?.invoke() }, accent = Color(0xFF2E7DD1),
                    )
                    slam != null -> {
                        val id = "slam:${slam.name}:${slam.startMs}"
                        BillboardData(
                            key = "slam:${slam.name}", art = leagueArt("atp", slam.name.length),
                            kicker = "Grand Slam  ·  ${spanLabel(slam.startMs, slam.endMs)}",
                            title = slam.name,
                            line = listOfNotNull(slam.lastMen, slam.lastWomen).takeIf { it.isNotEmpty() }?.let { "Holders: ${it.joinToString(" and ")}" },
                            countdownTo = slam.startMs?.takeIf { it > nowMs }, countdownLabel = "Starts in",
                            primary = if (id in remindedIds) "Reminder set" else "Remind me",
                            primaryIcon = if (id in remindedIds) Icons.Filled.NotificationsActive else Icons.Filled.Notifications,
                            onPrimary = { slam.startMs?.let { remindAt(id, slam.name, it) } },
                            accent = Color(0xFF2E7DD1),
                        )
                    }
                    else -> null
                }
            }
            SportTile.GOLF -> golf?.let { g ->
                val e = g.current ?: g.last ?: return@let null
                val lead = e.rows.firstOrNull()
                val id = "golf:${e.name}:${e.startMs}"
                BillboardData(
                    key = "golf:${e.name}", art = leagueArt("pga", e.name.length),
                    kicker = listOfNotNull("PGA Tour", spanLabel(e.startMs, e.endMs), e.course).joinToString("  ·  "),
                    title = e.name,
                    line = when {
                        e.state == "LIVE" && lead != null -> "Leader: ${lead.name} ${lead.toPar}"
                        e.state == "FINAL" && lead != null -> "Won by ${lead.name} at ${lead.toPar}"
                        else -> g.last?.rows?.firstOrNull()?.let { w -> "Last week: ${w.name} won " + (if (g.last.name.startsWith("The ")) g.last.name else "the ${g.last.name}") }
                    },
                    live = e.state == "LIVE",
                    countdownTo = e.startMs?.takeIf { e.state == "UPCOMING" && it > nowMs }, countdownLabel = "Tees off in",
                    primary = when { e.state == "LIVE" -> if (watch != null) "Watch live" else null; e.state == "UPCOMING" -> if (id in remindedIds) "Reminder set" else "Remind me"; else -> null },
                    primaryIcon = if (e.state == "LIVE") Icons.Filled.PlayArrow else if (id in remindedIds) Icons.Filled.NotificationsActive else Icons.Filled.Notifications,
                    onPrimary = { if (e.state == "LIVE") watch?.invoke() else e.startMs?.let { remindAt(id, e.name, it) } },
                    accent = Color(0xFF1F8A4C),
                )
            }
            else -> null
        }
    }
    val racingWords = Regex(when (series) { Series.F1 -> "formula|f1|grand prix|qualifying|motorsport"; Series.INDY -> "indycar|indy 500|indy car"; Series.NASCAR -> "nascar" }, RegexOption.IGNORE_CASE)
    val racingTv = tvRanked.filter { (row, p) -> racingWords.containsMatchIn(p.title + " " + row.guideChannel.name) }
    val favLive = footballSchedules.flatMap { it.live }.firstOrNull()
    val favNext = footballSchedules.flatMap { it.upcoming }.filter { it.startMs > nowMs - 3 * 3_600_000L }.minByOrNull { it.startMs }
    val bill: BillboardData? = when (tile) {
        SportTile.F1 -> {
            val w = weekend
            // (what's on Sky Sports F1 is only the race's own billboard while a session of the weekend is on: a replay of the
            // last race isn't "live now")
            val sessionOn = w != null && w.sessions.any { (_, at) -> nowMs in at..(at + 2 * 3_600_000L) }
            val top = if (series == Series.F1 && w != null && !sessionOn) null else racingTv.firstOrNull()
            if (series == Series.F1 && w != null) {
                val next = w.sessions.firstOrNull { it.second > nowMs } ?: w.sessions.lastOrNull()
                val raceId = "f1:${w.season}:${w.round}"
                val reminded = raceId in remindedIds
                BillboardData(
                    key = "f1:${w.season}:${w.round}:${top != null}",
                    art = leagueArt("f1", w.round ?: 0) ?: top?.second?.iconUrl?.takeIf { it.isNotBlank() } ?: w.thumb,
                    kicker = listOfNotNull("Formula 1", w.round?.let { "Round $it" }, "${w.locality}, ${w.country}").joinToString("  ·  "),
                    title = w.raceName,
                    line = if (top != null) "Live now on ${top.first.guideChannel.name}" else w.raceMs?.let { "Race  ·  ${SimpleDateFormat("EEE d MMM", Locale.UK).format(Date(it))}  ·  ${timeLabel(it)}" + (nextRace?.ukChannelName?.let { c -> "  ·  $c" } ?: "") },
                    live = top != null,
                    countdownTo = next?.second,
                    countdownLabel = next?.let { "${it.first} starts in" },
                    primary = if (top != null) "Watch live" else if (reminded) "Reminder set" else "Remind me",
                    primaryIcon = if (top != null) Icons.Filled.PlayArrow else if (reminded) Icons.Filled.NotificationsActive else Icons.Filled.Notifications,
                    onPrimary = {
                        if (top != null) store.playGuideRow(context, top.first, zapChannelIds = racingTv.mapNotNull { it.first.linkedChannel?.id })
                        else w.raceMs?.let { at ->
                            android.widget.Toast.makeText(context, if (reminded) "Reminder removed" else "We'll remind you before the ${w.raceName} starts", android.widget.Toast.LENGTH_SHORT).show()
                            LiveReminders.toggle(context, LiveReminders.Reminder(id = raceId, title = w.raceName, startMs = at, channelId = null, channelName = nextRace?.ukChannelName, fixtureId = nextRace?.id))
                        }
                    },
                    accent = Color(0xFFE10600),
                )
            } else {
                val shownF = nextRace ?: f1.filter { it.status == FixtureStatus.FINAL }.maxByOrNull { it.startMs }
                when {
                    top != null -> tvBillboard(top.first, top.second, racingTv.mapNotNull { it.first.linkedChannel?.id })
                    shownF != null -> fixtureBillboard(shownF.copy(home = null, away = null), Color(0xFFE10600))
                    else -> null
                }
            }
        }
        SportTile.FOOTBALL -> {
            val f = favLive ?: live.firstOrNull { sided(it) && hasStream(it) } ?: favNext ?: live.firstOrNull { sided(it) } ?: upcoming.firstOrNull { sided(it) }
            when {
                f != null && (f.status == FixtureStatus.LIVE || tvShown.isEmpty() || f == favNext) -> fixtureBillboard(f, tintFor(f.home?.name ?: f.name))
                tvShown.isNotEmpty() -> tvShown.first().let { (r, p) -> tvBillboard(r, p, tvShown.mapNotNull { it.first.linkedChannel?.id }) }
                f != null -> fixtureBillboard(f, tintFor(f.home?.name ?: f.name))
                else -> null
            }
        }
        SportTile.UFC, SportTile.TENNIS, SportTile.GOLF -> sportBillboard() ?: when {
            tvTop != null -> tvBillboard(tvTop.first, tvTop.second, tvShown.mapNotNull { it.first.linkedChannel?.id })
            heroEvent != null -> fixtureBillboard(heroEvent, tintFor(tile.label))
            else -> null
        }
        else -> when {
            billboardMatch != null && (billboardMatch.status == FixtureStatus.LIVE || tvTop == null) -> fixtureBillboard(billboardMatch, tintFor(billboardMatch.home?.name ?: billboardMatch.name))
            tvTop != null -> tvBillboard(tvTop.first, tvTop.second, tvShown.mapNotNull { it.first.linkedChannel?.id })
            billboardMatch != null -> fixtureBillboard(billboardMatch, tintFor(billboardMatch.home?.name ?: billboardMatch.name))
            heroEvent != null -> fixtureBillboard(heroEvent, tintFor(tile.label))
            else -> null
        }
    }
    // (the pictures wanted on this page: your teams', and the billboard's two sides')
    val artNames = remember(footballTeams, bill?.home?.name, bill?.away?.name) {
        (sportsDbLeague.values.map { "league:$it" } + footballTeams.map { it.name } + listOfNotNull(bill?.home?.name, bill?.away?.name)).distinct()
    }
    LaunchedEffect(artNames) {
        // (any the picture site turned away are asked for again a little later, a few times)
        repeat(4) { attempt ->
            val want = artNames.filter { it !in art }
            if (want.isEmpty()) return@LaunchedEffect
            art = art + runCatching { store.sportsArt(want) }.getOrDefault(emptyMap())
            delay(20_000L * (attempt + 1))
        }
    }
    // (UFC's card comes again shortly when its fighters' cut-outs weren't to be had the first time)
    LaunchedEffect(ufcCard?.bouts?.firstOrNull()?.red?.cutout == null && ufcCard != null) {
        if (ufcCard != null && ufcCard?.bouts?.firstOrNull()?.red?.cutout == null) { delay(130_000L); parseUfc(runCatching { store.sportsPageFresh("ufc") }.getOrNull())?.let { ufcCard = it } }
    }

    Box(Modifier.fillMaxSize().background(Dx.Background)) {
    SportsGlow(glow.first, glow.second)
    BillboardBackdrop(bill?.art, lists, page = tile.name + (if (tile == SportTile.F1) series.name else ""))
    // The whole page is one list of rows, as a film's page is: the sports along the top are the first row, and scroll away with
    // the rest; each row centres itself as the remote comes to it, in the page's one soft glide
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides SportsNoScroll,
        com.notdxbi.dxbi.tv.lumen.LocalPageScroller provides pageScroller,
    ) {
    LazyColumn(
        state = lists,
        modifier = Modifier.fillMaxSize().onGloballyPositioned { pageScroller.viewport = it },
        contentPadding = PaddingValues(bottom = 120.dp),
    ) {
        // (the page tag and the sports share the top line, as the menu and the clock do; they scroll away together)
        item(key = "pills") {
            // (the top line as a whole: Back on the page tag or a pill goes on to the menu)
            Row(Modifier.fillMaxWidth().height(66.dp).onFocusChanged { tilesFocused = it.hasFocus }) {
            LivePageTag(LiveSection.SPORTS, nav, compact = true)
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides com.notdxbi.dxbi.tv.lumen.rememberRowScrolling()) {
            LazyRow(
                state = tileRow,
                modifier = Modifier
                    .focusProperties {
                        onEnter = {
                            val idx = tiles.indexOf(tile)
                            if (requestedFocusDirection == androidx.compose.ui.focus.FocusDirection.Up &&
                                tileRow.layoutInfo.visibleItemsInfo.any { it.index == idx }
                            ) {
                                chosenTile.requestFocus()
                            }
                        }
                    }
                    // (Down from Motorsports goes into the page, not onto the series pills below the pill)
                    .onPreviewKeyEvent { ev ->
                        if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionDown && tile == SportTile.F1 && motorReady) runCatching { heroFocus.requestFocus() }.getOrDefault(false) else false
                    }
                    // (back up on the pills: the page goes to its top)
                    .onFocusChanged { if (it.hasFocus) pageScroller.toTop() }
                    .weight(1f).padding(top = 21.dp),
                contentPadding = PaddingValues(start = 12.dp, end = 130.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(tiles.size, key = { tiles[it].name }) { i ->
                    val t = tiles[i]
                    SportTileButton(
                        tile = t,
                        favourite = t.name in favouriteSports,
                        selected = tile == t,
                        onClick = { tile = t },
                        // (the page follows the remote along the pills: no second press to open a sport)
                        onFocus = { tile = t },
                        onLongClick = { toggleFavouriteSport(t) },
                        modifier = (if (i == 0) Modifier.focusRequester(contentFocusRequester) else Modifier)
                            .then(if (t == tile) Modifier.focusRequester(chosenTile) else Modifier)
                            .onPreviewKeyEvent { ev ->
                                if (i == 0 && ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft) { onMoveToRail(); true } else false
                            },
                    )
                }
            }
            }
            }
        }

        if (!feedReady || (tile == SportTile.F1 && (!motorReady || (series == Series.F1 && !weekendDone)))) {
            // (nothing until the feed, and for Formula 1 its extras, have come: then the page builds in from the top)
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().height(240.dp).padding(start = Dx.Gutter, top = 36.dp)) { Text("Loading…", fontSize = 14.sp, color = Color.White.copy(alpha = 0.4f)) }
            }
        } else if (tile == SportTile.F1) {
            item(key = "series") {
                // (the series along small pills, as the sports', and the page follows the remote along them)
                Row(
                    Modifier.padding(horizontal = Dx.Gutter, vertical = 6.dp)
                        // (back up into the series: the one being shown, not whichever is above the remote)
                        .focusProperties { onEnter = { if (requestedFocusDirection == androidx.compose.ui.focus.FocusDirection.Up) runCatching { seriesFocus.requestFocus() } } }
                        .focusGroup()
                        .onFocusChanged { if (it.hasFocus) pageScroller.toTop() }.loadIn(seen, "series"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Series.entries.forEach { sr -> PillButton(sr.label, selected = series == sr, onClick = { series = sr }, height = 34.dp, onFocus = { series = sr }, modifier = if (sr == series) Modifier.focusRequester(seriesFocus) else Modifier) }
                }
            }
            bill?.let { b ->
                item(key = "billboard") {
                    BigBillboard(b, Modifier.focusRequester(heroFocus).focusGroup().loadIn(seen, "billboard:${b.key}").centredWhenFocused())
                }
            }
            val w = weekend
            if (series == Series.F1 && w != null) {
                // (the weekend's times and the circuit side by side: information in one place, not a box each)
                item(key = "weekend") {
                    Row(Modifier.padding(horizontal = Dx.Gutter, vertical = 12.dp).fillMaxWidth().height(330.dp).loadIn(seen, "weekend").centredWhenFocused(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        WeekendPanel(w, nowMs, nextRace?.ukChannelName, Modifier.weight(1f).fillMaxHeight())
                        CircuitPanel(w, Modifier.weight(1.25f).fillMaxHeight())
                    }
                }
            }
            tvLiveSection(racingTv, nowMs, emptyList(), hideSportsScores, seen, { _, _ -> }) { row, _ -> store.playGuideRow(context, row, zapChannelIds = racingTv.mapNotNull { it.first.linkedChannel?.id }) }
            if (series == Series.F1) {
                // (the replays' cards wear that race's own picture from the TV guide when it's on there, else a Formula 1 photo)
                val round = rounds.firstOrNull()
                val roundArt = round?.let { r -> tvRanked.firstOrNull { (_, p) -> placeMatches(r.place, p.title) && !p.iconUrl.isNullOrBlank() }?.second?.iconUrl }
                replayCards(round, roundArt ?: leagueArt("f1", 1), seen) { r, sess -> playMotor(r, sess) }
            }
            fixtureSection("Live now", f1.filter { it.status == FixtureStatus.LIVE }, nowMs, hideSportsScores, remindedIds, seen) { store.playFixture(context, it) }
            fixtureSection("Races to come", f1.filter { it.status == FixtureStatus.UPCOMING }.drop(if (nextRace?.status == FixtureStatus.UPCOMING) 1 else 0).take(8), nowMs, hideSportsScores, remindedIds, seen) { toggleReminder(it) }
            standings?.let { st -> item(key = "standings") { F1Standings(st, favRacers, ::toggleRacer, Modifier.padding(horizontal = Dx.Gutter, vertical = 10.dp).loadIn(seen, "standings").centredWhenFocused()) } }
            if (f1.isEmpty() && w == null) item(key = "empty") {
                Text("No ${series.label} races in the next few days.", fontSize = 16.sp, color = Dx.Muted, modifier = Modifier.padding(start = Dx.Gutter, top = 24.dp))
            }
        } else {
            bill?.let { b ->
                item(key = "billboard") {
                    BigBillboard(b, Modifier.focusGroup().loadIn(seen, "billboard:${b.key}").centredWhenFocused())
                }
            }
            if (tile == SportTile.FOOTBALL) {
                // Football: your teams first (where they stand, their form, what's next), then what's on, then the tables
                teamsSection(teamCards, nowMs, seen) { c -> (c.next ?: c.last)?.let { openMatch = it } }
                panelSection("Live now", live.filter { sided(it) }, nowMs, hideSportsScores, favTeamIds, { }, seen) { openMatch = it }
                tvLiveSection(tvShown, nowMs, (fixtures + mine).distinctBy { it.id }, hideSportsScores, seen, { _, _ -> }) { row, _ -> store.playGuideRow(context, row, zapChannelIds = tvShown.mapNotNull { it.first.linkedChannel?.id }) }
                panelSection("Coming up", upcoming.filter { sided(it) }, nowMs, hideSportsScores, favTeamIds, { }, seen) { openMatch = it }
                tables["eng.1"]?.let { tableSection(it, footballMineIds, seen) }
                tableLeagues.filter { it != "eng.1" }.forEach { l -> tables[l]?.let { tableSection(it, footballMineIds, seen, window = true) } }
                panelSection("Results", past.filter { sided(it) }, nowMs, hideSportsScores, favTeamIds, { }, seen) { openMatch = it }
            } else if (tile == SportTile.UFC && ufcCard != null) {
                tvLiveSection(tvShown, nowMs, emptyList(), hideSportsScores, seen, { _, _ -> }) { row, _ -> store.playGuideRow(context, row, zapChannelIds = tvShown.mapNotNull { it.first.linkedChannel?.id }) }
                ufcSections(ufcCard!!, seen) { _ -> ufcCard?.startMs?.let { remindAt("ufc:$it", ufcCard!!.name, it) } }
            } else if (tile == SportTile.TENNIS && tennis != null) {
                val t = tennis!!
                tvLiveSection(tvShown, nowMs, emptyList(), hideSportsScores, seen, { _, _ -> }) { row, _ -> store.playGuideRow(context, row, zapChannelIds = tvShown.mapNotNull { it.first.linkedChannel?.id }) }
                // (the draw of what's on, else of the latest event, its later rounds as a bracket)
                (t.events.filter { it.state == "LIVE" }.ifEmpty { t.events.take(1) }).forEach { e -> e.draws.forEach { d -> drawSection(e, d, nowMs, seen) } }
                slamSection(t.slams, nowMs, seen)
                rankingsSection(t, seen)
            } else if (tile == SportTile.GOLF && golf != null) {
                val g = golf!!
                tvLiveSection(tvShown, nowMs, emptyList(), hideSportsScores, seen, { _, _ -> }) { row, _ -> store.playGuideRow(context, row, zapChannelIds = tvShown.mapNotNull { it.first.linkedChannel?.id }) }
                g.current?.takeIf { it.rows.isNotEmpty() }?.let { golfSection(if (it.state == "LIVE") "Leaderboard" else "Final leaderboard  ·  ${it.name}", it, seen) }
                g.last?.takeIf { g.current?.rows.isNullOrEmpty() }?.let { golfSection("Last week  ·  ${it.name}", it, seen, max = 20) }
            } else {
                // (every sport's page leads with what's live for it on the TV guide, the match feed or not)
                tvLiveSection(tvShown, nowMs, (fixtures + mine).distinctBy { it.id }, hideSportsScores, seen, { _, _ -> }) { row, _ -> store.playGuideRow(context, row, zapChannelIds = tvShown.mapNotNull { it.first.linkedChannel?.id }) }
                if (tile == SportTile.LIVE && tvShown.isEmpty() && live.isEmpty()) item(key = "nothing-live") {
                    Text("Nothing live right now. Coming up is under each sport.", fontSize = 16.sp, color = Dx.Muted, modifier = Modifier.padding(start = Dx.Gutter, top = 24.dp))
                }
                panelSection("Live now", live.filter { sided(it) }, nowMs, hideSportsScores, favTeamIds, { }, seen) { openMatch = it }
                if (tile != SportTile.LIVE) {
                    panelSection("Coming up", upcoming.filter { sided(it) }, nowMs, hideSportsScores, favTeamIds, { }, seen) { openMatch = it }
                    panelSection("Results", past.filter { sided(it) }, nowMs, hideSportsScores, favTeamIds, { }, seen) { openMatch = it }
                }
                eventSection("Events", events.filter { (tile != SportTile.LIVE || it.status == FixtureStatus.LIVE) && bill?.key != "fx:${it.id}" }.sortedBy { if (it.status == FixtureStatus.FINAL) Long.MAX_VALUE - it.startMs else it.startMs }.take(12), tintFor(tile.label), nowMs, seen) {
                    if (it.status == FixtureStatus.LIVE) store.playFixture(context, it) else toggleReminder(it)
                }
            }
            if (pool.isEmpty() && tile != SportTile.LIVE) item(key = "empty") {
                Text("No ${tile.label} matches coming up.", fontSize = 16.sp, color = Dx.Muted, modifier = Modifier.padding(start = Dx.Gutter, top = 24.dp))
            }
            // (at the foot of the page, out of the way of what's on)
            if (tile in teamSports) item(key = "add-team") {
                Row(Modifier.fillMaxWidth().padding(start = Dx.Gutter, end = Dx.Gutter, top = 28.dp).centredWhenFocused(), horizontalArrangement = Arrangement.Start) {
                    LiveChip(label = if (prefs.favouriteTeams.isEmpty()) "Add your teams" else "Add a team", selected = false, onClick = { addingTeam = true })
                }
            }
        }
    }
    }
    }

    openMatch?.let { f ->
        MatchPage(
            store = store,
            fixture = f,
            hideScores = hideSportsScores,
            reminded = "fixture:${f.id}" in remindedIds,
            onWatch = { openMatch = null; store.playFixture(context, f) },
            onRemind = { toggleReminder(f) },
            onBack = { openMatch = null },
        )
    }

    if (addingTeam) {
        AddSportsTeamDialog(
            store = store,
            followedTeams = prefs.favouriteTeams,
            onAddTeam = { store.addFavouriteTeam(it) },
            onRemoveTeam = { store.removeFavouriteTeam(it.teamId) },
            onClose = { addingTeam = false },
        )
    }
}

/** The page asks for no scrolling of its own when something in it is chosen: its rows centre themselves, in the page's glide */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private object SportsNoScroll : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> get() = com.notdxbi.dxbi.tv.lumen.PageGlide
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/** The sports whose matches are between teams (the others have events, or their own page) */
private val teamSports = setOf(SportTile.FOOTBALL, SportTile.NBA, SportTile.NHL)

/** What a page's parts do as they arrive: fade up and slide in from below, one after another, only the first time on a tab */
internal fun Modifier.loadIn(seen: MutableSet<String>, key: String, index: Int = 0): Modifier = composed {
    val first = remember { seen.add(key) }
    var shown by remember { mutableStateOf(!first) }
    LaunchedEffect(Unit) { if (!shown) { delay(70L * index.coerceAtMost(10)); shown = true } }
    val p by androidx.compose.animation.core.animateFloatAsState(if (shown) 1f else 0f, androidx.compose.animation.core.tween(560, easing = Dx.Ease), label = "load-in")
    val up = with(androidx.compose.ui.platform.LocalDensity.current) { 26.dp.toPx() }
    Modifier.graphicsLayer {
        alpha = p
        translationY = (1f - p) * up
        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
    }
}

private fun simpleNorm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

/** A pill: its words on faint glass, white when the remote is on it; a small star when it's a favourite */
@Composable
private fun PillButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    favourite: Boolean = false,
    height: androidx.compose.ui.unit.Dp = 40.dp,
    onLongClick: (() -> Unit)? = null,
    onFocus: (() -> Unit)? = null,
) {
    GlassTile(
        onClick = onClick,
        onLongClick = onLongClick,
        onFocus = onFocus,
        modifier = modifier.height(height),
        shape = RoundedCornerShape(50),
        fillColor = if (selected) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f),
        borderColor = Color.White.copy(alpha = 0.10f),
        sheen = false,
        instantFill = true,
        focusedScale = 1.06f,
    ) { _, ink ->
        Row(Modifier.padding(horizontal = if (height < 40.dp) 14.dp else 18.dp).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, fontSize = if (height < 40.dp) 13.sp else 14.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1)
            if (favourite) androidx.tv.material3.Icon(Icons.Filled.Star, contentDescription = "Favourite", tint = ink.copy(alpha = 0.7f), modifier = Modifier.size(11.dp))
        }
    }
}

/**
 * A sport along the top: a round icon button as the film page's (+, ✓, …), opening out to its name while the remote is
 * on it; the sport being shown keeps a brighter glass. Hold OK to favourite it.
 */
@Composable
private fun SportTileButton(
    tile: SportTile,
    favourite: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocus: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    androidx.tv.material3.Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.onFocusChanged {
            val f = it.isFocused || it.hasFocus
            if (f && !focused) onFocus?.invoke()
            focused = f
        },
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color.White.copy(alpha = 0.24f) else com.notdxbi.dxbi.tv.lumen.Glass.Fill,
            contentColor = Color.White,
            focusedContainerColor = Color(0xFFE8E8E8),
            focusedContentColor = Color.Black,
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        border = androidx.tv.material3.ClickableSurfaceDefaults.border(
            border = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp, com.notdxbi.dxbi.tv.lumen.Glass.Edge), shape = shape),
            focusedBorder = androidx.tv.material3.Border.None,
        ),
    ) {
        Row(
            Modifier.height(34.dp).widthIn(min = 34.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            androidx.tv.material3.Icon(tile.icon, contentDescription = tile.label, modifier = Modifier.size(18.dp))
            androidx.compose.animation.AnimatedVisibility(
                visible = focused,
                enter = com.notdxbi.dxbi.tv.lumen.ButtonMotion.open,
                exit = com.notdxbi.dxbi.tv.lumen.ButtonMotion.close,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tile.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 7.dp, end = if (favourite) 4.dp else 3.dp))
                    if (favourite) androidx.tv.material3.Icon(Icons.Filled.Star, contentDescription = "Favourite", modifier = Modifier.padding(end = 2.dp).size(11.dp))
                }
            }
        }
    }
}

/** A section's name, taking a row of its own as in the guide */
@Composable
internal fun SectionName(title: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(58.dp).padding(start = Dx.Gutter, bottom = 8.dp), contentAlignment = Alignment.BottomStart) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.55f), maxLines = 1)
    }
}

/** A cell as the guide's: faint glass on black with a fine edge, white and lifting a little when the remote is on it */
@Composable
private fun SportCell(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocus: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.RowScope.(focused: Boolean, ink: Color) -> Unit,
) {
    GlassTile(
        onClick = onClick,
        onFocus = onFocus,
        modifier = modifier.height(64.dp),
        shape = RoundedCornerShape(Dx.RCard),
        fillColor = Color.White.copy(alpha = 0.06f),
        borderColor = Color.White.copy(alpha = 0.09f),
        sheen = false,
        instantFill = true,
        focusedScale = Dx.FocusRow,
        maxGrow = 8.dp,
    ) { focused, ink ->
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            content(focused, ink)
        }
    }
}

internal fun androidx.compose.foundation.lazy.LazyListScope.eventSection(
    title: String,
    list: List<SportsFixture>,
    tint: Color,
    nowMs: Long,
    seen: MutableSet<String>,
    onPick: (SportsFixture) -> Unit,
) {
    if (list.isEmpty()) return
    item(key = "h:$title") { SectionName(title, Modifier.loadIn(seen, "h:$title")) }
    itemsIndexed(list.chunked(2), key = { _, row -> "ev:${row.first().id}" }) { i, row ->
        Row(Modifier.padding(horizontal = Dx.Gutter, vertical = 5.dp).fillMaxWidth().loadIn(seen, "ev:${row.first().id}", i).centredWhenFocused(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { f ->
                SportCell(onClick = { onPick(f) }, modifier = Modifier.weight(1f)) { _, ink ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(f.name.ifBlank { f.leagueName }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(f.leagueName.takeIf { it.isNotBlank() && it != f.name }, f.venue).joinToString("  ·  "), fontSize = 12.sp, color = ink.copy(alpha = 0.62f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (f.status == FixtureStatus.LIVE) Box(Modifier.size(7.dp).clip(CircleShape).background(LivePalette.LiveRed))
                            Text(when (f.status) { FixtureStatus.LIVE -> "  Live"; FixtureStatus.FINAL -> "Finished"; else -> dayLabel(f.startMs, nowMs) }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1)
                        }
                        if (f.status == FixtureStatus.UPCOMING) Text(timeLabel(f.startMs), style = TextStyle(fontSize = 12.sp, color = ink.copy(alpha = 0.62f), fontFeatureSettings = "tnum"))
                    }
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

/** Live sport on the TV guide right now: a cell per channel, two across; OK watches it */
internal fun androidx.compose.foundation.lazy.LazyListScope.tvLiveSection(
    list: List<Pair<lumos.iptv.GuideChannelRow, lumos.iptv.EpgProgramme>>,
    nowMs: Long,
    fixtures: List<SportsFixture>,
    hideScores: Boolean,
    seen: MutableSet<String>,
    onFocusTv: (lumos.iptv.GuideChannelRow, lumos.iptv.EpgProgramme) -> Unit,
    onPick: (lumos.iptv.GuideChannelRow, lumos.iptv.EpgProgramme) -> Unit,
) {
    if (list.isEmpty()) return
    item(key = "h:tv-live") { SectionName("On TV now", Modifier.loadIn(seen, "h:tv-live")) }
    itemsIndexed(list.chunked(2), key = { _, row -> "tv:${row.first().first.guideChannel.id}:${row.first().second.startMs}" }) { i, pair ->
        Row(Modifier.padding(horizontal = Dx.Gutter, vertical = 5.dp).fillMaxWidth().loadIn(seen, "tv:${pair.first().first.guideChannel.id}", i).centredWhenFocused(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            pair.forEach { (row, prog) ->
                // (the match the programme is, when the feed has it: its two crests, and its score and clock while it's live)
                val fx = remember(prog.title, fixtures) { fixtureForTitle(prog.title, fixtures, prog.startMs) }
                SportCell(onClick = { onPick(row, prog) }, modifier = Modifier.weight(1f), onFocus = { onFocusTv(row, prog) }) { focused, ink ->
                    val home = fx?.home; val away = fx?.away
                    if (fx != null && home != null && away != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
                            CrestBadge(logoUrl = home.logoUrl, name = home.name, size = 34.dp)
                            CrestBadge(logoUrl = away.logoUrl, name = away.name, size = 34.dp)
                        }
                    } else {
                        ChannelTile(logoUrl = row.logoUrl, name = row.guideChannel.name, width = 64.dp, height = 34.dp, plain = !focused)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(if (fx != null && home != null && away != null) fixtureTitle(fx) else prog.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(LivePalette.LiveRed))
                            val clock = fx?.takeIf { it.status == FixtureStatus.LIVE }?.let { it.clock ?: it.statusDetail?.takeIf { d -> d.isNotBlank() } }
                            Text(
                                if (clock != null) "$clock · ${row.guideChannel.name}" else "Live · until ${timeLabel(prog.endMs)}" + (if (fx != null) " · ${row.guideChannel.name}" else ""),
                                fontSize = 12.sp, color = ink.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (fx != null && fx.status == FixtureStatus.LIVE && !hideScores && home?.score != null) {
                        Text("${home.score} – ${fx.away?.score ?: "0"}", style = TextStyle(fontFamily = Condensed, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = ink, fontFeatureSettings = "tnum"), maxLines = 1)
                    } else if (fx != null) {
                        ChannelTile(logoUrl = row.logoUrl, name = row.guideChannel.name, width = 52.dp, height = 28.dp, plain = !focused)
                    }
                }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

/** Whether a live programme on the TV guide belongs to a sport's page: its words, or its channel's ("Sky Sports Golf") */
private fun tvForTile(tile: SportTile, row: lumos.iptv.GuideChannelRow, prog: lumos.iptv.EpgProgramme): Boolean {
    val words = when (tile) {
        SportTile.FOOTBALL -> "football|soccer|premier league|efl|championship|league one|league two|fa cup|carabao|champions league|europa|la liga|serie a|bundesliga|ligue 1|scottish|wsl|world cup|euro 20"
        SportTile.F1 -> "formula|f1|grand prix|motogp|nascar|indycar|motorsport|racing|rally|wrc|qualifying|superbike|speedway"
        SportTile.NBA -> "nba|basketball"
        SportTile.NHL -> "nhl|hockey"
        SportTile.UFC -> "ufc|mma|fight night|boxing|wrestling|wwe"
        SportTile.GOLF -> "golf|pga|lpga|dp world|masters|open championship|ryder|liv golf|hero world|tour championship"
        SportTile.TENNIS -> "tennis|atp|wta|wimbledon|roland garros|davis cup|us open|australian open"
        else -> return true
    }
    return Regex(words, RegexOption.IGNORE_CASE).containsMatchIn(prog.title + " " + row.guideChannel.name + " " + (prog.category ?: ""))
}

/** The match a TV programme ("EFL 26/27: Bristol City v Wrexham") is, when both sides' names are in its title and it kicks off near it */
private fun fixtureForTitle(title: String, list: List<SportsFixture>, startMs: Long): SportsFixture? {
    fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    val t = " ${norm(title)} "
    fun has(n: String?): Boolean { val x = n?.let(::norm).orEmpty(); return x.length >= 3 && t.contains(" $x ") }
    return list.firstOrNull { f ->
        val h = f.home; val a = f.away
        h != null && a != null && kotlin.math.abs(f.startMs - startMs) < 6 * 3_600_000L &&
            (has(h.name) || has(h.shortName)) && (has(a.name) || has(a.shortName))
    }
}

/** The chosen match, as the guide's billboard: both crests, the sides, the score or the kick-off, the channel and what OK does */
@Composable
private fun SportsBillboard(
    f: SportsFixture?, tileLabel: String, nowMs: Long, hideScores: Boolean, reminded: Boolean,
    modifier: Modifier = Modifier, tv: Pair<lumos.iptv.GuideChannelRow, lumos.iptv.EpgProgramme>? = null,
    onAction: () -> Unit = {}, artUrl: String? = null,
) {
    Box(modifier) {
    BillboardArt(f, tv, artUrl)
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        if (f == null && tv != null) {
            ChannelTile(logoUrl = tv.first.logoUrl, name = tv.first.guideChannel.name, width = 96.dp, height = 56.dp, plain = true)
            Column(Modifier.widthIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(tv.first.guideChannel.name, fontSize = 12.sp, color = Color.White.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tv.second.title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(LivePalette.LiveRed))
                    Text("Live · until ${timeLabel(tv.second.endMs)}", style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.9f), fontFeatureSettings = "tnum"))
                }
                Spacer(Modifier.height(6.dp))
                com.notdxbi.dxbi.tv.lumen.PlayButton("Watch live", onClick = onAction)
            }
            return@Row
        }
        if (f == null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tileLabel, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(dayLabel(nowMs, nowMs), fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))
            }
            return@Row
        }
        val home = f.home; val away = f.away
        val live = f.status == FixtureStatus.LIVE
        if (home != null || away != null) Row(horizontalArrangement = Arrangement.spacedBy((-10).dp)) {
            CrestBadge(logoUrl = home?.logoUrl, name = home?.name ?: f.name, size = 56.dp)
            CrestBadge(logoUrl = away?.logoUrl, name = away?.name ?: f.leagueName, size = 56.dp)
        }
        Column(Modifier.widthIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(listOfNotNull(f.leagueName.takeIf { it.isNotBlank() }, f.venue, f.ukChannelName).joinToString("  ·  "), fontSize = 12.sp, color = Color.White.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(fixtureTitle(f), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (live) Box(Modifier.size(8.dp).clip(CircleShape).background(LivePalette.LiveRed))
                val score = if (hideScores || home?.score == null) null else "${home.score} – ${away?.score ?: "0"}"
                Text(
                    when (f.status) {
                        FixtureStatus.LIVE -> listOfNotNull("Live", f.clock, score).joinToString(" · ")
                        FixtureStatus.FINAL -> listOfNotNull("Full time", score).joinToString(" · ")
                        else -> {
                            val left = (f.startMs - nowMs).coerceAtLeast(0L)
                            val inText = if (left >= 86_400_000L) "${left / 86_400_000L} d ${(left / 3_600_000L) % 24} h" else "${left / 3_600_000L} h ${(left / 60_000L) % 60} min"
                            "${dayLabel(f.startMs, nowMs)} · ${timeLabel(f.startMs)} · in $inText"
                        }
                    },
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.9f), fontFeatureSettings = "tnum"), maxLines = 1,
                )
            }
            Spacer(Modifier.height(6.dp))
            // (a race that's over has no match page: no button)
            if (home != null || f.status != FixtureStatus.FINAL) com.notdxbi.dxbi.tv.lumen.PlayButton(
                when {
                    live -> "Watch live"
                    f.status == FixtureStatus.UPCOMING -> if (reminded) "Reminder set" else "Remind me"
                    else -> "Match page"
                },
                onClick = onAction,
                icon = if (live || f.status == FixtureStatus.FINAL) androidx.compose.material.icons.Icons.Filled.PlayArrow else androidx.compose.material.icons.Icons.Filled.Notifications,
            )
        }
    }
    }
}

/** The picture on the right of the billboard: the programme's own image for a TV listing, or the two sides' big crests for a match, fading into the page */
@Composable
private fun BoxScope.BillboardArt(f: SportsFixture?, tv: Pair<lumos.iptv.GuideChannelRow, lumos.iptv.EpgProgramme>?, artUrl: String? = null) {
    val fade = Modifier
        .align(Alignment.CenterEnd).fillMaxWidth(0.6f).fillMaxHeight()
        .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.45f to Color.Black), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
            drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.2f to Color.Black, 0.8f to Color.Black, 1f to Color.Transparent), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
        }
    val art = artUrl?.takeIf { it.isNotBlank() } ?: if (f == null) tv?.second?.iconUrl?.takeIf { it.isNotBlank() } else null
    if (art != null) {
        coil3.compose.AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.Center, alpha = 0.8f, modifier = fade)
    } else if (f != null) {
        val a = f.home?.logoUrl?.takeIf { it.isNotBlank() }; val b = f.away?.logoUrl?.takeIf { it.isNotBlank() }
        if (a != null || b != null) Row(fade.padding(end = 24.dp, top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            listOfNotNull(a, b).forEach { u -> coil3.compose.AsyncImage(model = u, contentDescription = null, contentScale = ContentScale.Fit, alpha = 0.55f, modifier = Modifier.size(150.dp)) }
        }
    }
}

/** The soft glow behind the top of the page, in the colours of the chosen match's two sides */
@Composable
private fun SportsGlow(a: Color, b: Color) {
    val ca by androidx.compose.animation.animateColorAsState(a, androidx.compose.animation.core.tween(Dx.Slow), label = "glow-a")
    val cb by androidx.compose.animation.animateColorAsState(b, androidx.compose.animation.core.tween(Dx.Slow), label = "glow-b")
    Box(
        // (up under the page's tag strip too, so the glow has no edge at the top)
        Modifier.fillMaxWidth().height(396.dp).offset(y = (-66).dp).drawBehind {
            drawRect(Brush.radialGradient(0f to cb.copy(alpha = 0.5f), 1f to Color.Transparent, center = Offset(size.width * 0.8f, size.height * 0.12f), radius = size.width * 0.5f))
            drawRect(Brush.radialGradient(0f to ca.copy(alpha = 0.5f), 1f to Color.Transparent, center = Offset(size.width * 0.25f, size.height * 0.06f), radius = size.width * 0.5f))
            drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.25f), 0.4f to Color.Black.copy(alpha = 0.25f), 1f to Dx.Background))
        },
    )
}

/** The match the remote is on, big: both sides, the score or kick-off (with a countdown), the channel, and what OK does */
@Composable
private fun MatchDetail(f: SportsFixture, nowMs: Long, hideScores: Boolean, reminded: Boolean, modifier: Modifier = Modifier) {
    val home = f.home; val away = f.away
    val a = tintFor(home?.name ?: f.name); val b = tintFor(away?.name ?: f.leagueName)
    val shape = RoundedCornerShape(Dx.RCard + 4.dp)
    val live = f.status == FixtureStatus.LIVE
    Box(
        modifier.liveGlass(shape).clip(shape).drawBehind {
            drawRect(Brush.radialGradient(listOf(a.copy(alpha = 0.40f), Color.Transparent), center = Offset(0f, size.height / 2f), radius = size.width * 0.5f))
            drawRect(Brush.radialGradient(listOf(b.copy(alpha = 0.40f), Color.Transparent), center = Offset(size.width, size.height / 2f), radius = size.width * 0.5f))
        },
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(150.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CrestBadge(logoUrl = home?.logoUrl, name = home?.name ?: f.name, size = 52.dp)
                Text(home?.name ?: "", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(listOfNotNull(f.leagueName.takeIf { it.isNotBlank() }, f.venue).joinToString("  ·  "), fontSize = 12.sp, color = Color.White.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                when {
                    f.status == FixtureStatus.UPCOMING -> {
                        val left = (f.startMs - nowMs).coerceAtLeast(0L)
                        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            if (left >= 86_400_000L) CountUnit(left / 86_400_000L, "days")
                            CountUnit((left / 3_600_000L) % 24, "hours"); CountUnit((left / 60_000L) % 60, "minutes")
                        }
                        Text("${dayLabel(f.startMs, nowMs)} · ${timeLabel(f.startMs)}", style = TextStyle(fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f), fontFeatureSettings = "tnum"))
                    }
                    else -> {
                        val sc = if (hideScores || home?.score == null) "v" else "${home.score}  –  ${away?.score ?: "0"}"
                        Text(sc, style = TextStyle(fontFamily = Condensed, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color.White, fontFeatureSettings = "tnum"))
                        Text(if (live) "Live${f.clock?.let { " · $it" } ?: ""}" else "Full time", style = TextStyle(fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f), fontFeatureSettings = "tnum"))
                    }
                }
                Text(
                    (f.ukChannelName?.let { "On $it" } ?: "No channel listed") + "  ·  " + when {
                        live -> "OK to watch"
                        f.status == FixtureStatus.UPCOMING -> if (reminded) "Reminder set · OK to remove" else "OK to set a reminder"
                        else -> "OK for the match page"
                    },
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
                )
            }
            Column(Modifier.width(150.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CrestBadge(logoUrl = away?.logoUrl, name = away?.name ?: f.leagueName, size = 52.dp)
                Text(away?.name ?: "", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** An event with no two sides (a golf tournament, a race weekend): its name, where, and when, on a wash of the sport's colour */
@Composable
private fun EventPanel(f: SportsFixture, tint: Color, nowMs: Long, onPick: () -> Unit, modifier: Modifier = Modifier) {
    GlassTile(
        onClick = onPick,
        modifier = modifier.height(84.dp),
        shape = RoundedCornerShape(Dx.RCard + 4.dp),
        fillColor = Color(0xFF17171A),
        focusedScale = Dx.FocusRow,
    ) { focused, ink ->
        Box(Modifier.fillMaxSize()) {
            if (!focused) Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to tint.copy(alpha = 0.55f), 0.7f to Color.Transparent)))
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(f.name.ifBlank { f.leagueName }, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
                    Text(listOfNotNull(f.leagueName.takeIf { it.isNotBlank() && it != f.name }, f.venue).joinToString("  ·  "), fontSize = 12.sp, color = ink.copy(alpha = 0.62f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (f.status == FixtureStatus.LIVE) Box(Modifier.size(8.dp).clip(CircleShape).background(LivePalette.LiveRed))
                        Text(
                            when (f.status) { FixtureStatus.LIVE -> "  Live"; FixtureStatus.FINAL -> "Finished"; else -> dayLabel(f.startMs, nowMs) },
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1,
                        )
                    }
                    Text(f.statusDetail?.takeIf { it.isNotBlank() && f.status != FixtureStatus.UPCOMING } ?: (if (f.status == FixtureStatus.UPCOMING) timeLabel(f.startMs) else ""), style = TextStyle(fontSize = 12.sp, color = ink.copy(alpha = 0.62f), fontFeatureSettings = "tnum"), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
                }
            }
        }
    }
}

internal fun androidx.compose.foundation.lazy.LazyListScope.panelSection(
    title: String,
    list: List<SportsFixture>,
    nowMs: Long,
    hideScores: Boolean,
    favTeamIds: Set<String>,
    onFocusMatch: (SportsFixture) -> Unit,
    seen: MutableSet<String>,
    onPick: (SportsFixture) -> Unit,
) {
    if (list.isEmpty()) return
    item(key = "h:$title") { SectionName(title, Modifier.loadIn(seen, "h:$title")) }
    itemsIndexed(list.chunked(3), key = { _, row -> "$title:${row.first().id}" }) { i, row ->
        Row(Modifier.padding(horizontal = Dx.Gutter, vertical = 7.dp).fillMaxWidth().loadIn(seen, "$title:${row.first().id}", i).centredWhenFocused(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { f ->
                MatchPanel(f, nowMs, hideScores, mine = f.home?.id in favTeamIds || f.away?.id in favTeamIds, onPick = { onPick(f) }, modifier = Modifier.weight(1f), onFocus = { onFocusMatch(f) })
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** A match as a glass panel: each side's colour washing in from its edge, crests, the score or the kick-off */
@Composable
internal fun MatchPanel(f: SportsFixture, nowMs: Long, hideScores: Boolean, mine: Boolean, onPick: () -> Unit, modifier: Modifier = Modifier, onFocus: (() -> Unit)? = null) {
    val home = f.home; val away = f.away
    val a = tintFor(home?.name ?: f.name); val b = tintFor(away?.name ?: f.leagueName)
    GlassTile(
        onClick = onPick,
        onFocus = onFocus,
        modifier = modifier.height(84.dp),
        shape = RoundedCornerShape(Dx.RCard + 4.dp),
        fillColor = Color(0xFF17171A),
        sheen = true,
        focusedScale = Dx.FocusRow,
    ) { focused, ink ->
        Box(Modifier.fillMaxSize()) {
            // (each side's colour glows from its own corner, the two in opposite corners)
            if (!focused) Box(Modifier.fillMaxSize().drawBehind {
                drawRect(Brush.radialGradient(0f to a.copy(alpha = 0.6f), 1f to Color.Transparent, center = Offset(0f, 0f), radius = size.width * 0.6f))
                drawRect(Brush.radialGradient(0f to b.copy(alpha = 0.6f), 1f to Color.Transparent, center = Offset(size.width, size.height), radius = size.width * 0.6f))
            })
            if (mine) androidx.tv.material3.Icon(Icons.Filled.Star, contentDescription = "Your team", tint = ink.copy(alpha = 0.85f), modifier = Modifier.align(Alignment.TopStart).padding(top = 6.dp, start = 8.dp).size(12.dp))
            Row(Modifier.fillMaxSize().padding(horizontal = 14.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    CrestBadge(logoUrl = home?.logoUrl, name = home?.name ?: f.name, size = 38.dp)
                    Text(home?.shortName ?: home?.name ?: f.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
                val showScore = f.status != FixtureStatus.UPCOMING && !hideScores && home?.score != null
                Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (showScore) {
                        val big = ((home?.score?.length ?: 1).coerceAtLeast(away?.score?.length ?: 1)) >= 3
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (big) 8.dp else 12.dp)) {
                            Text("${home?.score}", softWrap = false, maxLines = 1, style = TextStyle(fontFamily = Condensed, fontSize = if (big) 28.sp else 32.sp, fontWeight = FontWeight.Bold, color = if (away?.winner == true) ink.copy(alpha = 0.5f) else ink, fontFeatureSettings = "tnum"))
                            Text("${away?.score ?: "0"}", softWrap = false, maxLines = 1, style = TextStyle(fontFamily = Condensed, fontSize = if (big) 28.sp else 32.sp, fontWeight = FontWeight.Bold, color = if (home?.winner == true) ink.copy(alpha = 0.5f) else ink, fontFeatureSettings = "tnum"))
                        }
                    } else {
                        Text(if (f.status == FixtureStatus.UPCOMING) timeLabel(f.startMs) else "v", style = TextStyle(fontFamily = Condensed, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = ink, fontFeatureSettings = "tnum"))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (f.status == FixtureStatus.LIVE) Box(Modifier.size(8.dp).clip(CircleShape).background(LivePalette.LiveRed))
                        Text(
                            when (f.status) {
                                FixtureStatus.LIVE -> (if (f.clock != null) "  ${f.clock}" else "  Live")
                                FixtureStatus.FINAL -> "Full time"
                                else -> dayLabel(f.startMs, nowMs)
                            },
                            style = TextStyle(fontSize = 13.sp, color = ink.copy(alpha = 0.7f), fontFeatureSettings = "tnum"), maxLines = 1,
                        )
                    }
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    CrestBadge(logoUrl = away?.logoUrl, name = away?.name ?: f.leagueName, size = 38.dp)
                    Text(away?.shortName ?: away?.name ?: "", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

/** The latest weekend's sessions in the replay addon, to play; only what the addon has */
@Composable
private fun ReplaysPanel(round: LiveTvStore.MotorRound?, onPick: (LiveTvStore.MotorRound, LiveTvStore.MotorSession) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(Dx.RCard + 4.dp)).background(Color.White.copy(alpha = 0.05f)).border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(Dx.RCard + 4.dp)).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (round != null) "Replays · ${round.place}" else "Replays", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(start = 6.dp, bottom = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (round == null) Text("No replays yet.", fontSize = 13.sp, color = Dx.Muted, modifier = Modifier.padding(start = 6.dp))
        round?.sessions?.take(4)?.forEach { sess ->
            GlassTile(
                onClick = { onPick(round, sess) },
                modifier = Modifier.fillMaxWidth().height(32.dp),
                shape = RoundedCornerShape(Dx.RCard),
                // (a row inside the panel: no box of its own until the remote is on it)
                fillColor = Color.Transparent,
                borderColor = Color.Transparent,
                focusedScale = Dx.FocusRow,
            ) { _, ink ->
                Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.tv.material3.Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
                    Text(sess.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** Whether a replay addon's place ("Las Vegas", "Mexican", "United States") is the race named like [text] */
private fun placeMatches(place: String, text: String): Boolean {
    fun norm(x: String) = java.text.Normalizer.normalize(x.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    val words = norm(text).split(Regex("[^a-z]+")).filter { it.length >= 4 }
    return norm(place).split(Regex("[^a-z]+")).filter { it.length >= 4 }.any { p -> words.any { w -> w.startsWith(p.take(4)) || p.startsWith(w.take(4)) } }
}

/** The next (or live) race: its name and place, the countdown, and what OK does */
@Composable
private fun RaceHero(fixture: SportsFixture, nowMs: Long, reminded: Boolean, onWatch: () -> Unit, onRemind: () -> Unit, modifier: Modifier = Modifier, wash: Color = Color(0xFFE10600), canReplay: Boolean = true) {
    val live = fixture.status == FixtureStatus.LIVE
    val finished = fixture.status == FixtureStatus.FINAL
    val shape = RoundedCornerShape(Dx.RCard + 4.dp)
    Box(
        modifier.clip(shape).background(Color.White.copy(alpha = 0.05f)).border(1.dp, Color.White.copy(alpha = 0.09f), shape).drawBehind {
            drawRect(Brush.radialGradient(listOf(wash.copy(alpha = 0.42f), Color.Transparent), center = Offset(size.width, 0f), radius = size.width * 0.8f))
            drawRect(Brush.radialGradient(listOf(wash.copy(alpha = 0.16f), Color.Transparent), center = Offset(0f, size.height), radius = size.width * 0.5f))
        },
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (live) "On now" else if (finished) "Latest result" else "Next up", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.7f))
                Text(fixture.name.ifBlank { fixture.leagueName }, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 28.sp)
                Text(listOfNotNull(fixture.venue, dayLabel(fixture.startMs, nowMs) + " " + timeLabel(fixture.startMs), fixture.ukChannelName).joinToString("  ·  "), style = TextStyle(fontSize = 13.sp, color = Dx.Muted, fontFeatureSettings = "tnum"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (live) LiveChip(label = "Watch", selected = true, onClick = onWatch)
                    else if (finished) { if (canReplay) LiveChip(label = "Watch replay", selected = true, onClick = onWatch) }
                    else LiveChip(label = if (reminded) "Reminder set" else "Remind me", selected = reminded, onClick = onRemind)
                }
            }
            if (!live && !finished) {
                val left = (fixture.startMs - nowMs).coerceAtLeast(0L)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CountUnit(left / 86_400_000L, "days"); CountUnit((left / 3_600_000L) % 24, "hours"); CountUnit((left / 60_000L) % 60, "minutes")
                }
            }
        }
    }
}

internal fun androidx.compose.foundation.lazy.LazyListScope.fixtureSection(
    title: String,
    list: List<SportsFixture>,
    nowMs: Long,
    hideScores: Boolean,
    remindedIds: Set<String>,
    seen: MutableSet<String>,
    onPick: (SportsFixture) -> Unit,
) {
    if (list.isEmpty()) return
    item(key = "h:$title") { SectionName(title, Modifier.loadIn(seen, "h:$title")) }
    itemsIndexed(list, key = { _, it -> "$title:${it.id}" }) { i, f ->
        FixtureRow(f, nowMs, hideScores, reminded = "fixture:${f.id}" in remindedIds, onPick = { onPick(f) }, modifier = Modifier.loadIn(seen, "$title:${f.id}", i))
    }
}


@Composable
private fun CountUnit(value: Long, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("%02d".format(value), style = TextStyle(fontFamily = Condensed, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White, fontFeatureSettings = "tnum"))
        Text(unit, fontSize = 12.sp, color = Dx.Muted)
    }
}

/** A plain row, as the season calendar: date · the two sides · the channel · what OK does */
@Composable
private fun FixtureRow(f: SportsFixture, nowMs: Long, hideScores: Boolean, reminded: Boolean, onPick: () -> Unit, modifier: Modifier = Modifier) {
    // (a race has no two sides and no match page: its name, and Finished rather than Full time)
    val racing = f.isRacing()
    GlassTile(
        onClick = onPick,
        modifier = modifier.padding(horizontal = Dx.Gutter, vertical = 5.dp).fillMaxWidth().height(58.dp).centredWhenFocused(),
        shape = RoundedCornerShape(Dx.RCard),
        // (a cell as the guide's: faint glass, white when the remote is on it)
        fillColor = Color.White.copy(alpha = 0.06f),
        borderColor = Color.White.copy(alpha = 0.09f),
        sheen = false,
        instantFill = true,
        maxGrow = 8.dp,
        focusedScale = Dx.FocusRow,
    ) { focused, ink ->
        val muted = ink.copy(alpha = if (focused) 0.6f else 0.55f)
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(150.dp)) {
                Text(dayLabel(f.startMs, nowMs), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1)
                Text(
                    when (f.status) { FixtureStatus.LIVE -> "Live" + (f.clock?.let { " · $it" } ?: ""); FixtureStatus.FINAL -> if (racing) "Finished" else "Full time"; else -> timeLabel(f.startMs) },
                    style = TextStyle(fontSize = 12.sp, color = if (f.status == FixtureStatus.LIVE && !focused) LivePalette.LiveRed else muted, fontFeatureSettings = "tnum"), maxLines = 1,
                )
            }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val home = f.home; val away = f.away
                if (!racing && home != null && away != null && home.name.isNotBlank() && away.name.isNotBlank()) {
                    CrestBadge(logoUrl = home.logoUrl, name = home.name, size = 32.dp)
                    Text(home.shortName ?: home.name, fontSize = 15.sp, fontWeight = if (home.winner) FontWeight.Bold else FontWeight.Medium, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    val showScore = f.status != FixtureStatus.UPCOMING && !hideScores && home.score != null
                    Text(
                        if (showScore) "${home.score} – ${away.score ?: "0"}" else "v",
                        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (showScore) ink else muted, fontFeatureSettings = "tnum"),
                        modifier = Modifier.widthIn(min = 54.dp), textAlign = TextAlign.Center,
                    )
                    Text(away.shortName ?: away.name, fontSize = 15.sp, fontWeight = if (away.winner) FontWeight.Bold else FontWeight.Medium, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    CrestBadge(logoUrl = away.logoUrl, name = away.name, size = 32.dp)
                } else {
                    Column {
                        Text(
                            listOfNotNull(f.name, f.shortName).firstOrNull { it.isNotBlank() } ?: f.leagueName,
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        f.statusDetail?.takeIf { it.startsWith("Won by") && !hideScores }?.let { Text(it, fontSize = 12.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
            // (sized to their words, at most so wide: in the half-width Formula 1 list fixed widths left the name none)
            Text(f.ukChannelName ?: f.leagueName, fontSize = 13.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp).widthIn(max = 220.dp))
            val action = when (f.status) {
                FixtureStatus.LIVE -> if (racing) "Watch" else "Match"
                FixtureStatus.UPCOMING -> if (reminded) "Reminder set" else "Remind me"
                else -> if (racing) (if (f.status == FixtureStatus.FINAL) "Replay" else "") else "Match details"
            }
            if (action.isNotEmpty()) Text(
                action,
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (focused) ink else Color.White.copy(alpha = 0.75f),
                textAlign = TextAlign.End, modifier = Modifier.padding(start = 16.dp).widthIn(min = 100.dp),
            )
        }
    }
}

internal fun fixtureTitle(f: SportsFixture): String {
    val h = f.home; val a = f.away
    return if (h != null && a != null) "${h.shortName ?: h.name} v ${a.shortName ?: a.name}" else f.shortName ?: f.name
}

/** A match's time on the 24-hour clock, in the TV's own time zone (the times come in as UTC) */
internal fun timeLabel(ms: Long): String = SimpleDateFormat("HH:mm", Locale.UK).format(Date(ms))

internal fun dayLabel(ms: Long, nowMs: Long): String {
    val day = SimpleDateFormat("yyyyMMdd", Locale.UK)
    return when (day.format(Date(ms))) {
        day.format(Date(nowMs)) -> "Today"
        day.format(Date(nowMs + 86_400_000L)) -> "Tomorrow"
        day.format(Date(nowMs - 86_400_000L)) -> "Yesterday"
        else -> SimpleDateFormat("EEE d MMM", Locale.UK).format(Date(ms))
    }
}


/** F1 team colours, by name */
private fun teamColour(team: String): Color = when {
    team.contains("mercedes", true) -> Color(0xFF27F4D2)
    team.contains("red bull", true) -> Color(0xFF3671C6)
    team.contains("racing bulls", true) || team.contains("rb", true) && team.length <= 3 || team.contains("alphatauri", true) -> Color(0xFF6692FF)
    team.contains("mclaren", true) -> Color(0xFFFF8000)
    team.contains("ferrari", true) -> Color(0xFFE80020)
    team.contains("aston", true) -> Color(0xFF229971)
    team.contains("alpine", true) -> Color(0xFF0093CC)
    team.contains("williams", true) -> Color(0xFF64C4FF)
    team.contains("haas", true) -> Color(0xFFB6BABD)
    team.contains("audi", true) || team.contains("sauber", true) -> Color(0xFF52E252)
    team.contains("cadillac", true) -> Color(0xFFC0C0C0)
    else -> Color(0xFF8A8D93)
}

/** The current grid, by surname: which team a driver races for */
private val DRIVER_TEAMS = mapOf(
    "antonelli" to "Mercedes", "russell" to "Mercedes", "verstappen" to "Red Bull", "hadjar" to "Red Bull",
    "lawson" to "Racing Bulls", "lindblad" to "Racing Bulls", "norris" to "McLaren", "piastri" to "McLaren",
    "leclerc" to "Ferrari", "hamilton" to "Ferrari", "alonso" to "Aston Martin", "stroll" to "Aston Martin",
    "gasly" to "Alpine", "colapinto" to "Alpine", "albon" to "Williams", "sainz" to "Williams",
    "ocon" to "Haas", "bearman" to "Haas", "hulkenberg" to "Audi", "hülkenberg" to "Audi", "bortoleto" to "Audi",
    "perez" to "Cadillac", "pérez" to "Cadillac", "bottas" to "Cadillac", "tsunoda" to "Red Bull",
)

/** A racing series' championship: drivers and constructors side by side, each row in its team's colour; OK favourites one */
@Composable
internal fun F1Standings(json: org.json.JSONObject, favourites: Set<String>, onToggle: (String) -> Unit, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        listOf("drivers" to "Drivers", "constructors" to "Constructors").forEach { (key, title) ->
            val rows = json.optJSONArray(key) ?: return@forEach
            if (rows.length() == 0) return@forEach
            val shape = RoundedCornerShape(Dx.RCard + 4.dp)
            val top = rows.getJSONObject(0).optString("points").toFloatOrNull() ?: 1f
            Column(
                Modifier.weight(1f).clip(shape).background(Color.White.copy(alpha = 0.05f))
                    // (a dark glow, as the mock-up's: a faint light from the top edge)
                    .drawBehind { drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.07f), Color.Transparent), center = Offset(size.width / 2f, 0f), radius = size.width * 0.7f)) }
                    .border(1.dp, Color.White.copy(alpha = 0.09f), shape)
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
                    Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                    Text("Points", fontSize = 12.sp, color = Dx.Muted)
                }
                for (i in 0 until minOf(rows.length(), 6)) {
                    val r = rows.getJSONObject(i)
                    val name = r.optString("name")
                    val team = if (key == "drivers") DRIVER_TEAMS[name.substringAfterLast(' ').lowercase()] ?: "" else name
                    val colour = teamColour(team)
                    val pts = r.optString("points")
                    val frac = ((pts.toFloatOrNull() ?: 0f) / top).coerceIn(0f, 1f)
                    val fav = name in favourites
                    GlassTile(
                        onClick = { onToggle(name) },
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        shape = RoundedCornerShape(Dx.RCard),
                        fillColor = if (fav) Color.White.copy(alpha = 0.08f) else Color.Transparent,
                        borderColor = Color.Transparent,
                        sheen = false,
                        focusedScale = Dx.FocusRow,
                    ) { _, ink ->
                        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(r.optString("rank"), style = TextStyle(fontFamily = Condensed, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ink.copy(alpha = 0.6f), fontFeatureSettings = "tnum"), modifier = Modifier.width(26.dp))
                            Box(Modifier.width(4.dp).height(24.dp).clip(RoundedCornerShape(2.dp)).background(colour))
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    if (fav) androidx.tv.material3.Icon(Icons.Filled.Star, contentDescription = "Favourite", tint = ink.copy(alpha = 0.85f), modifier = Modifier.padding(start = 6.dp).size(12.dp))
                                }
                                if (key == "drivers" && team.isNotEmpty()) Text(team, fontSize = 12.sp, color = ink.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                else Box(Modifier.padding(top = 3.dp).fillMaxWidth(frac * 0.9f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(colour.copy(alpha = 0.8f)))
                            }
                            Text(pts, style = TextStyle(fontFamily = Condensed, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = ink, fontFeatureSettings = "tnum"))
                        }
                    }
                }
            }
        }
    }
}

/** How good a TV channel's copy of a programme is: one that plays first, then 4K, full HD, HD, the rest */
private fun qualityScore(row: lumos.iptv.GuideChannelRow): Int {
    val q = (row.qualityBadge ?: "").uppercase()
    return (if (row.isPlayable) 100 else 0) + when {
        "4K" in q || "UHD" in q -> 40
        "FHD" in q || "1080" in q -> 30
        "HD" in q || "720" in q -> 20
        else -> 10
    }
}
