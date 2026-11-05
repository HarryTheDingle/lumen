package com.notdxbi.dxbi.tv.lumen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.notdxbi.dxbi.tv.phone.Airing
import com.notdxbi.dxbi.tv.phone.Continue
import com.notdxbi.dxbi.tv.phone.Title

/**
 * A card the remote can choose, the Apple TV way: it lifts towards you when chosen, larger, casting a
 * soft shadow down under it, with a faint white edge
 */
@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    corner: Dp = 12.dp,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    var focused by remember { mutableStateOf(false) }
    val lift by animateFloatAsState(if (focused) 1f else 0f, tween(180), label = "lift")
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        // (grown and lifted here rather than by the card, so the shadow grows with it: Android's light
        // sits above the screen, so a lifted card's shadow falls below it)
        modifier = modifier
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .graphicsLayer {
                val s = 1f + 0.1f * lift
                scaleX = s
                scaleY = s
                this.shape = shape
                shadowElevation = 26.dp.toPx() * lift
                ambientShadowColor = Color.Black
                spotShadowColor = Color.Black
            },
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(containerColor = Dx.Raised, focusedContainerColor = Dx.Raised),
        scale = CardDefaults.scale(focusedScale = 1f),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)), shape = shape),
        ),
        glow = CardDefaults.glow(focusedGlow = Glow.None),
    ) {
        content()
    }
}

/** A row moves only when the remote goes past its edge, just enough for the next card and a little of the one after */
@OptIn(ExperimentalFoundationApi::class)
private val RowScrolling = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val margin = containerSize * 0.06f
        return when {
            offset < margin -> offset - margin
            offset + size > containerSize - margin -> offset + size - containerSize + margin
            else -> 0f
        }
    }
}

/** A titled row of cards that scrolls sideways as the remote moves along it */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Shelf(title: String, modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Text(
            title,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = Dx.Gutter),
        )
        Spacer(Modifier.height(10.dp))
        CompositionLocalProvider(LocalBringIntoViewSpec provides RowScrolling) {
            LazyRow(
                // (room around the cards for them to grow and glow when chosen)
                contentPadding = PaddingValues(horizontal = Dx.Gutter, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                content = content,
            )
        }
    }
}

/**
 * A title in a row: its poster, and (Netflix's Top 10) a large number in the corner. [fill]: as wide as
 * the space it's given (a grid's), keeping the poster's shape
 */
@Composable
fun PosterCard(title: Title, onClick: () -> Unit, rank: Int? = null, width: Dp = Dx.Poster, fill: Boolean = false) {
    FocusCard(onClick = onClick, modifier = if (fill) Modifier.fillMaxWidth().aspectRatio(2f / 3f) else Modifier.size(width, width * 1.5f)) {
        Box(Modifier.fillMaxSize()) {
            Art(title.art, Modifier.fillMaxSize())
            if (rank != null) {
                Text(
                    "$rank",
                    style = TextStyle(
                        fontSize = (width.value * 0.3f).sp,
                        fontWeight = FontWeight.Black,
                        shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 12f),
                    ),
                    color = Color.White,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (title.watched) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Watched",
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).background(Color.Black, CircleShape),
                )
            }
        }
    }
}

/** Continue Watching: wide art with the logo, and a strip with how far they got */
@Composable
fun ContinueCard(item: Continue, onClick: () -> Unit, width: Dp = 300.dp) {
    val title = item.title
    FocusCard(onClick = onClick, corner = 16.dp, modifier = Modifier.size(width, width * 0.56f)) {
        Box(Modifier.fillMaxSize()) {
            Art(title.backdrop, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
            TitleLogo(
                title,
                maxHeight = width * 0.16f,
                maxWidth = width * 0.7f,
                textSize = 20.sp,
                centred = true,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = width * 0.15f).padding(bottom = 24.dp),
            )
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (item.progress > 0f) ProgressLine(item.progress, Modifier.width(56.dp))
                val words = listOfNotNull(
                    episodeLabel(item.videoId),
                    if (item.newEpisodes > 0) "${item.newEpisodes} new" else null,
                ).joinToString(" · ").ifEmpty { typeName(title.type) }
                Text(words, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White, maxLines = 1)
            }
        }
    }
}

/** New episodes this week: the episode's picture, the show and when */
@Composable
fun AiringCard(item: Airing, onClick: () -> Unit, width: Dp = 300.dp) {
    FocusCard(onClick = onClick, corner = 16.dp, modifier = Modifier.size(width, width * 0.56f)) {
        Box(Modifier.fillMaxSize()) {
            Art(item.still ?: item.background, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
            Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                Text(
                    if (item.out) "NEW EPISODE" else item.countdown.uppercase(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (item.out) Color.White else Dx.Muted,
                )
                Text(item.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "S${item.season}, E${item.episode}${if (item.more > 0) " +${item.more}" else ""} · ${item.whenText}",
                    fontSize = 12.sp,
                    color = Dx.Muted,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A row still loading: grey cards where its posters will be */
@Composable
fun LoadingShelf(title: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Dx.Faint, modifier = Modifier.padding(horizontal = Dx.Gutter))
        Spacer(Modifier.height(10.dp))
        Row(Modifier.padding(horizontal = Dx.Gutter, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            repeat(6) { Box(Modifier.size(Dx.Poster, Dx.Poster * 1.5f).background(Dx.Raised, RoundedCornerShape(12.dp))) }
        }
    }
}
