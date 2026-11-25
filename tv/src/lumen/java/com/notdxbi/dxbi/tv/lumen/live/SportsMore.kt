package com.notdxbi.dxbi.tv.lumen.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.notdxbi.dxbi.tv.lumen.Dx
import com.notdxbi.dxbi.tv.lumen.centredWhenFocused
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---- What the Worker sends for UFC, tennis and golf ----

private fun org.json.JSONObject.str(k: String): String? = optString(k).takeIf { it.isNotBlank() && it != "null" }
private fun org.json.JSONObject.ms(k: String): Long? = optLong(k, 0L).takeIf { it > 0L }
private fun org.json.JSONArray?.objects(): List<org.json.JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

internal class Fighter(val name: String, val short: String?, val record: String?, val flag: String?, val headshot: String?, val cutout: String?, val winner: Boolean)
internal class Bout(val id: String, val weight: String, val main: Boolean, val startMs: Long?, val state: String, val result: String?, val red: Fighter, val blue: Fighter)
internal class UfcCard(val name: String, val startMs: Long?, val venue: String?, val bouts: List<Bout>)

internal fun parseUfc(j: org.json.JSONObject?): UfcCard? {
    j ?: return null
    fun f(o: org.json.JSONObject?) = Fighter(o?.str("name") ?: "", o?.str("short"), o?.str("record"), o?.str("flag"), o?.str("headshot"), o?.str("cutout"), o?.optBoolean("winner") == true)
    val bouts = j.optJSONArray("bouts").objects().map { b ->
        Bout(b.str("id") ?: "", b.str("weight") ?: "", b.optBoolean("main"), b.ms("startMs"), b.str("state") ?: "UPCOMING", b.str("result"), f(b.optJSONObject("red")), f(b.optJSONObject("blue")))
    }
    return if (bouts.isEmpty()) null else UfcCard(j.str("name") ?: "UFC", j.ms("startMs"), j.str("venue"), bouts)
}

internal class TPlayer(val name: String, val short: String?, val flag: String?, val seed: Int?, val headshot: String?, val sets: List<Int>, val winner: Boolean)
internal class TMatch(val id: String, val startMs: Long?, val state: String, val p1: TPlayer, val p2: TPlayer)
internal class TRound(val name: String, val matches: List<TMatch>)
internal class TDraw(val name: String, val rounds: List<TRound>)
internal class TEvent(val name: String, val major: Boolean, val startMs: Long?, val endMs: Long?, val state: String, val draws: List<TDraw>)
internal class Slam(val name: String, val startMs: Long?, val endMs: Long?, val state: String, val men: String?, val women: String?, val lastMen: String?, val lastWomen: String?)
internal class Ranked(val rank: Int, val previous: Int?, val points: Double?, val name: String, val flag: String?, val headshot: String?)
internal class Tennis(val atp: List<Ranked>, val wta: List<Ranked>, val slams: List<Slam>, val events: List<TEvent>)

internal fun parseTennis(j: org.json.JSONObject?): Tennis? {
    j ?: return null
    fun p(o: org.json.JSONObject?) = TPlayer(
        o?.str("name") ?: "", o?.str("short"), o?.str("flag"), o?.optInt("seed", 0)?.takeIf { it > 0 }, o?.str("headshot"),
        o?.optJSONArray("sets")?.let { a -> (0 until a.length()).map { a.optDouble(it).toInt() } } ?: emptyList(), o?.optBoolean("winner") == true,
    )
    fun ranks(a: org.json.JSONArray?) = a.objects().map { Ranked(it.optInt("rank"), it.optInt("previous", 0).takeIf { v -> v > 0 }, it.optDouble("points").takeIf { v -> !v.isNaN() }, it.str("name") ?: "", it.str("flag"), it.str("headshot")) }
    val r = j.optJSONObject("rankings")
    return Tennis(
        atp = ranks(r?.optJSONArray("atp")), wta = ranks(r?.optJSONArray("wta")),
        slams = j.optJSONArray("slams").objects().map { s ->
            fun champ(k: String) = s.optJSONObject(k)?.str("name")
            Slam(s.str("name") ?: "", s.ms("startMs"), s.ms("endMs"), s.str("state") ?: "UPCOMING", champ("men"), champ("women"), s.str("lastMen"), s.str("lastWomen"))
        },
        events = j.optJSONArray("events").objects().map { e ->
            TEvent(e.str("name") ?: "", e.optBoolean("major"), e.ms("startMs"), e.ms("endMs"), e.str("state") ?: "", e.optJSONArray("draws").objects().map { d ->
                TDraw(d.str("name") ?: "", d.optJSONArray("rounds").objects().map { rd ->
                    TRound(rd.str("name") ?: "", rd.optJSONArray("matches").objects().map { m -> TMatch(m.str("id") ?: "", m.ms("startMs"), m.str("state") ?: "", p(m.optJSONObject("p1")), p(m.optJSONObject("p2"))) })
                })
            })
        },
    )
}

