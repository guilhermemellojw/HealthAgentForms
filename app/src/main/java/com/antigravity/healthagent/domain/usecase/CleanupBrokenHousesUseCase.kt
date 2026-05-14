package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.dao.HouseDao
import com.antigravity.healthagent.domain.repository.SyncRepository
import javax.inject.Inject

class CleanupBrokenHousesUseCase @Inject constructor(
    private val houseDao: HouseDao,
    private val syncRepository: SyncRepository
) {
    suspend operator fun invoke(agentUid: String): Result<Int> {
        return try {
            // 1. Identify houses with "SEM RUA", "SEM Nº", etc. (empty address fields)
            val brokenHouses = houseDao.getEmptyHouses(agentUid)
            
            if (brokenHouses.isEmpty()) {
                return Result.success(0)
            }
            
            // 2. Surgically delete them (Local + Cloud Tombstones)
            val deleteResult = syncRepository.deleteHousesSurgically(agentUid, brokenHouses)
            
            if (deleteResult.isSuccess) {
                Result.success(brokenHouses.size)
            } else {
                Result.failure(deleteResult.exceptionOrNull() ?: Exception("Failed to perform surgical cleanup"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
