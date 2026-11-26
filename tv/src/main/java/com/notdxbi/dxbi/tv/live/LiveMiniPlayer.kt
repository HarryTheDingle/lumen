package com.notdxbi.dxbi.tv.live

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import lumos.iptv.CanonicalChannel
import lumos.iptv.EpgProgramme
import lumos.iptv.StreamSource

/**
 * Holds a live or catch-up [LiveExoEngine] across Back navigation from [LivePlayerActivity]
 * into the corner mini-player inside Live TV, enabling zero-downtime fullscreen resume and
 * maintaining presence heartbeats until stopped or handed back.
 */
object LiveMiniPlayer {

    /** Kept playback session powering the floating corner mini-player. */
    data class Session(
        val engine: LiveExoEngine,
        val channel: CanonicalChannel,
        val activeStream: StreamSource?,
        val catchupProgramme: EpgProgramme?,
        val zapChannelIds: List<String>,
    )

    private const val HEARTBEAT_INTERVAL_MS = 45_000L
    private const val RECENT_STOP_GRACE_MS = 15_000L
    private const val MAX_RETRIES = 5

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    private var heartbeatJob: Job? = null
    private var lastStore: LiveTvStore? = null

    @Volatile
    var ownActiveSourceId: String? = null
        private set

    @Volatile
    private var lastStoppedSourceId: String? = null

    @Volatile
    var lastLocalStopAtMs: Long = 0L
        private set

    /**
     * How many Live TV screens are showing right now. The corner mini-player lives on that screen; when the full-screen
     * player was opened from anywhere else (a reminder, "Back to the channel") there is no corner for it to go to.
     */
    @Volatile
    var hosts: Int = 0

    /** True while transitioning into [LivePlayerActivity] so `ON_STOP` on the host preserves playback. */
    @Volatile
    var openingFullscreen: Boolean = false

    /** Records that this device is actively streaming on [sourceId]. */
    fun markOwnSourceActive(sourceId: String?) {
        if (sourceId.isNullOrBlank()) return
        ownActiveSourceId = sourceId
    }

    /** Records that this device recently released a stream on [sourceId]. */
    fun markOwnSourceStopped(sourceId: String?) {
        val resolved = sourceId?.takeIf { it.isNotBlank() } ?: ownActiveSourceId
        if (!resolved.isNullOrBlank()) {
            lastStoppedSourceId = resolved
            lastLocalStopAtMs = SystemClock.elapsedRealtime()
        }
        if (sourceId == null || ownActiveSourceId == sourceId) {
            ownActiveSourceId = null
        }
    }

    /**
     * True when this device is currently streaming on [sourceId] or released it within the
     * 15-second provider socket close grace window.
     */
    fun isOwnSourceActiveRecently(sourceId: String): Boolean {
        if (sourceId.isBlank()) return false
        if (ownActiveSourceId == sourceId) return true
        if (lastStoppedSourceId == sourceId && lastLocalStopAtMs > 0L) {
            val elapsed = SystemClock.elapsedRealtime() - lastLocalStopAtMs
            return elapsed in 0L..RECENT_STOP_GRACE_MS
        }
        return false
    }

