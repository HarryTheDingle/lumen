package com.notdxbi.dxbi.tv.lumen.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.notdxbi.dxbi.tv.live.LiveTvStore
import com.notdxbi.dxbi.tv.lumen.Dx
import com.notdxbi.dxbi.tv.lumen.PageWindow
import kotlinx.coroutines.delay
import lumos.iptv.FixtureStatus
import lumos.iptv.SportsFixture
import org.json.JSONArray
import org.json.JSONObject

/**
 * One match, as the football dashboard reference: the score big between the crests (live, with the minute), then
 * the goals and cards beside the stats, then both line-ups. Live, it refreshes every half minute (ESPN, free).
 */
@Composable
fun MatchPage(
    store: LiveTvStore,
    fixture: SportsFixture,
    hideScores: Boolean,
    reminded: Boolean,
    onWatch: () -> Unit,
    onRemind: () -> Unit,
    onBack: () -> Unit,
) {
    var match by remember { mutableStateOf<JSONObject?>(null) }
    var failed by remember { mutableStateOf(false) }
    // a finished match: is it in a channel's catch-up?
    val context = androidx.compose.ui.platform.LocalContext.current
    var replay by remember { mutableStateOf<LiveTvStore.Replay?>(null) }
    LaunchedEffect(fixture.id) {
        if (fixture.status == lumos.iptv.FixtureStatus.FINAL) replay = store.findReplay(fixture)
    }
    LaunchedEffect(fixture.id) {
        while (true) {
            val m = store.sportsMatch(fixture)
            if (m != null) match = m else if (match == null) failed = true
            if ((m?.optString("status") ?: fixture.status.name) != "LIVE") break
            delay(30_000L)
        }
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }

    val m = match
    val status = m?.optString("status") ?: fixture.status.name
    val live = status == "LIVE"
    val home = m?.optJSONObject("home")
    val away = m?.optJSONObject("away")

    PageWindow(onBack = onBack, solid = true) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF111A2C), Color.Black), endY = 900f)),
            contentPadding = PaddingValues(start = 56.dp, end = 56.dp, top = 36.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            item(key = "score") {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(56.dp)) {
                        Side(home?.optString("name") ?: fixture.home?.name ?: "", home?.optString("logoUrl") ?: fixture.home?.logoUrl)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (live) Box(Modifier.size(8.dp).clip(CircleShape).background(LivePalette.LiveRed))
                                Text(
                                    when (status) {
                                        "LIVE" -> "  Live" + (m?.optString("clock")?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                                        "FINAL" -> "Full time"
                                        else -> "Kick-off " + java.text.SimpleDateFormat("EEE d MMM, HH:mm", java.util.Locale.UK).format(java.util.Date(fixture.startMs))
                                    },
                                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                                )
                            }
                            Text(
                                if (status == "UPCOMING") "v" else if (hideScores) "–" else "${home?.optString("score") ?: fixture.home?.score ?: "0"} – ${away?.optString("score") ?: fixture.away?.score ?: "0"}",
                                style = TextStyle(fontSize = 84.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, fontFeatureSettings = "tnum", letterSpacing = (-2).sp),
                            )
                            Text(
                                listOfNotNull(fixture.leagueName.takeIf { it.isNotBlank() }, m?.optString("venue")?.takeIf { it.isNotBlank() && it != "null" }, fixture.ukChannelName).joinToString("  ·  "),
                                fontSize = 13.sp, color = Dx.Muted,
                            )
                        }
                        Side(away?.optString("name") ?: fixture.away?.name ?: "", away?.optString("logoUrl") ?: fixture.away?.logoUrl)
                    }
                    Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        when {
                            live -> LiveChip(label = "Watch" + (fixture.ukChannelName?.let { " on $it" } ?: ""), selected = true, onClick = onWatch, modifier = Modifier.focusRequester(first))
                            status == "UPCOMING" -> LiveChip(label = if (reminded) "Reminder set" else "Remind me", selected = true, onClick = onRemind, modifier = Modifier.focusRequester(first))
                            else -> {
                                replay?.let { r ->
                                    LiveChip(label = "Watch replay on ${r.channel.name}", selected = true, onClick = { store.playReplay(context, r) }, modifier = Modifier.focusRequester(first))
                                    LiveChip(label = "Back", selected = false, onClick = onBack)
                                } ?: LiveChip(label = "Back", selected = true, onClick = onBack, modifier = Modifier.focusRequester(first))
                            }
                        }
                    }
                }
            }

            if (m == null) {
                item(key = "wait") {
                    Text(if (failed) "Match details aren't available for this one." else "Getting the match details…", fontSize = 15.sp, color = Dx.Muted, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
            } else if (status != "UPCOMING" && !hideScores) {
                item(key = "detail") {
                    Section {
                        Row(horizontalArrangement = Arrangement.spacedBy(56.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Heading("Match events")
                                val events = m.optJSONArray("events") ?: JSONArray()
                                if (events.length() == 0) Text("No events yet.", fontSize = 14.sp, color = Dx.Muted)
                                for (i in 0 until events.length()) {
                                    val e = events.getJSONObject(i)
                                    EventLine(e, homeName = home?.optString("shortName")?.takeIf { it != "null" } ?: home?.optString("name") ?: "", awayName = away?.optString("shortName")?.takeIf { it != "null" } ?: away?.optString("name") ?: "")
                                }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Heading("Stats")
                                val stats = m.optJSONArray("stats") ?: JSONArray()
                                for (i in 0 until stats.length()) StatBar(stats.getJSONObject(i))
                            }
                        }
                    }
                }
            }
            if (m != null && ((m.optJSONArray("homeLineup")?.length() ?: 0) > 0)) {
                item(key = "lineups") {
                    Section {
                        Row(horizontalArrangement = Arrangement.spacedBy(56.dp)) {
                            Lineup(home?.optString("name") ?: "", m.optJSONArray("homeLineup"), Modifier.weight(1f))
                            Lineup(away?.optString("name") ?: "", m.optJSONArray("awayLineup"), Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/** A block the remote can stop on, so the page scrolls to it (white edge when it's there) */
@Composable
private fun Section(content: @Composable () -> Unit) {
    var on by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .onFocusChanged { on = it.isFocused }
            .focusable()
            .clip(RoundedCornerShape(22.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, if (on) Color.White.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(22.dp))
            .padding(28.dp),
    ) { content() }
}

@Composable
private fun Heading(text: String) = Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(bottom = 6.dp))

@Composable
private fun Side(name: String, logo: String?) {
    Column(Modifier.width(200.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CrestBadge(logoUrl = logo?.takeIf { it != "null" }, name = name, size = 112.dp)
        Text(name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun EventLine(e: JSONObject, homeName: String, awayName: String) {
    val kind = e.optString("kind")
    val who = e.optJSONArray("who")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
    val words = when {
        kind.contains("Substitution", true) && who.size >= 2 -> "${who[0]} on for ${who[1]}"
        else -> who.firstOrNull() ?: ""
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(e.optString("minute"), style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Dx.Muted, fontFeatureSettings = "tnum"), modifier = Modifier.width(52.dp))
        when {
            kind.contains("yellow", true) -> Box(Modifier.size(10.dp, 14.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFFFFD60A)))
            kind.contains("red", true) -> Box(Modifier.size(10.dp, 14.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFFFF453A)))
            kind.contains("goal", true) || kind.contains("penalty", true) -> Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White))
            else -> Spacer(Modifier.width(10.dp))
        }
        Text(
            "$kind · $words",
            fontSize = 14.sp,
            fontWeight = if (kind.contains("goal", true)) FontWeight.SemiBold else FontWeight.Normal,
            color = if (kind.contains("Substitution", true)) Dx.Muted else Color.White,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Text(if (e.optBoolean("home")) homeName else awayName, fontSize = 12.sp, color = Dx.Faint, maxLines = 1)
    }
}

@Composable
private fun StatBar(s: JSONObject) {
    // (ESPN gives possession as a bare number)
    val pct = s.optString("label") == "Possession"
    val h = s.optString("home").let { if (pct && !it.endsWith("%")) "$it%" else it }
    val a = s.optString("away").let { if (pct && !it.endsWith("%")) "$it%" else it }
    val hv = h.trimEnd('%').toFloatOrNull() ?: 0f
    val av = a.trimEnd('%').toFloatOrNull() ?: 0f
    val share = if (hv + av > 0f) hv / (hv + av) else 0.5f
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(h, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White, fontFeatureSettings = "tnum"), modifier = Modifier.width(60.dp))
            Text(s.optString("label"), fontSize = 13.sp, color = Dx.Muted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            Text(a, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White, fontFeatureSettings = "tnum"), textAlign = TextAlign.End, modifier = Modifier.width(60.dp))
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.18f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(share.coerceIn(0f, 1f)).background(Color.White))
        }
    }
}

@Composable
private fun Lineup(team: String, players: JSONArray?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Heading("$team line-up")
        val list = players ?: JSONArray()
        for (i in 0 until list.length()) {
            val p = list.getJSONObject(i)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(p.optString("number"), style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Dx.Muted, fontFeatureSettings = "tnum"), modifier = Modifier.width(30.dp))
                Text(p.optString("name"), fontSize = 14.sp, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1)
                Text(p.optString("position"), fontSize = 12.sp, color = Dx.Faint)
            }
        }
    }
}
