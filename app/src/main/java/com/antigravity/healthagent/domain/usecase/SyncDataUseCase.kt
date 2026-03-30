package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.domain.repository.SyncRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class SyncDataUseCase @Inject constructor(
    private val syncRepository: SyncRepository
) {
    /**
     * Pushes local data to the cloud.
     */
    suspend fun pushData(
        houses: List<House>,
        activities: List<DayActivity>,
        targetUid: String? = null,
        shouldReplace: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        syncRepository.pushLocalDataToCloud(houses, activities, targetUid, shouldReplace)
    }

    /**
     * Pulls data from the cloud to local storage.
     */
    suspend fun pullData(targetUid: String? = null, force: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        syncRepository.pullCloudDataToLocal(targetUid, force)
    }
}
