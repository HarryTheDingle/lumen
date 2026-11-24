package com.notdxbi.dxbi.tv.lumen.live

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import com.notdxbi.dxbi.tv.live.LiveMiniPlayer
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.notdxbi.dxbi.tv.live.LiveTvStore
import com.notdxbi.dxbi.tv.lumen.Action
import com.notdxbi.dxbi.tv.lumen.ActionMenu
import com.notdxbi.dxbi.tv.lumen.Dx
import com.notdxbi.dxbi.tv.lumen.PageWindow
import com.notdxbi.dxbi.tv.lumen.Pill
import com.notdxbi.dxbi.tv.phone.Dates
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lumos.iptv.CanonicalChannel
import lumos.iptv.EpgProgramme
import lumos.iptv.GuideChannelRow
import java.util.Calendar
import java.util.Date

private enum class EpgRowFilter(val label: String) {
    ALL("All"),
    PLAYABLE("Playable"),
    CATCHUP("Catch-up"),
    ENTERTAINMENT("Entertainment"),
    SPORTS("Sports"),
    CINEMA("Cinema"),
    NEWS("News"),
    KIDS("Kids"),
}

private enum class EpgDayChip {
    NOW,
    TONIGHT,
    TOMORROW,
    DAY_2,
    DAY_3,
    DAY_4,
}

private data class ViewportSlot(
    val startMs: Long,
    val endMs: Long,
    val programme: EpgProgramme?,
    val clippedLeft: Boolean,
    val weightMinutes: Float,
) {
    val midMs: Long get() = startMs + (endMs - startMs) / 2L
}

private data class LongPressTarget(
    val row: GuideChannelRow,
    val programme: EpgProgramme?,
)

// two hours across, so every title fits (three hours cut most of them short)
private const val VIEWPORT_DURATION_MS = 2L * 3_600_000L
private const val PAST_WINDOW_MS = 24L * 3_600_000L
private const val FUTURE_WINDOW_MS = 120L * 3_600_000L
private const val HALF_HOUR_MS = 30L * 60_000L
private const val PAN_STEP_MS = 60L * 60_000L
private const val HOLD_LEFT_UNLOCK_MS = 320L
private val LEFT_CHANNEL_WIDTH = 188.dp
private val ROW_HEIGHT = 70.dp
private val TIME_HEADER_HEIGHT = 32.dp

/**
 * Deterministic 3-hour viewport-clamped EPG timeline grid shared by TV Guide and Sports Channels.
 *
 * Landing focus starts directly on Row 0's live show (`1 click OK = watch TV`), the 188dp left
 * channel column is purely visual with zero focus stops, D-pad Up/Down restores focus on the slot
 * covering [focusedTimeMs], and holding Left at the live edge unlocks 24h past scrubbing.
 */
