package com.notdxbi.dxbi.tv.lumen.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.notdxbi.dxbi.tv.lumen.Dx
import com.notdxbi.dxbi.tv.lumen.ProgressLine
import lumos.iptv.EpgProgramme
import lumos.iptv.FixtureStatus
import lumos.iptv.SportsFixture
import lumos.iptv.StreamQuality

/**
 * Top Apple TV / Lumen glass hero preview header rendered on `TV_GUIDE` and `SPORTS`.
 *
 * Displays status badge, channel/broadcaster pill, bold title, time window or spoiler-respecting score,
 * 2-line synopsis, quality/catch-up pills on the left, and a scrimmed 16:9 artwork pane on the right
 * (reserving top-right clearance when [miniPlayerActive] is true).
 */
@Composable
fun LiveHeroHeader(
    selection: HeroSelection,
    hideSportsScores: Boolean,
    nowMs: Long,
    miniPlayerActive: Boolean = false,
    onArtworkError: (channelId: String?, programme: EpgProgramme) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    /** The small screen of what's playing, top left (the programme's picture stays top right) */
    leading: (@Composable () -> Unit)? = null,
) {
    val textStart = if (leading != null) MINI_W + 24.dp + 20.dp else 0.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(150.dp)
            .background(LivePalette.HeroBg),
    ) {
        // the words and picture fade across as the remote moves (the same programme updates in place)
        androidx.compose.animation.AnimatedContent(
            targetState = selection,
            contentKey = { heroKey(it) },
            transitionSpec = {
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(240, delayMillis = 40, easing = Dx.Ease)) togetherWith
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160, easing = Dx.Ease))
            },
            label = "hero",
            modifier = Modifier.fillMaxSize(),
        ) { selection ->
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            when (selection) {
                is HeroSelection.Programme -> {
                    val prog = selection.programme
                    val row = selection.row
                    val artUrl = selection.enrichment?.backdrop?.takeIf { it.isNotBlank() }
                        ?: prog?.iconUrl?.takeIf { it.isNotBlank() }
                    HeroArtworkPane(
                        artworkUrl = artUrl,
                        fallbackLabel = row.guideChannel.name,
                        fallbackLogo = row.logoUrl,
                        onError = {
                            if (prog != null) {
                                onArtworkError(row.linkedChannel?.id ?: row.guideChannel.id, prog)
                            }
                        },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                    ProgrammeHeroContent(
                        channelName = row.guideChannel.name,
                        channelNumber = row.guideChannel.number,
                        logoUrl = row.logoUrl,
                        hasCatchup = row.hasCatchup,
                        isPlayable = row.isPlayable,
                        quality = row.bestStream?.quality,
                        programme = prog,
                        descriptionOverride = selection.enrichment?.description,
                        nowMs = nowMs,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = textStart)
                            .fillMaxWidth(0.6f),
                    )
                }

                is HeroSelection.Channel -> {
                    val ch = selection.channel
                    val prog = selection.nowProgramme
                    val artUrl = selection.enrichment?.backdrop?.takeIf { it.isNotBlank() }
                        ?: prog?.iconUrl?.takeIf { it.isNotBlank() }
                    HeroArtworkPane(
                        artworkUrl = artUrl,
                        fallbackLabel = ch.name,
                        fallbackLogo = ch.logoUrl,
                        onError = {
                            if (prog != null) {
                                onArtworkError(ch.id, prog)
                            }
                        },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                    ProgrammeHeroContent(
                        channelName = ch.name,
                        channelNumber = ch.number,
                        logoUrl = ch.logoUrl,
                        hasCatchup = ch.hasCatchup,
                        isPlayable = ch.streams.isNotEmpty(),
                        quality = ch.streams.firstOrNull()?.quality,
                        programme = prog,
                        descriptionOverride = selection.enrichment?.description,
                        nextFallback = selection.nextProgramme,
                        nowMs = nowMs,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = textStart)
                            .fillMaxWidth(0.6f),
                    )
                }

                is HeroSelection.Fixture -> {
                    FixtureHeroArtwork(
                        fixture = selection.fixture,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                    FixtureHeroContent(
                        selection = selection,
                        hideSportsScores = hideSportsScores,
                        nowMs = nowMs,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = textStart)
                            .fillMaxWidth(0.6f),
                    )
                }

                is HeroSelection.Empty -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = textStart)
                            .padding(horizontal = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = selection.title,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                        Text(
                            text = selection.subtitle,
                            fontSize = 14.sp,
                            color = Dx.Muted,
                        )
                    }
                }
            }
        }
        }

        if (leading != null) {
            Box(Modifier.align(Alignment.CenterStart).padding(start = 24.dp)) { leading() }
        }
    }
}

/** The mini player's size in the header */
internal val MINI_W = 204.dp
internal val MINI_H = 115.dp

