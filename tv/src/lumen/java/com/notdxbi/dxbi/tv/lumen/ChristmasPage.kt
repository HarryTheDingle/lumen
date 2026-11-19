@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.notdxbi.dxbi.tv.lumen

import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.notdxbi.dxbi.tv.phone.Christmas
import com.notdxbi.dxbi.tv.phone.Stremio

/**
 * Christmas at Home: a red wash fading to black (a little green at the foot), the days to go, then the
 * plugin's rows as they come (the first ask can take several seconds, so the header shows at once). No snow:
 * this TV is slow.
 */
@Composable
fun ChristmasPage(stremio: Stremio, nav: Nav) {
    var data by remember { mutableStateOf<Christmas?>(null) }
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        data = stremio.christmas()
        done = true
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(data != null) {
        if (data != null) {
            kotlinx.coroutines.delay(120)
            runCatching { first.requestFocus() }
        }
    }

    PageWindow(onBack = { nav.back() }, home = first) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Dx.Background)
                .background(Brush.verticalGradient(0f to Color(0xFF8F1F27), 0.35f to Color(0xFF3A1218), 0.75f to Color.Black)),
        ) {
            // (a faint green at the foot)
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(240.dp)
                    .background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color(0xFF1F6B3A).copy(alpha = 0.22f))),
            )
            // (the page scrolls as Home's, Discover's and a genre's do: one glide to put the row in the middle. Without it
            // the rows' own scrolling and the page's fought, and every move bounced)
            val pageListState = androidx.compose.foundation.lazy.rememberLazyListState()
            val pageScope = androidx.compose.runtime.rememberCoroutineScope()
            val pageScroller = remember { PageScroller(pageListState, pageScope) }
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides CentredScrolling, LocalPageScroller provides pageScroller) {
            LazyColumn(state = pageListState, modifier = Modifier.onGloballyPositioned { pageScroller.viewport = it }, contentPadding = PaddingValues(top = 48.dp, bottom = pageBottomPad()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                item(key = "title") {
                    Column(Modifier.padding(horizontal = Dx.Gutter), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Christmas at Home", fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        val d = data
                        Text(
                            when {
                                d == null && done -> "Couldn't reach the catalogs. Check the TV's connection."
                                d == null -> "Loading…"
                                d.daysToChristmas == 0 -> "It's Christmas Day!"
                                d.daysToChristmas == 1 -> "1 day to Christmas"
                                else -> "${d.daysToChristmas} days to Christmas"
                            },
                            fontSize = 18.sp,
                            color = Color.White.copy(alpha = 0.75f),
                        )
                    }
                }
                data?.rows?.forEachIndexed { i, row ->
                    item(key = "row-$i") {
                        Shelf(row.name, if (i == 0) Modifier.focusRequester(first) else Modifier) {
                            items(row.items.distinctBy { it.id }, key = { it.id }) { t -> PreviewPosterCard(t, onClick = { nav.open(t) }) }
                        }
                    }
                }
            }
            }
        }
    }
}
