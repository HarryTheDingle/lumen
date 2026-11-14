package com.notdxbi.dxbi.tv.lumenphone

import android.os.StatFs
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.notdxbi.dxbi.tv.Downloads

/**
 * Downloads (iOS DownloadsView): what's being saved to watch offline, with its progress, Pause and Cancel, then
 * what's saved: films first, then each show with its episodes in order. A finished one plays from the phone, in
 * Lumen's player, with no page and no addons (where it was left is remembered); ⋮ deletes it. The top says what
 * they use and what's left on the phone, and Wi-Fi only keeps a download off mobile data. Downloads carry on
 * with Lumen closed, their progress in a notification (DownloadsService). On the opened Fold the list is the
 * centred column Profile's pages are, with larger pictures, as on the iPad.
 */
@Composable
fun DownloadsPage(layout: AppLayout, bottomSpace: Dp, onBack: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { Downloads.manager(context) }
    val all by Downloads.items.collectAsState()
    val wifiOnly by Downloads.wifiOnly.collectAsState()
    // (removing one, or a whole show's: asked first)
    var removing by remember { mutableStateOf<List<Downloads.Item>?>(null) }

    val items = all.filter { it.state != androidx.media3.exoplayer.offline.Download.STATE_REMOVING }
    val active = items.filter { !it.done }
    val saved = items.filter { it.done }
    val films = saved.filter { !it.isEpisode }.sortedBy { it.title.lowercase() }
    val shows = saved.filter { it.isEpisode }.groupBy { it.title }.toSortedMap(compareBy { it.lowercase() })
    val art = if (layout.compact) 112.dp else 144.dp

    ProfilePage("Downloads", layout, bottomSpace, onBack) {
        if (items.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No Downloads", color = Dx.Text, style = MaterialTheme.typography.titleLarge)
                Text(
                    "Download a film or episode from its ⋯ menu to watch it offline. Debrid and direct streams can be saved; torrents can't.",
                    color = Dx.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp, start = 24.dp, end = 24.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        item {
            val used = items.sumOf { it.bytes }
            ProfileGroup(footer = "Downloads wait for Wi-Fi when this is on, and use mobile data when it's off.") {
                row {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Used by downloads", color = Dx.Text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text("${Formatter.formatFileSize(context, used)} · ${Formatter.formatFileSize(context, freeBytes(context))} free", color = Dx.Muted, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                toggle("Download on Wi-Fi Only", on = wifiOnly) { Downloads.setWifiOnly(context, it) }
            }
        }
        if (active.isNotEmpty()) item {
            ProfileGroup(header = "Downloading") {
                active.forEach { d -> row { DownloadRow(d, art, wifiOnly, onDelete = { removing = listOf(d) }) } }
            }
        }
        if (films.isNotEmpty()) item {
            ProfileGroup(header = "Films") {
                films.forEach { d -> row { DownloadRow(d, art, wifiOnly, onDelete = { removing = listOf(d) }) } }
            }
        }
        shows.forEach { (name, episodes) ->
            item(key = "show:$name") {
                val sorted = episodes.sortedWith(compareBy({ it.season }, { it.episode }))
                ProfileGroup(
                    header = name,
                    footer = "${episodes.size} episode${if (episodes.size == 1) "" else "s"} · ${Formatter.formatFileSize(context, episodes.sumOf { it.bytes })}",
                ) {
                    sorted.forEach { d -> row { DownloadRow(d, art, wifiOnly, onDelete = { removing = listOf(d) }) } }
                    if (sorted.size > 1) link("Delete All Episodes", icon = Icons.Filled.Delete, chevron = false, destructive = true) { removing = sorted }
                }
            }
        }
    }

    removing?.let { list ->
        val one = list.singleOrNull()
        AlertDialog(
            onDismissRequest = { removing = null },
            containerColor = Color(0xFF2A2A2A),
            title = { Text(if (one == null) "Delete ${list.size} downloads?" else if (one.done) "Delete this download?" else "Cancel this download?", color = Dx.Text) },
            text = { Text(one?.let { listOf(it.title, it.subtitle).filter { s -> s.isNotEmpty() }.joinToString(" · ") } ?: list.first().title, color = Dx.Muted) },
            confirmButton = { TextButton(onClick = { list.forEach { Downloads.remove(context, it) }; removing = null }) { Text(if (one?.done == false) "Cancel Download" else "Delete", color = Dx.Problem) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Keep", color = Dx.Text) } },
        )
    }
}

/** A series' episode (its video id is the title's id, then season and episode), not a film */
private val Downloads.Item.isEpisode: Boolean get() = season >= 0 || subtitle.isNotEmpty()
private val Downloads.Item.season: Int get() = stream.optString("videoId").split(":").let { p -> if (p.size >= 3) p[p.size - 2].toIntOrNull() ?: -1 else -1 }
private val Downloads.Item.episode: Int get() = stream.optString("videoId").split(":").let { p -> if (p.size >= 3) p.last().toIntOrNull() ?: 0 else 0 }

private fun freeBytes(context: android.content.Context): Long = runCatching {
    StatFs((context.getExternalFilesDir(null) ?: context.filesDir).path).availableBytes
}.getOrDefault(0L)

@Composable
private fun DownloadRow(d: Downloads.Item, artWidth: Dp, wifiOnly: Boolean, onDelete: () -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val running = !d.done && !d.failed
    Row(
        Modifier.fillMaxWidth().then(if (d.done) Modifier.clickable { Downloads.play(context, d) } else Modifier).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(artWidth).height(artWidth * 9 / 16).clip(RoundedCornerShape(10.dp))) {
            Art(d.art, Modifier.fillMaxSize())
            if (d.done) Icon(Icons.Filled.PlayCircle, contentDescription = "Play", tint = Color.White, modifier = Modifier.align(Alignment.Center).size(30.dp))
        }
        Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp)) {
            Text(d.title, color = Dx.Text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (d.subtitle.isNotEmpty()) Text(d.subtitle, color = Dx.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (running) ProgressLine(d.percent / 100f, Modifier.fillMaxWidth().padding(top = 2.dp))
            val size = { b: Long -> Formatter.formatFileSize(context, b) }
            Text(
                when {
                    d.done -> size(d.bytes)
                    d.failed -> "Couldn't download. Try again, or delete it and download it afresh."
                    d.paused -> "Paused · " + if (d.totalBytes > 0) "${size(d.bytes)} of ${size(d.totalBytes)}" else size(d.bytes)
                    d.queued -> if (wifiOnly && onMobileData(context)) "Waiting for Wi-Fi" else "Waiting…"
                    d.totalBytes > 0 -> "${size(d.bytes)} of ${size(d.totalBytes)}"
                    d.bytes > 0 -> size(d.bytes)
                    else -> "Starting…"
                },
                color = if (d.failed) Dx.Problem else Dx.Faint,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
            )
        }
        when {
            // saved: ⋮ plays or deletes it
            d.done -> Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = Dx.Muted) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Color(0xFF2A2A2A)) {
                    DropdownMenuItem(text = { Text("Play", color = Dx.Text) }, leadingIcon = { Icon(Icons.Filled.PlayArrow, null, tint = Dx.Text) }, onClick = { menu = false; Downloads.play(context, d) })
                    DropdownMenuItem(text = { Text("Delete", color = Dx.Problem) }, leadingIcon = { Icon(Icons.Filled.Delete, null, tint = Dx.Problem) }, onClick = { menu = false; onDelete() })
                }
            }
            d.failed -> {
                if (d.request != null) IconButton(onClick = { Downloads.retry(context, d) }) { Icon(Icons.Filled.Refresh, contentDescription = "Try again", tint = Dx.Text) }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Dx.Muted) }
            }
            else -> {
                IconButton(onClick = { if (d.paused) Downloads.resume(context, d) else Downloads.pause(context, d) }) {
                    Icon(if (d.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, contentDescription = if (d.paused) "Resume" else "Pause", tint = Dx.Text)
                }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = Dx.Muted) }
            }
        }
    }
}
