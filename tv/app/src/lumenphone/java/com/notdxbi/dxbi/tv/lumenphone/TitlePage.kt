package com.notdxbi.dxbi.tv.lumenphone

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notdxbi.dxbi.tv.Phone
import com.notdxbi.dxbi.tv.phone.AnimeSeason
import com.notdxbi.dxbi.tv.phone.Details
import com.notdxbi.dxbi.tv.phone.Load
import com.notdxbi.dxbi.tv.phone.Reviews
import com.notdxbi.dxbi.tv.phone.StreamChoices
import com.notdxbi.dxbi.tv.phone.StreamOption
import com.notdxbi.dxbi.tv.phone.Stremio
import com.notdxbi.dxbi.tv.phone.Title
import com.notdxbi.dxbi.tv.phone.Video
import com.notdxbi.dxbi.tv.phone.text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** The round back button over a page's art (the system back gesture does the same) */
@Composable
fun PageBack(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.windowInsetsPadding(WindowInsets.statusBars).padding(12.dp).size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = onBack),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) }
}

/** A round button in glass over art (iOS's GlassCircle): the Library's + and ✓, the ⋯ */
@Composable
fun GlassCircle(modifier: Modifier = Modifier, size: Dp = 50.dp, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(modifier.size(size).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) { content() }
}

