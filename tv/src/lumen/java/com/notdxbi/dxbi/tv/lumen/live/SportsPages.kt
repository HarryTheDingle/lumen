package com.notdxbi.dxbi.tv.lumen.live

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.notdxbi.dxbi.tv.lumen.Dx
import com.notdxbi.dxbi.tv.live.LiveTvStore
import com.notdxbi.dxbi.tv.lumen.centredWhenFocused
import kotlinx.coroutines.delay
import lumos.iptv.FixtureStatus
import lumos.iptv.SavedSportsTeam
import lumos.iptv.SportsFixture
import lumos.iptv.SportsTeamSide
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---- The billboard: a big picture behind the top of the page, and the event's words, countdown and buttons over it ----

/** What a sport's billboard shows */
internal class BillboardData(
    val key: String,
    val art: String?,
    val kicker: String,
    val title: String,
    val line: String?,
    val home: SportsTeamSide? = null,
    val away: SportsTeamSide? = null,
    val live: Boolean = false,
    val countdownTo: Long? = null,
    val countdownLabel: String? = null,
    /** The main button; null: none (a match with no stream on the IPTV has no Watch) */
    val primary: String?,
    val primaryIcon: ImageVector = Icons.Filled.PlayArrow,
    val onPrimary: () -> Unit,
    val secondary: String? = null,
    val secondaryIcon: ImageVector? = null,
    val onSecondary: (() -> Unit)? = null,
    val accent: Color = Color(0xFFE10600),
    /** Full-length cut-outs of the two sides (UFC's fighters), stood on the right of the billboard */
    val cutouts: Pair<String?, String?>? = null,
)

/** How tall the billboard's picture is, from the very top of the page (it runs up behind the tabs and the clock) */
internal val BACKDROP_H = 470.dp

/**
 * The billboard's picture, drawn behind the page's list at the top of the screen and moving up with the billboard as the
 * page scrolls, as a film page's backdrop does; it fades into the black at its left and its foot.
 */
@Composable
internal fun BillboardBackdrop(art: String?, lists: LazyListState, page: String = "") {
    val h = with(LocalDensity.current) { BACKDROP_H.toPx() }
    // (a new tab's picture fades in once its page has settled, rather than jumping about as the page builds under it)
    var settled by remember(page) { mutableStateOf(false) }
    LaunchedEffect(page) { settled = false; delay(380); settled = true }
    val shown by androidx.compose.animation.core.animateFloatAsState(if (settled) 1f else 0f, tween(if (settled) 420 else 120), label = "backdrop-in")
    Box(
        Modifier.fillMaxWidth().height(BACKDROP_H).graphicsLayer {
            // (its foot stays level with the billboard's foot; off the page once the billboard is)
            val b = lists.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "billboard" }
            translationY = if (b != null) (b.offset + b.size - h).coerceAtMost(0f) else -h * 2
            alpha = shown
        },
    ) {
        Crossfade(targetState = art, animationSpec = tween(Dx.Slow), label = "backdrop", modifier = Modifier.fillMaxSize()) { url ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
            if (url != null) {
                var failed by remember(url) { mutableStateOf(false) }
                if (!failed) AsyncImage(
                    model = url, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter,
                    onError = { failed = true },
                    modifier = Modifier
                        .fillMaxWidth(0.74f).fillMaxHeight()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.42f to Color.Black), blendMode = BlendMode.DstIn)
                            drawRect(Brush.verticalGradient(0f to Color.Black, 0.62f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                        },
                )
            }
            }
        }
        // (the tabs and the clock stay readable over the picture's top)
        Box(Modifier.fillMaxWidth().height(110.dp).background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.55f), 1f to Color.Transparent)))
    }
}

