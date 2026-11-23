package lumos.iptv

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Configured IPTV source provider (Xtream Codes, M3U playlist, or Stalker/Ministra portal). */
@Serializable
sealed interface IptvSource {
    val id: String
    val name: String
    val enabled: Boolean
    val maxConnections: Int
    val userAgent: String?
    val epgUrl: String?
    val priority: Int

    @Serializable
    @SerialName("xtream")
    data class Xtream(
        override val id: String,
        override val name: String,
        override val enabled: Boolean = true,
        override val maxConnections: Int = 1,
        override val userAgent: String? = null,
        override val epgUrl: String? = null,
        override val priority: Int = 0,
        val serverUrl: String,
        val username: String,
        val password: String,
        val outputFormat: String = "ts",
    ) : IptvSource

    @Serializable
    @SerialName("m3u")
    data class M3u(
        override val id: String,
        override val name: String,
        override val enabled: Boolean = true,
        override val maxConnections: Int = 1,
        override val userAgent: String? = null,
        override val epgUrl: String? = null,
        override val priority: Int = 0,
        val url: String? = null,
        val inlineContent: String? = null,
    ) : IptvSource

    @Serializable
    @SerialName("stalker")
    data class Stalker(
        override val id: String,
        override val name: String,
        override val enabled: Boolean = true,
        override val maxConnections: Int = 1,
        override val userAgent: String? = null,
        override val epgUrl: String? = null,
        override val priority: Int = 0,
        val portalUrl: String,
        val macAddress: String,
        val serialNumber: String? = null,
        val deviceId: String? = null,
    ) : IptvSource
}

/** Provider subscription status and connection allowance. */
@Serializable
data class SourceAccountInfo(
    val sourceId: String,
    val status: String,
    val activeConnections: Int,
    val maxConnections: Int,
    val expiresAtMs: Long?,
    val serverTimezone: String?,
)

/** Catch-up URL generation strategy supported by a stream. */
@Serializable
enum class CatchupMode {
    XTREAM,
    DEFAULT,
    APPEND,
    SHIFT,
    FLUSSONIC,
    XC,
    STALKER,
}

/** Catch-up archive capability metadata attached to a [StreamSource]. */
@Serializable
data class CatchupConfig(
    val mode: CatchupMode,
    val days: Int,
    val sourceTemplate: String? = null,
    val streamId: String? = null,
)

/** Ordered display categories for canonical live channels. */
@Serializable
enum class ChannelCategory(val label: String, val order: Int) {
    FAVOURITES("Favourites", 0),
    TERRESTRIAL("UK TV", 1),
    ENTERTAINMENT("Entertainment", 2),
    SPORTS("Sports", 3),
    MOVIES("Movies", 4),
    NEWS("News", 5),
    DOCUMENTARY("Documentaries", 6),
    KIDS("Kids", 7),
    MUSIC("Music", 8),
    EVENTS("Live Events", 9),
    IRELAND("Ireland", 10),
    US_ENGLISH("US English", 11),
    OTHER("Other", 12),
}

/** Parsed technical characteristics of an IPTV stream variant. */
@Serializable
data class StreamQuality(
    val height: Int = 0,
    val fps: Int? = null,
    val codec: String? = null,
    val hdr: Boolean = false,
    val isRaw: Boolean = false,
    val isBackup: Boolean = false,
    val region: String? = null,
) {
    /** Compact display label summarizing resolution, high frame rate, and HDR. */
    val badge: String
        get() {
            val base = when {
                height >= 2160 -> "4K"
                height >= 1080 -> "1080p"
                height >= 720 -> "HD"
                else -> "SD"
            }
            return buildList {
                add(base)
                if (fps != null && fps > 0) add("${fps}fps")
                if (hdr) add("HDR")
            }.joinToString(" · ")
        }
}

/** Raw channel stream variant returned from a single IPTV source. */
@Serializable
data class StreamSource(
    val id: String,
    val sourceId: String,
    val sourceName: String,
    val rawName: String,
    val groupTitle: String,
    val tvgId: String?,
    val logoUrl: String?,
    val streamUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val quality: StreamQuality,
    val catchup: CatchupConfig? = null,
    val requiresResolve: Boolean = false,
    val stalkerCmd: String? = null,
    val xtreamStreamId: String? = null,
)

