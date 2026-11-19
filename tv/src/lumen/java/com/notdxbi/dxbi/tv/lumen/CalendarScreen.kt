package com.notdxbi.dxbi.tv.lumen

import android.content.Context
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.notdxbi.dxbi.tv.phone.Stremio
import com.notdxbi.dxbi.tv.phone.Title
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The Calendar, as Stremio's: a month as a grid of days, each with the posters of what comes out on it, and the day the
 * remote is on listed beside it. What comes out: new episodes of the Library's shows (the plugin's episode index) and the
 * Library's films out on digital (the dvdsreleasedates.com months Discover reads). "Everything" adds the popular shows'
 * episodes (Cinemeta's top series) and every film out on digital, for what isn't in the Library yet.
 */
private data class Entry(val day: Long, val time: Long, val title: Title, val name: String, val line: String, val poster: String?, val mine: Boolean)

@Composable
fun CalendarScreen(stremio: Stremio, nav: Nav, feed: DiscoverFeed) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("lumen", Context.MODE_PRIVATE) }
    val library by stremio.library.collectAsState()
    LaunchedEffect(Unit) { if (library.isEmpty()) stremio.loadLibrary(null) }
    var everything by remember { mutableStateOf(prefs.getBoolean("calendar.everything", false)) }
    // the month shown: 0 this one, -1 last month, up to 2 ahead
    var monthShift by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<Entry>?>(null) }
    val mine = remember(library) { library.map { it.id }.toSet() }
    LaunchedEffect(mine, everything) {
        entries = gather(stremio, feed, mine, everything)
    }

    val today = startOfDay(System.currentTimeMillis())
    val month = remember(monthShift) { Calendar.getInstance().apply { timeInMillis = today; set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, monthShift) } }
    val byDay = remember(entries) {
        entries.orEmpty().groupBy { it.day }.mapValues { (_, l) -> l.sortedWith(compareByDescending<Entry> { it.mine }.thenBy { it.name }) }
    }
    var chosenDay by remember { mutableLongStateOf(today) }
    val landingCell = remember { FocusRequester() }
    // (up from the month's first row: its buttons, which an empty start to the week would otherwise hide from the remote)
    val header = remember { FocusRequester() }
    // the first of the day's list (OK on a day goes to it)
    val listFirst = remember { FocusRequester() }
    // moving off the month's top or bottom row: the weekday column it left from, and whether it went down
    var arrive by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    var headerFocused by remember { mutableStateOf(false) }
    // Back from the days: to the month's buttons first (then Home, as everywhere)
    androidx.activity.compose.BackHandler(enabled = !headerFocused && nav.pages.isEmpty() && !nav.menuOpen) { runCatching { header.requestFocus() } }
    LaunchedEffect(monthShift) {
        kotlinx.coroutines.delay(150)
        runCatching { landingCell.requestFocus() }
    }

    Column(Modifier.fillMaxSize().background(Dx.Background)) {
        ScreenTag(nav)
        // the month, the arrows to the next and last, and what to show
        Row(Modifier.fillMaxWidth().padding(start = Dx.Gutter, end = Dx.Gutter, bottom = 10.dp).onFocusChanged { headerFocused = it.hasFocus }, verticalAlignment = Alignment.CenterVertically) {
            Text(SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(month.time), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.width(18.dp))
            Pill("‹ Last Month", selected = false, onClick = { if (monthShift > -1) { arrive = null; monthShift-- } })
            Spacer(Modifier.width(8.dp))
            Pill("Next Month ›", selected = false, onClick = { if (monthShift < 2) { arrive = null; monthShift++ } }, modifier = Modifier.focusRequester(header))
            Spacer(Modifier.weight(1f))
            Pill("My Library", selected = !everything, onClick = { everything = false; prefs.edit().putBoolean("calendar.everything", false).apply() })
            Spacer(Modifier.width(8.dp))
            Pill("Everything", selected = everything, onClick = { everything = true; prefs.edit().putBoolean("calendar.everything", true).apply() })
        }
        Row(Modifier.fillMaxSize().padding(start = Dx.Gutter, end = Dx.Gutter, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            MonthGrid(
                month, today, byDay, landingCell, header, arrive,
                canBack = monthShift > -1, canOn = monthShift < 2,
                onMonth = { step, column -> arrive = column to (step > 0); monthShift += step },
                onChoose = { day ->
                    chosenDay = day
                    if (!byDay[day].isNullOrEmpty()) runCatching { listFirst.requestFocus() }
                },
                modifier = Modifier.weight(0.64f).fillMaxHeight(),
            ) { chosenDay = it }
            DayList(chosenDay, today, byDay[chosenDay].orEmpty(), loading = entries == null, listFirst, Modifier.weight(0.36f).fillMaxHeight()) { nav.open(it) }
        }
    }
}

