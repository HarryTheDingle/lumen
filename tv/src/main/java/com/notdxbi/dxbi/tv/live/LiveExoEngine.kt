package com.notdxbi.dxbi.tv.live

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.notdxbi.dxbi.tv.PlayerActivity
import com.notdxbi.dxbi.tv.SoundMode
import com.notdxbi.dxbi.tv.hdrName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import lumos.iptv.ResolvedStream
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Current decoded video/audio characteristics of the tuned live or catch-up feed. */
data class LiveVideoInfo(
    val width: Int = 0,
    val height: Int = 0,
    val fps: Float = 0f,
    val hdrLabel: String = "",
    val videoCodec: String = "",
    val bitrateBps: Int = 0,
    val audioCodec: String = "",
    val audioChannels: Int = 0,
)

/** Selectable audio or subtitle track exposed by Media3 for the active feed. */
data class LiveTrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val language: String?,
    val detail: String?,
    val selected: Boolean,
    val supported: Boolean,
)

/** Aspect/resize presets cycled from the live OSD. */
enum class LiveZoomMode(val label: String, val resizeMode: Int) {
    FIT("Fit", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    ZOOM("Crop", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    FILL("Stretch", AspectRatioFrameLayout.RESIZE_MODE_FILL),
}

/**
 * Dedicated Media3 1.5.1 live TV and catch-up playback engine.
 *
 * Enforces synchronous single-connection teardown via [releaseCurrentSync] before opening a new
 * stream or backup feed, enables FFmpeg extension audio fallback for UK broadcast audio
 * (AC3/EAC3/MP2/AAC), and monitors buffering/frame progress with a 6s stall failover watchdog.
 */
@OptIn(UnstableApi::class)
class LiveExoEngine(
    context: Context,
    var onStallFailover: (String) -> Unit = {},
) {
    private class LiveRenderersFactory(
        context: Context,
        private val sound: SoundMode,
    ) : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink {
            val builder = if (PlayerActivity.passthroughEnabled(context)) {
                DefaultAudioSink.Builder(context)
            } else {
                @Suppress("DEPRECATION")
                DefaultAudioSink.Builder().setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
            }
            return builder
                .setEnableFloatOutput(false)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(sound))
                .build()
        }
    }

    private val appContext = context.applicationContext
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    // (ExoPlayer's own way of reading a transport stream: with access units "detected" the Sony drew about 9 frames a second
    // of Sky Cinema Action while TiviMate, using the plain reader, drew 25)
    private val tsExtractorsFactory = DefaultExtractorsFactory()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var player: ExoPlayer? = null
    private var attachedView: PlayerView? = null
    private var soundProcessor: SoundMode? = null
    private var currentSoundMode = SoundMode.MODES[0]
    private var watchdogJob: Job? = null
    private var lastStatsLogMs = 0L
    private var generation = 0L

    private val _videoInfo = MutableStateFlow(LiveVideoInfo())
    val videoInfo: StateFlow<LiveVideoInfo> = _videoInfo.asStateFlow()

    private val _audioTracks = MutableStateFlow<List<LiveTrackOption>>(emptyList())
    val audioTracks: StateFlow<List<LiveTrackOption>> = _audioTracks.asStateFlow()

    private val _subtitleTracks = MutableStateFlow<List<LiveTrackOption>>(emptyList())
    val subtitleTracks: StateFlow<List<LiveTrackOption>> = _subtitleTracks.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _bufferedMs = MutableStateFlow(0L)
    val bufferedMs: StateFlow<Long> = _bufferedMs.asStateFlow()

    private val _isSeekable = MutableStateFlow(false)
    val isSeekable: StateFlow<Boolean> = _isSeekable.asStateFlow()

    private val _soundModeLabel = MutableStateFlow(currentSoundMode.label)
    val soundModeLabel: StateFlow<String> = _soundModeLabel.asStateFlow()

    private val _zoomMode = MutableStateFlow(LiveZoomMode.FIT)
    val zoomMode: StateFlow<LiveZoomMode> = _zoomMode.asStateFlow()

    /** Binds [playerView] to the live engine and disables Media3's built-in VOD controller. */
    fun attachPlayerView(playerView: PlayerView, resizeModeOverride: Int? = null) {
        val prev = attachedView
        if (prev != null && prev !== playerView && prev.player === player) {
            prev.player = null
        }
        attachedView = playerView
        playerView.useController = false
        playerView.resizeMode = resizeModeOverride ?: _zoomMode.value.resizeMode
        playerView.player = player
    }

