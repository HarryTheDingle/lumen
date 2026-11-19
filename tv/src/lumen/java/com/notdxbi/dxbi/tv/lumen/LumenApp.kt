package com.notdxbi.dxbi.tv.lumen

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.tv.material3.Surface
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Border
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import coil3.compose.AsyncImage
import coil3.request.crossfade
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.notdxbi.dxbi.tv.phone.Engine
import com.notdxbi.dxbi.tv.phone.Load
import com.notdxbi.dxbi.tv.phone.Stremio
import com.notdxbi.dxbi.tv.phone.Title
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lumen on a TV: its own screens over Stremio's page, which keeps doing Stremio's work underneath
 * ([Engine]), as in the iPad app. Worked by remote: every screen is native, the page is never shown.
 */
@Composable
fun LumenApp(engine: Engine) {
    val context = LocalContext.current
    // pictures come up with a soft fade as they arrive, not a pop
    // (with an app's own name as its user agent: Wikimedia turns away OkHttp's default one, and every cast photo from
    // Wikipedia came back refused, leaving only initials)
    remember { CheckedArt.start(context.applicationContext, engine.core.sync); Labels.start(engine.core.sync) }
    coil3.compose.setSingletonImageLoaderFactory { ctx ->
        val client = okhttp3.OkHttpClient.Builder()
            .addNetworkInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", "Lumen/1.0 (Android TV)").build()) }
            .build()
        coil3.ImageLoader.Builder(ctx)
            .crossfade(450)
            // (metahub's pictures swapped for checked TMDB ones as they load: CheckedArt.kt)
            .components {
                add(CheckedArt.Swap)
                add(coil3.network.okhttp.OkHttpNetworkFetcherFactory(callFactory = { client }))
            }
            .build()
    }
    // (Stremio's state is read and turned into screens' data off the main thread, so moving never waits for it)
    val scope = rememberCoroutineScope { Dispatchers.Default }
    val stremio = remember { Stremio(context.applicationContext, engine, scope) }
    LumenTheme {
        val account by stremio.account.collectAsState()
        val ready by stremio.ready.collectAsState()
        val viewers by stremio.viewers.collectAsState()
        val chosen by stremio.profileChosen.collectAsState()
        val needsAddons by engine.needsAddons.collectAsState()
        val a = account
        when {
            a == null && !ready -> Starting()
            // (Lumos: its own sign-in, a code approved on a signed-in device)
            a != null && !a.signedIn -> LumosSignIn(stremio)
            needsAddons -> LumosAddons(stremio)
            viewers.size > 1 && !chosen -> ProfilePicker(stremio) { stremio.profileChosen.value = true }
            else -> {
                Screens(stremio, scope)
                // (once in, after a crash: offer to send Harry what happened)
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(2500)
                    (context as? android.app.Activity)?.let { ProblemReport.offerAfterCrash(it, engine) }
                }
            }
        }
    }
}