internal class GolfRow(val pos: Int, val tied: Boolean, val name: String, val flag: String?, val headshot: String?, val toPar: String, val rounds: List<Int>, val thru: String?)
internal class GolfEvent(val name: String, val startMs: Long?, val endMs: Long?, val state: String, val course: String?, val rows: List<GolfRow>)
internal class Golf(val current: GolfEvent?, val last: GolfEvent?)

internal fun parseGolf(j: org.json.JSONObject?): Golf? {
    j ?: return null
    fun ev(o: org.json.JSONObject?): GolfEvent? = o?.let { e ->
        GolfEvent(e.str("name") ?: "", e.ms("startMs"), e.ms("endMs"), e.str("state") ?: "", e.str("course"), e.optJSONArray("rows").objects().map { r ->
            GolfRow(r.optInt("pos"), r.optBoolean("tied"), r.str("name") ?: "", r.str("flag"), r.str("headshot"), r.str("toPar") ?: "E",
                r.optJSONArray("rounds").objects().map { it.optInt("strokes") }, r.str("thru"))
        })
    }
    return Golf(ev(j.optJSONObject("current")), ev(j.optJSONObject("last")))
}

private val dayMonth = SimpleDateFormat("d MMM", Locale.UK)
private val dayMonthYear = SimpleDateFormat("d MMM yyyy", Locale.UK)
internal fun spanLabel(from: Long?, to: Long?): String = when {
    from == null -> ""
    to == null || to - from < 86_400_000L -> dayMonth.format(Date(from))
    else -> "${dayMonth.format(Date(from))} – ${dayMonthYear.format(Date(to - 3_600_000L))}"
}

// ---- Small parts ----

/** A round photo of a player, with their flag tucked at its foot */
@Composable
internal fun PlayerFace(headshot: String?, flag: String?, name: String, size: Dp) {
    Box(Modifier.size(size)) {
        Box(Modifier.size(size).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.BottomCenter) {
            if (headshot != null) AsyncImage(model = headshot, contentDescription = name, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
        }
        if (flag != null) AsyncImage(model = flag, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.align(Alignment.BottomEnd).size(size * 0.36f).clip(CircleShape).background(Color.Black))
    }
}

/** A row the remote can stop on (so the page centres it), white when chosen, as a guide cell */
@Composable
private fun Line(modifier: Modifier = Modifier, height: Dp = 52.dp, highlight: Boolean = false, onClick: () -> Unit = {}, content: @Composable androidx.compose.foundation.layout.RowScope.(Color) -> Unit) {
    GlassTile(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(height),
        shape = RoundedCornerShape(Dx.RCard),
        fillColor = if (highlight) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.04f),
        borderColor = Color.White.copy(alpha = 0.07f),
        sheen = false,
        instantFill = true,
        focusedScale = 1.015f,
        maxGrow = 6.dp,
    ) { _, ink ->
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) { content(ink) }
    }
}

// ---- UFC ----

