package com.notdxbi.dxbi.tv.lumenphone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.notdxbi.dxbi.tv.phone.Engine
import com.notdxbi.dxbi.tv.phone.Stremio
import kotlinx.coroutines.Dispatchers

/**
 * Lumen on a phone or the Pixel Fold: native screens over Stremio's page, which keeps doing Stremio's work
 * underneath ([Engine]), as in the iPhone and iPad app. Every screen is Lumen's own: the page is never shown.
 * What's on screen follows the root's state the way iOS's RootView does: starting, sign-in, then the tabs.
 */
@Composable
fun LumenPhoneApp(engine: Engine) {
    val context = LocalContext.current
    // (Stremio's state is read and turned into screens' data off the main thread, so scrolling never waits for it)
    val scope = rememberCoroutineScope { Dispatchers.Default }
    val stremio = remember { Stremio(context.applicationContext, engine, scope) }
    LumenPhoneTheme {
        val layout = rememberAppLayout()
        val account by stremio.account.collectAsState()
        val ready by stremio.ready.collectAsState()
        val guest by stremio.guest.collectAsState()
        val swapping by stremio.swapping.collectAsState()
        val a = account
        Box(Modifier.fillMaxSize().background(Dx.Background)) {
            when {
                // (another account is being read in: Stremio's page starts again)
                swapping || (a == null && !ready) -> StartingScreen()
                a != null && !a.signedIn && !guest -> SignInScreen(stremio)
                else -> Shell(stremio, layout)
            }
        }
    }
}
