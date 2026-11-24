package lumos.iptv

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Ktor client for Stalker / Ministra STB portal endpoints (`/portal.php`, `/server/load.php`, `/c/`). */
class StalkerClient(private val http: HttpClient) {
    /** Normalizes a Stalker/Ministra portal URL to its API endpoint (`.../portal.php` or `.../server/load.php`). */
    fun portalEndpoint(portalUrl: String): String {
        val trimmed = portalUrl.trim().trimEnd('/')
        val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "http://$trimmed"
        }
        return when {
            withScheme.endsWith("/portal.php", ignoreCase = true) ||
                withScheme.endsWith("/server/load.php", ignoreCase = true) -> withScheme
            withScheme.endsWith("/c", ignoreCase = true) -> {
                val base = withScheme.dropLast(2).trimEnd('/')
                if (base.contains("/stalker_portal", ignoreCase = true)) {
                    "$base/server/load.php"
                } else {
                    "$base/portal.php"
                }
            }
            else -> "$withScheme/portal.php"
        }
    }

    /** Performs STB handshake to acquire a portal session bearer token. */
    suspend fun handshake(source: IptvSource.Stalker): String {
        val endpoint = portalEndpoint(source.portalUrl)
        val url = "$endpoint?type=stb&action=handshake&token=&JsHttpRequest=1-xml"
        val body = requestText(url, source, token = null)
        val root = iptvJson.parseToJsonElement(body).obj() ?: error("Invalid Stalker handshake response")
        return root["js"].obj()?.str("token") ?: error("Missing token in Stalker handshake")
    }

    /** Fetches genre titles and all live channels, returning deferred-resolve [StreamSource]s. */
    suspend fun fetchAllChannels(
        source: IptvSource.Stalker,
        token: String,
        skipForeign: Boolean = false,
    ): List<StreamSource> {
        val endpoint = portalEndpoint(source.portalUrl)
        val genres = runCatching {
            val gUrl = "$endpoint?type=itv&action=get_genres&JsHttpRequest=1-xml"
            val gRoot = iptvJson.parseToJsonElement(requestText(gUrl, source, token)).obj()
            gRoot?.get("js").arr()
                .mapNotNull { it.obj() }
                .mapNotNull { g ->
                    val id = g.str("id") ?: return@mapNotNull null
                    val title = g.str("title") ?: return@mapNotNull null
                    id to title
                }
                .toMap()
        }.getOrDefault(emptyMap())

        val foreignGenreIds = if (skipForeign) {
            buildSet {
                for ((id, title) in genres) {
                    if (ChannelNormalizer.isForeignGroup(title)) add(id)
                }
            }
        } else {
            emptySet()
        }
        val foreignGroupMemo = if (skipForeign) HashMap<String, Boolean>() else null

        val chUrl = "$endpoint?type=itv&action=get_all_channels&JsHttpRequest=1-xml"
        val chRoot = iptvJson.parseToJsonElement(requestText(chUrl, source, token)).obj()
            ?: error("Invalid Stalker channel list response")
        val jsElem = chRoot["js"]
        val channelRows = jsElem.obj()?.get("data").arr().ifEmpty { jsElem.arr() }

        val headers = source.userAgent?.takeIf { it.isNotBlank() }?.let { mapOf("User-Agent" to it) } ?: emptyMap()

        return channelRows.mapNotNull { elem ->
            val obj = elem.obj() ?: return@mapNotNull null
            val genreId = obj.str("tv_genre_id")
            if (skipForeign && genreId != null && genreId in foreignGenreIds) return@mapNotNull null

            val chId = obj.str("id") ?: obj.int("id")?.toString() ?: return@mapNotNull null
            val rawName = obj.str("name")?.trim()?.takeIf { it.isNotBlank() } ?: "Channel $chId"
            if (skipForeign && ChannelNormalizer.isForeignRawName(rawName)) return@mapNotNull null

            val groupTitle = genreId?.let { genres[it] } ?: obj.str("genre_title") ?: ""
            if (foreignGroupMemo != null && (genreId == null || genreId !in genres)) {
                val foreignGroup = foreignGroupMemo.getOrPut(groupTitle) {
                    ChannelNormalizer.isForeignGroup(groupTitle)
                }
                if (foreignGroup) return@mapNotNull null
            }

            val cmd = obj.str("cmd") ?: ""
            val logoUrl = obj.str("logo")?.let { normalizeLogoUrl(endpoint, it) }

            val tvArchive = obj.int("enable_tv_archive") == 1 || obj.bool("enable_tv_archive") == true
            val archiveDays = obj.int("tv_archive_duration") ?: 0
            val catchup = if (tvArchive && archiveDays > 0) {
                CatchupConfig(mode = CatchupMode.STALKER, days = archiveDays, streamId = chId)
            } else {
                null
            }

            StreamSource(
                id = "${source.id}:stalker:$chId",
                sourceId = source.id,
                sourceName = source.name,
                rawName = rawName,
                groupTitle = groupTitle,
                tvgId = obj.str("xmltv_id") ?: chId,
                logoUrl = logoUrl,
                streamUrl = cmd,
                headers = headers,
                quality = ChannelNormalizer.parseQuality(rawName),
                catchup = catchup,
                requiresResolve = true,
                stalkerCmd = cmd,
            )
        }
    }

    /**
     * Resolves a Stalker `cmd` into `(streamUrl, activeToken)`, refreshing the token once via
     * [handshake] if the portal rejects the supplied [token].
     */
    suspend fun resolveStreamUrl(
        source: IptvSource.Stalker,
        token: String,
        cmd: String,
    ): Pair<String, String> {
        val endpoint = portalEndpoint(source.portalUrl)
        val url = "$endpoint?type=itv&action=create_link&cmd=${urlEncode(cmd)}" +
            "&series=&forced_storage=0&disable_ad=0&download=0&JsHttpRequest=1-xml"
        return executeWithTokenRefresh(source, token) { activeToken ->
            val body = requestText(url, source, activeToken)
            parseResolvedCmd(body)
        }
    }

    /**
     * Resolves a Stalker TV-archive catch-up link for [streamId] starting at [startMs] for [durationSec],
     * refreshing the token once if expired.
     */
    suspend fun resolveCatchupUrl(
        source: IptvSource.Stalker,
        token: String,
        streamId: String,
        startMs: Long,
        durationSec: Int,
    ): Pair<String, String> {
        val endpoint = portalEndpoint(source.portalUrl)
        val startSec = startMs / 1000L
        val safeDuration = durationSec.coerceAtLeast(1)
        val endSec = startSec + safeDuration
        val cmd = urlEncode("auto /media/$streamId.mpg")
        val url = "$endpoint?type=tv_archive&action=create_link&ch_id=${urlEncode(streamId)}" +
            "&cmd=$cmd&from_ts=$startSec&to_ts=$endSec&duration=$safeDuration&JsHttpRequest=1-xml"
        return executeWithTokenRefresh(source, token) { activeToken ->
            val body = requestText(url, source, activeToken)
            parseResolvedCmd(body)
        }
    }

    /** Fetches portal EPG data for [periodHours] via `get_epg_info`. */
    suspend fun fetchEpg(
        source: IptvSource.Stalker,
        token: String,
        periodHours: Int = 24,
    ): List<EpgProgramme> {
        val endpoint = portalEndpoint(source.portalUrl)
        val url = "$endpoint?type=itv&action=get_epg_info&period=$periodHours&JsHttpRequest=1-xml"
        val (jsonRoot, _) = executeWithTokenRefresh(source, token) { activeToken ->
            val body = requestText(url, source, activeToken)
            iptvJson.parseToJsonElement(body).obj()
                ?.takeIf { it["js"] != null }
                ?: error("Invalid Stalker EPG response")
        }

        val data = jsonRoot["js"].obj()?.get("data") ?: jsonRoot["js"]
        val out = mutableListOf<EpgProgramme>()
        when (data) {
            is JsonObject -> {
                for ((channelKey, listElem) in data) {
                    for (item in listElem.arr().mapNotNull { it.obj() }) {
                        parseStalkerProgramme(item, channelKey)?.let(out::add)
                    }
                }
            }
            else -> {
                for (item in data.arr().mapNotNull { it.obj() }) {
                    val chId = item.str("ch_id") ?: item.str("channel_id") ?: continue
                    parseStalkerProgramme(item, chId)?.let(out::add)
                }
            }
        }
        return out
    }

    private fun parseStalkerProgramme(item: JsonObject, fallbackChannelId: String): EpgProgramme? {
        val title = (item.str("name") ?: item.str("title"))?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val startMs = item.long("start_timestamp")?.takeIf { it > 0L }?.let { it * 1000L }
            ?: item.str("time")?.let(::parseIsoUtcMillis)
            ?: return null
        val endMs = item.long("stop_timestamp")?.takeIf { it > 0L }?.let { it * 1000L }
            ?: item.str("time_to")?.let(::parseIsoUtcMillis)
            ?: return null
        if (endMs <= startMs) return null

        val channelId = item.str("ch_id") ?: item.str("channel_id") ?: fallbackChannelId
        val desc = (item.str("descr") ?: item.str("description"))?.trim()?.takeIf { it.isNotBlank() }
        val category = item.str("category")
        val hasCatchup = item.int("mark_archive") == 1 || item.bool("mark_archive") == true
        val (isLiveSport, sportTag) = detectSportTag(title, category = category, subtitle = desc)

        return EpgProgramme(
            id = item.str("id") ?: "$channelId:$startMs",
            channelId = channelId,
            title = title,
            description = desc,
            category = category,
            startMs = startMs,
            endMs = endMs,
            isLiveSport = isLiveSport,
            sportTag = sportTag,
            hasCatchup = hasCatchup,
        )
    }

    private suspend fun <T> executeWithTokenRefresh(
        source: IptvSource.Stalker,
        token: String,
        block: suspend (String) -> T,
    ): Pair<T, String> {
        return try {
            block(token) to token
        } catch (e: StalkerAuthException) {
            val freshToken = handshake(source)
            block(freshToken) to freshToken
        } catch (e: Exception) {
            val msg = e.message?.lowercase().orEmpty()
            if (msg.contains("auth") || msg.contains("token") || msg.contains("401") || msg.contains("403")) {
                val freshToken = handshake(source)
                block(freshToken) to freshToken
            } else {
                throw e
            }
        }
    }

    private fun parseResolvedCmd(body: String): String {
        if (body.contains("Authorization failed", ignoreCase = true)) {
            throw StalkerAuthException("Stalker authorization failed")
        }
        val root = iptvJson.parseToJsonElement(body).obj()
            ?: throw StalkerAuthException("Invalid Stalker create_link response")
        val js = root["js"].obj() ?: throw StalkerAuthException("Missing js object in create_link response")
        val err = js.str("error")
        if (err != null) {
            throw StalkerAuthException("Stalker create_link error: $err")
        }
        val rawCmd = js.str("cmd") ?: throw StalkerAuthException("Missing cmd in create_link response")
        val cleaned = stripStreamPrefix(rawCmd)
        if (cleaned.isBlank()) throw StalkerAuthException("Empty stream URL in create_link response")
        return cleaned
    }

    internal fun stripStreamPrefix(cmd: String): String {
        var current = cmd.trim()
        while (true) {
            val lower = current.lowercase()
            current = when {
                lower.startsWith("ffmpeg ") -> current.substring(7).trim()
                lower.startsWith("auto ") -> current.substring(5).trim()
                else -> return current
            }
        }
    }

    private suspend fun requestText(url: String, source: IptvSource.Stalker, token: String?): String {
        val ua = source.userAgent?.takeIf { it.isNotBlank() } ?: DEFAULT_MAG_USER_AGENT
        val resp = http.get(url) {
            header(HttpHeaders.Cookie, "mac=${source.macAddress.trim()}; stb_lang=en; timezone=Europe/London")
            header("X-User-Agent", STALKER_X_USER_AGENT)
            header(HttpHeaders.UserAgent, ua)
            if (!token.isNullOrBlank()) {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        }
        if (resp.status == HttpStatusCode.Unauthorized || resp.status == HttpStatusCode.Forbidden) {
            throw StalkerAuthException("Stalker HTTP ${resp.status.value}")
        }
        if (!resp.status.isSuccess()) {
            error("Stalker HTTP ${resp.status.value}")
        }
        val body = resp.bodyAsText()
        if (body.contains("Authorization failed", ignoreCase = true)) {
            throw StalkerAuthException("Stalker authorization failed")
        }
        return body
    }

    private fun normalizeLogoUrl(endpoint: String, rawLogo: String): String {
        val trimmed = rawLogo.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        val schemeEnd = endpoint.indexOf("://")
        val originEnd = if (schemeEnd >= 0) endpoint.indexOf('/', schemeEnd + 3) else -1
        val origin = if (originEnd >= 0) endpoint.substring(0, originEnd) else endpoint
        return if (trimmed.startsWith("/")) "$origin$trimmed" else "$origin/$trimmed"
    }

    private class StalkerAuthException(message: String) : IllegalStateException(message)

    companion object {
        private const val STALKER_X_USER_AGENT = "Model: MAG250; Link: WiFi"
        private const val DEFAULT_MAG_USER_AGENT =
            "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
    }
}
