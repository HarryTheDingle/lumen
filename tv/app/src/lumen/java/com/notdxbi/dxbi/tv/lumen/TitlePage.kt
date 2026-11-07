package com.notdxbi.dxbi.tv.lumen

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
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

/**
 * A film or show, the Apple TV way (as on the iPad: ios/Dxbi/UI/TitleView.swift): its art fills the
 * screen, darkened under the logo, the facts and Play low on the left, who's in it on the right; its
 * trailer fades in over the art after a moment. Down from Play: the seasons and episodes, related
 * titles, the cast and About, with the art fading as the page moves up over it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TitlePage(stremio: Stremio, nav: Nav, ref: Title, autoPlay: Boolean) {
    val context = LocalContext.current
    val covered = LocalCovered.current
    val live by stremio.details.collectAsState()
    // (what this page last showed: another title's page over it changes what Stremio has open)
    var kept by remember { mutableStateOf<Details?>(null) }
    val mine = live?.takeIf { it.metaId == ref.id }
    LaunchedEffect(mine) { if (mine != null) kept = mine }
    val d = mine ?: kept
    val title = d?.title ?: ref
    val ready = mine?.state == Load.Ready

    var choices by remember { mutableStateOf<StreamChoices?>(null) }
    var streamsOpen by remember { mutableStateOf(false) }
    var wantPlay by remember { mutableStateOf(autoPlay) }
    var season by remember { mutableStateOf<Int?>(null) }
    var related by remember { mutableStateOf<List<Title>>(emptyList()) }
    /** An anime's other seasons (Kitsu keeps each as its own show); empty for anything else, or one season */
    var animeSeasons by remember(ref.id) { mutableStateOf<List<AnimeSeason>>(emptyList()) }
    LaunchedEffect(ref.id) { if (ref.id.startsWith("kitsu:")) animeSeasons = stremio.animeSeasons(ref.id) }
    var reviews by remember { mutableStateOf<Reviews?>(null) }
    /** The cast with photos (TMDB, with a key in dx.bi settings), else null */
    var cast by remember { mutableStateOf<List<JSONObject>?>(null) }
    var menu by remember { mutableStateOf<Pair<String, List<Action>>?>(null) }
    var inList by remember(title.inLibrary) { mutableStateOf(title.inLibrary) }
    /** dx.bi settings → Blur unwatched episodes */
    val blurUnwatched by androidx.compose.runtime.produceState(false) { value = stremio.blurUnwatched() }
    /** dx.bi settings → Play Trailers (null until read) */
    val playTrailers by androidx.compose.runtime.produceState<Boolean?>(null) { value = stremio.playTrailers() }

    // Open (and open again when it's back on top)
    LaunchedEffect(covered) {
        if (!covered && stremio.details.value?.metaId != ref.id) stremio.open(ref.type, ref.id, kept?.videoId)
    }
    LaunchedEffect(ref.id) { reviews = stremio.reviews(ref.type, ref.id) }
    LaunchedEffect(ready) {
        val dd = mine ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        if (cast == null) {
            val names = JSONArray()
            dd.directors.take(3).forEach { names.put(JSONObject().put("name", it).put("role", "Director")) }
            dd.cast.take(13).forEach { names.put(JSONObject().put("name", it).put("role", "")) }
            launch { cast = stremio.cast(ref.type, imdbId(ref.id) ?: "", names) }
        }
        if (related.isEmpty()) launch { related = stremio.related(ref.type, ref.id, dd.title?.genres.orEmpty()) }
    }

    fun currentChoices(): StreamChoices? {
        val v = stremio.details.value?.takeIf { it.metaId == ref.id }?.videoId ?: return null
        return choices?.takeIf { it.videoId == v }
    }

    val launcher = LocalLauncher.current

    /** A stream chosen from the list: started, with the loading screen */
    fun play(option: StreamOption) {
        streamsOpen = false
        launcher?.play(title, option, d?.let(::episodeLine)) ?: stremio.play(option)
    }

    /**
     * Play plays: Lumen's pick (the stream chosen last time, else the best that's ready), with the
     * loading screen saying what it's doing, and no list to choose from. (… → Choose a Stream has every stream.)
     */
    fun start(videoId: String?, fromStart: Boolean = false, shuffle: Boolean = false) {
        launcher?.play(title, videoId, fromStart, shuffle)
    }

    // A series: the episode they're on, ready before Play is pressed. Play pressed early (or the page
    // opened to play) goes once the title's in.
    LaunchedEffect(ready, wantPlay) {
        val dd = mine ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        if (ref.type == "series") {
            if (season == null) season = nextVideo(dd)?.season ?: dd.seasons.firstOrNull()
            if (dd.videoId == null) nextVideo(dd)?.let { stremio.open(ref.type, ref.id, it.id) }
        }
        if (wantPlay) {
            wantPlay = false
            start(nextVideo(dd)?.id)
        }
    }
    // the episode picked shows in its season (back from the player, it's the next one: dxbi-engine.js afterPlaying)
    LaunchedEffect(d?.videoId) {
        d?.videos?.firstOrNull { it.id == d.videoId }?.season?.let { season = it }
    }
    // Lumen's reading of the streams, again as more addons answer
    val streamsKey = mine?.videoId?.let { v -> v + mine.streams.joinToString("|") { "${it.addon}${it.state}${it.streams.size}" } }
    LaunchedEffect(streamsKey) {
        if (streamsKey == null) return@LaunchedEffect
        choices = stremio.streams()
    }

    val list = rememberLazyListState()
    val atTop by remember { derivedStateOf { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset < 120 } }

    // The trailer: a moment after the page opens (at once when it carries on from the card's), while
    // it's on top and the app's showing (not under the player). It goes quiet once the page moves away
    // from the top, and stays quiet.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifeState by lifecycle.currentStateFlow.collectAsState()
    val trailerAllowed = title.trailer != null && playTrailers == true && !covered && !streamsOpen && launcher?.title == null && lifeState.isAtLeast(Lifecycle.State.RESUMED)
    var hushed by remember { mutableStateOf(false) }
    LaunchedEffect(atTop) { if (!atTop) hushed = true }
    var trailerOn by remember { mutableStateOf(false) }
    var trailerShowing by remember { mutableStateOf(false) }
    LaunchedEffect(trailerAllowed) {
        if (!trailerAllowed) {
            trailerOn = false
            trailerShowing = false
            return@LaunchedEffect
        }
        delay(if (title.trailer?.let { trailerTimes[it] } != null) 150 else 2000)
        // (the art lifts off it once its video's going: Trailer says)
        trailerOn = true
    }

    val scope = rememberCoroutineScope()
    val playFocus = remember { FocusRequester() }
    // Down from Play lands on the episode they're on, past the seasons (up from the episodes reaches those)
    val episodeFocus = remember { FocusRequester() }
    val seasonsFocus = remember { FocusRequester() }
    FocusOnOpen(playFocus)

    /**
     * The page goes in three whole sections, the remote moving it a section at a time: the top (the
     * picture, Play), the episodes (a screen of their own, a show's), and the rest (related, cast, about)
     */
    val hasEpisodes = ref.type == "series" && d != null && d.videos.isNotEmpty()
    // (an anime's seasons row sits between the top and the episodes)
    val hasSeasons = animeSeasons.size >= 2
    val episodesIndex = if (hasSeasons) 2 else 1
    val moreIndex = (if (hasEpisodes) episodesIndex + 1 else 1) + (if (hasSeasons && !hasEpisodes) 1 else 0)
    fun snap(index: Int) = scope.launch {
        // (a moment later: the focus move's own scrolling would otherwise cancel it)
        delay(80)
        list.animateScrollToItem(index)
    }

    fun toTop() = scope.launch {
        list.animateScrollToItem(0)
        runCatching { playFocus.requestFocus() }
    }

    // the trailer to itself after a few seconds of the remote being still
    val still = rememberStillness(trailerShowing && atTop && !streamsOpen && menu == null)
    val uiAlpha = still.alpha()

    PageWindow(onBack = { nav.back() }, home = playFocus) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                // (clear while the trailer shows: it plays under the page)
                .background(if (trailerShowing) Color.Transparent else Dx.Background)
                // Back: down the page, to the top; at the top, the page closes
                .onPreviewKeyEvent {
                    if (still.key(it)) return@onPreviewKeyEvent true
                    if (it.key != Key.Back) return@onPreviewKeyEvent false
                    if (it.type == KeyEventType.KeyUp) {
                        if (list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0) toTop() else nav.back()
                    }
                    true
                },
        ) {
            val screen = maxHeight
            val screenPx = with(LocalDensity.current) { screen.toPx() }
            // the art stays where it is as the page moves up over it, fading as it's covered
            val covering by remember {
                derivedStateOf { if (list.firstVisibleItemIndex > 0) 1f else (list.firstVisibleItemScrollOffset / (screenPx * 0.7f)).coerceIn(0f, 1f) }
            }
            val artAlpha by animateFloatAsState(if (trailerShowing) 0f else 1f, tween(900), label = "art")
            Box(Modifier.fillMaxSize().alpha((1f - covering) * uiAlpha).fadeBottom(0.72f)) {
                if (trailerOn) title.trailer?.let { Trailer(it, Modifier.fillMaxSize(), muted = hushed || menu != null, onPlaying = { trailerShowing = true }) }
                Art(title.backdrop, Modifier.fillMaxSize().alpha(artAlpha))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.3f), 0.2f to Color.Transparent, 0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))))
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.65f), 0.55f to Color.Transparent)))
            }
            // (the trailer plays under the page: it goes to black as the page moves up over it)
            if (trailerShowing) Box(Modifier.fillMaxSize().alpha(covering).background(Color.Black))

            CompositionLocalProvider(LocalBringIntoViewSpec provides PageScrolling) {
                LazyColumn(state = list, modifier = Modifier.alpha(uiAlpha), contentPadding = PaddingValues(bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(30.dp)) {
                    item(key = "header") {
                        Header(
                            title = title,
                            details = d,
                            reviews = reviews,
                            episode = if (ref.type == "series") d?.let(::nextVideo) else null,
                            badges = streamBadges(choices?.takeIf { c -> c.videoId == d?.videoId }?.let { c -> c.picked ?: c.items.minWithOrNull(StreamOption.BEST) }),
                            height = screen,
                            playLabel = playLabel(ref, d),
                            inList = inList,
                            playFocus = playFocus,
                            // (the episodes aren't there to move to until they're scrolled in: in first, then onto the one they're on)
                            onDown = if (!hasEpisodes && !hasSeasons) null else {
                                {
                                    scope.launch {
                                        list.animateScrollToItem(1)
                                        repeat(10) {
                                            if (runCatching { (if (hasSeasons) seasonsFocus else episodeFocus).requestFocus() }.isSuccess) return@launch
                                            delay(50)
                                        }
                                    }
                                }
                            },
                            onFocused = {
                                scope.launch {
                                    delay(80)
                                    if (list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0) list.animateScrollToItem(0)
                                }
                            },
                            onPlay = { if (ready) start(mine?.let(::nextVideo)?.id) else wantPlay = true },
                            onList = {
                                inList = !inList
                                stremio.listMarks.value = stremio.listMarks.value + (ref.id to inList)
                                stremio.setInList(inList)
                                Toast.makeText(context, if (inList) "Added to your Library" else "Removed from your Library", Toast.LENGTH_SHORT).show()
                            },
                            onMore = {
                                menu = title.name to buildList {
                                    // (a saved place to go back to: the film's, or the episode Play would start's)
                                    val place = d != null && d.resumeMs > 0 && (if (ref.type == "series") nextVideo(d)?.id == d.resumeVideoId else d.resumeVideoId == ref.id)
                                    if (place) add(Action("Start from the Beginning", Icons.Filled.Replay) { start(d?.let(::nextVideo)?.id, fromStart = true) })
                                    if (ref.type == "series" && d != null && ready) add(Action("Shuffle", Icons.Filled.Shuffle) { start(d.shuffled()?.id, shuffle = true) })
                                    if (!title.watched) add(Action("Mark as Watched", Icons.Filled.Visibility) { stremio.setWatched(true) })
                                    // (partly seen counts: Unwatched puts it back to the start)
                                    if (title.watched || (d?.resumeMs ?: 0) > 0) add(Action("Mark as Unwatched", Icons.Filled.VisibilityOff) { stremio.setWatched(false) })
                                    if (d?.videoId != null) add(Action("Choose a Stream", Icons.AutoMirrored.Filled.List) {
                                        streamsOpen = true
                                    })
                                }
                            },
                        )
                    }
                    if (hasSeasons) {
                        item(key = "seasons") {
                            SeasonsRow(
                                animeSeasons, landing = seasonsFocus,
                                onOpen = { nav.open(it) },
                                // (down: the episodes, scrolled in first)
                                onDown = {
                                    if (hasEpisodes) scope.launch {
                                        list.animateScrollToItem(episodesIndex)
                                        repeat(10) {
                                            if (runCatching { episodeFocus.requestFocus() }.isSuccess) return@launch
                                            delay(50)
                                        }
                                    }
                                },
                                modifier = Modifier.padding(top = 24.dp).onFocusChanged { if (it.hasFocus) snap(1) },
                            )
                        }
                    }
                    if (hasEpisodes && d != null) {
                        item(key = "episodes") {
                            Box(Modifier.fillParentMaxHeight().padding(top = 24.dp).onFocusChanged { if (it.hasFocus) snap(episodesIndex) }) {
                            Episodes(
                                title, d, season ?: d.seasons.firstOrNull(), nextVideo(d)?.id, title.backdrop, blurUnwatched,
                                landing = episodeFocus,
                                onSeason = { season = it },
                                onPlay = { start(it.id) },
                                onMenu = { v ->
                                    menu = episodeName(v) to buildList {
                                        add(Action("Play", Icons.Filled.PlayArrow) { start(v.id) })
                                        if (v.id == d.resumeVideoId && d.resumeMs > 0) add(Action("Start from the Beginning", Icons.Filled.Replay) { start(v.id, fromStart = true) })
                                        add(Action("Choose a Stream", Icons.AutoMirrored.Filled.List) {
                                            choices = null
                                            stremio.open(ref.type, ref.id, v.id)
                                            streamsOpen = true
                                        })
                                        if (v.watched) add(Action("Mark as Unwatched", Icons.Filled.VisibilityOff) { stremio.setWatched(v.id, false) })
                                        else add(Action("Mark as Watched", Icons.Filled.Visibility) { stremio.setWatched(v.id, true) })
                                        if ((v.episode ?: 0) > 1) add(Action("Mark Up to Here as Watched", Icons.Filled.DoneAll) { stremio.setWatched(v.id, true, upTo = true) })
                                    }
                                },
                            )
                            }
                        }
                    }
                    // the rest, as one section: it comes up whole from the episodes (or the top)
                    item(key = "more") {
                        Column(
                            Modifier
                                .padding(top = 36.dp)
                                .onFocusChanged { if (it.hasFocus && list.firstVisibleItemIndex < moreIndex) snap(moreIndex) },
                            verticalArrangement = Arrangement.spacedBy(30.dp),
                        ) {
                            if (related.isNotEmpty()) {
                                Shelf("Related") {
                                    items(related, key = { it.id }) { t -> PreviewPosterCard(t, onClick = { nav.open(t) }) }
                                }
                            }
                            if (d != null && (d.cast.isNotEmpty() || d.directors.isNotEmpty())) CastRow(d, cast, onPerson = { name, id, photo -> if (id != null) nav.push(Page.Person(id, name, photo)) else nav.search(name) })
                            if (title.description != null || title.genres.isNotEmpty()) About(title, d)
                        }
                    }
                }
            }
        }

        if (streamsOpen) {
            StreamsPanel(
                choices = currentChoices(),
                heading = title.name,
                subheading = d?.let(::episodeLine),
                onPlay = { play(it) },
                onDismiss = { streamsOpen = false },
            )
        }
        menu?.let { (heading, actions) -> ActionMenu(heading, actions, onDismiss = { menu = null }) }
    }
}

