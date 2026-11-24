package com.notdxbi.dxbi.tv.lumen

import android.app.Activity
import android.widget.Toast
import com.notdxbi.dxbi.tv.LogKeeper
import com.notdxbi.dxbi.tv.UpdatePanel
import com.notdxbi.dxbi.tv.phone.Engine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sending Harry the last 30 minutes of the log: from Settings → About, or offered when Lumos starts after a crash. It
 * answers with a short reference to tell him (he reads it with sync/logs.mjs). Runs in its own scope, so closing the
 * screen it was started from doesn't stop it.
 */
object ProblemReport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var sending = false

    fun send(activity: Activity, engine: Engine, crash: Boolean = false) {
        if (sending) return
        sending = true
        Toast.makeText(activity, "Sending the report…", Toast.LENGTH_SHORT).show()
        scope.launch {
            val result = runCatching { engine.sendReport(activity.applicationContext, if (crash) "after a crash" else null, crash) }
            sending = false
            if (crash) LogKeeper.forgetCrash(activity)
            if (activity.isFinishing || activity.isDestroyed) return@launch
            result.onSuccess { id ->
                UpdatePanel.show(activity, "Report sent", "Tell Harry the reference ${id.chunked(3).joinToString(" ")} and what you were doing.\n\nIt has the last 30 minutes of what ${com.notdxbi.dxbi.tv.BuildConfig.APP_NAME} did, with passwords and keys taken out.", listOf(UpdatePanel.Choice("OK") {}))
            }.onFailure { e ->
                UpdatePanel.show(activity, "The report didn't send", (e.message?.takeIf { it.length < 160 } ?: "Check your connection.") + "\n\nTry again in a moment.", listOf(UpdatePanel.Choice("OK") {}))
            }
        }
    }

    /** After a crash: offer to send it, once */
    fun offerAfterCrash(activity: Activity, engine: Engine) {
        if (LogKeeper.crashed(activity) == null) return
        scope.launch {
            val name = com.notdxbi.dxbi.tv.BuildConfig.APP_NAME
            UpdatePanel.show(
                activity, "$name closed unexpectedly",
                "Send Harry a report of what happened? It has the last 30 minutes of what $name did, with passwords and keys taken out.",
                listOf(UpdatePanel.Choice("Send Report") { send(activity, engine, crash = true) }, UpdatePanel.Choice("Not Now") { LogKeeper.forgetCrash(activity) }),
            )
        }
    }
}
