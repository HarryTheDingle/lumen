package lumos.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

/** What came across from Stremio; [stremioKey] is kept (Keychain) to keep Stremio in step while Lumen's in use */
data class Imported(val titles: Int, val addons: List<String>, val stremioKey: String)

/** One of the people watching (Lumen's viewers, synced) */
data class Profile(val id: String, val name: String, val colour: String?, val kids: Boolean)

/** Somewhere to carry on watching */
data class Resume(
    val id: String,
    val type: String,
    val video: String,
    val name: String,
    val poster: String?,
    val position: Double,
    val duration: Double,
    val at: Long,
) {
    val fraction: Double get() = if (duration > 0) (position / duration).coerceIn(0.0, 1.0) else 0.0
}

/**
 * The whole app below the screens, as Swift (and Kotlin) sees it: simple calls that answer lists.
 * The app keeps [state] on disk between launches and gives it back with [restore].
 */
class Lumos(syncServer: String) {
    internal val http = Engine.defaultHttp()
    internal var engine = Engine(emptyList(), null, http)
    val sync = Sync(syncServer, http)

    /** The time now, in ms since 1970 (the app sets it: common Kotlin has no wall clock here) */
    var clock: () -> Long = { 0L }

    /** Match my internet speed (Lumen's quickPlaySpeed, on by default) and what each network is known to do */
    var matchSpeed = true
    internal val speed = Speed(http) { clock() }

    /** Synced values by key, with when each changed, and what changed here since the last sync */
    internal val values = mutableMapOf<String, Entry>()
    private val dirty = mutableSetOf<String>()
    private var since = 0L

    val signedIn: Boolean get() = sync.session != null
    var viewer: String = "main"

    fun configure(addonUrls: List<String>, torBoxKey: String?) {
        engine = Engine(addonUrls, torBoxKey, http)
    }

    fun useSession(token: String, account: String) { sync.session = Session(token, account) }

    // ---- Profiles ----

    fun profiles(): List<Profile> = values["viewers"]?.v?.obj()?.get("list").arr().mapNotNull { it.obj() }.mapNotNull { v ->
        Profile(v.str("id") ?: return@mapNotNull null, v.str("name") ?: "Viewer", v.str("colour"), v.bool("kids") == true || v.int("kidsAge") != null)
    }

    fun chooseProfile(id: String) { viewer = id; viewerChosen = true }

    // Adding, renaming, recolouring and removing people (Lumen's viewers.add/rename/recolour/remove), synced
    private fun viewersNow(): JsonObject = values["viewers"]?.v?.obj()
        ?: buildJsonObject { putJsonArray("list") { add(buildJsonObject { put("id", "main"); put("name", "Me"); put("colour", "#2f80ff") }) }; put("current", "main") }

    private fun saveViewers(list: List<JsonObject>, removed: List<String>, now: Long) {
        val old = viewersNow()
        put("viewers", buildJsonObject {
            put("list", JsonArray(list))
            put("current", old.str("current") ?: "main")
            put("removed", JsonArray(removed.takeLast(50).map(::JsonPrimitive)))
        }, now)
    }

    private fun viewerList() = viewersNow()["list"].arr().mapNotNull { it.obj() }
    private fun removedViewers() = viewersNow().strings("removed")

    /** A new person ([colour] one of Lumen's, as #rrggbb); their id */
    fun addProfile(name: String, colour: String, now: Long): String {
        val id = "v" + now.toString(36)
        saveViewers(viewerList() + buildJsonObject { put("id", id); put("name", name.trim().take(20)); put("colour", colour) }, removedViewers(), now)
        return id
    }

    fun renameProfile(id: String, name: String, now: Long) {
        if (name.isBlank()) return
        saveViewers(viewerList().map { if (it.str("id") == id) JsonObject(it + ("name" to JsonPrimitive(name.trim().take(20)))) else it }, removedViewers(), now)
    }

