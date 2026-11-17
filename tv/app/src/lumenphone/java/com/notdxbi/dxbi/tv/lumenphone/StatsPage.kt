package com.notdxbi.dxbi.tv.lumenphone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.notdxbi.dxbi.tv.phone.Stremio
import com.notdxbi.dxbi.tv.phone.objects
import com.notdxbi.dxbi.tv.phone.text
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/**
 * Your stats (iOS StatsView): how long they've watched, what and when (the plugin's Watch stats, counted on this
 * phone). Bars and bars of white on black, as Apple TV draws progress: no accent colour.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatsPage(stremio: Stremio, layout: AppLayout, bottomSpace: Dp, onBack: () -> Unit) {
    var stats by remember { mutableStateOf<JSONObject?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        stats = stremio.stats()
        loaded = true
    }
    ProfilePage("Your Stats", layout, bottomSpace, onBack) {
        val s = stats
        when {
            s != null -> statsContent(s, layout)
            loaded -> item {
                Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Watch Stats Are Off", color = Dx.Text, style = MaterialTheme.typography.titleLarge)
                    Text("Turn on Watch stats in Lumen Settings to count how long you watch.", color = Dx.Muted, style = MaterialTheme.typography.bodyMedium)
                }
            }
            else -> item { Box(Modifier.fillMaxWidth().padding(60.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Dx.Text) } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
private fun androidx.compose.foundation.lazy.LazyListScope.statsContent(s: JSONObject, layout: AppLayout) {
    val year = s.optString("year")
    item {
        val since = DateFormat.getDateInstance(DateFormat.LONG).format(Date(s.optLong("since")))
        Text("${statsTime(s.optDouble("total"))} in total · since $since", color = Dx.Muted, style = MaterialTheme.typography.bodyMedium)
    }
    item {
        val streak = s.optJSONObject("streak")
        val tiles = s.optJSONArray("tiles").objects().map { Triple(it.optString("label"), statsTime(it.optDouble("sec")), null as String?) } +
            Triple("Streak", "${streak?.optInt("current") ?: 0} days", "best ${streak?.optInt("best") ?: 0}")
        // two across on a phone, three on the opened Fold
        val across = if (layout.compact) 2 else 3
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            tiles.chunked(across).forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    line.forEach { (label, value, note) -> StatsTile(label, value, note, Modifier.weight(1f)) }
                    repeat(across - line.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
    item { StatsSection("Last 30 Days") {
        val days = s.optJSONArray("days").objects().map { it.optDouble("sec") }
        val peak = (days.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
        Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
            days.forEach { sec ->
                Box(
                    Modifier.weight(1f).fillMaxHeight((sec / peak).toFloat().coerceAtLeast(0.03f)).clip(RoundedCornerShape(3.dp))
                        .background(if (sec > 0) Dx.Selected else Dx.Line),
                )
            }
        }
        if (days.any { it > 0 }) Text("Longest day: ${statsTime(peak)}", color = Dx.Faint, style = MaterialTheme.typography.bodySmall)
    } }
    item { StatsSection("Most Watched in $year") {
        val top = s.optJSONArray("top").objects()
        if (top.isEmpty()) Text("Nothing yet this year. Start watching and it shows up here.", color = Dx.Muted, style = MaterialTheme.typography.bodyMedium)
        top.forEachIndexed { i, t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", color = Dx.Text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.width(34.dp))
                Art(t.text("poster"), Modifier.width(46.dp).height(68.dp).clip(RoundedCornerShape(8.dp)))
                Column(Modifier.padding(start = 14.dp)) {
                    Text(t.optString("name"), color = Dx.Text, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${if (t.optString("type") == "series") "Series" else "Film"} · ${statsTime(t.optDouble("sec"))}", color = Dx.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    } }
    item { StatsSection("Top Genres in $year") {
        val genres = s.optJSONArray("genres").objects()
        val total = genres.sumOf { it.optDouble("sec") }.coerceAtLeast(1.0)
        if (genres.isEmpty()) Text("Genres appear once you've watched a few titles.", color = Dx.Muted, style = MaterialTheme.typography.bodyMedium)
        genres.forEach { g ->
            val share = (g.optDouble("sec") / total).toFloat()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(g.optString("name"), color = Dx.Text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(110.dp))
                Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Dx.Line)) {
                    Box(Modifier.fillMaxWidth(share).fillMaxHeight().background(Dx.Selected))
                }
                Text("${Math.round(share * 100)}%", color = Dx.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(44.dp).padding(start = 8.dp))
            }
        }
    } }
    item {
        val hour = if (s.isNull("hour")) null else s.optInt("hour")
        ProfileGroup(header = "Habits") {
            link("Favourite time", value = hour?.let { "%02d:00–%02d:00".format(it, (it + 1) % 24) } ?: "–", chevron = false) {}
            link("Busiest day", value = s.text("day") ?: "–", chevron = false) {}
            link("In your Stremio library", value = "${s.optInt("movies")} films · ${s.optInt("series")} series", chevron = false) {}
        }
    }
    item { Text("Stats stay on this phone (saved with Lumen's data). Only time spent actually playing counts.", color = Dx.Faint, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun StatsTile(label: String, value: String, note: String?, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Dx.Raised).padding(16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = Dx.Muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        Text(value, color = Dx.Text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
        if (note != null) Text(note, color = Dx.Faint, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StatsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, color = Dx.Text, style = MaterialTheme.typography.titleLarge)
        content()
    }
}

/** "1h 05m", "12m", "<1m" */
private fun statsTime(sec: Double): String {
    val s = Math.round(sec).toInt()
    if (s < 60) return if (s > 0) "<1m" else "0m"
    val h = s / 3600
    val m = s % 3600 / 60
    return if (h > 0) "%dh %02dm".format(h, m) else "${m}m"
}
