package com.notdxbi.dxbi.tv

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Lumen's own panel for the app's few questions of its own (an update's out, it needs permission to install, the
 * download failed), in place of Android's grey dialog with coloured buttons: a dark rounded card over the dimmed screen,
 * the title in white, the words softer, and pill buttons that turn white with dark words when the remote's on them,
 * as Lumen's own buttons do. The first button is the one the remote starts on.
 */
object UpdatePanel {

    class Choice(val label: String, val action: () -> Unit)

    fun show(activity: Activity, title: String, message: String, choices: List<Choice>, onDismiss: () -> Unit = {}): Dialog {
        val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, activity.resources.displayMetrics) }
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(32f).toInt(), dp(28f).toInt(), dp(32f).toInt(), dp(26f).toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(26f)
                setColor(Color.parseColor("#1C1C1E"))
                setStroke(dp(1f).toInt(), Color.argb(46, 255, 255, 255))
            }
        }
        card.addView(TextView(activity).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        })
        card.addView(TextView(activity).apply {
            text = message
            setTextColor(Color.argb(184, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setLineSpacing(0f, 1.2f)
            setPadding(0, dp(10f).toInt(), 0, dp(24f).toInt())
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // (room for the chosen pill to grow without being cut off at its edges)
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            clipChildren = false
            clipToPadding = false
            setPadding(dp(4f).toInt(), dp(4f).toInt(), dp(4f).toInt(), dp(4f).toInt())
        }
        var first: View? = null
        choices.forEachIndexed { i, c ->
            val button = pill(activity, c.label, dp) {
                dialog.dismiss()
                c.action()
            }
            row.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48f).toInt()).apply {
                if (i > 0) marginStart = dp(12f).toInt()
            })
            if (i == 0) first = button
        }
        card.addView(row)

        dialog.setContentView(card)
        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0.65f)
            // (a TV's width or a phone's, less a margin)
            val screen = activity.resources.displayMetrics.widthPixels
            w.setLayout(minOf(dp(600f).toInt(), screen - dp(32f).toInt()), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setOnDismissListener { onDismiss() }
        dialog.show()
        first?.requestFocus()
        return dialog
    }

    /** A pill: frosted at rest, white with dark words when chosen, a touch larger */
    private fun pill(activity: Activity, label: String, dp: (Float) -> Float, onClick: () -> Unit): TextView {
        fun shape(color: Int) = GradientDrawable().apply { cornerRadius = dp(24f); setColor(color) }
        return TextView(activity).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(24f).toInt(), 0, dp(24f).toInt(), 0)
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), shape(Color.WHITE))
                addState(intArrayOf(android.R.attr.state_pressed), shape(Color.WHITE))
                addState(intArrayOf(), shape(Color.argb(41, 255, 255, 255)))
            }
            setTextColor(Color.WHITE)
            setOnFocusChangeListener { v, focused ->
                (v as TextView).setTextColor(if (focused) Color.BLACK else Color.WHITE)
                v.animate().scaleX(if (focused) 1.06f else 1f).scaleY(if (focused) 1.06f else 1f).setDuration(150).start()
            }
            setOnClickListener { onClick() }
        }
    }
}
