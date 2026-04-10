package com.antigravity.healthagent.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.repository.HouseRepository
import com.antigravity.healthagent.data.repository.StreetRepository
import com.antigravity.healthagent.domain.usecase.HouseValidationUseCase
import com.antigravity.healthagent.domain.usecase.DayManagementUseCase
import com.antigravity.healthagent.domain.usecase.HouseManagementUseCase
import com.antigravity.healthagent.utils.formatStreetName
import com.antigravity.healthagent.utils.SoundManager
import com.antigravity.healthagent.data.settings.SettingsManager
import com.antigravity.healthagent.data.backup.BackupScheduler
import com.antigravity.healthagent.data.backup.BackupManager
import com.antigravity.healthagent.data.backup.BackupData
import com.antigravity.healthagent.utils.SemanalPdfGenerator
import com.antigravity.healthagent.utils.BoletimPdfGenerator
import com.antigravity.healthagent.domain.repository.*
import com.antigravity.healthagent.domain.usecase.*
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
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: HouseRepository,
    private val syncRepository: SyncRepository,
    private val agentRepository: AgentRepository,
    private val localizationRepository: LocalizationRepository,
    private val houseValidationUseCase: HouseValidationUseCase,
    val dayManagementUseCase: DayManagementUseCase,
    private val houseManagementUseCase: HouseManagementUseCase,
    private val soundManager: SoundManager,
    private val settingsManager: SettingsManager,
    private val backupScheduler: BackupScheduler,
    private val streetRepository: StreetRepository,
    private val authRepository: AuthRepository,
    private val syncDataUseCase: SyncDataUseCase,
    private val getWeeklySummaryUseCase: GetWeeklySummaryUseCase,
    private val getBoletimSummaryUseCase: GetBoletimSummaryUseCase,
    private val getRGBlocksUseCase: GetRGBlocksUseCase,
    private val cleanupHistoricalDataUseCase: CleanupHistoricalDataUseCase
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

    private val _selectedRgBairro = MutableStateFlow("")
    private val _rgYear = MutableStateFlow(Calendar.getInstance().get(Calendar.YEAR).toString())
    private val _selectedRgBlock = MutableStateFlow("")

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
    private val _syncStatus = MutableStateFlow(SyncStatus())
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
    private val recentlyEditedHouseIds: StateFlow<Set<Int>> = _recentlyEditedHouseIds.map { it.keys }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val clashDialogJobs = mutableMapOf<Int, kotlinx.coroutines.Job>()
    private val houseUpdateJobs = mutableMapOf<Int, kotlinx.coroutines.Job>()

    // --- State Injections ---
    val easyMode: StateFlow<Boolean> = settingsManager.easyMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val solarMode: StateFlow<Boolean> = settingsManager.solarMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val maxOpenHouses: StateFlow<Int> = settingsManager.maxOpenHouses
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 25)
    val backupFrequency: StateFlow<com.antigravity.healthagent.data.backup.BackupFrequency> = settingsManager.backupFrequency
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.antigravity.healthagent.data.backup.BackupFrequency.DAILY)
    val isAppModeSelected: StateFlow<Boolean?> = settingsManager.isAppModeSelected
        .map { it as Boolean? }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val themeMode: StateFlow<String?> = settingsManager.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    
    val themeColor: StateFlow<String?> = settingsManager.themeColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val popSound: StateFlow<String> = settingsManager.popSound
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "SYSTEM_NOTIFICATION_1")

    val successSound: StateFlow<String> = settingsManager.successSound
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "SYSTEM_NOTIFICATION_1")

    val celebrationSound: StateFlow<String> = settingsManager.celebrationSound
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "SYSTEM_ALARM")

    val warningSound: StateFlow<String> = settingsManager.warningSound
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "SYSTEM_NOTIFICATION_2")

    val pendingAccessRequestsCount: StateFlow<Int> = authRepository.pendingAccessRequests
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val dateCache = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun getTimestamp(date: String): Long {
        return dateCache.getOrPut(date) {
            try {
                dateFormatter.parse(date)?.time ?: 0L
            } catch (e: Exception) {
                0L
            }
        }
    }

    fun setRemoteAgent(agent: com.antigravity.healthagent.domain.repository.AgentData?) {
        if (agent != null) {
            // Store backup of local name if we haven't already
            if (_localAgentNameBackup == null) {
                _localAgentNameBackup = _agentName.value
            }
            _agentName.value = agent.agentName ?: agent.email
            _remoteAgent.value = agent.agentName ?: agent.email
            _remoteAgentUid.value = agent.uid
        } else {
            // Restoring local state
            _localAgentNameBackup?.let { _agentName.value = it }
            _localAgentNameBackup = null
            _remoteAgent.value = null
            _remoteAgentUid.value = null
        }
        
        // Persist for background processes
        viewModelScope.launch(Dispatchers.IO) {
            settingsManager.setRemoteAgentUid(agent?.uid)
            if (agent != null) {
                try {
                    val name = agent.agentName?.takeIf { it.isNotBlank() } ?: agent.email.substringBefore("@")
                    repository.migrateLocalData(name, agent.email, agent.uid)
                } catch (e: Exception) {
                    android.util.Log.e("HomeViewModel", "Error migrating remote agent local data", e)
                }
            }
        }
    }

    private val _currentUserUid = MutableStateFlow("")

    fun finishEditSession(onComplete: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            _syncStatus.value = SyncStatus(SyncStage.STARTING, 0.1f, "Finalizando edição...")
            
            try {
                // 1. Push local data (which belongs to the remote agent) to cloud
                _syncStatus.value = SyncStatus(SyncStage.UPLOADING, 0.5f, "Sincronizando dados remotos...")
                val agentName = _remoteAgent.value ?: ""
                val uid = _remoteAgentUid.value ?: _currentUserUid.value
                val houses = repository.getAllHousesOnce(agentName, uid)
                val activities = repository.getAllDayActivitiesOnce(agentName, uid)
                val result = syncDataUseCase.pushData(houses, activities, uid)
                
                if (result.isSuccess) {
                    // 2. Clear state and local DB
                    _syncStatus.value = SyncStatus(SyncStage.SUCCESS, 1.0f, "Sincronizado com sucesso!")
                    setRemoteAgent(null)
                    syncRepository.clearLocalData()
                    _uiEvent.value = "Edição finalizada e sincronizada!"
                    delay(1500)
                    onComplete()
                } else {
                    _syncStatus.value = SyncStatus(SyncStage.ERROR, 0.5f, "Falha: ${result.exceptionOrNull()?.message}")
                    _uiEvent.value = "Falha ao finalizar: ${result.exceptionOrNull()?.message}"
                    delay(3000)
                }
            } catch (e: Exception) {
                _syncStatus.value = SyncStatus(SyncStage.ERROR, 0f, "Erro: ${e.message}")
                _uiEvent.value = "Erro ao finalizar: ${e.message}"
                delay(3000)
            } finally {
                _syncStatus.value = SyncStatus(SyncStage.IDLE)
            }
        }
    }

    // Agent-filtered list for initialization and reactive updates
    private val allHousesFlow = combine(_agentName, _remoteAgentUid, _currentUserUid) { name, remoteUid, currentUid -> 
        Triple(name, remoteUid, currentUid) 
    }.distinctUntilChanged()
    .flatMapLatest { (name, remoteUid, currentUid) -> 
        val effectiveUid = remoteUid ?: currentUid
        repository.getAllHouses(name, effectiveUid) 
    }
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Global list for RG view (all agents)
    private val globalHousesFlow = repository.getAllHousesSnapshotFlow()


    val isDayClosed: StateFlow<Boolean> = combine(_data, _agentName, _remoteAgentUid, _currentUserUid) { date, name, remoteUid, currentUid ->
        val effectiveUid = remoteUid ?: currentUid
        Triple(date, name, effectiveUid)
    }.flatMapLatest { (date, name, uid) ->
        repository.getDayActivityFlow(date, name, uid).map { it?.isClosed == true && it?.isManualUnlock != true }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isWorkdayManualUnlock: StateFlow<Boolean> = combine(_data, _agentName, _remoteAgentUid, _currentUserUid) { date, name, remoteUid, currentUid ->
        val effectiveUid = remoteUid ?: currentUid
        Triple(date, name, effectiveUid)
    }.flatMapLatest { (date, name, uid) ->
        repository.getDayActivityFlow(date, name, uid).map { it?.isManualUnlock == true }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

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
        val mergedList = (dbHouses.map { drafts[it.id] ?: it } + inFlights).sortedBy { it.listOrder }
        mergedList
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
    
    val weeklySummary: StateFlow<List<DaySummary>> = combine(currentWeekDates, _agentName, _remoteAgentUid, _currentUserUid) { dates, agent, remoteUid, currentUid -> 
        val effectiveUid = remoteUid ?: currentUid
        Triple(dates, agent, effectiveUid) 
    }.flatMapLatest { (dates, agent, uid) ->
        val activitiesFlow = repository.getDayActivities(dates, agent, uid)
        // Use allHousesFlow instead of 'houses' to ensure strict agent filtering for Admins
        combine(allHousesFlow, activitiesFlow) { all: List<House>, activities: List<DayActivity> ->
            dates.map { date ->
                val dayHouses = all.filter { it.data == date }
                val activity = activities.find { it.date == date }
                val visitedHousesCount = dayHouses.count { it.situation != Situation.EMPTY }
                val inspectedHousesCount = dayHouses.count { it.situation == Situation.NONE }
                DaySummary(date, visitedHousesCount, inspectedHousesCount, activity?.status ?: "")
            }
        }
    }
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val weeklySummaryTotals: StateFlow<WeeklySummaryTotals> = combine(allHousesFlow, currentWeekDates) { list, dates ->
        val weekHouses = list.filter { dates.contains(it.data) }
        WeeklySummaryTotals(
            totalHouses = weekHouses.count { it.situation != Situation.EMPTY },
            totalTratados = weekHouses.count { house ->
                house.a1 > 0 || house.a2 > 0 || house.b > 0 || house.c > 0 ||
                house.d1 > 0 || house.d2 > 0 || house.e > 0 || house.eliminados > 0 || house.larvicida > 0
            },
            totalFoci = weekHouses.count { it.comFoco },
            totalWorked = weekHouses.count { it.situation == Situation.NONE },
            totalFechados = weekHouses.count { it.situation == Situation.F },
            totalRecusados = weekHouses.count { it.situation == Situation.REC },
            totalAbsent = weekHouses.count { it.situation == Situation.A },
            totalVacant = weekHouses.count { it.situation == Situation.V }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WeeklySummaryTotals())
    
    val weeklyObservations: StateFlow<List<House>> = combine(houses, currentWeekDates) { list, dates ->
        list.filter { dates.contains(it.data) && it.observation.isNotBlank() }
            .sortedByDescending { it.lastUpdated }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val boletimList: StateFlow<List<BoletimSummary>> = combine(houses, _agentName, _remoteAgentUid, _currentUserUid) { h, name, remoteUid, currentUid -> 
        val effectiveUid = remoteUid ?: currentUid
        Triple(h, name, effectiveUid) 
    }.flatMapLatest { (h: List<House>, name: String, uid: String) ->
            val dates = h.map { it.data }.distinct()
            repository.getDayActivities(dates, name, uid).map { activities ->
                getBoletimSummaryUseCase(h, activities)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activityOptions: StateFlow<List<String>> = settingsManager.customActivities.map { custom -> 
        (listOf("NORMAL", "FERIADO", "PONTO FACULTATIVO", "REUNIÃO", "TREINAMENTO") + custom.toList()).distinct().sorted()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf("NORMAL", "FERIADO", "PONTO FACULTATIVO", "REUNIÃO", "TREINAMENTO").sorted())

    val customActivities: StateFlow<Set<String>> = settingsManager.customActivities
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val availableYears: StateFlow<List<String>> = combine(allHousesFlow, _agentName) { all, name ->
        if (name == "Admin" || name == "Supervisor") emptyList()
        else all.mapNotNull { 
            try { 
                val d = dateFormatter.parse(it.data)
                if(d != null) {
                    val c = Calendar.getInstance().apply { time = d }
                    c.get(Calendar.YEAR).toString()
                } else null 
            } catch (e: Exception) { null }
        }.distinct().sortedDescending()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf(Calendar.getInstance().get(Calendar.YEAR).toString()))

    val rgBlocks: StateFlow<List<BlockSegment>> = combine(allHousesFlow, _selectedRgBairro, _rgYear, _agentName) { h, b, y, name ->
        // If the current identity is "Admin" or "Supervisor", it means no specific remote agent is being 
        // viewed in a scoped manner. To prevent a "messed up" composite view of multiple agents' 
        // sequences, we return empty until a specific agent is selected.
        val filtered = if (name == "Admin" || name == "Supervisor") {
             emptyList()
        } else h

        getRGBlocksUseCase(filtered, b, y)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedRgBlock: StateFlow<String> = _selectedRgBlock.asStateFlow()

    val rgFilteredList: StateFlow<List<House>> = combine(rgBlocks, selectedRgBlock) { blocks, selectedId ->
        blocks.find { it.id == selectedId }?.houses ?: emptyList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Helper to filter houses by Year (Semester removed)
    private fun filterByYear(houses: List<House>, year: String): List<House> {
        return houses.filter { house ->
            val date = try { dateFormatter.parse(house.data) } catch (e: Exception) { null }
            if (date != null) {
                val cal = Calendar.getInstance().apply { time = date }
                val hYear = cal.get(Calendar.YEAR).toString()
                hYear == year
            } else false
        }
    }

    val rgBairros: StateFlow<List<String>> = combine(allHousesFlow, _rgYear, _agentName) { all, year, name ->
        if (name == "Admin" || name == "Supervisor") emptyList()
        else filterByYear(all, year)
            .map { it.bairro.trim().uppercase() }
            .distinct()
            .sorted()
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())


    init {
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
                            it.blockNumber.equals(draft.blockNumber, ignoreCase = true) &&
                            it.blockSequence.equals(draft.blockSequence, ignoreCase = true) &&
                            it.streetName.equals(draft.streetName, ignoreCase = true) &&
                            it.number.equals(draft.number, ignoreCase = true) &&
                            it.sequence == draft.sequence &&
                            it.complement == draft.complement &&
                            it.bairro.equals(draft.bairro, ignoreCase = true) &&
                            it.visitSegment == draft.visitSegment
                        }
                        
                        // 2. Perfect Sync Check: Prune only if the DB record exactly matches ALL user-editable fields.
                        // ADDITION: Check lastUpdated to ensure we don't prune ORPHANED drafts if the DB update hasn't arrived.
                        val dbMatch = dbHouses.find { it.id == id }
                        val isDraftSynced = dbMatch != null && 
                            dbMatch.lastUpdated >= draft.lastUpdated && // STABILITY: Ensure DB is at least as new as draft
                            dbMatch.number == draft.number && 
                            dbMatch.sequence == draft.sequence && 
                            dbMatch.complement == draft.complement &&
                            dbMatch.propertyType == draft.propertyType &&
                            dbMatch.situation == draft.situation &&
                            dbMatch.streetName == draft.streetName &&
                            dbMatch.blockNumber == draft.blockNumber &&
                            dbMatch.blockSequence == draft.blockSequence &&
                            dbMatch.bairro == draft.bairro &&
                            dbMatch.observation == draft.observation &&
                            dbMatch.a1 == draft.a1 && dbMatch.a2 == draft.a2 &&
                            dbMatch.b == draft.b && dbMatch.c == draft.c &&
                            dbMatch.d1 == draft.d1 && dbMatch.d2 == draft.d2 &&
                            dbMatch.e == draft.e && dbMatch.eliminados == draft.eliminados &&
                            dbMatch.larvicida == draft.larvicida && dbMatch.comFoco == draft.comFoco &&
                            dbMatch.quarteiraoConcluido == draft.quarteiraoConcluido &&
                            dbMatch.localidadeConcluida == draft.localidadeConcluida
                        
                        // Prune if synced or if it no longer clashes and was manually confirmed (implicit).
                        if (isDraftSynced) {
                            resolvedIds.add(id)
                        } else if (!stillClashes && dbMatch != null && dbMatch.lastUpdated >= draft.lastUpdated) {
                            // If it no longer clashes and matches the DB timestamp, we can prune safely.
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
                        !dbHouses.any { db -> 
                            db.listOrder == inFlight.listOrder && 
                            db.data == inFlight.data &&
                            db.blockNumber == inFlight.blockNumber &&
                            db.streetName == inFlight.streetName &&
                            db.number == inFlight.number &&
                            db.sequence == inFlight.sequence &&
                            db.complement == inFlight.complement
                        }
                    }
                }
            }
        }

        // UI state reducer
        combine(
            latestHouses, _data, _agentName, _searchQuery, _isSupervisor, 
            _municipio, _bairro, _categoria, _zona, _ciclo, _tipo, _atividade,
            _selectedRgBairro, _rgYear, _currentWeekStart, allHousesFlow,
            _currentBlock, _currentBlockSequence, _currentStreet, isAppModeSelected,
            rgFilteredList, _selectedRgBlock, availableYears, activityOptions,
            weekRangeText, customActivities, settingsManager.easyMode, settingsManager.solarMode,
            settingsManager.maxOpenHouses, rgBlocks, weeklySummary, boletimList,
            bairrosList, rgBairros, _syncStatus, weeklySummaryTotals, isDayClosed, isWorkdayManualUnlock,
            weeklyObservations, _backupConfirmation, _isAdmin, _recentlyEditedHouseIds
        ) { args ->
            val h = args[0] as List<House>
            val d = args[1] as String
            val name = args[2] as String
            val q = args[3] as String
            val supervisor = args[4] as Boolean
            val rgB = args[12] as String
            val rgY = args[13] as String
            val weekStart = args[14] as Calendar
            val allH = args[15] as List<House>
            val recentlyEdited = args[41] as Map<Int, Long>
            
            val dayHouses = h.filter { it.data == d }
            val totals = calculateDashboardTotals(dayHouses)
            
            // Map houses with duplicate detection and silent-window flags
            val mappedDayHouses = dayHouses.filter { it.streetName.contains(q, true) || it.number.contains(q, true) }.map { house ->
                val isDuplicate = dayHouses.any { other -> 
                    other.id != house.id && 
                    other.listOrder != house.listOrder && // Avoid self-collision for drafts
                    other.streetName.equals(house.streetName, ignoreCase = true) &&
                    other.number.equals(house.number, ignoreCase = true) &&
                    other.sequence == house.sequence &&
                    other.complement == house.complement
                }
                
                // For in-flight houses (id 0), check if any are recently edited using ID 0
                val isRecentlyEdited = recentlyEdited.containsKey(house.id)
                
                HouseUiStateMapper.map(
                    house = house,
                    houseValidationUseCase = houseValidationUseCase,
                    isDuplicate = isDuplicate,
                    isRecentlyEdited = isRecentlyEdited
                )
            }
            
            // Weekly dates
            val weekDates = mutableListOf<String>()
            val cal = weekStart.clone() as Calendar
            for (i in 0..4) { 
                weekDates.add(dateFormatter.format(cal.time))
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            _uiState.update { current ->
                val dayHouses = h.filter { it.data == d }
                val workedCount = dayHouses.count { it.situation == Situation.NONE }
                val errorsCount = dayHouses.count { !houseValidationUseCase.isHouseValid(it, strict = true) }

                current.copy(
                    houses = mappedDayHouses,
                    dashboardTotals = totals,
                    data = d,
                    agentName = name,
                    isDayClosed = args[36] as Boolean,
                    isManualUnlock = args[37] as Boolean,
                    searchQuery = q,
                    isSupervisor = supervisor,
                    isAdmin = args[40] as Boolean,
                    municipality = args[5] as String,
                    neighborhood = args[6] as String,
                    category = args[7] as String,
                    zone = args[8] as String,
                    cycle = args[9] as String,
                    type = args[10] as Int,
                    activity = args[11] as Int,
                    rgBlocks = args[29] as List<BlockSegment>,
                    weeklySummary = args[30] as List<DaySummary>,
                    weeklyObservations = args[38] as List<House>,
                    boletimList = args[31] as List<BoletimSummary>,
                    bairrosList = args[32] as List<String>,
                    currentBlock = args[16] as String,
                    currentBlockSequence = args[17] as String,
                    currentStreet = args[18] as String,
                    isAppModeSelected = args[19] as Boolean?,
                    selectedRgBairro = rgB,
                    selectedRgBlock = args[21] as String,
                    rgFilteredList = args[20] as List<House>,
                    rgYear = rgY,
                    availableYears = args[22] as List<String>,
                    activityOptions = args[23] as List<String>,
                    weekRangeText = args[24] as String,
                    customActivities = args[25] as Set<String>,
                    isEasyMode = args[26] as Boolean,
                    isSolarMode = args[27] as Boolean,
                    maxOpenHouses = args[28] as Int,
                    rgBairros = args[33] as List<String>,
                    syncStatus = args[34] as SyncStatus,
                    weeklySummaryTotals = args[35] as WeeklySummaryTotals,
                    backupConfirmation = args[39] as BackupConfirmation?,
                    pendingCount = workedCount,
                    strictPendingCount = errorsCount
                )
            }
        }.launchIn(viewModelScope)
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
        mergedList.filter { it.data == date && (query.isBlank() || it.streetName.contains(query, ignoreCase = true) || it.blockNumber.contains(query, ignoreCase = true)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredHousesUiState: StateFlow<List<HouseUiState>> = combine(filteredHouses, _isDuplicateIds, recentlyEditedHouseIds) { list, dups, editing ->
        list.map { HouseUiStateMapper.map(it, houseValidationUseCase, dups.contains(it.id), editing.contains(it.id)) }
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

    val streetSuggestions: StateFlow<List<String>> = _bairro.flatMapLatest { currentB ->
        streetRepository.getStreetSuggestions(currentB)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())







    // Helper to filter houses by Year (Semester removed)









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
        viewModelScope.launch {
            delay(1000) // Small delay to let initial UI state flows settle
            try {
                houseManagementUseCase.migrateStreetNamesToFormat()
                houseManagementUseCase.migrateBairrosToUppercase()
                houseManagementUseCase.migrateDateFormats()
                repository.normalizeLocalDates()
                syncRepository.performDataCleanup()
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "Error migrating data", e)
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
                var uid: String = _remoteAgentUid.value ?: _currentUserUid.value
                
                if (name.isBlank()) {
                    val cached = settingsManager.cachedUser.first()
                    name = cached?.agentName ?: ""
                    uid = cached?.uid ?: ""
                }

                val allHouses = withContext(Dispatchers.IO) {
                    repository.getAllHousesOnce(name, uid)
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
                    repository.migrateLocalData(name, email, uid)
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
                        _bairro.value = lastHouse.bairro
                        _currentBlock.value = lastHouse.blockNumber
                        _currentBlockSequence.value = lastHouse.blockSequence
                        _currentStreet.value = lastHouse.streetName
                        _municipio.value = lastHouse.municipio
                        
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

        // Initialize RG Selection with Last Worked Bairro
        viewModelScope.launch {
            _bairro.filter { it.isNotBlank() }.first().let { workedBairro ->
                if (_selectedRgBairro.value.isBlank()) {
                    _selectedRgBairro.value = workedBairro
                }
            }
        }

        // Initialize Auto-Backup and observe frequency changes
        viewModelScope.launch {
            backupFrequency.collect { freq ->
                backupScheduler.scheduleBackup(freq)
            }
        }

        // 1. Initialize from Cache for immediate offline support
        viewModelScope.launch {
            settingsManager.cachedUser.first()?.let { cached ->
                if (_agentName.value.isBlank()) {
                    _agentName.value = cached.agentName ?: ""
                }
                if (_currentUserUid.value.isBlank()) {
                    _currentUserUid.value = cached.uid
                }
            }
        }

        // 2. Keep agentName and currentUserUid synced with AuthUser or RemoteAgent
        viewModelScope.launch {
            combine(authRepository.currentUserAsync, _remoteAgent) { user, remote ->
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
                                repository.migrateLocalData(name, user.email ?: "", uid)
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
            settingsManager.setAppModeSelected(true)
        }
    }

    fun updateSolarMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.setSolarMode(enabled)
        }
    }


    fun toggleDayLock() {
        viewModelScope.launch {
            try {
                val closed = isDayClosed.value
                val effectiveUid = _remoteAgentUid.value ?: _currentUserUid.value
                if (closed) {
                    if (dayManagementUseCase.canSafelyUnlock(_data.value)) {
                        dayManagementUseCase.unlockDay(_data.value, _agentName.value, effectiveUid)
                    } else {
                        _showHistoryUnlockConfirmation.value = true
                    }
                } else {
                    // Toggling locker when OPEN allows manual override of meta-limits
                    val manualUnlock = isWorkdayManualUnlock.value
                    val currentAgent = _agentName.value
                    val currentData = _data.value
                    
                    val activity = dayManagementUseCase.getDayActivity(currentData, currentAgent, effectiveUid)
                        ?: com.antigravity.healthagent.data.local.model.DayActivity(date = currentData, agentName = currentAgent, agentUid = effectiveUid)
                    
                    repository.updateDayActivity(activity.copy(isManualUnlock = !manualUnlock))
                    
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

            val workedCount = houses.value.count { it.data == _data.value && it.situation == Situation.NONE }
            if (workedCount < maxOpenHouses.value) {
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
        if (_isSyncing.value) return
        _isSyncing.value = true
        _syncStatus.value = SyncStatus(SyncStage.STARTING, 0.1f, "Iniciando sincronização...")
        
        val targetUid = _remoteAgentUid.value
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. Push local data to cloud
                _syncStatus.value = SyncStatus(SyncStage.UPLOADING, 0.3f, "Enviando dados para a nuvem...")
                val currentName = _agentName.value
                val houses = repository.getAllHousesOnce(currentName, targetUid ?: "")
                val activities = repository.getAllDayActivitiesOnce(currentName, targetUid ?: "")
                val pushResult = syncRepository.pushLocalDataToCloud(houses, activities, targetUid)
                
                if (pushResult.isSuccess) {
                    // 2. Pull cloud data to local
                    _syncStatus.value = SyncStatus(SyncStage.DOWNLOADING, 0.6f, "Baixando dados atualizados...")
                    val pullResult = syncRepository.pullCloudDataToLocal(targetUid)
                    if (pullResult.isSuccess) {
                        _syncStatus.value = SyncStatus(SyncStage.SUCCESS, 1.0f, "Sincronização concluída!")
                        _uiEvent.value = "Sincronização completa com sucesso."
                        delay(2000) // Show success for a bit
                    } else {
                        _syncStatus.value = SyncStatus(SyncStage.ERROR, 1.0f, "Erro ao baixar: ${pullResult.exceptionOrNull()?.message}")
                        _uiEvent.value = "Dados enviados, mas houve erro ao baixar: ${pullResult.exceptionOrNull()?.message}"
                        delay(3000)
                    }
                } else {
                    _syncStatus.value = SyncStatus(SyncStage.ERROR, 0.3f, "Falha ao enviar: ${pushResult.exceptionOrNull()?.message}")
                    _uiEvent.value = "Falha ao enviar dados: ${pushResult.exceptionOrNull()?.message}"
                    delay(3000)
                }
            } catch (e: Exception) {
                _syncStatus.value = SyncStatus(SyncStage.ERROR, 0f, "Erro: ${e.message}")
                _uiEvent.value = "Erro na sincronização: ${e.message}"
                delay(3000)
            } finally {
                _isSyncing.value = false
                _syncStatus.value = SyncStatus(SyncStage.IDLE)
            }
        }
    }

    fun pullDataFromCloud(targetUid: String? = null) {
        if (_isSyncing.value) return
        _isSyncing.value = true
        _syncStatus.value = SyncStatus(SyncStage.DOWNLOADING, 0.1f, "Iniciando download...")
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _syncStatus.value = SyncStatus(SyncStage.DOWNLOADING, 0.5f, "Baixando dados da nuvem...")
                val result = syncRepository.pullCloudDataToLocal(targetUid)
                if (result.isSuccess) {
                    _syncStatus.value = SyncStatus(SyncStage.SUCCESS, 1.0f, "Download concluído!")
                    _uiEvent.value = "Dados baixados com sucesso."
                    delay(2000)
                } else {
                    _syncStatus.value = SyncStatus(SyncStage.ERROR, 0.5f, "Falha ao baixar: ${result.exceptionOrNull()?.message}")
                    _uiEvent.value = "Falha ao baixar dados: ${result.exceptionOrNull()?.message}"
                    delay(3000)
                }
            } catch (e: Exception) {
                _syncStatus.value = SyncStatus(SyncStage.ERROR, 0f, "Erro: ${e.message}")
                _uiEvent.value = "Erro ao baixar: ${e.message}"
                delay(3000)
            } finally {
                _isSyncing.value = false
                _syncStatus.value = SyncStatus(SyncStage.IDLE)
            }
        }
    }

    private fun calculateAuditSummary(date: String): AuditSummary {
        val dayHouses = houses.value.filter { it.data == date }
        return AuditSummary(
            date = date,
            totalWorked = dayHouses.count { it.situation == Situation.NONE },
            totalTreated = dayHouses.count { (it.a1+it.a2+it.b+it.c+it.d1+it.d2+it.e+it.eliminados) > 0 || it.larvicida > 0.0 || it.comFoco },
            totalClosed = dayHouses.count { it.situation == Situation.F },
            totalRefused = dayHouses.count { it.situation == Situation.REC },
            totalAbsent = dayHouses.count { it.situation == Situation.A },
            totalVacant = dayHouses.count { it.situation == Situation.V },
            a1 = dayHouses.sumOf { it.a1 }, a2 = dayHouses.sumOf { it.a2 }, b = dayHouses.sumOf { it.b },
            c = dayHouses.sumOf { it.c }, d1 = dayHouses.sumOf { it.d1 }, d2 = dayHouses.sumOf { it.d2 },
            e = dayHouses.sumOf { it.e }, eliminados = dayHouses.sumOf { it.eliminados },
            totalLarvicide = dayHouses.sumOf { it.larvicida }
        )
    }

    fun confirmAndCloseDay(audit: AuditSummary) {
        viewModelScope.launch {
            try {
                dayManagementUseCase.closeDay(audit.date, _agentName.value, _remoteAgentUid.value)
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
                val next = dayManagementUseCase.getNextBusinessDay(_data.value, _agentName.value, _remoteAgentUid.value ?: _currentUserUid.value)
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

    fun addNewHouse() {
        val currentTime = System.currentTimeMillis()
        if (isAddingHouse || currentTime - lastAddClickTime < 300) return
        
        isAddingHouse = true
        lastAddClickTime = currentTime

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
                var prediction: HouseManagementUseCase.HousePrediction
                var initialBlock = _currentBlock.value
                var initialStreet = _currentStreet.value
                var initialBlockSeq = _currentBlockSequence.value
                
                if (isDayEmpty) {
                    // Find the absolute last house globally (presumably from previous workdays)
                    val lastGlobalHouse = latestHouses.maxByOrNull { it.listOrder }
                    
                    if (lastGlobalHouse != null) {
                        // Inherit Context
                        initialBlock = lastGlobalHouse.blockNumber
                        initialStreet = lastGlobalHouse.streetName
                        initialBlockSeq = lastGlobalHouse.blockSequence
                        
                        // Update UI State Context
                        _currentBlock.value = initialBlock
                        _currentStreet.value = initialStreet
                        _currentBlockSequence.value = initialBlockSeq
                        _bairro.value = lastGlobalHouse.bairro
                        _municipio.value = lastGlobalHouse.municipio
                        _agentName.value = lastGlobalHouse.agentName
                        
                        // Predict based on history
                        prediction = houseManagementUseCase.predictBasedOnHistory(latestHouses, lastGlobalHouse)
                    } else {
                        // No history at all, just blank prediction
                        prediction = HouseManagementUseCase.HousePrediction("", 0, 0, com.antigravity.healthagent.data.local.model.PropertyType.EMPTY, Situation.NONE)
                    }
                } else {
                    // Standard intra-day prediction using merged state
                    // Sanitize context inputs to ensure they match accurately against sanitized DB/Draft records
                    prediction = houseManagementUseCase.predictNextHouseValues(
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

                // VisitSegment Stabilization: Align perfectly with HouseManagementUseCase.recalculateVisitSegments
                // to prevent "shifting" which causes Natural Key collisions during bulk update.
                val dayHouses = currentDayHouses.sortedBy { it.listOrder }
                // --- ACTION BLOCKER ---
                // DEFERRED VALIDATION: Block new additions if there are unresolved conflicts in the current block/street.
                // CRITICAL: Perform a synchronous validation pass first to catch ANY "in-flight" or "un-debounced" typing 
                // that might have created a new conflict since the last auto-validation.
                validateCurrentDay(showDialog = false, strict = true) 
                
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
                    val s = h.streetName.trim().uppercase()
                    if (lastStreetName.isNotEmpty() && s != lastStreetName) {
                        predictedSegment++
                    }
                    lastStreetName = s
                }
                // If the new house starts a new street segment or continues the last one:
                if (lastStreetName.isNotEmpty() && newStreet.uppercase() != lastStreetName) {
                    predictedSegment++
                }

                var houseToInsert = House(
                    id = 0,
                    blockNumber = initialBlock.trim().uppercase(),
                    blockSequence = initialBlockSeq.trim().uppercase(),
                    streetName = initialStreet.trim().formatStreetName(),
                    number = prediction.number.trim().uppercase(),
                    sequence = prediction.sequence,
                    complement = prediction.complement,
                    propertyType = prediction.propertyType,
                    situation = prediction.situation,
                    tipo = _tipo.value,
                    ciclo = _ciclo.value,
                    municipio = _municipio.value.trim().uppercase(),
                    bairro = _bairro.value.trim().uppercase(),
                    agentName = _agentName.value.trim().uppercase(),
                    agentUid = _remoteAgentUid.value ?: _currentUserUid.value,
                    data = _data.value,
                    visitSegment = predictedSegment,
                    listOrder = maxOrder + 1
                )

                // Exhaustive Clash Detection: Check ALL fields in the unique index to prevent REPLACE-deletions
                var finalSequence = houseToInsert.sequence
                var finalComplement = houseToInsert.complement
                
                // Smarter Auto-Increment: If numbers match, prefer incrementing COMPLEMENT
                // CRITICAL: We check against 'latestHouses' (DB + Drafts) here to ensure 
                // the new house skip-increments over even unsaved duplicates.
                while (latestHouses.any { 
                    it.data == houseToInsert.data && 
                    it.agentUid == houseToInsert.agentUid &&
                    it.agentName.equals(houseToInsert.agentName, ignoreCase = true) &&
                    it.blockNumber.equals(houseToInsert.blockNumber, ignoreCase = true) &&
                    it.blockSequence.equals(houseToInsert.blockSequence, ignoreCase = true) &&
                    it.streetName.equals(houseToInsert.streetName, ignoreCase = true) &&
                    it.number.equals(houseToInsert.number, ignoreCase = true) && 
                    it.sequence == finalSequence &&
                    it.complement == finalComplement &&
                    it.bairro.equals(houseToInsert.bairro, ignoreCase = true) &&
                    it.visitSegment == houseToInsert.visitSegment
                }) {
                    if (houseToInsert.complement > 0 || houseToInsert.number.isNotBlank()) {
                        finalComplement++
                    } else {
                        finalSequence++
                    }
                }
                
                if (finalSequence != houseToInsert.sequence || finalComplement != houseToInsert.complement) {
                    houseToInsert = houseToInsert.copy(sequence = finalSequence, complement = finalComplement)
                }

                // Add to In-Flight temporarily to prevent duplicate prediction in rapid-fire clicks
                _housesInFlight.update { it + houseToInsert }
                
                val newId = houseManagementUseCase.insertHouse(houseToInsert, latestHouses)
                
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
                    (finalInFlightState.number != houseToInsert.number || 
                     finalInFlightState.sequence != houseToInsert.sequence || 
                     finalInFlightState.complement != houseToInsert.complement)) {
                     
                     houseManagementUseCase.updateHouse(
                        finalInFlightState.copy(id = newId.toInt()), 
                        refreshedLatestHouses
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
            val workedCount = houses.value.count { it.data == _data.value && it.situation == Situation.NONE }
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
                it.blockNumber.equals(house.blockNumber, ignoreCase = true) &&
                it.blockSequence.equals(house.blockSequence, ignoreCase = true) &&
                it.streetName.equals(house.streetName, ignoreCase = true) &&
                it.number.equals(house.number, ignoreCase = true) &&
                it.sequence == house.sequence &&
                it.complement == house.complement &&
                it.bairro.equals(house.bairro, ignoreCase = true) &&
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
                    baselineHouse.streetName != house.streetName || 
                    baselineHouse.blockNumber != house.blockNumber ||
                    baselineHouse.bairro != house.bairro
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
            performUpdateHouse(house)
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

    private fun performUpdateHouse(house: House, baselineHouse: House? = null) {
        viewModelScope.launch {
            try {
                // ... (identity calculation)
                val currentName = _agentName.value
                val currentUid = _remoteAgentUid.value ?: _currentUserUid.value
                val houseWithIdentity = house.copy(agentName = currentName, agentUid = currentUid)
                
                // Determine context from DB + current Drafts for the day
                val latestHouses = houses.value.map { _pendingUpdateDrafts.value[it.id] ?: it }
                
                // Use UseCase for context-aware updates (segment recalculation)
                val result = houseManagementUseCase.updateHouseWithContext(houseWithIdentity, latestHouses, baselineHouse)
                
                if (result.localizationChanged) {
                    _bairro.value = result.updatedHouse.bairro
                    _currentBlock.value = result.updatedHouse.blockNumber
                    _currentBlockSequence.value = result.updatedHouse.blockSequence
                    _currentStreet.value = result.updatedHouse.streetName
                    
                    // Also ensure subsequent houses get the current session identity if they are being updated
                    val subsequentWithIdentity = result.subsequentHouses.map { it.copy(agentName = currentName, agentUid = currentUid) }
                    houseManagementUseCase.updateHouses(subsequentWithIdentity + result.updatedHouse)
                } else {
                    houseManagementUseCase.updateHouse(result.updatedHouse, houses.value)
                }
                
                // If this house had a validation error highlight, remove it as it's being corrected
                if (_validationErrorHouseIds.value.contains(house.id)) {
                    _validationErrorHouseIds.value = _validationErrorHouseIds.value - house.id
                }
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "Error updating house", e)
                _uiEvent.value = "Falha ao atualizar imóvel: ${e.message}"
                soundManager.playWarning()
            }
        }
    }

    fun deleteHouse(house: House) {
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
                // Immediately clear from drafts to prevent "Ghost" house in UI
                _pendingUpdateDrafts.update { it - house.id }
                houseManagementUseCase.deleteHouse(house, houses.value)
                soundManager.playPop()
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "Error deleting house", e)
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
                    it.blockNumber.equals(house.blockNumber, ignoreCase = true) &&
                    it.blockSequence.equals(house.blockSequence, ignoreCase = true) &&
                    it.streetName.equals(house.streetName, ignoreCase = true) &&
                    it.number.equals(house.number, ignoreCase = true) &&
                    it.sequence == house.sequence &&
                    it.complement == house.complement &&
                    it.bairro.equals(house.bairro, ignoreCase = true) &&
                    it.visitSegment == house.visitSegment
                }

                if (clashing != null) {
                    // Restore as a "Red" draft if a locker exists now
                    _pendingUpdateDrafts.update { it + (house.id to house.copy(id = house.id)) }
                    _uiEvent.value = "Imóvel restaurado com conflito detectado."
                } else {
                    houseManagementUseCase.insertHouse(house.copy(id = 0), houses.value)
                }
                
                recentlyDeletedHouse = null 
                soundManager.playPop()
            }
        }
    }

    fun persistListOrder(reorderedList: List<House>) {
        viewModelScope.launch {
            val updatedList = reorderedList.mapIndexed { index, h -> h.copy(listOrder = index.toLong()) }
            val recalculated = houseManagementUseCase.recalculateVisitSegments(updatedList)
            houseManagementUseCase.updateHouses(recalculated)
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

        val existingWorked = houses.value.count { it.data == newDate && it.situation == Situation.NONE }

        if (existingWorked >= maxOpenHouses.value && maxOpenHouses.value > 0 && house.situation == Situation.NONE) {
            soundManager.playWarning()
            _uiEvent.value = "Impossível mover: Meta Diária do dia de destino atingida!"
            return
        }
        performMoveHouse(house, newDate)
    }

    private fun performMoveHouse(house: House, newDate: String) {
        viewModelScope.launch { 
            val updatedHouse = house.copy(
                data = newDate,
                agentName = _agentName.value,
                agentUid = _remoteAgentUid.value ?: _currentUserUid.value
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
                it.blockNumber.equals(updatedHouse.blockNumber, ignoreCase = true) &&
                it.blockSequence.equals(updatedHouse.blockSequence, ignoreCase = true) &&
                it.streetName.equals(updatedHouse.streetName, ignoreCase = true) &&
                it.number.equals(updatedHouse.number, ignoreCase = true) &&
                it.sequence == updatedHouse.sequence &&
                it.complement == updatedHouse.complement &&
                it.bairro.equals(updatedHouse.bairro, ignoreCase = true) &&
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
                    val housesToMove = repository.getHousesByDateAndAgent(normalizedOldDate, currentAgent, currentUid)
                    
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
                    val oldActivity = repository.getDayActivity(normalizedOldDate, currentAgent, currentUid)
                    if (oldActivity != null) {
                        // Check if an activity already exists for the new date
                        val existingNewActivity = repository.getDayActivity(normalizedNewDate, currentAgent, currentUid)
                        if (existingNewActivity == null) {
                            // Safe to move the status and observations to the new date
                            repository.updateDayActivity(oldActivity.copy(date = normalizedNewDate))
                        }
                        
                        // CRITICAL: Use deleteProduction to ensure cloud tombstones are recorded
                        // for the old date's activity metadata, preventing ghosts.
                        repository.deleteProduction(normalizedOldDate, currentAgent, currentUid)
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
        _data.value = d
        _ciclo.value = ci.uppercase()
        _atividade.value = a
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

    // --- RG Actions ---
    fun selectRgYear(y: String) { _rgYear.value = y }
    // fun selectRgSemester(s: Int) { _rgSemester.value = s } // Removed
    fun selectRgBairro(b: String) { _selectedRgBairro.value = b; _selectedRgBlock.value = "" }
    fun selectRgBlock(q: String) { _selectedRgBlock.value = q }

    // --- Semanal Actions ---
    fun previousWeek() {
        _currentWeekStart.value = (_currentWeekStart.value.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -7) }
    }
    fun nextWeek() {
        _currentWeekStart.value = (_currentWeekStart.value.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 7) }
    }

    fun navigateToDate(date: String) {
        _data.value = date
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
                
                repository.runInTransaction {
                    val currentAgent = _agentName.value
                    val currentUid = _remoteAgentUid.value ?: _currentUserUid.value
                    
                    // Normalize all activity dates for lookup consistency
                    val allActivities = repository.getAllDayActivitiesOnce(currentAgent, currentUid)
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
                    
                    // Update the status first
                    val updatedActivity = if (existing != null) {
                        if (existing.agentUid != currentUid) {
                            repository.deleteDayActivity(existing.date, existing.agentName, existing.agentUid)
                        }
                        existing.copy(date = date, status = status, agentUid = currentUid)
                    } else {
                        DayActivity(date = date, status = status, agentName = currentAgent, agentUid = currentUid)
                    }
                    repository.updateDayActivity(updatedActivity)

                    if (wasWorking != isWorking) {
                        // Ripple Effect: Shift production dates
                        val updatedStatusMap = allActivities.toMutableMap()
                        updatedStatusMap[date] = updatedActivity
                        
                        _uiEvent.value = "Calculando efeito cascata (movimentação de imóveis)..."
                        // Analyze all production for this agent/remote agent
                        val allAgentHouses = repository.getAllHousesOnce(currentAgent, currentUid)
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
                        val next = dayManagementUseCase.getNextBusinessDay(date, _agentName.value)
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
    suspend fun exportSemanalPdf(context: Context): File {
        return withContext(Dispatchers.IO) {
            clearOldPdfs(context, "Semanal_")
            val summary = weeklySummary.value
            val weekDates = summary.map { it.date }
            // Important: Use allHousesFlow (filtered) not 'houses' (possibly global) 
            // and filter by the current identity to be 100% sure.
            val currentAgent = _agentName.value
            val filteredHouses = houses.value.filter { it.agentName.uppercase() == currentAgent.uppercase() }
            val activities = summary.associate { it.date to it.status }
            SemanalPdfGenerator.generatePdf(context, weekDates, filteredHouses, activities, currentAgent)
        }
    }

    suspend fun exportWeeklyBatchPdf(context: Context): File {
        return withContext(Dispatchers.IO) {
            clearOldPdfs(context, "Produção_")
            clearOldPdfs(context, "Boletim_")
            val dates = currentWeekDates.value
            val currentAgent = _agentName.value
            val filteredHouses = houses.value.filter { it.agentName.uppercase() == currentAgent.uppercase() && dates.contains(it.data) }
            
            // Group by Date for the generator
            val weeklyData = filteredHouses.groupBy { it.data }

            // Get activities for the week
            val activities = weeklySummary.value.associate { it.date to it.status }
            
            BoletimPdfGenerator.generateWeeklyBatchPdf(context, weeklyData, currentAgent, activities, dates)
        }
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
                repository.deleteProduction(date, currentAgent, currentUid)
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
                val dayHouses = repository.getAllHousesOnce(currentAgent, currentUid).filter { it.data == date && it.agentName == currentAgent }
                val dayActivities = repository.getAllDayActivitiesOnce(currentAgent, currentUid).filter { it.date == date && it.agentName == currentAgent }
                val backupData = BackupData(dayHouses, dayActivities)

                // Generate Filename
                val safeAgentName = currentAgent.trim().replace(" ", "_").ifBlank { "Agente" }
                val fileName = "Producao_${safeAgentName}_${date.replace("/", "-")}.json"

                // Save to Cache Dir
                val backupDir = File(context.cacheDir, "exports")
                if (backupDir.exists()) backupDir.deleteRecursively()
                backupDir.mkdirs()

                val file = File(backupDir, fileName)
                BackupManager().exportToFile(file, backupData)

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
            val activity = dayManagementUseCase.getDayActivity(d, _agentName.value, effectiveUid)
            if (activity?.isClosed == true) {
                dayManagementUseCase.unlockDay(d, _agentName.value, effectiveUid)
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
            dayManagementUseCase.unlockDay(_data.value, _agentName.value, effectiveUid)
            _showHistoryUnlockConfirmation.value = false
        }
    }

    fun backupData(context: Context, uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val h = repository.getAllHousesSnapshot()
                val a = repository.getAllDayActivitiesSnapshot()
                BackupManager().exportData(context, uri, BackupData(h, a))
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
                val h = repository.getAllHousesSnapshot()
                val a = repository.getAllDayActivitiesSnapshot()
                val backupData = BackupData(h, a)
                
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
                BackupManager().exportToFile(file, backupData)
                
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

    fun restoreData(context: Context, uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val backupData = BackupManager().importData(context, uri)
                val currentAgent = _agentName.value.trim().uppercase()
                
                // Detection: Find the agent name in the backup
                val backupAgent = (backupData.houses.map { it.agentName } + backupData.dayActivities.map { it.agentName })
                    .filter { it.isNotBlank() }
                    .distinct()
                    .firstOrNull()?.trim()?.uppercase() ?: ""
                
                // Mismatch Check
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

                performRestore(context, uri, backupData)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiEvent.value = "Erro ao restaurar backup: ${e.message}"
                    soundManager.playWarning()
                }
            }
        }
    }

    private suspend fun performRestore(context: Context, uri: android.net.Uri, backupData: BackupData) {
        try {
            var targetAgent = _agentName.value.trim()
            
            // Smart Agent Adoption: If current is blank, try to find one in the backup
            val agentsInBackup = (backupData.houses.map { it.agentName } + backupData.dayActivities.map { it.agentName })
                .filter { it.isNotBlank() }
                .distinct()
            
            if (targetAgent.isBlank() && agentsInBackup.isNotEmpty()) {
                targetAgent = agentsInBackup.first()
                withContext(Dispatchers.Main) {
                    _agentName.value = targetAgent
                }
            }

            // Ensure all restored data belongs to the target agent context
            val sanitizedHouses = backupData.houses.map { 
                it.copy(id = 0, agentName = targetAgent) 
            }
            val sanitizedActivities = backupData.dayActivities.map { 
                it.copy(agentName = targetAgent) 
            }

            if (targetAgent.isNotBlank()) {
                 repository.restoreAgentData(targetAgent, sanitizedHouses, sanitizedActivities)
            } else {
                 repository.replaceAllHouses(sanitizedHouses)
                 repository.replaceAllDayActivities(sanitizedActivities)
            }
            
            val pushResult = syncRepository.pushLocalDataToCloud(
                houses = sanitizedHouses,
                activities = sanitizedActivities,
                targetUid = _remoteAgentUid.value,
                shouldReplace = true
            )

            withContext(Dispatchers.Main) {
                if (pushResult.isSuccess) {
                    _uiEvent.value = "Backup restaurado com sucesso (Local e Nuvem)!"
                } else {
                    _uiEvent.value = "Backup restaurado localmente, mas falhou ao atualizar nuvem: ${pushResult.exceptionOrNull()?.message}"
                }
                soundManager.playPop()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                _uiEvent.value = "Erro na finalização do restauro: ${e.message}"
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
                val backupData = BackupManager().importData(context, uri)
                if (isFullRestore) {
                    performRestore(context, uri, backupData)
                } else {
                    performImportDayData(context, uri, backupData)
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

    fun clearAllData() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _isSyncing.value = true
                _uiEvent.value = "Iniciando limpeza completa..."
                
                val user = authRepository.currentUserAsync.first()
                val isAdmin = user?.isAdmin == true
                
                // 1. Clear Local Data
                repository.clearAllData()
                
                // 2. If Admin, Clear ALL Cloud Data
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
                    _data.value = dateFormatter.format(Date())
                    _agentName.value = ""
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiEvent.value = "Erro ao apagar dados: ${e.message}"
                }
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun cleanupHistoricalData(beforeDate: String) {
        if (_isSyncing.value) return
        _isSyncing.value = true
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
                        val message = if (isAdmin) "Histórico global (todos os agentes) anterior a $beforeDate removido." 
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
                _isSyncing.value = false
            }
        }
    }

    fun importDayData(context: Context, uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val backupData = BackupManager().importData(context, uri)
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
                       isFullRestore = false
                   )
                   return@launch
                }
                
                performImportDayData(context, uri, backupData)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiEvent.value = "Erro ao importar: ${e.message}"
                    soundManager.playWarning()
                }
            }
        }
    }

    private suspend fun performImportDayData(context: Context, uri: android.net.Uri, backupData: BackupData) {
        try {
            var targetAgent = _agentName.value.trim()
            val agentsInBackup = (backupData.houses.map { it.agentName } + backupData.dayActivities.map { it.agentName })
                .filter { it.isNotBlank() }
                .distinct()
            
            if (targetAgent.isBlank() && agentsInBackup.isNotEmpty()) {
                targetAgent = agentsInBackup.first()
                withContext(Dispatchers.Main) {
                    _agentName.value = targetAgent
                }
            }

            val currentHouses = houses.value
            val existingHouseKeys = currentHouses.map { it.generateNaturalKey() }.toSet()

            var importedCount = 0
            var skippedCount = 0

            // Multi-tenant safe re-assignment to targetAgent
            val targetUid = _remoteAgentUid.value ?: _currentUserUid.value
            val targetDate = _data.value // RESTRICTION: Only import for current selected date

            // 1. Process Houses (Date Filtered)
            backupData.houses.filter { it.data == targetDate }.forEach { house ->
                val sanitizedHouse = house.copy(id = 0, agentName = targetAgent, agentUid = targetUid)
                val key = sanitizedHouse.generateNaturalKey()
                
                if (!existingHouseKeys.contains(key)) {
                    repository.insertHouse(sanitizedHouse)
                    importedCount++
                } else {
                    skippedCount++
                }
            }
            
            // 2. Process Activities (Date Filtered)
            backupData.dayActivities.filter { it.date == targetDate }.forEach { 
                repository.updateDayActivity(it.copy(agentName = targetAgent, agentUid = targetUid)) 
            }
            
            withContext(Dispatchers.Main) {
                val message = if (skippedCount > 0) {
                    "Importado: $importedCount, Ignorado (duplicado): $skippedCount"
                } else {
                    "Dados importados com sucesso ($importedCount imóveis)"
                }
                _uiEvent.value = message
                soundManager.playPop()
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
        viewModelScope.launch {
            settingsManager.setEasyMode(enabled)
        }
    }

    fun updateThemeMode(mode: String) {
        viewModelScope.launch { settingsManager.setThemeMode(mode) }
    }

    fun updateThemeColor(color: String) {
        viewModelScope.launch { settingsManager.setThemeColor(color) }
    }


    fun updateBackupFrequency(frequency: com.antigravity.healthagent.data.backup.BackupFrequency) {
        viewModelScope.launch {
            settingsManager.setBackupFrequency(frequency)
            backupScheduler.scheduleBackup(frequency)
        }
    }

    fun playPreview(soundId: String) {
        soundManager.playSound(soundId)
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

    fun testCelebration() { soundManager.playCelebration() }

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
            syncDataUseCase.pullData(force = true)
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
            totalHouses = dayHouses.count { it.situation != Situation.EMPTY },
            a1 = dayHouses.sumOf { it.a1 }, a2 = dayHouses.sumOf { it.a2 },
            b = dayHouses.sumOf { it.b }, c = dayHouses.sumOf { it.c },
            d1 = dayHouses.sumOf { it.d1 }, d2 = dayHouses.sumOf { it.d2 },
            e = dayHouses.sumOf { it.e }, eliminados = dayHouses.sumOf { it.eliminados },
            larvicida = dayHouses.sumOf { it.larvicida },
            totalFocos = dayHouses.count { it.comFoco },
            totalRegisteredHouses = dayHouses.size,
            worked = dayHouses.count { it.situation == Situation.NONE },
            recused = dayHouses.count { it.situation == Situation.REC },
            absent = dayHouses.count { it.situation == Situation.A },
            closed = dayHouses.count { it.situation == Situation.F },
            vacant = dayHouses.count { it.situation == Situation.V }
        )
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
}
