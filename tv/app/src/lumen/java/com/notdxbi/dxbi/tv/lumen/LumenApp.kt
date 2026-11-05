package com.notdxbi.dxbi.tv.lumen

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.tv.material3.Surface
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Border
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.notdxbi.dxbi.tv.phone.Engine
import com.notdxbi.dxbi.tv.phone.Load
import com.notdxbi.dxbi.tv.phone.Stremio
import com.notdxbi.dxbi.tv.phone.Title
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lumen on a TV: its own screens over Stremio's page, which keeps doing Stremio's work underneath
 * ([Engine]), as in the iPad app. Worked by remote: every screen is native, the page is never shown.
 */
@Composable
fun LumenApp(engine: Engine) {
    val context = LocalContext.current
    // (Stremio's state is read and turned into screens' data off the main thread, so moving never waits for it)
    val scope = rememberCoroutineScope { Dispatchers.Default }
    val stremio = remember { Stremio(context.applicationContext, engine, scope) }
    LumenTheme {
        val account by stremio.account.collectAsState()
        val ready by stremio.ready.collectAsState()
        val guest by stremio.guest.collectAsState()
        val a = account
        when {
            a == null && !ready -> Starting()
            a != null && !a.signedIn && !guest -> SignIn(stremio)
            else -> Screens(stremio, scope)
        }
    }
}

@Composable
private fun Screens(stremio: Stremio, scope: CoroutineScope) {
    val context = LocalContext.current
    val watching by stremio.continueWatching.collectAsState()
    HomeScreen(
        stremio,
        // (title pages are the next step)
        onOpen = { Toast.makeText(context, it.name, Toast.LENGTH_SHORT).show() },
        onPlay = { title ->
            val videoId = watching.firstOrNull { it.title.id == title.id }?.videoId
            if (title.type == "series" && videoId == null) {
                Toast.makeText(context, "Choose an episode on ${title.name}'s page", Toast.LENGTH_SHORT).show()
            } else {
                scope.launch { playNow(stremio, title, videoId) { message -> withContext(Dispatchers.Main) { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() } } }
            }
        },
    )
}

/**
 * Plays a title straight away (Home's Play): opens it, waits for the addons' streams and plays Lumen's
 * pick (the plugin's Just press Play, which remembers what played last time)
 */
private suspend fun playNow(stremio: Stremio, title: Title, videoId: String?, say: suspend (String) -> Unit) {
    stremio.open(title.type, title.id, videoId)
    for (attempt in 0 until 60) {
        val d = stremio.details.value
        if (d?.metaId == title.id && d.state == Load.Ready) break
        delay(150)
    }
    // the addons answer one by one: the pick once they all have, or once there's a good one after a while
    for (attempt in 0 until 40) {
        val choices = stremio.streams()
        val pick = choices?.picked
        if (pick != null && (choices.settled || attempt >= 10)) {
            stremio.play(pick)
            return
        }
        if (choices != null && choices.settled && choices.items.isEmpty()) break
        delay(500)
    }
    say("No streams found for ${title.name}")
}

@Composable
private fun Starting() {
    Box(Modifier.fillMaxSize().background(Dx.Background), contentAlignment = Alignment.Center) {
        Logo(130.dp)
    }
}

/** Signing in to Stremio, with the TV's keyboard (or browsing without an account) */
@Composable
private fun SignIn(stremio: Stremio) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Box(Modifier.fillMaxSize().background(Dx.Background), contentAlignment = Alignment.Center) {
        Column(Modifier.width(420.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Logo(100.dp)
            Text("Sign in with your Stremio account", fontSize = 16.sp, color = Dx.Muted)
            Spacer(Modifier.height(6.dp))
            Field("Email", email, { email = it }, KeyboardType.Email, Modifier.focusRequester(first))
            Field("Password", password, { password = it }, KeyboardType.Password)
            error?.let { Text(it, fontSize = 14.sp, color = Color(0xFFFF6B6B)) }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Button(
                    onClick = {
                        if (busy) return@Button
                        busy = true
                        error = null
                        scope.launch {
                            error = stremio.signIn(email.trim(), password)
                            busy = false
                        }
                    },
                    colors = ButtonDefaults.colors(containerColor = Dx.Accent.copy(alpha = 0.8f), contentColor = Dx.OnAccent, focusedContainerColor = Dx.Accent, focusedContentColor = Dx.OnAccent),
                ) { Text(if (busy) "Signing in…" else "Sign In", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                Button(
                    onClick = { stremio.continueAsGuest() },
                    colors = ButtonDefaults.colors(containerColor = Color.White.copy(alpha = 0.12f), contentColor = Color.White, focusedContainerColor = Color.White, focusedContentColor = Color.Black),
                ) { Text("Browse without an account", fontSize = 16.sp) }
            }
        }
    }
}

/**
 * A text box worked by remote: choosing it (OK) opens the TV's keyboard, and Done or Back closes it
 * again. (A text field that's simply moved onto would open the keyboard at once, and keep the arrows.)
 */
@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, type: KeyboardType, modifier: Modifier = Modifier) {
    var editing by remember { mutableStateOf(false) }
    val box = remember { FocusRequester() }
    val input = remember { FocusRequester() }
    val password = type == KeyboardType.Password
    val shape = RoundedCornerShape(12.dp)
    fun done() {
        editing = false
        runCatching { box.requestFocus() }
    }
    LaunchedEffect(editing) { if (editing) runCatching { input.requestFocus() } }

    if (editing) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
            cursorBrush = SolidColor(Dx.Accent),
            keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { done() }),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = modifier
                .fillMaxWidth()
                .focusRequester(input)
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyUp && it.key == Key.Back) { done(); true } else false
                }
                .background(Color.White.copy(alpha = 0.16f), shape)
                .border(2.dp, Dx.Accent, shape)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        )
    } else {
        Surface(
            onClick = { editing = true },
            modifier = modifier.fillMaxWidth().focusRequester(box),
            shape = ClickableSurfaceDefaults.shape(shape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.08f),
                contentColor = Color.White,
                focusedContainerColor = Color.White.copy(alpha = 0.16f),
                focusedContentColor = Color.White,
            ),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Dx.Accent), shape = shape)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        ) {
            val shown = if (password) "•".repeat(value.length) else value
            Text(
                shown.ifEmpty { label },
                fontSize = 18.sp,
                color = if (value.isEmpty()) Dx.Faint else Color.White,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
    }
}