@Composable
fun LiveEpgGrid(
    rows: List<GuideChannelRow>,
    store: LiveTvStore,
    favourites: Set<String>,
    epgVersion: Long,
    isSportsMode: Boolean,
    contentFocusRequester: FocusRequester,
    onMoveToRail: () -> Unit,
    onFocusSlot: (GuideChannelRow, EpgProgramme?) -> Unit,
    onOpenCatchup: (CanonicalChannel) -> Unit,
    modifier: Modifier = Modifier,
    /** The channel that was playing, and a number that changes each time the screen comes back (from the player): the guide goes to it */
    playingChannelId: String? = null,
    returnedKey: Long = 0L,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            nowMs = System.currentTimeMillis()
        }
    }

    val minViewportStartMs = remember(nowMs) { alignDownHalfHour(nowMs - PAST_WINDOW_MS) }
    val maxViewportStartMs = remember(nowMs) {
        (alignDownHalfHour(nowMs + FUTURE_WINDOW_MS) - VIEWPORT_DURATION_MS).coerceAtLeast(minViewportStartMs)
    }

    var viewportStartMs by remember { mutableLongStateOf(alignDownHalfHour(System.currentTimeMillis())) }
    val viewportEndMs = viewportStartMs + VIEWPORT_DURATION_MS

    var activeFilter by remember(isSportsMode) { mutableStateOf(EpgRowFilter.ALL) }
    var activeDay by remember(isSportsMode) { mutableStateOf(EpgDayChip.NOW) }
    var filterExpanded by remember { mutableStateOf(false) }
    var followLive by remember { mutableStateOf(true) }
    var pastUnlocked by remember { mutableStateOf(false) }
    var focusedRowIdx by remember { mutableIntStateOf(0) }
    var focusedTimeMs by remember { mutableLongStateOf(nowMs) }
    var focusedIsLive by remember { mutableStateOf(true) }
    var pendingFocus by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    var focusNonce by remember { mutableIntStateOf(0) }
    var didInitialFocus by remember(isSportsMode) { mutableStateOf(false) }
    var holdLeftJob by remember { mutableStateOf<Job?>(null) }
    // the filters, as icons down the side: Left from the first programme opens them (Back closes them)
    var filterRailOpen by remember { mutableStateOf(false) }
    val railRequesters = remember { EpgRowFilter.entries.associateWith { FocusRequester() } }
    fun openRail() { filterRailOpen = true }
    var longPressTarget by remember { mutableStateOf<LongPressTarget?>(null) }
    var streamPickerRow by remember { mutableStateOf<GuideChannelRow?>(null) }

    LaunchedEffect(nowMs, followLive, pastUnlocked) {
        if (followLive && activeDay == EpgDayChip.NOW && !pastUnlocked) {
            viewportStartMs = alignDownHalfHour(nowMs).coerceIn(minViewportStartMs, maxViewportStartMs)
        }
    }

    val availableFilters = remember(rows, isSportsMode) {
        if (isSportsMode) {
            listOf(EpgRowFilter.ALL, EpgRowFilter.PLAYABLE, EpgRowFilter.CATCHUP)
        } else {
            EpgRowFilter.entries.filter { filter ->
                filter == EpgRowFilter.ALL ||
                    filter == EpgRowFilter.PLAYABLE ||
                    filter == EpgRowFilter.CATCHUP ||
                    rows.any { matchesFilter(it, filter) }
            }
        }
    }

    // At the top: what's playing, favourite channels and recently watched, then every channel
    val miniSession by LiveMiniPlayer.session.collectAsState()
    val recents by store.recents.collectAsState()
    val watchingId = miniSession?.channel?.id
    val allRows = remember(rows, activeFilter) {
        if (activeFilter == EpgRowFilter.ALL) rows else rows.filter { matchesFilter(it, activeFilter) }
    }
    val sections = remember(allRows, activeFilter, favourites, recents, watchingId, isSportsMode) {
        val byChannel = allRows.filter { it.linkedChannel != null }.associateBy { it.linkedChannel!!.id }
        val out = ArrayList<Triple<String, GuideChannelRow, String?>>()
        if (activeFilter == EpgRowFilter.ALL && !isSportsMode) {
            val used = HashSet<String>()
            fun add(section: String, ids: List<String>) {
                var first = true
                for (id in ids) {
                    val r = byChannel[id] ?: continue
                    if (!used.add(id)) continue
                    out += Triple("$section:$id", r, if (first) section else null)
                    first = false
                }
            }
            watchingId?.let { add("Watching", listOf(it)) }
            add("Favourites", favourites.toList())
            add("Recently watched", recents.map { it.id }.take(4))
        }
        val pinned = out.isNotEmpty()
        allRows.forEachIndexed { i, r ->
            out += Triple("all:${r.guideChannel.id}", r, if (pinned && i == 0) "All channels" else null)
        }
        out
    }
    val filteredRows = remember(sections) { sections.map { it.second } }
    val rowKeys = remember(sections) { sections.map { it.first } }
    val zapChannelIds = remember(allRows) {
        allRows.mapNotNull { it.linkedChannel?.id }
    }

    val listState = rememberLazyListState()
    val slotRequesters = remember { mutableStateMapOf<String, FocusRequester>() }

    fun slotRequesterFor(rowId: String, slotIdx: Int): FocusRequester =
        slotRequesters.getOrPut("$rowId:$slotIdx") { FocusRequester() }

    fun slotsForRow(row: GuideChannelRow): List<ViewportSlot> {
        val progs = store.programmesForWindow(
            channel = row.linkedChannel,
            guideChannelId = row.guideChannel.id,
            fromMs = viewportStartMs,
            toMs = viewportEndMs,
        )
        return buildViewportSlots(progs, viewportStartMs, viewportEndMs)
    }

    fun requestSlotFocus(rowIdx: Int, targetTimeMs: Long) {
        val safeRow = rowIdx.coerceIn(0, (filteredRows.size - 1).coerceAtLeast(0))
        focusedRowIdx = safeRow
        focusedTimeMs = targetTimeMs
        pendingFocus = safeRow to targetTimeMs
        focusNonce++
    }

    LaunchedEffect(filteredRows.isNotEmpty(), epgVersion) {
        if (filteredRows.isEmpty()) return@LaunchedEffect
        val row = filteredRows.getOrNull(focusedRowIdx.coerceIn(0, filteredRows.lastIndex)) ?: filteredRows.first()
        val nowProg = row.linkedChannel?.let { store.nowAndNext(it, nowMs).first }
            ?: store.programmesForWindow(null, row.guideChannel.id, viewportStartMs, viewportEndMs)
                .firstOrNull { it.isOnAt(nowMs) }
        onFocusSlot(row, nowProg)
        if (!didInitialFocus) {
            delay(60)
            if (runCatching { contentFocusRequester.requestFocus() }.isSuccess) {
                didInitialFocus = true
            }
        }
    }

    // Back from the player: the guide is on the channel that was playing (zapping there moves it on from the row it was opened on)
    LaunchedEffect(returnedKey, filteredRows.size) {
        if (returnedKey == 0L || playingChannelId == null || filteredRows.isEmpty()) return@LaunchedEffect
        val idx = filteredRows.indexOfFirst { it.linkedChannel?.id == playingChannelId }
        if (idx >= 0) {
            delay(150)
            requestSlotFocus(idx, nowMs)
        }
    }

    LaunchedEffect(pendingFocus, focusNonce, viewportStartMs) {
        val target = pendingFocus ?: return@LaunchedEffect
        val (rowIdx, targetTimeMs) = target
        val row = filteredRows.getOrNull(rowIdx) ?: run {
            pendingFocus = null
            return@LaunchedEffect
        }
        val visibleIndices = listState.layoutInfo.visibleItemsInfo.map { it.index }
        if (rowIdx !in visibleIndices) {
            // (held Down: jump straight there; an animated scroll per press fell behind and lost the focus)
            listState.scrollToItem((rowIdx - 1).coerceAtLeast(0))
        }
        val slots = slotsForRow(row)
        val slotIdx = findSlotIndexForTime(slots, targetTimeMs)
        val req = slotRequesterFor(rowKeys[rowIdx], slotIdx)
        repeat(30) {
            if (runCatching { req.requestFocus() }.isSuccess) {
                pendingFocus = null
                return@LaunchedEffect
            }
            withFrameNanos { }
        }
        pendingFocus = null
    }

    LaunchedEffect(filterRailOpen) {
        if (filterRailOpen) {
            delay(80)
            runCatching { railRequesters[activeFilter]?.requestFocus() }
        }
    }
    BackHandler(enabled = filterRailOpen) {
        filterRailOpen = false
        requestSlotFocus(focusedRowIdx, if (followLive) nowMs else viewportStartMs + 30L * 60_000L)
    }

    fun jumpToDay(chip: EpgDayChip) {
        activeDay = chip
        followLive = chip == EpgDayChip.NOW
        if (chip == EpgDayChip.NOW) {
            pastUnlocked = false
        }
        val targetMs = computeDayChipTargetMs(chip, nowMs, minViewportStartMs, maxViewportStartMs)
        viewportStartMs = alignDownHalfHour(targetMs).coerceIn(minViewportStartMs, maxViewportStartMs)
        val focusT = if (chip == EpgDayChip.NOW) nowMs else viewportStartMs + 30L * 60_000L
        requestSlotFocus(focusedRowIdx, focusT)
    }

    fun panViewport(deltaMs: Long, rowIdx: Int, targetTimeMs: Long) {
        val nextStart = (viewportStartMs + deltaMs).coerceIn(minViewportStartMs, maxViewportStartMs)
        if (nextStart == viewportStartMs) return
        followLive = false
        viewportStartMs = nextStart
        requestSlotFocus(rowIdx, targetTimeMs.coerceIn(nextStart, nextStart + VIEWPORT_DURATION_MS - 1L))
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (filteredRows.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (rows.isEmpty()) "TV guide data is loading or unavailable." else "No channels match ${activeFilter.label}.",
                    fontSize = 15.sp,
                    color = Dx.Muted,
                )
            }
            return@Column
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 3-hour time header ruler
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(TIME_HEADER_HEIGHT)
                        .background(Color(0xFF0A0A0C)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .width(LEFT_CHANNEL_WIDTH)
                            .fillMaxHeight()
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = (if (activeFilter == EpgRowFilter.ALL) "" else "${activeFilter.label} · ") + "${filteredRows.size} ch · ${LiveTimeFormat.formatDayHeader(viewportStartMs)}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Dx.Muted,
                            maxLines = 1,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(LivePalette.Hairline),
                    )
                    ViewportTimeRuler(
                        viewportStartMs = viewportStartMs,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(LivePalette.Hairline),
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp),
                ) {
                    itemsIndexed(filteredRows, key = { i, _ -> rowKeys[i] }) { rowIdx, row ->
                        sections[rowIdx].third?.let { label ->
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.5f),
                                modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp),
                            )
                        }
                        val programmes = remember(row.guideChannel.id, viewportStartMs, viewportEndMs, epgVersion) {
                            store.programmesForWindow(
                                channel = row.linkedChannel,
                                guideChannelId = row.guideChannel.id,
                                fromMs = viewportStartMs,
                                toMs = viewportEndMs,
                            )
                        }
                        val slots = remember(programmes, viewportStartMs, viewportEndMs) {
                            buildViewportSlots(programmes, viewportStartMs, viewportEndMs)
                        }
                        val isFav = row.linkedChannel?.id in favourites
                        val liveSlotIdx = remember(slots, nowMs) {
                            slots.indexOfFirst { it.programme?.isOnAt(nowMs) == true }.takeIf { it >= 0 } ?: 0
                        }

                        GuideChannelViewportRow(
                            row = row,
                            rowIdx = rowIdx,
                            slots = slots,
                            liveSlotIdx = liveSlotIdx,
                            isFavourite = isFav,
                            nowMs = nowMs,
                            pastUnlocked = pastUnlocked,
                            rowCount = filteredRows.size,
                            contentFocusRequester = contentFocusRequester,
                            slotRequester = { slotIdx -> slotRequesterFor(rowKeys[rowIdx], slotIdx) },
                            onFocusSlot = { slot ->
                                focusedRowIdx = rowIdx
                                focusedTimeMs = slot.midMs
                                focusedIsLive = slot.programme?.isOnAt(System.currentTimeMillis()) == true
                                onFocusSlot(row, slot.programme)
                                slot.programme?.let { prog ->
                                    store.enrichIfMissing(row.linkedChannel?.id ?: row.guideChannel.id, prog)
                                }
                            },
                            onClickSlot = { slot ->
                                handleSlotClick(
                                    context = context,
                                    store = store,
                                    row = row,
                                    programme = slot.programme,
                                    nowMs = nowMs,
                                    zapChannelIds = zapChannelIds,
                                )
                            },
                            onLongClickSlot = { slot ->
                                longPressTarget = LongPressTarget(row = row, programme = slot.programme)
                            },
                            onStepRow = { delta ->
                                val nextRowIdx = rowIdx + delta
                                if (nextRowIdx in filteredRows.indices) {
                                    val nextRow = filteredRows[nextRowIdx]
                                    val nextSlots = slotsForRow(nextRow)
                                    // from something on now, land on what's on now in the next row too
                                    val targetMs = if (focusedIsLive) System.currentTimeMillis() else focusedTimeMs
                                    val nextSlotIdx = findSlotIndexForTime(nextSlots, targetMs)
                                    val req = slotRequesterFor(rowKeys[nextRowIdx], nextSlotIdx)
                                    focusedRowIdx = nextRowIdx
                                    if (runCatching { req.requestFocus() }.isFailure) {
                                        requestSlotFocus(nextRowIdx, targetMs)
                                    }
                                    true
                                } else {
                                    false
                                }
                            },
                            onNavigateRight = { slotIdx ->
                                if (slotIdx + 1 < slots.size) {
                                    val next = slots[slotIdx + 1]
                                    if (pastUnlocked && next.endMs > nowMs) {
                                        pastUnlocked = false
                                    }
                                    val req = slotRequesterFor(rowKeys[rowIdx], slotIdx + 1)
                                    runCatching { req.requestFocus() }
                                    true
                                } else if (viewportStartMs < maxViewportStartMs) {
                                    val nextTime = viewportEndMs + 15L * 60_000L
                                    if (pastUnlocked && nextTime > nowMs) {
                                        pastUnlocked = false
                                    }
                                    panViewport(PAN_STEP_MS, rowIdx, nextTime)
                                    true
                                } else {
                                    true
                                }
                            },
                            onNavigateLeftKeyDown = { slotIdx, repeatCount ->
                                val slot = slots[slotIdx]
                                val prevSlot = slots.getOrNull(slotIdx - 1)
                                val liveBoundary = alignDownHalfHour(nowMs)
                                val atLiveBoundary = !pastUnlocked && (
                                    (slotIdx == 0 && viewportStartMs <= liveBoundary) ||
                                        (prevSlot != null && prevSlot.endMs <= nowMs && slot.endMs > nowMs)
                                    )

                                // (held down, Left moves one programme, not on and on: press it again for the next)
                                if (!atLiveBoundary && repeatCount >= 1) true else when {
                                    atLiveBoundary -> {
                                        if (repeatCount >= 1) {
                                            holdLeftJob?.cancel()
                                            holdLeftJob = null
                                            pastUnlocked = true
                                            followLive = false
                                            if (slotIdx > 0) {
                                                val req = slotRequesterFor(rowKeys[rowIdx], slotIdx - 1)
                                                runCatching { req.requestFocus() }
                                            } else if (viewportStartMs > minViewportStartMs) {
                                                panViewport(-PAN_STEP_MS, rowIdx, viewportStartMs - 15L * 60_000L)
                                            }
                                        } else if (holdLeftJob == null) {
                                            holdLeftJob = scope.launch {
                                                delay(HOLD_LEFT_UNLOCK_MS)
                                                holdLeftJob = null
                                                pastUnlocked = true
                                                followLive = false
                                                if (slotIdx > 0) {
                                                    val req = slotRequesterFor(rowKeys[rowIdx], slotIdx - 1)
                                                    runCatching { req.requestFocus() }
                                                } else if (viewportStartMs > minViewportStartMs) {
                                                    panViewport(-PAN_STEP_MS, rowIdx, viewportStartMs - 15L * 60_000L)
                                                }
                                            }
                                        }
                                        true
                                    }
                                    slotIdx > 0 -> {
                                        val req = slotRequesterFor(rowKeys[rowIdx], slotIdx - 1)
                                        runCatching { req.requestFocus() }
                                        true
                                    }
                                    viewportStartMs > minViewportStartMs && (pastUnlocked || viewportStartMs > liveBoundary) -> {
                                        val minBound = if (pastUnlocked) minViewportStartMs else liveBoundary
                                        val nextStart = (viewportStartMs - PAN_STEP_MS).coerceAtLeast(minBound)
                                        if (nextStart < viewportStartMs) {
                                            val prevStart = viewportStartMs
                                            followLive = false
                                            viewportStartMs = nextStart
                                            requestSlotFocus(rowIdx, (prevStart - 15L * 60_000L).coerceAtLeast(nextStart))
                                        } else {
                                            openRail()
                                        }
                                        true
                                    }
                                    else -> {
                                        openRail()
                                        true
                                    }
                                }
                            },
                            onNavigateLeftKeyUp = {
                                val pendingHold = holdLeftJob
                                if (pendingHold != null) {
                                    pendingHold.cancel()
                                    holdLeftJob = null
                                    if (!pastUnlocked) {
                                        openRail()
                                    }
                                    true
                                } else {
                                    false
                                }
                            },
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(LivePalette.Hairline),
                        )
                    }
                }
            }

            // Subtle Dx.Blue playhead line strictly below the time ruler with a small dot on the header divider
            if (nowMs in viewportStartMs..viewportEndMs) {
                val fraction = ((nowMs - viewportStartMs).toFloat() / VIEWPORT_DURATION_MS.toFloat()).coerceIn(0f, 1f)
                // over the programmes, in Lumen's blue
                BoxWithConstraints(
                    modifier = Modifier
                        .zIndex(5f)
                        .fillMaxSize()
                        .padding(start = LEFT_CHANNEL_WIDTH + 1.dp),
                ) {
                    val xOffset = maxWidth * fraction
                    Box(
                        modifier = Modifier
                            .offset(x = xOffset - 3.dp, y = TIME_HEADER_HEIGHT - 3.dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Dx.Blue),
                    )
                    Box(
                        modifier = Modifier
                            .padding(top = TIME_HEADER_HEIGHT)
                            .offset(x = xOffset)
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(Dx.Blue.copy(alpha = 0.9f)),
                    )
                }
            }

            FilterRail(
                open = filterRailOpen,
                filters = availableFilters,
                active = activeFilter,
                requesters = railRequesters,
                onPick = { f ->
                    activeFilter = f
                    filterRailOpen = false
                    requestSlotFocus(0, if (followLive) nowMs else viewportStartMs + 30L * 60_000L)
                },
                onClose = {
                    filterRailOpen = false
                    requestSlotFocus(focusedRowIdx, if (followLive) nowMs else viewportStartMs + 30L * 60_000L)
                },
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
    }

    // Long-press OK frosted glass action sheet
    longPressTarget?.let { target ->
        val row = target.row
        val prog = target.programme
        val linked = row.linkedChannel
        val isFav = linked?.id in favourites
        val isPastCatchup = prog != null && prog.endMs <= nowMs && row.hasCatchup && linked != null
        val actions = buildList {
            // a programme still to come: a reminder two minutes before, anywhere in Lumen
            if (prog != null && prog.startMs > System.currentTimeMillis()) {
                val rid = "prog:${row.guideChannel.id}:${prog.startMs}"
                val on = com.notdxbi.dxbi.tv.live.LiveReminders.has(rid)
                add(Action(if (on) "Cancel reminder" else "Remind me", Icons.Filled.Notifications) {
                    com.notdxbi.dxbi.tv.live.LiveReminders.toggle(
                        context,
                        com.notdxbi.dxbi.tv.live.LiveReminders.Reminder(
                            id = rid,
                            title = prog.title,
                            startMs = prog.startMs,
                            channelId = linked?.id,
                            channelName = row.guideChannel.name,
                        ),
                    )
                })
            }
            if (isPastCatchup) {
                add(Action("Watch Catch-up: ${prog!!.title}", Icons.Filled.History) {
                    store.playCatchup(context, linked!!, prog, zapChannelIds = zapChannelIds)
                })
            }
            if (row.isPlayable) {
                add(Action("Watch Live: ${row.guideChannel.name}", Icons.Filled.PlayArrow) {
                    store.playGuideRow(context, row, zapChannelIds = zapChannelIds)
                })
            }
            if (linked != null) {
                add(
                    Action(
                        label = if (isFav) "Remove from Favourites" else "Add to Favourites",
                        icon = if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    ) {
                        store.toggleFavourite(linked.id)
                    },
                )
            }
            if (row.hasCatchup && linked != null) {
                add(Action("Browse Catch-up Archive", Icons.Filled.LiveTv) {
                    onOpenCatchup(linked)
                })
            }
            if (linked != null && linked.streams.size > 1) {
                add(Action("Choose Stream Feed (${linked.streams.size})", Icons.Filled.Tune) {
                    streamPickerRow = row
                })
            }
        }
        if (actions.isNotEmpty()) {
            ActionMenu(
                heading = prog?.title ?: row.guideChannel.name,
                actions = actions,
                onDismiss = { longPressTarget = null },
            )
        } else {
            longPressTarget = null
        }
    }

    streamPickerRow?.let { row ->
        val linked = row.linkedChannel
        if (linked != null) {
            val ranked = remember(linked.id) { store.rankedStreamsFor(linked) }
            StreamFeedPickerDialog(
                channel = linked,
                streams = ranked,
                onSelect = { stream ->
                    streamPickerRow = null
                    store.playLive(context, linked, streamId = stream.id, zapChannelIds = zapChannelIds)
                },
                onClose = { streamPickerRow = null },
            )
        } else {
            streamPickerRow = null
        }
    }
}

