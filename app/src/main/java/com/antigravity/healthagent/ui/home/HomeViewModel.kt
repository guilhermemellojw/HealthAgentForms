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
import com.antigravity.healthagent.domain.repository.SyncRepository
import com.antigravity.healthagent.domain.repository.AgentData
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
    private val houseValidationUseCase: HouseValidationUseCase,
    val dayManagementUseCase: DayManagementUseCase,
    private val houseManagementUseCase: HouseManagementUseCase,
    private val soundManager: SoundManager,
    private val settingsManager: SettingsManager,
    private val backupScheduler: BackupScheduler,
    private val streetRepository: StreetRepository,
    private val authRepository: com.antigravity.healthagent.domain.repository.AuthRepository,
    private val syncDataUseCase: com.antigravity.healthagent.domain.usecase.SyncDataUseCase,
    private val getWeeklySummaryUseCase: com.antigravity.healthagent.domain.usecase.GetWeeklySummaryUseCase,
    private val getBoletimSummaryUseCase: com.antigravity.healthagent.domain.usecase.GetBoletimSummaryUseCase,
    private val getRGBlocksUseCase: com.antigravity.healthagent.domain.usecase.GetRGBlocksUseCase,
    private val cleanupHistoricalDataUseCase: com.antigravity.healthagent.domain.usecase.CleanupHistoricalDataUseCase
) : ViewModel() {

    // --- State Definitions ---
    private val dateFormatter = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
    private val displayDateFormatter: SimpleDateFormat get() = SimpleDateFormat("dd/MM", Locale.US)
    
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _data = MutableStateFlow(dateFormatter.format(Date()))
    val data: StateFlow<String> = _data.asStateFlow()

    private val _agentName = MutableStateFlow("")
    val agentName: StateFlow<String> = _agentName.asStateFlow()
    
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

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

    private val _currentBlock = MutableStateFlow("")
    val currentBlock: StateFlow<String> = _currentBlock.asStateFlow()

    private val _currentBlockSequence = MutableStateFlow("")
    val currentBlockSequence: StateFlow<String> = _currentBlockSequence.asStateFlow()

    private val _currentStreet = MutableStateFlow("")
    val currentStreet: StateFlow<String> = _currentStreet.asStateFlow()

    private val _agentNames = MutableStateFlow<List<String>>(emptyList())

    private val _bairrosList = MutableStateFlow<List<String>>(emptyList())
    val bairrosList: StateFlow<List<String>> = _bairrosList.asStateFlow()

    private val _currentWeekStart = MutableStateFlow(Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
    })

    private val _remoteAgent = MutableStateFlow<String?>(null)
    private var _localAgentNameBackup: String? = null
    val remoteAgent: StateFlow<String?> = _remoteAgent.asStateFlow()

    private val _remoteAgentUid = MutableStateFlow<String?>(null)
    val remoteAgentUid: StateFlow<String?> = _remoteAgentUid.asStateFlow()

    private val _uiEvent = MutableStateFlow<String?>(null)
    val uiEvent: StateFlow<String?> = _uiEvent.asStateFlow()

    private val _navigationTab = MutableStateFlow<Int?>(null)
    val navigationTab: StateFlow<Int?> = _navigationTab.asStateFlow()

    private val _showGoalReached = MutableStateFlow(false)
    val showGoalReached: StateFlow<Boolean> = _showGoalReached.asStateFlow()

    private val _validationErrorHouseIds = MutableStateFlow<Set<Int>>(emptySet())
    val validationErrorHouseIds: StateFlow<Set<Int>> = _validationErrorHouseIds.asStateFlow()

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

    private val _isDuplicateIds = MutableStateFlow<Set<Int>>(emptySet())
    val isDuplicateIds: StateFlow<Set<Int>> = _isDuplicateIds.asStateFlow()

    private var validationJob: kotlinx.coroutines.Job? = null

    private val _situationLimitConfirmation = MutableStateFlow<House?>(null)
    val situationLimitConfirmation: StateFlow<House?> = _situationLimitConfirmation.asStateFlow()

    private val _showHistoryUnlockConfirmation = MutableStateFlow(false)
    val showHistoryUnlockConfirmation: StateFlow<Boolean> = _showHistoryUnlockConfirmation.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncStatus = MutableStateFlow(SyncStatus())
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _moveConfirmationData = MutableStateFlow<Pair<House, String>?>(null)
    val moveConfirmationData: StateFlow<Pair<House, String>?> = _moveConfirmationData.asStateFlow()

    private val _duplicateHouseConfirmation = MutableStateFlow<House?>(null)
    val duplicateHouseConfirmation: StateFlow<House?> = _duplicateHouseConfirmation.asStateFlow()

    private val _backupConfirmation = MutableStateFlow<BackupConfirmation?>(null)
    val backupConfirmation: StateFlow<BackupConfirmation?> = _backupConfirmation.asStateFlow()

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
        viewModelScope.launch {
            settingsManager.setRemoteAgentUid(agent?.uid)
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
    }.flatMapLatest { (name, remoteUid, currentUid) -> 
        val effectiveUid = remoteUid ?: currentUid
        repository.getAllHouses(name, effectiveUid) 
    }

    // Global list for RG view (all agents)
    private val globalHousesFlow = repository.getAllHousesSnapshotFlow()


    val isDayClosed: StateFlow<Boolean> = combine(_data, _agentName, _remoteAgentUid, _currentUserUid) { date, name, remoteUid, currentUid ->
        val effectiveUid = remoteUid ?: currentUid
        Triple(date, name, effectiveUid)
    }.flatMapLatest { (date, name, uid) ->
        repository.getDayActivityFlow(date, name, uid).map { it?.isClosed == true }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val houses: StateFlow<List<House>> = allHousesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
        combine(houses, activitiesFlow) { all: List<House>, activities: List<DayActivity> ->
            dates.map { date ->
                val dayHouses = all.filter { it.data == date }
                val activity = activities.find { it.date == date }
                val openHousesCount = dayHouses.count { it.situation == Situation.NONE }
                DaySummary(date, openHousesCount, activity?.status ?: "")
            }
        }
    }
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val weeklySummaryTotals: StateFlow<WeeklySummaryTotals> = combine(houses, currentWeekDates) { list, dates ->
        val weekHouses = list.filter { dates.contains(it.data) }
        WeeklySummaryTotals(
            totalHouses = weekHouses.count { it.situation == Situation.NONE },
            totalTratados = weekHouses.count { house ->
                house.a1 > 0 || house.a2 > 0 || house.b > 0 || house.c > 0 ||
                house.d1 > 0 || house.d2 > 0 || house.e > 0 || house.eliminados > 0 || house.larvicida > 0
            },
            totalFoci = weekHouses.count { it.comFoco },
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

    val availableYears: StateFlow<List<String>> = allHousesFlow.map { all ->
        all.mapNotNull { 
            try { 
                val d = dateFormatter.parse(it.data)
                if(d != null) {
                    val c = Calendar.getInstance().apply { time = d }
                    c.get(Calendar.YEAR).toString()
                } else null 
            } catch (e: Exception) { null }
        }.distinct().sortedDescending()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf(Calendar.getInstance().get(Calendar.YEAR).toString()))

    val rgBlocks: StateFlow<List<BlockSegment>> = combine(globalHousesFlow, _selectedRgBairro, _rgYear) { h, b, y ->
        getRGBlocksUseCase(h, b, y)
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

    val rgBairros: StateFlow<List<String>> = combine(globalHousesFlow, _rgYear) { all, year ->
        filterByYear(all, year)
            .map { it.bairro.trim().formatStreetName() }
            .distinct()
            .sorted()
    }.flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            loadDynamicConfig()
        }

        // UI state reducer
        combine(
            houses, _data, _agentName, _searchQuery, _isSupervisor, 
            _municipio, _bairro, _categoria, _zona, _ciclo, _tipo, _atividade,
            _selectedRgBairro, _rgYear, _currentWeekStart, allHousesFlow,
            _currentBlock, _currentBlockSequence, _currentStreet, isAppModeSelected,
            rgFilteredList, _selectedRgBlock, availableYears, activityOptions,
            weekRangeText, customActivities, settingsManager.easyMode, settingsManager.solarMode,
            settingsManager.maxOpenHouses, rgBlocks, weeklySummary, boletimList,
            bairrosList, rgBairros, _syncStatus, weeklySummaryTotals, isDayClosed,
            weeklyObservations, _backupConfirmation
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
            
            val dayHouses = h.filter { it.data == d }
            val totals = calculateDashboardTotals(dayHouses)
            
            // Weekly dates
            val weekDates = mutableListOf<String>()
            val cal = weekStart.clone() as Calendar
            for (i in 0..4) { 
                weekDates.add(dateFormatter.format(cal.time))
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            _uiState.update { current ->
                current.copy(
                    houses = dayHouses.filter { it.streetName.contains(q, true) || it.number.contains(q, true) }.map { HouseUiStateMapper.map(it, houseValidationUseCase) },
                    dashboardTotals = totals,
                    data = d,
                    agentName = name,
                    isDayClosed = args[36] as Boolean,
                    searchQuery = q,
                    isSupervisor = supervisor,
                    municipality = args[5] as String,
                    neighborhood = args[6] as String,
                    category = args[7] as String,
                    zone = args[8] as String,
                    cycle = args[9] as String,
                    type = args[10] as Int,
                    activity = args[11] as Int,
                    rgBlocks = args[29] as List<BlockSegment>,
                    weeklySummary = args[30] as List<DaySummary>,
                    weeklyObservations = args[37] as List<House>,
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
                    backupConfirmation = args[38] as BackupConfirmation?
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
            val bairrosResult = syncRepository.fetchBairros()
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

    val filteredHouses: StateFlow<List<House>> = combine(houses, _searchQuery, _data) { list, query, date ->
        list.filter { it.data == date && (query.isBlank() || it.streetName.contains(query, ignoreCase = true) || it.blockNumber.contains(query, ignoreCase = true)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredHousesUiState: StateFlow<List<HouseUiState>> = combine(filteredHouses, _isDuplicateIds) { list, dups ->
        list.map { HouseUiStateMapper.map(it, houseValidationUseCase, dups.contains(it.id)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingHousesCount: StateFlow<Int> = filteredHouses.map { list ->
        list.count { it.situation == Situation.NONE }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val strictPendingHousesCount: StateFlow<Int> = filteredHouses.map { list ->
        list.count { !houseValidationUseCase.isHouseValid(it, strict = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun getInvalidFields(house: House): Set<String> {
        return houseValidationUseCase.getInvalidFields(house, strict = true).toSet()
    }

    val dashboardTotals: StateFlow<DashboardTotals> = combine(houses, _data) { list, date ->
        val dayHouses = list.filter { it.data == date }
        DashboardTotals(
            totalHouses = dayHouses.count { it.situation == Situation.NONE },
            a1 = dayHouses.sumOf { it.a1 }, a2 = dayHouses.sumOf { it.a2 },
            b = dayHouses.sumOf { it.b }, c = dayHouses.sumOf { it.c },
            d1 = dayHouses.sumOf { it.d1 }, d2 = dayHouses.sumOf { it.d2 },
            e = dayHouses.sumOf { it.e }, eliminados = dayHouses.sumOf { it.eliminados },
            larvicida = dayHouses.sumOf { it.larvicida },
            totalFocos = dayHouses.count { it.comFoco },
            totalRegisteredHouses = dayHouses.size
        )
    }.flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardTotals())

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



    fun calculateRGBlocks(houses: List<House>, bairro: String, year: String): List<BlockSegment> {
        return getRGBlocksUseCase(houses, bairro, year)
    }

    // --- RG State ---
    val rgYear: StateFlow<String> = _rgYear.asStateFlow()

    // Semester filter removed as per user request
    // private val _rgSemester = MutableStateFlow(if (Calendar.getInstance().get(Calendar.MONTH) < 6) 1 else 2)
    // val rgSemester: StateFlow<Int> = _rgSemester.asStateFlow()

    val selectedRgBairro: StateFlow<String> = _selectedRgBairro.asStateFlow()




    // Helper to filter houses by Year (Semester removed)









    // --- Actions ---
    fun validateCurrentDay(showDialog: Boolean): Boolean {
        val result = houseValidationUseCase.validateCurrentDay(_data.value, houses.value, strict = true)
        _uiState.update { it.copy(validationErrorHouseIds = result.errorHouseIds) }
        _validationErrorDetails.value = result.errorDetails
        _isDuplicateIds.value = result.errorDetails.filter { it.isDuplicate }.map { it.houseId }.toSet()
        
        if (!result.isValid) {
            if (showDialog) {
                // We'll use a specific dialog for detailed errors, but for generic snackbar:
                _uiEvent.value = result.dialogMessage
                soundManager.playWarning()
            }
            return false
        }
        return true
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
                houseManagementUseCase.migrateBairrosToTitleCase()
                houseManagementUseCase.migrateDateFormats()
                syncRepository.performDataCleanup()
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "Error migrating data", e)
            }
            
            // 1. Fetch Agent Names (with 3s timeout)
            val agentNamesResult = withTimeoutOrNull(3000) { syncRepository.fetchAgentNames() }
            if (agentNamesResult != null && agentNamesResult.isSuccess) {
                _agentNames.value = agentNamesResult.getOrNull() ?: com.antigravity.healthagent.utils.AppConstants.AGENT_NAMES
            } else if (agentNamesResult == null) {
                // Timeout happened, use default
                _agentNames.value = com.antigravity.healthagent.utils.AppConstants.AGENT_NAMES
                android.util.Log.w("HomeViewModel", "Agent names fetch timed out, using defaults")
            }

            // 2. Fetch Bairros (with 3s timeout)
            val bairrosResult = withTimeoutOrNull(3000) { syncRepository.fetchBairros() }
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
            // Check directly from repo to avoid StateFlow initial value race conditions
            val allHouses = withContext(Dispatchers.IO) {
                repository.getAllHousesOnce(_agentName.value, _remoteAgentUid.value ?: _currentUserUid.value)
            }
            
            if (allHouses.isNotEmpty()) {
                val lastWorkDayHouse = allHouses.maxByOrNull { house ->
                    try {
                        dateFormatter.parse(house.data)?.time ?: 0L
                    } catch (e: Exception) {
                        0L
                    }
                }
                if (lastWorkDayHouse != null) {
                    _data.value = lastWorkDayHouse.data
                    _agentName.value = lastWorkDayHouse.agentName
                }
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
                    _bairro.value = lastHouse.bairro
                    _currentBlock.value = lastHouse.blockNumber
                    _currentBlockSequence.value = lastHouse.blockSequence
                    _currentStreet.value = lastHouse.streetName
                    _agentName.value = lastHouse.agentName
                    _municipio.value = lastHouse.municipio
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

        // Keep agentName and currentUserUid synced with AuthUser or RemoteAgent
        viewModelScope.launch {
            combine(authRepository.currentUserAsync, _remoteAgent) { user, remote ->
                val name = remote ?: user?.agentName?.takeIf { it.isNotBlank() } ?: user?.email?.takeIf { it.isNotBlank() } ?: "AGENTE"
                Pair(name, user)
            }.collect { (name, user) ->
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
                
                // Keep currentUserUid updated
                user?.uid?.let { uid ->
                    if (_currentUserUid.value != uid) {
                        _currentUserUid.value = uid
                        // Migrate local data to the new UID safely in background
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
                val currentUid = _remoteAgentUid.value
                if (closed) {
                    if (dayManagementUseCase.canSafelyUnlock(_data.value)) {
                        dayManagementUseCase.unlockDay(_data.value, _agentName.value, currentUid)
                    } else {
                        _showHistoryUnlockConfirmation.value = true
                    }
                } else {
                    // Lock icon only for opening. Closing now handled by startDayClosingFlow (Audit).
                    // We show a message to the user for clarity.
                    _uiEvent.value = "Para fechar o dia, realize a auditoria."
                }
            } catch (e: Exception) {
                _uiEvent.value = "Erro ao alterar estado do dia: ${e.message}"
                soundManager.playWarning()
            }
        }
    }

    fun startDayClosingFlow() {
        viewModelScope.launch {
            val workedCount = houses.value.filter { it.data == _data.value && it.situation == Situation.NONE }.size
            if (workedCount < maxOpenHouses.value) {
                _uiEvent.value = "Meta não atingida! (Trabalhados: $workedCount / ${maxOpenHouses.value})"
                return@launch
            }

            if (validateCurrentDay(showDialog = true)) {
                val summary = calculateAuditSummary(_data.value)
                _showClosingAudit.value = summary
            }
        }
    }

    fun syncDataToCloud() {
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
            totalTreated = dayHouses.count { (it.a1+it.a2+it.b+it.c+it.d1+it.d2+it.e+it.eliminados) > 0 || it.larvicida > 0.0 },
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
                _showGoalReached.value = true
                
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
                val next = dayManagementUseCase.getNextBusinessDay(_data.value, _agentName.value)
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

        viewModelScope.launch {
            try {
                // Pre-check for open limit inside the safe scope
                val currentOpen = houses.value.count { it.data == _data.value && (it.situation == Situation.NONE || it.situation == Situation.EMPTY) }
                if (currentOpen >= maxOpenHouses.value && maxOpenHouses.value > 0) {
                    if (validateCurrentDay(showDialog = true)) {
                        startDayClosingFlow()
                    }
                    return@launch
                }

                // Check if the day is empty (no houses at all for this date)
                val isDayEmpty = houses.value.none { it.data == _data.value }
                var prediction: HouseManagementUseCase.HousePrediction
                var initialBlock = _currentBlock.value
                var initialStreet = _currentStreet.value
                var initialBlockSeq = _currentBlockSequence.value
                
                if (isDayEmpty) {
                    // Find the absolute last house globally (presumably from previous workdays)
                    val lastGlobalHouse = houses.value.maxByOrNull { it.listOrder }
                    
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
                        prediction = houseManagementUseCase.predictBasedOnHistory(houses.value, lastGlobalHouse)
                    } else {
                        // No history at all, just blank prediction
                        prediction = HouseManagementUseCase.HousePrediction("", null, null, com.antigravity.healthagent.data.local.model.PropertyType.EMPTY, Situation.NONE)
                    }
                } else {
                    // Standard intra-day prediction
                    prediction = houseManagementUseCase.predictNextHouseValues(
                        houses.value, 
                        _data.value,
                        _currentBlock.value,
                        _currentStreet.value
                    )
                }

                val maxOrder = houses.value.maxOfOrNull { it.listOrder } ?: 0L
                val currentDayHouses = houses.value.filter { it.data == _data.value }.sortedBy { it.listOrder }
                val lastHouse = currentDayHouses.lastOrNull()
                val newStreet = initialStreet.trim().uppercase()
                val newSegment = if (lastHouse == null) 0 
                                 else if (newStreet == lastHouse.streetName.uppercase()) lastHouse.visitSegment 
                                 else lastHouse.visitSegment + 1

                val houseToInsert = House(
                    id = 0,
                    blockNumber = initialBlock.trim().uppercase(),
                    blockSequence = initialBlockSeq.trim().uppercase(),
                    streetName = initialStreet.trim().uppercase(),
                    number = prediction.number.trim().uppercase(),
                    sequence = prediction.sequence,
                    complement = prediction.complement,
                    propertyType = prediction.propertyType,
                    situation = prediction.situation,
                    tipo = _tipo.value,
                    ciclo = _ciclo.value,
                    municipio = _municipio.value.trim().uppercase(),
                    bairro = _bairro.value.trim().formatStreetName(),
                    agentName = _agentName.value.trim().uppercase(),
                    agentUid = _remoteAgentUid.value ?: _currentUserUid.value, // Added agentUid inheritance
                    data = _data.value,
                    visitSegment = newSegment,
                    listOrder = maxOrder + 1
                )

                houseManagementUseCase.insertHouse(houseToInsert)
                soundManager.playPop()
                
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
        val original = houses.value.find { it.id == house.id }

        if (original != null && (house.situation == Situation.NONE || house.situation == Situation.EMPTY) && 
            (original.situation != Situation.NONE && original.situation != Situation.EMPTY)) {
            val open = houses.value.count { it.data == _data.value && (it.situation == Situation.NONE || it.situation == Situation.EMPTY) }
            if (open >= maxOpenHouses.value) {
                soundManager.playWarning()
                _situationLimitConfirmation.value = house
                return
            }
        }
        performUpdateHouse(house)
        
        // Unified delayed validation (3s)
        triggerDelayedValidation()
    }

    private fun performUpdateHouse(house: House) {
        viewModelScope.launch {
            try {
                val result = houseManagementUseCase.updateHouseWithContext(house, houses.value)
                if (result.localizationChanged) {
                    _bairro.value = result.updatedHouse.bairro
                    _currentBlock.value = result.updatedHouse.blockNumber
                    _currentBlockSequence.value = result.updatedHouse.blockSequence
                    _currentStreet.value = result.updatedHouse.streetName
                    houseManagementUseCase.updateHouses(result.subsequentHouses + result.updatedHouse)
                } else {
                    houseManagementUseCase.updateHouse(result.updatedHouse)
                }
                
                // If this house had a validation error highlight, remove it as it's being corrected
                if (_validationErrorHouseIds.value.contains(house.id)) {
                    _validationErrorHouseIds.value = _validationErrorHouseIds.value - house.id
                }
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "Error updating house", e)
            }
        }
    }

    fun deleteHouse(house: House) {
        viewModelScope.launch {
            try {
                recentlyDeletedHouse = house
                houseManagementUseCase.deleteHouse(house)
                soundManager.playPop()
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "Error deleting house", e)
            }
        }
    }

    private var recentlyDeletedHouse: House? = null
    fun restoreDeletedHouse() {
        recentlyDeletedHouse?.let {
            viewModelScope.launch { 
                houseManagementUseCase.insertHouse(it.copy(id = 0))
                recentlyDeletedHouse = null 
                soundManager.playPop()
            }
        }
    }

    fun persistListOrder(reorderedList: List<House>) {
        viewModelScope.launch {
            houseManagementUseCase.updateHouses(reorderedList.mapIndexed { index, h -> h.copy(listOrder = index.toLong()) })
        }
    }

    fun moveHouse(house: House, moveUp: Boolean) {
        viewModelScope.launch {
            val list = filteredHouses.value.toMutableList()
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
        val existingOpen = houses.value.count { it.data == newDate && (it.situation == Situation.NONE || it.situation == Situation.EMPTY) }
        if (existingOpen >= maxOpenHouses.value && (house.situation == Situation.NONE || house.situation == Situation.EMPTY)) {
            _moveConfirmationData.value = house to newDate
            return
        }
        performMoveHouse(house, newDate)
    }

    private fun performMoveHouse(house: House, newDate: String) {
        viewModelScope.launch { repository.updateHouse(house.copy(data = newDate)) }
    }

    fun moveHousesToDate(oldDate: String, newDate: String) {
        viewModelScope.launch {
            val housesToMove = houses.value.filter { it.data == oldDate }
            repository.updateHouses(housesToMove.map { it.copy(data = newDate) })
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
        _currentBlock.value = b.uppercase(); _currentBlockSequence.value = s.uppercase(); _currentStreet.value = st.uppercase()
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
    fun updateDayStatus(date: String, status: String) {
        viewModelScope.launch {
            try {
                var wasWorkingChange: Pair<Boolean, Boolean>? = null
                
                repository.runInTransaction {
                    val currentAgent = _agentName.value
                    val currentUid = _remoteAgentUid.value ?: _currentUserUid.value
                    val allActivities = repository.getAllDayActivitiesOnce(currentAgent, currentUid)
                        .associateBy { it.date }
                    
                    val existing = allActivities[date]
                    val oldStatus = existing?.status ?: "NORMAL"
                    
                    // Only trigger ripple if "Working Day" status changes
                    val wasWorking = oldStatus == "NORMAL" || oldStatus.isBlank()
                    val isWorking = status == "NORMAL" || status.isBlank()
                    
                    if (wasWorking != isWorking) {
                        wasWorkingChange = wasWorking to isWorking
                    }
                    
                    // Update the status first
                    if (existing != null) {
                        if (existing.agentUid != currentUid) {
                            repository.deleteDayActivity(existing.date, existing.agentName, existing.agentUid)
                        }
                        repository.updateDayActivity(existing.copy(status = status, agentUid = currentUid))
                    } else {
                        repository.updateDayActivity(DayActivity(date = date, status = status, agentName = currentAgent, agentUid = currentUid))
                    }

                    if (wasWorking != isWorking) {
                        // Ripple Effect: Shift production dates
                        val updatedStatusMap = allActivities.toMutableMap()
                        updatedStatusMap[date] = DayActivity(date, status, agentName = currentAgent, agentUid = currentUid)
                        
                        // Analyze all production for this agent/remote agent
                        val allAgentHouses = repository.getAllHousesOnce(currentAgent, currentUid)
                        val targetDateObj = try { dateFormatter.parse(date) } catch (e: Exception) { null } ?: return@runInTransaction
                        
                        // Analyze production at or after the changed date
                        val housesToShift = allAgentHouses.filter { 
                            val houseDate = try { dateFormatter.parse(it.data) } catch (e: Exception) { null }
                            houseDate != null && !houseDate.before(targetDateObj) && it.agentName.equals(currentAgent, ignoreCase = true)
                        }
                        
                        if (housesToShift.isNotEmpty()) {
                            val productionDates = housesToShift.map { it.data }.distinct()
                            val productionDatesSet = productionDates.toSet()
                            val dateToOffset = mutableMapOf<String, Int>()
                            
                            // 1. Calculate offsets using old configuration
                            productionDates.forEach { pDate ->
                                var offset = 0
                                val cal = Calendar.getInstance()
                                cal.time = targetDateObj
                                val pDateObj = try { dateFormatter.parse(pDate) } catch (e: Exception) { null } ?: return@forEach
                                
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
                                if (newDate != null && newDate != house.data) {
                                    house.copy(data = newDate)
                                } else null
                            }
                            
                             if (updatedHouses.isNotEmpty()) {
                                 repository.updateHouses(updatedHouses)
                             }
                         }
                     }
                 }
                 
                 // Dynamic Date Navigation (Outside transaction block)
                 wasWorkingChange?.let { (wasWorking, isWorking) ->
                     if (!isWorking && date == _data.value) {
                         // Current day is no longer working, find next one
                         val next = dayManagementUseCase.getNextBusinessDay(date, _agentName.value)
                         if (next.isNotBlank()) {
                             _data.value = next
                             soundManager.playPop()
                         }
                     } else if (isWorking) {
                         // A day became working again. If it's earlier than current selection, move back to it
                         val currentD = try { dateFormatter.parse(_data.value) } catch (e: Exception) { null }
                         val newD = try { dateFormatter.parse(date) } catch (e: Exception) { null }
                         if (newD != null && currentD != null && newD.before(currentD)) {
                             _data.value = date
                             soundManager.playPop()
                         }
                     }
                 }
                 
                 // Sound feedback (Outside transaction)
                 soundManager.playSuccess()
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "Error updating day status", e)
                _uiEvent.value = "Erro ao atualizar status do dia: ${e.message}"
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
            val allHouses = houses.value
            val activities = summary.associate { it.date to it.status }
            SemanalPdfGenerator.generatePdf(context, weekDates, allHouses, activities, agentName.value)
        }
    }

    suspend fun exportWeeklyBatchPdf(context: Context): File {
        return withContext(Dispatchers.IO) {
            clearOldPdfs(context, "Produção_")
            clearOldPdfs(context, "Boletim_")
            val dates = currentWeekDates.value
            val allHouses = houses.value
            
            // Filter houses for the current week
            val weeklyHouses = allHouses.filter { dates.contains(it.data) }
            
            // Group by Date for the generator
            val weeklyData = weeklyHouses.groupBy { it.data }

            // Get activities for the week
            val activities = weeklySummary.value.associate { it.date to it.status }
            
            BoletimPdfGenerator.generateWeeklyBatchPdf(context, weeklyData, agentName.value, activities, dates)
        }
    }

    // --- Boletim Actions ---
    fun transferProduction(date: String, newAgent: String) {
        viewModelScope.launch {
            val housesToTransfer = houses.value.filter { it.data == date }
            repository.updateHouses(housesToTransfer.map { it.copy(agentName = newAgent) })
        }
    }

    fun getHousesForDate(date: String): List<House> {
        return houses.value.filter { it.data == date }
    }

    fun deleteProduction(date: String) {
        viewModelScope.launch {
            try {
                val currentAgent = _agentName.value
                val currentUid = _remoteAgentUid.value ?: _currentUserUid.value
                repository.deleteProduction(date, currentAgent, currentUid)
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
    fun dismissGoalReached() { _showGoalReached.value = false; advanceToNextDay() }
    fun navigateToErroneousDay(d: String) { 
        viewModelScope.launch {
            val activity = dayManagementUseCase.getDayActivity(d, _agentName.value)
            if (activity?.isClosed == true) {
                dayManagementUseCase.unlockDay(d, _agentName.value, _remoteAgentUid.value)
            }
            _data.value = d
            _showMultiDayErrorDialog.value = false
            // Allow UI to update to the new date before validating
            delay(300)
            validateCurrentDay(showDialog = true)
        }
    }
    fun dismissMultiDayErrorDialog() { _showMultiDayErrorDialog.value = false }
    fun showMultiDayErrorDialog() { _showMultiDayErrorDialog.value = true }

    fun confirmSituationExceeded() {
        _situationLimitConfirmation.value?.let { performUpdateHouse(it); _situationLimitConfirmation.value = null }
    }
    fun dismissSituationLimitConfirmation() { _situationLimitConfirmation.value = null }

    fun dismissHistoryUnlockConfirmation() { _showHistoryUnlockConfirmation.value = false }

    fun confirmUnlockHistory() {
        viewModelScope.launch {
            dayManagementUseCase.unlockDay(_data.value, _agentName.value, _remoteAgentUid.value)
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
            backupData.houses.forEach { house ->
                val sanitizedHouse = house.copy(id = 0, agentName = targetAgent, agentUid = targetUid)
                val key = sanitizedHouse.generateNaturalKey()
                
                if (!existingHouseKeys.contains(key)) {
                    repository.insertHouse(sanitizedHouse)
                    importedCount++
                } else {
                    skippedCount++
                }
            }

            // Also assign activities unconditionally
            backupData.dayActivities.forEach { 
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
            totalHouses = dayHouses.count { it.situation == Situation.NONE },
            a1 = dayHouses.sumOf { it.a1 }, a2 = dayHouses.sumOf { it.a2 },
            b = dayHouses.sumOf { it.b }, c = dayHouses.sumOf { it.c },
            d1 = dayHouses.sumOf { it.d1 }, d2 = dayHouses.sumOf { it.d2 },
            e = dayHouses.sumOf { it.e }, eliminados = dayHouses.sumOf { it.eliminados },
            larvicida = dayHouses.sumOf { it.larvicida },
            totalFocos = dayHouses.count { it.comFoco },
            totalRegisteredHouses = dayHouses.size
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