@Composable
private fun Screens(stremio: Stremio, scope: CoroutineScope) {
    val context = LocalContext.current
    val watching by stremio.continueWatching.collectAsState()
    val account by stremio.account.collectAsState()
    val nav = remember { Nav() }
    val feed = remember { DiscoverFeed(context.cacheDir) }
    // what this profile said it likes (Taste.kt): read for whoever's watching, and asked once when they have nothing watched
    val taste = remember { TasteState(context.applicationContext, feed, stremio) }
    val viewerNow by stremio.viewer.collectAsState()
    val noHistory by stremio.noHistory.collectAsState()
    LaunchedEffect(viewerNow?.id) { taste.load(null, viewerNow?.id) }
    LaunchedEffect(noHistory, taste.loadedFor, taste.current) {
        if (noHistory && taste.current == null && taste.loadedFor != null) taste.tuning = true
    }

    // Back on Discover, Search, the Calendar or Settings: the menu, as the Apple TV app's (Home's own Back goes to its top first)
    // (Discover's and the Calendar's own Back takes them to their top first, as Home's does)
    BackHandler(enabled = nav.tab != Tab.Home && !nav.menuOpen) { nav.menuOpen = true }
    // a new screen: the remote starts on its name in the corner
    var lastTab by remember { mutableStateOf(nav.tab) }
    LaunchedEffect(nav.tab) {
        if (nav.tab == lastTab) return@LaunchedEffect
        lastTab = nav.tab
        delay(60)
        runCatching { nav.tabFocus.requestFocus() }
    }

    // Play, everywhere: the loading screen, then the player
    val launcher = remember { Launcher(stremio, scope) }
    // a player whose stream failed while loading: the loading screen up again behind it, for the next best
    val retrying by LoadingHandoff.retries.collectAsState()
    LaunchedEffect(retrying) { if (retrying != null) launcher.retry() }
    val play: (Title) -> Unit = { title -> launcher.play(title, watching.firstOrNull { it.title.id == title.id }?.videoId) }
    // what a preview card's menu and caption do
    val cardActions = remember {
        CardActions(
            play = { play(it) },
            setInList = { title, add ->
                stremio.setInList(title, add)
                Toast.makeText(context, if (add) "Added to your Library" else "Removed from your Library", Toast.LENGTH_SHORT).show()
            },
            // (what's in My List already, then what's been added or taken out here)
            listMarks = combine(stremio.myList, stremio.listMarks) { rows, marks -> rows.flatMap { it.items }.associate { it.id to true } + marks }
                .stateIn(scope, SharingStarted.Eagerly, stremio.listMarks.value),
            trailer = { t -> feed.trailer(t.type, t.id) },
            watched = stremio.watchedIds,
            genres = { t -> stremio.genres(t.type, t.id) },
            turnedDown = stremio.turnedDown,
            notInterested = { t ->
                stremio.notInterested(t)
                Toast.makeText(context, "${t.name} won't be suggested again", Toast.LENGTH_SHORT).show()
            },
        )
    }

    CompositionLocalProvider(LocalCardActions provides cardActions, LocalLauncher provides launcher, LocalTaste provides taste, LocalDiscoverFeed provides feed) {
    // (noted, so the corner opens the menu only when the remote moves onto it)
    Box(Modifier.fillMaxSize().onPreviewKeyEvent {
        if (it.type == KeyEventType.KeyDown && (it.key == Key.DirectionUp || it.key == Key.DirectionLeft)) nav.movedAt = System.currentTimeMillis()
        false
    }) {
    // a new screen's content comes up a moment after the menu closes, as Apple TV's pages do
    key(nav.tab) {
        val rise = remember { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(Unit) {
            delay(150)
            rise.animateTo(1f, tween(380))
        }
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = rise.value }) {
        when (nav.tab) {
            Tab.Home -> HomeScreen(
                stremio,
                nav,
                onPlay = { play(it) },
            )
            Tab.Discover -> DiscoverScreen(stremio, nav, feed)
            Tab.Search -> SearchScreen(stremio, nav)
            Tab.Calendar -> CalendarScreen(stremio, nav, feed)
            Tab.Settings -> SettingsScreen(stremio, nav)
        }
        }
    }
    }

    // Apple TV's menu down the left
    val viewer by stremio.viewer.collectAsState()
    if (nav.menuOpen) SideMenu(nav, account?.email, viewer)

    // Back: the top page fades out, then goes (the effect restarts if Back is pressed again, which finishes it at once)
    LaunchedEffect(nav.leaving) {
        if (nav.leaving) {
            delay(300)
            nav.finishBack()
        }
    }

    // the pages over the tab, each in its own window
    nav.pages.forEachIndexed { i, page ->
        key(page, i) {
            CompositionLocalProvider(
                LocalCovered provides (i < nav.pages.lastIndex),
                LocalLeaving provides (nav.leaving && i == nav.pages.lastIndex),
            ) {
                when (page) {
                    is Page.Details -> TitlePage(stremio, nav, page.title, page.play)
                    is Page.Person -> PersonPage(stremio, nav, page)
                    Page.Networks -> PageWindow(onBack = { nav.back() }) { NetworksScreen(stremio) }
                    is Page.Genre -> GenrePage(nav, feed, page.name)
                    Page.Christmas -> ChristmasPage(feed, nav)
                    is Page.Genius -> GeniusPage(nav, feed, stremio, page.mood)
                    is Page.Chain -> ChainPage(nav, stremio, page.name, page.titles)
                    is Page.Franchise -> FranchisePage(stremio, nav, page.id, page.name)
                    Page.Profiles -> PageWindow(onBack = { nav.back() }) { ProfilePicker(stremio) { nav.back() } }
                }
            }
        }
    }

    // first time on a profile with nothing watched (or Settings → Tune Suggestions)
    if (taste.tuning) {
        TasteSetup(taste, viewerNow?.name) { done -> taste.finish(done) }
    }

    // over everything while a title's starting
    if (launcher.title != null) LoadingScreen(launcher)
    }
}

