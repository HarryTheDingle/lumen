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
                    .liveGlass(RoundedCornerShape(Dx.RPanel))
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
    // (the buttons come first, as on a film's page; Down again brings up the channels, to choose one)
    val stripOpen = expandedFlag && stripChannels.isNotEmpty() && !state.isCatchup
    LaunchedEffect(state.osdVisible) {
        if (!state.osdVisible) com.notdxbi.dxbi.tv.live.LivePlayerExtras.expanded.value = false
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
            .height(if (stripOpen) 386.dp else 230.dp)
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

            ControlsRow(state, actions, playFocus, panelButtons, Modifier.padding(top = 6.dp))

            if (stripOpen) {
                ChannelStrip(
                    entries = stripChannels,
                    currentChannelId = state.channel.id,
                    focusTick = stripTick,
                    onZap = actions.onZapChannel,
                    onOpenGuide = { actions.onOpenPanel(LiveOverlayPanel.MINI_GUIDE) },
                    guideFocus = panelButtons.getValue(LiveOverlayPanel.MINI_GUIDE),
                )
            }
        }
    }
}

/**
 * Play/pause, from the start or back to live, and the settings (audio, subtitles, picture, sound, feeds, more): round
 * buttons like a film's page, each opening out with its name when chosen
 */
@Composable
private fun ControlsRow(state: LiveOverlayState, actions: LiveOverlayActions, playFocus: FocusRequester, panelButtons: Map<LiveOverlayPanel, FocusRequester>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        com.notdxbi.dxbi.tv.lumen.RoundButton(if (state.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (state.isPaused) "Play" else "Pause", actions.onTogglePause, Modifier.focusRequester(playFocus), expands = true)
        if (state.isCatchup) {
            com.notdxbi.dxbi.tv.lumen.RoundButton(Icons.Filled.Replay10, "Back to live", actions.onGoLive, expands = true)
        } else if (state.channel.hasCatchup && state.nowProgramme != null) {
            com.notdxbi.dxbi.tv.lumen.RoundButton(Icons.Filled.Replay10, "Watch from start", actions.onWatchFromStart, expands = true)
        }
        Spacer(Modifier.weight(1f))
        com.notdxbi.dxbi.tv.lumen.RoundButton(Icons.Filled.Audiotrack, "Audio", { actions.onOpenPanel(LiveOverlayPanel.AUDIO) }, Modifier.focusRequester(panelButtons.getValue(LiveOverlayPanel.AUDIO)), expands = true)
        com.notdxbi.dxbi.tv.lumen.RoundButton(Icons.Filled.ClosedCaption, "Subtitles", { actions.onOpenPanel(LiveOverlayPanel.SUBTITLES) }, Modifier.focusRequester(panelButtons.getValue(LiveOverlayPanel.SUBTITLES)), expands = true)
        com.notdxbi.dxbi.tv.lumen.RoundButton(Icons.Filled.AspectRatio, "Picture: ${state.zoomMode.label}", actions.onCycleZoom, expands = true)
        com.notdxbi.dxbi.tv.lumen.RoundButton(Icons.Filled.GraphicEq, "Sound: ${state.soundModeLabel}", actions.onCycleSoundMode, expands = true)
        com.notdxbi.dxbi.tv.lumen.RoundButton(Icons.Filled.SwapHoriz, "Feeds", { actions.onOpenPanel(LiveOverlayPanel.FEEDS) }, Modifier.focusRequester(panelButtons.getValue(LiveOverlayPanel.FEEDS)), expands = true)
        com.notdxbi.dxbi.tv.lumen.RoundButton(Icons.Filled.MoreHoriz, "Options", { actions.onOpenPanel(LiveOverlayPanel.MORE) }, Modifier.focusRequester(panelButtons.getValue(LiveOverlayPanel.MORE)), expands = true)
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

    // (five whole tiles across: nothing is cut off at either edge, and the row only ever moves by whole tiles. The playing
    // channel opens at the far left, the ones after it to its right; the Guide button is just before it, a Left away)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val total = entries.size + 1
    fun firstFor(idx: Int): Int = (idx + 1).coerceIn(0, (total - STRIP_SHOWN).coerceAtLeast(0))
    val listState = rememberLazyListState(firstFor(currentIdx), 0)
    var firstPlace by remember { mutableStateOf(true) }
    LaunchedEffect(currentChannelId) {
        if (firstPlace) { firstPlace = false; return@LaunchedEffect }
        runCatching { listState.scrollToItem(firstFor(currentIdx), 0) }
    }
    LaunchedEffect(focusTick) {
        if (focusTick > 0L) {
            delay(120)
            runCatching { currentTile.requestFocus() }
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { StripPreview.value = null } }
    val spec = remember(density) { WholeTileSpec(with(density) { STRIP_EDGE.toPx() }) }

    // (the row is just wide enough for its five tiles and the room a chosen tile lifts into at each end)
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides spec) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    androidx.compose.foundation.lazy.LazyRow(
        state = listState,
        modifier = Modifier
            .width(STRIP_TILE_W * STRIP_SHOWN + STRIP_GAP * (STRIP_SHOWN - 1) + STRIP_EDGE * 2)
            .onFocusChanged { if (!it.hasFocus) StripPreview.value = null }
            .padding(top = 4.dp),
        contentPadding = PaddingValues(horizontal = STRIP_EDGE, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(STRIP_GAP),
    ) {
        item(key = "guide") {
            Surface(
                onClick = onOpenGuide,
                modifier = Modifier.width(STRIP_TILE_W).height(STRIP_HEIGHT).focusRequester(guideFocus).onFocusChanged { if (it.isFocused) StripPreview.value = null }
                    .liveFrost(RoundedCornerShape(Dx.RCard)),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(Dx.RCard)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                    focusedContainerColor = Color.White,
                    focusedContentColor = Color.Black,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = Dx.FocusRow),
            ) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = null, modifier = Modifier.size(22.dp), tint = LocalContentColor.current)
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
                corner = Dx.RCard,
            ) {
                // frosted glass over the picture, as the channel box at the top (the one playing a touch lighter)
                Box(Modifier.fillMaxSize().liveFrost(RoundedCornerShape(Dx.RCard), rim = false).then(if (playing) Modifier.background(Color.White.copy(alpha = 0.12f)) else Modifier)) {
                    val ink = Color.White
                    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // the channel's logo says which it is; what's on follows, in as many lines as it needs
                        Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.CenterStart) {
                            ChannelTile(logoUrl = logo, name = channel.name, width = 64.dp, height = 24.dp, corner = 5.dp, plain = true)
                        }
                        Text(
                            text = prog?.title ?: channel.name, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, color = ink,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        // (when it's on, as the guide shows it)
                        if (prog != null) {
                            Text(
                                text = "${LiveTimeFormat.formatTime24h(prog.startMs)} – ${LiveTimeFormat.formatTime24h(prog.endMs)}",
                                style = TextStyle(fontSize = 12.sp, lineHeight = 14.sp, color = ink.copy(alpha = 0.68f), fontFeatureSettings = "tnum"),
                                maxLines = 1,
                            )
                        }
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

/** Moves the row only when the chosen tile isn't wholly in view, and then just far enough to bring it to the edge (by whole tiles, as they're all alike) */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private class WholeTileSpec(private val edge: Float) : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> get() = com.notdxbi.dxbi.tv.lumen.RowGlide
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
        offset < edge -> offset - edge
        offset + size > containerSize - edge -> offset + size - (containerSize - edge)
        else -> 0f
    }
}

/** The channel the remote is on in the player's strip (the top box shows it), or null on the one playing */
private val StripPreview = androidx.compose.runtime.mutableStateOf<Pair<CanonicalChannel, EpgProgramme?>?>(null)

/** How tall the quick channel changer's tiles are, how wide, and the gap between them */
private val PANEL_BORDER = Color(0x1FFFFFFF)
private val STRIP_HEIGHT = 108.dp
private val STRIP_TILE_W = 148.dp
private val STRIP_GAP = 12.dp
/** Room at each end for a chosen tile to lift into */
private val STRIP_EDGE = 10.dp
/** The Guide button and four channels */
private const val STRIP_SHOWN = 5

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
                        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Dx.Muted, fontFeatureSettings = "tnum"),
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
            .widthIn(min = 320.dp, max = 560.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ChannelTile(logoUrl = remember(channel.id) { tileLogoFor(channel) }, name = channel.name, width = 72.dp, height = 40.dp, plain = true)

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
                        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Dx.Muted, fontFeatureSettings = "tnum"),
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
                        style = TextStyle(fontSize = 12.sp, color = Dx.Muted, fontFeatureSettings = "tnum"),
                        maxLines = 1,
                    )
                }
            } else {
                Text(
                    text = "Live broadcast",
                    fontSize = 13.sp,
                    color = Dx.Muted,
                )
            }

            if (nextProg != null) {
                Text(
                    text = "Next · ${LiveTimeFormat.formatTime12h(nextProg.startMs)} ${nextProg.title}",
                    style = TextStyle(fontSize = 12.sp, color = Dx.Muted, fontFeatureSettings = "tnum"),
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
                .liveGlass(RoundedCornerShape(Dx.RPanel))
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
                        text = "Someone's already watching",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        text = conflict.sourceName,
                        fontSize = 13.sp,
                        color = Dx.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Text(
                text = conflict.reason,
                fontSize = 15.sp,
                color = Color.White.copy(alpha = 0.9f),
            )

            Text(
                text = "Watching this channel could stop their stream, because your provider only allows a limited number at once.",
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
                .liveGlass(RoundedCornerShape(Dx.RPanel))
                .border(1.dp, PANEL_BORDER, RoundedCornerShape(Dx.RPanel))
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
            .liveGlass(RoundedCornerShape(Dx.RPanel))
            .border(1.dp, PANEL_BORDER, RoundedCornerShape(Dx.RPanel))
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
                color = Dx.Muted,
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

    FloatingPanelShell(title = "Mini-guide", width = 460.dp, onClose = onClose) {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
        ) {
            items(entries, key = { it.first.id }) { (ch, prog) ->
                val active = ch.id == currentChannelId
                PanelRow(
                    title = "${ch.number?.let { "$it · " } ?: ""}${ch.name}",
                    subtitle = prog?.title ?: ch.category.label,
                    badge = if (active) "On air" else formatCompactQuality(ch.streams.firstOrNull()?.quality),
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
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
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
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(Dx.RCard)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (pinned) Dx.Blue.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f),
                            contentColor = Color.White,
                            focusedContainerColor = Color.White,
                            focusedContentColor = Color.Black,
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = Dx.FocusRow),
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
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
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
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
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
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
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
        color = Dx.Muted,
        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, fontSize = 13.sp, color = Dx.Muted, maxLines = 1)
        Text(
            value,
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White, fontFeatureSettings = "tnum"),
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
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
        scale = ClickableSurfaceDefaults.scale(focusedScale = Dx.FocusRow),
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
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(Dx.RCard)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color.White.copy(alpha = 0.14f) else Color.Transparent,
            contentColor = if (selected) Color.White else Color(0xCCFFFFFF),
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = Dx.FocusRow),
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
                    style = TextStyle(fontSize = 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = ink, fontFeatureSettings = "tnum"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = TextStyle(fontSize = 12.sp, color = ink.copy(alpha = 0.65f), fontFeatureSettings = "tnum"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!badge.isNullOrBlank()) {
                Text(
                    text = badge,
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ink, fontFeatureSettings = "tnum"),
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
    // (small: a corner prompt over the picture, not a panel; Back dismisses it)
    Column(
        modifier = Modifier
            .width(310.dp)
            // (black glass with a fine rim, as Lumen's own panels: not the grey sheen of the player's frosted boxes)
            .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(Dx.RPanel))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(Dx.RPanel))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            if (offer.viaLumen) "Watch in Lumen" else "Started $join ago",
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.6f), fontFeatureSettings = "tnum"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(offer.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        // ("S2, E4" read out: Season 2 · Episode 4; a film just says so)
        val episode = remember(offer.label) {
            Regex("""S(\d+)\D+E(\d+)""", RegexOption.IGNORE_CASE).find(offer.label)?.let { "Season ${it.groupValues[1]} · Episode ${it.groupValues[2]}" } ?: offer.label
        }
        if (episode.isNotBlank()) Text(episode, fontSize = 13.sp, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (offer.viaLumen) {
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiveChip(label = if (mins >= 2) "From $join" else "Watch", selected = true, onClick = { onWatch(false) }, modifier = Modifier.focusRequester(first))
                if (mins >= 2) LiveChip(label = "From the start", selected = false, onClick = { onWatch(true) })
            }
        } else {
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiveChip(label = "From the start", selected = true, onClick = { onWatch(true) }, modifier = Modifier.focusRequester(first))
                LiveChip(label = "Keep watching live", selected = false, onClick = { com.notdxbi.dxbi.tv.live.LumenHandoff.dismiss() })
            }
        }
    }
}
