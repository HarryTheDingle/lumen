package com.notdxbi.dxbi.tv.lumen.live

import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.notdxbi.dxbi.tv.lumen.Dx
import com.notdxbi.dxbi.tv.lumen.GlassState
import com.notdxbi.dxbi.tv.lumen.glassEdge
import com.notdxbi.dxbi.tv.phone.Dates
import lumos.iptv.CanonicalChannel
import lumos.iptv.EpgEnrichment
import lumos.iptv.EpgProgramme
import lumos.iptv.GuideChannelRow
import lumos.iptv.ResolvedFixtureStream
import lumos.iptv.SportsFixture
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Top-level Live TV navigation sections in [LiveSideRail]. */
enum class LiveSection(val label: String) {
    SEARCH("Search"),
    TV_GUIDE("TV Guide"),
    SPORTS("Sports"),
    FAVOURITES("Favourites"),
    SETTINGS("Settings"),
}

/** Content displayed inside [LiveHeroHeader] based on current D-pad focus. */
sealed interface HeroSelection {
    data class Programme(
        val row: GuideChannelRow,
        val programme: EpgProgramme?,
        val enrichment: EpgEnrichment? = null,
    ) : HeroSelection

    data class Fixture(
        val fixture: SportsFixture,
        val resolvedStreams: List<ResolvedFixtureStream> = emptyList(),
    ) : HeroSelection

    data class Channel(
        val channel: CanonicalChannel,
        val nowProgramme: EpgProgramme?,
        val nextProgramme: EpgProgramme? = null,
        val enrichment: EpgEnrichment? = null,
    ) : HeroSelection

    data class Empty(
        val title: String,
        val subtitle: String,
    ) : HeroSelection
}

/** Apple TV / Lumen black glass colour tokens for Live TV. */
internal object LivePalette {
    val HeroBg = Dx.Background
    val RailBg = Color(0xEB141416)
    val CardBg = Dx.Raised
    val GlassFill = Color.White.copy(alpha = 0.08f)
    val GlassBorder = Color.White.copy(alpha = 0.14f)
    val CellBg = Color(0xFF151517)
    val CellLiveBg = Color(0xFF242427)
    val CellFocusedBg = Color.White
    val CellPastBg = Color(0xFF0D0D0E)
    val Hairline = Color.White.copy(alpha = 0.09f)
    val LiveRed = Color(0xFFE5484D)
    val LiveGreen = Color(0xFF30D158)
    val CrestPlate = Color(0xFFEAECEF)
    val LogoPlate = Color(0xFFD2D8E2)
}