    /** Detaches [playerView] only if it is currently bound to this engine's player. */
    fun detachPlayerView(playerView: PlayerView) {
        if (attachedView === playerView) {
            attachedView = null
        }
        if (playerView.player === player) {
            playerView.player = null
        }
    }

    /** True when an active live or catch-up player instance is currently prepared or playing. */
    fun isPlayingLiveOrCatchup(): Boolean {
        val exo = player ?: return false
        return exo.playbackState != Player.STATE_IDLE && exo.playbackState != Player.STATE_ENDED
    }

    /**
     * Synchronously tears down any existing connection and tunes [resolved].
     * Invokes [onReady] once video renders (or audio is ready for radio feeds) or [onFail] on startup failure.
     */
    fun tune(
        resolved: ResolvedStream,
        onReady: () -> Unit,
        onFail: (String) -> Unit,
    ) {
        releaseCurrentSync()
        val url = resolved.url.trim()
        if (url.isEmpty()) {
            onFail("Stream URL is empty")
            return
        }

        val tuneGen = ++generation
        var terminalFired = false
        var readyFired = false
        var bufferingSinceMs = SystemClock.elapsedRealtime()
        var stuckSinceMs = 0L
        var lastObservedPos = -1L
        var lastRenderedFrames = -1

        fun fireReadyOnce() {
            if (tuneGen != generation || terminalFired || readyFired) return
            readyFired = true
            _isBuffering.value = false
            onReady()
        }

        fun fireFailureOnce(reason: String) {
            if (tuneGen != generation || terminalFired) return
            terminalFired = true
            _isBuffering.value = false
            watchdogJob?.cancel()
            watchdogJob = null
            if (!readyFired) {
                onFail(reason)
            } else {
                onStallFailover(reason)
            }
        }

        val sound = SoundMode().also { it.mode = currentSoundMode }
        soundProcessor = sound
        val renderers = LiveRenderersFactory(appContext, sound)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 45_000, 800, 1_800)
            .build()

        val exo = ExoPlayer.Builder(appContext, renderers)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
            .build()

