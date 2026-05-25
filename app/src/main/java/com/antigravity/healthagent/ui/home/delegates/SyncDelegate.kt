package com.antigravity.healthagent.ui.home.delegates

import com.antigravity.healthagent.domain.repository.HouseRepository
import com.antigravity.healthagent.ui.state.SyncUiState
import com.antigravity.healthagent.domain.repository.SyncRepository
import com.antigravity.healthagent.domain.usecase.CleanupBrokenHousesUseCase
import com.antigravity.healthagent.domain.usecase.GenerateTestDataUseCase
import com.antigravity.healthagent.data.settings.SettingsManager
import com.antigravity.healthagent.utils.SoundManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncDelegate @Inject constructor(
    private val syncRepository: SyncRepository,
    private val settingsManager: SettingsManager,
    private val repository: HouseRepository,
    private val cleanupBrokenHousesUseCase: CleanupBrokenHousesUseCase,
    private val generateTestDataUseCase: GenerateTestDataUseCase,
    private val soundManager: SoundManager
) {
    private val dateFormatter = SimpleDateFormat("dd-MM-yyyy", Locale.US)

    fun syncDataToCloud(scope: CoroutineScope, state: HomeState, maxOpenHouses: Int) {
        if (state.pendingUpdateDrafts.value.isNotEmpty()) {
            state.uiEvent.value = "Resolva os conflitos (em vermelho) antes de sincronizar!"
            soundManager.playWarning()
            return
        }

        scope.launch {
            if (state.syncStatus.value !is SyncUiState.Idle) return@launch
            val uid = state.remoteAgentUid.value ?: state.currentUserUid.value ?: return@launch

            state.syncStatus.update { SyncUiState.Syncing(progress = 0.1f, message = "Iniciando sincronização...", isDownloading = false) }

            val targetUid = state.remoteAgentUid.value
            withContext(Dispatchers.IO) {
                try {
                    // 1. Pull cloud data to local FIRST (Applies Admin Authority exclusions)
                    val pullResult = syncRepository.pullCloudDataToLocal(uid)
                    if (pullResult.isSuccess) {
                        state.syncStatus.update { SyncUiState.Syncing(progress = 0.3f, message = "Baixando dados atualizados...", isDownloading = true) }

                        // 2. Push local data to cloud
                        state.syncStatus.update { SyncUiState.Syncing(progress = 0.6f, message = "Enviando dados para a nuvem...", isDownloading = false) }
                        val houses = repository.getAllHousesOnce(targetUid ?: "")
                        val activities = repository.getAllDayActivitiesOnce(uid)
                        val pushResult = syncRepository.pushLocalDataToCloud(houses, activities, targetUid)

                        if (pushResult.isSuccess) {
                            syncRepository.pruneOldTombstones()

                            // SURGICAL CLEANUP: Auto-remove empty/broken houses (zombies) after successful sync
                            if (uid.isNotBlank()) {
                                cleanupBrokenHousesUseCase(uid)
                            }

                            settingsManager.setLastSyncTimestamp(System.currentTimeMillis())
                            state.syncStatus.update { SyncUiState.Success(System.currentTimeMillis()) }

                            // SMART AUTO-DATE SELECTION: If today is empty after sync, auto-switch to the most recent work date
                            val housesAfterSync = repository.getAllHousesOnce(uid)
                            if (housesAfterSync.isNotEmpty()) {
                                val todayStr = dateFormatter.format(Date())
                                val hasTodayHouses = housesAfterSync.any { it.data == todayStr }
                                if (!hasTodayHouses) {
                                    val lastDate = housesAfterSync.mapNotNull {
                                        try { dateFormatter.parse(it.data) } catch (e: Exception) { null }
                                    }.maxOrNull()?.let { dateFormatter.format(it) } ?: todayStr
                                    state.data.value = lastDate
                                }
                            }
                        } else {
                            state.syncStatus.update { SyncUiState.Error("Erro ao enviar: ${pushResult.exceptionOrNull()?.message}") }
                        }
                    } else {
                        state.syncStatus.update { SyncUiState.Error("Falha ao baixar: ${pullResult.exceptionOrNull()?.message}") }
                    }
                } catch (e: Exception) {
                    state.syncStatus.update { SyncUiState.Error("Erro: ${e.message}") }
                } finally {
                    delay(1500) // Stabilize Success/Error message visibility
                    state.syncStatus.update { SyncUiState.Idle(state.syncStatus.value.lastSyncTime) }
                }
            }
        }
    }

    fun pullDataFromCloud(scope: CoroutineScope, state: HomeState, targetUid: String? = null) {
        scope.launch {
            if (state.syncStatus.value !is SyncUiState.Idle) return@launch
            val uid = targetUid ?: state.currentUserUid.value ?: return@launch

            state.syncStatus.update { SyncUiState.Syncing(progress = 0.1f, message = "Iniciando download...", isDownloading = true) }

            withContext(Dispatchers.IO) {
                try {
                    state.syncStatus.update { SyncUiState.Syncing(progress = 0.5f, message = "Baixando dados da nuvem...", isDownloading = true) }
                    val result = syncRepository.pullCloudDataToLocal(uid)
                    if (result.isSuccess) {
                        // SURGICAL CLEANUP: Auto-remove empty/broken houses after download
                        if (uid.isNotBlank()) {
                            cleanupBrokenHousesUseCase(uid)

                            // IMMEDIATELY heal local data to ensure pulled legacy records are claimed
                            val currentUser = settingsManager.cachedUser.first()
                            val name = currentUser?.agentName ?: ""
                            val email = currentUser?.email ?: ""
                            repository.migrateLocalData(name, email, uid, isCurrentAgent = true)
                        }

                        settingsManager.setLastSyncTimestamp(System.currentTimeMillis())
                        soundManager.vibrateSuccess()
                        state.syncStatus.update { SyncUiState.Success(System.currentTimeMillis()) }
                        state.uiEvent.value = "Dados baixados com sucesso."
                    } else {
                        state.syncStatus.update { SyncUiState.Error("Falha ao baixar: ${result.exceptionOrNull()?.message}") }
                        state.uiEvent.value = "Falha ao baixar dados: ${result.exceptionOrNull()?.message}"
                    }
                } catch (e: Exception) {
                    state.syncStatus.update { SyncUiState.Error("Erro: ${e.message}") }
                    state.uiEvent.value = "Erro ao baixar: ${e.message}"
                } finally {
                    delay(1500)
                    state.syncStatus.update { SyncUiState.Idle(state.syncStatus.value.lastSyncTime) }
                }
            }
        }
    }

    fun finishEditSession(scope: CoroutineScope, state: HomeState, onComplete: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            val remoteAgent = state.remoteAgent.value
            if (remoteAgent == null) {
                onComplete()
                return@launch
            }

            state.syncStatus.update { SyncUiState.Syncing(progress = 0.1f, message = "Finalizando edição...", lastSyncTime = it.lastSyncTime) }

            try {
                // 1. Push local data (which belongs to the remote agent) to cloud
                state.syncStatus.update { SyncUiState.Syncing(progress = 0.5f, message = "Sincronizando dados remotos...", lastSyncTime = it.lastSyncTime) }
                val uid = state.remoteAgentUid.value ?: state.currentUserUid.value
                val houses = repository.getAllHousesOnce(uid ?: "")
                val activities = repository.getAllDayActivitiesOnce(uid ?: "")
                val result = syncRepository.pushLocalDataToCloud(houses, activities, uid ?: "")

                if (result.isSuccess) {
                    val newTs = System.currentTimeMillis()
                    state.syncStatus.update { SyncUiState.Success(lastSyncTime = newTs) }
                    state.uiEvent.value = "Edição finalizada e sincronizada!"

                    // BUG FIX: Navigate away FIRST to prevent the UI from flickering
                    withContext(Dispatchers.Main) {
                        onComplete()
                    }

                    // Give the navigation some time to finish before swapping data context
                    delay(1000)
                    // Reset remote agent fields via callbacks or directly mutating state properties
                    state.remoteAgent.value = null
                    state.remoteAgentUid.value = null
                    state.pendingUpdateDrafts.value = emptyMap()
                    state.housesInFlight.value = emptyList()
                } else {
                    state.syncStatus.update { SyncUiState.Error(message = "Falha: ${result.exceptionOrNull()?.message}", lastSyncTime = it.lastSyncTime) }
                    state.uiEvent.value = "Falha ao finalizar: ${result.exceptionOrNull()?.message}"
                    delay(3000)
                }
            } catch (e: Exception) {
                state.syncStatus.update { SyncUiState.Error(message = "Erro: ${e.message}", lastSyncTime = it.lastSyncTime) }
                state.uiEvent.value = "Erro ao finalizar: ${e.message}"
                delay(3000)
            } finally {
                state.syncStatus.update { SyncUiState.Idle(lastSyncTime = state.syncStatus.value.lastSyncTime) }
            }
        }
    }

    fun generateMockData(scope: CoroutineScope, state: HomeState) {
        scope.launch {
            val email = settingsManager.cachedUser.firstOrNull()?.email
            if (email != "gmellobkp@gmail.com") return@launch // Double security layer

            val agent = state.agentName.value
            val uid = state.currentUserUid.value ?: return@launch
            val date = state.data.value

            state.syncStatus.update { SyncUiState.Syncing(progress = 0.1f, message = "Gerando 100 casas de teste...", lastSyncTime = it.lastSyncTime) }
            val result = generateTestDataUseCase(
                agentName = agent,
                agentUid = uid,
                currentDate = date,
                numberOfBlocks = 5,
                housesPerBlock = 20
            )

            if (result.isSuccess) {
                state.syncStatus.update { SyncUiState.Syncing(progress = 1.0f, message = "Dados gerados! Sincronizando...", lastSyncTime = it.lastSyncTime) }
                delay(1000)
                try {
                    val housesToPush = repository.getAllHousesOnce(uid)
                    val activitiesToPush = repository.getAllDayActivitiesOnce(uid)
                    syncRepository.pushLocalDataToCloud(housesToPush, activitiesToPush, uid)
                } catch(e: Exception) {
                    state.syncStatus.update { SyncUiState.Error(message = "Erro no push: ${e.message}", lastSyncTime = it.lastSyncTime) }
                }
            } else {
                state.syncStatus.update { SyncUiState.Error(message = "Erro: ${result.exceptionOrNull()?.message}", lastSyncTime = it.lastSyncTime) }
            }

            delay(2000)
            state.syncStatus.update { SyncUiState.Idle(lastSyncTime = state.syncStatus.value.lastSyncTime) }
        }
    }
}
