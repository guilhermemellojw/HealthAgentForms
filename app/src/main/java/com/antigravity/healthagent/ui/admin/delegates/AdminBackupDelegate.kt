package com.antigravity.healthagent.ui.admin.delegates

import android.content.Context
import android.net.Uri
import com.antigravity.healthagent.domain.repository.AgentRepository
import com.antigravity.healthagent.domain.repository.AuthRepository
import com.antigravity.healthagent.domain.repository.AccessControlRepository
import com.antigravity.healthagent.domain.usecase.RestoreDataUseCase
import com.antigravity.healthagent.domain.usecase.GetTimelineUseCase
import com.antigravity.healthagent.domain.usecase.RestoreFromTimelineUseCase
import com.antigravity.healthagent.domain.usecase.CleanupBrokenHousesUseCase
import com.antigravity.healthagent.ui.admin.AdminUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AdminBackupDelegate @Inject constructor(
    private val agentRepository: AgentRepository,
    private val authRepository: AuthRepository,
    private val accessControlRepository: AccessControlRepository,
    private val restoreDataUseCase: RestoreDataUseCase,
    private val getTimelineUseCase: GetTimelineUseCase,
    private val restoreFromTimelineUseCase: RestoreFromTimelineUseCase,
    private val cleanupBrokenHousesUseCase: CleanupBrokenHousesUseCase
) {

    fun restoreToSelf(scope: CoroutineScope, state: AdminState, context: Context, uri: Uri) {
        val myUid = authRepository.getCurrentUserUid() ?: return
        restoreAgentBackup(scope, state, context, myUid, uri)
    }

    fun restoreAgentBackup(
        scope: CoroutineScope,
        state: AdminState,
        context: Context,
        agentUid: String,
        uri: Uri,
        targetDate: String? = null,
        autoShift: Boolean = false,
        onLoadAgentsData: (suspend () -> Unit)? = null
    ) {
        scope.launch {
            if (!accessControlRepository.isUserAdmin()) {
                state.uiEvent.emit("Permissão negada")
                return@launch
            }
            val agents = if (state.uiState.value is AdminUiState.Success) {
                (state.uiState.value as AdminUiState.Success).agents
            } else {
                emptyList()
            }
            val agent = agents.find { it.uid == agentUid }
            val existingDates = agent?.activities?.map { it.date.replace("/", "-") } ?: emptyList()

            val result = restoreDataUseCase(context, agentUid, uri, targetDate, existingDates, isSingleDayImport = autoShift)
            if (result.isSuccess) {
                state.uiEvent.emit("Backup restaurado com sucesso para o agente")
                onLoadAgentsData?.invoke()
            } else {
                state.uiEvent.emit("Erro ao restaurar backup: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    fun deleteAgentHouse(scope: CoroutineScope, state: AdminState, agentUid: String, houseId: String, onLoadAgentsData: suspend () -> Unit) {
        scope.launch {
            val result = agentRepository.deleteAgentHouse(agentUid, houseId)
            if (result.isSuccess) {
                onLoadAgentsData()
                state.uiEvent.emit("Registro de imóvel excluído")
            }
        }
    }

    fun deleteAgentActivity(scope: CoroutineScope, state: AdminState, agentUid: String, activityDate: String, onLoadAgentsData: suspend () -> Unit) {
        scope.launch {
            val result = agentRepository.deleteAgentActivity(agentUid, activityDate)
            if (result.isSuccess) {
                onLoadAgentsData()
                state.uiEvent.emit("Registro de atividade excluído")
            }
        }
    }

    fun clearSyncError(scope: CoroutineScope, state: AdminState, uid: String, onLoadAgentsData: suspend () -> Unit) {
        scope.launch {
            val result = agentRepository.clearSyncError(uid)
            if (result.isSuccess) {
                state.uiEvent.emit("Erro de sincronização limpo")
                onLoadAgentsData()
            }
        }
    }

    fun loadTimeline(scope: CoroutineScope, state: AdminState, uid: String) {
        scope.launch {
            state.isTimelineLoading.value = true
            val result = getTimelineUseCase(uid)
            if (result.isSuccess) {
                state.timeline.value = result.getOrNull() ?: emptyList()
            } else {
                state.uiEvent.emit("Erro ao carregar timeline: ${result.exceptionOrNull()?.message}")
            }
            state.isTimelineLoading.value = false
        }
    }

    fun restoreFromTimeline(
        scope: CoroutineScope,
        state: AdminState,
        agentUid: String,
        storagePath: String,
        onLoadAgentsData: suspend () -> Unit
    ) {
        scope.launch {
            if (!accessControlRepository.isUserAdmin()) {
                state.uiEvent.emit("Permissão negada")
                return@launch
            }
            state.isTimelineLoading.value = true
            val result = restoreFromTimelineUseCase(agentUid, storagePath)
            if (result.isSuccess) {
                state.uiEvent.emit("Restauração concluída com sucesso")
                loadTimeline(scope, state, agentUid)
                onLoadAgentsData()
            } else {
                state.uiEvent.emit("Erro na restauração: ${result.exceptionOrNull()?.message}")
            }
            state.isTimelineLoading.value = false
        }
    }

    fun performSurgicalCleanup(scope: CoroutineScope, state: AdminState, uid: String, onLoadAgentsData: suspend () -> Unit) {
        scope.launch {
            if (!accessControlRepository.isUserAdmin()) {
                state.uiEvent.emit("Permissão negada")
                return@launch
            }
            state.isLoading.value = true
            val result = cleanupBrokenHousesUseCase(uid)
            if (result.isSuccess) {
                val count = result.getOrNull() ?: 0
                state.uiEvent.emit("Limpeza concluída: $count registros removidos")
                onLoadAgentsData()
            } else {
                state.uiEvent.emit("Erro na limpeza: ${result.exceptionOrNull()?.message}")
            }
            state.isLoading.value = false
        }
    }
}
