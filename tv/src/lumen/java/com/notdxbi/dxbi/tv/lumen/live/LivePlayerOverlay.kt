package com.notdxbi.dxbi.tv.lumen.live

import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.notdxbi.dxbi.tv.live.LiveOverlayActions
import com.notdxbi.dxbi.tv.live.LiveOverlayPanel
import com.notdxbi.dxbi.tv.live.LiveOverlayState
import com.notdxbi.dxbi.tv.live.LiveScrubState
import com.notdxbi.dxbi.tv.live.LiveTrackOption
import com.notdxbi.dxbi.tv.live.LiveVideoInfo
import com.notdxbi.dxbi.tv.lumen.Dx
import com.notdxbi.dxbi.tv.lumen.ProgressLine
import com.notdxbi.dxbi.tv.phone.Dates
import kotlinx.coroutines.delay
import lumos.iptv.ActiveViewerConflict
import lumos.iptv.CanonicalChannel
import lumos.iptv.EpgProgramme
import lumos.iptv.LivePlaybackState
import lumos.iptv.StreamSource
import kotlin.math.roundToInt

/**
 * Live TV player OSD matching Lumen's VOD player geometry (`activity_player.xml` / `PlayerControls.kt` / `Scrubber.kt`).
 *
 * Includes the 130dp top gradient scrim (circular Back button + top-right clock),
 * 240dp bottom gradient scrim (focusable SeekLine bar + floating scrub bubble + 3-zone transport/title/panel buttons),
 * and the bottom-right floating panel for Mini-Guide, Feeds, Stats, Subtitles, and Audio.
 */
@Composable
fun LivePlayerOverlay(
    state: LiveOverlayState,
    actions: LiveOverlayActions,
) {
    val seekFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }

    LaunchedEffect(state.scrub.requestSeekFocusTick) {
        if (state.scrub.requestSeekFocusTick > 0L) {
            delay(20)
            runCatching { seekFocus.requestFocus() }
        }
    }

    // (a setting's panel closing sends the remote back to the button that opened it, not to play)
    val panelButtons = remember { LiveOverlayPanel.entries.associateWith { FocusRequester() } }
    var lastPanel by remember { mutableStateOf(LiveOverlayPanel.NONE) }
    LaunchedEffect(state.osdVisible, state.activePanel) {
        val from = lastPanel
        lastPanel = state.activePanel
        if (state.osdVisible && state.activePanel == LiveOverlayPanel.NONE && !state.scrub.active) {
            delay(40)
            if (from != LiveOverlayPanel.NONE && runCatching { panelButtons.getValue(from).requestFocus() }.isSuccess) return@LaunchedEffect
            runCatching { playFocus.requestFocus() }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Concurrent viewer warning dialog (blocks playback start until resolved)
        val conflict = state.viewerConflict
        if (conflict != null) {
            ActiveViewerConflictDialog(
                conflict = conflict,
                alternateStream = state.alternateSourceStream,
                onWatchAnyway = actions.onResolveConflictWatchAnyway,
                onSwitchSource = {
                    state.alternateSourceStream?.let(actions.onResolveConflictSwitchSource)
                },
                onCancel = actions.onResolveConflictCancel,
            )
            return@Box
        }

        // Fatal error / unavailable screen
        val unavailable = state.playbackState as? LivePlaybackState.Unavailable
        if (unavailable != null) {
            UnavailableDialog(
                channel = unavailable.channel,
                reason = unavailable.reason,
                onRetry = actions.onRetry,
                onBack = actions.onBack,
            )
            return@Box
        }

        // Minimal frosted glass zap banner during Up/Down channel zapping (zero buttons)
        AnimatedVisibility(
            visible = state.zapBannerVisible && !state.osdVisible,
            enter = fadeIn(tween(LIVE_MS, easing = Dx.Ease)),
            exit = fadeOut(tween(LIVE_MS, easing = Dx.Ease)),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 48.dp, bottom = 36.dp),
        ) {
            ZapBanner(state = state)
        }

        // "Watch in Lumen": what's on is in Lumen, in a much better picture
        val lumenOffer by com.notdxbi.dxbi.tv.live.LumenHandoff.offer.collectAsState()
        AnimatedVisibility(
            visible = lumenOffer != null && !state.osdVisible,
            enter = fadeIn(tween(LIVE_MS, easing = Dx.Ease)),
            exit = fadeOut(tween(LIVE_MS, easing = Dx.Ease)),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 48.dp, bottom = 40.dp),
        ) {
            lumenOffer?.let { offer -> LumenOfferCard(offer, onWatch = actions.onWatchInLumen) }
        }

        // Top 130dp gradient scrim (Back button + Clock)
        AnimatedVisibility(
            visible = state.osdVisible,
            enter = fadeIn(tween(LIVE_MS, easing = Dx.Ease)),
            exit = fadeOut(tween(LIVE_MS, easing = Dx.Ease)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopOsdScrim(state = state)
        }

        // The channel number being typed on the remote
        val typedNumber by com.notdxbi.dxbi.tv.live.LivePlayerExtras.numberEntry.collectAsState()
        val numberMessage by com.notdxbi.dxbi.tv.live.LivePlayerExtras.numberMessage.collectAsState()
        if (typedNumber.isNotEmpty() || numberMessage.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 70.dp, end = 56.dp)
                    .liveGlass(RoundedCornerShape(22.dp))
                    .padding(horizontal = 22.dp, vertical = 12.dp),
            ) {
                Text(
                    text = if (typedNumber.isNotEmpty()) typedNumber else numberMessage,
                    fontSize = if (typedNumber.isNotEmpty()) 38.sp else 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                )
            }
        }

        // Tuning / failover / buffering status banner centered cleanly at top
        TuningStatusBanner(state = state.playbackState)

        // Bottom 240dp gradient scrim + SeekLine + 3-zone controls
        AnimatedVisibility(
            visible = state.osdVisible,
            enter = fadeIn(tween(LIVE_MS, easing = Dx.Ease)),
            exit = fadeOut(tween(LIVE_MS, easing = Dx.Ease)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            BottomOsdScrim(
                state = state,
                actions = actions,
                seekFocus = seekFocus,
                playFocus = playFocus,
                panelButtons = panelButtons,
            )
        }

        // Bottom-right floating panel matching activity_player.xml (@id/panel)
        AnimatedVisibility(
            visible = state.activePanel != LiveOverlayPanel.NONE,
            enter = fadeIn(tween(LIVE_MS, easing = Dx.Ease)),
            exit = fadeOut(tween(LIVE_MS, easing = Dx.Ease)),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 56.dp, bottom = 104.dp, top = 84.dp),
        ) {
            when (state.activePanel) {
                LiveOverlayPanel.MINI_GUIDE -> MiniGuidePanel(
                    currentChannelId = state.channel.id,
                    entries = state.miniGuideChannels,
                    onZap = actions.onZapChannel,
                    onClose = actions.onClosePanel,
                )
                LiveOverlayPanel.FEEDS -> FeedsPanel(
                    streams = state.streams,
                    activeStreamId = state.activeStreamId,
                    pinnedStreamId = state.pinnedStreamId,
                    onSelect = actions.onSelectStream,
                    onPin = actions.onPinStream,
                    onClose = actions.onClosePanel,
                )
                LiveOverlayPanel.MORE -> MorePanel(
                    onOpenPanel = actions.onOpenPanel,
                    audioLabel = state.audioTracks.firstOrNull { it.selected }?.label ?: "Automatic",
                    subtitleLabel = state.subtitleTracks.firstOrNull { it.selected }?.label ?: "Off",
                    channel = state.channel,
                    streams = state.streams,
                    activeStreamId = state.activeStreamId,
                    videoInfo = state.videoInfo,
                    bufferedMs = state.bufferedMs,
                    zoomLabel = state.zoomMode.label,
                    soundModeLabel = state.soundModeLabel,
                    onCycleZoom = actions.onCycleZoom,
                    onCycleSoundMode = actions.onCycleSoundMode,
                    onClose = actions.onClosePanel,
                )
                LiveOverlayPanel.SUBTITLES -> SubtitlesPanel(
                    subtitleTracks = state.subtitleTracks,
                    onSelectSubtitle = actions.onSelectSubtitle,
                    onDisableSubtitles = actions.onDisableSubtitles,
                    onClose = actions.onClosePanel,
                )
                LiveOverlayPanel.AUDIO -> AudioPanel(
                    audioTracks = state.audioTracks,
                    onSelectAudio = actions.onSelectAudio,
                    onClose = actions.onClosePanel,
                )
                LiveOverlayPanel.NONE -> Unit
            }
        }
    }
}

