package com.notdxbi.dxbi.tv.lumenphone

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.notdxbi.dxbi.tv.phone.HomeLayout
import com.notdxbi.dxbi.tv.phone.Stremio

/**
 * Customise Home (iOS CustomiseHomeView): which catalog rows show, and in what order. iOS drags rows by their
 * handles; here each has an up and a down arrow (a drag in a scrolling list is easy to get wrong with a thumb), and
 * a switch to show or hide it. Saved to the profile, so the other devices follow.
 */
@Composable
fun SettingsHomePage(stremio: Stremio, layout: AppLayout, bottomSpace: Dp, onBack: () -> Unit) {
    val all by stremio.allRows.collectAsState()
    val saved by stremio.layout.collectAsState()
    val byKey = remember(all) { all.distinctBy { it.key }.associateBy { it.key } }
    var keys by remember { mutableStateOf(saved.order.filter { it in byKey } + byKey.keys.filter { it !in saved.order }) }
    var hidden by remember { mutableStateOf(saved.hidden) }
    // (the rows arrive after the page opens: new ones join the end)
    val shown = keys.filter { it in byKey } + byKey.keys.filter { it !in keys }

    fun save(order: List<String>, hide: Set<String>) {
        keys = order
        hidden = hide
        stremio.saveLayout(HomeLayout(order, hide, saved.names))
    }

    ProfilePage("Customise Home", layout, bottomSpace, onBack) {
        item {
            ProfileGroup(footer = "Use the arrows to move a row up or down. Lumen saves this to your profile, so your other devices follow it.") {
                shown.forEachIndexed { i, key ->
                    val r = byKey[key] ?: return@forEachIndexed
                    row {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(saved.names[key] ?: r.heading, color = if (key in hidden) Dx.Faint else Dx.Text, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(r.addon, color = Dx.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                            IconButton(onClick = { save(shown.toMutableList().also { it.add(i - 1, it.removeAt(i)) }, hidden) }, enabled = i > 0) {
                                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up", tint = if (i > 0) Dx.Muted else Dx.Line, modifier = Modifier.size(26.dp))
                            }
                            IconButton(onClick = { save(shown.toMutableList().also { it.add(i + 1, it.removeAt(i)) }, hidden) }, enabled = i < shown.size - 1) {
                                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down", tint = if (i < shown.size - 1) Dx.Muted else Dx.Line, modifier = Modifier.size(26.dp))
                            }
                            Switch(
                                checked = key !in hidden,
                                onCheckedChange = { on -> save(shown, if (on) hidden - key else hidden + key) },
                                modifier = Modifier.padding(start = 4.dp, end = 10.dp),
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = Dx.Selected, checkedThumbColor = Dx.OnSelected, checkedBorderColor = Dx.Selected,
                                    uncheckedTrackColor = Dx.Line, uncheckedThumbColor = Dx.Muted, uncheckedBorderColor = Color.Transparent,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}
