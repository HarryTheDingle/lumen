package com.notdxbi.dxbi.tv

import android.content.Context
import android.content.Intent
import android.widget.FrameLayout
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ComposeView
import androidx.media3.common.util.UnstableApi
import com.notdxbi.dxbi.tv.lumenphone.LoadingHandoff
import com.notdxbi.dxbi.tv.lumenphone.LumenPhoneApp
import com.notdxbi.dxbi.tv.lumenphone.LumenPhoneTheme
import com.notdxbi.dxbi.tv.lumenphone.PlayerLoading
import com.notdxbi.dxbi.tv.phone.Engine
import com.notdxbi.dxbi.tv.phone.StreamOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Lumen for phones and the Pixel Fold, joined to dx.bi TV's code (see PhoneHooks.kt) the way dx.bi for phones
 * is (src/mobile): Lumen's own screens over the page, which keeps doing Stremio's work underneath, and where a
 * stream goes when it's played. The player, Chromecast, downloads and other-app playing are dx.bi for phones'
 * own, in src/phonecommon, shared by both apps (the player takes Lumen's look here: see PhonePlayer's LUMEN_LOOK).
 */
object Phone {

    /**
     * Where the next stream the page hands over goes (the screens choose before asking the page to
     * play): Lumen's player, another app, a Chromecast, or a download. Back to [Target.Player] once used.
     */
    enum class Target { Player, External, Cast, Download }

    @Volatile
    var nextTarget = Target.Player

    /** Where [Stremio.play] sends a stream: [target] when it's chosen, else a connected Chromecast or the other app they play in */
    fun route(context: Context, option: StreamOption, target: Target, otherApp: Boolean) {
        nextTarget = when {
            target != Target.Player -> target
            !option.torrent && Cast.connected(context) -> Target.Cast
            otherApp -> Target.External
            else -> target
        }
    }

    private var engine: Engine? = null
    private var external: ActivityResultLauncher<Intent>? = null