/** White, black text: Apple TV's Play pill */
@Composable
fun PlayPill(label: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(50.dp).clip(CircleShape).background(Color.White.copy(alpha = if (enabled) 1f else 0.5f)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.Black, modifier = Modifier.size(24.dp))
        Text(label, color = Color.Black, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

private fun playLabel(d: Details?, id: String, series: Boolean): String {
    if (d == null) return "Play"
    if (series) {
        val v = nextVideo(d)
        if (v?.season != null && v.episode != null) return "${if (v.id == d.resumeVideoId && d.resumeMs > 0) "Resume" else "Play"} S${v.season}, E${v.episode}"
    }
    return if (d.resumeMs > 0 && d.resumeVideoId == id) "Resume" else "Play"
}

private fun episodeLine(d: Details?): String? {
    val v = d?.videos?.firstOrNull { it.id == d.videoId } ?: return null
    if (v.season == null || v.episode == null) return null
    return "S${v.season}, E${v.episode}" + if (v.title.isNotEmpty()) " · ${v.title}" else ""
}

/**
 * A film or show, the Apple TV way (iOS TitleView, the same on a phone and the opened Fold): its art fills the top,
 * darkened under the logo, the facts and Play low on the left (who's in it on the right, where there's room); its
 * trailer fades in over the art after a moment. Down the page: the seasons and episodes, the trailer, related titles,
 * the cast and About, with the art staying put and fading as the page moves up over it.
 *
 * [active]: it's the page on top. A title opened over it makes Stremio open that one instead, so this page shows what
 * it last had while it's covered and opens itself again when it's back.
 */
@Composable
fun TitlePage(
    stremio: Stremio,
    layout: AppLayout,
    page: Page.TitleOf,
    active: Boolean,
    onBack: () -> Unit,
    onOpen: (Title) -> Unit,
    onPerson: (id: String, name: String, photo: String?) -> Unit,
    onSearch: (String) -> Unit,
) {
    val ref = page.title
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = LocalLauncher.current
    val live by stremio.details.collectAsState()
    var kept by remember { mutableStateOf<Details?>(null) }
    val mine = live?.takeIf { it.metaId == ref.id }
    LaunchedEffect(mine) { if (mine != null) kept = mine }
    val d = mine ?: kept
    val title = d?.title?.let { backgroundFrom(it, ref) } ?: ref
    val ready = mine?.state == Load.Ready

    var choices by remember { mutableStateOf<StreamChoices?>(null) }
    var streamsOpen by remember { mutableStateOf(false) }
    var downloadsOpen by remember { mutableStateOf(false) }
    var reviewsOpen by remember { mutableStateOf(false) }
    var season by remember { mutableStateOf<Int?>(null) }
    var related by remember { mutableStateOf<List<Title>>(emptyList()) }
    var animeSeasons by remember(ref.id) { mutableStateOf<List<AnimeSeason>>(emptyList()) }
    var reviews by remember { mutableStateOf<Reviews?>(null) }
    var cast by remember { mutableStateOf<List<JSONObject>?>(null) }
    var more by remember { mutableStateOf(false) }
    var autoPlayed by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val inList = inLibrary(stremio, title, mine?.title?.inLibrary == true)
    val blurUnwatched by produceState(false) { value = stremio.blurUnwatched() }
    val playTrailers by produceState<Boolean?>(null) { value = stremio.playTrailers() }

    LaunchedEffect(ref.id) { if (ref.id.startsWith("kitsu:")) animeSeasons = stremio.animeSeasons(ref.id) }
    // Open (and open again when it's back on top)
    LaunchedEffect(active) {
        if (active && stremio.details.value?.metaId != ref.id) stremio.open(ref.type, ref.id, kept?.videoId)
    }
    LaunchedEffect(ref.id) { reviews = stremio.reviews(ref.type, ref.id) }
    LaunchedEffect(ready) {
        val dd = mine ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        if (cast == null) {
            val names = JSONArray()
            dd.directors.take(3).forEach { names.put(JSONObject().put("name", it).put("role", "Director")) }
            dd.cast.take(13).forEach { names.put(JSONObject().put("name", it).put("role", "")) }
            launch { cast = stremio.cast(ref.type, if (Regex("tt\\d+").matches(ref.id)) ref.id else "", names) }
        }
        if (related.isEmpty()) launch { related = stremio.related(ref.type, ref.id, dd.title?.genres.orEmpty()) }
    }

    fun current(): StreamChoices? {
        val v = stremio.details.value?.takeIf { it.metaId == ref.id }?.videoId ?: return null
        return choices?.takeIf { it.videoId == v }
    }

    // (Android 13 and later ask before an app shows notifications: a download shows its progress in one)
    val notifications = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}

    /** A stream chosen from the list (or sent to another app, a Chromecast, or kept): started, with the loading screen */
    fun play(option: StreamOption, target: Phone.Target = Phone.Target.Player) {
        streamsOpen = false
        downloadsOpen = false
        if (target == Phone.Target.Download && android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        launcher?.play(title, option, episodeLine(d), target) ?: stremio.play(option, target)
    }

    /** Play: Lumen's pick (the stream chosen last time, else the best that's ready), with the loading screen and no list on the way */
    fun start(videoId: String?, fromStart: Boolean = false, shuffle: Boolean = false) {
        launcher?.play(title, videoId, fromStart, shuffle)
    }

    // A series: the episode they're on, ready before Play is pressed. Opened to play (Home's Play, a card's menu): once it's in.
    LaunchedEffect(ready) {
        val dd = mine ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        if (ref.type == "series") {
            if (season == null) season = nextVideo(dd)?.season ?: dd.seasons.firstOrNull()
            if (dd.videoId == null) nextVideo(dd)?.let { stremio.open(ref.type, ref.id, it.id) }
        }
        if ((page.play || page.fromStart || page.shuffle) && !autoPlayed) {
            autoPlayed = true
            start(if (page.shuffle) dd.shuffled()?.id else nextVideo(dd)?.id, fromStart = page.fromStart, shuffle = page.shuffle)
        }
    }
    // the episode picked shows in its season (back from the player, the page picks the next one when the last was finished)
    LaunchedEffect(d?.videoId) { d?.videos?.firstOrNull { it.id == d.videoId }?.season?.let { season = it } }
    // Lumen's reading of the streams, again as more addons answer
    val streamsKey = mine?.videoId?.let { v -> v + mine.streams.joinToString("|") { "${it.addon}${it.state}${it.streams.size}" } }
    LaunchedEffect(streamsKey) {
        if (streamsKey == null) return@LaunchedEffect
        choices = stremio.streams()
    }

    val list = rememberLazyListState()
    // The trailer: a moment after the page opens, while it's on top (not on mobile data), quiet once the page has moved
    var hushed by remember { mutableStateOf(false) }
    var trailerOn by remember { mutableStateOf(false) }
    var trailerPlaying by remember { mutableStateOf(false) }
    var soundOn by remember { mutableStateOf(false) }
    val trailerAllowed = title.trailer != null && playTrailers == true && active && !streamsOpen && launcher?.title == null && !onMobileData(context)
    LaunchedEffect(trailerAllowed) {
        if (!trailerAllowed) { trailerOn = false; trailerPlaying = false; return@LaunchedEffect }
        delay(1500)
        trailerOn = true
    }
    LaunchedEffect(list.firstVisibleItemIndex) { if (list.firstVisibleItemIndex > 0) hushed = true }

    BoxWithConstraints(Modifier.fillMaxSize().background(Dx.Background)) {
        val wide = maxWidth > 600.dp
        // Apple TV's: the art fills the top (most of the screen on a tablet); the details sit low on it
        val artHeight = if (wide) maxOf(minOf(maxHeight * 0.8f, maxWidth * 0.75f), 440.dp) else maxOf(maxWidth * 1.35f, 520.dp).coerceAtMost(maxHeight)
        val artPx = with(androidx.compose.ui.platform.LocalDensity.current) { artHeight.toPx() }
        val covered by remember { androidx.compose.runtime.derivedStateOf { if (list.firstVisibleItemIndex > 0) 1f else (list.firstVisibleItemScrollOffset / (artPx * 0.8f)).coerceIn(0f, 1f) } }
        val artFade by animateFloatAsState(if (trailerPlaying) 0f else 1f, tween(900), label = "art")

        // The art, behind the page: it stays as the page moves up over it, at half the speed, fading as it's covered
        Box(
            Modifier.fillMaxWidth().height(artHeight).graphicsLayer {
                translationY = if (list.firstVisibleItemIndex == 0) list.firstVisibleItemScrollOffset * 0.5f else 0f
                alpha = 1f - covered * 0.9f
            },
        ) {
            if (trailerOn) title.trailer?.let { TrailerPreview(it, muted = !soundOn || hushed, modifier = Modifier.fillMaxSize(), onPlaying = { trailerPlaying = true }) }
            Art(title.backdrop, Modifier.fillMaxSize().alpha(artFade))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.35f), 0.22f to Color.Transparent, 0.42f to Color.Transparent, 0.8f to Color.Black.copy(alpha = 0.72f), 1f to Color.Black)))
            if (wide) Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.6f), 0.55f to Color.Transparent)))
        }

        val bottomPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 40.dp
        LazyColumn(state = list, contentPadding = PaddingValues(bottom = bottomPad)) {
            item(key = "header") {
                Box(Modifier.fillMaxWidth().height(artHeight)) {
                    Row(
                        Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = if (wide) 44.dp else layout.gutter).padding(bottom = if (wide) 50.dp else 26.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(30.dp),
                    ) {
                        Column(Modifier.weight(1f).widthIn(max = if (wide) 520.dp else 600.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Info(title, d, choices?.takeIf { it.videoId == d?.videoId }, reviews, more, { more = !more }, { reviewsOpen = true }, wide)
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    PlayPill(playLabel(d, ref.id, ref.type == "series"), enabled = ready, modifier = Modifier.weight(1f, fill = false)) { start(d?.let { nextVideo(it)?.id }) }
                                    GlassCircle(onClick = {
                                        stremio.listMarks.value = stremio.listMarks.value + (ref.id to !inList)
                                        stremio.setInList(!inList)
                                        say(context, if (inList) "Removed from your Library" else "Added to your Library")
                                    }) { Icon(if (inList) Icons.Filled.Check else Icons.Filled.Add, contentDescription = if (inList) "Remove from Library" else "Add to Library", tint = Color.White) }
                                    Box {
                                        GlassCircle(size = 44.dp, onClick = { menu = true }) { Icon(Icons.Filled.MoreHoriz, contentDescription = "More", tint = Color.White) }
                                        MoreMenu(menu, { menu = false }, stremio, ref, d, ready, title, current(), reviews != null,
                                            fromStart = { start(d?.let { nextVideo(it)?.id }, fromStart = true) },
                                            shuffle = { start(d?.shuffled()?.id, shuffle = true) },
                                            chooseStream = { streamsOpen = true },
                                            download = { downloadsOpen = true },
                                            reviews = { reviewsOpen = true },
                                            play = { o, t -> play(o, t) })
                                    }
                                }
                                if (!wide) Credits(d)
                            }
                        }
                        if (wide) Box(Modifier.weight(1f)) { Box(Modifier.widthIn(max = 340.dp)) { Credits(d) } }
                    }
                }
            }
            if (animeSeasons.size > 1) item(key = "seasons") {
                Section("Seasons", layout) {
                    LazyRow(contentPadding = PaddingValues(horizontal = layout.gutter), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(animeSeasons, key = { it.title.id }) { s ->
                            Column(Modifier.width(layout.poster).clickable(enabled = !s.current) { onOpen(s.title) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.then(if (s.current) Modifier.border(2.5.dp, Color.White, RoundedCornerShape(16.dp)) else Modifier)) { PosterCard(s.title, layout.poster, onClick = { if (!s.current) onOpen(s.title) }) }
                                Text(s.label, color = if (s.current) Dx.Text else Dx.Muted, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 2)
                            }
                        }
                    }
                }
            }
            if (ref.type == "series" && d != null && d.videos.isNotEmpty()) item(key = "episodes") {
                Episodes(stremio, d, season, { season = it }, layout, title, blurUnwatched, onPlay = { start(it) }, onChoose = {
                    choices = null
                    stremio.open(ref.type, ref.id, it)
                    streamsOpen = true
                })
            }
            title.trailer?.let { yt ->
                item(key = "trailers") {
                    Section("Trailers", layout) {
                        Box(
                            Modifier.padding(horizontal = layout.gutter).widthIn(max = 350.dp).fillMaxWidth(0.72f).clip(RoundedCornerShape(14.dp))
                                .clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$yt"))) },
                        ) {
                            Art("https://img.youtube.com/vi/$yt/hqdefault.jpg", Modifier.fillMaxWidth().height(180.dp))
                            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f))))
                            Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
                                Text("${title.name} Trailer", color = Dx.Text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("YouTube", color = Dx.Muted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            if (related.isNotEmpty()) item(key = "related") {
                Section("Related", layout) {
                    LazyRow(contentPadding = PaddingValues(horizontal = layout.gutter), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(related, key = { it.id }) { t -> PosterCard(t, layout.poster, onClick = { onOpen(t) }) }
                    }
                }
            }
            val castList = cast
            if (d != null && (d.cast.isNotEmpty() || d.directors.isNotEmpty() || castList?.isNotEmpty() == true)) item(key = "cast") {
                val face = if (wide) 120.dp else 84.dp
                Section("Cast & Crew", layout) {
                    LazyRow(contentPadding = PaddingValues(horizontal = layout.gutter), horizontalArrangement = Arrangement.spacedBy(if (wide) 26.dp else 12.dp)) {
                        val people: List<Triple<String, String?, Pair<String?, String?>>> =
                            if (!castList.isNullOrEmpty()) castList.map { Triple(it.optString("name"), it.text("role"), it.text("photo") to it.text("id")) }
                            else d.directors.take(3).map { Triple(it, "Director", null to null) } + d.cast.take(15).map { Triple(it, null, null to null) }
                        items(people.size) { i ->
                            val (name, role, extra) = people[i]
                            val (photo, id) = extra
                            Column(
                                Modifier.width(face + 16.dp).clip(RoundedCornerShape(12.dp)).clickable { if (!id.isNullOrEmpty()) onPerson(id, name, photo) else onSearch(name) },
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Face(name, photo, face)
                                Text(name, color = Dx.Text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                if (!role.isNullOrEmpty()) Text(role, color = Dx.Muted, style = MaterialTheme.typography.labelMedium, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                        }
                    }
                }
            }
            if (title.description != null || title.genres.isNotEmpty()) item(key = "about") {
                About(title, d, layout)
            }
        }

        // The buttons float over the art
        Row(Modifier.fillMaxWidth()) {
            PageBack(onBack)
            Spacer(Modifier.weight(1f))
            if (trailerPlaying) Box(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(vertical = 12.dp)) {
                GlassCircle(size = 40.dp, onClick = { soundOn = !soundOn }) {
                    Icon(if (soundOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff, contentDescription = if (soundOn) "Sound off" else "Sound on", tint = Color.White)
                }
            }
            if (ref.id.startsWith("tt")) Box(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(12.dp)) {
                GlassCircle(size = 40.dp, onClick = {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "${title.name} https://www.imdb.com/title/${ref.id}/"), null))
                }) { Icon(Icons.Filled.Share, contentDescription = "Share", tint = Color.White, modifier = Modifier.size(20.dp)) }
            }
        }
    }

    if (streamsOpen) StreamsModal(current(), title.name, episodeLine(d), { streamsOpen = false }) { o, t -> play(o, t) }
    if (downloadsOpen) StreamsModal(current(), title.name, "Choose a file to download", { downloadsOpen = false }, Phone.Target.Download) { o, t -> play(o, t) }
    if (reviewsOpen) ReviewsSheet(title, reviews) { reviewsOpen = false }
}

