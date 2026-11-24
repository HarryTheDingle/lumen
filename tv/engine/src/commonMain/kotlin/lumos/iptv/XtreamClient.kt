package lumos.iptv

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * KMP-safe serializer for PHP-backed Xtream JSON fields that may arrive as strings, numbers,
 * booleans (`false` for unset, `true` for `"1"`), or `null`.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object FlexStringSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("lumos.iptv.FlexString", PrimitiveKind.STRING).nullable

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }

    override fun deserialize(decoder: Decoder): String? {
        val jsonDecoder = decoder as? JsonDecoder
        if (jsonDecoder != null) {
            val element = jsonDecoder.decodeJsonElement()
            if (element is JsonNull) return null
            val primitive = element as? JsonPrimitive ?: return null
            if (!primitive.isString) {
                val bool = primitive.booleanOrNull
                if (bool != null) return if (bool) "1" else null
            }
            return primitive.content
        }
        return if (decoder.decodeNotNullMark()) decoder.decodeString() else decoder.decodeNull()
    }
}

@Serializable
internal data class XtreamCategoryDto(
    @Serializable(with = FlexStringSerializer::class)
    val category_id: String? = null,
    @Serializable(with = FlexStringSerializer::class)
    val category_name: String? = null,
)

@Serializable
internal data class XtreamLiveStreamDto(
    @Serializable(with = FlexStringSerializer::class)
    val stream_id: String? = null,
    @Serializable(with = FlexStringSerializer::class)
    val category_id: String? = null,
    @Serializable(with = FlexStringSerializer::class)
    val category_name: String? = null,
    @Serializable(with = FlexStringSerializer::class)
    val name: String? = null,
    @Serializable(with = FlexStringSerializer::class)
    val epg_channel_id: String? = null,
    @Serializable(with = FlexStringSerializer::class)
    val stream_icon: String? = null,
    @Serializable(with = FlexStringSerializer::class)
    val tv_archive: String? = null,
    @Serializable(with = FlexStringSerializer::class)
    val tv_archive_duration: String? = null,
    @Serializable(with = FlexStringSerializer::class)
    val is_adult: String? = null,
)

/** Ktor client for Xtream Codes `player_api.php` and `xmltv.php` endpoints. */
class XtreamClient(private val http: HttpClient) {
    /** Authenticates credentials and returns [SourceAccountInfo] for [source]. */
    suspend fun authenticate(source: IptvSource.Xtream): SourceAccountInfo {
        val root = getJson(playerApiUrl(source), source.userAgent)?.obj()
            ?: error("Invalid Xtream authentication response")
        val userInfo = root["user_info"].obj() ?: error("Missing user_info in Xtream response")
        val status = userInfo.str("status")
        val auth = userInfo.int("auth")
        if (auth == 0 && status == null) {
            error("Xtream authentication rejected")
        }
        val serverInfo = root["server_info"].obj()
        return SourceAccountInfo(
            sourceId = source.id,
            status = status ?: if (auth == 1) "Active" else "Unknown",
            activeConnections = userInfo.int("active_cons") ?: 0,
            maxConnections = userInfo.int("max_connections") ?: source.maxConnections,
            expiresAtMs = userInfo.long("exp_date")?.takeIf { it > 0L }?.let { it * 1000L },
            serverTimezone = serverInfo?.str("timezone"),
        )
    }

    /** Fetches live categories and live channels, building playable [StreamSource]s with catch-up config. */
    suspend fun fetchLiveStreams(
        source: IptvSource.Xtream,
        skipForeign: Boolean = false,
    ): List<StreamSource> = coroutineScope {
        val categoriesDeferred = async { fetchCategories(source) }
        val base = normalizeBaseUrl(source.serverUrl)
        val user = urlEncode(source.username)
        val pass = urlEncode(source.password)
        val headers = source.userAgent?.takeIf { it.isNotBlank() }?.let { mapOf("User-Agent" to it) } ?: emptyMap()

        http.prepareGet(playerApiUrl(source, "get_live_streams")) {
            header(HttpHeaders.UserAgent, effectiveUserAgent(source.userAgent))
        }.execute { resp ->
            if (!resp.status.isSuccess()) {
                error("Xtream HTTP ${resp.status.value}")
            }
            // Wait for categories before reading streams so foreignCategoryIds filters items on the fly.
            val categories = categoriesDeferred.await()
            val foreignCategoryIds = if (skipForeign) {
                buildSet {
                    for ((id, name) in categories) {
                        if (ChannelNormalizer.isFilteredGroup(name)) add(id)
                    }
                }
            } else {
                emptySet()
            }
            val foreignGroupMemo = if (skipForeign) HashMap<String, Boolean>() else null

            val results = mutableListOf<StreamSource>()
            val channel = resp.bodyAsChannel()
            try {
                streamJsonObjects(channel) { objJson ->
                    val dto = runCatching { iptvJson.decodeFromString<XtreamLiveStreamDto>(objJson) }.getOrNull()
                        ?: return@streamJsonObjects
                    val stream = mapLiveStreamDto(
                        dto = dto,
                        source = source,
                        skipForeign = skipForeign,
                        categories = categories,
                        foreignCategoryIds = foreignCategoryIds,
                        foreignGroupMemo = foreignGroupMemo,
                        base = base,
                        user = user,
                        pass = pass,
                        headers = headers,
                    )
                    if (stream != null) results += stream
                }
            } finally {
                if (!channel.isClosedForRead) {
                    runCatching { channel.cancel(null) }
                }
            }
            results
        }
    }

