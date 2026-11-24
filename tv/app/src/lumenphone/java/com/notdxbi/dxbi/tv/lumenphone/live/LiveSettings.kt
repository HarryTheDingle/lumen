package com.notdxbi.dxbi.tv.lumenphone.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notdxbi.dxbi.tv.live.LiveEngine
import com.notdxbi.dxbi.tv.live.LiveTvStore
import com.notdxbi.dxbi.tv.lumenphone.AppLayout
import com.notdxbi.dxbi.tv.lumenphone.Dx
import kotlinx.coroutines.launch
import lumos.iptv.IptvSource
import java.util.UUID

@Composable
private fun SettingRow(title: String, detail: String? = null, trailing: @Composable (() -> Unit)? = null, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Dx.Text, style = MaterialTheme.typography.bodyLarge)
            if (!detail.isNullOrBlank()) Text(detail, color = Dx.Muted, style = MaterialTheme.typography.bodySmall)
        }
        trailing?.invoke()
    }
}

@Composable
private fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) = Switch(
    on, onChange,
    colors = SwitchDefaults.colors(checkedTrackColor = Color.White, checkedThumbColor = Color.Black, uncheckedTrackColor = Color(0xFF3A3A3C), uncheckedThumbColor = Color.White, uncheckedBorderColor = Color.Transparent),
)