/** The billboard's words, countdown and buttons; the picture is [BillboardBackdrop], behind the list */
@Composable
internal fun BigBillboard(d: BillboardData, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(360.dp)) {
        // the two fighters stood full-length on the right, red corner nearer the middle, facing each other
        d.cutouts?.let { (a, b) ->
            Row(Modifier.align(Alignment.BottomEnd).padding(end = 24.dp).height(430.dp), horizontalArrangement = Arrangement.spacedBy((-70).dp), verticalAlignment = Alignment.Bottom) {
                listOf(a, b).forEach { url ->
                    if (url != null) AsyncImage(
                        model = url, contentDescription = null, contentScale = ContentScale.Fit, alignment = Alignment.BottomCenter,
                        modifier = Modifier.width(300.dp).fillMaxHeight()
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawWithContent { drawContent(); drawRect(Brush.verticalGradient(0f to Color.Black, 0.8f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn) },
                    )
                }
            }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(start = Dx.Gutter, bottom = 18.dp).widthIn(max = 560.dp)) {
            if (d.home != null || d.away != null) {
                Row(horizontalArrangement = Arrangement.spacedBy((-12).dp), modifier = Modifier.padding(bottom = 10.dp)) {
                    CrestBadge(logoUrl = d.home?.logoUrl, name = d.home?.name ?: d.title, size = 58.dp)
                    CrestBadge(logoUrl = d.away?.logoUrl, name = d.away?.name ?: d.kicker, size = 58.dp)
                }
            }
            Text(d.kicker, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(d.title, fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            if (d.line != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                if (d.live) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(LivePalette.LiveRed))
                    Spacer(Modifier.width(7.dp))
                }
                Text(d.line, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.9f), fontFeatureSettings = "tnum"), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!d.live && d.countdownTo != null && d.countdownTo > System.currentTimeMillis()) {
                if (d.countdownLabel != null) Text(d.countdownLabel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                Countdown(d.countdownTo, d.accent)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
                if (d.primary != null) com.notdxbi.dxbi.tv.lumen.PlayButton(d.primary, onClick = d.onPrimary, icon = d.primaryIcon)
                if (d.secondary != null && d.onSecondary != null && d.secondaryIcon != null) {
                    com.notdxbi.dxbi.tv.lumen.RoundButton(d.secondaryIcon, d.secondary, onClick = d.onSecondary, expands = true)
                }
            }
        }
    }
}

/**
 * The time to go, large: days, hours, minutes and seconds each on its own glass plate with a fine line of the sport's
 * colour under it; the seconds tick over with a small roll, as a stadium clock's do.
 */
@Composable
internal fun Countdown(toMs: Long, accent: Color) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(toMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000L - now % 1000L)
        }
    }
    val left = (toMs - now).coerceAtLeast(0L)
    val units = listOf(
        left / 86_400_000L to "days",
        (left / 3_600_000L) % 24 to "hours",
        (left / 60_000L) % 60 to "minutes",
        (left / 1000L) % 60 to "seconds",
    )
    val plate = RoundedCornerShape(14.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        units.forEach { (value, label) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.width(76.dp).height(66.dp).clip(plate)
                        .background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.14f), 1f to Color.White.copy(alpha = 0.05f)))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), plate)
                        .drawBehind {
                            // (a fine line of the sport's colour along the plate's foot, glowing up into it)
                            drawRect(Brush.verticalGradient(0f to Color.Transparent, 1f to accent.copy(alpha = 0.35f)), topLeft = Offset(0f, size.height * 0.55f))
                            drawRect(accent, topLeft = Offset(0f, size.height - 3.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width, 3.dp.toPx()))
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(
                        targetState = value,
                        transitionSpec = {
                            (slideInVertically(tween(320)) { -it / 2 } + fadeIn(tween(320))) togetherWith (slideOutVertically(tween(320)) { it / 2 } + fadeOut(tween(220)))
                        },
                        label = "count-$label",
                    ) { v ->
                        Text(
                            if (label == "days") v.toString() else "%02d".format(v),
                            style = TextStyle(fontFamily = Condensed, fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Color.White, fontFeatureSettings = "tnum"),
                        )
                    }
                }
                Text(label, fontSize = 11.sp, letterSpacing = 1.sp, color = Color.White.copy(alpha = 0.55f), modifier = Modifier.padding(top = 5.dp))
            }
        }
    }
}

