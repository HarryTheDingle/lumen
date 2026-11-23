package com.notdxbi.dxbi.tv

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread

/**
 * The app's own log, kept on the TV so a problem can be sent to Harry afterwards (Settings → Send a Problem Report, or
 * the offer after a crash). Android lets an app read its own lines only, so this is everything Lumos itself said: a
 * logcat reader runs for as long as the app does, writing to two files of 2 MB that take turns. Starting again, it
 * picks up from the last line it kept, so the lines of a crash (written as the last process died) come in too.
 *
 * What's sent is the last 30 minutes with secrets taken out first ([clean]): links lose everything after their host
 * (addon links carry Easynews's sign-in and TorBox's key), keys and tokens become "…", as do long codes.
 */
object LogKeeper {
    private const val MAX = 2L * 1024 * 1024
    private val stamp = Regex("""^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})""")
    private val format get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var started = false

    private fun dir(context: Context) = File(context.filesDir, "logs").apply { mkdirs() }
    private fun current(context: Context) = File(dir(context), "current.log")
    private fun previous(context: Context) = File(dir(context), "previous.log")
    private fun crashMark(context: Context) = File(dir(context), "crashed")

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        // A crash: its stack goes into the log straight away (the reader may already be gone) and is remembered, so the
        // next start offers to send it
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                current(app).appendText("${format.format(Date())} CRASH in ${t.name}: ${Log.getStackTraceString(e)}\n")
                crashMark(app).writeText(format.format(Date()))
            }
            before?.uncaughtException(t, e)
        }
        thread(name = "lumos-logs", isDaemon = true) {
            runCatching { read(app) }.onFailure { Log.w("lumos-logs", "log keeping stopped: ${it.javaClass.simpleName}") }
        }
    }

    private fun read(app: Context) {
        val from = lastStamp(current(app)) ?: format.format(Date(System.currentTimeMillis() - 30 * 60_000L))
        val p = ProcessBuilder("logcat", "-v", "threadtime", "-v", "year", "-T", from).redirectErrorStream(true).start()
        var out = java.io.FileOutputStream(current(app), true)
        var size = current(app).length()
        p.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                // (the line the last run ended on comes again first: -T includes it)
                if (line.startsWith("--------- beginning")) continue
                val bytes = (line + "\n").toByteArray()
                out.write(bytes)
                size += bytes.size
                if (size > MAX) {
                    out.close()
                    previous(app).delete()
                    current(app).renameTo(previous(app))
                    out = java.io.FileOutputStream(current(app), true)
                    size = 0
                }
            }
        }
    }

    private fun lastStamp(f: File): String? {
        if (!f.exists() || f.length() == 0L) return null
        val tail = java.io.RandomAccessFile(f, "r").use { r ->
            val n = minOf(r.length(), 8192L)
            r.seek(r.length() - n)
            ByteArray(n.toInt()).also { r.readFully(it) }.toString(Charsets.UTF_8)
        }
        return tail.lines().asReversed().firstNotNullOfOrNull { stamp.find(it)?.groupValues?.get(1) }
    }

    /** When the app last crashed (and that's not been sent or set aside yet), else null */
    fun crashed(context: Context): String? = crashMark(context).takeIf { it.exists() }?.readText()
    fun forgetCrash(context: Context) { crashMark(context).delete() }

    /**
     * The last [minutes] of the log, secrets out, with a heading saying what it's from; gzipped then base64 for the sync
     * server (cut from the oldest end to stay under its limit)
     */
    fun report(context: Context, minutes: Int = 30): String {
        val cutoff = format.format(Date(System.currentTimeMillis() - minutes * 60_000L))
        val lines = (previous(context).takeIf { it.exists() }?.readLines().orEmpty() + current(context).takeIf { it.exists() }?.readLines().orEmpty())
        // (a line without its own time, the rest of a stack trace, goes with the one before it)
        var keep = false
        val kept = ArrayList<String>()
        for (l in lines) {
            val t = stamp.find(l)?.groupValues?.get(1)
            if (t != null) keep = t >= cutoff
            if (keep) kept += clean(l)
        }
        val head = "${BuildConfig.APP_NAME} ${BuildConfigVersion.name(context)} on ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}), " +
            "the last $minutes minutes to ${format.format(Date())}\n\n"
        var body: List<String> = kept
        while (true) {
            val gz = gzip(head + body.joinToString("\n"))
            if (gz.size < 1000 * 1024 || body.size < 100) return Base64.encodeToString(gz, Base64.NO_WRAP)
            body = body.drop(body.size / 4)
        }
    }

    private fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { b -> GZIPOutputStream(b).use { it.write(text.toByteArray()) } }.toByteArray()

    private val links = Regex("""\b([a-z][a-z0-9+.-]*://)([^/\s"'<>]+)[^\s"'<>]*""", RegexOption.IGNORE_CASE)
    private val named = Regex("""(?i)\b(auth_?key|token|api_?key|apikey|password|passwd|secret|torbox|authorization|bearer|cookie|session|username|user|mac|macAddress|portal|portalUrl|xtream|m3u)(["']?\s*[:=]\s*["']?|\s+)[^\s"',&}]+""")
    private val long = Regex("""\b[A-Za-z0-9_\-+/=]{32,}\b""")

    /** A line with its secrets taken out */
    fun clean(line: String): String = line
        // (a link keeps where it goes, not its path or query: an addon's path is its whole configuration, sign-ins and all;
        // and a user:password@ in front of the host goes too)
        .replace(links) { m -> m.groupValues[1] + m.groupValues[2].substringAfterLast('@') + "/…" }
        .replace(named) { m -> m.groupValues[1] + m.groupValues[2] + "…" }
        .replace(long, "…")
}
