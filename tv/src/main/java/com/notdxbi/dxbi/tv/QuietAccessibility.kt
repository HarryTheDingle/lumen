package com.notdxbi.dxbi.tv

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityManager

/**
 * Compose builds the whole screen's accessibility tree again on every frame (and works out scroll events from it)
 * whenever ANY accessibility service is on. On a Mi Box with a remote-button app (flar2's Home Button, which is one so it
 * can see key presses) that was a sixth of the main thread while rows moved, for a tree nobody reads.
 *
 * So when nothing that reads the screen is on (no screen reader speaking, no braille, no touch exploration), Lumen tells
 * its Compose view there's no service to serve; the moment one is turned on (TalkBack), Compose's own listener puts the
 * list back and it all works as before. Compose keeps this in a private list (`enabledServices`), so this reaches it by
 * name (kept by proguard-rules.pro); if a Compose update renames it, this does nothing and nothing breaks.
 */
object QuietAccessibility {
    private const val TAG = "lumen-a11y"
    private const val COMPOSE_VIEW = "androidx.compose.ui.platform.AndroidComposeView"

    fun watch(root: View) {
        val am = root.context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return
        val apply = { root.post { quieten(root, am) } }
        // (the Compose view arrives a moment after the screen's set up: once it's there)
        root.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (find(root) == null) return
                root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                apply()
            }
        })
        // (Compose fills its list again whenever a service goes on or off: after it has, again, if still nothing reads)
        am.addAccessibilityStateChangeListener { apply() }
        am.addTouchExplorationStateChangeListener { apply() }
        if (Build.VERSION.SDK_INT >= 33) am.addAccessibilityServicesStateChangeListener { apply() }
    }

    /** Whether something on is reading the screen (and so needs the tree) */
    private fun readerOn(am: AccessibilityManager): Boolean {
        if (!am.isEnabled) return false
        if (am.isTouchExplorationEnabled) return true
        val readers = AccessibilityServiceInfo.FEEDBACK_SPOKEN or AccessibilityServiceInfo.FEEDBACK_BRAILLE
        return am.getEnabledAccessibilityServiceList(readers).isNotEmpty()
    }

    private fun quieten(root: View, am: AccessibilityManager) {
        if (readerOn(am)) return
        val view = find(root) ?: return
        runCatching {
            val delegate = view.javaClass.getDeclaredField("composeAccessibilityDelegate").apply { isAccessible = true }.get(view) ?: return
            val services = delegate.javaClass.getDeclaredField("enabledServices").apply { isAccessible = true }
            if ((services.get(delegate) as? List<*>)?.isEmpty() == true) return
            services.set(delegate, emptyList<AccessibilityServiceInfo>())
            Log.i(TAG, "no screen reader on: the accessibility tree isn't built each frame")
        }.onFailure { Log.w(TAG, "couldn't quieten accessibility: ${it.javaClass.simpleName}") }
    }

    private fun find(v: View): View? {
        if (v.javaClass.name == COMPOSE_VIEW) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
        return null
    }
}
