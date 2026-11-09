package com.notdxbi.dxbi.tv.lumenphone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.notdxbi.dxbi.tv.phone.Load
import com.notdxbi.dxbi.tv.phone.Stremio
import com.notdxbi.dxbi.tv.phone.Title

/**
 * Home, the Apple TV way (iOS HomeView): a tall billboard to swipe through, then Continue Watching, the library's
 * rows, Lumen's own picks and the addons' catalogs in the viewer's order. Phase 1 shows them as they come from
 * the engine, which proves sign-in and the page work; opening a title, Play and the rest come with Phase 2.
 */
@Composable
fun HomeScreen(stremio: Stremio, layout: AppLayout, bottomSpace: Dp, onProfile: () -> Unit, onOpen: (Title) -> Unit = {}) {
    val ready by stremio.ready.collectAsState()
    val rows by stremio.rows.collectAsState()
    val resuming by stremio.continueWatching.collectAsState()
    val billboard by stremio.billboard.collectAsState()
    val myList by stremio.myList.collectAsState()
    val forYou by stremio.forYou.collectAsState()
    val account by stremio.account.collectAsState()
    // (details the page filled in later: a billboard title's backdrop and logo)
    val described by stremio.described.collectAsState()

    // What they're watching leads, then suggestions they aren't watching and haven't saved (iOS: billboardTitles)
    val slides = remember(resuming, billboard, myList, described) {
        val watching = resuming.take(4).map { stremio.filledIn(it.title) }
        val taken = (resuming.map { it.title.id } + myList.flatMap { r -> r.items.map { it.id } }).toMutableSet()
        val suggested = billboard.filter { it.backdrop != null && taken.add(it.id) }.take(4).map { stremio.filledIn(it) }
        buildList { for (i in 0 until maxOf(watching.size, suggested.size)) { watching.getOrNull(i)?.let(::add); suggested.getOrNull(i)?.let(::add) } }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Dx.Background)) {
        val hero = if (layout.compact) minOf(maxHeight * 0.62f, maxWidth * 1.25f) else minOf(maxHeight * 0.66f, 460.dp)
        LazyColumn(contentPadding = PaddingValues(bottom = bottomSpace + 32.dp), verticalArrangement = Arrangement.spacedBy(30.dp)) {
            item(key = "billboard") {
                if (slides.isNotEmpty()) Billboard(slides, hero, layout, onOpen)
                else Box(Modifier.fillMaxWidth().height(hero * 0.4f))
            }
            if (resuming.isNotEmpty()) item(key = "continue") {
                val width = (maxWidth * 0.68f).coerceIn(260.dp, 360.dp)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader("Continue Watching", layout.gutter)
                    LazyRow(contentPadding = PaddingValues(horizontal = layout.gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(resuming, key = { "${it.title.id}:${it.videoId}" }) { ContinueCard(it, width, onClick = { onOpen(it.title) }) }
                    }
                }
            }
            myList.forEach { row ->
                if (row.items.isNotEmpty()) item(key = "list:${row.name}") { PosterShelf(row.name, row.items, layout, onOpen) }
            }
            forYou?.takeIf { it.items.isNotEmpty() }?.let { row -> item(key = "foryou") { PosterShelf(row.name, row.items, layout, onOpen) } }
            items(rows.filter { it.state != Load.Err }, key = { "row:${it.key}:${it.index}" }) { row ->
                when {
                    row.state == Load.Ready && row.items.isNotEmpty() -> PosterShelf(row.heading, row.items, layout, onOpen)
                    row.state != Load.Ready -> LoadingShelf(row.heading, layout)
                }
            }
            if (rows.isEmpty()) item(key = "empty") {
                if (!ready || account == null) LoadingShelf("", layout)
                else Text(
                    "Nothing to show yet. Install addons to fill Home.",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = layout.gutter),
                    color = Dx.Muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // The title and the profile button float over the billboard, which shows through the status bar
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = layout.gutter, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Home", modifier = Modifier.weight(1f), color = Dx.Text, style = MaterialTheme.typography.headlineMedium)
            Box(Modifier.clip(CircleShape).clickable(onClick = onProfile)) { Initial(account?.email, 40.dp) }
        }
    }
}

/** The tall pictures at the top: the title's art with its name, a swipe to the next, and dots for where they are */
@Composable
private fun Billboard(titles: List<Title>, height: Dp, layout: AppLayout, onOpen: (Title) -> Unit) {
    val pager = rememberPagerState { titles.size }
    Box(Modifier.fillMaxWidth().height(height)) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            val title = titles[page]
            Box(Modifier.fillMaxSize().clickable { onOpen(title) }) {
                Art(title.backdrop ?: title.art, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Dx.Background)))
                Column(
                    Modifier.align(Alignment.BottomCenter).padding(horizontal = layout.gutter).padding(bottom = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(title.name, color = Dx.Text, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val about = listOfNotNull(typeName(title.type), title.genres.firstOrNull(), title.year).joinToString(" · ")
                    Text(about, color = Dx.Muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                }
            }
        }
        if (titles.size > 1) Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(titles.size) { i ->
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (i == pager.currentPage) Dx.Selected else Color.White.copy(alpha = 0.3f)))
            }
        }
    }
}
