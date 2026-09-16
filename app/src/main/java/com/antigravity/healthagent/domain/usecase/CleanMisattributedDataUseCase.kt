package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.domain.logger.AppLogger
import com.antigravity.healthagent.domain.repository.HouseReadRepository
import com.antigravity.healthagent.domain.repository.HouseWriteRepository
import com.antigravity.healthagent.utils.toDashDate
import javax.inject.Inject

class CleanMisattributedDataUseCase @Inject constructor(
    private val readRepository: HouseReadRepository,
    private val writeRepository: HouseWriteRepository
) {
    suspend operator fun invoke(inspectedUid: String, adminUid: String) {
        val allHouses = readRepository.getAllHousesSnapshot()
        val inspectedHouses = allHouses.filter { it.agentUid == inspectedUid }
        val adminHouses = allHouses.filter { it.agentUid == adminUid }

        if (inspectedHouses.isEmpty() || adminHouses.isEmpty()) return

        val closedDates = readRepository.getDayActivitiesByAgentSnapshot(inspectedUid)
            .filter { it.isClosed && !it.isManualUnlock }
            .map { it.date.toDashDate() }
            .toSet()

        val adminKeys = adminHouses.map { it.generateIdentityKey() }.toSet()
        val toDelete = inspectedHouses.filter {
            it.generateIdentityKey() in adminKeys &&
            it.data.toDashDate() !in closedDates
        }

        if (toDelete.isNotEmpty()) {
            AppLogger.i("CleanMisattributedData", "Removing ${toDelete.size} identity duplicates from $inspectedUid")
            toDelete.forEach { writeRepository.deleteHouse(it) }
        }
    }
}