/** Deduplicated, normalized channel grouping one or more ranked [StreamSource] variants. */
@Serializable
data class CanonicalChannel(
    val id: String,
    val number: Int?,
    val name: String,
    val category: ChannelCategory,
    val logoUrl: String?,
    val epgId: String?,
    val streams: List<StreamSource>,
    val isEventChannel: Boolean = false,
    val hasCatchup: Boolean = false,
    val maxCatchupDays: Int = 0,
) {
    init {
        require(name.isNotBlank())
    }
}

/** Single EPG programme listing in UTC epoch milliseconds. */
@Serializable
data class EpgProgramme(
    val id: String,
    val channelId: String,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val category: String? = null,
    val startMs: Long,
    val endMs: Long,
    val iconUrl: String? = null,
    val isLiveSport: Boolean = false,
    val sportTag: String? = null,
    val hasCatchup: Boolean = false,
) {
    /** True when the programme is currently airing at [nowMs]. */
    fun isOnAt(nowMs: Long): Boolean = nowMs >= startMs && nowMs < endMs

    /** Normalized `[0f, 1f]` playback progress at [nowMs]. */
    fun progressAt(nowMs: Long): Float {
        val duration = endMs - startMs
        if (duration <= 0L) return 0f
        return ((nowMs - startMs).toDouble() / duration.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    /** Whole minutes remaining until [endMs] at [nowMs], rounded up when partial minutes remain. */
    fun remainingMinutesAt(nowMs: Long): Int {
        if (nowMs >= endMs) return 0
        val remainingMs = endMs - maxOf(nowMs, startMs)
        return ((remainingMs + 59_999L) / 60_000L).toInt().coerceAtLeast(0)
    }

    /** True when this finished programme falls inside the [catchupDays] retention window at [nowMs]. */
    fun isCatchupEligible(nowMs: Long, catchupDays: Int): Boolean {
        if (catchupDays <= 0 || endMs <= startMs) return false
        if (endMs > nowMs) return false
        val cutoffMs = nowMs - catchupDays.toLong() * 86_400_000L
        return endMs >= cutoffMs
    }
}

/** Rolling failure/backoff state and start latency metrics for a stream variant. */
@Serializable
data class StreamHealth(
    val streamId: String,
    val failures: Int = 0,
    val lastFailureMs: Long = 0L,
    val lastSuccessMs: Long = 0L,
    val cooldownUntilMs: Long = 0L,
    val averageStartMs: Long? = null,
)

/** Curated TV Guide channel metadata from Cloudflare UK EPG or built-in US Sports guide. */
@Serializable
data class GuideChannel(
    val id: String,
    val name: String,
    val category: String = "",
    val number: Int? = null,
    val logoUrl: String? = null,
    val mainGuide: Boolean = true,
    val sportsGuide: Boolean = false,
)

/** Saved sports team tracked by a viewer profile for schedule and live match surfacing. */
@Serializable
data class SavedSportsTeam(
    val teamId: String,
    val sport: String,
    val league: String,
    val leagueName: String = "",
    val name: String,
    val shortName: String? = null,
    val logoUrl: String? = null,
)

/** Lifecycle state of a scheduled sports fixture. */
@Serializable
enum class FixtureStatus {
    UPCOMING,
    LIVE,
    FINAL,
}

/** One competing team or athlete side in a [SportsFixture]. */
@Serializable
data class SportsTeamSide(
    val id: String? = null,
    val name: String,
    val shortName: String? = null,
    val abbreviation: String? = null,
    val logoUrl: String? = null,
    val score: String? = null,
    val winner: Boolean = false,
)

/** Normalized sports fixture with optional UK TV broadcaster cross-match from Cloudflare D1 EPG. */
@Serializable
data class SportsFixture(
    val id: String,
    val sport: String,
    val league: String,
    val leagueName: String,
    val name: String,
    val shortName: String? = null,
    val startMs: Long,
    val status: FixtureStatus,
    val statusDetail: String? = null,
    val clock: String? = null,
    val period: Int? = null,
    val venue: String? = null,
    val home: SportsTeamSide? = null,
    val away: SportsTeamSide? = null,
    val broadcasts: List<String> = emptyList(),
    val ukChannelId: String? = null,
    val ukChannelName: String? = null,
)

/** Split live, recent past, and upcoming fixtures for a [SavedSportsTeam]. */
@Serializable
data class TeamScheduleGroup(
    val team: SavedSportsTeam,
    val live: List<SportsFixture> = emptyList(),
    val past: List<SportsFixture> = emptyList(),
    val upcoming: List<SportsFixture> = emptyList(),
)

/** Guide channel paired with the user's linked IPTV [CanonicalChannel] and top-ranked [StreamSource]. */
@Serializable
data class GuideChannelRow(
    val guideChannel: GuideChannel,
    val linkedChannel: CanonicalChannel? = null,
    val bestStream: StreamSource? = null,
    val logoUrl: String? = guideChannel.logoUrl?.takeIf { it.isNotBlank() } ?: linkedChannel?.logoUrl,
    val hasCatchup: Boolean = linkedChannel?.hasCatchup ?: false,
    val qualityBadge: String? = bestStream?.quality?.badge,
    val isPlayable: Boolean = bestStream != null,
)

/** How a [SportsStreamResolver] candidate stream was matched to a [SportsFixture]. */
@Serializable
enum class MatchReason {
    UK_BROADCASTER,
    US_BROADCASTER,
    DIRECT_EVENT,
}

/** Playable IPTV stream candidate resolved for a [SportsFixture]. */
@Serializable
data class ResolvedFixtureStream(
    val channel: CanonicalChannel,
    val stream: StreamSource,
    val reason: MatchReason,
    val matchedLabel: String,
)

/** Optional TMDB enrichment metadata (16:9 backdrop and description) for an EPG programme. */
@Serializable
data class EpgEnrichment(
    val backdrop: String? = null,
    val description: String? = null,
    val tmdbId: Int? = null,
    val mediaType: String? = null,
)

/** Conflict warning when an IPTV source already has active viewers or exhausted connection slots. */
@Serializable
data class ActiveViewerConflict(
    val sourceId: String,
    val sourceName: String,
    val otherDeviceName: String? = null,
    val otherChannelName: String? = null,
    val activeConnections: Int? = null,
    val maxConnections: Int? = null,
    val reason: String,
)

/** Active viewer entry returned by Cloudflare sync `/iptv/presence`. */
@Serializable
data class PresenceViewer(
    val deviceName: String,
    val channelName: String? = null,
    val at: Long = 0L,
)

/** Per-profile live TV preferences, hidden items, pinned streams, EPG overrides, and saved sports teams. */
@Serializable
data class IptvProfilePrefs(
    val favourites: List<String> = emptyList(),
    val hiddenChannels: Set<String> = emptySet(),
    val hiddenCategories: Set<String> = emptySet(),
    val recentChannels: List<String> = emptyList(),
    val preferredStreamByChannel: Map<String, String> = emptyMap(),
    val epgOverrides: Map<String, String> = emptyMap(),
    val showNonUk: Boolean = false,
    val favouriteTeams: List<SavedSportsTeam> = emptyList(),
    val hideSportsScores: Boolean = false,
)

/** Concrete playable URL and HTTP headers ready for the media player. */
data class ResolvedStream(
    val url: String,
    val headers: Map<String, String>,
    val streamSource: StreamSource,
    val isCatchup: Boolean = false,
)

/** Categorized live TV and guide search matches. */
data class LiveSearchResults(
    val channels: List<CanonicalChannel>,
    val onNow: List<Pair<CanonicalChannel, EpgProgramme>>,
    val upcoming: List<Pair<CanonicalChannel, EpgProgramme>>,
    val catchup: List<Pair<CanonicalChannel, EpgProgramme>>,
)

/** State machine for live TV tuning, playback, buffering, and failover. */
sealed interface LivePlaybackState {
    data object Idle : LivePlaybackState
    data class Resolving(val channel: CanonicalChannel) : LivePlaybackState
    data class Tuning(val channel: CanonicalChannel, val stream: StreamSource, val attempt: Int, val total: Int) : LivePlaybackState
    data class Playing(val channel: CanonicalChannel, val stream: StreamSource, val isCatchup: Boolean) : LivePlaybackState
    data class Buffering(val channel: CanonicalChannel, val stream: StreamSource, val elapsedMs: Long) : LivePlaybackState
    data class FailingOver(val channel: CanonicalChannel, val failedStream: StreamSource, val nextStream: StreamSource, val reason: String) : LivePlaybackState
    data class Unavailable(val channel: CanonicalChannel, val reason: String) : LivePlaybackState
}
