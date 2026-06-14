package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.domain.repository.HouseRepository
import com.antigravity.healthagent.utils.toDashDate
import javax.inject.Inject

/**
 * Enforces business logic constraints regarding locked (homologated) workdays.
 * Prevents modifications to agent data on days marked as closed by supervisors.
 */
class DayLockEnforcerUseCase @Inject constructor(
    private val repository: HouseRepository
) {
    /**
     * Checks if the day is locked/homologated and throws IllegalStateException if so.
     * Force parameter enables admin bypass.
     */
    suspend fun ensureDayNotLocked(date: String, agentUid: String, force: Boolean = false) {
        if (force) return
        val normalizedDate = date.toDashDate()
        val activity = repository.getDayActivity(normalizedDate, agentUid)
        if (activity?.isClosed == true && !activity.isManualUnlock) {
            throw IllegalStateException("Este dia ($normalizedDate) está bloqueado para edições (Auditoria Concluída).")
        }
    }
}
