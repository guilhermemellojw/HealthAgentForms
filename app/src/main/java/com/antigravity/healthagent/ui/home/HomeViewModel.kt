package com.antigravity.healthagent.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.domain.repository.HouseRepository
import com.antigravity.healthagent.data.repository.StreetRepository
import com.antigravity.healthagent.domain.repository.SyncRepository
import com.antigravity.healthagent.domain.repository.AgentRepository
import com.antigravity.healthagent.domain.repository.LocalizationRepository
import com.antigravity.healthagent.domain.repository.AgentData
import com.antigravity.healthagent.domain.usecase.SaveHouseUseCase
import com.antigravity.healthagent.domain.usecase.PredictHouseValuesUseCase
import com.antigravity.healthagent.domain.usecase.RecalculateVisitSegmentsUseCase
import com.antigravity.healthagent.domain.usecase.PerformLocalDatabaseMigrationUseCase
import com.antigravity.healthagent.domain.usecase.DayManagementUseCase
import com.antigravity.healthagent.domain.usecase.HouseValidationUseCase
import com.antigravity.healthagent.domain.usecase.GenerateTestDataUseCase
import com.antigravity.healthagent.domain.usecase.CleanupBrokenHousesUseCase
import com.antigravity.healthagent.domain.usecase.ClashDetector
import com.antigravity.healthagent.domain.usecase.DayLockEnforcer
import com.antigravity.healthagent.domain.usecase.RoleEnforcer
import com.antigravity.healthagent.domain.model.DailyContext
import com.antigravity.healthagent.domain.model.VisitAddress
import com.antigravity.healthagent.data.settings.SettingsManager
import com.antigravity.healthagent.data.backup.BackupManager
import com.antigravity.healthagent.ui.home.delegates.*
import com.antigravity.healthagent.ui.state.SyncUiState
import com.antigravity.healthagent.utils.SoundManager
import com.antigravity.healthagent.utils.formatStreetName
import com.antigravity.healthagent.utils.normalize as stringNormalize
import com.antigravity.healthagent.domain.logger.AppLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.antigravity.healthagent.data.backup.BackupData
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
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
    private val localizationRepository: LocalizationRepository,
    private val clashDetector: ClashDetector,
    private val dayLockEnforcer: DayLockEnforcer,
    private val roleEnforcer: RoleEnforcer,

    // DELEGATES
    private val syncDelegate: SyncDelegate,
    private val dayNavigationDelegate: DayNavigationDelegate,
    private val dayClosingDelegate: DayClosingDelegate,
    private val validationDelegate: ValidationDelegate,
    private val remoteAgentDelegate: RemoteAgentDelegate,
    private val boletimDataDelegate: BoletimDataDelegate,
    private val initializationDelegate: InitializationDelegate,
    private val houseEditDelegate: HouseEditDelegate
) : ViewModel(), HomeState {

    private val dateFormatter = SimpleDateFormat("dd-MM-yyyy", Locale.US)
    private val displayDateFormatter: SimpleDateFormat get() = SimpleDateFormat("dd/MM", Locale.US)

    // --- HomeState Implementations ---
    private val _uiState = MutableStateFlow(HomeUiState())
    override val uiState: MutableStateFlow<HomeUiState> get() = _uiState

    private val _data = MutableStateFlow(dateFormatter.format(Date()))
    override val data: MutableStateFlow<String> get() = _data

    private val _agentName = MutableStateFlow("")
    override val agentName: MutableStateFlow<String> get() = _agentName

    private val _searchQuery = MutableStateFlow("")

    private val _municipio = MutableStateFlow("BOM JARDIM")
    override val municipio: MutableStateFlow<String> get() = _municipio

    private val _bairro = MutableStateFlow("")
    override val bairro: MutableStateFlow<String> get() = _bairro

    private val _categoria = MutableStateFlow("BRR")
    override val categoria: MutableStateFlow<String> get() = _categoria

    private val _zona = MutableStateFlow("URB")
    override val zona: MutableStateFlow<String> get() = _zona

    private val _ciclo = MutableStateFlow("1º")
    override val ciclo: MutableStateFlow<String> get() = _ciclo

    private val _tipo = MutableStateFlow(2)
    override val tipo: MutableStateFlow<Int> get() = _tipo

    private val _atividade = MutableStateFlow(4)
    override val atividade: MutableStateFlow<Int> get() = _atividade

    private val _isSupervisor = MutableStateFlow(false)
    override val isSupervisor: MutableStateFlow<Boolean> get() = _isSupervisor

    private val _isAdmin = MutableStateFlow(false)
    override val isAdmin: MutableStateFlow<Boolean> get() = _isAdmin

    private val _currentBlock = MutableStateFlow("")
    override val currentBlock: MutableStateFlow<String> get() = _currentBlock

    private val _currentBlockSequence = MutableStateFlow("")
    override val currentBlockSequence: MutableStateFlow<String> get() = _currentBlockSequence

    private val _currentStreet = MutableStateFlow("")
    override val currentStreet: MutableStateFlow<String> get() = _currentStreet

    private val _bairrosList = MutableStateFlow<List<String>>(emptyList())
    val bairrosList: StateFlow<List<String>> = _bairrosList.asStateFlow()

    private val _currentWeekStart = MutableStateFlow(Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
    })

    private val _remoteAgent = MutableStateFlow<String?>(null)
    override val remoteAgent: MutableStateFlow<String?> get() = _remoteAgent

    private var _localAgentNameBackup: String? = null
    private val _remoteAgentUid = MutableStateFlow<String?>(null)
    override val remoteAgentUid: MutableStateFlow<String?> get() = _remoteAgentUid

    private val _currentUserUid = MutableStateFlow<String?>(null)
    override val currentUserUid: MutableStateFlow<String?> get() = _currentUserUid

    private val _uiEvent = MutableStateFlow<String?>(null)
    override val uiEvent: MutableStateFlow<String?> get() = _uiEvent

    private val _navigationTab = MutableStateFlow<Int?>(null)
    val navigationTab: StateFlow<Int?> = _navigationTab.asStateFlow()

    private val _showGoalReached = MutableStateFlow(false)
    override val showGoalReached: MutableStateFlow<Boolean> get() = _showGoalReached

    private val _validationErrorHouseIds = MutableStateFlow<Set<Int>>(emptySet())
    override val validationErrorHouseIds: MutableStateFlow<Set<Int>> get() = _validationErrorHouseIds

    private val _isDuplicateIds = MutableStateFlow<Set<Int>>(emptySet())
    override val isDuplicateIds: MutableStateFlow<Set<Int>> get() = _isDuplicateIds

    private val _syncStatus = MutableStateFlow<SyncUiState>(SyncUiState.Idle())
    override val syncStatus: MutableStateFlow<SyncUiState> get() = _syncStatus

    private val _backupConfirmation = MutableStateFlow<BackupConfirmation?>(null)

    private val _showClosingAudit = MutableStateFlow<AuditSummary?>(null)
    override val showClosingAudit: MutableStateFlow<AuditSummary?> get() = _showClosingAudit

    private val _integrityDialogMessage = MutableStateFlow<String?>(null)
    override val integrityDialogMessage: MutableStateFlow<String?> get() = _integrityDialogMessage

    private val _showMultiDayErrorDialog = MutableStateFlow(false)
    override val showMultiDayErrorDialog: MutableStateFlow<Boolean> get() = _showMultiDayErrorDialog

    private val _validationErrorDetails = MutableStateFlow<List<HouseValidationUseCase.ErrorDetail>>(emptyList())
    override val validationErrorDetails: MutableStateFlow<List<HouseValidationUseCase.ErrorDetail>> get() = _validationErrorDetails

    private val _scrollToHouseId = MutableStateFlow<Int?>(null)
    override val scrollToHouseId: MutableStateFlow<Int?> get() = _scrollToHouseId

    private val _situationLimitConfirmation = MutableStateFlow<House?>(null)
    override val situationLimitConfirmation: MutableStateFlow<House?> get() = _situationLimitConfirmation

    private val _showHistoryUnlockConfirmation = MutableStateFlow(false)
    override val showHistoryUnlockConfirmation: MutableStateFlow<Boolean> get() = _showHistoryUnlockConfirmation

    private val _isSyncing = MutableStateFlow(false)
    override val isSyncing: MutableStateFlow<Boolean> get() = _isSyncing

    private val _isSyncPullActive = MutableStateFlow(false)
    val isSyncPullActive: StateFlow<Boolean> = _isSyncPullActive.asStateFlow()

    fun setSyncPullActive(active: Boolean) {
        _isSyncPullActive.value = active
    }

    private val _moveConfirmationData = MutableStateFlow<Pair<House, String>?>(null)
    override val moveConfirmationData: MutableStateFlow<Pair<House, String>?> get() = _moveConfirmationData

    private val _duplicateHouseConfirmation = MutableStateFlow<House?>(null)
    override val duplicateHouseConfirmation: MutableStateFlow<House?> get() = _duplicateHouseConfirmation

    private val _pendingUpdateDrafts = MutableStateFlow<Map<Int, House>>(emptyMap())
    override val pendingUpdateDrafts: MutableStateFlow<Map<Int, House>> get() = _pendingUpdateDrafts

    private val _housesInFlight = MutableStateFlow<List<House>>(emptyList())
    override val housesInFlight: MutableStateFlow<List<House>> get() = _housesInFlight

    private val _recentlyEditedHouseIds = MutableStateFlow<Map<Int, Long>>(emptyMap())
    override val recentlyEditedHouseIds: MutableStateFlow<Map<Int, Long>> get() = _recentlyEditedHouseIds

    val recentlyEditedHouseSet: StateFlow<Set<Int>> = _recentlyEditedHouseIds.map { it.keys }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _highlightedHouseId = MutableStateFlow<Int?>(null)
    override val highlightedHouseId: MutableStateFlow<Int?> get() = _highlightedHouseId

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

    // --- DB Flows ---
    private val allHousesFlow: StateFlow<List<House>> = combine(
        _remoteAgentUid,
        settingsManager.cachedUser,
        _agentName
    ) { remoteUid, cachedUser, name ->
        val uid = remoteUid ?: cachedUser?.uid
        val effectiveName = if (remoteUid != null) name else (cachedUser?.agentName ?: name)
        uid to effectiveName
    }.flatMapLatest { (uid, name) ->
        if (uid != null) {
            repository.getPersonalHousesFlow(uid, name)
        } else {
            kotlinx.coroutines.flow.flowOf(emptyList())
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val streetSuggestions: StateFlow<List<String>> = combine(_bairro, _agentName, _remoteAgentUid, _currentUserUid) { currentB, name, remoteUid, currentUid ->
        val uid = remoteUid ?: currentUid
        streetRepository.getStreetSuggestions(currentB, name, uid ?: "")
    }.flatMapLatest { it }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
    }
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val currentWeekDates: StateFlow<List<String>> = _currentWeekStart.map { start ->
        val dates = mutableListOf<String>()
        val cal = start.clone() as Calendar
        for (i in 0..4) {
            dates.add(dateFormatter.format(cal.time))
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        dates
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val weekRangeText: StateFlow<String> = _currentWeekStart.map { start ->
        val cal = start.clone() as Calendar
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val sunday = cal.time
        cal.add(Calendar.DAY_OF_YEAR, 6)
        val saturday = cal.time
        "${displayDateFormatter.format(sunday)} a ${displayDateFormatter.format(saturday)}"
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val boletimList: StateFlow<List<BoletimSummary>> = boletimDataDelegate.getBoletimListFlow(
        scope = viewModelScope,
        allHousesFlow = allHousesFlow,
        agentNameFlow = _agentName,
        remoteAgentUidFlow = _remoteAgentUid,
        currentUserUidFlow = _currentUserUid
    )

    val activityOptions: StateFlow<List<String>> = settingsManager.customActivities.map { custom ->
        (listOf("NORMAL", "FERIADO", "PONTO FACULTATIVO", "REUNIÃO", "TREINAMENTO") + custom.toList()).distinct()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf("NORMAL", "FERIADO", "PONTO FACULTATIVO", "REUNIÃO", "TREINAMENTO"))

    val customActivities: StateFlow<Set<String>> = settingsManager.customActivities
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    init {
        initializationDelegate.initialize(
            scope = viewModelScope,
            viewModel = this,
            latestHousesFlow = latestHouses,
            allHousesFlow = allHousesFlow,
            easyMode = easyMode,
            solarMode = solarMode,
            editingToolsMode = editingToolsMode,
            maxOpenHouses = maxOpenHouses,
            generateHouseKey = ::generateHouseKey,
            calculateDashboardTotals = ::calculateDashboardTotals
        )
        viewModelScope.launch {
            _data.collect { dateStr ->
                _ciclo.value = calculateCicloFromDate(dateStr)
            }
        }
        viewModelScope.launch {
            _bairro.collect { currentBairro ->
                val calculatedTipo = if (currentBairro.uppercase() == "CENTRO") 1 else 2
                _tipo.value = calculatedTipo
            }
        }
    }

    // --- Action Routing ---
    fun syncDataToCloud() = syncDelegate.syncDataToCloud(viewModelScope, this, maxOpenHouses.value)
    fun pullDataFromCloud(targetUid: String? = null) = syncDelegate.pullDataFromCloud(viewModelScope, this, targetUid)
    fun generateMockData() = syncDelegate.generateMockData(viewModelScope, this)
    fun finishEditSession(onComplete: () -> Unit = {}) = syncDelegate.finishEditSession(viewModelScope, this, onComplete)

    fun moveDate(forward: Boolean) {
        if (forward) dayNavigationDelegate.moveDateForward(viewModelScope, this)
        else dayNavigationDelegate.moveDateBackward(viewModelScope, this)
    }
    fun selectToday() = dayNavigationDelegate.goToToday(this)
    fun moveHouseToDate(house: House, destinationDate: String) {
        dayNavigationDelegate.moveHouseToDate(viewModelScope, this, house, destinationDate, maxOpenHouses.value, isDayClosed.value)
    }
    fun confirmMoveHouse() = dayNavigationDelegate.confirmMoveHouse(viewModelScope, this)
    fun dismissMoveConfirmation() = dayNavigationDelegate.dismissMoveConfirmation(this)
    fun moveHousesToDate(oldDate: String, newDate: String) = dayNavigationDelegate.moveHousesToDate(viewModelScope, this, oldDate, newDate)

    fun clearNavigationTab() {
        _navigationTab.value = null
    }

    fun goToLastWorkDay() {
        viewModelScope.launch {
            val lastWorkDay = houses.value.maxByOrNull { house ->
                try { dateFormatter.parse(house.data)?.time ?: 0L } catch(e: Exception) { 0L }
            }?.data
            if (lastWorkDay != null) {
                _data.value = lastWorkDay
            }
        }
    }

    fun navigateToDate(date: String) {
        _data.value = date
        _navigationTab.value = 0
    }

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

    fun showMultiDayErrorDialog() { _showMultiDayErrorDialog.value = true }
    fun dismissMultiDayErrorDialog() { _showMultiDayErrorDialog.value = false }

    fun getHousesForDate(date: String, agentName: String? = null): List<House> {
        val targetName = agentName ?: _agentName.value
        val targetUid = _remoteAgentUid.value ?: _currentUserUid.value ?: ""
        
        return houses.value.filter { house ->
            house.data == date && (
                (targetUid.isNotBlank() && house.agentUid == targetUid) ||
                (targetName.isNotBlank() && house.agentName.uppercase() == targetName.uppercase())
            )
        }
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
    fun moveDateBackward() = dayNavigationDelegate.moveDateBackward(viewModelScope, this)
    fun moveDateForward() = dayNavigationDelegate.moveDateForward(viewModelScope, this)

    fun onDateSelected(date: String) {
        dayNavigationDelegate.onDateSelected(this, date)
    }

    fun dismissIntegrityDialog() { _integrityDialogMessage.value = null }
    fun dismissClosingAudit() { _showClosingAudit.value = null }
    fun dismissGoalReached() { _showGoalReached.value = false }
    fun dismissSituationLimitConfirmation() { _situationLimitConfirmation.value = null }

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

    fun updateHeader(m: String, b: String, c: String, z: String, t: Int, d: String, ci: String, a: Int) {
        val oldB = _bairro.value
        val oldM = _municipio.value
        val oldCat = _categoria.value
        val oldZ = _zona.value
        val oldT = _tipo.value
        val oldCic = _ciclo.value
        val oldAtiv = _atividade.value

        val calculatedCic = calculateCicloFromDate(d)
        val calculatedT = if (b.uppercase() == "CENTRO") 1 else 2

        _municipio.value = m.uppercase()
        _bairro.value = b.uppercase()
        _categoria.value = c.uppercase()
        _zona.value = z.uppercase()
        _tipo.value = calculatedT
        _data.value = d.replace("/", "-")
        _ciclo.value = calculatedCic
        _atividade.value = a

        val changed = oldB != b.uppercase() || oldM != m.uppercase() || 
                      oldCat != c.uppercase() || oldZ != z.uppercase() || 
                      oldT != calculatedT || oldCic != calculatedCic || oldAtiv != a

        if (changed) {
            viewModelScope.launch {
                val currentUid = _remoteAgentUid.value ?: _currentUserUid.value ?: return@launch
                val dayHouses = repository.getHousesByDateAndAgent(_data.value, currentUid)
                if (dayHouses.isNotEmpty()) {
                    val updated = dayHouses.map { hh ->
                        val finalBairro = if (hh.address.bairro.isBlank()) b.uppercase() else hh.address.bairro.uppercase()
                        hh.copy(
                            address = hh.address.copy(bairro = finalBairro),
                            context = hh.context.copy(
                                municipio = m.uppercase(),
                                categoria = c.uppercase(),
                                zona = z.uppercase(),
                                tipo = calculatedT,
                                ciclo = calculatedCic,
                                atividade = a
                            )
                        )
                    }
                    saveHouseUseCase.updateHouses(updated, _isAdmin.value)
                    triggerDelayedValidation(100)
                }
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

    fun startDayClosingFlow() = dayClosingDelegate.startDayClosingFlow(
        scope = viewModelScope,
        state = this,
        maxOpenHouses = maxOpenHouses.value,
        validateCurrentDay = { validateCurrentDay(it) }
    )

    fun confirmAndCloseDay(audit: AuditSummary) = dayClosingDelegate.confirmAndCloseDay(
        scope = viewModelScope,
        state = this,
        audit = audit,
        maxOpenHouses = maxOpenHouses.value,
        triggerImmediateSync = { triggerImmediateSync() }
    )

    fun toggleDayLock() = dayClosingDelegate.toggleDayLock(viewModelScope, this, isDayClosed.value)
    fun dismissHistoryUnlockConfirmation() = dayClosingDelegate.dismissHistoryUnlockConfirmation(this)
    fun confirmUnlockHistory() = dayClosingDelegate.confirmUnlockHistory(viewModelScope, this)

    fun validateCurrentDay(showDialog: Boolean, strict: Boolean = true): Boolean {
        return validationDelegate.validateCurrentDay(this, houses.value, showDialog, strict)
    }

    fun triggerDelayedValidation(delayMs: Long = 3000) {
        validationDelegate.triggerDelayedValidation(viewModelScope, this, { houses.value }, delayMs)
    }

    fun onHouseClick(houseId: Int) = validationDelegate.onHouseClick(viewModelScope, this, houseId)

    fun setRemoteAgent(agent: AgentData?) = remoteAgentDelegate.setRemoteAgent(
        scope = viewModelScope,
        state = this,
        agent = agent,
        localAgentNameBackup = _localAgentNameBackup,
        onBackupChanged = { _localAgentNameBackup = it }
    )

    fun deduplicateCurrentDay() = remoteAgentDelegate.deduplicateCurrentDay(viewModelScope, this)

    fun addNewHouseAt(afterId: Int) = houseEditDelegate.addNewHouseAt(viewModelScope, this, afterId, houses.value, isDayClosed.value)

    fun addNewHouse() = houseEditDelegate.addNewHouse(
        scope = viewModelScope,
        state = this,
        latestHousesList = houses.value,
        maxOpenHouses = maxOpenHouses.value,
        isDayClosed = isDayClosed.value,
        validateCurrentDay = { validateCurrentDay(it) },
        triggerDelayedValidation = { triggerDelayedValidation() },
        onHouseClick = { onHouseClick(it) }
    )

    fun updateHouse(house: House) = houseEditDelegate.updateHouse(
        scope = viewModelScope,
        state = this,
        house = house,
        latestHousesList = houses.value,
        maxOpenHouses = maxOpenHouses.value,
        isDayClosed = isDayClosed.value,
        triggerDelayedValidation = { triggerDelayedValidation() }
    )

    fun confirmDuplicateMerge() = houseEditDelegate.confirmDuplicateMerge(viewModelScope, this)
    fun dismissDuplicateConfirmation() = houseEditDelegate.dismissDuplicateConfirmation(this, { validateCurrentDay(it) })
    fun deleteHouse(house: House) = houseEditDelegate.deleteHouse(viewModelScope, this, house, houses.value, isDayClosed.value)
    fun restoreDeletedHouse() = houseEditDelegate.restoreDeletedHouse(viewModelScope, this, houses.value, isDayClosed.value)
    fun moveHouse(house: House, moveUp: Boolean) = houseEditDelegate.moveHouse(viewModelScope, this, house, moveUp, houses.value, { triggerDelayedValidation(it) })

    // --- ViewModel Specific Implementations ---
    fun setSupervisor(isSupervisor: Boolean) {
        _isSupervisor.value = isSupervisor
    }

    fun setAdmin(isAdmin: Boolean) {
        _isAdmin.value = isAdmin
    }

    fun setNavigationTab(tab: Int?) {
        _navigationTab.value = tab
    }

    fun refreshConfig() {
        viewModelScope.launch {
            loadDynamicConfig()
        }
    }

    private suspend fun loadDynamicConfig() {
        kotlinx.coroutines.withTimeoutOrNull(5000) {
            val bairrosResult = localizationRepository.fetchBairros()
            if (bairrosResult.isSuccess) {
                _bairrosList.value = bairrosResult.getOrNull() ?: com.antigravity.healthagent.utils.AppConstants.BAIRROS
            }

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
                settings["default_easy_mode"]?.let { raw ->
                    val boolVal = raw as? Boolean ?: false
                    settingsManager.setEasyMode(boolVal)
                }
                settings["custom_activities"]?.let { raw ->
                    val setVal = when (raw) {
                        is List<*> -> raw.mapNotNull { it?.toString() }.toSet()
                        is String -> raw.split(",").filter { it.isNotBlank() }.toSet()
                        else -> emptySet()
                    }
                    settingsManager.setCustomActivities(setVal)
                }
            }
        }
    }

    fun handleActivitySelection(option: String) {
        viewModelScope.launch {
            try {
                val effectiveUid = _remoteAgentUid.value ?: _currentUserUid.value
                val activity = dayManagementUseCase.getDayActivity(_data.value, effectiveUid)
                    ?: DayActivity(date = _data.value, agentName = _agentName.value, agentUid = effectiveUid ?: "")

                val isSupervisorViewing = _remoteAgentUid.value != null
                repository.updateDayActivity(activity.copy(status = option), isSupervisorViewing || _isAdmin.value)
            } catch (e: Exception) {
                AppLogger.e("HomeViewModel", "Error selecting day activity", e)
            }
        }
    }

    fun registerCustomActivity(activity: String) {
        viewModelScope.launch {
            val current = settingsManager.customActivities.first()
            if (!current.contains(activity)) {
                settingsManager.setCustomActivities(current + activity)
            }
        }
    }

    fun clearCustomActivities() {
        viewModelScope.launch {
            settingsManager.setCustomActivities(emptySet())
        }
    }

    fun dismissGoalDialog() {
        _showGoalReached.value = false
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun updateBlock(block: String) {
        _currentBlock.value = block
    }

    fun updateBlockSequence(sequence: String) {
        _currentBlockSequence.value = sequence
    }

    fun updateStreet(street: String) {
        _currentStreet.value = street
    }

    fun updateBairro(bairroName: String) {
        _bairro.value = bairroName
    }

    fun updateMunicipio(municipioName: String) {
        _municipio.value = municipioName
    }

    fun clearUiEvent() {
        _uiEvent.value = null
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
            AppLogger.e("HomeViewModel", "Failed to trigger sync", e)
            syncDataToCloud()
        }
    }

    fun forceFullSync() {
        viewModelScope.launch {
            syncRepository.pullCloudDataToLocal(force = true)
        }
    }

    fun shareBackup(context: android.content.Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val effectiveUid = _remoteAgentUid.value ?: _currentUserUid.value
                val dbHouses = repository.getHousesByDateAndAgent(_data.value, effectiveUid ?: "")
                val dayActivity = dayManagementUseCase.getDayActivity(_data.value, effectiveUid)

                val backupData = BackupData(
                    houses = dbHouses,
                    dayActivities = if (dayActivity != null) listOf(dayActivity) else emptyList()
                )

                val safeAgentName = _agentName.value.replace(" ", "_")
                val now = System.currentTimeMillis()
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

    private fun calculateDashboardTotals(dayHouses: List<House>): DashboardTotals {
        return boletimDataDelegate.calculateDashboardTotals(dayHouses)
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

    private fun calculateCicloFromDate(dateStr: String): String {
        val parts = dateStr.replace("/", "-").split("-")
        if (parts.size >= 2) {
            val month = parts[1].toIntOrNull() ?: 1
            val cicloNum = ((month - 1) / 2) + 1
            return "${cicloNum}º"
        }
        return "1º"
    }

    private fun getTimestamp(date: String): Long {
        return try { dateFormatter.parse(date)?.time ?: 0L } catch (e: Exception) { 0L }
    }

    private fun parseDate(date: String): Date? = try { dateFormatter.parse(date) } catch (e: Exception) { null }
}
