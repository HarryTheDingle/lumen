package lumos.engine

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import lumos.iptv.EpgEnrichment
import lumos.iptv.IptvCodec
import lumos.iptv.SavedSportsTeam
import lumos.iptv.SportsFixture
import lumos.iptv.TeamScheduleGroup

/** Something the sync server said no to, in words fit to show */
class SyncError(val status: Int, message: String) : Exception(message)

/** A signed-in device: keep [token] in the Keychain / encrypted storage, never in synced data */
data class Session(val token: String, val account: String)

/** A device waiting to be let in: show [code] on screen, and [Sync.poll] with [poll] until it's approved */
data class PendingSignIn(val code: String, val poll: String, val expires: Long)

data class Device(val id: String, val name: String, val seen: Long, val current: Boolean)

/** One synced value: [t] is when this device changed it */
data class Entry(val k: String, val t: Long, val v: JsonElement)

/**
 * Lumos's own sync server (sync/worker.js): accounts, signing in a TV with a code, and keeping
 * profiles, resume points, watched and My List the same everywhere.
 */
class Sync(private val server: String, private val http: HttpClient, var session: Session? = null) {

    /** The first device: makes the account (needs the server's setup phrase) */
    @Throws(Exception::class)
    suspend fun createAccount(setup: String, deviceName: String): Session =
        call("/account", buildJsonObject { put("setup", setup); put("name", deviceName) }).let(::session).also { session = it }

    /** A new device asks to be let in */
    @Throws(Exception::class)
    suspend fun startSignIn(deviceName: String): PendingSignIn {
        val o = call("/link/start", buildJsonObject { put("name", deviceName) })
        return PendingSignIn(o.str("code")!!, o.str("poll")!!, o.long("expires") ?: 0)
    }

    /** Null while it's waiting for approval; throws (410) once the code has run out */
    @Throws(Exception::class)
    suspend fun poll(pending: PendingSignIn): Session? {
        val o = call("/link/poll", buildJsonObject { put("poll", pending.poll) })
        if (o.bool("waiting") == true) return null
        return session(o).also { session = it }
    }

    /**
     * Sign in with Stremio: the Lumos account of the Stremio account [stremioAuthKey] belongs to (made for it, with its
     * Lumen profiles and history, when it used Lumen and has none yet). 403 when it never used Lumen
     */
    @Throws(Exception::class)
    suspend fun signInWithStremio(stremioAuthKey: String, deviceName: String): Session =
        call("/signin/stremio", buildJsonObject { put("authKey", stremioAuthKey); put("name", deviceName) }).let(::session).also { session = it }

    /** On a signed-in device: let the device showing [code] in. Answers that device's name */
    @Throws(Exception::class)
    suspend fun approve(code: String): String = call("/link/approve", buildJsonObject { put("code", code) }, signed = true).str("name") ?: "Device"

    @Throws(Exception::class)
    suspend fun devices(): List<Device> = call("/devices", JsonObject(emptyMap()), signed = true)["devices"].arr().mapNotNull { it.obj() }.map {
        Device(it.str("id") ?: "", it.str("name") ?: "Device", it.long("seen") ?: 0, it.bool("current") == true)
    }

    @Throws(Exception::class)
    suspend fun signOut(device: Device) { call("/devices/remove", buildJsonObject { put("id", device.id) }, signed = true) }

    /**
     * Sends what changed here since the last sync and gets back what changed elsewhere since [since]
     * (the `now` of the last answer). Resume points, watched and My List come back merged, title by title.
     */
    @Throws(Exception::class)
    suspend fun sync(since: Long, changes: List<Entry>): Pair<Long, List<Entry>> {
        val o = call("/sync", buildJsonObject {
            put("since", since)
            put("entries", buildJsonArray { changes.forEach { e -> add(buildJsonObject { put("k", e.k); put("t", e.t); put("v", e.v) }) } })
        }, signed = true)
        val entries = o["entries"].arr().mapNotNull { it.obj() }.mapNotNull { e ->
            Entry(e.str("k") ?: return@mapNotNull null, e.long("t") ?: 0, e["v"] ?: JsonNull)
        }
        return (o.long("now") ?: 0) to entries
    }

    /**
     * Lumos's own Home lists (sync/lists.js: Top 10 Today, New to Stream, Popular on each service, IMDb's Top 250…),
     * made on the server every 15 minutes: [{id, name, numbered, service, future, at, items: [title]}]
     */
    @Throws(Exception::class)
    suspend fun lists(): List<JsonObject> = call("/lists", JsonObject(emptyMap()), signed = true)["lists"].arr().mapNotNull { it.obj() }

