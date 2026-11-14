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
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.notdxbi.dxbi.tv.phone.Title
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// The small pieces Discover, Search, Library and their pages share (iOS: BrowseViews.swift, GenreShelf)

/** How far down the status bar reaches: the screens draw under it and start their content below */
@Composable
fun statusBarTop(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

/** A title in a grid: its poster at the width the cell gives it, and a white tick once it's watched (iOS: PosterGrid) */
@Composable
fun BrowsePoster(title: Title, onClick: () -> Unit) {
    BoxWithConstraints {
        val width = maxWidth
        // (PosterCard fills the cell and keeps its shape, and draws the watched tick itself)
        PosterCard(title, width, fillWidth = true, onClick = onClick)
    }
}

/** A row's name, and a chevron after it when See All has somewhere to go (iOS: Shelf) */
@Composable
fun BrowseHeader(title: String, gutter: Dp, onSeeAll: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onSeeAll != null) Modifier.clickable(onClick = onSeeAll) else Modifier).padding(horizontal = gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Dx.Text, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (onSeeAll != null) Icon(Icons.Filled.ChevronRight, contentDescription = "See all", tint = Dx.Muted, modifier = Modifier.padding(start = 2.dp).size(22.dp))
    }
}

private val DayMonth = SimpleDateFormat("d MMM", Locale.getDefault())

/**
 * A row of posters that scrolls sideways. [dates] puts a day under a poster (a film's digital release);
 * one still to come reads white, the rest grey (iOS: Discover's shelf).
 */
@Composable
fun BrowseShelf(
    name: String,
    titles: List<Title>,
    layout: AppLayout,
    modifier: Modifier = Modifier,
    dates: Map<String, Long> = emptyMap(),
    onSeeAll: (() -> Unit)? = null,
    onOpen: (Title) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BrowseHeader(name, layout.gutter, onSeeAll)
        LazyRow(contentPadding = PaddingValues(horizontal = layout.gutter), horizontalArrangement = Arrangement.spacedBy(if (layout.compact) 12.dp else 14.dp)) {
            items(titles.distinctBy { it.id }, key = { it.id }) { t ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PosterCard(t, layout.poster, onClick = { onOpen(t) })
                    dates[t.id]?.let { d ->
                        Text(
                            DayMonth.format(Date(d)),
                            color = if (d > System.currentTimeMillis()) Dx.Text else Dx.Muted,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

/** The back button in a page's corner, and its title when it has a small one (iOS: the navigation bar) */
@Composable
fun BrowseBar(title: String?, layout: AppLayout, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(top = statusBarTop() + 6.dp, bottom = 6.dp).padding(horizontal = layout.gutter - 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Dx.Text, modifier = Modifier.size(22.dp)) }
        if (title != null) {
            Text(title, color = Dx.Text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 14.dp).weight(1f))
        }
    }
}

/** Waiting for a page's first rows */
@Composable
fun BrowseSpinner(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Dx.Muted, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
    }
}

/** Nothing to show: a short line and what to do (iOS: ContentUnavailableView) */
@Composable
fun BrowseEmpty(heading: String, detail: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(heading, color = Dx.Text, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(detail, color = Dx.Muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

/**
 * Two or three choices side by side with the chosen one lifted (iOS: the segmented Picker). Grey and white,
 * as the rest of Lumen: no colour for what's chosen.
 */
@Composable
fun <T> BrowseSegments(options: List<Pair<String, T>>, selected: T, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    Row(modifier.clip(RoundedCornerShape(10.dp)).background(Dx.Raised).padding(2.dp)) {
        options.forEach { (label, value) ->
            val on = value == selected
            Box(
                Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (on) Color.White.copy(alpha = 0.22f) else Color.Transparent)
                    .clickable { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) Dx.Text else Dx.Muted, style = MaterialTheme.typography.bodyMedium, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
            }
        }
    }
}
