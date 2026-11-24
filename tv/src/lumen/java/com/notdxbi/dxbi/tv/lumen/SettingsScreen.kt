package com.notdxbi.dxbi.tv.lumen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.notdxbi.dxbi.tv.BuildConfigVersion
import com.notdxbi.dxbi.tv.phone.LumosSettings
import com.notdxbi.dxbi.tv.phone.Setting
import com.notdxbi.dxbi.tv.phone.Stremio
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Settings' sections, down the left (as tvOS Settings lists them): what's in each shows on the right as the remote moves */
private enum class Section(val title: String, val icon: ImageVector, val group: String? = null) {
    Playback("Playback", Icons.Rounded.PlayCircle, LumosSettings.PLAYBACK),
    Subtitles("Subtitles", Icons.Rounded.Subtitles, LumosSettings.SUBTITLES),
    Episodes("Episodes", Icons.Rounded.SkipNext, LumosSettings.EPISODES),
    Player("Player", Icons.Rounded.Tv, LumosSettings.PLAYER),
    Home("Home", Icons.Rounded.Home),
    Account("Profiles & Account", Icons.Rounded.Person),
    Addons("Addons", Icons.Rounded.Extension),
    LiveTV("Live TV", Icons.Rounded.LiveTv),
    About("About", Icons.Rounded.Info),
}

/** A row on the right: a setting, a page to open, something to do, or something to read */
private sealed interface Item {
    val key: String
    val help: String
    data class Value(val setting: Setting) : Item { override val key get() = setting.key; override val help get() = setting.description }
    data class Link(override val key: String, val title: String, override val help: String, val action: () -> Unit) : Item
    /** Pressed twice (the first press says what the second does), for what can't be undone */
    data class Action(override val key: String, val title: String, val confirm: String, override val help: String, val action: () -> Unit) : Item
    data class Toggle(override val key: String, val title: String, val on: Boolean, override val help: String, val onToggle: () -> Unit) : Item
    data class Info(override val key: String, val title: String, val value: String, override val help: String = "") : Item
}

