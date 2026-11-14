package com.notdxbi.dxbi.tv.lumen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.notdxbi.dxbi.tv.phone.Load
import com.notdxbi.dxbi.tv.phone.Stremio
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Search

/** Search: a box to type in with the TV's keyboard, and what each addon finds, in rows */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(stremio: Stremio, nav: Nav) {
    val results by stremio.search.collectAsState()
    var text by remember { mutableStateOf(nav.query) }
    // a name chosen on a title's page
    LaunchedEffect(nav.query) { if (nav.query.isNotEmpty()) text = nav.query }
    // looks a moment after the typing stops
    LaunchedEffect(text) {
        val q = text.trim()
        if (q.length < 2) return@LaunchedEffect
        if (q != nav.query) delay(600)
        stremio.search(q)
    }
    val rows = if (text.trim().length < 2) emptyList() else results.filter { it.type !in LEFT_OUT }
    // Down from the box goes to the first result, at the row's start (straight down landed on the third, under the box's middle)
    val firstResult = remember { androidx.compose.ui.focus.FocusRequester() }
    // A row whose addon hasn't answered after a few seconds goes (an addon that never answers left a row of grey
    // cards there for good: Popular Series, for a film's name)
    var patient by remember { mutableStateOf(true) }
    LaunchedEffect(text) {
        patient = true
        delay(6000)
        patient = false
    }
    val firstRow = rows.firstOrNull { it.state == Load.Ready && it.items.isNotEmpty() }?.key
    // (nothing typed: the Genres row, as Discover's; Anime only with the Kitsu addon)
    val addons by stremio.installedAddons.collectAsState()
    LaunchedEffect(Unit) { stremio.loadAddons() }
    val hasKitsu = addons.any { it.id.contains("kitsu", ignoreCase = true) || it.transportUrl.contains("kitsu", ignoreCase = true) }
    // Search chosen from the menu: the keyboard comes up at once, once the menu has gone and the corner's had its focus
    // (not when it opened with a name to look for, chosen on a title's page)
    var openKeyboard by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (nav.query.isNotEmpty()) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { nav.menuOpen }.first { !it }
        delay(250)
        openKeyboard = true
    }

    Box(Modifier.fillMaxSize().background(Dx.Background)) {
        val pageListState = androidx.compose.foundation.lazy.rememberLazyListState()
        val pageScope = androidx.compose.runtime.rememberCoroutineScope()
        val pageScroller = remember { PageScroller(pageListState, pageScope) }
        CompositionLocalProvider(LocalBringIntoViewSpec provides CentredScrolling, LocalPageScroller provides pageScroller) {
            LazyColumn(state = pageListState, modifier = Modifier.onGloballyPositioned { pageScroller.viewport = it }, contentPadding = PaddingValues(bottom = pageBottomPad()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                item(key = "top") { ScreenTag(nav) }
                item(key = "box") {
                    Field(
                        "Search films and TV shows", text, { text = it }, KeyboardType.Text,
                        Modifier.padding(horizontal = Dx.Gutter).width(560.dp).focusProperties {
                            down = if (firstRow != null) firstResult else androidx.compose.ui.focus.FocusRequester.Default
                        },
                        // (round at the ends, as Apple TV's)
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                        open = openKeyboard,
                        leading = { androidx.tv.material3.Icon(androidx.compose.material.icons.Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(22.dp)) },
                    )
                }
                val anyShown = firstRow != null || (patient && rows.any { it.state == Load.Loading })
                if (!anyShown) {
                    item(key = "hint") {
                        Text(
                            when {
                                text.trim().length < 2 -> "Type with the remote, or choose a genre."
                                rows.isEmpty() || patient -> "Searching…"
                                else -> "Nothing found for \"${text.trim()}\"."
                            },
                            fontSize = 15.sp,
                            color = Dx.Muted,
                            modifier = Modifier.padding(horizontal = Dx.Gutter),
                        )
                    }
                }
                if (text.trim().length < 2) genresItem(nav, inChristmasSeason(), hasKitsu)
                rows.forEach { row ->
                    when {
                        row.state == Load.Ready && row.items.isNotEmpty() -> item(key = "row-${row.key}") {
                            Shelf(row.heading) {
                                itemsIndexed(row.items, key = { _, t -> t.id }) { i, t ->
                                    if (i == 0 && row.key == firstRow) Box(Modifier.focusRequester(firstResult)) { PreviewPosterCard(t, onClick = { nav.open(t) }) }
                                    else PreviewPosterCard(t, onClick = { nav.open(t) })
                                }
                            }
                        }
                        row.state == Load.Loading && patient -> item(key = "row-${row.key}") { LoadingShelf(row.heading) }
                        else -> Unit
                    }
                }
            }
        }
    }
}
