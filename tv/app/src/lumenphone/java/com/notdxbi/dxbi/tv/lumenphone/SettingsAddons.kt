package com.notdxbi.dxbi.tv.lumenphone

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.notdxbi.dxbi.tv.phone.Addon
import com.notdxbi.dxbi.tv.phone.Stremio
import com.notdxbi.dxbi.tv.phone.objects
import kotlinx.coroutines.launch

/** Addons (iOS AddonsView): the installed ones, Stremio's Official and Community lists, and adding one from its link */
@Composable
fun SettingsAddonsPage(stremio: Stremio, layout: AppLayout, bottomSpace: Dp, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf(0) }
    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<Addon?>(null) }
    val installed by stremio.installedAddons.collectAsState()
    val remote by stremio.remoteAddons.collectAsState()

    fun catalog() = if (list == 1) "official" else "community"
    LaunchedEffect(list) { if (list == 0) stremio.loadAddons() else stremio.loadRemoteAddons(catalog()) }
    fun say(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    fun act(key: String, work: suspend () -> String?, done: String) {
        busy = key
        scope.launch {
            val problem = work()
            busy = null
            say(problem ?: done)
            if (problem == null && key == "link") link = ""
            if (list == 0) stremio.loadAddons() else stremio.loadRemoteAddons(catalog())
        }
    }

    val installedUrls = installed.map { it.transportUrl }.toSet()
    val shown = if (list == 0) installed else remote?.optJSONArray("items").objects().map(Addon::from).map { it.copy(installed = it.transportUrl in installedUrls) }
    val loading = list != 0 && (remote == null || remote?.optString("state") == "Loading") && shown.isEmpty()

    ProfilePage("Addons", layout, bottomSpace, onBack) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally)) {
                listOf("Installed", "Official", "Community").forEachIndexed { i, name ->
                    Text(
                        name, color = if (list == i) Dx.Text else Dx.Muted, style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (list == i) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.clickable { list = i }.padding(vertical = 8.dp, horizontal = 4.dp),
                    )
                }
            }
        }
        if (list == 0) {
            item {
                ProfileGroup(header = "Add from a link") {
                    row {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { ProfileField(link, { link = it }, "https://…/manifest.json", keyboard = KeyboardType.Uri) }
                            ProfileTextButton("Install", enabled = link.isNotBlank() && busy == null) { act("link", { stremio.installFromLink(link.trim()) }, "Installed") }
                        }
                    }
                }
            }
        }
        if (loading) item { Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Dx.Text) } }
        if (shown.isNotEmpty()) item(key = "addons-$list") {
            ProfileGroup {
                shown.forEach { a ->
                    row {
                        AddonRow(a, busy == a.transportUrl, onGet = { act(a.transportUrl, { stremio.install(a) }, "${a.name} installed") },
                            onConfigure = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.configureUrl))) } },
                            onUninstall = { removing = a })
                    }
                }
            }
        }
    }

    removing?.let { a ->
        AlertDialog(
            onDismissRequest = { removing = null },
            containerColor = Color(0xFF2A2A2A),
            title = { Text("Uninstall ${a.name}?", color = Dx.Text) },
            text = { Text("Its catalogs and streams will be gone from Lumen and Stremio on all your devices.", color = Dx.Muted) },
            confirmButton = { TextButton(onClick = { removing = null; act(a.transportUrl, { stremio.uninstall(a) }, "${a.name} removed") }) { Text("Uninstall", color = Dx.Problem) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel", color = Dx.Text) } },
        )
    }
}

@Composable
private fun AddonRow(a: Addon, busy: Boolean, onGet: () -> Unit, onConfigure: () -> Unit, onUninstall: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(Dx.Line), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Extension, contentDescription = null, tint = Dx.Faint)
            if (a.logo != null) AsyncImage(model = a.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.matchParentSize().padding(3.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(a.name, color = Dx.Text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (a.description.isNotBlank()) Text(a.description, color = Dx.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        when {
            busy -> CircularProgressIndicator(color = Dx.Text, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            a.installed -> Box {
                Icon(Icons.Filled.MoreHoriz, contentDescription = "More", tint = Dx.Text, modifier = Modifier.clip(CircleShape).clickable { menu = true }.padding(6.dp).size(24.dp))
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Color(0xFF2A2A2A)) {
                    if (a.configurable) DropdownMenuItem(text = { Text("Configure", color = Dx.Text) }, onClick = { menu = false; onConfigure() })
                    if (!a.protected) DropdownMenuItem(text = { Text("Uninstall", color = Dx.Problem) }, onClick = { menu = false; onUninstall() })
                    if (!a.configurable && a.protected) DropdownMenuItem(text = { Text("Built in", color = Dx.Muted) }, onClick = { menu = false })
                }
            }
            !a.configurationRequired -> Text(
                "Get", color = Dx.OnSelected, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(CircleShape).background(Dx.Selected).clickable(onClick = onGet).padding(horizontal = 16.dp, vertical = 7.dp),
            )
            // (an addon that must be set up on its own page first can't be installed from here)
            else -> Text("Set up first", color = Dx.Faint, style = MaterialTheme.typography.bodySmall)
        }
    }
}
