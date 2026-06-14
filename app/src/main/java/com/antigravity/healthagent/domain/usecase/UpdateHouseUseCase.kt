package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.Situation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class UpdateHouseUseCase @Inject constructor(
    private val clashDetector: ClashDetector,
    private val saveHouseUseCase: SaveHouseUseCase
) {

    sealed interface Result {
        data class Success(val updatedHouse: House, val subsequentUpdated: List<House>, val localizationChanged: Boolean) : Result
        data class ClashDetected(val clashingHouse: House, val updatedHouse: House) : Result
        data class Blocked(val message: String) : Result
        data class LimitReached(val house: House) : Result
        data class Error(val exception: Exception) : Result
    }

    data class Params(
        val house: House,
        val latestHousesList: List<House>,
        val currentAgentName: String,
        val currentAgentUid: String,
        val maxOpenHouses: Int,
        val isDayClosed: Boolean,
        val isManualUnlock: Boolean,
        val isAdmin: Boolean,
        val forceMerge: Boolean = false
    )

    suspend fun execute(params: Params): Result = withContext(Dispatchers.IO) {
        try {
            val original = params.latestHousesList.find { it.id == params.house.id }

            // 3. Admin Check Lock
            if (original?.editedByAdmin == true && !params.isAdmin && !params.isManualUnlock) {
                return@withContext Result.Blocked("Este imóvel foi homologado por um administrador. Desbloqueie o dia para editá-lo.")
            }

            // 4. Worked Limit Check
            val houseIsWorked = (params.house.situation == Situation.NONE || params.house.situation == Situation.EMPTY)
            val originalIsWorked = original != null && (original.situation == Situation.NONE || original.situation == Situation.EMPTY)

            if (houseIsWorked && !originalIsWorked) {
                val workedCount = params.latestHousesList.count {
                    it.data == params.house.data && (it.situation == Situation.NONE || it.situation == Situation.EMPTY)
                }
                if (workedCount >= params.maxOpenHouses && params.maxOpenHouses > 0 && !params.isManualUnlock && !params.isAdmin) {
                    return@withContext Result.LimitReached(params.house)
                }
            }

            // 5. Clash Check
            val clashingHouse = clashDetector.findClash(params.house, params.latestHousesList)
            val updatedHouse = params.house.copy(lastUpdated = System.currentTimeMillis())

            if (clashingHouse != null && !params.forceMerge) {
                return@withContext Result.ClashDetected(clashingHouse, updatedHouse)
            }

            // 6. DB Update Execution
            val houseWithIdentity = if (updatedHouse.agentUid.isBlank() || updatedHouse.agentUid == params.currentAgentUid) {
                updatedHouse.copy(agentName = params.currentAgentName, agentUid = params.currentAgentUid)
            } else {
                updatedHouse
            }

            val shouldForce = params.isAdmin || params.forceMerge
            val updateResult = saveHouseUseCase.updateHouseWithContext(houseWithIdentity, params.latestHousesList, original)

            var subsequentWithIdentity = emptyList<House>()
            if (updateResult.localizationChanged) {
                subsequentWithIdentity = updateResult.subsequentHouses.map {
                    if (it.agentUid.isBlank() || it.agentUid == params.currentAgentUid) {
                        it.copy(agentName = params.currentAgentName, agentUid = params.currentAgentUid)
                    } else {
                        it
                    }
                }
                saveHouseUseCase.updateHouses(subsequentWithIdentity + updateResult.updatedHouse, shouldForce)
            } else {
                saveHouseUseCase.updateHouse(updateResult.updatedHouse, params.latestHousesList, shouldForce)
            }

            Result.Success(
                updatedHouse = updateResult.updatedHouse,
                subsequentUpdated = subsequentWithIdentity,
                localizationChanged = updateResult.localizationChanged
            )
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
}
