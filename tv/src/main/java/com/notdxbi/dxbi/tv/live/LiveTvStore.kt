package com.notdxbi.dxbi.tv.live

import kotlinx.coroutines.flow.first
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.notdxbi.dxbi.tv.phone.Engine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import lumos.iptv.ActiveViewerConflict
import lumos.iptv.CanonicalChannel
import lumos.iptv.ChannelCategory
import lumos.iptv.EpgCacheSnapshot
import lumos.iptv.EpgEnrichment
import lumos.iptv.EpgProgramme
import lumos.iptv.GuideChannel
import lumos.iptv.GuideChannelRow
import lumos.iptv.GuideStreamLinker
import lumos.iptv.IptvCodec
import lumos.iptv.IptvProfilePrefs
import lumos.iptv.IptvRepository
import lumos.iptv.IptvSource
import lumos.iptv.IptvWarmSnapshot
import lumos.iptv.LiveSearchResults
import lumos.iptv.ResolvedFixtureStream
import lumos.iptv.ResolvedStream
import lumos.iptv.SavedSportsTeam
import lumos.iptv.SourceLoadReport
import lumos.iptv.SportsFixture
import lumos.iptv.SportsStreamResolver
import lumos.iptv.StreamHealthTracker
import lumos.iptv.StreamRanker
import lumos.iptv.StreamSource
import lumos.iptv.TeamScheduleGroup
import lumos.iptv.UkChannelRegistry
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Active QR pairing session linking a phone browser to this TV's IPTV sources. */
data class QrSetupPairing(
    val code: String,
    val setupUrl: String,
    val qrImageUrl: String,
    val expiresAtMs: Long,
)

/** Loading, refresh, and EPG freshness state exposed by [LiveTvStore]. */
data class LiveUiState(
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val epgUpdatedAtMs: Long = 0L,
    val lastLoadedAtMs: Long = 0L,
)

/**
 * Lazy singleton holder for [LiveTvStore].
 * Zero IPTV work executes at app startup; initialization begins only when Live TV or
 * Settings -> Live TV is opened.
 */
object LiveTvRuntime {
    @Volatile
    private var instance: LiveTvStore? = null

    /** Returns the lazily created [LiveTvStore] singleton bound to [engine]. */
    fun store(context: Context, engine: Engine): LiveTvStore {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: LiveTvStore(context.applicationContext, engine).also { instance = it }
        }
    }

    /** Returns the initialized [LiveTvStore], or null if Live TV has not been opened yet. */
    fun currentStore(): LiveTvStore? = instance
}

/**
 * Coordinates synced IPTV sources, profile preferences, warm disk caches, multi-source playlist
 * normalization, UK + provider EPG ingestion, stream health telemetry, and QR phone setup.
 */