    private suspend fun fetchCategories(source: IptvSource.Xtream): Map<String, String> = try {
        val body = getText(playerApiUrl(source, "get_live_categories"), source.userAgent)
        decodeXtreamList<XtreamCategoryDto>(body)
            .mapNotNull { cat ->
                val id = cat.category_id?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val name = cat.category_name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                id to name
            }
            .toMap()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        emptyMap()
    }

    private fun mapLiveStreamDto(
        dto: XtreamLiveStreamDto,
        source: IptvSource.Xtream,
        skipForeign: Boolean,
        categories: Map<String, String>,
        foreignCategoryIds: Set<String>,
        foreignGroupMemo: HashMap<String, Boolean>?,
        base: String,
        user: String,
        pass: String,
        headers: Map<String, String>,
    ): StreamSource? {
        if (skipForeign) {
            val adult = dto.is_adult?.trim()?.lowercase()
            if (adult == "1" || adult == "true") return null
        }

        val categoryId = dto.category_id?.trim()?.takeIf { it.isNotBlank() }
        if (skipForeign && categoryId != null && categoryId in foreignCategoryIds) return null

        val streamId = dto.stream_id?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val rawName = dto.name?.trim()?.takeIf { it.isNotBlank() } ?: "Channel $streamId"
        if (skipForeign && ChannelNormalizer.isForeignRawName(rawName)) return null

        val groupTitle = categoryId?.let { categories[it] }
            ?: dto.category_name?.trim()?.takeIf { it.isNotBlank() }
            ?: ""
        if (foreignGroupMemo != null && (categoryId == null || categoryId !in categories)) {
            val foreignGroup = foreignGroupMemo.getOrPut(groupTitle) {
                ChannelNormalizer.isFilteredGroup(groupTitle)
            }
            if (foreignGroup) return null
        }

        val tvgId = dto.epg_channel_id?.trim()?.takeIf { it.isNotBlank() }
        val logoUrl = dto.stream_icon?.trim()?.takeIf { it.isNotBlank() }

        val tvArchiveFlag = dto.tv_archive?.trim()?.lowercase()
        val tvArchive = tvArchiveFlag == "1" || tvArchiveFlag == "true"
        val archiveDays = dto.tv_archive_duration?.trim()?.toIntOrNull() ?: 0
        val catchup = if (tvArchive && archiveDays > 0) {
            CatchupConfig(mode = CatchupMode.XTREAM, days = archiveDays, streamId = streamId)
        } else {
            null
        }

        return StreamSource(
            id = "${source.id}:xtream:$streamId",
            sourceId = source.id,
            sourceName = source.name,
            rawName = rawName,
            groupTitle = groupTitle,
            tvgId = tvgId,
            logoUrl = logoUrl,
            streamUrl = buildLiveStreamUrl(base, user, pass, streamId, source.outputFormat),
            headers = headers,
            quality = ChannelNormalizer.parseQuality(rawName),
            catchup = catchup,
            requiresResolve = false,
            xtreamStreamId = streamId,
        )
    }

    /** Fetches up to [limit] upcoming/current programmes for [streamId] via `get_short_epg`. */
    suspend fun fetchShortEpg(
        source: IptvSource.Xtream,
        streamId: String,
        limit: Int = 6,
        channelIdOverride: String? = null,
    ): List<EpgProgramme> {
        val url = "${playerApiUrl(source, "get_short_epg")}&stream_id=${urlEncode(streamId)}&limit=$limit"
        val root = getJson(url, source.userAgent)?.obj() ?: return emptyList()
        return parseEpgListings(root, streamId, channelIdOverride)
    }

