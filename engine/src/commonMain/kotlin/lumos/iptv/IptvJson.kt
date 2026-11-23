package lumos.iptv

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Lenient JSON configuration for IPTV playlists, Xtream/Stalker APIs, and snapshots. */
internal val iptvJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    encodeDefaults = true
}

/** Disk-cached EPG guide snapshot preserving UK EPG, provider EPG, guide channels, and XMLTV aliases. */
@Serializable
data class EpgCacheSnapshot(
    val version: Int = 1,
    val updatedAtMs: Long = 0L,
    val builtIn: List<EpgProgramme> = emptyList(),
    val provider: List<EpgProgramme> = emptyList(),
    val aliases: Map<String, String> = emptyMap(),
    val guideChannels: List<GuideChannel> = emptyList(),
)

/** KMP-safe JSON serialization helpers for IPTV sources, prefs, programmes, and EPG caches. */
object IptvCodec {
    /** Shared lenient [Json] instance for streaming serialization on platform layers. */
    val json: Json = iptvJson

    /** Decodes synced IPTV sources from either a JSON array `[...]` or `{"sources": [...]}` object. */
    fun decodeSources(rawJson: String): List<IptvSource> {
        if (rawJson.isBlank()) return emptyList()
        val root = runCatching { iptvJson.parseToJsonElement(rawJson) }.getOrNull() ?: return emptyList()
        val elements = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["sources"] as? JsonArray) ?: return emptyList()
            else -> return emptyList()
        }
        return elements.mapNotNull { el ->
            val src = runCatching { iptvJson.decodeFromJsonElement<IptvSource>(el) }.getOrNull() ?: return@mapNotNull null
            src.takeIf(::isValidSource)
        }
    }

    /** Encodes [sources] as a polymorphic JSON array string. */
    fun encodeSources(sources: List<IptvSource>): String = iptvJson.encodeToString(sources)

    /** Decodes per-profile IPTV preferences, returning defaults on blank or invalid input. */
    fun decodePrefs(rawJson: String): IptvProfilePrefs {
        if (rawJson.isBlank()) return IptvProfilePrefs()
        return runCatching { iptvJson.decodeFromString<IptvProfilePrefs>(rawJson) }
            .getOrElse { IptvProfilePrefs() }
    }

    /** Encodes [prefs] as a JSON object string. */
    fun encodePrefs(prefs: IptvProfilePrefs): String = iptvJson.encodeToString(prefs)

    /** Decodes a single [EpgProgramme] from JSON, or null if invalid. */
    fun decodeProgramme(rawJson: String): EpgProgramme? {
        if (rawJson.isBlank()) return null
        return runCatching { iptvJson.decodeFromString<EpgProgramme>(rawJson) }.getOrNull()
    }

    /** Encodes [programme] as JSON for intent/storage handoff. */
    fun encodeProgramme(programme: EpgProgramme): String = iptvJson.encodeToString(programme)

    /** Decodes a cached [EpgCacheSnapshot], or null if missing/corrupt. */
    fun decodeEpgCache(rawJson: String): EpgCacheSnapshot? {
        if (rawJson.isBlank()) return null
        return runCatching { iptvJson.decodeFromString<EpgCacheSnapshot>(rawJson) }.getOrNull()
    }

    /** Encodes [snapshot] to JSON for warm disk persistence. */
    fun encodeEpgCache(snapshot: EpgCacheSnapshot): String = iptvJson.encodeToString(snapshot)

    /** Decodes sports fixtures from either `{"fixtures": [...]}` or a JSON array. */
    fun decodeSportsFixtures(rawJson: String): List<SportsFixture> = decodeWrappedList(rawJson, "fixtures")

    /** Decodes searched sports teams from either `{"teams": [...]}` or a JSON array. */
    fun decodeSportsTeams(rawJson: String): List<SavedSportsTeam> = decodeWrappedList(rawJson, "teams")

    /** Decodes saved team schedule groups from either `{"schedules": [...]}` or a JSON array. */
    fun decodeTeamSchedules(rawJson: String): List<TeamScheduleGroup> = decodeWrappedList(rawJson, "schedules")

    /** Decodes TMDB programme enrichment metadata from `/epg/enrich` JSON. */
    fun decodeEpgEnrichment(rawJson: String): EpgEnrichment {
        if (rawJson.isBlank()) return EpgEnrichment()
        return runCatching { iptvJson.decodeFromString<EpgEnrichment>(rawJson) }.getOrElse { EpgEnrichment() }
    }

    /** Decodes other active IPTV viewers from a `/iptv/presence` JSON response (`{"others": [...]}`). */
    fun decodePresenceOthers(json: String): List<PresenceViewer> = decodeWrappedList(json, "others")

    private inline fun <reified T> decodeWrappedList(rawJson: String, field: String): List<T> {
        if (rawJson.isBlank()) return emptyList()
        val root = runCatching { iptvJson.parseToJsonElement(rawJson) }.getOrNull() ?: return emptyList()
        val elements = when (root) {
            is JsonArray -> root
            is JsonObject -> (root[field] as? JsonArray) ?: return emptyList()
            else -> return emptyList()
        }
        return elements.mapNotNull { el ->
            runCatching { iptvJson.decodeFromJsonElement<T>(el) }.getOrNull()
        }
    }

    private fun isValidSource(source: IptvSource): Boolean {
        if (source.id.isBlank() || source.name.isBlank()) return false
        return when (source) {
            is IptvSource.Xtream -> source.serverUrl.isNotBlank() && source.username.isNotBlank() && source.password.isNotBlank()
            is IptvSource.M3u -> !source.url.isNullOrBlank() || !source.inlineContent.isNullOrBlank()
            is IptvSource.Stalker -> source.portalUrl.isNotBlank() && source.macAddress.isNotBlank()
        }
    }
}

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject

internal fun JsonElement?.arr(): List<JsonElement> = (this as? JsonArray) ?: emptyList()

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)
        ?.takeIf { it.isString || it.content != "null" }
        ?.content
        ?.takeIf { it.isNotBlank() }

internal fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)
        ?.takeIf { it.isString || it.content != "null" }
        ?.let { it.intOrNull ?: it.content.trim().toIntOrNull() }

internal fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)
        ?.takeIf { it.isString || it.content != "null" }
        ?.let {
            it.longOrNull
                ?: it.doubleOrNull?.toLong()
                ?: it.content.trim().toLongOrNull()
                ?: it.content.trim().toDoubleOrNull()?.toLong()
        }

internal fun JsonObject.double(key: String): Double? =
    (this[key] as? JsonPrimitive)
        ?.takeIf { it.isString || it.content != "null" }
        ?.let { it.doubleOrNull ?: it.content.trim().toDoubleOrNull() }

internal fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)
        ?.takeIf { it.isString || it.content != "null" }
        ?.let {
            when (it.content.trim().lowercase()) {
                "true", "1" -> true
                "false", "0" -> false
                else -> null
            }
        }

internal fun JsonObject.strings(key: String): List<String> =
    this[key].arr().mapNotNull { element ->
        (element as? JsonPrimitive)
            ?.takeIf { it.isString || it.content != "null" }
            ?.content
            ?.takeIf(String::isNotBlank)
    }
