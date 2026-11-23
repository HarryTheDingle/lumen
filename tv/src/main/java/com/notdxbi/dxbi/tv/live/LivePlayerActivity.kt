package com.notdxbi.dxbi.tv.live

import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.ui.PlayerView
import com.notdxbi.dxbi.tv.lumen.LumenTheme
import com.notdxbi.dxbi.tv.lumen.live.LivePlayerOverlay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import lumos.iptv.ActiveViewerConflict
import lumos.iptv.CanonicalChannel
import lumos.iptv.EpgProgramme
import lumos.iptv.IptvCodec
import lumos.iptv.LivePlaybackState
import lumos.iptv.StreamSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/** Modal side/overlay panels available inside the live player OSD. */
enum class LiveOverlayPanel {
    NONE,
    MINI_GUIDE,
    FEEDS,
    MORE,
    SUBTITLES,
    AUDIO,
}

/** Active catch-up or live timeshift scrubbing state shown above the seek bar. */
data class LiveScrubState(
    val active: Boolean = false,
    val isLiveTimeshift: Boolean = false,
    val targetMs: Long = 0L,
    val rangeMs: Long = 0L,
    val fraction: Float = 0f,
    val timeLabel: String = "",
    val deltaLabel: String = "",
    val requestSeekFocusTick: Long = 0L,
)

/** UI state snapshot rendered by [LivePlayerOverlay]. */
data class LiveOverlayState(
    val channel: CanonicalChannel,
    val playbackState: LivePlaybackState,
    val nowProgramme: EpgProgramme?,
    val nextProgramme: EpgProgramme?,
    val isCatchup: Boolean,
    val catchupProgramme: EpgProgramme?,
    val catchupStartOffsetMs: Long,
    val osdVisible: Boolean,
    val activePanel: LiveOverlayPanel,
    val streams: List<StreamSource>,
    val activeStreamId: String?,
    val pinnedStreamId: String?,
    val videoInfo: LiveVideoInfo,
    val audioTracks: List<LiveTrackOption>,
    val subtitleTracks: List<LiveTrackOption>,
    val isBuffering: Boolean,
    val isPaused: Boolean,
    val isSeekable: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val bufferedMs: Long,
    val scrub: LiveScrubState,
    val soundModeLabel: String,
    val zoomMode: LiveZoomMode,
    val miniGuideChannels: List<Pair<CanonicalChannel, EpgProgramme?>>,
    val zapBannerVisible: Boolean = false,
    val viewerConflict: ActiveViewerConflict? = null,
    val alternateSourceStream: StreamSource? = null,
)

/** Callbacks wired from [LivePlayerOverlay] into [LivePlayerActivity] and [LiveExoEngine]. */
data class LiveOverlayActions(
    val onShowOsd: () -> Unit,
    val onHideOsd: () -> Unit,
    val onOpenPanel: (LiveOverlayPanel) -> Unit,
    val onClosePanel: () -> Unit,
    val onSelectStream: (StreamSource) -> Unit,
    val onPinStream: (StreamSource?) -> Unit,
    val onSelectAudio: (Int, Int) -> Unit,
    val onSelectSubtitle: (Int, Int) -> Unit,
    val onDisableSubtitles: () -> Unit,
    val onTogglePause: () -> Unit,
    val onSeekBy: (Long) -> Unit,
    val onScrubStep: (Int, Int) -> Unit,
    val onScrubCommit: () -> Unit,
    val onScrubCancel: () -> Unit,
    val onSeekBarFocusChanged: (Boolean) -> Unit,
    val onStartTimeshiftScrub: () -> Unit,
    val onGoLive: () -> Unit,
    /** The programme on now, from its start (catch-up) */
    val onWatchFromStart: () -> Unit = {},
    /** Lumen has what's on: play it there (true: from its start; false: from where the channel is) */
    val onWatchInLumen: (Boolean) -> Unit = {},
    val onDismissLumenOffer: () -> Unit = {},
    val onZapPrev: () -> Unit,
    val onZapNext: () -> Unit,
    val onCycleSoundMode: () -> Unit,
    val onCycleZoom: () -> Unit,
    val onZapChannel: (CanonicalChannel) -> Unit,
    val onRetry: () -> Unit,
    val onBack: () -> Unit,
    val onResolveConflictWatchAnyway: () -> Unit = {},
    val onResolveConflictSwitchSource: (StreamSource) -> Unit = {},
    val onResolveConflictCancel: () -> Unit = {},
)

/**
 * Fullscreen live TV and catch-up playback Activity.
 *
 * Tunes ranked feeds via [LiveExoEngine] with synchronous single-connection teardown and automatic
 * multi-stream failover, supports catch-up seeking/pause, channel zapping, and mounts
 * [LivePlayerOverlay] via Jetpack Compose.
 */
class LivePlayerActivity : AppCompatActivity() {

    private var store: LiveTvStore? = null
    private lateinit var exoEngine: LiveExoEngine
    private lateinit var playerView: PlayerView

    private lateinit var currentChannelFlow: MutableStateFlow<CanonicalChannel>
    private val playbackStateFlow = MutableStateFlow<LivePlaybackState>(LivePlaybackState.Idle)
    private val catchupProgrammeFlow = MutableStateFlow<EpgProgramme?>(null)
    private val catchupStartOffsetFlow = MutableStateFlow(0L)
    private val osdVisibleFlow = MutableStateFlow(true)
    private val activePanelFlow = MutableStateFlow(LiveOverlayPanel.NONE)
    private val activeStreamIdFlow = MutableStateFlow<String?>(null)
    private val epgTickFlow = MutableStateFlow(System.currentTimeMillis())
    private val scrubStateFlow = MutableStateFlow(LiveScrubState())
    private val zapBannerVisibleFlow = MutableStateFlow(false)
    private val viewerConflictFlow = MutableStateFlow<ActiveViewerConflict?>(null)
    private val alternateSourceStreamFlow = MutableStateFlow<StreamSource?>(null)
    private lateinit var overlayStateFlow: StateFlow<LiveOverlayState>

