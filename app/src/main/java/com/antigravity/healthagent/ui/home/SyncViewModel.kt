package com.antigravity.healthagent.ui.home

import com.antigravity.healthagent.domain.repository.HouseRepository
import com.antigravity.healthagent.domain.repository.SyncRepository
import com.antigravity.healthagent.domain.usecase.CleanupBrokenHousesUseCase
import com.antigravity.healthagent.domain.usecase.GenerateTestDataUseCase
import com.antigravity.healthagent.data.settings.SettingsManager
import com.antigravity.healthagent.utils.SoundManager
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

import com.antigravity.healthagent.data.sync.SyncFeedbackManager

@Singleton
class SyncViewModel @Inject constructor(
    private val syncRepository: SyncRepository,
    private val settingsManager: SettingsManager,
    private val repository: HouseRepository,
    private val cleanupBrokenHousesUseCase: CleanupBrokenHousesUseCase,
    private val generateTestDataUseCase: GenerateTestDataUseCase,
    private val soundManager: SoundManager,
    private val feedbackManager: SyncFeedbackManager
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val dateFormatter get() = DateUtils.DASH_DATE.get()

    fun cancelScope() { scope.cancel() }

    fun syncDataToCloud(
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

            feedbackManager.syncing(progress = 0.1f, message = "Iniciando sincronização...", isDownloading = false)

            val targetUid = remoteAgentUid
            withContext(Dispatchers.IO) {
                try {
                    val pullResult = syncRepository.pullCloudDataToLocal(uid)
                    if (pullResult.isSuccess) {
                        val cloudMaxTime = pullResult.getOrNull()?.cloudMaxTime
                        val clockSkew = pullResult.getOrNull()?.clockSkewMs ?: 0L

                        feedbackManager.syncing(progress = 0.3f, message = "Baixando dados atualizados...", isDownloading = true)

                        feedbackManager.syncing(progress = 0.6f, message = "Enviando dados para a nuvem...", isDownloading = false)
                        val houses = repository.getAllHousesOnce(targetUid ?: "")
                        val activities = repository.getAllDayActivitiesOnce(uid)
                        val pushResult = syncRepository.pushLocalDataToCloud(houses, activities, targetUid)

                        if (pushResult.isSuccess) {
                            syncRepository.pruneOldTombstones()

                            if (uid.isNotBlank()) {
                                cleanupBrokenHousesUseCase(uid)
                            }

                            feedbackManager.success(cloudMaxTime, clockSkew)

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
                            feedbackManager.error("Erro ao enviar: ${pushResult.exceptionOrNull()?.message}")
                        }
                    } else {
                        feedbackManager.error("Falha ao baixar: ${pullResult.exceptionOrNull()?.message}")
                    }
                } catch (e: Exception) {
                    feedbackManager.error("Erro: ${e.message}")
                }
            }
        }
    }

    fun pullDataFromCloud(
        currentUserUid: String?,
        targetUid: String? = null,
        uiEvent: MutableStateFlow<String?>
    ) {
        scope.launch {
            val uid = targetUid ?: currentUserUid ?: return@launch

            feedbackManager.syncing(progress = 0.1f, message = "Iniciando download...", isDownloading = true)

            withContext(Dispatchers.IO) {
                try {
                    feedbackManager.syncing(progress = 0.5f, message = "Baixando dados da nuvem...", isDownloading = true)
                    val result = syncRepository.pullCloudDataToLocal(uid)
                    if (result.isSuccess) {
                        val cloudMaxTime = result.getOrNull()?.cloudMaxTime
                        val clockSkew = result.getOrNull()?.clockSkewMs ?: 0L

                        if (uid.isNotBlank()) {
                            cleanupBrokenHousesUseCase(uid)

                            val currentUser = settingsManager.cachedUser.first()
                            val name = currentUser?.agentName ?: ""
                            val email = currentUser?.email ?: ""
                            repository.migrateLocalData(name, email, uid, isCurrentAgent = true)
                        }

                        feedbackManager.success(cloudMaxTime, clockSkew)
                        soundManager.vibrateSuccess()
                        uiEvent.value = "Dados baixados com sucesso."
                    } else {
                        feedbackManager.error("Falha ao baixar: ${result.exceptionOrNull()?.message}")
                        uiEvent.value = "Falha ao baixar dados: ${result.exceptionOrNull()?.message}"
                    }
                } catch (e: Exception) {
                    feedbackManager.error("Erro: ${e.message}")
                    uiEvent.value = "Erro ao baixar: ${e.message}"
                }
            }
        }
    }

    fun finishEditSession(
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

            feedbackManager.syncing(progress = 0.1f, message = "Finalizando edição...")

            try {
                feedbackManager.syncing(progress = 0.5f, message = "Sincronizando dados remotos...")
                val uid = remoteAgentUid ?: currentUserUid
                val houses = repository.getAllHousesOnce(uid ?: "")
                val activities = repository.getAllDayActivitiesOnce(uid ?: "")
                val result = syncRepository.pushLocalDataToCloud(houses, activities, uid ?: "")

                if (result.isSuccess) {
                    feedbackManager.success()
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
                    feedbackManager.error("Falha: ${result.exceptionOrNull()?.message}")
                    uiEvent.value = "Falha ao finalizar: ${result.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                feedbackManager.error("Erro: ${e.message}")
                uiEvent.value = "Erro ao finalizar: ${e.message}"
            }
        }
    }

    fun forcePull(uid: String) {
        scope.launch {
            syncRepository.pullCloudDataToLocal(targetUid = uid, force = true)
        }
    }

    fun generateMockData(
        agentName: String,
        currentUserUid: String?,
        currentDate: String
    ) {
        scope.launch {
            if (!BuildConfig.DEBUG) return@launch

            val uid = currentUserUid ?: return@launch

            feedbackManager.syncing(progress = 0.1f, message = "Gerando 100 casas de teste...")
            val result = generateTestDataUseCase(
                agentName = agentName,
                agentUid = uid,
                currentDate = currentDate,
                numberOfBlocks = 5,
                housesPerBlock = 20
            )

            if (result.isSuccess) {
                feedbackManager.syncing(progress = 1.0f, message = "Dados gerados! Sincronizando...")
                delay(1000)
                try {
                    val housesToPush = repository.getAllHousesOnce(uid)
                    val activitiesToPush = repository.getAllDayActivitiesOnce(uid)
                    syncRepository.pushLocalDataToCloud(housesToPush, activitiesToPush, uid)
                } catch (e: Exception) {
                    feedbackManager.error("Erro no push: ${e.message}")
                }
            } else {
                feedbackManager.error("Erro: ${result.exceptionOrNull()?.message}")
            }
        }
    }
}
