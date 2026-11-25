package com.notdxbi.dxbi.tv

import android.animation.LayoutTransition
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A player button as Live TV's: a round icon on glass that turns white and opens out into a pill with its name beside the
 * icon while the remote is on it ("Subtitles", "Audio"), the buttons beside it moving along to make room, and back to a
 * circle when the remote moves on.
 */
class GlassPill(context: Context, icon: Drawable?, label: String) : LinearLayout(context) {
    private val image = ImageView(context)
    private val name = TextView(context)
    private val glass = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(20).toFloat()
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        contentDescription = label
        minimumWidth = dp(40)
        setPadding(dp(10), 0, dp(10), 0)
        background = glass
        image.setImageDrawable(icon?.mutate())
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        addView(image, LayoutParams(dp(20), dp(20)))
        name.text = label
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        name.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        name.maxLines = 1
        name.visibility = View.GONE
        addView(name, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8); marginEnd = dp(4) })
        // (the name eases in as the pill widens, and the buttons beside it slide along)
        layoutTransition = LayoutTransition().apply { enableTransitionType(LayoutTransition.CHANGING); setDuration(220L) }
        paint(false)
        setOnFocusChangeListener { _, has -> paint(has); onFocus?.invoke(has) }
    }

    /** Also told when the remote arrives or leaves (the controls use it to remember where it was) */
    var onFocus: ((Boolean) -> Unit)? = null

    fun setIcon(res: Int) = image.setImageResource(res).also { paint(isFocused) }

    /** Its name (Play or Pause, as what OK would do) */
    fun setLabel(text: String) { if (name.text != text) { name.text = text; contentDescription = text } }

    private fun paint(focused: Boolean) {
        glass.setColor(if (focused) Color.rgb(0xE8, 0xE8, 0xE8) else Color.argb(41, 255, 255, 255))
        glass.setStroke(if (focused) 0 else dp(1), Color.argb(70, 255, 255, 255))
        val ink = if (focused) Color.BLACK else Color.WHITE
        image.setColorFilter(ink)
        name.setTextColor(ink)
        name.visibility = if (focused) View.VISIBLE else View.GONE
        animate().scaleX(if (focused) 1.04f else 1f).scaleY(if (focused) 1.04f else 1f)
            .setInterpolator(PathInterpolator(0.25f, 0.1f, 0.25f, 1f)).setDuration(220L).start()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** The Live TV look on the TV's Lumen player (a phone or tablet keeps its own) */
        fun wanted(context: Context): Boolean = BuildConfig.APP_NAME == "Lumen" &&
            (context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager)?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION

        /** Swaps the layout's square buttons [ids] for glass pills, in their places, keeping ids, names and what's shown */
        fun swapAll(root: View, ids: List<Int>) {
            for (id in ids) {
                val old = root.findViewById<View>(id) as? ImageButton ?: continue
                val parent = old.parent as? ViewGroup ?: continue
                val at = parent.indexOfChild(old)
                val pill = GlassPill(old.context, old.drawable, old.contentDescription?.toString().orEmpty())
                pill.id = id
                pill.visibility = old.visibility
                val h = (40 * old.resources.displayMetrics.density).toInt()
                val gap = (10 * old.resources.displayMetrics.density).toInt()
                val lp: ViewGroup.LayoutParams = when (val o = old.layoutParams) {
                    is FrameLayout.LayoutParams -> FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, h).apply {
                        gravity = o.gravity; leftMargin = o.leftMargin; topMargin = o.topMargin; marginStart = o.marginStart
                    }
                    else -> LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, h).apply { marginEnd = gap }
                }
                parent.removeViewAt(at)
                parent.addView(pill, at, lp)
                // (the chosen one swells a little past its row: the rows round it mustn't cut its edges off)
                var up: ViewGroup? = parent
                while (up != null && up.id != R.id.controls) {
                    up.clipChildren = false
                    up.clipToPadding = false
                    up = up.parent as? ViewGroup
                }
                // (the rows slide their buttons along as one opens out)
                if (parent is LinearLayout && parent.layoutTransition == null) {
                    parent.layoutTransition = LayoutTransition().apply { enableTransitionType(LayoutTransition.CHANGING); setDuration(220L) }
                }
            }
        }
    }
}
