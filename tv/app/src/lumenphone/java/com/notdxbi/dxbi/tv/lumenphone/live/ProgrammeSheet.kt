package com.notdxbi.dxbi.tv.lumenphone.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.notdxbi.dxbi.tv.live.LiveReminders
import com.notdxbi.dxbi.tv.live.LiveTvStore
import com.notdxbi.dxbi.tv.lumenphone.Dx
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape

/** A programme (or just a channel): when it's on, what it is, and what can be done with it */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProgrammeSheet(store: LiveTvStore, picked: Picked, zap: List<String>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val ch = picked.channel
    val p = picked.programme
    val enrich by store.enrichments.collectAsState()
    val reminders by LiveReminders.all.collectAsState()
    val prefs by store.prefs.collectAsState()
    val now = System.currentTimeMillis()
    LaunchedEffect(p?.id) { if (p != null) store.enrichIfMissing(ch.id, p) }
    val extra = p?.let { enrich[it.id] }
    val picture = p?.iconUrl?.takeIf { it.isNotBlank() } ?: extra?.backdrop
    val text = p?.description?.takeIf { it.isNotBlank() } ?: extra?.description
    val onNow = p != null && p.startMs <= now && p.endMs > now
    val future = p != null && p.startMs > now
    val past = p != null && p.endMs <= now
    val fav = ch.id in prefs.favourites

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF1C1C1E)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 20.dp)) {
            if (picture != null) AsyncImage(
                model = picture, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)),
            )
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChannelTile(tileLogoFor(ch), ch.name, 64.dp, 40.dp)
                Column {
                    Text(p?.title ?: ch.name, color = Dx.Text, style = MaterialTheme.typography.titleLarge)
                    if (p != null) Text("${ch.name}  ·  ${LiveTime.range(p.startMs, p.endMs)}${if (future) "  ·  ${LiveTime.until(p.startMs)}" else ""}", color = Dx.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            p?.subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, color = Dx.Text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp)) }
            text?.let { Text(it, color = Dx.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (p == null || onNow) LiveButton("Watch ${if (p == null) ch.name else "now"}", Modifier.fillMaxWidth(), onClick = { onDismiss(); store.playLive(context, ch, zapChannelIds = zap) })
                if (onNow && ch.hasCatchup) LiveButton("Watch from the start", Modifier.fillMaxWidth(), primary = false, onClick = { onDismiss(); store.playCatchup(context, ch, p!!, zap) })
                if (past && ch.hasCatchup) LiveButton("Watch the replay", Modifier.fillMaxWidth(), onClick = { onDismiss(); store.playCatchup(context, ch, p!!, zap) })
                if (future) {
                    val has = reminders.any { it.id == p!!.id }
                    LiveButton(if (has) "Reminder set — tap to remove" else "Remind me", Modifier.fillMaxWidth(), primary = !has, onClick = {
                        LiveReminders.toggle(context, LiveReminders.Reminder(p!!.id, p.title, p.startMs, ch.id, ch.name))
                    })
                }
                LiveButton(if (fav) "Remove from favourites" else "Add channel to favourites", Modifier.fillMaxWidth(), primary = false, onClick = { store.toggleFavourite(ch.id) })
            }
        }
    }
}
