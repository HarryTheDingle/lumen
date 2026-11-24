package lumos.engine

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Keeps the Stremio account's library up to date with what's watched in Lumos, so Lumen (which reads
 * Stremio) shows the same Continue Watching while both are in use. Temporary: goes once Lumen is retired.
 *
 * Writes go through Stremio's datastore: the title's library item is read, its watch state changed,
 * and put back whole (Stremio keeps the most recent `_mtime`).
 */
class StremioBridge(private val authKey: String, private val http: HttpClient) {

    /** Where [video] of title [id] got to (seconds); [finished]: watched to the end */
    suspend fun progress(id: String, type: String, name: String, poster: String?, video: String, position: Double, duration: Double, nowMs: Long, finished: Boolean) {
        val existing = call("datastoreGet", buildJsonObject {
            put("authKey", authKey); put("collection", "libraryItem"); put("all", false)
            putJsonArray("ids") { add(JsonPrimitive(id)) }
        }).arr().firstOrNull()?.obj()
        val now = iso(nowMs)
        val item = existing?.toMutableMap() ?: mutableMapOf<String, JsonElement>(
            "_id" to JsonPrimitive(id), "name" to JsonPrimitive(name), "type" to JsonPrimitive(type),
            "poster" to JsonPrimitive(poster), "posterShape" to JsonPrimitive("poster"),
            // (watched without being added: Stremio's own "temp" items)
            "removed" to JsonPrimitive(true), "temp" to JsonPrimitive(true), "_ctime" to JsonPrimitive(now),
            "behaviorHints" to buildJsonObject { put("defaultVideoId", if (type == "movie") id else null); put("hasScheduledVideos", false) },
        )
        val old = item["state"].obj() ?: JsonObject(emptyMap())
        val oldOffset = old.long("timeOffset") ?: 0
        val offsetMs = (position * 1000).toLong()
        val spent = if (old.str("video_id") == video) (offsetMs - oldOffset).coerceIn(0, 60 * 60 * 1000) else 0
        val state = old.toMutableMap()
        state["lastWatched"] = JsonPrimitive(now)
        state["video_id"] = JsonPrimitive(video)
        state["duration"] = JsonPrimitive((duration * 1000).toLong())
        state["timeOffset"] = JsonPrimitive(if (finished) 0 else offsetMs)
        state["timeWatched"] = JsonPrimitive((old.long("timeWatched") ?: 0) + spent)
        state["overallTimeWatched"] = JsonPrimitive((old.long("overallTimeWatched") ?: 0) + spent)
        if (finished && (oldOffset > 0 || old.str("video_id") != video)) {
            state["timesWatched"] = JsonPrimitive((old.long("timesWatched") ?: 0) + 1)
            if (type == "movie") state["flaggedWatched"] = JsonPrimitive(1)
        }
        if ("noNotif" !in state) state["noNotif"] = JsonPrimitive(false)
        item["state"] = JsonObject(state)
        item["_mtime"] = JsonPrimitive(now)
        call("datastorePut", buildJsonObject {
            put("authKey", authKey); put("collection", "libraryItem")
            put("changes", buildJsonArray { add(JsonObject(item)) })
        })
    }

    /** A whole title marked watched or not (a film, or a series as a whole; Stremio's per-episode marks are a packed field left alone) */
    suspend fun watched(id: String, type: String, name: String, poster: String?, on: Boolean, nowMs: Long) = change(id, type, name, poster, nowMs) { item, state ->
        state["flaggedWatched"] = JsonPrimitive(if (on) 1 else 0)
        state["timesWatched"] = JsonPrimitive(if (on) maxOf(1, (item["state"].obj()?.long("timesWatched") ?: 0)) else 0)
        if (on) state["timeOffset"] = JsonPrimitive(0)
    }

    /** Taken out of Continue Watching: its place rewound to the start, as Stremio's own Remove does */
    suspend fun rewind(id: String, type: String, name: String, poster: String?, nowMs: Long) = change(id, type, name, poster, nowMs) { _, state ->
        state["timeOffset"] = JsonPrimitive(0)
    }

    /** In My List (Stremio's library) or not */
    suspend fun library(id: String, type: String, name: String, poster: String?, on: Boolean, nowMs: Long) = change(id, type, name, poster, nowMs) { item, _ ->
        item["removed"] = JsonPrimitive(!on)
        item["temp"] = JsonPrimitive(false)
    }

    /** Reads a title's library item (or starts one), lets [edit] change it and its state, and puts it back */
    private suspend fun change(id: String, type: String, name: String, poster: String?, nowMs: Long, edit: (MutableMap<String, JsonElement>, MutableMap<String, JsonElement>) -> Unit) {
        val existing = call("datastoreGet", buildJsonObject {
            put("authKey", authKey); put("collection", "libraryItem"); put("all", false)
            putJsonArray("ids") { add(JsonPrimitive(id)) }
        }).arr().firstOrNull()?.obj()
        val now = iso(nowMs)
        val item = existing?.toMutableMap() ?: mutableMapOf<String, JsonElement>(
            "_id" to JsonPrimitive(id), "name" to JsonPrimitive(name), "type" to JsonPrimitive(type),
            "poster" to JsonPrimitive(poster), "posterShape" to JsonPrimitive("poster"),
            "removed" to JsonPrimitive(true), "temp" to JsonPrimitive(true), "_ctime" to JsonPrimitive(now),
            "behaviorHints" to buildJsonObject { put("defaultVideoId", if (type == "movie") id else null); put("hasScheduledVideos", false) },
            "state" to buildJsonObject { put("timeOffset", 0); put("timeWatched", 0); put("overallTimeWatched", 0); put("timesWatched", 0); put("flaggedWatched", 0); put("duration", 0); put("noNotif", false) },
        )
        val state = (item["state"].obj() ?: JsonObject(emptyMap())).toMutableMap()
        edit(item, state)
        item["state"] = JsonObject(state)
        item["_mtime"] = JsonPrimitive(now)
        call("datastorePut", buildJsonObject {
            put("authKey", authKey); put("collection", "libraryItem")
            put("changes", buildJsonArray { add(JsonObject(item)) })
        })
    }

    private suspend fun call(method: String, body: JsonObject): JsonElement? {
        val r = http.post("https://api.strem.io/api/$method") {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val o = runCatching { json.parseToJsonElement(r.bodyAsText()).obj() }.getOrNull()
        o?.get("error").obj()?.let { throw SyncError(r.status.value, it.str("message") ?: "Stremio said no") }
        return o?.get("result")
    }

    companion object {
        /** Epoch milliseconds as Stremio writes dates: 2026-11-17T15:04:05.123Z */
        fun iso(ms: Long): String {
            val days = ms.floorDiv(86_400_000L)
            val rest = ms.mod(86_400_000L)
            // (days to a civil date: Howard Hinnant's algorithm)
            val z = days + 719_468
            val era = z.floorDiv(146_097L)
            val doe = z - era * 146_097
            val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = doy - (153 * mp + 2) / 5 + 1
            val m = if (mp < 10) mp + 3 else mp - 9
            val y = yoe + era * 400 + if (m <= 2) 1 else 0
            fun p(n: Long, w: Int = 2) = n.toString().padStart(w, '0')
            return "${p(y, 4)}-${p(m)}-${p(d)}T${p(rest / 3_600_000)}:${p(rest / 60_000 % 60)}:${p(rest / 1000 % 60)}.${p(rest % 1000, 3)}Z"
        }
    }
}
