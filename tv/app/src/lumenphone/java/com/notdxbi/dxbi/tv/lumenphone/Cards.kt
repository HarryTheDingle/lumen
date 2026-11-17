package com.notdxbi.dxbi.tv.lumenphone

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notdxbi.dxbi.tv.phone.Airing
import com.notdxbi.dxbi.tv.phone.Continue
import com.notdxbi.dxbi.tv.phone.Title

/**
 * A title in a row: its poster, with a glass rim catching the light at the top (iOS: PosterCard). Tap opens it;
 * hold for its menu (Play, Library, trailer…) wherever the app has given cards one. [rank]: Netflix's Top 10
 * number in the corner. [progress]: how far they got.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterCard(title: Title, width: Dp, modifier: Modifier = Modifier, rank: Int? = null, progress: Float? = null, resume: Boolean = false, fillWidth: Boolean = false, onClick: () -> Unit = {}) {
    val shape = RoundedCornerShape(minOf(18f, width.value * 0.12f).dp)
    var menu by remember { mutableStateOf(false) }
    val actions = LocalTitleActions.current
    Box(
        modifier
            // ([fillWidth]: a grid's cell gives the width, and the poster keeps its shape; [width] still sizes the details)
            .then(if (fillWidth) Modifier.fillMaxWidth().aspectRatio(2f / 3f) else Modifier.width(width).height(width * 1.5f))
            .clip(shape)
            .combinedClickable(onLongClick = if (actions != null) ({ menu = true }) else null, onClick = onClick)
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
        if (progress != null && progress > 0f) ProgressLine(progress, Modifier.align(Alignment.BottomCenter).padding(horizontal = 10.dp, vertical = 10.dp).fillMaxWidth())
        if (rank != null) Text(
            "$rank",
            modifier = Modifier.align(Alignment.TopStart).padding(start = width * 0.09f, top = width * 0.02f),
            style = TextStyle(fontSize = (width.value * 0.22f).sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.78f)),
        )
        if (title.watched) Icon(
            Icons.Filled.CheckCircle, contentDescription = "Watched", tint = Color.White,
            modifier = Modifier.align(Alignment.TopEnd).padding(if (width < 130.dp) 6.dp else 8.dp).size(if (width < 130.dp) 18.dp else 22.dp),
        )
        if (actions != null) TitleMenu(title, resume, menu, actions) { menu = false }
    }
}

/**
 * Hold on a title (iOS TitleMenu): Details, Play, the Library, the trailer, Share. [resume]: it's in Continue
 * Watching (Resume, Start from the Beginning, and Remove from the row).
 */
