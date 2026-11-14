package com.notdxbi.dxbi.tv.lumenphone

import android.widget.Toast
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import com.notdxbi.dxbi.tv.phone.Stremio
import com.notdxbi.dxbi.tv.phone.Title
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Where you can go from a tab. Pages sit on top of the tab that opened them, as on iOS (each tab has a
 * NavigationStack of its own): a title, a cast member's page, the profile.
 */
sealed interface Page {
    /** [play]: Play was pressed on the way (Home's billboard, a card's menu): the page starts it once it's open */
    data class TitleOf(val title: Title, val play: Boolean = false, val fromStart: Boolean = false, val shuffle: Boolean = false) : Page
    data class Person(val id: String, val name: String, val photo: String?) : Page
    data object Profile : Page
}

/** A page on a tab's stack. (Whether its first slide in has been seen is kept, so coming back to the top doesn't slide it in again.) */
@Stable
class Entry(val page: Page, val id: Int) {
    var entered = false
}

/**
 * The tabs and the pages on each. Every tab keeps its own stack, so a title opened from Discover is still there
 * when you come back to Discover from Home, and a tab's scroll position survives all of it (see [NavStack]).
 */
@Stable
class AppNav(private val scope: CoroutineScope) {
    var tab by mutableStateOf(Tab.Home)
        private set

    private val stacks = Tab.entries.associateWith { mutableStateListOf<Entry>() }
    private var ids = 0

    /** How far the page on top is on its way out, 0 to 1: the back gesture's finger, then the finish */
    val leaving = Animatable(0f)

    fun stack(tab: Tab): List<Entry> = stacks.getValue(tab)
    val top: Entry? get() = stacks.getValue(tab).lastOrNull()

    fun push(page: Page) {
        stacks.getValue(tab).add(Entry(page, ids++))
    }

    fun open(title: Title, play: Boolean = false, fromStart: Boolean = false, shuffle: Boolean = false) =
        push(Page.TitleOf(title, play, fromStart, shuffle))

    /** A tab's own button: that tab, or its first screen when it's the one already open (iOS's) */
    fun select(to: Tab) {
        if (to == tab) stacks.getValue(tab).clear() else tab = to
    }

    /** Takes the top page off, sliding it away */
    fun pop() {
        scope.launch { finishLeaving() }
    }

    internal suspend fun finishLeaving() {
        val stack = stacks.getValue(tab)
        if (stack.isEmpty()) return
        leaving.animateTo(1f, tween(220))
        stack.removeLastOrNull()
        leaving.snapTo(0f)
    }

    /** Back on a tab other than Home goes Home (then leaves the app), as Android's tabs do */
    val canGoHome get() = tab != Tab.Home && stacks.getValue(tab).isEmpty()
    fun goHome() { tab = Tab.Home }
}

/** What a long-press on a title can do, and what opens things: handed down so any screen's cards get the same menu */
@Stable
class TitleActions(val stremio: Stremio, val nav: AppNav, val launcher: Launcher) {
    fun open(t: Title) = nav.open(t)
    fun play(t: Title, fromStart: Boolean = false, shuffle: Boolean = false) = nav.open(t, play = true, fromStart = fromStart, shuffle = shuffle)
}

val LocalTitleActions = staticCompositionLocalOf<TitleActions?> { null }
val LocalLauncher = staticCompositionLocalOf<Launcher?> { null }

/** Whether [title] is in the Library: what was just chosen, what it came with, or the Library's own rows */
@Composable
fun inLibrary(stremio: Stremio, title: Title, alsoKnown: Boolean = false): Boolean {
    val marks by stremio.listMarks.collectAsState()
    val rows by stremio.myList.collectAsState()
    return marks[title.id] ?: (alsoKnown || title.inLibrary || rows.any { r -> r.items.any { it.id == title.id } })
}

/** A short message at the bottom, as iOS's toasts ("Added to your Library") */
fun say(context: android.content.Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

/**
 * The pages over the tab: the tab's own screen at the bottom, then its pages. Only the page on top and the one
 * under it are alive (the one under it is what a back swipe reveals); the others keep what they remember
 * (scroll positions) in a holder until they're back. A page slides in, and out with the system back gesture,
 * which follows the finger (predictive back).
 */
@Composable
fun NavStack(nav: AppNav, root: @Composable (Tab) -> Unit, page: @Composable (Entry, Boolean) -> Unit) {
    val holder = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()

    PredictiveBackHandler(enabled = nav.top != null) { progress ->
        try {
            progress.collect { nav.leaving.snapTo(it.progress * 0.85f) }
            nav.finishLeaving()
        } catch (_: CancellationException) {
            // let go before the end: it comes back
            scope.launch { nav.leaving.animateTo(0f, tween(180)) }
        }
    }
    androidx.activity.compose.BackHandler(enabled = nav.top == null && nav.canGoHome) { nav.goHome() }

    Crossfade(nav.tab, animationSpec = tween(160), label = "tab") { tab ->
        val stack = nav.stack(tab)
        Box(Modifier.fillMaxSize().background(Dx.Background)) {
            // (the tab's own screen is alive whenever it's the top or the one just under a page)
            if (stack.size <= 1) holder.SaveableStateProvider("root:${tab.name}") { root(tab) }
            val first = maxOf(stack.size - 2, 0)
            for (i in first until stack.size) {
                val entry = stack[i]
                val isTop = i == stack.size - 1
                key(entry.id) { holder.SaveableStateProvider("page:${entry.id}") { Layer(nav, entry, isTop) { page(entry, isTop) } } }
            }
        }
    }
}

@Composable
private fun Layer(nav: AppNav, entry: Entry, isTop: Boolean, content: @Composable () -> Unit) {
    val width = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val enter = remember(entry) { Animatable(if (entry.entered) 1f else 0f) }
    LaunchedEffect(entry) {
        if (!entry.entered) enter.animateTo(1f, tween(260))
        entry.entered = true
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val gone = if (isTop) nav.leaving.value else 0f
                val e = enter.value
                translationX = (1f - e) * width * 0.18f + gone * width * 0.22f
                scaleX = 1f - gone * 0.06f
                scaleY = 1f - gone * 0.06f
                alpha = e * (1f - gone * 0.9f)
            }
            .background(Dx.Background)
            // (a page covers what's under it: touches mustn't fall through its empty places)
            .pointerInput(Unit) {},
    ) { content() }
}
