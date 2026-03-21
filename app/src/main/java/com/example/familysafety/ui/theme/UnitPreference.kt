package com.example.familysafety.ui.theme

import android.content.Context

enum class UnitSystem { IMPERIAL, METRIC }

object UnitPreference {
    private const val PREFS = "familysafety_prefs"
    private const val KEY = "unit_system"

    fun get(context: Context): UnitSystem {
        val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "imperial")
        return if (v == "metric") UnitSystem.METRIC else UnitSystem.IMPERIAL
    }

    fun set(context: Context, system: UnitSystem) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, system.name.lowercase()).apply()
    }
}
