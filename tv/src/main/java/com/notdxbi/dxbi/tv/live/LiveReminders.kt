package com.notdxbi.dxbi.tv.live

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Programmes and matches someone asked to be reminded of. A card shows two minutes before, anywhere in Lumen
 * (see ReminderCard). Kept on this TV only.
 */
object LiveReminders {
    data class Reminder(
        /** A programme's or fixture's own id: one reminder each */
        val id: String,
        val title: String,
        val startMs: Long,
        /** Where it's on: the IPTV channel to tune to (null: not linked yet) */
        val channelId: String?,
        val channelName: String?,
        /** A sports fixture's id, to find its best stream at the time */
        val fixtureId: String? = null,
        val dismissed: Boolean = false,
    )

    private const val PREFS = "live-reminders"
    private const val KEY = "list"
    private val _all = MutableStateFlow<List<Reminder>>(emptyList())
    val all: StateFlow<List<Reminder>> = _all.asStateFlow()
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        val now = System.currentTimeMillis()
        _all.value = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Reminder(
                    id = o.getString("id"),
                    title = o.optString("title"),
                    startMs = o.optLong("startMs"),
                    channelId = o.optString("channelId").takeIf { it.isNotEmpty() },
                    channelName = o.optString("channelName").takeIf { it.isNotEmpty() },
                    fixtureId = o.optString("fixtureId").takeIf { it.isNotEmpty() },
                    dismissed = o.optBoolean("dismissed"),
                )
            }
        }.getOrDefault(emptyList()).filter { it.startMs > now - 3 * 3_600_000L }
    }

    fun has(id: String): Boolean = _all.value.any { it.id == id }

    fun toggle(context: Context, reminder: Reminder): Boolean {
        load(context)
        val on = !has(reminder.id)
        _all.value = if (on) _all.value + reminder else _all.value.filterNot { it.id == reminder.id }
        save(context)
        return on
    }

    fun dismiss(context: Context, id: String) {
        _all.value = _all.value.map { if (it.id == id) it.copy(dismissed = true) else it }
        save(context)
    }

    /** The reminder due now (from two minutes before it starts until ten minutes in), if any */
    fun due(nowMs: Long): Reminder? = _all.value
        .filter { !it.dismissed && nowMs >= it.startMs - 2 * 60_000L && nowMs < it.startMs + 10 * 60_000L }
        .minByOrNull { it.startMs }

    private fun save(context: Context) {
        val arr = JSONArray()
        _all.value.forEach { r ->
            arr.put(
                JSONObject()
                    .put("id", r.id).put("title", r.title).put("startMs", r.startMs)
                    .put("channelId", r.channelId ?: "").put("channelName", r.channelName ?: "")
                    .put("fixtureId", r.fixtureId ?: "").put("dismissed", r.dismissed),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}
