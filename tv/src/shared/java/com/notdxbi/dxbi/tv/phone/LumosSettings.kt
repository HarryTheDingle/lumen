package com.notdxbi.dxbi.tv.phone

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lumen's settings that mean something on a TV with the Lumos engine (Lumen's plugin had 72, most for Stremio's web page),
 * with Lumen's own wording and defaults. Kept on this TV. The screens read them as Lumen's did ([Stremio.settings]: key,
 * type, label, description, group, options, value); the engine applies them ([Engine.applySettings]).
 */
object LumosSettings {
    class Def(
        val key: String,
        val type: String,
        val group: String,
        val label: String,
        val description: String,
        val default: Any,
        val options: List<Pair<Any, String>> = emptyList(),
    )

    const val PLAYBACK = "Playback"
    const val SUBTITLES = "Subtitles"
    const val EPISODES = "Episodes"
    const val PLAYER = "Player"

    val DEFS = listOf(
        Def("quickPlay", "toggle", PLAYBACK, "Just press Play", "Play and episode clicks start the best stream straight away, and Lumen remembers what you choose. Skips streams that would start a new debrid download.", true),
        Def("quickPlayQuality", "select", PLAYBACK, "Highest quality to pick", "The best Just press Play will go to. Choose a stream yourself for anything else.", "best",
            listOf("best" to "Best available", "2160" to "4K", "1080" to "1080p", "720" to "720p")),
        Def("quickPlaySpeed", "toggle", PLAYBACK, "Match my internet speed", "Before picking, Lumen checks your connection with a short download from Cloudflare's speed test (rechecked every 10 minutes) and skips streams too big for it. If a stream still buffers, Streams in the player has lighter ones.", true),
        Def("preferImax", "toggle", PLAYBACK, "Prefer IMAX", "When the stream list has an IMAX version at the same quality, the automatic pick takes it.", false),
        Def("quickPlayAddon", "select", PLAYBACK, "Preferred addon", "Lumen picks from this addon whenever it has a stream that suits, and from the others when it doesn't.", "",
            listOf("" to "Any")),
        Def("quickPlayFallback", "select", PLAYBACK, "If my usual stream is missing", "For a title you chose a stream for yourself, when that stream isn't there any more.", "best",
            listOf("best" to "Play the next best", "ask" to "Show me the list")),

        Def("subtitlesStart", "select", SUBTITLES, "Subtitles when a video starts", "Off works like Netflix: turn them on from the subtitles button when you want them.", "off",
            listOf("off" to "Off", "eng" to "On, in English")),
        Def("subtitleStyle", "select", SUBTITLES, "Subtitle style", "How subtitles look. Picture-based subtitles keep their own look.", "stremio",
            listOf("stremio" to "Standard", "netflix" to "Netflix (white, soft edge)", "outline" to "Bold outline", "boxed" to "Boxed (dark background)", "yellow" to "Classic yellow", "soft" to "Soft")),
        Def("subtitleSize", "select", SUBTITLES, "Subtitle size", "", "100",
            listOf("75" to "Small", "100" to "Medium", "125" to "Large", "150" to "Extra large")),

        Def("skipButtons", "toggle", EPISODES, "Skip intro, recap & credits", "Skip Intro and Next Episode buttons. Times come from TheIntroDB (films and series) and AniSkip (anime), both community databases.", true),
        Def("autoSkip", "select", EPISODES, "Skip automatically", "", "off",
            listOf("off" to "Never (show the button)", "intro" to "Intros and recaps", "all" to "Intros, recaps and credits")),
        Def("upNext", "select", EPISODES, "Up next countdown", "When the credits roll, a card counts down and plays the next episode.", "10",
            listOf("5" to "5 seconds", "10" to "10 seconds", "15" to "15 seconds", "20" to "20 seconds", "off" to "Off")),
        Def("blurUnwatched", "toggle", EPISODES, "Blur unwatched episodes", "Episode pictures stay blurred until you've watched or started them, so they don't give anything away.", false),

        Def("playerClock", "toggle", PLAYER, "Show the time in the player", "The time now and when this ends, at the top right, while the controls are showing.", true),
        Def("seekPreviews", "toggle", PLAYER, "Seek bar previews", "Pictures above the seek bar as you move along it, from the lightest stream of what's playing.", true),
        Def("playTrailers", "toggle", PLAYER, "Play trailers", "A title's page fades its trailer in over the picture after a moment. Off: the picture stays.", true),
    )

    private val byKey = DEFS.associateBy { it.key }
    private var cachedRaw: String? = null
    private var cachedObj: JSONObject? = null

    @Synchronized
    private fun saved(prefs: SharedPreferences): JSONObject {
        val raw = prefs.getString("settings", "{}") ?: "{}"
        if (raw == cachedRaw) cachedObj?.let { return it }
        val parsed = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        cachedRaw = raw
        cachedObj = parsed
        return parsed
    }

    fun value(prefs: SharedPreferences, key: String): Any? {
        val obj = saved(prefs)
        return if (obj.has(key)) obj.opt(key) else byKey[key]?.default
    }

    fun text(prefs: SharedPreferences, key: String) = value(prefs, key)?.toString().orEmpty()
    fun on(prefs: SharedPreferences, key: String) = value(prefs, key) == true

    @Synchronized
    fun save(prefs: SharedPreferences, key: String, value: Any?) {
        if (key !in byKey) return
        val copy = JSONObject(saved(prefs).toString())
        copy.put(key, value ?: JSONObject.NULL)
        val raw = copy.toString()
        cachedRaw = raw
        cachedObj = copy
        prefs.edit().putString("settings", raw).apply()
    }

    /** As Lumen's page listed them; [addons]: the stream addons' names, for Preferred addon */
    fun list(prefs: SharedPreferences, addons: List<String>): JSONArray = JSONArray(DEFS.map { d ->
        val options = if (d.key == "quickPlayAddon") listOf<Pair<Any, String>>("" to "Any") + addons.map { it to it } else d.options
        JSONObject().put("key", d.key).put("type", d.type).put("group", d.group).put("label", d.label).put("description", d.description)
            .put("value", value(prefs, d.key) ?: JSONObject.NULL)
            .put("options", JSONArray(options.map { (v, l) -> JSONObject().put("value", v).put("label", l) }))
    })

    /** The player's subtitle look (Lumen's presets), as Stremio's subtitle settings were handed to it */
    fun subtitleStyle(prefs: SharedPreferences): JSONObject {
        val size = text(prefs, "subtitleSize").toIntOrNull() ?: 100
        val o = JSONObject().put("size", size).put("offset", 5)
        when (text(prefs, "subtitleStyle")) {
            "netflix" -> o.put("text", "#FFFFFF").put("outline", "#000000B0")
            "outline" -> o.put("text", "#FFFFFF").put("outline", "#000000").put("bold", true)
            "boxed" -> o.put("text", "#FFFFFF").put("background", "#000000B8").put("outline", "#00000000")
            "yellow" -> o.put("text", "#FFD43B").put("outline", "#000000")
            "soft" -> o.put("text", "#E8E8E8").put("outline", "#00000080").put("opacity", 90)
            else -> o.put("text", "#FFFFFF").put("outline", "#000000")
        }
        return o
    }
}
