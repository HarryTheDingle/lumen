package com.notdxbi.dxbi.tv.lumen.live

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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

private val PANEL_BG = Color(0xF2141418)
private val PANEL_BORDER = Color(0x1FFFFFFF)

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

    LaunchedEffect(state.osdVisible, state.activePanel) {
        if (state.osdVisible && state.activePanel == LiveOverlayPanel.NONE && !state.scrub.active) {
            delay(40)
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
            enter = fadeIn(),
            exit = fadeOut(),
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
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 48.dp, bottom = 40.dp),
        ) {
            lumenOffer?.let { offer -> LumenOfferCard(offer, onWatch = actions.onWatchInLumen) }
        }

        // Top 130dp gradient scrim (Back button + Clock)
        AnimatedVisibility(
            visible = state.osdVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopOsdScrim(state = state)
        }

        // Tuning / failover / buffering status banner centered cleanly at top
        TuningStatusBanner(
            state = state.playbackState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 28.dp),
        )

        // Bottom 240dp gradient scrim + SeekLine + 3-zone controls
        AnimatedVisibility(
            visible = state.osdVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            BottomOsdScrim(
                state = state,
                actions = actions,
                seekFocus = seekFocus,
                playFocus = playFocus,
            )
        }

        // Bottom-right floating panel matching activity_player.xml (@id/panel)
        AnimatedVisibility(
            visible = state.activePanel != LiveOverlayPanel.NONE,
            enter = fadeIn(),
            exit = fadeOut(),
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

        Text(
            text = Dates.formatTime(),
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 36.dp, end = 56.dp),
        )
    }
}

@Composable
private fun BottomOsdScrim(
    state: LiveOverlayState,
    actions: LiveOverlayActions,
    seekFocus: FocusRequester,
    playFocus: FocusRequester,
) {
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
            .height(240.dp)
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
                .padding(start = 56.dp, end = 56.dp, bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
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

            // Few controls, as the Apple TV app: play/pause, watch from the start, and Options for everything else
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PlayerIconButton(
                    icon = if (state.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = if (state.isPaused) "Play" else "Pause",
                    onClick = actions.onTogglePause,
                    modifier = Modifier.focusRequester(playFocus),
                )
                if (state.isCatchup) {
                    PlayerTextButton("Back to live", onClick = actions.onGoLive)
                } else if (state.channel.hasCatchup && state.nowProgramme != null) {
                    PlayerTextButton("Watch from start", onClick = actions.onWatchFromStart)
                }
                Spacer(Modifier.weight(1f))
                PlayerIconButton(
                    icon = Icons.Filled.MoreHoriz,
                    contentDescription = "Options",
                    active = state.activePanel == LiveOverlayPanel.MORE,
                    onClick = { actions.onOpenPanel(LiveOverlayPanel.MORE) },
                )
            }
        }
    }
}

@Composable
private fun PlayerTextButton(label: String, onClick: () -> Unit) {
    androidx.tv.material3.Surface(
        onClick = onClick,
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.16f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Replay10, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = androidx.tv.material3.LocalContentColor.current)
        }
    }
}

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
                    .background(PANEL_BG, RoundedCornerShape(10.dp))
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

@Composable
private fun PlayerIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    tint: Color? = null,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(52.dp),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (active) Color(0x33FFFFFF) else Color.Transparent,
            contentColor = tint ?: Color.White,
            focusedContainerColor = Color(0x40FFFFFF),
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
private fun TuningStatusBanner(
    state: LivePlaybackState,
    modifier: Modifier = Modifier,
) {
    val message = when (state) {
        is LivePlaybackState.Resolving -> "Resolving ${state.channel.name}…"
        is LivePlaybackState.Tuning -> "Tuning ${state.stream.sourceName} (${state.attempt}/${state.total}) · ${state.stream.quality.badge}"
        is LivePlaybackState.FailingOver -> "Switching to ${state.nextStream.sourceName} (${state.reason})"
        is LivePlaybackState.Buffering -> if (state.elapsedMs >= 1200L) "Buffering…" else null
        else -> null
    } ?: return

    Box(
        modifier = modifier
            .background(PANEL_BG, RoundedCornerShape(14.dp))
            .border(1.dp, PANEL_BORDER, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(
            text = message,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ZapBanner(
    state: LiveOverlayState,
    modifier: Modifier = Modifier,
) {
    val activeStream = state.streams.firstOrNull { it.id == state.activeStreamId } ?: state.streams.firstOrNull()
    val qualityBadge = formatCompactTechBadge(state.videoInfo)
        ?: formatCompactQuality(activeStream?.quality)
    val nowMs = System.currentTimeMillis()
    val nowProg = state.nowProgramme
    val nextProg = state.nextProgramme

    Row(
        modifier = modifier
            .widthIn(min = 380.dp, max = 560.dp)
            .background(Color(0xE8141418), RoundedCornerShape(18.dp))
            .border(1.dp, LivePalette.GlassBorder, RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        LandscapeChannelLogo(
            logoUrl = state.channel.logoUrl,
            name = state.channel.name,
            width = 72.dp,
            height = 34.dp,
            corner = 9.dp,
            plain = true,
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.channel.number?.let { num ->
                    Text(
                        text = num.toString(),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Dx.Muted,
                    )
                }
                Text(
                    text = state.channel.name,
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
                .background(Color(0xF518181C), RoundedCornerShape(22.dp))
                .border(1.dp, LivePalette.GlassBorder, RoundedCornerShape(22.dp))
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
                    label = "Watch Anyway",
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
                .background(PANEL_BG, RoundedCornerShape(22.dp))
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
                    label = "Back to Guide",
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
            .background(PANEL_BG, RoundedCornerShape(14.dp))
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
            .background(Color(0xEB242426), RoundedCornerShape(22.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("In Lumen · ${offer.label}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Dx.Muted)
        Text(offer.name, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("Watch it in Lumen for a much sharper picture. When it ends you can come back to the channel.", fontSize = 13.sp, color = Dx.Muted)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LiveChip(label = if (mins >= 2) "From $join" else "Watch", selected = true, onClick = { onWatch(false) }, modifier = Modifier.focusRequester(first))
            if (mins >= 2) LiveChip(label = "From the start", selected = false, onClick = { onWatch(true) })
        }
        Text("Back: not now", fontSize = 11.sp, color = Dx.Faint)
    }
}
