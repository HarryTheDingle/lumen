package com.notdxbi.dxbi.tv.lumenphone

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker

/** The side tabs' width, which the screens beside them don't have */
val SideRailWidth = 96.dp

/**
 * How much room the app has, which is all its screens ask: never what device it's on. A phone (and the Fold
 * folded, its outer screen) is [compact]; the Fold opened out flat (about 8 inches, nearly square), a tablet or
 * a window resized wide is not, and gets the larger layout rather than a stretched phone one.
 */
@Immutable
class AppLayout(private val width: WindowWidthSizeClass, val fold: FoldingFeature?, private val windowDp: Int = 412) {
    val compact get() = width == WindowWidthSizeClass.Compact

    /** Tabs down the side rather than along the bottom, as the iPad's sidebar */
    val sidebar get() = !compact

    val gutter: Dp get() = if (compact) Dx.Gutter else Dx.Gutter + 12.dp

    /** How wide the screens are: the window, less the side tabs when they're there */
    val contentWidth: Dp get() = windowDp.dp - if (sidebar) SideRailWidth else 0.dp

    /**
     * A poster in a row, sized from the room there is, so every screen's rows keep the same rhythm: a bit over
     * three across on a phone, about six on the opened Fold (as iPad portrait looks; the iPad's own 180 pt
     * posters would be only three and a half across there). Never so small it can't be read, nor larger than needed.
     */
    val poster: Dp get() {
        val across = if (compact) 3.4f else 6.2f
        return ((contentWidth - gutter) / across - 8.dp).coerceIn(96.dp, 150.dp)
    }

    /** Continue Watching's wide cards: one and a half across on a phone, two and a bit on the Fold */
    val wideCard: Dp get() =
        if (compact) (contentWidth * 0.68f).coerceIn(260.dp, 360.dp) else ((contentWidth - gutter) / 2.5f).coerceIn(250.dp, 360.dp)

    /** An episode's card on a title's page */
    val episodeCard: Dp get() =
        if (compact) (contentWidth * 0.72f).coerceIn(250.dp, 340.dp) else ((contentWidth - gutter) / 2.6f).coerceIn(250.dp, 340.dp)

    /** Half open on its side, like a laptop: the screen above and below the hinge (a title page's art above, its details below) */
    val tabletop get() = fold != null && fold.state == FoldingFeature.State.HALF_OPENED && fold.orientation == FoldingFeature.Orientation.HORIZONTAL

    /** Half open like a book: a screen either side of the hinge */
    val book get() = fold != null && fold.state == FoldingFeature.State.HALF_OPENED && fold.orientation == FoldingFeature.Orientation.VERTICAL
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** The layout for the window as it is now; it changes as the Fold folds and unfolds, with no restart */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun rememberAppLayout(): AppLayout {
    val activity = LocalContext.current.activity() ?: error("Lumen's screens are drawn in an activity")
    val sizeClass = calculateWindowSizeClass(activity)
    val info by WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collectAsState(initial = null)
    // (the width follows the Fold folding and unfolding: the activity handles those changes itself, so this updates in place)
    val windowDp = LocalConfiguration.current.screenWidthDp
    val fold = info?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull()
    // (kept while nothing changed, so screens given it aren't redrawn for nothing)
    return androidx.compose.runtime.remember(sizeClass.widthSizeClass, fold?.state, fold?.orientation, windowDp) {
        AppLayout(sizeClass.widthSizeClass, fold, windowDp)
    }
}
