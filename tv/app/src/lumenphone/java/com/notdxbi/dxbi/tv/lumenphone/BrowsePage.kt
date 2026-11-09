package com.notdxbi.dxbi.tv.lumenphone

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.notdxbi.dxbi.tv.phone.Discover
import com.notdxbi.dxbi.tv.phone.Load
import com.notdxbi.dxbi.tv.phone.Stremio
import com.notdxbi.dxbi.tv.phone.Title

/**
 * The pages a tab opens over itself: a genre, Christmas at Home, every catalog, or a row's See All (iOS: the
 * routes Discover and Search push). They're kept as a short text so a tab remembers which one is open when the
 * app switches tabs, and they sit over the tab rather than replace it, so its scroll place is kept.
 */
sealed interface BrowsePage {
    data class Genre(val name: String) : BrowsePage
    data object Christmas : BrowsePage
    data object Catalogs : BrowsePage
    data class All(val link: String, val name: String) : BrowsePage

    fun encode(): String = when (this) {
        is Genre -> "genre|$name"
        Christmas -> "christmas"
        Catalogs -> "catalogs"
        is All -> "all|$name|$link"
    }

    companion object {
        fun decode(s: String?): BrowsePage? {
            val p = s?.split("|", limit = 3) ?: return null
            return when (p[0]) {
                "genre" -> Genre(p.getOrElse(1) { "" })
                "christmas" -> Christmas
                "catalogs" -> Catalogs
                "all" -> All(link = p.getOrElse(2) { "" }, name = p.getOrElse(1) { "" })
                else -> null
            }
        }
    }
}

/** Shows [content], and over it the open page (if any) with Back to close it; predictive back shows the tab underneath */
@Composable
fun BrowseHost(
    page: String?,
    onPage: (String?) -> Unit,
    stremio: Stremio,
    layout: AppLayout,
    bottomSpace: Dp,
    onOpen: (Title) -> Unit,
    content: @Composable () -> Unit,
) {
    BackHandler(enabled = page != null) { onPage(null) }
    // (kept while it slides away, when `page` is already null)
    var shown by remember { mutableStateOf(page) }
    if (page != null) shown = page
    Box(Modifier.fillMaxSize()) {
        content()
        AnimatedVisibility(
            visible = page != null,
            enter = slideInHorizontally(tween(280)) { it / 4 } + fadeIn(tween(220)),
            exit = slideOutHorizontally(tween(220)) { it / 4 } + fadeOut(tween(180)),
        ) {
            // (opaque, and it takes the touches: the tab under it must not hear them)
            Box(Modifier.fillMaxSize().background(Dx.Background).pointerInput(Unit) {}) {
                val back = { onPage(null) }
                when (val p = BrowsePage.decode(shown)) {
                    is BrowsePage.Genre -> GenrePage(p.name, layout, bottomSpace, back, onOpen)
                    BrowsePage.Christmas -> ChristmasPage(stremio, layout, bottomSpace, back, onOpen)
                    BrowsePage.Catalogs -> BrowseCatalogsPage(stremio, layout, bottomSpace, back, onOpen)
                    is BrowsePage.All -> BrowseAllPage(stremio, p, layout, bottomSpace, back, onOpen)
                    null -> Unit
                }
            }
        }
    }
}

/**
 * Posters in a grid that fits the room: more across on the opened Fold and tablets, not a stretched phone grid
 * (iOS: PosterGrid, 108 on iPhone and 150 on iPad). [header] puts anything above the posters, full width;
 * [onEnd] is called as the last poster appears, for the next page.
 */
@Composable
fun BrowseGrid(
    items: List<Title>,
    layout: AppLayout,
    bottomSpace: Dp,
    modifier: Modifier = Modifier,
    top: Dp = 0.dp,
    header: LazyGridScope.() -> Unit = {},
    footer: LazyGridScope.() -> Unit = {},
    onEnd: (() -> Unit)? = null,
    onOpen: (Title) -> Unit,
) {
    val state = rememberLazyGridState()
    val unique = remember(items) { items.distinctBy { it.id } }
    if (onEnd != null) LaunchedEffect(state, unique.size) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index to state.layoutInfo.totalItemsCount }
            .collect { (last, total) -> if (last != null && total > 0 && last >= total - 6) onEnd() }
    }
    val gap = if (layout.compact) 12.dp else 18.dp
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (layout.compact) 108.dp else 150.dp),
        state = state,
        modifier = modifier,
        contentPadding = PaddingValues(start = layout.gutter - 4.dp, end = layout.gutter - 4.dp, top = top, bottom = bottomSpace + 30.dp),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalArrangement = Arrangement.spacedBy(if (layout.compact) 14.dp else 22.dp),
    ) {
        header()
        items(unique, key = { it.id }) { t -> BrowsePoster(t, onClick = { onOpen(t) }) }
        footer()
    }
}