@Composable
private fun ViewportTimeRuler(
    viewportStartMs: Long,
    modifier: Modifier = Modifier,
) {
    val ticks = remember(viewportStartMs) {
        List((VIEWPORT_DURATION_MS / HALF_HOUR_MS).toInt()) { idx -> viewportStartMs + idx * HALF_HOUR_MS }
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ticks.forEach { tickMs ->
            Row(
                modifier = Modifier
                    .weight(30f)
                    .fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(12.dp)
                        .background(Color.White.copy(alpha = 0.16f)),
                )
                Text(
                    text = LiveTimeFormat.formatTime12h(tickMs),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = Dx.Muted,
                    modifier = Modifier.padding(start = 6.dp),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun GuideChannelViewportRow(
    row: GuideChannelRow,
    rowIdx: Int,
    slots: List<ViewportSlot>,
    liveSlotIdx: Int,
    isFavourite: Boolean,
    nowMs: Long,
    pastUnlocked: Boolean,
    rowCount: Int,
    contentFocusRequester: FocusRequester,
    slotRequester: (Int) -> FocusRequester,
    onFocusSlot: (ViewportSlot) -> Unit,
    onClickSlot: (ViewportSlot) -> Unit,
    onLongClickSlot: (ViewportSlot) -> Unit,
    onStepRow: (Int) -> Boolean,
    onNavigateRight: (Int) -> Boolean,
    onNavigateLeftKeyDown: (Int, Int) -> Boolean,
    onNavigateLeftKeyUp: () -> Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Purely visual left channel column (zero focus stops!)
        Row(
            modifier = Modifier
                .width(LEFT_CHANNEL_WIDTH)
                .fillMaxHeight()
                .padding(start = 24.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = row.guideChannel.name,
                    fontSize = 14.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    // (the whole name, over up to three lines: never cut short with dots)
                    maxLines = 3,
                    overflow = TextOverflow.Clip,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    val numberText = row.guideChannel.number?.toString()
                        ?: if (!row.isPlayable) "Unlinked" else ""
                    if (numberText.isNotEmpty()) {
                        Text(
                            text = numberText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Dx.Muted,
                            maxLines = 1,
                        )
                    }
                    if (row.hasCatchup) {
                        Icon(
                            imageVector = Icons.Filled.History,
                            contentDescription = "Catch-up available",
                            tint = Dx.Amber,
                            modifier = Modifier.size(11.dp),
                        )
                    }
                    if (isFavourite) {
                        Icon(
                            imageVector = Icons.Filled.Favorite,
                            contentDescription = "Favourite",
                            tint = Color(0xFFFF6B81),
                            modifier = Modifier.size(11.dp),
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(LivePalette.Hairline),
        )

        // Proportional 3-hour timeline track
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            slots.forEachIndexed { slotIdx, slot ->
                val prog = slot.programme
                val isLive = prog?.isOnAt(nowMs) == true || (prog == null && row.isPlayable && nowMs in slot.startMs until slot.endMs)
                val isPast = slot.endMs <= nowMs
                val isPastCatchup = isPast && row.hasCatchup && prog != null
                val isPastUnavailable = isPast && (!row.hasCatchup || prog == null)
                val isFirstEntrySlot = rowIdx == 0 && slotIdx == liveSlotIdx

                val displayTitle = when {
                    prog != null && slot.clippedLeft -> "‹ ${prog.title}"
                    prog != null -> prog.title
                    row.isPlayable -> "Live Broadcast · ${row.guideChannel.name}"
                    else -> "No programme guide"
                }
                val timeLabel = if (prog != null) {
                    LiveTimeFormat.formatRange12h(prog.startMs, prog.endMs)
                } else {
                    LiveTimeFormat.formatRange12h(slot.startMs, slot.endMs)
                }

                val cellMod = Modifier
                    .weight(slot.weightMinutes)
                    .fillMaxHeight()
                    .then(if (isFirstEntrySlot) Modifier.focusRequester(contentFocusRequester) else Modifier)
                    .focusRequester(slotRequester(slotIdx))
                    .onPreviewKeyEvent { ev ->
                        when (ev.type) {
                            KeyEventType.KeyDown -> when (ev.key) {
                                Key.DirectionDown -> {
                                    if (rowIdx + 1 < rowCount) onStepRow(+1) else true
                                }
                                Key.DirectionUp -> {
                                    if (rowIdx > 0) onStepRow(-1) else false
                                }
                                Key.DirectionRight -> onNavigateRight(slotIdx)
                                Key.DirectionLeft -> onNavigateLeftKeyDown(slotIdx, ev.nativeKeyEvent.repeatCount)
                                else -> false
                            }
                            KeyEventType.KeyUp -> when (ev.key) {
                                Key.DirectionLeft -> onNavigateLeftKeyUp()
                                else -> false
                            }
                            else -> false
                        }
                    }

                // (a sliver of a few minutes shows no words: they'd only be dots)
                val tiny = slot.weightMinutes < 10f
                ProgrammeViewportCell(
                    title = if (tiny) "" else displayTitle,
                    timeLabel = if (tiny) "" else timeLabel,
                    isLive = isLive,
                    isPastCatchup = isPastCatchup,
                    isPastUnavailable = isPastUnavailable,
                    isSport = prog?.isLiveSport == true,
                    onFocus = { onFocusSlot(slot) },
                    onClick = { onClickSlot(slot) },
                    onLongClick = { onLongClickSlot(slot) },
                    modifier = cellMod,
                )
            }
        }
    }
}

@Composable
private fun ProgrammeViewportCell(
    title: String,
    timeLabel: String,
    isLive: Boolean,
    isPastCatchup: Boolean,
    isPastUnavailable: Boolean,
    isSport: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = when {
        isLive -> LivePalette.CellLiveBg
        isPastUnavailable -> LivePalette.CellPastBg
        else -> LivePalette.CellBg
    }
    val borderColor = when {
        isPastCatchup -> Dx.Amber.copy(alpha = 0.30f)
        else -> Color.Transparent
    }

    GlassTile(
        onClick = onClick,
        onLongClick = onLongClick,
        onFocus = onFocus,
        modifier = modifier,
        shape = RoundedCornerShape(9.dp),
        fillColor = bg,
        focusedFillColor = Color.White,
        borderColor = borderColor,
        focusedScale = 1.02f,
    ) { focused, ink ->
        val textAlpha = if (!focused && isPastUnavailable) 0.45f else 1f
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (isSport && isLive) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(LivePalette.LiveRed),
                    )
                }
                Text(
                    text = title,
                    fontSize = 13.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ink.copy(alpha = textAlpha),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = if (isPastCatchup) "Catch-up · $timeLabel" else timeLabel,
                fontSize = 11.sp,
                color = ink.copy(alpha = if (focused) 0.68f else 0.58f * textAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun buildViewportSlots(
    programmes: List<EpgProgramme>,
    viewportStartMs: Long,
    viewportEndMs: Long,
): List<ViewportSlot> {
    val overlapping = programmes
        .filter { it.endMs > viewportStartMs && it.startMs < viewportEndMs }
        .sortedBy { it.startMs }

    if (overlapping.isEmpty()) {
        val chunk = 60L * 60_000L
        return List(3) { idx ->
            val s = viewportStartMs + idx * chunk
            val e = (s + chunk).coerceAtMost(viewportEndMs)
            ViewportSlot(
                startMs = s,
                endMs = e,
                programme = null,
                clippedLeft = false,
                weightMinutes = ((e - s) / 60_000f).coerceAtLeast(1f),
            )
        }
    }

    val slots = mutableListOf<ViewportSlot>()
    var cursor = viewportStartMs
    val minGapMs = 5L * 60_000L

    for (prog in overlapping) {
        val rawStart = prog.startMs.coerceAtLeast(viewportStartMs)
        val clampedEnd = prog.endMs.coerceAtMost(viewportEndMs)
        if (clampedEnd <= cursor) continue

        val effectiveStart = if (rawStart > cursor && rawStart - cursor < minGapMs) {
            cursor
        } else {
            rawStart
        }

        if (effectiveStart > cursor) {
            slots += ViewportSlot(
                startMs = cursor,
                endMs = effectiveStart,
                programme = null,
                clippedLeft = false,
                weightMinutes = ((effectiveStart - cursor) / 60_000f).coerceAtLeast(1f),
            )
        }

        val finalStart = effectiveStart.coerceAtLeast(cursor)
        if (clampedEnd > finalStart) {
            slots += ViewportSlot(
                startMs = finalStart,
                endMs = clampedEnd,
                programme = prog,
                clippedLeft = prog.startMs < viewportStartMs && finalStart == viewportStartMs,
                weightMinutes = ((clampedEnd - finalStart) / 60_000f).coerceAtLeast(1f),
            )
            cursor = clampedEnd
        }
    }

    if (viewportEndMs - cursor >= minGapMs) {
        slots += ViewportSlot(
            startMs = cursor,
            endMs = viewportEndMs,
            programme = null,
            clippedLeft = false,
            weightMinutes = ((viewportEndMs - cursor) / 60_000f).coerceAtLeast(1f),
        )
    } else if (cursor < viewportEndMs && slots.isNotEmpty()) {
        val last = slots.removeAt(slots.lastIndex)
        slots += last.copy(
            endMs = viewportEndMs,
            weightMinutes = ((viewportEndMs - last.startMs) / 60_000f).coerceAtLeast(1f),
        )
    }

    return slots
}

private fun findSlotIndexForTime(slots: List<ViewportSlot>, timeMs: Long): Int {
    if (slots.isEmpty()) return 0
    val exact = slots.indexOfFirst { timeMs in it.startMs until it.endMs }
    if (exact >= 0) return exact
    return if (timeMs < slots.first().startMs) 0 else slots.lastIndex
}

private fun handleSlotClick(
    context: android.content.Context,
    store: LiveTvStore,
    row: GuideChannelRow,
    programme: EpgProgramme?,
    nowMs: Long,
    zapChannelIds: List<String>,
) {
    val linked = row.linkedChannel
    when {
        programme == null -> store.playGuideRow(context, row, zapChannelIds = zapChannelIds)
        programme.isOnAt(nowMs) -> store.playGuideRow(context, row, zapChannelIds = zapChannelIds)
        programme.endMs <= nowMs -> {
            if (row.hasCatchup && linked != null) {
                store.playCatchup(context, linked, programme, zapChannelIds = zapChannelIds)
            } else {
                Toast.makeText(
                    context,
                    if (linked == null) "No IPTV stream linked for ${row.guideChannel.name}"
                    else "Catch-up unavailable on ${row.guideChannel.name}",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        else -> {
            val whenText = "${LiveTimeFormat.formatDayHeader(programme.startMs)} at ${LiveTimeFormat.formatTime12h(programme.startMs)}"
            Toast.makeText(
                context,
                "${programme.title} airs $whenText on ${row.guideChannel.name}",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
}

private fun matchesFilter(row: GuideChannelRow, filter: EpgRowFilter): Boolean {
    val cat = row.guideChannel.category.lowercase()
    return when (filter) {
        EpgRowFilter.ALL -> true
        EpgRowFilter.PLAYABLE -> row.isPlayable
        EpgRowFilter.CATCHUP -> row.hasCatchup
        EpgRowFilter.ENTERTAINMENT -> "entertain" in cat || "terrestrial" in cat || "general" in cat
        EpgRowFilter.SPORTS -> "sport" in cat
        EpgRowFilter.CINEMA -> "cinema" in cat || "movie" in cat || "film" in cat
        EpgRowFilter.NEWS -> "news" in cat
        EpgRowFilter.KIDS -> "kid" in cat || "child" in cat
    }
}

private fun alignDownHalfHour(epochMs: Long): Long =
    (epochMs / HALF_HOUR_MS) * HALF_HOUR_MS

private fun dayChipLabel(chip: EpgDayChip, nowMs: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
    return when (chip) {
        EpgDayChip.NOW -> "Now"
        EpgDayChip.TONIGHT -> "Tonight"
        EpgDayChip.TOMORROW -> "Tomorrow"
        EpgDayChip.DAY_2 -> LiveTimeFormat.formatWeekdayShort(dayAtHour(cal, 2, 18))
        EpgDayChip.DAY_3 -> LiveTimeFormat.formatWeekdayShort(dayAtHour(cal, 3, 18))
        EpgDayChip.DAY_4 -> LiveTimeFormat.formatWeekdayShort(dayAtHour(cal, 4, 18))
    }
}

private fun computeDayChipTargetMs(
    chip: EpgDayChip,
    nowMs: Long,
    minStartMs: Long,
    maxStartMs: Long,
): Long {
    val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
    val rawTarget = when (chip) {
        EpgDayChip.NOW -> alignDownHalfHour(nowMs)
        EpgDayChip.TONIGHT -> {
            cal.set(Calendar.HOUR_OF_DAY, 19)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            cal.timeInMillis
        }
        EpgDayChip.TOMORROW -> dayAtHour(cal, 1, 18)
        EpgDayChip.DAY_2 -> dayAtHour(cal, 2, 18)
        EpgDayChip.DAY_3 -> dayAtHour(cal, 3, 18)
        EpgDayChip.DAY_4 -> dayAtHour(cal, 4, 18)
    }
    return rawTarget.coerceIn(minStartMs, maxStartMs)
}

private fun dayAtHour(base: Calendar, addDays: Int, hour: Int): Long {
    val copy = (base.clone() as Calendar).apply {
        add(Calendar.DAY_OF_YEAR, addDays)
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return copy.timeInMillis
}

@Composable
private fun StreamFeedPickerDialog(
    channel: CanonicalChannel,
    streams: List<lumos.iptv.StreamSource>,
    onSelect: (lumos.iptv.StreamSource) -> Unit,
    onClose: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(streams.firstOrNull()?.id) {
        delay(60)
        runCatching { firstFocus.requestFocus() }
    }
    PageWindow(onBack = onClose, dim = 0.78f, fadeIn = false) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .width(560.dp)
                    .background(LiveSurface, RoundedCornerShape(22.dp))
                    .border(1.dp, LivePalette.GlassBorder, RoundedCornerShape(22.dp))
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${channel.name} · Feeds", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Pill(text = "Close", selected = false, onClick = onClose)
                }
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    itemsIndexed(streams, key = { _, s -> s.id }) { idx, stream ->
                        Surface(
                            onClick = { onSelect(stream) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (idx == 0) Modifier.focusRequester(firstFocus) else Modifier),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = LivePalette.GlassFill,
                                contentColor = Color.White,
                                focusedContainerColor = Color.White,
                                focusedContentColor = Color.Black,
                            ),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                        ) {
                            val ink = LocalContentColor.current
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(stream.sourceName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ink)
                                    Text(
                                        "${stream.quality.badge}${if (stream.quality.isRaw) " · RAW" else ""}${if (stream.quality.isBackup) " · Backup" else ""}",
                                        fontSize = 12.sp,
                                        color = ink.copy(alpha = 0.65f),
                                    )
                                }
                                Text("Watch", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ink)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Modal listing available finished catch-up programmes for [channel], focusing the latest episode first. */
@Composable
internal fun CatchupModal(
    channel: CanonicalChannel,
    programmes: List<EpgProgramme>,
    onPlayProgramme: (EpgProgramme) -> Unit,
    onClose: () -> Unit,
) {
    val episodeFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }

    LaunchedEffect(programmes.firstOrNull()?.id) {
        delay(80)
        if (programmes.isNotEmpty()) {
            runCatching { episodeFocus.requestFocus() }
        } else {
            runCatching { closeFocus.requestFocus() }
        }
    }

    PageWindow(onBack = onClose, dim = 0.78f, fadeIn = false) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .width(660.dp)
                    .fillMaxHeight(0.82f)
                    .background(LiveSurface, RoundedCornerShape(22.dp))
                    .border(1.dp, LivePalette.GlassBorder, RoundedCornerShape(22.dp))
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        LandscapeChannelLogo(
                            logoUrl = channel.logoUrl,
                            name = channel.name,
                            width = 64.dp,
                            height = 36.dp,
                            corner = 8.dp,
                        )
                        Column {
                            Text("${channel.name} · Catch-up", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("${channel.maxCatchupDays}-day archive", fontSize = 13.sp, color = Dx.Muted)
                        }
                    }
                    Pill(
                        text = "Close",
                        selected = false,
                        modifier = Modifier.focusRequester(closeFocus),
                        onClick = onClose,
                    )
                }

                if (programmes.isEmpty()) {
                    Text(
                        "No finished catch-up programmes available in the EPG window for this channel.",
                        fontSize = 15.sp,
                        color = Dx.Muted,
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 16.dp),
                    ) {
                        itemsIndexed(programmes, key = { _, p -> p.id }) { idx, prog ->
                            Surface(
                                onClick = { onPlayProgramme(prog) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(if (idx == 0) Modifier.focusRequester(episodeFocus) else Modifier),
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = LivePalette.GlassFill,
                                    contentColor = Color.White,
                                    focusedContainerColor = Color.White,
                                    focusedContentColor = Color.Black,
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                            ) {
                                val ink = LocalContentColor.current
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(prog.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        prog.description?.takeIf { it.isNotBlank() }?.let { desc ->
                                            Text(desc, fontSize = 12.sp, color = ink.copy(alpha = 0.65f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        text = "${Dates.formatShortDay(prog.startMs)} ${Dates.formatTime(Date(prog.startMs))}",
                                        fontSize = 12.sp,
                                        color = ink.copy(alpha = 0.75f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


private fun filterIcon(f: EpgRowFilter) = when (f) {
    EpgRowFilter.ALL -> Icons.Filled.Apps
    EpgRowFilter.PLAYABLE -> Icons.Filled.PlayArrow
    EpgRowFilter.CATCHUP -> Icons.Filled.History
    EpgRowFilter.ENTERTAINMENT -> Icons.Filled.Theaters
    EpgRowFilter.SPORTS -> Icons.Filled.EmojiEvents
    EpgRowFilter.CINEMA -> Icons.Filled.Movie
    EpgRowFilter.NEWS -> Icons.Filled.Newspaper
    EpgRowFilter.KIDS -> Icons.Filled.ChildCare
}

/** The guide's filters as a column of icons that slides in over its left edge; Right or Back closes it */
@Composable
private fun FilterRail(
    open: Boolean,
    filters: List<EpgRowFilter>,
    active: EpgRowFilter,
    requesters: Map<EpgRowFilter, FocusRequester>,
    onPick: (EpgRowFilter) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = open,
        enter = slideInHorizontally(tween(LIVE_MS + 40)) { -it / 2 } + fadeIn(tween(LIVE_MS)),
        exit = slideOutHorizontally(tween(LIVE_MS)) { -it / 2 } + fadeOut(tween(LIVE_MS)),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .padding(start = 12.dp)
                .background(LiveSurface, RoundedCornerShape(22.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            filters.filter { it != EpgRowFilter.PLAYABLE }.forEach { f ->
                Surface(
                    onClick = { onPick(f) },
                    modifier = Modifier
                        .width(200.dp)
                        .height(36.dp)
                        .focusRequester(requesters.getValue(f))
                        .onPreviewKeyEvent { ev ->
                            if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionRight) { onClose(); true } else false
                        },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (f == active) Color.White.copy(alpha = 0.16f) else Color.Transparent,
                        contentColor = Color.White,
                        focusedContainerColor = Color.White,
                        focusedContentColor = Color.Black,
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
                ) {
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(filterIcon(f), contentDescription = null, modifier = Modifier.size(20.dp), tint = LocalContentColor.current)
                        Text(f.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = LocalContentColor.current, maxLines = 1)
                    }
                }
            }
        }
    }
}
