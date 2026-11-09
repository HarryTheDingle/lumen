package com.notdxbi.dxbi.tv.lumenphone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.notdxbi.dxbi.tv.phone.Setting
import com.notdxbi.dxbi.tv.phone.Stremio
import kotlinx.coroutines.launch

/**
 * What only changes how Stremio's page looks: Lumen's own screens don't use these, so they aren't listed. The same
 * ones iOS hides (ios/Dxbi/UI/AccountView.swift), and the TV's torrent switch, which is the TV's.
 */
private val WEB_ONLY = setOf(
    "liteMode", "builtInTheme", "previews", "ratings", "animeTitles", "wideContinue", "librarySort", "trailers", "trailerDelay",
    "whoIsWatching", "addonTools", "posterAccent", "crispPosters", "streamTools", "streamRows", "splash", "introSound",
    "profileSync", "uiSounds", "focusColour", "videoRowsOnly", "newEpisodes", "accent", "tvTorrents",
)

/** Lumen Settings: the same list the TV and iOS show, by group (iOS DxbiSettingsView): switches, choices and typed values */
@Composable
fun SettingsLumenPage(stremio: Stremio, layout: AppLayout, bottomSpace: Dp, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Setting>?>(null) }
    var editing by remember { mutableStateOf<Setting?>(null) }
    LaunchedEffect(Unit) { list = stremio.settings().filter { it.key !in WEB_ONLY && it.type in setOf("toggle", "select", "input") } }

    fun save(s: Setting, value: Any?) {
        // shown at once, saved behind
        list = list?.map { if (it.key == s.key) it.copy(value = value) else it }
        scope.launch { stremio.saveSetting(s.key, value) }
    }

    ProfilePage("Lumen Settings", layout, bottomSpace, onBack) {
        val items = list
        if (items == null) {
            item { Box(Modifier.fillMaxWidth().padding(60.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Dx.Text) } }
        } else {
            val groups = items.map { it.group }.distinct()
            groups.forEach { group ->
                item(key = "group-$group") {
                    ProfileGroup(header = group.ifEmpty { "General" }) {
                        items.filter { it.group == group }.forEach { s ->
                            when (s.type) {
                                "toggle" -> toggle(s.label, s.description.ifEmpty { null }, s.value == true) { save(s, it) }
                                "select" -> choose(
                                    s.label, s.description.ifEmpty { null },
                                    shown = s.options.firstOrNull { it.first?.toString() == s.value?.toString() }?.second ?: s.value?.toString().orEmpty(),
                                    options = s.options,
                                ) { save(s, it) }
                                else -> link(
                                    s.label, s.description.ifEmpty { null },
                                    // (a key is never shown: it's typed in and kept)
                                    value = if ((s.value as? String).isNullOrEmpty()) "Not set" else "Set", chevron = false,
                                ) { editing = s }
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { s ->
        var text by remember(s.key) { mutableStateOf((s.value as? String).orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = Color(0xFF2A2A2A),
            title = { Text(s.label, color = Dx.Text) },
            text = {
                Column {
                    if (s.description.isNotEmpty()) Text(s.description, color = Dx.Muted, modifier = Modifier.padding(bottom = 12.dp))
                    ProfileField(text, { text = it }, "Not set")
                }
            },
            confirmButton = { TextButton(onClick = { save(s, text.trim()); editing = null }) { Text("Save", color = Dx.Text) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel", color = Dx.Muted) } },
        )
    }
}
