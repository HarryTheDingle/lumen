package com.notdxbi.dxbi.tv.lumen

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.notdxbi.dxbi.tv.phone.Addon
import com.notdxbi.dxbi.tv.phone.NetworkInfo
import com.notdxbi.dxbi.tv.phone.Stremio
import com.notdxbi.dxbi.tv.phone.objects
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A row in Settings that opens a page: its name and what's there (white with dark text when the remote's on it) */
@Composable
internal fun LinkRow(title: String, note: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = Dx.Gutter - 14.dp).widthIn(max = 788.dp).fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        val ink = LocalContentColor.current
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(note, fontSize = 12.sp, color = ink.copy(alpha = 0.6f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(18.dp))
            Text("›", fontSize = 20.sp, color = ink.copy(alpha = 0.6f))
        }
    }
}

private fun toast(context: android.content.Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

// -------------------------------------------------------------------------------------------------
// Networks

private val CAPS = listOf(0 to "Auto (its speed)", 2160 to "4K", 1080 to "1080p", 720 to "720p")

/**
 * Settings → Networks: the speed Lumen has learnt on each connection (so a known one needs no speed
 * test), and a quality cap for each. (These are internet connections, not channels.)
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun NetworksScreen(stremio: Stremio) {
    var networks by remember { mutableStateOf<List<NetworkInfo>?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { networks = stremio.networks() }
    val nameBox = remember { FocusRequester() }
    // the remote starts on the first network's first button, and moves to the name box when it opens (and back after)
    LaunchedEffect(networks?.size, renaming) {
        delay(80)
        runCatching { if (renaming != null) nameBox.requestFocus() else first.requestFocus() }
    }

    fun update(work: suspend () -> Unit) {
        scope.launch {
            work()
            networks = stremio.networks()
        }
    }

    Box(Modifier.fillMaxSize().background(Dx.Background)) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides CentredScrolling) {
            LazyColumn(contentPadding = PaddingValues(bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "title") { Heading("Networks", top = 40) }
                item(key = "note") {
                    Text(
                        "The speed Lumen has learnt on each connection, from what streams really got there. Match my internet speed uses it.",
                        fontSize = 13.sp, color = Dx.Muted, modifier = Modifier.padding(horizontal = Dx.Gutter).widthIn(max = 760.dp),
                    )
                }
                val list = networks
                if (list == null) {
                    item(key = "loading") { Text("Loading…", fontSize = 15.sp, color = Dx.Muted, modifier = Modifier.padding(horizontal = Dx.Gutter, vertical = 20.dp)) }
                } else if (list.isEmpty()) {
                    item(key = "none") {
                        Text("No networks yet. Play something, and the speed it really gets here is remembered.", fontSize = 15.sp, color = Dx.Muted, modifier = Modifier.padding(horizontal = Dx.Gutter, vertical = 20.dp).widthIn(max = 760.dp))
                    }
                } else {
                    items(list, key = { it.id }) { n ->
                        val speed = if (n.mbps > 0) "${Math.round(n.mbps)} Mbps" else "Not measured yet"
                        val state = if (n.trusted) "from ${n.readings} reading${if (n.readings == 1) "" else "s"}" else "Checking: a slow reading is measured again"
                        Column(Modifier.padding(horizontal = Dx.Gutter).padding(top = 14.dp).widthIn(max = 900.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(n.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                if (n.current) Text("  ·  Now", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.85f))
                            }
                            Text("$speed · $state", fontSize = 13.sp, color = Dx.Muted, modifier = Modifier.padding(bottom = 8.dp))
                            val isFirst = list.first() == n
                            if (renaming == n.id) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(Modifier.width(380.dp).focusRequester(nameBox).focusGroup()) { Field("Home, Office…", newName, { newName = it }, KeyboardType.Text) }
                                    Pill("Save", selected = false, onClick = {
                                        renaming = null
                                        update { stremio.setNetwork(n.id, name = newName) }
                                    })
                                    Pill("Cancel", selected = false, onClick = { renaming = null })
                                }
                            } else {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Pill(
                                        "Highest quality: " + (CAPS.firstOrNull { it.first == n.cap }?.second ?: "Auto"),
                                        selected = false,
                                        // (each OK moves on to the next cap)
                                        onClick = {
                                            val i = CAPS.indexOfFirst { it.first == n.cap }
                                            update { stremio.setNetwork(n.id, cap = CAPS[(i + 1) % CAPS.size].first) }
                                        },
                                        modifier = if (isFirst) Modifier.focusRequester(first) else Modifier,
                                    )
                                    Pill("Rename", selected = false, onClick = { newName = n.name; renaming = n.id })
                                    Pill("Measure Again", selected = false, onClick = { update { stremio.setNetwork(n.id, relearn = true) } })
                                    Pill("Forget", selected = false, onClick = { update { stremio.forgetNetwork(n.id) } })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Addons

/** Settings → Addons: the installed ones (OK to remove), Stremio's official and community lists, and adding one by its link */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun AddonsScreen(stremio: Stremio) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf(0) }
    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<Addon?>(null) }
    var confirm by remember { mutableStateOf<Addon?>(null) }
    val installed by stremio.installedAddons.collectAsState()
    val remote by stremio.remoteAddons.collectAsState()
    val first = remember { FocusRequester() }

    fun catalog(i: Int) = if (i == 1) "official" else "community"
    LaunchedEffect(list) { if (list == 0) stremio.loadAddons() else stremio.loadRemoteAddons(catalog(list)) }
    FocusOnOpen(first)

    fun act(addon: Addon, install: Boolean) {
        busy = true
        scope.launch {
            val problem = if (install) stremio.install(addon) else stremio.uninstall(addon)
            busy = false
            toast(context, problem ?: if (install) "${addon.name} installed" else "${addon.name} removed")
            stremio.loadAddons()
            if (list != 0) stremio.loadRemoteAddons(catalog(list))
        }
    }

    val installedUrls = installed.map { it.transportUrl }.toSet()
    val addons = if (list == 0) installed else remote?.optJSONArray("items").objects().map(Addon::from).map { it.copy(installed = it.transportUrl in installedUrls) }
    val loading = list != 0 && (remote == null || remote?.optString("state") == "Loading") && addons.isEmpty()

    Box(Modifier.fillMaxSize().background(Dx.Background)) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides CentredScrolling) {
            LazyColumn(contentPadding = PaddingValues(bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item(key = "title") { Heading("Addons", top = 40) }
                item(key = "lists") {
                    Row(Modifier.padding(horizontal = Dx.Gutter - 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("Installed", "Official", "Community").forEachIndexed { i, name ->
                            Pill(name, selected = list == i, onClick = { list = i }, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier)
                        }
                    }
                }
                if (list == 0) {
                    item(key = "link") {
                        Row(Modifier.padding(horizontal = Dx.Gutter).padding(vertical = 10.dp).widthIn(max = 900.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Field("Add from a link: https://…/manifest.json", link, { link = it }, KeyboardType.Uri, Modifier.width(560.dp))
                            Pill("Install", selected = false, onClick = {
                                if (link.isBlank() || busy) return@Pill
                                busy = true
                                scope.launch {
                                    val problem = stremio.installFromLink(link.trim())
                                    busy = false
                                    if (problem == null) link = ""
                                    toast(context, problem ?: "Installed")
                                    stremio.loadAddons()
                                }
                            })
                        }
                    }
                }
                if (loading) item(key = "loading") { Text("Loading…", fontSize = 15.sp, color = Dx.Muted, modifier = Modifier.padding(horizontal = Dx.Gutter, vertical = 20.dp)) }
                items(addons, key = { "$list-${it.transportUrl}" }) { a -> AddonRow(a) { menu = a } }
            }
        }
    }

    menu?.let { a ->
        val actions = buildList {
            if (a.installed) {
                if (!a.protected) add(Action("Remove") { confirm = a })
            } else {
                add(Action("Install") { act(a, true) })
            }
        }
        if (actions.isEmpty()) {
            toast(context, "${a.name} is built in, so it can't be removed")
            menu = null
        } else {
            ActionMenu(a.name, actions, onDismiss = { menu = null })
        }
    }
    confirm?.let { a ->
        ActionMenu("Remove ${a.name}?", listOf(Action("Keep it") {}, Action("Remove") { act(a, false) }), onDismiss = { confirm = null })
    }
}

/** One addon: its logo, name and what it does */
@Composable
private fun AddonRow(a: Addon, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = Dx.Gutter - 14.dp).widthIn(max = 900.dp).fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        val ink = LocalContentColor.current
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.1f))) {
                if (a.logo != null) AsyncImage(model = a.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(a.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(a.description, fontSize = 12.sp, color = ink.copy(alpha = 0.6f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(18.dp))
            Text(if (a.installed) "Installed" else "Get", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = ink.copy(alpha = 0.75f))
        }
    }
}