@Composable
private fun TopOsdScrim(state: LiveOverlayState) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(170.dp)
            .background(
                Brush.verticalGradient(
                    0f to Color(0x99000000),
                    1f to Color.Transparent,
                ),
            ),
    ) {
        // what's playing, in the box that shows when changing channel (Back on the remote leaves; no arrow needed)
        Box(Modifier.align(Alignment.TopStart).padding(start = 40.dp, top = 30.dp)) {
            ZapBanner(state = state)
        }

        // the same clock, in the same place, as on Lumen's screens
        com.notdxbi.dxbi.tv.lumen.LumenClock(Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun BottomOsdScrim(
    state: LiveOverlayState,
    actions: LiveOverlayActions,
    seekFocus: FocusRequester,
    playFocus: FocusRequester,
    panelButtons: Map<LiveOverlayPanel, FocusRequester>,
) {
    val stripChannels by com.notdxbi.dxbi.tv.live.LivePlayerExtras.stripChannels.collectAsState()
    val stripTick by com.notdxbi.dxbi.tv.live.LivePlayerExtras.focusStripTick.collectAsState()
    val expandedFlag by com.notdxbi.dxbi.tv.live.LivePlayerExtras.expanded.collectAsState()
    // (catch-up and paused always show their controls; a live channel opens them out with a second Down)
    val expanded = expandedFlag || state.isPaused || state.isCatchup || state.scrub.active
    LaunchedEffect(state.osdVisible) {
        if (!state.osdVisible) com.notdxbi.dxbi.tv.live.LivePlayerExtras.expanded.value = false
    }
    LaunchedEffect(expandedFlag) {
        if (expandedFlag) { delay(80); runCatching { playFocus.requestFocus() } }
    }
    val nowMs = System.currentTimeMillis()
    val canScrubLive = !state.isCatchup && state.channel.hasCatchup && state.nowProgramme != null
    val canSeek = state.isCatchup || canScrubLive

    val effectiveCatchupDur = if (state.isCatchup) {
        val progDur = state.catchupProgramme?.let { (it.endMs - it.startMs).coerceAtLeast(1L) } ?: 0L
        if (state.catchupStartOffsetMs == 0L && state.durationMs > 0L) state.durationMs else progDur
    } else 0L

    val effectiveCatchupPos = if (state.isCatchup) {
        (state.catchupStartOffsetMs + state.positionMs).coerceIn(0L, effectiveCatchupDur.coerceAtLeast(1L))
    } else 0L

    val playedFraction = when {
        state.isCatchup && effectiveCatchupDur > 0L ->
            (effectiveCatchupPos.toFloat() / effectiveCatchupDur.toFloat()).coerceIn(0f, 1f)
        state.nowProgramme != null -> state.nowProgramme.progressAt(nowMs)
        else -> 1f
    }
    val bufferedFraction = if (state.isCatchup && effectiveCatchupDur > 0L) {
        ((state.catchupStartOffsetMs + state.bufferedMs).toFloat() / effectiveCatchupDur.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val leftTimeText = when {
        state.scrub.active -> state.scrub.timeLabel
        state.isCatchup -> LiveTimeFormat.formatDurationMs(effectiveCatchupPos)
        state.nowProgramme != null -> "Started " + LiveTimeFormat.formatTime12h(state.nowProgramme.startMs)
        else -> "0:00"
    }
    val rightTimeText = when {
        state.isCatchup && effectiveCatchupDur > 0L ->
            "-${LiveTimeFormat.formatDurationMs((effectiveCatchupDur - effectiveCatchupPos).coerceAtLeast(0L))}"
        state.scrub.active && state.scrub.isLiveTimeshift -> "TIMESHIFT"
        else -> "LIVE"
    }

    val titleText = buildString {
        state.channel.number?.let { append("$it · ") }
        append(state.channel.name)
        state.nowProgramme?.let { append(" · ${it.title}") }
    }
    val techBadge = formatCompactTechBadge(state.videoInfo)
    val subtitleText = if (state.isCatchup) {
        val prog = state.catchupProgramme
        buildString {
            append("CATCH-UP")
            if (prog != null) {
                append(" · ${LiveTimeFormat.formatTime24h(prog.startMs)} - ${LiveTimeFormat.formatTime24h(prog.endMs)}")
            }
            if (techBadge != null) append(" · $techBadge")
        }
    } else {
        buildString {
            append("LIVE")
            if (techBadge != null) append(" · $techBadge")
            state.nextProgramme?.let { append(" · Next: ${it.title}") }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (stripChannels.isEmpty()) 260.dp else if (expanded) 340.dp else 250.dp)
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.5f to Color(0x73000000),
                    1f to Color(0xE6000000),
                ),
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 56.dp, end = 56.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // SeekLine row with floating scrub bubble
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = leftTimeText,
                    style = TextStyle(
                        fontSize = 15.sp,
                        color = Color.White,
                        fontFeatureSettings = "tnum",
                    ),
                    textAlign = TextAlign.Start,
                    maxLines = 1,
                    softWrap = false,
                )

                SeekLineBar(
                    playedFraction = playedFraction,
                    bufferedFraction = bufferedFraction,
                    scrub = state.scrub,
                    accentColor = if (state.isCatchup) Dx.Amber else Color.White,
                    canSeek = canSeek,
                    seekFocus = seekFocus,
                    onFocusChanged = actions.onSeekBarFocusChanged,
                    onScrubStep = actions.onScrubStep,
                    onScrubCommit = actions.onScrubCommit,
                    onStartTimeshift = actions.onStartTimeshiftScrub,
                    onTogglePause = actions.onTogglePause,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                )

                Row(
                    modifier = Modifier.widthIn(min = 72.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (rightTimeText == "LIVE") {
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(LivePalette.LiveRed),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = rightTimeText,
                        style = TextStyle(
                            fontSize = 15.sp,
                            fontWeight = if (rightTimeText == "LIVE") FontWeight.Bold else FontWeight.Normal,
                            color = if (rightTimeText == "TIMESHIFT") Dx.Amber else Color.White,
                            fontFeatureSettings = "tnum",
                        ),
                        textAlign = TextAlign.End,
                        maxLines = 1,
                    )
                }
            }

            // (without a strip the controls sit under the bar; with one they're below it, TiviMate's way)
            if (stripChannels.isEmpty()) ControlsRow(state, actions, playFocus, panelButtons, Modifier.padding(top = 6.dp))

            if (stripChannels.isNotEmpty() && !state.isCatchup) {
                ChannelStrip(
                    entries = stripChannels,
                    currentChannelId = state.channel.id,
                    focusTick = stripTick,
                    onZap = actions.onZapChannel,
                    onOpenGuide = { actions.onOpenPanel(LiveOverlayPanel.MINI_GUIDE) },
                    guideFocus = panelButtons.getValue(LiveOverlayPanel.MINI_GUIDE),
                )
            }
            // the second Down: play/pause and the settings, below the channels
            if (stripChannels.isNotEmpty() && expanded) ControlsRow(state, actions, playFocus, panelButtons, Modifier.padding(top = 10.dp))
        }
    }
}

/** Play/pause, from the start or back to live, and the settings (audio, subtitles, picture, sound, feeds, more) */
@Composable
private fun ControlsRow(state: LiveOverlayState, actions: LiveOverlayActions, playFocus: FocusRequester, panelButtons: Map<LiveOverlayPanel, FocusRequester>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SpringButton(onClick = actions.onTogglePause, round = true, modifier = Modifier.focusRequester(playFocus)) { ink ->
            Icon(if (state.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause, contentDescription = if (state.isPaused) "Play" else "Pause", tint = ink, modifier = Modifier.size(24.dp))
        }
        if (state.isCatchup) {
            SettingChip(Icons.Filled.Replay10, "Back to live", onClick = actions.onGoLive)
        } else if (state.channel.hasCatchup && state.nowProgramme != null) {
            SettingChip(Icons.Filled.Replay10, "Watch from start", onClick = actions.onWatchFromStart)
        }
        Spacer(Modifier.weight(1f))
        SettingChip(Icons.Filled.Audiotrack, "Audio", Modifier.focusRequester(panelButtons.getValue(LiveOverlayPanel.AUDIO))) { actions.onOpenPanel(LiveOverlayPanel.AUDIO) }
        SettingChip(Icons.Filled.ClosedCaption, "Subtitles", Modifier.focusRequester(panelButtons.getValue(LiveOverlayPanel.SUBTITLES))) { actions.onOpenPanel(LiveOverlayPanel.SUBTITLES) }
        SettingChip(Icons.Filled.AspectRatio, state.zoomMode.label, onClick = actions.onCycleZoom)
        SettingChip(Icons.Filled.GraphicEq, state.soundModeLabel, onClick = actions.onCycleSoundMode)
        SettingChip(Icons.Filled.SwapHoriz, "Feeds", Modifier.focusRequester(panelButtons.getValue(LiveOverlayPanel.FEEDS))) { actions.onOpenPanel(LiveOverlayPanel.FEEDS) }
        // (the rest of the options: round, just the dots, so the row never runs out of room)
        SpringButton(onClick = { actions.onOpenPanel(LiveOverlayPanel.MORE) }, round = true, modifier = Modifier.focusRequester(panelButtons.getValue(LiveOverlayPanel.MORE))) { ink ->
            Icon(Icons.Filled.MoreHoriz, contentDescription = "Options", tint = ink, modifier = Modifier.size(22.dp))
        }
    }
}

/** One setting: its icon and what it's set to, on glass */
@Composable
private fun SettingChip(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    SpringButton(onClick = onClick, modifier = modifier) { ink ->
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1, softWrap = false)
        }
    }
}

