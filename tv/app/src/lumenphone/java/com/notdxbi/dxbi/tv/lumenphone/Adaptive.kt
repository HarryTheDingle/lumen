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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker

/**
 * How much room the app has, which is all its screens ask: never what device it's on. A phone (and the Fold
 * folded, its outer screen) is [compact]; the Fold opened out flat (about 8 inches, nearly square), a tablet or
 * a window resized wide is not, and gets the larger layout rather than a stretched phone one.
 */
@Immutable
class AppLayout(private val width: WindowWidthSizeClass, val fold: FoldingFeature?) {
    val compact get() = width == WindowWidthSizeClass.Compact

    /** Tabs down the side rather than along the bottom, as the iPad's sidebar */
    val sidebar get() = !compact

    val gutter: Dp get() = if (compact) Dx.Gutter else Dx.Gutter + 12.dp
    val poster: Dp get() = if (compact) Dx.PosterCompact else Dx.PosterWide

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
    return AppLayout(sizeClass.widthSizeClass, info?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull())
}
