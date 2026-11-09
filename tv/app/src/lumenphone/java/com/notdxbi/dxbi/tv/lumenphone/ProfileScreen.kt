package com.notdxbi.dxbi.tv.lumenphone

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.notdxbi.dxbi.tv.BuildConfig
import com.notdxbi.dxbi.tv.BuildConfigVersion
import com.notdxbi.dxbi.tv.MainActivity
import com.notdxbi.dxbi.tv.Updates
import com.notdxbi.dxbi.tv.phone.Stremio

private tailrec fun Context.profileActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.profileActivity()
    else -> null
}

/** The pages Profile opens over itself (its own back stack of one: system Back closes the page, then Profile closes) */
private enum class ProfilePageKey { Profiles, Accounts, Addons, Stats, Customise, Settings, Playback }

/**
 * Profile (iOS AccountView), a page the picture on Home opens: who's watching and the account behind them, then
 * Addons, Your Stats, Tune Suggestions, Customise Home, Lumen Settings and Playback, Sign Out, and the version with
 * its update check. The pages it opens (and the profile picker) show over it and close with Back; Back on Profile
 * itself is the caller's. On the opened Fold the list is a centred column, as on the iPad.
 *
 * Downloads and Networks have rows here, as on iOS, but do nothing yet: a later phase adds their pages.
 */
@Composable
fun ProfileScreen(stremio: Stremio, layout: AppLayout, bottomSpace: Dp) {
    var page by rememberSaveable { mutableStateOf<ProfilePageKey?>(null) }
    BackHandler(enabled = page != null) { page = null }

    AnimatedContent(
        targetState = page,
        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(140)) },
        label = "profile pages",
    ) { shown ->
        val close = { page = null }
        when (shown) {
            null -> ProfileHome(stremio, layout, bottomSpace, open = { page = it })
            ProfilePageKey.Profiles -> {
                val nav = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }
                ProfilePicker(stremio, layout, extraBottom = (bottomSpace - nav).coerceAtLeast(0.dp), onDone = close)
            }
            ProfilePageKey.Accounts -> AccountsPage(stremio, layout, bottomSpace, onBack = close, onSwitched = close)
            ProfilePageKey.Addons -> SettingsAddonsPage(stremio, layout, bottomSpace, close)
            ProfilePageKey.Stats -> StatsPage(stremio, layout, bottomSpace, close)
            ProfilePageKey.Customise -> SettingsHomePage(stremio, layout, bottomSpace, close)
            ProfilePageKey.Settings -> SettingsLumenPage(stremio, layout, bottomSpace, close)
            ProfilePageKey.Playback -> SettingsPlaybackPage(stremio, layout, bottomSpace, close)
        }
    }
}

@Composable
private fun ProfileHome(stremio: Stremio, layout: AppLayout, bottomSpace: Dp, open: (ProfilePageKey) -> Unit) {
    val context = LocalContext.current
    val account by stremio.account.collectAsState()
    val viewer by stremio.viewer.collectAsState()
    val viewers by stremio.viewers.collectAsState()
    val taste = LocalTaste.current
    // Back, the way the system's would go (the caller decides what that closes)
    val back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    var signingOut by remember { mutableStateOf(false) }
    val signedIn = account?.signedIn == true

    ProfilePage("Profile", layout, bottomSpace, onBack = back?.let { { it.onBackPressed() } }) {
        item {
            ProfileGroup {
                // who's watching now (their own Continue Watching, watched marks and picks)
                row {
                    Row(Modifier.fillMaxWidth().clickable { open(ProfilePageKey.Profiles) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        val v = viewer
                        if (v != null) ProfileAvatar(v.name, v.colour, 52.dp) else AccountAvatar(account?.email, account?.avatar, 52.dp)
                        androidx.compose.foundation.layout.Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text(viewer?.name ?: account?.email ?: "Not signed in", color = Dx.Text, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            Text(if (viewers.size > 1) "Switch or manage profiles" else "Add a profile for someone else", color = Dx.Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        androidx.compose.material3.Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Dx.Faint, modifier = Modifier.size(22.dp))
                    }
                }
                row {
                    Row(Modifier.fillMaxWidth().clickable { open(ProfilePageKey.Accounts) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AccountAvatar(account?.email, account?.avatar, 30.dp)
                        androidx.compose.foundation.layout.Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text(account?.email ?: "Not signed in", color = Dx.Text, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                            Text(if (signedIn) "Stremio account" else "Using Lumen without an account", color = Dx.Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        androidx.compose.material3.Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Dx.Faint, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
        item {
            ProfileGroup {
                link("Addons", icon = Icons.Filled.Extension) { open(ProfilePageKey.Addons) }
                // PHASE 3: Downloads has its page later; the row is here as on iOS and does nothing yet
                link("Downloads", icon = Icons.Filled.Download) { soon(context, "Downloads") }
                link("Your Stats", icon = Icons.Filled.BarChart) { open(ProfilePageKey.Stats) }
                link("Tune Suggestions", icon = Icons.Filled.AutoFixHigh, chevron = false) { taste?.tuning = true }
                link("Customise Home", icon = Icons.Filled.Dashboard) { open(ProfilePageKey.Customise) }
                link("Lumen Settings", icon = Icons.Filled.Tune) { open(ProfilePageKey.Settings) }
                link("Playback", icon = Icons.Filled.PlayCircle) { open(ProfilePageKey.Playback) }
                // PHASE 3: Networks has its page later; the row is here as on iOS and does nothing yet
                link("Networks", icon = Icons.Filled.Wifi) { soon(context, "Networks") }
            }
        }
        item {
            ProfileGroup(
                footer = "Lumen ${BuildConfigVersion.name(context)} · Not affiliated with Stremio.",
            ) {
                link("Check for Updates", icon = Icons.Filled.Update, chevron = false) { checkForUpdates(context) }
                if (signedIn) link("Sign Out", icon = Icons.Filled.Logout, chevron = false, destructive = true) { signingOut = true }
                else link("Sign In", icon = Icons.Filled.Login, chevron = false) { stremio.guest.value = false }
            }
        }
    }

    if (signingOut) {
        AlertDialog(
            onDismissRequest = { signingOut = false },
            containerColor = Color(0xFF2A2A2A),
            title = { Text("Sign out of ${account?.email ?: "Stremio"}?", color = Dx.Text) },
            text = { Text("This account comes off this phone. You'll need its password to sign in again.", color = Dx.Muted) },
            confirmButton = { TextButton(onClick = { signingOut = false; stremio.signOut() }) { Text("Sign Out", color = Dx.Problem) } },
            dismissButton = { TextButton(onClick = { signingOut = false }) { Text("Cancel", color = Dx.Text) } },
        )
    }
}

private fun soon(context: Context, what: String) =
    Toast.makeText(context, "$what is coming in a later update", Toast.LENGTH_SHORT).show()

/**
 * Looks for a newer Lumen now (the update offer the app makes when it opens, asked for again): a version they
 * skipped is offered again, and a test build, which doesn't update itself, says so. Updates says nothing when
 * there's nothing newer, so this says it's looking.
 */
private fun checkForUpdates(context: Context) {
    if (!BuildConfig.SELF_UPDATE) {
        Toast.makeText(context, "Test builds don't update themselves", Toast.LENGTH_SHORT).show()
        return
    }
    val activity = context.profileActivity() as? MainActivity ?: return
    context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().remove("skipped").apply()
    Toast.makeText(context, "Looking for a newer Lumen. If there is one, you're asked here.", Toast.LENGTH_LONG).show()
    Updates.checkApp(activity)
}
