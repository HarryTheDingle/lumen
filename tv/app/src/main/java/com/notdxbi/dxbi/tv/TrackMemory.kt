package com.notdxbi.dxbi.tv

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONObject

/**
 * Lumen: each show's sound and subtitles, as the iPad keeps them: the audio track and the subtitles
 * (on in a language, or off) last chosen for a show are chosen again for its next episode (and a
 * film's for itself). Kept by the show's id; matched by language, then by the track's name.
 */
class TrackMemory(context: Context, private val exo: ExoPlayer, private val show: String) {
    private val prefs = context.getSharedPreferences("lumen-tracks", Context.MODE_PRIVATE)
    private var saved = load()
    private var audioDone = saved?.optString("audioLang").isNullOrEmpty()
    /** The remembered subtitles: chosen once they're there (an addon's arrive a moment after the video's own) */
    private var subtitlesDone = saved?.optBoolean("subOn") != true
    private var since = System.currentTimeMillis()

    private fun load() = prefs.getString(show, null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** Another stream of the same video is loading: the sound and subtitles chosen so far are chosen again on it */
    fun again() {
        saved = load()
        audioDone = saved?.optString("audioLang").isNullOrEmpty()
        subtitlesDone = saved?.optBoolean("subOn") != true
        since = System.currentTimeMillis()
    }

    /** The video's tracks changed: the remembered ones chosen (once), else what's chosen now remembered */
    fun changed(tracks: Tracks) {
        if (show.isEmpty()) return
        val restoring = !audioDone || !subtitlesDone
        if (restoring) {
            restore(tracks)
            // (an addon's subtitles that haven't come in 20 seconds aren't coming)
            if (System.currentTimeMillis() - since > 20_000) subtitlesDone = true
            return
        }
        remember(tracks)
    }

    private fun restore(tracks: Tracks) {
        val s = saved ?: return
        val params = exo.trackSelectionParameters.buildUpon()
        var changed = false
        if (!audioDone) {
            find(tracks, C.TRACK_TYPE_AUDIO, s.optString("audioLang"), s.optString("audioLabel"))?.let { (group, i) ->
                params.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                changed = true
            }
            // (one look: the file's own tracks are all there at the start)
            if (tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }) audioDone = true
        }
        if (!subtitlesDone) {
            find(tracks, C.TRACK_TYPE_TEXT, s.optString("subLang"), s.optString("subLabel"))?.let { (group, i) ->
                params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                subtitlesDone = true
                changed = true
            }
        }
        if (changed) exo.trackSelectionParameters = params.build()
    }

    private fun remember(tracks: Tracks) {
        val audio = chosen(tracks, C.TRACK_TYPE_AUDIO)
        val text = chosen(tracks, C.TRACK_TYPE_TEXT)
        val o = JSONObject()
            .put("audioLang", audio?.language ?: "")
            .put("audioLabel", audio?.label ?: "")
            .put("subOn", text != null)
            .put("subLang", text?.language ?: "")
            .put("subLabel", text?.label ?: "")
        prefs.edit().putString(show, o.toString()).apply()
    }

    private fun chosen(tracks: Tracks, type: Int): Format? =
        tracks.groups.filter { it.type == type }.firstNotNullOfOrNull { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } }

    /** The track of [type] in [language] (the same name first), that can play */
    private fun find(tracks: Tracks, type: Int, language: String, label: String): Pair<Tracks.Group, Int>? {
        if (language.isEmpty()) return null
        val all = tracks.groups.filter { it.type == type }.flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g to it } }
        val inLanguage = all.filter { (g, i) -> g.getTrackFormat(i).language?.let(::iso3) == iso3(language) }
        return inLanguage.firstOrNull { (g, i) -> label.isNotEmpty() && g.getTrackFormat(i).label == label } ?: inLanguage.firstOrNull()
    }

    private fun iso3(code: String): String = runCatching { java.util.Locale(code.substringBefore('-').substringBefore('_')).isO3Language }.getOrDefault(code).lowercase()
}
