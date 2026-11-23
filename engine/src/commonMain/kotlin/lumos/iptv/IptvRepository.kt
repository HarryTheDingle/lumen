package lumos.iptv

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.http.parseUrl
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.concurrent.Volatile

/** Per-source outcome report produced when refreshing IPTV playlists and portals. */
@Serializable
data class SourceLoadReport(
    val sourceId: String,
    val sourceName: String,
    val success: Boolean,
    val streamCount: Int,
    val error: String? = null,
    val accountInfo: SourceAccountInfo? = null,
    val discoveredEpgUrls: List<String> = emptyList(),
)

/** Combined channel lineup and per-source load diagnostics. */
data class IptvLoadResult(
    val channels: List<CanonicalChannel>,
    val reports: List<SourceLoadReport>,
)

/** Downloaded provider EPG payloads ready for synchronized ingestion into [EpgStore]. */
data class ProviderEpgBatch(
    val xmltvResults: List<XmltvParseResult>,
    val providerProgrammes: List<EpgProgramme>,
) {
    val totalProgrammes: Int
        get() = xmltvResults.sumOf { it.programmes.size } + providerProgrammes.size
}

/** Serializable cold-start cache snapshot of normalized channels, stream health, and account info. */
@Serializable
data class IptvWarmSnapshot(
    val version: Int = CURRENT_VERSION,
    val savedAtMs: Long = 0L,
    val channels: List<CanonicalChannel> = emptyList(),
    val health: Map<String, StreamHealth> = emptyMap(),
    val accountInfos: Map<String, SourceAccountInfo> = emptyMap(),
) {
    companion object {
        const val CURRENT_VERSION: Int = 2
    }
}

/**
 * Coordinates multi-source parallel playlist loading, EPG ingestion, Stalker token caching,
 * live/catch-up stream resolution, and warm snapshot persistence.
 */
