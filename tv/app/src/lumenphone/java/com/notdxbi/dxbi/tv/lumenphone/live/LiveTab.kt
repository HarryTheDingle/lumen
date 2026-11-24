package com.notdxbi.dxbi.tv.lumenphone.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.notdxbi.dxbi.tv.live.LiveEngine
import com.notdxbi.dxbi.tv.live.LiveTvRuntime
import com.notdxbi.dxbi.tv.live.LiveTvStore
import com.notdxbi.dxbi.tv.lumenphone.AppLayout
import com.notdxbi.dxbi.tv.lumenphone.Dx
import com.notdxbi.dxbi.tv.phone.Stremio
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class LivePage(val label: String) { Guide("Guide"), Find("Find"), Sports("Sports") }

/**
 * Live TV, the fifth tab: signs in on its own with the Stremio account (Lumen's server knows it), asks for an IPTV
 * provider when there is none, then the guide, a channel finder and sports.
 */
@Composable
fun LiveTab(stremio: Stremio, layout: AppLayout, bottomSpace: Dp) {
    val context = LocalContext.current
    val engine = remember { LiveEngine.get(context) }
    val signedIn by engine.signedIn.collectAsState()
    val viewer by stremio.viewer.collectAsState()
    LaunchedEffect(viewer?.name) { engine.chooseViewerNamed(viewer?.name) }

    Box(Modifier.fillMaxSize().background(Dx.Background)) {
        if (!signedIn) LiveSignIn(stremio, engine, bottomSpace)
        else {
            val store = remember { LiveTvRuntime.store(context, engine) }
            LaunchedEffect(Unit) { store.ensureLoaded() }
            LiveSignedIn(stremio, store, engine, layout, bottomSpace)
        }
    }
}

@Composable
private fun LiveSignedIn(stremio: Stremio, store: LiveTvStore, engine: LiveEngine, layout: AppLayout, bottomSpace: Dp) {
    val sources by store.sources.collectAsState()
    val ui by store.uiState.collectAsState()
    var page by rememberSaveable { mutableStateOf(LivePage.Guide) }
    var settings by rememberSaveable { mutableStateOf(false) }
    val synced by remember { mutableStateOf(true) }

    if (settings) { LiveSettings(store, engine, layout, bottomSpace, onBack = { settings = false }); return }
    if (sources.isEmpty() && !ui.loading) { AddProvider(store, layout, bottomSpace, onSettings = { settings = true }); return }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().padding(start = layout.gutter, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Live TV", color = Dx.Text, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            Box(Modifier.clickable { settings = true }.padding(12.dp)) { Icon(Icons.Filled.Settings, "Live TV settings", tint = Dx.Muted) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = layout.gutter, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LivePage.entries.forEach { LiveChip(it.label, page == it, { page = it }) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (page) {
                LivePage.Guide -> GuidePage(store, layout, bottomSpace)
                LivePage.Find -> FindPage(store, layout, bottomSpace)
                LivePage.Sports -> SportsPage(store, layout, bottomSpace)
            }
        }
    }
}

/** Signing in to Live TV: the Stremio account first (no typing), a code approved on the TV if that fails */
@Composable
private fun LiveSignIn(stremio: Stremio, engine: LiveEngine, bottomSpace: Dp) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Setting up Live TV…") }
    var code by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(attempt) {
        failed = false; code = null; status = "Setting up Live TV…"
        // 1. the Stremio account this phone is signed in with
        val key = runCatching { stremio.authKey() }.getOrNull()
        if (key != null) {
            val session = engine.signInWithStremio(key)
            if (session != null) { engine.finishSignIn(session); return@LaunchedEffect }
        }
        // 2. a code approved on a device that already has Live TV (Lumen TV: Settings → Devices)
        status = "Waiting for approval"
        val pending = runCatching { engine.startSignIn() }.getOrNull()
        if (pending == null) { failed = true; status = "Couldn't reach Lumen's server. Check the connection."; return@LaunchedEffect }
        code = pending.code
        while (System.currentTimeMillis() < pending.expires) {
            delay(3_000)
            val session = runCatching { engine.pollSignIn(pending) }.getOrNull()
            if (session != null) { engine.finishSignIn(session); return@LaunchedEffect }
        }
        failed = true; status = "The code ran out."
    }

    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Live TV", color = Dx.Text, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        if (code == null && !failed) { LiveSpinner(); Spacer(Modifier.height(12.dp)); Text(status, color = Dx.Muted) }
        code?.let { c ->
            Text("This Stremio account hasn't used Lumen's Live TV yet.", color = Dx.Muted, style = MaterialTheme.typography.bodyMedium)
            Text("On a device already signed in to Lumen, open Settings and approve this code:", color = Dx.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            Text(c, color = Dx.Text, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 18.dp))
            Text(status, color = Dx.Faint, style = MaterialTheme.typography.bodySmall)
        }
        if (failed) {
            Text(status, color = Dx.Muted)
            Spacer(Modifier.height(16.dp))
            LiveButton("Try again", onClick = { attempt++ })
        }
        Spacer(Modifier.height(bottomSpace))
    }
}