/**
 * A player button on glass that springs when the remote moves onto it, as Lumen's cards do: it swells a little and turns
 * white with a soft settle, and eases back as the remote moves on (the TV library's buttons snapped in a few frames).
 * OK presses it when let go.
 */
@Composable
private fun SpringButton(onClick: () -> Unit, modifier: Modifier = Modifier, round: Boolean = false, content: @Composable (Color) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val k by androidx.compose.animation.core.animateFloatAsState(
        if (focused) 1f else 0f,
        androidx.compose.animation.core.spring(dampingRatio = 0.72f, stiffness = 190f),
        label = "button",
    )
    val shape = if (round) CircleShape else RoundedCornerShape(50)
    val fill = androidx.compose.ui.graphics.lerp(Color.White.copy(alpha = 0.12f), Color.White, k.coerceIn(0f, 1f))
    val ink = androidx.compose.ui.graphics.lerp(Color.White, Color.Black, k.coerceIn(0f, 1f))
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier
            .height(42.dp)
            .then(if (round) Modifier.width(42.dp) else Modifier)
            .graphicsLayer { val sc = 1f + 0.08f * k; scaleX = sc; scaleY = sc }
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                val select = e.key == androidx.compose.ui.input.key.Key.DirectionCenter || e.key == androidx.compose.ui.input.key.Key.Enter || e.key == androidx.compose.ui.input.key.Key.NumPadEnter
                if (!select) return@onKeyEvent false
                when (e.type) {
                    androidx.compose.ui.input.key.KeyEventType.KeyDown -> { if (e.nativeKeyEvent.repeatCount == 0) pressed = true; true }
                    androidx.compose.ui.input.key.KeyEventType.KeyUp -> { if (pressed) { pressed = false; onClick() }; true }
                    else -> false
                }
            }
            .focusable()
            .background(fill, shape)
            .border(1.dp, GlassRim, shape),
        contentAlignment = Alignment.Center,
    ) { content(ink) }
}

