package com.notdxbi.dxbi.tv.lumen

import android.view.WindowManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.notdxbi.dxbi.tv.phone.Title
import kotlinx.coroutines.delay

/** Lumen's screens, in the menu down the left as on Apple TV */
enum class Tab(val label: String, val icon: ImageVector) {
    Search("Search", Icons.Filled.Search),
    Home("Home", Icons.Filled.Home),
    Discover("Discover", Icons.Filled.Explore),
    Settings("Settings", Icons.Filled.Settings),
}

/** A page over the tabs */
sealed interface Page {
    data class Details(val title: Title, val play: Boolean = false) : Page
    data object Catalogs : Page
    /** Someone from a cast: TMDB's id for them, their name and photo (shown while it loads) */
    data class Person(val id: String, val name: String, val photo: String?) : Page
    /** Who's watching? and the accounts */
    data object Profiles : Page
    /** Settings' own pages */
    data object Networks : Page
    data object Addons : Page
}

/**
 * Where the app is: the tab, and the pages open over it. Each page is a window of its own, so the remote
 * stays in it, and Back returns to exactly where it was underneath.
 */
@Stable
class Nav {
    var tab by mutableStateOf(Tab.Home)
    val pages = mutableStateListOf<Page>()
    /** What Search looks for (a name chosen on a title's page) */
    var query by mutableStateOf("")
    /** The screen's name in the corner, to come back to after switching */
    val tabFocus = FocusRequester()
    /** The menu down the left is open */
    var menuOpen by mutableStateOf(false)
    /** When it last closed (the remote lands back on the corner then, which mustn't open it again) */
    var menuClosedAt = 0L
    /** When the remote last moved up or left */
    var movedAt = 0L
    /** Home's place, kept while another tab is showing */
    val homeList = LazyListState()
    /** Home has had its first look (the remote starts on the billboard's Play just the once) */
    var homeStarted = false

    fun open(title: Title, play: Boolean = false) {
        pages += Page.Details(title, play)
    }

    fun push(page: Page) {
        pages += page
    }

    fun back() {
        if (pages.isNotEmpty()) pages.removeAt(pages.lastIndex)
    }

    /** Search for a name, from anywhere */
    fun search(text: String) {
        pages.clear()
        query = text
        tab = Tab.Search
    }
}

/** True for a page with another over it (it stops its trailer, and opens its title again once it's on top) */
val LocalCovered = compositionLocalOf { false }

/**
 * The page's own window, filling the screen, fading in and out. [home]: where the remote goes when it
 * has nowhere in the page (as it's back from the player, which leaves it nowhere)
 */
@Composable
fun PageWindow(onBack: () -> Unit, dim: Float = 0f, home: FocusRequester? = null, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.let {
                if (dim > 0f) {
                    it.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    it.setDimAmount(dim)
                } else {
                    it.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                }
                it.setWindowAnimations(android.R.style.Animation_Toast)
            }
        }
        var hasFocus by remember { mutableStateOf(false) }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val life by lifecycle.currentStateFlow.collectAsState()
        val resumed = life.isAtLeast(Lifecycle.State.RESUMED)
        LaunchedEffect(resumed) {
            if (!resumed || home == null) return@LaunchedEffect
            delay(250)
            if (!hasFocus) runCatching { home.requestFocus() }
        }
        Box(Modifier.fillMaxSize().onFocusChanged { hasFocus = it.hasFocus }) { content() }
    }
}

/** Asks for the remote's focus once the window's up (a moment after it opens) */
@Composable
fun FocusOnOpen(requester: FocusRequester, key: Any? = Unit) {
    LaunchedEffect(key) {
        delay(80)
        runCatching { requester.requestFocus() }
    }
}

/**
 * The screen's name in a small capsule in the top corner, as Apple TV has it (no logo, no tab bar): the
 * remote moving up onto it opens the menu down the left
 */
