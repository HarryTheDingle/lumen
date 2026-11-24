package com.notdxbi.dxbi.tv.lumenphone.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notdxbi.dxbi.tv.live.LiveTvStore
import com.notdxbi.dxbi.tv.lumenphone.AppLayout
import com.notdxbi.dxbi.tv.lumenphone.Dx
import lumos.iptv.CanonicalChannel
import lumos.iptv.ChannelCategory
import lumos.iptv.EpgProgramme

/** Find a channel, or what's on: a search box, categories, and results as logo tiles */
@Composable
internal fun FindPage(store: LiveTvStore, layout: AppLayout, bottomSpace: Dp) {
    val context = LocalContext.current
    val channels by store.channels.collectAsState()
    val categories by store.categories.collectAsState()
    val prefs by store.prefs.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<Picked?>(null) }
    val q = query.trim()
    val results = remember(q, channels) { if (q.length >= 2) store.search(q) else null }
    val shown = remember(channels, category, prefs) {
        channels.filter { it.id !in prefs.hiddenChannels && (category == null || it.category.name == category) }
    }
    val zap = remember(shown) { shown.map { it.id } }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(horizontal = layout.gutter, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Dx.Raised).padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.Search, null, tint = Dx.Muted)
            BasicTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 17.sp),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.weight(1f),
                decorationBox = { inner -> Box { if (query.isEmpty()) Text("Channels, programmes, teams", color = Dx.Faint, fontSize = 17.sp); inner() } },
            )
        }
        if (results != null) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomSpace + 16.dp)) {
                if (results.channels.isNotEmpty()) {
                    item { SectionTitle("Channels") }
                    items(results.channels.take(30), key = { "c" + it.id }) { ch -> ChannelLine(store, ch, store.nowAndNext(ch).first, zap, layout) { picked = Picked(ch, store.nowAndNext(ch).first) } }
                }
                section("On now", results.onNow, store, zap, layout) { picked = it }
                section("Coming up", results.upcoming, store, zap, layout) { picked = it }
                section("Catch-up", results.catchup, store, zap, layout) { picked = it }
                if (results.channels.isEmpty() && results.onNow.isEmpty() && results.upcoming.isEmpty() && results.catchup.isEmpty()) item { EmptyNote("Nothing found", "Nothing on your channels matches “$q”.") }
            }
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = layout.gutter, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiveChip("All", category == null, { category = null })
                categories.forEach { (c, n) -> LiveChip(c.label, category == c.name, { category = c.name }) }
            }
            LazyVerticalGrid(
                GridCells.Adaptive(if (layout.compact) 104.dp else 132.dp), Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = layout.gutter, end = layout.gutter, bottom = bottomSpace + 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(shown, key = { it.id }) { ch ->
                    Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable { store.playLive(context, ch, zapChannelIds = zap) }) {
                        ChannelTile(tileLogoFor(ch), ch.name, if (layout.compact) 104.dp else 132.dp, if (layout.compact) 62.dp else 80.dp)
                        Text(store.nowAndNext(ch).first?.title ?: ch.name, color = Dx.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
    picked?.let { ProgrammeSheet(store, it, zap, onDismiss = { picked = null }) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String, list: List<Pair<CanonicalChannel, EpgProgramme>>, store: LiveTvStore, zap: List<String>, layout: AppLayout, onPick: (Picked) -> Unit,
) {
    if (list.isEmpty()) return
    item(key = "h$title") { SectionTitle(title) }
    items(list.take(25), key = { "$title${it.first.id}${it.second.id}" }) { (ch, p) -> ChannelLine(store, ch, p, zap, layout) { onPick(Picked(ch, p)) } }
}

@Composable
private fun ChannelLine(store: LiveTvStore, ch: CanonicalChannel, p: EpgProgramme?, zap: List<String>, layout: AppLayout, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = layout.gutter, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ChannelTile(tileLogoFor(ch), ch.name, 76.dp, 48.dp)
        Column(Modifier.weight(1f)) {
            Text(p?.title ?: ch.name, color = Dx.Text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (p != null) "${ch.name}  ·  ${LiveTime.hm(p.startMs)}" else "", color = Dx.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
    }
}