        player = exo
        attachedView?.let { view ->
            view.resizeMode = _zoomMode.value.resizeMode
            view.player = exo
        }

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (tuneGen != generation) return
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        _isBuffering.value = true
                        if (bufferingSinceMs == 0L) bufferingSinceMs = SystemClock.elapsedRealtime()
                    }
                    Player.STATE_READY -> {
                        _isBuffering.value = false
                        bufferingSinceMs = 0L
                        _isSeekable.value = exo.isCurrentMediaItemSeekable
                        if (resolved.isCatchup) {
                            _positionMs.value = exo.currentPosition.coerceAtLeast(0L)
                            _durationMs.value = exo.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
                            _bufferedMs.value = exo.bufferedPosition.coerceAtLeast(0L)
                        }
                        refreshTrackStates(exo)
                        refreshVideoInfo(exo)
                        val hasVideoTrack = exo.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }
                        if (!hasVideoTrack) {
                            fireReadyOnce()
                        }
                    }
                    Player.STATE_ENDED -> {
                        if (!resolved.isCatchup) {
                            fireFailureOnce("Live stream ended")
                        }
                    }
                    Player.STATE_IDLE -> Unit
                }
            }

            override fun onRenderedFirstFrame() {
                if (tuneGen != generation) return
                refreshVideoInfo(exo)
                fireReadyOnce()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (tuneGen != generation) return
                refreshVideoInfo(exo)
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (tuneGen != generation) return
                refreshTrackStates(exo)
                refreshVideoInfo(exo)
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (tuneGen != generation) return
                _isSeekable.value = exo.isCurrentMediaItemSeekable
                if (resolved.isCatchup) {
                    _durationMs.value = exo.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (tuneGen != generation) return
                _isPaused.value = !playWhenReady
                if (!playWhenReady) {
                    bufferingSinceMs = 0L
                    stuckSinceMs = 0L
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (tuneGen != generation) return
                _isPaused.value = !exo.playWhenReady
                if (isPlaying) {
                    stuckSinceMs = 0L
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (tuneGen != generation) return
                fireFailureOnce(describeError(error))
            }
        })

        val mediaSource = buildMediaSource(url, resolved.headers)
        exo.setMediaSource(mediaSource)
        exo.prepare()
        exo.playWhenReady = true

        _isBuffering.value = true
        _isPaused.value = false

        watchdogJob = scope.launch {
            var slowWindowStartMs = 0L
            var slowWindowFrames = 0
            val tuneStartedAt = SystemClock.elapsedRealtime()
            while (isActive && tuneGen == generation && !terminalFired) {
                delay(500L)
                if (tuneGen != generation || terminalFired) break

                val now = SystemClock.elapsedRealtime()
                val pos = exo.currentPosition.coerceAtLeast(0L)
                val buffered = exo.bufferedPosition.coerceAtLeast(0L)
                _bufferedMs.value = buffered
                if (resolved.isCatchup) {
                    val dur = exo.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
                    _positionMs.value = pos
                    _durationMs.value = dur
                }

                if (!exo.playWhenReady) {
                    bufferingSinceMs = 0L
                    stuckSinceMs = 0L
                    continue
                }

                if (exo.playbackState == Player.STATE_BUFFERING) {
                    if (bufferingSinceMs == 0L) bufferingSinceMs = now
                    if (now - bufferingSinceMs > STALL_TIMEOUT_MS) {
                        fireFailureOnce(if (readyFired) "Stream stalled buffering" else "Buffering timed out")
                        break
                    }
                    continue
                } else {
                    bufferingSinceMs = 0L
                }

                if (!readyFired) {
                    if (now - tuneStartedAt > INITIAL_TUNE_TIMEOUT_MS) {
                        fireFailureOnce("Stream timed out")
                        break
                    }
                    continue
                }

                if (!resolved.isCatchup && now - lastStatsLogMs >= 5_000L) {
                    lastStatsLogMs = now
                    val vf = exo.videoFormat
                    val vc = exo.videoDecoderCounters?.also { it.ensureUpdated() }
                    android.util.Log.i("lumos-live", "stats: ${vf?.sampleMimeType} ${vf?.width}x${vf?.height} fps=${vf?.frameRate} bitrate=${vf?.bitrate} " +
                        "decoder=${vc?.let { "rendered=${it.renderedOutputBufferCount} dropped=${it.droppedBufferCount} skipped=${it.skippedOutputBufferCount} maxDroppedInRow=${it.maxConsecutiveDroppedBufferCount}" }} " +
                        "buffered=${buffered - pos}ms state=${exo.playbackState}")
                }

                if (!resolved.isCatchup && exo.playbackState == Player.STATE_READY) {
                    val hasVideo = exo.videoFormat != null
                    exo.videoDecoderCounters?.ensureUpdated()
                    val renderedFrames = exo.videoDecoderCounters?.renderedOutputBufferCount ?: 0
                    // (a picture the decoder throws away as late, so it draws a frame or so a second while the sound plays on: this
                    // feed can't be shown on this TV; the next one is tried, as for a freeze)
                    if (hasVideo && exo.videoDecoderCounters != null) {
                        if (slowWindowStartMs == 0L) {
                            slowWindowStartMs = now
                            slowWindowFrames = renderedFrames
                        } else if (now - slowWindowStartMs >= 10_000L) {
                            val fps = (renderedFrames - slowWindowFrames) * 1000.0 / (now - slowWindowStartMs)
                            val dropped = exo.videoDecoderCounters?.droppedBufferCount ?: 0
                            if (fps < 4.0 && dropped > 50) {
                                android.util.Log.i("lumos-live", "too slow: ${"%.1f".format(fps)} fps drawn, $dropped dropped, ${exo.videoFormat?.sampleMimeType} ${exo.videoFormat?.width}x${exo.videoFormat?.height}")
                                fireFailureOnce("Video too slow")
                                break
                            }
                            slowWindowStartMs = now
                            slowWindowFrames = renderedFrames
                        }
                    }
                    val progressed = if (hasVideo && exo.videoDecoderCounters != null) {
                        // (frames only: a frozen picture with the sound still going moves the position on, and was never caught)
                        renderedFrames > lastRenderedFrames
                    } else if (hasVideo) {
                        pos > lastObservedPos
                    } else {
                        pos > lastObservedPos
                    }
                    if (progressed) {
                        lastRenderedFrames = renderedFrames
                        lastObservedPos = pos
                        stuckSinceMs = 0L
                    } else {
                        if (stuckSinceMs == 0L) stuckSinceMs = now
                        if (now - stuckSinceMs > STALL_TIMEOUT_MS) {
                            android.util.Log.i("lumos-live", "froze: video=${exo.videoFormat?.sampleMimeType} ${exo.videoFormat?.width}x${exo.videoFormat?.height} codec=${exo.videoFormat?.codecs} " +
                                "rendered=$renderedFrames dropped=${exo.videoDecoderCounters?.droppedBufferCount} audio=${exo.audioFormat?.sampleMimeType} pos=$pos buffered=$buffered")
                            fireFailureOnce("Playback froze")
                            break
                        }
                    }
                }
            }
        }
    }

    /**
     * Synchronously stops and releases the active [ExoPlayer] and cancels all inflight OkHttp calls.
     * Must run before tuning another stream or backup feed so single-connection providers never reject.
     */
    fun releaseCurrentSync() {
        generation++
        watchdogJob?.cancel()
        watchdogJob = null
        attachedView?.player = null
        player?.let { exo ->
            runCatching { exo.stop() }
            runCatching { exo.release() }
        }
        player = null
        soundProcessor = null
        okHttpClient.dispatcher.cancelAll()
        _isBuffering.value = false
        _isPaused.value = false
        _positionMs.value = 0L
        _durationMs.value = 0L
        _bufferedMs.value = 0L
        _isSeekable.value = false
        _videoInfo.value = LiveVideoInfo()
        _audioTracks.value = emptyList()
        _subtitleTracks.value = emptyList()
    }

    fun togglePause() {
        val exo = player ?: return
        exo.playWhenReady = !exo.playWhenReady
        _isPaused.value = !exo.playWhenReady
    }

    fun setPaused(paused: Boolean) {
        val exo = player ?: return
        exo.playWhenReady = !paused
        _isPaused.value = paused
    }

    fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        val dur = exo.duration.takeIf { it != C.TIME_UNSET && it > 0L }
        val target = (exo.currentPosition + deltaMs).let { raw ->
            if (dur != null) raw.coerceIn(0L, dur) else raw.coerceAtLeast(0L)
        }
        exo.seekTo(target)
        _positionMs.value = target
    }

    fun seekTo(ms: Long) {
        val exo = player ?: return
        val dur = exo.duration.takeIf { it != C.TIME_UNSET && it > 0L }
        val target = if (dur != null) ms.coerceIn(0L, dur) else ms.coerceAtLeast(0L)
        exo.seekTo(target)
        _positionMs.value = target
    }

    fun selectAudioTrack(groupIndex: Int, trackIndex: Int) {
        val exo = player ?: return
        val group = exo.currentTracks.groups.getOrNull(groupIndex) ?: return
        if (group.type != C.TRACK_TYPE_AUDIO || trackIndex !in 0 until group.length) return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            .build()
    }

    fun selectSubtitleTrack(groupIndex: Int, trackIndex: Int) {
        val exo = player ?: return
        val group = exo.currentTracks.groups.getOrNull(groupIndex) ?: return
        if (group.type != C.TRACK_TYPE_TEXT || trackIndex !in 0 until group.length) return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            .build()
    }

    fun disableSubtitles() {
        val exo = player ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    fun cycleSoundMode() {
        val modes = SoundMode.MODES
        val idx = modes.indexOfFirst { it === currentSoundMode }.let { if (it < 0) 0 else it }
        val next = modes[(idx + 1) % modes.size]
        currentSoundMode = next
        soundProcessor?.mode = next
        _soundModeLabel.value = next.label
    }

    fun cycleZoom() {
        val modes = LiveZoomMode.entries
        val next = modes[(_zoomMode.value.ordinal + 1) % modes.size]
        _zoomMode.value = next
        attachedView?.resizeMode = next.resizeMode
    }

    fun release() {
        releaseCurrentSync()
        attachedView = null
        scope.cancel()
    }

    private fun buildMediaSource(url: String, headers: Map<String, String>): MediaSource {
        val ownAgent = headers.keys.any { it.equals("User-Agent", ignoreCase = true) }
        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(if (ownAgent) null else DEFAULT_USER_AGENT)
            .setDefaultRequestProperties(headers)

        val uri = Uri.parse(url)
        val mediaItem = MediaItem.Builder().setUri(uri).build()
        val path = uri.path?.lowercase().orEmpty()
        val query = uri.query?.lowercase().orEmpty()

        return when {
            path.endsWith(".m3u8") || query.contains("output=m3u8") || query.contains("type=m3u8") ->
                HlsMediaSource.Factory(dataSourceFactory)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(mediaItem)

            path.endsWith(".mpd") ->
                DashMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(mediaItem)

            path.endsWith(".ts") || isExtensionlessPath(path) ->
                ProgressiveMediaSource.Factory(dataSourceFactory, tsExtractorsFactory)
                    .createMediaSource(mediaItem)

            else ->
                DefaultMediaSourceFactory(dataSourceFactory, tsExtractorsFactory)
                    .createMediaSource(mediaItem)
        }
    }

    private fun isExtensionlessPath(path: String): Boolean {
        val lastSeg = path.substringAfterLast('/')
        return lastSeg.isNotEmpty() && !lastSeg.contains('.')
    }

    private fun refreshVideoInfo(exo: ExoPlayer) {
        val video = exo.videoFormat
        val audio = exo.audioFormat
        val hdr = hdrName(exo, video).removePrefix(" · ")
        val videoCodec = video?.sampleMimeType?.substringAfter('/')?.removePrefix("x-")?.uppercase().orEmpty()
        val audioLabel = formatAudioCodec(audio)
        _videoInfo.value = LiveVideoInfo(
            width = video?.width?.coerceAtLeast(0) ?: 0,
            height = video?.height?.coerceAtLeast(0) ?: 0,
            fps = video?.frameRate?.takeIf { it > 0f } ?: 0f,
            hdrLabel = hdr,
            videoCodec = videoCodec,
            bitrateBps = video?.bitrate?.takeIf { it > 0 } ?: 0,
            audioCodec = audioLabel,
            audioChannels = audio?.channelCount?.takeIf { it > 0 } ?: 0,
        )
    }

    private fun refreshTrackStates(exo: ExoPlayer) {
        val groups = exo.currentTracks.groups
        val audio = mutableListOf<LiveTrackOption>()
        val subs = mutableListOf<LiveTrackOption>()

        groups.forEachIndexed { groupIdx, group ->
            when (group.type) {
                C.TRACK_TYPE_AUDIO -> {
                    for (i in 0 until group.length) {
                        val fmt = group.getTrackFormat(i)
                        val name = trackLabel(fmt, audio.size + 1)
                        val detail = buildAudioDetail(fmt)
                        audio += LiveTrackOption(
                            groupIndex = groupIdx,
                            trackIndex = i,
                            label = name,
                            language = fmt.language,
                            detail = detail,
                            selected = group.isTrackSelected(i),
                            supported = group.isTrackSupported(i),
                        )
                    }
                }
                C.TRACK_TYPE_TEXT -> {
                    for (i in 0 until group.length) {
                        val fmt = group.getTrackFormat(i)
                        if (!group.isTrackSupported(i)) continue
                        subs += LiveTrackOption(
                            groupIndex = groupIdx,
                            trackIndex = i,
                            label = trackLabel(fmt, subs.size + 1),
                            language = fmt.language,
                            detail = null,
                            selected = group.isTrackSelected(i),
                            supported = true,
                        )
                    }
                }
            }
        }

        _audioTracks.value = audio
        _subtitleTracks.value = subs
    }

    private fun trackLabel(format: Format, ordinal: Int): String =
        format.label?.takeIf { it.isNotBlank() }
            ?: format.language?.let { Locale.forLanguageTag(it).displayLanguage }?.takeIf { it.isNotBlank() }
            ?: "Track $ordinal"

    private fun buildAudioDetail(format: Format): String? {
        val parts = buildList {
            formatAudioCodec(format).takeIf { it.isNotEmpty() }?.let(::add)
            when (format.channelCount) {
                1 -> add("Mono")
                2 -> add("Stereo")
                6 -> add("5.1")
                8 -> add("7.1")
                in 3..99 -> add("${format.channelCount}ch")
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun formatAudioCodec(format: Format?): String {
        val mime = format?.sampleMimeType ?: return ""
        return when (mime) {
            MimeTypes.AUDIO_AC3 -> "AC3"
            MimeTypes.AUDIO_E_AC3 -> "EAC3"
            MimeTypes.AUDIO_E_AC3_JOC -> "EAC3 Atmos"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L2 -> "MP2"
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "DTS"
            MimeTypes.AUDIO_TRUEHD -> "TrueHD"
            else -> mime.substringAfter('/').removePrefix("x-").uppercase()
        }
    }

    private fun describeError(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
            val code = generateSequence(error.cause) { it.cause }
                .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>()
                .firstOrNull()?.responseCode
            when (code) {
                // what Xtream panels answer when the line's connections are all in use
                429, 458, 509, 511 -> REFUSED_FULL
                401, 403, 423 -> REFUSED_LOGIN
                else -> "Stream refused by server" + (code?.let { " ($it)" } ?: "")
            }
        }
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Stream connection timed out"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "Unsupported video/audio format"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "Invalid stream format"
        else -> error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')
    }

    companion object {
        // a player name every IPTV panel knows (some refuse names they don't recognise)
        private const val DEFAULT_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"
        /** The provider says all of the line's connections are in use: its other feeds would say the same */
        const val REFUSED_FULL = "All of this provider's connections are in use. Another TV may be watching it."
        /** The provider refused the login itself */
        const val REFUSED_LOGIN = "The provider refused this login. Check it in Settings → Live TV."
        private const val STALL_TIMEOUT_MS = 6_000L
        private const val INITIAL_TUNE_TIMEOUT_MS = 15_000L
    }
}
