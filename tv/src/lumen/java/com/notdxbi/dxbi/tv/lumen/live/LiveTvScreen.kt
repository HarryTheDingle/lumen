package com.notdxbi.dxbi.tv.lumen.live

import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.notdxbi.dxbi.tv.R
import com.notdxbi.dxbi.tv.live.LiveMiniPlayer
import com.notdxbi.dxbi.tv.live.LiveTvRuntime
import com.notdxbi.dxbi.tv.live.LiveTvStore
import com.notdxbi.dxbi.tv.live.QrSetupPairing
import com.notdxbi.dxbi.tv.lumen.Dx
import com.notdxbi.dxbi.tv.lumen.Nav
import com.notdxbi.dxbi.tv.lumen.Tab
import com.notdxbi.dxbi.tv.lumen.Pill
import com.notdxbi.dxbi.tv.phone.Engine
import kotlinx.coroutines.delay
import lumos.iptv.CanonicalChannel

/**
 * Apple TV / Lumen glass Live TV hub coordinating the auto-hiding [LiveSideRail],
 * conditional [LiveHeroHeader], viewport-clamped [LiveEpgGrid], unified [SportsFixturesView],
 * [LiveFavouritesView], [LiveSearchView], [LiveSettingsView], and Sky-style corner mini-player.
 */