private fun heroKey(sel: HeroSelection): String = when (sel) {
    is HeroSelection.Programme -> "p:${sel.row.guideChannel.id}:${sel.programme?.startMs}"
    is HeroSelection.Channel -> "c:${sel.channel.id}:${sel.nowProgramme?.startMs}"
    is HeroSelection.Fixture -> "f:${sel.fixture.id}"
    is HeroSelection.Empty -> "e:${sel.title}"
}

@Composable
private fun ProgrammeHeroContent(
    channelName: String,
    channelNumber: Int?,
    logoUrl: String?,
    hasCatchup: Boolean,
    isPlayable: Boolean,
    quality: StreamQuality?,
    programme: EpgProgramme?,
    descriptionOverride: String?,
    nowMs: Long,
    nextFallback: EpgProgramme? = null,
    modifier: Modifier = Modifier,
) {
    val (statusLabel, statusBg) = when {
        programme == null -> if (isPlayable) "LIVE TV" to Dx.Blue else "TV GUIDE" to Color.White.copy(alpha = 0.16f)
        programme.isOnAt(nowMs) -> if (programme.isLiveSport) "LIVE SPORT" to LivePalette.LiveRed else "ON NOW" to Dx.Blue
        programme.endMs <= nowMs -> if (hasCatchup) "CATCH-UP" to Dx.Amber.copy(alpha = 0.9f) else "AIRED" to Color.White.copy(alpha = 0.16f)
        else -> "UP NEXT" to Color(0xFF33415C)
    }
    val statusTextColor = if (statusLabel == "CATCH-UP") Color.Black else Color.White

    val qualityText = formatCompactQuality(quality)
    val title = programme?.title ?: channelName
    val windowLine = when {
        programme != null -> buildString {
            append(LiveTimeFormat.formatRange12h(programme.startMs, programme.endMs))
            programme.subtitle?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
            if (programme.isOnAt(nowMs)) {
                append(" · ${programme.remainingMinutesAt(nowMs)}m left")
            }
        }
        nextFallback != null -> "Next: ${LiveTimeFormat.formatTime12h(nextFallback.startMs)} · ${nextFallback.title}"
        else -> if (isPlayable) "Press OK to watch live" else "No IPTV stream linked"
    }
    val synopsis = descriptionOverride?.takeIf { it.isNotBlank() }
        ?: programme?.description?.takeIf { it.isNotBlank() }
        ?: nextFallback?.let { "Up next: ${it.title}" }
        ?: if (isPlayable) "Live broadcast on $channelName" else "Link an IPTV source in Settings to watch $channelName"

    Column(
        modifier = modifier.padding(horizontal = 24.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // the channel's logo, nothing else on the line (its name, number and "on now" were words round a picture)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val live = programme?.isOnAt(nowMs) == true
            if (live) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(LivePalette.LiveRed))
            }
            if (!logoUrl.isNullOrBlank()) {
                LandscapeChannelLogo(logoUrl = logoUrl, name = channelName, width = 112.dp, height = 28.dp, plain = true)
            } else {
                Text(channelName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
            }
            if (hasCatchup && statusLabel != "CATCH-UP") Text("Catch-up", fontSize = 13.sp, color = Dx.Amber)
        }

        Text(
            text = title,
            fontSize = 24.sp,
            letterSpacing = (-0.3).sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = windowLine,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (programme != null && programme.isOnAt(nowMs)) {
                // white, as the rest of Lumen's progress (no blue)
                Box(Modifier.width(100.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.22f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(programme.progressAt(nowMs).coerceIn(0f, 1f)).background(Color.White))
                }
            }
        }

        Text(
            text = synopsis,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = Dx.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FixtureHeroContent(
    selection: HeroSelection.Fixture,
    hideSportsScores: Boolean,
    nowMs: Long,
    modifier: Modifier = Modifier,
) {
    val fixture = selection.fixture
    val topStream = selection.resolvedStreams.firstOrNull()

    val (badgeLabel, badgeColor) = when (fixture.status) {
        FixtureStatus.LIVE -> "LIVE MATCH" to LivePalette.LiveRed
        FixtureStatus.FINAL -> "FINAL" to Color(0xFF33415C)
        FixtureStatus.UPCOMING -> "UPCOMING" to Dx.Blue
    }
    val broadcaster = fixture.ukChannelName?.takeIf { it.isNotBlank() }
        ?: topStream?.matchedLabel
        ?: fixture.broadcasts.firstOrNull()
        ?: "No broadcast linked"

    val metaLine = buildString {
        append(fixture.leagueName)
        when (fixture.status) {
            FixtureStatus.LIVE -> {
                if (hideSportsScores) {
                    append(" · Score Hidden")
                } else {
                    val hs = fixture.home?.score
                    val aws = fixture.away?.score
                    if (!hs.isNullOrBlank() && !aws.isNullOrBlank()) {
                        append(" · $hs - $aws")
                    }
                    (fixture.clock ?: fixture.statusDetail)?.takeIf { it.isNotBlank() }?.let {
                        append(" ($it)")
                    }
                }
            }
            FixtureStatus.FINAL -> {
                if (hideSportsScores) {
                    append(" · Score Hidden")
                } else {
                    val hs = fixture.home?.score
                    val aws = fixture.away?.score
                    if (!hs.isNullOrBlank() && !aws.isNullOrBlank()) {
                        append(" · $hs - $aws")
                    }
                    fixture.statusDetail?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                }
            }
            FixtureStatus.UPCOMING -> {
                append(" · ${LiveTimeFormat.formatFixtureWhen(fixture.startMs, nowMs)}")
            }
        }
    }

    val descLine = buildString {
        fixture.venue?.takeIf { it.isNotBlank() }?.let { append("$it · ") }
        append("Broadcast: ")
        val ukName = fixture.ukChannelName?.takeIf { it.isNotBlank() }
        val allBroadcasts = if (ukName != null) {
            listOf(ukName)
        } else {
            fixture.broadcasts.distinct()
        }
        if (allBroadcasts.isNotEmpty()) {
            append(allBroadcasts.take(3).joinToString(", "))
        } else {
            append("Check Live Events")
        }
    }

    Column(
        modifier = modifier.padding(horizontal = 24.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HeroPillBadge(text = badgeLabel, containerColor = badgeColor)
            HeroPillBadge(
                text = broadcaster,
                containerColor = Color.White.copy(alpha = 0.12f),
            )
            topStream?.stream?.quality?.let { q ->
                formatCompactQuality(q)?.let { badge ->
                    HeroPillBadge(
                        text = badge,
                        containerColor = Dx.Blue.copy(alpha = 0.25f),
                        contentColor = Color.White,
                    )
                }
            }
        }

        Text(
            text = fixture.name,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Text(
            text = metaLine,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.88f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Text(
            text = descLine,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = Dx.Muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HeroArtworkPane(
    artworkUrl: String?,
    fallbackLabel: String,
    fallbackLogo: String?,
    onError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var artworkFailed by remember(artworkUrl) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth(0.48f)
            .fillMaxHeight(),
    ) {
        if (!artworkUrl.isNullOrBlank() && !artworkFailed) {
            AsyncImage(
                model = artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = {
                    artworkFailed = true
                    onError()
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color(0xFF1F222A), LivePalette.HeroBg),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .border(1.dp, LivePalette.GlassBorder, RoundedCornerShape(18.dp))
                        .padding(horizontal = 24.dp, vertical = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    LandscapeChannelLogo(
                        logoUrl = fallbackLogo,
                        name = fallbackLabel,
                        width = 132.dp,
                        height = 68.dp,
                        corner = 12.dp,
                    )
                }
            }
        }

        HeroScrimOverlays()
    }
}

@Composable
private fun FixtureHeroArtwork(
    fixture: SportsFixture,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth(0.46f)
            .fillMaxHeight()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF1C2029), LivePalette.HeroBg),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        val home = fixture.home
        val away = fixture.away
        if (home != null && away != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                modifier = Modifier.padding(start = 48.dp),
            ) {
                CrestBadge(
                    logoUrl = home.logoUrl,
                    name = home.shortName ?: home.name,
                    size = 72.dp,
                    shape = RoundedCornerShape(18.dp),
                )
                Text(
                    text = "VS",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White.copy(alpha = 0.55f),
                )
                CrestBadge(
                    logoUrl = away.logoUrl,
                    name = away.shortName ?: away.name,
                    size = 72.dp,
                    shape = RoundedCornerShape(18.dp),
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(LivePalette.GlassFill)
                    .border(1.dp, LivePalette.GlassBorder, RoundedCornerShape(16.dp))
                    .padding(horizontal = 24.dp, vertical = 14.dp),
            ) {
                Text(
                    text = fixture.sport.uppercase(),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                )
            }
        }
        HeroScrimOverlays()
    }
}

@Composable
private fun HeroScrimOverlays() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.horizontalGradient(
                    0f to LivePalette.HeroBg,
                    0.28f to LivePalette.HeroBg.copy(alpha = 0.72f),
                    0.75f to Color.Transparent,
                ),
            ),
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.7f to Color.Transparent,
                    1f to LivePalette.HeroBg.copy(alpha = 0.92f),
                ),
            ),
    )
}

internal fun formatCompactQuality(quality: StreamQuality?): String? {
    if (quality == null) return null
    val base = when {
        quality.height >= 2160 -> "4K"
        quality.height >= 1080 -> "1080p"
        quality.height >= 720 -> "720p"
        quality.height > 0 -> "SD"
        else -> null
    } ?: return null
    val fpsPart = quality.fps?.takeIf { it >= 50 }?.toString().orEmpty()
    val hdrPart = if (quality.hdr) " HDR" else ""
    return "$base$fpsPart$hdrPart"
}
