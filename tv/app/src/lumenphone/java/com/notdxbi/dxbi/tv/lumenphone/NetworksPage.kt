package com.notdxbi.dxbi.tv.lumenphone

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.notdxbi.dxbi.tv.phone.NetworkInfo
import com.notdxbi.dxbi.tv.phone.Stremio
import kotlinx.coroutines.launch

/**
 * Networks (iOS NetworksView): the speed Lumen has learnt on each connection from what streams really got (so a
 * known one needs no speed test) and a quality cap for each. A slow reading isn't trusted until it's been seen
 * three times: until then it says "Checking". Rename gives a network a name (Home, Office), Measure Again clears
 * what was learnt and Forget removes the network. All of it is kept by dx.bi's page (dxbi.networks), the same
 * memory the iPad and TV use; this page only shows and changes it.
 */
@Composable
fun NetworksPage(stremio: Stremio, layout: AppLayout, bottomSpace: Dp, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var networks by remember { mutableStateOf<List<NetworkInfo>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<NetworkInfo?>(null) }
    var newName by remember { mutableStateOf("") }
    var forgetting by remember { mutableStateOf<NetworkInfo?>(null) }

    suspend fun reload() {
        networks = runCatching { stremio.networks() }.getOrDefault(networks)
        loaded = true
    }
    // (a change, then what's there now)
    fun change(block: suspend () -> Unit) { scope.launch { block(); reload() } }
    LaunchedEffect(Unit) { reload() }

    ProfilePage("Networks", layout, bottomSpace, onBack) {
        item {
            Text(
                "You're on " + if (onMobileData(context)) "mobile data." else "Wi-Fi, or a connection that isn't metered.",
                color = Dx.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (loaded && networks.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No Networks Yet", color = Dx.Text, style = MaterialTheme.typography.titleLarge)
                Text(
                    "Play something, and the speed it really gets on this network is remembered here. Match my internet speed (Lumen Settings) uses it.",
                    color = Dx.Muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, start = 24.dp, end = 24.dp),
                )
            }
        }
        networks.forEach { n ->
            item(key = n.id) {
                ProfileGroup(header = if (n.current) "${n.title} · Now" else n.title, footer = detail(n)) {
                    choose(
                        "Highest quality here", shown = capName(n.cap),
                        options = listOf(0 to "Auto (its speed)", 2160 to "4K", 1080 to "1080p", 720 to "720p"),
                    ) { cap -> change { stremio.setNetwork(n.id, cap = cap) } }
                    link("Rename", icon = Icons.Filled.Edit, chevron = false) { newName = n.name; renaming = n }
                    link("Measure Again", icon = Icons.Filled.Refresh, chevron = false) { change { stremio.setNetwork(n.id, relearn = true) } }
                    link("Forget", icon = Icons.Filled.Delete, chevron = false, destructive = true) { forgetting = n }
                }
            }
        }
    }

    renaming?.let { n ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            containerColor = Color(0xFF2A2A2A),
            title = { Text("Name This Network", color = Dx.Text) },
            text = { ProfileField(newName, { newName = it.take(40) }, "Home, Office…", onDone = { change { stremio.setNetwork(n.id, name = newName) }; renaming = null }) },
            confirmButton = { TextButton(onClick = { change { stremio.setNetwork(n.id, name = newName) }; renaming = null }) { Text("Save", color = Dx.Text) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel", color = Dx.Muted) } },
        )
    }
    forgetting?.let { n ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            containerColor = Color(0xFF2A2A2A),
            title = { Text("Forget ${n.title}?", color = Dx.Text) },
            text = { Text("Lumen measures it again the next time something plays on it, and its quality cap goes.", color = Dx.Muted) },
            confirmButton = { TextButton(onClick = { change { stremio.forgetNetwork(n.id) }; forgetting = null }) { Text("Forget", color = Dx.Problem) } },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Cancel", color = Dx.Text) } },
        )
    }
}

private fun capName(cap: Int) = when (cap) { 2160 -> "4K"; 1080 -> "1080p"; 720 -> "720p"; else -> "Auto (its speed)" }

private fun detail(n: NetworkInfo): String {
    val speed = if (n.mbps > 0) "${Math.round(n.mbps)} Mbps" else "Not measured yet"
    val state = if (n.trusted) "from ${n.readings} reading${if (n.readings == 1) "" else "s"}" else "Checking: a slow reading is measured again"
    return "$speed · $state"
}
