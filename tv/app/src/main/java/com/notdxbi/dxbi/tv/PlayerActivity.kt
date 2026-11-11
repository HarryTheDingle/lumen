package com.notdxbi.dxbi.tv

import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The native player: plays the stream Stremio picked (MKV, HEVC, HDR and surround sound included,
 * as far as the TV's hardware can), with the addons' subtitles. Reports its position back so the
 * watch progress is kept.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var controls: Controls
    private var extras: PlayerHooks? = null
    // the subtitles' size (Stremio's setting), put back after the phone app's small window's bigger one
    private var subtitleSize = SubtitleView.DEFAULT_TEXT_SIZE_FRACTION
    private lateinit var zoom: PictureZoom
    private var party = false
    private val partyTick = object : Runnable {
        override fun run() {
            val exo = player ?: return
            val duration = exo.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            PlaybackBus.listener?.onPartyTick(exo.currentPosition, duration, !exo.playWhenReady, exo.playbackState == Player.STATE_BUFFERING, exo.playbackParameters.speed)
            handler.postDelayed(this, 500)
        }
    }
    private val hideToast = Runnable { seekFlash.visibility = View.GONE }

    /** The page's watch party: plays, pauses and seeks with everyone, and says what's happening */
    fun control(json: String) {
        val exo = player ?: return
        val c = JSONObject(json)
        when (c.optString("cmd")) {
            "play" -> exo.play()
            "pause" -> exo.pause()
            "seek" -> exo.seekTo(c.optLong("ms"))
            "rate" -> exo.setPlaybackSpeed(c.optDouble("rate", 1.0).toFloat())
            "close" -> finishWith()
            "party" -> {
                val on = c.optBoolean("on")
                if (on != party) {
                    party = on
                    handler.removeCallbacks(partyTick)
                    if (on) handler.post(partyTick)
                }
                controls.party(on, c.optString("code"), c.optJSONArray("people")?.let { list -> (0 until list.length()).map { list.optString(it) } } ?: emptyList())
            }
            "toast" -> {
                seekFlash.text = c.optString("text")
                seekFlash.visibility = View.VISIBLE
                handler.removeCallbacks(hideToast)
                handler.postDelayed(hideToast, 3500)
            }
        }
    }
    // (both go with the player when it's kept after Back: HeldPlayer)
    private var sound = SoundMode()
    private var subtitleDelay = SubtitleDelay()
    private val subtitleDelayMs get() = subtitleDelay.ms
    /** The screen's own listeners on the player, taken off it when it's kept */
    private val exoAnalytics = mutableListOf<androidx.media3.exoplayer.analytics.AnalyticsListener>()
    private val exoListeners = mutableListOf<Player.Listener>()
    /** This player was kept from before (Back, then Play again on the same stream) */
    private var reused = false
    private val refreshSubtitles = Runnable { player?.let { it.seekTo(it.currentPosition) } }
    // Up next: what's after this episode, how long the card counts down (0: never), the card's state
    private var nextTitle = ""
    private var nextId = ""
    private var nextArt = ""
    private var upNextSeconds = 0
    private var upNextLeft = 0
    private var upNextDismissed = false
    private lateinit var upNextCard: View
    private val upNextCount = object : Runnable {
        override fun run() {
            val exo = player ?: return
            if (upNextCard.visibility != View.VISIBLE) return
            if (exo.isPlaying) upNextLeft--
            findViewById<TextView>(R.id.up_next_count).text = upNextLeft.coerceAtLeast(0).toString()
            if (upNextLeft <= 0) finishWith(next = true) else handler.postDelayed(this, 1000)
        }
    }

    // (on the app's context, and the sound and delay its own: the player can outlive this screen, see HeldPlayer)
    private class Renderers(context: android.content.Context, private val sound: SoundMode, private val delay: SubtitleDelay) : DefaultRenderersFactory(context) {
        override fun buildTextRenderers(context: android.content.Context, output: TextOutput, outputLooper: Looper, extensionRendererMode: Int, out: ArrayList<Renderer>) {
            val own = ArrayList<Renderer>()
            super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, own)
            own.mapTo(out) { DelayedText(it) { delay.ms * 1000 } }
        }

        override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
            DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(false) // (Dialogue boost works on 16-bit sound)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(sound))
                .build()
    }

    private val phone get() = (getSystemService(UI_MODE_SERVICE) as android.app.UiModeManager).currentModeType != Configuration.UI_MODE_TYPE_TELEVISION &&
        !packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    /**
     * Lumen's player: white controls, the loading screen carried on, each show's sound and subtitles remembered,
     * a stream that is only a short error clip passed over. Lumen TV and Lumen for phones both; dx.bi's apps keep theirs.
     */
    private val lumenLook get() = Phone.ownsKeys() || BuildConfig.APP_NAME == "Lumen"
    private lateinit var clock: TextView
    private var showClock = true
    private lateinit var seekFlash: TextView
    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var closed = false
    private var lastError: String? = null
    /** The subtitles to start with have been chosen (the file's own, when it has them in the language) */
    private var subtitlesChosen = false
    // Lumen: its seeking (pause, choose, OK), and each show's sound and subtitles remembered
    private var scrubber: Scrubber? = null
    private var tracks: TrackMemory? = null
    private var audioChecked = false

    /**
     * The track to start on when the one chosen is a commentary (or isn't English while the file has English): the English
     * track that isn't a commentary, the file's own default first, then the most channels. Null when the one playing is fine.
     */
    private fun plainAudio(tracks: Tracks): Pair<Tracks.Group, Int>? {
        val all = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g to it } }
        fun english(f: androidx.media3.common.Format) = f.language?.let { l -> runCatching { java.util.Locale(l.substringBefore('-')).isO3Language }.getOrDefault(l).lowercase() } == "eng"
        val playing = all.firstOrNull { (g, i) -> g.isTrackSelected(i) } ?: return null
        val pf = playing.first.getTrackFormat(playing.second)
        val fine = !isCommentary(pf) && (english(pf) || pf.language == null || pf.language == "und" || all.none { (g, i) -> english(g.getTrackFormat(i)) && !isCommentary(g.getTrackFormat(i)) })
        if (fine) return null
        return all.filter { (g, i) -> g.getTrackFormat(i).let { english(it) && !isCommentary(it) } }
            .maxWithOrNull(compareBy({ (g, i) -> g.getTrackFormat(i).selectionFlags and C.SELECTION_FLAG_DEFAULT }, { (g, i) -> g.getTrackFormat(i).channelCount }))
            ?: all.filter { (g, i) -> !isCommentary(g.getTrackFormat(i)) }.firstOrNull()?.takeIf { playing.first.getTrackFormat(playing.second).let(::isCommentary) }
    }
    /** How fast the stream came, as the player measured it while loading (bits a second) */
    private var measuredBps = 0L
    private var pictureReported = false
    private var clipChecked = false
    // Lumen's Streams list: the sources a swap changes headers on, the subtitles to keep, which stream plays, and
    // where a stream chosen from the list goes back to if it won't play
    private var onlineSource: OkHttpDataSource.Factory? = null
    private var subtitleItems: List<MediaItem.SubtitleConfiguration> = emptyList()
    private var currentUrl = ""
    private var playKey = ""
    private var playIndex = -1
    private var videoRoute: List<String> = emptyList()
    private class Return(val url: String, val headers: Map<String, String>, val key: String, val index: Int)
    private var returnTo: Return? = null
    private var currentHeaders: Map<String, String> = emptyMap()
    private var switchingAway = false
    private var lastText: List<String?> = emptyList()

    private val reportProgress = object : Runnable {
        override fun run() {
            report()
            handler.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }
    private val hideFlash = Runnable { seekFlash.visibility = View.GONE }

    // Skip intro / recap / credits: the episode's times (seconds; end -1 = to the end), whether dx.bi
    // skips by itself ("intro": intros and recaps, "all": credits too), and which were skipped already
    private class Segment(val type: String, val start: Double, val end: Double)
    private var segments: List<Segment> = emptyList()
    private var autoSkip = "off"
    private val autoSkipped = mutableSetOf<Segment>()
    private var showing: Segment? = null
    private lateinit var skipButton: TextView
    // A torrent played on the TV itself: how it's going, while the picture waits for it
    private var torrent = false
    private val torrentStatus = object : Runnable {
        override fun run() {
            val exo = player
            val waiting = exo == null || exo.playbackState == Player.STATE_BUFFERING || exo.playbackState == Player.STATE_IDLE
            val text = if (torrent && waiting) TorrentEngine.status() else null
            if (text != null && loading?.showing == true) {
                loading?.detail(text)
            } else if (text != null) {
                seekFlash.text = text
                seekFlash.visibility = View.VISIBLE
            } else if (seekFlash.text.contains("peer") || seekFlash.text.startsWith("Finding")) {
                seekFlash.visibility = View.GONE
            }
            handler.postDelayed(this, 700)
        }
    }
    private val updateClock = object : Runnable {
        override fun run() {
            val exo = player
            if (exo != null) {
                val format = android.text.format.DateFormat.getTimeFormat(this@PlayerActivity)
                val left = if (exo.duration > 0) ((exo.duration - exo.currentPosition) / exo.playbackParameters.speed).toLong() else 0L
                val now = System.currentTimeMillis()
                clock.text = format.format(now) + if (left > 0) " · Ends at ${format.format(now + left)}" else ""
            }
            handler.postDelayed(this, 1000)
        }
    }
    private val checkSkip = object : Runnable {
        override fun run() {
            updateSkip()
            updateUpNext()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Phones turn freely; TVs stay landscape
        if (phone) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        // (Lumen for phones keeps the screen on only while a video plays: its player view says so, see PhonePlayer)
        if (!(lumenLook && !Phone.ownsKeys())) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player_view)
        clock = findViewById(R.id.clock)
        seekFlash = findViewById(R.id.seek_flash)
        skipButton = findViewById(R.id.skip_button)
        skipButton.setOnClickListener { doSkip() }

        val stream = JSONObject(intent.getStringExtra(EXTRA_STREAM) ?: "{}")
        // dx.bi for phones: its controls, gestures, small window and downloads (none in dx.bi TV)
        extras = Phone.player(host, stream)
        segments = stream.optJSONArray("skips")?.let { list ->
            (0 until list.length()).mapNotNull { i ->
                val o = list.optJSONObject(i) ?: return@mapNotNull null
                val type = o.optString("type")
                if (type !in setOf("intro", "recap", "credits")) return@mapNotNull null
                Segment(type, o.optDouble("start", 0.0), if (o.isNull("end")) -1.0 else o.optDouble("end", -1.0))
            }
        } ?: emptyList()
        autoSkip = stream.optString("autoSkip", "off")
        showClock = stream.optBoolean("clock", true)
        torrent = stream.optBoolean("torrent", false)
        findViewById<TextView>(R.id.title).text = stream.optString("title")
        findViewById<TextView>(R.id.subtitle).apply {
            text = stream.optString("subtitle")
            visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        stream.optJSONObject("subtitleStyle")?.let { styleSubtitles(it) }

        val url = stream.optString("url")
        currentUrl = url
        // (the player's address says which stream, and which video of which title)
        val route = stream.optString("hash").substringBefore("?").split("/")
        playKey = StreamAlt.keyOf(stream.optString("hash"))
        if (route.size > 7 && route[1] == "player") videoRoute = listOf(route[5], route[6], route[7]).map { runCatching { Uri.decode(it) }.getOrDefault(it) }
        if (lumenLook) currentHeaders = stream.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { k -> h.optString(k) } } ?: emptyMap()
        if (url.isEmpty()) {
            finishWith(error = "no stream address")
            return
        }

        // Addons can ask for headers on their links (Stremio's proxyHeaders)
        val headers = mutableMapOf<String, String>()
        stream.optJSONObject("headers")?.let { h -> h.keys().forEach { k -> headers[k] = h.optString(k) } }
        val http = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            // a torrent's first bytes wait for its details and first pieces to come from peers
            .readTimeout(if (torrent) 150L else 30L, TimeUnit.SECONDS)
            .followRedirects(true)
            // (a debrid link that's been followed once goes straight to its file: RedirectMemory)
            .addInterceptor(RedirectMemory.interceptor)
            .build()
        // (an addon's own User-Agent replaces ours: Media3 would send both)
        val ownAgent = headers.keys.any { it.equals("User-Agent", ignoreCase = true) }
        val online = OkHttpDataSource.Factory(http)
            .setUserAgent(if (ownAgent) null else "dxbi-tv/${BuildConfigVersion.name(this)}")
            .setDefaultRequestProperties(headers)
        onlineSource = online
        val dataSource = extras?.dataSource(online) ?: online

        // The TV's own decoders first, falling back to software ones when a format isn't supported
        // A player kept after Back on this same stream: taken back, with all it had downloaded (HeldPlayer)
        val held = if (lumenLook && !torrent) HeldPlayer.take(url) else HeldPlayer.release().let { null }
        if (held != null) {
            sound = held.sound
            subtitleDelay = held.delay
            reused = true
        }
        val renderers = Renderers(applicationContext, sound, subtitleDelay)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        // After running dry it carries on once 15 seconds are in hand (5 when the connection is clearly
        // quicker than the stream needs: more than 3 times its bitrate); starting out stays quick
        val bandwidth = androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(this)
        var exoRef: ExoPlayer? = null
        // (the picture comes once 1.5 s of it is in hand, not Media3's 2.5: a 4K remux over Wi-Fi took a second more to
        // show its first picture for nothing, the line filling far faster than it plays; the rest are Media3's own)
        val loadControl = object : androidx.media3.exoplayer.DefaultLoadControl(
            androidx.media3.exoplayer.upstream.DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
            DEFAULT_MIN_BUFFER_MS, DEFAULT_MAX_BUFFER_MS, 1500, DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            DEFAULT_TARGET_BUFFER_BYTES, DEFAULT_PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS, DEFAULT_BACK_BUFFER_DURATION_MS, DEFAULT_RETAIN_BACK_BUFFER_FROM_KEYFRAME,
        ) {
            override fun shouldStartPlayback(
                timeline: androidx.media3.common.Timeline, mediaPeriodId: androidx.media3.exoplayer.source.MediaSource.MediaPeriodId,
                bufferedDurationUs: Long, playbackSpeed: Float, rebuffering: Boolean, targetLiveOffsetUs: Long,
            ): Boolean {
                if (!super.shouldStartPlayback(timeline, mediaPeriodId, bufferedDurationUs, playbackSpeed, rebuffering, targetLiveOffsetUs)) return false
                if (!rebuffering) return true
                val e = exoRef
                val need = ((e?.videoFormat?.bitrate ?: -1).coerceAtLeast(0) + (e?.audioFormat?.bitrate ?: -1).coerceAtLeast(0)).toLong()
                val fast = need > 0 && bandwidth.bitrateEstimate > need * 3
                return fast || bufferedDurationUs >= 15_000_000L * playbackSpeed
            }
        }
        val exo = held?.exo ?: ExoPlayer.Builder(applicationContext, renderers)
            .setLoadControl(loadControl)
            .setBandwidthMeter(bandwidth)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .build()
        exoRef = exo
        player = exo
        playerView.player = exo
        val lumen = lumenLook
        // (the remote's own parts: seeking by its keys, subtitles that always start off and in English only)
        val remote = Phone.ownsKeys()
        // (Lumen: white, as Apple TV's player)
        val accent = if (lumen) Color.WHITE else try { Color.parseColor(stream.optString("accent")) } catch (_: Exception) { Color.parseColor("#E50914") }
        zoom = PictureZoom(playerView)
        val next = stream.optJSONObject("next")
        nextTitle = next?.optString("title").orEmpty()
        nextId = next?.optString("id").orEmpty()
        nextArt = next?.optString("thumbnail").orEmpty()
        // (Lumen for phones has no Up next card, as the iPad's: its Next button is how the next episode starts)
        upNextSeconds = if (next == null || (lumenLook && !Phone.ownsKeys())) 0 else stream.optString("upNext", "10").toIntOrNull() ?: 0
        upNextCard = findViewById(R.id.up_next)
        controls = extras?.controls(exo, sound, zoom, accent, next != null, showClock)
            ?: PlayerControls(this, playerView, exo, sound, zoom, ::shiftSubtitles, accent, clock.takeIf { showClock }, next != null, ::seek, { PlaybackBus.listener?.onPartyLeave() }) { go -> finishWith(next = go) }
        PlaybackBus.player = this
        extras?.started(exo, controls)
        if (lumen) {
            // the spinner in white, and the title's picture behind it until the video's first picture
            playerView.findViewById<android.widget.ProgressBar>(androidx.media3.ui.R.id.exo_buffering)
                ?.indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            val root = findViewById<android.widget.FrameLayout>(android.R.id.content).getChildAt(0) as? android.widget.FrameLayout
            // (the loading screen carried on, when it started this; else the title's picture and a spinner)
            loading = root?.let { Phone.loading(this, it) }
            if (loading == null) cover(stream.optString("background"))
            // (over the app's own loading screen, at once: the same screen carries on, nothing slides)
            else {
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
            // seeking with pictures from the lightest stream of this video (the page's previewSource)
            val preview = stream.optJSONObject("preview")
            val previewUrl = preview?.optString("url")?.takeIf { it.startsWith("http") }
            val previewHeaders = mutableMapOf<String, String>()
            preview?.optJSONObject("headers")?.let { h -> h.keys().forEach { k -> previewHeaders[k] = h.optString(k) } }
            android.util.Log.i("dxbi-player", "seek pictures from ${if (previewUrl != null) "the lightest stream" else "the stream playing"}")
            // (a phone's seek bar shows its pictures itself, from the same lightest stream: see PhonePlayer)
            if (remote) scrubber = Scrubber(this, exo, findViewById(R.id.seek_line), previewUrl ?: url, if (previewUrl != null) previewHeaders else headers) { controls }
            tracks = TrackMemory(this, exo, stream.optString("videoId").substringBefore(':'))
        }
        // how fast the stream comes (not a torrent's: that's the TV's own server), for the network's memory
        exo.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            init { exoAnalytics += this }
            override fun onBandwidthEstimate(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) {
                if (!torrent && totalBytesLoaded > 4_000_000) measuredBps = bitrateEstimate
            }
        })
        fitControls()
        findViewById<View>(R.id.up_next_play).setOnClickListener { finishWith(next = true) }
        findViewById<View>(R.id.up_next_cancel).setOnClickListener {
            upNextDismissed = true
            hideUpNext()
        }

        // Subtitles start in Stremio's subtitle language (Settings → Player) when dx.bi's "Subtitles when
        // a video starts" is on: the page sends that language, and marks the subtitle file to start
        // with. Off (dx.bi's default, like Netflix): pick one from the subtitles button.
        // Files with several audio tracks (dual-audio releases) start in Stremio's audio language
        // (Settings → Player), when they have it
        val audioLanguage = stream.optString("audioLanguage").ifEmpty { null }
        // (Lumen: subtitles always start off, even a track the file marks to show; English only, in the
        // subtitles menu)
        // (Lumen for phones starts them as the iPad does: in the language the page sends, unless this show was last
        // watched with them off)
        val subtitleLanguage = if (remote || tracks?.subtitlesOff == true) null else stream.optString("subtitleLanguage").ifEmpty { null }
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, remote || tracks?.subtitlesOff == true)
            .setPreferredTextLanguage(subtitleLanguage)
            .setSelectUndeterminedTextLanguage(false)
            .apply { if (audioLanguage != null) setPreferredAudioLanguage(audioLanguage) }
            .build()

        val subtitles = mutableListOf<MediaItem.SubtitleConfiguration>()
        val sideloaded = mutableSetOf<String>()
        stream.optJSONArray("subtitles")?.let { list ->
            for (i in 0 until list.length()) {
                val s = list.optJSONObject(i) ?: continue
                val subUrl = s.optString("url").takeIf { it.isNotEmpty() } ?: continue
                if (remote && !isEnglish(s.optString("lang"))) continue
                val id = extras?.subtitleId(s.optString("source", "addon"), i) ?: "sub-${s.optString("source", "addon")}-$i"
                sideloaded += id
                subtitles += MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrl))
                    .setId(id)
                    .setMimeType(subtitleMime(subUrl))
                    .setLanguage(s.optString("lang").ifEmpty { null })
                    .setLabel(s.optString("label").ifEmpty { s.optString("lang") })
                    .setSelectionFlags(if (s.optBoolean("start") && !remote && tracks?.subtitlesOff != true) C.SELECTION_FLAG_DEFAULT else 0)
                    .build()
            }
        }

        subtitleItems = subtitles
        // (each step to the first picture in the log, timed from here: adb logcat -s lumen-play)
        val openedAt = android.os.SystemClock.elapsedRealtime()
        var firstShown = false
        fun step(what: String) { if (!firstShown) android.util.Log.i("lumen-play", "player +${android.os.SystemClock.elapsedRealtime() - openedAt} ms $what") }
        step("open, from ${stream.optLong("startMs", 0L) / 1000} s")
        exo.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            init { exoAnalytics += this }
            override fun onLoadCompleted(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, load: androidx.media3.exoplayer.source.LoadEventInfo, data: androidx.media3.exoplayer.source.MediaLoadData) {
                step("read ${load.dataSpec.position / 1_000_000} MB +${load.bytesLoaded / 1000} kB in ${load.loadDurationMs} ms (type ${data.dataType})")
            }
            override fun onLoadCanceled(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, load: androidx.media3.exoplayer.source.LoadEventInfo, data: androidx.media3.exoplayer.source.MediaLoadData) {
                step("read cancelled at ${load.dataSpec.position / 1_000_000} MB after ${load.bytesLoaded / 1000} kB, ${load.loadDurationMs} ms")
            }
            override fun onVideoDecoderInitialized(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                step("video decoder $decoderName ($initializationDurationMs ms)")
            }
            override fun onAudioDecoderInitialized(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                step("audio decoder $decoderName ($initializationDurationMs ms)")
            }
            override fun onPlaybackStateChanged(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, state: Int) {
                step("state ${when (state) { Player.STATE_BUFFERING -> "buffering"; Player.STATE_READY -> "ready"; Player.STATE_ENDED -> "ended"; else -> "idle" }}, ${exo.totalBufferedDuration} ms in hand")
            }
            override fun onRenderedFirstFrame(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                step("FIRST PICTURE")
                firstShown = true
            }
        })
        if (!reused) exo.setMediaItem(mediaItem(url), stream.optLong("startMs", 0L).coerceAtLeast(0L))
        exo.addListener(object : Player.Listener {
            init { exoListeners += this }
            override fun onPlaybackStateChanged(state: Int) {
                // (Lumen: a video of a few seconds, for a film or an episode, is an addon's error clip (ElfHosted's
                // "Couldn't fetch this release"): passed over like a stream that won't play, not counted as watched)
                if ((state == Player.STATE_READY || state == Player.STATE_ENDED) && lumenLook && !clipChecked && returnTo == null) {
                    val length = exo.duration
                    if (length != C.TIME_UNSET) {
                        clipChecked = true
                        if (length in 1..30_000L && stream.optLong("startMs", 0L) == 0L) {
                            lastError = "It's only ${length / 1000} seconds long: an error clip"
                            android.util.Log.w("dxbi-player", "stream is a short clip: $lastError file=${Uri.parse(currentUrl).lastPathSegment}")
                            if (loading?.showing == true) loading?.retrying(lastError.orEmpty())
                            finishWith(error = lastError)
                            return
                        }
                    }
                }
                if (state == Player.STATE_ENDED) finishWith(ended = true, next = upNextSeconds > 0 && !upNextDismissed)
            }

            // A subtitle switched on mid-line only shows from its next line: a seek to where we are
            // brings the current one up straight away
            override fun onRenderedFirstFrame() {
                // (Lumen: the picture's real width, once per stream (each stream is its own player), so the page learns a stream that plays lower than its name)
                if (!pictureReported && lumenLook && exo.videoSize.width > 0) {
                    pictureReported = true
                    Phone.realPicture(exo.videoSize.width)
                }
                uncover()
                loading?.ready()
                returnTo = null
            }

            override fun onTracksChanged(tracks: Tracks) {
                this@PlayerActivity.tracks?.changed(tracks)
                // (whatever the file started on, a commentary or a description track isn't the film: its English sound is)
                if (!audioChecked && tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }) {
                    audioChecked = true
                    plainAudio(tracks)?.let { (group, i) ->
                        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i)).build()
                    }
                }
                if (!subtitlesChosen && subtitleLanguage != null) subtitlesChosen = preferOwnSubtitles(tracks, subtitleLanguage, sideloaded)
                val text = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { g -> (0 until g.length).filter { g.isTrackSelected(it) }.map { i -> g.getTrackFormat(i).id } }
                if (text.isNotEmpty() && text != lastText) exo.seekTo(exo.currentPosition)
                lastText = text
            }

            override fun onPlayerError(error: PlaybackException) {
                // (a stream chosen from the Streams list that won't play: back to the one that was, where it was)
                returnTo?.let { back ->
                    returnTo = null
                    android.util.Log.w("dxbi-player", "chosen stream failed: ${describe(error)} (${error.errorCodeName}) file=${Uri.parse(currentUrl).lastPathSegment}")
                    putStream(back.url, back.headers, back.key, back.index, exo.currentPosition, null)
                    flashText("That stream didn't play")
                    if (back.index >= 0) Phone.pageCall("phone.source", org.json.JSONArray().put(back.index)) {}
                    return
                }
                lastError = (if (torrent) TorrentEngine.failed() else null) ?: describe(error)
                // (before its first picture: closed quietly, and the page tries the next best stream with
                // the loading screen still up, so a stream that won't play is passed over, not shown failing)
                if (loading?.showing == true) {
                    android.util.Log.w("dxbi-player", "stream failed while loading: $lastError (${error.errorCodeName}) file=${Uri.parse(currentUrl).lastPathSegment}")
                    loading?.retrying(lastError.orEmpty())
                    finishWith(error = lastError)
                    return
                }
                // (in the TV's log, to tell what went wrong with which file: its name, not its address)
                android.util.Log.w("dxbi-player", "stream failed: $lastError (${error.errorCodeName}: ${error.cause?.message}) file=${Uri.parse(currentUrl).lastPathSegment}")
                finishWith(error = lastError)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) { extras?.changed() }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                extras?.changed()
                // (a video with no picture to wait for: playing is ready enough)
                if (isPlaying) handler.postDelayed({ loading?.ready() }, 2500)
            }
        })
        if (reused) {
            // (where it was, unless Play asked for somewhere else: Start from the Beginning)
            val wanted = stream.optLong("startMs", 0L).coerceAtLeast(0L)
            if (kotlin.math.abs(wanted - exo.currentPosition) > 5_000) exo.seekTo(wanted)
            step("taken back from before, ${exo.totalBufferedDuration / 1000} s in hand")
            // (its picture's already there: the cover goes as soon as the screen shows it)
            handler.postDelayed({ uncover(); loading?.ready() }, 400)
        } else exo.prepare()
        exo.playWhenReady = true
        // (no picture in 40 seconds: as good as failed, so the page moves on to the next best)
        handler.postDelayed({
            if (loading?.showing == true && !isFinishing) {
                lastError = "It didn't start"
                android.util.Log.w("dxbi-player", "stream didn't start in 40s file=${Uri.parse(currentUrl).lastPathSegment}")
                loading?.retrying(lastError.orEmpty())
                finishWith(error = lastError)
            }
        }, 40_000)
        handler.postDelayed(reportProgress, PROGRESS_INTERVAL_MS)
        if (showClock) handler.post(updateClock)
        handler.postDelayed(checkSkip, 500)
        if (torrent) handler.post(torrentStatus)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            // Back leaves the player straight away, like Netflix (the position is kept). In Lumen, Back
            // with the controls showing puts them away first, as on Apple TV
            override fun handleOnBackPressed() {
                if (loading?.showing == true) {
                    finishWith(error = lastError)
                    return
                }
                if (scrubber?.active == true) {
                    scrubber?.cancel()
                    return
                }
                if (controls.closePanel()) return
                if (Phone.ownsKeys() && controls.visible) {
                    controls.hide()
                    return
                }
                finishWith(keep = true)
            }
        })
    }

    /**
     * With the controls hidden, the remote works like Netflix's: left/right skip 10 seconds, OK
     * pauses or plays. With the controls showing, the arrows move between them as usual.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val exo = player ?: return super.dispatchKeyEvent(event)
        // (the loading screen's up: only Back works, to stop and go back)
        if (loading?.showing == true) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) return super.dispatchKeyEvent(event)
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> { exo.playWhenReady = !exo.playWhenReady; controls.show(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { exo.play(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { exo.pause(); controls.show(); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seek(+1); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { seek(-1); return true }
            }
            // Lumen: left/right choose where to go (paused), OK goes there, up/down let it be
            val scrub = scrubber
            if (scrub != null && !controls.panelOpen && upNextCard.visibility != View.VISIBLE) {
                val onBar = findViewById<View>(R.id.seek_line).isFocused
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (scrub.active || !controls.visible || onBar) {
                        scrub.step(if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) +1 else -1, event.repeatCount)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (scrub.active) {
                        scrub.commit()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (scrub.active) scrub.cancel()
                }
            }
            // OK presses Skip while it's up (and the controls aren't)
            if (showing != null && !controls.visible && (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                doSkip()
                return true
            }
            if (controls.panelKey(event.keyCode)) return true
            if (controls.visible) controls.poke()
            // The up next card's buttons take the remote
            if (upNextCard.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
            if (!controls.visible) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { seek(-1); return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { seek(+1); return true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        exo.playWhenReady = !exo.playWhenReady
                        controls.show()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { controls.show(); return true }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun mediaItem(url: String) = MediaItem.Builder()
        .setUri(Uri.parse(url))
        .setSubtitleConfigurations(subtitleItems)
        // (what the lock screen and headphones' controls show: the phone's media session reads it)
        .apply {
            if (extras != null) setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(findViewById<TextView>(R.id.title).text)
                    .setSubtitle(findViewById<TextView>(R.id.subtitle).text)
                    .build(),
            )
        }
        .build()

    private fun flashText(text: String) {
        seekFlash.text = text
        seekFlash.visibility = View.VISIBLE
        handler.removeCallbacks(hideFlash)
        handler.postDelayed(hideFlash, 2500)
    }

    /** Lumen's Streams list: this video's streams (null: the page didn't give them), the playing one first */
    fun loadStreams(done: (List<StreamAlt>?) -> Unit) {
        val (type, id, videoId) = videoRoute.takeIf { it.size == 3 } ?: return done(null)
        Phone.playerStreams(type, id, videoId) { page ->
            val list = page?.let { StreamAlt.list(it, playKey) }
            list?.firstOrNull { it.playing }?.let { playIndex = it.index }
            done(list)
        }
    }

    /**
     * Lumen's Streams list: plays [alt] from where this one is. A web stream is swapped in the player at once (the
     * page's phone.source notes it as the one playing); a torrent, or any that can't be, closes this player and
     * the page opens it, with the loading screen up as when a stream is retried
     */
    fun switchStream(alt: StreamAlt) {
        if (alt.playing || closed) return
        controls.hide()
        Phone.pageCall("phone.source", org.json.JSONArray().put(alt.index)) { answer ->
            if (closed) return@pageCall
            val source = answer as? JSONObject
            val url = source?.optString("url").orEmpty()
            if (url.isEmpty()) {
                loading?.switching()
                switchingAway = true
                Phone.playOther(alt.index)
                finishWith()
                return@pageCall
            }
            val headers = source?.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { k -> h.optString(k) } } ?: emptyMap()
            val exo = player ?: return@pageCall
            // (a torrent that was playing can't be gone back to in place)
            returnTo = if (torrent) null else Return(currentUrl, currentHeaders, playKey, playIndex)
            if (torrent) {
                TorrentEngine.close()
                torrent = false
            }
            putStream(url, headers, alt.key, alt.index, exo.currentPosition, source?.optString("label"))
            tracks?.again()
            pictureReported = false
        }
    }

    /** [url] in the player, from [positionMs] (its sound and subtitle choices carried over by the track memory) */
    private fun putStream(url: String, headers: Map<String, String>, key: String, index: Int, positionMs: Long, label: String?) {
        val exo = player ?: return
        onlineSource?.apply {
            // (an addon's own User-Agent replaces ours, as at the start)
            setUserAgent(if (headers.keys.any { it.equals("User-Agent", ignoreCase = true) }) null else "dxbi-tv/${BuildConfigVersion.name(this@PlayerActivity)}")
            setDefaultRequestProperties(headers)
        }
        currentUrl = url
        currentHeaders = headers
        playKey = key
        playIndex = index
        exo.setMediaItem(mediaItem(url), positionMs)
        exo.prepare()
        exo.playWhenReady = true
        if (!label.isNullOrEmpty()) flashText("Playing $label")
    }

    private fun segmentEnd(s: Segment, durationMs: Long): Long =
        if (s.end < 0) durationMs else (s.end * 1000).toLong()

    private fun updateSkip() {
        val exo = player ?: return
        val duration = exo.duration.takeIf { it != C.TIME_UNSET } ?: return
        val now = exo.currentPosition
        // (not the last second of a part: pressing then would skip nothing)
        // (the up next card takes over from Skip credits)
        val seg = segments.firstOrNull { now >= (it.start * 1000).toLong() && now < segmentEnd(it, duration) - 1000 && !(it.type == "credits" && upNextSeconds > 0) }
        val auto = seg != null && (autoSkip == "all" || (autoSkip == "intro" && seg.type != "credits"))
        if (seg != null && auto && seg !in autoSkipped) {
            autoSkipped += seg
            doSkip(seg)
            return
        }
        if (seg == showing) return
        showing = seg
        if (seg == null) {
            skipButton.visibility = View.GONE
            return
        }
        skipButton.text = when (seg.type) {
            "intro" -> "Skip intro"
            "recap" -> "Skip recap"
            else -> "Skip credits"
        }
        skipButton.visibility = View.VISIBLE
        if (!controls.visible) skipButton.requestFocus()
    }

    private fun updateUpNext() {
        val exo = player ?: return
        if (upNextSeconds <= 0 || exo.duration == C.TIME_UNSET || exo.duration < 120_000) return
        val duration = exo.duration
        val credits = segments.firstOrNull { it.type == "credits" }?.let { (it.start * 1000).toLong() }?.takeIf { it > duration / 2 && it < duration }
        val at = credits ?: (duration - 30_000)
        if (exo.currentPosition < at - 2000) {
            upNextDismissed = false
            hideUpNext()
        } else if (exo.currentPosition >= at && !upNextDismissed && upNextCard.visibility != View.VISIBLE) {
            showUpNext()
        }
    }

    private fun showUpNext() {
        if (controls.visible) controls.hide()
        upNextLeft = upNextSeconds
        findViewById<TextView>(R.id.up_next_title).text = nextTitle
        findViewById<TextView>(R.id.up_next_count).text = upNextLeft.toString()
        upNextCard.visibility = View.VISIBLE
        findViewById<View>(R.id.up_next_play).requestFocus()
        handler.postDelayed(upNextCount, 1000)
        if (nextArt.startsWith("https://")) kotlin.concurrent.thread(name = "dxbi-up-next-art") {
            val art = try { java.net.URL(nextArt).openStream().use { BitmapFactory.decodeStream(it) } } catch (_: Exception) { null }
            if (art != null) runOnUiThread { findViewById<ImageView>(R.id.up_next_art).setImageBitmap(art) }
        }
    }

    private fun hideUpNext() {
        if (upNextCard.visibility != View.VISIBLE) return
        upNextCard.visibility = View.GONE
        handler.removeCallbacks(upNextCount)
        playerView.requestFocus()
    }

    private var coverView: View? = null
    /** Lumen: its loading screen carried on over the video (null: none, as in dx.bi TV) */
    private var loading: LoadingCover? = null

    /**
     * Lumen: the title's picture, darkened, with the white spinner over it, until the video's first picture
     * (carrying on from the loading screen, instead of black)
     */
    private fun cover(art: String) {
        val root = findViewById<android.widget.FrameLayout>(android.R.id.content).getChildAt(0) as? android.widget.FrameLayout ?: return
        val frame = android.widget.FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val image = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            alpha = 0f
        }
        frame.addView(image, android.widget.FrameLayout.LayoutParams(-1, -1))
        frame.addView(View(this).apply { setBackgroundColor(Color.argb(150, 0, 0, 0)) }, android.widget.FrameLayout.LayoutParams(-1, -1))
        val size = (44 * resources.displayMetrics.density).toInt()
        frame.addView(android.widget.ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        }, android.widget.FrameLayout.LayoutParams(size, size, android.view.Gravity.CENTER))
        // (over the picture, under the controls)
        root.addView(frame, 1, android.widget.FrameLayout.LayoutParams(-1, -1))
        coverView = frame
        if (art.startsWith("http")) Thread {
            val bitmap = try { java.net.URL(art).openStream().use { BitmapFactory.decodeStream(it) } } catch (_: Exception) { null }
            if (bitmap != null) runOnUiThread { image.setImageBitmap(bitmap); image.animate().alpha(1f).setDuration(300).start() }
        }.start()
    }

    private fun uncover() {
        val view = coverView ?: return
        coverView = null
        view.animate().alpha(0f).setDuration(400).withEndAction { (view.parent as? android.view.ViewGroup)?.removeView(view) }.start()
    }

    /** Moves the subtitles by [byMs] and returns where they are now */
    private fun shiftSubtitles(byMs: Long): Long {
        if (byMs != 0L) {
            subtitleDelay.ms += byMs
            // lines already passed only come back from a seek; once, after the last press
            handler.removeCallbacks(refreshSubtitles)
            handler.postDelayed(refreshSubtitles, 600)
        }
        return subtitleDelayMs
    }

    fun refit() = fitControls()

    private fun fitControls() {
        val config = resources.configuration
        controls.fit(config.screenWidthDp, config.orientation == Configuration.ORIENTATION_PORTRAIT)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::controls.isInitialized) fitControls()
    }

    private fun doSkip(seg: Segment? = showing) {
        val exo = player ?: return
        val s = seg ?: return
        val duration = exo.duration.takeIf { it != C.TIME_UNSET } ?: return
        exo.seekTo(segmentEnd(s, duration).coerceAtMost(duration))
        showing = null
        skipButton.visibility = View.GONE
        playerView.requestFocus()
    }

    private fun seek(direction: Int) {
        val exo = player ?: return
        if (direction > 0) exo.seekForward() else exo.seekBack()
        seekFlash.text = if (direction > 0) "+10 s   ${clock(exo.currentPosition)}" else "−10 s   ${clock(exo.currentPosition)}"
        seekFlash.visibility = View.VISIBLE
        handler.removeCallbacks(hideFlash)
        handler.postDelayed(hideFlash, 900)
    }

    private fun report() {
        val exo = player ?: return
        val duration = exo.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        if (extras?.keep(exo.currentPosition, ended = false) != true) PlaybackBus.listener?.onProgress(exo.currentPosition, duration, !exo.isPlaying)
    }

    /** Closes the player and says how it went ([positionMs]/[durationMs]: where another app left it, instead of this one) */
    private fun finishWith(ended: Boolean = false, error: String? = null, next: Boolean = false, positionMs: Long? = null, durationMs: Long? = null, keep: Boolean = false) {
        if (closed) return
        closed = true
        handler.removeCallbacksAndMessages(null)
        if (::controls.isInitialized) controls.release()
        val exo = player
        val duration = durationMs?.takeIf { it > 0 } ?: exo?.duration?.takeIf { it != C.TIME_UNSET } ?: 0L
        val position = if (ended) duration else positionMs ?: exo?.currentPosition ?: 0L
        scrubber?.release()
        // (on to the next episode: Lumen's loading screen comes up for it first, behind this player)
        val toNext = next && nextTitle.isNotEmpty() && nextId.isNotEmpty() && loading != null
        if (toNext) loading?.next(nextTitle, nextId)
        // (Lumen starts the next episode itself, as Play does: the page just goes back, as for any close)
        if (extras?.keep(position, ended) != true) PlaybackBus.closed(position, duration, ended, error, next && !toNext, measuredBps / 1_000_000.0)
        if (torrent) TorrentEngine.close()
        // Back (not the end, not a failure): the player's kept a few minutes, paused, with what it's downloaded, for Play
        // or Resume on the same stream to carry straight on (HeldPlayer)
        val exoNow = player
        if (keep && exoNow != null && lumenLook && !torrent && error == null && !ended && !next && returnTo == null &&
            loading?.showing != true && exoNow.playbackState != Player.STATE_IDLE) {
            exoAnalytics.forEach { exoNow.removeAnalyticsListener(it) }
            exoListeners.forEach { exoNow.removeListener(it) }
            exoNow.pause()
            playerView.player = null
            HeldPlayer.hold(HeldPlayer.Kept(exoNow, sound, subtitleDelay, currentUrl))
            player = null
        }
        finish()
        // (failed while loading: the app's loading screen is already behind it, so no slide)
        if ((error != null && loading?.showing == true) || toNext || switchingAway) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun onPause() {
        super.onPause()
        // Leaving the app (Home button) pauses, unless it carries on in the phone app's small window;
        // the progress so far is kept
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !isInPictureInPictureMode) player?.pause()
        report()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        extras?.leaving()
    }

    /** The phone app's small window (dx.bi TV never opens one): just the picture in it, a little bigger subtitles */
    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        if (inPip) {
            if (::controls.isInitialized) controls.hide()
            skipButton.visibility = View.GONE
            seekFlash.visibility = View.GONE
            if (::upNextCard.isInitialized) upNextCard.visibility = View.GONE
            playerView.subtitleView?.setFractionalTextSize(subtitleSize * 1.6f)
        } else {
            playerView.subtitleView?.setFractionalTextSize(subtitleSize)
            // The small window was closed (not opened back up): the video's over
            if (lifecycle.currentState == Lifecycle.State.CREATED) finishWith()
        }
    }

    /** What the phone app's part of the player can ask of it */
    private val host = object : PlayerHost {
        override val activity get() = this@PlayerActivity
        override val playerView get() = this@PlayerActivity.playerView
        override val seekFlash get() = this@PlayerActivity.seekFlash
        override fun seek(direction: Int) = this@PlayerActivity.seek(direction)
        override fun shiftSubtitles(byMs: Long) = this@PlayerActivity.shiftSubtitles(byMs)
        override fun leaveParty() { PlaybackBus.listener?.onPartyLeave() }
        override fun finishWith(ended: Boolean, next: Boolean, positionMs: Long?, durationMs: Long?) =
            this@PlayerActivity.finishWith(ended = ended, next = next, positionMs = positionMs, durationMs = durationMs)

        override fun flash(text: String, forMs: Long) {
            handler.removeCallbacks(hideFlash)
            if (text.isEmpty()) {
                seekFlash.visibility = View.GONE
                return
            }
            seekFlash.text = text
            seekFlash.visibility = View.VISIBLE
            handler.postDelayed(hideFlash, forMs)
        }
    }

    override fun onDestroy() {
        if (!closed) finishWith()
        handler.removeCallbacksAndMessages(null)
        if (PlaybackBus.player === this) PlaybackBus.player = null
        if (::controls.isInitialized) controls.release()
        if (::zoom.isInitialized) zoom.release()
        extras?.release()
        player?.release()
        player = null
        super.onDestroy()
    }

    /**
     * Stremio's subtitle settings (size, colours, outline, position) instead of the player's white
     * text on black boxes. Colours come as #RRGGBB or #RRGGBBAA.
     */
    private fun styleSubtitles(style: JSONObject) {
        val view = playerView.subtitleView ?: return
        fun color(key: String, fallback: Int): Int {
            val hex = style.optString(key).removePrefix("#")
            val v = hex.toLongOrNull(16) ?: return fallback
            return when (hex.length) {
                6 -> (0xFF000000 or v).toInt()
                8 -> ((v and 0xFF) shl 24 or (v ushr 8)).toInt()
                else -> fallback
            }
        }
        val opacity = (style.optDouble("opacity", 100.0) / 100).coerceIn(0.0, 1.0)
        val text = color("text", Color.WHITE).let { Color.argb((Color.alpha(it) * opacity).toInt(), Color.red(it), Color.green(it), Color.blue(it)) }
        val outline = color("outline", Color.BLACK)
        val typeface = if (style.optBoolean("bold")) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        view.setStyle(CaptionStyleCompat(
            text,
            color("background", Color.TRANSPARENT),
            Color.TRANSPARENT,
            if (Color.alpha(outline) > 0) CaptionStyleCompat.EDGE_TYPE_OUTLINE else CaptionStyleCompat.EDGE_TYPE_NONE,
            outline,
            typeface,
        ))
        // the file's own colours and sizes would win over these otherwise
        view.setApplyEmbeddedStyles(false)
        view.setApplyEmbeddedFontSizes(false)
        val size = style.optDouble("size", 100.0).coerceIn(50.0, 300.0) / 100
        subtitleSize = (SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * size).toFloat()
        view.setFractionalTextSize(subtitleSize)
        // Stremio's offset is how far up from the bottom of the picture the lines sit
        val offset = style.optDouble("offset", 5.0).coerceIn(0.0, 40.0) / 100
        view.setBottomPaddingFraction(0f)
        view.addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
            val pad = ((bottom - top) * offset).toInt()
            if (v.paddingBottom != pad) v.post { v.setPadding(0, 0, 0, pad) }
        }
    }

    private fun subtitleMime(url: String): String {
        val path = Uri.parse(url).lastPathSegment?.lowercase() ?: ""
        return when {
            path.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            path.endsWith(".ass") || path.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            path.endsWith(".ttml") || path.endsWith(".dfxp") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    /**
     * Subtitles start in the file's own track when it has one in [language]: made for this release, it's
     * in sync, where a subtitle addon's (found by searching the title) is often for another release and
     * drifts. Forced (signs-only) tracks don't count. True once the tracks were there to choose from.
     */
    private fun preferOwnSubtitles(tracks: Tracks, language: String, sideloaded: Set<String>): Boolean {
        val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        if (groups.isEmpty()) return false
        val want = iso3(language)
        val own = groups.firstNotNullOfOrNull { g ->
            (0 until g.length).firstOrNull { i ->
                val f = g.getTrackFormat(i)
                g.isTrackSupported(i) && f.id !in sideloaded && f.language?.let(::iso3) == want && f.selectionFlags and C.SELECTION_FLAG_FORCED == 0
            }?.let { g to it }
        } ?: return true
        val exo = player ?: return true
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(own.first.mediaTrackGroup, own.second))
            .build()
        return true
    }

    /** English, or no language given (a file's own track without one is most often English) */
    private fun isEnglish(code: String?): Boolean = code.isNullOrEmpty() || iso3(code).let { it == "eng" || it == "und" }

    /** "en", "eng", "en-GB": the same three letters */
    private fun iso3(code: String): String = runCatching { java.util.Locale(code.substringBefore('-').substringBefore('_')).isO3Language }.getOrDefault(code).lowercase()

    private fun describe(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "the server refused it"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "the connection failed"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
            // (sound the TV can't decode goes to the bundled FFmpeg decoder, so this is almost always the picture)
            "this TV can't decode its picture (try another release)"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "its file format isn't supported"
        else -> error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    companion object {
        const val EXTRA_STREAM = "stream"
        private const val PROGRESS_INTERVAL_MS = 5000L
        private const val SEEK_STEP_MS = 10_000L
    }
}
