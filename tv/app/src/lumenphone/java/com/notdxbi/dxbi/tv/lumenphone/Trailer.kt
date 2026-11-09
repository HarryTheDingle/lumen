package com.notdxbi.dxbi.tv.lumenphone

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.net.ConnectivityManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/** Whether the connection is mobile data (a trailer playing by itself would use it up: iOS skips it too) */
fun onMobileData(context: android.content.Context): Boolean =
    (context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.isActiveNetworkMetered == true

/**
 * A title's trailer, playing silently and looped over its art, as Apple TV's pages do (iOS TrailerPreview):
 * YouTube's privacy-enhanced player, with everything but the video hidden, and out of sight until the video is
 * really playing ([onPlaying]), so YouTube's loading screen and its errors never show. [muted] mutes it; it keeps going.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TrailerPreview(id: String, muted: Boolean, modifier: Modifier = Modifier, onPlaying: () -> Unit) {
    val context = LocalContext.current
    val playing by rememberUpdatedState(onPlaying)
    val quiet by rememberUpdatedState(muted)
    val web = remember(id) {
        WebView(context).apply {
            // a picture behind the page, nothing to touch
            isFocusable = false
            isClickable = false
            setBackgroundColor(AndroidColor.BLACK)
            alpha = 0f
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.domStorageEnabled = true
            webChromeClient = WebChromeClient()
            addJavascriptInterface(object {
                @JavascriptInterface
                fun playing() { post { alpha = 1f; playing() } }
            }, "LumenTrailer")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript("window.lumenMuted=$quiet;$TRAILER_SCRIPT", null)
                }
            }
            val q = "autoplay=1&mute=1&controls=0&cc_load_policy=0&disablekb=1&fs=0&iv_load_policy=3&modestbranding=1&playsinline=1&rel=0&loop=1&playlist=$id"
            // YouTube's player wants to know which site it's on: Stremio's, as in the TV's cards
            loadUrl("https://www.youtube-nocookie.com/embed/$id?$q", mapOf("Referer" to "https://web.stremio.com/"))
        }
    }
    LaunchedEffect(muted) { web.evaluateJavascript("window.lumenMuted=$muted", null) }
    DisposableEffect(web) {
        onDispose {
            web.loadUrl("about:blank")
            web.destroy()
        }
    }
    AndroidView(factory = { web }, modifier = modifier)
}

/** Hides all of YouTube's player but its video, fills the space with it, keeps it going, and says when it is */
private const val TRAILER_SCRIPT = """
(function () {
  var s = document.createElement('style');
  s.textContent = 'html,body{margin:0!important;overflow:hidden!important;background:#000!important}body *{visibility:hidden!important}video{visibility:visible!important;object-fit:cover!important;width:100vw!important;height:100vh!important;left:0!important;top:0!important}';
  document.documentElement.appendChild(s);
  var said = false;
  setInterval(function () {
    var v = document.querySelector('video');
    if (!v) return;
    var quiet = window.lumenMuted !== false;
    if (v.muted !== quiet) v.muted = quiet;
    if (v.paused) v.play().catch(function () {});
    if (!said && !v.paused && v.currentTime > 0.3) { said = true; LumenTrailer.playing(); }
  }, 300);
})();
"""
