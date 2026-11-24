package com.notdxbi.dxbi.tv.lumenphone.live

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.notdxbi.dxbi.tv.live.LiveReminders
import com.notdxbi.dxbi.tv.live.LiveTvStore
import com.notdxbi.dxbi.tv.live.LumenHandoff
import com.notdxbi.dxbi.tv.lumenphone.AppLayout
import com.notdxbi.dxbi.tv.lumenphone.Dx
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lumos.iptv.FixtureStatus
import lumos.iptv.SportsFixture
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Series(val label: String, val code: String, val sport: String) {
    F1("Formula 1", "f1", "f1"), INDY("IndyCar", "irl", "indycar"), NASCAR("NASCAR", "nascar-premier", "nascar")
}

private fun SportsFixture.isRacing() =
    sport.contains("racing", true) || league.equals("f1", true) || league.equals("irl", true) ||
        league.startsWith("nascar", true) || leagueName.contains("formula", true)

private enum class SportTile(val label: String, val filter: SportFilter?) {
    FOOTBALL("Football", SportFilter.FOOTBALL), F1("Motorsport", SportFilter.F1), NFL("NFL", SportFilter.NFL),
    NBA("NBA", SportFilter.NBA), NHL("NHL", SportFilter.NHL), MLB("MLB", SportFilter.MLB),
    UFC("UFC", SportFilter.UFC), GOLF("Golf", SportFilter.GOLF), TENNIS("Tennis", SportFilter.TENNIS),
}

private val teamSports = setOf(SportTile.FOOTBALL, SportTile.NFL, SportTile.NBA, SportTile.NHL, SportTile.MLB)

private fun fixtureTitle(f: SportsFixture): String {
    val h = f.home; val a = f.away
    return if (h != null && a != null) "${h.shortName ?: h.name} v ${a.shortName ?: a.name}" else f.shortName ?: f.name
}

private fun dayLabel(ms: Long, nowMs: Long): String {
    val day = SimpleDateFormat("yyyyMMdd", Locale.UK)
    return when (day.format(Date(ms))) {
        day.format(Date(nowMs)) -> "Today"
        day.format(Date(nowMs + 86_400_000L)) -> "Tomorrow"
        day.format(Date(nowMs - 86_400_000L)) -> "Yesterday"
        else -> SimpleDateFormat("EEE d MMM", Locale.UK).format(Date(ms))
    }
}

