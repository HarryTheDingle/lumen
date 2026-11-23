package lumos.iptv

import kotlinx.serialization.encodeToString

/**
 * Tracks stream failures with exponential backoff cooldowns (`30s -> 2m -> 10m -> 30m`)
 * and rolling start-latency telemetry across tuning attempts.
 */
class StreamHealthTracker(initial: Map<String, StreamHealth> = emptyMap()) {
    private val entries = initial.toMutableMap()

    /** Records a tuning/playback failure for [streamId] and applies the next backoff tier. */
    fun recordFailure(streamId: String, nowMs: Long): StreamHealth {
        val prev = entries[streamId]
        val failures = (prev?.failures ?: 0) + 1
        val cooldownMs = backoffDurationMs(failures)
        val updated = StreamHealth(
            streamId = streamId,
            failures = failures,
            lastFailureMs = nowMs,
            lastSuccessMs = prev?.lastSuccessMs ?: 0L,
            cooldownUntilMs = nowMs + cooldownMs,
            averageStartMs = prev?.averageStartMs,
        )
        entries[streamId] = updated
        return updated
    }

    /** Clears failure cooldown for [streamId] and updates rolling start latency when supplied. */
    fun recordSuccess(streamId: String, nowMs: Long, startLatencyMs: Long? = null): StreamHealth {
        val prev = entries[streamId]
        val avgStart = when {
            startLatencyMs == null || startLatencyMs < 0L -> prev?.averageStartMs
            prev?.averageStartMs == null -> startLatencyMs
            else -> ((prev.averageStartMs * 3L) + startLatencyMs) / 4L
        }
        val updated = StreamHealth(
            streamId = streamId,
            failures = 0,
            lastFailureMs = prev?.lastFailureMs ?: 0L,
            lastSuccessMs = nowMs,
            cooldownUntilMs = 0L,
            averageStartMs = avgStart,
        )
        entries[streamId] = updated
        return updated
    }

    /** True while [streamId] remains inside its active failure cooldown window at [nowMs]. */
    fun isOnCooldown(streamId: String, nowMs: Long): Boolean {
        val h = entries[streamId] ?: return false
        return h.cooldownUntilMs > nowMs
    }

    fun get(streamId: String): StreamHealth? = entries[streamId]

    fun snapshot(): Map<String, StreamHealth> = entries.toMap()

    fun restore(snapshot: Map<String, StreamHealth>) {
        entries.clear()
        entries.putAll(snapshot)
    }

    fun toJson(): String = iptvJson.encodeToString(entries.toMap())

    companion object {
        private val BACKOFF_MS = longArrayOf(
            30_000L,
            120_000L,
            600_000L,
            1_800_000L,
        )

        fun backoffDurationMs(failures: Int): Long {
            if (failures <= 0) return 0L
            val index = (failures - 1).coerceAtMost(BACKOFF_MS.lastIndex)
            return BACKOFF_MS[index]
        }

        fun fromJson(rawJson: String): StreamHealthTracker =
            runCatching {
                val decoded = iptvJson.decodeFromString<Map<String, StreamHealth>>(rawJson)
                StreamHealthTracker(decoded)
            }.getOrElse { StreamHealthTracker() }
    }
}