/**
 * How the page scrolls to what the remote moves onto: not at all while it's on screen, else so it sits
 * a fifth of the way down
 */
@OptIn(ExperimentalFoundationApi::class)
private val PageScrolling = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        if (offset >= 0f && offset + size <= containerSize) return 0f
        return offset - containerSize * 0.2f
    }
}

/** What Play starts: the episode last played if it was left part-way, the one after it if it was finished, else the first (the engine decides) */
fun nextVideo(d: Details): Video? {
    if (d.title?.type != "series" && d.videos.none { it.season != null }) return null
    return d.videos.firstOrNull { it.id == d.nextVideoId } ?: d.videos.firstOrNull { it.id == d.videoId } ?: d.videos.firstOrNull { it.id == d.resumeVideoId }
        ?: d.videos.firstOrNull { (it.season ?: 0) > 0 && !it.upcoming } ?: d.videos.firstOrNull()
}

private fun playLabel(ref: Title, d: Details?): String {
    if (d == null) return "Play"
    if (ref.type == "series") {
        val v = nextVideo(d)
        if (v?.season != null && v.episode != null) {
            val resuming = v.id == d.resumeVideoId && d.resumeMs > 0
            return "${if (resuming) "Resume" else "Play"} S${v.season}, E${v.episode}"
        }
    }
    return if (d.resumeMs > 0 && d.resumeVideoId == ref.id) "Resume" else "Play"
}

