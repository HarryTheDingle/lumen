package com.notdxbi.dxbi.tv.lumenphone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.notdxbi.dxbi.tv.phone.Stremio
import com.notdxbi.dxbi.tv.phone.Title

/**
 * What's saved to their Stremio library, synced with their other devices (iOS: LibraryView): all of it, or just
 * movies or TV shows, newest watched first, as a grid that goes on loading as they scroll. A white tick marks
 * what's watched. The grid fits the room, so the opened Fold shows more across rather than a stretched phone.
 */
@Composable
fun LibraryScreen(stremio: Stremio, layout: AppLayout, bottomSpace: Dp, onOpen: (Title) -> Unit) {
    val library by stremio.library.collectAsState()
    // (null: all of it)
    var type by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(type) { stremio.loadLibrary(type) }

    Box(Modifier.fillMaxSize().background(Dx.Background)) {
        BrowseGrid(
            library, layout, bottomSpace,
            top = statusBarTop() + 8.dp,
            header = {
                item(key = "title", span = { GridItemSpan(maxLineSpan) }) {
                    Text("Library", modifier = Modifier.padding(horizontal = 4.dp), color = Dx.Text, style = MaterialTheme.typography.headlineMedium)
                }
                item(key = "type", span = { GridItemSpan(maxLineSpan) }) {
                    BrowseSegments(
                        listOf("All" to null, "Movies" to "movie", "TV Shows" to "series"), type,
                        Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    ) { type = it }
                }
                if (library.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    BrowseEmpty("Your Library Is Empty", "Tap + on a title to keep it here.")
                }
            },
            onEnd = { stremio.libraryMore() },
            onOpen = onOpen,
        )
    }
}
