package com.notdxbi.dxbi.tv

import android.content.Context
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.media3.common.util.UnstableApi
import com.notdxbi.dxbi.tv.lumen.LoadingHandoff
import com.notdxbi.dxbi.tv.lumen.LumenApp
import com.notdxbi.dxbi.tv.lumen.PlayerLoading
import com.notdxbi.dxbi.tv.phone.Engine
import com.notdxbi.dxbi.tv.phone.StreamOption
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Lumen for Android TV, joined to the original TV app's code (see PhoneHooks.kt): Lumen's own screens, worked by
 * remote, over the page, which keeps doing Stremio's work underneath as it does in the phone and iPad
 * apps. Playing goes to the original TV app's player, which is already made for a remote.
 */
object Phone {

    /** Where the next stream goes: only Lumen's player for now (other apps come later) */
    enum class Target { Player }

    @Volatile
    var nextTarget = Target.Player

    private var engine: Engine? = null

    fun route(context: Context, option: StreamOption, target: Target, otherApp: Boolean) {
        nextTarget = target
    }

    fun attach(activity: MainActivity, root: FrameLayout, js: (String) -> Unit) {
        val engine = Engine(js).also { engine = it }
        val screens = ComposeView(activity).apply { setContent { LumenApp(engine) } }
        // The page underneath is never shown: not drawn at all (alpha 0), so the TV's graphics chip doesn't paint a
        // whole screen of it under every frame of Lumen's own. It keeps running, which is all Stremio's work needs.
        root.getChildAt(0)?.alpha = 0f
        root.addView(screens, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    /** The remote works Lumen's screens, never the page */
    fun ownsKeys() = true

    /** dxbi-engine.js, run in the page after dxbi-tv.js */
    fun pageScript(context: Context): String =
        if (engine == null) "" else "\n" + context.assets.open("dxbi-engine.js").bufferedReader().use { it.readText() }

    fun pageMessage(json: String) {
        engine?.onMessage(json)
    }

    fun pageGone() {
        engine?.onMessage("""{"type":"gone"}""")
    }

    fun play(activity: MainActivity, json: String): Boolean {
        nextTarget = Target.Player
        return false
    }

    /** The picture's real width, as the player first showed it: the page notes a stream that plays lower than its name said */
    fun realPicture(width: Int) {
        val engine = engine ?: return
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val lower = try {
                engine.call("dx", JSONObject().put("method", "phone.realPicture").put("args", org.json.JSONArray().put(width)))
            } catch (e: Exception) { null }
            android.util.Log.i("dxbi-player", "real picture $width wide, lower than its name: $lower")
        }
    }

    /**
     * The player's Streams list: the playing video's streams from the page (reopening its details first when the page
     * has another video open, as after Up next), as {items: every stream, all: the ones worth listing, without the playing
     * one, samples, cinema recordings and not-yet-cached ones}. Null when the page doesn't answer
     */
    fun playerStreams(type: String, id: String, videoId: String, done: (JSONObject?) -> Unit) {
        val engine = engine ?: return done(null)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            suspend fun dx(method: String, vararg args: Any?): Any? = try {
                engine.call("dx", JSONObject().put("method", method).put("args", org.json.JSONArray(args.toList())), 8_000)
            } catch (e: Exception) { null }
            fun path(resource: String, kind: String, key: String) =
                JSONObject().put("resource", resource).put("type", kind).put("id", key).put("extra", org.json.JSONArray())
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
                kotlinx.coroutines.delay(500)
            }
            val c = found ?: return@launch done(null)
            val groups = (dx("phone.alternatives", 0) as? JSONObject)?.optJSONArray("groups")
            var all = org.json.JSONArray()
            if (groups != null) for (i in 0 until groups.length()) {
                val g = groups.optJSONObject(i) ?: continue
                if (g.optString("title") == "All streams") all = g.optJSONArray("options") ?: all
            }
            done(JSONObject().put("items", c.optJSONArray("items")).put("all", all))
        }
    }

    /** A page call from the player, for its answer (null when the page doesn't give one) */
    fun pageCall(method: String, args: org.json.JSONArray, done: (Any?) -> Unit) {
        val engine = engine ?: return done(null)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            done(try { engine.call("dx", JSONObject().put("method", method).put("args", args), 8_000) } catch (e: Exception) { null })
        }
    }

    /** A stream that can't be swapped in at once (a torrent): once the player has closed, the page plays it, as from the title's page */
    fun playOther(index: Int) {
        val engine = engine ?: return
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            // (the page goes back from the player first)
            kotlinx.coroutines.delay(1300)
            val link = try {
                engine.call("dx", JSONObject().put("method", "phone.play").put("args", org.json.JSONArray().put(index)), 8_000) as? String
            } catch (e: Exception) { null } ?: return@launch
            try { engine.call("play", JSONObject().put("link", link), 8_000) } catch (_: Exception) {}
        }
    }

    @UnstableApi
    fun player(host: PlayerHost, stream: JSONObject): PlayerHooks? = null

    /** The loading screen carried on over the player (null: it wasn't started from one, so the player has its own cover) */
    fun loading(activity: PlayerActivity, root: FrameLayout): LoadingCover? {
        val info = LoadingHandoff.take() ?: return null
        val view = ComposeView(activity)
        val cover = PlayerLoading(info) { root.removeView(view) }
        // (in Lumen's own theme, its font and type sizes: the app's loading screen carries on here exactly)
        view.setContent { com.notdxbi.dxbi.tv.lumen.LumenTheme { cover.Content() } }
        root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        return cover
    }

    fun install(activity: MainActivity, apk: File) = false
}
