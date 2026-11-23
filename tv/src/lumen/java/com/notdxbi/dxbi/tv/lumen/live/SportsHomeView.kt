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
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Brush
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

private enum class SportsTab(val label: String) { MY_TEAMS("My teams"), F1("Motorsport"), ALL("All sports") }

/** The racing series on the Motorsport tab: ESPN's league code, and the Worker's sport name */
private enum class Series(val label: String, val code: String, val sport: String) {
    F1("Formula 1", "f1", "f1"), INDY("IndyCar", "irl", "indycar"), NASCAR("NASCAR", "nascar-premier", "nascar")
}

private fun SportsFixture.isRacing() =
    sport.contains("racing", true) || league.equals("f1", true) || league.equals("irl", true) ||
        league.startsWith("nascar", true) || leagueName.contains("formula", true)

/**
 * Sports, as the football dashboard reference: your team's next match big at the top (a countdown, or the live
 * score), then what's coming up and the latest results as plain rows; Formula 1's weekend; and every sport.
 */
@Composable
fun SportsHomeView(
    store: LiveTvStore,
    hideSportsScores: Boolean,
    contentFocusRequester: FocusRequester,
    onMoveToRail: () -> Unit,
    allSports: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val prefs by store.prefs.collectAsState()
    val schedules by store.teamSchedules.collectAsState()
    val fixtures by store.sportsFixtures.collectAsState()
    val reminders by LiveReminders.all.collectAsState()
    var tab by remember { mutableStateOf(SportsTab.MY_TEAMS) }
    val teamsList = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var addingTeam by remember { mutableStateOf(false) }
    var openMatch by remember { mutableStateOf<SportsFixture?>(null) }
    var series by remember { mutableStateOf(Series.F1) }
    var standings by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        LiveReminders.load(context)
        store.refreshSavedTeamSchedules()
        while (true) {
            delay(30_000L)
            nowMs = System.currentTimeMillis()
        }
    }
    LaunchedEffect(tab, series) {
        if (tab == SportsTab.F1) {
            standings = null
            store.loadSportsFixtures(sport = series.sport)
            standings = store.f1Standings(series.code)
        }
    }
    val remindedIds = remember(reminders) { reminders.mapTo(HashSet()) { it.id } }

    fun toggleReminder(f: SportsFixture) {
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

    Column(Modifier.fillMaxSize().background(Dx.Background)) {
        // the page's own words to choose: plain, one soft capsule
        Row(
            modifier = Modifier
                .padding(start = 24.dp, top = 22.dp, end = 24.dp, bottom = 6.dp)
                // (up on the tabs: the page goes back to its top, the big match)
                .onFocusChanged { if (it.hasFocus) scope.launch { teamsList.animateScrollToItem(0) } },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Sports", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(end = 18.dp))
            SportsTab.entries.forEachIndexed { i, t ->
                LiveChip(
                    label = t.label,
                    selected = tab == t,
                    onClick = { tab = t },
                    modifier = (if (i == 0) Modifier.focusRequester(contentFocusRequester) else Modifier)
                        .onPreviewKeyEvent { ev ->
                            if (i == 0 && ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft) { onMoveToRail(); true } else false
                        },
                )
            }
            Spacer(Modifier.weight(1f))
            if (tab == SportsTab.MY_TEAMS) LiveChip(label = "Add a team", selected = false, onClick = { addingTeam = true })
            if (tab == SportsTab.F1) Series.entries.forEach { sr ->
                LiveChip(label = sr.label, selected = series == sr, onClick = { series = sr })
            }
        }

        when (tab) {
            SportsTab.ALL -> Box(Modifier.weight(1f)) { allSports() }
            SportsTab.F1 -> {
                val f1 = remember(fixtures, series) {
                    fixtures.filter { it.isRacing() && it.league.equals(series.code, true) }
                        .sortedBy { it.startMs }
                }
                Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                FixtureList(
                    sections = listOf(
                        "Live now" to f1.filter { it.status == FixtureStatus.LIVE },
                        "Coming up" to f1.filter { it.status == FixtureStatus.UPCOMING }.take(8),
                        "Results" to f1.filter { it.status == FixtureStatus.FINAL }.sortedByDescending { it.startMs }.take(6),
                    ),
                    empty = "No ${series.label} races in the next few days.",
                    nowMs = nowMs,
                    hideScores = hideSportsScores,
                    remindedIds = remindedIds,
                    onPick = { f -> if (f.status == FixtureStatus.LIVE) store.playFixture(context, f) else if (f.status == FixtureStatus.UPCOMING) toggleReminder(f) },
                )
                }
                standings?.let { F1Standings(it, Modifier.width(380.dp).padding(top = 22.dp, end = 48.dp)) }
                }
            }
            SportsTab.MY_TEAMS -> {
                val all = remember(schedules) { schedules.flatMap { it.live + it.upcoming + it.past }.distinctBy { it.id } }
                val live = all.filter { it.status == FixtureStatus.LIVE }.sortedBy { it.startMs }
                val upcoming = all.filter { it.status == FixtureStatus.UPCOMING }.sortedBy { it.startMs }
                val past = all.filter { it.status == FixtureStatus.FINAL }.sortedByDescending { it.startMs }
                val headline = live.firstOrNull() ?: upcoming.firstOrNull()
                if (prefs.favouriteTeams.isEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(top = 90.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Follow your teams", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(
                            "Their next match, live scores and results show here, with the channel to watch.",
                            fontSize = 15.sp, color = Dx.Muted, modifier = Modifier.padding(top = 8.dp, bottom = 18.dp),
                        )
                        LiveChip(label = "Add a team", selected = true, onClick = { addingTeam = true })
                    }
                } else {
                    LazyColumn(
                        state = teamsList,
                        contentPadding = PaddingValues(bottom = 40.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        headline?.let { h ->
                            item(key = "hero") {
                                MatchHero(
                                    fixture = h,
                                    nowMs = nowMs,
                                    hideScores = hideSportsScores,
                                    reminded = "fixture:${h.id}" in remindedIds,
                                    onWatch = { store.playFixture(context, h) },
                                    onRemind = { toggleReminder(h) },
                                    onDetails = { openMatch = h },
                                )
                            }
                        }
                        fixtureSection("Live now", live.drop(if (headline in live) 1 else 0), nowMs, hideSportsScores, remindedIds) { openMatch = it }
                        fixtureSection("Coming up", upcoming.drop(if (headline in upcoming) 1 else 0).take(8), nowMs, hideSportsScores, remindedIds) { toggleReminder(it) }
                        fixtureSection("Results", past.take(8), nowMs, hideSportsScores, remindedIds) { openMatch = it }
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
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(start = 48.dp, top = 22.dp, bottom = 6.dp))
    }
    items(list, key = { "$title:${it.id}" }) { f ->
        FixtureRow(f, nowMs, hideScores, reminded = "fixture:${f.id}" in remindedIds, onPick = { onPick(f) })
    }
}

@Composable
private fun FixtureList(
    sections: List<Pair<String, List<SportsFixture>>>,
    empty: String,
    nowMs: Long,
    hideScores: Boolean,
    remindedIds: Set<String>,
    onPick: (SportsFixture) -> Unit,
) {
    if (sections.all { it.second.isEmpty() }) {
        Text(empty, fontSize = 16.sp, color = Dx.Muted, modifier = Modifier.padding(start = 48.dp, top = 40.dp))
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
        sections.forEach { (title, list) -> fixtureSection(title, list, nowMs, hideScores, remindedIds, onPick) }
    }
}

/** The next (or live) match, big: crests either side, a countdown or the score in the middle */
@Composable
private fun MatchHero(
    fixture: SportsFixture,
    nowMs: Long,
    hideScores: Boolean,
    reminded: Boolean,
    onWatch: () -> Unit,
    onRemind: () -> Unit,
    onDetails: () -> Unit,
) {
    val live = fixture.status == FixtureStatus.LIVE
    Box(
        Modifier
            .padding(horizontal = 48.dp, vertical = 10.dp)
            .fillMaxWidth()
            .height(250.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.radialGradient(listOf(Color(0xFF1B2133), Color(0xFF0A0B10)), radius = 1400f)),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            TeamColumn(fixture.home?.name ?: "", fixture.home?.logoUrl)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    listOfNotNull(fixture.leagueName.takeIf { it.isNotBlank() }, dayLabel(fixture.startMs, nowMs), fixture.venue).joinToString("  ·  "),
                    fontSize = 13.sp, color = Dx.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (live) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(LivePalette.LiveRed))
                        Text("  Live" + (fixture.clock?.let { " · $it" } ?: ""), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                    Text(
                        if (hideScores) "–" else "${fixture.home?.score ?: "0"} – ${fixture.away?.score ?: "0"}",
                        style = TextStyle(fontSize = 64.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, fontFeatureSettings = "tnum", letterSpacing = (-1).sp),
                    )
                } else {
                    val left = (fixture.startMs - nowMs).coerceAtLeast(0L)
                    val d = left / 86_400_000L
                    val h = (left / 3_600_000L) % 24
                    val m = (left / 60_000L) % 60
                    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                        CountUnit(d, "days"); CountUnit(h, "hours"); CountUnit(m, "minutes")
                    }
                }
                Text(
                    buildString {
                        append(timeLabel(fixture.startMs))
                        fixture.ukChannelName?.let { append(" on $it") }
                    },
                    fontSize = 14.sp, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(top = 10.dp),
                )
                Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (live) LiveChip(label = "Watch", selected = true, onClick = onWatch)
                    else LiveChip(label = if (reminded) "Reminder set" else "Remind me", selected = reminded, onClick = onRemind)
                    LiveChip(label = "Match details", selected = false, onClick = onDetails)
                }
            }
            TeamColumn(fixture.away?.name ?: "", fixture.away?.logoUrl)
        }
    }
}