    fun recolourProfile(id: String, colour: String, now: Long) {
        saveViewers(viewerList().map { if (it.str("id") == id) JsonObject(it + ("colour" to JsonPrimitive(colour))) else it }, removedViewers(), now)
    }

    /** Takes a person off (never the last one); kept in "removed" so another device doesn't bring them back */
    fun removeProfile(id: String, now: Long) {
        val list = viewerList()
        if (list.size < 2 || list.none { it.str("id") == id }) return
        saveViewers(list.filter { it.str("id") != id }, removedViewers() + id, now)
        if (viewer == id) viewer = list.first { it.str("id") != id }.str("id") ?: "main"
    }
    private var viewerChosen = false

    // ---- Signing in ----

    @Throws(Exception::class)
    suspend fun createAccount(setup: String, deviceName: String): Session = sync.createAccount(setup, deviceName)
    @Throws(Exception::class)
    suspend fun startSignIn(deviceName: String): PendingSignIn = sync.startSignIn(deviceName)
    /** Signs in with a Stremio session key (see [Sync.signInWithStremio]) */
    @Throws(Exception::class)
    suspend fun signInWithStremio(key: String, deviceName: String): Session = sync.signInWithStremio(key, deviceName)
    @Throws(Exception::class)
    suspend fun pollSignIn(pending: PendingSignIn): Session? = sync.poll(pending)
    @Throws(Exception::class)
    suspend fun approveSignIn(code: String): String = sync.approve(code)
    /** Once: Lumen's profiles and Stremio's history into Lumos, and the account's stream addons (for the app to keep) */
    @Throws(Exception::class)
    suspend fun importStremio(email: String, password: String): Imported = importStremio(sync.stremioLogin(email, password))

    /** The same, with a Stremio session key already had (Stremio's link code, finished on a phone: the TV's way) */
    @Throws(Exception::class)
    suspend fun importStremio(key: String): Imported {
        val addons = runCatching { sync.stremioStreamAddons(key) }.getOrDefault(emptyList())
        val titles = if (signedIn) sync.importStremio(key).also { syncNow() } else 0
        useStremio(key)
        return Imported(titles, addons, key)
    }

    /** Top Picks for a profile, from the sync server (see [Sync.picks]) */
    @Throws(Exception::class)
    suspend fun picks(viewer: String): String = sync.picks(viewer)

    /** AI search for a profile, from the sync server (see [Sync.ask]) */
    @Throws(Exception::class)
    suspend fun ask(viewer: String, prompt: String): String = sync.ask(viewer, prompt)

    /** Sends a problem report (see [Sync.sendLogs]); its reference */
    @Throws(Exception::class)
    suspend fun sendLogs(gz: String, device: String, version: String, note: String?, crash: Boolean): String = sync.sendLogs(gz, device, version, note, crash)

    // ---- Keeping Stremio in step (while Lumen's still used) ----

    private var stremio: StremioBridge? = null
    private var lastPull = 0L

    fun useStremio(authKey: String?) { stremio = authKey?.takeIf { it.isNotBlank() }?.let { StremioBridge(it, http) } }
    val keepsStremio: Boolean get() = stremio != null

    /** Tells Stremio where this title got to (when the player closes); false when it couldn't */
    @Throws(Exception::class)
    suspend fun tellStremio(id: String, type: String, video: String, name: String, poster: String?, position: Double, duration: Double, now: Long): Boolean {
        val bridge = stremio ?: return false
        // (Stremio's library is the main profile's, as in Lumen: the others' watching stays in Lumos)
        if (viewer != "main") return false
        val finished = duration > 0 && position / duration >= FINISHED
        return runCatching { bridge.progress(id, type, name, poster, video, position, duration, now, finished) }.isSuccess
    }

