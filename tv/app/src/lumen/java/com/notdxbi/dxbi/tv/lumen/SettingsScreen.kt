package com.notdxbi.dxbi.tv.lumen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.notdxbi.dxbi.tv.phone.Setting
import com.notdxbi.dxbi.tv.phone.Stremio
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * How Stremio's page looks: Lumen's own screens don't use these (the iPad hides the same ones:
 * ios/Dxbi/UI/AccountView.swift)
 */
private val WEB_ONLY = setOf(
    "liteMode", "builtInTheme", "previews", "ratings", "animeTitles", "wideContinue", "librarySort",
    "whoIsWatching", "addonTools", "posterAccent", "crispPosters", "streamTools", "streamRows", "splash", "introSound",
    "profileSync", "uiSounds", "focusColour", "videoRowsOnly", "newEpisodes", "accent", "trailers", "trailerDelay",
)

/**
 * Settings: who's signed in, then Lumen's settings by group, as the iPad lists them. A switch changes
 * with OK; a choice moves on to the next with each OK; a key or name is typed with the TV's keyboard.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(stremio: Stremio, nav: Nav) {
    val tasteState = LocalTaste.current
    val account by stremio.account.collectAsState()
    var settings by remember { mutableStateOf<List<Setting>?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { settings = stremio.settings().filter { it.key !in WEB_ONLY && it.type in setOf("toggle", "select", "input") } }

    fun save(s: Setting, value: Any?) {
        settings = settings?.map { if (it.key == s.key) it.copy(value = value) else it }
        scope.launch { stremio.saveSetting(s.key, value) }
    }

    Box(Modifier.fillMaxSize().background(Dx.Background)) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides CentredScrolling) {
            LazyColumn(contentPadding = PaddingValues(bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item(key = "top") { ScreenTag(nav) }
                item(key = "title") { Heading("Settings", top = 8) }
                item(key = "account") {
                    Row(Modifier.padding(horizontal = Dx.Gutter).widthIn(max = 760.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(account?.email, 44.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(account?.email ?: "Not signed in", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            Text("Stremio account", fontSize = 13.sp, color = Dx.Muted)
                        }
                        Pill("Profiles", selected = false, onClick = { nav.push(Page.Profiles) })
                        Spacer(Modifier.width(8.dp))
                        Pill("Sign Out", selected = false, onClick = { stremio.signOut() })
                    }
                }
                item(key = "pages") {
                    Column {
                        Heading("Manage", top = 26)
                        LinkRow("Tune Suggestions", "Tell Lumen what you like so Home suggests more of it") { tasteState?.tuning = true }
                        LinkRow("Addons", "What Stremio finds streams and catalogues with") { nav.push(Page.Addons) }
                        LinkRow("Networks", "The speed Lumen has learnt on each Wi-Fi or cable connection") { nav.push(Page.Networks) }
                    }
                }
                val list = settings
                if (list == null) {
                    item(key = "loading") { Text("Loading…", fontSize = 15.sp, color = Dx.Muted, modifier = Modifier.padding(horizontal = Dx.Gutter, vertical = 20.dp)) }
                } else {
                    list.map { it.group }.distinct().forEach { group ->
                        item(key = "group-$group") { Heading(group.ifEmpty { "General" }, top = 26) }
                        items(list.filter { it.group == group }, key = { it.key }) { s -> SettingRow(s) { save(s, it) } }
                    }
                }
            }
        }
    }
}

@Composable
internal fun Heading(text: String, top: Int) {
    Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(start = Dx.Gutter, top = top.dp, bottom = 6.dp))
}

/** One setting: its name and what it does, and its value on the right */
@Composable
private fun SettingRow(s: Setting, onChange: (Any?) -> Unit) {
    if (s.type == "input") {
        // typed: saved a moment after the typing stops
        var text by remember(s.key) { mutableStateOf(s.value?.toString().orEmpty()) }
        LaunchedEffect(text) {
            if (text == s.value?.toString().orEmpty()) return@LaunchedEffect
            delay(800)
            onChange(text.trim())
        }
        Column(Modifier.padding(horizontal = Dx.Gutter, vertical = 4.dp).widthIn(max = 760.dp)) {
            Text(s.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            if (s.description.isNotEmpty()) Text(s.description, fontSize = 12.sp, color = Dx.Muted, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 6.dp))
            Field(s.label, text, { text = it }, KeyboardType.Text)
        }
        return
    }
    val shown = when (s.type) {
        "toggle" -> if (s.value == true) "On" else "Off"
        else -> s.options.firstOrNull { it.first?.toString() == s.value?.toString() }?.second ?: s.value?.toString().orEmpty()
    }
    val shape = RoundedCornerShape(14.dp)
    Surface(
        onClick = {
            when (s.type) {
                "toggle" -> onChange(s.value != true)
                else -> if (s.options.isNotEmpty()) {
                    val i = s.options.indexOfFirst { it.first?.toString() == s.value?.toString() }
                    onChange(s.options[(i + 1) % s.options.size].first)
                }
            }
        },
        modifier = Modifier.padding(horizontal = Dx.Gutter - 14.dp).widthIn(max = 788.dp).fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(shape),
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
                Text(s.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (s.description.isNotEmpty()) Text(s.description, fontSize = 12.sp, color = ink.copy(alpha = 0.6f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(18.dp))
            Text(shown, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = ink.copy(alpha = 0.75f))
        }
    }
}
