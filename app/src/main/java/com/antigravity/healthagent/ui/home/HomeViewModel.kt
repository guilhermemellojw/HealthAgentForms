package com.antigravity.healthagent.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.domain.repository.HouseRepository
import com.antigravity.healthagent.domain.repository.StreetRepository
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
import com.antigravity.healthagent.domain.usecase.LoadDynamicConfigUseCase
import com.antigravity.healthagent.domain.usecase.TriggerImmediateSyncUseCase
import com.antigravity.healthagent.domain.usecase.SelectDayActivityUseCase
import com.antigravity.healthagent.domain.usecase.UpdateDayHeaderUseCase
import com.antigravity.healthagent.data.settings.SettingsManager
import com.antigravity.healthagent.data.backup.BackupManager
import com.antigravity.healthagent.data.backup.BackupData
import com.antigravity.healthagent.ui.home.delegates.*
import com.antigravity.healthagent.ui.state.SyncUiState
import com.antigravity.healthagent.utils.SoundManager
import com.antigravity.healthagent.domain.logger.AppLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.delay
import com.antigravity.healthagent.utils.DateUtils
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar
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
    private val loadDynamicConfigUseCase: LoadDynamicConfigUseCase,
    private val triggerImmediateSyncUseCase: TriggerImmediateSyncUseCase,
    private val selectDayActivityUseCase: SelectDayActivityUseCase,
    private val updateDayHeaderUseCase: UpdateDayHeaderUseCase,

    // DELEGATES PROVIDER
    private val delegatesProvider: HomeDelegatesProvider
) : ViewModel(), HomeState by delegatesProvider.stateDelegate {

    private val syncDelegate get() = delegatesProvider.syncDelegate
    private val dayNavigationDelegate get() = delegatesProvider.dayNavigationDelegate
    private val dayClosingDelegate get() = delegatesProvider.dayClosingDelegate
    private val validationDelegate get() = delegatesProvider.validationDelegate
    private val remoteAgentDelegate get() = delegatesProvider.remoteAgentDelegate
    private val boletimDataDelegate get() = delegatesProvider.boletimDataDelegate
    private val initializationDelegate get() = delegatesProvider.initializationDelegate
    private val houseEditDelegate get() = delegatesProvider.houseEditDelegate

    private val dateFormatter get() = DateUtils.DASH_DATE.get()
    private val displayDateFormatter get() = DateUtils.SLASH_DATE.get()

    private val _searchQuery = MutableStateFlow("")
    @OptIn(FlowPreview::class)
    val searchQuery: StateFlow<String> = _searchQuery
        .debounce(300)
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _bairrosList = MutableStateFlow<List<String>>(emptyList())
    val bairrosList: StateFlow<List<String>> = _bairrosList.asStateFlow()

    private val _currentWeekStart = MutableStateFlow(Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
    })

    private var _localAgentNameBackup: String? = null

    private val _navigationTab = MutableStateFlow<Int?>(null)
    val navigationTab: StateFlow<Int?> = _navigationTab.asStateFlow()

    private val _isSyncPullActive = MutableStateFlow(false)
    val isSyncPullActive: StateFlow<Boolean> = _isSyncPullActive.asStateFlow()

    fun setSyncPullActive(active: Boolean) {
        _isSyncPullActive.value = active
    }

    val recentlyEditedHouseSet: StateFlow<Set<Int>> = recentlyEditedHouseIds.map { it.keys }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    // --- State Injections ---
    val easyMode: StateFlow<Boolean> = settingsManager.easyMode
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val solarMode: StateFlow<Boolean> = settingsManager.solarMode
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val editingToolsMode: StateFlow<Boolean> = settingsManager.editingToolsMode
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val maxOpenHouses: StateFlow<Int> = settingsManager.maxOpenHouses
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 5)
    val backupFrequency: StateFlow<com.antigravity.healthagent.data.backup.BackupFrequency> = settingsManager.backupFrequency
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.antigravity.healthagent.data.backup.BackupFrequency.DAILY)
    val themeMode: StateFlow<String?> = settingsManager.themeMode
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val themeColor: StateFlow<String?> = settingsManager.themeColor
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // --- DB Flows ---
    private val allHousesFlow: StateFlow<List<House>> = combine(
        remoteAgentUid,
        settingsManager.cachedUser,
        agentName
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
    }.distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val streetSuggestions: StateFlow<List<String>> = combine(bairro, agentName, remoteAgentUid, currentUserUid) { currentB, name, remoteUid, currentUid ->
        val uid = remoteUid ?: currentUid
        streetRepository.getStreetSuggestions(currentB, name, uid ?: "")
    }.flatMapLatest { it }
    .distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isDayClosed: StateFlow<Boolean> = combine(data, agentName, remoteAgentUid, currentUserUid) { date, name, remoteUid, currentUid ->
        val effectiveUid = remoteUid ?: currentUid
        Triple(date, name, effectiveUid)
    }.flatMapLatest { (date, name, uid) ->
        repository.getDayActivityFlow(date, uid).map { it?.isClosed == true && it?.isManualUnlock != true }
    }.distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isWorkdayManualUnlock: StateFlow<Boolean> = combine(data, agentName, remoteAgentUid, currentUserUid) { date, name, remoteUid, currentUid ->
        val effectiveUid = remoteUid ?: currentUid
        Triple(date, name, effectiveUid)
    }.flatMapLatest { (date, name, uid) ->
        repository.getDayActivityFlow(date, uid).map { it?.isManualUnlock == true }
    }.distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val latestHouses: StateFlow<List<House>> = combine(
        allHousesFlow,
        pendingUpdateDrafts,
        housesInFlight
    ) { dbHouses, drafts, inFlights ->
        (dbHouses.map { drafts[it.id] ?: it } + inFlights).sortedBy { it.listOrder }
    }
    .flowOn(Dispatchers.Default)
    .distinctUntilChanged()
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
                .sortedByDescending { HouseQueryHelper.getTimestamp(it.date) }
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
        agentNameFlow = agentName,
        remoteAgentUidFlow = remoteAgentUid,
        currentUserUidFlow = currentUserUid
    )

    val activityOptions: StateFlow<List<String>> = settingsManager.customActivities.map { custom ->
        (listOf("NORMAL", "FERIADO", "PONTO FACULTATIVO", "REUNIÃO", "TREINAMENTO") + custom.toList()).distinct()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf("NORMAL", "FERIADO", "PONTO FACULTATIVO", "REUNIÃO", "TREINAMENTO"))

    val customActivities: StateFlow<Set<String>> = settingsManager.customActivities
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val _houseUpdateQueue = MutableSharedFlow<House>(extraBufferCapacity = 64)

    private val houseUpdateJob = viewModelScope.launch {
        _houseUpdateQueue
            .debounce(300)
            .collect { house ->
                houseEditDelegate.updateHouse(
                    scope = viewModelScope,
                    state = this@HomeViewModel,
                    house = house,
                    latestHousesList = houses.value,
                    maxOpenHouses = maxOpenHouses.value,
                    isDayClosed = isDayClosed.value,
                    triggerDelayedValidation = { triggerDelayedValidation() }
                )
            }
    }

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
            generateHouseKey = { HouseQueryHelper.generateHouseKey(it) },
            calculateDashboardTotals = ::calculateDashboardTotals
        )
        viewModelScope.launch {
            data.collect { dateStr ->
                ciclo.value = HouseQueryHelper.calculateCicloFromDate(dateStr)
            }
        }
        viewModelScope.launch {
            bairro.collect { currentBairro ->
                val calculatedTipo = if (currentBairro.uppercase() == "CENTRO") 1 else 2
                tipo.value = calculatedTipo
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
                data.value = lastWorkDay
            }
        }
    }

    fun navigateToDate(date: String) {
        data.value = date
        _navigationTab.value = 0
    }

    fun navigateToErroneousDay(d: String) { 
        viewModelScope.launch {
            val effectiveUid = remoteAgentUid.value ?: currentUserUid.value
            val activity = dayManagementUseCase.getDayActivity(d, effectiveUid)
            if (activity?.isClosed == true) {
                dayManagementUseCase.unlockDay(d, effectiveUid)
            }
            data.value = d
            showMultiDayErrorDialog.value = false
            // Allow UI to update to the new date before validating
            delay(300)
            validateCurrentDay(showDialog = false) // Silence navigation dialog per user request
        }
    }

    fun showMultiDayErrorDialog() { showMultiDayErrorDialog.value = true }
    fun dismissMultiDayErrorDialog() { showMultiDayErrorDialog.value = false }

    fun getHousesForDate(date: String, agentName: String? = null): List<House> {
        return HouseQueryHelper.getHousesForDate(
            houses = houses.value,
            date = date,
            agentName = this.agentName.value,
            targetUid = remoteAgentUid.value ?: currentUserUid.value,
            targetName = agentName
        )
    }

    fun deleteProduction(date: String) {
        viewModelScope.launch {
            try {
                val currentAgent = agentName.value
                val currentUid = remoteAgentUid.value ?: currentUserUid.value
                repository.deleteProduction(date, currentUid)
                uiEvent.value = "Produção excluída com sucesso."
                soundManager.playPop()
            } catch (e: Exception) {
                AppLogger.e("HomeViewModel", "Error deleting production", e)
                uiEvent.value = "Erro ao excluir produção: ${e.message}"
            }
        }
    }

    fun exportDayDataAndShare(context: Context, date: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentAgent = agentName.value
                val currentUid = remoteAgentUid.value ?: currentUserUid.value
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
                backupManager.exportToFile(context, file, backupData)

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
                AppLogger.e("HomeViewModel", "Erro ao exportar dados", e)
                withContext(Dispatchers.Main) {
                    uiEvent.value = "Erro ao exportar dados: ${e.message}"
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

    fun dismissIntegrityDialog() { integrityDialogMessage.value = null }
    fun dismissClosingAudit() { showClosingAudit.value = null }
    fun dismissGoalReached() { showGoalReached.value = false }
    fun dismissSituationLimitConfirmation() { situationLimitConfirmation.value = null }

    fun advanceToNextDay() {
        viewModelScope.launch {
            try {
                val next = dayManagementUseCase.getNextBusinessDay(data.value, remoteAgentUid.value ?: currentUserUid.value)
                if (next.isNotBlank()) {
                    data.value = next
                    soundManager.playPop()
                    showGoalReached.value = false
                }
            } catch (e: Exception) {
                uiEvent.value = "Erro ao avançar dia: ${e.message}"
            }
        }
    }

    fun updateHeader(m: String, b: String, c: String, z: String, t: Int, d: String, ci: String, a: Int) {
        val oldB = bairro.value
        val oldM = municipio.value
        val oldCat = categoria.value
        val oldZ = zona.value
        val oldT = tipo.value
        val oldCic = ciclo.value
        val oldAtiv = atividade.value

        val calculatedCic = HouseQueryHelper.calculateCicloFromDate(d)
        val calculatedT = if (b.uppercase() == "CENTRO") 1 else 2

        municipio.value = m.uppercase()
        bairro.value = b.uppercase()
        categoria.value = c.uppercase()
        zona.value = z.uppercase()
        tipo.value = calculatedT
        data.value = d.replace("/", "-")
        ciclo.value = calculatedCic
        atividade.value = a

        val changed = oldB != b.uppercase() || oldM != m.uppercase() || 
                      oldCat != c.uppercase() || oldZ != z.uppercase() || 
                      oldT != calculatedT || oldCic != calculatedCic || oldAtiv != a

        if (changed) {
            viewModelScope.launch {
                val currentUid = remoteAgentUid.value ?: currentUserUid.value ?: return@launch
                updateDayHeaderUseCase(
                    agentUid = currentUid,
                    data = data.value,
                    bairro = b,
                    municipio = m,
                    categoria = c,
                    zona = z,
                    tipo = calculatedT,
                    ciclo = calculatedCic,
                    atividade = a
                )
                triggerDelayedValidation(100)
            }
        }
    }

    fun persistListOrder(reorderedList: List<House>) {
        viewModelScope.launch {
            val adminBypass = isAdmin.value
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

    fun updateHouse(house: House) {
        _houseUpdateQueue.tryEmit(house)
    }

    fun confirmDuplicateMerge() = houseEditDelegate.confirmDuplicateMerge(viewModelScope, this)
    fun dismissDuplicateConfirmation() = houseEditDelegate.dismissDuplicateConfirmation(this, { validateCurrentDay(it) })
    fun deleteHouse(house: House) = houseEditDelegate.deleteHouse(viewModelScope, this, house, houses.value, isDayClosed.value)
    fun restoreDeletedHouse() = houseEditDelegate.restoreDeletedHouse(viewModelScope, this, houses.value, isDayClosed.value)
    fun moveHouse(house: House, moveUp: Boolean) = houseEditDelegate.moveHouse(viewModelScope, this, house, moveUp, houses.value, { triggerDelayedValidation(it) })

    // --- ViewModel Specific Implementations ---
    fun setSupervisor(isSupervisor: Boolean) {
        this.isSupervisor.value = isSupervisor
    }

    fun setAdmin(isAdmin: Boolean) {
        this.isAdmin.value = isAdmin
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
        _bairrosList.value = loadDynamicConfigUseCase()
    }

    fun handleActivitySelection(option: String) {
        viewModelScope.launch {
            selectDayActivityUseCase(
                date = data.value,
                agentUid = remoteAgentUid.value ?: currentUserUid.value,
                agentName = agentName.value,
                option = option,
                isAdmin = isAdmin.value
            )
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
        showGoalReached.value = false
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun updateBlock(block: String) {
        currentBlock.value = block
    }

    fun updateBlockSequence(sequence: String) {
        currentBlockSequence.value = sequence
    }

    fun updateStreet(street: String) {
        currentStreet.value = street
    }

    fun updateBairro(bairroName: String) {
        bairro.value = bairroName
    }

    fun updateMunicipio(municipioName: String) {
        municipio.value = municipioName
    }

    fun clearUiEvent() {
        uiEvent.value = null
    }

    fun triggerImmediateSync() {
        try {
            triggerImmediateSyncUseCase()
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
                val effectiveUid = remoteAgentUid.value ?: currentUserUid.value
                val dbHouses = repository.getHousesByDateAndAgent(data.value, effectiveUid ?: "")
                val dayActivity = dayManagementUseCase.getDayActivity(data.value, effectiveUid)

                val backupData = BackupData(
                    houses = dbHouses,
                    dayActivities = if (dayActivity != null) listOf(dayActivity) else emptyList()
                )

                val safeAgentName = agentName.value.replace(" ", "_")
                val now = System.currentTimeMillis()
                val fileName = "Backup_${safeAgentName}_$now.json"

                val backupDir = File(context.cacheDir, "backups")
                if (backupDir.exists()) backupDir.deleteRecursively()
                backupDir.mkdirs()

                val file = File(backupDir, fileName)
                backupManager.exportToFile(context, file, backupData)

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
                AppLogger.e("HomeViewModel", "Erro ao gerar backup para compartilhamento", e)
                withContext(Dispatchers.Main) {
                    uiEvent.value = "Erro ao gerar backup para compartilhamento: ${e.message}"
                    soundManager.playWarning()
                }
            }
        }
    }

    private fun calculateDashboardTotals(dayHouses: List<House>): DashboardTotals {
        return boletimDataDelegate.calculateDashboardTotals(dayHouses)
    }
}
