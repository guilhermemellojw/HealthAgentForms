package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.domain.logger.AppLogger
import com.antigravity.healthagent.domain.repository.HouseReadRepository
import com.antigravity.healthagent.domain.repository.HouseWriteRepository
import com.antigravity.healthagent.utils.toDashDate
import javax.inject.Inject

class DeduplicateAgentDataUseCase @Inject constructor(
    private val readRepository: HouseReadRepository,
    private val writeRepository: HouseWriteRepository
) {
    suspend operator fun invoke(agentUid: String) {
        val allHouses = readRepository.getAllHousesOnce(agentUid)
        if (allHouses.isEmpty()) return

        val groups = allHouses.groupBy { it.generateNaturalKey() }
        val toDelete = mutableListOf<com.antigravity.healthagent.data.local.model.House>()

        groups.forEach { (_, matches) ->
            if (matches.size > 1) {
                val kept = matches.sortedWith(
                    compareByDescending<com.antigravity.healthagent.data.local.model.House> { it.isSynced }
                        .thenByDescending { it.lastUpdated }
                        .thenByDescending { it.listOrder }
                ).first()

                matches.forEach { house ->
                    if (house.id != kept.id) {
                        toDelete.add(house)
                    }
                }
            }
        }

        if (toDelete.isNotEmpty()) {
            val closedDates = toDelete.map { it.data.toDashDate() }.distinct().filter { date ->
                val activity = readRepository.getDayActivity(date, agentUid)
                activity?.isClosed == true && !activity.isManualUnlock
            }.toSet()

            val safeToDelete = toDelete.filter { it.data.toDashDate() !in closedDates }
            if (closedDates.isNotEmpty()) {
                AppLogger.w("DeduplicateAgentData", "Preserved ${toDelete.size - safeToDelete.size} duplicates in ${closedDates.size} closed days.")
            }

            safeToDelete.forEach { writeRepository.deleteHouse(it) }
        }
    }
}
