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
    coil3.compose.setSingletonImageLoaderFactory { ctx -> coil3.ImageLoader.Builder(ctx).crossfade(350).build() }
    // (Stremio's state is read and turned into screens' data off the main thread, so moving never waits for it)
    val scope = rememberCoroutineScope { Dispatchers.Default }
    val stremio = remember { Stremio(context.applicationContext, engine, scope) }
    LumenTheme {
        val account by stremio.account.collectAsState()
        val ready by stremio.ready.collectAsState()
        val guest by stremio.guest.collectAsState()
        val swapping by stremio.swapping.collectAsState()
        val viewers by stremio.viewers.collectAsState()
        val chosen by stremio.profileChosen.collectAsState()
        val a = account
        when {
            // another account is being read in: Stremio's page starts again
            swapping -> Starting()
            a == null && !ready -> Starting()
            a != null && !a.signedIn && !guest -> SignIn(stremio)
            viewers.size > 1 && !chosen -> ProfilePicker(stremio) { stremio.profileChosen.value = true }
            else -> Screens(stremio, scope)
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
    val accountId by stremio.currentAccountId.collectAsState()
    val noHistory by stremio.noHistory.collectAsState()
    LaunchedEffect(accountId, viewerNow?.id) { taste.load(accountId, viewerNow?.id) }
    LaunchedEffect(noHistory, taste.loadedFor, taste.current) {
        if (noHistory && taste.current == null && taste.loadedFor != null) taste.tuning = true
    }

    // Back on Discover, Search or Settings: to Home (Home's own Back goes to its top, then leaves)
    BackHandler(enabled = nav.tab != Tab.Home) { nav.tab = Tab.Home }
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
    // what a preview card's Play, + and scores do
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
            scores = { t -> stremio.reviews(t.type, t.id)?.scores.orEmpty() },
            trailer = { t -> feed.trailer(t.type, t.id) },
        )
    }

    CompositionLocalProvider(LocalCardActions provides cardActions, LocalLauncher provides launcher, LocalTaste provides taste) {
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
            Tab.Settings -> SettingsScreen(stremio, nav)
        }
        }
    }
    }

    // Apple TV's menu down the left
    val viewer by stremio.viewer.collectAsState()
    if (nav.menuOpen) SideMenu(nav, account?.email, viewer)

    // the pages over the tab, each in its own window
    nav.pages.forEachIndexed { i, page ->
        key(page, i) {
            CompositionLocalProvider(LocalCovered provides (i < nav.pages.lastIndex)) {
                when (page) {
                    is Page.Details -> TitlePage(stremio, nav, page.title, page.play)
                    Page.Catalogs -> CatalogBrowser(stremio, nav)
                    is Page.Person -> PersonPage(stremio, nav, page)
                    Page.Networks -> PageWindow(onBack = { nav.back() }) { NetworksScreen(stremio) }
                    is Page.Genre -> GenrePage(nav, feed, page.name)
                    Page.Christmas -> ChristmasPage(stremio, nav)
                    Page.Addons -> PageWindow(onBack = { nav.back() }) { AddonsScreen(stremio) }
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

/** Signing in to Stremio, with the TV's keyboard (or browsing without an account) */
@Composable
private fun SignIn(stremio: Stremio) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val accounts by stremio.accounts.collectAsState()
    val adding by stremio.addingAccount.collectAsState()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Box(Modifier.fillMaxSize().background(Dx.Background), contentAlignment = Alignment.Center) {
        Column(Modifier.width(420.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Logo(100.dp)
            Text("Sign in with your Stremio account", fontSize = 16.sp, color = Dx.Muted)
            Spacer(Modifier.height(6.dp))
            Field("Email", email, { email = it }, KeyboardType.Email, Modifier.focusRequester(first))
            Field("Password", password, { password = it }, KeyboardType.Password)
            error?.let { Text(it, fontSize = 14.sp, color = Color(0xFFFF6B6B)) }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Button(
                    onClick = {
                        if (busy) return@Button
                        busy = true
                        error = null
                        scope.launch {
                            error = stremio.signIn(email.trim(), password)
                            busy = false
                        }
                    },
                    colors = ButtonDefaults.colors(containerColor = Dx.Accent.copy(alpha = 0.8f), contentColor = Dx.OnAccent, focusedContainerColor = Dx.Accent, focusedContentColor = Dx.OnAccent),
                ) { Text(if (busy) "Signing in…" else "Sign In", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                Button(
                    onClick = { stremio.continueAsGuest() },
                    colors = ButtonDefaults.colors(containerColor = Color.White.copy(alpha = 0.12f), contentColor = Color.White, focusedContainerColor = Color.White, focusedContentColor = Color.Black),
                ) { Text("Browse without an account", fontSize = 16.sp) }
            }
            // kept on this device: back in without typing
            if (accounts.isNotEmpty()) {
                Text("Saved on this device", fontSize = 13.sp, color = Dx.Muted, modifier = Modifier.padding(top = 8.dp))
                accounts.forEach { a -> Pill(a.email.ifEmpty { "Stremio account" }, selected = false, onClick = { stremio.switchAccount(a.id) }) }
            }
            if (adding) Pill("Cancel", selected = false, onClick = { stremio.cancelAddAccount() })
        }
    }
}

/**
 * A text box worked by remote: choosing it (OK) opens the TV's keyboard, and Done or Back closes it
 * again. (A text field that's simply moved onto would open the keyboard at once, and keep the arrows.)
 */
@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, type: KeyboardType, modifier: Modifier = Modifier) {
    var editing by remember { mutableStateOf(false) }
    val box = remember { FocusRequester() }
    val input = remember { FocusRequester() }
    val password = type == KeyboardType.Password
    val shape = RoundedCornerShape(12.dp)
    fun done() {
        editing = false
        runCatching { box.requestFocus() }
    }
    LaunchedEffect(editing) { if (editing) runCatching { input.requestFocus() } }

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
                    if (it.type == KeyEventType.KeyUp && it.key == Key.Back) { done(); true } else false
                }
                .background(Color.White.copy(alpha = 0.16f), shape)
                .border(1.5.dp, Color.White.copy(alpha = 0.6f), shape)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        )
    } else {
        Surface(
            onClick = { editing = true },
            modifier = modifier.fillMaxWidth().focusRequester(box),
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
            Text(
                shown.ifEmpty { label },
                fontSize = 18.sp,
                color = if (value.isEmpty()) androidx.tv.material3.LocalContentColor.current.copy(alpha = 0.5f) else Color.Unspecified,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
    }
}
