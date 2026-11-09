package com.notdxbi.dxbi.tv.lumenphone

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.notdxbi.dxbi.tv.phone.Stremio
import org.json.JSONObject

/** The languages iOS offers (Stremio's own three-letter codes: "fre", "ger", "chi", so the account's other devices agree) */
private val LANGUAGES = listOf(
    "eng" to "English", "spa" to "Spanish", "fre" to "French", "ger" to "German", "ita" to "Italian", "por" to "Portuguese",
    "ara" to "Arabic", "urd" to "Urdu", "hin" to "Hindi", "tur" to "Turkish", "rus" to "Russian", "jpn" to "Japanese",
    "kor" to "Korean", "chi" to "Chinese", "dut" to "Dutch", "pol" to "Polish", "swe" to "Swedish", "per" to "Persian",
)

private fun languageName(code: String?) = if (code.isNullOrEmpty()) "Default" else LANGUAGES.firstOrNull { it.first == code }?.second ?: code

private val COLOURS = listOf("#FFFFFFFF" to "White", "#FFFF00FF" to "Yellow", "#000000FF" to "Black", "#808080FF" to "Grey", "#00000000" to "None")

/**
 * Playback (iOS PlaybackSettingsView): where videos play, the languages Stremio keeps for the account, how subtitles
 * look in Lumen's player, and the streaming server torrents need. These are Stremio's own settings (they follow the
 * account to its other devices), not Lumen's.
 */
@Composable
fun SettingsPlaybackPage(stremio: Stremio, layout: AppLayout, bottomSpace: Dp, onBack: () -> Unit) {
    val settings by stremio.profileSettings.collectAsState()
    val server by stremio.streamingServer.collectAsState()
    val other by stremio.playInOtherApp.collectAsState()
    var editingServer by remember { mutableStateOf(false) }
    fun set(key: String, value: Any?) = stremio.updateSettings(JSONObject().put(key, value ?: JSONObject.NULL))
    fun str(key: String) = settings.takeUnless { it.isNull(key) }?.optString(key)?.takeIf { it.isNotEmpty() }

    ProfilePage("Playback", layout, bottomSpace, onBack) {
        item {
            ProfileGroup(footer = "Lumen's player plays almost anything. Another app (VLC, mpv, MX Player…) keeps no watch progress in Stremio.") {
                choose("Play videos in", shown = if (other) "Another app" else "Lumen", options = listOf(false to "Lumen", true to "Another app (ask which)")) { stremio.setPlayInOtherApp(it) }
            }
        }
        item {
            ProfileGroup(header = "Languages") {
                choose("Audio", shown = languageName(str("audioLanguage")), options = LANGUAGES) { set("audioLanguage", it) }
                choose("Subtitles", shown = if (str("subtitlesLanguage") == null) "None" else languageName(str("subtitlesLanguage")), options = listOf<Pair<String?, String>>(null to "None") + LANGUAGES) { set("subtitlesLanguage", it) }
                choose("Second subtitles", "Used when the first isn't there", shown = if (str("secondarySubtitlesLanguage") == null) "None" else languageName(str("secondarySubtitlesLanguage")), options = listOf<Pair<String?, String>>(null to "None") + LANGUAGES) { set("secondarySubtitlesLanguage", it) }
            }
        }
        item {
            ProfileGroup(header = "Subtitle Look") {
                choose("Size", shown = "${settings.optInt("subtitlesSize", 100)}%", options = listOf(75, 100, 125, 150, 175, 200, 250).map { it to "$it%" }) { set("subtitlesSize", it) }
                toggle("Bold", on = settings.optBoolean("subtitlesBold")) { set("subtitlesBold", it) }
                choose("Text", shown = COLOURS.firstOrNull { it.first.equals(str("subtitlesTextColor"), true) }?.second ?: "Custom", options = COLOURS.dropLast(1)) { set("subtitlesTextColor", it) }
                choose("Background", shown = COLOURS.firstOrNull { it.first.equals(str("subtitlesBackgroundColor"), true) }?.second ?: "Custom", options = COLOURS) { set("subtitlesBackgroundColor", it) }
                choose("Outline", shown = COLOURS.firstOrNull { it.first.take(7).equals(str("subtitlesOutlineColor")?.take(7), true) }?.second ?: "Custom", options = COLOURS) { set("subtitlesOutlineColor", it) }
            }
        }
        item {
            val up = server?.optString("state") == "Ready"
            ProfileGroup(
                header = "Streaming Server",
                footer = "Torrents need Stremio (or Lumen) running on a computer: put its address here. Debrid streams play without one.",
            ) {
                link("Server", note = if (str("streamingServerUrl") != null) (if (up) "Connected" else "Not answering") else null, value = str("streamingServerUrl") ?: "Not set") { editingServer = true }
            }
        }
    }

    if (editingServer) {
        var url by remember { mutableStateOf(str("streamingServerUrl").orEmpty()) }
        AlertDialog(
            onDismissRequest = { editingServer = false },
            containerColor = Color(0xFF2A2A2A),
            title = { Text("Streaming Server", color = Dx.Text) },
            text = {
                Column {
                    Text("Its address, like http://192.168.1.10:11470", color = Dx.Muted, modifier = Modifier.padding(bottom = 12.dp))
                    ProfileField(url, { url = it }, "http://192.168.1.10:11470", keyboard = KeyboardType.Uri)
                }
            },
            confirmButton = { TextButton(onClick = { set("streamingServerUrl", url.trim()); editingServer = false }) { Text("Save", color = Dx.Text) } },
            dismissButton = { TextButton(onClick = { editingServer = false }) { Text("Cancel", color = Dx.Muted) } },
        )
    }
}