/** The month's weeks, Monday first; a day out of the month is left empty. The remote lands on today (else the 1st). */
@Composable
private fun MonthGrid(
    month: Calendar,
    today: Long,
    byDay: Map<Long, List<Entry>>,
    landing: FocusRequester,
    header: FocusRequester,
    /** Come from the month before or after: the weekday column, and whether it moved down (to this month's first row) */
    arrive: Pair<Int, Boolean>?,
    canBack: Boolean,
    canOn: Boolean,
    onMonth: (step: Int, column: Int) -> Unit,
    onChoose: (Long) -> Unit,
    modifier: Modifier,
    onDay: (Long) -> Unit,
) {
    val first = month.clone() as Calendar
    val lead = (first.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
    val days = first.getActualMaximum(Calendar.DAY_OF_MONTH)
    val weeks = (lead + days + 6) / 7
    val dayOf = { n: Int -> (first.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, n) }.timeInMillis }
    val landOn = arrive?.let { (column, down) ->
        val first = (column - lead + 1).let { if (it < 1) it + 7 else it }
        if (down) first else generateSequence(first) { it + 7 }.takeWhile { it <= days }.last()
    } ?: (1..days).firstOrNull { dayOf(it) == today } ?: 1
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach {
                Text(it, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Dx.Muted, modifier = Modifier.weight(1f).padding(start = 6.dp))
            }
        }
        for (w in 0 until weeks) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (d in 0 until 7) {
                    val n = w * 7 + d - lead + 1
                    if (n < 1 || n > days) {
                        Spacer(Modifier.weight(1f))
                        continue
                    }
                    val day = dayOf(n)
                    DayCell(
                        n, day == today, day < today, byDay[day].orEmpty(),
                        Modifier.weight(1f).fillMaxHeight()
                            .then(if (n == landOn) Modifier.focusRequester(landing) else Modifier)
                            // past the top or bottom row: on to the month before or after (at the ends, up goes to the buttons)
                            .then(if (n <= 7 && !canBack) Modifier.focusProperties { up = header } else Modifier)
                            .onPreviewKeyEvent {
                                if (it.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when {
                                    it.key == androidx.compose.ui.input.key.Key.DirectionUp && n <= 7 && canBack -> { onMonth(-1, d); true }
                                    it.key == androidx.compose.ui.input.key.Key.DirectionDown && n + 7 > days && canOn -> { onMonth(1, d); true }
                                    else -> false
                                }
                            },
                        onChoose = { onChoose(day) },
                    ) { onDay(day) }
                }
            }
        }
    }
}

