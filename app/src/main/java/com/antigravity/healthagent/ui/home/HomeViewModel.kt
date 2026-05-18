package com.antigravity.healthagent.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.ui.state.SyncUiState
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.repository.HouseRepository
import com.antigravity.healthagent.data.repository.StreetRepository
import com.antigravity.healthagent.domain.usecase.HouseValidationUseCase
import com.antigravity.healthagent.domain.usecase.DayManagementUseCase
import com.antigravity.healthagent.domain.usecase.SaveHouseUseCase
import com.antigravity.healthagent.domain.usecase.PredictHouseValuesUseCase
import com.antigravity.healthagent.domain.usecase.RecalculateVisitSegmentsUseCase
import com.antigravity.healthagent.domain.usecase.PerformLocalDatabaseMigrationUseCase
import com.antigravity.healthagent.utils.formatStreetName
import com.antigravity.healthagent.utils.normalize as stringNormalize
import com.antigravity.healthagent.utils.SoundManager
import com.antigravity.healthagent.data.settings.SettingsManager
import com.antigravity.healthagent.data.backup.BackupScheduler
import com.antigravity.healthagent.data.backup.BackupManager
import com.antigravity.healthagent.data.backup.BackupData

import com.antigravity.healthagent.domain.repository.*
import com.antigravity.healthagent.domain.usecase.*
import com.antigravity.healthagent.domain.model.DailyContext
import com.antigravity.healthagent.domain.model.VisitAddress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import com.antigravity.healthagent.utils.normalize
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: HouseRepository,
    private val settingsManager: SettingsManager,
    private val soundManager: SoundManager,
    private val syncRepository: SyncRepository,
    private val saveHouseUseCase: SaveHouseUseCase,
    private val predictHouseValuesUseCase: PredictHouseValuesUseCase,
    private val recalculateVisitSegmentsUseCase: RecalculateVisitSegmentsUseCase,
    private val performLocalDatabaseMigrationUseCase: PerformLocalDatabaseMigrationUseCase,
    val dayManagementUseCase: DayManagementUseCase,
    private val houseValidationUseCase: HouseValidationUseCase,
    private val streetRepository: StreetRepository,
    private val backupManager: BackupManager,
    private val generateTestDataUseCase: GenerateTestDataUseCase,
    private val cleanupBrokenHousesUseCase: CleanupBrokenHousesUseCase,
    private val agentRepository: AgentRepository,
    private val localizationRepository: LocalizationRepository
) : ViewModel() {

    // --- State Definitions ---
    private val dateFormatter = SimpleDateFormat("dd-MM-yyyy", Locale.US)
    private val displayDateFormatter: SimpleDateFormat get() = SimpleDateFormat("dd/MM", Locale.US)
    
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _data = MutableStateFlow(dateFormatter.format(Date()))
    val data: StateFlow<String> = _data.asStateFlow()

    private val _agentName = MutableStateFlow("")
    val agentName: StateFlow<String> = _agentName.asStateFlow()
    
    private val _searchQuery = MutableStateFlow("")



    private val _municipio = MutableStateFlow("BOM JARDIM")
    private val _bairro = MutableStateFlow("")
    private val _categoria = MutableStateFlow("BRR")
    private val _zona = MutableStateFlow("URB")
    private val _ciclo = MutableStateFlow("1º")
    private val _tipo = MutableStateFlow(2)
    private val _atividade = MutableStateFlow(4)
    fun setSupervisor(isSupervisor: Boolean) {
        _isSupervisor.value = isSupervisor
    }

    private val _isSupervisor = MutableStateFlow(false)
    private val _isAdmin = MutableStateFlow(false)

    fun setAdmin(isAdmin: Boolean) {
        _isAdmin.value = isAdmin
    }

    private val _currentBlock = MutableStateFlow("")
    private val _currentBlockSequence = MutableStateFlow("")
    private val _currentStreet = MutableStateFlow("")

    private val _agentNames = MutableStateFlow<List<String>>(emptyList())

    private val _bairrosList = MutableStateFlow<List<String>>(emptyList())
    val bairrosList: StateFlow<List<String>> = _bairrosList.asStateFlow()

    private val _currentWeekStart = MutableStateFlow(Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
    })

    private val _remoteAgent = MutableStateFlow<String?>(null)
    private var _localAgentNameBackup: String? = null
    private val _remoteAgentUid = MutableStateFlow<String?>(null)

    private val _uiEvent = MutableStateFlow<String?>(null)
    val uiEvent: StateFlow<String?> = _uiEvent.asStateFlow()

    private val _navigationTab = MutableStateFlow<Int?>(null)
    val navigationTab: StateFlow<Int?> = _navigationTab.asStateFlow()

    private val _showGoalReached = MutableStateFlow(false)
    val showGoalReached: StateFlow<Boolean> = _showGoalReached.asStateFlow()

    private val _validationErrorHouseIds = MutableStateFlow<Set<Int>>(emptySet())
    private val _isDuplicateIds = MutableStateFlow<Set<Int>>(emptySet())
    private val _syncStatus = MutableStateFlow<SyncUiState>(SyncUiState.Idle())
    val syncStatus: StateFlow<SyncUiState> = _syncStatus.asStateFlow()

    init {
        // Load persisted sync timestamp
        viewModelScope.launch {
            settingsManager.lastSyncTimestamp.collect { ts ->
                _syncStatus.update { state -> 
                    SyncUiState.Idle(lastSyncTime = ts) 
                }
            }
        }
    }

    fun triggerSync() {
        syncDataToCloud()
    }

    private val _backupConfirmation = MutableStateFlow<BackupConfirmation?>(null)

    private val _showClosingAudit = MutableStateFlow<AuditSummary?>(null)
    val showClosingAudit: StateFlow<AuditSummary?> = _showClosingAudit.asStateFlow()

    private val _integrityDialogMessage = MutableStateFlow<String?>(null)
    val integrityDialogMessage: StateFlow<String?> = _integrityDialogMessage.asStateFlow()

    private val _showMultiDayErrorDialog = MutableStateFlow(false)
    val showMultiDayErrorDialog: StateFlow<Boolean> = _showMultiDayErrorDialog.asStateFlow()

    private val _validationErrorDetails = MutableStateFlow<List<HouseValidationUseCase.ErrorDetail>>(emptyList())
    val validationErrorDetails: StateFlow<List<HouseValidationUseCase.ErrorDetail>> = _validationErrorDetails.asStateFlow()

    private val _scrollToHouseId = MutableStateFlow<Int?>(null)
    val scrollToHouseId: StateFlow<Int?> = _scrollToHouseId.asStateFlow()

    private var validationJob: kotlinx.coroutines.Job? = null

    private val _situationLimitConfirmation = MutableStateFlow<House?>(null)
    val situationLimitConfirmation: StateFlow<House?> = _situationLimitConfirmation.asStateFlow()

    private val _showHistoryUnlockConfirmation = MutableStateFlow(false)
    val showHistoryUnlockConfirmation: StateFlow<Boolean> = _showHistoryUnlockConfirmation.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _moveConfirmationData = MutableStateFlow<Pair<House, String>?>(null)
    val moveConfirmationData: StateFlow<Pair<House, String>?> = _moveConfirmationData.asStateFlow()

    private val _duplicateHouseConfirmation = MutableStateFlow<House?>(null)
    val duplicateHouseConfirmation: StateFlow<House?> = _duplicateHouseConfirmation.asStateFlow()

    private val _pendingUpdateDrafts = MutableStateFlow<Map<Int, House>>(emptyMap())
    private val _housesInFlight = MutableStateFlow<List<House>>(emptyList())
    private val _recentlyEditedHouseIds = MutableStateFlow<Map<Int, Long>>(emptyMap())
    val recentlyEditedHouseIds: StateFlow<Set<Int>> = _recentlyEditedHouseIds.map { it.keys }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val clashDialogJobs = mutableMapOf<Int, kotlinx.coroutines.Job>()
    private val houseUpdateJobs = mutableMapOf<Int, kotlinx.coroutines.Job>()
    private val _highlightedHouseId = MutableStateFlow<Int?>(null)

    // --- State Injections ---
    val easyMode: StateFlow<Boolean> = settingsManager.easyMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val solarMode: StateFlow<Boolean> = settingsManager.solarMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val editingToolsMode: StateFlow<Boolean> = settingsManager.editingToolsMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val maxOpenHouses: StateFlow<Int> = settingsManager.maxOpenHouses
        .stateIn(viewModelScope, SharingStarted.Eagerly, 5)
    val backupFrequency: StateFlow<com.antigravity.healthagent.data.backup.BackupFrequency> = settingsManager.backupFrequency
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.antigravity.healthagent.data.backup.BackupFrequency.DAILY)

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


    private val dateCache = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun getTimestamp(date: String): Long {
        return dateCache.getOrPut(date) {
            try {
                // BUG FIX: Handle slash dates to prevent timestamp 0 sorting issues
                dateFormatter.parse(date.replace("/", "-"))?.time ?: 0L
            } catch (e: Exception) {
                0L
            }
        }
    }

    fun setRemoteAgent(agent: com.antigravity.healthagent.domain.repository.AgentData?) {
        val previousAgentName = _remoteAgent.value
        val previousAgentUid = _remoteAgentUid.value

        if (agent != null) {
            // Store backup of local name if we haven't already
            if (_localAgentNameBackup == null) {
                _localAgentNameBackup = _agentName.value
            }
            
            // IMPROVEMENT: Use the best available name (Bug Fix #11)
            val selectedName = (agent.agentName?.takeIf { it.isNotBlank() && !it.contains("@") } 
                ?: agent.email.substringBefore("@")).uppercase()
                
            _agentName.value = selectedName
            _remoteAgent.value = selectedName
            _remoteAgentUid.value = agent.uid
            _pendingUpdateDrafts.value = emptyMap()
            _housesInFlight.value = emptyList()

            // SURGICAL FIX: Immediately remove any of my work that might be misattributed to this agent
            viewModelScope.launch(Dispatchers.IO) {
                repository.cleanMisattributedData(agent.uid, _currentUserUid.value ?: "")
            }
        } else {
            // Restoring local state
            _localAgentNameBackup?.let { _agentName.value = it }
            _localAgentNameBackup = null
            _remoteAgent.value = null
            _remoteAgentUid.value = null
            _pendingUpdateDrafts.value = emptyMap()
            _housesInFlight.value = emptyList()

            // GUARANTEED CLEANUP: If we were inspecting someone, clear their data now
            if (previousAgentName != null && previousAgentUid != null) {
                viewModelScope.launch(Dispatchers.IO) {
                    android.util.Log.i("HomeViewModel", "Guaranteed cleanup: Clearing data for $previousAgentName ($previousAgentUid)")
                    syncRepository.clearAgentData(previousAgentUid)
                }
            }
        }
        
        // Persist for background processes
        viewModelScope.launch(Dispatchers.IO) {
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
                    android.util.Log.e("HomeViewModel", "Error migrating remote agent local data", e)
                }
            }
        }
    }

    private val _currentUserUid = MutableStateFlow<String?>(null)

    fun finishEditSession(onComplete: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val remoteAgent = _remoteAgent.value
            if (remoteAgent == null) {
                onComplete()
                return@launch
            }
            
            _syncStatus.update { state -> SyncUiState.Syncing(progress = 0.1f, message = "Finalizando edição...", lastSyncTime = state.lastSyncTime) }
            
            try {
                // 1. Push local data (which belongs to the remote agent) to cloud
                _syncStatus.update { state -> SyncUiState.Syncing(progress = 0.5f, message = "Sincronizando dados remotos...", lastSyncTime = state.lastSyncTime) }
                val uid = _remoteAgentUid.value ?: _currentUserUid.value
                val houses = repository.getAllHousesOnce(uid ?: "")
                val activities = repository.getAllDayActivitiesOnce(uid ?: "")
                val result = syncRepository.pushLocalDataToCloud(houses, activities, uid ?: "")
                
                if (result.isSuccess) {
                    val newTs = System.currentTimeMillis()
                    _syncStatus.update { SyncUiState.Success(lastSyncTime = newTs) }
                    _uiEvent.value = "Edição finalizada e sincronizada!"
                    
                    // BUG FIX: Navigate away FIRST to prevent the UI from flickering 
                    // and briefly showing the Admin's production before the screen closes.
                    withContext(Dispatchers.Main) { 
                        onComplete() 
                    }
                    
                    // Give the navigation some time to finish before swapping data context
                    delay(1000)
                    setRemoteAgent(null)
                } else {
                    _syncStatus.update { state -> SyncUiState.Error(message = "Falha: ${result.exceptionOrNull()?.message}", lastSyncTime = state.lastSyncTime) }
                    _uiEvent.value = "Falha ao finalizar: ${result.exceptionOrNull()?.message}"
                    delay(3000)
                }
            } catch (e: Exception) {
                _syncStatus.update { state -> SyncUiState.Error(message = "Erro: ${e.message}", lastSyncTime = state.lastSyncTime) }
                _uiEvent.value = "Erro ao finalizar: ${e.message}"
                delay(3000)
            } finally {
                _syncStatus.update { state -> SyncUiState.Idle(lastSyncTime = state.lastSyncTime) }
            }
        }
    }
    
    fun generateMockData() {
        viewModelScope.launch {
            val email = settingsManager.cachedUser.firstOrNull()?.email
            if (email != "gmellobkp@gmail.com") return@launch // Double security layer
            
            val agent = _agentName.value
            val uid = _currentUserUid.value ?: return@launch
            val date = _data.value
            
            _syncStatus.update { state -> SyncUiState.Syncing(progress = 0.1f, message = "Gerando 100 casas de teste...", lastSyncTime = state.lastSyncTime) }
            val result = generateTestDataUseCase(
                agentName = agent,
                agentUid = uid,
                currentDate = date,
                numberOfBlocks = 5,
                housesPerBlock = 20
            )
            
            if (result.isSuccess) {
                _syncStatus.update { state -> SyncUiState.Syncing(progress = 1.0f, message = "Dados gerados! Sincronizando...", lastSyncTime = state.lastSyncTime) }
                delay(1000)
                try {
                    val housesToPush = repository.getAllHousesOnce(uid)
                    val activitiesToPush = repository.getAllDayActivitiesOnce(uid)
                    syncRepository.pushLocalDataToCloud(housesToPush, activitiesToPush, uid)
                } catch(e: Exception) {
                    _syncStatus.update { state -> SyncUiState.Error(message = "Erro no push: ${e.message}", lastSyncTime = state.lastSyncTime) }
                }
            } else {
                _syncStatus.update { state -> SyncUiState.Error(message = "Erro: ${result.exceptionOrNull()?.message}", lastSyncTime = state.lastSyncTime) }
            }
            
            delay(2000)
            _syncStatus.update { state -> SyncUiState.Idle(lastSyncTime = state.lastSyncTime) }
        }
    }


    private val allHousesFlow = combine(_agentName, _remoteAgentUid, _currentUserUid) { name, remoteUid, currentUid -> 
        Triple(name, remoteUid, currentUid) 
    }.distinctUntilChanged()
    .flatMapLatest { (name, remoteUid, currentUid) -> 
        val effectiveUid = remoteUid ?: currentUid
        if (effectiveUid != null) {
            // STRICT ISOLATION with ORPHAN HEALING: 
            // We fetch personal houses (UID match) PLUS orphans that match the current agent's name.
            // This ensures that records with blank UIDs (Ghost houses) are pulled into the flow,
            // allowing them to be pruned from the in-flight list and displayed for correction.
            repository.getPersonalHousesFlow(effectiveUid, name ?: "") 
        } else {
            kotlinx.coroutines.flow.flowOf(emptyList())
        }
    }
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Global list for RG view (all agents)
    // ENHANCED PRIVACY: Only provides global snapshot if viewing a remote agent or explicitly requested.
    private val globalHousesFlow = combine(_agentName, _remoteAgentUid, _currentUserUid, _isAdmin) { name, remoteUid, currentUid, isAdminRole ->
        val isViewingRemoteAgent = remoteUid != null && remoteUid != currentUid
        
        if (isAdminRole && isViewingRemoteAgent) {
            // Admin can see the specific remote agent's history or global if in a specific mode
            // For the Home screen, we still prefer isolation.
            val effectiveUid = remoteUid ?: currentUid
            repository.getHousesByAgentSnapshotFlow(effectiveUid ?: "")
        } else if (isAdminRole) {
            // Admin on their own screen: DON'T merge everything. Just show their own houses.
            repository.getHousesByAgentSnapshotFlow(currentUid ?: "")
        } else {
            val effectiveUid = remoteUid ?: currentUid
            repository.getHousesByAgentSnapshotFlow(effectiveUid ?: "")
        }
    }.flatMapLatest { it }

    // Participatory list for RG view (Team blocks)
    private val participatoryHousesFlow = combine(_agentName, _remoteAgentUid, _currentUserUid) { name, remoteUid, currentUid ->
        val effectiveUid = remoteUid ?: currentUid
        if (effectiveUid != null) {
            repository.getParticipatoryHousesFlow(effectiveUid)
        } else {
            kotlinx.coroutines.flow.flowOf(emptyList())
        }
    }.flatMapLatest { it }
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())


    val isDayClosed: StateFlow<Boolean> = combine(_data, _agentName, _remoteAgentUid, _currentUserUid) { date, name, remoteUid, currentUid ->
        val effectiveUid = remoteUid ?: currentUid
        Triple(date, name, effectiveUid)
    }.flatMapLatest { (date, name, uid) ->
        repository.getDayActivityFlow(date, uid).map { it?.isClosed == true && it?.isManualUnlock != true }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isWorkdayManualUnlock: StateFlow<Boolean> = combine(_data, _agentName, _remoteAgentUid, _currentUserUid) { date, name, remoteUid, currentUid ->
        val effectiveUid = remoteUid ?: currentUid
        Triple(date, name, effectiveUid)
    }.flatMapLatest { (date, name, uid) ->
        repository.getDayActivityFlow(date, uid).map { it?.isManualUnlock == true }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // --- CENTRALIZED TRUTH (UNIFIED STATE) ---
    // Centralized 'latestHouses' flow that combines:
    // 1. Database records (allHousesFlow)
    // 2. Pending typing (Active Drafts)
    // 3. Newly added houses not yet in DB (In-Flight)
    val latestHouses: StateFlow<List<House>> = combine(
        allHousesFlow, 
        _pendingUpdateDrafts, 
        _housesInFlight
    ) { dbHouses, drafts, inFlights ->
        (dbHouses.map { drafts[it.id] ?: it } + inFlights).sortedBy { it.listOrder }
    }
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val houses: StateFlow<List<House>> = latestHouses

    // --- Semanal State ---
    val currentWeekDates: StateFlow<List<String>> = _currentWeekStart.map { start ->
        val dates = mutableListOf<String>()
        val cal = start.clone() as Calendar
        // Work week: Monday to Friday
        for (i in 0..4) { 
            dates.add(dateFormatter.format(cal.time))
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        dates
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val weekRangeText: StateFlow<String> = _currentWeekStart.map { start ->
        val cal = start.clone() as Calendar
        // PDF/User logic: Sunday to Saturday
        // We are already at Monday. Go back 1 to Sunday.
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val sunday = cal.time
        cal.add(Calendar.DAY_OF_YEAR, 6)
        val saturday = cal.time
        
        "${displayDateFormatter.format(sunday)} a ${displayDateFormatter.format(saturday)}"
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    // Global Percurso for Completion Detection
    private val globalSortedVisits: StateFlow<List<House>> = allHousesFlow.map { all ->
        all.sortedWith(compareBy(
            { getTimestamp(it.data) },
            { it.agentName },
            { it.listOrder },
            { it.id }
        ))
    }.flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Real Activity Status Flow
    private val weekActivitiesFlow: StateFlow<List<DayActivity>> = combine(currentWeekDates, _remoteAgentUid, _currentUserUid) { dates, remote, current ->
        repository.getDayActivities(dates, remote ?: current)
    }.flatMapLatest { it }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    

    val boletimList: StateFlow<List<BoletimSummary>> = combine(allHousesFlow, globalSortedVisits, _agentName, _remoteAgentUid, _currentUserUid) { all, global, name, remoteUid, currentUid -> 
        val targetUid = remoteUid ?: currentUid
        val personalHouses = all.filter { it.agentUid == targetUid || (it.agentUid.isEmpty() && it.agentName.uppercase() == name) }
        val groupedByDate = personalHouses.groupBy { it.data }.toList().sortedByDescending { parseDate(it.first)?.time ?: 0L }
        
        groupedByDate.map { (date, houses) ->
            val blocks = houses.groupBy { "${it.address.blockNumber}-${it.address.blockSequence}-${it.address.bairro}" }
                .map { (_, blockHouses) ->
                    val h = blockHouses.first()
                    
                    // Unified Completion Logic (Manual + Successor)
                    val lastHouseInBlock = blockHouses.lastOrNull()
                    val lastHouseId = lastHouseInBlock?.id ?: -1L
                    val indexOfLastInGlobal = global.indexOfLast { it.id == lastHouseId }
                    val isImplicitlyConcluded = indexOfLastInGlobal != -1 && indexOfLastInGlobal < global.lastIndex
                    
                    val isCompleted = blockHouses.any { it.quarteiraoConcluido || it.localidadeConcluida } || isImplicitlyConcluded

                    BlockSummary(
                        number = h.address.blockNumber,
                        sequence = h.address.blockSequence,
                        bairro = h.address.bairro,
                        isCompleted = isCompleted,
                        isLocalidadeConcluded = blockHouses.any { it.isLocalidadeConcluded },
                        totalHouses = blockHouses.count { it.situation == Situation.NONE || it.situation == Situation.EMPTY },
                        totalVisits = blockHouses.size,
                        focos = blockHouses.count { it.treatment.comFoco }
                    )
                }
            
            BoletimSummary(
                date = date,
                agentName = houses.firstOrNull()?.agentName ?: name,
                totals = calculateDashboardTotals(houses),
                blocks = blocks,
                status = if (blocks.all { it.isCompleted }) "CONCLUÍDO" else "EM ABERTO"
            )
        }
    }
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())


    val activityOptions: StateFlow<List<String>> = settingsManager.customActivities.map { custom -> 
        (listOf("NORMAL", "FERIADO", "PONTO FACULTATIVO", "REUNIÃO", "TREINAMENTO") + custom.toList()).distinct()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf("NORMAL", "FERIADO", "PONTO FACULTATIVO", "REUNIÃO", "TREINAMENTO"))

    val customActivities: StateFlow<Set<String>> = settingsManager.customActivities
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())


    init {
        // Load initial state from SettingsManager
        viewModelScope.launch {
            settingsManager.cachedUser.collect { user ->
                user?.let {
                    val name = it.agentName?.uppercase()?.ifBlank { null }
                        ?: it.email?.substringBefore("@")?.uppercase()
                        ?: "DESCONHECIDO"
                    _agentName.value = name
                    _currentUserUid.value = it.uid
                    _isSupervisor.value = it.role == UserRole.SUPERVISOR
                    _isAdmin.value = it.role == UserRole.ADMIN
                }
            }
        }

        // Validation observer: Clear errors immediately on date change
        viewModelScope.launch {
            _data.collect { 
                // Clear error states immediately on date change to prevent "ghost" highlights
                _validationErrorHouseIds.value = emptySet()
                _validationErrorDetails.value = emptyList()
                _isDuplicateIds.value = emptySet()
                _integrityDialogMessage.value = null // Aggressively clear any stuck dialog
            }
        }

        // CRITICAL: Basic Header Info Observer (Decoupled from heavy house processing)
        // This ensures the Date and Agent Name are ALWAYS populated immediately.
        viewModelScope.launch {
            combine(
                _data, _agentName, _municipio, _bairro, _zona, _ciclo, _tipo, _atividade, _isSupervisor, _isAdmin
            ) { args ->
                Triple(args[0] as String, args[1] as String, args)
            }.collect { (date, name, args) ->
                _uiState.update { current ->
                    current.copy(
                        data = date,
                        agentName = name,
                        municipality = args[2] as String,
                        neighborhood = args[3] as String,
                        zone = args[4] as String,
                        cycle = args[5] as String,
                        type = args[6] as Int,
                        activity = args[7] as Int,
                        isSupervisor = args[8] as Boolean,
                        isAdmin = args[9] as Boolean
                    )
                }
            }
        }

        // CRITICAL: House List and Dashboard Totals Observer
        // Decoupled from the monolithic combine block to ensure immediate rendering.
        viewModelScope.launch {
            combine(
                latestHouses, _data, _recentlyEditedHouseIds, _highlightedHouseId, _currentUserUid
            ) { h, d, recentlyEdited, highlightedId, myUid ->
                val dayHouses = h.filter { it.data == d }
                val totals = calculateDashboardTotals(dayHouses)
                
                // Optimized duplicate check (O(N))
                val identityCounts = mutableMapOf<String, Int>()
                dayHouses.forEach { hh ->
                    val key = generateHouseKey(hh)
                    identityCounts[key] = (identityCounts[key] ?: 0) + 1
                }

                val mapped = dayHouses.map { hh ->
                    val key = generateHouseKey(hh)
                    HouseUiStateMapper.map(
                        house = hh,
                        houseValidationUseCase = houseValidationUseCase,
                        isDuplicate = (identityCounts[key] ?: 0) > 1,
                        isRecentlyEdited = recentlyEdited.containsKey(hh.id),
                        isHighlighted = hh.id == highlightedId,
                        isMine = hh.agentUid == myUid
                    )
                }
                mapped to totals
            }.collect { (mapped, totals) ->
                _uiState.update { it.copy(houses = mapped, dashboardTotals = totals) }
            }
        }

        // CRITICAL: Settings Observer (Easy Mode, Solar Mode, etc.)
        viewModelScope.launch {
            combine(easyMode, solarMode, editingToolsMode, maxOpenHouses) { e, s, t, m -> 
                listOf(e, s, t, m)
            }.collect { args ->
                _uiState.update { it.copy(
                    isEasyMode = args[0] as Boolean,
                    isSolarMode = args[1] as Boolean,
                    isEditingToolsEnabled = args[2] as Boolean,
                    maxOpenHouses = args[3] as Int
                )}
            }
        }

        // SURGICAL PROTECTION: Detect misattributed data (Merged Production Bug)
        viewModelScope.launch {
            allHousesFlow.collect { houses ->
                val myName = _agentName.value.uppercase()
                val myUid = _currentUserUid.value
                val isViewingRemote = _remoteAgentUid.value != null && _remoteAgentUid.value != myUid
                
                if (myName.isNotBlank() && myUid != null && !isViewingRemote) {
                    val hasLeaks = houses.any { 
                        it.agentUid == myUid && 
                        it.agentName.isNotBlank() && 
                        it.agentName.uppercase() != myName &&
                        !it.agentName.contains("@") // Ignore email-based names which might be legitimate fallbacks
                    }
                    _uiState.update { it.copy(hasMisattributedData = hasLeaks) }
                } else {
                    _uiState.update { it.copy(hasMisattributedData = false) }
                }
            }
        }

        // Observer for Sync Info (Metadata)
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                settingsManager.lastSyncTimestamp,
                settingsManager.clockSkewMs
            ) { lastSync, skew ->
                lastSync to skew
            }.collect { (lastSync, skew) ->
                _syncStatus.update { state -> 
                    if (state is SyncUiState.Success) state.copy(lastSyncTime = lastSync, clockSkewMs = skew) 
                    else SyncUiState.Idle(lastSyncTime = lastSync) 
                }
            }
        }

        // PRUNE OBSERVER: Reconciles the Local State (Drafts/In-Flights) with the Source of Truth (Database).
        viewModelScope.launch {
            // Observe the raw Database flow (allHousesFlow) instead of the merged 'houses' state
            // to prevent the reconciliation logic from matching against its own local overlays.
            allHousesFlow.collect { dbHouses ->
                _pendingUpdateDrafts.update { currentDrafts ->
                    if (currentDrafts.isEmpty()) return@update currentDrafts
                    val resolvedIds = mutableListOf<Int>()
                    
                    currentDrafts.forEach { (id, draft) ->
                        // 1. Check for persistent collisions in the DB
                        val stillClashes = dbHouses.any { 
                            it.id != draft.id && 
                            it.data == draft.data && 
                            it.agentUid == draft.agentUid &&
                            it.agentName.equals(draft.agentName, ignoreCase = true) &&
                            it.address.blockNumber.equals(draft.address.blockNumber, ignoreCase = true) &&
                            it.address.blockSequence.equals(draft.address.blockSequence, ignoreCase = true) &&
                            it.address.streetName.equals(draft.address.streetName, ignoreCase = true) &&
                            it.address.number.equals(draft.address.number, ignoreCase = true) &&
                            it.address.sequence == draft.address.sequence &&
                            it.address.complement == draft.address.complement &&
                            it.address.bairro.equals(draft.address.bairro, ignoreCase = true) &&
                            it.visitSegment == draft.visitSegment
                        }
                        
                        // 2. Perfect Sync Check: Prune only if the DB record exactly matches ALL user-editable fields.
                        // ADDITION: Check lastUpdated to ensure we don't prune ORPHANED drafts if the DB update hasn't arrived.
                        val dbMatch = dbHouses.find { it.id == id }
                        val isDraftSynced = dbMatch != null && 
                            dbMatch.lastUpdated >= draft.lastUpdated && // STABILITY: Ensure DB is at least as new as draft
                            dbMatch.address.number == draft.address.number && 
                            dbMatch.address.sequence == draft.address.sequence && 
                            dbMatch.address.complement == draft.address.complement &&
                            dbMatch.propertyType == draft.propertyType &&
                            dbMatch.situation == draft.situation &&
                            dbMatch.address.streetName == draft.address.streetName &&
                            dbMatch.address.blockNumber == draft.address.blockNumber &&
                            dbMatch.address.blockSequence == draft.address.blockSequence &&
                            dbMatch.address.bairro == draft.address.bairro &&
                            dbMatch.observation == draft.observation &&
                            dbMatch.treatment.a1 == draft.treatment.a1 && dbMatch.treatment.a2 == draft.treatment.a2 &&
                            dbMatch.treatment.b == draft.treatment.b && dbMatch.treatment.c == draft.treatment.c &&
                            dbMatch.treatment.d1 == draft.treatment.d1 && dbMatch.treatment.d2 == draft.treatment.d2 &&
                            dbMatch.treatment.e == draft.treatment.e && dbMatch.treatment.eliminados == draft.treatment.eliminados &&
                            dbMatch.treatment.larvicida == draft.treatment.larvicida && dbMatch.treatment.comFoco == draft.treatment.comFoco &&
                            dbMatch.quarteiraoConcluido == draft.quarteiraoConcluido &&
                            dbMatch.localidadeConcluida == draft.localidadeConcluida
                        
                        // Prune if synced or if it no longer clashes and was manually confirmed (implicit).
                        if (isDraftSynced) {
                            resolvedIds.add(id)
                        } else if (!stillClashes && dbMatch != null && dbMatch.lastUpdated == draft.lastUpdated) {
                            // If it no longer clashes and matches the DB timestamp EXACTLY, we can prune safely without flickering.
                            resolvedIds.add(id)
                        }
                    }
                    
                    if (resolvedIds.isNotEmpty()) currentDrafts - resolvedIds else currentDrafts
                }
                
                // Cleanup 'In-Flight' houses (ID 0) that have now been assigned a real ID in the DB
                _housesInFlight.update { inFlights ->
                    if (inFlights.isEmpty()) return@update inFlights
                    inFlights.filter { inFlight ->
                        // Match by natural identity + content against the RAW database records.
                        // ADDITION: Check for Identity (address signature) match to handle ID-less DB records.
                        !dbHouses.any { db -> 
                            (db.listOrder == inFlight.listOrder && db.data == inFlight.data &&
                             db.address.blockNumber == inFlight.address.blockNumber &&
                             db.address.streetName == inFlight.address.streetName &&
                             db.address.number == inFlight.address.number &&
                             db.address.sequence == inFlight.address.sequence &&
                             db.address.complement == inFlight.address.complement) ||
                            (db.generateIdentityKey() == inFlight.generateIdentityKey())
                        }
                    }
                }
            }
        }

        // --- MAIN PRODUCTION STATE REDUCER ---
        // Optimized to only include fields essential for the Production Screen.
        // Heavy reporting (RG/Weekly/Boletim) is moved to independent flows below.
        combine(
            latestHouses, _data, _agentName, _searchQuery, _isSupervisor, 
            _municipio, _bairro, _categoria, _zona, _ciclo, _tipo, _atividade,
            _currentWeekStart, _currentBlock, _currentBlockSequence, _currentStreet,
            bairrosList, _syncStatus, isDayClosed, isWorkdayManualUnlock,
            _backupConfirmation, _isAdmin, _recentlyEditedHouseIds, _highlightedHouseId,
            easyMode, solarMode, editingToolsMode,
            maxOpenHouses
        ) { args ->
            try {
                val h = args[0] as List<House>
                val d = args[1] as String
                val name = args[2] as String
                val q = args[3] as String
                val supervisor = args[4] as Boolean
                val recentlyEdited = args[22] as Map<Int, Long>
                val highlightedId = args[23] as Int?
                
                val dayHouses = h.filter { it.data == d }
                
                // Optimization: Pre-calculate duplicate counts for O(N) detection
                val identityCounts = mutableMapOf<String, Int>()
                val duplicates = mutableSetOf<Int>()
                
                dayHouses.forEach { hh ->
                    val key = generateHouseKey(hh)
                    identityCounts[key] = (identityCounts[key] ?: 0) + 1
                }
                
                dayHouses.forEach { hh ->
                    val key = generateHouseKey(hh)
                    if ((identityCounts[key] ?: 0) > 1) duplicates.add(hh.id)
                }

                val normalizedQ = (q ?: "").normalize()
                val mappedDayHouses = dayHouses.filter { 
                    (it.address.streetName ?: "").formatStreetName().contains(normalizedQ.formatStreetName(), true) || 
                    (it.address.number ?: "").contains(q ?: "", true) 
                }.map { house ->
                    val key = generateHouseKey(house)
                    val isDuplicate = (identityCounts[key] ?: 0) > 1
                    val isRecentlyEdited = recentlyEdited.containsKey(house.id)
                    
                    HouseUiStateMapper.map(
                        house = house,
                        houseValidationUseCase = houseValidationUseCase,
                        isDuplicate = isDuplicate,
                        isRecentlyEdited = isRecentlyEdited,
                        isHighlighted = house.id == highlightedId
                    )
                }
                
                val totals = calculateDashboardTotals(dayHouses)
                val errorsCount = dayHouses.count { !houseValidationUseCase.isHouseValid(it, strict = true) }

                _uiState.update { current ->
                    current.copy(
                        houses = mappedDayHouses,
                        dashboardTotals = totals,
                        data = d,
                        agentName = name,
                        searchQuery = q,
                        isSupervisor = supervisor,
                        isAdmin = args[21] as Boolean,
                        municipality = args[5] as String,
                        neighborhood = args[6] as String,
                        category = args[7] as String,
                        zone = args[8] as String,
                        cycle = args[9] as String,
                        type = args[10] as Int,
                        activity = args[11] as Int,
                        bairrosList = args[16] as List<String>,
                        currentBlock = args[13] as String,
                        currentBlockSequence = args[14] as String,
                        currentStreet = args[15] as String,
                        syncStatus = args[17] as SyncUiState,
                        isDayClosed = args[18] as Boolean,
                        isManualUnlock = args[19] as Boolean,
                        backupConfirmation = args[20] as BackupConfirmation?,
                        isDuplicateIds = duplicates,
                        pendingCount = totals.worked,
                        strictPendingCount = errorsCount,
                        isEasyMode = args[24] as Boolean,
                        isSolarMode = args[25] as Boolean,
                        isEditingToolsEnabled = args[26] as Boolean,
                        maxOpenHouses = args[27] as Int,
                        highlightedHouseId = highlightedId
                    )
                }
            } catch (e: Exception) {
                // Defensive fallback: Ensure the date is at least updated so 'SELECIONAR' disappears
                val fallbackDate = args[1] as? String ?: ""
                val fallbackName = args[2] as? String ?: ""
                _uiState.update { it.copy(data = fallbackDate, agentName = fallbackName) }
            }
        }
        .flowOn(Dispatchers.Default)
        .launchIn(viewModelScope)
    }

    fun refreshConfig() {
        viewModelScope.launch {
            loadDynamicConfig()
        }
    }

    private suspend fun loadDynamicConfig() {
        withTimeoutOrNull(5000) {
            // 1. Sync Bairros
            val bairrosResult = localizationRepository.fetchBairros()
            if (bairrosResult.isSuccess) {
                _bairrosList.value = bairrosResult.getOrNull() ?: com.antigravity.healthagent.utils.AppConstants.BAIRROS
            }

            // 2. Sync Global System Settings
            val settingsResult = syncRepository.fetchSystemSettings()
            if (settingsResult.isSuccess) {
                val settings = settingsResult.getOrNull() ?: emptyMap()
                
                // Meta Diária
                settings["max_open_houses"]?.let { raw ->
                    val intVal = when(raw) {
                        is Long -> raw.toInt()
                        is Int -> raw
                        is Number -> raw.toInt()
                        is String -> raw.toIntOrNull() ?: 25
                        else -> 25
                    }
                    settingsManager.setMaxOpenHouses(intVal)
                }

                // Global Custom Activities
                settings["custom_activities"]?.let { raw ->
                    val setVal = when(raw) {
                        is List<*> -> raw.mapNotNull { it?.toString() }.toSet()
                        is String -> raw.split(",").filter { it.isNotBlank() }.toSet()
                        else -> emptySet()
                    }
                    if (setVal.isNotEmpty()) {
                        settingsManager.setCustomActivities(setVal)
                    }
                }

                // Default Easy Mode (only for new users or if not set)
                settings["default_easy_mode"]?.let { raw ->
                    val boolVal = when(raw) {
                        is Boolean -> raw
                        is String -> raw.toBoolean()
                        else -> false
                    }
                    // If the user hasn't explicitly set easy mode yet, we could apply this.
                    // For now, let's just ensure it's available if needed.
                }
            }
        }
    }

    // --- Computed State ---

    val filteredHouses: StateFlow<List<House>> = combine(houses, _pendingUpdateDrafts, _searchQuery, _data) { dbList, drafts, query, date ->
        val mergedList = dbList.map { house -> drafts[house.id] ?: house }
        mergedList.filter { it.data == date && (query.isBlank() || it.address.streetName.contains(query, ignoreCase = true) || it.address.blockNumber.contains(query, ignoreCase = true)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredHousesUiState: StateFlow<List<HouseUiState>> = combine(filteredHouses, _isDuplicateIds, recentlyEditedHouseIds, _currentUserUid) { list, dups, editing, myUid ->
        list.map { 
            val isMine = it.agentUid.isBlank() || it.agentUid == myUid
            HouseUiStateMapper.map(it, houseValidationUseCase, dups.contains(it.id), editing.contains(it.id), isMine = isMine) 
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingHousesCount: StateFlow<Int> = filteredHouses.map { list ->
        // Pending are those that were visited but NOT successfully worked (excluding Vacant which is a final state)
        list.count { it.situation == Situation.F || it.situation == Situation.REC || it.situation == Situation.A }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val strictPendingHousesCount: StateFlow<Int> = filteredHouses.map { list ->
        list.count { !houseValidationUseCase.isHouseValid(it, strict = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun getInvalidFields(house: House): Set<String> {
        return houseValidationUseCase.getInvalidFields(house, strict = true).toSet()
    }


    val daysWithErrors: StateFlow<List<DayErrorSummary>> = houses.map { all ->
        try {
            all.groupBy { it.data }
                .mapNotNull { (date, h) -> 
                    val validationResult = houseValidationUseCase.validateCurrentDay(date, h, strict = true)
                    if (!validationResult.isValid) {
                        val errorCount = h.count { !houseValidationUseCase.isHouseValid(it, strict = true) }
                        if (errorCount > 0) DayErrorSummary(date, errorCount) else null
                    } else null
                }
                .sortedByDescending { getTimestamp(it.date) }
        } catch (e: Exception) {
            emptyList()
        }
    }.flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val streetSuggestions: StateFlow<List<String>> = combine(_bairro, _agentName, _remoteAgentUid, _currentUserUid) { currentB, name, remoteUid, currentUid ->
        val uid = remoteUid ?: currentUid
        streetRepository.getStreetSuggestions(currentB, name, uid ?: "")
    }.flatMapLatest { it }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- Actions ---
    fun validateCurrentDay(showDialog: Boolean, strict: Boolean = true): Boolean {
        // Ensure we validate using the merged state (DB + Drafts)
        val latestHouses = houses.value.map { _pendingUpdateDrafts.value[it.id] ?: it }
        val result = houseValidationUseCase.validateCurrentDay(_data.value, latestHouses, strict = strict)
        _validationErrorHouseIds.value = result.errorHouseIds
        _validationErrorDetails.value = result.errorDetails
        _isDuplicateIds.value = result.errorDetails.filter { it.isDuplicate }.map { it.houseId }.toSet()
        
        if (!result.isValid) {
            if (showDialog) {
                // Trigger the full ValidationErrorsDialog with details
                _integrityDialogMessage.value = result.dialogMessage
                soundManager.playWarning()
            }
            return false
        } else {
            _integrityDialogMessage.value = null
            return true
        }
    }

    fun triggerDelayedValidation(delayMs: Long = 3000) {
        validationJob?.cancel()
        validationJob = viewModelScope.launch {
            delay(delayMs)
            validateCurrentDay(showDialog = false)
        }
    }

    fun onHouseClick(houseId: Int) {
        _scrollToHouseId.value = houseId
        _integrityDialogMessage.value = null // Close old dialog if any
        // Reset scroll id after brief delay to allow re-triggering
        viewModelScope.launch {
            delay(500)
            _scrollToHouseId.value = null
        }
    }

    init {
        // Defer non-critical background work to prioritize UI rendering
        viewModelScope.launch(Dispatchers.IO) {
            delay(1000) // Small delay to let initial UI state flows settle
            try {
                performLocalDatabaseMigrationUseCase.migrateStreetNamesToFormat()
                performLocalDatabaseMigrationUseCase.migrateBairrosToUppercase()
                performLocalDatabaseMigrationUseCase.migrateDateFormats()
                repository.normalizeLocalDates()
                syncRepository.performDataCleanup()
            } catch (ex: Exception) {
                android.util.Log.e("HomeViewModel", "Error migrating data", ex)
            }
            
            // 1. Fetch Agent Names (with 3s timeout)
            val agentNamesResult = withTimeoutOrNull(3000) { agentRepository.fetchAgentNames() }
            if (agentNamesResult != null && agentNamesResult.isSuccess) {
                _agentNames.value = agentNamesResult.getOrNull() ?: com.antigravity.healthagent.utils.AppConstants.AGENT_NAMES
            } else if (agentNamesResult == null) {
                // Timeout happened, use default
                _agentNames.value = com.antigravity.healthagent.utils.AppConstants.AGENT_NAMES
                android.util.Log.w("HomeViewModel", "Agent names fetch timed out, using defaults")
            }

            // 2. Fetch Bairros (with 3s timeout)
            val bairrosResult = withTimeoutOrNull(3000) { localizationRepository.fetchBairros() }
            if (bairrosResult != null && bairrosResult.isSuccess) {
                _bairrosList.value = bairrosResult.getOrNull() ?: com.antigravity.healthagent.utils.AppConstants.BAIRROS
            } else if (bairrosResult == null) {
                // Timeout happened, use default
                _bairrosList.value = com.antigravity.healthagent.utils.AppConstants.BAIRROS
                android.util.Log.w("HomeViewModel", "Bairros fetch timed out, using defaults")
            }
            
            loadDynamicConfig()
        }

        // Check for existing multi-day errors on startup
        viewModelScope.launch {
            daysWithErrors.filter { it.isNotEmpty() }.first()
            _showMultiDayErrorDialog.value = true
        }

        // Observe bairro changes for automatic tipo selection
        viewModelScope.launch {
            _bairro.collect { b ->
                if (b.equals("Centro", ignoreCase = true)) {
                    _tipo.value = 1
                } else {
                    _tipo.value = 2
                }
            }
        }

        // Observe date changes to update ciclo
        viewModelScope.launch {
            _data.collect { d ->
                _ciclo.value = calculateCicloForDate(d)
            }
        }

        // Set initial date to the last work day (most recent day with houses) or generate tutorial data
        viewModelScope.launch {
            try {
                // Check directly from repo to avoid StateFlow initial value race conditions
                // Try from current values (might be empty) then from cachedUser
                var name: String = _agentName.value
                var uid: String = _remoteAgentUid.value ?: _currentUserUid.value ?: ""
                
                if (name.isBlank()) {
                    val cached = settingsManager.cachedUser.first()
                    name = cached?.agentName ?: ""
                    uid = cached?.uid ?: ""
                }

                val allHouses = withContext(Dispatchers.IO) {
                    repository.getAllHousesOnce(uid)
                }
                
                if (allHouses.isNotEmpty()) {
                    val todayStr = dateFormatter.format(Date())
                    val hasTodayHouses = allHouses.any { it.data == todayStr }
                    
                    if (hasTodayHouses) {
                        _data.value = todayStr
                    } else {
                        // Prefer most recent work day if today is empty
                        // BUG FIX: Parse dates before comparing to avoid lexicographical errors with DD-MM-YYYY
                        val lastDate = allHouses.mapNotNull { 
                            try { dateFormatter.parse(it.data) } catch (e: Exception) { null } 
                        }.maxOrNull()?.let { dateFormatter.format(it) } ?: todayStr
                        _data.value = lastDate
                    }
                } else {
                    // Default to today if no houses found
                    _data.value = dateFormatter.format(Date())
                }

                // BUG FIX: Trigger local data migration on cold start to claim any newly synchronized or legacy orphan data
                if (name.isNotBlank() && uid.isNotBlank()) {
                    val currentUser = settingsManager.cachedUser.first()
                    val email = currentUser?.email ?: ""
                    repository.migrateLocalData(name, email, uid, isCurrentAgent = true)
                    
                    // RECONCILIATION: If we have a proper name but records are still using email, fix them.
                    if (name.isNotBlank() && !name.contains("@")) {
                        repository.fixEmailNamesForUid(uid, name)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "Error in initial date fetch", e)
                _data.value = dateFormatter.format(Date())
            }
        }

        // Observe date changes and inherit values from the most recent house
        viewModelScope.launch {
            combine(_data, houses) { date, allHouses ->
                val dayHouses = allHouses.filter { it.data == date }
                if (dayHouses.isNotEmpty()) {
                    dayHouses.maxByOrNull { it.listOrder }
                } else {
                    // Smart Inheritance: If current day is empty, find the absolute last worked house
                    allHouses.maxByOrNull { 
                        try {
                            dateFormatter.parse(it.data)?.time ?: 0L
                        } catch (e: Exception) { 0L }
                    }
                }
            }.collectLatest { lastHouse ->
                if (lastHouse != null) {
                    // --- CONTEXT GUARD ---
                    // Don't auto-update context from a draft that has an identity conflict (Red Draft).
                    // This prevents "flickering" or incorrect context inheritance while user is resolving duplicates.
                    val isClashingDraft = _pendingUpdateDrafts.value.containsKey(lastHouse.id) && 
                        _isDuplicateIds.value.contains(lastHouse.id)
                    
                    if (!isClashingDraft) {
                        _bairro.value = lastHouse.address.bairro
                        _currentBlock.value = lastHouse.address.blockNumber
                        _currentBlockSequence.value = lastHouse.address.blockSequence
                        _currentStreet.value = lastHouse.address.streetName
                        _municipio.value = lastHouse.context.municipio
                        
                        // ONLY update agentName if it's currently empty, to avoid loops
                        if (_agentName.value.isBlank()) {
                            _agentName.value = lastHouse.agentName
                        }
                    }
                }
                // Clear validation highlights when switching days
                _validationErrorHouseIds.value = emptySet()
            }
        }


        // 1. Initialize from Cache for immediate offline support
        viewModelScope.launch {
            settingsManager.cachedUser.first()?.let { cached ->
                if (_agentName.value.isBlank()) {
                    _agentName.value = cached.agentName ?: ""
                }
                if ((_currentUserUid.value ?: "").isBlank()) {
                    _currentUserUid.value = cached.uid
                }
            }
        }

        // 2. Keep agentName and currentUserUid synced with AuthUser or RemoteAgent
        viewModelScope.launch {
            combine(settingsManager.cachedUser, _remoteAgent) { user, remote ->
                val name = remote ?: user?.standardName ?: "AGENTE"
                Triple(name, user, remote)
            }.collect { (name, user, remote) ->
                val current = _agentName.value
                val newIsEmail = name.contains("@")
                val currentIsEmail = current.contains("@")
                
                val shouldUpdate = when {
                    name.isBlank() -> true
                    current.isBlank() -> true
                    !newIsEmail -> name != current 
                    newIsEmail && currentIsEmail -> name != current
                    else -> false // Don't overwrite proper name with email
                }
                
                if (shouldUpdate) {
                    _agentName.value = name
                }
                
                // Keep currentUserUid updated and trigger migration ONLY for the actual authenticated user
                user?.uid?.let { uid ->
                    val uidChanged = _currentUserUid.value != uid
                    if (uidChanged) {
                        _currentUserUid.value = uid
                    }
                    
                    // Only trigger migration if it's the LOGGED-IN user's identity being updated
                    // We check if remote is null to be sure we are not in Admin view mode
                    if (remote == null && (uidChanged || shouldUpdate)) {
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                repository.migrateLocalData(name, user.email ?: "", uid, isCurrentAgent = true)
                            } catch (e: Exception) {
                                android.util.Log.e("HomeViewModel", "Error migrating UID", e)
                            }
                        }
                    }
                }
            }
        }
    }



    fun selectAppMode(enableEasyMode: Boolean) {
        viewModelScope.launch {
            settingsManager.setEasyMode(enableEasyMode)
        }
    }



    fun toggleDayLock() {
        viewModelScope.launch {
            try {
                // RULE: Regular Supervisors cannot toggle locks REMOTELY (for other agents).
                // But they CAN toggle locks for THEIR OWN production.
                val isViewingRemoteAgent = _remoteAgent.value != null
                if (isViewingRemoteAgent && _isSupervisor.value && !_isAdmin.value) {
                    _uiEvent.value = "Apenas administradores podem gerenciar travas remotamente."
                    soundManager.playWarning()
                    return@launch
                }

                val closed = isDayClosed.value
                val isAdmin = _isAdmin.value
                val effectiveUid = _remoteAgentUid.value ?: _currentUserUid.value
                if (closed) {
                    if (dayManagementUseCase.canSafelyUnlock(_data.value, effectiveUid, isAdmin)) {
                        dayManagementUseCase.unlockDay(_data.value, effectiveUid, isAdmin)
                    } else {
                        _showHistoryUnlockConfirmation.value = true
                    }
                } else {
                    // Toggling locker when OPEN allows manual override of meta-limits
                    val manualUnlock = isWorkdayManualUnlock.value
                    val currentAgent = _agentName.value
                    val currentData = _data.value
                    
                    val activity = dayManagementUseCase.getDayActivity(currentData, effectiveUid)
                        ?: com.antigravity.healthagent.data.local.model.DayActivity(date = currentData, agentName = currentAgent, agentUid = effectiveUid ?: "")
                    
                    repository.updateDayActivity(activity.copy(isManualUnlock = !manualUnlock), isAdmin)
                    
                    if (!manualUnlock) {
                        _uiEvent.value = "Edição extra habilitada para este dia."
                    } else {
                        _uiEvent.value = "Edição extra desabilitada."
                    }
                }
            } catch (e: Exception) {
                _uiEvent.value = "Erro ao alterar estado do dia: ${e.message}"
                soundManager.playWarning()
            }
        }
    }

    fun deduplicateCurrentDay() {
        viewModelScope.launch {
            if (!_isAdmin.value) {
                _uiEvent.value = "Apenas administradores podem executar deduplicação."
                soundManager.playWarning()
                return@launch
            }
            
            _isSyncing.value = true
            _uiEvent.value = "Iniciando deduplicação..."
            
            try {
                val currentAgent = _agentName.value
                val currentUid = _remoteAgentUid.value ?: _currentUserUid.value
                
                if (currentAgent.isNotBlank() && (currentUid ?: "").isNotBlank()) {
                    repository.deduplicateAgentData(currentUid ?: "")
                    
                    // If we are inspecting, also do a cross-identity surgical clean
                    if (_remoteAgentUid.value != null) {
                        repository.cleanMisattributedData(currentUid ?: "", _currentUserUid.value ?: "")
                    }
                    
                    _uiEvent.value = "Deduplicação concluída. Imóveis conflitantes removidos."
                    soundManager.playSuccess()
                } else {
                    _uiEvent.value = "Erro: Identidade do agente não localizada."
                }
            } catch (e: Exception) {
                _uiEvent.value = "Erro na deduplicação: ${e.message}"
                soundManager.playWarning()
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun startDayClosingFlow() {
        viewModelScope.launch {
            // CRITICAL: DRAFT CHECK - Defer duplicate confirmation to this point
            if (_pendingUpdateDrafts.value.isNotEmpty()) {
                val firstClash = _pendingUpdateDrafts.value.values.first()
                _duplicateHouseConfirmation.value = firstClash
                _uiEvent.value = "Resolva os conflitos (em vermelho) antes de fechar o dia."
                soundManager.playWarning()
                return@launch
            }

            val workedCount = houses.value.count { it.data == _data.value && (it.situation == Situation.NONE || it.situation == Situation.EMPTY) }
            val todayStr = dateFormatter.format(java.util.Date())
            val isToday = _data.value == todayStr
            
            if (isToday && workedCount < maxOpenHouses.value) {
                _uiEvent.value = "Meta de Abertos não atingida! (Abertos: $workedCount / ${maxOpenHouses.value})"
                return@launch
            }

            if (validateCurrentDay(showDialog = true)) {
                val summary = calculateAuditSummary(_data.value)
                _showClosingAudit.value = summary
            }
        }
    }

    fun syncDataToCloud() {
        if (_pendingUpdateDrafts.value.isNotEmpty()) {
            _uiEvent.value = "Resolva os conflitos (em vermelho) antes de sincronizar!"
            soundManager.playWarning()
            return
        }
        
        viewModelScope.launch {
            if (_syncStatus.value !is SyncUiState.Idle) return@launch
            val uid = _remoteAgentUid.value ?: _currentUserUid.value ?: return@launch
            
            _syncStatus.update { SyncUiState.Syncing(progress = 0.1f, message = "Iniciando sincronização...", isDownloading = false) }
            
            val targetUid = _remoteAgentUid.value
            withContext(Dispatchers.IO) {
                try {
                    // 1. Pull cloud data to local FIRST (Applies Admin Authority exclusions)
                    val pullResult = syncRepository.pullCloudDataToLocal(uid)
                    if (pullResult.isSuccess) {
                        _syncStatus.update { SyncUiState.Syncing(progress = 0.3f, message = "Baixando dados atualizados...", isDownloading = true) }
                        
                        // 2. Push local data to cloud
                        _syncStatus.update { SyncUiState.Syncing(progress = 0.6f, message = "Enviando dados para a nuvem...", isDownloading = false) }
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
                            _syncStatus.update { SyncUiState.Success(System.currentTimeMillis()) }
                        } else {
                            _syncStatus.update { SyncUiState.Error("Erro ao enviar: ${pushResult.exceptionOrNull()?.message}") }
                        }
                    } else {
                        _syncStatus.update { SyncUiState.Error("Falha ao baixar: ${pullResult.exceptionOrNull()?.message}") }
                    }
                } catch (e: Exception) {
                    _syncStatus.update { SyncUiState.Error("Erro: ${e.message}") }
                } finally {
                    delay(1500) // Stabilize Success/Error message visibility
                    _syncStatus.update { SyncUiState.Idle(_syncStatus.value.lastSyncTime) }
                }
            }
        }
    }
    fun pullDataFromCloud(targetUid: String? = null) {
        viewModelScope.launch {
            if (_syncStatus.value !is SyncUiState.Idle) return@launch
            val uid = targetUid ?: _currentUserUid.value ?: return@launch
            
            _syncStatus.update { SyncUiState.Syncing(progress = 0.1f, message = "Iniciando download...", isDownloading = true) }
            
            withContext(Dispatchers.IO) {
                try {
                    _syncStatus.update { SyncUiState.Syncing(progress = 0.5f, message = "Baixando dados da nuvem...", isDownloading = true) }
                    val result = syncRepository.pullCloudDataToLocal(uid)
                    if (result.isSuccess) {
                        // SURGICAL CLEANUP: Auto-remove empty/broken houses after download
                        if (uid.isNotBlank()) {
                            cleanupBrokenHousesUseCase(uid)
                        }

                        settingsManager.setLastSyncTimestamp(System.currentTimeMillis())
                        soundManager.vibrateSuccess()
                        _syncStatus.update { SyncUiState.Success(System.currentTimeMillis()) }
                        _uiEvent.value = "Dados baixados com sucesso."
                    } else {
                        _syncStatus.update { SyncUiState.Error("Falha ao baixar: ${result.exceptionOrNull()?.message}") }
                        _uiEvent.value = "Falha ao baixar dados: ${result.exceptionOrNull()?.message}"
                    }
                } catch (e: Exception) {
                    _syncStatus.update { SyncUiState.Error("Erro: ${e.message}") }
                    _uiEvent.value = "Erro ao baixar: ${e.message}"
                } finally {
                    delay(1500)
                    _syncStatus.update { SyncUiState.Idle(_syncStatus.value.lastSyncTime) }
                }
            }
        }
    }

    private fun calculateAuditSummary(date: String): AuditSummary {
        val dayHouses = houses.value.filter { it.data == date }
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

    fun confirmAndCloseDay(audit: AuditSummary) {
        viewModelScope.launch {
            try {
                val isAdmin = _isAdmin.value
                val effectiveUid = _remoteAgentUid.value ?: _currentUserUid.value
                dayManagementUseCase.closeDay(audit.date, effectiveUid, isAdmin)
                _showClosingAudit.value = null
                if (audit.totalWorked >= maxOpenHouses.value && maxOpenHouses.value > 0) {
                    _showGoalReached.value = true
                }
                
                // Trigger immediate background sync
                val context = com.antigravity.healthagent.context.getContext() // Assuming this helper exists or use DI
                triggerImmediateSync()
            } catch (e: Exception) {
                _uiEvent.value = "Erro ao fechar o dia: ${e.message}"
                soundManager.playWarning()
            }
        }
    }

    fun advanceToNextDay() {
        viewModelScope.launch {
            try {
                val next = dayManagementUseCase.getNextBusinessDay(_data.value, _remoteAgentUid.value ?: _currentUserUid.value)
                if (next.isNotBlank()) {
                    _data.value = next
                    soundManager.playPop()
                    _showGoalReached.value = false
                }
            } catch (e: Exception) {
                _uiEvent.value = "Erro ao avançar dia: ${e.message}"
            }
        }
    }

    fun moveDateBackward() {
        viewModelScope.launch {
            try {
                val calendar = Calendar.getInstance()
                // Use safe parsing
                val date = try { dateFormatter.parse(_data.value) } catch (e: Exception) { null }
                calendar.time = date ?: Date()
                
                do {
                    calendar.add(Calendar.DAY_OF_YEAR, -1)
                } while (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY)
                _data.value = dateFormatter.format(calendar.time)
                soundManager.playPop()
            } catch (e: Exception) {
                _uiEvent.value = "Erro ao navegar: ${e.message}"
            }
        }
    }

    fun moveDateForward() {
        viewModelScope.launch {
            try {
                val calendar = Calendar.getInstance()
                 // Use safe parsing
                val date = try { dateFormatter.parse(_data.value) } catch (e: Exception) { null }
                calendar.time = date ?: Date()
                
                do {
                    calendar.add(Calendar.DAY_OF_YEAR, 1)
                } while (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY)
                _data.value = dateFormatter.format(calendar.time)
                soundManager.playPop()
            } catch (e: Exception) {
                 _uiEvent.value = "Erro ao navegar: ${e.message}"
            }
        }
    }

    private var isAddingHouse = false
    private var lastAddClickTime = 0L

    fun addNewHouseAt(afterId: Int) {
        // ROLE ENFORCEMENT
        if (_isSupervisor.value && !_isAdmin.value) {
            _uiEvent.value = "Apenas administradores podem adicionar dados remotamente."
            soundManager.playWarning()
            return
        }

        val currentTime = System.currentTimeMillis()
        if (isAddingHouse || currentTime - lastAddClickTime < 300) return
        
        isAddingHouse = true
        lastAddClickTime = currentTime

        if (_agentName.value.isBlank()) {
            _uiEvent.value = "Aguardando carregamento do perfil..."
            return
        }

        viewModelScope.launch {
            try {
                val isUnlocked = isWorkdayManualUnlock.value
                val isAdmin = _isAdmin.value
                if (uiState.value.isDayClosed && !isUnlocked && !isAdmin) {
                    _uiEvent.value = "Este dia está FECHADO."
                    soundManager.playWarning()
                    isAddingHouse = false
                    return@launch
                }

                val currentDayHouses = houses.value.filter { it.data == _data.value }
                val targetIndex = if (afterId == -1) -1 else currentDayHouses.indexOfFirst { it.id == afterId }
                
                // 1. Prepare Template for inheritance
                val template = if (targetIndex != -1) currentDayHouses[targetIndex] else currentDayHouses.firstOrNull()
                
                // 2. Predict next values based on template
                val prediction = if (template != null) {
                    predictHouseValuesUseCase.predictBasedOnHistory(houses.value, template)
                } else {
                    PredictHouseValuesUseCase.HousePrediction("", 0, 0, com.antigravity.healthagent.data.local.model.PropertyType.R, Situation.NONE)
                }

                var houseToInsert = House(
                    context = DailyContext(municipio = _municipio.value),
                    address = VisitAddress(
                        blockNumber = template?.address?.blockNumber ?: _currentBlock.value,
                        blockSequence = template?.address?.blockSequence ?: _currentBlockSequence.value,
                        streetName = template?.address?.streetName ?: _currentStreet.value,
                        number = prediction.number,
                        sequence = prediction.sequence,
                        complement = prediction.complement,
                        bairro = _bairro.value.uppercase()
                    ),
                    propertyType = prediction.propertyType,
                    situation = prediction.situation,
                    data = _data.value,
                    agentName = _agentName.value.uppercase(),
                    agentUid = _remoteAgentUid.value ?: _currentUserUid.value ?: "",
                    listOrder = 0 // Will be set by recalculation
                )

                // 2.1 Clash Detection (similar to addNewHouse)
                var finalSequence = houseToInsert.address.sequence
                var finalComplement = houseToInsert.address.complement
                
                // We use latestHouses (including drafts) for safe incrementing
                val allLatest = houses.value 
                
                while (allLatest.any { 
                    it.data == houseToInsert.data && 
                    it.agentUid == houseToInsert.agentUid &&
                    it.agentName.equals(houseToInsert.agentName, ignoreCase = true) &&
                    it.address.blockNumber.stringNormalize() == houseToInsert.address.blockNumber.stringNormalize() &&
                    it.address.blockSequence.stringNormalize() == houseToInsert.address.blockSequence.stringNormalize() &&
                    it.address.streetName.formatStreetName() == houseToInsert.address.streetName.formatStreetName() &&
                    it.address.number.stringNormalize() == houseToInsert.address.number.stringNormalize() && 
                    it.address.sequence == finalSequence &&
                    it.address.complement == finalComplement &&
                    it.address.bairro.stringNormalize() == houseToInsert.address.bairro.stringNormalize()
                }) {
                    if (houseToInsert.address.complement > 0 || houseToInsert.address.number.isNotBlank()) {
                        finalComplement++
                    } else {
                        finalSequence++
                    }
                }
                
                if (finalSequence != houseToInsert.address.sequence || finalComplement != houseToInsert.address.complement) {
                    houseToInsert = houseToInsert.copy(address = houseToInsert.address.copy(sequence = finalSequence, complement = finalComplement))
                }

                // 3. Insert and Reorder
                val mutableList = currentDayHouses.toMutableList()
                if (targetIndex == -1) {
                    mutableList.add(0, houseToInsert)
                } else {
                    mutableList.add(targetIndex + 1, houseToInsert)
                }

                val updatedList = mutableList.mapIndexed { index, h -> h.copy(listOrder = index.toLong()) }
                val recalculated = recalculateVisitSegmentsUseCase.recalculateVisitSegments(updatedList)
                
                // For new house (id 0), we use insertHouse, for others updateHouses
                val newlyAdded = recalculated.find { it.id == 0 }
                val others = recalculated.filter { it.id != 0 }
                
                if (others.isNotEmpty()) {
                    repository.updateHouses(others, isAdmin)
                }
                if (newlyAdded != null) {
                    val newId = repository.insertHouse(newlyAdded, isAdmin)
                    // Trigger highlight for 2 seconds instead of scrolling
                    _highlightedHouseId.value = newId.toInt()
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(2000)
                        if (_highlightedHouseId.value == newId.toInt()) {
                            _highlightedHouseId.value = null
                        }
                    }
                }

                soundManager.playPop()
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "Error adding house at position", e)
                _uiEvent.value = "Erro ao inserir: ${e.message}"
                soundManager.playWarning()
            } finally {
                isAddingHouse = false
            }
        }
    }


    fun addNewHouse() {
        // ROLE ENFORCEMENT
        if (_isSupervisor.value && !_isAdmin.value) {
            _uiEvent.value = "Apenas administradores podem adicionar dados remotamente."
            soundManager.playWarning()
            return
        }

        val currentTime = System.currentTimeMillis()
        if (isAddingHouse || currentTime - lastAddClickTime < 300) return
        
        isAddingHouse = true
        lastAddClickTime = currentTime

        if (_agentName.value.isBlank()) {
            _uiEvent.value = "Aguardando carregamento do perfil..."
            return
        }

        // Immediately mark '0' as recently edited to suppress error labels 
        // for the in-flight card while it's being inserted.
        markAsRecentlyEdited(0)

        viewModelScope.launch {
            try {
                // LOCK ENFORCEMENT: Block ANY additions to closed days unless Admin or Manual Override (isUnlocked)
                val isUnlocked = isWorkdayManualUnlock.value
                val isAdmin = _isAdmin.value
                
                if (uiState.value.isDayClosed && !isUnlocked && !isAdmin) {
                    _uiEvent.value = "Este dia de trabalho está FECHADO e não permite edições."
                    soundManager.playWarning()
                    isAddingHouse = false
                    return@launch
                }

                // Pre-check for open limit inside the safe scope
                val currentTotal = houses.value.count { it.data == _data.value }
                val safetyLimit = (maxOpenHouses.value * 3).coerceAtLeast(150)
                
                if (currentTotal >= safetyLimit && !isUnlocked && !isAdmin) {

                    soundManager.playWarning()
                    _situationLimitConfirmation.value = House(data = _data.value)
                    isAddingHouse = false
                    return@launch
                }

                // CRITICAL: Synchronously merge DB + Drafts + InFlights to ensure 
                // the prediction uses the absolute latest typing, even if the Flow hasn't emitted yet.
                val dbHouses = allHousesFlow.value
                val drafts = _pendingUpdateDrafts.value
                val inFlights = _housesInFlight.value
                
                val latestHouses: List<House> = (dbHouses.map { drafts[it.id] ?: it } + inFlights)
                val isDayEmpty = latestHouses.none { it.data == _data.value }
                var prediction: PredictHouseValuesUseCase.HousePrediction
                var initialBlock = _currentBlock.value
                var initialStreet = _currentStreet.value
                var initialBlockSeq = _currentBlockSequence.value
                
                if (isDayEmpty) {
                    // Find the absolute last house globally (presumably from previous workdays)
                    val lastGlobalHouse = latestHouses.maxByOrNull { it.listOrder }
                    
                    if (lastGlobalHouse != null) {
                        // Inherit Context
                        initialBlock = lastGlobalHouse.address.blockNumber
                        initialStreet = lastGlobalHouse.address.streetName
                        initialBlockSeq = lastGlobalHouse.address.blockSequence
                        
                        // Update UI State Context
                        _currentBlock.value = initialBlock
                        _currentStreet.value = initialStreet
                        _currentBlockSequence.value = initialBlockSeq
                        _bairro.value = lastGlobalHouse.address.bairro
                        _municipio.value = lastGlobalHouse.context.municipio
                        _agentName.value = lastGlobalHouse.agentName
                        
                        // Predict based on history
                        prediction = predictHouseValuesUseCase.predictBasedOnHistory(latestHouses, lastGlobalHouse)
                    } else {
                        // No history at all, just blank prediction
                        prediction = PredictHouseValuesUseCase.HousePrediction("", 0, 0, com.antigravity.healthagent.data.local.model.PropertyType.EMPTY, Situation.NONE)
                    }
                } else {
                    // Standard intra-day prediction using merged state
                    // Sanitize context inputs to ensure they match accurately against sanitized DB/Draft records
                    prediction = predictHouseValuesUseCase.predictNextHouseValues(
                        latestHouses, 
                        _data.value,
                        _currentBlock.value.trim().uppercase(),
                        _currentStreet.value.trim().formatStreetName()
                    )
                }

                val maxOrder = latestHouses.maxOfOrNull { it.listOrder } ?: 0L
                val currentDayHouses = latestHouses.filter { it.data == _data.value }.sortedBy { it.listOrder }
                val lastHouse = currentDayHouses.lastOrNull()
                val newStreet = initialStreet.trim().formatStreetName()

                // VisitSegment Stabilization: Align perfectly with RecalculateVisitSegmentsUseCase.recalculateVisitSegments
                // to prevent "shifting" which causes Natural Key collisions during bulk update.
                val dayHouses = currentDayHouses.sortedBy { it.listOrder }
                // --- ACTION BLOCKER ---
                // DEFERRED VALIDATION: Block new additions if there are unresolved conflicts in the current block/street.
                // CRITICAL: Perform a synchronous validation pass first to catch ANY "in-flight" or "un-debounced" typing 
                // that might have created a new conflict since the last auto-validation.
                validateCurrentDay(showDialog = false, strict = true) 
                
                val currentAgent = _agentName.value
                val currentUid = _remoteAgentUid.value ?: _currentUserUid.value
                val adminBypass = _isAdmin.value
                
                val clashingDraftIds = _isDuplicateIds.value
                val hasClashes = clashingDraftIds.isNotEmpty()
                
                if (hasClashes) {
                    _uiEvent.value = "Resolva os conflitos (em vermelho) antes de adicionar um novo imóvel."
                    soundManager.playWarning()
                    // Pull focus to the first clashing house
                    onHouseClick(clashingDraftIds.first())
                    isAddingHouse = false
                    return@launch
                }

                var predictedSegment = 0
                var lastStreetName = ""
                dayHouses.forEach { h ->
                    val s = h.address.streetName.trim().uppercase()
                    if (lastStreetName.isNotEmpty() && s != lastStreetName) {
                        predictedSegment++
                    }
                    lastStreetName = s
                }
                // If the new house starts a new street segment or continues the last one:
                if (lastStreetName.isNotEmpty() && newStreet.uppercase() != lastStreetName) {
                    predictedSegment++
                }

                val myUid = _currentUserUid.value
                val activeRemoteUid = _remoteAgentUid.value
                
                // CRITICAL IDENTITY GUARD: Ensure we have a valid UID before creating any record.
                if (myUid == null && activeRemoteUid == null) {
                    android.util.Log.e("HomeViewModel", "ADD HOUSE FAILED: No UID available.")
                    _uiEvent.emit("Erro: Identidade não carregada. Aguarde ou faça re-login.")
                    isAddingHouse = false
                    return@launch
                }
                
                val currentAgentUid = activeRemoteUid ?: myUid!!
                val currentAgentName = _agentName.value

                var houseToInsert = House(
                    id = 0,
                    address = VisitAddress(
                        blockNumber = initialBlock.trim().uppercase(),
                        blockSequence = initialBlockSeq.trim().uppercase(),
                        streetName = initialStreet.trim().formatStreetName(),
                        number = prediction.number.trim().uppercase(),
                        sequence = prediction.sequence,
                        complement = prediction.complement,
                        bairro = _bairro.value.trim().uppercase()
                    ),
                    propertyType = prediction.propertyType,
                    situation = prediction.situation,
                    context = DailyContext(tipo = _tipo.value, ciclo = _ciclo.value, municipio = _municipio.value.trim().uppercase()),
                    agentName = currentAgentName.trim().uppercase(),
                    agentUid = currentAgentUid,
                    data = _data.value,
                    visitSegment = predictedSegment,
                    listOrder = maxOrder + 1
                )

                // Exhaustive Clash Detection: Check ALL fields in the unique index to prevent REPLACE-deletions
                var finalSequence = houseToInsert.address.sequence
                var finalComplement = houseToInsert.address.complement
                
                // Smarter Auto-Increment: If numbers match, prefer incrementing COMPLEMENT
                // CRITICAL: We check against 'latestHouses' (DB + Drafts) here to ensure 
                // the new house skip-increments over even unsaved duplicates.
                while (latestHouses.any { 
                    it.data == houseToInsert.data && 
                    it.agentUid == houseToInsert.agentUid &&
                    it.agentName.equals(houseToInsert.agentName, ignoreCase = true) &&
                    it.address.blockNumber.equals(houseToInsert.address.blockNumber, ignoreCase = true) &&
                    it.address.blockSequence.equals(houseToInsert.address.blockSequence, ignoreCase = true) &&
                    it.address.streetName.equals(houseToInsert.address.streetName, ignoreCase = true) &&
                    it.address.number.equals(houseToInsert.address.number, ignoreCase = true) && 
                    it.address.sequence == finalSequence &&
                    it.address.complement == finalComplement &&
                    it.address.bairro.equals(houseToInsert.address.bairro, ignoreCase = true) &&
                    it.visitSegment == houseToInsert.visitSegment
                }) {
                    if (houseToInsert.address.complement > 0 || houseToInsert.address.number.isNotBlank()) {
                        finalComplement++
                    } else {
                        finalSequence++
                    }
                }
                
                if (finalSequence != houseToInsert.address.sequence || finalComplement != houseToInsert.address.complement) {
                    houseToInsert = houseToInsert.copy(address = houseToInsert.address.copy(sequence = finalSequence, complement = finalComplement))
                }

                // Add to In-Flight temporarily to prevent duplicate prediction in rapid-fire clicks
                _housesInFlight.update { it + houseToInsert }
                
                val newId = saveHouseUseCase.insertHouse(houseToInsert, latestHouses, adminBypass)
                
                // SURGICAL RACE CONDITION CHECK: 
                // If the user deleted the house (removed from in-flight) while we were saving,
                // we must immediately DELETE it from the DB to prevent it from "reappearing".
                val isStillInFlight = _housesInFlight.value.any { it.listOrder == houseToInsert.listOrder && it.data == houseToInsert.data }
                if (!isStillInFlight) {
                    saveHouseUseCase.deleteHouse(houseToInsert.copy(id = newId.toInt()), latestHouses, adminBypass)
                    return@launch
                }

                // CRITICAL BUG FIX (Race Condition): 
                // Any edits made to the 'in-flight' house card while 'insertHouse' was suspending (Saving)
                // must be synced back into the DB, otherwise they are lost when the in-flight list is cleared.
                // We RE-CALCULATE latestHouses here to include the physical DB content + any remaining in-flights.
                val dbHousesAfter = allHousesFlow.value
                val currentDrafts = _pendingUpdateDrafts.value
                val currentInFlights = _housesInFlight.value
                val refreshedLatestHouses = (dbHousesAfter.map { currentDrafts[it.id] ?: it } + currentInFlights)

                val finalInFlightState = _housesInFlight.value.find { 
                    it.listOrder == houseToInsert.listOrder && it.data == houseToInsert.data 
                }
                
                if (finalInFlightState != null && 
                    (finalInFlightState.address.number != houseToInsert.address.number || 
                     finalInFlightState.address.sequence != houseToInsert.address.sequence || 
                     finalInFlightState.address.complement != houseToInsert.address.complement)) {
                     
                     saveHouseUseCase.updateHouse(
                        finalInFlightState.copy(id = newId.toInt()), 
                        refreshedLatestHouses,
                        adminBypass
                    )
                }

                
                // Once inserted and synced, remove from In-Flight (Room Flow will pick it up)
                _housesInFlight.update { list -> 
                    list.filter { it.listOrder != houseToInsert.listOrder || it.data != houseToInsert.data } 
                }
                
                soundManager.playPop()
                
                // Silent window for the newly added house to allow the agent to fill it 
                // without premature error highlights or dialogs.
                markAsRecentlyEdited(newId.toInt())

                // Unified delayed validation (3s)
                triggerDelayedValidation()
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "Error adding new house", e)
                _uiEvent.value = "Erro ao adicionar imóvel: ${e.message}"
                soundManager.playWarning()
            } finally {
                delay(100) // Small buffer
                isAddingHouse = false
            }
        }
    }

    fun updateHouse(house: House) {
        // LOCK ENFORCEMENT
        val isUnlocked = isWorkdayManualUnlock.value
        val isAdmin = _isAdmin.value
        val isSupervisor = _isSupervisor.value

        // ROLE ENFORCEMENT: Non-admin supervisors cannot edit
        if (isSupervisor && !isAdmin) {
            return // Silent return as fields are usually disabled in UI
        }

        // Early lock check - If day is closed, we shouldn't even reach here from the UI usually (since fields are disabled)
        // But for safety, we return early WITHOUT a noisy Snackbar during active typing
        if (uiState.value.isDayClosed && !isUnlocked && !isAdmin && house.id != 0) {
            return
        }

        val original = houses.value.find { it.id == house.id }


        // Limit Enforcement: Only block if CHANGING to worked and meta was reached.
        val houseIsWorked = (house.situation == Situation.NONE || house.situation == Situation.EMPTY)
        val originalIsWorked = original != null && (original.situation == Situation.NONE || original.situation == Situation.EMPTY)

        if (houseIsWorked && !originalIsWorked) {
            val workedCount = houses.value.count { it.data == _data.value && (it.situation == Situation.NONE || it.situation == Situation.EMPTY) }
            val isUnlocked = isWorkdayManualUnlock.value
            val isAdmin = _isAdmin.value
            
            if (workedCount >= maxOpenHouses.value && maxOpenHouses.value > 0 && !isUnlocked && !isAdmin) {
                soundManager.playWarning()
                _situationLimitConfirmation.value = house
                return
            }
        }

        // --- IN-FLIGHT HANDLING (ID 0) ---
        // If the house is still 'in-flight' (unsaved), skip DB persistence and only update the draft state.
        if (house.id == 0) {
            _housesInFlight.update { list ->
                list.map {
                    if (it.listOrder == house.listOrder) house else it
                }
            }
            return
        }

        // --- MERGE PREVENTION (DUPLICATE NATURAL KEY) ---
        // Check if this update would clash with ANOTHER house
        val currentHouses = houses.value
        val clashingHouse = currentHouses.find { 
                it.id != house.id && 
                it.data == house.data && 
                it.agentUid == (house.agentUid ?: _currentUserUid.value) &&
                it.agentName.equals(house.agentName, ignoreCase = true) &&
                it.address.blockNumber.equals(house.address.blockNumber, ignoreCase = true) &&
                it.address.blockSequence.equals(house.address.blockSequence, ignoreCase = true) &&
                it.address.streetName.equals(house.address.streetName, ignoreCase = true) &&
                it.address.number.equals(house.address.number, ignoreCase = true) &&
                it.address.sequence == house.address.sequence &&
                it.address.complement == house.address.complement &&
                it.address.bairro.equals(house.address.bairro, ignoreCase = true) &&
                it.visitSegment == house.visitSegment
        }

        // UNIFIED PERSISTENCE (Flicker-Free): Always keep latest typing in Drafts
        val updatedHouse = house.copy(lastUpdated = System.currentTimeMillis())
        _pendingUpdateDrafts.update { it + (updatedHouse.id to updatedHouse) }
        
        // 2. Perform DB update ONLY if no clash with ANOTHER ID
        if (clashingHouse != null) {
            // DUPLICATE DETECTED: Still keep as a "draft" in memory so the UI reflects the user's input.
            // But skip DB update to prevent a REPLACE/Merge that would delete the other record.
            android.util.Log.w("HomeViewModel", "Clash detected for house ${updatedHouse.id} with ${clashingHouse.id}. Skipping DB update.")
            clashDialogJobs[house.id]?.cancel()
            clashDialogJobs.remove(house.id)
        } else {
            // NO DUPLICATE: Save normally. 
            // Draft will be automatically cleared by the 'houses' collector in init when the DB update is confirmed.
            clashDialogJobs[house.id]?.cancel()
            clashDialogJobs.remove(house.id)
            
            performUpdateHouseWithDebounce(updatedHouse, original)
        }
        
        // Mark as recently edited to suppress annoying "Missing data" labels during the typing settle time
        markAsRecentlyEdited(updatedHouse.id)
        
        // Unified delayed validation (3s)
        triggerDelayedValidation()
    }

    private fun performUpdateHouseWithDebounce(house: House, baselineHouse: House? = null) {
        // Cancel any pending update for THIS house to prevent "fighting" coroutines (pisca-pisca)
        houseUpdateJobs[house.id]?.cancel()
        
        houseUpdateJobs[house.id] = viewModelScope.launch {
            try {
                // Determine if this is a "heavy" update (locality change) vs a "light" one (numbers/types)
                val isHeavy = baselineHouse != null && (
                    baselineHouse.address.streetName != house.address.streetName || 
                    baselineHouse.address.blockNumber != house.address.blockNumber ||
                    baselineHouse.address.bairro != house.address.bairro
                )
                
                // Debounce delay: longer for locality changes to avoid segment recalculation spam
                delay(if (isHeavy) 800L else 300L)
                
                performUpdateHouse(house, baselineHouse)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Normal cancellation, do nothing
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "Error in debounced update", e)
            }
        }
    }

    fun confirmDuplicateMerge() {
        _duplicateHouseConfirmation.value?.let { house ->
            clashDialogJobs[house.id]?.cancel()
            clashDialogJobs.remove(house.id)
            _pendingUpdateDrafts.value = _pendingUpdateDrafts.value - house.id
            performUpdateHouse(house, forceMerge = true)
            _duplicateHouseConfirmation.value = null
        }
    }

    private fun markAsRecentlyEdited(houseId: Int) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            _recentlyEditedHouseIds.update { it + (houseId to now) }
            // If the user continues editing, they should call this again which will refresh the timestamp
            delay(4000) // Slightly longer window (4s) for better UX
            _recentlyEditedHouseIds.update { current ->
                if (current[houseId] == now) current - houseId else current
            }
        }
    }

    fun dismissDuplicateConfirmation() {
        _duplicateHouseConfirmation.value?.let { house ->
            clashDialogJobs[house.id]?.cancel()
            clashDialogJobs.remove(house.id)
            _pendingUpdateDrafts.value = _pendingUpdateDrafts.value - house.id
            _duplicateHouseConfirmation.value = null
            // Trigger a re-validation to clear error states if necessary
            validateCurrentDay(showDialog = false)
        }
    }

    private fun performUpdateHouse(house: House, baselineHouse: House? = null, forceMerge: Boolean = false) {
        viewModelScope.launch {
            try {
                val currentName = _agentName.value
                val currentUid = _remoteAgentUid.value ?: _currentUserUid.value
                val houseWithIdentity = house.copy(agentName = currentName, agentUid = currentUid ?: "")
                
                // Determine context from DB + current Drafts for the day
                val latestHouses = houses.value.map { _pendingUpdateDrafts.value[it.id] ?: it }
                
                // Use UseCase for context-aware updates (segment recalculation)
                val adminBypass = _isAdmin.value
                val shouldForce = adminBypass || forceMerge
                val result = saveHouseUseCase.updateHouseWithContext(houseWithIdentity, latestHouses, baselineHouse)
                
                if (result.localizationChanged) {
                    _bairro.value = result.updatedHouse.address.bairro
                    _currentBlock.value = result.updatedHouse.address.blockNumber
                    _currentBlockSequence.value = result.updatedHouse.address.blockSequence
                    _currentStreet.value = result.updatedHouse.address.streetName
                    
                    // Also ensure subsequent houses get the current session identity if they are being updated
                    val subsequentWithIdentity = result.subsequentHouses.map { it.copy(agentName = currentName, agentUid = currentUid ?: "") }
                    saveHouseUseCase.updateHouses(subsequentWithIdentity + result.updatedHouse, shouldForce)
                } else {
                    saveHouseUseCase.updateHouse(result.updatedHouse, houses.value, shouldForce)
                }
                
                // If this house had a validation error highlight, remove it as it's being corrected
                if (_validationErrorHouseIds.value.contains(house.id)) {
                    _validationErrorHouseIds.value = _validationErrorHouseIds.value - house.id
                }

                // BUG FIX: Clear the draft after successful DB update to allow fresh DB states to flow back to the UI.
                _pendingUpdateDrafts.update { it - house.id }
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "Error updating house", e)
                _uiEvent.value = "Falha ao atualizar imóvel: ${e.message}"
                soundManager.playWarning()
            }
        }
    }

    fun deleteHouse(house: House) {
        // ROLE ENFORCEMENT
        if (_isSupervisor.value && !_isAdmin.value) {
            _uiEvent.value = "Apenas administradores podem excluir dados remotamente."
            soundManager.playWarning()
            return
        }

        // LOCK ENFORCEMENT
        val isUnlocked = isWorkdayManualUnlock.value
        val isAdmin = _isAdmin.value
        if (uiState.value.isDayClosed && !isUnlocked && !isAdmin) {
            _uiEvent.value = "Este dia está FECHADO. Desbloqueie para deletar."
            soundManager.playWarning()
            return
        }

        viewModelScope.launch {
            try {
                recentlyDeletedHouse = house
                // Immediately clear from both state pools to prevent "Zombie" house in UI
                _pendingUpdateDrafts.update { it - house.id }
                _housesInFlight.update { list -> 
                    list.filter { it.listOrder != house.listOrder || it.data != house.data }
                }

                // If it's an in-flight house (ID 0), it hasn't finished saving yet.
                // The 'addNewHouse' routine will detect its removal and cancel the DB commit.
                if (house.id == 0) return@launch

                val adminBypass = _isAdmin.value
                saveHouseUseCase.deleteHouse(house, houses.value, adminBypass)
                soundManager.playPop()
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "Error deleting house", e)
                _uiEvent.value = "Erro ao excluir: ${e.message}"
                soundManager.playWarning()
            }
        }
    }

    private var recentlyDeletedHouse: House? = null
    fun restoreDeletedHouse() {
        // LOCK ENFORCEMENT
        val isUnlocked = isWorkdayManualUnlock.value
        val isAdmin = _isAdmin.value
        if (uiState.value.isDayClosed && !isUnlocked && !isAdmin) {
            _uiEvent.value = "Este dia está FECHADO. Desbloqueie para restaurar."
            soundManager.playWarning()
            return
        }

        recentlyDeletedHouse?.let { house ->
            viewModelScope.launch { 
                // CRITICAL: Check for new collisions that might have happened while it was "deleted"
                val latestHouses = houses.value.map { _pendingUpdateDrafts.value[it.id] ?: it }
                val clashing = latestHouses.find { 
                    it.data == house.data && 
                    it.agentUid == house.agentUid &&
                    it.address.blockNumber.equals(house.address.blockNumber, ignoreCase = true) &&
                    it.address.blockSequence.equals(house.address.blockSequence, ignoreCase = true) &&
                    it.address.streetName.equals(house.address.streetName, ignoreCase = true) &&
                    it.address.number.equals(house.address.number, ignoreCase = true) &&
                    it.address.sequence == house.address.sequence &&
                    it.address.complement == house.address.complement &&
                    it.address.bairro.equals(house.address.bairro, ignoreCase = true) &&
                    it.visitSegment == house.visitSegment
                }

                if (clashing != null) {
                    // Restore as a "Red" draft if a locker exists now
                    _pendingUpdateDrafts.update { it + (house.id to house.copy(id = house.id)) }
                    _uiEvent.value = "Imóvel restaurado com conflito detectado."
                } else {
                    val adminBypass = _isAdmin.value
                    saveHouseUseCase.insertHouse(house.copy(id = 0), houses.value, adminBypass)
                }
                
                recentlyDeletedHouse = null 
                soundManager.playPop()
            }
        }
    }

    fun persistListOrder(reorderedList: List<House>) {
        viewModelScope.launch {
            val adminBypass = _isAdmin.value
            val updatedList = reorderedList.mapIndexed { index, h -> h.copy(listOrder = index.toLong()) }
            val recalculated = recalculateVisitSegmentsUseCase.recalculateVisitSegments(updatedList)
            saveHouseUseCase.updateHouses(recalculated, adminBypass)
            triggerDelayedValidation(500) // Trigger rapid validation after move
        }
    }

    fun moveHouse(house: House, moveUp: Boolean) {
        // LOCK ENFORCEMENT
        val isUnlocked = isWorkdayManualUnlock.value
        val isAdmin = _isAdmin.value
        if (uiState.value.isDayClosed && !isUnlocked && !isAdmin) {
            _uiEvent.value = "Este dia está FECHADO. Desbloqueie para ordenar."
            soundManager.playWarning()
            return
        }
        
        viewModelScope.launch {
            val currentDate = _data.value
            val list = houses.value.filter { it.data == currentDate }.toMutableList()
            val index = list.indexOfFirst { it.id == house.id }
            if (index != -1) {
                if (moveUp && index > 0) {
                    Collections.swap(list, index, index - 1)
                } else if (!moveUp && index < list.size - 1) {
                    Collections.swap(list, index, index + 1)
                }
                persistListOrder(list)
            }
        }
    }

    fun moveHouseToDate(house: House, newDate: String) {
        // LOCK ENFORCEMENT (Source and Destination)
        val isUnlocked = isWorkdayManualUnlock.value
        val isAdmin = _isAdmin.value
        
        // We know current day is closed if uiState.value.isDayClosed is true
        if (uiState.value.isDayClosed && !isUnlocked && !isAdmin) {
             _uiEvent.value = "O dia de ORIGEM está FECHADO. Desbloqueie primeiro."
             soundManager.playWarning()
             return
        }

        val existingWorked = houses.value.count { it.data == newDate && (it.situation == Situation.NONE || it.situation == Situation.EMPTY) }
        val houseIsWorked = house.situation == Situation.NONE || house.situation == Situation.EMPTY

        if (existingWorked >= maxOpenHouses.value && maxOpenHouses.value > 0 && houseIsWorked) {
            soundManager.playWarning()
            _uiEvent.value = "Impossível mover: Meta Diária do dia de destino atingida!"
            return
        }
        performMoveHouse(house, newDate)
    }

    private fun performMoveHouse(house: House, newDate: String) {
        viewModelScope.launch { 
            // --- PRO-ACTIVE DESTINATION LOCK CHECK ---
            val destActivity = dayManagementUseCase.getDayActivity(newDate, _remoteAgentUid.value ?: _currentUserUid.value ?: "")
            val isDestUnlocked = destActivity?.isManualUnlock == true
            if (dayManagementUseCase.isDateLocked(destActivity) && !isDestUnlocked && !_isAdmin.value) {
                _uiEvent.value = "A data de DESTINO está FECHADA. Acesse-a e destranque."
                soundManager.playWarning()
                return@launch
            }

            val updatedHouse = house.copy(
                data = newDate,
                agentName = _agentName.value,
                agentUid = _remoteAgentUid.value ?: _currentUserUid.value ?: ""
            )

            // --- CLASH PREVENTION (Moving) ---
            // Check if moving this house would clash with an EXISTING house on the target date.
            // We check against both DB and Drafts on that target date.
            val latestHouses = houses.value.map { _pendingUpdateDrafts.value[it.id] ?: it }
            val clashingHouse = latestHouses.find { 
                it.id != updatedHouse.id && 
                it.data == updatedHouse.data && 
                it.agentUid == updatedHouse.agentUid &&
                it.agentName.equals(updatedHouse.agentName, ignoreCase = true) &&
                it.address.blockNumber.equals(updatedHouse.address.blockNumber, ignoreCase = true) &&
                it.address.blockSequence.equals(updatedHouse.address.blockSequence, ignoreCase = true) &&
                it.address.streetName.equals(updatedHouse.address.streetName, ignoreCase = true) &&
                it.address.number.equals(updatedHouse.address.number, ignoreCase = true) &&
                it.address.sequence == updatedHouse.address.sequence &&
                it.address.complement == updatedHouse.address.complement &&
                it.address.bairro.equals(updatedHouse.address.bairro, ignoreCase = true) &&
                it.visitSegment == updatedHouse.visitSegment
            }

            if (clashingHouse != null) {
                // MOVE CLASH: Defer resolution by holding in drafts
                _pendingUpdateDrafts.update { it + (updatedHouse.id to updatedHouse) }
                _uiEvent.value = "Conflito detectado no destino! Resolva em vermelho."
                soundManager.playWarning()
                // Trigger transition to the target date so the user can see the red clash
                _data.value = newDate
            } else {
                if (updatedHouse.id == 0) {
                    _housesInFlight.update { inFlights ->
                        inFlights.map { if (it.listOrder == house.listOrder) updatedHouse else it }
                    }
                } else {
                    repository.updateHouse(updatedHouse.copy(isSynced = false, lastUpdated = System.currentTimeMillis()))
                }
                
                // Clear any draft from the original date to prevent ghosts
                _pendingUpdateDrafts.update { it - updatedHouse.id }
                
                _uiEvent.value = "Imóvel movido com sucesso para $newDate"
                soundManager.playPop()
                // Transition to see the result
                _data.value = newDate
            }
        }
    }

    fun moveHousesToDate(oldDate: String, newDate: String) {
        viewModelScope.launch {
            try {
                val currentAgent = _agentName.value
                val currentUid = _remoteAgentUid.value ?: _currentUserUid.value
                
                // Normalize inputs for robust querying and state updates
                val normalizedOldDate = oldDate.replace("/", "-")
                val normalizedNewDate = newDate.replace("/", "-")
                
                repository.runInTransaction {
                    // 1. Get all houses for the old date
                    val housesToMove = repository.getHousesByDateAndAgent(normalizedOldDate, currentUid ?: "")
                    
                    if (housesToMove.isNotEmpty()) {
                        // 2. Update houses with new date, unsynced flag and timestamp
                        val updatedHouses = housesToMove.map { 
                            it.copy(
                                data = normalizedNewDate,
                                isSynced = false,
                                lastUpdated = System.currentTimeMillis()
                            ) 
                        }
                        repository.updateHouses(updatedHouses)
                    }

                    // 3. Migrate DayActivity (Status/Observations) with Cloud Tombstones
                    val oldActivity = repository.getDayActivity(normalizedOldDate, currentUid)
                    if (oldActivity != null) {
                        // Check if an activity already exists for the new date
                        val existingNewActivity = repository.getDayActivity(normalizedNewDate, currentUid)
                        if (existingNewActivity == null) {
                            // Safe to move the status and observations to the new date
                            repository.updateDayActivity(oldActivity.copy(date = normalizedNewDate))
                        }
                        
                        // CRITICAL: Use deleteProduction to ensure cloud tombstones are recorded
                        // for the old date's activity metadata, preventing ghosts.
                        repository.deleteProduction(normalizedOldDate, currentUid)
                    }
                }
                
                // 4. Handle in-flight houses for that date
                _housesInFlight.update { inFlights ->
                    inFlights.map { 
                        if (it.data.replace("/", "-") == normalizedOldDate && it.agentUid == currentUid) {
                            it.copy(data = normalizedNewDate)
                        } else it
                    }
                }

                _uiEvent.value = "Produção movida com sucesso!"
                soundManager.playPop()
                
                // If we are currently looking at the old date, follow the movement to the new date
                if (_data.value.replace("/", "-") == normalizedOldDate) {
                    _data.value = normalizedNewDate
                }
                
            } catch (e: Exception) {
                _uiEvent.value = "Erro ao mover produção: ${e.message}"
                e.printStackTrace()
            }
        }
    }

    fun confirmMoveHouse() {
        _moveConfirmationData.value?.let { (house, date) ->
            performMoveHouse(house, date)
            _moveConfirmationData.value = null
        }
    }
    fun dismissMoveConfirmation() { _moveConfirmationData.value = null }

    fun goToToday() {
        _data.value = dateFormatter.format(Date())
    }

    fun performSurgicalCleanup() {
        val myName = _agentName.value.uppercase()
        val myUid = _currentUserUid.value
        if (myName.isBlank() || myUid == null) return

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true) }
            try {
                // Use repository instead of non-existent houseDao
                val houses = repository.getAllHousesSnapshot()
                val misattributed = houses.filter { 
                    it.agentUid == myUid && 
                    it.agentName.isNotBlank() && 
                    it.agentName.uppercase() != myName &&
                    !it.agentName.contains("@") &&
                    it.address.blockNumber.isNotBlank() // Ensure it's a real house record
                }
                
                if (misattributed.isNotEmpty()) {
                    android.util.Log.w("HomeViewModel", "Surgical Cleanup: Removing ${misattributed.size} leaked records.")
                    misattributed.forEach { repository.deleteHouse(it) }
                }
                
                _uiState.update { it.copy(hasMisattributedData = false, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Falha na limpeza: ${e.message}", isLoading = false) }
            }
        }
    }

    fun goToLastWorkDay() {
        viewModelScope.launch {
            val allHouses = houses.value
            val lastWorkDay = allHouses.maxByOrNull { house ->
                try {
                    dateFormatter.parse(house.data)?.time ?: 0L
                } catch (e: Exception) {
                    0L
                }
            }?.data
            if (lastWorkDay != null) {
                _data.value = lastWorkDay
            }
        }
    }

    fun goToCurrentWeek() {
        _currentWeekStart.value = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        }
    }

    fun updateSearchQuery(q: String) { _searchQuery.value = q }
    fun updateBairro(b: String) { _bairro.value = b.uppercase() }
    fun updateAgentName(n: String) { _agentName.value = n.uppercase() }
    fun updateContext(b: String, s: String, st: String) {
        _currentBlock.value = b.trim().uppercase(); _currentBlockSequence.value = s.trim().uppercase(); _currentStreet.value = st.trim().formatStreetName()
    }
    fun updateHeader(m: String, b: String, c: String, z: String, t: Int, d: String, ci: String, a: Int) {
        _municipio.value = m.uppercase()
        _bairro.value = b.uppercase()
        _categoria.value = c.uppercase()
        _zona.value = z.uppercase()
        _tipo.value = t
        _data.value = d.replace("/", "-")
        _ciclo.value = ci.uppercase()
        _atividade.value = a
    }

    fun onDateSelected(date: String) {
        val normalizedDate = date.replace("/", "-")
        _data.value = normalizedDate
        
        viewModelScope.launch {
            val uid = _currentUserUid.value ?: return@launch
            // Try to restore context from historical data
            val lastHouse = repository.getLastHouseForAgentOnDate(uid, normalizedDate) 
                ?: repository.getLastHouseForAgent(uid)
            
            lastHouse?.let { house ->
                // Auto-fill header fields if they are currently blank/default to improve UX
                if (_municipio.value.isBlank() || _municipio.value == "BOM JARDIM") _municipio.value = house.context.municipio.uppercase()
                if (_bairro.value.isBlank()) _bairro.value = house.address.bairro.uppercase()
                if (_zona.value.isBlank()) _zona.value = house.context.zona.uppercase()
                if (_ciclo.value.isBlank()) _ciclo.value = house.context.ciclo.uppercase()
                if (_categoria.value.isBlank()) _categoria.value = house.context.categoria.uppercase()
                
                // Note: We don't overwrite Tipo and Atividade unless they are definitely uninitialized
                // as those might be day-specific and user might have started typing.
            }
        }
    }

    private fun calculateCicloForDate(dateStr: String): String {
        return try {
            val dateObj = dateFormatter.parse(dateStr.replace("/", "-"))
            if (dateObj != null) {
                val cal = Calendar.getInstance()
                cal.time = dateObj
                val month = cal.get(Calendar.MONTH)
                when (month) {
                    Calendar.JANUARY, Calendar.FEBRUARY -> "1º"
                    Calendar.MARCH, Calendar.APRIL -> "2º"
                    Calendar.MAY, Calendar.JUNE -> "3º"
                    Calendar.JULY, Calendar.AUGUST -> "4º"
                    Calendar.SEPTEMBER, Calendar.OCTOBER -> "5º"
                    Calendar.NOVEMBER, Calendar.DECEMBER -> "6º"
                    else -> "1º"
                }
            } else "1º"
        } catch (e: Exception) {
            "1º"
        }
    }
    fun updateMunicipio(m: String) { _municipio.value = m.uppercase() }
    fun updateZona(z: String) { _zona.value = z.uppercase() }
    fun updateCategoria(c: String) { _categoria.value = c.uppercase() }


    fun navigateToDate(date: String) {
        _data.value = date.replace("/", "-")
        _navigationTab.value = 0 // Navigate to Production tab
    }

    fun clearNavigationTab() {
        _navigationTab.value = null
    }
    fun updateDayStatus(originalDate: String, status: String) {
        val date = originalDate.replace("/", "-")
        viewModelScope.launch {
            var rippleError: String? = null
            _uiEvent.value = "Iniciando atualização de status..."
            try {
                var wasWorkingChange: Pair<Boolean, Boolean>? = null
                val currentUid = _remoteAgentUid.value ?: _currentUserUid.value
                
                repository.runInTransaction {
                    val currentAgent = _agentName.value
                    
                    // Normalize all activity dates for lookup consistency
                    val allActivities = repository.getAllDayActivitiesOnce(currentUid ?: "")
                        .associateBy { it.date.replace("/", "-") }
                    
                    val existing = allActivities[date]
                    val oldStatus = existing?.status ?: "NORMAL"
                    _uiEvent.value = "Status atual: $oldStatus. Novo: $status"
                    
                    // Only trigger ripple if "Working Day" status changes
                    val wasWorking = (oldStatus.trim().uppercase() == "NORMAL") || oldStatus.isBlank()
                    val isWorking = (status.trim().uppercase() == "NORMAL") || status.isBlank()
                    
                    if (wasWorking != isWorking) {
                        wasWorkingChange = wasWorking to isWorking
                        _uiEvent.value = "Mudança de tipo de dia detectada (${if(wasWorking) "Trabalho" else "Folga"} -> ${if(isWorking) "Trabalho" else "Folga"})"
                    }
                    
                    // Update the status and manage lock state
                    val updatedActivity = if (existing != null) {
                        if (existing.agentUid != currentUid) {
                            repository.deleteDayActivity(existing.date, existing.agentUid)
                        }
                        existing.copy(
                            date = date, 
                            status = status, 
                            isClosed = !isWorking, 
                            isManualUnlock = if (isWorking) false else existing.isManualUnlock,
                            agentUid = currentUid ?: ""
                        )
                    } else {
                        DayActivity(
                            date = date, 
                            status = status, 
                            isClosed = !isWorking, 
                            agentName = currentAgent, 
                            agentUid = currentUid ?: ""
                        )
                    }
                    repository.updateDayActivity(updatedActivity)

                    if (wasWorking != isWorking) {
                        // Ripple Effect: Shift production dates
                        val updatedStatusMap = allActivities.toMutableMap()
                        updatedStatusMap[date] = updatedActivity
                        
                        _uiEvent.value = "Calculando efeito cascata (movimentação de imóveis)..."
                        // Analyze all production for this agent/remote agent
                        val allAgentHouses = repository.getAllHousesOnce(currentUid ?: "")
                        val targetDateObj = try { dateFormatter.parse(date) } catch (e: Exception) { null } 
                        
                        if (targetDateObj == null) {
                            _uiEvent.value = "Erro: Data $date inválida para cálculo."
                            return@runInTransaction
                        }
                        
                        // Analyze production at or after the changed date
                        val housesToShift = allAgentHouses.filter { 
                            val houseDate = try { dateFormatter.parse(it.data.replace("/", "-")) } catch (e: Exception) { null }
                            houseDate != null && !houseDate.before(targetDateObj)
                        }
                        
                        _uiEvent.value = "Casas identificadas para mover: ${housesToShift.size}"
                        
                        if (housesToShift.isNotEmpty()) {
                            val productionDates = housesToShift.map { it.data }.distinct()
                            val productionDatesSet = productionDates.map { it.replace("/", "-") }.toSet()
                            val dateToOffset = mutableMapOf<String, Int>()
                            
                            // 1. Calculate offsets using old configuration
                            productionDates.forEach { pDate ->
                                var offset = 0
                                val cal = Calendar.getInstance()
                                cal.time = targetDateObj
                                val pDateObj = try { dateFormatter.parse(pDate.replace("/", "-")) } catch (e: Exception) { null } ?: return@forEach
                                
                                while (cal.time.before(pDateObj)) {
                                    val currentDateStr = dateFormatter.format(cal.time)
                                    if (isWorkingDay(cal, allActivities) || productionDatesSet.contains(currentDateStr)) {
                                        offset++
                                    }
                                    cal.add(Calendar.DAY_OF_YEAR, 1)
                                }
                                dateToOffset[pDate] = offset
                            }
                            
                            // 2. Map offsets to new target dates using updated configuration
                            val sortedOffsets = dateToOffset.values.distinct().sorted()
                            val offsetToNewDate = mutableMapOf<Int, String>()
                            
                            var currentWorkingOffset = 0
                            val cal = Calendar.getInstance()
                            cal.time = targetDateObj
                            
                            var daysChecked = 0
                            while (currentWorkingOffset <= (sortedOffsets.lastOrNull() ?: 0) && daysChecked < 365) {
                                if (isWorkingDay(cal, updatedStatusMap)) {
                                    if (sortedOffsets.contains(currentWorkingOffset)) {
                                        offsetToNewDate[currentWorkingOffset] = dateFormatter.format(cal.time)
                                    }
                                    currentWorkingOffset++
                                }
                                cal.add(Calendar.DAY_OF_YEAR, 1)
                                daysChecked++
                            }
                            
                            // 3. Batch update records
                            val updatedHouses = housesToShift.mapNotNull { house ->
                                val offset = dateToOffset[house.data]
                                val newDate = offsetToNewDate[offset]
                                if (newDate != null && newDate != house.data.replace("/", "-")) {
                                    house.copy(
                                        data = newDate, 
                                        isSynced = false, 
                                        lastUpdated = System.currentTimeMillis()
                                    )
                                } else null
                            }
                            
                             if (updatedHouses.isNotEmpty()) {
                                  _uiEvent.value = "Atualizando ${updatedHouses.size} imóveis..."
                                  repository.updateHouses(updatedHouses)
                                  _uiEvent.value = "Movimentação concluída com sucesso."
                             } else if (housesToShift.isNotEmpty()) {
                                 _uiEvent.value = "Nenhuma casa precisou mudar de data."
                             }
                        }
                    }
                }
                
                // Success path
                wasWorkingChange?.let { (wasWorking, isWorking) ->
                    if (!isWorking && date == _data.value) {
                        val next = dayManagementUseCase.getNextBusinessDay(date, currentUid)
                        if (next.isNotBlank()) {
                            _data.value = next
                            soundManager.playPop()
                        }
                    } else if (isWorking) {
                        val currentD = try { dateFormatter.parse(_data.value) } catch (e: Exception) { null }
                        val newD = try { dateFormatter.parse(date) } catch (e: Exception) { null }
                        if (newD != null && currentD != null && newD.before(currentD)) {
                            _data.value = date
                            soundManager.playPop()
                        }
                    }
                }
                if (rippleError != null) {
                    _uiEvent.value = rippleError
                    soundManager.playWarning()
                } else {
                    soundManager.playSuccess()
                }
            } catch (e: IllegalStateException) {
                android.util.Log.e("HomeViewModel", "Day locked during ripple: ${e.message}")
                _uiEvent.value = rippleError ?: "Erro: Alguns dias estão bloqueados para Auditoria."
                soundManager.playWarning()
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "Error updating day status", e)
                _uiEvent.value = "Erro ao atualizar status: ${e.message}"
                soundManager.playWarning()
            }
        }
    }
    fun addNewActivity(name: String) {
        viewModelScope.launch { settingsManager.addCustomActivity(name) }
    }
    fun removeActivity(name: String) {
        viewModelScope.launch { settingsManager.removeCustomActivity(name) }
    }


    // --- Boletim Actions ---
    fun transferProduction(date: String, newAgent: String) {
        viewModelScope.launch {
            val housesToTransfer = houses.value.filter { it.data == date }
            repository.updateHouses(housesToTransfer.map { it.copy(agentName = newAgent) })
        }
    }

    fun getHousesForDate(date: String, agentName: String? = null): List<House> {
        val targetName = agentName ?: _agentName.value
        return houses.value.filter { it.data == date && it.agentName.uppercase() == targetName.uppercase() }
    }

    fun deleteProduction(date: String) {
        viewModelScope.launch {
            try {
                val currentAgent = _agentName.value
                val currentUid = _remoteAgentUid.value ?: _currentUserUid.value
                repository.deleteProduction(date, currentUid)
                _uiEvent.value = "Produção excluída com sucesso."
                soundManager.playPop()
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "Error deleting production", e)
                _uiEvent.value = "Erro ao excluir produção: ${e.message}"
                soundManager.playWarning()
            }
        }
    }

    fun exportDayDataAndShare(context: Context, date: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentAgent = _agentName.value
                val currentUid = _remoteAgentUid.value ?: _currentUserUid.value
                val dayHouses = repository.getAllHousesOnce(currentUid ?: "").filter { it.data == date && it.agentName == currentAgent }
                val dayActivities = repository.getAllDayActivitiesOnce(currentUid ?: "").filter { it.date == date && it.agentName == currentAgent }
                val backupData = BackupData(dayHouses, dayActivities)

                // Generate Filename
                val safeAgentName = currentAgent.trim().replace(" ", "_").ifBlank { "Agente" }
                val fileName = "Producao_${safeAgentName}_${date.replace("/", "-")}.json"

                // Save to Cache Dir
                val backupDir = File(context.cacheDir, "exports")
                if (backupDir.exists()) backupDir.deleteRecursively()
                backupDir.mkdirs()

                val file = File(backupDir, fileName)
                backupManager.exportToFile(file, backupData)

                // Create Share Intent
                val authority = "${context.packageName}.fileprovider"
                val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, file)

                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    putExtra(android.content.Intent.EXTRA_SUBJECT, fileName)
                    putExtra(android.content.Intent.EXTRA_TEXT, "Segue em anexo a produção do dia $date.")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                val chooser = android.content.Intent.createChooser(shareIntent, "Compartilhar Produção")
                chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

                withContext(Dispatchers.Main) {
                    context.startActivity(chooser)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    _uiEvent.value = "Erro ao exportar dados: ${e.message}"
                    soundManager.playWarning()
                }
            }
        }
    }

    fun clearUiEvent() { _uiEvent.value = null }

    fun triggerUiEvent(message: String) {
        _uiEvent.value = message
    }
    fun dismissIntegrityDialog() { _integrityDialogMessage.value = null }
    fun dismissClosingAudit() { _showClosingAudit.value = null }
    fun dismissGoalReached() { _showGoalReached.value = false }
    fun navigateToErroneousDay(d: String) { 
        viewModelScope.launch {
            val effectiveUid = _remoteAgentUid.value ?: _currentUserUid.value
            val activity = dayManagementUseCase.getDayActivity(d, effectiveUid)
            if (activity?.isClosed == true) {
                dayManagementUseCase.unlockDay(d, effectiveUid)
            }
            _data.value = d
            _showMultiDayErrorDialog.value = false
            // Allow UI to update to the new date before validating
            delay(300)
            validateCurrentDay(showDialog = false) // Silence navigation dialog per user request
        }
    }
    fun dismissMultiDayErrorDialog() { _showMultiDayErrorDialog.value = false }
    fun showMultiDayErrorDialog() { _showMultiDayErrorDialog.value = true }

    fun confirmSituationExceeded() {
        _situationLimitConfirmation.value = null
    }
    fun dismissSituationLimitConfirmation() { _situationLimitConfirmation.value = null }

    fun dismissHistoryUnlockConfirmation() { _showHistoryUnlockConfirmation.value = false }

    fun confirmUnlockHistory() {
        viewModelScope.launch {
            val effectiveUid = _remoteAgentUid.value ?: _currentUserUid.value
            dayManagementUseCase.unlockDay(_data.value, effectiveUid)
            _showHistoryUnlockConfirmation.value = false
        }
    }

    fun backupData(context: Context, uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val effectiveUid = _remoteAgentUid.value ?: _currentUserUid.value
                val agentName = _agentName.value
                val h = repository.getAllHousesOnce(effectiveUid ?: "")
                val a = repository.getAllDayActivitiesOnce(effectiveUid ?: "")
                backupManager.exportData(context, uri, BackupData(h, a, effectiveUid ?: "", agentName))
                withContext(Dispatchers.Main) {
                    _uiEvent.value = "Backup concluído com sucesso!"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiEvent.value = "Erro ao salvar backup: ${e.message}"
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
                
                // Generate Filename
                val sdf = java.text.SimpleDateFormat("dd-MM-yyyy_HH-mm", java.util.Locale.US)
                val now = sdf.format(java.util.Date())
                val safeAgentName = _agentName.value.trim().replace(" ", "_").ifBlank { "Agente" }
                val fileName = "Backup_${safeAgentName}_$now.json"
                
                // Save to Cache Dir (so we can share it via FileProvider)
                // Using cacheDir/backups to keep it clean
                val backupDir = File(context.cacheDir, "backups")
                if (backupDir.exists()) backupDir.deleteRecursively()
                backupDir.mkdirs()
                
                val file = File(backupDir, fileName)
                backupManager.exportToFile(file, backupData)
                
                // Create Share Intent
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
                    // _uiEvent.value = "Selecione onde salvar o backup" // Optional feedback
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


    private fun isWorkingDay(cal: Calendar, statusMap: Map<String, DayActivity>): Boolean {
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) return false
        val dateStr = dateFormatter.format(cal.time)
        val activity = statusMap[dateStr]
        val status = activity?.status ?: "NORMAL"
        return status == "NORMAL" || status.isBlank()
    }

    fun forceFullSync() {
        viewModelScope.launch {
            syncRepository.pullCloudDataToLocal(force = true)
        }
    }

    fun triggerImmediateSync() {
        try {
            val context = com.antigravity.healthagent.context.getContext()
            val constraints = androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build()

            val syncRequest = androidx.work.OneTimeWorkRequestBuilder<com.antigravity.healthagent.data.sync.SyncWorker>()
                .setConstraints(constraints)
                .build()

            androidx.work.WorkManager.getInstance(context).enqueue(syncRequest)
        } catch (e: Exception) {
            android.util.Log.e("HomeViewModel", "Failed to trigger sync", e)
            // Fallback: manual sync trigger if background fails or context is missing
            syncDataToCloud()
        }
    }

    private fun calculateDashboardTotals(dayHouses: List<House>): DashboardTotals {
        return DashboardTotals(
            totalHouses = dayHouses.size, // If it's in the list, it's a property being counted
            a1 = dayHouses.sumOf { it.treatment.a1 }, a2 = dayHouses.sumOf { it.treatment.a2 },
            b = dayHouses.sumOf { it.treatment.b }, c = dayHouses.sumOf { it.treatment.c },
            d1 = dayHouses.sumOf { it.treatment.d1 }, d2 = dayHouses.sumOf { it.treatment.d2 },
            e = dayHouses.sumOf { it.treatment.e }, eliminados = dayHouses.sumOf { it.treatment.eliminados },
            larvicida = dayHouses.sumOf { it.treatment.larvicida },
            totalFocos = dayHouses.count { it.treatment.comFoco },
            totalRegisteredHouses = dayHouses.size,
            worked = dayHouses.count { it.situation == Situation.NONE || it.situation == Situation.EMPTY },
            recused = dayHouses.count { it.situation == Situation.REC },
            absent = dayHouses.count { it.situation == Situation.A },
            closed = dayHouses.count { it.situation == Situation.F },
            vacant = dayHouses.count { it.situation == Situation.V }
        )
    }
    
    private fun generateHouseKey(hh: House): String {
        val b = (hh.address.bairro).stringNormalize()
        val bn = (hh.address.blockNumber).stringNormalize()
        val bs = (hh.address.blockSequence).stringNormalize()
        val sn = (hh.address.streetName).formatStreetName()
        val n = (hh.address.number).stringNormalize()
        val c = hh.address.complement.toString().stringNormalize()
        val vs = hh.visitSegment.toString()
        return "$b|$bn|$bs|$sn|$n|${hh.address.sequence}|$c|$vs".uppercase()
    }

    private fun clearOldPdfs(context: Context, prefix: String) {
        try {
            context.cacheDir.listFiles()?.forEach { 
                if (it.isFile && it.name.startsWith(prefix, ignoreCase = true) && it.name.endsWith(".pdf", ignoreCase = true)) {
                    it.delete()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("HomeViewModel", "Error clearing old PDFs with prefix $prefix", e)
        }
    }

    private fun House.toUiState(): HouseUiState {
        return HouseUiStateMapper.map(this, houseValidationUseCase)
    }

    private val House.isConcluded: Boolean get() = quarteiraoConcluido || localidadeConcluida
    private val House.isLocalidadeConcluded: Boolean get() = localidadeConcluida
    private fun parseDate(date: String): Date? = try { dateFormatter.parse(date) } catch (e: Exception) { null }
}
