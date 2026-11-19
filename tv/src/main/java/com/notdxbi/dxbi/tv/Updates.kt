package com.notdxbi.dxbi.tv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Keeps the app current on the TV: a new release is offered once, and downloaded and installed when wanted.
 */
object Updates {
    private const val REPO = BuildConfig.UPDATE_REPO
    // Lumos: no GitHub repo; the sync server has the latest release ({tag_name, body, assets, history}, as GitHub's API
    // answers, history being the releases before it) next to its APK
    private const val FEED = BuildConfig.UPDATE_FEED
    private const val RELEASES_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val LATEST_PAGE = "https://github.com/$REPO/releases/latest"
    private val ENABLED = BuildConfig.SELF_UPDATE
    private const val RECHECK_MS = 6 * 60 * 60 * 1000L
    // each app updates to its own: dxbi-tv.apk or dxbi-mobile.apk
    private const val APK_ASSET = BuildConfig.APK_NAME

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    /** A TV app is rarely closed: it comes back from Home for days, so look again when it does */
    fun onResume(activity: MainActivity) {
        pendingInstall?.let { retry ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.packageManager.canRequestPackageInstalls()) {
                pendingInstall = null
                retry(activity)
            }
        }
        if (lastCheck != 0L && System.currentTimeMillis() - lastCheck > RECHECK_MS) {
            checkApp(activity)
        }
    }

    @Volatile private var lastCheck = 0L
    @Volatile private var offering = false
    private var pendingInstall: ((MainActivity) -> Unit)? = null

    private class Latest(val version: String, val url: String, val size: Long)

    private fun fromApi(): Latest? {
        val release = JSONObject(get(if (FEED.isNotEmpty()) FEED else RELEASES_URL) ?: return null)
        val assets = release.optJSONArray("assets") ?: return null
        // (only once GitHub has the whole file: an asset still uploading is listed too)
        val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name") == APK_ASSET && it.optString("state", "uploaded") == "uploaded" } ?: return null
        return Latest(release.optString("tag_name").removePrefix("v"), asset.optString("browser_download_url"), asset.optLong("size", -1))
    }

    /** GitHub's API allows few checks per home connection. The releases page has no such limit: it redirects to the latest tag */
    private fun fromPage(): Latest? {
        if (FEED.isNotEmpty()) return null
        val tag = http.newCall(Request.Builder().url(LATEST_PAGE).head().build()).execute().use { it.request.url.pathSegments.lastOrNull() }
        val version = tag?.takeIf { it.startsWith("v") }?.removePrefix("v") ?: return null
        // That release's own copy (just after a release, "latest" can differ from page to file), once attached
        val url = "https://github.com/$REPO/releases/download/$tag/$APK_ASSET"
        val found = http.newCall(Request.Builder().url(url).head().build()).execute().use { it.isSuccessful }
        return if (found) Latest(version, url, -1) else null
    }

    /** What changed between two versions, for the update panel: new things, and fixes */
    class Notes(val added: List<String>, val fixed: List<String>) {
        val isEmpty get() = added.isEmpty() && fixed.isEmpty()
    }

    /**
     * The notes of every release after [current] up to [latest] (a version skipped before is still told), newest first,
     * from the releases' own text: a "- " line each. Only what's about this app: a line led by "Lumen iPad:" or
     * "Lumen TV:" belongs to that one, and the lines of a "Fixes:" list are told as fixes. null when GitHub can't say
     */
    private fun releaseNotes(current: String, latest: String): Notes? {
        val list = if (FEED.isNotEmpty()) JSONObject(get(FEED) ?: return null).let { latest ->
            org.json.JSONArray().put(latest).also { a -> latest.optJSONArray("history")?.let { h -> for (i in 0 until h.length()) a.put(h.get(i)) } }
        } else org.json.JSONArray(get("https://api.github.com/repos/$REPO/releases?per_page=10") ?: return null)
        val added = ArrayList<String>()
        val fixed = ArrayList<String>()
        for (i in 0 until list.length()) {
            val release = list.getJSONObject(i)
            val v = release.optString("tag_name").removePrefix("v")
            if (release.optBoolean("draft") || !isNewer(v, current) || isNewer(v, latest)) continue
            for (raw in release.optString("body").lines()) {
                val line = raw.trim().removePrefix("- ").trim()
                if (!raw.trim().startsWith("- ") || line.isEmpty()) continue
                val colon = line.indexOf(':')
                val prefix = if (colon in 1..60) line.substring(0, colon) else ""
                val about = prefix.lowercase()
                val device = about.contains("tv") || about.contains("phone") || about.contains("ipad") || about.contains("iphone") || about.contains("android")
                if (device && !about.contains("tv")) continue
                val text = (if (device || about == "fixes") line.substring(colon + 1) else line).trim()
                    .replaceFirstChar { it.uppercase() }
                when {
                    about == "fixes" || about.endsWith(", fixes") -> splitFixes(text).forEach { fixed += it }
                    Regex("""\bfix(es|ed)?\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> fixed += text
                    else -> added += text
                }
            }
        }
        return Notes(added.distinct(), fixed.distinct())
    }

    /** "a, b (c, d), e." as a, b (c, d), e: split at the commas outside brackets */
    private fun splitFixes(text: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        val cur = StringBuilder()
        for (c in text.trimEnd('.')) {
            when {
                c == '(' -> { depth++; cur.append(c) }
                c == ')' -> { depth = maxOf(0, depth - 1); cur.append(c) }
                c == ',' && depth == 0 -> { out += cur.toString().trim(); cur.clear() }
                else -> cur.append(c)
            }
        }
        out += cur.toString().trim()
        return out.filter { it.isNotEmpty() }.map { it.replaceFirstChar { c -> c.uppercase() } }
    }

    fun checkApp(activity: MainActivity) = thread(name = "dxbi-app-update") {
        if (offering || !ENABLED) return@thread
        lastCheck = System.currentTimeMillis()
        try {
            val found = try { fromApi() } catch (_: Exception) { null } ?: fromPage() ?: return@thread
            val latest = found.version
            val current = BuildConfigVersion.name(activity)
            if (!isNewer(latest, current)) return@thread
            val prefs = activity.getSharedPreferences("updates", Context.MODE_PRIVATE)
            if (prefs.getString("skipped", null) == latest) return@thread
            val notes = try { releaseNotes(current, latest) } catch (_: Exception) { null }
            activity.runOnUiThread {
                if (activity.isFinishing || offering) return@runOnUiThread
                offering = true
                // (Lumen's own panel, not Android's grey dialog; the remote starts on Update, so OK never means Skip)
                UpdatePanel.show(
                    activity,
                    "${BuildConfig.APP_NAME} $latest is ready",
                    "You have $current. It downloads in the background, then Android asks you to install it.",
                    listOf(
                        UpdatePanel.Choice("Update") { download(activity, found.url, found.size, latest) },
                        UpdatePanel.Choice("Later") {},
                        UpdatePanel.Choice("Skip This Version") { prefs.edit().putString("skipped", latest).apply() },
                    ),
                    onDismiss = { offering = false },
                    notes = notes?.takeIf { !it.isEmpty },
                )
            }
        } catch (_: Exception) { /* offline: try again next launch */ }
    }

    private val downloading = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun download(activity: MainActivity, url: String, size: Long, version: String) {
        // Android only installs apps from dx.bi once it's allowed to ("Install unknown apps")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            // It carries on by itself when they come back from that screen having allowed it. First, what to do there:
            // some TVs (the Mi Box) open the list of every app, not Lumen's own switch, with another app chosen
            val name = BuildConfig.APP_NAME
            UpdatePanel.show(
                activity,
                "One thing first",
                "To update itself, $name needs permission to install apps. On the next screen, move down to $name, switch it on, then press Back. You only need to do this once.",
                listOf(
                    UpdatePanel.Choice("Continue") {
                        pendingInstall = { download(it, url, size, version) }
                        try {
                            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                        } catch (_: Exception) {
                            // Some TVs (Fire OS among them) have no such screen to open directly
                            UpdatePanel.show(
                                activity, "Allow $name to install apps",
                                "This TV has no shortcut to the setting. In its settings, find Install unknown apps (on Fire TV: My Fire TV → Developer options), switch on $name, then come back and choose Update again.",
                                listOf(UpdatePanel.Choice("OK") {}),
                            )
                        }
                    },
                    UpdatePanel.Choice("Not Now") {},
                ),
            )
            return
        }
        // One download at a time: pressing Update twice had two downloads writing the same file, and
        // Android then said "There was a problem parsing the package"
        if (!downloading.compareAndSet(false, true)) {
            Toast.makeText(activity, "The update is already downloading…", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(activity, "Downloading the update…", Toast.LENGTH_SHORT).show()
        thread(name = "dxbi-apk-download") {
            try {
                val dir = File(activity.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
                val apk = File(dir, "dxbi-tv-$version.apk")
                // A download cut short (slow Wi-Fi, the TV sleeping) is tried again before giving up
                var problem: String? = null
                for (attempt in 1..3) {
                    problem = fetchApk(url, apk, size, version, activity)
                    if (problem == null) break
                }
                if (problem != null) throw IllegalStateException(problem)
                install(activity, apk)
            } catch (e: Exception) {
                activity.runOnUiThread {
                    if (activity.isFinishing) return@runOnUiThread
                    UpdatePanel.show(
                        activity, "The update didn't download",
                        "${e.message?.replaceFirstChar { it.uppercase() }}.\n\n" + if (FEED.isNotEmpty()) "Try again later." else "Try again later, or install it with the Downloader app from github.com/$REPO/releases/latest/download/$APK_ASSET",
                        listOf(UpdatePanel.Choice("OK") {}),
                    )
                }
            } finally {
                downloading.set(false)
            }
        }
    }

    /** Hands the downloaded update to Android's installer */
    private fun install(activity: MainActivity, apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
        activity.runOnUiThread {
            activity.startActivity(Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /**
     * Downloads the APK and makes sure Android will be able to install it: every byte there, and a
     * readable dx.bi package of the version expected. Returns null when it's good, else what's wrong.
     */
    private fun fetchApk(url: String, apk: File, size: Long, version: String, activity: MainActivity): String? {
        val partial = File(apk.parentFile, apk.name + ".part")
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return "the server answered ${response.code}"
                partial.outputStream().use { out -> response.body!!.byteStream().copyTo(out) }
            }
        } catch (e: Exception) {
            partial.delete()
            return "the download was interrupted (${e.message ?: e.javaClass.simpleName})"
        }
        if (size > 0 && partial.length() != size) {
            val got = partial.length()
            partial.delete()
            return "the download was incomplete ($got of $size bytes)"
        }
        @Suppress("DEPRECATION")
        val info = activity.packageManager.getPackageArchiveInfo(partial.path, 0)
        if (info == null || info.packageName != activity.packageName || info.versionName != version) {
            partial.delete()
            return "the downloaded file isn't a ${BuildConfig.APP_NAME} $version app"
        }
        apk.delete()
        if (!partial.renameTo(apk)) return "the update couldn't be saved"
        return null
    }

    private fun get(url: String): String? =
        http.newCall(Request.Builder().url(url).header("User-Agent", "dxbi-tv").build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }

    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split(".").map { it.toIntOrNull() ?: 0 }
        val b = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}

