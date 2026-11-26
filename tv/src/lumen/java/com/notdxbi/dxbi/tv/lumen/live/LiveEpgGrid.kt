package com.notdxbi.dxbi.tv.lumen.live

import com.notdxbi.dxbi.tv.lumen.centredWhenFocused
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.graphics.graphicsLayer
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

private class GuideListing(val rowOf: IntArray, val labelOf: List<String?>, val itemOfRow: IntArray)

private data class LongPressTarget(
    val row: GuideChannelRow,
    val programme: EpgProgramme?,
)

// an hour and a half across: wide cells, so a title fits in two lines and the chosen row needn't open out
private const val VIEWPORT_DURATION_MS = 90L * 60_000L
private const val PAST_WINDOW_MS = 24L * 3_600_000L
private const val FUTURE_WINDOW_MS = 120L * 3_600_000L
private const val HALF_HOUR_MS = 30L * 60_000L
/** The guide is laid out this much wider each side of what shows, so the timeline can slide along (it is scrolled, not paged) */
private const val PAD_MS = 60L * 60_000L
private const val PAD_MIN = 60f
private const val VIEW_MIN = 90f
private const val PAN_STEP_MS = 60L * 60_000L
private const val HOLD_LEFT_UNLOCK_MS = 320L
private val LEFT_CHANNEL_WIDTH = 140.dp
private val ROW_HEIGHT = 64.dp
/** The row the remote is on grows (to show a programme's whole name) */
private val ROW_HEIGHT_FOCUSED = 80.dp
private val TIME_HEADER_HEIGHT = 38.dp