/** TiviMate-style: the channels around this one along the bottom, each with what's on now; OK goes to it */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ChannelStrip(
    entries: List<Pair<CanonicalChannel, EpgProgramme?>>,
    currentChannelId: String,
    focusTick: Long,
    onZap: (CanonicalChannel) -> Unit,
    onOpenGuide: () -> Unit,
    guideFocus: FocusRequester,
) {
    val currentIdx = entries.indexOfFirst { it.first.id == currentChannelId }.coerceAtLeast(0)
    val currentTile = remember { FocusRequester() }
    val nowMs = System.currentTimeMillis()

    // (the channel playing sits in the middle, with those either side fading out towards the edges. The list starts
    // there, worked out from the sizes, so nothing slides into place as the strip comes up)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val viewportDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp - 112
    fun centred(idx: Int): Pair<Int, Int> {
        var i = idx + 1
        var left = viewportDp / 2f - STRIP_TILE_W.value / 2f
        while (i > 0 && left > 40f) { i--; left -= if (i == 0) STRIP_GUIDE_W.value + STRIP_GAP.value else STRIP_TILE_W.value + STRIP_GAP.value }
        return i to with(density) { (40f - left).coerceAtLeast(0f).dp.roundToPx() }
    }
    val start = remember { centred(currentIdx) }
    val listState = rememberLazyListState(start.first, start.second)
    var firstPlace by remember { mutableStateOf(true) }
    LaunchedEffect(currentChannelId) {
        if (firstPlace) { firstPlace = false; return@LaunchedEffect }
        val (i, o) = centred(currentIdx)
        runCatching { listState.scrollToItem(i, o) }
    }
    LaunchedEffect(focusTick) {
        if (focusTick > 0L) {
            delay(120)
            runCatching { currentTile.requestFocus() }
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { StripPreview.value = null } }

    // (the tiles at the sides fade out rather than being cut off by the screen's edge; the chosen one stays central)
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides CentreSpec) {
    androidx.compose.foundation.lazy.LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { if (!it.hasFocus) StripPreview.value = null }
            .padding(top = 4.dp)
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val edge = 90.dp.toPx() / size.width
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.Transparent, edge to Color.Black,
                        1f - edge to Color.Black, 1f to Color.Transparent,
                    ),
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                )
            },
        // (room between the tiles for the chosen one to lift into without touching its neighbours, and above and below it)
        contentPadding = PaddingValues(horizontal = 40.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(STRIP_GAP),
    ) {
        item(key = "guide") {
            Surface(
                onClick = onOpenGuide,
                modifier = Modifier.width(STRIP_GUIDE_W).height(STRIP_HEIGHT).focusRequester(guideFocus).onFocusChanged { if (it.isFocused) StripPreview.value = null }
                    .liveFrost(RoundedCornerShape(12.dp)),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                    focusedContainerColor = Color.White,
                    focusedContentColor = Color.Black,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
            ) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = null, modifier = Modifier.size(20.dp), tint = LocalContentColor.current)
                    Spacer(Modifier.height(4.dp))
                    Text("Guide", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = LocalContentColor.current)
                }
            }
        }
        items(entries, key = { it.first.id }) { (channel, prog) ->
            val playing = channel.id == currentChannelId
            val logo = remember(channel.id) { tileLogoFor(channel) }
            // Lumen's card (as the films on Home): it lifts when chosen, with the glass rim
            com.notdxbi.dxbi.tv.lumen.FocusCard(
                onClick = { onZap(channel) },
                modifier = Modifier.width(STRIP_TILE_W).height(STRIP_HEIGHT).then(if (playing) Modifier.focusRequester(currentTile) else Modifier)
                    .onFocusChanged { if (it.isFocused || it.hasFocus) StripPreview.value = channel to prog },
                corner = 12.dp,
            ) {
                // frosted glass over the picture, as the channel box at the top (the one playing a touch lighter)
                Box(Modifier.fillMaxSize().liveFrost(RoundedCornerShape(12.dp), rim = false).then(if (playing) Modifier.background(Color.White.copy(alpha = 0.12f)) else Modifier)) {
                val ink = Color.White
                Row(Modifier.fillMaxSize().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // the channel's logo tile says which it is (its name in words was one more line)
                    ChannelTile(logoUrl = logo, name = channel.name, width = 80.dp, height = 46.dp, corner = 7.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            text = prog?.title ?: channel.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ink,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(ink.copy(alpha = 0.2f))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth((prog?.progressAt(nowMs) ?: 0f).coerceIn(0f, 1f)).background(ink))
                        }
                    }
                }
                }
            }
        }
    }
    }
}