private fun episodeLine(d: Details): String? {
    val v = d.videos.firstOrNull { it.id == d.videoId } ?: return null
    if (v.season == null || v.episode == null) return null
    return "S${v.season}, E${v.episode} · ${v.title}"
}

private fun episodeName(v: Video) = if (v.episode != null) "${v.episode}. ${v.title}" else v.title

/** The title's IMDb id (tt…): Stremio's own id for most titles */
private fun imdbId(id: String) = id.takeIf { Regex("""tt\d+""").matches(it) }

// ---------------------------------------------------------------------------------------------
// The top: logo, facts, Play

@Composable
private fun Header(
    title: Title,
    details: Details?,
    reviews: Reviews?,
    episode: Video?,
    badges: List<String>,
    height: Dp,
    playLabel: String,
    inList: Boolean,
    playFocus: FocusRequester,
    onDown: (() -> Unit)?,
    onFocused: () -> Unit,
    onPlay: () -> Unit,
    onList: () -> Unit,
    onMore: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(height)) {
        TitleInfo(
            title,
            detailed = true,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = Dx.Gutter, bottom = 40.dp),
            episode = episode,
            reviews = reviews,
            badges = badges,
            buttonsModifier = Modifier
                .onFocusChanged { if (it.hasFocus) onFocused() }
                .onPreviewKeyEvent {
                    if (onDown == null || it.key != Key.DirectionDown) return@onPreviewKeyEvent false
                    if (it.type == KeyEventType.KeyDown) onDown()
                    true
                },
        ) {
            PlayButton(playLabel, onPlay, Modifier.focusRequester(playFocus))
            RoundButton(if (inList) Icons.Filled.Check else Icons.Filled.Add, if (inList) "In your Library" else "Add to Library", onList)
            RoundButton(Icons.Filled.MoreHoriz, "More", onMore)
        }
        // who's in it, and who made it
        if (details != null && (details.cast.isNotEmpty() || details.directors.isNotEmpty())) {
            Column(
                Modifier.align(Alignment.BottomEnd).padding(end = Dx.Gutter, bottom = 44.dp).width(260.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (details.cast.isNotEmpty()) Credit("Starring", details.cast.take(3).joinToString(", "))
                if (details.directors.isNotEmpty()) Credit(if (details.directors.size > 1) "Directors" else "Director", details.directors.take(2).joinToString(", "))
            }
        }
    }
}