@Composable
fun TitleMenu(title: Title, resume: Boolean, open: Boolean, actions: TitleActions, close: () -> Unit) {
    val context = LocalContext.current
    val inList = inLibrary(actions.stremio, title)
    DropdownMenu(expanded = open, onDismissRequest = close) {
        DropdownMenuItem(text = { Text("Details") }, onClick = { close(); actions.open(title) })
        DropdownMenuItem(text = { Text(if (resume) "Resume" else "Play") }, onClick = { close(); actions.play(title) })
        if (resume) DropdownMenuItem(text = { Text("Start from the Beginning") }, onClick = { close(); actions.play(title, fromStart = true) })
        if (title.type == "series") DropdownMenuItem(text = { Text("Shuffle") }, onClick = { close(); actions.play(title, shuffle = true) })
        DropdownMenuItem(
            text = { Text(if (inList) "Remove from Library" else "Add to Library") },
            onClick = {
                close()
                actions.stremio.setInList(title, !inList)
                say(context, if (inList) "Removed from your Library" else "Added to your Library")
            },
        )
        if (resume) DropdownMenuItem(text = { Text("Remove from Continue Watching") }, onClick = { close(); actions.stremio.removeFromContinue(title.id) })
        title.trailer?.let { yt ->
            DropdownMenuItem(text = { Text("Trailer") }, onClick = { close(); context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$yt"))) })
        }
        if (title.id.startsWith("tt")) DropdownMenuItem(
            text = { Text("Share") },
            onClick = {
                close()
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "${title.name} https://www.imdb.com/title/${title.id}/"), null))
            },
        )
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

/** "28m left", "1h 12m left", from the title's length and how far in they are (nothing when the length isn't known) */
fun timeLeft(item: Continue): String? {
    if (item.progress <= 0.02f || item.progress >= 0.98f) return null
    val runtime = item.title.runtime ?: return null
    val h = Regex("""(\d+)\s*h""").find(runtime)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val m = Regex("""(\d+)\s*m""").find(runtime)?.groupValues?.get(1)?.toIntOrNull() ?: if (h == 0) runtime.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 else 0
    val total = h * 60 + m
    if (total <= 0) return null
    val left = Math.round(total * (1 - item.progress))
    if (left <= 0) return null
    return if (left >= 60) "${left / 60}h ${left % 60}m left" else "${left}m left"
}

/**
 * Continue Watching (iOS ContinueCard): wide art with the title's logo (its name when it has none) over the
 * middle, and a strip along the bottom with how far they got, then S2, E5 · 28m left · 1 new. Tap opens the title.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContinueCard(item: Continue, width: Dp, onClick: () -> Unit = {}) {
    val shape = RoundedCornerShape(22.dp)
    val title = item.title
    val actions = LocalTitleActions.current
    var menu by remember { mutableStateOf(false) }
    Box(
        Modifier
            .width(width)
            .height(width * 0.62f)
            .clip(shape)
            .combinedClickable(onLongClick = if (actions != null) ({ menu = true }) else null, onClick = onClick)
            .border(BorderStroke(1.dp, Dx.Line), shape),
    ) {
        Art(title.backdrop, Modifier.fillMaxSize())
        // (iOS blurs the picture toward the bottom; a shade does the same job where there's no cheap blur)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
        Box(Modifier.align(Alignment.Center).padding(bottom = 40.dp).fillMaxWidth(0.7f), contentAlignment = Alignment.Center) {
            TitleLogo(title, maxHeight = width * 0.2f, maxWidth = width * 0.7f, textSize = 22.sp, centred = true)
        }
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(44.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (item.progress > 0f) ProgressLine(item.progress, Modifier.width(64.dp))
            val said = listOfNotNull(episodeLabel(item.videoId), timeLeft(item), if (item.newEpisodes > 0) "${item.newEpisodes} new" else null).joinToString(" · ")
            Text(said.ifEmpty { typeName(title.type) }, color = Dx.Text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1)
        }
        if (actions != null) TitleMenu(title, true, menu, actions) { menu = false }
    }
}

/** New episodes this week: the episode's picture, the show and when (iOS: AiringCard) */
@Composable
fun AiringCard(item: Airing, width: Dp, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(Modifier.width(width).height(width * 0.56f).clip(shape).clickable(onClick = onClick)) {
        Art(item.still ?: item.background, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (item.out) "NEW EPISODE" else item.countdown.uppercase(), color = if (item.out) Dx.Text else Dx.Muted, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text(item.name, color = Dx.Text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("S${item.season}, E${item.episode}${if (item.more > 0) " +${item.more}" else ""} · ${item.whenText}", color = Dx.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
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

/** A named row of posters that scrolls sideways; its posters are sized from the room there is ([AppLayout.poster]). [ranked]: Top 10 numbers */
@Composable
fun PosterShelf(name: String, titles: List<Title>, layout: AppLayout, onOpen: (Title) -> Unit = {}, ranked: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(name, layout.gutter)
        LazyRow(
            contentPadding = PaddingValues(horizontal = layout.gutter),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val shown = if (ranked) titles.take(10) else titles
            items(shown.size, key = { shown[it].id }) { i -> PosterCard(shown[i], layout.poster, rank = if (ranked) i + 1 else null, onClick = { onOpen(shown[i]) }) }
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