    /**
     * Keeps [newSession] running in the corner mini-player, releasing any prior distinct engine
     * and maintaining a 45-second presence heartbeat while open.
     */
    fun keep(newSession: Session, store: LiveTvStore?) {
        val oldToRelease: Session?
        synchronized(lock) {
            val prev = _session.value
            oldToRelease = if (prev != null && prev.engine !== newSession.engine) prev else null
            heartbeatJob?.cancel()
            heartbeatJob = null
            if (store != null) lastStore = store
            _session.value = newSession
        }

        val effectiveStore = store ?: lastStore ?: LiveTvRuntime.currentStore()
        // (a live stream stalls now and then, a dropped connection or a provider cutting it off after a while: the corner
        // player tunes the same feed again, a few times in a row, before it gives up; it used to stop at the first stall)
        var retries = 0
        // (feeds already tried here: a feed that keeps buffering, or stalls a second time, is left for the channel's next
        // one, as the full-screen player does, rather than tuned again)
        val tried = HashSet<String>().apply { newSession.activeStream?.id?.let(::add) }
        newSession.engine.onStallFailover = { reason ->
            scope.launch(Dispatchers.Main) {
                if (_session.value?.engine !== newSession.engine) return@launch
                val current = _session.value ?: return@launch
                val moveOn = current.catchupProgramme == null && effectiveStore != null &&
                    (reason == LiveExoEngine.KEEPS_BUFFERING || retries >= 1)
                val next = if (moveOn) effectiveStore!!.rankedStreamsFor(current.channel).firstOrNull { it.id !in tried } else null
                if (next != null && retries < MAX_RETRIES) {
                    retries++
                    tried += next.id
                    current.activeStream?.id?.let { effectiveStore!!.recordStreamFailure(it) }
                    android.util.Log.i("lumos-live", "corner player stalled ($reason): next feed ${next.id} ($retries of $MAX_RETRIES)")
                    val resolved = runCatching { kotlinx.coroutines.withContext(Dispatchers.IO) { effectiveStore!!.resolveLive(current.channel, next) } }.getOrNull()
                    if (resolved == null || _session.value?.engine !== newSession.engine) { stop(effectiveStore); return@launch }
                    _session.value = current.copy(activeStream = next)
                    newSession.engine.tune(
                        resolved,
                        onReady = { retries = 0 },
                        onFail = { scope.launch(Dispatchers.Main) { if (_session.value?.engine === newSession.engine) stop(effectiveStore) } },
                    )
                    return@launch
                }
                val again = newSession.engine.lastTuned
                if (again != null && retries < MAX_RETRIES) {
                    retries++
                    android.util.Log.i("lumos-live", "corner player stalled ($reason): tuning again ($retries of $MAX_RETRIES)")
                    delay(1_000L * retries)
                    if (_session.value?.engine === newSession.engine) {
                        newSession.engine.tune(
                            again,
                            onReady = { retries = 0 },
                            onFail = { scope.launch(Dispatchers.Main) { if (_session.value?.engine === newSession.engine) stop(effectiveStore) } },
                        )
                    }
                } else {
                    stop(effectiveStore)
                }
            }
        }

        if (oldToRelease != null) {
            val oldSourceId = oldToRelease.activeStream?.sourceId
            oldToRelease.engine.release()
            if (oldSourceId != null && oldSourceId != newSession.activeStream?.sourceId) {
                effectiveStore?.notifyPresenceStop(oldSourceId)
            }
        }

        val sourceId = newSession.activeStream?.sourceId
        markOwnSourceActive(sourceId)
        if (!sourceId.isNullOrBlank() && effectiveStore != null) {
            synchronized(lock) {
                heartbeatJob = scope.launch {
                    while (isActive) {
                        delay(HEARTBEAT_INTERVAL_MS)
                        if (!isActive) break
                        effectiveStore.notifyPresenceHeartbeat(sourceId, newSession.channel.name)
                    }
                }
            }
        }
    }

    /**
     * Returns and clears the kept session if it matches [channelId] and [catchupProgramme]?.id;
     * otherwise stops and releases any mismatched kept session and returns null.
     */
    fun takeIfSame(channelId: String, catchupProgramme: EpgProgramme?): Session? {
        val matched: Session?
        val shouldStopOld: Boolean
        synchronized(lock) {
            val current = _session.value ?: return null
            val sameChannel = current.channel.id == channelId &&
                current.catchupProgramme?.id == catchupProgramme?.id
            if (sameChannel) {
                heartbeatJob?.cancel()
                heartbeatJob = null
                _session.value = null
                matched = current
                shouldStopOld = false
            } else {
                matched = null
                shouldStopOld = true
            }
        }
        if (shouldStopOld) {
            stop(lastStore ?: LiveTvRuntime.currentStore())
        }
        return matched
    }

    /** Stops and releases the kept mini-player session and reports presence stop. */
    fun stop(store: LiveTvStore? = null) {
        val old: Session?
        val effectiveStore: LiveTvStore?
        synchronized(lock) {
            heartbeatJob?.cancel()
            heartbeatJob = null
            old = _session.value
            _session.value = null
            if (store != null) lastStore = store
            effectiveStore = store ?: lastStore ?: LiveTvRuntime.currentStore()
        }
        if (old != null) {
            val sourceId = old.activeStream?.sourceId
            old.engine.release()
            markOwnSourceStopped(sourceId)
            if (!sourceId.isNullOrBlank()) {
                effectiveStore?.notifyPresenceStop(sourceId)
            }
        }
    }
}