/** Keeps the chosen tile of a row in the middle */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private object CentreSpec : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> get() = com.notdxbi.dxbi.tv.lumen.RowGlide
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        offset - (containerSize - size) / 2f
}

/** The channel the remote is on in the player's strip (the top box shows it), or null on the one playing */
private val StripPreview = androidx.compose.runtime.mutableStateOf<Pair<CanonicalChannel, EpgProgramme?>?>(null)

/** How tall the quick channel changer's tiles are, how wide, and the gap between them */
private val PANEL_BORDER = Color(0x1FFFFFFF)
private val STRIP_HEIGHT = 62.dp
private val STRIP_TILE_W = 300.dp
private val STRIP_GUIDE_W = 96.dp
private val STRIP_GAP = 26.dp

@Composable
private fun SeekLineBar(
    playedFraction: Float,
    bufferedFraction: Float,
    scrub: LiveScrubState,
    accentColor: Color,
    canSeek: Boolean,
    seekFocus: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onScrubStep: (Int, Int) -> Unit,
    onScrubCommit: () -> Unit,
    onStartTimeshift: () -> Unit,
    onTogglePause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var focused by remember { mutableStateOf(false) }
    var barWidthPx by remember { mutableIntStateOf(0) }
    var bubbleWidthPx by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier
            .height(32.dp)
            .focusRequester(seekFocus)
            .onFocusChanged {
                focused = it.isFocused
                onFocusChanged(it.isFocused)
            }
            .onSizeChanged { barWidthPx = it.width }
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        onScrubStep(-1, ev.nativeKeyEvent.repeatCount)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        onScrubStep(+1, ev.nativeKeyEvent.repeatCount)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        when {
                            scrub.active -> onScrubCommit()
                            canSeek -> onStartTimeshift()
                            else -> onTogglePause()
                        }
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
        contentAlignment = Alignment.CenterStart,
    ) {
        val barHeight = if (focused || scrub.active) 6.dp else 4.dp
        val displayFraction = if (scrub.active) scrub.fraction else playedFraction

        // Track
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.25f)),
        ) {
            if (bufferedFraction > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(bufferedFraction.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(Color.White.copy(alpha = 0.38f), CircleShape),
                )
            }
            if (playedFraction > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(playedFraction.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(accentColor, CircleShape),
                )
            }
            if (scrub.active) {
                Box(
                    Modifier
                        .fillMaxWidth(scrub.fraction.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(Color.White.copy(alpha = 0.6f), CircleShape),
                )
            }
        }

        // White knob when focused or scrubbing
        if (focused || scrub.active) {
            val knobRadiusPx = with(density) { 9.dp.toPx() }
            val knobOffsetPx = if (barWidthPx > 0) {
                (barWidthPx * displayFraction.coerceIn(0f, 1f) - knobRadiusPx)
                    .coerceIn(0f, (barWidthPx - knobRadiusPx * 2f).coerceAtLeast(0f))
            } else 0f
            Box(
                modifier = Modifier
                    .offset { IntOffset(knobOffsetPx.roundToInt(), 0) }
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }

        // Floating dark rounded Scrubber time bubble above the bar when scrubbing
        if (scrub.active && barWidthPx > 0) {
            val gapPx = with(density) { 10.dp.roundToPx() }
            val bubbleOffsetX = (barWidthPx * scrub.fraction.coerceIn(0f, 1f) - bubbleWidthPx / 2f)
                .coerceIn(0f, (barWidthPx - bubbleWidthPx).coerceAtLeast(0).toFloat())
            val bubbleOffsetY = -with(density) { 46.dp.roundToPx() } - gapPx

            Column(
                modifier = Modifier
                    .offset { IntOffset(bubbleOffsetX.roundToInt(), bubbleOffsetY) }
                    // (its own size, whatever the bar allows: the time used to wrap and be cut off)
                    .wrapContentSize(unbounded = true)
                    .onSizeChanged { bubbleWidthPx = it.width }
                    .liveGlass(RoundedCornerShape(10.dp))
                    .border(1.dp, PANEL_BORDER, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = scrub.timeLabel,
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White, fontFeatureSettings = "tnum"),
                    maxLines = 1,
                    softWrap = false,
                )
                if (scrub.deltaLabel.isNotBlank()) {
                    Text(
                        text = scrub.deltaLabel,
                        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Dx.Muted, fontFeatureSettings = "tnum"),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/**
 * A channel loading: its tile, a spinner and "Loading <channel>" in the middle (no feed names, counts or reasons);
 * buffering during play is just the spinner
 */
@Composable
private fun TuningStatusBanner(
    state: LivePlaybackState,
    modifier: Modifier = Modifier,
) {
    val channel = when (state) {
        is LivePlaybackState.Resolving -> state.channel
        is LivePlaybackState.Tuning -> state.channel
        is LivePlaybackState.FailingOver -> state.channel
        else -> null
    }
    if (channel != null) {
        val logo = remember(channel.id) { tileLogoFor(channel) }
        LiveLoading(
            message = "Loading ${channel.name}",
            logoUrl = logo,
            channelName = channel.name,
            modifier = Modifier.background(Color.Black.copy(alpha = 0.55f)),
        )
        return
    }
    if (state is LivePlaybackState.Buffering && state.elapsedMs >= 1200L) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LiveSpinner(40.dp) }
    }
}

@Composable
private fun ZapBanner(
    state: LiveOverlayState,
    modifier: Modifier = Modifier,
) {
    // (the remote on another channel in the strip below: this box shows that one, larger and easier to read)
    val preview = StripPreview.value?.takeIf { it.first.id != state.channel.id }
    val channel = preview?.first ?: state.channel
    val activeStream = state.streams.firstOrNull { it.id == state.activeStreamId } ?: state.streams.firstOrNull()
    val qualityBadge = if (preview != null) null else formatFormatBadges(state.videoInfo)
        ?: formatCompactQuality(activeStream?.quality)
    val nowMs = System.currentTimeMillis()
    val nowProg = if (preview != null) preview.second else state.nowProgramme
    val nextProg = if (preview != null) null else state.nextProgramme

    Row(
        modifier = modifier
            .widthIn(min = 380.dp, max = 560.dp)
            .liveFrost(RoundedCornerShape(22.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ChannelTile(logoUrl = remember(channel.id) { tileLogoFor(channel) }, name = channel.name, width = 72.dp, height = 40.dp)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                channel.number?.let { num ->
                    Text(
                        text = num.toString(),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Dx.Muted,
                    )
                }
                Text(
                    text = channel.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!qualityBadge.isNullOrBlank()) {
                    Text(qualityBadge, fontSize = 13.sp, color = Dx.Muted, maxLines = 1)
                }
            }

            if (nowProg != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = nowProg.title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.92f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = LiveTimeFormat.formatRange12h(nowProg.startMs, nowProg.endMs),
                        fontSize = 11.sp,
                        color = Dx.Muted,
                        maxLines = 1,
                    )
                }
                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.22f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(nowProg.progressAt(nowMs).coerceIn(0f, 1f)).background(Color.White))
                }
            } else {
                Text(
                    text = "Live Broadcast",
                    fontSize = 13.sp,
                    color = Dx.Muted,
                )
            }

            if (nextProg != null) {
                Text(
                    text = "Next · ${LiveTimeFormat.formatTime12h(nextProg.startMs)} ${nextProg.title}",
                    fontSize = 12.sp,
                    color = Dx.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ActiveViewerConflictDialog(
    conflict: ActiveViewerConflict,
    alternateStream: StreamSource?,
    onWatchAnyway: () -> Unit,
    onSwitchSource: () -> Unit,
    onCancel: () -> Unit,
) {
    val watchFocus = remember { FocusRequester() }
    LaunchedEffect(conflict.sourceId) {
        delay(60)
        runCatching { watchFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.82f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .liveGlass(RoundedCornerShape(22.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Dx.Amber.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.WarningAmber,
                        contentDescription = null,
                        tint = Dx.Amber,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column {
                    Text(
                        text = "Active Viewer Warning",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        text = conflict.sourceName,
                        fontSize = 13.sp,
                        color = Dx.Muted,
                    )
                }
            }

            Text(
                text = conflict.reason,
                fontSize = 15.sp,
                color = Color.White.copy(alpha = 0.9f),
            )

            Text(
                text = "Opening another stream on this provider may interrupt the active viewer if your subscription has reached its connection limit.",
                fontSize = 13.sp,
                color = Dx.Muted,
            )

            Spacer(Modifier.height(4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PanelActionPill(
                    label = "Watch anyway",
                    onClick = onWatchAnyway,
                    modifier = Modifier.focusRequester(watchFocus),
                )
                if (alternateStream != null) {
                    PanelActionPill(
                        label = "Switch to ${alternateStream.sourceName}",
                        onClick = onSwitchSource,
                    )
                }
                PanelActionPill(
                    label = "Cancel",
                    onClick = onCancel,
                )
            }
        }
    }
}

@Composable
private fun UnavailableDialog(
    channel: CanonicalChannel,
    reason: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val retryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { retryFocus.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.84f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(520.dp)
                .liveGlass(RoundedCornerShape(22.dp))
                .border(1.dp, PANEL_BORDER, RoundedCornerShape(22.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "${channel.number?.let { "$it · " } ?: ""}${channel.name}",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                text = reason,
                fontSize = 15.sp,
                color = Dx.Muted,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PanelActionPill(
                    label = "Retry",
                    onClick = onRetry,
                    modifier = Modifier.focusRequester(retryFocus),
                )
                PanelActionPill(
                    label = "Back to guide",
                    onClick = onBack,
                )
            }
        }
    }
}

@Composable
private fun FloatingPanelShell(
    title: String,
    width: Dp,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(width)
            .heightIn(max = 420.dp)
            .liveGlass(RoundedCornerShape(14.dp))
            .border(1.dp, PANEL_BORDER, RoundedCornerShape(14.dp))
            .padding(10.dp)
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0x99FFFFFF),
            )
            PanelActionPill(label = "Close", onClick = onClose)
        }
        content()
    }
}

@Composable
private fun MiniGuidePanel(
    currentChannelId: String,
    entries: List<Pair<CanonicalChannel, EpgProgramme?>>,
    onZap: (CanonicalChannel) -> Unit,
    onClose: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    val currentIdx = entries.indexOfFirst { it.first.id == currentChannelId }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIdx - 2).coerceAtLeast(0))

    LaunchedEffect(Unit) {
        delay(60)
        runCatching { firstFocus.requestFocus() }
    }

    FloatingPanelShell(title = "Mini-Guide", width = 460.dp, onClose = onClose) {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            items(entries, key = { it.first.id }) { (ch, prog) ->
                val active = ch.id == currentChannelId
                PanelRow(
                    title = "${ch.number?.let { "$it · " } ?: ""}${ch.name}",
                    subtitle = prog?.title ?: ch.category.label,
                    badge = if (active) "On Air" else formatCompactQuality(ch.streams.firstOrNull()?.quality),
                    selected = active,
                    modifier = if (active) Modifier.focusRequester(firstFocus) else Modifier,
                    onClick = { onZap(ch) },
                )
            }
        }
    }
}