/** Thread-safe cached formatters safe on Android 7+ (`minSdk 24`). */
internal object LiveTimeFormat {
    private fun local(pattern: String, locale: Locale = Locale.getDefault()) =
        object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() = SimpleDateFormat(pattern, locale)
        }

    private val time12h = local("h:mma", Locale.UK)
    private val time24h = local("HH:mm", Locale.UK)
    private val dayHeader = local("EEE d MMM", Locale.UK)
    private val weekdayShort = local("EEE", Locale.UK)

    fun formatTime12h(epochMs: Long): String =
        time12h.get()!!.format(Date(epochMs)).lowercase(Locale.UK)

    fun formatTime24h(epochMs: Long): String =
        time24h.get()!!.format(Date(epochMs))

    fun formatRange12h(startMs: Long, endMs: Long): String =
        "${formatTime12h(startMs)} - ${formatTime12h(endMs)}"

    fun formatDayHeader(epochMs: Long): String =
        dayHeader.get()!!.format(Date(epochMs))

    fun formatWeekdayShort(epochMs: Long): String =
        weekdayShort.get()!!.format(Date(epochMs))

    fun formatDurationMs(ms: Long): String {
        val total = (ms.coerceAtLeast(0L) / 1000L).toInt()
        val h = total / 3600
        val m = (total / 60) % 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun formatFixtureWhen(startMs: Long, nowMs: Long): String {
        val startCal = Calendar.getInstance().apply { timeInMillis = startMs }
        val nowCal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val dayDiff = dayDelta(nowCal, startCal)
        val clock = formatTime24h(startMs)
        return when (dayDiff) {
            0 -> "Today · $clock"
            1 -> "Tomorrow · $clock"
            -1 -> "Yesterday · $clock"
            else -> "${Dates.formatShortDay(startMs)} · $clock"
        }
    }

    private fun dayDelta(a: Calendar, b: Calendar): Int {
        val ca = (a.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val cb = (b.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return ((cb.timeInMillis - ca.timeInMillis) / 86_400_000L).toInt()
    }
}

/** True for TV remote D-pad Select / Enter / gamepad A key events. */
internal fun androidx.compose.ui.input.key.KeyEvent.isSelectKey(): Boolean = when (nativeKeyEvent.keyCode) {
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER,
    KeyEvent.KEYCODE_BUTTON_A -> true
    else -> false
}

/**
 * Apple TV frosted glass tile with Lumen's specular [GlassState] rim, spring focus lift,
 * deterministic D-pad click handling, and optional long-press [onLongClick] trigger.
 */
@Composable
internal fun GlassTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    fillColor: Color = LivePalette.GlassFill,
    focusedFillColor: Color = Color.White,
    borderColor: Color = LivePalette.GlassBorder,
    focusedScale: Float = 1.025f,
    /** The soft glass sheen and rim while it isn't chosen (cells in the guide, cards) */
    sheen: Boolean = false,
    onFocus: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.(focused: Boolean, ink: Color) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val lift by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = spring(dampingRatio = 1f, stiffness = 320f),
        label = "glass-tile-lift",
    )
    val glass = remember { GlassState() }
    LaunchedEffect(focused) {
        if (focused) glass.arrived()
    }

    var pressed by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    val ink = if (focused) Color.Black else Color.White

    Box(
        modifier = modifier
            .zIndex(if (focused) 1f else 0f)
            .onFocusChanged { state ->
                val isNowFocused = state.isFocused || state.hasFocus
                if (isNowFocused && !focused) {
                    onFocus?.invoke()
                }
                focused = isNowFocused
                if (!isNowFocused) {
                    pressed = false
                    held = false
                }
            }
            .graphicsLayer {
                val scale = 1f + (focusedScale - 1f) * lift
                scaleX = scale
                scaleY = scale
            }
            // (a tile asked for no edge has none until the remote's on it: plain words, as Apple TV's)
            .then(if (focused || borderColor != Color.Transparent) Modifier.glassEdge(shape, glass, tracked = focused) { lift } else Modifier)
            .background(if (focused) focusedFillColor else fillColor, shape)
            .then(if (sheen && !focused) Modifier.background(GlassSheen, shape).border(1.dp, GlassRim, shape) else Modifier)
            .then(if (focused || borderColor != Color.Transparent) Modifier.border(1.dp, if (focused) Color.White.copy(alpha = 0.65f) else borderColor, shape) else Modifier)
            .onKeyEvent { e ->
                if (!e.isSelectKey()) return@onKeyEvent false
                val native = e.nativeKeyEvent
                when (e.type) {
                    KeyEventType.KeyDown -> {
                        if (native.repeatCount == 0 && !native.isLongPress) {
                            pressed = true
                            held = false
                        } else if (onLongClick != null && !held && pressed) {
                            held = true
                            onLongClick()
                        }
                        true
                    }
                    KeyEventType.KeyUp -> {
                        val shouldClick = pressed && !held
                        pressed = false
                        held = false
                        if (shouldClick) onClick()
                        true
                    }
                    else -> false
                }
            }
            .focusable()
            .clip(shape),
    ) {
        CompositionLocalProvider(LocalContentColor provides ink) {
            content(focused, ink)
        }
    }
}