@Composable
private fun Credit(role: String, names: String) {
    Text(
        androidx.compose.ui.text.buildAnnotatedString {
            pushStyle(androidx.compose.ui.text.SpanStyle(color = Dx.Muted))
            append("$role ")
            pop()
            append(names)
        },
        fontSize = 13.sp,
        color = Color.White,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

/** "2026 · 47 min", Watched, and the scores */
@Composable
fun Facts(title: Title, reviews: Reviews?, badges: List<String> = emptyList()) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        val when_ = listOfNotNull(title.year, title.runtime).joinToString(" · ")
        if (when_.isNotEmpty()) Text(when_, fontSize = 14.sp, color = Color.White)
        badges.forEach { FormatBadge(it) }
        if (title.watched) Text("Watched", fontSize = 14.sp, color = Dx.Muted)
        reviews?.scores?.take(3)?.forEach { s ->
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold))
                    append(s.value)
                    pop()
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = Dx.Muted))
                    append(" ${scoreName(s)}")
                    pop()
                },
                fontSize = 14.sp,
                color = Color.White,
                maxLines = 1,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Seasons (anime)

/**
 * An anime's seasons: small posters with "Season 2" under each, plain text. The one open has a white ring
 * and doesn't open (it's here); the others open their own page.
 */
@Composable
private fun SeasonsRow(
    seasons: List<AnimeSeason>,
    landing: FocusRequester,
    onOpen: (Title) -> Unit,
    onDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val width = 110.dp
    val row = rememberLazyListState()
    val here = seasons.indexOfFirst { it.current }.coerceAtLeast(0)
    LaunchedEffect(seasons) { row.scrollToItem((here - 1).coerceAtLeast(0)) }
    Column(modifier.fillMaxWidth()) {
        Text("Seasons", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(horizontal = Dx.Gutter))
        LazyRow(
            state = row,
            contentPadding = PaddingValues(horizontal = Dx.Gutter, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.onPreviewKeyEvent {
                if (it.key == Key.DirectionDown) {
                    if (it.type == KeyEventType.KeyDown) onDown()
                    true
                } else false
            },
        ) {
            itemsIndexed(seasons, key = { _, s -> s.title.id }) { i, s ->
                Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    FocusCard(
                        onClick = { if (!s.current) onOpen(s.title) },
                        modifier = Modifier.size(width, width * 1.5f).then(if (i == here) Modifier.focusRequester(landing) else Modifier),
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            Art(s.title.art, Modifier.fillMaxSize())
                            // (the white ring: the season they're on)
                            if (s.current) Box(Modifier.fillMaxSize().border(3.dp, Color.White, RoundedCornerShape(12.dp)))
                        }
                    }
                    Text(
                        s.label, fontSize = 14.sp, fontWeight = if (s.current) FontWeight.Bold else FontWeight.Medium,
                        color = if (s.current) Color.White else Dx.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// Episodes

@Composable
private fun Episodes(
    title: Title,
    d: Details,
    season: Int?,
    current: String?,
    fallbackArt: String?,
    blurUnwatched: Boolean,
    landing: FocusRequester,
    onSeason: (Int) -> Unit,
    onPlay: (Video) -> Unit,
    onMenu: (Video) -> Unit,
) {
    val shown = d.videos.filter { it.season == season }
    val row = rememberLazyListState()
    // the episode they're on, in view (once the episodes are there, and again on its season)
    LaunchedEffect(shown.size, season, current) {
        val i = shown.indexOfFirst { it.id == current }
        if (i > 0) row.scrollToItem(i)
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // the show's name over its seasons, as on Apple TV (the art it was on has gone by now)
        TitleLogo(title, maxHeight = 46.dp, maxWidth = 320.dp, textSize = 30.sp, centred = true, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
        if (d.seasons.size > 1) {
            LazyRow(contentPadding = PaddingValues(horizontal = Dx.Gutter), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(d.seasons) { s ->
                    Pill(if (s == 0) "Specials" else "Season $s", selected = s == season, onClick = { onSeason(s) })
                }
            }
        } else {
            Text(if (season == 0) "Specials" else "Season ${season ?: 1}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(horizontal = Dx.Gutter))
        }
        LazyRow(
            state = row,
            contentPadding = PaddingValues(horizontal = Dx.Gutter, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // (the episode they're on, else the season's first: where Down from Play lands)
            val target = shown.firstOrNull { it.id == current }?.id ?: shown.firstOrNull()?.id
            items(shown, key = { it.id }) { v ->
                val here = v.id == target
                EpisodeCard(
                    v, fallbackArt, isCurrent = v.id == current, blur = blurUnwatched,
                    onClick = { onPlay(v) }, onLongClick = { onMenu(v) },
                    modifier = if (here) Modifier.focusRequester(landing) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun EpisodeCard(v: Video, fallbackArt: String?, isCurrent: Boolean, blur: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = 250.dp) {
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FocusCard(onClick = onClick, onLongClick = onLongClick, modifier = modifier.width(width).height(width * 9f / 16f)) {
            Box(Modifier.fillMaxSize()) {
                // (Blur unwatched episodes: hidden until it's watched or started; TVs before Android 12 can't
                // blur, so the picture's covered instead)
                val hidden = blur && !v.watched && (v.progress ?: 0f) <= 0f
                Art(v.thumbnail ?: fallbackArt, Modifier.fillMaxSize().alpha(if (v.upcoming) 0.5f else 1f).then(if (hidden) Modifier.blur(16.dp) else Modifier))
                if (hidden && android.os.Build.VERSION.SDK_INT < 31) Box(Modifier.fillMaxSize().background(Color(0xFF2A2A2E)))
                if (v.watched) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "Watched",
                        tint = Color.White,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).background(Color.Black, CircleShape),
                    )
                }
                val p = v.progress
                if (p != null && p > 0f && !v.watched) {
                    ProgressLine(p, Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(10.dp))
                }
            }
        }
        // Apple TV's: EPISODE 4, its name, the story and the day it came out
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            v.episode?.let { Text("EPISODE $it", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (isCurrent) Color.White else Dx.Muted) }
            if (v.title.isNotEmpty()) Text(v.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            v.overview?.let { Text(it, fontSize = 12.sp, color = Color.White.copy(alpha = 0.75f), maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp) }
            releasedText(v)?.let { Text(it, fontSize = 11.sp, color = Dx.Muted, modifier = Modifier.padding(top = 2.dp)) }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Cast, about

/** Who's in it: choosing someone opens their page (with a TMDB key), else searches for them, as on the desktop */
@Composable
private fun CastRow(d: Details, photos: List<JSONObject>?, onPerson: (name: String, id: String?, photo: String?) -> Unit) {
    val people: List<List<String?>> = if (!photos.isNullOrEmpty()) {
        photos.map { listOf(it.optString("name"), it.text("role"), it.text("photo"), it.text("id")) }
    } else {
        d.directors.take(3).map { listOf(it, "Director", null, null) } + d.cast.take(15).map { listOf(it, null, null, null) }
    }
    Column(Modifier.fillMaxWidth()) {
        Text("Cast & Crew", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(horizontal = Dx.Gutter))
        Spacer(Modifier.height(10.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = Dx.Gutter, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            items(people) { (name, role, photo, id) -> Person(name!!, role, photo) { onPerson(name, id, photo) } }
        }
    }
}

@Composable
fun Person(name: String, role: String?, photo: String?, size: Dp = 96.dp, onClick: () -> Unit) {
    Column(Modifier.width(size + 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            onClick = onClick,
            modifier = Modifier.size(size),
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF555555), focusedContainerColor = Color(0xFF666666)),
            border = ClickableSurfaceDefaults.border(focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.5.dp, Color.White), shape = CircleShape)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        ) {
            Box(Modifier.fillMaxSize().clip(CircleShape), contentAlignment = Alignment.Center) {
                Text(name.split(' ').mapNotNull { it.firstOrNull() }.take(2).joinToString(""), fontSize = (size.value * 0.34f).sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                if (photo != null) AsyncImage(model = photo, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Text(name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White, maxLines = 2, textAlign = TextAlign.Center, lineHeight = 16.sp)
        if (!role.isNullOrEmpty()) Text(role, fontSize = 11.sp, color = Dx.Muted, maxLines = 2, textAlign = TextAlign.Center)
    }
}

/** The title, its genres and the whole story, in a box (and the facts, as Apple TV's Information) */
@Composable
private fun About(title: Title, d: Details?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Dx.Gutter)) {
        Text("About", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(12.dp))
        Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
            if (title.genres.isNotEmpty()) Text(title.genres.joinToString(", ").uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Dx.Muted)
            title.description?.let { Text(it, fontSize = 14.sp, color = Color.White.copy(alpha = 0.85f), lineHeight = 20.sp, modifier = Modifier.padding(top = 6.dp)) }
            val info = listOfNotNull(
                title.year?.let { "Released $it" },
                title.runtime,
                d?.directors?.takeIf { it.isNotEmpty() }?.let { "Directed by ${it.joinToString(", ")}" },
            )
            if (info.isNotEmpty()) Text(info.joinToString(" · "), fontSize = 12.sp, color = Dx.Muted, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** "7 Oct 2026" (or "Coming 7 Oct 2026") from the episode's release date */
private fun releasedText(v: Video): String? {
    val day = v.released?.take(10) ?: return null
    val date = runCatching { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(day) }.getOrNull() ?: return null
    val text = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(date)
    return if (v.upcoming) "Coming $text" else text
}
