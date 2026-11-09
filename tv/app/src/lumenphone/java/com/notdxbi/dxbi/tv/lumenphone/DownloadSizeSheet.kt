package com.notdxbi.dxbi.tv.lumenphone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notdxbi.dxbi.tv.Downloads
import com.notdxbi.dxbi.tv.phone.StreamChoices
import com.notdxbi.dxbi.tv.phone.StreamOption

/**
 * Download's size choice (iOS: the Download menu on a title's ⋯): High, Medium or Small from the addons' streams,
 * each with how big it is before anything is downloaded, then "Choose a File…" for the whole list. See
 * [downloadSizes] for how they're picked.
 *
 * Where it's used: a title's ⋯ menu opens this for Download… instead of the stream list, with
 * `onPick = { play(it, Phone.Target.Download) }` and `onMore = { downloadsOpen = true }` (the list it has now).
 * [minutes] is the film or episode's length when the page knows it (it makes the sizes per hour exact).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSizeSheet(
    choices: StreamChoices?,
    heading: String,
    subheading: String?,
    onPick: (StreamOption) -> Unit,
    onMore: () -> Unit,
    onDismiss: () -> Unit,
    minutes: Int = 0,
) {
    val context = LocalContext.current
    val sizes = remember(choices?.items, minutes) { downloadSizes(choices?.items.orEmpty(), minutes) }
    val searching = choices == null || !choices.settled
    val wifiOnly by Downloads.wifiOnly.collectAsState()
    // (a film on mobile data is a lot of someone's allowance: said before it starts, as the stream list says it on iOS)
    val costs = onMobileData(context) && !wifiOnly

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Color(0xFF111111)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column {
                Text("Download", style = MaterialTheme.typography.titleLarge, color = Dx.Text)
                Text(listOfNotNull(heading, subheading).filter { it.isNotBlank() }.joinToString(" · "), color = Dx.Muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (costs) Text("You're on mobile data. Wi-Fi is better for a download this size.", color = Dx.Muted, style = MaterialTheme.typography.bodySmall)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Dx.Raised)) {
                sizes.forEachIndexed { i, s ->
                    if (i > 0) Hairline()
                    Row(Modifier.fillMaxWidth().clickable { onPick(s.option) }.padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Download, contentDescription = null, tint = Dx.Text, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.name, color = Dx.Text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                listOfNotNull(s.option.quality, s.option.codec.takeIf { it.isNotBlank() }, s.option.provider.takeIf { it.isNotBlank() }).joinToString(" · "),
                                color = Dx.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(s.sizeText, color = Dx.Text, style = MaterialTheme.typography.titleSmall)
                            Text(s.perHourText, color = Dx.Faint, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (sizes.isNotEmpty()) Hairline()
                Row(Modifier.fillMaxWidth().clickable(onClick = onMore).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (sizes.isEmpty()) "Choose a File…" else "More…", color = Dx.Text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Dx.Faint, modifier = Modifier.size(22.dp))
                }
            }
            when {
                searching -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Dx.Text, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(if (sizes.isEmpty()) "Finding streams…" else "More addons answering…", color = Dx.Muted, style = MaterialTheme.typography.bodySmall)
                }
                sizes.isEmpty() -> Text("No sizes are known for this one. Choose a file from the whole list.", color = Dx.Muted, style = MaterialTheme.typography.bodySmall)
                else -> Text("Sizes are about what Netflix's downloads cost. Debrid and direct streams only: torrents can't be saved.", color = Dx.Faint, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** The thin line between rows of a group */
@Composable
internal fun Hairline() {
    Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(Dx.Line))
}