    /** Fetches full archive-capable EPG table for [streamId] via `get_simple_data_table`. */
    suspend fun fetchArchiveEpg(
        source: IptvSource.Xtream,
        streamId: String,
        channelIdOverride: String? = null,
    ): List<EpgProgramme> {
        val url = "${playerApiUrl(source, "get_simple_data_table")}&stream_id=${urlEncode(streamId)}"
        val root = getJson(url, source.userAgent)?.obj() ?: return emptyList()
        return parseEpgListings(root, streamId, channelIdOverride)
    }

    /** Builds the provider-wide XMLTV guide URL for [source]. */
    fun xmltvUrl(source: IptvSource.Xtream): String =
        "${normalizeBaseUrl(source.serverUrl)}/xmltv.php?username=${urlEncode(source.username)}&password=${urlEncode(source.password)}"

    private fun parseEpgListings(
        root: JsonObject,
        streamId: String,
        channelIdOverride: String?,
    ): List<EpgProgramme> {
        return root["epg_listings"].arr().mapNotNull { it.obj() }.mapNotNull { item ->
            val rawTitle = item.str("title") ?: return@mapNotNull null
            val title = decodeBase64Utf8(rawTitle).trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val desc = item.str("description")?.let { decodeBase64Utf8(it).trim().takeIf(String::isNotBlank) }
            val startMs = item.long("start_timestamp")?.takeIf { it > 0L }?.let { it * 1000L }
                ?: item.str("start")?.let(::parseIsoUtcMillis)
                ?: return@mapNotNull null
            val endMs = item.long("stop_timestamp")?.takeIf { it > 0L }?.let { it * 1000L }
                ?: (item.str("end") ?: item.str("stop"))?.let(::parseIsoUtcMillis)
                ?: return@mapNotNull null
            if (endMs <= startMs) return@mapNotNull null

            val channelId = channelIdOverride
                ?: item.str("epg_id")
                ?: item.str("channel_id")
                ?: streamId
            val hasCatchup = item.int("has_archive") == 1 || item.bool("has_archive") == true
            val (isLiveSport, sportTag) = detectSportTag(title, subtitle = desc)

            EpgProgramme(
                id = item.str("id") ?: "$channelId:$startMs",
                channelId = channelId,
                title = title,
                description = desc,
                startMs = startMs,
                endMs = endMs,
                isLiveSport = isLiveSport,
                sportTag = sportTag,
                hasCatchup = hasCatchup,
            )
        }
    }

    private fun playerApiUrl(source: IptvSource.Xtream, action: String? = null): String {
        val base = normalizeBaseUrl(source.serverUrl)
        val creds = "username=${urlEncode(source.username)}&password=${urlEncode(source.password)}"
        return if (action == null) {
            "$base/player_api.php?$creds"
        } else {
            "$base/player_api.php?$creds&action=$action"
        }
    }

    private fun buildLiveStreamUrl(
        base: String,
        user: String,
        pass: String,
        streamId: String,
        outputFormat: String,
    ): String {
        val fmt = outputFormat.trim().lowercase().removePrefix(".")
        return when {
            fmt.isBlank() || fmt == "raw" || fmt == "none" -> "$base/$user/$pass/$streamId"
            fmt == "hls" -> "$base/live/$user/$pass/$streamId.m3u8"
            fmt == "mpegts" -> "$base/live/$user/$pass/$streamId.ts"
            else -> "$base/live/$user/$pass/$streamId.$fmt"
        }
    }

