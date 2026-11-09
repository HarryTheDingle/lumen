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
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.notdxbi.dxbi.tv.phone.Stremio
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild

/** The tabs, as the iOS app has them (App.swift's AppTab): Home, Discover, Library, and Search last */
enum class Tab(val route: String, val label: String, val on: ImageVector, val off: ImageVector) {
    Home("home", "Home", Icons.Filled.Home, Icons.Outlined.Home),
    Discover("discover", "Discover", Icons.Filled.GridView, Icons.Outlined.GridView),
    Library("library", "Library", Icons.Filled.VideoLibrary, Icons.Outlined.VideoLibrary),
    Search("search", "Search", Icons.Filled.Search, Icons.Outlined.Search),
    /** (iOS opens Profile from the picture on Home; a phone's tab bar has room, and the side rail has too) */
    Profile("profile", "Profile", Icons.Filled.Person, Icons.Outlined.Person),
}

/** How tall the tab bar is above the gesture bar: screens leave this much room under their last row */
private val BarHeight = 64.dp

private fun NavHostController.switchTo(tab: Tab) {
    navigate(tab.route) {
        // (tabs sit on Home: Back from any of them goes there, then leaves)
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * The app once they're in: the tabs and the screens they show. On a phone (and the Fold folded) the tabs are a bar
 * along the bottom; with room (the Fold opened out, a tablet, a wide window) they go down the side, as the iPad's
 * sidebar does. Which one is chosen by [AppLayout], never by what device it is.
 */
@Composable
fun Shell(stremio: Stremio, layout: AppLayout) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val current = Tab.entries.firstOrNull { it.route == entry?.destination?.route } ?: Tab.Home
    val haze = remember { HazeState() }
    val bottomBar = layout.compact

    Row(Modifier.fillMaxSize().background(Dx.Background)) {
        if (!bottomBar) SideRail(current) { nav.switchTo(it) }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            // (the screens draw under the status bar and the bar, and leave room for them where they need it)
            val bottomSpace = navigationBarHeight() + if (bottomBar) BarHeight else 0.dp
            NavHost(
                nav,
                startDestination = Tab.Home.route,
                modifier = Modifier.fillMaxSize().haze(haze),
                enterTransition = { fadeIn(tween(200)) },
                exitTransition = { fadeOut(tween(140)) },
                popEnterTransition = { fadeIn(tween(200)) },
                popExitTransition = { fadeOut(tween(140)) },
            ) {
                composable(Tab.Home.route) { HomeScreen(stremio, layout, bottomSpace, onProfile = { nav.switchTo(Tab.Profile) }) }
                composable(Tab.Discover.route) { DiscoverScreen(stremio, layout, bottomSpace, onOpen = {}) }
                composable(Tab.Library.route) { LibraryScreen(stremio, layout, bottomSpace, onOpen = {}) }
                composable(Tab.Search.route) { SearchScreen(stremio, layout, bottomSpace, onOpen = {}) }
                composable(Tab.Profile.route) { ProfileScreen(stremio, layout, bottomSpace) }
            }
            if (bottomBar) TabBar(current, haze, Modifier.align(Alignment.BottomCenter)) { nav.switchTo(it) }
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
            .width(96.dp)
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
