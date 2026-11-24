package com.notdxbi.dxbi.tv.lumenphone.live

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notdxbi.dxbi.tv.live.LiveOverlayActions
import com.notdxbi.dxbi.tv.live.LiveOverlayPanel
import com.notdxbi.dxbi.tv.live.LiveOverlayState
import com.notdxbi.dxbi.tv.live.LiveTrackOption
import com.notdxbi.dxbi.tv.live.LumenHandoff
import com.notdxbi.dxbi.tv.lumenphone.Dx
import lumos.iptv.CanonicalChannel
import lumos.iptv.EpgProgramme
import lumos.iptv.LivePlaybackState
import lumos.iptv.StreamSource

/**
 * Live TV's player on a phone or tablet: tap for the controls, swipe up or down to change channel, the small bar
 * for what's on, the channel list and Options as sheets. (The TV's remote-driven overlay is in Lumos TV; this reads
 * the same state and calls the same actions.)
 */
@Composable
fun PhoneLiveOverlay(state: LiveOverlayState, actions: LiveOverlayActions, onPip: () -> Unit) {
    val lumenOffer by LumenHandoff.offer.collectAsState()
    val unavailable = state.playbackState as? LivePlaybackState.Unavailable
    val shown = state.osdVisible || unavailable != null

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(shown, state.activePanel) {
                detectTapGestures(onTap = { if (state.activePanel != LiveOverlayPanel.NONE) actions.onClosePanel() else if (shown) actions.onHideOsd() else actions.onShowOsd() })
            }
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (total < -140f) actions.onZapNext() else if (total > 140f) actions.onZapPrev(); total = 0f },
                ) { _, dy -> total += dy }
            },
    ) {
        // what's happening: tuning, buffering
        val busy = state.isBuffering || state.playbackState is LivePlaybackState.Resolving || state.playbackState is LivePlaybackState.Tuning ||
            state.playbackState is LivePlaybackState.FailingOver
        if (busy && unavailable == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                LiveSpinner(Modifier.size(38.dp))
                val label = when (val p = state.playbackState) {
                    is LivePlaybackState.Resolving -> "Loading ${state.channel.name}"
                    is LivePlaybackState.Tuning -> if (p.total > 1) "Trying feed ${p.attempt} of ${p.total}" else "Loading ${state.channel.name}"
                    is LivePlaybackState.FailingOver -> "That feed stopped, trying another"
                    else -> null
                }
                if (label != null) Text(label, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
            }
        }

        AnimatedVisibility(shown && unavailable == null, enter = fadeIn(), exit = fadeOut()) {
            Controls(state, actions, onPip)
        }

        if (unavailable != null) Unavailable(unavailable, state, actions)

        // a number banner when zapping
        AnimatedVisibility(state.zapBannerVisible && !shown, modifier = Modifier.align(Alignment.TopStart), enter = fadeIn(), exit = fadeOut()) {
            ZapBanner(state)
        }

        // Watch in Lumen
        lumenOffer?.let { o ->
            Column(
                Modifier.align(Alignment.BottomStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp).widthIn(max = 380.dp)
                    .clip(RoundedCornerShape(18.dp)).background(Color(0xE61C1C1E)).padding(16.dp),
            ) {
                Text(if (o.viaLumen) "Also in Lumen" else "From the start", color = Dx.Muted, style = MaterialTheme.typography.labelMedium)
                Text("${o.name} · ${o.label}", color = Dx.Text, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (o.viaLumen) {
                        LiveButton("Watch in Lumen", onClick = { actions.onWatchInLumen(false) })
                        LiveButton("From start", primary = false, onClick = { actions.onWatchInLumen(true) })
                    } else {
                        LiveButton("Watch from start", onClick = { actions.onWatchInLumen(true) })
                    }
                    LiveButton("Not now", primary = false, onClick = actions.onDismissLumenOffer)
                }
            }
        }

        // a second viewer on a one-connection line
        state.viewerConflict?.let { c ->
            Box(Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}, contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 420.dp).padding(24.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF1C1C1E)).padding(22.dp)) {
                    Text("Someone may be watching", color = Dx.Text, style = MaterialTheme.typography.titleLarge)
                    Text(listOfNotNull(c.reason, c.otherDeviceName?.let { "On $it" }, c.otherChannelName?.let { "watching $it" }).joinToString(" · "), color = Dx.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LiveButton("Watch anyway", onClick = actions.onResolveConflictWatchAnyway)
                        state.alternateSourceStream?.let { alt -> LiveButton("Use ${alt.sourceName}", primary = false, onClick = { actions.onResolveConflictSwitchSource(alt) }) }
                        LiveButton("Cancel", primary = false, onClick = actions.onResolveConflictCancel)
                    }
                }
            }
        }

        // sheets
        if (state.activePanel != LiveOverlayPanel.NONE) {
            Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { actions.onClosePanel() })
            Sheet(Modifier.align(Alignment.BottomCenter)) {
                when (state.activePanel) {
                    LiveOverlayPanel.MINI_GUIDE -> ChannelsSheet(state, actions)
                    LiveOverlayPanel.FEEDS -> FeedsSheet(state, actions)
                    LiveOverlayPanel.AUDIO -> TracksSheet("Audio", state.audioTracks, { g, t -> actions.onSelectAudio(g, t) }, null)
                    LiveOverlayPanel.SUBTITLES -> TracksSheet("Subtitles", state.subtitleTracks, { g, t -> actions.onSelectSubtitle(g, t) }, actions.onDisableSubtitles)
                    else -> OptionsSheet(state, actions)
                }
            }
        }
    }
}