    /** A whole title marked watched, or put in or taken out of My List, told to Stremio (main profile only, as [tellStremio]) */
    @Throws(Exception::class)
    suspend fun tellStremioMark(id: String, type: String, name: String, poster: String?, watched: Boolean?, inList: Boolean?, now: Long): Boolean {
        val bridge = stremio ?: return false
        if (viewer != "main" || !Regex("""^tt\d+$""").matches(id)) return false
        return runCatching {
            watched?.let { bridge.watched(id, type, name, poster, it, now) }
            inList?.let { bridge.library(id, type, name, poster, it, now) }
        }.isSuccess
    }

    /** Continue Watching's Remove, told to Stremio too (main profile only) */
    @Throws(Exception::class)
    suspend fun tellStremioRemoved(id: String, type: String, name: String, poster: String?, now: Long): Boolean {
        val bridge = stremio ?: return false
        if (viewer != "main" || !Regex("""^tt\d+$""").matches(id)) return false
        return runCatching { bridge.rewind(id, type, name, poster, now) }.isSuccess
    }

    /**
     * What was watched in Lumen since: Stremio's history merged into Lumos again, title by title (at most
     * every 10 minutes). False when nothing was asked
     */
    @Throws(Exception::class)
    suspend fun pullFromStremio(now: Long, key: String): Boolean {
        if (!signedIn || stremio == null || now - lastPull < 10 * 60_000) return false
        lastPull = now
        runCatching { sync.importStremio(key) }.getOrElse { return false }
        return syncNow()
    }

    // ---- Progress ----

    /**
     * Part-way through, or a show's next episode not started yet ("Up Next": Lumen and Stremio keep a show that way once an
     * episode's finished, the next one at 0). A place forgotten (Continue Watching's ✕) is the show's own id at 0: left out
     */
    fun continueWatching(): List<Resume> = progress().mapNotNull { (id, v) -> v.obj()?.let(::resume)?.copy(id = id) }
        .filter { (it.position > 0 && it.fraction < FINISHED) || upNext(it) }.sortedByDescending { it.at }

    private fun upNext(r: Resume) = r.type == "series" && r.position <= 0 && r.video.startsWith("${r.id}:") && r.video.count { it == ':' } == 2 &&
        titles("watched:$viewer")[r.video]?.obj()?.bool("on") != true

    /** When this profile's place in [id] was last changed (whatever state it's in), null if it has none */
    fun placeAt(id: String): Long? = progress()[id]?.obj()?.long("at")

    /** Where a show's place is (its video id), whatever state it's in */
    internal fun placeVideo(id: String): String? = progress()[id]?.obj()?.str("video")

    /** A show moved on to [video], not started yet: Continue Watching's "Up Next" */
    internal fun setUpNext(id: String, video: String, name: String, poster: String?, now: Long) {
        val entry = buildJsonObject {
            put("at", now); put("type", "series"); put("video", video); put("name", name); poster?.let { put("poster", it) }
            put("position", 0); put("duration", 0)
        }
        put("progress:$viewer", JsonObject(progress() + (id to entry)), now)
    }

    fun saveProgress(id: String, type: String, video: String, name: String, poster: String?, position: Double, duration: Double, now: Long) {
        val entry = buildJsonObject {
            put("at", now); put("type", type); put("video", video); put("name", name); poster?.let { put("poster", it) }
            put("position", position.toLong()); put("duration", duration.toLong())
        }
        put("progress:$viewer", JsonObject(progress() + (id to entry)), now)
        // (finished: that film, or that episode, is watched; a show isn't watched because one episode is)
        if (duration > 0 && position / duration >= FINISHED) {
            put("watched:$viewer", JsonObject(titles("watched:$viewer") + (video to buildJsonObject { put("at", now); put("on", true) })), now)
        }
    }