class LiveTvStore(
    context: Context,
    private val engine: Engine,
) {
    private val appContext = context.applicationContext
    private val cacheFile = File(appContext.filesDir, "iptv-cache.json")
    private val cacheMetaFile = File(appContext.filesDir, "iptv-cache.meta")
    private val epgCacheFile = File(appContext.filesDir, "iptv-epg-cache.json")
    private val healthFile = File(appContext.filesDir, "iptv-health.json")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    private val epgLock = Any()
    private val healthLock = Any()
    private val warmLoaded = AtomicBoolean(false)

    @Volatile
    private var repo: IptvRepository = engine.core.newIptvRepository()
    @Volatile
    private var hasLiveStreamsLoaded = false
    @Volatile
    private var lastBandwidthBps: Long? = null
    @Volatile
    private var activeViewer: String? = null
    @Volatile
    private var lastUkEpgFetchedAtMs: Long = 0L
    @Volatile
    private var cachedShowNonUk: Boolean? = null

    private val pendingHealthOutcomes = mutableListOf<HealthEvent>()
    private val shortEpgLastFetchMs = ConcurrentHashMap<String, Long>()
    private val archiveEpgLastFetchMs = ConcurrentHashMap<String, Long>()
    private val inflightChannelEpg = ConcurrentHashMap.newKeySet<String>()
    private val enrichLastAttemptMs = ConcurrentHashMap<String, Long>()
    private val inflightEnrich = ConcurrentHashMap.newKeySet<String>()
    private val titleEnrichmentCache = ConcurrentHashMap<String, EpgEnrichment>()
    private val enrichLock = Any()
    private val derivedGeneration = AtomicLong(0L)
    private val sportsGeneration = AtomicLong(0L)
    private val teamsGeneration = AtomicLong(0L)
    private val fixtureResolutionGeneration = AtomicLong(0L)
    private var derivedEpgJob: Job? = null
    private var sportsJob: Job? = null
    private var teamsJob: Job? = null
    private var fixtureResolutionJob: Job? = null
    private var pairingJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _uiState = MutableStateFlow(LiveUiState())
    val uiState: StateFlow<LiveUiState> = _uiState.asStateFlow()

    private val _channels = MutableStateFlow<List<CanonicalChannel>>(emptyList())
    val channels: StateFlow<List<CanonicalChannel>> = _channels.asStateFlow()

    private val _categories = MutableStateFlow<List<Pair<ChannelCategory, Int>>>(emptyList())
    val categories: StateFlow<List<Pair<ChannelCategory, Int>>> = _categories.asStateFlow()

    private val _favourites = MutableStateFlow<List<CanonicalChannel>>(emptyList())
    val favourites: StateFlow<List<CanonicalChannel>> = _favourites.asStateFlow()

    private val _recents = MutableStateFlow<List<CanonicalChannel>>(emptyList())
    val recents: StateFlow<List<CanonicalChannel>> = _recents.asStateFlow()

    private val _liveSportsNow = MutableStateFlow<List<Pair<CanonicalChannel, EpgProgramme>>>(emptyList())
    val liveSportsNow: StateFlow<List<Pair<CanonicalChannel, EpgProgramme>>> = _liveSportsNow.asStateFlow()

    private val _nowNextByChannel = MutableStateFlow<Map<String, Pair<EpgProgramme?, EpgProgramme?>>>(emptyMap())
    val nowNextByChannel: StateFlow<Map<String, Pair<EpgProgramme?, EpgProgramme?>>> = _nowNextByChannel.asStateFlow()

    private val _mainGuideRows = MutableStateFlow<List<GuideChannelRow>>(emptyList())
    val mainGuideRows: StateFlow<List<GuideChannelRow>> = _mainGuideRows.asStateFlow()

    private val _sportsLiveRows = MutableStateFlow<List<GuideChannelRow>>(emptyList())
    val sportsLiveRows: StateFlow<List<GuideChannelRow>> = _sportsLiveRows.asStateFlow()

    private val _sportsFixtures = MutableStateFlow<List<SportsFixture>>(emptyList())
    val sportsFixtures: StateFlow<List<SportsFixture>> = _sportsFixtures.asStateFlow()

    private val _sportsLoading = MutableStateFlow(false)
    val sportsLoading: StateFlow<Boolean> = _sportsLoading.asStateFlow()

    private val _sportsError = MutableStateFlow<String?>(null)
    val sportsError: StateFlow<String?> = _sportsError.asStateFlow()

    private val _teamSchedules = MutableStateFlow<List<TeamScheduleGroup>>(emptyList())
    val teamSchedules: StateFlow<List<TeamScheduleGroup>> = _teamSchedules.asStateFlow()

    private val _resolvedFixtureStreams = MutableStateFlow<Map<String, List<ResolvedFixtureStream>>>(emptyMap())
    val resolvedFixtureStreams: StateFlow<Map<String, List<ResolvedFixtureStream>>> = _resolvedFixtureStreams.asStateFlow()

    private val _teamsLoading = MutableStateFlow(false)
    val teamsLoading: StateFlow<Boolean> = _teamsLoading.asStateFlow()

    private val _enrichments = MutableStateFlow<Map<String, EpgEnrichment>>(emptyMap())
    val enrichments: StateFlow<Map<String, EpgEnrichment>> = _enrichments.asStateFlow()

    private val _catchupCount = MutableStateFlow(0)
    val catchupCount: StateFlow<Int> = _catchupCount.asStateFlow()

    private val _sources = MutableStateFlow<List<IptvSource>>(emptyList())
    val sources: StateFlow<List<IptvSource>> = _sources.asStateFlow()

    private val _sourceReports = MutableStateFlow<List<SourceLoadReport>>(emptyList())
    val sourceReports: StateFlow<List<SourceLoadReport>> = _sourceReports.asStateFlow()

    private val _prefs = MutableStateFlow(IptvProfilePrefs())
    val prefs: StateFlow<IptvProfilePrefs> = _prefs.asStateFlow()

    private val _setupPairing = MutableStateFlow<QrSetupPairing?>(null)
    val setupPairing: StateFlow<QrSetupPairing?> = _setupPairing.asStateFlow()

    private val _epgVersion = MutableStateFlow(0L)
    val epgVersion: StateFlow<Long> = _epgVersion.asStateFlow()

    /** Restores warm disk caches immediately if needed and triggers background refresh when empty or stale. */
    fun ensureLoaded() {
        scope.launch {
            val firstLoad = warmLoaded.compareAndSet(false, true)
            if (firstLoad) {
                restoreWarmCaches()
            }

            val (syncedSources, syncedPrefs, viewerId) = readEngineState()
            val viewerChanged = activeViewer != null && activeViewer != viewerId
            activeViewer = viewerId
            _sources.value = syncedSources
            val hasGuideChannels = synchronized(epgLock) {
                repo.updateSources(syncedSources)
                repo.epg.guideChannels().isNotEmpty()
            }
            if (firstLoad || viewerChanged) {
                _prefs.value = syncedPrefs
                if (viewerChanged) {
                    applyPrefsLocally(syncedPrefs)
                } else {
                    publishChannelDerivedState()
                }
            } else if (_channels.value.isNotEmpty() || hasGuideChannels) {
                scheduleEpgDerivedRebuild()
            }

            val now = System.currentTimeMillis()
            val stale = _uiState.value.lastLoadedAtMs == 0L || (now - _uiState.value.lastLoadedAtMs) >= STALE_AFTER_MS
            val emptyWithSources = _channels.value.isEmpty() && syncedSources.any { it.enabled }
            val needsEpg = lastUkEpgFetchedAtMs == 0L || (now - lastUkEpgFetchedAtMs) >= EPG_STALE_AFTER_MS || !hasGuideChannels
            val cachedSourceIds = loadedChannelSourceIds(_channels.value)
            val enabledSourceIds = enabledSourceIds(syncedSources)
            val sourcesMismatch = cachedSourceIds != enabledSourceIds
            val showNonUkChanged = cachedShowNonUk == null || cachedShowNonUk != syncedPrefs.showNonUk

            if (emptyWithSources || stale || needsEpg || sourcesMismatch || showNonUkChanged) {
                runRefresh(forceEpg = needsEpg, skipIfFresh = true)
            }
        }
    }

    /** Refreshes synced IPTV sources, channels, UK guide, and provider EPG guides in parallel. */
    fun refreshAll(forceEpg: Boolean = false) {
        scope.launch {
            runRefresh(forceEpg = forceEpg)
        }
    }

    /** Adds or updates [source] in synced storage and refreshes the channel lineup. */
    suspend fun addSource(source: IptvSource) {
        val current = _sources.value.toMutableList()
        val idx = current.indexOfFirst { it.id == source.id }
        if (idx >= 0) current[idx] = source else current.add(source)
        persistSourcesAndRefresh(current)
    }

    /** Removes [sourceId] from synced storage and refreshes the channel lineup. */
    suspend fun removeSource(sourceId: String) {
        val current = _sources.value.filterNot { it.id == sourceId }
        persistSourcesAndRefresh(current)
    }

    /** Enables or disables [sourceId] in synced storage and refreshes the channel lineup. */
    suspend fun toggleSource(sourceId: String, enabled: Boolean) {
        val current = _sources.value.map { src ->
            if (src.id != sourceId) src else when (src) {
                is IptvSource.Xtream -> src.copy(enabled = enabled)
                is IptvSource.M3u -> src.copy(enabled = enabled)
                is IptvSource.Stalker -> src.copy(enabled = enabled)
            }
        }
        persistSourcesAndRefresh(current)
    }

    /** Starts a phone QR pairing flow and polls sync every 3s until sources arrive or the code expires. */
    fun startQrSetup() {
        pairingJob?.cancel()
        pairingJob = scope.launch {
            try {
                val resp = engine.core.startIptvSetup()
                val code = resp["code"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: error("Sync server did not return a pairing code")
                val path = resp["path"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: "/iptv?code=$code"
                val expiresAtMs = resp["expires"]?.jsonPrimitive?.longOrNull
                    ?: (System.currentTimeMillis() + 600_000L)
                val normalizedPath = if (path.startsWith("/")) path else "/$path"
                val setupUrl = "${engine.syncServerUrl.trimEnd('/')}$normalizedPath"
                val qrImageUrl = "https://api.qrserver.com/v1/create-qr-code/?size=360x360&margin=10&data=${Uri.encode(setupUrl)}"

                val (baselineRaw, baselineSources) = engine.withIptvCore { core ->
                    val raw = core.iptvSourcesJson()
                    raw to raw?.let(IptvCodec::decodeSources).orEmpty()
                }

                _setupPairing.value = QrSetupPairing(
                    code = code,
                    setupUrl = setupUrl,
                    qrImageUrl = qrImageUrl,
                    expiresAtMs = expiresAtMs,
                )

                while (isActive && System.currentTimeMillis() < expiresAtMs) {
                    delay(QR_POLL_INTERVAL_MS)
                    if (!isActive || _setupPairing.value?.code != code) break
                    val synced = engine.syncIptvNow()
                    if (!synced) continue

                    val (latestRaw, latestSources) = engine.withIptvCore { core ->
                        val raw = core.iptvSourcesJson()
                        raw to raw?.let(IptvCodec::decodeSources).orEmpty()
                    }
                    val countGrew = latestSources.size > baselineSources.size
                    val changedNonEmpty = latestRaw != null && latestRaw != baselineRaw && latestSources.isNotEmpty()
                    if (countGrew || changedNonEmpty) {
                        _sources.value = latestSources
                        _setupPairing.value = null
                        runRefresh(forceEpg = false)
                        return@launch
                    }
                }
                if (_setupPairing.value?.code == code) {
                    _setupPairing.value = null
                }
            } catch (e: Throwable) {
                Log.w(TAG, "QR setup failed: ${e.message ?: e.javaClass.simpleName}")
                _setupPairing.value = null
                _uiState.value = _uiState.value.copy(
                    error = e.message ?: "Unable to start QR pairing. Check you are signed in to Lumen.",
                )
            }
        }
    }

    /** Cancels any active QR pairing polling loop and hides the pairing state. */
    fun stopQrSetup() {
        pairingJob?.cancel()
        pairingJob = null
        _setupPairing.value = null
    }

    fun toggleFavourite(channelId: String) {
        updatePrefs { current ->
            val nextFavs = if (channelId in current.favourites) {
                current.favourites - channelId
            } else {
                current.favourites + channelId
            }
            current.copy(favourites = nextFavs)
        }
    }

    fun hideChannel(channelId: String) {
        updatePrefs(renormalize = true) { current ->
            current.copy(
                hiddenChannels = current.hiddenChannels + channelId,
                favourites = current.favourites - channelId,
                recentChannels = current.recentChannels - channelId,
            )
        }
    }

    /** Adds a provider channel to the guide (a row of its own, in its category), or takes it out again */
    fun toggleGuideChannel(channelId: String) {
        updatePrefs { current ->
            current.copy(guideChannels = if (channelId in current.guideChannels) current.guideChannels - channelId else current.guideChannels + channelId)
        }
    }

    fun pinPreferredStream(channelId: String, streamId: String?) {
        updatePrefs(renormalize = true) { current ->
            val nextPins = current.preferredStreamByChannel.toMutableMap()
            if (streamId == null) nextPins.remove(channelId) else nextPins[channelId] = streamId
            current.copy(preferredStreamByChannel = nextPins)
        }
    }

    fun recordRecent(channelId: String) {
        updatePrefs { current ->
            val next = (listOf(channelId) + current.recentChannels.filterNot { it == channelId }).take(MAX_RECENTS)
            current.copy(recentChannels = next)
        }
    }

    fun nowAndNext(channel: CanonicalChannel, nowMs: Long = System.currentTimeMillis()): Pair<EpgProgramme?, EpgProgramme?> =
        synchronized(epgLock) { repo.epg.nowAndNext(channel, nowMs) }

    fun programmesInWindow(channel: CanonicalChannel, fromMs: Long, toMs: Long): List<EpgProgramme> =
        synchronized(epgLock) { repo.epg.programmesInWindow(channel, fromMs, toMs) }

    /**
     * Thread-safe snapshot of EPG programmes overlapping `[fromMs, toMs)`, falling back to a synthetic
     * [CanonicalChannel] keyed by [guideChannelId] when no IPTV stream is linked yet.
     */
    fun programmesForWindow(
        channel: CanonicalChannel?,
        guideChannelId: String,
        fromMs: Long,
        toMs: Long,
    ): List<EpgProgramme> = synchronized(epgLock) {
        if (channel != null) {
            repo.epg.programmesInRange(channel, fromMs, toMs)
        } else {
            val cleanId = guideChannelId.trim()
            if (cleanId.isEmpty()) return@synchronized emptyList()
            val entry = UkChannelRegistry.findById(cleanId)
            val synthetic = CanonicalChannel(
                id = cleanId,
                number = entry?.number,
                name = entry?.name ?: cleanId,
                category = entry?.category ?: ChannelCategory.OTHER,
                logoUrl = null,
                epgId = entry?.epgIds?.firstOrNull() ?: cleanId,
                streams = emptyList(),
            )
            repo.epg.programmesInRange(synthetic, fromMs, toMs)
        }
    }

    fun catchupFor(channel: CanonicalChannel, nowMs: Long = System.currentTimeMillis()): List<EpgProgramme> =
        synchronized(epgLock) { repo.epg.catchupProgrammes(channel, nowMs) }

    fun search(query: String, nowMs: Long = System.currentTimeMillis()): LiveSearchResults =
        synchronized(epgLock) { repo.epg.search(query, _channels.value, nowMs) }

    /** Loads UK & US sports fixtures for [sport] and [date] into [sportsFixtures]. */
    fun loadSportsFixtures(sport: String? = null, date: String? = null) {
        if (!engine.core.signedIn) {
            _sportsLoading.value = false
            _sportsError.value = "Sign in to Lumen to see sports fixtures."
            return
        }
        val gen = sportsGeneration.incrementAndGet()
        sportsJob?.cancel()
        sportsJob = scope.launch {
            _sportsLoading.value = true
            _sportsError.value = null
            try {
                val fixtures = engine.sportsFixtures(sport, date)
                Log.i("lumos-sports", "fixtures for ${sport ?: "all"}: ${fixtures.size}, live ${fixtures.count { it.status == lumos.iptv.FixtureStatus.LIVE }}, leagues ${fixtures.map { it.league }.distinct().take(8)}")
                if (sportsGeneration.get() == gen) {
                    _sportsFixtures.value = fixtures
                    scheduleFixtureStreamResolution()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (sportsGeneration.get() == gen) {
                    Log.w(TAG, "Sports fixtures fetch failed: ${e.javaClass.simpleName}")
                    Log.i("lumos-sports", "fixtures failed: ${e.javaClass.simpleName} ${e.message}")
                    _sportsError.value = e.message ?: "Unable to load sports fixtures"
                }
            } finally {
                if (sportsGeneration.get() == gen) {
                    _sportsLoading.value = false
                }
            }
        }
    }

    /** Searches UK Football and US pro sports teams via Lumos sync. */
    suspend fun searchSportsTeams(query: String): List<SavedSportsTeam> {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || !engine.core.signedIn) return emptyList()
        return runCatching { engine.searchSportsTeams(trimmed) }.getOrDefault(emptyList())
    }

    /** Persists [team] in profile favourites, syncs prefs, and refreshes team schedules. */
    fun addFavouriteTeam(team: SavedSportsTeam) {
        var added = false
        updatePrefs { current ->
            if (current.favouriteTeams.any { it.teamId == team.teamId && it.league == team.league }) {
                current
            } else {
                added = true
                current.copy(favouriteTeams = current.favouriteTeams + team)
            }
        }
        if (added) {
            refreshSavedTeamSchedules()
        }
    }

    /** Removes [teamId] from profile favourites, syncs prefs, and refreshes team schedules. */
    fun removeFavouriteTeam(teamId: String) {
        var removed = false
        updatePrefs { current ->
            val next = current.favouriteTeams.filterNot { it.teamId == teamId }
            if (next.size == current.favouriteTeams.size) {
                current
            } else {
                removed = true
                current.copy(favouriteTeams = next)
            }
        }
        if (removed) {
            val keepIds = _prefs.value.favouriteTeams.mapTo(HashSet()) { it.teamId }
            _teamSchedules.value = _teamSchedules.value.filter { it.team.teamId in keepIds }
            scheduleFixtureStreamResolution()
            refreshSavedTeamSchedules()
        }
    }

    /** Refreshes live, recent past, and upcoming schedules for saved sports teams. */
    fun refreshSavedTeamSchedules() {
        val teams = _prefs.value.favouriteTeams
        val gen = teamsGeneration.incrementAndGet()
        teamsJob?.cancel()
        if (teams.isEmpty()) {
            _teamSchedules.value = emptyList()
            _teamsLoading.value = false
            scheduleFixtureStreamResolution()
            return
        }
        if (!engine.core.signedIn) {
            _teamsLoading.value = false
            return
        }
        teamsJob = scope.launch {
            _teamsLoading.value = true
            try {
                val groups = engine.savedTeamsSchedule(teams)
                if (teamsGeneration.get() == gen) {
                    _teamSchedules.value = groups
                    scheduleFixtureStreamResolution()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Team schedules fetch failed: ${e.javaClass.simpleName}")
            } finally {
                if (teamsGeneration.get() == gen) {
                    _teamsLoading.value = false
                }
            }
        }
    }

    /** Toggles spoiler-free sports score hiding in profile prefs. */
    fun setHideSportsScores(hide: Boolean) {
        if (_prefs.value.hideSportsScores == hide) return
        updatePrefs { current -> current.copy(hideSportsScores = hide) }
    }

    /** Toggles non-UK channel visibility, renormalizing immediately and refreshing when enabled. */
    fun setShowNonUk(show: Boolean) {
        if (_prefs.value.showNonUk == show) return
        updatePrefs(renormalize = true) { current -> current.copy(showNonUk = show) }
        if (show) {
            refreshAll(forceEpg = false)
        }
    }

    /** Resolves ordered playable IPTV stream candidates for [fixture]. */
    fun resolveFixtureStreams(fixture: SportsFixture): List<ResolvedFixtureStream> {
        _resolvedFixtureStreams.value[fixture.id]?.let { return it }
        val currentPrefs = _prefs.value
        val priorities = _sources.value.associate { it.id to it.priority }
        val healthCopy = synchronized(healthLock) { StreamHealthTracker(repo.health.snapshot()) }
        return SportsStreamResolver.resolve(
            fixture = fixture,
            channels = _channels.value,
            health = healthCopy,
            prefs = currentPrefs,
            sourcePriorities = priorities,
            nowMs = System.currentTimeMillis(),
            maxHeight = displayMaxHeight(),
            bandwidthBps = lastBandwidthBps,
        )
    }

    /** Tunes [LivePlayerActivity] to the top resolved stream for [fixture]. */
    fun playFixture(context: Context, fixture: SportsFixture) {
        val top = resolveFixtureStreams(fixture).firstOrNull()
        if (top != null) {
            playLive(context, top.channel, streamId = top.stream.id)
        } else {
            val msg = fixture.ukChannelName?.let { "No IPTV stream linked for $it" }
                ?: "No playable IPTV stream matched for this event"
            showToast(context, msg)
        }
    }

    /** A finished fixture's broadcast, found in a channel's catch-up archive */
    data class Replay(val channel: CanonicalChannel, val programme: EpgProgramme)

    /**
     * Looks for [fixture] in the catch-up archives of the channels that showed it: the programme that was on
     * across kick-off (or the start), on a channel whose provider keeps an archive. Null when none does.
     */
    suspend fun findReplay(fixture: SportsFixture): Replay? {
        val now = System.currentTimeMillis()
        val candidates = buildList {
            fixture.ukChannelId?.let { id -> addAll(_channels.value.filter { it.id == id }) }
            runCatching { resolveFixtureStreams(fixture).forEach { add(it.channel) } }
        }.distinctBy { it.id }
        Log.i("lumos-replay", "candidates: ${candidates.joinToString { it.name + " catchup=" + it.hasCatchup + "/" + it.maxCatchupDays + " xtream=" + it.streams.count { s -> s.xtreamStreamId != null } + "/" + it.streams.size }}")
        Log.i("lumos-replay", "channels with catch-up: ${_channels.value.count { it.hasCatchup && it.maxCatchupDays > 0 }} of ${_channels.value.size}; sport: ${_channels.value.filter { it.hasCatchup && it.maxCatchupDays > 0 && Regex("sport|f1|tnt|dazn|premier", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }.take(12).joinToString { it.name + "/" + it.maxCatchupDays + "d" }}")
        val showing = candidates
            .filter { it.hasCatchup && it.maxCatchupDays > 0 && it.streams.any { s -> s.xtreamStreamId != null } }
        // (the match itself, not the show before it: whatever was on for most of the first two hours)
        val from = fixture.startMs
        val to = fixture.startMs + 2 * 3_600_000L
        Log.i("lumos-replay", "${fixture.name}: channel ${fixture.ukChannelId}, ${showing.size} with catch-up: ${showing.joinToString { it.name + "/" + it.maxCatchupDays + "d" }}")
        for (channel in showing) {
            val progs = runCatching { repo.loadArchiveEpg(channel, ingest = false) }.getOrDefault(emptyList())
            if (progs.any { it.endMs > it.startMs && it.title.isNotBlank() }) {
                synchronized(epgLock) { repo.epg.putProviderEpg(progs) }
            }
            val words = fixture.name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 4 }
            val best = synchronized(epgLock) { repo.epg.catchupProgrammes(channel, now) }
                .map { p ->
                    val overlap = minOf(p.endMs, to) - maxOf(p.startMs, from)
                    val named = words.count { p.title.lowercase().contains(it) || (p.description ?: "").lowercase().contains(it) }
                    Triple(p, overlap, named)
                }
                .filter { it.second >= 30 * 60_000L }
                .maxWithOrNull(compareBy<Triple<EpgProgramme, Long, Int>> { it.third }.thenBy { it.second })
            Log.i("lumos-replay", "${channel.name}: archive ${progs.size}, eligible ${synchronized(epgLock) { repo.epg.catchupProgrammes(channel, now) }.size}, match ${best?.first?.title}")
            if (best != null) return Replay(channel, best.first)
        }
        return null
    }

    fun playReplay(context: Context, replay: Replay) = playCatchup(context, replay.channel, replay.programme)

    /** Finds and plays [fixture]'s broadcast from the archive, saying so when there is none */
    fun watchReplay(context: Context, fixture: SportsFixture) {
        showToast(context, "Looking for the replay…")
        scope.launch {
            val replay = findReplay(fixture)
            if (replay != null) playReplay(context, replay)
            else showToast(context, fixture.ukChannelName?.let { "$it doesn't have this in its catch-up" } ?: "No replay on your channels")
        }
    }

    /** Tunes [LivePlayerActivity] to the best ranked stream on [row]. */
    fun playGuideRow(
        context: Context,
        row: GuideChannelRow,
        zapChannelIds: List<String>? = null,
    ) {
        val channel = row.linkedChannel
        if (channel != null && row.isPlayable) {
            playLive(context, channel, streamId = row.bestStream?.id, zapChannelIds = zapChannelIds)
        } else if (_uiState.value.loading || _uiState.value.refreshing || _channels.value.isEmpty()) {
            // (the providers' lists are still loading: play it as soon as it's linked, rather than saying there's none)
            showToast(context, "Loading ${row.guideChannel.name}…")
            scope.launch {
                val ready = kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                    _mainGuideRows.first { rows: List<GuideChannelRow> -> rows.any { it.guideChannel.id == row.guideChannel.id && it.isPlayable && it.linkedChannel != null } }
                        .first { it.guideChannel.id == row.guideChannel.id && it.isPlayable }
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    val ch = ready?.linkedChannel
                    if (ready != null && ch != null) playLive(context, ch, streamId = ready.bestStream?.id, zapChannelIds = zapChannelIds)
                    else showToast(context, "None of your providers has ${row.guideChannel.name} at the moment")
                }
            }
        } else {
            showToast(context, "None of your providers has ${row.guideChannel.name} at the moment")
        }
    }

    /**
     * Fetches TMDB 16:9 backdrop and description for [programme] when missing (or when
     * [forceArtwork] is set after a guide image error), debounced per programme ID and
     * memoized by title across airings.
     */
    fun enrichIfMissing(
        channelId: String?,
        programme: EpgProgramme,
        forceArtwork: Boolean = false,
    ) {
        if (!forceArtwork && !programme.iconUrl.isNullOrBlank() && !programme.description.isNullOrBlank()) return
        if (programme.title.isBlank() || !engine.core.signedIn) return
        val existing = _enrichments.value[programme.id]
        if (existing != null && (!forceArtwork || !existing.backdrop.isNullOrBlank())) return

        val titleKey = normalizeTitleKey(programme.title)
        titleEnrichmentCache[titleKey]?.let { cached ->
            if (!forceArtwork || !cached.backdrop.isNullOrBlank()) {
                storeEnrichment(programme.id, cached)
                return
            }
        }

        val now = System.currentTimeMillis()
        val attemptKey = if (forceArtwork) "${programme.id}:art" else programme.id
        val lastAt = enrichLastAttemptMs[attemptKey] ?: 0L
        if (now - lastAt < ENRICH_DEBOUNCE_MS) return
        if (!inflightEnrich.add(attemptKey)) return

        scope.launch {
            try {
                enrichLastAttemptMs[attemptKey] = System.currentTimeMillis()
                val enriched = runCatching {
                    engine.enrichEpgProgramme(
                        title = programme.title,
                        channelId = channelId,
                        category = programme.category,
                    )
                }.getOrNull() ?: return@launch

                if (!enriched.backdrop.isNullOrBlank() || !enriched.description.isNullOrBlank()) {
                    if (titleKey.isNotEmpty() && (!forceArtwork || !enriched.backdrop.isNullOrBlank())) {
                        titleEnrichmentCache[titleKey] = enriched
                    }
                    storeEnrichment(programme.id, enriched)
                }
            } finally {
                inflightEnrich.remove(attemptKey)
            }
        }
    }

    /**
     * Fetches Xtream short EPG for [channel] on demand when no Now programme is cached,
     * debounced per channel ID across a 12-minute window.
     */
    fun fetchChannelEpgIfMissing(channel: CanonicalChannel) {
        if (channel.streams.none { it.xtreamStreamId != null }) return
        if (_nowNextByChannel.value[channel.id]?.first != null) return
        fetchChannelEpgDebounced(
            channel = channel,
            kind = "short",
            lastFetchMap = shortEpgLastFetchMs,
            skipIfNowPresent = true,
        ) { targetRepo ->
            targetRepo.loadShortEpg(channel, ingest = false)
        }
    }

    /**
     * Fetches full Xtream archive EPG for [channel] when opening catch-up listings,
     * debounced per channel ID across a 12-minute window.
     */
    fun fetchChannelArchiveEpg(channel: CanonicalChannel) {
        if (channel.streams.none { it.xtreamStreamId != null }) return
        fetchChannelEpgDebounced(
            channel = channel,
            kind = "archive",
            lastFetchMap = archiveEpgLastFetchMs,
            skipIfNowPresent = false,
        ) { targetRepo ->
            targetRepo.loadArchiveEpg(channel, ingest = false)
        }
    }

    fun channelById(channelId: String): CanonicalChannel? =
        _channels.value.firstOrNull { it.id == channelId }

    fun rankedStreamsFor(channel: CanonicalChannel): List<StreamSource> {
        val currentPrefs = _prefs.value
        val priorities = _sources.value.associate { it.id to it.priority }
        val healthCopy = synchronized(healthLock) { StreamHealthTracker(repo.health.snapshot()) }
        return StreamRanker.rank(
            streams = channel.streams,
            category = channel.category,
            isEventChannel = channel.isEventChannel,
            health = healthCopy,
            preferredStreamId = currentPrefs.preferredStreamByChannel[channel.id],
            sourcePriorities = priorities,
            nowMs = System.currentTimeMillis(),
            maxHeight = displayMaxHeight(),
            bandwidthBps = lastBandwidthBps,
        )
    }

    suspend fun resolveLive(channel: CanonicalChannel, stream: StreamSource): ResolvedStream =
        repo.resolveLiveStream(stream)

    suspend fun resolveCatchup(
        channel: CanonicalChannel,
        programme: EpgProgramme,
        stream: StreamSource,
    ): ResolvedStream? =
        repo.resolveCatchupStream(stream, programme.startMs, programme.endMs, System.currentTimeMillis())

    /**
     * Resolves an archive/timeshift stream starting at [startAtMs] inside an ongoing or finished
     * [programme] for live timeshift scrubbing.
     */
    suspend fun resolveCatchupAt(
        channel: CanonicalChannel,
        programme: EpgProgramme,
        startAtMs: Long,
        stream: StreamSource,
    ): ResolvedStream? {
        val now = System.currentTimeMillis()
        val clampedStart = startAtMs.coerceIn(programme.startMs, now - 1_000L)
        val endMs = minOf(programme.endMs, now).coerceAtLeast(clampedStart + 1_000L)
        return repo.resolveCatchupStream(stream, clampedStart, endMs, now)
    }

    fun recordStreamSuccess(streamId: String, latencyMs: Long) {
        val now = System.currentTimeMillis()
        val json = synchronized(healthLock) {
            repo.health.recordSuccess(streamId, now, latencyMs)
            pendingHealthOutcomes += HealthEvent(streamId, success = true, nowMs = now, latencyMs = latencyMs)
            repo.health.toJson()
        }
        scope.launch { writeAtomically(healthFile, json) }
    }

    fun recordStreamFailure(streamId: String) {
        val now = System.currentTimeMillis()
        val json = synchronized(healthLock) {
            repo.health.recordFailure(streamId, now)
            pendingHealthOutcomes += HealthEvent(streamId, success = false, nowMs = now, latencyMs = 0L)
            repo.health.toJson()
        }
        scope.launch { writeAtomically(healthFile, json) }
    }

    /**
     * Checks both Lumos viewer presence and Xtream connection limits in parallel before opening
     * a new stream, failing soft to `null` after 1.2s or on network error.
     */
    suspend fun checkActiveViewerConflict(
        channel: CanonicalChannel,
        preferredStreamId: String? = null,
    ): ActiveViewerConflict? {
        val ranked = rankedStreamsFor(channel)
        val targetStream = preferredStreamId?.let { id -> ranked.firstOrNull { it.id == id } }
            ?: ranked.firstOrNull()
            ?: return null
        val source = _sources.value.firstOrNull { it.id == targetStream.sourceId } ?: return null

        return runCatching {
            withTimeoutOrNull(CONFLICT_CHECK_TIMEOUT_MS) {
                coroutineScope {
                    val presenceJob = async(Dispatchers.IO) {
                        if (!engine.core.signedIn) {
                            emptyList()
                        } else {
                            val json = runCatching { engine.iptvPresence("check", targetStream.sourceId) }.getOrNull()
                            if (json.isNullOrBlank()) emptyList() else IptvCodec.decodePresenceOthers(json)
                        }
                    }
                    val xtreamJob = async(Dispatchers.IO) {
                        runCatching {
                            repo.checkXtreamConnectionConflict(
                                source = source,
                                ownSourceActiveRecently = LiveMiniPlayer.isOwnSourceActiveRecently(targetStream.sourceId),
                            )
                        }.getOrNull()
                    }

                    val others = presenceJob.await()
                    val xtreamConflict = xtreamJob.await()
                    val firstOther = others.firstOrNull()
                    when {
                        firstOther != null && xtreamConflict != null -> xtreamConflict.copy(
                            otherDeviceName = firstOther.deviceName,
                            otherChannelName = firstOther.channelName,
                            reason = buildString {
                                append("${firstOther.deviceName} is currently watching")
                                firstOther.channelName?.takeIf { it.isNotBlank() }?.let { append(" $it") }
                                append(" · ${xtreamConflict.reason}")
                            },
                        )
                        firstOther != null -> ActiveViewerConflict(
                            sourceId = source.id,
                            sourceName = source.name,
                            otherDeviceName = firstOther.deviceName,
                            otherChannelName = firstOther.channelName,
                            reason = buildString {
                                append("${firstOther.deviceName} is currently watching on ${source.name}")
                                firstOther.channelName?.takeIf { it.isNotBlank() }?.let { append(" ($it)") }
                            },
                        )
                        xtreamConflict != null -> xtreamConflict
                        else -> null
                    }
                }
            }
        }.getOrNull()
    }

    /** Records that this device started watching [channelName] on [sourceId]. */
    fun notifyPresenceStart(sourceId: String, channelName: String) {
        if (sourceId.isBlank()) return
        LiveMiniPlayer.markOwnSourceActive(sourceId)
        if (!engine.core.signedIn) return
        scope.launch {
            runCatching { engine.iptvPresence("start", sourceId, channelName) }
        }
    }

    /** Refreshes the active viewer presence lease on [sourceId] while playing. */
    fun notifyPresenceHeartbeat(sourceId: String, channelName: String) {
        if (sourceId.isBlank() || !engine.core.signedIn) return
        scope.launch {
            runCatching { engine.iptvPresence("heartbeat", sourceId, channelName) }
        }
    }

    /** Clears active viewer presence on [sourceId] and starts the local reconnect grace window. */
    fun notifyPresenceStop(sourceId: String) {
        if (sourceId.isBlank()) return
        LiveMiniPlayer.markOwnSourceStopped(sourceId)
        if (!engine.core.signedIn) return
        scope.launch {
            runCatching { engine.iptvPresence("stop", sourceId) }
        }
    }

    fun playLive(
        context: Context,
        channel: CanonicalChannel,
        streamId: String? = null,
        zapChannelIds: List<String>? = null,
        skipConflictCheck: Boolean = false,
    ) {
        recordRecent(channel.id)
        markPlayerOpen(context)
        LiveMiniPlayer.openingFullscreen = true
        val intent = Intent(context, LivePlayerActivity::class.java).apply {
            putExtra(LivePlayerActivity.EXTRA_CHANNEL_ID, channel.id)
            streamId?.let { putExtra(LivePlayerActivity.EXTRA_STREAM_ID, it) }
            zapChannelIds?.takeIf { it.isNotEmpty() }?.let {
                putStringArrayListExtra(LivePlayerActivity.EXTRA_ZAP_CHANNEL_IDS, ArrayList(it))
            }
            if (skipConflictCheck) {
                putExtra(LivePlayerActivity.EXTRA_SKIP_CONFLICT_CHECK, true)
            }
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun playCatchup(
        context: Context,
        channel: CanonicalChannel,
        programme: EpgProgramme,
        zapChannelIds: List<String>? = null,
        skipConflictCheck: Boolean = false,
    ) {
        recordRecent(channel.id)
        markPlayerOpen(context)
        LiveMiniPlayer.openingFullscreen = true
        val intent = Intent(context, LivePlayerActivity::class.java).apply {
            putExtra(LivePlayerActivity.EXTRA_CHANNEL_ID, channel.id)
            putExtra(LivePlayerActivity.EXTRA_PROGRAMME_JSON, IptvCodec.encodeProgramme(programme))
            zapChannelIds?.takeIf { it.isNotEmpty() }?.let {
                putStringArrayListExtra(LivePlayerActivity.EXTRA_ZAP_CHANNEL_IDS, ArrayList(it))
            }
            if (skipConflictCheck) {
                putExtra(LivePlayerActivity.EXTRA_SKIP_CONFLICT_CHECK, true)
            }
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** One match's page from ESPN (score, stats, goals and cards, line-ups); null when it can't be had */
    suspend fun sportsMatch(fixture: SportsFixture): org.json.JSONObject? =
        if (fixture.league.isBlank() || fixture.id.isBlank()) null else engine.sportsMatch(fixture.league, fixture.id)

    /** Formula 1's drivers' and constructors' standings */
    suspend fun f1Standings(series: String = "f1"): org.json.JSONObject? = engine.f1Standings(series)

    private val sportsExtras = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, org.json.JSONObject>>()

    /** The sports pages' extras, kept a while in memory so moving between sports never waits on them twice */
    private suspend fun keptJson(key: String, ttlMs: Long, make: suspend () -> org.json.JSONObject?): org.json.JSONObject? {
        sportsExtras[key]?.takeIf { System.currentTimeMillis() - it.first < ttlMs }?.let { return it.second }
        val made = make() ?: return sportsExtras[key]?.second
        sportsExtras[key] = System.currentTimeMillis() to made
        return made
    }

    /** Formula 1's next race weekend: sessions, the circuit and its outline, last year's winner, artwork */
    suspend fun f1Weekend(): org.json.JSONObject? = keptJson("f1weekend", 30 * 60_000L) { engine.f1Weekend() }

    /** UFC's next card, tennis (rankings, slams, draws) or golf (leaderboards): [kind] "ufc", "tennis" or "golf" */
    suspend fun sportsPage(kind: String): org.json.JSONObject? = keptJson("page:$kind", 10 * 60_000L) { engine.sportsJson("/iptv/sports/$kind") }

    /** As [sportsPage], asked for afresh */
    suspend fun sportsPageFresh(kind: String): org.json.JSONObject? { sportsExtras.remove("page:$kind"); return sportsPage(kind) }

    /** A football league's table */
    suspend fun sportsTable(league: String): org.json.JSONObject? = keptJson("table:$league", 15 * 60_000L) { engine.sportsTable(league) }

    /** Billboard pictures (fan art, badge) for each team named, by name */
    suspend fun sportsArt(teams: List<String>): Map<String, org.json.JSONObject> {
        val names = teams.filter { it.isNotBlank() }.distinct().sorted()
        if (names.isEmpty()) return emptyMap()
        // (not kept here: the Worker keeps them, and one the picture site turned away is asked for again next time)
        val json = engine.sportsArt(names) ?: return emptyMap()
        val all = json.optJSONObject("teams") ?: return emptyMap()
        return all.keys().asSequence().mapNotNull { k -> all.optJSONObject(k)?.let { k to it } }.toMap()
    }

    /** A race weekend in the replay addon: its round, place, and the sessions it has (in the order to show them) */
    data class MotorRound(val round: Int, val place: String, val feedId: String, val feedType: String, val sessions: List<MotorSession>)
    data class MotorSession(val label: String, val videoId: String)

    private var motorCache: Pair<Long, List<MotorRound>>? = null

    /**
     * Formula 1's weekends in the replay addon (Formulio), newest round first, with the sessions worth watching (race,
     * qualifying, sprint, practice). Empty when the addon isn't installed or has nothing.
     */
    suspend fun motorReplays(): List<MotorRound> {
        motorCache?.takeIf { System.currentTimeMillis() - it.first < 10 * 60_000L }?.let { return it.second }
        val series = engine.addonSeries("formulio", listOf("Sky F1", "F1TV (English)", "Sky F1 UHD")) ?: return emptyList()
        val order = listOf("Race", "Sprint", "Qualifying", "Sprint Qualifying", "Free Practice Three", "Free Practice Two", "Free Practice One")
        val rounds = series.videos.mapNotNull { (id, title) ->
            val round = id.split(':').getOrNull(1)?.toIntOrNull()
            // two ways the addon words a title: "Race - Las Vegas Grand Prix", and "F1 2026 R21 Las Vegas Race Sky Sports F1 UHD …"
            val dash = Regex("""^(.+?) - (.+?)( Grand Prix)?$""").find(title)
            val long = Regex("""^F1 \d{4} R(\d+) (.+?) Sky Sports""").find(title)
            val (r, place, sessionText) = when {
                long != null -> {
                    val rest = long.groupValues[2]
                    val sess = order.sortedByDescending { it.length }.firstOrNull { rest.endsWith(it) } ?: return@mapNotNull null
                    Triple(long.groupValues[1].toInt(), rest.removeSuffix(sess).trim(), sess)
                }
                dash != null && round != null -> Triple(round, dash.groupValues[2].trim(), dash.groupValues[1].trim())
                else -> return@mapNotNull null
            }
            val session = order.firstOrNull { it.equals(sessionText, true) } ?: return@mapNotNull null
            Triple(r, place, MotorSession(session, id))
        }.groupBy { it.first }.map { (round, list) ->
            MotorRound(round, list.first().second, series.id, series.type, list.map { it.third }.sortedBy { order.indexOf(it.label).let { i -> if (i < 0) 99 else i } })
        }.sortedByDescending { it.round }
        motorCache = System.currentTimeMillis() to rounds
        android.util.Log.i("lumos-formulio", "replays: ${rounds.size} weekends, newest ${rounds.firstOrNull()?.let { it.place + " " + it.sessions.joinToString { s -> s.label } }}")
        return rounds
    }

    /** The next weekends' races, which the day's scoreboard doesn't list: asked for by the next few Sundays */
    suspend fun upcomingRaces(sport: String, leagueCode: String): List<SportsFixture> {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.UK)
        val cal = java.util.Calendar.getInstance()
        val days: List<String> = (0..3).map { _ ->
            while (cal.get(java.util.Calendar.DAY_OF_WEEK) != java.util.Calendar.SUNDAY) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            fmt.format(cal.time).also { cal.add(java.util.Calendar.DAY_OF_YEAR, 1) }
        }
        return kotlinx.coroutines.coroutineScope {
            val calls = days.map { d: String -> async { runCatching { engine.sportsFixtures(sport, d) }.getOrDefault(emptyList<SportsFixture>()) } }
            calls.awaitAll().flatten()
        }.filter { it.league.equals(leagueCode, true) && it.status == lumos.iptv.FixtureStatus.UPCOMING }.distinctBy { it.id }.sortedBy { it.startMs }
    }

    /**
     * A series' next race from TheSportsDB (its free key), for when ESPN has none: between seasons ESPN only knows the
     * season just gone, while TheSportsDB has next year's calendar as soon as it's out. Practice, qualifying and tests are
     * skipped. A race listed by date alone (its time not yet set) comes as midnight UK time with statusDetail "time TBC".
     */
    suspend fun nextSeasonRace(sportsDbLeague: String, leagueCode: String): SportsFixture? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val json = keptJson("next:$sportsDbLeague", 6 * 3_600_000L) {
            runCatching {
                val c = java.net.URL("https://www.thesportsdb.com/api/v1/json/123/eventsnextleague.php?id=$sportsDbLeague").openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 8_000; c.readTimeout = 8_000
                c.inputStream.bufferedReader().use { org.json.JSONObject(it.readText()) }.also { c.disconnect() }
            }.getOrNull()
        } ?: return@withContext null
        val events = json.optJSONArray("events") ?: return@withContext null
        val skip = Regex("practice|qualif|testing|test day|duel", RegexOption.IGNORE_CASE)
        val now = System.currentTimeMillis()
        (0 until events.length()).asSequence().mapNotNull { events.optJSONObject(it) }.mapNotNull { e ->
            val name = e.optString("strEvent").takeIf { it.isNotBlank() && !skip.containsMatchIn(it) } ?: return@mapNotNull null
            val date = e.optString("dateEvent").takeIf { it.length == 10 } ?: return@mapNotNull null
            val time = e.optString("strTime").takeIf { it.isNotBlank() && it != "null" && !it.startsWith("00:00") }
            val ms = if (time != null) {
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.UK).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    .parse("$date ${time.take(8).padEnd(8, '0').let { t -> if (t.length == 5) "$t:00" else t }}")?.time
            } else {
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.UK).apply { timeZone = java.util.TimeZone.getTimeZone("Europe/London") }.parse(date)?.time
            } ?: return@mapNotNull null
            if (ms < now) return@mapNotNull null
            SportsFixture(
                id = "tsdb:${e.optString("idEvent")}", sport = "racing", league = leagueCode,
                leagueName = e.optString("strLeague"), name = name, startMs = ms, status = lumos.iptv.FixtureStatus.UPCOMING,
                statusDetail = if (time == null) "time TBC" else null,
                venue = listOf(e.optString("strVenue"), e.optString("strCity")).firstOrNull { it.isNotBlank() && it != "null" },
            )
        }.minByOrNull { it.startMs }
    }

    /** (logs what the replay addon offers: its catalogs and ids) */
    suspend fun probeAddon(nameLike: String): String = engine.probeAddon(nameLike)

    /** What the channel is showing, as a Lumen title (its IMDb id), for "Watch in Lumen"; null if not found */
    suspend fun findInLumen(channel: CanonicalChannel, programme: EpgProgramme): EpgEnrichment? = runCatching {
        engine.enrichEpgProgramme(
            title = programme.title,
            channelId = channel.id,
            category = programme.category,
            description = programme.description,
            imdb = true,
        )
    }.getOrNull()?.takeIf { !it.imdbId.isNullOrBlank() }

    /** Whether Lumen's stream addons have anything for this film (its IMDb id) or episode (tt…:season:episode) */
    suspend fun hasStreams(type: String, videoId: String): Boolean = engine.hasStreams(type, videoId)

    /** Like Lumen's own player: the TV may close the main screen while the player has it (the Mi Box does when short of
     *  memory), and coming back must not ask Who's watching? again */
    private fun markPlayerOpen(context: Context) {
        context.getSharedPreferences("phone-cache", Context.MODE_PRIVATE).edit()
            .putLong("x.playerUntil", System.currentTimeMillis() + 12 * 3600_000L).apply()
    }

    private suspend fun runRefresh(
        forceEpg: Boolean,
        skipIfFresh: Boolean = false,
    ) = refreshMutex.withLock {
        if (skipIfFresh && _channels.value.isNotEmpty()) {
            val nowCheck = System.currentTimeMillis()
            val ageMs = nowCheck - _uiState.value.lastLoadedAtMs
            val epgStillFresh = lastUkEpgFetchedAtMs > 0L &&
                (nowCheck - lastUkEpgFetchedAtMs) < AUTO_REFRESH_COOLDOWN_MS &&
                synchronized(epgLock) { repo.epg.guideChannels().isNotEmpty() }
            if ((!forceEpg || epgStillFresh) && ageMs in 0 until AUTO_REFRESH_COOLDOWN_MS) {
                val (latestSources, latestPrefs, _) = readEngineState()
                val loadedIds = loadedChannelSourceIds(_channels.value)
                val enabledIds = enabledSourceIds(latestSources)
                if (loadedIds == enabledIds && cachedShowNonUk == latestPrefs.showNonUk) {
                    return@withLock
                }
            }
        }
        val initialEmpty = _channels.value.isEmpty()
        _uiState.value = _uiState.value.copy(loading = initialEmpty, refreshing = true, error = null)

        try {
            runCatching { engine.syncIptvNow() }
            val (currentSources, currentPrefs, viewerId) = readEngineState()
            activeViewer = viewerId
            _sources.value = currentSources
            _prefs.value = currentPrefs

            val now = System.currentTimeMillis()
            val bw = runCatching { engine.core.estimatedBandwidthBps() }.getOrNull()?.takeIf { it > 0L }
            lastBandwidthBps = bw

            val stagingRepo = engine.core.newIptvRepository()
            val baseline = synchronized(healthLock) {
                pendingHealthOutcomes.clear()
                val healthSnap = repo.health.snapshot()
                val (builtIn, provider, aliases, guideChans) = synchronized(epgLock) {
                    RefreshEpgSnap(
                        builtIn = repo.epg.exportBuiltInProgrammes(),
                        provider = repo.epg.exportProviderProgrammes(),
                        aliases = repo.epg.exportChannelAliases(),
                        guideChannels = repo.epg.exportGuideChannels(),
                    )
                }
                RefreshBaseline(
                    health = healthSnap,
                    builtIn = builtIn,
                    provider = provider,
                    aliases = aliases,
                    guideChannels = guideChans,
                )
            }
            stagingRepo.health.restore(baseline.health)

            val shouldFetchUkEpg = forceEpg || lastUkEpgFetchedAtMs == 0L || (now - lastUkEpgFetchedAtMs) > EPG_STALE_AFTER_MS || baseline.guideChannels.isEmpty()
            val signedIn = engine.core.signedIn

            var ukEpgUpdated = false
            val (loadResult, sourcesMs, ukEpgMs) = coroutineScope {
                val sourcesJob = async {
                    val t0 = SystemClock.elapsedRealtime()
                    val res = stagingRepo.loadSources(
                        sources = currentSources,
                        prefs = currentPrefs,
                        nowMs = now,
                        maxHeight = displayMaxHeight(),
                        bandwidthBps = bw,
                    )
                    res to (SystemClock.elapsedRealtime() - t0)
                }
                val ukEpgJob = async {
                    val t0 = SystemClock.elapsedRealtime()
                    val json = if (shouldFetchUkEpg && signedIn) {
                        runCatching {
                            engine.core.ukEpg(now - EPG_PAST_WINDOW_MS, now + EPG_FUTURE_WINDOW_MS)
                        }.getOrNull()
                    } else null
                    json to (SystemClock.elapsedRealtime() - t0)
                }

                val (ukJson, ukElapsedMs) = ukEpgJob.await()
                if (!ukJson.isNullOrBlank()) {
                    val count = runCatching { stagingRepo.loadUkEpgJson(ukJson) }.getOrDefault(0)
                    if (count > 0 || stagingRepo.epg.guideChannels().isNotEmpty()) {
                        ukEpgUpdated = true
                        lastUkEpgFetchedAtMs = now
                    }
                }
                if (!ukEpgUpdated) {
                    if (baseline.guideChannels.isNotEmpty()) {
                        stagingRepo.epg.putGuideChannels(baseline.guideChannels)
                    }
                    if (baseline.builtIn.isNotEmpty()) {
                        stagingRepo.epg.putBuiltInUkEpg(baseline.builtIn)
                    }
                }
                if (baseline.aliases.isNotEmpty()) {
                    stagingRepo.epg.restoreChannelAliases(baseline.aliases)
                }
                if (baseline.provider.isNotEmpty()) {
                    stagingRepo.epg.putProviderEpg(baseline.provider)
                }

                val (res, srcElapsedMs) = sourcesJob.await()
                Triple(res, srcElapsedMs, ukElapsedMs)
            }

            synchronized(healthLock) {
                for (evt in pendingHealthOutcomes) {
                    if (evt.success) {
                        stagingRepo.health.recordSuccess(evt.streamId, evt.nowMs, evt.latencyMs)
                    } else {
                        stagingRepo.health.recordFailure(evt.streamId, evt.nowMs)
                    }
                }
                pendingHealthOutcomes.clear()
                synchronized(epgLock) {
                    repo = stagingRepo
                    hasLiveStreamsLoaded = true
                }
            }

            _sourceReports.value = loadResult.reports
            val visibleChannels = publishChannelLists(loadResult.channels)
            computeAndPublishEpgDerived(visibleChannels, now)

            val failedReports = loadResult.reports.filter { !it.success }
            val errMsg = if (loadResult.channels.isEmpty() && failedReports.isNotEmpty()) {
                failedReports.firstNotNullOfOrNull { it.error } ?: "Couldn't load your Live TV providers. Check your connection and try again."
            } else null

            _uiState.value = LiveUiState(
                loading = false,
                refreshing = true,
                error = errMsg,
                epgUpdatedAtMs = lastUkEpgFetchedAtMs,
                lastLoadedAtMs = now,
            )
            cachedShowNonUk = currentPrefs.showNonUk
            _epgVersion.value = _epgVersion.value + 1L

            val streamCount = loadResult.channels.sumOf { it.streams.size }
            Log.i(
                TAG,
                "refresh sourcesMs=$sourcesMs ukEpgMs=$ukEpgMs channelCount=${loadResult.channels.size} streamCount=$streamCount",
            )

            withContext(Dispatchers.IO) {
                val providerBatch = runCatching { stagingRepo.fetchProviderEpgBatch(currentSources) }.getOrNull()
                val addedProvider = if (providerBatch != null && providerBatch.totalProgrammes > 0) {
                    synchronized(epgLock) {
                        providerBatch.xmltvResults.forEach(stagingRepo.epg::putXmltv)
                        if (providerBatch.providerProgrammes.isNotEmpty()) {
                            stagingRepo.epg.putProviderEpg(providerBatch.providerProgrammes)
                        }
                    }
                    providerBatch.totalProgrammes
                } else {
                    0
                }

                if (addedProvider > 0) {
                    computeAndPublishEpgDerived(_channels.value, System.currentTimeMillis())
                    _epgVersion.value = _epgVersion.value + 1L
                }

                persistCaches(now, stagingRepo, currentPrefs.showNonUk)
            }

            _uiState.value = _uiState.value.copy(refreshing = false)
        } catch (e: CancellationException) {
            _uiState.value = _uiState.value.copy(loading = false, refreshing = false)
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "Live TV refresh failed: ${e.javaClass.simpleName}")
            _uiState.value = _uiState.value.copy(
                loading = false,
                refreshing = false,
                error = "Couldn't refresh Live TV. Check your connection and try again.",
            )
        }
    }

    private fun restoreWarmCaches() {
        val t0 = SystemClock.elapsedRealtime()
        val warmRepo = engine.core.newIptvRepository()
        if (healthFile.exists()) {
            val loadedHealth = runCatching { StreamHealthTracker.fromJson(healthFile.readText()) }.getOrNull()
            if (loadedHealth != null) warmRepo.health.restore(loadedHealth.snapshot())
        }
        if (cacheFile.exists()) {
            val snapshot = readJsonStream(cacheFile, IptvWarmSnapshot.serializer())
            if (snapshot == null || !warmRepo.restoreWarmSnapshot(snapshot)) {
                runCatching { cacheFile.delete() }
                runCatching { cacheMetaFile.delete() }
            }
        }
        if (epgCacheFile.exists()) {
            val snap = readJsonStream(epgCacheFile, EpgCacheSnapshot.serializer())
            if (snap != null) {
                lastUkEpgFetchedAtMs = snap.updatedAtMs
                if (snap.guideChannels.isNotEmpty()) {
                    warmRepo.epg.putGuideChannels(snap.guideChannels)
                }
                warmRepo.epg.restoreChannelAliases(snap.aliases)
                warmRepo.epg.putBuiltInUkEpg(snap.builtIn)
                warmRepo.epg.putProviderEpg(snap.provider)
            } else {
                runCatching { epgCacheFile.delete() }
            }
        }
        synchronized(epgLock) {
            repo = warmRepo
        }
        val restored = warmRepo.currentChannels()
        cachedShowNonUk = if (restored.isNotEmpty() && cacheMetaFile.exists()) {
            when (runCatching { cacheMetaFile.readText().trim() }.getOrNull()) {
                "true" -> true
                "false" -> false
                else -> null
            }
        } else {
            null
        }
        if (restored.isNotEmpty()) {
            _channels.value = restored
            _uiState.value = _uiState.value.copy(
                epgUpdatedAtMs = lastUkEpgFetchedAtMs,
                lastLoadedAtMs = if (cacheFile.exists()) cacheFile.lastModified() else 0L,
            )
            _epgVersion.value = _epgVersion.value + 1L
            scheduleFixtureStreamResolution()
        }
        val elapsedMs = SystemClock.elapsedRealtime() - t0
        Log.i(TAG, "warmRestore ms=$elapsedMs channelCount=${restored.size}")
    }

    private suspend fun persistSourcesAndRefresh(updated: List<IptvSource>) {
        val now = System.currentTimeMillis()
        val encoded = IptvCodec.encodeSources(updated)
        engine.withIptvCore { core -> core.setIptvSourcesJson(encoded, now) }
        _sources.value = updated
        engine.syncIptvNow()
        runRefresh(forceEpg = false)
    }

    private suspend fun readEngineState(): Triple<List<IptvSource>, IptvProfilePrefs, String> =
        engine.withIptvCore { core ->
            val srcJson = core.iptvSourcesJson()
            val prefsJson = core.iptvPrefsJson()
            val parsedSources = srcJson?.let(IptvCodec::decodeSources).orEmpty()
            val parsedPrefs = prefsJson?.let(IptvCodec::decodePrefs) ?: IptvProfilePrefs()
            Triple(parsedSources, parsedPrefs, core.viewer)
        }

    private fun updatePrefs(
        renormalize: Boolean = false,
        transform: (IptvProfilePrefs) -> IptvProfilePrefs,
    ) {
        val next = transform(_prefs.value)
        _prefs.value = next
        if (renormalize) {
            applyPrefsLocally(next)
        } else {
            publishChannelLists()
        }
        scope.launch {
            val now = System.currentTimeMillis()
            val encoded = IptvCodec.encodePrefs(next)
            engine.withIptvCore { core -> core.setIptvPrefsJson(encoded, now) }
            if (renormalize) {
                val snapshot = synchronized(healthLock) {
                    synchronized(epgLock) {
                        repo.buildWarmSnapshot(now)
                    }
                }
                writeJsonStreamAtomically(cacheFile, IptvWarmSnapshot.serializer(), snapshot)
                writeAtomically(cacheMetaFile, next.showNonUk.toString())
                cachedShowNonUk = next.showNonUk
            }
            engine.syncIptvNow()
        }
    }

    private fun applyPrefsLocally(nextPrefs: IptvProfilePrefs) {
        val now = System.currentTimeMillis()
        val updatedChannels = if (hasLiveStreamsLoaded) {
            synchronized(epgLock) {
                repo.renormalize(
                    prefs = nextPrefs,
                    nowMs = now,
                    maxHeight = displayMaxHeight(),
                    bandwidthBps = lastBandwidthBps,
                )
            }
        } else {
            val priorities = _sources.value.associate { it.id to it.priority }
            val healthCopy = synchronized(healthLock) { StreamHealthTracker(repo.health.snapshot()) }
            _channels.value
                .filterNot { it.id in nextPrefs.hiddenChannels || it.category.name in nextPrefs.hiddenCategories }
                .map { ch ->
                    val ranked = StreamRanker.rank(
                        streams = ch.streams,
                        category = ch.category,
                        isEventChannel = ch.isEventChannel,
                        health = healthCopy,
                        preferredStreamId = nextPrefs.preferredStreamByChannel[ch.id],
                        sourcePriorities = priorities,
                        nowMs = now,
                        maxHeight = displayMaxHeight(),
                        bandwidthBps = lastBandwidthBps,
                    )
                    ch.copy(streams = ranked)
                }
        }
        publishChannelDerivedState(updatedChannels, now)
    }

    private fun publishChannelLists(
        channelList: List<CanonicalChannel> = _channels.value,
    ): List<CanonicalChannel> {
        val currentPrefs = _prefs.value
        val visible = channelList.filterNot { it.id in currentPrefs.hiddenChannels }
        val byId = visible.associateBy { it.id }

        val favList = currentPrefs.favourites.mapNotNull { byId[it] }
        val recentList = currentPrefs.recentChannels.mapNotNull { byId[it] }

        val grouped = visible.groupingBy { it.category }.eachCount()
        val categoryCounts = buildList {
            if (favList.isNotEmpty()) add(ChannelCategory.FAVOURITES to favList.size)
            grouped.entries
                .filter { it.key != ChannelCategory.FAVOURITES && it.value > 0 }
                .sortedBy { it.key.order }
                .forEach { add(it.key to it.value) }
        }

        _channels.value = visible
        _favourites.value = favList
        _recents.value = recentList
        _categories.value = categoryCounts
        scheduleFixtureStreamResolution()
        return visible
    }

    private fun scheduleFixtureStreamResolution() {
        val gen = fixtureResolutionGeneration.incrementAndGet()
        fixtureResolutionJob?.cancel()

        val teamFixtures = _teamSchedules.value.flatMap { it.live + it.upcoming }
        val allFixtures = buildList(_sportsFixtures.value.size + teamFixtures.size) {
            addAll(_sportsFixtures.value)
            addAll(teamFixtures)
        }.distinctBy { it.id }

        val channelSnapshot = _channels.value
        if (allFixtures.isEmpty() || channelSnapshot.isEmpty()) {
            _resolvedFixtureStreams.value = emptyMap()
            return
        }

        val currentPrefs = _prefs.value
        val priorities = _sources.value.associate { it.id to it.priority }
        val maxHeight = displayMaxHeight()
        val bandwidthBps = lastBandwidthBps

        fixtureResolutionJob = scope.launch(Dispatchers.Default) {
            val activeRepo = repo
            val healthCopy = synchronized(healthLock) { StreamHealthTracker(activeRepo.health.snapshot()) }
            val resolved = SportsStreamResolver.resolveBatch(
                fixtures = allFixtures,
                channels = channelSnapshot,
                health = healthCopy,
                prefs = currentPrefs,
                sourcePriorities = priorities,
                nowMs = System.currentTimeMillis(),
                maxHeight = maxHeight,
                bandwidthBps = bandwidthBps,
            )
            if (fixtureResolutionGeneration.get() == gen) {
                _resolvedFixtureStreams.value = resolved
            }
        }
    }

    private fun publishChannelDerivedState(
        channelList: List<CanonicalChannel> = _channels.value,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val visible = publishChannelLists(channelList)
        scheduleEpgDerivedRebuild(visible, nowMs)
    }

    private fun scheduleEpgDerivedRebuild(
        visible: List<CanonicalChannel> = _channels.value,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        derivedEpgJob?.cancel()
        derivedEpgJob = scope.launch {
            computeAndPublishEpgDerived(visible, nowMs)
        }
    }

    private suspend fun computeAndPublishEpgDerived(
        visible: List<CanonicalChannel>,
        nowMs: Long,
    ) {
        val gen = derivedGeneration.incrementAndGet()
        val derived = withContext(Dispatchers.Default) {
            val count = visible.count { it.hasCatchup && it.maxCatchupDays > 0 }
            val activeRepo = repo
            val healthCopy = synchronized(healthLock) { StreamHealthTracker(activeRepo.health.snapshot()) }
            val currentPrefs = _prefs.value
            val priorities = _sources.value.associate { it.id to it.priority }
            val maxH = displayMaxHeight()
            val bw = lastBandwidthBps

            val (nn, sp, guideChans) = synchronized(epgLock) {
                val map = HashMap<String, Pair<EpgProgramme?, EpgProgramme?>>(visible.size)
                for (ch in visible) {
                    val pair = activeRepo.epg.nowAndNext(ch, nowMs)
                    if (pair.first != null || pair.second != null) {
                        map[ch.id] = pair
                    }
                }
                val liveSports = activeRepo.epg.liveSportsNow(visible, nowMs)
                val guides = activeRepo.epg.guideChannels()
                Triple(map, liveSports, guides)
            }

            val mainRows = GuideStreamLinker.mainGuide(
                guideChannels = guideChans,
                canonicalChannels = visible,
                health = healthCopy,
                prefs = currentPrefs,
                sourcePriorities = priorities,
                nowMs = nowMs,
                maxHeight = maxH,
                bandwidthBps = bw,
            )
            Log.i("lumos-diag", "guide=${guideChans.size} visible=${visible.size} main=${mainRows.size} playable=${mainRows.count { it.isPlayable }}")
            mainRows.filter { !it.isPlayable }.take(10).forEach { row ->
                Log.i("lumos-diag", "unlinked guide id=${row.guideChannel.id} name=${row.guideChannel.name}")
            }
            visible.filter { ch ->
                val n = ch.name.lowercase()
                n.contains("bbc") || n.contains("itv") || n.contains("channel 4") || n.contains("channel 5") || n.contains("dave")
            }.take(25).forEach { ch ->
                val s = ch.streams.firstOrNull()
                Log.i("lumos-diag", "visible id=${ch.id} name=${ch.name} raw=${s?.rawName} grp=${s?.groupTitle} streams=${ch.streams.size}")
            }
            val sportsRows = GuideStreamLinker.sportsLive(
                guideChannels = guideChans,
                canonicalChannels = visible,
                health = healthCopy,
                prefs = currentPrefs,
                sourcePriorities = priorities,
                nowMs = nowMs,
                maxHeight = maxH,
                bandwidthBps = bw,
            )
            DerivedGuideState(
                catchupCount = count,
                nowNextMap = nn,
                liveSports = sp,
                mainGuideRows = mainRows,
                sportsLiveRows = sportsRows,
            )
        }
        if (derivedGeneration.get() == gen) {
            _catchupCount.value = derived.catchupCount
            _nowNextByChannel.value = derived.nowNextMap
            _liveSportsNow.value = derived.liveSports
            _mainGuideRows.value = derived.mainGuideRows
            _sportsLiveRows.value = derived.sportsLiveRows
        }
    }

    private fun fetchChannelEpgDebounced(
        channel: CanonicalChannel,
        kind: String,
        lastFetchMap: ConcurrentHashMap<String, Long>,
        skipIfNowPresent: Boolean,
        fetcher: suspend (IptvRepository) -> List<EpgProgramme>,
    ) {
        val now = System.currentTimeMillis()
        val lastAt = lastFetchMap[channel.id] ?: 0L
        if (now - lastAt < CHANNEL_EPG_DEBOUNCE_MS) return

        val inflightKey = "$kind:${channel.id}"
        if (!inflightChannelEpg.add(inflightKey)) return

        scope.launch {
            try {
                val activeRepo = repo
                if (skipIfNowPresent) {
                    val alreadyPresent = synchronized(epgLock) {
                        activeRepo.epg.nowAndNext(channel, System.currentTimeMillis()).first != null
                    }
                    if (alreadyPresent) return@launch
                }

                val progs = runCatching { fetcher(activeRepo) }.getOrDefault(emptyList())
                lastFetchMap[channel.id] = System.currentTimeMillis()
                val hasValid = progs.any { it.endMs > it.startMs && it.title.isNotBlank() }
                if (hasValid) {
                    synchronized(epgLock) {
                        repo.epg.putProviderEpg(progs)
                    }
                    computeAndPublishEpgDerived(_channels.value, System.currentTimeMillis())
                    _epgVersion.value = _epgVersion.value + 1L
                }
            } finally {
                inflightChannelEpg.remove(inflightKey)
            }
        }
    }

    private fun persistCaches(nowMs: Long, targetRepo: IptvRepository, showNonUk: Boolean) {
        val warmSnapshot = synchronized(healthLock) { targetRepo.buildWarmSnapshot(nowMs) }
        val healthJson = synchronized(healthLock) { targetRepo.health.toJson() }
        val epgSnapshot = synchronized(epgLock) {
            EpgCacheSnapshot(
                version = 1,
                updatedAtMs = lastUkEpgFetchedAtMs,
                builtIn = targetRepo.epg.exportBuiltInProgrammes(),
                provider = targetRepo.epg.exportProviderProgrammes(),
                aliases = targetRepo.epg.exportChannelAliases(),
                guideChannels = targetRepo.epg.exportGuideChannels(),
            )
        }
        writeJsonStreamAtomically(cacheFile, IptvWarmSnapshot.serializer(), warmSnapshot)
        writeAtomically(cacheMetaFile, showNonUk.toString())
        cachedShowNonUk = showNonUk
        writeAtomically(healthFile, healthJson)
        writeJsonStreamAtomically(epgCacheFile, EpgCacheSnapshot.serializer(), epgSnapshot)
    }

    private fun storeEnrichment(programmeId: String, enrichment: EpgEnrichment) {
        synchronized(enrichLock) {
            val next = LinkedHashMap(_enrichments.value)
            next[programmeId] = enrichment
            if (next.size > MAX_ENRICHMENTS) {
                val iter = next.entries.iterator()
                while (next.size > MAX_ENRICHMENTS && iter.hasNext()) {
                    iter.next()
                    iter.remove()
                }
            }
            _enrichments.value = next
        }
    }

    private fun normalizeTitleKey(title: String): String =
        buildString(title.length) {
            for (ch in title.lowercase()) {
                if (ch.isLetterOrDigit()) append(ch)
            }
        }

    private fun showToast(context: Context, message: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        } else {
            mainHandler.post { Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show() }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun <T> readJsonStream(file: File, deserializer: DeserializationStrategy<T>): T? {
        if (!file.exists()) return null
        return runCatching {
            BufferedInputStream(FileInputStream(file), STREAM_IO_BUFFER_BYTES).use { input ->
                IptvCodec.json.decodeFromStream(deserializer, input)
            }
        }.getOrNull()
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun <T> writeJsonStreamAtomically(
        file: File,
        serializer: SerializationStrategy<T>,
        value: T,
    ) {
        writeAtomicallyWith(file) { target ->
            BufferedOutputStream(FileOutputStream(target), STREAM_IO_BUFFER_BYTES).use { out ->
                IptvCodec.json.encodeToStream(serializer, value, out)
            }
        }
    }

    private fun writeAtomically(file: File, text: String) {
        writeAtomicallyWith(file) { target -> target.writeText(text) }
    }

    private fun writeAtomicallyWith(file: File, writer: (File) -> Unit) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            try {
                writer(tmp)
                if (!tmp.renameTo(file)) {
                    writer(file)
                    tmp.delete()
                }
            } catch (t: Throwable) {
                tmp.delete()
                throw t
            }
        }
    }

    private fun enabledSourceIds(sources: List<IptvSource>): Set<String> =
        sources.asSequence()
            .filter { it.enabled }
            .map { it.id }
            .toHashSet()

    private fun loadedChannelSourceIds(channels: List<CanonicalChannel>): Set<String> =
        channels.asSequence()
            .flatMap { it.streams.asSequence() }
            .map { it.sourceId }
            .toHashSet()

    private fun displayMaxHeight(): Int {
        val physicalMax = runCatching {
            val dm = appContext.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
            val display = dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            display?.supportedModes?.maxOfOrNull { minOf(it.physicalWidth, it.physicalHeight) } ?: 0
        }.getOrDefault(0)
        val dim = if (physicalMax > 0) physicalMax else {
            val m = appContext.resources.displayMetrics
            minOf(m.widthPixels, m.heightPixels)
        }
        return when {
            dim >= 2000 -> 2160
            dim >= 1000 -> 1080
            else -> 720
        }
    }

    private data class HealthEvent(
        val streamId: String,
        val success: Boolean,
        val nowMs: Long,
        val latencyMs: Long,
    )

    private data class RefreshEpgSnap(
        val builtIn: List<EpgProgramme>,
        val provider: List<EpgProgramme>,
        val aliases: Map<String, String>,
        val guideChannels: List<GuideChannel>,
    )

    private data class RefreshBaseline(
        val health: Map<String, lumos.iptv.StreamHealth>,
        val builtIn: List<EpgProgramme>,
        val provider: List<EpgProgramme>,
        val aliases: Map<String, String>,
        val guideChannels: List<GuideChannel>,
    )

    private data class DerivedGuideState(
        val catchupCount: Int,
        val nowNextMap: Map<String, Pair<EpgProgramme?, EpgProgramme?>>,
        val liveSports: List<Pair<CanonicalChannel, EpgProgramme>>,
        val mainGuideRows: List<GuideChannelRow>,
        val sportsLiveRows: List<GuideChannelRow>,
    )

    companion object {
        private const val TAG = "lumos-live"
        private const val QR_POLL_INTERVAL_MS = 3_000L
        private const val AUTO_REFRESH_COOLDOWN_MS = 60_000L
        private const val STREAM_IO_BUFFER_BYTES = 32_768
        private const val STALE_AFTER_MS = 30 * 60_000L
        private const val EPG_STALE_AFTER_MS = 2 * 3_600_000L
        private const val EPG_PAST_WINDOW_MS = 24L * 3_600_000L
        private const val EPG_FUTURE_WINDOW_MS = 120L * 3_600_000L
        private const val CHANNEL_EPG_DEBOUNCE_MS = 12 * 60_000L
        private const val ENRICH_DEBOUNCE_MS = 10 * 60_000L
        private const val CONFLICT_CHECK_TIMEOUT_MS = 1_200L
        private const val MAX_ENRICHMENTS = 250
        private const val MAX_RECENTS = 20
    }
}

/** The guide's category for a provider channel (the UK guide's own words: Entertainment, Sports, Movies, News, Kids) */
fun guideCategoryFor(channel: CanonicalChannel): String = when (channel.category) {
    lumos.iptv.ChannelCategory.SPORTS, lumos.iptv.ChannelCategory.EVENTS -> "Sports"
    lumos.iptv.ChannelCategory.MOVIES -> "Movies"
    lumos.iptv.ChannelCategory.NEWS -> "News"
    lumos.iptv.ChannelCategory.KIDS -> "Kids"
    lumos.iptv.ChannelCategory.DOCUMENTARY -> "Documentaries"
    lumos.iptv.ChannelCategory.MUSIC -> "Music"
    else -> "Entertainment"
}
