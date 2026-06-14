package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.domain.repository.HouseReadRepository
import com.antigravity.healthagent.domain.repository.HouseWriteRepository
import com.antigravity.healthagent.utils.TimeManager
import com.antigravity.healthagent.utils.toDashDate
import javax.inject.Inject

class NormalizeLocalDatesUseCase @Inject constructor(
    private val readRepository: HouseReadRepository,
    private val writeRepository: HouseWriteRepository
) {
    suspend operator fun invoke() {
        val allHouses = readRepository.getAllHousesSnapshot()
        val housesToUpdate = allHouses.filter { it.data.contains("/") }
        if (housesToUpdate.isNotEmpty()) {
            val updated = housesToUpdate.map {
                it.copy(
                    data = it.data.toDashDate(),
                    isSynced = false,
                    lastUpdated = TimeManager.currentTimeMillis()
                )
            }
            writeRepository.upsertHousesRaw(updated)
        }

        val allActivities = readRepository.getAllDayActivitiesSnapshot()
        val activitiesToMigrate = allActivities.filter { it.date.contains("/") }
        if (activitiesToMigrate.isNotEmpty()) {
            for (activity in activitiesToMigrate) {
                val newActivity = activity.copy(
                    date = activity.date.toDashDate(),
                    isSynced = false,
                    lastUpdated = TimeManager.currentTimeMillis()
                )
                writeRepository.deleteDayActivity(activity.date, activity.agentUid)
                writeRepository.upsertDayActivitiesRaw(listOf(newActivity))
            }
        }
    }
}
