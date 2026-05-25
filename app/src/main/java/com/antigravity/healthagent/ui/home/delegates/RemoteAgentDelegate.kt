package com.antigravity.healthagent.ui.home.delegates

import com.antigravity.healthagent.domain.repository.AgentData
import com.antigravity.healthagent.domain.repository.HouseRepository
import com.antigravity.healthagent.domain.repository.SyncRepository
import com.antigravity.healthagent.data.settings.SettingsManager
import com.antigravity.healthagent.utils.SoundManager
import com.antigravity.healthagent.domain.logger.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteAgentDelegate @Inject constructor(
    private val repository: HouseRepository,
    private val syncRepository: SyncRepository,
    private val settingsManager: SettingsManager,
    private val soundManager: SoundManager
) {

    fun setRemoteAgent(
        scope: CoroutineScope,
        state: HomeState,
        agent: AgentData?,
        localAgentNameBackup: String?,
        onBackupChanged: (String?) -> Unit
    ) {
        val previousAgentName = state.remoteAgent.value
        val previousAgentUid = state.remoteAgentUid.value

        if (agent != null) {
            // Store backup of local name if we haven't already
            if (localAgentNameBackup == null) {
                onBackupChanged(state.agentName.value)
            }

            // IMPROVEMENT: Use the best available name
            val selectedName = (agent.agentName?.takeIf { it.isNotBlank() && !it.contains("@") }
                ?: agent.email.substringBefore("@")).uppercase()

            state.agentName.value = selectedName
            state.remoteAgent.value = selectedName
            state.remoteAgentUid.value = agent.uid
            state.pendingUpdateDrafts.value = emptyMap()
            state.housesInFlight.value = emptyList()

            // SURGICAL FIX: Immediately remove any of my work that might be misattributed to this agent
            scope.launch(Dispatchers.IO) {
                repository.cleanMisattributedData(agent.uid, state.currentUserUid.value ?: "")
            }
        } else {
            // Restoring local state
            localAgentNameBackup?.let { state.agentName.value = it }
            onBackupChanged(null)
            state.remoteAgent.value = null
            state.remoteAgentUid.value = null
            state.pendingUpdateDrafts.value = emptyMap()
            state.housesInFlight.value = emptyList()

            // GUARANTEED CLEANUP: If we were inspecting someone, clear their data now
            if (previousAgentName != null && previousAgentUid != null) {
                scope.launch(Dispatchers.IO) {
                    AppLogger.i("HomeViewModel", "Guaranteed cleanup: Clearing data for $previousAgentName ($previousAgentUid)")
                    syncRepository.clearAgentData(previousAgentUid)
                }
            }
        }

        // Persist for background processes
        scope.launch(Dispatchers.IO) {
            settingsManager.setRemoteAgentUid(agent?.uid)
            settingsManager.setRemoteAgentName(agent?.agentName ?: agent?.email?.substringBefore("@"))
            if (agent != null) {
                try {
                    // Force normalization of all local data for this agent
                    val name = agent.agentName?.takeIf { it.isNotBlank() } ?: agent.email.substringBefore("@")
                    repository.migrateLocalData(name, agent.email, agent.uid, isCurrentAgent = false)

                    // Specific cleanup if name was just an email
                    if (agent.agentName?.isNotBlank() == true && !agent.agentName.contains("@")) {
                        repository.fixEmailNamesForUid(agent.uid, agent.agentName)
                    }
                } catch (e: Exception) {
                    AppLogger.e("HomeViewModel", "Error migrating remote agent local data", e)
                }
            }
        }
    }

    fun deduplicateCurrentDay(scope: CoroutineScope, state: HomeState) {
        scope.launch {
            if (!state.isAdmin.value) {
                state.uiEvent.value = "Apenas administradores podem executar deduplicação."
                soundManager.playWarning()
                return@launch
            }

            state.isSyncing.value = true
            state.uiEvent.value = "Iniciando deduplicação..."

            try {
                val currentAgent = state.agentName.value
                val currentUid = state.remoteAgentUid.value ?: state.currentUserUid.value

                if (currentAgent.isNotBlank() && (currentUid ?: "").isNotBlank()) {
                    repository.deduplicateAgentData(currentUid ?: "")

                    // If we are inspecting, also do a cross-identity surgical clean
                    if (state.remoteAgentUid.value != null) {
                        repository.cleanMisattributedData(currentUid ?: "", state.currentUserUid.value ?: "")
                    }

                    state.uiEvent.value = "Deduplicação concluída. Imóveis conflitantes removidos."
                    soundManager.playSuccess()
                } else {
                    state.uiEvent.value = "Erro: Identidade do agente não localizada."
                }
            } catch (e: Exception) {
                state.uiEvent.value = "Erro na deduplicação: ${e.message}"
                soundManager.playWarning()
            } finally {
                state.isSyncing.value = false
            }
        }
    }
}