/** A panel's frame that can be chosen with the remote (so the page can stop on it and centre it), lighting its edge when it is */
@Composable
internal fun Modifier.panelFrame(onFocus: ((Boolean) -> Unit)? = null): Modifier {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Dx.RCard + 6.dp)
    return this
        .onFocusChanged { focused = it.isFocused; onFocus?.invoke(it.isFocused) }
        .focusable()
        .clip(shape)
        .background(Color.White.copy(alpha = if (focused) 0.10f else 0.05f))
        .border(if (focused) 2.dp else 1.dp, Color.White.copy(alpha = if (focused) 0.85f else 0.09f), shape)
}

// ---- Football: your teams, and the tables ----

/** A row of a league table */
internal class TableRow(
    val rank: Int, val id: String?, val name: String, val shortName: String?, val logoUrl: String?,
    val played: Int, val won: Int, val drawn: Int, val lost: Int, val goalDiff: String, val points: Int,
)

/** A league's table */
internal class LeagueTable(val league: String, val name: String, val rows: List<TableRow>)

internal fun parseTable(json: org.json.JSONObject?): LeagueTable? {
    json ?: return null
    val arr = json.optJSONArray("rows") ?: return null
    val rows = (0 until arr.length()).mapNotNull { i ->
        val r = arr.optJSONObject(i) ?: return@mapNotNull null
        TableRow(
            rank = r.optInt("rank", i + 1),
            id = r.optString("id").takeIf { it.isNotBlank() && it != "null" },
            name = r.optString("name"),
            shortName = r.optString("shortName").takeIf { it.isNotBlank() && it != "null" },
            logoUrl = r.optString("logoUrl").takeIf { it.isNotBlank() && it != "null" },
            played = r.optInt("played"), won = r.optInt("won"), drawn = r.optInt("drawn"), lost = r.optInt("lost"),
            goalDiff = r.optString("goalDiff", "0"), points = r.optInt("points"),
        )
    }
    return if (rows.isEmpty()) null else LeagueTable(json.optString("league"), json.optString("leagueName"), rows)
}

/** One of your teams: where it stands, its last five, and what's next */
internal class TeamCardData(val team: SavedSportsTeam, val row: TableRow?, val leagueName: String, val form: List<Char>, val next: SportsFixture?, val last: SportsFixture?)

internal fun ordinal(n: Int): String = n.toString() + when {
    n % 100 in 11..13 -> "th"
    n % 10 == 1 -> "st"
    n % 10 == 2 -> "nd"
    n % 10 == 3 -> "rd"
    else -> "th"
}

/** W, D or L for [teamId] in a finished match */
internal fun resultFor(f: SportsFixture, teamId: String): Char? {
    val home = f.home ?: return null
    val away = f.away ?: return null
    val hs = home.score?.toIntOrNull() ?: return null
    val aws = away.score?.toIntOrNull() ?: return null
    val mineHome = home.id == teamId
    val us = if (mineHome) hs else aws
    val them = if (mineHome) aws else hs
    return when { us > them -> 'W'; us < them -> 'L'; else -> 'D' }
}

private val FORM_COLOURS = mapOf('W' to Color(0xFF2FBF5B), 'D' to Color(0xFF8A8A92), 'L' to Color(0xFFE5484D))

