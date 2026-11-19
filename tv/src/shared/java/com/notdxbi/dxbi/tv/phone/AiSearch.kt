package com.notdxbi.dxbi.tv.phone

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Where an "Ask AI" search has got to */
enum class AiPhase { Idle, Listening, Finding, Failed, Empty, NeedsText, Ready }

/**
 * Ask AI in Search, shared by Lumen's TV and phone screens: say (or type) what you're after, get one row of titles.
 * Speaking uses Android's own recogniser; with none on the device, or no microphone permission, the text already in
 * the search box is the prompt instead.
 */
class AiSearch(private val context: Context, private val stremio: Stremio, private val scope: CoroutineScope) {
    var phase by mutableStateOf(AiPhase.Idle); private set
    /** What's been heard so far while listening, then the prompt the row is titled with */
    var prompt by mutableStateOf(""); private set
    var titles by mutableStateOf<List<Title>>(emptyList()); private set
    /** Set by the composable that owns the permission prompt */
    internal var askPermission: (() -> Unit)? = null
    private var typed = ""
    private var recogniser: SpeechRecognizer? = null
    private var job: Job? = null

    val active get() = phase != AiPhase.Idle

    private fun hasMic() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** The button: listen (asking for the microphone first time), or use the typed text when listening can't be done */
    fun start(typedText: String) {
        typed = typedText.trim()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return fallBack()
        if (!hasMic()) { askPermission?.invoke() ?: fallBack(); return }
        listen()
    }

    internal fun permissionAnswered(granted: Boolean) { if (granted) listen() else fallBack() }

    private fun fallBack() {
        if (typed.length >= 3) ask(typed) else { prompt = ""; phase = AiPhase.NeedsText }
    }

    private fun listen() {
        stopListening()
        job?.cancel()
        prompt = ""; titles = emptyList(); phase = AiPhase.Listening
        val r = recognitionService()?.let { SpeechRecognizer.createSpeechRecognizer(context, it) } ?: SpeechRecognizer.createSpeechRecognizer(context)
        recogniser = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(b: Bundle?) {
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { if (phase == AiPhase.Listening) prompt = it }
            }
            override fun onResults(b: Bundle?) {
                val said = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                stopListening()
                if (said.isBlank()) phase = AiPhase.Empty else ask(said)
            }
            override fun onError(error: Int) {
                stopListening()
                // (heard nothing: try again; no recogniser or no permission: type it)
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> { phase = AiPhase.Empty }
                    else -> fallBack()
                }
            }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        })
    }

    /**
     * The recogniser to use: the box's own voice search (Google TV's) when there is one. The default some boxes name is
     * a text-to-speech package that can't listen, which left Ask AI with "not connected"
     */
    private fun recognitionService(): ComponentName? {
        val found = context.packageManager.queryIntentServices(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).setAction("android.speech.RecognitionService"), 0)
            .mapNotNull { it.serviceInfo }
        val best = found.firstOrNull { it.packageName.contains("katniss") }
            ?: found.firstOrNull { it.packageName == "com.google.android.googlequicksearchbox" }
            ?: found.firstOrNull { !it.packageName.contains("tts") }
        return best?.let { ComponentName(it.packageName, it.name) }
    }

    /** Looks for the prompt (spoken or typed) */
    fun ask(text: String) {
        stopListening()
        job?.cancel()
        prompt = text.trim(); titles = emptyList(); phase = AiPhase.Finding
        job = scope.launch {
            val found = stremio.aiSearch(prompt)
            when {
                found == null -> phase = AiPhase.Failed
                found.isEmpty() -> phase = AiPhase.Empty
                else -> { titles = found.distinctBy { it.id }; phase = AiPhase.Ready }
            }
        }
    }

    /** Try the same prompt again, or listen again when there wasn't one */
    fun retry() { if (prompt.isNotBlank() && phase == AiPhase.Failed) ask(prompt) else start(typed) }

    fun close() { stopListening(); job?.cancel(); phase = AiPhase.Idle; titles = emptyList(); prompt = "" }

    private fun stopListening() {
        recogniser?.run { runCatching { cancel() }; runCatching { destroy() } }
        recogniser = null
    }
}

/** One AiSearch for a screen, with the microphone permission prompt wired in */
@Composable
fun rememberAiSearch(stremio: Stremio): AiSearch {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ai = remember { AiSearch(context, stremio, scope) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ai.permissionAnswered(it) }
    ai.askPermission = { launcher.launch(Manifest.permission.RECORD_AUDIO) }
    DisposableEffect(ai) { onDispose { ai.close() } }
    return ai
}
