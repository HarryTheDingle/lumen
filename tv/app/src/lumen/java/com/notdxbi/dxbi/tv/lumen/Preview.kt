package com.notdxbi.dxbi.tv.lumen

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.notdxbi.dxbi.tv.phone.Title
import kotlinx.coroutines.delay

/** How long a title stays chosen before its card opens out, and then before its trailer starts */
private const val OPEN_AFTER_MS = 900L
private const val TRAILER_AFTER_MS = 1500L

/**
 * A poster that previews itself, as Netflix's TV app does (dx.bi's hover cards, for a remote): rest on it
 * and it opens out sideways into a wide card with the title's picture, logo, year and genres, pushing its
 * neighbours along; a moment later its trailer plays in it, silently. Moving on closes it at once.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PreviewPosterCard(title: Title, onClick: () -> Unit, rank: Int? = null, width: Dp = Dx.Poster) {
    val height = width * 1.5f
    var focused by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    val reveal = remember { BringIntoViewRequester() }

    LaunchedEffect(focused) {
        if (!focused) {
            open = false
            playing = false
            return@LaunchedEffect
        }
        delay(OPEN_AFTER_MS)
        open = true
        // the wider card may now run past the screen's edge: the row moves to show all of it
        delay(320)
        runCatching { reveal.bringIntoView() }
        if (title.trailer != null) {
            delay(TRAILER_AFTER_MS)
            playing = true
        }
    }
    val cardWidth by animateDpAsState(if (open) height * 16f / 9f else width, tween(300), label = "preview-width")

    FocusCard(
        onClick = onClick,
        modifier = Modifier
            .width(cardWidth)
            .height(height)
            .bringIntoViewRequester(reveal)
            .onFocusChanged { focused = it.isFocused || it.hasFocus },
    ) {
        Crossfade(open, animationSpec = tween(250), label = "preview") { wide ->
            if (!wide) {
                PosterFace(title, rank, width)
            } else {
                Box(Modifier.fillMaxSize()) {
                    Art(title.backdrop, Modifier.fillMaxSize())
                    if (playing) title.trailer?.let { Trailer(it, Modifier.fillMaxSize()) }
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.9f))))
                    Column(
                        Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TitleLogo(title, maxHeight = 34.dp, maxWidth = 180.dp, textSize = 18.sp)
                        Text(
                            listOfNotNull(title.year, typeName(title.type), title.genres.firstOrNull()).joinToString(" · "),
                            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, shadow = Shadow(Color.Black, blurRadius = 6f)),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A title's trailer, playing silently and looped, in YouTube's privacy-enhanced player (as dx.bi's hover
 * cards play them). It fades in once it's had a moment to start, so YouTube's own loading screen never shows.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun Trailer(id: String, modifier: Modifier = Modifier) {
    var shown by remember(id) { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(600), label = "trailer")
    LaunchedEffect(id) {
        delay(1400)
        shown = true
    }
    var web by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(id) {
        onDispose {
            web?.let { w ->
                w.loadUrl("about:blank")
                (w.parent as? ViewGroup)?.removeView(w)
                w.destroy()
            }
            web = null
        }
    }
    // (faded in by a black cover lifting off it: a web view drawn see-through shows its video black)
    Box(modifier) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                // the remote never lands on it: it's a picture in the card, nothing more
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                setBackgroundColor(AndroidColor.BLACK)
                settings.javaScriptEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.domStorageEnabled = true
                webViewClient = WebViewClient()
                webChromeClient = WebChromeClient()
                val q = "autoplay=1&mute=1&controls=0&disablekb=1&fs=0&iv_load_policy=3&modestbranding=1&playsinline=1&rel=0&loop=1&playlist=$id"
                // (the player's frame is a little taller than the card, so YouTube's title bar sits outside it)
                val html = """
                    <html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
                    <body style="margin:0;background:#000;overflow:hidden">
                    <iframe src="https://www.youtube-nocookie.com/embed/$id?$q" frameborder="0" allow="autoplay; encrypted-media"
                      style="position:absolute;left:-4%;top:-14%;width:108%;height:128%;border:0"></iframe>
                    </body></html>
                """.trimIndent()
                // YouTube's player wants to know which site it's on: Stremio's, as in dx.bi's own cards
                loadDataWithBaseURL("https://web.stremio.com/", html, "text/html", "utf-8", null)
                web = this
            }
        },
    )
        Box(Modifier.fillMaxSize().alpha(1f - alpha).background(Color.Black))
    }
}

/** The poster side of the card (and Netflix's Top 10 number) */
@Composable
private fun PosterFace(title: Title, rank: Int?, width: Dp) {
    Box(Modifier.fillMaxSize()) {
        Art(title.art, Modifier.fillMaxSize())
        if (rank != null) {
            Text(
                "$rank",
                style = TextStyle(fontSize = (width.value * 0.3f).sp, fontWeight = FontWeight.Black, shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 12f)),
                color = Color.White,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (title.watched) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "Watched",
                tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).background(Color.Black, CircleShape),
            )
        }
    }
}
