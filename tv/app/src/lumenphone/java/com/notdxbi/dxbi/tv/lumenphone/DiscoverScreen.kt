package com.notdxbi.dxbi.tv.lumenphone

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notdxbi.dxbi.tv.phone.Stremio
import com.notdxbi.dxbi.tv.phone.Title
import kotlinx.coroutines.launch

/** What For You washes the page in: a soft grey, as Lumen has no colour of its own (the services bring theirs) */
private val ForYouTop = Color(0xFF8C8C8C)
private val ForYouDeep = Color(0xFF161616)

/**
 * Discover, by streaming service (iOS: DiscoverView): For You (what's new on digital, from the library first,
 * then popular and genre rows), or one service's own page in its colours (new, popular series and films, its
 * genres). Genres and Christmas at Home sit among the rows; the full catalog browser is at the bottom. Pages
 * open over the tab (BrowseHost), so coming back finds the tab where it was left.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(stremio: Stremio, layout: AppLayout, bottomSpace: Dp, onOpen: (Title) -> Unit) {
    val context = LocalContext.current
    val feed = discoverFeed(context)
    val prefs = remember { context.getSharedPreferences("lumen.discover", 0) }
    var selected by rememberSaveable { mutableStateOf(prefs.getString("platform", "") ?: "") }
    var rows by remember { mutableStateOf<List<DiscoverRow>>(emptyList()) }
    var loadedFor by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val library by stremio.library.collectAsState()
    val platform = DiscoverFeed.platforms.firstOrNull { it.id == selected }

    // (the library can arrive after For You first loads: its "out on digital" row follows it)
    val libraryArrived = library.isNotEmpty()
    LaunchedEffect(selected, refresh, libraryArrived) {
        if (selected.isEmpty() && library.isEmpty()) stremio.loadLibrary(null)
        val wanted = selected
        // (the last tab's rows stay until the new ones are in: a page that emptied and refilled jumped)
        if (platform != null) {
            val built = platformRows(feed, platform)
            if (wanted == selected) rows = built
        } else {
            forYouRows(feed, stremio) { partial -> if (wanted == selected) rows = partial }
        }
        if (wanted == selected) loadedFor = wanted
    }

    val top by animateColorAsState(platform?.colour?.copy(alpha = 0.42f) ?: ForYouTop.copy(alpha = 0.2f), tween(450), label = "top")
    val deep by animateColorAsState(platform?.deep ?: ForYouDeep, tween(450), label = "deep")

    BrowseHost(page, { page = it }, stremio, layout, bottomSpace, onOpen) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to top, 0.28f to deep, 0.62f to Color.Black))) {
            PullToRefreshBox(isRefreshing = false, onRefresh = { refresh++ }, modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(top = statusBarTop() + 8.dp, bottom = bottomSpace + 40.dp),
                    verticalArrangement = Arrangement.spacedBy(30.dp),
                ) {
                    item(key = "title") {
                        Text("Discover", modifier = Modifier.padding(horizontal = layout.gutter), color = Dx.Text, style = MaterialTheme.typography.headlineMedium)
                    }
                    item(key = "tabs") {
                        LazyRow(contentPadding = PaddingValues(horizontal = layout.gutter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item(key = "") { PlatformChip("For You", on = selected.isEmpty(), colour = Color.White, icon = null) { selected = ""; prefs.edit().putString("platform", "").apply() } }
                            itemsIndexed(DiscoverFeed.platforms, key = { _, p -> p.id }) { _, p ->
                                PlatformChip(p.name, on = selected == p.id, colour = p.colour, icon = p.icon) { selected = p.id; prefs.edit().putString("platform", p.id).apply() }
                            }
                        }
                    }
                    item(key = "header") { PlatformHeader(platform, loadedFor != selected, layout) }
                    when {
                        loadedFor == null && rows.isEmpty() -> item(key = "wait") { BrowseSpinner() }
                        rows.isEmpty() && loadedFor == selected -> item(key = "none") {
                            BrowseEmpty("Nothing to show", "Couldn't reach the catalogs. Pull down to try again.")
                        }
                    }
                    // (in the season, For You opens with a festive card)
                    if (selected.isEmpty() && ChristmasSeason.inSeason && loadedFor != null) item(key = "festive") {
                        FestiveCard(layout) { page = BrowsePage.Christmas.encode() }
                    }
                    rows.forEachIndexed { i, row ->
                        item(key = "row:${row.id}") { BrowseShelf(row.title, row.items.filter { it.type in WATCHABLE }, layout, dates = row.dates, onOpen = onOpen) }
                        // (Genres after the first couple of rows: tiles, as Apple TV's Search has)
                        if (selected.isEmpty() && i == minOf(1, rows.size - 1)) item(key = "genres") {
                            GenresSection(stremio, layout, onGenre = { page = BrowsePage.Genre(it).encode() }, onChristmas = { page = BrowsePage.Christmas.encode() })
                        }
                    }
                    item(key = "catalogs") {
                        Row(
                            Modifier.padding(horizontal = layout.gutter).clip(CircleShape).background(Color.White.copy(alpha = 0.14f))
                                .clickable { page = BrowsePage.Catalogs.encode() }.padding(horizontal = 18.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Filled.GridView, contentDescription = null, tint = Dx.Text, modifier = Modifier.size(20.dp))
                            Text("Browse All Catalogs", color = Dx.Text, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
}

/** A service (or For You) to switch to: its name, with its icon; filled in its own colour when it's the one shown */
@Composable
private fun PlatformChip(name: String, on: Boolean, colour: Color, icon: String?, onClick: () -> Unit) {
    val fill by animateColorAsState(if (on) colour else Color.White.copy(alpha = 0.1f), tween(250), label = "chip")
    val ink = if (on && colour.luminance() > 0.45f) Color.Black else Color.White
    Row(
        Modifier.clip(CircleShape).background(fill).clickable(onClick = onClick)
            .padding(start = if (icon == null) 16.dp else 8.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Art(icon, Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)))
        Text(name, color = ink, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/**
 * The page's heading: the service's own logo, large, with its name (For You: a plain mark). The same height on
 * every tab, so switching doesn't move the page.
 */
@Composable
private fun PlatformHeader(platform: DiscoverFeed.Platform?, busy: Boolean, layout: AppLayout) {
    Row(
        Modifier.fillMaxWidth().height(80.dp).padding(horizontal = layout.gutter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val shape = RoundedCornerShape(16.dp)
        Box(
            Modifier.size(72.dp).shadow(16.dp, shape, ambientColor = platform?.colour ?: Color.White, spotColor = platform?.colour ?: Color.White).clip(shape).background(Dx.Raised),
            contentAlignment = Alignment.Center,
        ) {
            if (platform?.icon != null) Art(platform.icon, Modifier.fillMaxSize())
            else Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Dx.Text, modifier = Modifier.size(34.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(platform?.name ?: "For You", color = Dx.Text, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(
                if (platform == null) "New on digital, popular and by genre" else "What's on ${platform.name}",
                color = Dx.Muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
            )
        }
        if (busy) androidx.compose.material3.CircularProgressIndicator(color = Dx.Muted, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
    }
}
