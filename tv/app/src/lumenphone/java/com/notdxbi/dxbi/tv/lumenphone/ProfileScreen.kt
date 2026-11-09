package com.notdxbi.dxbi.tv.lumenphone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.notdxbi.dxbi.tv.BuildConfigVersion
import com.notdxbi.dxbi.tv.phone.Stremio
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding

/**
 * Profile (iOS AccountView): the account, its sign-out, and the version. Addons, Downloads, Your Stats, Customise
 * Home and the settings come in Phase 2.
 */
@Composable
fun ProfileScreen(stremio: Stremio, layout: AppLayout, bottomSpace: Dp) {
    val account by stremio.account.collectAsState()
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = layout.gutter).padding(bottom = bottomSpace),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Initial(account?.email, 72.dp)
        Text(account?.email ?: "Not signed in", color = Dx.Text, style = MaterialTheme.typography.titleMedium)
        Text(if (account?.signedIn == true) "Stremio account" else "Using Lumen without an account", color = Dx.Muted, style = MaterialTheme.typography.bodySmall)
        if (account?.signedIn == true) TextButton(onClick = { stremio.signOut() }, modifier = Modifier.padding(top = 12.dp)) { Text("Sign Out", color = Dx.Problem) }
        else TextButton(onClick = { stremio.guest.value = false }, modifier = Modifier.padding(top = 12.dp)) { Text("Sign In", color = Dx.Text) }
        Text("Lumen ${BuildConfigVersion.name(context)} · Not affiliated with Stremio.", color = Dx.Faint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(top = 24.dp), textAlign = TextAlign.Center)
    }
}