    private fun normalizeBaseUrl(serverUrl: String): String {
        val trimmed = serverUrl.trim().trimEnd('/')
        return if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed
        } else {
            "http://$trimmed"
        }
    }

    private fun effectiveUserAgent(userAgent: String?): String =
        userAgent?.trim()?.takeIf { it.isNotEmpty() } ?: IptvRepository.DEFAULT_PLAYER_USER_AGENT

    private suspend fun getText(url: String, userAgent: String?): String {
        val resp = http.get(url) {
            header(HttpHeaders.UserAgent, effectiveUserAgent(userAgent))
        }
        if (!resp.status.isSuccess()) {
            error("Xtream HTTP ${resp.status.value}")
        }
        return resp.bodyAsText()
    }

    private inline fun <reified T> decodeXtreamList(body: String): List<T> {
        val trimmed = body.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (!trimmed.startsWith('[')) return emptyList()
        return runCatching { iptvJson.decodeFromString<List<T>>(trimmed) }.getOrDefault(emptyList())
    }

    private suspend fun getJson(url: String, userAgent: String?): JsonElement? {
        val body = getText(url, userAgent)
        if (body.isBlank()) return null
        return iptvJson.parseToJsonElement(body)
    }

    /**
     * Streams a top-level JSON array `[ {...}, ... ]` from [channel] one object at a time so
     * multi-megabyte playlists never materialize a full response string or full DTO list in memory.
     */
    private suspend fun streamJsonObjects(
        channel: ByteReadChannel,
        onObject: (String) -> Unit,
    ) {
        val chunk = ByteArray(STREAM_CHUNK_BYTES)
        var objBuf = ByteArray(INITIAL_OBJECT_BUFFER_BYTES)
        var objLen = 0

        var inArray = false
        var arrayDepth = 0
        var braceDepth = 0
        var capturing = false
        var inString = false
        var escaped = false
        var bomStage = 0

        while (true) {
            val read = channel.readAvailable(chunk, 0, chunk.size)
            if (read < 0) break
            if (read == 0) {
                if (!channel.awaitContent(1)) break
                continue
            }

            for (i in 0 until read) {
                val b = chunk[i]
                if (!inArray) {
                    if (bomStage == 1) {
                        if (b == BYTE_BOM_1) {
                            bomStage = 2
                            continue
                        }
                        return
                    }
                    if (bomStage == 2) {
                        if (b == BYTE_BOM_2) {
                            bomStage = 0
                            continue
                        }
                        return
                    }
                    if (b == BYTE_BOM_0) {
                        bomStage = 1
                        continue
                    }
                    if (isJsonWhitespace(b)) continue
                    if (b == BYTE_OPEN_BRACKET) {
                        inArray = true
                        arrayDepth = 1
                        continue
                    }
                    return
                }

                if (capturing) {
                    if (objLen == objBuf.size) {
                        objBuf = objBuf.copyOf(objBuf.size * 2)
                    }
                    objBuf[objLen++] = b
                }
                if (inString) {
                    if (escaped) {
                        escaped = false
                    } else if (b == BYTE_BACKSLASH) {
                        escaped = true
                    } else if (b == BYTE_QUOTE) {
                        inString = false
                    }
                    continue
                }
                if (braceDepth == 0) {
                    when (b) {
                        BYTE_QUOTE -> inString = true
                        BYTE_OPEN_BRACKET -> arrayDepth++
                        BYTE_CLOSE_BRACKET -> {
                            arrayDepth--
                            if (arrayDepth == 0) return
                        }
                        BYTE_OPEN_BRACE -> {
                            braceDepth = 1
                            if (arrayDepth == 1) {
                                capturing = true
                                objLen = 1
                                objBuf[0] = b
                            }
                        }
                    }
                    continue
                }
                when (b) {
                    BYTE_QUOTE -> inString = true
                    BYTE_OPEN_BRACE -> braceDepth++
                    BYTE_CLOSE_BRACE -> {
                        braceDepth--
                        if (braceDepth == 0 && capturing) {
                            val objJson = objBuf.decodeToString(0, objLen)
                            objLen = 0
                            if (objBuf.size > MAX_RETAINED_OBJECT_BUFFER_BYTES) {
                                objBuf = ByteArray(INITIAL_OBJECT_BUFFER_BYTES)
                            }
                            capturing = false
                            onObject(objJson)
                        }
                    }
                }
            }
        }
    }

    private fun isJsonWhitespace(b: Byte): Boolean =
        b == BYTE_SPACE || b == BYTE_TAB || b == BYTE_CR || b == BYTE_LF

    private companion object {
        const val STREAM_CHUNK_BYTES = 65_536
        const val INITIAL_OBJECT_BUFFER_BYTES = 4_096
        const val MAX_RETAINED_OBJECT_BUFFER_BYTES = 65_536

        const val BYTE_BOM_0: Byte = -17 // 0xEF
        const val BYTE_BOM_1: Byte = -69 // 0xBB
        const val BYTE_BOM_2: Byte = -65 // 0xBF
        const val BYTE_SPACE: Byte = 0x20
        const val BYTE_TAB: Byte = 0x09
        const val BYTE_CR: Byte = 0x0D
        const val BYTE_LF: Byte = 0x0A
        const val BYTE_OPEN_BRACKET: Byte = 0x5B
        const val BYTE_CLOSE_BRACKET: Byte = 0x5D
        const val BYTE_OPEN_BRACE: Byte = 0x7B
        const val BYTE_CLOSE_BRACE: Byte = 0x7D
        const val BYTE_QUOTE: Byte = 0x22
        const val BYTE_BACKSLASH: Byte = 0x5C
    }
}
