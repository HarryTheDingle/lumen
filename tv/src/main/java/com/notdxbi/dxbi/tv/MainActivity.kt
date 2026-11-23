package com.notdxbi.dxbi.tv

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Lumos TV: Lumen TV's own screens (Phone.attach puts them up), worked by the Lumos engine. Lumen TV's MainActivity
 * hosted Stremio's web page under them; Lumos has none, so this is only the screens' window. Streams play in
 * [PlayerActivity], which tells the engine where it got to (PlaybackBus).
 */
class MainActivity : AppCompatActivity() {

    private var lastBack = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // (the app's own log, kept for a problem report)
        LogKeeper.start(this)
        leaveLumen2Behind()
        if (BuildConfig.IMMERSIVE) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        LowSpace.relieve(this)

        val root = FrameLayout(this)
        root.setBackgroundColor(getColor(R.color.background))
        setContentView(root)
        Phone.attach(this, root) {}
        // (no accessibility tree built every frame when nothing reads it: a remote-button app had it on, on the Mi Box)
        QuietAccessibility.watch(root)
        // (a newer Lumos offered once a little after starting, when there is one)
        root.postDelayed({ if (!isFinishing) Updates.checkApp(this) }, 8000)

        // (the player's reports go to the engine whether or not this screen is still there: Android can close it while
        // a film plays on a TV short of memory, and its own listener went with it, so nothing watched was kept, not
        // even where it was closed)
        PlaybackBus.listener = AppPlayback
        // (a player closed while there was nowhere for it to go: where it got to, kept now)
        AppPlayback.onClosed()

        // (Lumen's screens handle Back first, with their own handlers; on Home, Back twice leaves)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val now = System.currentTimeMillis()
                if (now - lastBack < 2500) finish()
                else {
                    lastBack = now
                    Toast.makeText(this@MainActivity, "Press Back again to leave ${BuildConfig.APP_NAME}", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    /**
     * Once, the first time it starts: what Lumen 2 left behind when this installed over it. Its hidden Stremio page's
     * storage (often hundreds of MB, on TVs short of room), its caches, and its cached screens (another account's rows
     * and profiles, from Stremio). Nothing of this app's own is lost: it has nothing yet on the first start
     */
    private fun leaveLumen2Behind() {
        val marks = getSharedPreferences("lumen3", MODE_PRIVATE)
        if (marks.getBoolean("moved", false)) return
        runCatching {
            java.io.File(applicationInfo.dataDir, "app_webview").deleteRecursively()
            cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            getSharedPreferences("phone-cache", MODE_PRIVATE).edit().clear().commit()
        }
        marks.edit().putBoolean("moved", true).apply()
    }

    override fun onResume() {
        super.onResume()
        com.notdxbi.dxbi.tv.live.LumenHandoff.let { h ->
            if (h.playerOpened) {
                h.playerOpened = false
                h.backOffer.value = h.returnTo
                h.returnTo = null
            }
        }
        window.decorView.postDelayed({ getSharedPreferences("phone-cache", MODE_PRIVATE).edit().remove("x.playerUntil").apply() }, 4000)
        hideSystemBars()
        Updates.onResume(this)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) return true
        return super.dispatchKeyEvent(event)
    }

    /** A stream for the player (from the engine) */
    fun play(json: String) {
        // (from a channel's "Watch in Lumen": start where it was asked, and offer the channel back when it closes)
        val handoff = com.notdxbi.dxbi.tv.live.LumenHandoff
        val json = handoff.startMsOnce?.let { at ->
            handoff.startMsOnce = null
            runCatching { org.json.JSONObject(json).put("startMs", at).toString() }.getOrDefault(json)
        } ?: json
        if (handoff.returnTo != null) handoff.playerOpened = true
        PlaybackBus.started(json)
        // (the TV may close this screen while the player has it: coming back mustn't ask who's watching again)
        getSharedPreferences("phone-cache", MODE_PRIVATE).edit().putLong("x.playerUntil", System.currentTimeMillis() + 12 * 3600_000L).apply()
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_STREAM, json))
    }

    /** Whether there's a screen to show yet (what the intro waits for): Lumen's screens are up at once */
    fun pageReady(answer: (Boolean) -> Unit) = answer(true)

    /** (Lumen TV ran scripts in its page; there's no page) */
    fun js(code: String) {}

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        HeldPlayer.trim(level)
    }

    /** The player's reports, to the engine (Phone): for the whole app, not this screen (see onCreate) */
    object AppPlayback : PlaybackBus.Listener {
        override fun onProgress(positionMs: Long, durationMs: Long, paused: Boolean) = Phone.progress(positionMs, durationMs, paused)
        override fun onClosed() {
            val close = PlaybackBus.takePendingClose()?.let { runCatching { org.json.JSONObject(it) }.getOrNull() } ?: return
            Phone.closed(close.optLong("timeMs"), close.optLong("durationMs"))
        }
    }
}