/** A row's See All: the catalog it came from, as a grid that goes on loading as they scroll (iOS: BrowseView) */
@Composable
fun BrowseAllPage(stremio: Stremio, page: BrowsePage.All, layout: AppLayout, bottomSpace: Dp, onBack: () -> Unit, onOpen: (Title) -> Unit) {
    val discover by stremio.discover.collectAsState()
    LaunchedEffect(page.link) { stremio.discover(page.link) }
    val d = discover
    Box(Modifier.fillMaxSize().background(Dx.Background)) {
        BrowseGrid(
            d?.items.orEmpty(), layout, bottomSpace,
            top = statusBarTop() + 56.dp,
            footer = { if (d?.state == Load.Loading || d == null) item(span = { GridItemSpan(maxLineSpan) }) { BrowseSpinner() } },
            onEnd = { if (d?.nextPage == true && d.state != Load.Loading) stremio.discoverMore() },
            onOpen = onOpen,
        )
        BrowseBar(page.name, layout, onBack, Modifier.background(Dx.Background.copy(alpha = 0.85f)))
    }
}

/** Every catalog the addons have, by type, catalog and genre (iOS: CatalogBrowserView, "Browse All Catalogs") */
@Composable
fun BrowseCatalogsPage(stremio: Stremio, layout: AppLayout, bottomSpace: Dp, onBack: () -> Unit, onOpen: (Title) -> Unit) {
    val discover by stremio.discover.collectAsState()
    val rows by stremio.allRows.collectAsState()
    // (the catalogs list can arrive after the page opens)
    LaunchedEffect(rows.size) { stremio.discoverStart() }
    val d = discover
    Box(Modifier.fillMaxSize().background(Dx.Background)) {
        BrowseGrid(
            d?.items.orEmpty(), layout, bottomSpace,
            top = statusBarTop() + 56.dp,
            header = { if (d != null) item(span = { GridItemSpan(maxLineSpan) }) { CatalogPickers(stremio, d) } },
            footer = {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    when {
                        d == null || d.state == Load.Loading -> BrowseSpinner()
                        d.state == Load.Err -> BrowseEmpty("Nothing here", "This catalog has nothing for that choice.")
                    }
                }
            },
            onEnd = { if (d?.nextPage == true && d.state != Load.Loading) stremio.discoverMore() },
            onOpen = onOpen,
        )
        BrowseBar("All Catalogs", layout, onBack, Modifier.background(Dx.Background.copy(alpha = 0.85f)))
    }
}

/** The type, the catalog and the genre to look at */
@Composable
private fun CatalogPickers(stremio: Stremio, d: Discover) {
    Box(Modifier.padding(bottom = 4.dp)) {
        androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (d.types.size > 1) {
                BrowseSegments(
                    d.types.map { (if (it.name == "movie") "Movies" else if (it.name == "series") "TV Shows" else typeName(it.name)) to (it.link ?: "") },
                    selected = d.types.firstOrNull { it.selected }?.link ?: "",
                    modifier = Modifier.padding(horizontal = 4.dp),
                ) { if (it.isNotEmpty()) stremio.discover(it) }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
                item(key = "catalog") {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        Row(
                            Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.12f)).clickable { open = true }.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(d.catalogs.firstOrNull { it.selected }?.name ?: "Catalog", color = Dx.Text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = Dx.Muted, modifier = Modifier.padding(start = 2.dp))
                        }
                        DropdownMenu(open, onDismissRequest = { open = false }) {
                            d.catalogs.forEach { c ->
                                DropdownMenuItem(
                                    text = { Text(c.name, fontWeight = if (c.selected) FontWeight.Bold else FontWeight.Normal) },
                                    onClick = { open = false; c.link?.let { stremio.discover(it) } },
                                )
                            }
                        }
                    }
                }
                if (d.genres.isNotEmpty()) {
                    item(key = "genre-all") { GenreChip("All", d.genre == null) { stremio.discoverGenre(null) } }
                    items(d.genres, key = { "genre-$it" }) { g -> GenreChip(g, d.genre == g) { stremio.discoverGenre(g) } }
                }
            }
        }
    }
}

/** A genre to filter by: white when chosen, as Lumen marks a choice everywhere */
@Composable
private fun GenreChip(name: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(CircleShape).background(if (on) Color.White else Color.White.copy(alpha = 0.12f)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(name, color = if (on) Color.Black else Dx.Text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
