package io.carmo.airplay.receiver

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class RoomPreset(
    val id: String,
    val name: String,
    val createdAt: Long,
    val values: Map<String, String>
)

object RoomPresetStore {
    const val MAX_PRESETS = 5

    private val SNAPSHOT_KEYS = listOf(
        ReceiverPreferences.KEY_CUSTOM_DEVICE_NAME,
        ReceiverPreferences.KEY_QUALITY_PROFILE,
        ReceiverPreferences.KEY_SCREEN_FIT,
        ReceiverPreferences.KEY_AUDIO_SYNC_MS,
        ReceiverPreferences.KEY_AUDIO_ONLY_DISPLAY,
        ReceiverPreferences.KEY_SECURITY_MODE,
        ReceiverPreferences.KEY_WAKE_MODE,
        ReceiverPreferences.KEY_VISUALIZER_ENABLED
    )

    fun presets(context: Context): List<RoomPreset> {
        val raw = ReceiverPreferences.prefs(context)
            .getString(ReceiverPreferences.KEY_ROOM_PRESETS, "[]")
            ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val valuesJson = item.optJSONObject("values") ?: JSONObject()
                val values = mutableMapOf<String, String>()
                valuesJson.keys().forEach { key ->
                    values[key] = valuesJson.optString(key)
                }
                RoomPreset(
                    id = item.optString("id"),
                    name = item.optString("name"),
                    createdAt = item.optLong("createdAt"),
                    values = values
                )
            }.filter { it.id.isNotBlank() && it.name.isNotBlank() }
        }.getOrElse { emptyList() }
    }

    fun activePreset(context: Context): RoomPreset? {
        val activeId = ReceiverPreferences.prefs(context)
            .getString(ReceiverPreferences.KEY_ACTIVE_ROOM_PRESET_ID, null)
            ?: return null
        return presets(context).firstOrNull { it.id == activeId }
    }

    fun saveCurrent(context: Context, name: String): RoomPreset? {
        val cleanName = name.trim().take(48)
        if (cleanName.isBlank()) return null
        val existing = presets(context)
        if (existing.size >= MAX_PRESETS) return null
        val prefs = ReceiverPreferences.prefs(context)
        val values = SNAPSHOT_KEYS.associateWith { key ->
            when (key) {
                ReceiverPreferences.KEY_AUDIO_SYNC_MS ->
                    prefs.getInt(key, 0).toString()
                ReceiverPreferences.KEY_VISUALIZER_ENABLED ->
                    prefs.getBoolean(key, true).toString()
                else ->
                    prefs.getString(key, null).orEmpty()
            }
        }
        val preset = RoomPreset(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            createdAt = System.currentTimeMillis(),
            values = values
        )
        writePresets(context, existing + preset)
        return preset
    }

    fun load(context: Context, preset: RoomPreset) {
        val editor = ReceiverPreferences.prefs(context).edit()
        preset.values.forEach { (key, value) ->
            when (key) {
                ReceiverPreferences.KEY_AUDIO_SYNC_MS ->
                    editor.putInt(key, value.toIntOrNull() ?: 0)
                ReceiverPreferences.KEY_VISUALIZER_ENABLED ->
                    editor.putBoolean(key, value.toBooleanStrictOrNull() ?: true)
                ReceiverPreferences.KEY_CUSTOM_DEVICE_NAME -> {
                    if (value.isBlank()) editor.remove(key) else editor.putString(key, value)
                }
                else -> if (value.isBlank()) editor.remove(key) else editor.putString(key, value)
            }
        }
        editor.putString(ReceiverPreferences.KEY_ACTIVE_ROOM_PRESET_ID, preset.id)
        editor.apply()
    }

    fun delete(context: Context, id: String) {
        val remaining = presets(context).filterNot { it.id == id }
        val activeId = ReceiverPreferences.prefs(context)
            .getString(ReceiverPreferences.KEY_ACTIVE_ROOM_PRESET_ID, null)
        writePresets(context, remaining)
        if (activeId == id) {
            ReceiverPreferences.prefs(context).edit()
                .remove(ReceiverPreferences.KEY_ACTIVE_ROOM_PRESET_ID)
                .apply()
        }
    }

    fun cycleNext(context: Context): RoomPreset? {
        val list = presets(context)
        if (list.isEmpty()) return null
        val active = activePreset(context)
        val currentIndex = list.indexOfFirst { it.id == active?.id }.takeIf { it >= 0 } ?: -1
        val next = list[(currentIndex + 1 + list.size) % list.size]
        load(context, next)
        return next
    }

    fun formattedCreatedAt(preset: RoomPreset): String {
        return SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(preset.createdAt))
    }

    private fun writePresets(context: Context, presets: List<RoomPreset>) {
        val array = JSONArray()
        presets.take(MAX_PRESETS).forEach { preset ->
            val values = JSONObject()
            preset.values.forEach { (key, value) -> values.put(key, value) }
            array.put(
                JSONObject()
                    .put("id", preset.id)
                    .put("name", preset.name)
                    .put("createdAt", preset.createdAt)
                    .put("values", values)
            )
        }
        ReceiverPreferences.prefs(context).edit()
            .putString(ReceiverPreferences.KEY_ROOM_PRESETS, array.toString())
            .apply()
    }
}