/** The title with what the page's own title lacks (a card's art) kept, so the art doesn't vanish while details arrive */
private fun backgroundFrom(page: Title, card: Title): Title = page.copy(
    poster = page.poster ?: card.poster, background = page.background ?: card.background, logo = page.logo ?: card.logo,
    description = page.description ?: card.description, genres = page.genres.ifEmpty { card.genres },
    year = page.year ?: card.year, runtime = page.runtime ?: card.runtime,
)


@Composable
private fun Section(name: String, layout: AppLayout, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Dx.Background).padding(top = 34.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(name, layout.gutter)
        content()
    }
}

/** On the art's left: the logo, what it is, the story (the episode Play starts), the facts */
@Composable
private fun Info(title: Title, d: Details?, choices: StreamChoices?, reviews: Reviews?, more: Boolean, toggleMore: () -> Unit, openReviews: () -> Unit, wide: Boolean) {
    val video = d?.let { nextVideo(it) }
    val label = video?.let { v -> if (v.season != null && v.episode != null) "S${v.season}, E${v.episode}" + if (v.title.isNotEmpty()) " · ${v.title}" else "" else null }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TitleLogo(title, maxHeight = if (wide) 120.dp else 100.dp, maxWidth = 380.dp, textSize = 38.sp, modifier = Modifier.padding(bottom = 6.dp))
        Text(
            (listOf(if (title.type == "movie") "Film" else typeName(title.type)) + title.genres.take(2)).joinToString(" · "),
            color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, maxLines = 1,
        )
        val text = video?.overview ?: title.description ?: ""
        if (label != null || text.isNotEmpty()) {
            val long = (label?.length ?: 0) + text.length > 130
            Column(Modifier.clickable { toggleMore() }) {
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        if (label != null) withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold, color = Color.White)) { append(label + if (text.isEmpty()) "" else ": ") }
                        withStyle(androidx.compose.ui.text.SpanStyle(color = Color.White.copy(alpha = 0.78f))) { append(text) }
                    },
                    style = MaterialTheme.typography.bodyMedium, maxLines = if (more) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                )
                if (long && !more) Text("MORE", color = Dx.Muted, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
        // "2026 · 47 min", the scores, then small outlined badges: 4K, Dolby Vision, Dolby Atmos…
        val formats = choices?.picked?.let { p -> listOfNotNull("4K".takeIf { p.res >= 2160 }, if (p.dv) "Dolby Vision" else if (p.hdr10plus) "HDR10+" else if (p.hdr) "HDR" else null, "Dolby Atmos".takeIf { p.atmos }, "IMAX".takeIf { p.imax }) }.orEmpty()
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val facts = listOfNotNull(title.year, title.runtime).joinToString(" · ")
            if (facts.isNotEmpty()) Text(facts, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            if (title.watched) FormatBadge("WATCHED")
            reviews?.scores?.takeIf { it.isNotEmpty() }?.let { scores ->
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        scores.take(2).forEachIndexed { i, s ->
                            if (i > 0) append("  ")
                            withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold)) { append(s.value) }
                            withStyle(androidx.compose.ui.text.SpanStyle(color = Dx.Muted)) { append(" ${s.name}") }
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.clickable(onClick = openReviews),
                )
            }
            formats.forEach { FormatBadge(it) }
        }
    }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.withStyle(style: androidx.compose.ui.text.SpanStyle, block: androidx.compose.ui.text.AnnotatedString.Builder.() -> Unit) {
    pushStyle(style); block(); pop()
}

