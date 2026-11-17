package com.notdxbi.dxbi.tv.lumenphone

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notdxbi.dxbi.tv.phone.SavedAccount
import com.notdxbi.dxbi.tv.phone.Stremio
import kotlinx.coroutines.launch

/**
 * The first screen until they sign in or choose to go without an account: the iOS app's WelcomeView. The logo,
 * what signing in brings, email and password, a white Sign In, the accounts already on this device, and the two
 * ways round (no account, or make one).
 */
@Composable
fun SignInScreen(stremio: Stremio) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val ready by stremio.ready.collectAsState()
    val saved by stremio.accounts.collectAsState()
    val adding by stremio.addingAccount.collectAsState()
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }

    fun signIn() {
        if (busy || email.isBlank() || password.isEmpty() || !ready) return
        busy = true
        problem = null
        scope.launch {
            problem = stremio.signIn(email.trim(), password)
            busy = false
        }
    }

    val field = TextFieldDefaults.colors(
        focusedContainerColor = Dx.Raised, unfocusedContainerColor = Dx.Raised,
        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
        focusedTextColor = Dx.Text, unfocusedTextColor = Dx.Text,
        focusedLabelColor = Dx.Muted, unfocusedLabelColor = Dx.Faint, cursorColor = Dx.Text,
    )

    // (a faint light from the top, fading to black: iOS tints it with its cyan, Lumen's phone apps keep to white)
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.14f), Dx.Background, Dx.Background)))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Spacer(Modifier.height(48.dp))
            Logo(84.dp)
            Text(
                "Sign in with your Stremio account.\nYour addons, library and progress come with it.",
                color = Dx.Muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge,
            )
            Column(Modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(
                    email, { email = it }, label = { Text("Email") }, singleLine = true, colors = field, shape = RoundedCornerShape(18.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    password, { password = it }, label = { Text("Password") }, singleLine = true, colors = field, shape = RoundedCornerShape(18.dp),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { signIn() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            problem?.let { Text(it, color = Dx.Problem, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
            // Apple TV's white pill
            Button(
                onClick = { signIn() },
                enabled = !busy && email.isNotBlank() && password.isNotEmpty() && ready,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Dx.Selected, contentColor = Dx.OnSelected,
                    disabledContainerColor = Dx.Selected.copy(alpha = 0.3f), disabledContentColor = Dx.OnSelected.copy(alpha = 0.6f),
                ),
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                if (busy) CircularProgressIndicator(color = Dx.OnSelected, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("Sign In", style = MaterialTheme.typography.labelLarge)
            }
            if (!ready) Text("Starting Stremio…", color = Dx.Faint, style = MaterialTheme.typography.bodySmall)
            if (saved.isNotEmpty()) SavedAccounts(saved, enabled = ready) { stremio.switchAccount(it.id) }
            if (adding) TextButton(onClick = { stremio.cancelAddAccount() }) { Text("Cancel", color = Dx.Text, fontWeight = FontWeight.SemiBold) }
            TextButton(onClick = { stremio.continueAsGuest() }) { Text("Continue Without an Account", color = Dx.Text, fontWeight = FontWeight.SemiBold) }
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.stremio.com/register"))) }) {
                Text("Create a Stremio account", color = Dx.Muted, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** "Saved on this device": the accounts signed in before, a tap to switch to one */
@Composable
private fun SavedAccounts(accounts: List<SavedAccount>, enabled: Boolean, onPick: (SavedAccount) -> Unit) {
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Saved on this device", color = Dx.Muted, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.CenterHorizontally))
        accounts.forEach { a ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Dx.Raised).clickable(enabled = enabled) { onPick(a) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Initial(a.email, 30.dp)
                Text(a.email, color = Dx.Text, maxLines = 1, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** A round grey badge with the account's first letter (its own picture comes with the profile screens) */
@Composable
fun Initial(email: String?, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Dx.Line), contentAlignment = Alignment.Center) {
        Text((email?.firstOrNull() ?: '?').uppercase(), color = Dx.Text, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.45f).sp)
    }
}