@Composable
fun LiveTvScreen(engine: Engine, nav: Nav) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val store = remember { LiveTvRuntime.store(context, engine) }

    LaunchedEffect(Unit) {
        store.ensureLoaded()
    }

    val uiState by store.uiState.collectAsState()
    val channels by store.channels.collectAsState()
    val mainGuideRows by store.mainGuideRows.collectAsState()
    val prefs by store.prefs.collectAsState()
    val sources by store.sources.collectAsState()
    val pairing by store.setupPairing.collectAsState()
    val enrichments by store.enrichments.collectAsState()
    val epgVersion by store.epgVersion.collectAsState()
    val miniSession by LiveMiniPlayer.session.collectAsState()
    val miniPlayerActive = miniSession != null

    // the page is chosen under Live TV in Lumen's own menu (one menu for everything)
    val activeSection = nav.liveSection.let { if (it == LiveSection.SETTINGS || it == LiveSection.FAVOURITES) LiveSection.TV_GUIDE else it }
    var railOpen by remember { mutableStateOf(false) }
    var catchupChannel by remember { mutableStateOf<CanonicalChannel?>(null) }
    var heroSelection by remember {
        mutableStateOf<HeroSelection>(
            HeroSelection.Empty("Live TV", "Loading UK TV guide and channels…"),
        )
    }

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            nowMs = System.currentTimeMillis()
        }
    }

    LaunchedEffect(catchupChannel) {
        catchupChannel?.let { store.fetchChannelArchiveEpg(it) }
    }

    // Stop kept mini-player when leaving Tab.LiveTV or backgrounding without launching fullscreen
    DisposableEffect(Unit) {
        LiveMiniPlayer.hosts++
        onDispose {
            LiveMiniPlayer.hosts = (LiveMiniPlayer.hosts - 1).coerceAtLeast(0)
            if (!LiveMiniPlayer.openingFullscreen) {
                LiveMiniPlayer.stop(store)
            }
        }
    }
    var resumedAt by remember { mutableLongStateOf(0L) }
    val miniRequester = remember { FocusRequester() }
    var miniFocused by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    if (!LiveMiniPlayer.openingFullscreen) {
                        LiveMiniPlayer.stop(store)
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    LiveMiniPlayer.openingFullscreen = false
                    resumedAt = android.os.SystemClock.uptimeMillis()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val railRequesters = remember { LiveSection.entries.associateWith { FocusRequester() } }
    val contentRequester = remember { FocusRequester() }
    val favSet = remember(prefs.favourites) { prefs.favourites.toHashSet() }

    val closeRail: () -> Unit = {
        railOpen = false
        runCatching { contentRequester.requestFocus() }
    }

    // Back first goes to the small screen of what's playing (OK there: back to full screen), then opens Lumen's menu,
    // opened out at Live TV's pages (and not on the Back that just closed the player)
    BackHandler(enabled = !nav.menuOpen && catchupChannel == null && pairing == null) {
        if (android.os.SystemClock.uptimeMillis() - resumedAt < 700) return@BackHandler
        if (miniSession != null && !miniFocused && runCatching { miniRequester.requestFocus() }.isSuccess) return@BackHandler
        nav.menuOpen = true
    }
    BackHandler(enabled = pairing != null && catchupChannel == null) {
        store.stopQrSetup()
    }
    BackHandler(enabled = catchupChannel != null) {
        catchupChannel = null
    }

    LaunchedEffect(activeSection) {
        delay(120)
        if (!railOpen) {
            runCatching { contentRequester.requestFocus() }
        }
    }

    val resolvedHero = remember(heroSelection, enrichments) {
        when (val sel = heroSelection) {
            is HeroSelection.Programme -> {
                val enriched = sel.programme?.id?.let { enrichments[it] }
                if (enriched != null) sel.copy(enrichment = enriched) else sel
            }
            is HeroSelection.Channel -> {
                val enriched = sel.nowProgramme?.id?.let { enrichments[it] }
                if (enriched != null) sel.copy(enrichment = enriched) else sel
            }
            else -> sel
        }
    }

    // The guide always has its header; the other pages get the same one (the small screen top left, the programme's
    // words and picture beside it) while something is playing, so Back from a match lands on the same layout
    val showHero = activeSection == LiveSection.TV_GUIDE
    // (only the guide has the small screen: leaving it for sports, search or favourites stops the picture)
    LaunchedEffect(activeSection, miniSession != null) {
        if (activeSection != LiveSection.TV_GUIDE && miniSession != null && !LiveMiniPlayer.openingFullscreen) LiveMiniPlayer.stop(store)
    }
    val playingNow = remember(miniSession?.channel?.id, nowMs / 60_000L) {
        miniSession?.let { s ->
            store.programmesInWindow(s.channel, nowMs, nowMs + 1).firstOrNull()
        }
    }
    val headerHero = if (activeSection == LiveSection.TV_GUIDE) resolvedHero
    else miniSession?.let { HeroSelection.Channel(it.channel, playingNow, null, playingNow?.id?.let { id -> enrichments[id] }) } ?: resolvedHero

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Dx.Background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (showHero) {
                LiveHeroHeader(
                    selection = headerHero,
                    hideSportsScores = prefs.hideSportsScores,
                    nowMs = nowMs,
                    miniPlayerActive = miniPlayerActive,
                    leading = miniSession?.let { session -> {
                        CornerMiniPlayerCard(
                            session = session,
                            store = store,
                            onResumeFullscreen = {
                                val catchup = session.catchupProgramme
                                if (catchup != null) {
                                    store.playCatchup(context = context, channel = session.channel, programme = catchup, zapChannelIds = session.zapChannelIds, skipConflictCheck = true)
                                } else {
                                    store.playLive(context = context, channel = session.channel, streamId = session.activeStream?.id, zapChannelIds = session.zapChannelIds, skipConflictCheck = true)
                                }
                            },
                            onStop = { LiveMiniPlayer.stop(store) },
                            focusRequester = miniRequester,
                            onFocusedChange = { miniFocused = it },
                        )
                    } },
                    onArtworkError = { chId, prog ->
                        store.enrichIfMissing(chId, prog, forceArtwork = true)
                    },
                )
            }

            uiState.error?.let { err ->
                Text(
                    text = err,
                    fontSize = 12.sp,
                    color = Color(0xFFFF7A7E),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF231317))
                        .padding(horizontal = 24.dp, vertical = 6.dp),
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (activeSection) {
                    LiveSection.TV_GUIDE -> {
                        if (sources.isEmpty() && channels.isEmpty() && mainGuideRows.isEmpty() && !uiState.loading) {
                            EmptyLiveTvSetup(
                                pairing = pairing,
                                contentFocusRequester = contentRequester,
                                onStartPairing = { store.startQrSetup() },
                                onStopPairing = { store.stopQrSetup() },
                            )
                        } else {
                            LiveEpgGrid(
                                rows = mainGuideRows,
                                store = store,
                                favourites = favSet,
                                epgVersion = epgVersion,
                                isSportsMode = false,
                                contentFocusRequester = contentRequester,
                                onMoveToRail = { nav.menuOpen = true },
                                onFocusSlot = { row, prog ->
                                    heroSelection = HeroSelection.Programme(row = row, programme = prog)
                                },
                                onOpenCatchup = { ch -> catchupChannel = ch },
                                playingChannelId = miniSession?.channel?.id,
                                returnedKey = resumedAt,
                            )
                        }
                    }

                    LiveSection.SPORTS -> {
                        SportsHomeView(
                            store = store,
                            hideSportsScores = prefs.hideSportsScores,
                            contentFocusRequester = contentRequester,
                            onMoveToRail = { nav.menuOpen = true },
                        )
                    }

                    LiveSection.FAVOURITES -> Unit

                    LiveSection.SEARCH -> {
                        LiveSearchView(
                            store = store,
                            hideSportsScores = prefs.hideSportsScores,
                            nowMs = nowMs,
                            miniPlayerActive = miniPlayerActive,
                            contentFocusRequester = contentRequester,
                            onMoveToRail = { nav.menuOpen = true },
                            onOpenCatchup = { ch -> catchupChannel = ch },
                            onOpenSport = { sport ->
                                context.getSharedPreferences("lumen_sports", android.content.Context.MODE_PRIVATE).edit().putString("start", sport).apply()
                                nav.liveSection = LiveSection.SPORTS
                            },
                        )
                    }

                    LiveSection.SETTINGS -> {
                        LiveSettingsView(
                            store = store,
                            miniPlayerActive = miniPlayerActive,
                            contentFocusRequester = contentRequester,
                            onMoveToRail = { nav.menuOpen = true },
                        )
                    }
                }
            }
        }

        catchupChannel?.let { selected ->
            val catchupProgrammes = remember(selected.id, epgVersion) {
                store.catchupFor(selected, System.currentTimeMillis())
            }
            CatchupModal(
                channel = selected,
                programmes = catchupProgrammes,
                onPlayProgramme = { prog ->
                    catchupChannel = null
                    store.playCatchup(context, selected, prog)
                },
                onClose = { catchupChannel = null },
            )
        }

        val activePairing = pairing
        if (activePairing != null && (sources.isNotEmpty() || channels.isNotEmpty() || mainGuideRows.isNotEmpty())) {
            QrPairingModal(
                pairing = activePairing,
                onClose = { store.stopQrSetup() },
            )
        }
    }
}

