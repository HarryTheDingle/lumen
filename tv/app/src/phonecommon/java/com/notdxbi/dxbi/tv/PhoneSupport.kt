package com.notdxbi.dxbi.tv

import android.app.PendingIntent
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.widget.Toast
import java.io.File

/**
 * What both phone apps' `Phone` objects (src/mobile, src/lumenphone) do the same way, kept in one place: whether this
 * is a phone, and installing a downloaded update. (Each `Phone` keeps its own screens and its own stream targets.)
 */
object PhoneSupport {

    /** A phone or tablet rather than a TV */
    fun isPhone(context: Context): Boolean {
        val ui = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        return ui.currentModeType != Configuration.UI_MODE_TYPE_TELEVISION &&
            !context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    /**
     * Installs a downloaded update through Android's package installer: from Android 12, once the app
     * installed the copy it's replacing (every update after the first), it installs without asking,
     * and the app closes to restart as the new version. Otherwise Android asks first (UpdateReceiver
     * brings that up). False: older Androids, which get the installer screen instead.
     */
    fun install(activity: MainActivity, apk: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return try {
            val installer = activity.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(activity.packageName)
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("dxbi.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val done = PendingIntent.getBroadcast(
                    activity, id, Intent(activity, UpdateReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                activity.runOnUiThread { Toast.makeText(activity, "Installing the update: ${BuildConfig.APP_NAME} will close and open as the new version", Toast.LENGTH_LONG).show() }
                session.commit(done.intentSender)
            }
            true
        } catch (_: Exception) {
            false // the installer screen instead
        }
    }
}

/** Hears how an update's install went: brings up Android's "Install?" when it wants to ask, or says why it failed */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // (the new version is starting)
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit // they said no
            else -> Toast.makeText(context, "The update didn't install: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "try again later"}", Toast.LENGTH_LONG).show()
        }
    }
}
