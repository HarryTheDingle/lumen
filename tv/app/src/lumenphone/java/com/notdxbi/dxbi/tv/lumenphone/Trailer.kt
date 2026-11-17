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

/** The trailer's page that's alive now (there's one at a time), so whatever starts the player can silence it at once */
@Volatile
private var liveTrailer: WebView? = null

/**
 * Stops the trailer now: Play was pressed, or the page is going. (Leaving the page does the same by itself, as the
 * preview leaves the screen; this is for the moment before that, so no sound runs under the player's loading screen.)
 */
fun stopTrailer() {
    val web = liveTrailer ?: return
    web.post { web.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){v.pause()})", null); web.onPause() }
}

/**
 * A title's trailer, playing silently and looped over its art, as Apple TV's pages do (iOS TrailerPreview):
 * YouTube's privacy-enhanced player, with everything but the video hidden, and out of sight until the video really
 * has frames on show ([onPlaying]), so YouTube's loading screen and its errors never show. Where the video plays but
 * the screen can't draw it (an emulator, a driver that paints it black) the page never says so, and the art stays.
 * [muted] mutes it; it keeps going.
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
            setBackgroundColor(AndroidColor.TRANSPARENT)
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
        liveTrailer = web
        onDispose {
            if (liveTrailer === web) liveTrailer = null
            // (stopped before it's taken down: its sound mustn't outlive the page)
            web.onPause()
            web.loadUrl("about:blank")
            web.destroy()
        }
    }
    AndroidView(factory = { web }, modifier = modifier)
}

/**
 * Hides all of YouTube's player but its video, fills the space with it, keeps it going, and says when it has frames on
 * show: frames really presented (not just a clock running), and a picture that isn't all black where the page lets it be
 * read. A video that only ever gives black is never reported, so the art stays; it gives up looking after a while.
 */
private const val TRAILER_SCRIPT = """
(function () {
  var s = document.createElement('style');
  s.textContent = 'html,body{margin:0!important;overflow:hidden!important;background:#000!important}body *{visibility:hidden!important}video{visibility:visible!important;object-fit:cover!important;width:100vw!important;height:100vh!important;left:0!important;top:0!important}';
  document.documentElement.appendChild(s);
  var said = false, frames = 0, started = Date.now(), c = document.createElement('canvas');
  c.width = 16; c.height = 9;
  var g = c.getContext('2d');
  function lit(v) {
    try {
      g.drawImage(v, 0, 0, 16, 9);
      var d = g.getImageData(0, 0, 16, 9).data, t = 0;
      for (var i = 0; i < d.length; i += 4) t += d[i] + d[i + 1] + d[i + 2];
      return t / (d.length / 4) > 18;
    } catch (e) { return null; }
  }
  function count(v) {
    if (!v.requestVideoFrameCallback || v.lumenCounting) return;
    v.lumenCounting = true;
    (function again() { v.requestVideoFrameCallback(function () { frames++; again(); }); })();
  }
  setInterval(function () {
    var v = document.querySelector('video');
    if (!v) return;
    count(v);
    var quiet = window.lumenMuted !== false;
    if (v.muted !== quiet) v.muted = quiet;
    if (v.paused) v.play().catch(function () {});
    if (said || v.paused || v.currentTime < 0.5 || !v.videoWidth) return;
    if (v.requestVideoFrameCallback && frames < 3) return;
    var ok = lit(v);
    if (ok === false) { if (Date.now() - started > 20000) said = true; return; }
    said = true;
    LumenTrailer.playing();
  }, 300);
})();
"""