@Composable
private fun CornerMiniPlayerCard(
    session: LiveMiniPlayer.Session,
    store: LiveTvStore,
    onResumeFullscreen: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocusedChange: (Boolean) -> Unit = {},
) {
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    val isBuffering by session.engine.isBuffering.collectAsState()

    DisposableEffect(session.engine) {
        onDispose {
            playerViewRef?.let { pv -> session.engine.detachPlayerView(pv) }
        }
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        GlassTile(
            onClick = onResumeFullscreen,
            onLongClick = onStop,
            modifier = Modifier
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { onFocusedChange(it.isFocused || it.hasFocus) }
                .width(MINI_W)
                .height(MINI_H),
            shape = RoundedCornerShape(14.dp),
            fillColor = Color.Black,
            focusedFillColor = Color.Black,
            borderColor = LivePalette.GlassBorder,
            focusedScale = 1.03f,
        ) { focused, _ ->
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        (LayoutInflater.from(ctx).inflate(R.layout.live_mini_player_view, null, false) as PlayerView).also { pv ->
                            playerViewRef = pv
                            session.engine.attachPlayerView(pv, AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
                        }
                    },
                    update = { pv ->
                        playerViewRef = pv
                        session.engine.attachPlayerView(pv, AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
                    },
                    onRelease = { pv ->
                        session.engine.detachPlayerView(pv)
                        if (playerViewRef === pv) playerViewRef = null
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                // Subtle bottom scrim for channel label and OK hint
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.85f),
                            ),
                        ),
                )

                if (isBuffering) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = "Buffering…",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = session.channel.name,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

    }
}

@Composable
private fun EmptyLiveTvSetup(
    pairing: QrSetupPairing?,
    contentFocusRequester: FocusRequester,
    onStartPairing: () -> Unit,
    onStopPairing: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (pairing != null) {
            QrSetupCard(pairing = pairing, onClose = onStopPairing)
        } else {
            Column(
                modifier = Modifier.width(520.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("No Live TV yet", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(
                    "Scan the code with your phone to add your Xtream, M3U or Stalker provider.",
                    fontSize = 14.sp,
                    color = Dx.Muted,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Pill(
                    text = "Add a provider with your phone",
                    selected = true,
                    modifier = Modifier.focusRequester(contentFocusRequester),
                    onClick = onStartPairing,
                )
            }
        }
    }
}