/**
 * Settings, as tvOS lays them out: the sections down the left, and what's in the one the remote is on down the right,
 * changing as it moves (no OK needed). Right goes into them, Back (or left) comes out again. A switch changes with OK;
 * a choice opens its options in place, with a tick by the one in use. What a setting does shows under the sections.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(stremio: Stremio, nav: Nav) {
    val context = LocalContext.current
    val tasteState = LocalTaste.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf<List<Setting>>(emptyList()) }
    var section by remember { mutableStateOf(Section.Playback) }
    var inRight by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf<Setting?>(null) }
    var help by remember { mutableStateOf("") }
    var addons by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var speed by remember { mutableStateOf<String?>(null) }
    var refocus by remember { mutableStateOf<String?>(null) }
    var passthroughOn by remember { mutableStateOf(com.notdxbi.dxbi.tv.PlayerActivity.passthroughEnabled(context)) }
    val viewers by stremio.viewers.collectAsState()
    val sectionFocus = remember { Section.entries.associateWith { FocusRequester() } }
    val rightFirst = remember { FocusRequester() }
    val rowFocus = remember { mutableMapOf<String, FocusRequester>() }
    fun rowRequester(key: String) = rowFocus.getOrPut(key) { FocusRequester() }

    suspend fun reload() {
        settings = stremio.settings()
        addons = runCatching { stremio.engine.addonList() }.getOrDefault(emptyList())
    }
    LaunchedEffect(Unit) {
        reload()
        speed = runCatching { stremio.networks().firstOrNull { it.current }?.takeIf { it.mbps > 0 }?.let { "%.0f Mbps".format(it.mbps) } }.getOrNull()
        delay(250)
        runCatching { sectionFocus.getValue(section).requestFocus() }
    }
    // (back on the row that was open, after a choice or a page)
    LaunchedEffect(refocus, choosing) {
        val key = refocus ?: return@LaunchedEffect
        delay(60)
        runCatching { rowRequester(key).requestFocus() }
        refocus = null
    }

    fun save(s: Setting, value: Any?) {
        settings = settings.map { if (it.key == s.key) it.copy(value = value) else it }
        scope.launch { stremio.saveSetting(s.key, value) }
    }

    // Back: a choice closes; from the right, back to the section; from the sections, Lumen's menu (LumenApp)
    BackHandler(enabled = choosing != null) {
        refocus = choosing?.key
        choosing = null
    }
    BackHandler(enabled = choosing == null && inRight) { runCatching { sectionFocus.getValue(section).requestFocus() } }

    val items: List<Item> = when (section) {
        Section.Playback, Section.Subtitles, Section.Episodes, Section.Player -> if (settings.isEmpty()) emptyList() else buildList {
            settings.filter { it.group == section.group }.forEach { add(Item.Value(it)) }
            if (section == Section.Playback) add(Item.Link("networks", "Networks", "The speed Lumen has learnt on each Wi-Fi or cable connection, and a quality cap for each.") { nav.push(Page.Networks) })
            if (section == Section.Player) add(Item.Toggle(
                "hdmiPassthrough",
                "HDMI audio passthrough",
                passthroughOn,
                "Send Dolby and DTS straight to your TV or receiver. Off: decode to PCM on this box (best if surround streams have no sound).",
            ) {
                passthroughOn = !passthroughOn
                com.notdxbi.dxbi.tv.PlayerActivity.setPassthroughEnabled(context, passthroughOn)
            })
        }
        Section.Home -> listOf(
            Item.Link("taste", "Tune Suggestions", "Tell Lumen what you like so Home suggests more of it.") { tasteState?.tuning = true },
        )
        Section.Account -> buildList {
            add(Item.Link("profiles", "Profiles", "Who's watching: add, rename, recolour or remove people (${viewers.size} now). Each has their own Continue Watching, My List and suggestions.") { nav.push(Page.Profiles) })
            val keeps = stremio.engine.keepsStremio
            add(Item.Link("stremio", if (keeps) "Bring Again from Stremio" else "Bring from Stremio", "Your stream addons and history from your Stremio account, with a code on your phone. While you still use Lumen, what you watch here goes back to Stremio.") { stremio.engine.bringFromStremio() })
            if (keeps) add(Item.Action("stopStremio", "Stop Keeping Stremio in Step", "Press Again to Stop", "What you watch here stops going to Stremio, and Lumen's watching stops coming here. Your history stays.") {
                stremio.engine.stopKeepingStremio()
            })
            add(Item.Action("signOut", "Sign Out of ${com.notdxbi.dxbi.tv.BuildConfig.APP_NAME}", "Press Again to Sign Out", "This TV signs out. Your profiles and history stay on your account; sign in again with a code from your iPhone.") { stremio.signOut() })
        }
        Section.Addons -> buildList {
            addons.forEach { (url, name) ->
                add(Item.Action("addon:$url", name, "Press Again to Remove", "Press OK twice to remove this addon from this TV. Bring from Stremio adds your addons again.") {
                    stremio.engine.removeAddon(url)
                    scope.launch { reload() }
                })
            }
            add(Item.Link("addAddons", "Add from Stremio", "Your Stremio account's stream addons, with a code on your phone.") { stremio.engine.bringFromStremio() })
        }
        Section.LiveTV -> liveTvSectionItems(context, stremio, scope)
        Section.About -> listOf(
            Item.Info("version", "Version", com.notdxbi.dxbi.tv.BuildConfig.APP_NAME + " " + BuildConfigVersion.name(context), "No Stremio page underneath: it talks to your addons and TorBox itself."),
            Item.Info("speed", "This connection", speed ?: "Not measured yet", "From Match my internet speed's last check. Settings → Playback → Networks has more."),
            Item.Info("people", "Profiles", "${viewers.size}"),
            Item.Info("addons", "Addons", "${addons.size}"),
            Item.Link("report", "Send a Problem Report", "Something went wrong? This sends Harry the last 30 minutes of what ${com.notdxbi.dxbi.tv.BuildConfig.APP_NAME} did (passwords and keys taken out) and shows a reference to tell him.") {
                (context as? android.app.Activity)?.let { ProblemReport.send(it, stremio.engine) }
            },
        )
    }

    Row(Modifier.fillMaxSize().background(Dx.Background)) {
        // ---- the sections ----
        Column(Modifier.width(400.dp).fillMaxHeight()) {
            ScreenTag(nav)
            Text("Settings", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(start = Dx.Gutter, top = 10.dp, bottom = 14.dp))
            Column(Modifier.padding(start = Dx.Gutter - 14.dp, end = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Section.entries.forEach { s ->
                    SectionRow(
                        s, chosen = s == section,
                        modifier = Modifier
                            .focusRequester(sectionFocus.getValue(s))
                            // (into the settings: the first of them; safe while they're still coming in)
                            .onPreviewKeyEvent { e ->
                                e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight && runCatching { rightFirst.requestFocus() }.isSuccess
                            }
                            .onFocusChanged {
                                if (it.isFocused) {
                                    if (section != s) { section = s; choosing = null }
                                    inRight = false
                                    help = ""
                                }
                            },
                        onClick = { runCatching { rightFirst.requestFocus() } },
                    )
                }
            }
        }

        // ---- what's in it ----
        Column(Modifier.weight(1f).fillMaxHeight().padding(top = 100.dp, end = Dx.Gutter)) {
          Box(Modifier.weight(1f)) {
           CompositionLocalProvider(LocalBringIntoViewSpec provides KeepInView) {
            AnimatedContent(
                targetState = section to choosing,
                transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(120)) },
                label = "settings",
            ) { (shown, open) ->
                val back = Modifier.focusProperties { left = sectionFocus.getValue(shown) }
                if (open != null) {
                    Column {
                        Text(open.label.uppercase(), fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp, color = Dx.Muted, modifier = Modifier.padding(start = 18.dp, bottom = 10.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 60.dp)) {
                            itemsIndexed(open.options, key = { _, o -> o.first.toString() }) { i, (value, label) ->
                                val current = value?.toString() == open.value?.toString()
                                SettingLine(
                                    label, value = null, tick = current,
                                    modifier = back.then(if (current || (i == 0 && open.options.none { it.first?.toString() == open.value?.toString() })) Modifier.focusRequester(rightFirst) else Modifier)
                                        .onFocusChanged { if (it.isFocused) { inRight = true; help = open.description } },
                                    onClick = {
                                        save(open, value)
                                        refocus = open.key
                                        choosing = null
                                    },
                                )
                            }
                        }
                    }
                    // (straight onto the one in use)
                    LaunchedEffect(open.key) { delay(80); runCatching { rightFirst.requestFocus() } }
                } else {
                    val list = if (shown == section) items else emptyList()
                    Column {
                    Text(shown.title.uppercase(), fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp, color = Dx.Muted, modifier = Modifier.padding(start = 18.dp, bottom = 10.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 60.dp)) {
                        // (no keys: the settings arriving after "Networks" mustn't push the list down past them)
                        itemsIndexed(list) { i, item ->
                            var armed by remember(item.key) { mutableStateOf(false) }
                            val mod = back
                                .focusRequester(rowRequester(item.key))
                                .then(if (i == 0) Modifier.focusRequester(rightFirst) else Modifier)
                                .onFocusChanged {
                                    if (it.isFocused) { inRight = true; help = item.help } else armed = false
                                }
                            when (item) {
                                is Item.Value -> {
                                    val s = item.setting
                                    val shownValue = when (s.type) {
                                        "toggle" -> if (s.value == true) "On" else "Off"
                                        else -> s.options.firstOrNull { it.first?.toString() == s.value?.toString() }?.second ?: s.value?.toString().orEmpty()
                                    }
                                    SettingLine(s.label, shownValue, chevron = s.type == "select", modifier = mod, onClick = {
                                        when (s.type) {
                                            "toggle" -> save(s, s.value != true)
                                            "select" -> choosing = s
                                        }
                                    })
                                }
                                is Item.Link -> SettingLine(item.title, null, chevron = true, modifier = mod, onClick = item.action)
                                is Item.Action -> SettingLine(if (armed) item.confirm else item.title, null, warn = armed, modifier = mod, onClick = {
                                    if (armed) { armed = false; item.action() } else armed = true
                                })
                                is Item.Toggle -> SettingLine(item.title, if (item.on) "On" else "Off", modifier = mod, onClick = item.onToggle)
                                is Item.Info -> SettingLine(item.title, item.value, modifier = mod, onClick = {})
                            }
                        }
                    }
                    }
                }
            }
           }
          }
            // what the setting the remote's on does (tvOS shows it under the list)
            Text(
                help, fontSize = 13.sp, color = Dx.Muted, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 18.dp, end = 40.dp, top = 6.dp, bottom = 22.dp).height(38.dp),
            )
        }
    }
}

/** Short lists: scrolled only as far as needed to show the row the remote's on (Android TV's own keeps it a third of the way down) */
@OptIn(ExperimentalFoundationApi::class)
private object KeepInView : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val end = offset + size
        return when {
            offset < 0 -> offset
            end > containerSize -> end - containerSize
            else -> 0f
        }
    }
}

