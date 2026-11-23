package com.notdxbi.dxbi.tv.lumen.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.notdxbi.dxbi.tv.live.LiveTvStore
import com.notdxbi.dxbi.tv.live.QrSetupPairing
import com.notdxbi.dxbi.tv.lumen.Dx
import com.notdxbi.dxbi.tv.lumen.PageWindow
import com.notdxbi.dxbi.tv.lumen.Pill
import com.notdxbi.dxbi.tv.phone.Dates
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lumos.iptv.IptvSource
import lumos.iptv.SourceLoadReport
import java.util.Date

/**
 * Full-height Apple TV frosted glass Live TV settings screen managing spoiler-free mode,
 * non-UK channels, guide refresh, QR source pairing, and connected provider health.
 */
@Composable
fun LiveSettingsView(
    store: LiveTvStore,
    miniPlayerActive: Boolean,
    contentFocusRequester: FocusRequester,
    onMoveToRail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val uiState by store.uiState.collectAsState()
    val prefs by store.prefs.collectAsState()
    val sources by store.sources.collectAsState()
    val reports by store.sourceReports.collectAsState()
    val channels by store.channels.collectAsState()
    val catchupCount by store.catchupCount.collectAsState()
    val pairing by store.setupPairing.collectAsState()

    val reportById = remember(reports) { reports.associateBy { it.sourceId } }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(end = if (miniPlayerActive) 316.dp else 0.dp),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "set:title") {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Live TV Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(
                    "Preferences, EPG guide sync, and connected IPTV sources",
                    fontSize = 13.sp,
                    color = Dx.Muted,
                )
            }
        }

        item(key = "set:hdr:prefs") {
            LiveSectionHeading("Playback & Guide Preferences")
        }

        item(key = "set:spoiler") {
            SettingsToggleRow(
                title = "Hide Sports Scores (Spoiler-Free)",
                subtitle = "Mask live and final scores across hero previews, Sports fixtures, and saved team schedules.",
                checked = prefs.hideSportsScores,
                onClick = { store.setHideSportsScores(!prefs.hideSportsScores) },
                onMoveToRail = onMoveToRail,
                modifier = Modifier.focusRequester(contentFocusRequester),
            )
        }

        item(key = "set:nonuk") {
            SettingsToggleRow(
                title = "Show Non-UK Channels",
                subtitle = "Include international groups in the channel lineup alongside UK & US Sports.",
                checked = prefs.showNonUk,
                onClick = { store.setShowNonUk(!prefs.showNonUk) },
                onMoveToRail = onMoveToRail,
            )
        }

        item(key = "set:hdr:sync") {
            LiveSectionHeading("Guide & Sources", "${channels.size} channels · $catchupCount catch-up")
        }

        item(key = "set:refresh") {
            val epgStamp = if (uiState.epgUpdatedAtMs > 0L) {
                "Last updated ${Dates.formatShortDay(uiState.epgUpdatedAtMs)} ${Dates.formatTime(Date(uiState.epgUpdatedAtMs))}"
            } else {
                "Fetch latest UK EPG, provider listings, and playlists"
            }
            SettingsActionRow(
                title = if (uiState.refreshing) "Refreshing Guide…" else "Refresh Guide",
                subtitle = epgStamp,
                actionLabel = if (uiState.refreshing) "Syncing" else "Refresh Now",
                accent = Dx.Blue,
                icon = Icons.Filled.Refresh,
                onMoveToRail = onMoveToRail,
                onClick = { store.refreshAll(forceEpg = true) },
            )
        }

        item(key = "set:qr") {
            SettingsActionRow(
                title = "Add IPTV Source (QR)",
                subtitle = if (pairing != null) "Pairing active — scan QR with code ${pairing!!.code}" else "Scan a QR code with your phone to add Xtream, M3U, or Stalker sources.",
                actionLabel = if (pairing != null) "Close QR" else "Pair Phone",
                accent = Dx.Blue,
                icon = Icons.Filled.QrCode2,
                onMoveToRail = onMoveToRail,
                onClick = { if (pairing != null) store.stopQrSetup() else store.startQrSetup() },
            )
        }

        item(key = "set:hdr:sources") {
            LiveSectionHeading(
                title = "Connected Sources",
                subtitle = if (sources.isEmpty()) "None configured" else "${sources.count { it.enabled }}/${sources.size} enabled",
            )
        }

        if (sources.isEmpty()) {
            item(key = "set:sources:empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(LivePalette.GlassFill, RoundedCornerShape(14.dp))
                        .border(1.dp, LivePalette.GlassBorder, RoundedCornerShape(14.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Text(
                        text = "No IPTV sources added yet. Use Add IPTV Source (QR) above to link Xtream Codes, M3U, or Stalker portals.",
                        fontSize = 13.sp,
                        color = Dx.Muted,
                    )
                }
            }
        } else {
            items(sources, key = { "src:${it.id}" }) { source ->
                SourceHealthRow(
                    source = source,
                    report = reportById[source.id],
                    onMoveToRail = onMoveToRail,
                    onToggleEnabled = {
                        scope.launch { store.toggleSource(source.id, !source.enabled) }
                    },
                    onRemove = {
                        scope.launch { store.removeSource(source.id) }
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onClick: () -> Unit,
    onMoveToRail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassTile(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft) {
                    onMoveToRail()
                    true
                } else false
            },
        shape = RoundedCornerShape(14.dp),
        fillColor = LivePalette.GlassFill,
        focusedFillColor = Color.White,
        borderColor = LivePalette.GlassBorder,
        focusedScale = 1.015f,
    ) { focused, ink ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ink)
                Text(subtitle, fontSize = 12.sp, color = ink.copy(alpha = 0.65f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            HeroPillBadge(
                text = if (checked) "ON" else "OFF",
                containerColor = when {
                    checked && focused -> LivePalette.LiveGreen
                    checked -> LivePalette.LiveGreen.copy(alpha = 0.28f)
                    focused -> Color.Black.copy(alpha = 0.12f)
                    else -> Color.White.copy(alpha = 0.12f)
                },
                contentColor = when {
                    checked && focused -> Color.Black
                    checked -> LivePalette.LiveGreen
                    else -> ink.copy(alpha = 0.72f)
                },
            )
        }
    }
}

@Composable
private fun SettingsActionRow(
    title: String,
    subtitle: String,
    actionLabel: String,
    accent: Color,
    icon: ImageVector,
    onMoveToRail: () -> Unit,
    onClick: () -> Unit,
) {
    GlassTile(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft) {
                    onMoveToRail()
                    true
                } else false
            },
        shape = RoundedCornerShape(14.dp),
        fillColor = LivePalette.GlassFill,
        focusedFillColor = Color.White,
        borderColor = LivePalette.GlassBorder,
        focusedScale = 1.015f,
    ) { focused, ink ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ink)
                Text(subtitle, fontSize = 12.sp, color = ink.copy(alpha = 0.65f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            HeroPillBadge(
                text = actionLabel,
                containerColor = if (focused) Color.Black.copy(alpha = 0.12f) else accent.copy(alpha = 0.25f),
                contentColor = ink,
            )
        }
    }
}