    private var zapChannelIds: List<String> = emptyList()
    private var activeCandidates: List<StreamSource> = emptyList()
    private var activeStreamIndex: Int = -1
    private var tuneJob: Job? = null
    private var osdHideJob: Job? = null
    private var zapBannerHideJob: Job? = null
    private var scrubGiveUpJob: Job? = null
    private var presenceHeartbeatJob: Job? = null
    private var presenceSourceId: String? = null
    private var handedOffToMiniPlayer: Boolean = false
    private var conflictDecisionDeferred: CompletableDeferred<ConflictResolution>? = null
    private var lastTuneRequestPreferredStreamId: String? = null
    private var seekBarFocused: Boolean = false
    private var scrubWasPlaying: Boolean = false
    private var liveScrubAnchorNowMs: Long = 0L
    private var liveScrubMaxMs: Long = 0L
    private var lastScrubDirection: Int = 0
    private var lastScrubTapElapsedMs: Long = 0L
    private var consecutiveScrubTaps: Int = 0
    private var scrubFocusSeq: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val liveStore = LiveTvRuntime.currentStore()
        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID)
        val initialChannel = if (liveStore != null && !channelId.isNullOrBlank()) {
            liveStore.channelById(channelId)
        } else null

        if (liveStore == null || initialChannel == null) {
            Log.w(TAG, "Unable to open LivePlayerActivity: store=${liveStore != null}, channelId=$channelId")
            Toast.makeText(this, "Channel unavailable", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        store = liveStore

        val catchupProgramme = intent.getStringExtra(EXTRA_PROGRAMME_JSON)?.let(IptvCodec::decodeProgramme)
        val initialPreferredStreamId = intent.getStringExtra(EXTRA_STREAM_ID)
        val skipConflictCheck = intent.getBooleanExtra(EXTRA_SKIP_CONFLICT_CHECK, false)
        zapChannelIds = intent.getStringArrayListExtra(EXTRA_ZAP_CHANNEL_IDS)?.toList().orEmpty()
        lastTuneRequestPreferredStreamId = initialPreferredStreamId
        LiveMiniPlayer.openingFullscreen = false

        val resumedSession = LiveMiniPlayer.takeIfSame(initialChannel.id, catchupProgramme)
        if (resumedSession != null && zapChannelIds.isEmpty()) {
            zapChannelIds = resumedSession.zapChannelIds
        }

        currentChannelFlow = MutableStateFlow(initialChannel)
        catchupProgrammeFlow.value = catchupProgramme

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        exoEngine = if (resumedSession != null) {
            resumedSession.engine.also { engine ->
                engine.onStallFailover = { reason -> handleMidStreamFailover(reason) }
            }
        } else {
            LiveExoEngine(applicationContext) { reason ->
                handleMidStreamFailover(reason)
            }
        }

        playerView = PlayerView(this).apply {
            setBackgroundColor(Color.BLACK)
            useController = false
            keepScreenOn = true
        }
        exoEngine.attachPlayerView(playerView)

        overlayStateFlow = buildOverlayStateFlow(liveStore)
        val actions = LiveOverlayActions(
            onShowOsd = { showOsd(autoHide = true) },
            onHideOsd = { hideOsd() },
            onOpenPanel = { panel ->
                cancelScrub(restorePlay = true)
                activePanelFlow.value = panel
                showOsd(autoHide = false)
            },
            onClosePanel = {
                activePanelFlow.value = LiveOverlayPanel.NONE
                showOsd(autoHide = true)
            },
            onSelectStream = { stream ->
                activePanelFlow.value = LiveOverlayPanel.NONE
                val catchupProg = catchupProgrammeFlow.value
                val offset = catchupStartOffsetFlow.value
                startChannelTune(
                    channel = currentChannelFlow.value,
                    preferredStreamId = stream.id,
                    catchup = catchupProg,
                    catchupStartAtMs = if (catchupProg != null && offset > 0L) catchupProg.startMs + offset else null,
                )
            },
            onPinStream = { stream ->
                liveStore.pinPreferredStream(currentChannelFlow.value.id, stream?.id)
                liveStore.channelById(currentChannelFlow.value.id)?.let { updated ->
                    currentChannelFlow.value = updated
                }
            },
            onSelectAudio = { groupIdx, trackIdx ->
                exoEngine.selectAudioTrack(groupIdx, trackIdx)
                pokeOsd()
            },
            onSelectSubtitle = { groupIdx, trackIdx ->
                exoEngine.selectSubtitleTrack(groupIdx, trackIdx)
                pokeOsd()
            },
            onDisableSubtitles = {
                exoEngine.disableSubtitles()
                pokeOsd()
            },
            onTogglePause = {
                cancelScrub(restorePlay = false)
                exoEngine.togglePause()
                pokeOsd()
            },
            onSeekBy = { deltaMs ->
                if (catchupProgrammeFlow.value != null) {
                    if (exoEngine.isSeekable.value && exoEngine.durationMs.value > 0L) {
                        exoEngine.seekBy(deltaMs)
                    } else {
                        stepScrub(if (deltaMs < 0) -1 else +1, repeatCount = 0, focusBar = true)
                    }
                    pokeOsd()
                }
            },
            onScrubStep = { dir, repeat ->
                stepScrub(dir, repeat, focusBar = false)
            },
            onScrubCommit = {
                commitScrub()
            },
            onScrubCancel = {
                cancelScrub(restorePlay = true)
            },
            onSeekBarFocusChanged = { focused ->
                seekBarFocused = focused
                if (!focused && scrubStateFlow.value.active) {
                    cancelScrub(restorePlay = true)
                }
            },
            onStartTimeshiftScrub = {
                if (catchupProgrammeFlow.value != null) {
                    stepScrub(-1, repeatCount = 0, focusBar = true)
                } else {
                    stepScrub(-1, repeatCount = 0, focusBar = true)
                }
            },
            onGoLive = {
                cancelScrub(restorePlay = false)
                startChannelTune(
                    channel = currentChannelFlow.value,
                    preferredStreamId = activeStreamIdFlow.value,
                    catchup = null,
                )
            },
            onWatchFromStart = {
                cancelScrub(restorePlay = false)
                val now = System.currentTimeMillis()
                val prog = store?.nowAndNext(currentChannelFlow.value, now)?.first
                if (prog != null) {
                    startChannelTune(
                        channel = currentChannelFlow.value,
                        preferredStreamId = activeStreamIdFlow.value,
                        catchup = prog,
                    )
                }
            },
            onWatchInLumen = { fromStart -> watchInLumen(fromStart) },
            onDismissLumenOffer = { LumenHandoff.dismiss() },
            onZapPrev = { zapRelative(-1) },
            onZapNext = { zapRelative(+1) },
            onCycleSoundMode = {
                exoEngine.cycleSoundMode()
                pokeOsd()
            },
            onCycleZoom = {
                exoEngine.cycleZoom()
                pokeOsd()
            },
            onZapChannel = { target ->
                activePanelFlow.value = LiveOverlayPanel.NONE
                liveStore.recordRecent(target.id)
                startChannelTune(target, preferredStreamId = null, catchup = null)
            },
            onRetry = {
                val catchupProg = catchupProgrammeFlow.value
                val offset = catchupStartOffsetFlow.value
                startChannelTune(
                    channel = currentChannelFlow.value,
                    preferredStreamId = lastTuneRequestPreferredStreamId,
                    catchup = catchupProg,
                    catchupStartAtMs = if (catchupProg != null && offset > 0L) catchupProg.startMs + offset else null,
                )
            },
            onBack = { handleBackNavigation() },
            onResolveConflictWatchAnyway = {
                conflictDecisionDeferred?.complete(ConflictResolution.WatchAnyway)
            },
            onResolveConflictSwitchSource = { alt ->
                conflictDecisionDeferred?.complete(ConflictResolution.SwitchSource(alt))
            },
            onResolveConflictCancel = {
                conflictDecisionDeferred?.complete(ConflictResolution.Cancel)
            },
        )

        val composeOverlay = ComposeView(this).apply {
            setContent {
                LumenTheme {
                    val state by overlayStateFlow.collectAsState()
                    LivePlayerOverlay(state = state, actions = actions)
                }
            }
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                playerView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                composeOverlay,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        setContentView(root)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleBackNavigation()
                }
            },
        )

        observeBufferingState()
        startEpgTicker()

        if (resumedSession != null) {
            val ranked = liveStore.rankedStreamsFor(initialChannel)
            val resumedStream = resumedSession.activeStream
            activeCandidates = ranked
            activeStreamIndex = if (resumedStream != null) {
                ranked.indexOfFirst { it.id == resumedStream.id }.takeIf { it >= 0 } ?: 0
            } else -1
            activeStreamIdFlow.value = resumedStream?.id
            playbackStateFlow.value = if (resumedStream != null) {
                LivePlaybackState.Playing(
                    channel = initialChannel,
                    stream = resumedStream,
                    isCatchup = catchupProgramme != null,
                )
            } else {
                LivePlaybackState.Resolving(initialChannel)
            }
            if (resumedStream != null) {
                startPresenceTracking(liveStore, resumedStream.sourceId, initialChannel.name, notifyStart = false)
            }
            showOsd(autoHide = true)
        } else {
            startChannelTune(
                channel = initialChannel,
                preferredStreamId = initialPreferredStreamId,
                catchup = catchupProgramme,
                skipConflictCheck = skipConflictCheck,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        // back from Home or another app: tune in again (the stream was let go when we left)
        if (releasedOnStop) {
            releasedOnStop = false
            val catchup = catchupProgrammeFlow.value
            val offset = catchupStartOffsetFlow.value
            startChannelTune(
                channel = currentChannelFlow.value,
                preferredStreamId = activeStreamIdFlow.value,
                catchup = catchup,
                catchupStartAtMs = if (catchup != null && offset > 0L) catchup.startMs + offset else null,
                skipConflictCheck = true,
            )
        }
    }

    /** The stream was let go because the app left the screen */
    private var releasedOnStop = false

    override fun onStop() {
        super.onStop()
        // Leaving for Home or another app lets go of the stream entirely: a paused live stream keeps its connection
        // open, and most IPTV lines allow only one (the Sony kept NigmaTV's busy after the app was left)
        if (!handedOffToMiniPlayer && !isFinishing) {
            tuneJob?.cancel()
            stopPresenceIfNeeded()
            exoEngine.releaseCurrentSync()
            releasedOnStop = true
        }
    }

    override fun onDestroy() {
        tuneJob?.cancel()
        osdHideJob?.cancel()
        zapBannerHideJob?.cancel()
        scrubGiveUpJob?.cancel()
        presenceHeartbeatJob?.cancel()
        if (!handedOffToMiniPlayer) {
            stopPresenceIfNeeded()
            if (::exoEngine.isInitialized) {
                exoEngine.release()
            }
        }
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val osdOpen = osdVisibleFlow.value
        val unavailable = playbackStateFlow.value is LivePlaybackState.Unavailable
        val panelOpen = activePanelFlow.value != LiveOverlayPanel.NONE
        val scrubActive = scrubStateFlow.value.active
        val conflictOpen = viewerConflictFlow.value != null

        if (event.action == KeyEvent.ACTION_DOWN) {
            if (conflictOpen || (LumenHandoff.offer.value != null && !osdOpen)) {
                return super.dispatchKeyEvent(event)
            }
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                    zapRelative(+1, showBannerOnly = !osdOpen)
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                    zapRelative(-1, showBannerOnly = !osdOpen)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                    cancelScrub(restorePlay = false)
                    exoEngine.togglePause()
                    showOsd(autoHide = true)
                    return true
                }
            }

            if (!unavailable && !panelOpen && (scrubActive || !osdOpen || seekBarFocused)) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        if (!osdOpen) showOsd(autoHide = true)
                        if (stepScrub(-1, event.repeatCount, focusBar = !osdOpen || !seekBarFocused) || seekBarFocused) {
                            pokeOsd()
                            return true
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        if (!osdOpen) showOsd(autoHide = true)
                        if (stepScrub(+1, event.repeatCount, focusBar = !osdOpen || !seekBarFocused) || seekBarFocused) {
                            pokeOsd()
                            return true
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (scrubActive) {
                            commitScrub()
                            return true
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (scrubActive) {
                            cancelScrub(restorePlay = true)
                        }
                    }
                }
            }

            if (!osdOpen && !unavailable && !panelOpen && !scrubActive) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        zapRelative(-1, showBannerOnly = true)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        zapRelative(+1, showBannerOnly = true)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        // one press pauses, as in Lumen's player and on Apple TV (it used to only bring up the controls)
                        hideZapBanner()
                        exoEngine.togglePause()
                        showOsd(autoHide = true)
                        return true
                    }
                }
            } else if (osdOpen) {
                pokeOsd()
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun startChannelTune(
        channel: CanonicalChannel,
        preferredStreamId: String?,
        catchup: EpgProgramme?,
        catchupStartAtMs: Long? = null,
        suppressOsd: Boolean = false,
        skipConflictCheck: Boolean = false,
    ) {
        val liveStore = store ?: return
        tuneJob?.cancel()
        conflictDecisionDeferred?.cancel()
        conflictDecisionDeferred = null
        viewerConflictFlow.value = null
        alternateSourceStreamFlow.value = null
        cancelScrub(restorePlay = false)
        exoEngine.releaseCurrentSync()

        currentChannelFlow.value = channel
        catchupProgrammeFlow.value = catchup
        catchupStartOffsetFlow.value = if (catchup != null && catchupStartAtMs != null) {
            (catchupStartAtMs - catchup.startMs).coerceAtLeast(0L)
        } else {
            0L
        }
        activeStreamIdFlow.value = null
        activeStreamIndex = -1
        lastTuneRequestPreferredStreamId = preferredStreamId
        epgTickFlow.value = System.currentTimeMillis()
        if (suppressOsd) {
            osdHideJob?.cancel()
            osdVisibleFlow.value = false
            showZapBanner()
        } else {
            hideZapBanner()
            showOsd(autoHide = true)
        }

        if (catchup == null && liveStore.nowNextByChannel.value[channel.id]?.first == null) {
            liveStore.fetchChannelEpgIfMissing(channel)
        }

        val baseRanked = liveStore.rankedStreamsFor(channel)
        val filtered = if (catchup != null) {
            baseRanked.filter { it.catchup != null && it.catchup!!.days > 0 }
        } else {
            baseRanked
        }
        val candidates = if (preferredStreamId != null) {
            val preferred = filtered.firstOrNull { it.id == preferredStreamId }
            if (preferred != null) listOf(preferred) + filtered.filterNot { it.id == preferredStreamId } else filtered
        } else {
            filtered
        }
        activeCandidates = candidates

        if (candidates.isEmpty()) {
            stopPresenceIfNeeded()
            playbackStateFlow.value = LivePlaybackState.Unavailable(
                channel = channel,
                reason = if (catchup != null) "Catch-up archive is unavailable for this channel" else "No streams configured for this channel",
            )
            hideZapBanner()
            showOsd(autoHide = false)
            return
        }

        val primaryStream = candidates.first()
        val needConflictCheck = !skipConflictCheck && primaryStream.sourceId != presenceSourceId
        if (!needConflictCheck) {
            tuneFromIndex(channel, candidates, startIndex = 0, catchup = catchup, catchupStartAtMs = catchupStartAtMs, suppressOsd = suppressOsd)
            return
        }

        tuneJob = lifecycleScope.launch {
            playbackStateFlow.value = LivePlaybackState.Resolving(channel)
            val conflict = liveStore.checkActiveViewerConflict(channel, primaryStream.id)
            var effectiveCandidates = candidates
            if (conflict != null) {
                val alt = candidates.firstOrNull { it.sourceId != conflict.sourceId }
                alternateSourceStreamFlow.value = alt
                viewerConflictFlow.value = conflict
                val deferred = CompletableDeferred<ConflictResolution>()
                conflictDecisionDeferred = deferred
                val decision = try {
                    deferred.await()
                } finally {
                    conflictDecisionDeferred = null
                    viewerConflictFlow.value = null
                    alternateSourceStreamFlow.value = null
                }
                when (decision) {
                    ConflictResolution.WatchAnyway -> {
                        LiveMiniPlayer.markOwnSourceActive(primaryStream.sourceId)
                    }
                    is ConflictResolution.SwitchSource -> {
                        effectiveCandidates = listOf(decision.stream) +
                            candidates.filterNot { it.id == decision.stream.id }
                        activeCandidates = effectiveCandidates
                    }
                    ConflictResolution.Cancel -> {
                        finish()
                        return@launch
                    }
                }
            }

            runTuneLoop(
                liveStore = liveStore,
                channel = channel,
                candidates = effectiveCandidates,
                startIndex = 0,
                catchup = catchup,
                catchupStartAtMs = catchupStartAtMs,
                suppressOsd = suppressOsd,
            )
        }
    }

    private fun tuneFromIndex(
        channel: CanonicalChannel,
        candidates: List<StreamSource>,
        startIndex: Int,
        catchup: EpgProgramme?,
        catchupStartAtMs: Long? = null,
        suppressOsd: Boolean = false,
    ) {
        val liveStore = store ?: return
        tuneJob?.cancel()
        tuneJob = lifecycleScope.launch {
            runTuneLoop(
                liveStore = liveStore,
                channel = channel,
                candidates = candidates,
                startIndex = startIndex,
                catchup = catchup,
                catchupStartAtMs = catchupStartAtMs,
                suppressOsd = suppressOsd,
            )
        }
    }

    private suspend fun runTuneLoop(
        liveStore: LiveTvStore,
        channel: CanonicalChannel,
        candidates: List<StreamSource>,
        startIndex: Int,
        catchup: EpgProgramme?,
        catchupStartAtMs: Long?,
        suppressOsd: Boolean,
    ) {
        playbackStateFlow.value = LivePlaybackState.Resolving(channel)

        // a provider that refused (connections full, login refused) refuses all its feeds: try other providers only
        val refusedSources = HashSet<String>()
        var lastRefusal: String? = null
        for (idx in startIndex until candidates.size) {
            val stream = candidates[idx]
            if (stream.sourceId in refusedSources) {
                if (candidates.drop(idx + 1).all { it.sourceId in refusedSources }) {
                    stopPresenceIfNeeded()
                    playbackStateFlow.value = LivePlaybackState.Unavailable(channel, lastRefusal ?: "Stream refused by server")
                    hideZapBanner()
                    showOsd(autoHide = false)
                    return
                }
                continue
            }
            exoEngine.releaseCurrentSync()
            playbackStateFlow.value = LivePlaybackState.Tuning(
                channel = channel,
                stream = stream,
                attempt = idx + 1,
                total = candidates.size,
            )

            val resolvedResult = withContext(Dispatchers.IO) {
                runCatching {
                    when {
                        catchup != null && catchupStartAtMs != null && catchupStartAtMs > catchup.startMs ->
                            liveStore.resolveCatchupAt(channel, catchup, catchupStartAtMs, stream)
                        catchup != null ->
                            liveStore.resolveCatchup(channel, catchup, stream)
                        else ->
                            liveStore.resolveLive(channel, stream)
                    }
                }
            }
            val resolved = resolvedResult.getOrNull()
            if (resolved == null) {
                val reason = resolvedResult.exceptionOrNull()?.message ?: "Failed to resolve stream URL"
                liveStore.recordStreamFailure(stream.id)
                if (idx + 1 < candidates.size) {
                    playbackStateFlow.value = LivePlaybackState.FailingOver(
                        channel = channel,
                        failedStream = stream,
                        nextStream = candidates[idx + 1],
                        reason = reason,
                    )
                    delay(FAILOVER_BANNER_DELAY_MS)
                    continue
                } else {
                    stopPresenceIfNeeded()
                    playbackStateFlow.value = LivePlaybackState.Unavailable(channel, reason)
                    hideZapBanner()
                    showOsd(autoHide = false)
                    return
                }
            }

            val tuneStartedAt = SystemClock.elapsedRealtime()
            val tuneResult = suspendCancellableCoroutine<Result<Unit>> { cont ->
                exoEngine.tune(
                    resolved = resolved,
                    onReady = {
                        if (cont.isActive) cont.resume(Result.success(Unit))
                    },
                    onFail = { err ->
                        if (cont.isActive) cont.resume(Result.failure(IllegalStateException(err)))
                    },
                )
            }

            if (tuneResult.isSuccess) {
                liveStore.recordStreamSuccess(stream.id, SystemClock.elapsedRealtime() - tuneStartedAt)
                activeStreamIndex = idx
                activeStreamIdFlow.value = stream.id
                startPresenceTracking(liveStore, stream.sourceId, channel.name, notifyStart = true)
                playbackStateFlow.value = LivePlaybackState.Playing(
                    channel = channel,
                    stream = stream,
                    isCatchup = resolved.isCatchup,
                )
                if (!resolved.isCatchup) lookForLumenCopy(liveStore, channel) else LumenHandoff.offer.value = null
                if (!suppressOsd) {
                    showOsd(autoHide = true)
                }
                return
            }

            val failReason = tuneResult.exceptionOrNull()?.message ?: "Stream playback failed"
            liveStore.recordStreamFailure(stream.id)
            exoEngine.releaseCurrentSync()
            if (failReason == LiveExoEngine.REFUSED_FULL || failReason == LiveExoEngine.REFUSED_LOGIN) {
                refusedSources += stream.sourceId
                lastRefusal = failReason
            }

            if (idx + 1 < candidates.size && candidates.drop(idx + 1).any { it.sourceId !in refusedSources }) {
                playbackStateFlow.value = LivePlaybackState.FailingOver(
                    channel = channel,
                    failedStream = stream,
                    nextStream = candidates[idx + 1],
                    reason = failReason,
                )
                delay(FAILOVER_BANNER_DELAY_MS)
            } else {
                stopPresenceIfNeeded()
                playbackStateFlow.value = LivePlaybackState.Unavailable(channel, failReason)
                hideZapBanner()
                showOsd(autoHide = false)
            }
        }
    }

    private fun handleMidStreamFailover(reason: String) {
        val liveStore = store ?: return
        val channel = currentChannelFlow.value
        val failedStream = activeCandidates.getOrNull(activeStreamIndex)
        if (failedStream != null) {
            liveStore.recordStreamFailure(failedStream.id)
        }
        exoEngine.releaseCurrentSync()

        val nextIdx = activeStreamIndex + 1
        if (nextIdx in activeCandidates.indices && failedStream != null) {
            playbackStateFlow.value = LivePlaybackState.FailingOver(
                channel = channel,
                failedStream = failedStream,
                nextStream = activeCandidates[nextIdx],
                reason = reason,
            )
            val catchupProg = catchupProgrammeFlow.value
            val offset = catchupStartOffsetFlow.value
            val startAt = if (catchupProg != null && offset > 0L) catchupProg.startMs + offset else null
            tuneFromIndex(channel, activeCandidates, nextIdx, catchupProg, startAt)
        } else {
            stopPresenceIfNeeded()
            playbackStateFlow.value = LivePlaybackState.Unavailable(channel, reason)
            hideZapBanner()
            showOsd(autoHide = false)
        }
    }

    private fun zapRelative(delta: Int, showBannerOnly: Boolean = false) {
        val liveStore = store ?: return
        val list = orderedZapChannels(liveStore)
        if (list.isEmpty()) return
        val currentIdx = list.indexOfFirst { it.id == currentChannelFlow.value.id }
        val nextIdx = if (currentIdx < 0) 0 else (currentIdx + delta + list.size) % list.size
        val nextChannel = list[nextIdx]
        if (nextChannel.id == currentChannelFlow.value.id) return
        liveStore.recordRecent(nextChannel.id)
        startChannelTune(
            channel = nextChannel,
            preferredStreamId = null,
            catchup = null,
            suppressOsd = showBannerOnly,
        )
    }

    private fun orderedZapChannels(liveStore: LiveTvStore): List<CanonicalChannel> {
        val byId = liveStore.channels.value.associateBy { it.id }
        if (zapChannelIds.isNotEmpty()) {
            val curated = zapChannelIds.mapNotNull { byId[it] }
            if (curated.isNotEmpty()) return curated
        }
        val guideOrdered = liveStore.mainGuideRows.value.mapNotNull { it.linkedChannel }
        return if (guideOrdered.isNotEmpty()) guideOrdered else liveStore.channels.value
    }

    private fun startPresenceTracking(
        liveStore: LiveTvStore,
        sourceId: String,
        channelName: String,
        notifyStart: Boolean,
    ) {
        val prevSource = presenceSourceId
        if (prevSource != null && prevSource != sourceId) {
            liveStore.notifyPresenceStop(prevSource)
        }
        presenceSourceId = sourceId
        if (notifyStart) {
            liveStore.notifyPresenceStart(sourceId, channelName)
        } else {
            LiveMiniPlayer.markOwnSourceActive(sourceId)
        }
        presenceHeartbeatJob?.cancel()
        presenceHeartbeatJob = lifecycleScope.launch {
            while (isActive) {
                delay(PRESENCE_HEARTBEAT_MS)
                if (!isActive) break
                liveStore.notifyPresenceHeartbeat(sourceId, currentChannelFlow.value.name)
            }
        }
    }

    private fun stopPresenceIfNeeded() {
        presenceHeartbeatJob?.cancel()
        presenceHeartbeatJob = null
        val prev = presenceSourceId ?: return
        presenceSourceId = null
        store?.notifyPresenceStop(prev)
    }

    private fun showZapBanner() {
        zapBannerVisibleFlow.value = true
        zapBannerHideJob?.cancel()
        zapBannerHideJob = lifecycleScope.launch {
            delay(ZAP_BANNER_TIMEOUT_MS)
            zapBannerVisibleFlow.value = false
        }
    }

    private fun hideZapBanner() {
        zapBannerHideJob?.cancel()
        zapBannerHideJob = null
        zapBannerVisibleFlow.value = false
    }

    private fun observeBufferingState() {
        var bufferingStartedAt = 0L
        lifecycleScope.launch {
            exoEngine.isBuffering.collect { buffering ->
                val state = playbackStateFlow.value
                if (buffering && state is LivePlaybackState.Playing) {
                    bufferingStartedAt = SystemClock.elapsedRealtime()
                    playbackStateFlow.value = LivePlaybackState.Buffering(
                        channel = state.channel,
                        stream = state.stream,
                        elapsedMs = 0L,
                    )
                } else if (!buffering && state is LivePlaybackState.Buffering) {
                    bufferingStartedAt = 0L
                    playbackStateFlow.value = LivePlaybackState.Playing(
                        channel = state.channel,
                        stream = state.stream,
                        isCatchup = catchupProgrammeFlow.value != null,
                    )
                }
            }
        }
        lifecycleScope.launch {
            while (isActive) {
                delay(500L)
                val state = playbackStateFlow.value
                if (state is LivePlaybackState.Buffering && bufferingStartedAt > 0L) {
                    playbackStateFlow.value = state.copy(elapsedMs = SystemClock.elapsedRealtime() - bufferingStartedAt)
                }
            }
        }
    }

    private fun startEpgTicker() {
        lifecycleScope.launch {
            while (isActive) {
                delay(15_000L)
                epgTickFlow.value = System.currentTimeMillis()
            }
        }
    }

    private fun buildOverlayStateFlow(liveStore: LiveTvStore): StateFlow<LiveOverlayState> {
        val baseFlow = combine(
            currentChannelFlow,
            playbackStateFlow,
            catchupProgrammeFlow,
            osdVisibleFlow,
            activePanelFlow,
        ) { ch, state, catchup, osd, panel ->
            BaseSlice(ch, state, catchup, osd, panel)
        }

        val mediaFlow = combine(
            exoEngine.videoInfo,
            exoEngine.audioTracks,
            exoEngine.subtitleTracks,
            exoEngine.isBuffering,
            exoEngine.isPaused,
        ) { info, audio, subs, buf, paused ->
            MediaSlice(info, audio, subs, buf, paused)
        }

        val timingFlow = combine(
            exoEngine.positionMs,
            exoEngine.durationMs,
            exoEngine.bufferedMs,
            exoEngine.isSeekable,
            scrubStateFlow,
        ) { pos, dur, bufMs, seekable, scrub ->
            TimingSlice(pos, dur, bufMs, seekable, scrub)
        }

        val baseMetaFlow = combine(
            exoEngine.soundModeLabel,
            exoEngine.zoomMode,
            activeStreamIdFlow,
            catchupStartOffsetFlow,
        ) { soundLabel, zoom, activeStreamId, catchupOffset ->
            BaseMetaSlice(soundLabel, zoom, activeStreamId, catchupOffset)
        }

        val metaFlow = combine(
            baseMetaFlow,
            zapBannerVisibleFlow,
            viewerConflictFlow,
            alternateSourceStreamFlow,
        ) { baseMeta, zapVisible, conflict, altStream ->
            MetaSlice(
                soundLabel = baseMeta.soundLabel,
                zoom = baseMeta.zoom,
                activeStreamId = baseMeta.activeStreamId,
                catchupStartOffsetMs = baseMeta.catchupStartOffsetMs,
                zapBannerVisible = zapVisible,
                viewerConflict = conflict,
                alternateSourceStream = altStream,
            )
        }

        val feedsOpenFlow = activePanelFlow
            .map { it == LiveOverlayPanel.FEEDS }
            .distinctUntilChanged()

        val streamsFlow = combine(
            currentChannelFlow,
            liveStore.prefs,
            feedsOpenFlow,
        ) { ch, _, _ ->
            liveStore.rankedStreamsFor(ch)
        }.flowOn(Dispatchers.Default)

        val miniGuideOpenFlow = activePanelFlow
            .map { it == LiveOverlayPanel.MINI_GUIDE }
            .distinctUntilChanged()

        val miniGuideFlow = combine(
            miniGuideOpenFlow,
            liveStore.channels,
            liveStore.nowNextByChannel,
        ) { isOpen, channels, nowNextMap ->
            if (!isOpen) {
                emptyList()
            } else {
                channels.map { ch -> ch to nowNextMap[ch.id]?.first }
            }
        }.flowOn(Dispatchers.Default)

        val nowNextFlow = combine(
            currentChannelFlow,
            catchupProgrammeFlow,
            liveStore.epgVersion,
            epgTickFlow,
        ) { ch, catchup, _, nowMs ->
            if (catchup != null) {
                catchup to null
            } else {
                liveStore.nowAndNext(ch, nowMs)
            }
        }.flowOn(Dispatchers.Default)

        val guideFlow = combine(
            nowNextFlow,
            streamsFlow,
            miniGuideFlow,
            currentChannelFlow,
            liveStore.prefs,
        ) { (nowProg, nextProg), streams, miniGuide, ch, prefs ->
            GuideSlice(
                nowProgramme = nowProg,
                nextProgramme = nextProg,
                streams = streams,
                pinnedStreamId = prefs.preferredStreamByChannel[ch.id],
                miniGuideChannels = miniGuide,
            )
        }

        return combine(baseFlow, mediaFlow, timingFlow, metaFlow, guideFlow) { base, media, timing, meta, guide ->
            LiveOverlayState(
                channel = base.channel,
                playbackState = base.state,
                nowProgramme = guide.nowProgramme,
                nextProgramme = guide.nextProgramme,
                isCatchup = base.catchup != null,
                catchupProgramme = base.catchup,
                catchupStartOffsetMs = meta.catchupStartOffsetMs,
                osdVisible = base.osd,
                activePanel = base.panel,
                streams = guide.streams,
                activeStreamId = meta.activeStreamId,
                pinnedStreamId = guide.pinnedStreamId,
                videoInfo = media.info,
                audioTracks = media.audio,
                subtitleTracks = media.subs,
                isBuffering = media.buffering,
                isPaused = media.paused,
                isSeekable = timing.seekable,
                positionMs = timing.pos,
                durationMs = timing.dur,
                bufferedMs = timing.bufMs,
                scrub = timing.scrub,
                soundModeLabel = meta.soundLabel,
                zoomMode = meta.zoom,
                miniGuideChannels = guide.miniGuideChannels,
                zapBannerVisible = meta.zapBannerVisible,
                viewerConflict = meta.viewerConflict,
                alternateSourceStream = meta.alternateSourceStream,
            )
        }.stateIn(
            scope = lifecycleScope,
            started = SharingStarted.Eagerly,
            initialValue = LiveOverlayState(
                channel = currentChannelFlow.value,
                playbackState = playbackStateFlow.value,
                nowProgramme = null,
                nextProgramme = null,
                isCatchup = catchupProgrammeFlow.value != null,
                catchupProgramme = catchupProgrammeFlow.value,
                catchupStartOffsetMs = catchupStartOffsetFlow.value,
                osdVisible = true,
                activePanel = LiveOverlayPanel.NONE,
                streams = liveStore.rankedStreamsFor(currentChannelFlow.value),
                activeStreamId = null,
                pinnedStreamId = liveStore.prefs.value.preferredStreamByChannel[currentChannelFlow.value.id],
                videoInfo = exoEngine.videoInfo.value,
                audioTracks = emptyList(),
                subtitleTracks = emptyList(),
                isBuffering = false,
                isPaused = false,
                isSeekable = false,
                positionMs = 0L,
                durationMs = 0L,
                bufferedMs = 0L,
                scrub = LiveScrubState(),
                soundModeLabel = exoEngine.soundModeLabel.value,
                zoomMode = exoEngine.zoomMode.value,
                miniGuideChannels = emptyList(),
            ),
        )
    }

    private fun stepScrub(direction: Int, repeatCount: Int, focusBar: Boolean): Boolean {
        val catchupProg = catchupProgrammeFlow.value
        val stateSnapshot = if (::overlayStateFlow.isInitialized) overlayStateFlow.value else null
        val current = scrubStateFlow.value

        val nowElapsed = SystemClock.elapsedRealtime()
        val presses = if (repeatCount > 0) {
            consecutiveScrubTaps = repeatCount
            lastScrubDirection = direction
            lastScrubTapElapsedMs = nowElapsed
            repeatCount
        } else {
            if (direction == lastScrubDirection && nowElapsed - lastScrubTapElapsedMs < 600L) {
                consecutiveScrubTaps++
            } else {
                consecutiveScrubTaps = 1
            }
            lastScrubDirection = direction
            lastScrubTapElapsedMs = nowElapsed
            consecutiveScrubTaps
        }
        val stepMs = when {
            presses > 24 -> 60_000L
            presses > 6 -> 30_000L
            else -> 10_000L
        }

        if (catchupProg != null) {
            val progDur = (catchupProg.endMs - catchupProg.startMs).coerceAtLeast(1L)
            val exoDur = exoEngine.durationMs.value
            val startOff = catchupStartOffsetFlow.value
            val totalDur = if (startOff == 0L && exoDur > 0L) exoDur else progDur
            val currentPos = (startOff + exoEngine.positionMs.value).coerceIn(0L, totalDur)

            val baseTarget = if (current.active && !current.isLiveTimeshift) {
                current.targetMs
            } else {
                scrubWasPlaying = !exoEngine.isPaused.value
                exoEngine.setPaused(true)
                currentPos
            }
            val nextTarget = (baseTarget + direction * stepMs).coerceIn(0L, totalDur)
            val focusTick = if (focusBar) ++scrubFocusSeq else current.requestSeekFocusTick
            val fraction = (nextTarget.toFloat() / totalDur.toFloat()).coerceIn(0f, 1f)
            scrubStateFlow.value = LiveScrubState(
                active = true,
                isLiveTimeshift = false,
                targetMs = nextTarget,
                rangeMs = totalDur,
                fraction = fraction,
                timeLabel = formatDurationClock(nextTarget),
                deltaLabel = formatDeltaClock(nextTarget - currentPos),
                requestSeekFocusTick = focusTick,
            )
            restartScrubTimeout()
            pokeOsd()
            return true
        }

        val liveProg = stateSnapshot?.nowProgramme
        if (!currentChannelFlow.value.hasCatchup || liveProg == null) return false

        val nowMs = System.currentTimeMillis()
        if (!current.active || !current.isLiveTimeshift) {
            if (direction >= 0) return false
            liveScrubAnchorNowMs = nowMs
            liveScrubMaxMs = (nowMs - liveProg.startMs).coerceAtLeast(0L)
            if (liveScrubMaxMs <= 1_000L) return false
        }

        val progDur = (liveProg.endMs - liveProg.startMs).coerceAtLeast(1L)
        val baseTarget = if (current.active && current.isLiveTimeshift) current.targetMs else liveScrubMaxMs
        val nextTarget = (baseTarget + direction * stepMs).coerceIn(0L, liveScrubMaxMs)
        val targetEpochMs = liveScrubAnchorNowMs - (liveScrubMaxMs - nextTarget)
        val focusTick = if (focusBar) ++scrubFocusSeq else current.requestSeekFocusTick
        val fraction = (nextTarget.toFloat() / progDur.toFloat()).coerceIn(0f, 1f)

        scrubStateFlow.value = LiveScrubState(
            active = true,
            isLiveTimeshift = true,
            targetMs = nextTarget,
            rangeMs = liveScrubMaxMs,
            fraction = fraction,
            timeLabel = formatWallClock(targetEpochMs),
            deltaLabel = formatDeltaClock(nextTarget - liveScrubMaxMs),
            requestSeekFocusTick = focusTick,
        )
        restartScrubTimeout()
        pokeOsd()
        return true
    }

    private fun commitScrub() {
        val current = scrubStateFlow.value
        if (!current.active) return
        scrubGiveUpJob?.cancel()
        scrubStateFlow.value = LiveScrubState()

        if (!current.isLiveTimeshift) {
            val catchupProg = catchupProgrammeFlow.value ?: return
            val startOff = catchupStartOffsetFlow.value
            val exoDur = exoEngine.durationMs.value
            val target = current.targetMs
            if (target >= startOff && exoEngine.isSeekable.value && exoDur > 0L) {
                val rel = (target - startOff).coerceIn(0L, exoDur)
                exoEngine.seekTo(rel)
                if (scrubWasPlaying) exoEngine.setPaused(false)
                pokeOsd()
            } else {
                startChannelTune(
                    channel = currentChannelFlow.value,
                    preferredStreamId = activeStreamIdFlow.value,
                    catchup = catchupProg,
                    catchupStartAtMs = catchupProg.startMs + target,
                )
            }
            return
        }

        val liveProg = if (::overlayStateFlow.isInitialized) overlayStateFlow.value.nowProgramme else null
        if (liveProg == null) return
        val deltaFromLive = liveScrubMaxMs - current.targetMs
        if (deltaFromLive < 3_000L) {
            pokeOsd()
            return
        }
        val targetEpochMs = liveScrubAnchorNowMs - deltaFromLive
        startChannelTune(
            channel = currentChannelFlow.value,
            preferredStreamId = activeStreamIdFlow.value,
            catchup = liveProg,
            catchupStartAtMs = targetEpochMs,
        )
    }

    private fun cancelScrub(restorePlay: Boolean) {
        scrubGiveUpJob?.cancel()
        val was = scrubStateFlow.value
        if (!was.active) return
        scrubStateFlow.value = LiveScrubState()
        if (!was.isLiveTimeshift && restorePlay && scrubWasPlaying) {
            exoEngine.setPaused(false)
        }
        pokeOsd()
    }

    private fun restartScrubTimeout() {
        scrubGiveUpJob?.cancel()
        scrubGiveUpJob = lifecycleScope.launch {
            delay(SCRUB_GIVE_UP_MS)
            if (scrubStateFlow.value.active) {
                cancelScrub(restorePlay = true)
            }
        }
    }

    private fun showOsd(autoHide: Boolean) {
        hideZapBanner()
        osdVisibleFlow.value = true
        epgTickFlow.value = System.currentTimeMillis()
        scheduleOsdHide(autoHide)
    }

    private fun hideOsd() {
        osdHideJob?.cancel()
        cancelScrub(restorePlay = true)
        activePanelFlow.value = LiveOverlayPanel.NONE
        osdVisibleFlow.value = false
    }

    private fun pokeOsd() {
        if (!osdVisibleFlow.value) osdVisibleFlow.value = true
        scheduleOsdHide(autoHide = activePanelFlow.value == LiveOverlayPanel.NONE && !scrubStateFlow.value.active)
    }

    private fun scheduleOsdHide(autoHide: Boolean) {
        osdHideJob?.cancel()
        if (!autoHide || scrubStateFlow.value.active) return
        osdHideJob = lifecycleScope.launch {
            delay(OSD_TIMEOUT_MS)
            if (activePanelFlow.value == LiveOverlayPanel.NONE &&
                !scrubStateFlow.value.active &&
                !exoEngine.isPaused.value &&
                playbackStateFlow.value is LivePlaybackState.Playing
            ) {
                osdVisibleFlow.value = false
            }
        }
    }

    private var lumenOfferJob: Job? = null

    /** A film, or an episode the guide numbers, that Lumen has: offer it there (a much better picture) */
    private fun lookForLumenCopy(liveStore: LiveTvStore, channel: CanonicalChannel) {
        lumenOfferJob?.cancel()
        LumenHandoff.offer.value = null
        lumenOfferJob = lifecycleScope.launch {
            delay(4_000L)
            val now = System.currentTimeMillis()
            val prog = liveStore.nowAndNext(channel, now).first ?: return@launch
            if ("${channel.id}:${prog.startMs}" in LumenHandoff.answered) return@launch
            if (prog.isLiveSport || Regex("news|sport|weather|live", RegexOption.IGNORE_CASE).containsMatchIn(prog.category.orEmpty())) return@launch
            val found = liveStore.findInLumen(channel, prog) ?: return@launch
            if (currentChannelFlow.value.id != channel.id) return@launch
            val imdb = found.imdbId ?: return@launch
            val episode = LumenHandoff.episodeOf(prog.subtitle, prog.title, prog.description)
            val (type, videoId, label) = when {
                found.mediaType == "movie" -> Triple("movie", null, "Film")
                episode != null -> Triple("series", "$imdb:${episode.first}:${episode.second}", "S${episode.first}, E${episode.second}")
                else -> return@launch
            }
            LumenHandoff.offer.value = LumenHandoff.Offer(
                programmeKey = "${channel.id}:${prog.startMs}",
                name = prog.title.replace(Regex("^(?:new|film|movie)\\s*:\\s*", RegexOption.IGNORE_CASE), ""),
                label = label,
                joinMs = (now - prog.startMs).coerceAtLeast(0L),
                request = LumenHandoff.Request(
                    id = imdb, type = type, name = prog.title, background = found.backdrop,
                    videoId = videoId, channelId = channel.id, channelName = channel.name,
                ),
            )
            // (an offer that isn't taken goes after a while)
            delay(10_000L)
            LumenHandoff.offer.value = null
        }
    }

    private fun watchInLumen(fromStart: Boolean) {
        val offer = LumenHandoff.offer.value ?: return
        LumenHandoff.dismiss()
        LumenHandoff.startMsOnce = if (fromStart) 0L else offer.joinMs
        LumenHandoff.returnTo = offer.request.channelId to offer.request.channelName
        LumenHandoff.pending.value = offer.request
        // the channel's let go of (most lines allow one connection) and Lumen's screens take over
        tuneJob?.cancel()
        stopPresenceIfNeeded()
        exoEngine.releaseCurrentSync()
        finish()
    }

    private fun handleBackNavigation() {
        when {
            LumenHandoff.offer.value != null -> {
                LumenHandoff.dismiss()
            }
            viewerConflictFlow.value != null -> {
                conflictDecisionDeferred?.complete(ConflictResolution.Cancel)
            }
            scrubStateFlow.value.active -> {
                cancelScrub(restorePlay = true)
            }
            activePanelFlow.value != LiveOverlayPanel.NONE -> {
                activePanelFlow.value = LiveOverlayPanel.NONE
                showOsd(autoHide = true)
            }
            osdVisibleFlow.value && playbackStateFlow.value !is LivePlaybackState.Unavailable -> {
                hideOsd()
            }
            playbackStateFlow.value is LivePlaybackState.Playing && exoEngine.isPlayingLiveOrCatchup() && LiveMiniPlayer.hosts > 0 -> {
                val activeStream = activeCandidates.getOrNull(activeStreamIndex)
                val session = LiveMiniPlayer.Session(
                    engine = exoEngine,
                    channel = currentChannelFlow.value,
                    activeStream = activeStream,
                    catchupProgramme = catchupProgrammeFlow.value,
                    zapChannelIds = zapChannelIds,
                )
                handedOffToMiniPlayer = true
                presenceHeartbeatJob?.cancel()
                presenceHeartbeatJob = null
                exoEngine.detachPlayerView(playerView)
                LiveMiniPlayer.keep(session, store)
                finish()
            }
            else -> {
                stopPresenceIfNeeded()
                exoEngine.releaseCurrentSync()
                finish()
            }
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun formatDurationClock(ms: Long): String {
        val total = (ms.coerceAtLeast(0L) / 1000L).toInt()
        val h = total / 3600
        val m = (total / 60) % 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun formatDeltaClock(deltaMs: Long): String {
        if (deltaMs == 0L) return "Live edge"
        val sign = if (deltaMs < 0L) "-" else "+"
        val absSec = (kotlin.math.abs(deltaMs) / 1000L).toInt()
        val m = absSec / 60
        val s = absSec % 60
        return when {
            m >= 60 -> "$sign${m / 60}h ${m % 60}m"
            m > 0 && s > 0 -> "$sign${m}m ${s}s"
            m > 0 -> "$sign${m}m"
            else -> "$sign${s}s"
        }
    }

    private fun formatWallClock(epochMs: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))

    private data class BaseSlice(
        val channel: CanonicalChannel,
        val state: LivePlaybackState,
        val catchup: EpgProgramme?,
        val osd: Boolean,
        val panel: LiveOverlayPanel,
    )

    private data class MediaSlice(
        val info: LiveVideoInfo,
        val audio: List<LiveTrackOption>,
        val subs: List<LiveTrackOption>,
        val buffering: Boolean,
        val paused: Boolean,
    )

    private data class TimingSlice(
        val pos: Long,
        val dur: Long,
        val bufMs: Long,
        val seekable: Boolean,
        val scrub: LiveScrubState,
    )

    private sealed interface ConflictResolution {
        data object WatchAnyway : ConflictResolution
        data class SwitchSource(val stream: StreamSource) : ConflictResolution
        data object Cancel : ConflictResolution
    }

    private data class BaseMetaSlice(
        val soundLabel: String,
        val zoom: LiveZoomMode,
        val activeStreamId: String?,
        val catchupStartOffsetMs: Long,
    )

    private data class MetaSlice(
        val soundLabel: String,
        val zoom: LiveZoomMode,
        val activeStreamId: String?,
        val catchupStartOffsetMs: Long,
        val zapBannerVisible: Boolean,
        val viewerConflict: ActiveViewerConflict?,
        val alternateSourceStream: StreamSource?,
    )

    private data class GuideSlice(
        val nowProgramme: EpgProgramme?,
        val nextProgramme: EpgProgramme?,
        val streams: List<StreamSource>,
        val pinnedStreamId: String?,
        val miniGuideChannels: List<Pair<CanonicalChannel, EpgProgramme?>>,
    )

    companion object {
        private const val TAG = "lumos-live-player"
        const val EXTRA_CHANNEL_ID = "channel_id"
        const val EXTRA_STREAM_ID = "stream_id"
        const val EXTRA_PROGRAMME_JSON = "programme_json"
        const val EXTRA_ZAP_CHANNEL_IDS = "zap_channel_ids"
        const val EXTRA_SKIP_CONFLICT_CHECK = "skip_conflict_check"
        private const val OSD_TIMEOUT_MS = 5_000L
        private const val ZAP_BANNER_TIMEOUT_MS = 3_500L
        private const val PRESENCE_HEARTBEAT_MS = 45_000L
        private const val SCRUB_GIVE_UP_MS = 60_000L
        private const val FAILOVER_BANNER_DELAY_MS = 350L
    }
}
