package com.antigravity.healthagent.ui.home.delegates

import android.content.Context
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.domain.repository.HouseRepository
import com.antigravity.healthagent.ui.home.AuditSummary
import com.antigravity.healthagent.ui.home.DashboardTotals
import com.antigravity.healthagent.domain.usecase.DayManagementUseCase
import com.antigravity.healthagent.utils.SoundManager
import com.antigravity.healthagent.domain.logger.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DayClosingDelegate @Inject constructor(
    private val repository: HouseRepository,
    private val dayManagementUseCase: DayManagementUseCase,
    private val soundManager: SoundManager
) {

    fun startDayClosingFlow(
        scope: CoroutineScope,
        state: HomeState,
        maxOpenHouses: Int,
        validateCurrentDay: (Boolean) -> Boolean
    ) {
        scope.launch {
            if (state.pendingUpdateDrafts.value.isNotEmpty()) {
                val firstClash = state.pendingUpdateDrafts.value.values.first()
                state.duplicateHouseConfirmation.value = firstClash
                state.uiEvent.value = "Resolva os conflitos (em vermelho) antes de fechar o dia."
                soundManager.playWarning()
                return@launch
            }

            val uid = state.remoteAgentUid.value ?: state.currentUserUid.value ?: ""
            val dbHouses = repository.getHousesByDateAndAgent(state.data.value, uid)
            val drafts = state.pendingUpdateDrafts.value
            val inFlights = state.housesInFlight.value
            val allHouses = (dbHouses.map { drafts[it.id] ?: it } + inFlights)
                .filter { it.data == state.data.value }
            val workedCount = allHouses.count { it.situation == Situation.NONE || it.situation == Situation.EMPTY }

            val todayStr = com.antigravity.healthagent.utils.DateUtils.DASH_DATE.get().format(java.util.Date())
            val isToday = state.data.value == todayStr

            if (isToday && workedCount < maxOpenHouses) {
                state.uiEvent.value = "Meta de Abertos não atingida! (Abertos: $workedCount / $maxOpenHouses)"
                return@launch
            }

            if (validateCurrentDay(true)) {
                val summary = calculateAuditSummary(state.data.value, allHouses)
                state.showClosingAudit.value = summary
            }
        }
    }

    fun confirmAndCloseDay(
        scope: CoroutineScope,
        state: HomeState,
        audit: AuditSummary,
        maxOpenHouses: Int,
        triggerImmediateSync: () -> Unit
    ) {
        scope.launch {
            try {
                val isAdmin = state.isAdmin.value
                val effectiveUid = state.remoteAgentUid.value ?: state.currentUserUid.value
                dayManagementUseCase.closeDay(audit.date, effectiveUid, isAdmin)
                state.showClosingAudit.value = null

                triggerImmediateSync()
                state.showGoalReached.value = true
            } catch (e: Exception) {
                state.uiEvent.value = "Erro ao fechar o dia: ${e.message}"
                soundManager.playWarning()
            }
        }
    }

    fun toggleDayLock(scope: CoroutineScope, state: HomeState, isDayClosed: Boolean) {
        scope.launch {
            try {
                val isViewingRemoteAgent = state.remoteAgent.value != null
                if (isViewingRemoteAgent && state.isSupervisor.value && !state.isAdmin.value) {
                    state.uiEvent.value = "Apenas administradores podem gerenciar travas remotamente."
                    soundManager.playWarning()
                    return@launch
                }

                val closed = isDayClosed
                val isAdmin = state.isAdmin.value
                val effectiveUid = state.remoteAgentUid.value ?: state.currentUserUid.value
                if (closed) {
                    if (dayManagementUseCase.canSafelyUnlock(state.data.value, effectiveUid, isAdmin)) {
                        dayManagementUseCase.unlockDay(state.data.value, effectiveUid, isAdmin)
                    } else {
                        state.showHistoryUnlockConfirmation.value = true
                    }
                } else {
                    val manualUnlock = state.uiState.value.isManualUnlock
                    val currentAgent = state.agentName.value
                    val currentData = state.data.value

                    val activity = dayManagementUseCase.getDayActivity(currentData, effectiveUid)
                        ?: DayActivity(date = currentData, agentName = currentAgent, agentUid = effectiveUid ?: "")

                    repository.updateDayActivity(activity.copy(isManualUnlock = !manualUnlock), isAdmin)

                    if (!manualUnlock) {
                        state.uiEvent.value = "Edição extra habilitada para este dia."
                    } else {
                        state.uiEvent.value = "Edição extra desabilitada."
                    }
                }
            } catch (e: Exception) {
                state.uiEvent.value = "Erro ao alterar estado do dia: ${e.message}"
                soundManager.playWarning()
            }
        }
    }

    fun advanceToNextDay(scope: CoroutineScope, state: HomeState) {
        scope.launch {
            try {
                val next = dayManagementUseCase.getNextBusinessDay(state.data.value, state.remoteAgentUid.value ?: state.currentUserUid.value)
                if (next.isNotBlank()) {
                    state.data.value = next
                    soundManager.playPop()
                    state.showGoalReached.value = false
                }
            } catch (e: Exception) {
                state.uiEvent.value = "Erro ao avançar dia: ${e.message}"
            }
        }
    }

    fun dismissHistoryUnlockConfirmation(state: HomeState) {
        state.showHistoryUnlockConfirmation.value = false
    }

    fun confirmUnlockHistory(scope: CoroutineScope, state: HomeState) {
        scope.launch {
            val effectiveUid = state.remoteAgentUid.value ?: state.currentUserUid.value
            dayManagementUseCase.unlockDay(state.data.value, effectiveUid)
            state.showHistoryUnlockConfirmation.value = false
        }
    }

    fun calculateAuditSummary(date: String, dayHouses: List<House>): AuditSummary {
        return AuditSummary(
            date = date,
            totalWorked = dayHouses.count { it.situation == Situation.NONE || it.situation == Situation.EMPTY },
            totalTreated = dayHouses.count { it.treatment.hasAnyTreatment },
            totalClosed = dayHouses.count { it.situation == Situation.F },
            totalRefused = dayHouses.count { it.situation == Situation.REC },
            totalAbsent = dayHouses.count { it.situation == Situation.A },
            totalVacant = dayHouses.count { it.situation == Situation.V },
            a1 = dayHouses.sumOf { it.treatment.a1 }, a2 = dayHouses.sumOf { it.treatment.a2 }, b = dayHouses.sumOf { it.treatment.b },
            c = dayHouses.sumOf { it.treatment.c }, d1 = dayHouses.sumOf { it.treatment.d1 }, d2 = dayHouses.sumOf { it.treatment.d2 },
            e = dayHouses.sumOf { it.treatment.e }, eliminados = dayHouses.sumOf { it.treatment.eliminados },
            totalLarvicide = dayHouses.sumOf { it.treatment.larvicida }
        )
    }
}