@Composable
private fun FeedsPanel(
    streams: List<StreamSource>,
    activeStreamId: String?,
    pinnedStreamId: String?,
    onSelect: (StreamSource) -> Unit,
    onPin: (StreamSource?) -> Unit,
    onClose: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { firstFocus.requestFocus() }
    }

    FloatingPanelShell(title = "Streams", width = 520.dp, onClose = onClose) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            items(streams, key = { it.id }) { stream ->
                val isFirst = streams.firstOrNull()?.id == stream.id
                val active = stream.id == activeStreamId
                val pinned = stream.id == pinnedStreamId
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PanelRow(
                        title = stream.sourceName,
                        subtitle = "${stream.quality.badge}${if (stream.quality.isRaw) " · RAW" else ""}${if (stream.quality.isBackup) " · Backup" else ""}",
                        badge = when {
                            active && pinned -> "Active · Pinned"
                            active -> "Playing"
                            pinned -> "Pinned"
                            else -> null
                        },
                        selected = active,
                        modifier = Modifier
                            .weight(1f)
                            .then(if (isFirst) Modifier.focusRequester(firstFocus) else Modifier),
                        onClick = { onSelect(stream) },
                    )
                    Surface(
                        onClick = { onPin(if (pinned) null else stream) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (pinned) Dx.Blue.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f),
                            contentColor = Color.White,
                            focusedContainerColor = Color.White,
                            focusedContentColor = Color.Black,
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
                    ) {
                        Box(Modifier.padding(10.dp), contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.PushPin,
                                contentDescription = if (pinned) "Unpin default" else "Pin as default",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MorePanel(
    onOpenPanel: (LiveOverlayPanel) -> Unit,
    audioLabel: String,
    subtitleLabel: String,
    channel: CanonicalChannel,
    streams: List<StreamSource>,
    activeStreamId: String?,
    videoInfo: LiveVideoInfo,
    bufferedMs: Long,
    zoomLabel: String,
    soundModeLabel: String,
    onCycleZoom: () -> Unit,
    onCycleSoundMode: () -> Unit,
    onClose: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { firstFocus.requestFocus() }
    }

    val activeStream = streams.firstOrNull { it.id == activeStreamId }
    val pictureText = if (videoInfo.width > 0 && videoInfo.height > 0) "${videoInfo.width} × ${videoInfo.height}" else "—"
    val videoText = buildList {
        videoInfo.videoCodec.takeIf { it.isNotBlank() }?.let(::add)
        videoInfo.hdrLabel.takeIf { it.isNotBlank() }?.let(::add)
        if (videoInfo.fps > 0f) add("${"%.0f".format(videoInfo.fps)} fps")
    }.joinToString(" · ").ifEmpty { "—" }
    val audioText = buildList {
        videoInfo.audioCodec.takeIf { it.isNotBlank() }?.let(::add)
        if (videoInfo.audioChannels > 0) add(channelCountName(videoInfo.audioChannels))
    }.joinToString(" · ").ifEmpty { "—" }
    val bitrateText = if (videoInfo.bitrateBps > 0) "%.1f Mbps".format(videoInfo.bitrateBps / 1_000_000f) else "—"
    val bufferedText = "%.1f s".format((bufferedMs.coerceAtLeast(0L)) / 1000f)

    FloatingPanelShell(title = "Options", width = 380.dp, onClose = onClose) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            item { PanelSectionLabel("Sound and subtitles") }
            item {
                PanelRow(
                    title = "Audio",
                    subtitle = audioLabel,
                    selected = false,
                    modifier = Modifier.focusRequester(firstFocus),
                    onClick = { onOpenPanel(LiveOverlayPanel.AUDIO) },
                )
            }
            item {
                PanelRow(title = "Subtitles", subtitle = subtitleLabel, selected = false, onClick = { onOpenPanel(LiveOverlayPanel.SUBTITLES) })
            }
            item { PanelSectionLabel("Picture") }
            item {
                PanelRow(
                    title = "Feed",
                    subtitle = listOfNotNull(activeStream?.sourceName, activeStream?.quality?.badge?.takeIf { it.isNotBlank() }).joinToString(" · ").ifEmpty { "Automatic" },
                    selected = false,
                    onClick = { onOpenPanel(LiveOverlayPanel.FEEDS) },
                )
            }
            item {
                PanelRow(title = "Zoom", subtitle = zoomLabel, selected = false, onClick = onCycleZoom)
            }
            item {
                PanelRow(title = "Sound mode", subtitle = soundModeLabel, selected = false, onClick = onCycleSoundMode)
            }
            item { LipSyncRow() }
            item {
                PanelSectionLabel("This stream")
            }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    StatLine("Channel", channel.name)
                    StatLine("Source", activeStream?.sourceName ?: "—")
                    StatLine("Picture", pictureText)
                    StatLine("Video", videoText)
                    StatLine("Audio", audioText)
                    StatLine("Bitrate", bitrateText)
                    StatLine("Buffered", bufferedText)
                }
            }
        }
    }
}

