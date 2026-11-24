package com.notdxbi.dxbi.tv.lumen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import com.notdxbi.dxbi.tv.phone.Stremio
import com.notdxbi.dxbi.tv.phone.Viewer

/** Lumen's viewer colours (the plugin accepts only these) */
val ProfileColours = listOf("#e50914", "#2f80ff", "#1db954", "#f5a524", "#8b5cf6", "#ec4899", "#14b8a6", "#f97316")

private fun profileColour(hex: String?): Color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Dx.Raised2)

/** A profile's circle: its colour and first letter, as on Apple TV */
@Composable
fun ProfileAvatar(name: String, colour: String?, size: Dp = 36.dp) {
    val c = profileColour(colour)
    Box(
        Modifier.size(size).background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(c, c.copy(alpha = 0.62f))), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.trim().take(1).uppercase(), fontSize = (size.value * 0.46f).sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

/** One round choice on the picker: white ring and a little growth when the remote's on it */
@Composable
private fun Circle(onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
        border = ClickableSurfaceDefaults.border(
            border = if (selected) Border(BorderStroke(2.dp, Color.White.copy(alpha = 0.5f))) else Border.None,
            focusedBorder = Border(BorderStroke(4.dp, Color.White)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = Dx.FocusRound),
    ) { content() }
}

/**
 * Who's watching? A person each, with their own Continue Watching, watched marks and picks (as on the iPad:
 * ios/Dxbi/UI/ProfileViews.swift). Opens when the app starts with more than one, and from the menu or
 * Settings. Manage adds, renames and removes them.
 */
@Composable
fun ProfilePicker(stremio: Stremio, onDone: () -> Unit) {
    val viewers by stremio.viewers.collectAsState()
    val current by stremio.viewer.collectAsState()
    var managing by remember { mutableStateOf(false) }
    // null: the list; a Viewer or NEW: the editor
    var screen by remember { mutableStateOf<Any?>(null) }
    val first = remember { FocusRequester() }

    Box(Modifier.fillMaxSize().background(Dx.Background), contentAlignment = Alignment.Center) {
        LumenClock(Modifier.align(Alignment.TopEnd))
        when (val s = screen) {
            is Viewer, NEW -> ProfileEditor(stremio, s as? Viewer, viewers.size) { screen = null }
            else -> {
                FocusOnOpen(first, viewers.size)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Who's watching?", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.height(40.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.Top) {
                        viewers.forEachIndexed { i, v ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Circle(
                                    onClick = { if (managing) screen = v else { stremio.chooseViewer(v.id); onDone() } },
                                    selected = v.id == current?.id && !managing,
                                    modifier = Modifier.then(if (i == 0 || v.id == current?.id) Modifier.focusRequester(first) else Modifier),
                                ) { ProfileAvatar(v.name, v.colour, 112.dp) }
                                Text(if (managing) "Edit" else v.name, fontSize = 15.sp, color = if (managing) Dx.Muted else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 128.dp))
                                if (managing) Text(v.name, fontSize = 13.sp, color = Dx.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 128.dp))
                            }
                        }
                        if (viewers.size < 8) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Circle(onClick = { screen = NEW }) {
                                Box(Modifier.size(112.dp).background(Color.White.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.Add, contentDescription = "Add profile", tint = Color.White, modifier = Modifier.size(44.dp))
                                }
                            }
                            Text("Add profile", fontSize = 15.sp, color = Dx.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 128.dp))
                        }
                    }
                    Spacer(Modifier.height(44.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Pill(if (managing) "Done" else "Manage", selected = false, onClick = { managing = !managing })
                    }
                }
            }
        }
    }
}

private val NEW = Any()

/** Name and colour of a profile; Delete for one that exists */
@Composable
private fun ProfileEditor(stremio: Stremio, viewer: Viewer?, count: Int, onClose: () -> Unit) {
    var name by remember { mutableStateOf(viewer?.name.orEmpty()) }
    val used = remember { stremio.viewers.value.mapNotNull { it.colour }.toSet() }
    var colour by remember { mutableStateOf(viewer?.colour ?: ProfileColours.firstOrNull { it !in used } ?: ProfileColours[0]) }
    var confirm by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    FocusOnOpen(field)
    Column(Modifier.width(420.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(if (viewer == null) "New Profile" else "Edit Profile", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
        ProfileAvatar(name.ifBlank { "?" }, colour, 96.dp)
        Field("Name", name, { name = it.take(20) }, KeyboardType.Text, Modifier.focusRequester(field))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ProfileColours.forEach { c ->
                Circle(onClick = { colour = c }, selected = c == colour) {
                    Box(Modifier.size(34.dp).background(profileColour(c), CircleShape), contentAlignment = Alignment.Center) {
                        if (c == colour) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Pill("Save", selected = true, onClick = {
                val n = name.trim()
                if (n.isNotEmpty()) {
                    if (viewer == null) stremio.addViewer(n, colour) else stremio.editViewer(viewer.id, n, colour)
                    onClose()
                }
            })
            Pill("Cancel", selected = false, onClick = onClose)
            if (viewer != null && count > 1) Pill(if (confirm) "Press again to delete" else "Delete", selected = confirm, tint = Dx.Danger, onClick = {
                if (confirm) { stremio.removeViewer(viewer.id); onClose() } else confirm = true
            })
        }
    }
}
