package com.notdxbi.dxbi.tv.lumen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize

/**
 * A card's glass edge: a fine white rim, bright at the top and fading down, with a brighter spot where one light above
 * the middle of the screen catches it. Each card works out where it is against that light, so the spot slides round the
 * rim as rows and the page move (cards on the left catch it top right, on the right top left). The chosen card's rim is
 * brighter, and its spot leans the way the remote moved, settling back softly.
 *
 * Everything that moves is read while drawing, so scrolling only redraws the rim: nothing is laid out or built again.
 */
class GlassState {
    /** The card's middle and the screen's size, in px (Unspecified until it's been placed) */
    var centre by mutableStateOf(Offset.Unspecified)
    var screen by mutableStateOf(Size.Zero)
    val drift = Animatable(Offset.Zero, Offset.VectorConverter)

    /** Chosen: lean the spot the way the remote came from the last chosen card, then settle back */
    suspend fun arrived() {
        val from = lastChosen
        val here = centre
        if (here.isSpecified) lastChosen = here
        if (from == null || !here.isSpecified) return
        val d = here - from
        val len = d.getDistance()
        if (len < 1f) return
        drift.animateTo(d / len * 0.55f, spring(dampingRatio = 1f, stiffness = 400f))
        drift.animateTo(Offset.Zero, spring(dampingRatio = 1f, stiffness = 40f))
    }

    companion object {
        /** Where the last chosen card was: which way the remote moved */
        private var lastChosen: Offset? = null
    }
}

private fun white(a: Float) = Color.White.copy(alpha = a.coerceIn(0f, 1f))

/** [focus]: how far the card has lifted (0 resting, 1 chosen), read while drawing */
fun Modifier.glassEdge(shape: Shape, state: GlassState, focus: () -> Float): Modifier = this
    .onGloballyPositioned { c ->
        val p = c.positionInRoot()
        state.centre = Offset(p.x + c.size.width / 2f, p.y + c.size.height / 2f)
        state.screen = c.findRootCoordinates().size.toSize()
    }
    .drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val w = 1.dp.toPx()
        val rim = Stroke(w)
        val spotRim = Stroke(w * 1.5f)
        onDrawWithContent {
            drawContent()
            val f = focus().coerceIn(0f, 1f)
            // the rim: lighter at the top, almost gone at the foot
            drawOutline(
                outline,
                Brush.verticalGradient(0f to white(0.22f + 0.18f * f), 0.45f to white(0.07f + 0.08f * f), 1f to white(0.03f + 0.04f * f), endY = size.height),
                style = rim,
            )
            // a faint sheen just inside the top, as on a pane of glass
            drawOutline(outline, Brush.verticalGradient(0f to white(0.05f + 0.04f * f), 0.2f to Color.Transparent, endY = size.height))
            // where the light catches it
            val screen = state.screen
            val c = state.centre
            if (screen.width > 0f && c.isSpecified) {
                val light = Offset(screen.width / 2f, -screen.height * 0.35f)
                var d = light - c
                d /= d.getDistance().coerceAtLeast(1f)
                d += state.drift.value
                d /= d.getDistance().coerceAtLeast(0.001f)
                val spot = Offset(size.width / 2f * (1f + d.x), size.height / 2f * (1f + d.y))
                drawOutline(
                    outline,
                    Brush.radialGradient(listOf(white(0.45f + 0.35f * f), Color.Transparent), center = spot, radius = size.width * 0.7f),
                    style = spotRim,
                )
            }
        }
    }
