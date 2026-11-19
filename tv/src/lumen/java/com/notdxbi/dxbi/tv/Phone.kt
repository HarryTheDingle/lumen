package com.notdxbi.dxbi.tv

import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import com.notdxbi.dxbi.tv.lumen.LoadingHandoff
import com.notdxbi.dxbi.tv.lumen.LumenApp
import com.notdxbi.dxbi.tv.lumen.PlayerLoading
import com.notdxbi.dxbi.tv.phone.Engine
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Lumen for Android TV, joined to the original TV app's code (see PhoneHooks.kt): Lumen's own screens, worked by
 * remote, over the page, which keeps doing Stremio's work underneath as it does in the phone and iPad
 * apps. Playing goes to the original TV app's player, which is already made for a remote.
 */
object Phone {

    private var engine: Engine? = null

    fun attach(activity: MainActivity, root: FrameLayout, js: (String) -> Unit) {
        // (Lumos: the engine answers Lumen's screens itself; streams it starts go to the player)
        val engine = Engine(activity).also { engine = it }
        engine.onPlay = { json -> activity.play(json) }
        val screens = ComposeView(activity).apply { setContent { LumenApp(engine) } }
        // Lumen's screens paint every pixel themselves: no window background painted under every frame
        activity.window.setBackgroundDrawable(null)
        root.addView(screens, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    /** The remote works Lumen's screens */
    fun ownsKeys() = true

    /** The player's place (PlaybackBus), for the engine to keep */
    fun progress(positionMs: Long, durationMs: Long, paused: Boolean = false) { engine?.progress(positionMs, durationMs, paused) }

    /** The player closed at [positionMs] */
    fun closed(positionMs: Long, durationMs: Long) { engine?.closed(positionMs, durationMs) }

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
            suspend fun pack(c: JSONObject): JSONObject {
                val groups = (dx("phone.alternatives", 0) as? JSONObject)?.optJSONArray("groups")
                var all = org.json.JSONArray()
                if (groups != null) for (i in 0 until groups.length()) {
                    val g = groups.optJSONObject(i) ?: continue
                    if (g.optString("title") == "All streams") all = g.optJSONArray("options") ?: all
                }
                return JSONObject().put("items", c.optJSONArray("items")).put("all", all)
            }

            // Single initial fetch
            var c = dx("phone.streams") as? JSONObject
            if (c != null && c.optString("videoId") == videoId && (c.optJSONArray("items")?.length() ?: 0) > 0 && c.optBoolean("settled")) {
                return@launch done(pack(c))
            }

            // Subscribe to Engine's reactive stream push events instead of polling every 500 ms
            val updates = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
            val unsubscribe = PlaybackBus.addStreamsListener { updates.trySend(Unit) }
            try {
                if (c == null || c.optString("videoId") != videoId) {
                    val args = JSONObject().put("metaPath", path("meta", type, id)).put("streamPath", path("stream", type, videoId)).put("guessStream", false)
                    try { engine.call("load", JSONObject().put("field", "meta_details").put("model", "MetaDetails").put("args", args), 8_000) } catch (_: Exception) {}
                }
                c = dx("phone.streams") as? JSONObject
                var best = c?.takeIf { it.optString("videoId") == videoId && (it.optJSONArray("items")?.length() ?: 0) > 0 }
                if (best != null && best.optBoolean("settled")) {
                    return@launch done(pack(best))
                }
                val began = System.currentTimeMillis()
                while (true) {
                    val elapsed = System.currentTimeMillis() - began
                    val waitMs = if (best != null) 4_000L - elapsed else 14_000L - elapsed
                    if (waitMs <= 0) break
                    kotlinx.coroutines.withTimeoutOrNull(waitMs) { updates.receive() } ?: break
                    val next = dx("phone.streams") as? JSONObject
                    if (next != null && next.optString("videoId") == videoId) {
                        val hasItems = (next.optJSONArray("items")?.length() ?: 0) > 0
                        if (hasItems) best = next
                        if (next.optBoolean("settled")) break
                    }
                }
                val found = best ?: return@launch done(null)
                done(pack(found))
            } finally {
                unsubscribe()
                updates.close()
            }
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
}
