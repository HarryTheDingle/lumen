package com.notdxbi.dxbi.tv.lumenphone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notdxbi.dxbi.tv.phone.Stremio
import com.notdxbi.dxbi.tv.phone.Viewer
import kotlinx.coroutines.launch

/** Lumen's viewer colours (the plugin accepts only these) */
val ProfileColours = listOf("#e50914", "#2f80ff", "#1db954", "#f5a524", "#8b5cf6", "#ec4899", "#14b8a6", "#f97316")

private fun profileColour(hex: String?): Color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color(0xFF33668C))

/** A profile's circle: its colour and first letter, as on Apple TV (a viewer's colour is theirs: the one place colour shows) */
@Composable
fun ProfileAvatar(name: String, colour: String?, size: Dp = 96.dp) {
    val c = profileColour(colour)
    Box(
        Modifier.size(size).background(Brush.linearGradient(listOf(c, c.copy(alpha = 0.62f))), CircleShape).border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.trim().take(1).uppercase(), fontSize = (size.value * 0.46f).sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

/**
 * Who's watching? A person each, with their own Continue Watching, watched marks and picks (iOS ProfilePicker). Opens
 * after sign-in when the account has more than one, and from Profile. Manage adds, renames and removes them; the
 * account at the bottom switches the Stremio account.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfilePicker(stremio: Stremio, layout: AppLayout, extraBottom: Dp = 0.dp, onDone: () -> Unit) {
    val viewers by stremio.viewers.collectAsState()
    val current by stremio.viewer.collectAsState()
    val account by stremio.account.collectAsState()
    val accounts by stremio.accounts.collectAsState()
    var managing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Viewer?>(null) }
    var adding by remember { mutableStateOf(false) }
    var accountsOpen by remember { mutableStateOf(false) }

    BackHandler(enabled = accountsOpen) { accountsOpen = false }
    if (accountsOpen) {
        AccountsPage(stremio, layout, bottomSpace = 0.dp, onBack = { accountsOpen = false }, onSwitched = { accountsOpen = false; onDone() })
        return
    }

    val size = if (layout.compact) 96.dp else 120.dp
    Column(Modifier.fillMaxSize().background(Dx.Background).windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = extraBottom), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(52.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (viewers.isNotEmpty()) ProfileTextButton(if (managing) "Done" else "Manage") { managing = !managing }
        }
        Box(Modifier.weight(1f))
        Text("Who's watching?", color = Dx.Text, fontSize = if (layout.compact) 30.sp else 38.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(34.dp))
        // (a long list scrolls, with room round it for the chosen one's ring, which is drawn outside its picture)
        Column(Modifier.weight(6f, fill = false).widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 10.dp)) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(26.dp),
            ) {
                viewers.forEach { v ->
                    Column(
                        Modifier.width(size + 12.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            if (managing) editing = v else { stremio.chooseViewer(v.id); onDone() }
                        },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val ring = v.id == current?.id && !managing
                        Box(Modifier.size(size + 12.dp).then(if (ring) Modifier.border(3.dp, Dx.Selected, CircleShape) else Modifier), contentAlignment = Alignment.Center) {
                            ProfileAvatar(v.name, v.colour, size)
                            if (managing) {
                                Icon(
                                    Icons.Filled.Edit, contentDescription = "Edit ${v.name}", tint = Dx.OnSelected,
                                    modifier = Modifier.align(Alignment.BottomEnd).size(30.dp).background(Dx.Selected, CircleShape).padding(6.dp),
                                )
                            }
                        }
                        Text(v.name, color = Dx.Text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                }
                if (viewers.size < 8) {
                    Column(
                        Modifier.width(size + 12.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { adding = true },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(Modifier.size(size + 12.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(size).background(Dx.Line, CircleShape), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Add, contentDescription = null, tint = Dx.Text, modifier = Modifier.size(38.dp))
                            }
                        }
                        Text("Add Profile", color = Dx.Muted, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                }
            }
        }
        Box(Modifier.weight(1f))
        // the account behind them, to switch it
        Row(
            Modifier.padding(bottom = 22.dp).clip(CircleShape).clickable { accountsOpen = true }.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AccountAvatar(account?.email, account?.avatar, 28.dp)
            Column(Modifier.padding(start = 10.dp)) {
                Text(if (account?.signedIn == true) (account?.email ?: "Stremio account") else "No account", color = Dx.Text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(if (accounts.size > 1 || account?.signedIn != true) "Switch account" else "Accounts", color = Dx.Muted, style = MaterialTheme.typography.labelMedium)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Dx.Faint, modifier = Modifier.padding(start = 6.dp).size(20.dp))
        }
    }

    editing?.let { v -> ViewerEditor(stremio, v, layout) { editing = null } }
    if (adding) ViewerEditor(stremio, null, layout) { adding = false }
}

/** Name and colour of a profile, in a sheet; Delete for one that exists (iOS ProfileEditor) */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ViewerEditor(stremio: Stremio, viewer: Viewer?, layout: AppLayout, onClose: () -> Unit) {
    val viewers by stremio.viewers.collectAsState()
    var name by remember { mutableStateOf(viewer?.name.orEmpty()) }
    // a new person starts on a colour nobody has yet
    var colour by remember {
        val used = viewers.mapNotNull { it.colour }.toSet()
        mutableStateOf(viewer?.colour ?: ProfileColours.firstOrNull { it !in used } ?: ProfileColours[0])
    }
    var confirm by remember { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun close(then: () -> Unit = {}) = scope.launch { sheet.hide() }.invokeOnCompletion { then(); onClose() }
    val ready = name.trim().isNotEmpty()

    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet, containerColor = Dx.Raised, contentColor = Dx.Text) {
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = layout.gutter).padding(bottom = 28.dp).widthIn(max = 560.dp).align(Alignment.CenterHorizontally),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ProfileTextButton("Cancel") { close() }
                Text(if (viewer == null) "New Profile" else "Edit Profile", color = Dx.Text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                ProfileTextButton("Save", enabled = ready) {
                    val n = name.trim()
                    if (viewer != null) stremio.editViewer(viewer.id, n, colour) else stremio.addViewer(n, colour)
                    close()
                }
            }
            Spacer(Modifier.height(14.dp))
            ProfileAvatar(name.ifBlank { "?" }, colour, 92.dp)
            Spacer(Modifier.height(20.dp))
            ProfileField(name, { name = it.take(20) }, "Name")
            Spacer(Modifier.height(22.dp))
            Text("COLOUR", color = Dx.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 10.dp))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ProfileColours.forEach { c ->
                    Box(
                        Modifier.size(44.dp).background(profileColour(c), CircleShape).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = c) { colour = c },
                        contentAlignment = Alignment.Center,
                    ) { if (c == colour) Icon(Icons.Filled.Check, contentDescription = "Chosen", tint = Color.White) }
                }
            }
            if (viewer != null && viewers.size > 1) {
                Spacer(Modifier.height(26.dp))
                ProfileTextButton("Delete Profile", destructive = true) { confirm = true }
                Text(
                    "Titles only they had saved leave the Library. Their Continue Watching and stats go with them.",
                    color = Dx.Faint, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                )
            }
        }
    }

    if (confirm && viewer != null) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = Color(0xFF2A2A2A),
            title = { Text("Delete ${viewer.name}?", color = Dx.Text) },
            confirmButton = { TextButton(onClick = { confirm = false; stremio.removeViewer(viewer.id); close() }) { Text("Delete Profile", color = Dx.Problem) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel", color = Dx.Text) } },
        )
    }
}
