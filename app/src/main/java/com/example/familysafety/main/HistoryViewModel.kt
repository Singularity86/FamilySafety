package com.example.familysafety.main

import android.graphics.Bitmap
import android.location.Location
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.familysafety.avatar.AvatarRepository
import com.example.familysafety.group.FamilyMember
import com.example.familysafety.group.GroupStateManager
import com.example.familysafety.location.MemberLocation
import com.example.familysafety.storage.LocationHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val historyRepo: LocationHistoryRepository,
    private val groupStateManager: GroupStateManager,
    private val avatarRepository: AvatarRepository
) : ViewModel() {

    val memberId: String = checkNotNull(savedStateHandle["memberId"])

    companion object {
        const val MS_PER_DAY = 24 * 60 * 60 * 1000L
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

    val distanceMeters: StateFlow<Float> = locationHistory
        .map { history ->
            var total = 0f
            val results = FloatArray(1)
            for (i in 0 until history.size - 1) {
                val a = history[i]
                val b = history[i + 1]
                Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, results)
                total += results[0]
            }
            total
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)

    fun previousDay() {
        _selectedDayStart.value -= MS_PER_DAY
    }

    fun nextDay() {
        val today = todayMidnightMs()
        if (_selectedDayStart.value < today) {
            _selectedDayStart.value += MS_PER_DAY
        }
    }

    fun isAtToday(): Boolean = _selectedDayStart.value >= todayMidnightMs()

    /** Total number of location records stored for this member (all days). */
    val totalRecordCount: StateFlow<Int> = flow {
        emit(historyRepo.getCountForMember(memberId))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
}