/**
 * Apple TV frosted glass pill button with Lumen's specular [GlassState] edge and spring focus lift.
 */
@Composable
internal fun GlassPillButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Dx.Blue,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(50)
    // Apple TV's words-to-choose: plain text, one soft capsule behind the chosen one, white under the remote
    val fill = if (selected) Color.White.copy(alpha = 0.16f) else Color.Transparent
    val border = Color.Transparent
    GlassTile(
        onClick = onClick,
        modifier = modifier,
        shape = shape,
        fillColor = fill,
        focusedFillColor = Color.White,
        borderColor = border,
        focusedScale = 1.05f,
    ) { focused, ink ->
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            leading?.invoke()
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (focused) Color.Black else if (selected) Color.White else ink.copy(alpha = 0.62f),
                maxLines = 1,
            )
            trailing?.invoke()
        }
    }
}

/** Builds a compact 2-4 character monogram from [name] for channels without a valid logo. */
internal fun channelMonogram(name: String): String {
    val words = name.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return "TV"
    if (words.size == 1) return words[0].take(3).uppercase()
    val joined = buildString {
        for (w in words.take(3)) {
            val first = w.firstOrNull { it.isLetterOrDigit() }
            if (first != null) append(first.uppercaseChar())
        }
    }
    return joined.ifEmpty { words[0].take(3).uppercase() }
}

/** Renders a channel or team logo with a crisp slate monogram fallback if absent or failing to load. */
@Composable
internal fun ChannelLogo(
    logoUrl: String?,
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
    corner: Dp = 8.dp,
) {
    val cleanUrl = logoUrl?.trim()?.takeIf { it.isNotEmpty() }
    var failed by remember(cleanUrl) { mutableStateOf(false) }
    val shape = RoundedCornerShape(corner)
    val paletteIdx = remember(name) { abs(name.hashCode()) % MONOGRAM_GRADIENTS.size }
    val gradient = MONOGRAM_GRADIENTS[paletteIdx]

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(
                if (cleanUrl != null && !failed) {
                    Brush.verticalGradient(listOf(LivePalette.LogoPlate.copy(alpha = 0.85f), LivePalette.LogoPlate.copy(alpha = 0.72f)))
                } else {
                    Brush.linearGradient(gradient)
                },
            )
            .border(1.dp, Color.White.copy(alpha = 0.2f), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (cleanUrl != null && !failed) {
            AsyncImage(
                model = cleanUrl,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier
                    .size(size - 6.dp)
                    .padding(1.dp),
            )
        } else {
            val fontSize = (size.value * 0.31f).coerceIn(9f, 16f).sp
            Text(
                text = channelMonogram(name),
                fontSize = fontSize,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                maxLines = 1,
            )
        }
    }
}

/**
 * Wide landscape channel logo box (`58dp x 32dp` default) with a light-tinted frosted plate
 * so wide Sky Sports / BBC wordmarks read cleanly without cropping.
 */