    /**
     * Checked TMDB pictures by IMDb id (sync/art.js): {art: {tt: {poster, background, logo} | null}, pending: [tt]}.
     * null: none checked (keep the addon's); [pending]: not looked up yet, ask again.
     */
    @Throws(Exception::class)
    suspend fun art(ids: List<String>): String = call("/art", buildJsonObject { put("ids", buildJsonArray { ids.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }) }, signed = true).toString()

    /** A title's review scores (sync/scores.js, from MDBList): {scores: [{source, name, value, votes, url}], scoresFrom} */
    @Throws(Exception::class)
    suspend fun scores(id: String, type: String): String = call("/scores", buildJsonObject { put("id", id); put("type", type) }, signed = true).toString()

    /** Cards' labels (sync/labels.js): {labels: {tt: "In Cinemas" | null}, pending: [tt]} */
    @Throws(Exception::class)
    suspend fun labels(ids: List<String>, facts: Boolean = false): String = call("/labels", buildJsonObject {
        put("ids", buildJsonArray { ids.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }); if (facts) put("facts", true)
    }, signed = true).toString()

    /** A title's pictures said to be wrong: never used for it again; answers the next best, as [art] does */
    @Throws(Exception::class)
    suspend fun wrongArt(id: String): String = call("/art/wrong", buildJsonObject { put("id", id) }, signed = true).toString()

    /**
     * A problem report: [gz] is the device's recent log, gzipped then base64 (secrets already taken out). Answers the short
     * reference to tell Harry
     */
    @Throws(Exception::class)
    suspend fun sendLogs(gz: String, device: String, version: String, note: String?, crash: Boolean): String =
        call("/logs", buildJsonObject {
            put("gz", gz); put("device", device); put("version", version); note?.let { put("note", it) }; put("crash", crash)
        }, signed = true).str("id") ?: throw SyncError(500, "No reference came back")

    /**
     * Top Picks and Because you watched for a profile (sync/picks.js), as JSON text: {rows: [{name, items}], partial?}.
     * partial: the server ran out of lookups before the end; ask again for the rest
     */
    @Throws(Exception::class)
    suspend fun picks(viewer: String): String = call("/picks", buildJsonObject { put("viewer", viewer) }, signed = true).toString()

    /** AI search for a profile (sync/picks.js): {titles: [{id, type, name, poster}]} */
    @Throws(Exception::class)
    suspend fun ask(viewer: String, prompt: String): String = call("/ask", buildJsonObject { put("viewer", viewer); put("prompt", prompt) }, signed = true).toString()

    /** Once: Lumen's profiles and Stremio's watch history, from a Stremio session key */
    @Throws(Exception::class)
    suspend fun importStremio(stremioAuthKey: String): Int =
        call("/import/stremio", buildJsonObject { put("authKey", stremioAuthKey) }, signed = true).int("titles") ?: 0