class IptvRepository(
    private val http: HttpClient,
    val health: StreamHealthTracker = StreamHealthTracker(),
    val epg: EpgStore = EpgStore(),
) {
    private val xtream = XtreamClient(http)
    private val stalker = StalkerClient(http)
    private val tokenMutex = Mutex()
    private val stalkerTokens = mutableMapOf<String, String>()

    @Volatile
    private var sources: List<IptvSource> = emptyList()
    private var rawStreams: List<StreamSource> = emptyList()
    private var channels: List<CanonicalChannel> = emptyList()
    private val accountInfos = mutableMapOf<String, SourceAccountInfo>()
    private val discoveredEpgUrls = linkedSetOf<String>()

    fun currentChannels(): List<CanonicalChannel> = channels

    fun currentSources(): List<IptvSource> = sources

    fun accountInfo(sourceId: String): SourceAccountInfo? = accountInfos[sourceId]

    /**
     * Checks live Xtream connection usage against [IptvSource.Xtream] limits, discounting one
     * slot when [ownSourceActiveRecently] indicates this device may still hold a recent socket.
     * Never throws; returns `null` for non-Xtream sources or on network/API failure.
     */
    suspend fun checkXtreamConnectionConflict(
        source: IptvSource,
        ownSourceActiveRecently: Boolean,
    ): ActiveViewerConflict? {
        val xtreamSource = (source as? IptvSource.Xtream)
            ?: (sources.firstOrNull { it.id == source.id } as? IptvSource.Xtream)
            ?: return null
        val info = runCatching { xtream.authenticate(xtreamSource) }.getOrNull() ?: return null
        val rawActive = info.activeConnections
        val adjusted = if (ownSourceActiveRecently) (rawActive - 1).coerceAtLeast(0) else rawActive
        val max = info.maxConnections
        if (max > 0 && adjusted >= max) {
            return ActiveViewerConflict(
                sourceId = xtreamSource.id,
                sourceName = xtreamSource.name,
                activeConnections = rawActive,
                maxConnections = max,
                reason = "All $max stream slot(s) active ($rawActive/$max in use)",
            )
        }
        return null
    }

    /** Updates active source definitions (used after warm snapshot restoration prior to full refresh). */
    fun updateSources(sources: List<IptvSource>) {
        this.sources = sources
    }

    /**
     * Loads all enabled [sources] in parallel with strict fault isolation so a failing source
     * never cancels healthy sources, then normalizes and merges the combined channel lineup.
     */
    suspend fun loadSources(
        sources: List<IptvSource>,
        prefs: IptvProfilePrefs = IptvProfilePrefs(),
        nowMs: Long = 0L,
        maxHeight: Int = 2160,
        bandwidthBps: Long? = null,
    ): IptvLoadResult = coroutineScope {
        this@IptvRepository.sources = sources
        val enabledIndices = sources.indices.filter { sources[it].enabled }
        val skipForeign = !prefs.showNonUk

        val outcomes = enabledIndices.map { index ->
            async { index to loadSingleSource(sources[index], skipForeign = skipForeign) }
        }.awaitAll()

        val resolvedSources = sources.toMutableList()
        val loadedStreams = mutableListOf<StreamSource>()
        val reports = mutableListOf<SourceLoadReport>()
        discoveredEpgUrls.clear()

        for ((index, outcome) in outcomes) {
            resolvedSources[index] = outcome.effectiveSource
            reports.add(outcome.report)
            outcome.report.accountInfo?.let { accountInfos[outcome.report.sourceId] = it }
            discoveredEpgUrls.addAll(outcome.report.discoveredEpgUrls)
            loadedStreams.addAll(outcome.streams)
        }

        val effectiveSources = resolvedSources.toList()
        this@IptvRepository.sources = effectiveSources
        rawStreams = loadedStreams
        channels = ChannelNormalizer.normalizeAndMerge(
            sources = effectiveSources,
            rawStreams = loadedStreams,
            health = health,
            prefs = prefs,
            nowMs = nowMs,
            maxHeight = maxHeight,
            bandwidthBps = bandwidthBps,
        )

        IptvLoadResult(channels = channels, reports = reports)
    }

    /** Re-merges and re-ranks already loaded streams after profile prefs or health changes. */
    fun renormalize(
        prefs: IptvProfilePrefs = IptvProfilePrefs(),
        nowMs: Long = 0L,
        maxHeight: Int = 2160,
        bandwidthBps: Long? = null,
    ): List<CanonicalChannel> {
        channels = ChannelNormalizer.normalizeAndMerge(
            sources = sources,
            rawStreams = rawStreams,
            health = health,
            prefs = prefs,
            nowMs = nowMs,
            maxHeight = maxHeight,
            bandwidthBps = bandwidthBps,
        )
        return channels
    }

    /** Loads curated built-in UK EPG JSON (array, `/epg/uk` object, or `{"channels": {...}}`) into [epg]. */
    fun loadUkEpgJson(jsonText: String): Int {
        if (jsonText.isBlank()) return 0
        val root = iptvJson.parseToJsonElement(jsonText)
        val programmes = mutableListOf<EpgProgramme>()
        var guideChannels: List<GuideChannel>? = null

        when (root) {
            is JsonArray -> parseJsonProgrammeList(root, fallbackChannelId = null, out = programmes)
            is JsonObject -> {
                root["programmes"]?.arr()?.takeIf { it.isNotEmpty() }?.let { arr ->
                    parseJsonProgrammeList(arr, fallbackChannelId = null, out = programmes)
                }
                when (val channelsElem = root["channels"]) {
                    is JsonArray -> guideChannels = parseGuideChannelList(channelsElem)
                    is JsonObject -> {
                        for ((channelId, listElem) in channelsElem) {
                            parseJsonProgrammeList(listElem.arr(), fallbackChannelId = channelId, out = programmes)
                        }
                    }
                    else -> Unit
                }
            }
            else -> Unit
        }

        guideChannels?.let(epg::putGuideChannels)
        epg.putBuiltInUkEpg(programmes)
        return programmes.size
    }

    /** Downloads and parses an XMLTV guide from [url] (bounded by [MAX_XMLTV_BYTES]) and merges it into [epg]. */
    suspend fun loadXmltv(url: String, userAgent: String? = null): Int {
        val parsed = fetchXmltvBounded(url, userAgent) ?: return 0
        epg.putXmltv(parsed)
        return parsed.programmes.size
    }

    /**
     * Fetches XMLTV (explicit source `epgUrl` and M3U header EPG URLs) and Stalker portal EPG guides
     * across enabled sources in parallel without mutating [epg] directly.
     */
    suspend fun fetchProviderEpgBatch(sources: List<IptvSource> = this.sources): ProviderEpgBatch = coroutineScope {
        val xmltvRequests = linkedMapOf<String, String?>()
        for (source in sources) {
            if (!source.enabled) continue
            source.epgUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { url ->
                if (url !in xmltvRequests) xmltvRequests[url] = source.userAgent
            }
        }
        for (url in discoveredEpgUrls) {
            if (url !in xmltvRequests) xmltvRequests[url] = null
        }

        val xmlJobs = xmltvRequests.entries.map { (url, ua) ->
            async { runCatching { fetchXmltvBounded(url, ua) }.getOrNull() }
        }
        val stalkerJobs = sources.filter { it.enabled && it is IptvSource.Stalker }.map { src ->
            async {
                runCatching {
                    val s = src as IptvSource.Stalker
                    val token = getOrAcquireStalkerToken(s)
                    stalker.fetchEpg(s, token)
                }.getOrDefault(emptyList())
            }
        }

        val xmltvResults = xmlJobs.awaitAll().filterNotNull()
        val stalkerProgrammes = stalkerJobs.awaitAll().flatten()
        ProviderEpgBatch(
            xmltvResults = xmltvResults,
            providerProgrammes = stalkerProgrammes,
        )
    }

    /**
     * Fetches XMLTV and Stalker portal EPG guides across enabled sources in parallel with fault isolation,
     * returning the total number of provider programmes ingested.
     */
    suspend fun loadProviderEpg(sources: List<IptvSource> = this.sources): Int {
        val batch = fetchProviderEpgBatch(sources)
        batch.xmltvResults.forEach(epg::putXmltv)
        if (batch.providerProgrammes.isNotEmpty()) {
            epg.putProviderEpg(batch.providerProgrammes)
        }
        return batch.totalProgrammes
    }

    /** Loads Xtream short EPG for the first Xtream stream on [channel] and optionally merges it into [epg]. */
    suspend fun loadShortEpg(
        channel: CanonicalChannel,
        limit: Int = 6,
        ingest: Boolean = true,
    ): List<EpgProgramme> {
        val stream = channel.streams.firstOrNull { it.xtreamStreamId != null } ?: return emptyList()
        val source = sources.firstOrNull { it.id == stream.sourceId } as? IptvSource.Xtream ?: return emptyList()
        val streamId = stream.xtreamStreamId ?: return emptyList()
        val channelKey = channel.epgId ?: channel.id
        val progs = xtream.fetchShortEpg(source, streamId, limit = limit, channelIdOverride = channelKey)
        if (ingest && progs.isNotEmpty()) {
            epg.putProviderEpg(progs)
        }
        return progs
    }

    /** Loads Xtream archive EPG for [channel] and optionally merges it into [epg]. */
    suspend fun loadArchiveEpg(
        channel: CanonicalChannel,
        ingest: Boolean = true,
    ): List<EpgProgramme> {
        val stream = channel.streams.firstOrNull { it.xtreamStreamId != null } ?: return emptyList()
        val source = sources.firstOrNull { it.id == stream.sourceId } as? IptvSource.Xtream ?: return emptyList()
        val streamId = stream.xtreamStreamId ?: return emptyList()
        val channelKey = channel.epgId ?: channel.id
        val progs = xtream.fetchArchiveEpg(source, streamId, channelIdOverride = channelKey)
        if (ingest && progs.isNotEmpty()) {
            epg.putProviderEpg(progs)
        }
        return progs
    }

    /** Resolves a live [StreamSource] into a concrete playable URL and headers (handling Stalker tokens). */
    suspend fun resolveLiveStream(stream: StreamSource): ResolvedStream {
        val source = sources.firstOrNull { it.id == stream.sourceId }
        val resolvedUrl = if (stream.requiresResolve && source is IptvSource.Stalker) {
            val token = getOrAcquireStalkerToken(source)
            val cmd = stream.stalkerCmd ?: stream.streamUrl
            val (url, refreshedToken) = stalker.resolveStreamUrl(source, token, cmd)
            tokenMutex.withLock { stalkerTokens[source.id] = refreshedToken }
            url
        } else {
            stream.streamUrl
        }
        return ResolvedStream(
            url = resolvedUrl,
            headers = effectiveHeaders(stream, source),
            streamSource = stream,
            isCatchup = false,
        )
    }

    /** Resolves a catch-up stream URL for `[startMs, endMs]` across M3U, Xtream, and Stalker sources. */
    suspend fun resolveCatchupStream(
        stream: StreamSource,
        startMs: Long,
        endMs: Long,
        nowMs: Long,
    ): ResolvedStream? {
        val catchup = stream.catchup ?: return null
        if (catchup.days <= 0 || endMs <= startMs) return null
        val source = sources.firstOrNull { it.id == stream.sourceId }

        val url = if (catchup.mode == CatchupMode.STALKER) {
            if (source !is IptvSource.Stalker) return null
            val streamId = catchup.streamId ?: return null
            val durationSec = ((endMs - startMs) / 1000L).coerceAtLeast(1L).toInt()
            val token = getOrAcquireStalkerToken(source)
            val (resolvedUrl, refreshedToken) = stalker.resolveCatchupUrl(source, token, streamId, startMs, durationSec)
            tokenMutex.withLock { stalkerTokens[source.id] = refreshedToken }
            resolvedUrl
        } else {
            CatchupUrlBuilder.buildM3uOrXtreamCatchupUrl(stream, source, startMs, endMs, nowMs) ?: return null
        }

        return ResolvedStream(
            url = url,
            headers = effectiveHeaders(stream, source),
            streamSource = stream,
            isCatchup = true,
        )
    }

    /** Builds a serializable warm-start snapshot of current channels, stream health, and account metadata. */
    fun buildWarmSnapshot(nowMs: Long = 0L): IptvWarmSnapshot = IptvWarmSnapshot(
        version = IptvWarmSnapshot.CURRENT_VERSION,
        savedAtMs = nowMs,
        channels = channels,
        health = health.snapshot(),
        accountInfos = accountInfos.toMap(),
    )

    /** Serializes current channels, stream health, and account metadata to a warm-start JSON snapshot. */
    fun exportWarmSnapshot(nowMs: Long = 0L): String =
        iptvJson.encodeToString(buildWarmSnapshot(nowMs))

    /** Restores channels, health metrics, and account info from a decoded [IptvWarmSnapshot]. */
    fun restoreWarmSnapshot(snapshot: IptvWarmSnapshot): Boolean {
        if (snapshot.version != IptvWarmSnapshot.CURRENT_VERSION) return false
        channels = snapshot.channels
        health.restore(snapshot.health)
        accountInfos.clear()
        accountInfos.putAll(snapshot.accountInfos)
        return snapshot.channels.isNotEmpty()
    }

    /** Restores channels, health metrics, and account info from a warm JSON snapshot. */
    fun restoreWarmSnapshot(rawJson: String): Boolean {
        val snapshot = runCatching {
            iptvJson.decodeFromString<IptvWarmSnapshot>(rawJson)
        }.getOrNull() ?: return false
        return restoreWarmSnapshot(snapshot)
    }

    private suspend fun loadSingleSource(
        source: IptvSource,
        skipForeign: Boolean,
    ): SingleSourceOutcome {
        return try {
            when (source) {
                is IptvSource.M3u -> loadM3uSource(source, skipForeign = skipForeign)
                is IptvSource.Xtream -> loadXtreamSource(source, skipForeign = skipForeign)
                is IptvSource.Stalker -> loadStalkerSource(source, skipForeign = skipForeign)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            SingleSourceOutcome(
                streams = emptyList(),
                report = SourceLoadReport(
                    sourceId = source.id,
                    sourceName = source.name,
                    success = false,
                    streamCount = 0,
                    error = sanitizeSourceError(source.name, e),
                ),
                effectiveSource = source,
            )
        }
    }

    private suspend fun loadM3uSource(
        source: IptvSource.M3u,
        skipForeign: Boolean,
    ): SingleSourceOutcome {
        val content = try {
            source.inlineContent
                ?: source.url?.let { fetchText(it, source.userAgent) }
                ?: error("M3U source '${source.name}' has neither url nor inlineContent")
        } catch (e: HttpStatusException) {
            val xtreamFallback = parseXtreamGetPhpSource(source) ?: throw e
            return try {
                loadXtreamSource(xtreamFallback, skipForeign = skipForeign)
            } catch (fallbackError: Throwable) {
                if (fallbackError is CancellationException) throw fallbackError
                throw e
            }
        }

        val parsed = M3uParser.parse(content, source, skipForeign = skipForeign)
        val epgUrls = buildList {
            source.epgUrl?.takeIf { it.isNotBlank() }?.let(::add)
            addAll(parsed.headerEpgUrls)
        }.distinct()
        return SingleSourceOutcome(
            streams = parsed.streams,
            report = SourceLoadReport(
                sourceId = source.id,
                sourceName = source.name,
                success = true,
                streamCount = parsed.streams.size,
                discoveredEpgUrls = epgUrls,
            ),
            effectiveSource = source,
        )
    }

    private suspend fun loadXtreamSource(
        source: IptvSource.Xtream,
        skipForeign: Boolean,
    ): SingleSourceOutcome = coroutineScope {
        val authDeferred = async { runCatching { xtream.authenticate(source) }.getOrNull() }
        val streams = xtream.fetchLiveStreams(source, skipForeign = skipForeign)
        val info = authDeferred.await()
        val epgUrls = listOfNotNull(source.epgUrl?.trim()?.takeIf { it.isNotEmpty() })
        SingleSourceOutcome(
            streams = streams,
            report = SourceLoadReport(
                sourceId = source.id,
                sourceName = source.name,
                success = true,
                streamCount = streams.size,
                accountInfo = info,
                discoveredEpgUrls = epgUrls,
            ),
            effectiveSource = source,
        )
    }

    private suspend fun loadStalkerSource(
        source: IptvSource.Stalker,
        skipForeign: Boolean,
    ): SingleSourceOutcome {
        var token = getOrAcquireStalkerToken(source)
        val streams = try {
            stalker.fetchAllChannels(source, token, skipForeign = skipForeign)
        } catch (_: Exception) {
            token = stalker.handshake(source)
            tokenMutex.withLock { stalkerTokens[source.id] = token }
            stalker.fetchAllChannels(source, token, skipForeign = skipForeign)
        }
        val epgUrls = listOfNotNull(source.epgUrl?.takeIf { it.isNotBlank() })
        return SingleSourceOutcome(
            streams = streams,
            report = SourceLoadReport(
                sourceId = source.id,
                sourceName = source.name,
                success = true,
                streamCount = streams.size,
                discoveredEpgUrls = epgUrls,
            ),
            effectiveSource = source,
        )
    }

    private suspend fun fetchXmltvBounded(url: String, userAgent: String?): XmltvParseResult? {
        val resp = http.get(url) {
            if (!userAgent.isNullOrBlank()) header(HttpHeaders.UserAgent, userAgent)
        }
        if (!resp.status.isSuccess()) {
            error("XMLTV HTTP ${resp.status.value}")
        }

        val declaredLen = resp.contentLength()
        val channel = resp.bodyAsChannel()
        if (declaredLen != null && declaredLen > MAX_XMLTV_BYTES) {
            runCatching { channel.cancel(null) }
            return null
        }

        val limit = MAX_XMLTV_BYTES.toInt()
        val initialCap = declaredLen?.toInt()?.coerceIn(8_192, limit) ?: XMLTV_CHUNK_BYTES
        var acc = ByteArray(initialCap)
        var total = 0
        val chunk = ByteArray(XMLTV_CHUNK_BYTES)

        try {
            while (true) {
                val read = channel.readAvailable(chunk, 0, chunk.size)
                if (read < 0) break
                if (read == 0) {
                    if (!channel.awaitContent(1)) break
                    continue
                }
                if (total.toLong() + read > MAX_XMLTV_BYTES) {
                    runCatching { channel.cancel(null) }
                    return null
                }
                val needed = total + read
                if (needed > acc.size) {
                    val nextCap = minOf(limit, maxOf(acc.size * 2, needed))
                    acc = acc.copyOf(nextCap)
                }
                chunk.copyInto(acc, destinationOffset = total, startIndex = 0, endIndex = read)
                total = needed
            }
        } finally {
            if (!channel.isClosedForRead) {
                runCatching { channel.cancel(null) }
            }
        }

        if (total == 0) return null
        val xmlText = acc.decodeToString(0, total)
        return XmltvParser.parse(xmlText)
    }

    private suspend fun getOrAcquireStalkerToken(source: IptvSource.Stalker): String {
        tokenMutex.withLock {
            stalkerTokens[source.id]?.let { return it }
        }
        val fresh = stalker.handshake(source)
        tokenMutex.withLock {
            stalkerTokens[source.id] = fresh
        }
        return fresh
    }

    internal suspend fun fetchText(url: String, userAgent: String? = null): String {
        val customUa = userAgent?.trim()?.takeIf { it.isNotEmpty() }
        val candidates = if (customUa != null) {
            listOf(customUa)
        } else {
            listOf(DEFAULT_PLAYER_USER_AGENT) + FALLBACK_PLAYER_USER_AGENTS
        }

        var lastStatus = 0
        for ((index, ua) in candidates.withIndex()) {
            val resp = http.get(url) {
                header(HttpHeaders.UserAgent, ua)
            }
            if (resp.status.isSuccess()) {
                return resp.bodyAsText()
            }
            lastStatus = resp.status.value
            val canRetry = customUa == null &&
                lastStatus in RETRYABLE_USER_AGENT_STATUSES &&
                index < candidates.lastIndex
            if (!canRetry) break
        }

        val message = if (lastStatus == 884) HTTP_884_ERROR_MESSAGE else "HTTP $lastStatus"
        throw HttpStatusException(lastStatus, message)
    }

    private fun effectiveHeaders(stream: StreamSource, source: IptvSource?): Map<String, String> {
        val merged = linkedMapOf<String, String>()
        source?.userAgent?.takeIf { it.isNotBlank() }?.let { merged["User-Agent"] = it }
        merged.putAll(stream.headers)
        return merged
    }

    private fun parseJsonProgrammeList(
        elements: List<JsonElement>,
        fallbackChannelId: String?,
        out: MutableList<EpgProgramme>,
    ) {
        for (el in elements) {
            val obj = el.obj() ?: continue
            val chId = obj.str("channelId")
                ?: obj.str("channel_id")
                ?: obj.str("channel")
                ?: fallbackChannelId
                ?: continue
            val title = obj.str("title") ?: obj.str("name") ?: continue

            val startMs = obj.long("startMs")
                ?: obj.long("start_ms")
                ?: obj.long("start_timestamp")?.let { it * 1000L }
                ?: obj.str("start")?.let { parseIsoUtcMillis(it) ?: parseXmltvTime(it) }
                ?: continue
            val endMs = obj.long("endMs")
                ?: obj.long("end_ms")
                ?: obj.long("stop_timestamp")?.let { it * 1000L }
                ?: (obj.str("end") ?: obj.str("stop"))?.let { parseIsoUtcMillis(it) ?: parseXmltvTime(it) }
                ?: continue
            if (endMs <= startMs) continue

            val subtitle = obj.str("subtitle") ?: obj.str("sub_title")
            val desc = obj.str("description") ?: obj.str("desc")
            val category = obj.str("category") ?: obj.str("genre")
            val iconUrl = obj.str("iconUrl") ?: obj.str("icon_url") ?: obj.str("icon")
            val (detectedSport, detectedTag) = detectSportTag(title, category = category, subtitle = subtitle)
            val isLiveSport = obj.bool("isLiveSport") ?: obj.bool("is_live_sport") ?: obj.bool("isSport") ?: detectedSport
            val sportTag = obj.str("sportTag") ?: obj.str("sport_tag") ?: detectedTag
            val hasCatchup = obj.bool("hasCatchup") ?: obj.bool("has_catchup") ?: false

            out.add(
                EpgProgramme(
                    id = obj.str("id") ?: "$chId:$startMs",
                    channelId = chId,
                    title = title,
                    subtitle = subtitle,
                    description = desc,
                    category = category,
                    startMs = startMs,
                    endMs = endMs,
                    iconUrl = iconUrl,
                    isLiveSport = isLiveSport,
                    sportTag = sportTag,
                    hasCatchup = hasCatchup,
                )
            )
        }
    }

    private fun parseGuideChannelList(elements: List<JsonElement>): List<GuideChannel> {
        val out = ArrayList<GuideChannel>(elements.size)
        for (el in elements) {
            val obj = el.obj() ?: continue
            val id = obj.str("id") ?: continue
            val name = obj.str("name") ?: id
            val category = obj.str("category") ?: ""
            val number = obj.int("number")
            val logoUrl = obj.str("logoUrl") ?: obj.str("logo")
            val reg = UkChannelRegistry.findById(id)
            val mainGuide = obj.bool("mainGuide") ?: obj.bool("main_guide") ?: true
            val sportsGuide = obj.bool("sportsGuide") ?: obj.bool("sports_guide")
                ?: (category.equals("Sports", ignoreCase = true) || reg?.category == ChannelCategory.SPORTS)
            out.add(
                GuideChannel(
                    id = id,
                    name = name,
                    category = category,
                    number = number,
                    logoUrl = logoUrl,
                    mainGuide = mainGuide,
                    sportsGuide = sportsGuide,
                )
            )
        }
        return out
    }

    private data class SingleSourceOutcome(
        val streams: List<StreamSource>,
        val report: SourceLoadReport,
        val effectiveSource: IptvSource,
    )

    private class HttpStatusException(
        val statusCode: Int,
        message: String,
    ) : IllegalStateException(message)

    companion object {
        const val MAX_XMLTV_BYTES = 10L * 1024 * 1024
        private const val XMLTV_CHUNK_BYTES = 32_768
        internal const val DEFAULT_PLAYER_USER_AGENT = "IPTVSmartersPlayer"
        private val FALLBACK_PLAYER_USER_AGENTS = listOf("VLC/3.0.20", "TiviMate/4.7.0")
        private val RETRYABLE_USER_AGENT_STATUSES = setOf(403, 406, 458, 884)
        internal const val HTTP_884_ERROR_MESSAGE =
            "Provider blocked M3U download (HTTP 884) — try adding this source using Xtream Codes login instead"
        private val URL_STRIP_REGEX = Regex("""https?://([^/\s\]"']+)[^\s\]"']*""", RegexOption.IGNORE_CASE)
        private val CREDENTIAL_PARAM_REGEX =
            Regex("""(?i)\b(username|password|user|pass|token|apikey|api_key|mac)=[^&\s,\]]+""")

        /**
         * Formats a source failure into a user-safe message with timeout condensation
         * and URL path/query credential redaction.
         */
        internal fun sanitizeSourceError(sourceName: String, error: Throwable): String {
            val raw = error.message?.trim().orEmpty()
            if (
                raw.contains("connect timeout", ignoreCase = true) ||
                raw.contains("request timeout", ignoreCase = true) ||
                raw.contains("socket timeout", ignoreCase = true)
            ) {
                return "$sourceName: Connection timed out (check internet or VPN)"
            }
            val sanitized = raw
                .replace(URL_STRIP_REGEX, "$1")
                .replace(CREDENTIAL_PARAM_REGEX, "$1=***")
                .trim()
            return sanitized.ifBlank { "Failed to load $sourceName" }
        }

        internal fun parseXtreamGetPhpSource(source: IptvSource.M3u): IptvSource.Xtream? {
            val rawUrl = source.url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val parsed = parseUrl(rawUrl) ?: return null
            if (parsed.protocol != URLProtocol.HTTP && parsed.protocol != URLProtocol.HTTPS) return null
            if (parsed.host.isBlank()) return null
            if (!parsed.encodedPath.endsWith("/get.php", ignoreCase = true)) return null

            val username = parsed.parameters["username"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val password = parsed.parameters["password"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null

            val beforeQuery = rawUrl.substringBefore('?').substringBefore('#')
            val getPhpIdx = beforeQuery.lastIndexOf("/get.php", ignoreCase = true)
            if (getPhpIdx < 0) return null
            val serverUrl = beforeQuery.substring(0, getPhpIdx).trimEnd('/')
            if (serverUrl.isBlank()) return null

            val rawOutput = parsed.parameters["output"]?.trim()?.lowercase()?.removePrefix(".").orEmpty()
            val outputFormat = when (rawOutput) {
                "hls", "m3u8" -> "m3u8"
                "", "mpegts", "ts" -> "ts"
                else -> rawOutput
            }

            return IptvSource.Xtream(
                id = source.id,
                name = source.name,
                enabled = source.enabled,
                maxConnections = source.maxConnections,
                userAgent = source.userAgent,
                epgUrl = source.epgUrl,
                priority = source.priority,
                serverUrl = serverUrl,
                username = username,
                password = password,
                outputFormat = outputFormat,
            )
        }
    }
}