    fun attach(activity: MainActivity, root: FrameLayout, js: (String) -> Unit) {
        engine = null
        if (!PhoneSupport.isPhone(activity)) return
        // Another app playing a stream (VLC, mpv, MX Player…): it says where it got to when it closes
        external = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { ExternalPlayer.finished(it.data) }
        val engine = Engine(js).also { engine = it }
        // (where it draws nothing, touches reach the page: how the page shows through when it's needed)
        val screens = ComposeView(activity).apply { setContent { LumenPhoneApp(engine) } }
        root.addView(screens, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    /** Whether native screens take the keys rather than the page (a phone is worked by touch: no) */
    fun ownsKeys() = false

    /** dxbi-engine.js, run in the page after dxbi-tv.js (only when the screens are there) */
    fun pageScript(context: Context): String =
        if (engine == null) "" else "\n" + context.assets.open("dxbi-engine.js").bufferedReader().use { it.readText() }

    fun pageMessage(json: String) {
        engine?.onMessage(json)
    }

    fun pageGone() {
        engine?.onMessage("""{"type":"gone"}""")
    }

    /** A stream the page handed over, when it isn't for the player: true when it's been dealt with */
    fun play(activity: MainActivity, json: String): Boolean {
        val target = nextTarget.also { nextTarget = Target.Player }
        when (target) {
            Target.Player -> return false
            Target.External -> external?.let { ExternalPlayer.open(activity, json, it) } ?: return false
            Target.Cast -> Cast.play(activity, json)
            Target.Download -> {
                Downloads.add(activity, json)
                // nothing plays: the page goes back to where it was
                PlaybackBus.closed(0, 0, ended = false, error = null)
            }
        }
        return true
    }

    /** The picture's real width, as the player first showed it: the page notes a stream that plays lower than its name said */
    fun realPicture(width: Int) {
        val engine = engine ?: return
        CoroutineScope(Dispatchers.Main).launch {
            val lower = try {
                engine.call("dx", JSONObject().put("method", "phone.realPicture").put("args", JSONArray().put(width)))
            } catch (e: Exception) { null }
            android.util.Log.i("lumen-phone", "real picture $width wide, lower than its name: $lower")
        }
    }

    /**
     * The player's Streams list: the playing video's streams from the page (reopening its details first when the page
     * has another video open), as {items: every stream, all: the ones worth listing, without the playing one, samples,
     * cinema recordings and not-yet-cached ones}. Null when the page doesn't answer. (As Lumen TV's.)
     */
    fun playerStreams(type: String, id: String, videoId: String, done: (JSONObject?) -> Unit) {
        val engine = engine ?: return done(null)
        CoroutineScope(Dispatchers.Main).launch {
            suspend fun dx(method: String, vararg args: Any?): Any? = try {
                engine.call("dx", JSONObject().put("method", method).put("args", JSONArray(args.toList())), 8_000)
            } catch (e: Exception) { null }
            fun path(resource: String, kind: String, key: String) =
                JSONObject().put("resource", resource).put("type", kind).put("id", key).put("extra", JSONArray())
            var found: JSONObject? = null
            var opened = false
            val began = System.currentTimeMillis()
            while (System.currentTimeMillis() - began < 14_000) {
                val c = dx("phone.streams") as? JSONObject
                if (c != null && c.optString("videoId") == videoId) {
                    // (the addons answer one by one: the list as it is once there's something in it, and a moment for more)
                    if ((c.optJSONArray("items")?.length() ?: 0) > 0 && (c.optBoolean("settled") || System.currentTimeMillis() - began > 4_000)) { found = c; break }
                } else if (!opened) {
                    opened = true
                    val args = JSONObject().put("metaPath", path("meta", type, id)).put("streamPath", path("stream", type, videoId)).put("guessStream", false)
                    try { engine.call("load", JSONObject().put("field", "meta_details").put("model", "MetaDetails").put("args", args), 8_000) } catch (_: Exception) {}
                }
                delay(500)
            }
            val c = found ?: return@launch done(null)
            val groups = (dx("phone.alternatives", 0) as? JSONObject)?.optJSONArray("groups")
            var all = JSONArray()
            if (groups != null) for (i in 0 until groups.length()) {
                val g = groups.optJSONObject(i) ?: continue
                if (g.optString("title") == "All streams") all = g.optJSONArray("options") ?: all
            }
            done(JSONObject().put("items", c.optJSONArray("items")).put("all", all))
        }
    }

    /** A page call from the player, for its answer (null when the page doesn't give one) */
    fun pageCall(method: String, args: JSONArray, done: (Any?) -> Unit) {
        val engine = engine ?: return done(null)
        CoroutineScope(Dispatchers.Main).launch {
            done(try { engine.call("dx", JSONObject().put("method", method).put("args", args), 8_000) } catch (e: Exception) { null })
        }
    }

    /** A stream that can't be swapped in at once (a torrent): once the player has closed, the page plays it, as from the title's page */
    fun playOther(index: Int) {
        val engine = engine ?: return
        CoroutineScope(Dispatchers.Main).launch {
            // (the page goes back from the player first)
            delay(1300)
            val link = try {
                engine.call("dx", JSONObject().put("method", "phone.play").put("args", JSONArray().put(index)), 8_000) as? String
            } catch (e: Exception) { null } ?: return@launch
            try { engine.call("play", JSONObject().put("link", link), 8_000) } catch (_: Exception) {}
        }
    }

    @UnstableApi
    fun player(host: PlayerHost, stream: JSONObject): PlayerHooks? =
        if (PhoneSupport.isPhone(host.activity)) PhonePlayer(host, stream) else null

    /**
     * The loading screen carried on over the player (null when it wasn't started from one: the player's own cover then,
     * the title's picture and a spinner), so a stream that won't play goes back to the app's loading screen for the next best
     */
    fun loading(activity: PlayerActivity, root: FrameLayout): LoadingCover? {
        val info = LoadingHandoff.take() ?: return null
        val view = ComposeView(activity)
        val cover = PlayerLoading(info, onClose = { activity.onBackPressedDispatcher.onBackPressed() }) { root.removeView(view) }
        // (in Lumen's own theme, font and type sizes: the app's loading screen carries on here exactly)
        view.setContent { LumenPhoneTheme { cover.Content() } }
        root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        return cover
    }

    fun install(activity: MainActivity, apk: File) = PhoneSupport.install(activity, apk)
}
