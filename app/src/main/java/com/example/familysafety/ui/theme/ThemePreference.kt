package com.example.familysafety.ui.theme

import android.content.Context

enum class ThemeMode { SYSTEM, LIGHT, DARK }

object ThemePreference {
    private const val PREFS = "theme_prefs"
    private const val KEY = "theme_mode"

    fun get(context: Context): ThemeMode {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, ThemeMode.SYSTEM.name)
        return ThemeMode.entries.firstOrNull { it.name == name } ?: ThemeMode.SYSTEM
    }

    fun set(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, mode.name).apply()
    }
}