@Composable
fun ScreenTag(nav: Nav) {
    Box(Modifier.padding(start = Dx.Gutter - 8.dp, top = 22.dp)) {
        val shape = RoundedCornerShape(50)
        Surface(
            onClick = { nav.menuOpen = true },
            modifier = Modifier
                .focusRequester(nav.tabFocus)
                // (only when the remote moved it there: not the app's first focus, nor coming back from the menu)
                .onFocusChanged {
                    val now = System.currentTimeMillis()
                    if (it.isFocused && now - nav.movedAt < 400 && now - nav.menuClosedAt > 600) nav.menuOpen = true
                },
            shape = ClickableSurfaceDefaults.shape(shape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Black.copy(alpha = 0.28f),
                contentColor = Color.White,
                focusedContainerColor = Color.White,
                focusedContentColor = Color.Black,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        ) {
            Row(Modifier.padding(start = 6.dp, end = 14.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(22.dp).background(Color.White.copy(alpha = 0.22f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(nav.tab.icon, contentDescription = null, modifier = Modifier.size(13.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(nav.tab.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * Apple TV's menu: a pane of frosted glass down the left with who's signed in and the time, then
 * Search, Home, Discover and Settings. Back or right closes it.
 */
@Composable
fun SideMenu(nav: Nav, email: String?, viewer: com.notdxbi.dxbi.tv.phone.Viewer?) {
    val chosen = remember { FocusRequester() }
    fun close() {
        nav.menuClosedAt = System.currentTimeMillis()
        nav.menuOpen = false
    }
    PageWindow(onBack = { close() }) {
        FocusOnOpen(chosen)
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.65f), 0.45f to Color.Transparent))
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionRight) { close(); true } else false
                },
        ) {
            Column(
                Modifier
                    .padding(start = Dx.Gutter - 14.dp, top = 18.dp)
                    .width(230.dp)
                    .background(Color(0xFF2A2A2E).copy(alpha = 0.92f), RoundedCornerShape(24.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // who's watching: OK opens the profiles
                Surface(
                    onClick = { close(); nav.push(Page.Profiles) },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                    colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, contentColor = Color.White, focusedContainerColor = Color.White, focusedContentColor = Color.Black),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
                ) {
                Row(Modifier.padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (viewer != null) ProfileAvatar(viewer.name, viewer.colour, 28.dp) else Avatar(email, 28.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(viewer?.name ?: email?.substringBefore('@') ?: "Guest", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
                    Text(java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date()), fontSize = 13.sp, color = androidx.tv.material3.LocalContentColor.current.copy(alpha = 0.6f))
                }
                }
                Spacer(Modifier.height(8.dp))
                Tab.entries.forEach { t ->
                    val on = nav.tab == t
                    val shape = RoundedCornerShape(50)
                    Surface(
                        onClick = {
                            nav.tab = t
                            close()
                        },
                        modifier = Modifier.fillMaxWidth().then(if (on) Modifier.focusRequester(chosen) else Modifier),
                        shape = ClickableSurfaceDefaults.shape(shape),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (on) Color.White.copy(alpha = 0.14f) else Color.Transparent,
                            contentColor = Color.White,
                            focusedContainerColor = Color.White,
                            focusedContentColor = Color.Black,
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(t.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(t.label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

/**
 * A word to choose, as Apple TV's season names: plain text, a soft capsule behind the one that's chosen,
 * white when the remote's on it. [tint]: the chosen one's capsule in a colour (a streaming service's)
 */
@Composable
fun Pill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) (tint?.copy(alpha = 0.45f) ?: Color.White.copy(alpha = 0.18f)) else Color.Transparent,
            contentColor = if (selected) Color.White else Color.White.copy(alpha = 0.72f),
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Row(
            Modifier.padding(start = if (leading != null) 8.dp else 16.dp, end = 16.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            leading?.invoke()
            Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** One thing a menu can do */
data class Action(val label: String, val icon: ImageVector? = null, val run: () -> Unit)

/** A long-press or … menu: its things to do, in a column in the middle of the screen */
@Composable
fun ActionMenu(heading: String, actions: List<Action>, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    // A long press opens it while OK's still held: letting go mustn't choose the first thing in it. OK
    // counts only once it's been pressed afresh here.
    var armed by remember { mutableStateOf(false) }
    PageWindow(onBack = onDismiss, dim = 0.7f) {
        FocusOnOpen(first)
        Box(
            Modifier.fillMaxSize().onPreviewKeyEvent {
                val ok = it.key == Key.DirectionCenter || it.key == Key.Enter || it.key == Key.NumPadEnter
                if (!ok || armed) return@onPreviewKeyEvent false
                if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.repeatCount == 0) {
                    armed = true
                    false
                } else true
            },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .width(380.dp)
                    .background(Color(0xFF181818), RoundedCornerShape(22.dp))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(heading, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, modifier = Modifier.padding(start = 10.dp, bottom = 8.dp))
                actions.forEachIndexed { i, a ->
                    val shape = RoundedCornerShape(12.dp)
                    Surface(
                        onClick = {
                            onDismiss()
                            a.run()
                        },
                        modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                        shape = ClickableSurfaceDefaults.shape(shape),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.Transparent,
                            contentColor = Color.White,
                            focusedContainerColor = Color.White,
                            focusedContentColor = Color.Black,
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            a.icon?.let {
                                Icon(it, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(14.dp))
                            }
                            Text(a.label, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

/**
 * While a trailer plays behind a screen, as on Apple TV: after a few seconds of the remote being still,
 * the screen's words and buttons fade away to leave just the video, and the next press only brings
 * them back (it doesn't do anything else).
 */
@Stable
class Stillness {
    var faded by mutableStateOf(false)
        internal set
    internal var presses by mutableIntStateOf(0)
    private var swallowUp = false

    /** For the screen's onPreviewKeyEvent: true when the press only brought the screen back */
    fun key(e: KeyEvent): Boolean {
        if (e.type == KeyEventType.KeyDown) {
            presses++
            if (faded) {
                faded = false
                swallowUp = true
                return true
            }
            return false
        }
        if (e.type == KeyEventType.KeyUp && swallowUp) {
            swallowUp = false
            return true
        }
        return false
    }
}

/** A screen's [Stillness], fading after [afterMs] of no presses while [active] (a trailer's showing) */
@Composable
fun rememberStillness(active: Boolean, afterMs: Long = 5000): Stillness {
    val still = remember { Stillness() }
    LaunchedEffect(active, still.presses) {
        if (!active) {
            still.faded = false
            return@LaunchedEffect
        }
        delay(afterMs)
        still.faded = true
    }
    return still
}

/** How visible a screen's words and buttons are: they fade slowly away, and come straight back */
@Composable
fun Stillness.alpha(): Float {
    val a by animateFloatAsState(if (faded) 0f else 1f, tween(if (faded) 1200 else 250), label = "stillness")
    return a
}
