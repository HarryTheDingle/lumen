package com.notdxbi.dxbi.tv.lumen

import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.notdxbi.dxbi.tv.phone.Reviews
import com.notdxbi.dxbi.tv.phone.StreamOption
import com.notdxbi.dxbi.tv.phone.Title
import com.notdxbi.dxbi.tv.phone.Video
import com.notdxbi.dxbi.tv.phone.episodeLabel

/**
 * The words and buttons over the art, the Apple TV way, left-aligned: the same block on Home's
 * billboard and at the top of a title's page, the page's the more detailed (`detailed`).
 *
 *  - the logo, and "TV Show · Drama · Sci-Fi"
 *  - Home: the description (two lines, muted) and a status line when there's something real to say
 *  - the page: the episode Play would start ("S4, E3 · The Bear Hug:" and its synopsis, else the
 *    title's description; MORE opens the lot), then the year, runtime and what the best stream offers
 *
 * [buttons] is the row under it (Play, then the round ones). [status] is asked for the title shown, so
 * the billboard's fade between titles carries it.
 */
@Composable
fun TitleInfo(
    title: Title,
    detailed: Boolean,
    modifier: Modifier = Modifier,
    animated: Boolean = false,
    status: (Title) -> String? = { null },
    episode: Video? = null,
    reviews: Reviews? = null,
    badges: List<String> = emptyList(),
    /** A film not out on digital yet: "In Cinemas", "Digital 18 Nov" */
    notice: String? = null,
    buttonsModifier: Modifier = Modifier,
    /** A small tag over the logo, as Apple TV's "New": "New", "New Episode", "Watched" */
    tag: String? = null,
    /** The age rating and the like (TMDB) */
    facts: TitleFacts? = null,
    buttons: @Composable RowScope.() -> Unit,
) {
    var full by remember { mutableStateOf(false) }
    val info: @Composable (Title) -> Unit = { t ->
        Column(Modifier.widthIn(max = 470.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (tag != null) Text(
                tag, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                modifier = Modifier.glass(RoundedCornerShape(Dx.RCard)).padding(horizontal = 7.dp, vertical = 2.dp),
            )
            TitleLogo(t, maxHeight = 110.dp, maxWidth = 380.dp, textSize = 40.sp, modifier = Modifier.padding(bottom = 4.dp))
            Text(kindLine(t), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.85f))
            if (detailed) {
                val lead = episode?.let(::episodeLead)?.takeIf { episode.overview != null }
                val body = if (lead != null) episode.overview else t.description
                if (body != null) Synopsis(lead, body, onMore = { full = true })
                Facts(t, reviews, badges, notice, facts?.rating)
            } else {
                t.description?.let {
                    Text(it, fontSize = 14.sp, color = Dx.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
                }
                status(t)?.let { Text(it, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White) }
            }
        }
    }
    Column(modifier) {
        if (animated) AnimatedContent(title, // (the picture settles first, then the words come up: as Apple TV's billboard does)
        transitionSpec = { fadeIn(tween(450, delayMillis = 380)) togetherWith fadeOut(tween(200)) }, label = "title-info",
        // (the same title with something changed, as when it's added to the Library, keeps its words where they are)
        contentKey = { it.id }) { info(it) }
        else info(title)
        Row(
            Modifier.padding(top = 18.dp).then(buttonsModifier),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = buttons,
        )
    }
    if (full) {
        val lead = episode?.let(::episodeLead)?.takeIf { episode.overview != null }
        FullText(lead ?: title.name, if (lead != null) episode.overview.orEmpty() else title.description.orEmpty(), onDismiss = { full = false })
    }
}

/** "TV Show · Drama · Sci-Fi": what it is and up to two genres */
private fun kindLine(t: Title) =
    (listOf(if (t.type == "movie") "Film" else typeName(t.type)) + t.genres.take(2)).joinToString(" · ")

/** "S4, E3 · The Bear Hug:" */
private fun episodeLead(v: Video): String? {
    if (v.season == null || v.episode == null) return null
    return "${episodeLabel(v.season, v.episode, v.title)}:"
}

/** Two or three lines of it, bold lead first; MORE (when it doesn't all fit) opens the whole text */
@Composable
private fun Synopsis(lead: String?, body: String, onMore: () -> Unit) {
    var cut by remember(lead, body) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            buildAnnotatedString {
                if (lead != null) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) { append(lead) }
                    append(" ")
                }
                append(body)
            },
            fontSize = 14.sp,
            color = Dx.Muted,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 19.sp,
            onTextLayout = { cut = it.hasVisualOverflow },
        )
        if (cut) {
            Surface(
                onClick = onMore,
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(Dx.RCard)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                    focusedContainerColor = Color.White,
                    focusedContentColor = Color.Black,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = Dx.FocusButton),
            ) {
                Text("MORE", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
    }
}

