package com.antigravity.healthagent.ui.home

import com.antigravity.healthagent.domain.repository.HouseRepository
import com.antigravity.healthagent.domain.repository.SyncRepository
import com.antigravity.healthagent.domain.usecase.CleanupBrokenHousesUseCase
import com.antigravity.healthagent.domain.usecase.GenerateTestDataUseCase
import com.antigravity.healthagent.data.settings.SettingsManager
import com.antigravity.healthagent.utils.SoundManager
import com.antigravity.healthagent.ui.state.SyncUiState
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.BuildConfig
import com.antigravity.healthagent.utils.DateUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncViewModel @Inject constructor(
    private val syncRepository: SyncRepository,
    private val settingsManager: SettingsManager,
    private val repository: HouseRepository,
    private val cleanupBrokenHousesUseCase: CleanupBrokenHousesUseCase,
    private val generateTestDataUseCase: GenerateTestDataUseCase,
    private val soundManager: SoundManager
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val dateFormatter get() = DateUtils.DASH_DATE.get()

    fun cancelScope() { scope.cancel() }

    fun syncDataToCloud(
        syncStatus: MutableStateFlow<SyncUiState>,
        remoteAgentUid: String?,
        currentUserUid: String?,
        maxOpenHouses: Int,
        pendingUpdateDrafts: Map<Int, House>,
        uiEvent: MutableStateFlow<String?>,
        data: MutableStateFlow<String>
    ) {
        if (pendingUpdateDrafts.isNotEmpty()) {
            uiEvent.value = "Resolva os conflitos (em vermelho) antes de sincronizar!"
            soundManager.playWarning()
            return
        }

        scope.launch {
            val uid = remoteAgentUid ?: currentUserUid ?: return@launch

            syncStatus.update { SyncUiState.Syncing(progress = 0.1f, message = "Iniciando sincronização...", isDownloading = false) }

            val targetUid = remoteAgentUid
            withContext(Dispatchers.IO) {
                try {
                    val pullResult = syncRepository.pullCloudDataToLocal(uid)
                    if (pullResult.isSuccess) {
                        syncStatus.update { SyncUiState.Syncing(progress = 0.3f, message = "Baixando dados atualizados...", isDownloading = true) }

                        syncStatus.update { SyncUiState.Syncing(progress = 0.6f, message = "Enviando dados para a nuvem...", isDownloading = false) }
                        val houses = repository.getAllHousesOnce(targetUid ?: "")
                        val activities = repository.getAllDayActivitiesOnce(uid)
                        val pushResult = syncRepository.pushLocalDataToCloud(houses, activities, targetUid)

                        if (pushResult.isSuccess) {
                            syncRepository.pruneOldTombstones()

                            if (uid.isNotBlank()) {
                                cleanupBrokenHousesUseCase(uid)
                            }

                            settingsManager.setLastSyncTimestamp(System.currentTimeMillis())
                            syncStatus.update { SyncUiState.Success(System.currentTimeMillis()) }

                            val housesAfterSync = repository.getAllHousesOnce(uid)
                            if (housesAfterSync.isNotEmpty()) {
                                val todayStr = dateFormatter.format(Date())
                                val hasTodayHouses = housesAfterSync.any { it.data == todayStr }
                                if (!hasTodayHouses) {
                                    val lastDate = housesAfterSync.mapNotNull {
                                        try { dateFormatter.parse(it.data) } catch (e: Exception) { null }
                                    }.maxOrNull()?.let { dateFormatter.format(it) } ?: todayStr
                                    data.value = lastDate
                                }
                            }
                        } else {
                            syncStatus.update { SyncUiState.Error("Erro ao enviar: ${pushResult.exceptionOrNull()?.message}") }
                        }
                    } else {
                        syncStatus.update { SyncUiState.Error("Falha ao baixar: ${pullResult.exceptionOrNull()?.message}") }
                    }
                } catch (e: Exception) {
                    syncStatus.update { SyncUiState.Error("Erro: ${e.message}") }
                } finally {
                    delay(1500)
                    syncStatus.update { SyncUiState.Idle(syncStatus.value.lastSyncTime) }
                }
            }
        }
    }

    fun pullDataFromCloud(
        syncStatus: MutableStateFlow<SyncUiState>,
        currentUserUid: String?,
        targetUid: String? = null,
        uiEvent: MutableStateFlow<String?>
    ) {
        scope.launch {
            val uid = targetUid ?: currentUserUid ?: return@launch

            syncStatus.update { SyncUiState.Syncing(progress = 0.1f, message = "Iniciando download...", isDownloading = true) }

            withContext(Dispatchers.IO) {
                try {
                    syncStatus.update { SyncUiState.Syncing(progress = 0.5f, message = "Baixando dados da nuvem...", isDownloading = true) }
                    val result = syncRepository.pullCloudDataToLocal(uid)
                    if (result.isSuccess) {
                        if (uid.isNotBlank()) {
                            cleanupBrokenHousesUseCase(uid)

                            val currentUser = settingsManager.cachedUser.first()
                            val name = currentUser?.agentName ?: ""
                            val email = currentUser?.email ?: ""
                            repository.migrateLocalData(name, email, uid, isCurrentAgent = true)
                        }

                        settingsManager.setLastSyncTimestamp(System.currentTimeMillis())
                        soundManager.vibrateSuccess()
                        syncStatus.update { SyncUiState.Success(System.currentTimeMillis()) }
                        uiEvent.value = "Dados baixados com sucesso."
                    } else {
                        syncStatus.update { SyncUiState.Error("Falha ao baixar: ${result.exceptionOrNull()?.message}") }
                        uiEvent.value = "Falha ao baixar dados: ${result.exceptionOrNull()?.message}"
                    }
                } catch (e: Exception) {
                    syncStatus.update { SyncUiState.Error("Erro: ${e.message}") }
                    uiEvent.value = "Erro ao baixar: ${e.message}"
                } finally {
                    delay(1500)
                    syncStatus.update { SyncUiState.Idle(syncStatus.value.lastSyncTime) }
                }
            }
        }
    }

    fun finishEditSession(
        syncStatus: MutableStateFlow<SyncUiState>,
        remoteAgent: String?,
        remoteAgentUid: String?,
        currentUserUid: String?,
        uiEvent: MutableStateFlow<String?>,
        onComplete: () -> Unit,
        remoteAgentFlow: MutableStateFlow<String?>,
        remoteAgentUidFlow: MutableStateFlow<String?>,
        pendingUpdateDraftsFlow: MutableStateFlow<Map<Int, House>>,
        housesInFlightFlow: MutableStateFlow<List<House>>
    ) {
        scope.launch(Dispatchers.IO) {
            if (remoteAgent == null) {
                onComplete()
                return@launch
            }

            syncStatus.update { SyncUiState.Syncing(progress = 0.1f, message = "Finalizando edição...", lastSyncTime = it.lastSyncTime) }

            try {
                syncStatus.update { SyncUiState.Syncing(progress = 0.5f, message = "Sincronizando dados remotos...", lastSyncTime = it.lastSyncTime) }
                val uid = remoteAgentUid ?: currentUserUid
                val houses = repository.getAllHousesOnce(uid ?: "")
                val activities = repository.getAllDayActivitiesOnce(uid ?: "")
                val result = syncRepository.pushLocalDataToCloud(houses, activities, uid ?: "")

                if (result.isSuccess) {
                    val newTs = System.currentTimeMillis()
                    syncStatus.update { SyncUiState.Success(lastSyncTime = newTs) }
                    uiEvent.value = "Edição finalizada e sincronizada!"

                    withContext(Dispatchers.Main) {
                        onComplete()
                    }

                    delay(1000)
                    remoteAgentFlow.value = null
                    remoteAgentUidFlow.value = null
                    pendingUpdateDraftsFlow.value = emptyMap()
                    housesInFlightFlow.value = emptyList()
                } else {
                    syncStatus.update { SyncUiState.Error(message = "Falha: ${result.exceptionOrNull()?.message}", lastSyncTime = it.lastSyncTime) }
                    uiEvent.value = "Falha ao finalizar: ${result.exceptionOrNull()?.message}"
                    delay(3000)
                }
            } catch (e: Exception) {
                syncStatus.update { SyncUiState.Error(message = "Erro: ${e.message}", lastSyncTime = it.lastSyncTime) }
                uiEvent.value = "Erro ao finalizar: ${e.message}"
                delay(3000)
            } finally {
                syncStatus.update { SyncUiState.Idle(lastSyncTime = syncStatus.value.lastSyncTime) }
            }
        }
    }

    fun forcePull(uid: String) {
        scope.launch {
            syncRepository.pullCloudDataToLocal(targetUid = uid, force = true)
        }
    }

    fun generateMockData(
        syncStatus: MutableStateFlow<SyncUiState>,
        agentName: String,
        currentUserUid: String?,
        currentDate: String
    ) {
        scope.launch {
            if (!BuildConfig.DEBUG) return@launch

            val uid = currentUserUid ?: return@launch

            syncStatus.update { SyncUiState.Syncing(progress = 0.1f, message = "Gerando 100 casas de teste...", lastSyncTime = it.lastSyncTime) }
            val result = generateTestDataUseCase(
                agentName = agentName,
                agentUid = uid,
                currentDate = currentDate,
                numberOfBlocks = 5,
                housesPerBlock = 20
            )

            if (result.isSuccess) {
                syncStatus.update { SyncUiState.Syncing(progress = 1.0f, message = "Dados gerados! Sincronizando...", lastSyncTime = it.lastSyncTime) }
                delay(1000)
                try {
                    val housesToPush = repository.getAllHousesOnce(uid)
                    val activitiesToPush = repository.getAllDayActivitiesOnce(uid)
                    syncRepository.pushLocalDataToCloud(housesToPush, activitiesToPush, uid)
                } catch (e: Exception) {
                    syncStatus.update { SyncUiState.Error(message = "Erro no push: ${e.message}", lastSyncTime = it.lastSyncTime) }
                }
            } else {
                syncStatus.update { SyncUiState.Error(message = "Erro: ${result.exceptionOrNull()?.message}", lastSyncTime = it.lastSyncTime) }
            }

            delay(2000)
            syncStatus.update { SyncUiState.Idle(lastSyncTime = syncStatus.value.lastSyncTime) }
        }
    }
}
