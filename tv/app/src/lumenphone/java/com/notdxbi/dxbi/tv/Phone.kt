package com.notdxbi.dxbi.tv

import android.content.Context
import android.content.Intent
import android.widget.FrameLayout
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ComposeView
import androidx.media3.common.util.UnstableApi
import com.notdxbi.dxbi.tv.lumenphone.LumenPhoneApp
import com.notdxbi.dxbi.tv.phone.Engine
import com.notdxbi.dxbi.tv.phone.StreamOption
import org.json.JSONObject
import java.io.File

/**
 * Lumen for phones and the Pixel Fold, joined to dx.bi TV's code (see PhoneHooks.kt) the way dx.bi for phones
 * is (src/mobile): Lumen's own screens over the page, which keeps doing Stremio's work underneath, and where a
 * stream goes when it's played. The player, Chromecast, downloads and other-app playing are dx.bi for phones'
 * own, in src/phonecommon, shared by both apps.
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

    fun realPicture(width: Int) = Unit
    fun playerStreams(type: String, id: String, videoId: String, done: (JSONObject?) -> Unit) = done(null)
    fun pageCall(method: String, args: org.json.JSONArray, done: (Any?) -> Unit) = done(null)
    fun playOther(index: Int) = Unit

    @UnstableApi
    fun player(host: PlayerHost, stream: JSONObject): PlayerHooks? =
        if (PhoneSupport.isPhone(host.activity)) PhonePlayer(host, stream) else null

    /** The phone apps have no Lumen loading screen over their player (the iOS app's comes in a later phase) */
    fun loading(activity: PlayerActivity, root: FrameLayout): LoadingCover? = null

    fun install(activity: MainActivity, apk: File) = PhoneSupport.install(activity, apk)
}
