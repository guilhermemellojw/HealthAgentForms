package com.antigravity.healthagent.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.healthagent.data.backup.BackupScheduler
import com.antigravity.healthagent.data.backup.BackupManager
import com.antigravity.healthagent.data.backup.BackupData
import com.antigravity.healthagent.data.backup.BackupFrequency
import com.antigravity.healthagent.data.repository.HouseRepository
import com.antigravity.healthagent.data.repository.StreetRepository
import com.antigravity.healthagent.data.settings.SettingsManager
import com.antigravity.healthagent.domain.repository.AuthRepository
import com.antigravity.healthagent.domain.repository.LocalizationRepository
import com.antigravity.healthagent.domain.repository.SyncRepository
import com.antigravity.healthagent.domain.repository.UserRole
import com.antigravity.healthagent.domain.usecase.CleanupHistoricalDataUseCase
import com.antigravity.healthagent.ui.state.SyncUiState
import com.antigravity.healthagent.domain.usecase.RestoreDataUseCase
import com.antigravity.healthagent.utils.SoundManager
import com.antigravity.healthagent.ui.home.BackupConfirmation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsManager: SettingsManager,
    private val backupManager: BackupManager,
    private val backupScheduler: BackupScheduler,
    private val soundManager: SoundManager,
    private val cleanupHistoricalDataUseCase: CleanupHistoricalDataUseCase,
    private val repository: HouseRepository,
    private val authRepository: AuthRepository,
    private val accessControlRepository: com.antigravity.healthagent.domain.repository.AccessControlRepository,
    private val restoreDataUseCase: RestoreDataUseCase,
    private val localizationRepository: LocalizationRepository,
    private val syncRepository: SyncRepository
) : ViewModel() {

    private val dateFormatter = SimpleDateFormat("dd-MM-yyyy", Locale.US)

    val easyMode: StateFlow<Boolean> = settingsManager.easyMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val solarMode: StateFlow<Boolean> = settingsManager.solarMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val editingToolsMode: StateFlow<Boolean> = settingsManager.editingToolsMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val maxOpenHouses: StateFlow<Int> = settingsManager.maxOpenHouses
        .stateIn(viewModelScope, SharingStarted.Eagerly, 25)

    val backupFrequency: StateFlow<BackupFrequency> = settingsManager.backupFrequency
        .stateIn(viewModelScope, SharingStarted.Eagerly, BackupFrequency.DAILY)

    val themeMode: StateFlow<String?> = settingsManager.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val themeColor: StateFlow<String?> = settingsManager.themeColor
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val popSound: StateFlow<String> = settingsManager.popSound
        .stateIn(viewModelScope, SharingStarted.Eagerly, "SILENT")

    val successSound: StateFlow<String> = settingsManager.successSound
        .stateIn(viewModelScope, SharingStarted.Eagerly, "SILENT")

    val celebrationSound: StateFlow<String> = settingsManager.celebrationSound
        .stateIn(viewModelScope, SharingStarted.Eagerly, "SILENT")

    val warningSound: StateFlow<String> = settingsManager.warningSound
        .stateIn(viewModelScope, SharingStarted.Eagerly, "SILENT")

    private val _uiEvent = MutableStateFlow<String?>(null)
    val uiEvent: StateFlow<String?> = _uiEvent.asStateFlow()

    private val _syncState = MutableStateFlow<SyncUiState>(SyncUiState.Idle())
    val syncState: StateFlow<SyncUiState> = _syncState.asStateFlow()

    private val _backupConfirmation = MutableStateFlow<BackupConfirmation?>(null)
    val backupConfirmation: StateFlow<BackupConfirmation?> = _backupConfirmation.asStateFlow()

    private val _agentName = MutableStateFlow("")
    private val _currentUserUid = MutableStateFlow<String?>(null)
    private val _remoteAgentUid = MutableStateFlow<String?>(null)

    val pendingAccessRequestsCount: StateFlow<Int> = accessControlRepository.pendingAccessRequests
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    init {
        // Observe settings profile cache
        viewModelScope.launch {
            settingsManager.cachedUser.collect { user ->
                user?.let {
                    val name = it.agentName?.uppercase()?.ifBlank { null }
                        ?: it.email?.substringBefore("@")?.uppercase()
                        ?: "DESCONHECIDO"
                    _agentName.value = name
                    _currentUserUid.value = it.uid
                }
            }
        }

        viewModelScope.launch {
            settingsManager.remoteAgentUid.collect { uid ->
                _remoteAgentUid.value = uid
            }
        }

        viewModelScope.launch {
            settingsManager.remoteAgentName.collect { name ->
                name?.uppercase()?.ifBlank { null }?.let {
                    _agentName.value = it
                }
            }
        }
    }

    fun updatePopSound(soundId: String) {
        viewModelScope.launch { settingsManager.setPopSound(soundId) }
    }

    fun updateSuccessSound(soundId: String) {
        viewModelScope.launch { settingsManager.setSuccessSound(soundId) }
    }

    fun updateCelebrationSound(soundId: String) {
        viewModelScope.launch { settingsManager.setCelebrationSound(soundId) }
    }

    fun updateWarningSound(soundId: String) {
        viewModelScope.launch { settingsManager.setWarningSound(soundId) }
    }

    fun updateEasyMode(enabled: Boolean) {
        viewModelScope.launch { settingsManager.setEasyMode(enabled) }
    }

    fun updateSolarMode(enabled: Boolean) {
        viewModelScope.launch { settingsManager.setSolarMode(enabled) }
    }

    fun updateEditingToolsMode(enabled: Boolean) {
        viewModelScope.launch { settingsManager.setEditingToolsMode(enabled) }
    }

    fun updateThemeMode(mode: String) {
        viewModelScope.launch { settingsManager.setThemeMode(mode) }
    }

    fun updateThemeColor(color: String) {
        viewModelScope.launch { settingsManager.setThemeColor(color) }
    }

    fun updateBackupFrequency(frequency: BackupFrequency) {
        viewModelScope.launch {
            settingsManager.setBackupFrequency(frequency)
            backupScheduler.scheduleBackup(frequency)
        }
    }

    fun playPreview(soundId: String) {
        soundManager.playSound(soundId)
    }

    fun testCelebration() {
        soundManager.playCelebration()
    }

    fun clearUiEvent() {
        _uiEvent.value = null
    }

    fun getSoundTitle(soundId: String, context: Context): String {
        return when {
            soundId == "SILENT" -> "Silencioso"
            soundId.startsWith("SYSTEM_") -> "Som do Sistema"
            soundId.startsWith("content://") -> {
                try {
                    val ringtone = android.media.RingtoneManager.getRingtone(context, android.net.Uri.parse(soundId))
                    ringtone?.getTitle(context) ?: "Som do Sistema"
                } catch (e: Exception) {
                    "Som do Sistema"
                }
            }
            soundId.startsWith("file://") -> "Arquivo Personalizado"
            else -> "Padrão"
        }
    }

    fun refreshConfig() {
        viewModelScope.launch {
            loadDynamicConfig()
        }
    }

    private suspend fun loadDynamicConfig() {
        kotlinx.coroutines.withTimeoutOrNull(5000) {
            val bairrosResult = localizationRepository.fetchBairros()
            val settingsResult = syncRepository.fetchSystemSettings()
            if (settingsResult.isSuccess) {
                val settings = settingsResult.getOrNull() ?: emptyMap()
                settings["max_open_houses"]?.let { raw ->
                    val intVal = when (raw) {
                        is Long -> raw.toInt()
                        is Int -> raw
                        is Number -> raw.toInt()
                        is String -> raw.toIntOrNull() ?: 25
                        else -> 25
                    }
                    settingsManager.setMaxOpenHouses(intVal)
                }
                settings["custom_activities"]?.let { raw ->
                    val setVal = when (raw) {
                        is List<*> -> raw.mapNotNull { it?.toString() }.toSet()
                        is String -> raw.split(",").filter { it.isNotBlank() }.toSet()
                        else -> emptySet()
                    }
                    if (setVal.isNotEmpty()) {
                        settingsManager.setCustomActivities(setVal)
                    }
                }
            }
        }
    }

    fun backupDataAndShare(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val effectiveUid = _remoteAgentUid.value ?: _currentUserUid.value
                val agentName = _agentName.value
                val h = repository.getAllHousesOnce(effectiveUid ?: "")
                val a = repository.getAllDayActivitiesOnce(effectiveUid ?: "")
                val backupData = BackupData(h, a, effectiveUid ?: "", agentName)
                
                val sdf = SimpleDateFormat("dd-MM-yyyy_HH-mm", Locale.US)
                val now = sdf.format(Date())
                val safeAgentName = _agentName.value.trim().replace(" ", "_").ifBlank { "Agente" }
                val fileName = "Backup_${safeAgentName}_$now.json"
                
                val backupDir = File(context.cacheDir, "backups")
                if (backupDir.exists()) backupDir.deleteRecursively()
                backupDir.mkdirs()
                
                val file = File(backupDir, fileName)
                backupManager.exportToFile(file, backupData)
                
                val authority = "${context.packageName}.fileprovider"
                val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, file)
                
                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    putExtra(android.content.Intent.EXTRA_SUBJECT, fileName)
                    putExtra(android.content.Intent.EXTRA_TEXT, "Segue em anexo o backup dos dados do agente $safeAgentName.")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                
                val chooser = android.content.Intent.createChooser(shareIntent, "Salvar Backup em...")
                chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                
                withContext(Dispatchers.Main) {
                    context.startActivity(chooser)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    _uiEvent.value = "Erro ao gerar backup para compartilhamento: ${e.message}"
                    soundManager.playWarning()
                }
            }
        }
    }

    fun restoreData(context: Context, uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val backupData = backupManager.importData(context, uri)
                val currentAgent = _agentName.value.trim().uppercase()
                
                val backupAgent = (backupData.houses.map { it.agentName } + backupData.dayActivities.map { it.agentName })
                    .filter { it.isNotBlank() }
                    .distinct()
                    .firstOrNull()?.trim()?.uppercase() ?: ""
                
                if (currentAgent.isNotBlank() && backupAgent.isNotBlank() && currentAgent != backupAgent) {
                    _backupConfirmation.value = BackupConfirmation(
                        backupAgentName = backupAgent,
                        currentAgentName = currentAgent,
                        housesCount = backupData.houses.size,
                        activitiesCount = backupData.dayActivities.size,
                        uri = uri,
                        isFullRestore = true
                    )
                    return@launch
                }

                performRestore(context, uri)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiEvent.value = "Erro ao restaurar backup: ${e.message}"
                    soundManager.playWarning()
                }
            }
        }
    }

    private suspend fun performRestore(context: Context, uri: android.net.Uri) {
        try {
            val targetUid = _remoteAgentUid.value ?: _currentUserUid.value
            val result = restoreDataUseCase(context, targetUid ?: "", uri)

            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    val isPartial = result.getOrDefault(false)
                    if (isPartial) {
                        _uiEvent.value = "Aviso: Backup incompleto (arquivo truncado). Alguns dados foram recuperados."
                        soundManager.playWarning()
                    } else {
                        _uiEvent.value = "Backup restaurado com sucesso!"
                        soundManager.playPop()
                    }
                } else {
                    _uiEvent.value = "Falha ao restaurar: ${result.exceptionOrNull()?.message}"
                    soundManager.playWarning()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                _uiEvent.value = "Erro no restauro: ${e.message}"
                soundManager.playWarning()
            }
        }
    }

    fun confirmBackupImport(context: Context) {
        val confirmation = _backupConfirmation.value ?: return
        val uri = confirmation.uri
        val isFullRestore = confirmation.isFullRestore
        _backupConfirmation.value = null
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (isFullRestore) {
                    performRestore(context, uri)
                } else {
                    performImportDayData(context, uri)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiEvent.value = "Erro ao processar confirmação: ${e.message}"
                    soundManager.playWarning()
                }
            }
        }
    }

    fun cancelBackupImport() {
        if (_backupConfirmation.value != null) {
            _backupConfirmation.value = null
            _uiEvent.value = "Importação cancelada pelo usuário."
        }
    }

    fun importDayData(context: Context, uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val backupData = backupManager.importData(context, uri)
                
                // For day import check if it contains any houses
                if (backupData.houses.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        _uiEvent.value = "O arquivo não contém registros de imóveis."
                        soundManager.playWarning()
                    }
                    return@launch
                }

                performImportDayData(context, uri)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiEvent.value = "Erro ao importar dia: ${e.message}"
                    soundManager.playWarning()
                }
            }
        }
    }

    private suspend fun performImportDayData(context: Context, uri: android.net.Uri) {
        try {
            val targetUid = _remoteAgentUid.value ?: _currentUserUid.value
            val existingDates = repository.getHousesByAgentSnapshot(targetUid ?: "").map { it.data }.distinct()

            val result = restoreDataUseCase(
                context = context,
                targetUid = targetUid ?: "",
                fileUri = uri,
                targetDate = "", // single day import will auto-detect from backupData houses if targetDate is blank or matches
                existingDates = existingDates,
                isSingleDayImport = true
            )

            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    val isPartial = result.getOrDefault(false)
                    if (isPartial) {
                        _uiEvent.value = "Aviso: Importação incompleta. Verifique os dados."
                        soundManager.playWarning()
                    } else {
                        _uiEvent.value = "Dados importados com sucesso!"
                        soundManager.playPop()
                    }
                } else {
                    _uiEvent.value = "Falha na importação: ${result.exceptionOrNull()?.message}"
                    soundManager.playWarning()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                _uiEvent.value = "Erro na finalização da importação: ${e.message}"
                soundManager.playWarning()
            }
        }
    }

    fun importCustomSound(uri: android.net.Uri, category: com.antigravity.healthagent.utils.SoundCategory) {
        viewModelScope.launch {
            val soundUri = "file://${uri.path}"
            when (category) {
                com.antigravity.healthagent.utils.SoundCategory.POP -> settingsManager.setPopSound(soundUri)
                com.antigravity.healthagent.utils.SoundCategory.SUCCESS -> settingsManager.setSuccessSound(soundUri)
                com.antigravity.healthagent.utils.SoundCategory.CELEBRATION -> settingsManager.setCelebrationSound(soundUri)
                com.antigravity.healthagent.utils.SoundCategory.WARNING -> settingsManager.setWarningSound(soundUri)
            }
        }
    }

    fun cleanupHistoricalData(beforeDate: String) {
        if (_syncState.value is SyncUiState.Syncing) return
        _syncState.value = SyncUiState.Syncing(progress = 0.5f, message = "Limpando histórico...")
        _uiEvent.value = "Iniciando limpeza de histórico..."
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val user = authRepository.currentUserAsync.first()
                val isAdmin = user?.isAdmin == true
                
                val result = cleanupHistoricalDataUseCase(
                    beforeDate = beforeDate,
                    agentName = _agentName.value,
                    agentUid = user?.uid ?: "",
                    isGlobal = isAdmin
                )
                
                withContext(Dispatchers.Main) {
                    if (result.isSuccess) {
                        val message = if (isAdmin) "Histórico global anterior a $beforeDate removido."
                                     else "Seu histórico anterior a $beforeDate removido localmente."
                        _uiEvent.value = message
                        soundManager.playSuccess()
                    } else {
                        _uiEvent.value = "Erro na limpeza: ${result.exceptionOrNull()?.message}"
                        soundManager.playWarning()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiEvent.value = "Erro inesperado: ${e.message}"
                    soundManager.playWarning()
                }
            } finally {
                _syncState.value = SyncUiState.Idle()
            }
        }
    }

    fun clearAllData() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _syncState.value = SyncUiState.Syncing(progress = 0.5f, message = "Limpando todos os dados...")
                _uiEvent.value = "Iniciando limpeza completa..."
                
                val user = authRepository.currentUserAsync.first()
                val isAdmin = user?.isAdmin == true
                
                repository.clearAllData()
                
                if (isAdmin) {
                    _uiEvent.value = "Limpando dados da nuvem (Global)..."
                    val cloudResult = syncRepository.deleteAllCloudData()
                    if (cloudResult.isFailure) {
                        _uiEvent.value = "Limpeza local concluída, mas falha na nuvem: ${cloudResult.exceptionOrNull()?.message}"
                        soundManager.playWarning()
                        return@launch
                    }
                }

                withContext(Dispatchers.Main) {
                    soundManager.playPop()
                    val message = if (isAdmin) "Todos os dados locais e da nuvem foram apagados." else "Todos os dados locais foram apagados."
                    _uiEvent.value = message
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiEvent.value = "Erro ao apagar dados: ${e.message}"
                }
            } finally {
                _syncState.value = SyncUiState.Idle()
            }
        }
    }
}