    /** Stremio's session key for an email and password (for [importStremio]; neither is kept) */
    @Throws(Exception::class)
    suspend fun stremioLogin(email: String, password: String): String {
        val r = http.post("https://api.strem.io/api/login") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("type", "Login"); put("email", email); put("password", password); put("facebook", false) }.toString())
        }
        val o = runCatching { json.parseToJsonElement(r.bodyAsText()).obj() }.getOrNull()
        return o?.get("result").obj()?.str("authKey")
            ?: throw SyncError(401, o?.get("error").obj()?.str("message") ?: "Stremio didn't accept that email and password.")
    }

    /** The stream addons on a Stremio account (their manifest links), for Lumos to use the same ones */
    @Throws(Exception::class)
    suspend fun stremioStreamAddons(authKey: String): List<String> {
        val r = http.post("https://api.strem.io/api/addonCollectionGet") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("type", "AddonCollectionGet"); put("authKey", authKey); put("update", true) }.toString())
        }
        val addons = runCatching { json.parseToJsonElement(r.bodyAsText()).obj() }.getOrNull()?.get("result").obj()?.get("addons").arr()
        return addons.mapNotNull { it.obj() }.filter { a ->
            a["manifest"].obj()?.get("resources").arr().any { res ->
                (res as? kotlinx.serialization.json.JsonPrimitive)?.content == "stream" || res.obj()?.str("name") == "stream"
            }
        }.mapNotNull { it.str("transportUrl") }.filter { it.startsWith("https://") }
    }

    /** Fetches UK TV guide listings within `[fromMs, toMs)` from the sync server as JSON text. */
    @Throws(Exception::class)
    suspend fun ukEpg(fromMs: Long, toMs: Long): String =
        call("/epg/uk", buildJsonObject { put("fromMs", fromMs); put("toMs", toMs) }, signed = true).toString()

    /** Starts a phone IPTV pairing session and returns `{ code, path, expires }`. */
    @Throws(Exception::class)
    suspend fun startIptvSetup(): JsonObject =
        call("/iptv/setup/start", JsonObject(emptyMap()), signed = true)

    /** Fetches live & upcoming UK/US sports fixtures with D1 UK TV broadcaster cross-matches. */
    @Throws(Exception::class)
    suspend fun sportsFixtures(sport: String? = null, date: String? = null): List<SportsFixture> {
        val body = buildJsonObject {
            sport?.takeIf { it.isNotBlank() }?.let { put("sport", it) }
            date?.takeIf { it.isNotBlank() }?.let { put("date", it) }
        }
        return IptvCodec.decodeSportsFixtures(call("/iptv/sports/fixtures", body, signed = true).toString())
    }

    /** Searches UK Football and US pro sports team directories on the sync server. */
    @Throws(Exception::class)
    suspend fun searchSportsTeams(query: String): List<SavedSportsTeam> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val res = call("/iptv/sports/teams/search", buildJsonObject { put("query", q) }, signed = true)
        return IptvCodec.decodeSportsTeams(res.toString())
    }

    /** Fetches live, recent past, and upcoming schedules for saved [teams]. */
    @Throws(Exception::class)
    suspend fun savedTeamsSchedule(teams: List<SavedSportsTeam>): List<TeamScheduleGroup> {
        if (teams.isEmpty()) return emptyList()
        val body = buildJsonObject {
            put("teams", json.encodeToJsonElement(teams))
        }
        val res = call("/iptv/sports/teams/schedule", body, signed = true)
        return IptvCodec.decodeTeamSchedules(res.toString())
    }

    /** Enriches a TV guide programme with TMDB 16:9 backdrop and description when missing. */
    @Throws(Exception::class)
    suspend fun enrichEpgProgramme(
        title: String,
        channelId: String? = null,
        category: String? = null,
        description: String? = null,
        imdb: Boolean = false,
    ): EpgEnrichment {
        val clean = title.trim()
        if (clean.isEmpty()) return EpgEnrichment()
        val body = buildJsonObject {
            put("title", clean)
            channelId?.takeIf { it.isNotBlank() }?.let { put("channelId", it) }
            category?.takeIf { it.isNotBlank() }?.let { put("category", it) }
            description?.takeIf { it.isNotBlank() }?.let { put("description", it.take(400)) }
            if (imdb) put("imdb", true)
        }
        return IptvCodec.decodeEpgEnrichment(call("/epg/enrich", body, signed = true).toString())
    }

    /** Reports or checks concurrent IPTV viewer presence on `/iptv/presence`; returns null on error or offline. */
    suspend fun iptvPresence(action: String, sourceId: String, channelName: String? = null): String? {
        val cleanAction = action.trim()
        val cleanSourceId = sourceId.trim()
        if (cleanAction.isEmpty() || cleanSourceId.isEmpty()) return null
        val body = buildJsonObject {
            put("action", cleanAction)
            put("sourceId", cleanSourceId)
            channelName?.trim()?.takeIf { it.isNotEmpty() }?.let { put("channelName", it) }
        }
        return runCatching { call("/iptv/presence", body, signed = true).toString() }.getOrNull()
    }

    private fun session(o: JsonObject) = Session(o.str("token") ?: throw SyncError(500, "No sign-in came back"), o.str("account") ?: "")

    private suspend fun call(path: String, body: JsonObject, signed: Boolean = false): JsonObject {
        val r = http.post(server.trimEnd('/') + path) {
            contentType(ContentType.Application.Json)
            if (signed) bearerAuth(session?.token ?: throw SyncError(401, "Sign in to Lumos."))
            setBody(body.toString())
        }
        val o = runCatching { json.parseToJsonElement(r.bodyAsText()).obj() }.getOrNull()
        if (r.status.value !in 200..299 || o == null) throw SyncError(r.status.value, o?.str("error") ?: "The sync server didn't answer (${r.status.value}).")
        return o
    }
}

/**
 * The per-title merge the server does, for applying an answer locally: the most recent `at` of each
 * title wins
 */
fun mergeTitles(mine: JsonObject, theirs: JsonObject): JsonObject {
    val out = mine.toMutableMap()
    for ((id, e) in theirs) {
        val old = out[id]?.obj()?.long("at") ?: Long.MIN_VALUE
        if ((e.obj()?.long("at") ?: 0) >= old) out[id] = e
    }
    return JsonObject(out)
}
