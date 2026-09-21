package com.example.familysafety.main

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.familysafety.avatar.AvatarRepository
import com.example.familysafety.geofence.GeofenceRepository
import com.example.familysafety.geofence.GeofenceZone
import com.example.familysafety.group.FamilyMember
import com.example.familysafety.group.GroupStateManager
import com.example.familysafety.history.DayTimeline
import com.example.familysafety.history.DayTimelineBuilder
import com.example.familysafety.history.FrequentPlaces
import com.example.familysafety.history.LatLon
import com.example.familysafety.history.PlaceRef
import com.example.familysafety.history.PlaceSuggestion
import com.example.familysafety.history.Stay
import com.example.familysafety.location.MemberLocation
import com.example.familysafety.storage.LocationHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val historyRepo: LocationHistoryRepository,
    private val groupStateManager: GroupStateManager,
    private val avatarRepository: AvatarRepository,
    private val geofenceRepository: GeofenceRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val memberId: String = checkNotNull(savedStateHandle["memberId"])

    companion object {
        const val MS_PER_DAY = 24 * 60 * 60 * 1000L
        private const val SUGGESTION_WINDOW_DAYS = 30
        private const val PREFS = "familysafety_prefs"
        private const val DISMISSED_KEY = "history_dismissed_places"
    }

    private fun todayMidnightMs(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    val _selectedDayStart = MutableStateFlow(todayMidnightMs())

    /** Raw fixes for the selected day, newest first, exactly as the database returns them. */
    val locationHistory: StateFlow<List<MemberLocation>> = _selectedDayStart
        .flatMapLatest { dayStart ->
            historyRepo.observeLocationHistory(memberId, dayStart, dayStart + MS_PER_DAY)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val member: StateFlow<FamilyMember?> = groupStateManager.groupDefinition
        .map { group -> group?.members?.find { it.memberId == memberId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val memberAvatar: StateFlow<Bitmap?> = avatarRepository.memberAvatars
        .map { it[memberId] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** The family's own zones, used only to name places. Address lookup is deliberately absent. */
    private val places: StateFlow<List<PlaceRef>> = geofenceRepository.geofences
        .map { zones -> zones.map { it.toPlaceRef() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val timeline: StateFlow<DayTimeline> = combine(locationHistory, places) { fixes, named ->
        DayTimelineBuilder.build(fixes, named, System.currentTimeMillis())
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DayTimeline.EMPTY)

    private val dismissed = MutableStateFlow(loadDismissed())

    /** Unnamed places this person keeps returning to, over the retained history. */
    val suggestions: StateFlow<List<PlaceSuggestion>> = combine(places, dismissed) { named, skipped ->
        named to skipped
    }
        .flatMapLatest { (named, skipped) ->
            flow {
                val now = System.currentTimeMillis()
                val fixes = historyRepo.getLocationHistory(
                    memberId, now - SUGGESTION_WINDOW_DAYS * MS_PER_DAY, now
                )
                val stays = DayTimelineBuilder.build(fixes, named, now).entries.filterIsInstance<Stay>()
                emit(FrequentPlaces.find(stays, skipped))
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Saves a suggestion as a zone with both alerts off, so it labels the place here and never
     * notifies anyone. It shows up in Zones, where alerts can be switched on if wanted.
     */
    fun namePlace(suggestion: PlaceSuggestion, name: String) {
        val label = name.trim().take(40)
        if (label.isEmpty()) return
        viewModelScope.launch {
            geofenceRepository.add(
                GeofenceZone(
                    name = label,
                    latitude = suggestion.latitude,
                    longitude = suggestion.longitude,
                    radiusMeters = 150f,
                    alertOnEnter = false,
                    alertOnExit = false
                )
            )
        }
    }

    fun dismissPlace(suggestion: PlaceSuggestion) {
        val next = dismissed.value + LatLon(suggestion.latitude, suggestion.longitude)
        dismissed.value = next
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(DISMISSED_KEY, next.map { "${it.latitude},${it.longitude}" }.toSet())
            .apply()
    }

    private fun loadDismissed(): List<LatLon> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(DISMISSED_KEY, emptySet())
            .orEmpty()
            .mapNotNull { entry ->
                val parts = entry.split(",")
                val lat = parts.getOrNull(0)?.toDoubleOrNull()
                val lon = parts.getOrNull(1)?.toDoubleOrNull()
                if (lat != null && lon != null) LatLon(lat, lon) else null
            }

    private fun GeofenceZone.toPlaceRef() = PlaceRef(id, name, latitude, longitude, radiusMeters)

    fun previousDay() {
        _selectedDayStart.value -= MS_PER_DAY
    }

    fun nextDay() {
        val today = todayMidnightMs()
        if (_selectedDayStart.value < today) {
            _selectedDayStart.value += MS_PER_DAY
        }
    }

    fun setDay(dayStartMs: Long) {
        _selectedDayStart.value = minOf(dayStartMs, todayMidnightMs())
    }

    fun todayStartMs(): Long = todayMidnightMs()

    fun isAtToday(): Boolean = _selectedDayStart.value >= todayMidnightMs()

    /** Total number of location records stored for this member (all days). */
    val totalRecordCount: StateFlow<Int> = flow {
        emit(historyRepo.getCountForMember(memberId))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
}