    /** Sends what changed here and takes in what changed elsewhere; false when it couldn't reach the server */
    @Throws(Exception::class)
    suspend fun syncNow(): Boolean {
        if (!signedIn) return false
        val changes = dirty.mapNotNull { values[it] }
        val (now, entries) = runCatching { sync.sync(since, changes) }.getOrElse { return false }
        dirty.removeAll(changes.map { it.k }.toSet())
        for (e in entries) {
            val mine = values[e.k]
            values[e.k] = if (e.k.substringBefore(':') in MERGED && mine != null) {
                Entry(e.k, maxOf(e.t, mine.t), mergeTitles(mine.v.obj() ?: JsonObject(emptyMap()), e.v.obj() ?: JsonObject(emptyMap())))
            } else if (mine == null || e.t >= mine.t) e else mine
        }
        since = now
        if (!viewerChosen) profiles().firstOrNull()?.let { if (profiles().none { p -> p.id == viewer }) viewer = it.id }
        return true
    }

    // ---- What Lumen's screens ask about a title ----

    val phone = Phone(this)

    /** Where this profile left [id] (null: not started, or finished) */
    fun resumeOf(id: String): Resume? = progress()[id]?.obj()?.let(::resume)?.copy(id = id)?.takeIf { it.position > 0 && it.fraction < FINISHED }

    /** This profile's watched titles and episodes */
    fun watchedIds(): Set<String> = titles("watched:$viewer").filterValues { it.obj()?.bool("on") != false }.keys

    internal fun myListEntries(): List<Pair<String, JsonObject>> = titles("myList:$viewer").mapNotNull { (id, e) -> e.obj()?.takeIf { it.bool("on") != false }?.let { id to it } }
        .sortedByDescending { it.second.long("at") ?: 0 }

    fun inMyList(id: String): Boolean = titles("myList:$viewer")[id]?.obj()?.bool("on") == true

    fun setInMyList(id: String, type: String, name: String, poster: String?, on: Boolean, now: Long) {
        val e = buildJsonObject { put("at", now); put("on", on); put("type", type); put("name", name); poster?.let { put("poster", it) } }
        put("myList:$viewer", JsonObject(titles("myList:$viewer") + (id to e)), now)
    }

    fun setWatched(id: String, on: Boolean, now: Long) {
        if (on) {
            put("watched:$viewer", JsonObject(titles("watched:$viewer") + (id to buildJsonObject { put("at", now); put("on", on) })), now)
            progress()[id]?.let { put("progress:$viewer", JsonObject(progress() - id), now) }
            return
        }
        // Unwatched is a clean slate (Harry's call): the mark goes, a show's episode marks go with it, and the place is
        // put back to 0 (kept as a removal, as Continue Watching's ✕ does, so other devices forget it too)
        val off = buildJsonObject { put("at", now); put("on", false) }
        val episodes = if (id.count { it == ':' } == 0) titles("watched:$viewer").keys.filter { it.startsWith("$id:") } else emptyList()
        put("watched:$viewer", JsonObject(titles("watched:$viewer") + (listOf(id) + episodes).associateWith { off }), now)
        val show = id.substringBefore(':')
        val place = progress()[show]?.obj()?.let(::resume)
        if (place != null && (show == id || place.video == id)) forget(show, now)
    }


    /** Several watched at once (one save, and so one sync, rather than one for each) */
    fun setWatchedAll(ids: Collection<String>, on: Boolean, now: Long) {
        if (ids.isEmpty()) return
        val marks = ids.associateWith { buildJsonObject { put("at", now); put("on", on) } as kotlinx.serialization.json.JsonElement }
        put("watched:$viewer", JsonObject(titles("watched:$viewer") + marks), now)
        if (on) progress().keys.filter { it in ids }.let { gone -> if (gone.isNotEmpty()) put("progress:$viewer", JsonObject(progress() - gone.toSet()), now) }
    }

    /** Continue Watching's ✕: the place is forgotten (kept as a removal, so other devices forget it too) */
    fun forget(id: String, now: Long) {
        if (progress()[id] == null) return
        put("progress:$viewer", JsonObject(progress() + (id to buildJsonObject { put("at", now); put("video", id); put("position", 0); put("duration", 0) })), now)
    }

