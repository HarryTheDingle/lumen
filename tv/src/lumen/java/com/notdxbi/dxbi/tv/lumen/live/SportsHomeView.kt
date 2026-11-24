package com.notdxbi.dxbi.tv.lumen.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Brush
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
import kotlinx.coroutines.delay
import lumos.iptv.FixtureStatus
import lumos.iptv.SportsFixture
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The racing series on the Formula 1 page: ESPN's league code, and the Worker's sport name */
private enum class Series(val label: String, val code: String, val sport: String) {
    F1("Formula 1", "f1", "f1"), INDY("IndyCar", "irl", "indycar"), NASCAR("NASCAR", "nascar-premier", "nascar")
}

private fun SportsFixture.isRacing() =
    sport.contains("racing", true) || league.equals("f1", true) || league.equals("irl", true) ||
        league.startsWith("nascar", true) || leagueName.contains("formula", true)

/** A sport's tile: the sport, its words, its icon. [ALL] is the whole hub (every sport's matches) */
internal enum class SportTile(val label: String, val filter: SportFilter?, val icon: ImageVector) {
    FOOTBALL("Football", SportFilter.FOOTBALL, Icons.Filled.SportsSoccer),
    F1("Motorsports", SportFilter.F1, Icons.Filled.SportsMotorsports),
    NFL("NFL", SportFilter.NFL, Icons.Filled.SportsFootball),
    NBA("NBA", SportFilter.NBA, Icons.Filled.SportsBasketball),
    NHL("NHL", SportFilter.NHL, Icons.Filled.SportsHockey),
    MLB("MLB", SportFilter.MLB, Icons.Filled.SportsBaseball),
    UFC("UFC", SportFilter.UFC, Icons.Filled.SportsMma),
    GOLF("Golf", SportFilter.GOLF, Icons.Filled.SportsGolf),
    TENNIS("Tennis", SportFilter.TENNIS, Icons.Filled.SportsTennis),
}

/** The condensed face for scores and countdowns, as Apple Sports' numerals */
private val Condensed = androidx.compose.ui.text.font.FontFamily(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD))

