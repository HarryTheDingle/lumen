package com.notdxbi.dxbi.tv.lumenphone

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notdxbi.dxbi.tv.phone.Review
import com.notdxbi.dxbi.tv.phone.Reviews
import com.notdxbi.dxbi.tv.phone.Title

/**
 * Scores and reviews for a title (iOS ReviewsView): each site's score, what critics said, and audience reviews
 * (MDBList, Wikidata, Wikipedia and TMDB, with the keys set in Lumen settings). In a sheet, in white on dark: the
 * sites' own colours are left out, as Apple TV's pages leave out colour.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewsSheet(title: Title, reviews: Reviews?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var reading by remember { mutableStateOf<Review?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Color(0xFF111111)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Reviews", style = MaterialTheme.typography.titleLarge, color = Dx.Text, modifier = Modifier.padding(horizontal = 20.dp))
            Text(title.name, style = MaterialTheme.typography.bodyMedium, color = Dx.Muted, modifier = Modifier.padding(horizontal = 20.dp))
            val r = reviews
            when {
                r == null -> Text("Gathering reviews…", color = Dx.Muted, modifier = Modifier.padding(20.dp))
                r.scores.isEmpty() && r.critics.isEmpty() && r.audience.isEmpty() ->
                    Text("No reviews found for this one yet." + if ("tmdb" in r.missing) " A TMDB key in Lumen settings adds audience reviews." else "", color = Dx.Muted, modifier = Modifier.padding(20.dp))
                else -> {
                    if (r.scores.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(r.scores, key = { it.source }) { s ->
                            Column(
                                Modifier.clip(RoundedCornerShape(14.dp)).background(Dx.Raised)
                                    .clickable(enabled = s.url != null) { s.url?.let { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                            ) {
                                Text(s.value, color = Dx.Text, style = MaterialTheme.typography.titleMedium)
                                Text(if (s.note.isNotEmpty()) "${s.name} · ${s.note}" else s.name, color = Dx.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                        }
                    }
                    if (r.critics.isNotEmpty()) Column(Modifier.padding(horizontal = 20.dp).clip(RoundedCornerShape(16.dp)).background(Dx.Raised).padding(16.dp)) {
                        Text("What critics said", color = Dx.Muted, style = MaterialTheme.typography.labelMedium)
                        Text(r.critics.joinToString("\n\n"), color = Dx.Text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                        r.criticsUrl?.let { url ->
                            Text("Read more on Wikipedia", color = Dx.Text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 10.dp).clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) })
                        }
                    }
                    if (r.audience.isNotEmpty()) {
                        Text("Audience reviews · TMDB", color = Dx.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 20.dp))
                        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(r.audience) { review ->
                                Column(Modifier.width(270.dp).heightIn(min = 150.dp).clip(RoundedCornerShape(16.dp)).background(Dx.Raised).clickable { reading = review }.padding(14.dp)) {
                                    Row {
                                        Text(review.author, color = Dx.Text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        review.rating?.let { Text("${"%.0f".format(it)}/10", color = Dx.Muted, style = MaterialTheme.typography.labelMedium) }
                                    }
                                    Text(review.text, color = Dx.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                                }
                            }
                        }
                    }
                    val notes = listOfNotNull(
                        r.audienceProblem?.let { "$it. Check the TMDB key in Lumen settings." },
                        r.problem?.let { "$it, so these scores come from Wikidata." },
                        r.scoresFrom?.let { "Scores from $it" },
                        if (r.critics.isNotEmpty()) "Summary from Wikipedia (CC BY-SA)" else null,
                        if (r.audience.isNotEmpty()) "This product uses the TMDB API but is not endorsed or certified by TMDB" else null,
                    )
                    if (notes.isNotEmpty()) Text(notes.joinToString(" · "), color = Dx.Faint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp))
                }
            }
        }
    }
    reading?.let { review ->
        AlertDialog(
            onDismissRequest = { reading = null },
            containerColor = Color(0xFF1C1C1E),
            title = { Text(review.author + (review.rating?.let { " · ${"%.0f".format(it)}/10" } ?: "")) },
            text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) { Text(review.text, color = Dx.Text, style = MaterialTheme.typography.bodyMedium) } },
            confirmButton = { TextButton(onClick = { reading = null }) { Text("Close", color = Dx.Text) } },
        )
    }
}