/** On the art's right (under Play on a phone): who's in it, and who made it */
@Composable
private fun Credits(d: Details?) {
    if (d == null || (d.cast.isEmpty() && d.directors.isEmpty())) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (d.cast.isNotEmpty()) Text(androidx.compose.ui.text.buildAnnotatedString {
            withStyle(androidx.compose.ui.text.SpanStyle(color = Dx.Muted)) { append("Starring ") }
            append(d.cast.take(3).joinToString(", "))
        }, color = Dx.Text, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
        if (d.directors.isNotEmpty()) Text(androidx.compose.ui.text.buildAnnotatedString {
            withStyle(androidx.compose.ui.text.SpanStyle(color = Dx.Muted)) { append(if (d.directors.size > 1) "Directors " else "Director ") }
            append(d.directors.take(2).joinToString(", "))
        }, color = Dx.Text, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
    }
}

/** The ⋯ menu: Start from the Beginning, Shuffle, watched, Reviews, Choose a Stream, an external player, Download */
@Composable
private fun MoreMenu(
    open: Boolean, close: () -> Unit, stremio: Stremio, ref: Title, d: Details?, ready: Boolean, title: Title, choices: StreamChoices?, hasReviews: Boolean,
    fromStart: () -> Unit, shuffle: () -> Unit, chooseStream: () -> Unit, download: () -> Unit, reviews: () -> Unit, play: (StreamOption, Phone.Target) -> Unit,
) {
    val context = LocalContext.current
    val hasPlace = d != null && d.resumeMs > 0 && (if (ref.type == "series") nextVideo(d)?.id == d.resumeVideoId else d.resumeVideoId == ref.id)
    DropdownMenu(expanded = open, onDismissRequest = close) {
        if (hasPlace) DropdownMenuItem(text = { Text("Start from the Beginning") }, onClick = { close(); fromStart() })
        if (ref.type == "series" && ready) DropdownMenuItem(text = { Text("Shuffle") }, onClick = { close(); shuffle() })
        if (!title.watched) DropdownMenuItem(text = { Text("Mark as Watched") }, onClick = { close(); stremio.setWatched(true) })
        if (title.watched || (d?.resumeMs ?: 0) > 0) DropdownMenuItem(text = { Text("Mark as Unwatched") }, onClick = { close(); stremio.setWatched(false) })
        title.trailer?.let { yt ->
            DropdownMenuItem(text = { Text("Trailer") }, onClick = { close(); context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$yt"))) })
        }
        DropdownMenuItem(text = { Text("Reviews") }, onClick = { close(); reviews() })
        if (d?.videoId != null) DropdownMenuItem(text = { Text("Choose a Stream") }, onClick = { close(); chooseStream() })
        choices?.picked?.let { pick ->
            DropdownMenuItem(text = { Text("Play with External Player") }, onClick = { close(); play(pick, Phone.Target.External) })
            DropdownMenuItem(text = { Text("Download…") }, onClick = { close(); download() })
        }
    }
}

/** The seasons and episodes: a season menu, then a row of tall cards, the one they're on in view */
@Composable
private fun Episodes(
    stremio: Stremio, d: Details, season: Int?, setSeason: (Int) -> Unit, layout: AppLayout, title: Title, blur: Boolean,
    onPlay: (String) -> Unit, onChoose: (String) -> Unit,
) {
    val shownSeason = season ?: d.seasons.firstOrNull()
    val shown = d.videos.filter { it.season == shownSeason }
    var seasons by remember { mutableStateOf(false) }
    val row = rememberLazyListState()
    val playing = nextVideo(d)
    LaunchedEffect(shown.size, shownSeason, d.videoId) {
        val i = shown.indexOfFirst { it.id == playing?.id }
        if (i > 0) { delay(250); row.animateScrollToItem(i) }
    }
    Column(Modifier.fillMaxWidth().background(Dx.Background).padding(top = 34.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.padding(horizontal = layout.gutter)) {
            Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { seasons = true }, verticalAlignment = Alignment.CenterVertically) {
                Text(seasonName(shownSeason), color = Dx.Text, style = MaterialTheme.typography.titleLarge)
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = Dx.Muted)
            }
            DropdownMenu(expanded = seasons, onDismissRequest = { seasons = false }) {
                d.seasons.forEach { s -> DropdownMenuItem(text = { Text(seasonName(s)) }, onClick = { seasons = false; setSeason(s) }) }
            }
        }
        LazyRow(state = row, contentPadding = PaddingValues(horizontal = layout.gutter), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(shown, key = { it.id }) { v ->
                EpisodeCard(
                    v, layout.episodeCard, title.backdrop, playing = v.id == playing?.id, blur = blur,
                    onPlay = { onPlay(v.id) }, onChoose = { onChoose(v.id) },
                    onWatched = { watched, upTo -> stremio.setWatched(v.id, watched, upTo) },
                )
            }
        }
    }
}

private fun seasonName(s: Int?) = when (s) { null -> "Episodes"; 0 -> "Specials"; else -> "Season $s" }

/** About: the title, its genres and the whole story, in a box (and the facts, as Apple TV's Information) */
@Composable
private fun About(title: Title, d: Details?, layout: AppLayout) {
    Column(Modifier.fillMaxWidth().background(Dx.Background).padding(top = 34.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("About", layout.gutter)
        Column(
            Modifier.padding(horizontal = layout.gutter).widthIn(max = 620.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Dx.Raised).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title.name, color = Dx.Text, style = MaterialTheme.typography.titleLarge)
            if (title.genres.isNotEmpty()) Text(title.genres.joinToString(", ").uppercase(), color = Dx.Muted, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            title.description?.let { Text(it, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)) }
            val info = listOfNotNull(title.year?.let { "Released $it" }, title.runtime, d?.directors?.takeIf { it.isNotEmpty() }?.let { "Directed by ${it.joinToString(", ")}" })
            if (info.isNotEmpty()) Text(info.joinToString(" · "), color = Dx.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
