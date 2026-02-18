package com.example.familysafety.offline

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.example.familysafety.group.GroupDefinition
import com.example.familysafety.location.MemberLocation
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OfflineCache @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val Context.offlineDataStore: DataStore<Preferences> by preferencesDataStore(
        name = "offline_cache"
    )
    
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    companion object {
        private val GROUP_DEFINITION_KEY = stringPreferencesKey("group_definition")
        private val LAST_LOCATIONS_KEY = stringPreferencesKey("last_locations")
        private val PENDING_ACTIONS_KEY = stringPreferencesKey("pending_actions")
    }

    suspend fun cacheGroupDefinition(groupDefinition: GroupDefinition) {
        try {
            val groupJson = json.encodeToString(groupDefinition)
            context.offlineDataStore.edit { preferences ->
                preferences[GROUP_DEFINITION_KEY] = groupJson
            }
            Timber.d("Cached group definition v${groupDefinition.version}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to cache group definition")
        }
    }

    suspend fun getCachedGroupDefinition(): GroupDefinition? {
        return try {
            val groupJson = withTimeoutOrNull(5_000) {
                context.offlineDataStore.data
                    .map { it[GROUP_DEFINITION_KEY] }
                    .first()
            }
            groupJson?.let { json.decodeFromString(it) }
        } catch (e: Exception) {
            Timber.e(e, "Failed to retrieve cached group definition")
            null
        }
    }

    suspend fun cacheLocations(locations: Map<String, MemberLocation>) {
        try {
            val locationsJson = json.encodeToString(locations)
            context.offlineDataStore.edit { preferences ->
                preferences[LAST_LOCATIONS_KEY] = locationsJson
            }
            Timber.d("Cached ${locations.size} locations")
        } catch (e: Exception) {
            Timber.e(e, "Failed to cache locations")
        }
    }

    suspend fun getCachedLocations(): Map<String, MemberLocation> {
        return try {
            val locationsJson = withTimeoutOrNull(5_000) {
                context.offlineDataStore.data
                    .map { it[LAST_LOCATIONS_KEY] }
                    .first()
            }
            locationsJson?.let { json.decodeFromString(it) } ?: emptyMap()
        } catch (e: Exception) {
            Timber.e(e, "Failed to retrieve cached locations")
            emptyMap()
        }
    }

    suspend fun cachePendingAction(action: PendingAction) {
        try {
            val actionsJson = withTimeoutOrNull(5_000) {
                context.offlineDataStore.data
                    .map { it[PENDING_ACTIONS_KEY] }
                    .first()
            }
            
            val actions = actionsJson?.let { 
                json.decodeFromString<List<PendingAction>>(it)
            }?.toMutableList() ?: mutableListOf()
            
            actions.add(action)
            
            val updatedJson = json.encodeToString(actions)
            context.offlineDataStore.edit { preferences ->
                preferences[PENDING_ACTIONS_KEY] = updatedJson
            }
            
            Timber.d("Cached pending action: ${action.type}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to cache pending action")
        }
    }

    suspend fun getPendingActions(): List<PendingAction> {
        return try {
            val actionsJson = withTimeoutOrNull(5_000) {
                context.offlineDataStore.data
                    .map { it[PENDING_ACTIONS_KEY] }
                    .first()
            }
            
            val actions = actionsJson?.let { 
                json.decodeFromString<List<PendingAction>>(it)
            } ?: emptyList()
            
            context.offlineDataStore.edit { preferences ->
                preferences.remove(PENDING_ACTIONS_KEY)
            }
            
            actions
        } catch (e: Exception) {
            Timber.e(e, "Failed to retrieve pending actions")
            emptyList()
        }
    }

    suspend fun clearCache() {
        try {
            context.offlineDataStore.edit { it.clear() }
            Timber.i("Cleared offline cache")
        } catch (e: Exception) {
            Timber.e(e, "Failed to clear cache")
        }
    }
}

@kotlinx.serialization.Serializable
data class PendingAction(
    val type: String,
    val payload: String,
    val timestamp: Long = System.currentTimeMillis()
)
