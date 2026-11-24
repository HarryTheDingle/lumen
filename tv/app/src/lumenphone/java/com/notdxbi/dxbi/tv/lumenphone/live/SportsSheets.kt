package com.notdxbi.dxbi.tv.lumenphone.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notdxbi.dxbi.tv.live.LiveTvStore
import com.notdxbi.dxbi.tv.lumenphone.Dx
import kotlinx.coroutines.delay
import lumos.iptv.FixtureStatus
import lumos.iptv.SavedSportsTeam
import lumos.iptv.SportsFixture
import org.json.JSONArray
import org.json.JSONObject

/** One match: the score between the crests, the goals and cards, the stats and the line-ups (refreshed every half minute while live) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MatchSheet(store: LiveTvStore, fixture: SportsFixture, hideScores: Boolean, reminded: Boolean, onWatch: () -> Unit, onRemind: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var match by remember { mutableStateOf<JSONObject?>(null) }
    var failed by remember { mutableStateOf(false) }
    var replay by remember { mutableStateOf<LiveTvStore.Replay?>(null) }
    LaunchedEffect(fixture.id) { if (fixture.status == FixtureStatus.FINAL) replay = store.findReplay(fixture) }
    LaunchedEffect(fixture.id) {
        while (true) {
            val m = store.sportsMatch(fixture)
            if (m != null) match = m else if (match == null) failed = true
            if ((m?.optString("status") ?: fixture.status.name) != "LIVE") break
            delay(30_000L)
        }
    }
    val m = match
    val status = m?.optString("status") ?: fixture.status.name
    val live = status == "LIVE"
    val home = m?.optJSONObject("home"); val away = m?.optJSONObject("away")
    fun nm(o: JSONObject?, f: String?) = o?.optString("name")?.takeIf { it.isNotBlank() && it != "null" } ?: f ?: ""
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF111A2C)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Crest(home?.optString("logoUrl") ?: fixture.home?.logoUrl, nm(home, fixture.home?.name), 64.dp)
                    Text(nm(home, fixture.home?.name), color = Dx.Text, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        when (status) { "LIVE" -> "Live" + (m?.optString("clock")?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""); "FINAL" -> "Full time"; else -> "Kick-off ${LiveTime.hm(fixture.startMs)}" },
                        color = if (live) Color(0xFFE5484D) else Dx.Text, style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        if (status == "UPCOMING") "v" else if (hideScores) "–" else "${home?.optString("score") ?: fixture.home?.score ?: "0"} – ${away?.optString("score") ?: fixture.away?.score ?: "0"}",
                        color = Dx.Text, style = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, fontFeatureSettings = "tnum"),
                    )
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Crest(away?.optString("logoUrl") ?: fixture.away?.logoUrl, nm(away, fixture.away?.name), 64.dp)
                    Text(nm(away, fixture.away?.name), color = Dx.Text, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
                }
            }
            Text(listOfNotNull(fixture.leagueName.takeIf { it.isNotBlank() }, m?.optString("venue")?.takeIf { it.isNotBlank() && it != "null" }, fixture.ukChannelName).joinToString("  ·  "), color = Dx.Muted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
                when {
                    live -> LiveButton("Watch" + (fixture.ukChannelName?.let { " on $it" } ?: ""), onClick = onWatch)
                    status == "UPCOMING" -> LiveButton(if (reminded) "Reminder set" else "Remind me", primary = !reminded, onClick = onRemind)
                    else -> replay?.let { r -> LiveButton("Watch replay on ${r.channel.name}", onClick = { onDismiss(); store.playReplay(context, r) }) }
                }
            }
            if (m == null) Text(if (failed) "Match details aren't available for this one." else "Getting the match details…", color = Dx.Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            else if (status != "UPCOMING" && !hideScores) {
                Heading("Match events")
                val events = m.optJSONArray("events") ?: JSONArray()
                if (events.length() == 0) Text("No events yet.", color = Dx.Muted)
                for (i in 0 until events.length()) EventLine(events.getJSONObject(i))
                val stats = m.optJSONArray("stats") ?: JSONArray()
                if (stats.length() > 0) { Heading("Stats", 18.dp); for (i in 0 until stats.length()) StatBar(stats.getJSONObject(i)) }
            }
            if (m != null && (m.optJSONArray("homeLineup")?.length() ?: 0) > 0) {
                Heading("${nm(home, null)} line-up", 18.dp); Lineup(m.optJSONArray("homeLineup"))
                Heading("${nm(away, null)} line-up", 18.dp); Lineup(m.optJSONArray("awayLineup"))
            }
        }
    }
}

@Composable
private fun Heading(text: String, top: androidx.compose.ui.unit.Dp = 4.dp) =
    Text(text, color = Dx.Text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = top, bottom = 6.dp))

@Composable
private fun EventLine(e: JSONObject) {
    val kind = e.optString("kind")
    val who = e.optJSONArray("who")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
    val words = if (kind.contains("Substitution", true) && who.size >= 2) "${who[0]} on for ${who[1]}" else who.firstOrNull() ?: ""
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(e.optString("minute"), color = Dx.Muted, fontWeight = FontWeight.Bold, modifier = Modifier.width(44.dp))
        when {
            kind.contains("yellow", true) -> Box(Modifier.size(10.dp, 14.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFFFFC107)))
            kind.contains("red", true) -> Box(Modifier.size(10.dp, 14.dp).clip(RoundedCornerShape(2.dp)).background(Dx.Problem))
            kind.contains("goal", true) || kind.contains("penalty", true) -> Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White))
            else -> Box(Modifier.size(10.dp))
        }
        Text("$kind · $words", color = if (kind.contains("Substitution", true)) Dx.Muted else Dx.Text, fontWeight = if (kind.contains("goal", true)) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StatBar(s: JSONObject) {
    val pct = s.optString("label") == "Possession"
    val h = s.optString("home").let { if (pct && !it.endsWith("%")) "$it%" else it }
    val a = s.optString("away").let { if (pct && !it.endsWith("%")) "$it%" else it }
    val hv = h.trimEnd('%').toFloatOrNull() ?: 0f; val av = a.trimEnd('%').toFloatOrNull() ?: 0f
    val share = if (hv + av > 0f) hv / (hv + av) else 0.5f
    Column(Modifier.padding(vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(h, color = Dx.Text, fontWeight = FontWeight.Bold, modifier = Modifier.width(60.dp))
            Text(s.optString("label"), color = Dx.Muted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Text(a, color = Dx.Text, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.width(60.dp))
        }
        Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.18f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(share.coerceIn(0f, 1f)).background(Color.White))
        }
    }
}

@Composable
private fun Lineup(players: JSONArray?) {
    val list = players ?: JSONArray()
    for (i in 0 until list.length()) {
        val p = list.getJSONObject(i)
        Row(Modifier.padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(p.optString("number"), color = Dx.Muted, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
            Text(p.optString("name"), color = Dx.Text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(p.optString("position"), color = Dx.Faint, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Search for teams to follow (UK football, NFL, NBA, NHL, MLB) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddTeamSheet(store: LiveTvStore, followed: List<SavedSportsTeam>, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SavedSportsTeam>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val keys = followed.mapTo(HashSet()) { "${it.league}:${it.teamId}" }
    LaunchedEffect(query) {
        val t = query.trim()
        if (t.length < 2) { results = emptyList(); searching = false; return@LaunchedEffect }
        searching = true; delay(250)
        results = store.searchSportsTeams(t); searching = false
    }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = Color(0xFF1C1C1E)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.8f).padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("Add a team", color = Dx.Text, style = MaterialTheme.typography.titleLarge)
            Text("UK football, NFL, NBA, NHL and MLB", color = Dx.Muted, style = MaterialTheme.typography.bodySmall)
            Box(Modifier.padding(vertical = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Dx.Raised).padding(14.dp)) {
                BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = Color.White, fontSize = 17.sp), cursorBrush = SolidColor(Color.White),
                    decorationBox = { inner -> Box { if (query.isEmpty()) Text("Arsenal, Chiefs, Lakers…", color = Dx.Faint, fontSize = 17.sp); inner() } })
            }
            when {
                searching -> Text("Searching…", color = Dx.Muted)
                query.trim().length >= 2 && results.isEmpty() -> Text("No teams match.", color = Dx.Muted)
                else -> LazyColumn {
                    items(results, key = { "${it.league}:${it.teamId}" }) { t ->
                        val on = "${t.league}:${t.teamId}" in keys
                        Row(Modifier.fillMaxWidth().clickable { if (on) store.removeFavouriteTeam(t.teamId) else store.addFavouriteTeam(t) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Crest(t.logoUrl, t.name, 38.dp)
                            Column(Modifier.weight(1f)) { Text(t.name, color = Dx.Text); Text(t.leagueName, color = Dx.Muted, style = MaterialTheme.typography.bodySmall) }
                            Text(if (on) "Following" else "Follow", color = if (on) Dx.Muted else Dx.Text, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            if (followed.isNotEmpty() && query.isBlank()) {
                Text("Your teams", color = Dx.Text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                LazyColumn {
                    items(followed, key = { "f${it.league}:${it.teamId}" }) { t ->
                        Row(Modifier.fillMaxWidth().clickable { store.removeFavouriteTeam(t.teamId) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Crest(t.logoUrl, t.name, 34.dp); Text(t.name, color = Dx.Text, modifier = Modifier.weight(1f)); Text("Remove", color = Dx.Muted)
                        }
                    }
                }
            }
        }
    }
}