/**
 * Lip sync, for a TV or sound system whose sound runs behind or ahead of the picture: Left brings the sound sooner, Right
 * makes it later, OK puts it back in time. Kept for this TV, and the film player uses the same.
 */
@Composable
private fun LipSyncRow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var ms by remember { mutableStateOf(com.notdxbi.dxbi.tv.LipSync.ms) }
    var focused by remember { mutableStateOf(false) }
    PanelRow(
        title = "Lip sync",
        subtitle = if (focused) "◀ sooner   ${com.notdxbi.dxbi.tv.LipSync.label(ms)}   later ▶" else com.notdxbi.dxbi.tv.LipSync.label(ms),
        selected = false,
        onClick = { ms = com.notdxbi.dxbi.tv.LipSync.set(context, 0L) },
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent ev.key == Key.DirectionLeft || ev.key == Key.DirectionRight
                when (ev.key) {
                    Key.DirectionLeft -> { ms = com.notdxbi.dxbi.tv.LipSync.shift(context, -com.notdxbi.dxbi.tv.LipSync.STEP_MS); true }
                    Key.DirectionRight -> { ms = com.notdxbi.dxbi.tv.LipSync.shift(context, +com.notdxbi.dxbi.tv.LipSync.STEP_MS); true }
                    else -> false
                }
            },
    )
}

