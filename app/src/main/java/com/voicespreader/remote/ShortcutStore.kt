package com.voicespreader.remote

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

data class ShortcutCommand(
    val id: String,
    val name: String,
    val modifiers: List<String>,
    val key: String,
    val repeat: Int = 1,
)

/** 保存手机端快捷键布局；实际执行仍由 Windows 端完成。 */
class ShortcutStore(context: Context) {
    private val preferences = context.getSharedPreferences("shortcut_layout", Context.MODE_PRIVATE)

    fun list(): List<ShortcutCommand> = runCatching {
        val values = JSONArray(preferences.getString(KEY_ITEMS, "[]"))
        buildList {
            for (index in 0 until values.length()) {
                val value = values.optJSONObject(index) ?: continue
                val modifiers = buildList {
                    value.optJSONArray("modifiers")?.let { array ->
                        for (modifierIndex in 0 until array.length()) {
                            array.optString(modifierIndex).takeIf { it.isNotBlank() }?.let(::add)
                        }
                    }
                }
                val key = value.optString("key").takeIf { it.isNotBlank() } ?: continue
                add(
                    ShortcutCommand(
                        id = value.optString("id").ifBlank { ProtocolV3.newRequestId() },
                        name = value.optString("name").ifBlank { key },
                        modifiers = modifiers,
                        key = key,
                        repeat = value.optInt("repeat", 1).coerceIn(1, 3),
                    ),
                )
            }
        }
    }.getOrDefault(defaults())

    fun save(commands: Collection<ShortcutCommand>) {
        val values = JSONArray(commands.map { command ->
            JSONObject()
                .put("id", command.id)
                .put("name", command.name)
                .put("modifiers", JSONArray(command.modifiers))
                .put("key", command.key)
                .put("repeat", command.repeat.coerceIn(1, 3))
        })
        preferences.edit { putString(KEY_ITEMS, values.toString()) }
    }

    fun ensureDefaults(): List<ShortcutCommand> {
        val existing = list()
        if (existing.isNotEmpty()) return existing
        return defaults().also(::save)
    }

    fun add(command: ShortcutCommand): List<ShortcutCommand> {
        val updated = list()
            .filterNot { it.id == command.id }
            .plus(command)
        save(updated)
        return updated
    }

    companion object {
        private const val KEY_ITEMS = "items"

        val modifiers: List<String> = listOf("CTRL", "SHIFT", "ALT", "WIN")

        val keys: List<String> = buildList {
            addAll(('A'..'Z').map(Char::toString))
            addAll((0..9).map(Int::toString))
            addAll((1..24).map { "F$it" })
            addAll(
                listOf(
                    "TAB",
                    "ENTER",
                    "ESC",
                    "SPACE",
                    "BACKSPACE",
                    "DELETE",
                    "INSERT",
                    "HOME",
                    "END",
                    "PAGEUP",
                    "PAGEDOWN",
                    "LEFT",
                    "UP",
                    "RIGHT",
                    "DOWN",
                    "PRINTSCREEN",
                    "MEDIA_PLAY_PAUSE",
                    "MEDIA_NEXT",
                    "MEDIA_PREVIOUS",
                    "VOLUME_MUTE",
                    "VOLUME_DOWN",
                    "VOLUME_UP",
                ),
            )
        }
    }

    private fun defaults() = listOf(
        ShortcutCommand("copy", "复制", listOf("CTRL"), "C"),
        ShortcutCommand("paste", "粘贴", listOf("CTRL"), "V"),
        ShortcutCommand("switch", "切换窗口", listOf("ALT"), "TAB"),
        ShortcutCommand("desktop", "显示桌面", listOf("META"), "D"),
    )

}
