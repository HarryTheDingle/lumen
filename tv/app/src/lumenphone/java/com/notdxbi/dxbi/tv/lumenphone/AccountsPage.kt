package com.notdxbi.dxbi.tv.lumenphone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.notdxbi.dxbi.tv.phone.Stremio

/** A person's picture: their Stremio avatar when it has one, else the first letter of their email in a grey circle */
@Composable
fun AccountAvatar(email: String?, avatar: String?, size: Dp = 34.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Dx.Line), contentAlignment = Alignment.Center) {
        Text((email?.firstOrNull() ?: '?').uppercase(), color = Dx.Text, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.45f).sp)
        if (avatar != null) Art(avatar, Modifier.matchParentSize(), ContentScale.Crop)
    }
}

/**
 * The accounts kept on this phone (iOS AccountsView): switch without a password, add another, or browse without
 * one. Switching starts Stremio's page again on the other account, so [onSwitched] closes what's open.
 */
@Composable
fun AccountsPage(stremio: Stremio, layout: AppLayout, bottomSpace: Dp, onBack: () -> Unit, onSwitched: () -> Unit) {
    val accounts by stremio.accounts.collectAsState()
    val account by stremio.account.collectAsState()
    val currentId by stremio.currentAccountId.collectAsState()
    val guest by stremio.guest.collectAsState()
    val signedIn = account?.signedIn == true
    var removing by remember { mutableStateOf<com.notdxbi.dxbi.tv.phone.SavedAccount?>(null) }

    ProfilePage("Accounts", layout, bottomSpace, onBack) {
        item {
            ProfileGroup(
                footer = "Each account you sign in to stays on this phone, encrypted with a key that never leaves it, so you can switch without typing the password again. " +
                    "The password itself is never kept. Remove takes an account off this phone; the account itself stays.",
            ) {
                accounts.filter { !it.isGuest }.forEach { a ->
                    val on = signedIn && a.id == currentId
                    row {
                        Row(
                            Modifier.fillMaxWidth().then(if (!on) Modifier.clickable { stremio.switchAccount(a.id); onSwitched() } else Modifier).padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AccountAvatar(a.email, a.avatar, 36.dp)
                            Text(a.email.ifEmpty { "Stremio account" }, color = Dx.Text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 12.dp))
                            if (on) Icon(Icons.Filled.Check, contentDescription = "In use", tint = Dx.Text, modifier = Modifier.size(22.dp))
                            else Text("Remove", color = Dx.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clickable { removing = a }.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 2.dp))
                        }
                    }
                }
                // (signed in, but not kept yet: the page is about to remember it)
                if (accounts.none { !it.isGuest } && signedIn) {
                    row {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            AccountAvatar(account?.email, account?.avatar, 36.dp)
                            Text(account?.email ?: "Stremio account", color = Dx.Text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 12.dp))
                            Icon(Icons.Filled.Check, contentDescription = "In use", tint = Dx.Text, modifier = Modifier.size(22.dp))
                        }
                    }
                }
                link("Add Account", icon = Icons.Filled.AddCircle, chevron = false) { stremio.beginAddAccount(); onSwitched() }
                link(
                    "Browse Without an Account", icon = Icons.Filled.PersonOutline, chevron = false,
                    value = if (guest && !signedIn) "In use" else null,
                ) { stremio.switchAccount(null); onSwitched() }
            }
        }
    }

    removing?.let { a ->
        AlertDialog(
            onDismissRequest = { removing = null },
            containerColor = Color(0xFF2A2A2A),
            title = { Text("Take ${a.email.ifEmpty { "this account" }} off this phone?", color = Dx.Text) },
            text = { Text("You'll need its password to sign in again.", color = Dx.Muted) },
            confirmButton = { TextButton(onClick = { stremio.forgetAccount(a.id); removing = null }) { Text("Remove", color = Dx.Problem) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel", color = Dx.Text) } },
        )
    }
}
