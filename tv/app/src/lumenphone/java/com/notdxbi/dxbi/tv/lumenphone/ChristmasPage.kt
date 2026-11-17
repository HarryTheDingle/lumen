package com.notdxbi.dxbi.tv.lumenphone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notdxbi.dxbi.tv.phone.Christmas
import com.notdxbi.dxbi.tv.phone.Stremio
import com.notdxbi.dxbi.tv.phone.Title
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.sin

/** The festive season, and how long to Christmas (iOS: ChristmasSeason) */
object ChristmasSeason {
    /** Mid-November to early January: the festive card and the first tile */
    val inSeason: Boolean get() {
        val c = Calendar.getInstance()
        val m = c.get(Calendar.MONTH)
        val d = c.get(Calendar.DAY_OF_MONTH)
        return (m == Calendar.NOVEMBER && d >= 15) || m == Calendar.DECEMBER || (m == Calendar.JANUARY && d <= 6)
    }

    val daysToChristmas: Int get() {
        val now = Calendar.getInstance()
        val today = Calendar.getInstance().apply { set(now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH), 0, 0, 0); set(Calendar.MILLISECOND, 0) }
        // (the first days of January still count from the Christmas just gone, so they read as none left, not 350)
        var year = now.get(Calendar.YEAR)
        if (now.get(Calendar.MONTH) == Calendar.JANUARY && now.get(Calendar.DAY_OF_MONTH) <= 6) year -= 1
        var day = Calendar.getInstance().apply { set(year, Calendar.DECEMBER, 25, 0, 0, 0); set(Calendar.MILLISECOND, 0) }
        if (day.before(today)) day = Calendar.getInstance().apply { set(year + 1, Calendar.DECEMBER, 25, 0, 0, 0); set(Calendar.MILLISECOND, 0) }
        // (rounded: a day of 23 or 25 hours where the clocks change)
        return maxOf(Math.round((day.timeInMillis - today.timeInMillis) / 86_400_000.0).toInt(), 0)
    }

    val countdown: String get() {
        val n = daysToChristmas
        return if (n == 0) "It's Christmas Day!" else if (n == 1) "1 day to Christmas" else "$n days to Christmas"
    }
}

/** Gentle snow: slow flakes down the view, a few dozen, drawn together (iOS: SnowView) */
@Composable
fun Snow(flakes: Int, modifier: Modifier = Modifier) {
    var clock by remember { mutableLongStateOf(0L) }
    // (about 24 frames a second is plenty for slow snow, and kind to the battery)
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                if (now - last > 41_000_000L) { last = now; clock = now }
            }
        }
    }
    Canvas(modifier) {
        val time = clock / 1e9
        for (i in 0 until flakes) {
            // (each flake: its own place, size and speed, fixed by its number)
            val seed = i * 12.9898
            val rx = abs(sin(seed) * 43758.5453) % 1.0
            val ry = abs(sin(seed * 1.7) * 24634.6345) % 1.0
            val speed = 14 + 22 * ry
            val y = (ry * size.height + time * speed * density) % (size.height + 12 * density) - 6 * density
            val x = rx * size.width + sin(time * 0.5 + seed) * 8 * density
            val r = (1.1 + 1.8 * ry) * density
            drawCircle(Color.White.copy(alpha = (0.35 + 0.5 * ry).toFloat()), radius = r.toFloat(), center = Offset(x.toFloat(), y.toFloat()))
        }
    }
}

/** On For You in the season: a wide festive card that opens Christmas at Home (iOS: FestiveCard) */
@Composable
fun FestiveCard(layout: AppLayout, onClick: () -> Unit) {
    Box(
        Modifier.padding(horizontal = layout.gutter).fillMaxWidth().height(118.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick)
            .background(Brush.linearGradient(listOf(Color(0xFFC42B33), Color(0xFF7D1A21), Color(0xFF0D4A2E)), start = Offset(Float.POSITIVE_INFINITY, 0f), end = Offset(0f, Float.POSITIVE_INFINITY))),
    ) {
        Snow(26, Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Christmas at Home", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Text("${ChristmasSeason.countdown} · films, series and specials", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
        }
        Text("🎄", fontSize = 40.sp, modifier = Modifier.align(Alignment.TopEnd).padding(14.dp))
    }
}

/**
 * The festive page: red and green, snow, how long to Christmas, then the rows (the plugin picks them: the first
 * ask looks up some fifty titles and can take a few seconds, so the header shows at once). iOS: ChristmasView.
 */
@Composable
fun ChristmasPage(stremio: Stremio, layout: AppLayout, bottomSpace: Dp, onBack: () -> Unit, onOpen: (Title) -> Unit) {
    var data by remember { mutableStateOf<Christmas?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        data = stremio.christmas()
        loaded = true
    }
    Box(
        Modifier.fillMaxSize().background(Dx.Background)
            .background(Brush.verticalGradient(0f to Color(0xFF8F1F27), 0.32f to Color(0xFF3B1217), 0.6f to Color.Black)),
    ) {
        // (a faint green at the foot)
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(260.dp)
                .background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color(0xFF0F5938).copy(alpha = 0.3f))),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = bottomSpace + 40.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            item(key = "header") {
                Box(Modifier.fillMaxWidth().height(statusBarTop() + 210.dp)) {
                    Snow(46, Modifier.fillMaxSize())
                    Column(Modifier.align(Alignment.BottomStart).padding(horizontal = layout.gutter).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Christmas at Home", color = Color.White, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.ExtraBold)
                        Text(ChristmasSeason.countdown, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (!loaded) item(key = "loading") { BrowseSpinner() }
            items(data?.rows.orEmpty(), key = { it.name }) { row -> BrowseShelf(row.name, row.items, layout, onOpen = onOpen) }
            if (loaded && data?.rows.isNullOrEmpty()) item(key = "empty") { BrowseEmpty("Nothing to show", "Couldn't reach the catalogs.") }
        }
        BrowseBar(null, layout, onBack)
    }
}
