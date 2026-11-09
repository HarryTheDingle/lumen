package com.notdxbi.dxbi.tv.lumenphone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.notdxbi.dxbi.tv.phone.Stremio
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild

/** The tabs, as the iOS app has them (App.swift's AppTab): Home, Discover, Library, and Search last. Profile opens from Home's picture. */
enum class Tab(val label: String, val on: ImageVector, val off: ImageVector) {
    Home("Home", Icons.Filled.Home, Icons.Outlined.Home),
    Discover("Discover", Icons.Filled.GridView, Icons.Outlined.GridView),
    Library("Library", Icons.Filled.VideoLibrary, Icons.Outlined.VideoLibrary),
    Search("Search", Icons.Filled.Search, Icons.Outlined.Search),
}

/** How tall the tab bar is above the gesture bar: screens leave this much room under their last row */
private val BarHeight = 64.dp

/**
 * The app once they're in: the tabs and the pages on them. On a phone (and the Fold folded) the tabs are a bar
 * along the bottom; with room (the Fold opened out, a tablet, a wide window) they go down the side, as the iPad's
 * sidebar does. Which one is chosen by [AppLayout], never by what device it is. Every tab keeps its own stack of
 * pages (a title, a cast member, Profile), so tabs and their scroll positions survive pushes, tab switches and folding.
 */
@Composable
fun Shell(stremio: Stremio, layout: AppLayout) {
    val scope = rememberCoroutineScope()
    val nav = remember { AppNav(scope) }
    val launcher = remember { Launcher(stremio, scope) }
    val actions = remember { TitleActions(stremio, nav, launcher) }
    val haze = remember { HazeState() }
    val bottomBar = layout.compact

    CompositionLocalProvider(LocalTitleActions provides actions, LocalLauncher provides launcher) {
        Box(Modifier.fillMaxSize().background(Dx.Background)) {
            Row(Modifier.fillMaxSize()) {
                if (!bottomBar) SideRail(nav.tab) { nav.select(it) }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    // (the screens draw under the status bar and the bar, and leave room for them where they need it)
                    val gesture = navigationBarHeight()
                    val barShown = bottomBar && nav.top == null
                    val bottomSpace = gesture + if (barShown) BarHeight else 0.dp
                    Box(Modifier.fillMaxSize().haze(haze)) {
                        NavStack(
                            nav,
                            root = { tab ->
                                // (Discover, Library and Search are the placeholders until their screens are merged in: they take
                                // [onOpen], and anything they open pushes a title page here)
                                when (tab) {
                                    Tab.Home -> HomeScreen(stremio, layout, bottomSpace, onProfile = { nav.push(Page.Profile) }, onOpen = { nav.open(it) })
                                    Tab.Discover -> DiscoverScreen(stremio, layout, bottomSpace, onOpen = { nav.open(it) })
                                    Tab.Library -> LibraryScreen(stremio, layout, bottomSpace, onOpen = { nav.open(it) })
                                    Tab.Search -> SearchScreen(stremio, layout, bottomSpace, onOpen = { nav.open(it) })
                                }
                            },
                            page = { entry, active ->
                                when (val p = entry.page) {
                                    is Page.TitleOf -> TitlePage(
                                        stremio, layout, p, active, onBack = { nav.pop() }, onOpen = { nav.open(it) },
                                        onPerson = { id, name, photo -> nav.push(Page.Person(id, name, photo)) },
                                        onSearch = { name -> stremio.search(name); nav.select(Tab.Search) },
                                    )
                                    is Page.Person -> PersonPage(stremio, layout, p, onOpen = { nav.open(it) }, onBack = { nav.pop() })
                                    Page.Profile -> Box(Modifier.fillMaxSize()) {
                                        ProfileScreen(stremio, layout, gesture)
                                    }
                                }
                            },
                        )
                    }
                    if (barShown) TabBar(nav.tab, haze, Modifier.align(Alignment.BottomCenter)) { nav.select(it) }
                }
            }
            // Play's loading screen, over everything until the player is up
            LoadingScreen(launcher)
        }
    }
}

/** The height of the gesture or button bar at the bottom (screens' last rows clear it) */
@Composable
private fun navigationBarHeight(): Dp =
    with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }

/**
 * The tab bar: frosted black over the screen, a hairline along its top, icons outlined and the chosen one filled
 * and white (Apple TV's selection: no colour, no pill behind it).
 */
@Composable
private fun TabBar(current: Tab, haze: HazeState, modifier: Modifier, onSelect: (Tab) -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .hazeChild(haze, style = HazeStyle(backgroundColor = Dx.Background, tint = HazeTint(Color.Black.copy(alpha = 0.72f)), blurRadius = 24.dp, noiseFactor = 0f))
            .border(width = 0.5.dp, color = Dx.Line, shape = RectangleShape)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Row(Modifier.fillMaxWidth().height(BarHeight).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Tab.entries.forEach { t ->
                val on = t == current
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(t) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(if (on) t.on else t.off, contentDescription = t.label, tint = if (on) Dx.Selected else Dx.Muted, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.height(3.dp))
                    Text(t.label, color = if (on) Dx.Text else Dx.Muted, style = MaterialTheme.typography.labelMedium, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}

/** The sidebar for the opened Fold and wider windows: the tabs down the left, kept clear of the cut-out, bars and gestures */
@Composable
private fun SideRail(current: Tab, onSelect: (Tab) -> Unit) {
    Column(
        Modifier
            .fillMaxHeight()
            .width(SideRailWidth)
            .background(Dx.Background)
            .border(width = 0.5.dp, color = Dx.Line, shape = RectangleShape)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Tab.entries.forEach { t ->
            val on = t == current
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(t) }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(if (on) t.on else t.off, contentDescription = t.label, tint = if (on) Dx.Selected else Dx.Muted, modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(3.dp))
                Text(t.label, color = if (on) Dx.Text else Dx.Muted, style = MaterialTheme.typography.labelMedium, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
            }
        }
    }
}