/**
 * Deterministic 3-hour viewport-clamped EPG timeline grid shared by TV Guide and Sports Channels.
 *
 * Landing focus starts directly on Row 0's live show (`1 click OK = watch TV`), the 188dp left
 * channel column is purely visual with zero focus stops, D-pad Up/Down restores focus on the slot
 * covering [focusedTimeMs], and holding Left at the live edge unlocks 24h past scrubbing.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    /** A programme's page: OK on one still to come, or OK held on any */
    onOpenProgramme: (GuideChannelRow, EpgProgramme, List<String>) -> Unit = { _, _, _ -> },
    /** Changes when the programme page closes: the remote goes back to the programme it was on */
    restoreFocusKey: Long = 0L,
    /** The button at the foot of the guide: every channel the provider has (null: no button) */
    onSeeMore: (() -> Unit)? = null,
) {
    val seeMoreFocus = remember { FocusRequester() }
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
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
    val windowStartMs = viewportStartMs - PAD_MS
    val windowEndMs = viewportEndMs + PAD_MS

    var activeFilter by remember(isSportsMode) { mutableStateOf(EpgRowFilter.ALL) }
    var activeDay by remember(isSportsMode) { mutableStateOf(EpgDayChip.NOW) }
    var followLive by remember { mutableStateOf(true) }
    var pastUnlocked by remember { mutableStateOf(false) }
    var focusedRowIdx by remember { mutableIntStateOf(0) }
    var focusedTimeMs by remember { mutableLongStateOf(nowMs) }
    var focusedIsLive by remember { mutableStateOf(true) }
    // (where the remote was when a programme's page opened: closing it, the focus first falls on the live programme)
    var openedFrom by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var pendingFocus by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    var focusNonce by remember { mutableIntStateOf(0) }
    var didInitialFocus by remember(isSportsMode) { mutableStateOf(false) }
    var holdLeftJob by remember { mutableStateOf<Job?>(null) }
    // the filters, as icons down the side: Left from the first programme opens them (Back closes them)
    var filterRailOpen by remember { mutableStateOf(false) }
    // the guide moves aside for the filters (they're a list beside it, not a card over it)
    val railShift by androidx.compose.animation.core.animateDpAsState(if (filterRailOpen) 236.dp else 0.dp, androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 200f), label = "rail-shift")
    val railShiftPx = with(androidx.compose.ui.platform.LocalDensity.current) { railShift.toPx() }
    val railRequesters = remember { EpgRowFilter.entries.associateWith { FocusRequester() } }
    // the filters, down the left: Left from the first programme opens them (a second Left there opens Lumen's menu)
    fun openRail() { filterRailOpen = true }
    var longPressTarget by remember { mutableStateOf<LongPressTarget?>(null) }
    // (a channel just added to or taken out of Favourites: its row moves, so the remote goes with it once the menu's closed)
    var refocusChannelId by remember { mutableStateOf<String?>(null) }
    var streamPickerRow by remember { mutableStateOf<GuideChannelRow?>(null) }

    LaunchedEffect(nowMs, followLive, pastUnlocked) {
        if (followLive && activeDay == EpgDayChip.NOW && !pastUnlocked) {
            viewportStartMs = alignDownHalfHour(nowMs).coerceIn(minViewportStartMs, maxViewportStartMs)
        }
    }

    val availableFilters = remember(rows, isSportsMode) {
        if (isSportsMode) {
            listOf(EpgRowFilter.ALL, EpgRowFilter.PLAYABLE)
        } else {
            EpgRowFilter.entries.filter { filter ->
                filter == EpgRowFilter.ALL ||
                    filter == EpgRowFilter.PLAYABLE ||
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
    // (channels added from Find a channel that the UK listings don't have get a row of their own in Favourites: their
    // provider's own guide, if it has one, fills it)
    val providerChannels by store.channels.collectAsState()
    val sections = remember(allRows, activeFilter, favourites, recents, watchingId, isSportsMode, providerChannels) {
        val byChannel = HashMap(allRows.filter { it.linkedChannel != null }.associateBy { it.linkedChannel!!.id })
        if (favourites.any { it !in byChannel }) {
            val byId = providerChannels.associateBy { it.id }
            for (id in favourites) {
                if (id in byChannel) continue
                val ch = byId[id] ?: continue
                byChannel[id] = GuideChannelRow(
                    guideChannel = lumos.iptv.GuideChannel(id = "iptv:${ch.id}", name = ch.name, logoUrl = ch.logoUrl, mainGuide = false),
                    linkedChannel = ch,
                    bestStream = ch.streams.firstOrNull(),
                )
            }
        }
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
    // The page's items: a row each, and a row's worth of height for each section's name, so the page is a whole number of
    // equal rows tall (rowOf: the row an item shows, or -1 for a name; itemOfRow: the item a row is)
    val listing = remember(sections) {
        val rowOf = ArrayList<Int>(); val labelOf = ArrayList<String?>(); val itemOfRow = IntArray(sections.size)
        sections.forEachIndexed { i, s ->
            if (s.third != null) { rowOf += -1; labelOf += s.third }
            itemOfRow[i] = rowOf.size; rowOf += i; labelOf += null
        }
        GuideListing(rowOf.toIntArray(), labelOf, itemOfRow)
    }
    val rowKeys = remember(sections) { sections.map { it.first } }
    val zapChannelIds = remember(allRows) {
        allRows.mapNotNull { it.linkedChannel?.id }
    }

    // The timeline scrolls with the remote: the programmes are laid out an hour wider each side than shows, and when the view
    // moves along (the chosen programme runs past an edge) the row slides to its new place, by exactly how far it moved
    val baseMs = remember { viewportStartMs }
    val trackWidthPx = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val shownStartMin = androidx.compose.animation.core.animateFloatAsState(
        (viewportStartMs - baseMs) / 60_000f,
        androidx.compose.animation.core.tween(Dx.Med + 120, easing = Dx.Ease),
        label = "timeline",
    )
    val slide: () -> Float = {
        ((((viewportStartMs - baseMs) / 60_000f) - shownStartMin.value).coerceIn(-PAD_MIN, PAD_MIN) / VIEW_MIN) * trackWidthPx.floatValue
    }
    val fade: () -> Float = { 1f }

    val listState = rememberLazyListState()
    val pageScroller = remember(listState) {
        com.notdxbi.dxbi.tv.lumen.PageScroller(listState, scope)
    }
    val slotRequesters = remember { mutableStateMapOf<String, FocusRequester>() }

    fun slotRequesterFor(rowId: String, slotIdx: Int): FocusRequester =
        slotRequesters.getOrPut("$rowId:$slotIdx") { FocusRequester() }

    fun slotsForRow(row: GuideChannelRow): List<ViewportSlot> {
        val progs = store.programmesForWindow(
            channel = row.linkedChannel,
            guideChannelId = row.guideChannel.id,
            fromMs = windowStartMs,
            toMs = windowEndMs,
        )
        return buildViewportSlots(progs, windowStartMs, windowEndMs)
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
    LaunchedEffect(restoreFocusKey) {
        if (restoreFocusKey == 0L) return@LaunchedEffect
        val (key, t) = openedFrom ?: return@LaunchedEffect
        delay(60)
        // (by the row's key, not its place: adding the channel to Favourites on the page moves every row under it down one,
        // and the Recently watched row it was on can be gone, moved up to Favourites)
        val channelId = key.substringAfter(':')
        val r = rowKeys.indexOf(key).takeIf { it >= 0 }
            ?: filteredRows.indexOfFirst { it.linkedChannel?.id == channelId || it.guideChannel.id == channelId }.takeIf { it >= 0 }
            ?: focusedRowIdx
        requestSlotFocus(r, t)
    }
    LaunchedEffect(returnedKey, filteredRows.size) {
        if (returnedKey == 0L || playingChannelId == null || filteredRows.isEmpty()) return@LaunchedEffect
        val idx = filteredRows.indexOfFirst { it.linkedChannel?.id == playingChannelId }
        if (idx >= 0) {
            delay(150)
            requestSlotFocus(idx, nowMs)
        }
    }

    /** How many rows the page shows */
    fun shownRows(): Int {
        val pitch = with(density) { ROW_HEIGHT.roundToPx() }
        return (listState.layoutInfo.viewportSize.height / pitch).takeIf { it > 0 } ?: 5
    }

    /** Whether row [rowIdx] is on the page (laid out, so its programmes can take the remote) */
    fun rowShown(rowIdx: Int): Boolean {
        val item = listing.itemOfRow.getOrElse(rowIdx) { return false }
        return listState.layoutInfo.visibleItemsInfo.any { it.index == item }
    }

    /** The item to put at the top with row [row] third from it (its section's name with it, when that's just above) */
    fun firstItemFor(row: Int, shown: Int = shownRows()): Int {
        val r = (row - 2).coerceAtLeast(0)
        var item = listing.itemOfRow.getOrElse(r) { 0 }
        if (item > 0 && listing.rowOf[item - 1] < 0) item--
        // (the sections' names take rows too: with two of them between, the row itself could fall below the page's foot)
        item = item.coerceAtLeast(listing.itemOfRow.getOrElse(row) { 0 } - shown + 1)
        return item.coerceIn(0, (listing.rowOf.size - shown).coerceAtLeast(0))
    }

    LaunchedEffect(pendingFocus, focusNonce, viewportStartMs) {
        val target = pendingFocus ?: return@LaunchedEffect
        val (rowIdx, targetTimeMs) = target
        val row = filteredRows.getOrNull(rowIdx) ?: run {
            pendingFocus = null
            return@LaunchedEffect
        }
        // (a row just under the page's foot comes in with the page's glide within a few frames)
        repeat(8) { if (!rowShown(rowIdx)) withFrameNanos { } }
        if (!rowShown(rowIdx)) {
            // (held Down: jump straight there; an animated scroll per press fell behind and lost the focus)
            // (with the row in the middle of the screen, not at its top: the page showed the next section otherwise)
            listState.scrollToItem(firstItemFor(rowIdx))
        }
        val slots = slotsForRow(row)
        val slotIdx = findSlotIndexForTime(slots, targetTimeMs)
        val req = slotRequesterFor(rowKeys[rowIdx], slotIdx)
        // (a requester whose programme isn't laid out yet takes the request without a word and nothing happens: only ask
        // once the row's on the page)
        repeat(30) {
            if (rowShown(rowIdx) && runCatching { req.requestFocus() }.isSuccess) {
                pendingFocus = null
                return@LaunchedEffect
            }
            withFrameNanos { }
        }
        pendingFocus = null
    }

    // The page keeps the row the remote is on third from the top, a whole row at a time: the first row shown always starts at
    // the top, so none is ever cut off (the viewport is a whole number of rows tall)
    LaunchedEffect(focusedRowIdx) {
        if (filteredRows.isEmpty()) return@LaunchedEffect
        val pitch = with(density) { ROW_HEIGHT.roundToPx() }
        val shown = (listState.layoutInfo.viewportSize.height / pitch).coerceAtLeast(1)
        pageScroller.glideRow = pitch.toFloat()
        pageScroller.place(firstItemFor(focusedRowIdx, shown), 0f)
    }

    // Favourites changed from the hold-OK menu: the row the remote was on can be gone (a channel in Recently watched moves up
    // to Favourites), and with nothing in the guide focused, Down went nowhere. Put the remote on the channel's row again.
    LaunchedEffect(refocusChannelId, sections) {
        val id = refocusChannelId ?: return@LaunchedEffect
        // (after the menu's window has gone: closing it hands the focus back to this window)
        delay(120)
        val idx = filteredRows.indexOfFirst { it.linkedChannel?.id == id }
        refocusChannelId = null
        requestSlotFocus(if (idx >= 0) idx else focusedRowIdx.coerceIn(0, filteredRows.lastIndex.coerceAtLeast(0)), if (focusedIsLive) System.currentTimeMillis() else focusedTimeMs)
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

    /** The view follows the remote along a row: when the chosen programme runs past an edge, it moves just far enough */
    fun ensureVisible(slot: ViewportSlot) {
        val lower = (if (pastUnlocked) minViewportStartMs else alignDownHalfHour(nowMs)).coerceAtLeast(minViewportStartMs)
        // (only when too little of it shows to read: moving up or down onto a long programme leaves the timeline where it is)
        val length = slot.endMs - slot.startMs
        val need = minOf(length, 30L * 60_000L)
        val shown = minOf(slot.endMs, viewportEndMs) - maxOf(slot.startMs, viewportStartMs)
        if (shown >= need) return
        // (just far enough to read it: a programme coming in at the right edge brings the timeline along by the bit that was out
        // of sight, not by a page, and the same going back)
        var target = if (slot.startMs < viewportStartMs) slot.endMs - need else slot.startMs + need - VIEWPORT_DURATION_MS
        target = target.coerceIn(viewportStartMs - PAD_MS, viewportStartMs + PAD_MS)
            .coerceIn(lower.coerceAtMost(maxViewportStartMs), maxViewportStartMs)
        if (target != viewportStartMs) {
            followLive = false
            viewportStartMs = target
        }
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
                    text = if (rows.isEmpty()) "The TV guide isn't available right now." else "No channels match ${activeFilter.label}.",
                    fontSize = 15.sp,
                    color = Dx.Muted,
                )
            }
            return@Column
        }

        // (solid: the channel's colour wash behind the page's top must not show in the gap the guide leaves for the filters)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // (the time strip's band runs the full width, still: when the guide moves aside for the filters, it doesn't
            // leave a gap at its left end)
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
                onMenu = {
                    filterRailOpen = false
                    onMoveToRail()
                },
                // (from the top of the guide, beside the time strip: lower down, the last filters ran off the bottom of the screen)
                modifier = Modifier.align(Alignment.TopStart).fillMaxHeight(),
            )
            Column(modifier = Modifier.fillMaxSize().graphicsLayer { translationX = railShiftPx }) {
                // 3-hour time header ruler
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(TIME_HEADER_HEIGHT),
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
                            text = "",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Dx.Muted,
                            maxLines = 1,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight(),
                    )
                    Box(Modifier.weight(1f).fillMaxHeight().clipToBounds().onSizeChanged { trackWidthPx.floatValue = it.width.toFloat() }) {
                        ViewportTimeRuler(
                            viewportStartMs = viewportStartMs,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    translationX = slide()
                                    alpha = fade()
                                    compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                                },
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp),
                )

                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides guideScrollSpec,
                    // (no centring by the rows: the page moves a whole row at a time, below)
                    com.notdxbi.dxbi.tv.lumen.LocalPageScroller provides null,
                ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        // (as tall as a whole number of rows: the last one is never cut off, and the first sits at the top)
                        .layout { measurable, constraints ->
                            val pitch = ROW_HEIGHT.roundToPx()
                            val h = (constraints.maxHeight / pitch).coerceAtLeast(1) * pitch
                            val p = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                            layout(p.width, h) { p.place(0, 0) }
                        }
                        .fillMaxWidth()
                        .onGloballyPositioned { pageScroller.viewport = it }
                        // (the press rate: a run of presses makes the page glide briskly, to keep up with the remote)
                        .onPreviewKeyEvent { ev ->
                            if (ev.type == KeyEventType.KeyDown && (ev.key == Key.DirectionDown || ev.key == Key.DirectionUp)) com.notdxbi.dxbi.tv.lumen.PressRate.press()
                            false
                        },
                    contentPadding = PaddingValues(0.dp),
                ) {
                    items(count = listing.rowOf.size, key = { i -> if (listing.rowOf[i] >= 0) rowKeys[listing.rowOf[i]] else "label:${listing.labelOf[i]}" }) { itemIdx ->
                        val rowIdx = listing.rowOf[itemIdx]
                        if (rowIdx < 0) {
                            // (a section's name takes a row of its own, so every row of the page is the same height)
                            Box(Modifier.fillMaxWidth().height(ROW_HEIGHT).padding(start = 40.dp), contentAlignment = Alignment.CenterStart) {
                                Text(listing.labelOf[itemIdx].orEmpty(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.55f))
                            }
                            return@items
                        }
                        val row = filteredRows[rowIdx]
                        val programmes = remember(row.guideChannel.id, windowStartMs, windowEndMs, epgVersion) {
                            store.programmesForWindow(
                                channel = row.linkedChannel,
                                guideChannelId = row.guideChannel.id,
                                fromMs = windowStartMs,
                                toMs = windowEndMs,
                            )
                        }
                        val slots = remember(programmes, windowStartMs, windowEndMs) {
                            buildViewportSlots(programmes, windowStartMs, windowEndMs)
                        }
                        val isFav = row.linkedChannel?.id in favourites
                        // (a channel with no listings, as one added to Favourites from Find a channel, is hour-long blanks: the one
                        // running now, not the first, which is in the hidden hour before the view, so the remote went out of sight)
                        val liveSlotIdx = remember(slots, nowMs) {
                            slots.indexOfFirst { it.programme?.isOnAt(nowMs) == true }.takeIf { it >= 0 }
                                ?: slots.indexOfFirst { nowMs >= it.startMs && nowMs < it.endMs }.takeIf { it >= 0 }
                                ?: 0
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
                            focusedRow = focusedRowIdx == rowIdx,
                            slide = slide,
                            fade = fade,
                            shownStartMin = { shownStartMin.value },
                            baseMs = baseMs,
                            contentFocusRequester = contentFocusRequester,
                            slotRequester = { slotIdx -> slotRequesterFor(rowKeys[rowIdx], slotIdx) },
                            onFocusSlot = { slot ->
                                focusedRowIdx = rowIdx
                                // (the middle of what shows of it, so the next row's programme is the one under it)
                                val shownFrom = maxOf(slot.startMs, viewportStartMs)
                                val shownTo = minOf(slot.endMs, viewportEndMs)
                                focusedTimeMs = if (shownTo > shownFrom) (shownFrom + shownTo) / 2 else slot.midMs
                                ensureVisible(slot)
                                val now = System.currentTimeMillis()
                                focusedIsLive = slot.programme?.isOnAt(now) ?: (now >= slot.startMs && now < slot.endMs)
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
                                    onOpenProgramme = { r, p -> openedFrom = rowKeys[rowIdx] to slot.midMs; onOpenProgramme(r, p, zapChannelIds) },
                                )
                            },
                            onLongClickSlot = { slot ->
                                val prog = slot.programme
                                if (prog != null) { openedFrom = rowKeys[rowIdx] to slot.midMs; onOpenProgramme(row, prog, zapChannelIds) }
                                else longPressTarget = LongPressTarget(row = row, programme = null)
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
                                    // (a row below the page's foot isn't laid out yet: asking its programme for the remote did nothing,
                                    // so Down stuck once Favourites and Recently watched, with their names, pushed the rows down)
                                    if (!rowShown(nextRowIdx) || runCatching { req.requestFocus() }.isFailure) {
                                        requestSlotFocus(nextRowIdx, targetMs)
                                    }
                                    true
                                } else if (delta > 0 && onSeeMore != null && !isSportsMode) {
                                    // (Down from the last row: the button under it, the page brought up to show it)
                                    scope.launch {
                                        val info = listState.layoutInfo
                                        listState.scrollToItem((info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(0))
                                        withFrameNanos { }
                                        runCatching { seeMoreFocus.requestFocus() }
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
                                        (prevSlot != null && prevSlot.endMs <= nowMs && slot.endMs > nowMs) ||
                                        (prevSlot != null && prevSlot.endMs <= liveBoundary)
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
                    }
                    if (onSeeMore != null && !isSportsMode && filteredRows.isNotEmpty()) item(key = "see-more") {
                        Box(Modifier.fillMaxWidth().height(ROW_HEIGHT).padding(start = 40.dp), contentAlignment = Alignment.CenterStart) {
                            LiveChip(
                                label = "See more channels",
                                selected = false,
                                onClick = onSeeMore,
                                modifier = Modifier.focusRequester(seeMoreFocus).onPreviewKeyEvent { ev ->
                                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when (ev.key) {
                                        Key.DirectionUp -> { requestSlotFocus(filteredRows.lastIndex, focusedTimeMs); true }
                                        Key.DirectionDown, Key.DirectionRight -> true
                                        else -> false
                                    }
                                },
                            )
                        }
                    }
                }
                }
            }

            // Subtle Dx.Blue playhead line strictly below the time ruler with a small dot on the header divider
            if (nowMs in windowStartMs..windowEndMs) {
                val fraction = (nowMs - viewportStartMs).toFloat() / VIEWPORT_DURATION_MS.toFloat()
                // over the programmes, in Lumen's blue
                BoxWithConstraints(
                    modifier = Modifier
                        .zIndex(5f)
                        .fillMaxSize()
                        .graphicsLayer { translationX = railShiftPx }
                        .padding(start = LEFT_CHANNEL_WIDTH + 1.dp)
                        .clipToBounds(),
                ) {
                    val xOffset = maxWidth * fraction
                    Box(
                        modifier = Modifier
                            .padding(top = TIME_HEADER_HEIGHT)
                            .offset(x = xOffset - 1.dp)
                            .graphicsLayer { translationX = slide() }
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(Color.White.copy(alpha = 0.85f)),
                    )
                    Box(
                        modifier = Modifier
                            .offset(x = xOffset - 3.dp, y = TIME_HEADER_HEIGHT - 3.dp)
                            .graphicsLayer { translationX = slide() }
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color.White),
                    )
                }
            }

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
                add(Action("Watch catch-up: ${prog!!.title}", Icons.Filled.History) {
                    store.playCatchup(context, linked!!, prog, zapChannelIds = zapChannelIds)
                })
            }
            if (row.isPlayable) {
                add(Action("Watch live: ${row.guideChannel.name}", Icons.Filled.PlayArrow) {
                    store.playGuideRow(context, row, zapChannelIds = zapChannelIds)
                })
            }
            if (linked != null) {
                add(
                    Action(
                        label = if (isFav) "Remove from favourites" else "Add to favourites",
                        icon = if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    ) {
                        store.toggleFavourite(linked.id)
                        refocusChannelId = linked.id
                    },
                )
            }
            if (row.hasCatchup && linked != null) {
                add(Action("Browse catch-up archive", Icons.Filled.LiveTv) {
                    onOpenCatchup(linked)
                })
            }
            if (linked != null && linked.streams.size > 1) {
                add(Action("Choose stream feed (${linked.streams.size})", Icons.Filled.Tune) {
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
    // (a time at every half hour, placed where it falls: the ruler slides along with the programmes)
    val windowStart = viewportStartMs - PAD_MS
    val windowEnd = viewportStartMs + VIEWPORT_DURATION_MS + PAD_MS
    val ticks = remember(viewportStartMs) {
        val first = ((windowStart + HALF_HOUR_MS - 1) / HALF_HOUR_MS) * HALF_HOUR_MS
        generateSequence(first) { it + HALF_HOUR_MS }.takeWhile { it < windowEnd }.toList()
    }
    BoxWithConstraints(modifier) {
        val trackW = maxWidth
        ticks.forEach { tickMs ->
            Column(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = trackW * ((tickMs - viewportStartMs).toFloat() / VIEWPORT_DURATION_MS.toFloat()))
                    .padding(start = 6.dp),
            ) {
                Text(
                    text = LiveTimeFormat.formatTime24h(tickMs),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    softWrap = false,
                    style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                )
                Text(text = rulerDay(tickMs), fontSize = 12.sp, color = Dx.Muted, maxLines = 1, softWrap = false)
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
    focusedRow: Boolean,
    slide: () -> Float,
    fade: () -> Float,
    shownStartMin: () -> Float,
    baseMs: Long,
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
    // (every row the same height: the chosen cell lifts instead, as Home's cards do. A row that opened out moved the rows
    // under it while the page glided, and the two moving at once made the page swim)
    val rowHeight = ROW_HEIGHT
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            // Home's page glide: moving into a row centres it in one soft glide, decided once (the guide's own scroll
            // re-aimed every frame and hopped in a few)
            .centredWhenFocused(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Purely visual left channel column (zero focus stops!)
        Row(
            modifier = Modifier
                .width(LEFT_CHANNEL_WIDTH)
                .fillMaxHeight()
                .padding(start = 40.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(verticalArrangement = Arrangement.Center) {
                // the channel's logo as a mark on the page (no name in words: the logo says it)
                ChannelTile(
                    logoUrl = row.logoUrl,
                    name = row.guideChannel.name,
                    width = 92.dp,
                    height = 44.dp,
                    plain = true,
                    modifier = Modifier.graphicsLayer { alpha = if (row.isPlayable) 1f else 0.5f },
                )
            }
        }

        Box(Modifier.width(1.dp))

        // Proportional 3-hour timeline track
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
        val trackW = maxWidth
        val trackPx = with(androidx.compose.ui.platform.LocalDensity.current) { trackW.toPx() }
        // (an hour wider each side than shows: what's beyond an edge is there to slide in. A required width wider than the box is
        // centred in it, which puts the hour of spare each side, and the view's start at the box's left edge)
        val totalW = trackW * ((VIEWPORT_DURATION_MS + 2 * PAD_MS).toFloat() / VIEWPORT_DURATION_MS.toFloat())
        Row(
            modifier = Modifier
                .requiredWidth(totalW)
                .fillMaxHeight()
                .padding(vertical = 5.dp)
                .focusGroup()
                .graphicsLayer {
                    translationX = slide()
                    alpha = fade()
                    compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            slots.forEachIndexed { slotIdx, slot ->
                // (each programme keeps its own place in the composition when the view moves along: the slots' numbers shift as
                // the window does, and the remote's place went to whichever programme then had its old number)
                androidx.compose.runtime.key(slot.programme?.id ?: slot.startMs) {
                val prog = slot.programme
                val isLive = prog?.isOnAt(nowMs) == true || (prog == null && row.isPlayable && nowMs in slot.startMs until slot.endMs)
                val isPast = slot.endMs <= nowMs
                val isPastCatchup = isPast && row.hasCatchup && prog != null
                val isPastUnavailable = isPast && (!row.hasCatchup || prog == null)
                val isFirstEntrySlot = rowIdx == 0 && slotIdx == liveSlotIdx

                val displayTitle = when {
                    prog != null && slot.clippedLeft -> "‹ ${prog.title}"
                    prog != null -> prog.title
                    row.isPlayable -> "Live broadcast · ${row.guideChannel.name}"
                    else -> "No programme guide"
                }
                // (only when it starts: where it ends shows from where the next one begins)
                val timeLabel = LiveTimeFormat.formatTime24h(prog?.startMs ?: slot.startMs)

                val cellMod = Modifier
                    .weight(slot.weightMinutes)
                    .fillMaxHeight()
                    .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(Dx.RCard))
                    .then(if (isFirstEntrySlot) Modifier.focusRequester(contentFocusRequester) else Modifier)
                    .focusRequester(slotRequester(slotIdx))
                    .onPreviewKeyEvent { ev ->
                        when (ev.type) {
                            KeyEventType.KeyDown -> when (ev.key) {
                                Key.DirectionDown -> {
                                    onStepRow(+1).let { true }
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
                    isPastUnavailable = isPast,
                    isSport = prog?.isLiveSport == true,
                    rowFocused = focusedRow,
                    // (a programme that began before the left edge keeps its name in view, sliding along inside it)
                    startInset = {
                        val hiddenMin = (shownStartMin() - (slot.startMs - baseMs) / 60_000f).coerceAtLeast(0f)
                        minOf(hiddenMin, slot.weightMinutes - 8f).coerceAtLeast(0f) / VIEW_MIN * trackPx
                    },
                    onFocus = { onFocusSlot(slot) },
                    onClick = { onClickSlot(slot) },
                    onLongClick = { onLongClickSlot(slot) },
                    modifier = cellMod,
                )
                }
            }
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
    rowFocused: Boolean,
    startInset: () -> Float,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = when {
        isLive -> Color.White.copy(alpha = 0.12f)
        // (faint glass over black, as Home's cards: only the chosen one is white)
        else -> Color.White.copy(alpha = 0.06f)
    }
    val borderColor = when {
        isPastCatchup -> Color.Transparent
        else -> Color.Transparent
    }

    GlassTile(
        onClick = onClick,
        onLongClick = onLongClick,
        onFocus = onFocus,
        modifier = modifier,
        shape = RoundedCornerShape(Dx.RCard),
        fillColor = bg,
        focusedFillColor = Color.White,
        borderColor = borderColor,
        focusedScale = Dx.FocusRow,
        sheen = false,
        instantFill = true,
        maxGrow = 8.dp,
    ) { focused, ink ->
        val textAlpha = if (!focused && isPastUnavailable) 0.55f else 1f
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .layout { measurable, constraints ->
                    val inset = startInset().toInt().coerceAtLeast(0)
                    val p = measurable.measure(constraints.copy(maxWidth = (constraints.maxWidth - inset).coerceAtLeast(0), minWidth = 0))
                    layout(p.width + inset, p.height) { p.place(inset, 0) }
                }
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
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
                    fontWeight = if (isLive && !focused) FontWeight.Bold else FontWeight.SemiBold,
                    color = ink.copy(alpha = textAlpha),
                    // (one line; the row the remote is on has room for the whole name)
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = timeLabel,
                style = androidx.compose.ui.text.TextStyle(
                    fontSize = 12.sp,
                    color = ink.copy(alpha = if (focused) 0.68f else (0.62f * textAlpha).coerceAtLeast(0.55f)),
                    fontFeatureSettings = "tnum",
                ),
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
        // (hour-long blanks across the whole span: it is wider than what shows, so the timeline has no missing stretch)
        val count = ((viewportEndMs - viewportStartMs + chunk - 1) / chunk).toInt().coerceAtLeast(1)
        return List(count) { idx ->
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
    onOpenProgramme: (GuideChannelRow, EpgProgramme) -> Unit,
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
                    if (linked == null) "None of your providers has ${row.guideChannel.name} at the moment"
                    else "${row.guideChannel.name} can't play this again",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        // still to come: its page (Remind me, Watch channel)
        else -> onOpenProgramme(row, programme)
    }
}

private fun matchesFilter(row: GuideChannelRow, filter: EpgRowFilter): Boolean {
    val cat = row.guideChannel.category.lowercase()
    return when (filter) {
        EpgRowFilter.ALL -> true
        EpgRowFilter.PLAYABLE -> row.isPlayable
        EpgRowFilter.ENTERTAINMENT -> "entertain" in cat || "terrestrial" in cat || "general" in cat
        EpgRowFilter.SPORTS -> "sport" in cat
        EpgRowFilter.CINEMA -> "cinema" in cat || "movie" in cat || "film" in cat
        EpgRowFilter.NEWS -> "news" in cat
        EpgRowFilter.KIDS -> "kid" in cat || "child" in cat
    }
}

private fun alignDownHalfHour(epochMs: Long): Long =
    (epochMs / HALF_HOUR_MS) * HALF_HOUR_MS

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
                    .liveGlass(RoundedCornerShape(Dx.RPanel))
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${channel.name} · Feeds", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Pill(text = "Close", selected = false, onClick = onClose)
                }
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    itemsIndexed(streams, key = { _, s -> s.id }) { idx, stream ->
                        Surface(
                            onClick = { onSelect(stream) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (idx == 0) Modifier.focusRequester(firstFocus) else Modifier),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(Dx.RCard)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = LivePalette.GlassFill,
                                contentColor = Color.White,
                                focusedContainerColor = Color.White,
                                focusedContentColor = Color.Black,
                            ),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = Dx.FocusRow),
                        ) {
                            val ink = LocalContentColor.current
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(stream.sourceName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${stream.quality.badge}${if (stream.quality.isRaw) " · RAW" else ""}${if (stream.quality.isBackup) " · Backup" else ""}",
                                        fontSize = 12.sp,
                                        color = ink.copy(alpha = 0.65f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
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
                    .liveGlass(RoundedCornerShape(Dx.RPanel))
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
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
                            Text("${channel.name} · Catch-up", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${channel.maxCatchupDays}-day archive", fontSize = 13.sp, color = Dx.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    ) {
                        itemsIndexed(programmes, key = { _, p -> p.id }) { idx, prog ->
                            Surface(
                                onClick = { onPlayProgramme(prog) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(if (idx == 0) Modifier.focusRequester(episodeFocus) else Modifier),
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(Dx.RCard)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = LivePalette.GlassFill,
                                    contentColor = Color.White,
                                    focusedContainerColor = Color.White,
                                    focusedContentColor = Color.Black,
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = Dx.FocusRow),
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
                                        text = "${Dates.formatShortDay(prog.startMs)} ${LiveTimeFormat.formatTime24h(prog.startMs)}",
                                        style = androidx.compose.ui.text.TextStyle(
                                            fontSize = 12.sp,
                                            color = ink.copy(alpha = 0.75f),
                                            fontFeatureSettings = "tnum",
                                        ),
                                        maxLines = 1,
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
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = open,
        enter = fadeIn(tween(LIVE_MS + 120, easing = Dx.Ease)),
        exit = fadeOut(tween(LIVE_MS, easing = Dx.Ease)),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                // (scrolls if a short screen still can't fit them all: the chosen one is brought into view)
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(start = 40.dp, top = 4.dp, bottom = 24.dp)
                .width(180.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Show", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Dx.Muted, modifier = Modifier.padding(start = 16.dp, bottom = 2.dp))
            filters.filter { it != EpgRowFilter.PLAYABLE }.forEach { f ->
                Surface(
                    onClick = { onPick(f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .focusRequester(requesters.getValue(f))
                        .onPreviewKeyEvent { ev ->
                            if (ev.type != KeyEventType.KeyDown) false
                            else when (ev.key) {
                                Key.DirectionRight -> { onClose(); true }
                                // (a second Left, from the filters, goes on to the main menu)
                                Key.DirectionLeft -> { onMenu(); true }
                                else -> false
                            }
                        },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (f == active) Color.White.copy(alpha = 0.16f) else Color.Transparent,
                        contentColor = Color.White,
                        focusedContainerColor = Color.White,
                        focusedContentColor = Color.Black,
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
                ) {
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(filterIcon(f), contentDescription = null, modifier = Modifier.size(20.dp), tint = LocalContentColor.current)
                        Text(f.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = LocalContentColor.current, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * The guide's scrolling is all [com.notdxbi.dxbi.tv.lumen.PageScroller]'s (each row centres itself once as the remote
 * moves into it), as Home's is. Compose's own bring-into-view is turned off here: each row hands its programmes' requests
 * on as a point at its top, which this used to move to a fifth of the way down, so every Left or Right shifted the page.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private val guideScrollSpec = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> = com.notdxbi.dxbi.tv.lumen.PageGlide
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}


/** "Today", "Tomorrow", "Yesterday" or the weekday, under a time on the ruler */
private fun rulerDay(ms: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    val t = Calendar.getInstance()
    val d = (c.get(Calendar.YEAR) - t.get(Calendar.YEAR)) * 400 + c.get(Calendar.DAY_OF_YEAR) - t.get(Calendar.DAY_OF_YEAR)
    return when (d) {
        0 -> "Today"; 1 -> "Tomorrow"; -1 -> "Yesterday"
        else -> java.text.SimpleDateFormat("EEEE", java.util.Locale.UK).format(java.util.Date(ms))
    }
}
