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
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
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
    // (nothing typed: the Genres row, as Discover's; Anime only with the Kitsu addon)
    val addons by stremio.installedAddons.collectAsState()
    LaunchedEffect(Unit) { stremio.loadAddons() }
    val hasKitsu = addons.any { it.id.contains("kitsu", ignoreCase = true) || it.transportUrl.contains("kitsu", ignoreCase = true) }

    Box(Modifier.fillMaxSize().background(Dx.Background)) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides ScreenScrolling) {
            LazyColumn(contentPadding = PaddingValues(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                item(key = "top") { ScreenTag(nav) }
                item(key = "box") {
                    Field("Search films and TV shows", text, { text = it }, KeyboardType.Text, Modifier.padding(horizontal = Dx.Gutter).width(560.dp))
                }
                if (rows.isEmpty()) {
                    item(key = "hint") {
                        Text(
                            if (text.trim().length < 2) "Choose the box to type with the remote." else "Searching…",
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
                            Shelf(row.heading) { items(row.items, key = { it.id }) { t -> PreviewPosterCard(t, onClick = { nav.open(t) }) } }
                        }
                        row.state == Load.Loading -> item(key = "row-${row.key}") { LoadingShelf(row.heading) }
                        else -> Unit
                    }
                }
            }
        }
    }
}
