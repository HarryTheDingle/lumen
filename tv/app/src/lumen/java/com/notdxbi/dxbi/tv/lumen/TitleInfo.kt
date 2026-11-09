package com.notdxbi.dxbi.tv.lumen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
    buttonsModifier: Modifier = Modifier,
    buttons: @Composable RowScope.() -> Unit,
) {
    var full by remember { mutableStateOf(false) }
    val info: @Composable (Title) -> Unit = { t ->
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TitleLogo(t, maxHeight = 110.dp, maxWidth = 380.dp, textSize = 40.sp, modifier = Modifier.padding(bottom = 4.dp))
            Text(kindLine(t), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.85f))
            if (detailed) {
                val lead = episode?.let(::episodeLead)?.takeIf { episode.overview != null }
                val body = if (lead != null) episode.overview else t.description
                if (body != null) Synopsis(lead, body, onMore = { full = true })
                Facts(t, reviews, badges)
            } else {
                t.description?.let {
                    Text(it, fontSize = 14.sp, color = Dx.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
                }
                status(t)?.let { Text(it, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White) }
            }
        }
    }
    Column(modifier.widthIn(max = 470.dp)) {
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
    return "S${v.season}, E${v.episode} · ${v.title}:"
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
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                    focusedContainerColor = Color.White,
                    focusedContentColor = Color.Black,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
            ) {
                Text("MORE", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
    }
}

/** The whole text, over the page; Back closes it */
@Composable
private fun FullText(heading: String, body: String, onDismiss: () -> Unit) {
    PageWindow(onBack = onDismiss, dim = 0.7f, fadeIn = false) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(620.dp).background(Color(0xFF181818), RoundedCornerShape(22.dp)).padding(28.dp),
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
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White.copy(alpha = 0.85f),
        maxLines = 1,
        modifier = Modifier
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** Play (or Resume): white, dark text */
@Composable
fun PlayButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            // Apple TV's: dark glass at rest, a soft white when the remote's on it
            containerColor = Color.Black.copy(alpha = 0.42f),
            contentColor = Color.White,
            focusedContainerColor = Color(0xFFE8E8E8),
            focusedContentColor = Color.Black,
        ),
        scale = ButtonDefaults.scale(focusedScale = 1.08f),
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The round button beside Play: +, ✓, … */
@Composable
fun RoundButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onClick,
        modifier = modifier,
        colors = IconButtonDefaults.colors(
            containerColor = Color.Black.copy(alpha = 0.42f),
            contentColor = Color.White,
            focusedContainerColor = Color(0xFFE8E8E8),
            focusedContentColor = Color.Black,
        ),
        scale = IconButtonDefaults.scale(focusedScale = 1.1f),
    ) {
        Icon(icon, contentDescription = label)
    }
}