@Composable
private fun SourceHealthRow(
    source: IptvSource,
    report: SourceLoadReport?,
    onMoveToRail: () -> Unit,
    onToggleEnabled: () -> Unit,
    onRemove: () -> Unit,
) {
    val kind = when (source) {
        is IptvSource.Xtream -> "Xtream"
        is IptvSource.M3u -> "M3U"
        is IptvSource.Stalker -> "Stalker"
    }
    val (statusLabel, statusBg, statusInk) = when {
        !source.enabled -> Triple("Disabled", Color.White.copy(alpha = 0.1f), Dx.Muted)
        report == null -> Triple("Pending", Dx.Blue.copy(alpha = 0.2f), Color.White)
        report.success -> Triple("${report.streamCount} streams", LivePalette.LiveGreen.copy(alpha = 0.24f), LivePalette.LiveGreen)
        else -> Triple("Error", LivePalette.LiveRed.copy(alpha = 0.25f), Color(0xFFFF7A7E))
    }
    val detail = buildString {
        append("$kind · max ${source.maxConnections} conn")
        report?.accountInfo?.let { info ->
            if (info.status.isNotBlank()) append(" · ${info.status}")
            append(" · ${info.activeConnections}/${info.maxConnections} active")
        }
        report?.error?.takeIf { !report.success && it.isNotBlank() }?.let { err ->
            append(" · $err")
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassTile(
            onClick = onToggleEnabled,
            modifier = Modifier
                .weight(1f)
                .onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft) {
                        onMoveToRail()
                        true
                    } else false
                },
            shape = RoundedCornerShape(14.dp),
            fillColor = LivePalette.GlassFill,
            focusedFillColor = Color.White,
            borderColor = LivePalette.GlassBorder,
            focusedScale = 1.015f,
        ) { focused, ink ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(source.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(detail, fontSize = 12.sp, color = ink.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                HeroPillBadge(
                    text = statusLabel,
                    containerColor = if (focused) Color.Black.copy(alpha = 0.1f) else statusBg,
                    contentColor = if (focused) Color.Black else statusInk,
                )
            }
        }

        Surface(
            onClick = onRemove,
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = LivePalette.GlassFill,
                contentColor = Color(0xFFFF7A7E),
                focusedContainerColor = Color.White,
                focusedContentColor = Color.Black,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        ) {
            Box(Modifier.padding(12.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = "Remove source", modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Modal dialog displaying the phone QR pairing code for adding IPTV sources. */
@Composable
internal fun QrPairingModal(
    pairing: QrSetupPairing,
    onClose: () -> Unit,
) {
    PageWindow(onBack = onClose, dim = 0.96f, fadeIn = false, solid = true) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            QrSetupCard(pairing = pairing, onClose = onClose)
        }
    }
}

/** QR setup card displaying the pairing code, setup URL, and scannable QR image. */
@Composable
internal fun QrSetupCard(
    pairing: QrSetupPairing,
    onClose: () -> Unit,
) {
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { closeFocus.requestFocus() }
    }
    // Apple TV's sign-in screen: what to do on the left, the code to scan on the right
    Row(
        modifier = Modifier
            .width(860.dp)
            .padding(24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(64.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Add your TV provider", fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp, color = Color.White)
            Text(
                "Use your phone. Paste the whole message your provider sent you and Lumen picks out the details.",
                fontSize = 15.sp, lineHeight = 22.sp, color = Dx.Muted,
            )
            Spacer(Modifier.height(8.dp))
            listOf(
                "Point your phone's camera at the code.",
                "Paste the login message into the box.",
                "Check what Lumen found and tap Send to TV. This TV adds it by itself.",
            ).forEachIndexed { i, step ->
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(
                        Modifier.size(26.dp).background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(50)),
                        contentAlignment = Alignment.Center,
                    ) { Text("${i + 1}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                    Text(step, fontSize = 16.sp, lineHeight = 22.sp, color = Color.White.copy(alpha = 0.85f))
                }
            }
            Spacer(Modifier.height(10.dp))
            Pill(
                text = "Cancel",
                selected = false,
                modifier = Modifier.focusRequester(closeFocus),
                onClick = onClose,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .background(Color.White, RoundedCornerShape(20.dp))
                    .padding(16.dp),
            ) {
                AsyncImage(
                    model = pairing.qrImageUrl,
                    contentDescription = "QR code to add a TV provider",
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Text("or type this code on the setup page", fontSize = 13.sp, color = Dx.Muted)
            Text(pairing.code, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp, color = Color.White)
            Text("Waiting for your phone…", fontSize = 13.sp, color = Dx.Muted)
        }
    }
}