/** A day: its number (today's in a white circle) and the posters of what comes out on it */
@Composable
private fun DayCell(n: Int, isToday: Boolean, past: Boolean, items: List<Entry>, modifier: Modifier, onChoose: () -> Unit, onFocus: () -> Unit) {
    Surface(
        onClick = onChoose,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = if (items.isEmpty()) 0.03f else 0.07f),
            focusedContainerColor = Color.White.copy(alpha = 0.22f),
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp, Color.White), shape = RoundedCornerShape(10.dp))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        Box(Modifier.fillMaxSize().padding(5.dp)) {
            Box(
                Modifier.size(20.dp).then(if (isToday) Modifier.background(Color.White, CircleShape) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$n", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = when { isToday -> Color.Black; past -> Color.White.copy(alpha = 0.4f); else -> Color.White },
                )
            }
            if (items.isNotEmpty()) {
                Row(Modifier.align(Alignment.BottomEnd), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                    // (two fit in a day; more: one and how many others)
                    val shown = items.take(if (items.size > 2) 1 else 2)
                    shown.forEach { e ->
                        Art(e.poster, Modifier.width(22.dp).height(33.dp).clip(RoundedCornerShape(3.dp)).then(if (e.mine) Modifier.border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(3.dp)) else Modifier))
                    }
                    if (items.size > 2) {
                        Box(Modifier.width(22.dp).height(33.dp).background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(3.dp)), contentAlignment = Alignment.Center) {
                            Text("+${items.size - 1}", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

/** The day the remote is on: everything that comes out then, the Library's first */
@Composable
private fun DayList(day: Long, today: Long, items: List<Entry>, loading: Boolean, first: FocusRequester, modifier: Modifier, onOpen: (Title) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(dayHeading(day, today), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        when {
            loading -> Text("Looking up dates…", fontSize = 15.sp, color = Dx.Muted)
            items.isEmpty() -> Text("Nothing out this day.", fontSize = 15.sp, color = Dx.Muted)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(items, key = { _, e -> e.title.id + e.line }) { i, e ->
                Surface(
                    onClick = { onOpen(e.title) },
                    modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.White.copy(alpha = 0.05f), contentColor = Color.White,
                        focusedContainerColor = Color.White, focusedContentColor = Color.Black,
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                ) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Art(e.poster, Modifier.width(36.dp).height(54.dp).clip(RoundedCornerShape(5.dp)))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(e.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                e.line + if (e.mine) " · In your Library" else "", fontSize = 12.sp, maxLines = 1,
                                color = androidx.tv.material3.LocalContentColor.current.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The episodes and the films, each with its day; [everything]: the popular shows and every film too */
private suspend fun gather(stremio: Stremio, feed: DiscoverFeed, mine: Set<String>, everything: Boolean): List<Entry> = coroutineScope {
    val libraryEpisodes = async { runCatching { stremio.calendar() }.getOrNull().orEmpty() }
    // (films: last month's to two months ahead, the months the site has pages for)
    val months = (-1..2).map { d -> Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, d) } }
    val releases = months.map { c -> async { runCatching { feed.digital(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1) }.getOrDefault(emptyList()) } }
    // (the popular shows: Cinemeta's top 30 not in the Library, each one's episodes)
    val popular = if (!everything) null else async {
        val shows = runCatching { feed.popular("series") }.getOrDefault(emptyList()).map { it.title }.filter { it.id.startsWith("tt") && it.id !in mine }.take(30)
        shows.map { s -> async { s to runCatching { feed.episodes(s.id) }.getOrDefault(emptyList()) } }.awaitAll()
    }
    val from = startOfDay(months.first().timeInMillis)
    val to = Calendar.getInstance().apply { timeInMillis = months.last().timeInMillis; add(Calendar.MONTH, 1) }.timeInMillis

    val out = ArrayList<Entry>()
    /** A show's episodes on one day as one entry ("Season 2, Episodes 7–9") */
    fun addShow(t: Title, eps: List<Triple<Int, Int, Long>>, isMine: Boolean) {
        for ((day, list) in eps.filter { it.third in from until to }.groupBy { startOfDay(it.third) }) {
            val seasons = list.map { it.first }.distinct()
            val numbers = list.map { it.second }.sorted()
            val firstEp = list.minBy { it.first * 10_000 + it.second }
            val line = when {
                list.size == 1 -> "Season ${firstEp.first}, Episode ${firstEp.second}"
                seasons.size == 1 && numbers.last() - numbers.first() == numbers.size - 1 -> "Season ${firstEp.first}, Episodes ${numbers.first()}–${numbers.last()}"
                seasons.size == 1 -> "Season ${firstEp.first}, ${list.size} episodes"
                else -> "${list.size} episodes"
            }
            out += Entry(day, firstEp.third, t, t.name, line, t.art, isMine)
        }
    }
    for ((_, eps) in libraryEpisodes.await().groupBy { it.id }) {
        val e = eps.first()
        val t = Title(id = e.id, type = "series", name = e.name, poster = e.poster, background = e.background)
        addShow(t, eps.map { Triple(it.season, it.episode, it.time) }, true)
    }
    popular?.await()?.forEach { (show, eps) -> addShow(show, eps.map { Triple(it.season, it.episode, it.time) }, false) }
    val seen = HashSet<String>()
    for (r in releases.awaitAll().flatten()) {
        val isMine = r.id in mine
        if ((!isMine && !everything) || !seen.add(r.id)) continue
        out += Entry(startOfDay(r.date), r.date, r.title, r.name, "Film · Out on digital", r.title.art, isMine)
    }
    out
}

private const val DAY_MS = 24L * 60 * 60 * 1000

private fun startOfDay(t: Long): Long = Calendar.getInstance().apply {
    timeInMillis = t
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** "Today", "Tomorrow", then "Thursday 13 November" */
private fun dayHeading(day: Long, today: Long): String {
    val date = SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(day))
    return when ((day - today + DAY_MS / 2).floorDiv(DAY_MS)) {
        0L -> "Today · $date"
        1L -> "Tomorrow · $date"
        -1L -> "Yesterday · $date"
        else -> date
    }
}
