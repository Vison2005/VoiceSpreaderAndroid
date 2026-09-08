package com.voicespreader.remote

import android.content.Context
import androidx.core.content.edit

enum class ThemeMode {
    LIGHT,
    DARK,
    EXTREME_DARK,
}

/** 保存三种应用主题；极暗主题只改变应用颜色，不调用屏幕亮度 API。 */
class ThemeModeStore(context: Context) {
    private val preferences = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    fun get(): ThemeMode = runCatching {
        ThemeMode.valueOf(preferences.getString(KEY_MODE, ThemeMode.DARK.name).orEmpty())
    }.getOrDefault(ThemeMode.DARK)

    fun set(mode: ThemeMode) {
        preferences.edit { putString(KEY_MODE, mode.name) }
    }

    private companion object {
        const val KEY_MODE = "theme_mode"
    }
}
