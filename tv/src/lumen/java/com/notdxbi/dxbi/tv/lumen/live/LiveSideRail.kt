package com.notdxbi.dxbi.tv.lumen.live

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.notdxbi.dxbi.tv.lumen.Dx
import kotlinx.coroutines.delay

/**
 * Auto-hiding frosted glass slide-out Live TV section rail (`Search`, `Guide`, `Sports`, `Favs`, `Settings`).
 *
 * Completely hidden while browsing content so screens use full width; pressing Right, picking
 * a section, or pressing Back closes it cleanly.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LiveSideRail(
    open: Boolean,
    activeSection: LiveSection,
    railRequesters: Map<LiveSection, FocusRequester>,
    onSelectSection: (LiveSection) -> Unit,
    onClose: () -> Unit,
    onHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val homeRequester = remember { FocusRequester() }
    val topSections = remember {
        listOf(
            LiveSection.SEARCH,
            LiveSection.TV_GUIDE,
            LiveSection.SPORTS,
            LiveSection.FAVOURITES,
        )
    }

    LaunchedEffect(open, activeSection) {
        if (open) {
            delay(50)
            runCatching { railRequesters.getValue(activeSection).requestFocus() }
        }
    }

    AnimatedVisibility(
        visible = open,
        enter = slideInHorizontally { -it } + fadeIn(),
        exit = slideOutHorizontally { -it } + fadeOut(),
        modifier = modifier,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Subtle backdrop scrim behind the slide-out rail
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.68f),
                            0.38f to Color.Black.copy(alpha = 0.25f),
                            0.7f to Color.Transparent,
                        ),
                    ),
            )

            Column(
                modifier = Modifier
                    .padding(start = 18.dp, top = 20.dp, bottom = 20.dp)
                    .width(204.dp)
                    .fillMaxHeight()
                    .liveGlass(RoundedCornerShape(22.dp))
                    .padding(horizontal = 12.dp, vertical = 16.dp)
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) {
                            onClose()
                            true
                        } else {
                            false
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Back to Lumen's Home, at the top (Back in Live TV opens this menu, not Lumen's)
                RailRow(
                    label = "Home",
                    icon = Icons.Filled.Home,
                    active = false,
                    requester = homeRequester,
                    onClick = onHome,
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .height(1.dp)
                        .background(LivePalette.Hairline),
                )
                Text(
                    text = "Live TV",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )

                topSections.forEach { section ->
                    RailItem(
                        section = section,
                        active = activeSection == section,
                        requester = railRequesters.getValue(section),
                        onClick = {
                            onSelectSection(section)
                            onClose()
                        },
                    )
                }

                Spacer(Modifier.weight(1f))

                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .height(1.dp)
                        .background(LivePalette.Hairline),
                )

                RailItem(
                    section = LiveSection.SETTINGS,
                    active = activeSection == LiveSection.SETTINGS,
                    requester = railRequesters.getValue(LiveSection.SETTINGS),
                    onClick = {
                        onSelectSection(LiveSection.SETTINGS)
                        onClose()
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun RailItem(
    section: LiveSection,
    active: Boolean,
    requester: FocusRequester,
    onClick: () -> Unit,
) {
    val label = when (section) {
        LiveSection.SEARCH -> "Search"
        LiveSection.TV_GUIDE -> "Guide"
        LiveSection.SPORTS -> "Sports"
        LiveSection.FAVOURITES -> "Favs"
        LiveSection.SETTINGS -> "Settings"
    }
    RailRow(label, sectionIcon(section), active, requester, onClick)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun RailRow(
    label: String,
    icon: ImageVector,
    active: Boolean,
    requester: FocusRequester,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(requester)
            .focusProperties {
                onExit = {
                    if (requestedFocusDirection == FocusDirection.Left) {
                        cancelFocusChange()
                    }
                }
            }
            .onFocusChanged { focused = it.isFocused || it.hasFocus },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (active) Color.White.copy(alpha = 0.14f) else Color.Transparent,
            contentColor = if (active) Color.White else Color.White.copy(alpha = 0.72f),
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
    ) {
        val ink = LocalContentColor.current
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (active && !focused) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(Dx.Blue, CircleShape),
                )
            }
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                fontSize = 15.sp,
                fontWeight = if (active || focused) FontWeight.Bold else FontWeight.Medium,
                color = ink,
                maxLines = 1,
            )
        }
    }
}

private fun sectionIcon(section: LiveSection): ImageVector = when (section) {
    LiveSection.SEARCH -> Icons.Filled.Search
    LiveSection.TV_GUIDE -> Icons.Filled.LiveTv
    LiveSection.SPORTS -> Icons.Filled.EmojiEvents
    LiveSection.FAVOURITES -> Icons.Filled.Favorite
    LiveSection.SETTINGS -> Icons.Filled.Settings
}