@Composable
internal fun LiveSettings(store: LiveTvStore, engine: LiveEngine, layout: AppLayout, bottomSpace: Dp, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val sources by store.sources.collectAsState()
    val reports by store.sourceReports.collectAsState()
    val prefs by store.prefs.collectAsState()
    val ui by store.uiState.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var confirmOut by remember { mutableStateOf(false) }
    if (adding) { AddProvider(store, layout, bottomSpace, onSettings = {}, onDone = { adding = false }); return }

    Column(Modifier.fillMaxSize().background(Dx.Background).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clickable(onClick = onBack).padding(12.dp)) { Icon(Icons.Filled.ArrowBack, "Back", tint = Dx.Text) }
            Text("Live TV settings", color = Dx.Text, style = MaterialTheme.typography.titleLarge)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomSpace + 24.dp)) {
            item { SectionTitle("Providers") }
            items(sources, key = { it.id }) { s ->
                val r = reports.firstOrNull { it.sourceId == s.id }
                val info = r?.accountInfo
                SettingRow(
                    s.name,
                    listOfNotNull(
                        when { r == null -> null; r.success -> "${r.streamCount} channels"; else -> r.error ?: "Couldn't load" },
                        info?.let { "${it.activeConnections}/${it.maxConnections} connections" },
                        info?.expiresAtMs?.let { "expires " + java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.UK).format(java.util.Date(it)) },
                    ).joinToString(" · "),
                    trailing = { Toggle(s.enabled) { on -> scope.launch { store.toggleSource(s.id, on) } } },
                )
            }
            item { SettingRow("Add a provider", "Xtream login, M3U link or Stalker portal", onClick = { adding = true }) }
            item { SettingRow("Refresh channels and guide", if (ui.refreshing) "Refreshing…" else null, onClick = { store.refreshAll(forceEpg = true) }) }
            item { SectionTitle("Channels") }
            item { SettingRow("Show channels from other countries", "Off keeps the guide to UK and Irish channels", trailing = { Toggle(prefs.showNonUk) { store.setShowNonUk(it) } }) }
            item { SectionTitle("Sports") }
            item { SettingRow("Hide scores", "No spoilers on the Sports page", trailing = { Toggle(prefs.hideSportsScores) { store.setHideSportsScores(it) } }) }
            item { SectionTitle("Account") }
            item { SettingRow("Sign out of Live TV", "Providers and favourites stay in your account", onClick = { confirmOut = true }) }
        }
    }
    if (confirmOut) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmOut = false }, containerColor = Color(0xFF2A2A2A),
        title = { Text("Sign out of Live TV?", color = Dx.Text) },
        text = { Text("You can sign in again from the Live TV tab.", color = Dx.Muted) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { confirmOut = false; engine.signOut(); onBack() }) { Text("Sign out", color = Dx.Problem) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmOut = false }) { Text("Cancel", color = Dx.Text) } },
    )
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, password: Boolean = false, multiline: Boolean = false, type: KeyboardType = KeyboardType.Text) {
    Column(Modifier.padding(bottom = 10.dp)) {
        Text(label, color = Dx.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 4.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Dx.Raised).padding(horizontal = 14.dp, vertical = 12.dp)) {
            BasicTextField(
                value, onChange, singleLine = !multiline, minLines = if (multiline) 4 else 1,
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp), cursorBrush = SolidColor(Color.White),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else type),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private enum class ProviderType(val label: String) { Xtream("Xtream login"), M3u("M3U link"), Stalker("Stalker portal") }

private fun pick(text: String, keys: String): String =
    Regex("(?:^|\\n|\\s)(?:$keys)\\s*[:=\\-–]\\s*(\\S+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim().orEmpty()

/** Adding an IPTV provider: paste the message it sent and the form fills itself, or type it in */
@Composable
internal fun AddProvider(store: LiveTvStore, layout: AppLayout, bottomSpace: Dp, onSettings: () -> Unit, onDone: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(ProviderType.Xtream) }
    var name by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var m3u by remember { mutableStateOf("") }
    var epg by remember { mutableStateOf("") }
    var portal by remember { mutableStateOf("") }
    var mac by remember { mutableStateOf("") }
    var conns by remember { mutableStateOf("1") }
    var paste by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun read(t: String) {
        paste = t
        val urls = Regex("https?://[^\\s\"'<>]+", RegexOption.IGNORE_CASE).findAll(t).map { it.value }.toList()
        val getPhp = urls.firstOrNull { it.contains("get.php?", true) && it.contains("username=", true) }
        val macFound = Regex("\\b([0-9A-F]{2}(?::[0-9A-F]{2}){5})\\b", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1).orEmpty()
        var u = pick(t, "user(?:name)?|login|user id")
        var p = pick(t, "pass(?:word)?|pwd")
        var s = pick(t, "server(?: url)?|host|url|dns|portal(?: url)?|xtream(?: url)?|domain")
        if (s.isNotEmpty() && !s.startsWith("http", true)) s = if (Regex("^[\\w.-]+(:\\d+)?$").matches(s)) "http://$s" else ""
        if (s.isEmpty()) s = urls.firstOrNull { !Regex("\\.(m3u8?|xml|gz)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(it) }.orEmpty()
        if (getPhp != null) runCatching {
            val uri = android.net.Uri.parse(getPhp)
            u = u.ifEmpty { uri.getQueryParameter("username").orEmpty() }; p = p.ifEmpty { uri.getQueryParameter("password").orEmpty() }
            s = "${uri.scheme}://${uri.authority}"
        }
        pick(t, "max(?:imum)? connections?|connections?|screens?").takeIf { Regex("^\\d{1,2}$").matches(it) }?.let { conns = it }
        when {
            macFound.isNotEmpty() && s.isNotEmpty() -> { type = ProviderType.Stalker; portal = s; mac = macFound.uppercase() }
            u.isNotEmpty() && p.isNotEmpty() && s.isNotEmpty() -> { type = ProviderType.Xtream; server = s.trimEnd('/'); user = u; pass = p }
            else -> urls.firstOrNull { Regex("\\.m3u8?(\\?|$)|m3u|playlist", RegexOption.IGNORE_CASE).containsMatchIn(it) }?.let { type = ProviderType.M3u; m3u = it }
        }
        urls.firstOrNull { Regex("\\.xml(\\.gz)?(\\?|$)|epg", RegexOption.IGNORE_CASE).containsMatchIn(it) }?.let { epg = it }
    }

    val ready = when (type) {
        ProviderType.Xtream -> server.isNotBlank() && user.isNotBlank() && pass.isNotBlank()
        ProviderType.M3u -> m3u.isNotBlank()
        ProviderType.Stalker -> portal.isNotBlank() && mac.isNotBlank()
    }
    Column(Modifier.fillMaxSize().background(Dx.Background).windowInsetsPadding(WindowInsets.statusBars).verticalScroll(rememberScrollState()).padding(horizontal = layout.gutter)) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onDone != null) Box(Modifier.clickable { onDone() }.padding(end = 12.dp, top = 8.dp, bottom = 8.dp)) { Icon(Icons.Filled.ArrowBack, "Back", tint = Dx.Text) }
            Text("Add your TV provider", color = Dx.Text, style = MaterialTheme.typography.headlineMedium)
        }
        Text("Paste the login message your IPTV provider sent and Lumen fills the rest in.", color = Dx.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
        Field("Paste the message here", paste, ::read, multiline = true)
        Row(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { ProviderType.entries.forEach { LiveChip(it.label, type == it, { type = it }) } }
        Field("Name (optional)", name, { name = it })
        when (type) {
            ProviderType.Xtream -> { Field("Server", server, { server = it }, type = KeyboardType.Uri); Field("Username", user, { user = it }); Field("Password", pass, { pass = it }, password = true) }
            ProviderType.M3u -> { Field("Playlist link", m3u, { m3u = it }, type = KeyboardType.Uri); Field("Guide (XMLTV) link, optional", epg, { epg = it }, type = KeyboardType.Uri) }
            ProviderType.Stalker -> { Field("Portal link", portal, { portal = it }, type = KeyboardType.Uri); Field("MAC address", mac, { mac = it.uppercase() }) }
        }
        Field("Screens it allows at once", conns, { conns = it.filter(Char::isDigit).take(2) }, type = KeyboardType.Number)
        error?.let { Text(it, color = Dx.Problem, modifier = Modifier.padding(bottom = 8.dp)) }
        LiveButton(if (saving) "Adding…" else "Add provider", Modifier.fillMaxWidth(), primary = ready && !saving, onClick = {
            if (!ready || saving) return@LiveButton
            saving = true; error = null
            val id = UUID.randomUUID().toString()
            val label = name.ifBlank { when (type) { ProviderType.Xtream -> server.removePrefix("http://").removePrefix("https://").substringBefore('/').substringBefore(':'); ProviderType.M3u -> "Playlist"; ProviderType.Stalker -> "Portal" } }
            val n = conns.toIntOrNull()?.coerceIn(1, 20) ?: 1
            val source: IptvSource = when (type) {
                ProviderType.Xtream -> IptvSource.Xtream(id = id, name = label, maxConnections = n, serverUrl = server.trim().trimEnd('/'), username = user.trim(), password = pass)
                ProviderType.M3u -> IptvSource.M3u(id = id, name = label, maxConnections = n, epgUrl = epg.trim().ifEmpty { null }, url = m3u.trim())
                ProviderType.Stalker -> IptvSource.Stalker(id = id, name = label, maxConnections = n, portalUrl = portal.trim(), macAddress = mac.trim())
            }
            scope.launch { runCatching { store.addSource(source) }.onFailure { error = "Couldn't add it: ${it.message}" }; saving = false; onDone?.invoke() }
        })
        Spacer(Modifier.height(bottomSpace + 24.dp))
    }
}