/** A team's wash on a match panel: from its name, steady between runs */
private val PANEL_TINTS = listOf(
    Color(0xFF1B3A8A), Color(0xFF9A1B25), Color(0xFF1F6B3A), Color(0xFF6A3A7A),
    Color(0xFFB36A12), Color(0xFF0F6F7A), Color(0xFF7A1F5A), Color(0xFF3A4A5A),
)
private fun tintFor(name: String): Color = PANEL_TINTS[kotlin.math.abs(name.hashCode()) % PANEL_TINTS.size]

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
) {
    val context = LocalContext.current
    val prefs by store.prefs.collectAsState()
    val schedules by store.teamSchedules.collectAsState()
    val fixtures by store.sportsFixtures.collectAsState()
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
                ?: SportTile.entries.first { it.name in favouriteSports },
        )
    }
    LaunchedEffect(Unit) { sportPrefs.edit().remove("start").apply() }
    val tiles = remember(favouriteSports) {
        SportTile.entries.sortedByDescending { it.name in favouriteSports }
    }

    LaunchedEffect(Unit) {
        LiveReminders.load(context)
        store.refreshSavedTeamSchedules()
        while (true) {
            delay(30_000L)
            nowMs = System.currentTimeMillis()
        }
    }
    LaunchedEffect(tile, series) {
        if (tile == SportTile.F1) {
            standings = null
            comingRaces = emptyList()
            store.loadSportsFixtures(sport = series.sport)
            scope.launch { comingRaces = store.upcomingRaces(series.sport, series.code) }
            if (series == Series.F1) scope.launch { rounds = store.motorReplays() }
            standings = store.f1Standings(series.code)
        } else {
            store.loadSportsFixtures()
        }
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

    // (Up from a sport's page lands back on that sport's tile, not on whichever tile is above the remote)
    val chosenTile = remember { FocusRequester() }
    val tileRow = androidx.compose.foundation.lazy.rememberLazyListState()
    Column(Modifier.fillMaxSize().background(Dx.Background)) {
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
                .background(Dx.Background)
                .padding(top = 14.dp)
                // (back up on the tiles: the page goes to its top)
                .onFocusChanged { if (it.hasFocus) scope.launch { lists.animateScrollToItem(0) } },
            contentPadding = PaddingValues(horizontal = Dx.Gutter, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(tiles.size, key = { tiles[it].name }) { i ->
                val t = tiles[i]
                SportTileButton(
                    tile = t,
                    favourite = t.name in favouriteSports,
                    selected = tile == t,
                    onClick = { tile = t },
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

        when (tile) {
            SportTile.F1 -> {
                val f1 = remember(fixtures, series) {
                    fixtures.filter { it.isRacing() && it.league.equals(series.code, true) }.sortedBy { it.startMs }
                }
                val next = f1.firstOrNull { it.status == FixtureStatus.LIVE } ?: (f1.filter { it.status == FixtureStatus.UPCOMING } + comingRaces).distinctBy { it.id }.minByOrNull { it.startMs }
                LazyColumn(state = lists, contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp)) {
                    item(key = "series") {
                        Row(Modifier.padding(horizontal = Dx.Gutter, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Series.entries.forEach { sr -> LiveChip(label = sr.label, selected = series == sr, onClick = { series = sr }) }
                        }
                    }
                    item(key = "hero") {
                        val done = f1.filter { it.status == FixtureStatus.FINAL }.sortedByDescending { it.startMs }
                        val shown = next ?: done.firstOrNull()
                        // (the replay addon's weekend for the one shown: replays are only offered when it has them)
                        fun roundFor(f: SportsFixture?) = if (series != Series.F1) null else f?.let { x -> rounds.firstOrNull { placeMatches(it.place, x.name + " " + (x.venue ?: "")) } }
                        val shownRound = if (shown?.status == FixtureStatus.FINAL) roundFor(shown) else null
                        val replayRound = if (series == Series.F1) shownRound ?: rounds.firstOrNull() else null
                        Row(Modifier.padding(horizontal = Dx.Gutter, vertical = 8.dp).fillMaxWidth().height(184.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            shown?.let { n ->
                                val race = shownRound?.sessions?.firstOrNull { it.label == "Race" } ?: shownRound?.sessions?.firstOrNull()
                                RaceHero(
                                    fixture = n, nowMs = nowMs, reminded = "fixture:${n.id}" in remindedIds,
                                    wash = Color(0xFFE10600),
                                    canReplay = series == Series.F1 && race != null,
                                    onWatch = {
                                        if (n.status == FixtureStatus.FINAL) { if (race != null && shownRound != null) playMotor(shownRound, race) }
                                        else store.playFixture(context, n)
                                    },
                                    onRemind = { toggleReminder(n) },
                                    modifier = Modifier.weight(1.3f).fillMaxHeight(),
                                )
                            }
                            // (replays come from the Formula 1 addon only: the other series have none until there is a source)
                            if (series == Series.F1) ReplaysPanel(replayRound, onPick = { r, sess -> playMotor(r, sess) }, modifier = Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                    standings?.let { st -> item(key = "standings") { F1Standings(st, favRacers, ::toggleRacer, Modifier.padding(horizontal = Dx.Gutter, vertical = 8.dp)) } }
                    fixtureSection("Live now", f1.filter { it.status == FixtureStatus.LIVE }, nowMs, hideSportsScores, remindedIds) { store.playFixture(context, it) }
                    fixtureSection("Coming up", f1.filter { it.status == FixtureStatus.UPCOMING }.drop(if (next?.status == FixtureStatus.UPCOMING) 1 else 0).take(8), nowMs, hideSportsScores, remindedIds) { toggleReminder(it) }
                    if (f1.isEmpty()) item(key = "empty") {
                        Text("No ${series.label} races in the next few days.", fontSize = 16.sp, color = Dx.Muted, modifier = Modifier.padding(start = Dx.Gutter, top = 24.dp))
                    }
                }
            }
            else -> {
                val mine = remember(schedules) { schedules.flatMap { it.live + it.upcoming + it.past } }
                val sportFilter = tile.filter
                val pool = remember(fixtures, mine, tile) {
                    (fixtures + mine).distinctBy { it.id }.filter { sportFilter == null || matchesSport(it, sportFilter) }
                }
                fun isMine(f: SportsFixture) = f.home?.id in favTeamIds || f.away?.id in favTeamIds
                val live = pool.filter { it.status == FixtureStatus.LIVE }.sortedWith(compareBy({ !isMine(it) }, { it.startMs }))
                val upcoming = pool.filter { it.status == FixtureStatus.UPCOMING }.sortedWith(compareBy({ !isMine(it) }, { it.startMs })).take(12)
                val past = pool.filter { it.status == FixtureStatus.FINAL }.sortedWith(compareBy({ !isMine(it) }, { -it.startMs })).take(10)
                val headline: SportsFixture? = null
                // (golf and the like have no two sides: an event, not a match)
                fun sided(f: SportsFixture) = !f.home?.name.isNullOrBlank() && !f.away?.name.isNullOrBlank()
                val events = pool.filterNot { sided(it) }
                val heroEvent = events.firstOrNull { it.status == FixtureStatus.LIVE } ?: events.filter { it.status == FixtureStatus.UPCOMING }.minByOrNull { it.startMs }
                    ?: events.filter { it.status == FixtureStatus.FINAL }.maxByOrNull { it.startMs }
                val detailFor = focusedMatch?.takeIf { f -> pool.any { it.id == f.id } } ?: (live + upcoming).firstOrNull { sided(it) }
                Column(Modifier.fillMaxSize()) {
                    // (pinned: it stays while the cards scroll beneath)
                    detailFor?.let { d ->
                        MatchDetail(d, nowMs, hideSportsScores, reminded = "fixture:${d.id}" in remindedIds, modifier = Modifier.padding(horizontal = Dx.Gutter, vertical = 8.dp).fillMaxWidth().height(138.dp))
                    }
                                    LazyColumn(Modifier.weight(1f), state = lists, contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp)) {
                    item(key = "title") {
                        Row(Modifier.fillMaxWidth().padding(start = Dx.Gutter, end = Dx.Gutter, top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("${tile.label} · ${dayLabel(nowMs, nowMs)}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (tile in teamSports) LiveChip(label = if (prefs.favouriteTeams.isEmpty()) "Add your teams" else "Add a team", selected = false, onClick = { addingTeam = true })
                        }
                    }
                    heroEvent?.let { h ->
                        item(key = "event-hero") {
                            RaceHero(
                                fixture = h, nowMs = nowMs, reminded = "fixture:${h.id}" in remindedIds,
                                wash = tintFor(tile.label),
                                canReplay = false,
                                onWatch = { if (h.status != FixtureStatus.FINAL) store.playFixture(context, h) }, onRemind = { toggleReminder(h) },
                                modifier = Modifier.padding(horizontal = Dx.Gutter, vertical = 8.dp).fillMaxWidth().height(150.dp),
                            )
                        }
                    }
                    panelSection("Live now", live.filter { sided(it) }, nowMs, hideSportsScores, favTeamIds, { focusedMatch = it }) { store.playFixture(context, it) }
                    panelSection("Coming up", upcoming.filter { sided(it) }, nowMs, hideSportsScores, favTeamIds, { focusedMatch = it }) { toggleReminder(it) }
                    panelSection("Results", past.filter { sided(it) }, nowMs, hideSportsScores, favTeamIds, { focusedMatch = it }) { openMatch = it }
                    eventSection("Events", events.filter { it != heroEvent }.sortedBy { if (it.status == FixtureStatus.FINAL) Long.MAX_VALUE - it.startMs else it.startMs }.take(12), tintFor(tile.label), nowMs) {
                        if (it.status == FixtureStatus.LIVE) store.playFixture(context, it) else toggleReminder(it)
                    }
                    if (pool.isEmpty()) item(key = "empty") {
                        Text("No ${tile.label} matches coming up.", fontSize = 16.sp, color = Dx.Muted, modifier = Modifier.padding(start = Dx.Gutter, top = 24.dp))
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

/** The sports whose matches are between teams (the others have events, or their own page) */
private val teamSports = setOf(SportTile.FOOTBALL, SportTile.NFL, SportTile.NBA, SportTile.NHL, SportTile.MLB)

/** A sport's large glass tile: its icon and words; ★ when it's a favourite; hold OK to favourite */
@Composable
private fun SportTileButton(
    tile: SportTile,
    favourite: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassTile(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.width(116.dp).height(70.dp),
        shape = RoundedCornerShape(Dx.RCard + 4.dp),
        fillColor = if (selected) Color.White.copy(alpha = 0.22f) else LivePalette.GlassFill,
        sheen = true,
        focusedScale = Dx.FocusButton,
    ) { _, ink ->
        Box(Modifier.fillMaxSize().padding(12.dp)) {
            androidx.tv.material3.Icon(tile.icon, contentDescription = null, tint = ink, modifier = Modifier.size(22.dp).align(Alignment.TopStart))
            if (favourite) androidx.tv.material3.Icon(Icons.Filled.Star, contentDescription = "Favourite", tint = ink.copy(alpha = 0.8f), modifier = Modifier.size(13.dp).align(Alignment.TopEnd))
            Text(tile.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart))
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.eventSection(
    title: String,
    list: List<SportsFixture>,
    tint: Color,
    nowMs: Long,
    onPick: (SportsFixture) -> Unit,
) {
    if (list.isEmpty()) return
    item(key = "h:$title") {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = Dx.Gutter, end = Dx.Gutter, top = 22.dp, bottom = 8.dp))
    }
    items(list.chunked(2), key = { row -> "ev:${row.first().id}" }) { row ->
        Row(Modifier.padding(horizontal = Dx.Gutter, vertical = 6.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            row.forEach { f -> EventPanel(f, tint, nowMs, onPick = { onPick(f) }, modifier = Modifier.weight(1f)) }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
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

private fun androidx.compose.foundation.lazy.LazyListScope.panelSection(
    title: String,
    list: List<SportsFixture>,
    nowMs: Long,
    hideScores: Boolean,
    favTeamIds: Set<String>,
    onFocusMatch: (SportsFixture) -> Unit,
    onPick: (SportsFixture) -> Unit,
) {
    if (list.isEmpty()) return
    item(key = "h:$title") {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = Dx.Gutter, end = Dx.Gutter, top = 22.dp, bottom = 8.dp))
    }
    items(list.chunked(2), key = { row -> "$title:${row.first().id}" }) { row ->
        Row(Modifier.padding(horizontal = Dx.Gutter, vertical = 6.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            row.forEach { f ->
                MatchPanel(f, nowMs, hideScores, mine = f.home?.id in favTeamIds || f.away?.id in favTeamIds, onPick = { onPick(f) }, modifier = Modifier.weight(1f), onFocus = { onFocusMatch(f) })
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
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
            if (!focused) Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to a.copy(alpha = 0.55f), 0.38f to Color.Transparent, 0.62f to Color.Transparent, 1f to b.copy(alpha = 0.55f))))
            Text(f.leagueName, fontSize = 12.sp, color = ink.copy(alpha = 0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp, start = 24.dp, end = 24.dp))
            if (mine) androidx.tv.material3.Icon(Icons.Filled.Star, contentDescription = "Your team", tint = ink.copy(alpha = 0.85f), modifier = Modifier.align(Alignment.TopStart).padding(top = 6.dp, start = 8.dp).size(12.dp))
            Row(Modifier.fillMaxSize().padding(horizontal = 14.dp).padding(top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    CrestBadge(logoUrl = home?.logoUrl, name = home?.name ?: f.name, size = 38.dp)
                    Text(home?.shortName ?: home?.name ?: f.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
                val showScore = f.status != FixtureStatus.UPCOMING && !hideScores && home?.score != null
                Column(Modifier.width(132.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
    Column(modifier.liveGlass(RoundedCornerShape(Dx.RCard + 4.dp)).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
        modifier.liveGlass(shape).clip(shape).drawBehind {
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

private fun androidx.compose.foundation.lazy.LazyListScope.fixtureSection(
    title: String,
    list: List<SportsFixture>,
    nowMs: Long,
    hideScores: Boolean,
    remindedIds: Set<String>,
    onPick: (SportsFixture) -> Unit,
) {
    if (list.isEmpty()) return
    item(key = "h:$title") {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = Dx.Gutter, end = Dx.Gutter, top = 22.dp, bottom = 6.dp))
    }
    items(list, key = { "$title:${it.id}" }) { f ->
        FixtureRow(f, nowMs, hideScores, reminded = "fixture:${f.id}" in remindedIds, onPick = { onPick(f) })
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
private fun FixtureRow(f: SportsFixture, nowMs: Long, hideScores: Boolean, reminded: Boolean, onPick: () -> Unit) {
    // (a race has no two sides and no match page: its name, and Finished rather than Full time)
    val racing = f.isRacing()
    GlassTile(
        onClick = onPick,
        modifier = Modifier.padding(horizontal = Dx.Gutter).fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(Dx.RCard),
        fillColor = Color.Transparent,
        borderColor = Color.Transparent,
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

private fun fixtureTitle(f: SportsFixture): String {
    val h = f.home; val a = f.away
    return if (h != null && a != null) "${h.shortName ?: h.name} v ${a.shortName ?: a.name}" else f.shortName ?: f.name
}

/** A match's time on the 24-hour clock, in the TV's own time zone (the times come in as UTC) */
private fun timeLabel(ms: Long): String = SimpleDateFormat("HH:mm", Locale.UK).format(Date(ms))

private fun dayLabel(ms: Long, nowMs: Long): String {
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
private fun F1Standings(json: org.json.JSONObject, favourites: Set<String>, onToggle: (String) -> Unit, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        listOf("drivers" to "Drivers", "constructors" to "Constructors").forEach { (key, title) ->
            val rows = json.optJSONArray(key) ?: return@forEach
            if (rows.length() == 0) return@forEach
            val shape = RoundedCornerShape(Dx.RCard + 4.dp)
            val top = rows.getJSONObject(0).optString("points").toFloatOrNull() ?: 1f
            Column(
                Modifier.weight(1f).clip(shape).background(Color(0xFF0E0E10))
                    // (a dark glow, as the mock-up's: a faint light from the top edge)
                    .drawBehind { drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.07f), Color.Transparent), center = Offset(size.width / 2f, 0f), radius = size.width * 0.7f)) }
                    .border(1.dp, GlassRim, shape)
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
