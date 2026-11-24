package com.notdxbi.dxbi.tv.live

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lumos.engine.Lumos
import lumos.engine.PendingSignIn
import lumos.engine.Session
import java.io.File

/**
 * What Live TV needs from the Lumos engine, for Lumen on phones. The phone's Home, Discover and Library still run on Stremio's
 * page; Live TV, the guide and sports run here, on the Lumos sync server (sources, favourites, guide, scores). Signing in is by
 * a code approved from a device that's already signed in (the TV).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveEngine(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("lumen-live", Context.MODE_PRIVATE)
    private val stateFile = File(app.filesDir, "lumen-live-state.json")

    /** Everything that touches the engine's state runs here, one thing at a time */
    private val one = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + one)

    val core = Lumos(SYNC)

    /** Whether this phone has a Live TV session */
    val signedIn = MutableStateFlow(false)

    /** Set by the phone to answer "does Lumen have streams for this" from Stremio's page (Watch in Lumen) */
    var hasStreamsHook: (suspend (type: String, videoId: String) -> Boolean)? = null

    init {
        core.clock = { System.currentTimeMillis() }
        if (stateFile.exists()) runCatching { core.restore(stateFile.readText()) }
        val token = prefs.getString("token", null)
        val account = prefs.getString("account", null)
        if (token != null && account != null) core.useSession(token, account)
        signedIn.value = core.signedIn
    }

    private fun save() { runCatching { stateFile.writeText(core.state()) } }

    val deviceName: String get() = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    suspend fun startSignIn(): PendingSignIn = withContext(one) { core.startSignIn(deviceName) }
    suspend fun pollSignIn(pending: PendingSignIn): Session? = withContext(one) { core.pollSignIn(pending) }

    suspend fun signInWithStremio(key: String): Session? = withContext(one) { runCatching { core.signInWithStremio(key, deviceName) }.getOrNull() }

    suspend fun finishSignIn(session: Session) = withContext(one) {
        prefs.edit().putString("token", session.token).putString("account", session.account).apply()
        core.useSession(session.token, session.account)
        signedIn.value = core.signedIn
        runCatching { core.syncNow() }
        save()
    }

    fun signOut() {
        prefs.edit().clear().apply()
        stateFile.delete()
        signedIn.value = false
    }

    /** The viewer whose Live TV choices (favourites, hidden, recents, sports teams) are used: by name when it matches */
    fun chooseViewerNamed(name: String?) {
        val p = core.profiles()
        val hit = p.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return
        core.chooseProfile(hit.id)
    }

    val syncServerUrl: String get() = SYNC

    suspend fun syncIptvNow(): Boolean = withContext(one) {
        val synced = runCatching { core.syncNow() }.getOrDefault(false)
        save()
        synced
    }

    suspend fun <T> withIptvCore(block: suspend (Lumos) -> T): T = withContext(one) { block(core) }

    suspend fun sportsFixtures(sport: String? = null, date: String? = null): List<lumos.iptv.SportsFixture> =
        withContext(Dispatchers.IO) { core.sportsFixtures(sport, date) }

    suspend fun sportsMatch(league: String, eventId: String): org.json.JSONObject? = withContext(Dispatchers.IO) {
        runCatching { org.json.JSONObject(core.sportsMatch(league, eventId)).optJSONObject("match") }.getOrNull()
    }

    suspend fun hasStreams(type: String, videoId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { hasStreamsHook?.invoke(type, videoId) ?: false }.getOrDefault(false)
    }

    suspend fun addonSeries(nameLike: String, prefer: List<String>): lumos.engine.Phone.AddonSeries? = withContext(Dispatchers.IO) {
        runCatching { core.phone.addonSeries(nameLike, prefer) }.getOrNull()
    }

    suspend fun probeAddon(nameLike: String): String = withContext(Dispatchers.IO) {
        runCatching { core.phone.probeAddon(nameLike) }.getOrElse { "probe failed: ${it.javaClass.simpleName}" }
    }

    suspend fun f1Standings(series: String = "f1"): org.json.JSONObject? = withContext(Dispatchers.IO) {
        runCatching { org.json.JSONObject(core.f1Standings(series)) }.getOrNull()
    }

    suspend fun searchSportsTeams(query: String): List<lumos.iptv.SavedSportsTeam> = withContext(Dispatchers.IO) { core.searchSportsTeams(query) }

    suspend fun savedTeamsSchedule(teams: List<lumos.iptv.SavedSportsTeam>): List<lumos.iptv.TeamScheduleGroup> =
        withContext(Dispatchers.IO) { core.savedTeamsSchedule(teams) }

    suspend fun enrichEpgProgramme(
        title: String, channelId: String? = null, category: String? = null, description: String? = null, imdb: Boolean = false,
    ): lumos.iptv.EpgEnrichment = withContext(Dispatchers.IO) { core.enrichEpgProgramme(title, channelId, category, description, imdb) }

    suspend fun iptvPresence(action: String, sourceId: String, channelName: String? = null): String? =
        withContext(Dispatchers.IO) { core.iptvPresence(action, sourceId, channelName) }

    companion object {
        const val SYNC = "https://lumos-sync.hedigital.workers.dev"

        @Volatile private var instance: LiveEngine? = null
        fun get(context: Context): LiveEngine = instance ?: synchronized(this) { instance ?: LiveEngine(context).also { instance = it } }
    }
}