@Composable
private fun Starting() {
    Box(Modifier.fillMaxSize().background(Dx.Background), contentAlignment = Alignment.Center) {
        Logo(130.dp)
    }
}

/**
 * A text box worked by remote: choosing it (OK) opens the TV's keyboard, and Done or Back closes it
 * again. (A text field that's simply moved onto would open the keyboard at once, and keep the arrows.)
 */
@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    type: KeyboardType,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(12.dp),
    /** Set true to open the keyboard without OK being pressed (Search, as it opens) */
    open: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(open) { if (open) editing = true }
    val box = remember { FocusRequester() }
    val input = remember { FocusRequester() }
    val password = type == KeyboardType.Password
    // (the box the remote goes back to isn't there until typing has finished: focus is asked of it once it's drawn, not at
    // once, which found nothing and dropped the remote on the screen's name in the corner. [then]: a move to make after, as
    // Down out of the box with the keyboard away goes on to the results)
    var refocus by remember { mutableStateOf<androidx.compose.ui.focus.FocusDirection?>(null) }
    var refocusAsked by remember { mutableStateOf(false) }
    var boxFocused by remember { mutableStateOf(false) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    fun done(then: androidx.compose.ui.focus.FocusDirection? = null) {
        editing = false
        refocus = then
        refocusAsked = true
    }
    LaunchedEffect(editing, refocusAsked) {
        if (editing || !refocusAsked) return@LaunchedEffect
        refocusAsked = false
        // (a few frames at most for the box to be drawn and take it)
        for (i in 0 until 20) {
            androidx.compose.runtime.withFrameNanos { }
            if (boxFocused) break
            runCatching { box.requestFocus() }
        }
        delay(60)
        refocus?.let { focusManager.moveFocus(it) }
        refocus = null
    }
    LaunchedEffect(editing) { if (editing) runCatching { input.requestFocus() } }
    // The keyboard closed by its own Back (which the box never sees): out of typing too, so the remote moves on again
    // (soon: the text box has lost the remote by then, and a press in the meantime started from nowhere, the corner)
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    val keyboardUp = WindowInsets.isImeVisible
    var keyboardSeen by remember { mutableStateOf(false) }
    // (not while the voice typing the remote's mic starts has the keyboard away or the window: the box has to stay for what it types)
    val windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(editing, keyboardUp, windowFocused) {
        if (!editing) { keyboardSeen = false; return@LaunchedEffect }
        if (keyboardUp) keyboardSeen = true
        else if (keyboardSeen && windowFocused) { delay(400); done() }
    }

    if (editing) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { done() }),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = modifier
                .fillMaxWidth()
                .focusRequester(input)
                .onPreviewKeyEvent {
                    when {
                        it.type == KeyEventType.KeyUp && it.key == Key.Back -> { done(); true }
                        // (up or down with the keyboard away: out of the box, so the next press moves on)
                        it.type == KeyEventType.KeyDown && (it.key == Key.DirectionUp || it.key == Key.DirectionDown) -> {
                            done(if (it.key == Key.DirectionDown) androidx.compose.ui.focus.FocusDirection.Down else androidx.compose.ui.focus.FocusDirection.Up)
                            true
                        }
                        else -> false
                    }
                }
                .background(Color.White.copy(alpha = 0.16f), shape)
                .border(1.5.dp, Color.White.copy(alpha = 0.6f), shape)
                .padding(horizontal = if (leading != null) 22.dp else 16.dp, vertical = 14.dp),
        )
    } else {
        Surface(
            onClick = { editing = true },
            modifier = modifier.fillMaxWidth().focusRequester(box).onFocusChanged { boxFocused = it.isFocused },
            shape = ClickableSurfaceDefaults.shape(shape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.08f),
                contentColor = Color.White,
                focusedContainerColor = Color.White,
                focusedContentColor = Color.Black,
            ),
            
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        ) {
            val shown = if (password) "•".repeat(value.length) else value
            androidx.compose.foundation.layout.Row(
                Modifier.padding(horizontal = if (leading != null) 22.dp else 16.dp, vertical = 14.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            ) {
                leading?.invoke()
                Text(
                    shown.ifEmpty { label },
                    fontSize = 18.sp,
                    color = if (value.isEmpty()) androidx.tv.material3.LocalContentColor.current.copy(alpha = 0.5f) else Color.Unspecified,
                )
            }
        }
    }
}
