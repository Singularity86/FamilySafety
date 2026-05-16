package com.example.familysafety.storage

import com.example.familysafety.location.MemberLocation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocationPublishOutboxRepository @Inject constructor(
    private val pendingLocationPublishDao: PendingLocationPublishDao
) {
    companion object {
        private const val TAG = "LocationOutbox"
    }

    suspend fun enqueue(location: MemberLocation): Long {
        return pendingLocationPublishDao.insert(
            PendingLocationPublishEntity.fromMemberLocation(location)
        )
    }

    suspend fun enqueueAll(locations: List<MemberLocation>) {
        if (locations.isEmpty()) return
        pendingLocationPublishDao.insertAll(locations.map { PendingLocationPublishEntity.fromMemberLocation(it) })
    }

    suspend fun getPendingForMember(memberId: String): List<PendingLocationPublishEntity> {
        return pendingLocationPublishDao.getPendingForMember(memberId)
    }

    suspend fun getPendingCountForMember(memberId: String): Int {
        return pendingLocationPublishDao.getPendingCountForMember(memberId)
    }

    suspend fun getNewestPendingTimestamp(memberId: String): Long? {
        return pendingLocationPublishDao.getNewestPendingTimestamp(memberId)
    }

    suspend fun markAttempt(id: Long, error: String? = null) {
        pendingLocationPublishDao.recordAttempt(id, System.currentTimeMillis(), error)
    }

    suspend fun delete(id: Long) {
        pendingLocationPublishDao.deleteById(id)
    }

    suspend fun clearForMember(memberId: String) {
        pendingLocationPublishDao.deleteAllForMember(memberId)
        Timber.d("$TAG: cleared pending locations for member $memberId")
    }

    suspend fun applyRetentionPolicy(retentionDays: Long = 7L): Int {
        val cutoff = System.currentTimeMillis() - (retentionDays * 24 * 60 * 60 * 1000L)
        return pendingLocationPublishDao.deleteOlderThan(cutoff)
    }

    fun observePendingCount(memberId: String): Flow<Int> = flow {
        emit(getPendingCountForMember(memberId))
    }
}
