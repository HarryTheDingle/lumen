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
 * Lumen for Android TV, joined to dx.bi TV's code (see PhoneHooks.kt): Lumen's own screens, worked by
 * remote, over the page, which keeps doing Stremio's work underneath as it does in the phone and iPad
 * apps. Playing goes to dx.bi TV's player, which is already made for a remote.
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