@Composable
private fun SubtitlesPanel(
    subtitleTracks: List<LiveTrackOption>,
    onSelectSubtitle: (Int, Int) -> Unit,
    onDisableSubtitles: () -> Unit,
    onClose: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { firstFocus.requestFocus() }
    }

    val subsOff = subtitleTracks.none { it.selected }

    FloatingPanelShell(title = "Subtitles", width = 340.dp, onClose = onClose) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            item {
                PanelRow(
                    title = "Off",
                    subtitle = "Disable subtitles",
                    badge = if (subsOff) "✓" else null,
                    selected = subsOff,
                    modifier = Modifier.focusRequester(firstFocus),
                    onClick = {
                        onDisableSubtitles()
                        onClose()
                    },
                )
            }
            items(subtitleTracks, key = { "s:${it.groupIndex}:${it.trackIndex}" }) { track ->
                PanelRow(
                    title = track.label,
                    subtitle = track.language ?: "Subtitle",
                    badge = if (track.selected) "✓" else null,
                    selected = track.selected,
                    onClick = {
                        onSelectSubtitle(track.groupIndex, track.trackIndex)
                        onClose()
                    },
                )
            }
        }
    }
}

@Composable
private fun AudioPanel(
    audioTracks: List<LiveTrackOption>,
    onSelectAudio: (Int, Int) -> Unit,
    onClose: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { firstFocus.requestFocus() }
    }

    FloatingPanelShell(title = "Audio", width = 360.dp, onClose = onClose) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            if (audioTracks.isEmpty()) {
                item {
                    Text(
                        text = "No alternate audio tracks",
                        fontSize = 14.sp,
                        color = Dx.Muted,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            } else {
                items(audioTracks, key = { "a:${it.groupIndex}:${it.trackIndex}" }) { track ->
                    val isFirst = audioTracks.firstOrNull()?.let { it.groupIndex == track.groupIndex && it.trackIndex == track.trackIndex } == true
                    PanelRow(
                        title = track.label,
                        subtitle = track.detail ?: if (track.supported) "Supported" else "Can't play here",
                        badge = if (track.selected) "✓" else null,
                        selected = track.selected,
                        modifier = if (isFirst) Modifier.focusRequester(firstFocus) else Modifier,
                        onClick = {
                            if (track.supported) {
                                onSelectAudio(track.groupIndex, track.trackIndex)
                                onClose()
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PanelSectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0x99FFFFFF),
        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 13.sp, color = Color(0x99FFFFFF))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PanelActionPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.12f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun PanelRow(
    title: String,
    subtitle: String,
    badge: String? = null,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color.White.copy(alpha = 0.14f) else Color.Transparent,
            contentColor = if (selected) Color.White else Color(0xCCFFFFFF),
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        val ink = LocalContentColor.current
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = ink.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!badge.isNullOrBlank()) {
                Text(
                    text = badge,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = ink,
                )
            }
        }
    }
}

/** "FHD · 50 fps · Stereo": what the picture and sound are, as TiviMate's badges (plain words, not pills) */
private fun formatFormatBadges(info: LiveVideoInfo): String? {
    val res = when {
        info.height >= 2160 -> "4K"
        info.height >= 1080 -> "FHD"
        info.height >= 720 -> "HD"
        info.height > 0 -> "SD"
        else -> return null
    }
    val parts = mutableListOf(res)
    if (info.fps >= 49f) parts += "${"%.0f".format(info.fps)} fps"
    if (info.hdrLabel.isNotBlank()) parts += info.hdrLabel
    if (info.audioChannels > 0) parts += channelCountName(info.audioChannels)
    return parts.joinToString(" · ")
}

private fun formatCompactTechBadge(info: LiveVideoInfo): String? {
    val res = when {
        info.height >= 2160 -> "4K"
        info.height >= 1080 -> "1080p"
        info.height >= 720 -> "720p"
        info.height > 0 -> "${info.height}p"
        else -> null
    } ?: return null
    val fps = if (info.fps >= 49f) "${"%.0f".format(info.fps)}" else ""
    val hdr = if (info.hdrLabel.isNotBlank()) " ${info.hdrLabel}" else ""
    return "$res$fps$hdr"
}

private fun channelCountName(count: Int): String = when (count) {
    1 -> "Mono"
    2 -> "Stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> "${count}ch"
}


@Composable
private fun LumenOfferCard(offer: com.notdxbi.dxbi.tv.live.LumenHandoff.Offer, onWatch: (Boolean) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(offer.programmeKey) { delay(80); runCatching { first.requestFocus() } }
    val mins = offer.joinMs / 60_000L
    val join = if (mins >= 60) "%d:%02d".format(mins / 60, mins % 60) else "${mins} min"
    Column(
        modifier = Modifier
            .width(400.dp)
            .liveFrost(RoundedCornerShape(22.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(if (offer.viaLumen) "In Lumen · ${offer.label}" else "Started $join ago · ${offer.label}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Dx.Muted)
        Text(offer.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (offer.viaLumen) {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LiveChip(label = if (mins >= 2) "From $join" else "Watch", selected = true, onClick = { onWatch(false) }, modifier = Modifier.focusRequester(first))
                if (mins >= 2) LiveChip(label = "From the start", selected = false, onClick = { onWatch(true) })
            }
        } else {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LiveChip(label = "From the start", selected = true, onClick = { onWatch(true) }, modifier = Modifier.focusRequester(first))
                LiveChip(label = "Keep watching live", selected = false, onClick = { com.notdxbi.dxbi.tv.live.LumenHandoff.dismiss() })
            }
        }
        Text("Back: not now", fontSize = 11.sp, color = Dx.Faint)
    }
}
