package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.domain.logger.AppLogger
import com.antigravity.healthagent.domain.repository.HouseRepository
import javax.inject.Inject

class SelectDayActivityUseCase @Inject constructor(
    private val repository: HouseRepository,
    private val dayManagementUseCase: DayManagementUseCase
) {
    suspend operator fun invoke(
        date: String,
        agentUid: String?,
        agentName: String,
        option: String,
        isAdmin: Boolean
    ) {
        try {
            val effectiveUid = agentUid
            val activity = dayManagementUseCase.getDayActivity(date, effectiveUid)
                ?: DayActivity(date = date, agentName = agentName, agentUid = effectiveUid ?: "")

            val isSupervisorViewing = agentUid != null
            repository.updateDayActivity(activity.copy(status = option), isSupervisorViewing || isAdmin)
        } catch (e: Exception) {
            AppLogger.e("SelectDayActivityUseCase", "Error selecting day activity", e)
        }
    }
}
