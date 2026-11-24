package com.notdxbi.dxbi.tv.lumenphone.live

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.notdxbi.dxbi.tv.live.LiveTvStore
import com.notdxbi.dxbi.tv.lumenphone.AppLayout
import com.notdxbi.dxbi.tv.lumenphone.Dx
import kotlinx.coroutines.delay
import lumos.iptv.CanonicalChannel
import lumos.iptv.EpgProgramme
import lumos.iptv.GuideChannelRow

/** What the programme sheet is open on */
internal data class Picked(val channel: CanonicalChannel, val programme: EpgProgramme?)

@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    return now
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GuidePage(store: LiveTvStore, layout: AppLayout, bottomSpace: Dp) {
    val context = LocalContext.current
    val rows by store.mainGuideRows.collectAsState()
    val prefs by store.prefs.collectAsState()
    val channels by store.channels.collectAsState()
    val version by store.epgVersion.collectAsState()
    val ui by store.uiState.collectAsState()
    val now = rememberNow()
    var grid by rememberSaveable { mutableStateOf(!layout.compact) }
    var picked by remember { mutableStateOf<Picked?>(null) }

    val visible = remember(rows, prefs, channels) {
        val playable = rows.filter { it.isPlayable && it.linkedChannel != null && it.linkedChannel!!.id !in prefs.hiddenChannels }
        playable.ifEmpty { if (channels.isEmpty()) emptyList() else rows.filter { it.linkedChannel?.id !in prefs.hiddenChannels } }
    }
    val zap = remember(visible) { visible.mapNotNull { it.linkedChannel?.id } }

    if (visible.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (ui.loading || ui.refreshing || channels.isEmpty()) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LiveSpinner(); Text(if (ui.error != null) ui.error!! else "Finding your channels…", color = Dx.Muted, modifier = Modifier.padding(top = 12.dp))
            } else EmptyNote("No channels yet", ui.error ?: "Your provider hasn't given Lumen any channels. Check it in Live TV settings.")
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = layout.gutter, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LiveChip("List", !grid, { grid = false }); LiveChip("Timeline", grid, { grid = true })
        }
        if (grid) Timeline(store, visible, layout, bottomSpace, now, version, zap) { picked = it }
        else ChannelList(store, visible, layout, bottomSpace, now, version, zap) { picked = it }
    }
    picked?.let { ProgrammeSheet(store, it, zap, onDismiss = { picked = null }) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelList(store: LiveTvStore, rows: List<GuideChannelRow>, layout: AppLayout, bottomSpace: Dp, now: Long, version: Long, zap: List<String>, onPick: (Picked) -> Unit) {
    val context = LocalContext.current
    val favs by store.favourites.collectAsState()
    val recents by store.recents.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = bottomSpace + 16.dp)) {
        if (favs.isNotEmpty()) item(key = "favs") { ChannelRail("Favourites", favs, store, zap) }
        if (recents.isNotEmpty()) item(key = "recents") { ChannelRail("Recently watched", recents.take(12), store, zap) }
        item(key = "all") { SectionTitle("All channels") }
        items(rows, key = { it.guideChannel.id }) { row ->
            val ch = row.linkedChannel
            val (cur, next) = remember(row, version, now / 30_000) { ch?.let { store.nowAndNext(it, now) } ?: (null to null) }
            LaunchedEffect(ch?.id, cur == null) { if (ch != null && cur == null) store.fetchChannelEpgIfMissing(ch) }
            Row(
                Modifier.fillMaxWidth()
                    .combinedClickable(
                        onClick = { store.playGuideRow(context, row, zap) },
                        onLongClick = { ch?.let { onPick(Picked(it, cur)) } },
                    )
                    .padding(horizontal = layout.gutter, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ChannelTile(row.logoUrl, row.guideChannel.name, 76.dp, 48.dp)
                Column(Modifier.weight(1f)) {
                    Text(cur?.title ?: row.guideChannel.name, color = Dx.Text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (cur != null) {
                        Text("${LiveTime.range(cur.startMs, cur.endMs)}${next?.let { "  ·  Next: ${it.title}" } ?: ""}", color = Dx.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ProgressLine(((now - cur.startMs).toFloat() / (cur.endMs - cur.startMs).coerceAtLeast(1L)), Modifier.padding(top = 6.dp))
                    } else Text(row.guideChannel.name, color = Dx.Faint, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                row.qualityBadge?.takeIf { it.isNotBlank() }?.let { Text(it, color = Dx.Faint, style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

@Composable
private fun ChannelRail(title: String, channels: List<CanonicalChannel>, store: LiveTvStore, zap: List<String>) {
    val context = LocalContext.current
    SectionTitle(title)
    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Dx.Gutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(channels, key = { it.id }) { ch ->
            Column(Modifier.width(110.dp).clip(RoundedCornerShape(10.dp)).combinedClickableLite { store.playLive(context, ch, zapChannelIds = zap) }) {
                ChannelTile(tileLogoFor(ch), ch.name, 110.dp, 66.dp)
                Text(store.nowAndNext(ch).first?.title ?: ch.name, color = Dx.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

private fun Modifier.combinedClickableLite(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

/** The guide as a timeline: channels down the side, programmes along, scrolling together */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Timeline(store: LiveTvStore, rows: List<GuideChannelRow>, layout: AppLayout, bottomSpace: Dp, now: Long, version: Long, zap: List<String>, onPick: (Picked) -> Unit) {
    val context = LocalContext.current
    val perMin = 4.2f
    val halfHour = 30 * 60_000L
    val from = remember(now / halfHour) { (now / halfHour) * halfHour - 30 * 60_000L }
    val spanMs = 6 * 3_600_000L
    val to = from + spanMs
    val scroll = rememberScrollState()
    val labelW = 88.dp
    val listState = rememberLazyListState()
    LaunchedEffect(from) { scroll.scrollTo(0) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(28.dp)) {
            Spacer(Modifier.width(labelW))
            Row(Modifier.weight(1f).horizontalScroll(scroll)) {
                var t = from
                while (t < to) {
                    Box(Modifier.width((halfHour / 60_000L * perMin).dp), contentAlignment = Alignment.CenterStart) {
                        Text(LiveTime.hm(t), color = Dx.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 4.dp))
                    }
                    t += halfHour
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = bottomSpace + 16.dp)) {
            items(rows, key = { it.guideChannel.id }) { row ->
                val ch = row.linkedChannel
                val progs = remember(row, version, from) { store.programmesForWindow(ch, row.guideChannel.id, from, to) }
                LaunchedEffect(ch?.id, progs.isEmpty()) { if (ch != null && progs.isEmpty()) store.fetchChannelEpgIfMissing(ch) }
                Row(Modifier.fillMaxWidth().height(60.dp).padding(vertical = 2.dp)) {
                    Box(Modifier.width(labelW).fillMaxHeight().padding(end = 6.dp, start = 6.dp).combinedClickableLite { store.playGuideRow(context, row, zap) }, contentAlignment = Alignment.Center) {
                        ChannelTile(row.logoUrl, row.guideChannel.name, 76.dp, 50.dp)
                    }
                    Row(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll)) {
                        var cursor = from
                        progs.sortedBy { it.startMs }.forEach { p ->
                            val s = maxOf(p.startMs, cursor); val e = minOf(p.endMs, to)
                            if (e <= s) return@forEach
                            if (s > cursor) Spacer(Modifier.width(((s - cursor) / 60_000f * perMin).dp))
                            val live = p.startMs <= now && p.endMs > now
                            Box(
                                Modifier.width(((e - s) / 60_000f * perMin).dp - 2.dp).fillMaxHeight().padding(end = 2.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (live) Color(0xFF3D3D44) else Color(0xFF151517))
                                    .combinedClickable(
                                        onClick = { if (ch != null) onPick(Picked(ch, p)) },
                                        onLongClick = { if (ch != null) store.playGuideRow(context, row, zap) },
                                    )
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            ) {
                                Column {
                                    Text(p.title, color = if (live) Dx.Text else Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall, fontWeight = if (live) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(LiveTime.hm(p.startMs), color = Dx.Faint, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                }
                            }
                            cursor = e
                        }
                    }
                }
            }
        }
    }
}
