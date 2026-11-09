package com.notdxbi.dxbi.tv.lumenphone

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayCircle
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.notdxbi.dxbi.tv.phone.Video

/**
 * An episode, as Apple TV shows them (iOS EpisodeCard): a tall card, the picture above and the words on a dark
 * foot below. Tap plays it; hold for Play, Choose a Stream, and watched or not. [playing]: the one Play would start.
 * [blur]: Blur unwatched episodes in Lumen settings (the picture hidden until it's watched or started; Android 11
 * and older can't blur, so it's covered).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EpisodeCard(
    v: Video, width: Dp, fallbackArt: String?, playing: Boolean, blur: Boolean,
    onPlay: () -> Unit, onChoose: () -> Unit, onWatched: (watched: Boolean, upTo: Boolean) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var missing by remember(v.thumbnail) { mutableStateOf(v.thumbnail == null) }
    val hidden = blur && !v.watched && (v.progress ?: 0f) <= 0f
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier
            .width(width).height(width * 0.92f).clip(shape)
            .border(if (playing) 2.dp else 1.dp, Color.White.copy(alpha = if (playing) 0.6f else 0.1f), shape)
            .alpha(if (v.upcoming) 0.6f else 1f)
            .combinedClickable(enabled = !v.upcoming, onLongClick = { menu = true }, onClick = onPlay),
    ) {
        val art = if (missing) fallbackArt else v.thumbnail ?: fallbackArt
        if (hidden && android.os.Build.VERSION.SDK_INT < 31) Box(Modifier.fillMaxSize().background(Color(0xFF2A2A2E)))
        else Art(art, Modifier.fillMaxSize().then(if (hidden) Modifier.blur(22.dp) else Modifier), onError = { missing = true })
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.36f to Color.Transparent, 0.7f to Color.Black.copy(alpha = 0.78f), 1f to Color.Black.copy(alpha = 0.92f))))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(v.episode?.let { "EPISODE $it" } ?: "SPECIAL", color = Dx.Muted, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text(v.title.ifEmpty { "Episode ${v.episode ?: 0}" }, color = Dx.Text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            v.overview?.let { Text(it, color = Dx.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    v.upcoming -> Text(v.released?.take(10) ?: "Coming soon", color = Dx.Text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    v.watched -> {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                        Text("Watched", color = Dx.Text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    }
                    (v.progress ?: 0f) > 0f -> ProgressLine(v.progress ?: 0f, Modifier.width(60.dp))
                    else -> v.released?.take(10)?.let { Text(it, color = Dx.Muted, style = MaterialTheme.typography.bodySmall) }
                }
                Box(Modifier.weight(1f))
                if (playing) Icon(Icons.Filled.PlayCircle, contentDescription = "Up next", tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Play") }, onClick = { menu = false; onPlay() })
            DropdownMenuItem(text = { Text("Choose a Stream") }, onClick = { menu = false; onChoose() })
            DropdownMenuItem(text = { Text(if (v.watched) "Mark as Unwatched" else "Mark as Watched") }, onClick = { menu = false; onWatched(!v.watched, false) })
            if ((v.episode ?: 0) > 1) DropdownMenuItem(text = { Text("Mark Up to Here as Watched") }, onClick = { menu = false; onWatched(true, true) })
        }
    }
}