@Composable
private fun Sheet(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().widthIn(max = 560.dp).fillMaxHeight(0.62f).clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .background(Color(0xF21C1C1E)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) { content() }
}

@Composable
private fun SheetTitle(text: String) {
    Text(text, color = Dx.Text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
}

@Composable
private fun SheetRow(label: String, detail: String? = null, selected: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Dx.Text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!detail.isNullOrBlank()) Text(detail, color = Dx.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Icon(Icons.Filled.Check, null, tint = Dx.Text, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun OptionsSheet(state: LiveOverlayState, actions: LiveOverlayActions) {
    SheetTitle("Options")
    LazyColumn {
        item { SheetRow("Feeds", "${state.streams.size} available", onClick = { actions.onOpenPanel(LiveOverlayPanel.FEEDS) }) }
        if (state.audioTracks.size > 1) item { SheetRow("Audio", state.audioTracks.firstOrNull { it.selected }?.label, onClick = { actions.onOpenPanel(LiveOverlayPanel.AUDIO) }) }
        if (state.subtitleTracks.isNotEmpty()) item { SheetRow("Subtitles", state.subtitleTracks.firstOrNull { it.selected }?.label ?: "Off", onClick = { actions.onOpenPanel(LiveOverlayPanel.SUBTITLES) }) }
        item { SheetRow("Sound", state.soundModeLabel, onClick = actions.onCycleSoundMode) }
        item { SheetRow("Picture", state.zoomMode.label, onClick = actions.onCycleZoom) }
        if (state.videoInfo.height > 0) item {
            val v = state.videoInfo
            SheetRow("Playing", buildString { append("${v.width}×${v.height}"); if (v.fps > 0) append(" · ${v.fps.toInt()} fps"); if (v.hdrLabel.isNotBlank()) append(" · ${v.hdrLabel}"); if (v.videoCodec.isNotBlank()) append(" · ${v.videoCodec}") }, onClick = {})
        }
    }
}

@Composable
private fun FeedsSheet(state: LiveOverlayState, actions: LiveOverlayActions) {
    SheetTitle("Feeds")
    LazyColumn {
        items(state.streams, key = { it.id }) { s ->
            val pinned = s.id == state.pinnedStreamId
            SheetRow(
                s.sourceName + if (s.quality.badge.isNotBlank()) " · ${s.quality.badge}" else "",
                if (pinned) "Always used for this channel" else s.rawName,
                selected = s.id == state.activeStreamId,
                onClick = { actions.onSelectStream(s) },
            )
        }
        item {
            val active = state.streams.firstOrNull { it.id == state.activeStreamId }
            if (active != null) SheetRow(if (state.pinnedStreamId == active.id) "Stop always using this feed" else "Always use this feed", onClick = { actions.onPinStream(if (state.pinnedStreamId == active.id) null else active) })
        }
    }
}

@Composable
private fun TracksSheet(title: String, tracks: List<LiveTrackOption>, onPick: (Int, Int) -> Unit, onOff: (() -> Unit)?) {
    SheetTitle(title)
    LazyColumn {
        if (onOff != null) item { SheetRow("Off", selected = tracks.none { it.selected }, onClick = onOff) }
        items(tracks) { t -> SheetRow(t.label, t.detail, selected = t.selected, onClick = { if (t.supported) onPick(t.groupIndex, t.trackIndex) }) }
    }
}

@Composable
private fun ChannelsSheet(state: LiveOverlayState, actions: LiveOverlayActions) {
    SheetTitle("Channels")
    LazyColumn {
        items(state.miniGuideChannels, key = { it.first.id }) { (ch, now) ->
            Row(
                Modifier.fillMaxWidth().clickable { actions.onZapChannel(ch) }.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ChannelTile(ch.logoUrl, ch.name, 64.dp, 40.dp)
                Column(Modifier.weight(1f)) {
                    Text(ch.name, color = if (ch.id == state.channel.id) Dx.Text else Dx.Muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, fontWeight = if (ch.id == state.channel.id) FontWeight.Bold else FontWeight.Normal)
                    Text(now?.title ?: "No guide", color = Dx.Faint, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ZapBanner(state: LiveOverlayState) {
    Row(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xCC000000)).padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ChannelTile(state.channel.logoUrl, state.channel.name, 56.dp, 36.dp)
        Column {
            Text(state.channel.name, color = Dx.Text, style = MaterialTheme.typography.titleSmall)
            state.nowProgramme?.let { Text(it.title, color = Dx.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
        }
    }
}

@Composable
private fun Unavailable(u: LivePlaybackState.Unavailable, state: LiveOverlayState, actions: LiveOverlayActions) {
    Box(Modifier.fillMaxSize().background(Color(0xE6000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(u.channel.name, color = Dx.Muted, style = MaterialTheme.typography.bodyMedium)
            Text("Can't play this channel", color = Dx.Text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 4.dp))
            Text(u.reason, color = Dx.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LiveButton("Try again", onClick = actions.onRetry)
                LiveButton("Channels", primary = false, onClick = { actions.onOpenPanel(LiveOverlayPanel.MINI_GUIDE) })
                LiveButton("Back", primary = false, onClick = actions.onBack)
            }
        }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, desc: String, size: Int = 44, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = Color.White, modifier = Modifier.size((size * 0.52f).dp)) }
}

@Composable
private fun Controls(state: LiveOverlayState, actions: LiveOverlayActions, onPip: () -> Unit) {
    val ch = state.channel
    val prog = if (state.isCatchup) state.catchupProgramme else state.nowProgramme
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(150.dp).background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(220.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000)))))

        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp, vertical = 12.dp)) {
            // top: back, what's on, options
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RoundIcon(Icons.Filled.ArrowBack, "Back", onClick = actions.onBack)
                ChannelTile(ch.logoUrl, ch.name, 64.dp, 40.dp)
                Column(Modifier.weight(1f)) {
                    Text(prog?.title ?: ch.name, color = Dx.Text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(ch.name.takeIf { prog != null }, prog?.let { LiveTime.range(it.startMs, it.endMs) }).joinToString(" · "),
                        color = Dx.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                RoundIcon(Icons.Filled.PictureInPictureAlt, "Small window", onClick = onPip)
                RoundIcon(Icons.Filled.MoreHoriz, "Options", onClick = { actions.onOpenPanel(LiveOverlayPanel.MORE) })
            }

            Spacer(Modifier.weight(1f))

            // middle controls
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(Icons.Filled.SkipPrevious, "Previous channel", 52, actions.onZapPrev)
                Spacer(Modifier.width(24.dp))
                RoundIcon(if (state.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (state.isPaused) "Play" else "Pause", 68, actions.onTogglePause)
                Spacer(Modifier.width(24.dp))
                RoundIcon(Icons.Filled.SkipNext, "Next channel", 52, actions.onZapNext)
            }

            Spacer(Modifier.weight(1f))

            // bottom: where we are in what's on, then the buttons
            if (prog != null && !state.isCatchup) {
                val now = System.currentTimeMillis()
                val len = (prog.endMs - prog.startMs).coerceAtLeast(1L)
                ProgressLine(((now - prog.startMs).toFloat() / len))
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("LIVE", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFFE5484D)).padding(horizontal = 6.dp, vertical = 2.dp))
                    Text("  ${LiveTime.hm(prog.startMs)}", color = Dx.Muted, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    Text(LiveTime.hm(prog.endMs), color = Dx.Muted, style = MaterialTheme.typography.bodySmall)
                }
                state.nextProgramme?.let { Text("Next · ${it.title}", color = Dx.Faint, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
            } else if (state.isCatchup && state.isSeekable && state.durationMs > 0) {
                Seek(state, actions)
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                LiveButton("Channels", primary = false, onClick = { actions.onOpenPanel(LiveOverlayPanel.MINI_GUIDE) })
                if (state.isCatchup) LiveButton("Go live", onClick = actions.onGoLive)
                else if (state.nowProgramme != null && ch.hasCatchup) LiveButton("From the start", primary = false, onClick = actions.onWatchFromStart)
            }
        }
    }
}

@Composable
private fun Seek(state: LiveOverlayState, actions: LiveOverlayActions) {
    var dragging by remember { mutableStateOf(false) }
    var value by remember { mutableFloatStateOf(0f) }
    val frac = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
    Column {
        Slider(
            value = if (dragging) value else frac,
            onValueChange = { dragging = true; value = it },
            onValueChangeFinished = { actions.onSeekBy((value * state.durationMs).toLong() - state.positionMs); dragging = false },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.25f)),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(LiveTime.clock(state.positionMs), color = Dx.Muted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            Text(LiveTime.clock(state.durationMs), color = Dx.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