@Composable
private fun TeamColumn(name: String, logo: String?) {
    Column(Modifier.width(170.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CrestBadge(logoUrl = logo, name = name, size = 96.dp)
        Text(name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun CountUnit(value: Long, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("%02d".format(value), style = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, fontFeatureSettings = "tnum"))
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
        modifier = Modifier.padding(horizontal = 40.dp).fillMaxWidth().height(64.dp),
        shape = RoundedCornerShape(16.dp),
        fillColor = Color.Transparent,
        borderColor = Color.Transparent,
        focusedScale = 1.01f,
    ) { focused, ink ->
        val muted = ink.copy(alpha = if (focused) 0.6f else 0.55f)
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(150.dp)) {
                Text(dayLabel(f.startMs, nowMs), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1)
                Text(
                    when (f.status) { FixtureStatus.LIVE -> "Live" + (f.clock?.let { " · $it" } ?: ""); FixtureStatus.FINAL -> if (racing) "Finished" else "Full time"; else -> timeLabel(f.startMs) },
                    fontSize = 12.sp, color = if (f.status == FixtureStatus.LIVE && !focused) LivePalette.LiveRed else muted, maxLines = 1,
                )
            }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val home = f.home; val away = f.away
                if (!racing && home != null && away != null && home.name.isNotBlank() && away.name.isNotBlank()) {
                    CrestBadge(logoUrl = home.logoUrl, name = home.name, size = 32.dp)
                    Text(home.shortName ?: home.name, fontSize = 15.sp, fontWeight = if (home.winner) FontWeight.Bold else FontWeight.Medium, color = ink, maxLines = 1)
                    val showScore = f.status != FixtureStatus.UPCOMING && !hideScores && home.score != null
                    Text(
                        if (showScore) "${home.score} – ${away.score ?: "0"}" else "v",
                        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (showScore) ink else muted, fontFeatureSettings = "tnum"),
                        modifier = Modifier.widthIn(min = 54.dp), textAlign = TextAlign.Center,
                    )
                    Text(away.shortName ?: away.name, fontSize = 15.sp, fontWeight = if (away.winner) FontWeight.Bold else FontWeight.Medium, color = ink, maxLines = 1)
                    CrestBadge(logoUrl = away.logoUrl, name = away.name, size = 32.dp)
                } else {
                    Column {
                        Text(
                            listOfNotNull(f.name, f.shortName).firstOrNull { it.isNotBlank() } ?: f.leagueName,
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        f.statusDetail?.takeIf { it.startsWith("Won by") && !hideScores }?.let { Text(it, fontSize = 12.sp, color = muted, maxLines = 1) }
                    }
                }
            }
            // (sized to their words, at most so wide: in the half-width Formula 1 list fixed widths left the name none)
            Text(f.ukChannelName ?: f.leagueName, fontSize = 13.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp).widthIn(max = 220.dp))
            val action = when (f.status) {
                FixtureStatus.LIVE -> if (racing) "Watch" else "Match"
                FixtureStatus.UPCOMING -> if (reminded) "Reminder set" else "Remind me"
                else -> if (racing) "" else "Match details"
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

private fun timeLabel(ms: Long): String = SimpleDateFormat("h:mma", Locale.UK).format(Date(ms)).lowercase()

private fun dayLabel(ms: Long, nowMs: Long): String {
    val day = SimpleDateFormat("yyyyMMdd", Locale.UK)
    return when (day.format(Date(ms))) {
        day.format(Date(nowMs)) -> "Today"
        day.format(Date(nowMs + 86_400_000L)) -> "Tomorrow"
        day.format(Date(nowMs - 86_400_000L)) -> "Yesterday"
        else -> SimpleDateFormat("EEE d MMM", Locale.UK).format(Date(ms))
    }
}


/** A racing series' championship: drivers, then constructors (IndyCar and NASCAR have drivers only) */
@Composable
private fun F1Standings(json: org.json.JSONObject, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("drivers" to "Drivers", "constructors" to "Constructors").forEach { (key, title) ->
            val rows = json.optJSONArray(key) ?: return@forEach
            if (rows.length() == 0) return@forEach
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
            for (i in 0 until minOf(rows.length(), if (key == "drivers") 8 else 5)) {
                val r = rows.getJSONObject(i)
                Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.optString("rank"), style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Dx.Muted, fontFeatureSettings = "tnum"), modifier = Modifier.width(30.dp))
                    Text(r.optString("name"), fontSize = 14.sp, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(r.optString("points"), style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White, fontFeatureSettings = "tnum"))
                }
            }
        }
    }
}