/** A team's card: its crest large, its place in the league, its last five, and its next match; OK opens the next match */
@Composable
internal fun TeamCard(d: TeamCardData, nowMs: Long, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val tint = tintFor(d.team.name)
    GlassTile(
        onClick = onOpen,
        modifier = modifier.height(168.dp),
        shape = RoundedCornerShape(Dx.RCard + 6.dp),
        fillColor = Color(0xFF141417),
        sheen = true,
        focusedScale = Dx.FocusRow,
        maxGrow = 10.dp,
    ) { focused, ink ->
        Box(Modifier.fillMaxSize()) {
            if (!focused) Box(Modifier.fillMaxSize().drawBehind {
                drawRect(Brush.radialGradient(0f to tint.copy(alpha = 0.55f), 1f to Color.Transparent, center = Offset(0f, 0f), radius = size.width * 0.7f))
            })
            Row(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                CrestBadge(logoUrl = d.team.logoUrl ?: d.row?.logoUrl, name = d.team.name, size = 76.dp)
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(d.team.name, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            d.row?.let { "${ordinal(it.rank)} in the ${d.leagueName}" } ?: d.leagueName.takeIf { it.isNotBlank() },
                            d.row?.let { "${it.points} pts" },
                        ).joinToString("  ·  "),
                        fontSize = 13.sp, color = ink.copy(alpha = 0.7f), maxLines = 1,
                    )
                    if (d.form.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(top = 2.dp)) {
                        d.form.forEach { c ->
                            Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(FORM_COLOURS[c] ?: Color.Gray), contentAlignment = Alignment.Center) {
                                Text(c.toString(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                    d.next?.let { n ->
                        val home = n.home?.id == d.team.teamId
                        val other = (if (home) n.away else n.home)?.let { it.shortName ?: it.name } ?: n.name
                        Text(
                            listOfNotNull(
                                "Next: ${dayLabel(n.startMs, nowMs)} ${timeLabel(n.startMs)}",
                                "${if (home) "v" else "at"} $other",
                                n.ukChannelName,
                            ).joinToString("  ·  "),
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ink.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** The table's column heads */
@Composable
private fun TableHead(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("", modifier = Modifier.width(30.dp))
        Text("Team", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f), modifier = Modifier.weight(1f).padding(start = 40.dp))
        listOf("P", "W", "D", "L", "GD").forEach { Text(it, fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f), modifier = Modifier.width(46.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        Text("Pts", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f), modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** A table row: place, crest and name, the numbers; your team's row in its own colour */
@Composable
private fun TableLine(r: TableRow, mine: Boolean, modifier: Modifier = Modifier) {
    val tint = tintFor(r.name)
    GlassTile(
        onClick = {},
        modifier = modifier.fillMaxWidth().height(44.dp),
        shape = RoundedCornerShape(Dx.RCard),
        fillColor = if (mine) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.04f),
        borderColor = if (mine) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f),
        sheen = false,
        instantFill = true,
        focusedScale = 1.015f,
        maxGrow = 6.dp,
    ) { focused, ink ->
        Box(Modifier.fillMaxSize()) {
            if (mine && !focused) Box(Modifier.fillMaxSize().drawBehind {
                drawRect(Brush.horizontalGradient(0f to tint.copy(alpha = 0.6f), 0.5f to Color.Transparent))
            })
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.rank.toString(), style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = ink.copy(alpha = 0.8f), fontFeatureSettings = "tnum"), modifier = Modifier.width(30.dp))
                CrestBadge(logoUrl = r.logoUrl, name = r.name, size = 26.dp)
                Text(r.name, fontSize = 15.sp, fontWeight = if (mine) FontWeight.Bold else FontWeight.SemiBold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 14.dp))
                listOf(r.played, r.won, r.drawn, r.lost).forEach {
                    Text(it.toString(), style = TextStyle(fontSize = 14.sp, color = ink.copy(alpha = 0.8f), fontFeatureSettings = "tnum"), modifier = Modifier.width(46.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                Text(r.goalDiff, style = TextStyle(fontSize = 14.sp, color = ink.copy(alpha = 0.8f), fontFeatureSettings = "tnum"), modifier = Modifier.width(46.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text(r.points.toString(), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ink, fontFeatureSettings = "tnum"), modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

/** Your teams, as large cards two to a row */
internal fun LazyListScope.teamsSection(cards: List<TeamCardData>, nowMs: Long, seen: MutableSet<String>, onOpen: (TeamCardData) -> Unit) {
    if (cards.isEmpty()) return
    item(key = "h:teams") { SectionName("Your teams", Modifier.loadIn(seen, "h:teams")) }
    itemsIndexed(cards.chunked(2), key = { _, row -> "team:${row.first().team.teamId}" }) { i, row ->
        Row(Modifier.padding(horizontal = Dx.Gutter, vertical = 7.dp).fillMaxWidth().loadIn(seen, "team:${row.first().team.teamId}", i).centredWhenFocused(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            row.forEach { c -> TeamCard(c, nowMs, onOpen = { onOpen(c) }, modifier = Modifier.weight(1f)) }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

/** A league table, a row at a time so the remote moves down it and the page follows; [window] keeps only the rows around your team */
internal fun LazyListScope.tableSection(table: LeagueTable, mineIds: Set<String>, seen: MutableSet<String>, window: Boolean = false) {
    val rows = if (!window) table.rows else {
        val at = table.rows.indexOfFirst { it.id in mineIds }
        if (at < 0) table.rows.take(8) else table.rows.subList((at - 3).coerceAtLeast(0), (at + 4).coerceAtMost(table.rows.size))
    }
    if (rows.isEmpty()) return
    val k = "table:${table.league}"
    item(key = "h:$k") { SectionName(table.name.ifBlank { "Table" }, Modifier.loadIn(seen, "h:$k")) }
    item(key = "head:$k") { TableHead(Modifier.padding(horizontal = Dx.Gutter).padding(bottom = 6.dp).loadIn(seen, "head:$k")) }
    itemsIndexed(rows, key = { _, r -> "$k:${r.id ?: r.name}" }) { i, r ->
        TableLine(r, mine = r.id in mineIds, modifier = Modifier.padding(horizontal = Dx.Gutter, vertical = 3.dp).loadIn(seen, "$k:${r.id ?: r.name}", i.coerceAtMost(8)).centredWhenFocused())
    }
}

// ---- Formula 1: the race weekend, and the circuit ----

internal class F1Weekend(
    val season: Int, val round: Int?, val raceName: String, val officialName: String?,
    val circuitName: String, val locality: String, val country: String, val circuitType: String?, val laps: Int?,
    val trackX: FloatArray, val trackY: FloatArray, val rotation: Float, val corners: Int?,
    val lastWinner: String?, val lastWinnerTeam: String?, val lastWinnerTime: String?,
    val sessions: List<Pair<String, Long>>,
    val thumb: String?, val banner: String?,
) {
    val raceMs: Long? get() = sessions.lastOrNull { it.first == "Race" }?.second
}

internal fun parseWeekend(j: org.json.JSONObject?): F1Weekend? {
    j ?: return null
    val c = j.optJSONObject("circuit") ?: org.json.JSONObject()
    val t = c.optJSONObject("track")
    val xs = t?.optJSONArray("x"); val ys = t?.optJSONArray("y")
    val n = if (xs != null && ys != null) minOf(xs.length(), ys.length()) else 0
    val ss = j.optJSONArray("sessions")
    val w = j.optJSONObject("lastWinner")
    val art = j.optJSONObject("art")
    fun s(o: org.json.JSONObject?, k: String) = o?.optString(k)?.takeIf { it.isNotBlank() && it != "null" }
    return F1Weekend(
        season = j.optInt("season"), round = j.optInt("round").takeIf { it > 0 },
        raceName = j.optString("raceName"), officialName = s(j, "officialName"),
        circuitName = c.optString("name"), locality = c.optString("locality"), country = c.optString("country"),
        circuitType = s(c, "type"), laps = c.optInt("laps").takeIf { it > 0 },
        trackX = FloatArray(n) { xs!!.optDouble(it).toFloat() }, trackY = FloatArray(n) { ys!!.optDouble(it).toFloat() },
        rotation = t?.optDouble("rotation")?.toFloat() ?: 0f, corners = t?.optInt("corners")?.takeIf { it > 0 },
        lastWinner = s(w, "name"), lastWinnerTeam = s(w, "team"), lastWinnerTime = s(w, "time"),
        sessions = if (ss == null) emptyList() else (0 until ss.length()).mapNotNull { i -> ss.optJSONObject(i)?.let { it.optString("label") to it.optLong("startMs") } }.filter { it.second > 0 },
        thumb = s(art, "thumb"), banner = s(art, "banner"),
    )
}

private val dayFmt = SimpleDateFormat("EEE", Locale.UK)

/**
 * The weekend's sessions as lines of text in one panel (day, time, session), the one to come next marked in the race's red,
 * the ones done faded with a tick; then where to watch.
 */
@Composable
internal fun WeekendPanel(w: F1Weekend, nowMs: Long, channel: String?, modifier: Modifier = Modifier) {
    val next = w.sessions.firstOrNull { it.second + 2 * 3_600_000L > nowMs }
    Column(modifier.panelFrame().padding(horizontal = 24.dp, vertical = 20.dp)) {
        Text("This weekend", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(listOfNotNull(w.round?.let { "Round $it" }, "${w.locality}, ${w.country}").joinToString("  ·  "), fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
        w.sessions.forEach { (label, at) ->
            val done = at + 2 * 3_600_000L <= nowMs
            val isNext = next?.first == label && next.second == at
            val on = nowMs in at..(at + 2 * 3_600_000L) && label != "Race" || (label == "Race" && nowMs in at..(at + 2 * 3_600_000L))
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(18.dp), contentAlignment = Alignment.CenterStart) {
                    when {
                        on -> Box(Modifier.size(8.dp).clip(CircleShape).background(LivePalette.LiveRed))
                        isNext -> Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFE10600)))
                        done -> androidx.tv.material3.Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(13.dp))
                    }
                }
                Text(dayFmt.format(Date(at)), style = TextStyle(fontSize = 15.sp, color = Color.White.copy(alpha = if (done) 0.4f else 0.7f), fontFeatureSettings = "tnum"), modifier = Modifier.width(48.dp))
                Text(timeLabel(at), style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = if (done) 0.4f else 0.9f), fontFeatureSettings = "tnum"), modifier = Modifier.width(62.dp))
                Text(
                    label, fontSize = 16.sp,
                    fontWeight = if (label == "Race" || isNext) FontWeight.Bold else FontWeight.SemiBold,
                    color = Color.White.copy(alpha = if (done) 0.4f else 1f), modifier = Modifier.weight(1f),
                )
                if (on) Text("Live", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LivePalette.LiveRed)
                else if (isNext) Text("Next", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF4B3E))
            }
        }
        if (channel != null) Text("On $channel", fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(top = 12.dp))
    }
}

/** The circuit drawn from its outline, in the race's red with a soft glow under it, and the circuit's facts beside it */
@Composable
internal fun CircuitPanel(w: F1Weekend, modifier: Modifier = Modifier) {
    Row(modifier.panelFrame().padding(horizontal = 24.dp, vertical = 20.dp)) {
        Column(Modifier.width(210.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("The circuit", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(w.circuitName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.9f), maxLines = 2)
            Fact("Type", w.circuitType?.replace(" - ", ", ")?.let { "$it circuit" })
            Fact("Laps", w.laps?.toString())
            Fact("Corners", w.corners?.toString())
            Fact("${w.season - 1} winner", w.lastWinner?.let { n -> listOfNotNull(n, w.lastWinnerTeam).joinToString(", ") })
        }
        Spacer(Modifier.width(12.dp))
        TrackMap(w, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun Fact(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Column {
        Text(label, fontSize = 11.sp, letterSpacing = 0.5.sp, color = Color.White.copy(alpha = 0.5f))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2)
    }
}

/** The outline, turned as Formula 1's own maps are (its rotation, then flipped top to bottom), fitted into the space */
@Composable
internal fun TrackMap(w: F1Weekend, modifier: Modifier = Modifier) {
    if (w.trackX.size < 10) return
    val red = Color(0xFFE10600)
    Canvas(modifier) {
        val a = Math.toRadians(w.rotation.toDouble())
        val cx = w.trackX.average().toFloat(); val cy = w.trackY.average().toFloat()
        val cos = kotlin.math.cos(a).toFloat(); val sin = kotlin.math.sin(a).toFloat()
        val px = FloatArray(w.trackX.size); val py = FloatArray(w.trackX.size)
        for (i in w.trackX.indices) {
            val x = w.trackX[i] - cx; val y = w.trackY[i] - cy
            px[i] = x * cos - y * sin
            py[i] = -(x * sin + y * cos)
        }
        val minX = px.min(); val maxX = px.max(); val minY = py.min(); val maxY = py.max()
        val pad = 14.dp.toPx()
        val scale = minOf((size.width - pad * 2) / (maxX - minX), (size.height - pad * 2) / (maxY - minY))
        val ox = (size.width - (maxX - minX) * scale) / 2f; val oy = (size.height - (maxY - minY) * scale) / 2f
        val path = Path()
        for (i in px.indices) {
            val X = ox + (px[i] - minX) * scale; val Y = oy + (py[i] - minY) * scale
            if (i == 0) path.moveTo(X, Y) else path.lineTo(X, Y)
        }
        path.close()
        drawPath(path, red.copy(alpha = 0.18f), style = Stroke(width = 18.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, red.copy(alpha = 0.35f), style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, Color.White, style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        // the start and finish
        val sx = ox + (px[0] - minX) * scale; val sy = oy + (py[0] - minY) * scale
        drawCircle(red, radius = 7.dp.toPx(), center = Offset(sx, sy))
        drawCircle(Color.White, radius = 3.dp.toPx(), center = Offset(sx, sy))
    }
}

/** The last weekend's sessions in the replay addon, as wide cards with the race's picture, to play */
internal fun LazyListScope.replayCards(round: LiveTvStore.MotorRound?, art: String?, seen: MutableSet<String>, onPick: (LiveTvStore.MotorRound, LiveTvStore.MotorSession) -> Unit) {
    round ?: return
    if (round.sessions.isEmpty()) return
    item(key = "h:replays") { SectionName("Replays · ${round.place}", Modifier.loadIn(seen, "h:replays")) }
    item(key = "replays") {
        LazyRow(
            modifier = Modifier.fillMaxWidth().loadIn(seen, "replays").centredWhenFocused(),
            contentPadding = PaddingValues(horizontal = Dx.Gutter, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(round.sessions, key = { _, s -> s.videoId }) { _, s ->
                GlassTile(
                    onClick = { onPick(round, s) },
                    modifier = Modifier.width(250.dp).height(141.dp),
                    shape = RoundedCornerShape(Dx.RCard + 2.dp),
                    fillColor = Color(0xFF1A0B0B),
                    sheen = true,
                    focusedScale = Dx.FocusRow,
                ) { focused, _ ->
                    Box(Modifier.fillMaxSize()) {
                        if (art != null) AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF6A0A0A), Color(0xFF160404)))))
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
                        Row(Modifier.align(Alignment.BottomStart).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            androidx.tv.material3.Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(s.label, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                        }
                        if (focused) Box(Modifier.fillMaxSize().border(3.dp, Color.White, RoundedCornerShape(Dx.RCard + 2.dp)))
                    }
                }
            }
        }
    }
}