@Composable
internal fun LandscapeChannelLogo(
    logoUrl: String?,
    name: String,
    modifier: Modifier = Modifier,
    width: Dp = 58.dp,
    height: Dp = 32.dp,
    corner: Dp = 7.dp,
    /** No box behind it: the logo as it is (tinted white it turned any logo with a background into a white block) */
    plain: Boolean = false,
) {
    val cleanUrl = logoUrl?.trim()?.takeIf { it.isNotEmpty() }
    var failed by remember(cleanUrl) { mutableStateOf(false) }
    val shape = RoundedCornerShape(corner)
    if (plain) {
        Box(modifier.size(width = width, height = height), contentAlignment = Alignment.CenterStart) {
            if (cleanUrl != null && !failed) {
                AsyncImage(
                    model = cleanUrl,
                    contentDescription = name,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    onError = { failed = true },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(channelMonogram(name), fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1)
            }
        }
        return
    }
    val paletteIdx = remember(name) { abs(name.hashCode()) % MONOGRAM_GRADIENTS.size }
    val gradient = MONOGRAM_GRADIENTS[paletteIdx]

    Box(
        modifier = modifier
            .size(width = width, height = height)
            .clip(shape)
            .background(
                if (cleanUrl != null && !failed) {
                    Brush.verticalGradient(
                        listOf(
                            LivePalette.LogoPlate.copy(alpha = 0.9f),
                            LivePalette.LogoPlate.copy(alpha = 0.76f),
                        ),
                    )
                } else {
                    Brush.linearGradient(gradient)
                },
            )
            .border(1.dp, Color.White.copy(alpha = 0.28f), shape)
            .padding(horizontal = 5.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (cleanUrl != null && !failed) {
            AsyncImage(
                model = cleanUrl,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier.size(width = width - 10.dp, height = height - 6.dp),
            )
        } else {
            Text(
                text = channelMonogram(name),
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                maxLines = 1,
            )
        }
    }
}

/**
 * Sports team crest wrapped in a light rounded/circular plate (`#EAECEF`) so dark badges
 * such as Tottenham Hotspur stay crisp against dark glass cards.
 */
@Composable
internal fun CrestBadge(
    logoUrl: String?,
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
) {
    val cleanUrl = logoUrl?.trim()?.takeIf { it.isNotEmpty() }
    var failed by remember(cleanUrl) { mutableStateOf(false) }
    val paletteIdx = remember(name) { abs(name.hashCode()) % MONOGRAM_GRADIENTS.size }

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(
                if (cleanUrl != null && !failed) LivePalette.CrestPlate
                else MONOGRAM_GRADIENTS[paletteIdx].first(),
            )
            .border(1.dp, Color.White.copy(alpha = 0.24f), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (cleanUrl != null && !failed) {
            AsyncImage(
                model = cleanUrl,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier
                    .size(size - 6.dp)
                    .padding(1.dp),
            )
        } else {
            val fontSize = (size.value * 0.34f).coerceIn(8f, 15f).sp
            Text(
                text = channelMonogram(name),
                fontSize = fontSize,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                maxLines = 1,
            )
        }
    }
}

/** Compact pill badge used across hero, fixture cards, and guide rows. */
@Composable
internal fun HeroPillBadge(
    text: String,
    containerColor: Color,
    contentColor: Color = Color.White,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(containerColor, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = contentColor,
            maxLines = 1,
        )
    }
}

private val MONOGRAM_GRADIENTS = listOf(
    listOf(Color(0xFF1F3B66), Color(0xFF12233D)),
    listOf(Color(0xFF3E2656), Color(0xFF211430)),
    listOf(Color(0xFF1E4B45), Color(0xFF102A26)),
    listOf(Color(0xFF532B33), Color(0xFF2E171C)),
    listOf(Color(0xFF2F384A), Color(0xFF1A202B)),
)

/** Compact frosted glass chip pill for guide filters, day presets, and sport selectors. */
@Composable
internal fun LiveChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    GlassPillButton(
        label = label,
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        leading = leading,
        trailing = trailing,
    )
}

/** Section header text used across Live TV views. */
@Composable
internal fun LiveSectionHeading(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = Dx.Muted,
                maxLines = 1,
            )
        }
    }
}

/** The one surface for cards, panels and rails over the picture */
internal val LiveSurface = Color(0xF2141418)

/** The one duration for overlays coming and going */
internal const val LIVE_MS = 200

/** Glass, cheap: a soft white sheen from the top and a rim that's brightest at the top edge (no blur, so it costs nothing on a Mi Box) */
internal val GlassSheen = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.03f)))
internal val GlassRim = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.05f)))

/** A card or panel over the picture: the dark surface with the glass sheen and rim on it */
internal fun Modifier.liveGlass(shape: Shape = RoundedCornerShape(22.dp)): Modifier =
    this.background(LiveSurface, shape).background(GlassSheen, shape).border(1.dp, GlassRim, shape)
