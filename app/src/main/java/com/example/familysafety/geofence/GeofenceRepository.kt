package com.example.familysafety.geofence

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.geofenceDataStore: DataStore<Preferences> by preferencesDataStore(name = "geofences")
private val GEOFENCES_KEY = stringPreferencesKey("geofence_list")

@Singleton
class GeofenceRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val geofences: Flow<List<GeofenceZone>> = context.geofenceDataStore.data
        .map { prefs ->
            prefs[GEOFENCES_KEY]?.let { jsonStr ->
                runCatching { json.decodeFromString<List<GeofenceZone>>(jsonStr) }.getOrNull()
            } ?: emptyList()
        }

    suspend fun add(zone: GeofenceZone) {
        context.geofenceDataStore.edit { prefs ->
            val current = decode(prefs)
            prefs[GEOFENCES_KEY] = json.encodeToString(current + zone)
        }
    }

    suspend fun update(zone: GeofenceZone) {
        context.geofenceDataStore.edit { prefs ->
            val current = decode(prefs)
            prefs[GEOFENCES_KEY] = json.encodeToString(current.map { if (it.id == zone.id) zone else it })
        }
    }

    suspend fun delete(id: String) {
        context.geofenceDataStore.edit { prefs ->
            val current = decode(prefs)
            prefs[GEOFENCES_KEY] = json.encodeToString(current.filter { it.id != id })
        }
    }

    private fun decode(prefs: Preferences): List<GeofenceZone> =
        prefs[GEOFENCES_KEY]?.let {
            runCatching { json.decodeFromString<List<GeofenceZone>>(it) }.getOrNull()
        } ?: emptyList()
}