/** A section down the left: its icon and name; white when the remote's on it, a soft capsule on the one shown */
@Composable
private fun SectionRow(s: Section, chosen: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (chosen) Color.White.copy(alpha = 0.10f) else Color.Transparent,
            contentColor = if (chosen) Color.White else Color.White.copy(alpha = 0.72f),
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = Dx.FocusRow),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(s.icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Text(s.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(26.dp), tint = LocalContentColor.current.copy(alpha = 0.5f))
        }
    }
}

/** A line on the right: its name, and its value (dimmer) or a tick, or ›; red words when pressing again can't be undone */
@Composable
private fun SettingLine(
    title: String,
    value: String?,
    modifier: Modifier,
    tick: Boolean = false,
    chevron: Boolean = false,
    warn: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.12f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = Dx.FocusRow),
    ) {
        val ink = LocalContentColor.current
        Row(Modifier.padding(horizontal = 18.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (warn) Color(0xFFE5484D) else ink, modifier = Modifier.weight(1f),
            )
            if (value != null) {
                Spacer(Modifier.width(16.dp))
                Text(value, fontSize = 16.sp, color = ink.copy(alpha = 0.6f), maxLines = 1)
            }
            if (tick) Icon(Icons.Rounded.Check, contentDescription = "In use", modifier = Modifier.size(22.dp))
            if (chevron) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(28.dp), tint = ink.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
internal fun Heading(text: String, top: Int) {
    Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(start = Dx.Gutter, top = top.dp, bottom = 6.dp))
}

@Composable
private fun liveTvSectionItems(
    context: android.content.Context,
    stremio: Stremio,
    scope: kotlinx.coroutines.CoroutineScope,
): List<Item> {
    val liveStore = remember { com.notdxbi.dxbi.tv.live.LiveTvRuntime.store(context, stremio.engine) }
    LaunchedEffect(liveStore) { liveStore.ensureLoaded() }
    val sources by liveStore.sources.collectAsState()
    val reports by liveStore.sourceReports.collectAsState()
    val uiState by liveStore.uiState.collectAsState()
    val pairing by liveStore.setupPairing.collectAsState()

    pairing?.let { activePairing ->
        com.notdxbi.dxbi.tv.lumen.live.QrPairingModal(
            pairing = activePairing,
            onClose = { liveStore.stopQrSetup() },
        )
    }

    val reportById = reports.associateBy { it.sourceId }
    val enabledCount = sources.count { it.enabled }

    return buildList {
        add(
            Item.Info(
                key = "live:count",
                title = "Configured sources",
                value = "${sources.size} ($enabledCount enabled)",
                help = "Synced IPTV playlists, Xtream accounts, and Stalker portals on your Lumen account.",
            ),
        )
        add(
            Item.Link(
                key = "live:refresh",
                title = if (uiState.refreshing) "Refreshing channels & EPG…" else "Refresh Live TV",
                help = "Reload enabled IPTV playlists, portal channels, and UK/provider TV guides now.",
            ) {
                liveStore.refreshAll(forceEpg = true)
            },
        )
        add(
            Item.Link(
                key = "live:qr",
                title = if (pairing != null) "Close (${pairing!!.code})" else "Add a TV provider",
                help = "Scan a QR code with your phone to submit Xtream, M3U, or Stalker credentials directly to this TV.",
            ) {
                if (pairing != null) liveStore.stopQrSetup() else liveStore.startQrSetup()
            },
        )
        sources.forEach { src ->
            val kind = when (src) {
                is lumos.iptv.IptvSource.Xtream -> "Xtream"
                is lumos.iptv.IptvSource.M3u -> "M3U"
                is lumos.iptv.IptvSource.Stalker -> "Stalker"
            }
            val report = reportById[src.id]
            val detail = when {
                !src.enabled -> "$kind source is disabled."
                report == null -> "$kind source (${src.maxConnections} conn)."
                report.success -> "$kind · ${report.streamCount} streams loaded."
                else -> "$kind error: ${report.error ?: "failed to load"}"
            }
            add(
                Item.Toggle(
                    key = "live:toggle:${src.id}",
                    title = "${src.name} ($kind)",
                    on = src.enabled,
                    help = detail,
                ) {
                    scope.launch { liveStore.toggleSource(src.id, !src.enabled) }
                },
            )
            add(
                Item.Action(
                    key = "live:remove:${src.id}",
                    title = "Remove ${src.name}",
                    confirm = "Press Again to Remove",
                    help = "Press OK twice to remove ${src.name} from your synced IPTV sources.",
                ) {
                    scope.launch { liveStore.removeSource(src.id) }
                },
            )
        }
    }
}
