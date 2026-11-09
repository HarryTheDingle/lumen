package com.notdxbi.dxbi.tv.lumenphone

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notdxbi.dxbi.tv.phone.Continue
import com.notdxbi.dxbi.tv.phone.Title

/** A title in a row: its poster, with a glass rim catching the light at the top (iOS: PosterCard) */
@Composable
fun PosterCard(title: Title, width: Dp, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    val shape = RoundedCornerShape(minOf(18f, width.value * 0.12f).dp)
    Box(
        modifier
            .width(width)
            .height(width * 1.5f)
            .clip(shape)
            .clickable(onClick = onClick)
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.32f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.14f))), shape),
    ) {
        Art(title.art, Modifier.fillMaxSize())
        // (an anime's poster often doesn't name it in a language you can read: its name goes on it in text)
        if (title.id.startsWith("kitsu:") || title.id.startsWith("mal:")) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.78f))))
            Text(
                title.name,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                color = Dx.Text,
                fontSize = maxOf(11f, width.value * 0.1f).sp,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A thin progress line: white over a faint track, with a dot where there's only a little (iOS: ProgressLine) */
@Composable
fun ProgressLine(value: Float, modifier: Modifier = Modifier) {
    val height = 4.dp
    Box(modifier.height(height).clip(CircleShape).background(Color.White.copy(alpha = 0.3f))) {
        if (value > 0f) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                Box(Modifier.width(maxOf(maxWidth * value.coerceIn(0f, 1f), height)).height(height).background(Color.White))
            }
        }
    }
}

/** Continue Watching: wide art with the title's name, and a strip with how far they got (iOS: ContinueCard) */
@Composable
fun ContinueCard(item: Continue, width: Dp, onClick: () -> Unit = {}) {
    val shape = RoundedCornerShape(22.dp)
    val title = item.title
    Box(
        Modifier
            .width(width)
            .height(width * 0.62f)
            .clip(shape)
            .clickable(onClick = onClick)
            .border(BorderStroke(1.dp, Dx.Line), shape),
    ) {
        Art(title.backdrop, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.9f))))
        Text(
            title.name,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 20.dp).padding(bottom = 30.dp),
            color = Dx.Text,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(44.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (item.progress > 0f) ProgressLine(item.progress, Modifier.width(64.dp))
            val said = listOfNotNull(episodeLabel(item.videoId), if (item.newEpisodes > 0) "${item.newEpisodes} new" else null).joinToString(" · ")
            Text(said.ifEmpty { typeName(title.type) }, color = Dx.Text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

/** A row's name, large and bold (iOS: SectionHeader; its chevron comes with See All) */
@Composable
fun SectionHeader(title: String, gutter: Dp = Dx.Gutter) {
    Text(
        title,
        modifier = Modifier.fillMaxWidth().padding(horizontal = gutter),
        color = Dx.Text,
        style = MaterialTheme.typography.titleLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A named row of posters that scrolls sideways */
@Composable
fun PosterShelf(name: String, titles: List<Title>, layout: AppLayout, onOpen: (Title) -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(name, layout.gutter)
        LazyRow(
            contentPadding = PaddingValues(horizontal = layout.gutter),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(titles, key = { it.id }) { PosterCard(it, layout.poster, onClick = { onOpen(it) }) }
        }
    }
}

/** A row that hasn't arrived yet: grey posters where the real ones go */
@Composable
fun LoadingShelf(name: String, layout: AppLayout) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (name.isNotEmpty()) SectionHeader(name, layout.gutter) else Box(Modifier.height(28.dp))
        Row(Modifier.padding(horizontal = layout.gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(if (layout.compact) 3 else 6) {
                Box(Modifier.size(layout.poster, layout.poster * 1.5f).clip(RoundedCornerShape(layout.poster * 0.12f)).background(Dx.Raised))
            }
        }
    }
}

/** What a screen says until it's made: its name, centred (Phase 2 fills them in) */
@Composable
fun ComingSoon(name: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(name, color = Dx.Text, style = MaterialTheme.typography.headlineMedium)
            Text("Coming soon", color = Dx.Muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
