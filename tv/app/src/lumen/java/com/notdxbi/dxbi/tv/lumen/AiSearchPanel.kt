@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.notdxbi.dxbi.tv.lumen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.notdxbi.dxbi.tv.phone.AiPhase
import com.notdxbi.dxbi.tv.phone.AiSearch
import com.notdxbi.dxbi.tv.phone.Stremio
import com.notdxbi.dxbi.tv.phone.Title
import kotlinx.coroutines.delay

/** "✨ Ask AI", by the search box: plain frosted pill, white when the remote's on it */
@Composable
fun AskAiButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Glass.Fill, contentColor = Color.White, focusedContainerColor = Color.White, focusedContentColor = Color.Black),
        border = ClickableSurfaceDefaults.border(border = androidx.tv.material3.Border(BorderStroke(1.dp, Glass.Edge), shape = shape)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Text("✨  Ask AI", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
    }
}

/**
 * What Ask AI shows below the box: listening, finding, a failure, or one row of posters titled with the prompt. Resting on
 * a poster brings a second row, "More like <it>" (as Apple TV's Genius Browse does), switching as the remote moves.
 * Back from the posters goes to the box.
 */
@Composable
fun AiSearchPanel(ai: AiSearch, stremio: Stremio, nav: Nav, searchBox: FocusRequester, firstTile: FocusRequester, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when (ai.phase) {
            AiPhase.Listening -> Status(if (ai.prompt.isBlank()) "Listening…" else ai.prompt, hint = if (ai.prompt.isBlank()) "Say what you feel like watching" else "Listening…")
            AiPhase.Finding -> Status("Finding titles…", spinner = true, hint = ai.prompt)
            AiPhase.Failed -> Status("Couldn't search right now", retry = onRetry)
            AiPhase.Empty -> Status("Nothing matched, try describing it differently", retry = onRetry)
            AiPhase.NeedsText -> Status("Type what you're after in the box, then choose Ask AI")
            AiPhase.Ready -> Results(ai, stremio, nav, searchBox, firstTile)
            AiPhase.Idle -> {}
        }
    }
}

@Composable
private fun Status(line: String, hint: String? = null, spinner: Boolean = false, retry: (() -> Unit)? = null) {
    Column(Modifier.padding(horizontal = Dx.Gutter, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (spinner) {
                Spinner()
                Spacer(Modifier.width(12.dp))
            }
            Text(line, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
        if (!hint.isNullOrBlank()) Text(hint, fontSize = 15.sp, color = Dx.Muted)
        if (retry != null) {
            val shape = RoundedCornerShape(50)
            Surface(
                onClick = retry,
                shape = ClickableSurfaceDefaults.shape(shape),
                colors = ClickableSurfaceDefaults.colors(containerColor = Glass.Fill, contentColor = Color.White, focusedContainerColor = Color.White, focusedContentColor = Color.Black),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
            ) { Text("Retry", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) }
        }
    }
}

@Composable
private fun Results(ai: AiSearch, stremio: Stremio, nav: Nav, searchBox: FocusRequester, firstTile: FocusRequester) {
    var hovered by remember { mutableStateOf<Title?>(null) }
    // the row shown, and whose it is: the old one stays until the next has loaded, and isn't swapped under the remote
    var moreFor by remember { mutableStateOf<Title?>(null) }
    var moreLike by remember { mutableStateOf<List<Title>>(emptyList()) }
    var pending by remember { mutableStateOf<Pair<Title, List<Title>>?>(null) }
    var inMore by remember { mutableStateOf(false) }
    var inRow by remember { mutableStateOf(false) }

    // a moment after the remote rests on a poster (a new focus cancels the load in flight)
    LaunchedEffect(hovered) {
        val t = hovered ?: return@LaunchedEffect
        if (moreFor == null && pending == null) delay(150) else delay(350)
        val list = runCatching { stremio.related(t.type, t.id, t.genres) }.getOrDefault(emptyList()).filter { it.id != t.id }.take(20)
        if (list.isNotEmpty()) pending = t to list
    }
    LaunchedEffect(pending, inMore) {
        val p = pending ?: return@LaunchedEffect
        if (!inMore) { moreFor = p.first; moreLike = p.second; pending = null }
    }
    // (the box is where Back goes from the posters)
    BackHandler(enabled = inRow || inMore) { runCatching { searchBox.requestFocus() } }

    CompositionLocalProvider(LocalBringIntoViewSpec provides RowScrolling) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(ai.prompt.replaceFirstChar { it.uppercase() }, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, modifier = Modifier.padding(horizontal = Dx.Gutter))
            LazyRow(
                Modifier.focusRestorer(firstTile).onFocusChanged { inRow = it.hasFocus },
                contentPadding = PaddingValues(horizontal = Dx.Gutter, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(ai.titles, key = { it.id }) { t ->
                    Box(Modifier.onFocusChanged { if (it.hasFocus) hovered = t }.then(if (t.id == ai.titles.first().id) Modifier.focusRequester(firstTile) else Modifier)) {
                        PosterCard(t, onClick = { nav.open(t) })
                    }
                }
            }
            val shownFor = moreFor
            if (shownFor != null && moreLike.isNotEmpty()) {
                Text("More like ${shownFor.name}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.85f), maxLines = 1, modifier = Modifier.padding(horizontal = Dx.Gutter))
                LazyRow(
                    Modifier.focusRestorer().onFocusChanged { inMore = it.hasFocus },
                    contentPadding = PaddingValues(horizontal = Dx.Gutter, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(moreLike, key = { it.id }) { t -> PosterCard(t, onClick = { nav.open(t) }) }
                }
            }
        }
    }
}

/** A small turning arc (the TV build has no Material progress indicator) */
@Composable
private fun Spinner() {
    val turn by rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "turn")
    Canvas(Modifier.size(22.dp)) {
        val w = 2.5.dp.toPx()
        drawArc(Dx.Muted, turn, 270f, false, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round))
    }
}
