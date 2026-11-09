package com.notdxbi.dxbi.tv.lumenphone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
 * What's on screen follows the root's state the way iOS's RootView does: starting, sign-in, who's watching (when the account has several),
 * then the tabs, with the taste setup over them when a profile has nothing watched yet.
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
        val viewers by stremio.viewers.collectAsState()
        val chosen by stremio.profileChosen.collectAsState()
        val viewerNow by stremio.viewer.collectAsState()
        val accountId by stremio.currentAccountId.collectAsState()
        val noHistory by stremio.noHistory.collectAsState()
        val a = account
        // what this profile said it likes (TasteSetup.kt): read for whoever's watching
        val taste = remember { TasteState(context.applicationContext, stremio) }
        LaunchedEffect(accountId, viewerNow?.id) { taste.load(accountId, viewerNow?.id) }
        val inApp = !swapping && !(a == null && !ready) && !(a != null && !a.signedIn && !guest)
        val picking = inApp && viewers.size > 1 && !chosen
        // Asked once, on a profile with nothing watched and no taste yet (iOS's offerTuning); Skip is remembered
        LaunchedEffect(noHistory, taste.loadedFor, taste.current, inApp, picking) {
            if (inApp && !picking && noHistory && taste.current == null && taste.loadedFor != null) taste.tuning = true
        }
        Box(Modifier.fillMaxSize().background(Dx.Background)) {
            CompositionLocalProvider(LocalTaste provides taste) {
                when {
                    // (another account is being read in: Stremio's page starts again)
                    swapping || (a == null && !ready) -> StartingScreen()
                    a != null && !a.signedIn && !guest -> SignInScreen(stremio)
                    // after sign-in, when the account has several people: who's watching
                    picking -> ProfilePicker(stremio, layout) { stremio.profileChosen.value = true }
                    else -> Shell(stremio, layout)
                }
                if (inApp && !picking && taste.tuning) {
                    Box(Modifier.fillMaxSize().background(Dx.Background)) {
                        TasteSetup(taste, viewerNow?.name, layout) { taste.finish(it) }
                    }
                }
            }
        }
    }
}