/** One bout: the two fighters at either end with their faces, records and flags, the weight in the middle */
@Composable
private fun BoutCard(b: Bout, modifier: Modifier = Modifier, onClick: () -> Unit) {
    GlassTile(
        onClick = onClick,
        modifier = modifier.height(if (b.main) 132.dp else 128.dp),
        shape = RoundedCornerShape(Dx.RCard + 6.dp),
        fillColor = Color(0xFF141417),
        sheen = true,
        focusedScale = Dx.FocusRow,
        maxGrow = 10.dp,
    ) { focused, ink ->
        Box(Modifier.fillMaxSize()) {
            if (!focused) Box(Modifier.fillMaxSize().drawBehind {
                drawRect(Brush.radialGradient(0f to Color(0xFFD20A0A).copy(alpha = 0.45f), 1f to Color.Transparent, center = Offset(0f, 0f), radius = size.width * 0.5f))
                drawRect(Brush.radialGradient(0f to Color(0xFF1F4FD1).copy(alpha = 0.40f), 1f to Color.Transparent, center = Offset(size.width, size.height), radius = size.width * 0.5f))
            })
            Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                @Composable fun side(f: Fighter, end: Boolean) {
                    val faded = ink.copy(alpha = if (b.state == "FINAL" && !f.winner) 0.55f else 1f)
                    val record = listOfNotNull(f.record, if (b.state == "FINAL" && f.winner) "Won" else null).joinToString("  ·  ")
                    if (b.main) Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (end) Arrangement.End else Arrangement.Start) {
                        // (the main event: face beside the full name)
                        if (!end) PlayerFace(f.headshot, f.flag, f.name, 72.dp)
                        Column(Modifier.weight(1f, fill = false).padding(horizontal = 12.dp), horizontalAlignment = if (end) Alignment.End else Alignment.Start) {
                            Text(f.name, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = faded, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(record, fontSize = 13.sp, color = ink.copy(alpha = 0.65f), maxLines = 1)
                        }
                        if (end) PlayerFace(f.headshot, f.flag, f.name, 72.dp)
                    } else Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        // (two to a row: the face above the name, so the name has the whole side)
                        PlayerFace(f.headshot, f.flag, f.name, 50.dp)
                        Text(f.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = faded, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        Text(record, fontSize = 12.sp, color = ink.copy(alpha = 0.65f), maxLines = 1)
                    }
                }
                side(b.red, false)
                Column(Modifier.width(if (b.main) 120.dp else 96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (b.main) "Main event" else b.weight, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ink.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("v", style = TextStyle(fontFamily = Condensed, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = ink))
                    if (b.main) Text(b.weight, fontSize = 12.sp, color = ink.copy(alpha = 0.6f), maxLines = 1)
                    if (b.state == "FINAL") b.result?.let { Text(it, fontSize = 11.sp, color = ink.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                side(b.blue, true)
            }
        }
    }
}

/** The card: the main event across the page, then the rest of the main card and the prelims, two to a row */
internal fun LazyListScope.ufcSections(card: UfcCard, seen: MutableSet<String>, onBout: (Bout) -> Unit) {
    val main = card.bouts.first()
    item(key = "h:main") { SectionName("Main event", Modifier.loadIn(seen, "h:main")) }
    item(key = "bout:${main.id}") {
        BoutCard(main, Modifier.padding(horizontal = Dx.Gutter, vertical = 6.dp).fillMaxWidth().loadIn(seen, "bout:${main.id}").centredWhenFocused()) { onBout(main) }
    }
    fun group(title: String, list: List<Bout>) {
        if (list.isEmpty()) return
        item(key = "h:$title") { SectionName(title, Modifier.loadIn(seen, "h:$title")) }
        itemsIndexed(list.chunked(2), key = { _, row -> "bout:${row.first().id}" }) { i, row ->
            Row(Modifier.padding(horizontal = Dx.Gutter, vertical = 6.dp).fillMaxWidth().loadIn(seen, "bout:${row.first().id}", i).centredWhenFocused(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { b -> BoutCard(b, Modifier.weight(1f)) { onBout(b) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    group("Main card", card.bouts.drop(1).take(4))
    group("Prelims", card.bouts.drop(5))
}

// ---- Tennis ----

/** The slams: four to a row, each its dates and either its champions, how long until it starts, or that it's on */
internal fun LazyListScope.slamSection(slams: List<Slam>, nowMs: Long, seen: MutableSet<String>) {
    // (the latest four: this year's still to come and next year's after; or this year's, when they're all done)
    val shown = slams.filter { (it.endMs ?: 0L) > nowMs - 300L * 86_400_000L }.take(4)
    if (shown.isEmpty()) return
    item(key = "h:slams") { SectionName("Grand Slams", Modifier.loadIn(seen, "h:slams")) }
    item(key = "slams") {
        Row(Modifier.padding(horizontal = Dx.Gutter, vertical = 6.dp).fillMaxWidth().height(150.dp).loadIn(seen, "slams").centredWhenFocused(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            shown.forEach { s ->
                val tint = when {
                    s.name.contains("Wimbledon", true) -> Color(0xFF1C6B3A)
                    s.name.contains("Roland", true) || s.name.contains("French", true) -> Color(0xFFB4542A)
                    s.name.contains("Australian", true) -> Color(0xFF1F6FD1)
                    else -> Color(0xFF1B2D7A)
                }
                GlassTile(onClick = {}, modifier = Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(Dx.RCard + 6.dp), fillColor = Color(0xFF141417), sheen = true, focusedScale = Dx.FocusRow, maxGrow = 8.dp) { focused, ink ->
                    Box(Modifier.fillMaxSize()) {
                        if (!focused) Box(Modifier.fillMaxSize().drawBehind { drawRect(Brush.radialGradient(0f to tint.copy(alpha = 0.7f), 1f to Color.Transparent, center = Offset(0f, 0f), radius = size.width * 0.9f)) })
                        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(s.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(spanLabel(s.startMs, s.endMs), fontSize = 12.sp, color = ink.copy(alpha = 0.7f))
                            Spacer(Modifier.weight(1f))
                            when (s.state) {
                                "FINAL" -> {
                                    s.men?.let { Text("Men: $it", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    s.women?.let { Text("Women: $it", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                }
                                "LIVE" -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(8.dp).clip(CircleShape).background(LivePalette.LiveRed)); Spacer(Modifier.width(6.dp))
                                    Text("On now", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ink)
                                }
                                else -> {
                                    val days = ((s.startMs ?: nowMs) - nowMs) / 86_400_000L
                                    Text(if (days <= 0) "Starts today" else "In $days days", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = ink)
                                    if (s.lastMen != null) Text("Holders: ${s.lastMen.substringAfterLast(' ')}, ${s.lastWomen?.substringAfterLast(' ') ?: ""}".trimEnd(',', ' '), fontSize = 12.sp, color = ink.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One player in a draw: seed, flag, name, and the sets they won in bold */
@Composable
private fun DrawPlayer(p: TPlayer, other: TPlayer, ink: Color, done: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        if (p.flag != null) AsyncImage(model = p.flag, contentDescription = null, modifier = Modifier.size(16.dp).clip(CircleShape))
        Spacer(Modifier.width(7.dp))
        Text(
            (p.seed?.let { "($it) " } ?: "") + (p.short ?: p.name),
            fontSize = 14.sp, fontWeight = if (p.winner) FontWeight.Bold else FontWeight.SemiBold,
            color = ink.copy(alpha = if (done && !p.winner) 0.55f else 1f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        p.sets.forEachIndexed { i, s ->
            val won = s > (other.sets.getOrNull(i) ?: -1)
            Text(s.toString(), style = TextStyle(fontSize = 14.sp, fontWeight = if (won) FontWeight.Bold else FontWeight.Normal, color = ink.copy(alpha = if (won) 1f else 0.55f), fontFeatureSettings = "tnum"), modifier = Modifier.width(16.dp), textAlign = TextAlign.Center)
        }
    }
}

/** A draw's later rounds side by side, as a bracket: each round a column of its matches */
internal fun LazyListScope.drawSection(event: TEvent, draw: TDraw, nowMs: Long, seen: MutableSet<String>) {
    val rounds = draw.rounds.filter { it.matches.isNotEmpty() }.takeLast(4)
    if (rounds.isEmpty()) return
    val k = "draw:${event.name}:${draw.name}"
    item(key = "h:$k") { SectionName("${draw.name}  ·  ${event.name}", Modifier.loadIn(seen, "h:$k")) }
    item(key = k) {
        val tallest = rounds.maxOf { it.matches.size }.coerceAtMost(8)
        LazyRow(
            Modifier.fillMaxWidth().loadIn(seen, k).centredWhenFocused(),
            contentPadding = PaddingValues(horizontal = Dx.Gutter, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(rounds, key = { _, r -> r.name }) { _, r ->
                Column(Modifier.width(290.dp).height((tallest * 78 + 30).dp), verticalArrangement = Arrangement.SpaceAround) {
                    Text(r.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.6f))
                    r.matches.take(8).forEach { m ->
                        GlassTile(onClick = {}, modifier = Modifier.fillMaxWidth().height(66.dp), shape = RoundedCornerShape(Dx.RCard), fillColor = Color.White.copy(alpha = 0.05f), borderColor = Color.White.copy(alpha = 0.08f), sheen = false, instantFill = true, focusedScale = 1.02f, maxGrow = 6.dp) { _, ink ->
                            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                DrawPlayer(m.p1, m.p2, ink, m.state == "FINAL")
                                DrawPlayer(m.p2, m.p1, ink, m.state == "FINAL")
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The world rankings, men's and women's side by side, a line each with the player's face and movement */
internal fun LazyListScope.rankingsSection(t: Tennis, seen: MutableSet<String>) {
    if (t.atp.isEmpty() && t.wta.isEmpty()) return
    item(key = "h:ranks") { SectionName("World rankings", Modifier.loadIn(seen, "h:ranks")) }
    val n = maxOf(t.atp.size, t.wta.size).coerceAtMost(10)
    itemsIndexed((0 until n).toList(), key = { _, i -> "rank:$i" }) { i, _ ->
        Row(Modifier.padding(horizontal = Dx.Gutter, vertical = 3.dp).fillMaxWidth().loadIn(seen, "rank:$i", i).centredWhenFocused(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf(t.atp.getOrNull(i), t.wta.getOrNull(i)).forEach { r ->
                if (r == null) Spacer(Modifier.weight(1f)) else Line(Modifier.weight(1f), height = 54.dp) { ink ->
                    Text(r.rank.toString(), style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ink, fontFeatureSettings = "tnum"), modifier = Modifier.width(30.dp))
                    PlayerFace(r.headshot, r.flag, r.name, 38.dp)
                    Text(r.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 12.dp))
                    val move = r.previous?.let { it - r.rank } ?: 0
                    if (move != 0) Text(if (move > 0) "▲$move" else "▼${-move}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (move > 0) Color(0xFF2FBF5B) else Color(0xFFE5484D), modifier = Modifier.padding(end = 10.dp))
                    r.points?.let { Text("%,d".format(it.toInt()), style = TextStyle(fontSize = 14.sp, color = ink.copy(alpha = 0.75f), fontFeatureSettings = "tnum")) }
                }
            }
        }
    }
}

// ---- Golf ----

/** A leaderboard, as the PGA Tour's: position (T for a tie), the player, to par, each round's score */
internal fun LazyListScope.golfSection(title: String, e: GolfEvent, seen: MutableSet<String>, max: Int = 30) {
    if (e.rows.isEmpty()) return
    val k = "golf:${e.name}"
    item(key = "h:$k") { SectionName(title, Modifier.loadIn(seen, "h:$k")) }
    item(key = "head:$k") {
        Row(Modifier.padding(horizontal = Dx.Gutter + 16.dp).fillMaxWidth().padding(bottom = 4.dp).loadIn(seen, "head:$k"), verticalAlignment = Alignment.CenterVertically) {
            Text("Pos", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f), modifier = Modifier.width(46.dp))
            Text("Player", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f), modifier = Modifier.weight(1f).padding(start = 50.dp))
            Text("To par", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f), modifier = Modifier.width(70.dp), textAlign = TextAlign.Center)
            (1..4).forEach { Text("R$it", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f), modifier = Modifier.width(50.dp), textAlign = TextAlign.Center) }
        }
    }
    itemsIndexed(e.rows.take(max), key = { i, r -> "$k:$i:${r.name}" }) { i, r ->
        Line(Modifier.padding(horizontal = Dx.Gutter, vertical = 3.dp).loadIn(seen, "$k:$i", i.coerceAtMost(8)).centredWhenFocused(), height = 50.dp, highlight = r.pos == 1) { ink ->
            Text((if (r.tied) "T" else "") + r.pos, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ink, fontFeatureSettings = "tnum"), modifier = Modifier.width(46.dp))
            PlayerFace(r.headshot, r.flag, r.name, 36.dp)
            Text(r.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 14.dp))
            val under = r.toPar.startsWith("-")
            Text(r.toPar, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (under && ink == Color.White) Color(0xFFFF6B5E) else ink, fontFeatureSettings = "tnum"), modifier = Modifier.width(70.dp), textAlign = TextAlign.Center)
            (0 until 4).forEach { n -> Text(r.rounds.getOrNull(n)?.toString() ?: "–", style = TextStyle(fontSize = 14.sp, color = ink.copy(alpha = 0.75f), fontFeatureSettings = "tnum"), modifier = Modifier.width(50.dp), textAlign = TextAlign.Center) }
        }
    }
}