    /** Just press Play (Lumen's quickPlay setting) and its quality cap (0: any, else 2160/1080/720) */
    var justPressPlay = true
    /** Prefer IMAX: an IMAX version at the same quality wins the pick */
    var preferImax = false
    /** Preferred addon (part of its name): picked from whenever it has a stream that suits; "" none */
    var preferredAddon = ""
    /** If a stream chosen by hand for a title is gone: false, play the next best; true, show the list */
    var fallbackAsk = false
    var qualityCap = 0

    /** The stream chosen for each title, to choose again (Remember my stream choice: for each series) */
    private val choices = mutableMapOf<String, String>()
    fun rememberChoice(metaId: String, memory: String) { choices[metaId] = memory }
    fun rememberedChoice(metaId: String): String? = choices[metaId]
    fun forgetChoice(metaId: String) { choices.remove(metaId) }

    /**
     * The version chosen for each title (Extended, Director's Cut, Encore...; "" for the film as released): Play plays that
     * one. Not chosen: as released, where there is one
     */
    private val versions = mutableMapOf<String, String>()
    fun versionFor(metaId: String): String? = versions[metaId]?.ifEmpty { null }
    fun chooseVersion(metaId: String, edition: String?) { versions[metaId] = edition.orEmpty() }

    // ---- Kept between launches ----

    fun state(): String = buildJsonObject {
        put("since", since)
        put("viewer", viewer)
        put("viewerChosen", viewerChosen)
        put("choices", JsonObject(choices.mapValues { JsonPrimitive(it.value) }))
        // (choices kept since 2026-11-17 are ones made by hand; before, every stream played was kept, pinning titles)
        put("choicesByHand", true)
        put("dirty", kotlinx.serialization.json.JsonArray(dirty.map(::JsonPrimitive)))
        put("values", JsonObject(values.mapValues { (_, e) -> buildJsonObject { put("t", e.t); put("v", e.v) } }))
        put("networks", speed.state())
    }.toString()

    fun restore(saved: String) {
        val o = runCatching { json.parseToJsonElement(saved).jsonObject }.getOrNull() ?: return
        since = o.long("since") ?: 0
        viewer = o.str("viewer") ?: viewer
        viewerChosen = o.bool("viewerChosen") == true
        if (o.bool("choicesByHand") == true) o["choices"].obj()?.forEach { (k, v) -> (v as? JsonPrimitive)?.content?.let { choices[k] = it } }
        dirty += o.strings("dirty")
        o["values"].obj()?.forEach { (k, e) -> e.obj()?.let { values[k] = Entry(k, it.long("t") ?: 0, it["v"] ?: kotlinx.serialization.json.JsonNull) } }
        speed.restore(o["networks"].obj())
    }

    // ----

    /** A synced value as JSON text (Lumen's own keys: taste:<viewer>, homeLayout:<viewer>, ratings:<viewer>…), null if none */
    fun value(key: String): String? = values[key]?.v?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.toString()

    /** Sets a synced value (JSON text of an object) */
    fun setValue(key: String, jsonText: String, now: Long) {
        val v = runCatching { json.parseToJsonElement(jsonText).jsonObject }.getOrNull() ?: return
        put(key, v, now)
    }

    private fun put(k: String, v: JsonObject, now: Long) { values[k] = Entry(k, now, v); dirty += k }
    private fun titles(k: String): Map<String, kotlinx.serialization.json.JsonElement> = values[k]?.v?.obj() ?: emptyMap()
    private fun progress() = titles("progress:$viewer")

    private fun resume(o: JsonObject): Resume? {
        val video = o.str("video") ?: return null
        return Resume(
            id = video.substringBefore(':'),
            type = o.str("type") ?: "movie",
            video = video,
            name = o.str("name") ?: "",
            poster = o.str("poster"),
            position = o.double("position") ?: 0.0,
            duration = o.double("duration") ?: 0.0,
            at = o.long("at") ?: 0,
        )
    }

    companion object {
        const val FINISHED = 0.92
        private val MERGED = setOf("progress", "watched", "myList")
    }
}
