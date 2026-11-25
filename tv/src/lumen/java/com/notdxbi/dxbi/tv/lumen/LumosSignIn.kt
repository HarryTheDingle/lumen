package com.notdxbi.dxbi.tv.lumen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import coil3.compose.AsyncImage
import com.notdxbi.dxbi.tv.phone.Stremio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.notdxbi.dxbi.tv.phone.Engine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lumos.engine.PendingSignIn

/**
 * Signing this TV in, with nothing typed on the remote, two ways at once:
 *  - Sign in with Stremio (the main way, for people who used Lumen): Stremio's link code, scanned or entered on a phone.
 *    The TV joins the Lumos account of that Stremio account (made for it the first time, with its Lumen profiles and
 *    history), and its addons come across in the same step
 *  - a Lumos code approved on a signed-in iPhone (Profile → Lumos → Sign In a Device)
 */
@Composable
fun LumosSignIn(stremio: Stremio) {
    val engine = stremio.engine
    val name = com.notdxbi.dxbi.tv.BuildConfig.APP_NAME
    var pending by remember { mutableStateOf<PendingSignIn?>(null) }
    var link by remember { mutableStateOf<Stremio.LinkCode?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    // the iPhone's code
    LaunchedEffect(Unit) {
        while (true) {
            val made = runCatching { engine.startSignIn() }.getOrNull()
            pending = made
            if (made == null) { status = "Can't connect to $name. Check your connection and try again."; delay(15_000); continue }
            val until = System.currentTimeMillis() + 9 * 60_000
            while (System.currentTimeMillis() < until) {
                delay(3_000)
                if (busy) continue
                val session = runCatching { engine.pollSignIn(made) }.getOrNull() ?: continue
                engine.signedIn(session)
                return@LaunchedEffect
            }
        }
    }
    // Stremio's link code
    LaunchedEffect(Unit) {
        while (true) {
            val made = stremio.linkCreate()
            link = made
            if (made == null) { delay(15_000); continue }
            val until = System.currentTimeMillis() + 4 * 60_000
            while (System.currentTimeMillis() < until) {
                delay(3_000)
                val key = stremio.linkRead(made.code) ?: continue
                busy = true
                status = "Signing in and bringing your profiles, history and addons across…"
                val result = runCatching { engine.signInWithStremio(key) }
                if (result.isSuccess) return@LaunchedEffect
                busy = false
                status = result.exceptionOrNull()?.message ?: "That didn't work. Try again."
                break
            }
        }
    }

    Row(Modifier.fillMaxSize().background(Dx.Background), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        // the main way: the Stremio account's own email and password, typed once with the remote
        Column(Modifier.width(440.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Logo(90.dp)
            Text("Welcome to $name", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color.White, textAlign = TextAlign.Center)
            Text(
                "Sign in with your Stremio account: your profiles, Continue Watching and addons come across. The password is used once and not kept.",
                fontSize = 14.sp, color = Dx.Muted, textAlign = TextAlign.Center,
            )
            Field("Stremio email", email, { email = it }, androidx.compose.ui.text.input.KeyboardType.Email, Modifier.focusRequester(first))
            Field("Password", password, { password = it }, androidx.compose.ui.text.input.KeyboardType.Password)
            status?.let { Text(it, fontSize = 15.sp, color = Color.White, textAlign = TextAlign.Center) }
            Button(
                onClick = {
                    if (busy || email.isBlank() || password.isEmpty()) return@Button
                    busy = true
                    status = "Signing in and bringing your profiles, history and addons across…"
                    scope.launch {
                        val result = runCatching { engine.signInWithStremio(email, password) }
                        if (result.isFailure) { busy = false; status = result.exceptionOrNull()?.message ?: "That didn't work. Try again." }
                    }
                },
                colors = ButtonDefaults.colors(containerColor = Dx.Accent.copy(alpha = 0.8f), contentColor = Dx.OnAccent, focusedContainerColor = Dx.Accent, focusedContentColor = Dx.OnAccent),
            ) { Text(if (busy) "Signing in…" else "Sign In", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        }
        Spacer(Modifier.width(72.dp))
        // the same with a phone, for anyone who'd rather not type a password on a TV
        Column(Modifier.width(300.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Or use your phone", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            androidx.compose.foundation.layout.Box(Modifier.size(190.dp).background(Color.White, RoundedCornerShape(Dx.RPanel)).padding(12.dp)) {
                link?.let { AsyncImage(model = it.qrUrl, contentDescription = "QR code to sign in with Stremio", modifier = Modifier.fillMaxSize()) }
            }
            Text("Scan, or go to link.stremio.com and enter", fontSize = 13.sp, color = Dx.Muted, textAlign = TextAlign.Center)
            Text(link?.code ?: "· · · ·", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White, letterSpacing = 6.sp)
            Spacer(Modifier.height(6.dp))
            Text("Or, on a signed-in iPhone: Profile → Sign In a Device, and enter", fontSize = 12.sp, color = Dx.Faint, textAlign = TextAlign.Center)
            Text(pending?.code ?: "· · · ·", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.8f), letterSpacing = 5.sp)
        }
    }
}

/**
 * After signing in: the TV's stream addons and Stremio history, brought across with Stremio's own link code (scanned
 * or entered on a phone, as Lumen TV's sign-in does), so nothing is typed with the remote. Stremio is kept in step
 * from then on while Lumen's still in use. Skip leaves the TV with Cinemeta only.
 */
@Composable
fun LumosAddons(stremio: Stremio) {
    val engine = stremio.engine
    var link by remember { mutableStateOf<Stremio.LinkCode?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val skip = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { skip.requestFocus() } }
    LaunchedEffect(Unit) {
        while (true) {
            val made = stremio.linkCreate()
            link = made
            if (made == null) { status = "Can't connect to Stremio. Trying again…"; delay(15_000); continue }
            val until = System.currentTimeMillis() + 4 * 60_000
            while (System.currentTimeMillis() < until) {
                delay(3_000)
                val key = stremio.linkRead(made.code) ?: continue
                status = "Bringing your addons and history across…"
                val result = runCatching { engine.importStremio(key) }
                result.onSuccess { (titles, addons) ->
                    status = "Done: $addons stream addon${if (addons == 1) "" else "s"} and $titles titles."
                    return@LaunchedEffect
                }
                status = result.exceptionOrNull()?.message ?: "That didn't work. Try again."
            }
        }
    }

    Row(Modifier.fillMaxSize().background(Dx.Background), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Column(Modifier.width(380.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Scan with your phone", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            androidx.compose.foundation.layout.Box(Modifier.size(260.dp).background(Color.White, RoundedCornerShape(Dx.RPanel)).padding(14.dp)) {
                link?.let { AsyncImage(model = it.qrUrl, contentDescription = "QR code to sign in to Stremio", modifier = Modifier.fillMaxSize()) }
            }
            Text("or go to link.stremio.com and enter", fontSize = 14.sp, color = Dx.Muted)
            Text(link?.code ?: "· · · ·", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color.White, letterSpacing = 8.sp)
        }
        Spacer(Modifier.width(72.dp))
        Column(Modifier.width(440.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Logo(90.dp)
            Text("Your addons and history from Stremio", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White, textAlign = TextAlign.Center)
            Text(
                "Sign in to Stremio on your phone with this code. Your stream addons, Continue Watching and watched titles come across, and what you watch here goes back to Stremio. Your password never touches this TV.",
                fontSize = 14.sp, color = Dx.Muted, textAlign = TextAlign.Center,
            )
            status?.let { Text(it, fontSize = 15.sp, color = Color.White, textAlign = TextAlign.Center) }
            Button(
                onClick = { engine.skipAddons() },
                modifier = Modifier.focusRequester(skip),
                colors = ButtonDefaults.colors(containerColor = Color.White.copy(alpha = 0.12f), contentColor = Color.White, focusedContainerColor = Color.White, focusedContentColor = Color.Black),
            ) { Text("Skip for now", fontSize = 16.sp) }
        }
    }
}