/** The whole text, over the page; Back closes it */
@Composable
internal fun FullText(heading: String, body: String, onDismiss: () -> Unit) {
    PageWindow(onBack = onDismiss, dim = 0.7f, fadeIn = false) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(620.dp).background(Dx.Raised, RoundedCornerShape(Dx.RPanel)).padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(heading, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(body, fontSize = 15.sp, color = Color.White.copy(alpha = 0.85f), lineHeight = 21.sp)
            }
        }
    }
}

/** What the stream Play would start offers, as the small outlined badges: 4K, Dolby Vision or HDR, Dolby Atmos, IMAX */
fun streamBadges(best: StreamOption?): List<String> {
    if (best == null) return emptyList()
    return listOfNotNull(
        "4K".takeIf { best.res >= 2160 },
        if (best.dv) "Dolby Vision" else if (best.hdr10plus) "HDR10+" else if (best.hdr) "HDR" else null,
        "Dolby Atmos".takeIf { best.atmos },
        "IMAX".takeIf { best.imax },
    )
}

@Composable
fun FormatBadge(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White.copy(alpha = 0.9f),
        maxLines = 1,
        // (glass, so it reads on bright art and on black alike)
        modifier = Modifier
            .glass(RoundedCornerShape(Dx.RSmall), fill = Color.White.copy(alpha = 0.12f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/**
 * Play (or Resume): white, dark text. [loading]: still finding out whether anything can play (a title page's streams
 * coming in): a small turning ring in its place, about Play's width so the buttons beside it hardly move, which settles
 * into Play or Look Again with a soft fade as the button eases to its new size
 */
@Composable
fun PlayButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector = Icons.Filled.PlayArrow, loading: Boolean = false, expands: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    var everFocused by remember { mutableStateOf(false) }
    val open = !expands || focused || (!everFocused && label != "Look Again")
    // (the sides ease in and out with the words, rather than jumping)
    val padX by androidx.compose.animation.core.animateDpAsState(if (open) 16.dp else 10.dp, ButtonMotion.springDp, label = "pad")
    Button(
        onClick = onClick,
        modifier = modifier.onFocusChanged {
            val f = it.isFocused || it.hasFocus
            focused = f
            if (f) everFocused = true
        },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = padX, vertical = 10.dp),
        colors = ButtonDefaults.colors(
            // Apple TV's: frosted glass at rest (light enough to see on black), a soft white when the remote's on it
            containerColor = Glass.Fill,
            contentColor = Color.White,
            focusedContainerColor = Color(0xFFE8E8E8),
            focusedContentColor = Color.Black,
        ),
        scale = ButtonDefaults.scale(focusedScale = Dx.FocusButton),
        border = ButtonDefaults.border(border = androidx.tv.material3.Border(BorderStroke(1.dp, Glass.Edge), shape = RoundedCornerShape(50)), focusedBorder = androidx.tv.material3.Border.None),
    ) {
        AnimatedContent(
            targetState = if (loading) null else label to icon,
            transitionSpec = {
                (fadeIn(tween(320, delayMillis = 90)) togetherWith fadeOut(tween(160)))
                    .using(androidx.compose.animation.SizeTransform(clip = false) { _, _ -> androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 260f) })
            },
            contentAlignment = androidx.compose.ui.Alignment.Center,
            label = "play",
        ) { shown ->
            if (shown == null) {
                androidx.compose.foundation.layout.Box(Modifier.widthIn(min = if (open) 96.dp else 22.dp).height(PLAY_CONTENT_HEIGHT), contentAlignment = androidx.compose.ui.Alignment.Center) { Turning(Modifier.size(20.dp)) }
            } else {
                androidx.compose.foundation.layout.Row(Modifier.height(PLAY_CONTENT_HEIGHT), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Icon(shown.second, contentDescription = shown.first, modifier = Modifier.size(22.dp))
                    androidx.compose.animation.AnimatedVisibility(
                        visible = open,
                        enter = ButtonMotion.open,
                        exit = ButtonMotion.close,
                    ) {
                        Text(shown.first, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}

/**
 * How the title page's buttons open out and close (their names sliding in beside the icon): springs that settle without
 * a bounce, as Apple TV's do, and can turn round mid-way when the remote moves on quickly (fixed-length curves couldn't)
 */
private object ButtonMotion {
    private const val STIFF = 320f // ≈ 0.35 s
    val springDp = androidx.compose.animation.core.spring<androidx.compose.ui.unit.Dp>(dampingRatio = 1f, stiffness = STIFF)
    val open = androidx.compose.animation.expandHorizontally(
        androidx.compose.animation.core.spring<androidx.compose.ui.unit.IntSize>(dampingRatio = 1f, stiffness = STIFF),
        expandFrom = androidx.compose.ui.Alignment.Start,
    ) + fadeIn(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 200f))
    val close = androidx.compose.animation.shrinkHorizontally(
        androidx.compose.animation.core.spring<androidx.compose.ui.unit.IntSize>(dampingRatio = 1f, stiffness = STIFF),
        shrinkTowards = androidx.compose.ui.Alignment.Start,
    ) + fadeOut(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 900f))
}

/** Play's inside, turning or not: one height, so the button never grows while the streams come in */
private val PLAY_CONTENT_HEIGHT = 22.dp

/** A thin ring turning, in the text's colour (black on the chosen button, white otherwise) */
@Composable
private fun Turning(modifier: Modifier) {
    val colour = androidx.tv.material3.LocalContentColor.current
    val spin = androidx.compose.animation.core.rememberInfiniteTransition(label = "turning")
    val angle by spin.animateFloat(
        0f, 360f,
        androidx.compose.animation.core.infiniteRepeatable(tween(900, easing = androidx.compose.animation.core.LinearEasing)),
        label = "angle",
    )
    androidx.compose.foundation.Canvas(modifier) {
        val w = 2.5.dp.toPx()
        drawCircle(colour.copy(alpha = 0.2f), radius = (size.minDimension - w) / 2f, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
        drawArc(
            colour, startAngle = angle - 90f, sweepAngle = 100f, useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(w / 2f, w / 2f),
            size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
            style = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
    }
}

/** The round button beside Play: +, ✓, … */
@Composable
fun RoundButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Color.White, expands: Boolean = false) {
    // [expands]: chosen, it opens out into a pill with its name beside the icon ("Add to Library", "Rate"), the buttons
    // beside it moving along to make room, as Apple TV's do; back to a circle when the remote moves on
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.isFocused || it.hasFocus },
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = Glass.Fill,
            contentColor = tint,
            focusedContainerColor = Color(0xFFE8E8E8),
            focusedContentColor = Color.Black,
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = if (expands) 1.04f else 1.1f),
        border = androidx.tv.material3.ClickableSurfaceDefaults.border(border = androidx.tv.material3.Border(BorderStroke(1.dp, Glass.Edge), shape = shape), focusedBorder = androidx.tv.material3.Border.None),
    ) {
        androidx.compose.foundation.layout.Row(
            Modifier.height(40.dp).widthIn(min = 40.dp).padding(horizontal = 10.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
            if (expands) androidx.compose.animation.AnimatedVisibility(
                visible = focused,
                enter = ButtonMotion.open,
                exit = ButtonMotion.close,
            ) {
                Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(start = 8.dp, end = 4.dp))
            }
        }
    }
}

/**
 * A score's own mark, drawn (no emoji): Rotten Tomatoes' tomato (a green splat when it's rotten, under 60%), the
 * audience's popcorn bucket, IMDb's yellow badge, Metacritic's coloured square. [size]: the mark's height.
 */
@Composable
fun ScoreMark(source: String, value: String, size: androidx.compose.ui.unit.Dp = 14.dp) {
    val pct = value.filter { it.isDigit() || it == '.' }.toFloatOrNull()
    when (source) {
        "tomatoes" -> androidx.compose.foundation.Canvas(Modifier.size(size)) {
            val w = this.size.width
            if (pct != null && pct < 60f) {
                // rotten: a green splat
                val splat = Color(0xFF0FA34A)
                drawCircle(splat, radius = w * 0.36f, center = center)
                for (i in 0 until 7) {
                    val a = Math.toRadians(i * 360.0 / 7 + 10).toFloat()
                    drawCircle(splat, radius = w * 0.11f, center = androidx.compose.ui.geometry.Offset(center.x + kotlin.math.cos(a) * w * 0.4f, center.y + kotlin.math.sin(a) * w * 0.4f))
                }
            } else {
                // fresh: a red tomato with its green leaves
                drawCircle(Color(0xFFFA320A), radius = w * 0.44f, center = androidx.compose.ui.geometry.Offset(center.x, center.y + w * 0.05f))
                drawCircle(Color.White.copy(alpha = 0.35f), radius = w * 0.1f, center = androidx.compose.ui.geometry.Offset(center.x - w * 0.18f, center.y - w * 0.04f))
                val leaf = Color(0xFF00912D)
                for (dx in listOf(-0.16f, 0f, 0.16f)) {
                    drawOval(leaf, topLeft = androidx.compose.ui.geometry.Offset(center.x + dx * w - w * 0.09f, w * 0.02f), size = androidx.compose.ui.geometry.Size(w * 0.18f, w * 0.2f))
                }
            }
        }
        "popcorn" -> androidx.compose.foundation.Canvas(Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            // the corn on top, then the striped bucket
            val corn = Color(0xFFFFE9A8)
            for ((x, y) in listOf(0.3f to 0.25f, 0.5f to 0.18f, 0.7f to 0.25f, 0.4f to 0.34f, 0.6f to 0.34f)) drawCircle(corn, radius = w * 0.14f, center = androidx.compose.ui.geometry.Offset(x * w, y * h))
            val bucket = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.18f, h * 0.38f); lineTo(w * 0.82f, h * 0.38f); lineTo(w * 0.72f, h); lineTo(w * 0.28f, h); close()
            }
            drawPath(bucket, Color(0xFFFA320A))
            drawLine(Color.White, androidx.compose.ui.geometry.Offset(w * 0.41f, h * 0.4f), androidx.compose.ui.geometry.Offset(w * 0.44f, h * 0.98f), strokeWidth = w * 0.09f)
            drawLine(Color.White, androidx.compose.ui.geometry.Offset(w * 0.59f, h * 0.4f), androidx.compose.ui.geometry.Offset(w * 0.56f, h * 0.98f), strokeWidth = w * 0.09f)
        }
        "imdb" -> Box(
            Modifier.height(size).background(Color(0xFFF5C518), RoundedCornerShape(Dx.RSmall)).padding(horizontal = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("IMDb", fontSize = (size.value * 0.62f).sp, fontWeight = FontWeight.Black, color = Color.Black, maxLines = 1, lineHeight = (size.value * 0.7f).sp)
        }
        "metacritic" -> Box(
            Modifier.size(size).background(
                when { pct == null -> Color.Gray; pct >= 61f -> Color(0xFF00CE7A); pct >= 40f -> Color(0xFFFFBD3F); else -> Color(0xFFFF6874) },
                RoundedCornerShape(Dx.RSmall),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text("M", fontSize = (size.value * 0.62f).sp, fontWeight = FontWeight.Black, color = Color.Black, lineHeight = (size.value * 0.7f).sp)
        }
        // Letterboxd's three dots
        "letterboxd" -> Row(Modifier.height(size), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(-(size.value * 0.12f).dp)) {
            listOf(Color(0xFFFF8000), Color(0xFF00E054), Color(0xFF40BCF4)).forEach { c -> Box(Modifier.size(size * 0.62f).background(c, CircleShape)) }
        }
        // TMDB's and Trakt's, as small badges in their colours
        "tmdb" -> Box(Modifier.height(size).background(Color(0xFF0D253F), RoundedCornerShape(Dx.RSmall)).padding(horizontal = 3.dp), contentAlignment = Alignment.Center) {
            Text("TMDB", fontSize = (size.value * 0.55f).sp, fontWeight = FontWeight.Black, color = Color(0xFF90CEA1), maxLines = 1, lineHeight = (size.value * 0.65f).sp)
        }
        "trakt" -> Box(Modifier.size(size).background(Color(0xFFED1C24), CircleShape), contentAlignment = Alignment.Center) {
            Text("t", fontSize = (size.value * 0.62f).sp, fontWeight = FontWeight.Black, color = Color.White, lineHeight = (size.value * 0.7f).sp)
        }
        else -> Text(source.take(4), fontSize = (size.value * 0.7f).sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.8f))
    }
}

/** A score as its mark and its value ("🍅 92%" drawn properly) */
@Composable
fun ScoreChip(source: String, value: String, size: androidx.compose.ui.unit.Dp = 14.dp, fontSize: androidx.compose.ui.unit.TextUnit = 13.sp) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        ScoreMark(source, value, size)
        Text(value, fontSize = fontSize, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
    }
}

/** "126 min" → "2 hr 6 min", as Apple TV says it (anything else as it was) */
fun runtimeText(runtime: String?): String? {
    if (runtime == null) return null
    val m = Regex("""^\s*(\d+)\s*min""").find(runtime)?.groupValues?.get(1)?.toIntOrNull() ?: return runtime
    if (m < 60) return "$m min"
    return if (m % 60 == 0) "${m / 60} hr" else "${m / 60} hr ${m % 60} min"
}

/** What TMDB says of a title for its page (Stremio.facts): the age rating, where it's from, its languages, who made it */
data class TitleFacts(val rating: String?, val countries: List<String>, val language: String?, val spoken: List<String>, val studio: String?) {
    companion object {
        fun from(o: org.json.JSONObject?): TitleFacts? {
            if (o == null) return null
            fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } } }.orEmpty()
            fun text(k: String) = o.optString(k).takeIf { it.isNotBlank() && it != "null" }
            return TitleFacts(text("rating"), list("countries"), text("language"), list("spoken"), text("studio"))
        }
    }
}