private fun teamColour(team: String): Color = when {
    team.contains("mercedes", true) -> Color(0xFF27F4D2)
    team.contains("red bull", true) -> Color(0xFF3671C6)
    team.contains("racing bulls", true) || team.contains("alphatauri", true) -> Color(0xFF6692FF)
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

private val DRIVER_TEAMS = mapOf(
    "antonelli" to "Mercedes", "russell" to "Mercedes", "verstappen" to "Red Bull", "hadjar" to "Red Bull",
    "lawson" to "Racing Bulls", "lindblad" to "Racing Bulls", "norris" to "McLaren", "piastri" to "McLaren",
    "leclerc" to "Ferrari", "hamilton" to "Ferrari", "alonso" to "Aston Martin", "stroll" to "Aston Martin",
    "gasly" to "Alpine", "colapinto" to "Alpine", "albon" to "Williams", "sainz" to "Williams",
    "ocon" to "Haas", "bearman" to "Haas", "hulkenberg" to "Audi", "hülkenberg" to "Audi", "bortoleto" to "Audi",
    "perez" to "Cadillac", "pérez" to "Cadillac", "bottas" to "Cadillac", "tsunoda" to "Red Bull",
)

@Composable
internal fun Crest(logo: String?, name: String, size: Dp) {
    val url = logo?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    var failed by remember(url) { mutableStateOf(false) }
    Box(Modifier.size(size).clip(CircleShape).background(if (url != null && !failed) Color(0xFFEAECEF) else Color(0xFF2E3A4F)), contentAlignment = Alignment.Center) {
        if (url != null && !failed) AsyncImage(url, name, contentScale = ContentScale.Fit, onError = { failed = true }, modifier = Modifier.size(size - 8.dp))
        else Text(name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase(), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = (size.value * 0.34f).sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SportsPage(store: LiveTvStore, layout: AppLayout, bottomSpace: Dp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by store.prefs.collectAsState()
    val schedules by store.teamSchedules.collectAsState()
    val fixtures by store.sportsFixtures.collectAsState()
    val loading by store.sportsLoading.collectAsState()
    val reminders by LiveReminders.all.collectAsState()
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var addingTeam by remember { mutableStateOf(false) }
    var openMatch by remember { mutableStateOf<SportsFixture?>(null) }
    var series by rememberSaveable { mutableStateOf(Series.F1) }
    var standings by remember { mutableStateOf<JSONObject?>(null) }
    var rounds by remember { mutableStateOf<List<LiveTvStore.MotorRound>>(emptyList()) }
    var comingRaces by remember { mutableStateOf<List<SportsFixture>>(emptyList()) }

    val sportPrefs = remember { context.getSharedPreferences("lumen_sports", android.content.Context.MODE_PRIVATE) }
    var favouriteSports by remember { mutableStateOf(sportPrefs.getStringSet("favourites", null)?.toSet() ?: setOf(SportTile.FOOTBALL.name, SportTile.F1.name)) }
    var favRacers by remember { mutableStateOf(sportPrefs.getStringSet("racers", null)?.toSet() ?: emptySet()) }
    var tile by rememberSaveable { mutableStateOf(SportTile.entries.first { it.name in favouriteSports }) }
    val tiles = remember(favouriteSports) { SportTile.entries.sortedByDescending { it.name in favouriteSports } }

    LaunchedEffect(Unit) {
        LiveReminders.load(context)
        store.refreshSavedTeamSchedules()
        while (true) { delay(30_000L); nowMs = System.currentTimeMillis() }
    }
    LaunchedEffect(tile, series) {
        if (tile == SportTile.F1) {
            standings = null; comingRaces = emptyList()
            store.loadSportsFixtures(sport = series.sport)
            scope.launch { comingRaces = store.upcomingRaces(series.sport, series.code) }
            if (series == Series.F1) scope.launch { rounds = store.motorReplays() }
            standings = store.f1Standings(series.code)
        } else store.loadSportsFixtures()
    }
    val remindedIds = remember(reminders) { reminders.mapTo(HashSet()) { it.id } }
    val favTeamIds = remember(prefs.favouriteTeams) { prefs.favouriteTeams.mapTo(HashSet()) { it.teamId } }

    fun toggleReminder(f: SportsFixture) {
        val had = "fixture:${f.id}" in remindedIds
        android.widget.Toast.makeText(context, if (had) "Reminder removed" else "We'll remind you before ${fixtureTitle(f)} starts", android.widget.Toast.LENGTH_SHORT).show()
        LiveReminders.toggle(context, LiveReminders.Reminder("fixture:${f.id}", fixtureTitle(f), f.startMs, null, f.ukChannelName, fixtureId = f.id))
    }
    fun playMotor(r: LiveTvStore.MotorRound, s: LiveTvStore.MotorSession) {
        LumenHandoff.startMsOnce = null
        LumenHandoff.returnTo = null
        LumenHandoff.pending.value = LumenHandoff.Request(r.feedId, r.feedType, "${r.place} ${s.label}", null, s.videoId, "", "")
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = layout.gutter, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tiles.forEach { t ->
                Box(Modifier.combinedClickable(onClick = { tile = t }, onLongClick = {
                    favouriteSports = if (t.name in favouriteSports) favouriteSports - t.name else favouriteSports + t.name
                    sportPrefs.edit().putStringSet("favourites", favouriteSports).apply()
                })) { LiveChip((if (t.name in favouriteSports) "★ " else "") + t.label, tile == t, onClick = { tile = t }) }
            }
        }
        if (tile == SportTile.F1) {
            val f1 = remember(fixtures, series) { fixtures.filter { it.isRacing() && it.league.equals(series.code, true) }.sortedBy { it.startMs } }
            val next = f1.firstOrNull { it.status == FixtureStatus.LIVE } ?: (f1.filter { it.status == FixtureStatus.UPCOMING } + comingRaces).distinctBy { it.id }.minByOrNull { it.startMs }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = bottomSpace + 24.dp)) {
                item {
                    Row(Modifier.padding(horizontal = layout.gutter, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Series.entries.forEach { sr -> LiveChip(sr.label, series == sr, { series = sr }) }
                    }
                }
                val done = f1.filter { it.status == FixtureStatus.FINAL }.sortedByDescending { it.startMs }
                val shown = next ?: done.firstOrNull()
                shown?.let { n ->
                    item(key = "hero") {
                        val round = if (series == Series.F1 && n.status == FixtureStatus.FINAL) rounds.firstOrNull { placeMatches(it.place, n.name + " " + (n.venue ?: "")) } else null
                        val race = round?.sessions?.firstOrNull { it.label == "Race" } ?: round?.sessions?.firstOrNull()
                        RaceHero(n, nowMs, "fixture:${n.id}" in remindedIds, Modifier.padding(horizontal = layout.gutter, vertical = 6.dp), canReplay = race != null,
                            onWatch = { if (n.status == FixtureStatus.FINAL) { if (race != null && round != null) playMotor(round, race) } else store.playFixture(context, n) },
                            onRemind = { toggleReminder(n) })
                    }
                }
                if (series == Series.F1 && rounds.isNotEmpty()) {
                    val r = rounds.first()
                    item(key = "replays") {
                        Column(Modifier.padding(horizontal = layout.gutter, vertical = 6.dp).fillMaxWidth().card().padding(14.dp)) {
                            Text("Replays · ${r.place}", color = Dx.Text, style = MaterialTheme.typography.titleSmall)
                            r.sessions.take(5).forEach { s ->
                                Row(Modifier.fillMaxWidth().clickable { playMotor(r, s) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.PlayArrow, null, tint = Dx.Text, modifier = Modifier.size(18.dp)); Text("  ${s.label}", color = Dx.Text)
                                }
                            }
                        }
                    }
                }
                standings?.let { st ->
                    item(key = "standings") {
                        Standings(st, favRacers, layout) { name ->
                            favRacers = if (name in favRacers) favRacers - name else favRacers + name
                            sportPrefs.edit().putStringSet("racers", favRacers).apply()
                        }
                    }
                }
                fixtureSection("Live now", f1.filter { it.status == FixtureStatus.LIVE }, nowMs, prefs.hideSportsScores, remindedIds, layout) { store.playFixture(context, it) }
                fixtureSection("Coming up", f1.filter { it.status == FixtureStatus.UPCOMING }.drop(if (next?.status == FixtureStatus.UPCOMING) 1 else 0).take(8), nowMs, prefs.hideSportsScores, remindedIds, layout) { toggleReminder(it) }
                if (f1.isEmpty() && !loading) item { EmptyNote("No ${series.label} races", "None in the next few days.") }
            }
        } else {
            val mine = remember(schedules) { schedules.flatMap { it.live + it.upcoming + it.past } }
            val sportFilter = tile.filter
            val pool = remember(fixtures, mine, tile) { (fixtures + mine).distinctBy { it.id }.filter { sportFilter == null || matchesSport(it, sportFilter) } }
            fun isMine(f: SportsFixture) = f.home?.id in favTeamIds || f.away?.id in favTeamIds
            fun sided(f: SportsFixture) = !f.home?.name.isNullOrBlank() && !f.away?.name.isNullOrBlank()
            val live = pool.filter { it.status == FixtureStatus.LIVE }.sortedWith(compareBy({ !isMine(it) }, { it.startMs }))
            val upcoming = pool.filter { it.status == FixtureStatus.UPCOMING }.sortedWith(compareBy({ !isMine(it) }, { it.startMs })).take(14)
            val past = pool.filter { it.status == FixtureStatus.FINAL }.sortedWith(compareBy({ !isMine(it) }, { -it.startMs })).take(12)
            val events = pool.filterNot { sided(it) }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = bottomSpace + 24.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = layout.gutter, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${tile.label} · ${dayLabel(nowMs, nowMs)}", color = Dx.Text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        if (tile in teamSports) LiveChip(if (prefs.favouriteTeams.isEmpty()) "Add your teams" else "Add a team", false, { addingTeam = true })
                    }
                }
                fixtureSection("Live now", live.filter { sided(it) }, nowMs, prefs.hideSportsScores, remindedIds, layout, favTeamIds) { store.playFixture(context, it) }
                fixtureSection("Coming up", upcoming.filter { sided(it) }, nowMs, prefs.hideSportsScores, remindedIds, layout, favTeamIds) { openMatch = it }
                fixtureSection("Results", past.filter { sided(it) }, nowMs, prefs.hideSportsScores, remindedIds, layout, favTeamIds) { openMatch = it }
                fixtureSection("Events", events.sortedBy { if (it.status == FixtureStatus.FINAL) Long.MAX_VALUE - it.startMs else it.startMs }.take(12), nowMs, prefs.hideSportsScores, remindedIds, layout) {
                    if (it.status == FixtureStatus.LIVE) store.playFixture(context, it) else toggleReminder(it)
                }
                if (pool.isEmpty() && !loading) item { EmptyNote("No ${tile.label} matches", "Nothing coming up. Add your teams to follow them here.") }
                if (loading && pool.isEmpty()) item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { LiveSpinner() } }
            }
        }
    }

    openMatch?.let { f ->
        MatchSheet(store, f, prefs.hideSportsScores, "fixture:${f.id}" in remindedIds, onWatch = { openMatch = null; store.playFixture(context, f) }, onRemind = { toggleReminder(f) }, onDismiss = { openMatch = null })
    }
    if (addingTeam) AddTeamSheet(store, prefs.favouriteTeams, onClose = { addingTeam = false })
}

