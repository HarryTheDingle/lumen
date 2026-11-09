package com.notdxbi.dxbi.tv.lumenphone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The pieces of Profile and its pages, after iOS's inset grouped lists (ios/Dxbi/UI/AccountView.swift): a title
 * with a back arrow, then rounded groups of rows split by hairlines. On the opened Fold the list is a centred
 * column, as on the iPad, not a phone's list stretched across the screen.
 */

/** A page: its title and back arrow, then its content as a centred, scrolling list */
@Composable
fun ProfilePage(
    title: String,
    layout: AppLayout,
    bottomSpace: Dp,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().background(Dx.Background)) {
        Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).height(52.dp)) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp)) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = "Back", tint = Dx.Text, modifier = Modifier.size(30.dp))
                }
            }
            Text(title, color = Dx.Text, style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.align(Alignment.Center))
        }
        Box(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.fillMaxHeight().then(if (layout.compact) Modifier.fillMaxWidth() else Modifier.widthIn(max = 680.dp).fillMaxWidth()),
                contentPadding = PaddingValues(start = layout.gutter, end = layout.gutter, top = 10.dp, bottom = 28.dp + bottomSpace),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                content = content,
            )
        }
    }
}

class ProfileGroupScope {
    internal val rows = mutableListOf<@Composable () -> Unit>()
    fun row(content: @Composable () -> Unit) { rows += content }
}

/** A rounded group of rows with an optional small heading above and a note below (iOS's List section) */
@Composable
fun ProfileGroup(header: String? = null, footer: String? = null, build: ProfileGroupScope.() -> Unit) {
    val scope = ProfileGroupScope().apply(build)
    Column {
        if (header != null) Text(header.uppercase(), color = Dx.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 16.dp, bottom = 7.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Dx.Raised)) {
            scope.rows.forEachIndexed { i, row ->
                if (i > 0) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(Dx.Line))
                row()
            }
        }
        if (footer != null) Text(footer, color = Dx.Faint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp))
    }
}

/** A row's frame: tappable when it has somewhere to go, with room for a finger */
@Composable
private fun ProfileRowFrame(onClick: (() -> Unit)?, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 50.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun RowTitle(title: String, note: String?, destructive: Boolean, modifier: Modifier) {
    Column(modifier) {
        Text(title, color = if (destructive) Dx.Problem else Dx.Text, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!note.isNullOrEmpty()) Text(note, color = Dx.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** A row that opens a page or does something: an icon, its name, what's there, and a chevron when it opens a page */
fun ProfileGroupScope.link(
    title: String,
    note: String? = null,
    icon: ImageVector? = null,
    value: String? = null,
    chevron: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) = row {
    ProfileRowFrame(onClick) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (destructive) Dx.Problem else Dx.Text, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(14.dp))
        }
        RowTitle(title, note, destructive, Modifier.weight(1f))
        if (value != null) Text(value, color = Dx.Muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.padding(start = 10.dp))
        if (chevron) Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Dx.Faint, modifier = Modifier.padding(start = 4.dp).size(22.dp))
    }
}

/** A row with a switch: white when on, as Apple TV marks what's chosen (no green or blue) */
fun ProfileGroupScope.toggle(title: String, note: String? = null, on: Boolean, onChange: (Boolean) -> Unit) = row {
    ProfileRowFrame({ onChange(!on) }) {
        RowTitle(title, note, false, Modifier.weight(1f))
        Switch(
            checked = on,
            onCheckedChange = null,
            modifier = Modifier.padding(start = 12.dp),
            colors = SwitchDefaults.colors(
                checkedTrackColor = Dx.Selected, checkedThumbColor = Dx.OnSelected, checkedBorderColor = Dx.Selected,
                uncheckedTrackColor = Dx.Line, uncheckedThumbColor = Dx.Muted, uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

/** A row that picks one of several in a menu, showing the one chosen (iOS's Picker) */
fun <T> ProfileGroupScope.choose(title: String, note: String? = null, shown: String, options: List<Pair<T, String>>, onPick: (T) -> Unit) = row {
    var open by remember { mutableStateOf(false) }
    Box {
        ProfileRowFrame({ open = true }) {
            RowTitle(title, note, false, Modifier.weight(1f))
            Text(shown, color = Dx.Muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.padding(start = 10.dp))
            Icon(Icons.Filled.UnfoldMore, contentDescription = null, tint = Dx.Faint, modifier = Modifier.padding(start = 4.dp).size(20.dp))
        }
        // (a long list, languages say, scrolls in its own menu rather than running off the screen)
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = Color(0xFF2A2A2A),
            modifier = if (options.size > 8) Modifier.height(380.dp) else Modifier,
        ) {
            options.forEach { (value, label) ->
                DropdownMenuItem(text = { Text(label, color = Dx.Text) }, onClick = { open = false; onPick(value) })
            }
        }
    }
}

/** The section's small grey words (a heading inside a page's list that isn't a group) */
fun LazyListScope.profileHeading(text: String) = item { Text(text, color = Dx.Text, style = MaterialTheme.typography.titleLarge) }

/** A one-line field in Lumen's look: a lighter grey box, white text, a white cursor */
@Composable
fun ProfileField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    onDone: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF2C2C2C)).padding(horizontal = 14.dp, vertical = 13.dp)) {
        if (value.isEmpty()) Text(placeholder, color = Dx.Faint, style = MaterialTheme.typography.bodyLarge)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Dx.Text, fontSize = MaterialTheme.typography.bodyLarge.fontSize),
            cursorBrush = SolidColor(Dx.Text),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = androidx.compose.ui.text.input.ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone?.invoke() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A plain text button: bold white, no box (iOS toolbar buttons) */
@Composable
fun ProfileTextButton(label: String, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        color = if (destructive) Dx.Problem else if (enabled) Dx.Text else Dx.Faint,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
    )
}