private fun placeMatches(place: String, text: String): Boolean {
    val p = place.lowercase().replace("grand prix", "").trim()
    val t = text.lowercase()
    return p.isNotEmpty() && (t.contains(p) || (p == "united states" && t.contains("austin")) || (p == "mexican" && t.contains("mexico")) || (p == "emilia romagna" && t.contains("imola")))
}

private fun androidx.compose.foundation.lazy.LazyListScope.fixtureSection(
    title: String, list: List<SportsFixture>, nowMs: Long, hideScores: Boolean, reminded: Set<String>, layout: AppLayout,
    mineIds: Set<String> = emptySet(), onPick: (SportsFixture) -> Unit,
) {
    if (list.isEmpty()) return
    item(key = "h$title") { SectionTitle(title) }
    items(list, key = { "$title${it.id}" }) { f ->
        FixtureCard(f, nowMs, hideScores, "fixture:${f.id}" in reminded, f.home?.id in mineIds || f.away?.id in mineIds, layout) { onPick(f) }
    }
}

@Composable
private fun FixtureCard(f: SportsFixture, nowMs: Long, hideScores: Boolean, reminded: Boolean, mine: Boolean, layout: AppLayout, onClick: () -> Unit) {
    val racing = f.isRacing()
    val h = f.home; val a = f.away
    Column(Modifier.padding(horizontal = layout.gutter, vertical = 5.dp).fillMaxWidth().card().clickable(onClick = onClick).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (f.status) { FixtureStatus.LIVE -> "Live" + (f.clock?.let { " · $it" } ?: ""); FixtureStatus.FINAL -> if (racing) "Finished" else "Full time"; else -> "${dayLabel(f.startMs, nowMs)} ${LiveTime.hm(f.startMs)}" },
                color = if (f.status == FixtureStatus.LIVE) Color(0xFFE5484D) else Dx.Muted, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
            )
            Text(f.ukChannelName ?: f.leagueName, color = Dx.Faint, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        if (!racing && h != null && a != null && h.name.isNotBlank() && a.name.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Crest(h.logoUrl, h.name, 34.dp)
                Text(h.shortName ?: h.name, color = Dx.Text, fontWeight = if (h.winner) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 10.dp))
                val show = f.status != FixtureStatus.UPCOMING && !hideScores && h.score != null
                Text(if (show) "${h.score} – ${a.score ?: "0"}" else "v", color = if (show) Dx.Text else Dx.Muted, style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"), textAlign = TextAlign.Center, modifier = Modifier.width(72.dp))
                Text(a.shortName ?: a.name, color = Dx.Text, fontWeight = if (a.winner) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.weight(1f).padding(end = 10.dp))
                Crest(a.logoUrl, a.name, 34.dp)
            }
        } else Text(f.name.ifBlank { f.leagueName }, color = Dx.Text, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val action = when (f.status) {
            FixtureStatus.LIVE -> "Watch"
            FixtureStatus.UPCOMING -> if (reminded) "Reminder set" else if (racing) "Remind me" else "Details"
            else -> if (racing) "" else "Match details"
        }
        if (action.isNotEmpty()) Text((if (mine) "★ " else "") + action, color = Dx.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun RaceHero(f: SportsFixture, nowMs: Long, reminded: Boolean, modifier: Modifier, canReplay: Boolean, onWatch: () -> Unit, onRemind: () -> Unit) {
    val live = f.status == FixtureStatus.LIVE
    val finished = f.status == FixtureStatus.FINAL
    val wash = Color(0xFFE10600)
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(listOf(wash.copy(alpha = 0.45f), Color(0xFF111111)))).padding(18.dp)) {
        Text(if (live) "On now" else if (finished) "Latest result" else "Next up", color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelMedium)
        Text(f.name.ifBlank { f.leagueName }, color = Dx.Text, style = MaterialTheme.typography.titleLarge, maxLines = 2)
        Text(listOfNotNull(f.venue, "${dayLabel(f.startMs, nowMs)} ${LiveTime.hm(f.startMs)}", f.ukChannelName).joinToString("  ·  "), color = Dx.Muted, style = MaterialTheme.typography.bodySmall)
        if (!live && !finished) {
            val left = (f.startMs - nowMs).coerceAtLeast(0L)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                listOf(left / 86_400_000L to "days", (left / 3_600_000L) % 24 to "hours", (left / 60_000L) % 60 to "mins").forEach { (v, u) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("$v", color = Dx.Text, style = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")); Text(u, color = Dx.Muted, style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (live) LiveButton("Watch", onClick = onWatch)
            else if (finished) { if (canReplay) LiveButton("Watch replay", onClick = onWatch) }
            else LiveButton(if (reminded) "Reminder set" else "Remind me", primary = !reminded, onClick = onRemind)
        }
    }
}

@Composable
private fun Standings(json: JSONObject, favourites: Set<String>, layout: AppLayout, onToggle: (String) -> Unit) {
    val keys = listOf("drivers" to "Drivers", "constructors" to "Constructors")
    val content: @Composable (String, String, Modifier) -> Unit = { key, title, mod ->
        val rows = json.optJSONArray(key)
        if (rows != null && rows.length() > 0) {
            val top = rows.getJSONObject(0).optString("points").toFloatOrNull() ?: 1f
            Column(mod.card().padding(12.dp)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) { Text(title, color = Dx.Text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f)); Text("Points", color = Dx.Muted, style = MaterialTheme.typography.labelMedium) }
                for (i in 0 until minOf(rows.length(), 8)) {
                    val r = rows.getJSONObject(i)
                    val name = r.optString("name")
                    val team = if (key == "drivers") DRIVER_TEAMS[name.substringAfterLast(' ').lowercase()] ?: "" else name
                    val colour = teamColour(team)
                    val fav = name in favourites
                    Row(Modifier.fillMaxWidth().clickable { onToggle(name) }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(r.optString("rank"), color = Dx.Muted, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"), modifier = Modifier.width(26.dp))
                        Box(Modifier.width(4.dp).height(26.dp).clip(RoundedCornerShape(2.dp)).background(colour))
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(name, color = Dx.Text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                if (fav) Icon(Icons.Filled.Star, "Favourite", tint = Dx.Text, modifier = Modifier.padding(start = 6.dp).size(12.dp))
                            }
                            if (key == "drivers" && team.isNotEmpty()) Text(team, color = Dx.Muted, style = MaterialTheme.typography.bodySmall)
                            else Box(Modifier.padding(top = 3.dp).fillMaxWidth(((r.optString("points").toFloatOrNull() ?: 0f) / top).coerceIn(0f, 1f) * 0.9f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(colour.copy(alpha = 0.8f)))
                        }
                        Text(r.optString("points"), color = Dx.Text, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"))
                    }
                }
            }
        }
    }
    if (layout.compact) Column(Modifier.padding(horizontal = layout.gutter, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { keys.forEach { (k, t) -> content(k, t, Modifier.fillMaxWidth()) } }
    else Row(Modifier.padding(horizontal = layout.gutter, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) { keys.forEach { (k, t) -> content(k, t, Modifier.weight(1f)) } }
}
