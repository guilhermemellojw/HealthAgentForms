package com.antigravity.healthagent.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.domain.repository.HouseRepository
import com.antigravity.healthagent.domain.repository.StreetRepository
import com.antigravity.healthagent.domain.repository.AgentData
import com.antigravity.healthagent.domain.usecase.SaveHouseUseCase
import com.antigravity.healthagent.domain.usecase.RecalculateVisitSegmentsUseCase
import com.antigravity.healthagent.domain.usecase.DayManagementUseCase
import com.antigravity.healthagent.domain.usecase.HouseValidationUseCase
import com.antigravity.healthagent.domain.usecase.LoadDynamicConfigUseCase
import com.antigravity.healthagent.domain.usecase.TriggerImmediateSyncUseCase
import com.antigravity.healthagent.domain.usecase.SelectDayActivityUseCase
import com.antigravity.healthagent.domain.usecase.UpdateDayHeaderUseCase
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.domain.model.TreatmentData
import com.antigravity.healthagent.domain.model.GeoCapture
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
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: HouseRepository,
    private val settingsManager: SettingsManager,
    private val soundManager: SoundManager,
    private val saveHouseUseCase: SaveHouseUseCase,
    private val recalculateVisitSegmentsUseCase: RecalculateVisitSegmentsUseCase,
    val dayManagementUseCase: DayManagementUseCase,
    private val houseValidationUseCase: HouseValidationUseCase,
    private val streetRepository: StreetRepository,
    private val backupManager: BackupManager,
    private val loadDynamicConfigUseCase: LoadDynamicConfigUseCase,
    private val triggerImmediateSyncUseCase: TriggerImmediateSyncUseCase,
    private val selectDayActivityUseCase: SelectDayActivityUseCase,
    private val updateDayHeaderUseCase: UpdateDayHeaderUseCase,

    // Direct delegate/viewmodel injection
    private val homeStateDelegate: HomeStateDelegate,
    private val syncViewModel: SyncViewModel,
    private val dayManagementViewModel: DayManagementViewModel,
    private val houseEditDelegate: HouseEditDelegate,
    private val validationViewModel: ValidationViewModel,
    private val dayClosingDelegate: DayClosingDelegate,
    private val remoteAgentDelegate: RemoteAgentDelegate,
    private val boletimDataDelegate: BoletimDataDelegate,
    private val initializationDelegate: InitializationDelegate
) : ViewModel(), HomeState by homeStateDelegate {

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
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val solarMode: StateFlow<Boolean> = settingsManager.solarMode
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val editingToolsMode: StateFlow<Boolean> = settingsManager.editingToolsMode
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val maxOpenHouses: StateFlow<Int> = settingsManager.maxOpenHouses
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 5)
    val backupFrequency: StateFlow<com.antigravity.healthagent.data.backup.BackupFrequency> = settingsManager.backupFrequency
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.antigravity.healthagent.data.backup.BackupFrequency.DAILY)
    val themeMode: StateFlow<String?> = settingsManager.themeMode
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val themeColor: StateFlow<String?> = settingsManager.themeColor
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

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
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

        val latestHouses: StateFlow<List<House>> = combine(
            allHousesFlow,
            pendingUpdateDrafts,
            housesInFlight
        ) { dbHouses, drafts, inFlights ->
            val combined = (dbHouses.map { drafts[it.id] ?: it } + inFlights.filter { inFlight ->
                !dbHouses.any { db -> db.generateIdentityKey() == inFlight.generateIdentityKey() }
            }).sortedBy { it.listOrder }
            combined.firstOrNull()?.let { first ->
                AppLogger.d("PERSIST_DEBUG", "COMBINE: count=${combined.size} first_id=${first.id} first_pt=${first.propertyType.code}")
            }
            combined.find { it.id == 1608 }?.let { h ->
                AppLogger.d("PERSIST_DEBUG", "COMBINE_H1608: n=${h.address.number} pt=${h.propertyType.code} src=" + (if (drafts.containsKey(1608)) "DRAFT" else "DB"))
            }
            combined
        }
        .flowOn(Dispatchers.Default)
        .distinctUntilChanged { old, new ->
            if (old.size != new.size) false
            else old.zip(new).all { (a, b) ->
                a.id == b.id &&
                a.address == b.address &&
                a.treatment == b.treatment &&
                a.context == b.context &&
                a.propertyType == b.propertyType &&
                a.situation == b.situation &&
                a.observation == b.observation &&
                a.listOrder == b.listOrder &&
                a.visitSegment == b.visitSegment &&
                a.data == b.data &&
                a.agentName == b.agentName &&
                a.agentUid == b.agentUid &&
                a.geo == b.geo &&
                a.localidadeConcluida == b.localidadeConcluida &&
                a.quarteiraoConcluido == b.quarteiraoConcluido
            }
        }
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
    }.distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf("NORMAL", "FERIADO", "PONTO FACULTATIVO", "REUNIÃO", "TREINAMENTO"))

    val customActivities: StateFlow<Set<String>> = settingsManager.customActivities
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val settingsState: StateFlow<HomeSettingsState> = combine(
        easyMode, solarMode, editingToolsMode, maxOpenHouses,
        backupFrequency, themeMode, themeColor, customActivities
    ) { args: Array<*> ->
        HomeSettingsState(
            easyMode = args[0] as Boolean,
            solarMode = args[1] as Boolean,
            editingToolsMode = args[2] as Boolean,
            maxOpenHouses = args[3] as Int,
            backupFrequency = args[4] as com.antigravity.healthagent.data.backup.BackupFrequency,
            themeMode = args[5] as String?,
            themeColor = args[6] as String?,
            customActivities = args[7] as Set<String>
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeSettingsState())

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

    override fun onCleared() {
        super.onCleared()
        initializationDelegate.cancel()
        validationViewModel.cancel()
        dayManagementViewModel.cancelScope()
        syncViewModel.cancelScope()
        houseEditDelegate.cancelAll()
    }

    // ──────────────────────────────────────────────────────────
    // HOISTED DIALOG STATES
    // ──────────────────────────────────────────────────────────

    data class TreatmentDialogState(
        val houseId: Int,
        val treatment: TreatmentData,
        val geo: GeoCapture,
        val comFoco: Boolean
    )

    data class ContextDialogState(
        val houseId: Int,
        val block: String,
        val blockSequence: String,
        val street: String,
        val bairro: String,
        val quarteiraoConcluido: Boolean,
        val localidadeConcluida: Boolean
    )

    private val _treatmentDialogState = MutableStateFlow<TreatmentDialogState?>(null)
    val treatmentDialogState: StateFlow<TreatmentDialogState?> = _treatmentDialogState
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _contextDialogState = MutableStateFlow<ContextDialogState?>(null)
    val contextDialogState: StateFlow<ContextDialogState?> = _contextDialogState
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun openTreatmentDialog(house: House) {
        _treatmentDialogState.value = TreatmentDialogState(
            houseId = house.id,
            treatment = house.treatment,
            geo = house.geo,
            comFoco = house.treatment.comFoco
        )
    }

    fun closeTreatmentDialog() {
        _treatmentDialogState.value = null
    }

    fun confirmTreatmentDialog(treatment: TreatmentData, geo: GeoCapture) {
        _treatmentDialogState.value?.let { state ->
            viewModelScope.launch {
                val effectiveUid = remoteAgentUid.value ?: currentUserUid.value
                val activity = withContext(Dispatchers.IO) {
                    dayManagementUseCase.getDayActivity(data.value, effectiveUid)
                }
                val isEffectivelyClosed = activity?.isClosed == true && activity?.isManualUnlock != true

                if (isEffectivelyClosed && !isAdmin.value) {
                    uiEvent.value = "Dia fechado. Desbloqueie para editar o tratamento."
                    soundManager.playWarning()
                    return@launch
                }

                updateHouseField(state.houseId) { house ->
                    house.copy(treatment = treatment, geo = geo)
                }
                closeTreatmentDialog()
            }
        }
    }

    fun openContextDialog(house: House) {
        _contextDialogState.value = ContextDialogState(
            houseId = house.id,
            block = house.address.blockNumber,
            blockSequence = house.address.blockSequence,
            street = house.address.streetName,
            bairro = house.address.bairro,
            quarteiraoConcluido = house.quarteiraoConcluido,
            localidadeConcluida = house.localidadeConcluida
        )
    }

    fun closeContextDialog() {
        _contextDialogState.value = null
    }

    fun confirmContextDialog(
        houseId: Int,
        block: String,
        blockSeq: String,
        street: String,
        bairro: String,
        qConcluido: Boolean,
        lConcluida: Boolean
    ) {
        updateHouseField(houseId) { house ->
            house.copy(
                address = house.address.copy(
                    blockNumber = block,
                    blockSequence = blockSeq,
                    streetName = street,
                    bairro = bairro
                ),
                quarteiraoConcluido = qConcluido,
                localidadeConcluida = lConcluida
            )
        }
        closeContextDialog()
    }

    // ──────────────────────────────────────────────────────────
    // REORDER STATE (hoisted from ReorderableHouseList)
    // ──────────────────────────────────────────────────────────

    private val _reorderHouses = MutableStateFlow<List<HouseUiState>>(emptyList())
    val reorderHouses: StateFlow<List<HouseUiState>> = _reorderHouses
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var _reorderMode = false

    fun startReorderMode(currentHouses: List<HouseUiState>) {
        _reorderMode = true
        _reorderHouses.value = currentHouses
    }

    fun cancelReorderMode() {
        _reorderMode = false
        _reorderHouses.value = emptyList()
    }

    fun updateReorderList(newOrder: List<HouseUiState>) {
        _reorderHouses.value = newOrder
    }

    fun persistReorderList() {
        if (!_reorderMode) return
        val housesToList = _reorderHouses.value.map { it.house }
        houseEditDelegate.persistListOrder(viewModelScope, this, housesToList, ::triggerDelayedValidation)
        _reorderMode = false
        _reorderHouses.value = emptyList()
    }

    // ──────────────────────────────────────────────────────────
    // Action Routing
    // ──────────────────────────────────────────────────────────
    fun syncDataToCloud() = syncViewModel.syncDataToCloud(
        syncStatus = syncStatus,
        remoteAgentUid = remoteAgentUid.value,
        currentUserUid = currentUserUid.value,
        maxOpenHouses = maxOpenHouses.value,
        pendingUpdateDrafts = pendingUpdateDrafts.value,
        uiEvent = uiEvent,
        data = data
    )

    fun pullDataFromCloud(targetUid: String? = null) = syncViewModel.pullDataFromCloud(
        syncStatus = syncStatus,
        currentUserUid = currentUserUid.value,
        targetUid = targetUid,
        uiEvent = uiEvent
    )

    fun generateMockData() = syncViewModel.generateMockData(
        syncStatus = syncStatus,
        agentName = agentName.value,
        currentUserUid = currentUserUid.value,
        currentDate = data.value
    )

    fun finishEditSession(onComplete: () -> Unit = {}) = syncViewModel.finishEditSession(
        syncStatus = syncStatus,
        remoteAgent = remoteAgent.value,
        remoteAgentUid = remoteAgentUid.value,
        currentUserUid = currentUserUid.value,
        uiEvent = uiEvent,
        onComplete = onComplete,
        remoteAgentFlow = remoteAgent,
        remoteAgentUidFlow = remoteAgentUid,
        pendingUpdateDraftsFlow = pendingUpdateDrafts,
        housesInFlightFlow = housesInFlight
    )

    fun moveDate(forward: Boolean) {
        if (forward) dayManagementViewModel.moveDateForward(data, uiEvent)
        else dayManagementViewModel.moveDateBackward(data, uiEvent)
    }

    fun selectToday() = dayManagementViewModel.goToToday(data)

    fun moveHouseToDate(house: House, destinationDate: String) {
        dayManagementViewModel.moveHouseToDate(
            data = data,
            uiEvent = uiEvent,
            uiState = uiState,
            isAdmin = isAdmin,
            agentName = agentName,
            remoteAgentUid = remoteAgentUid,
            currentUserUid = currentUserUid,
            housesInFlight = housesInFlight,
            pendingUpdateDrafts = pendingUpdateDrafts,
            moveConfirmationData = moveConfirmationData,
            house = house,
            newDate = destinationDate,
            maxOpenHouses = maxOpenHouses.value,
            isDayClosed = isDayClosed.value
        )
    }

    fun confirmMoveHouse() = dayManagementViewModel.confirmMoveHouse(
        moveConfirmationData = moveConfirmationData,
        data = data,
        uiEvent = uiEvent,
        isAdmin = isAdmin,
        agentName = agentName,
        remoteAgentUid = remoteAgentUid,
        currentUserUid = currentUserUid,
        housesInFlight = housesInFlight,
        pendingUpdateDrafts = pendingUpdateDrafts
    )

    fun dismissMoveConfirmation() = dayManagementViewModel.dismissMoveConfirmation(moveConfirmationData)

    fun moveHousesToDate(oldDate: String, newDate: String) = dayManagementViewModel.moveHousesToDate(
        data = data,
        uiEvent = uiEvent,
        remoteAgentUid = remoteAgentUid,
        currentUserUid = currentUserUid,
        housesInFlight = housesInFlight,
        oldDate = oldDate,
        newDate = newDate
    )

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
            delay(300)
            validateCurrentDay(showDialog = false)
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

                val safeAgentName = currentAgent.trim().replace(" ", "_").ifBlank { "Agente" }
                val fileName = "Producao_${safeAgentName}_${date.replace("/", "-")}.json"

                val backupDir = File(context.cacheDir, "exports")
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
    fun moveDateBackward() = dayManagementViewModel.moveDateBackward(data, uiEvent)
    fun moveDateForward() = dayManagementViewModel.moveDateForward(data, uiEvent)

    fun onDateSelected(date: String) {
        data.value = date
        dayManagementViewModel.onDateSelected(data)
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
        houseEditDelegate.persistListOrder(viewModelScope, this, reorderedList, ::triggerDelayedValidation)
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

    fun toggleDayLock() = dayClosingDelegate.toggleDayLock(viewModelScope, this)
    fun dismissHistoryUnlockConfirmation() = dayClosingDelegate.dismissHistoryUnlockConfirmation(this)
    fun confirmUnlockHistory() = dayClosingDelegate.confirmUnlockHistory(viewModelScope, this)

    fun validateCurrentDay(showDialog: Boolean, strict: Boolean = true): Boolean {
        return validationViewModel.validateCurrentDay(this, houses.value, showDialog, strict)
    }

    fun triggerDelayedValidation(delayMs: Long = 3000) {
        validationViewModel.triggerDelayedValidation(this, { houses.value }, delayMs)
    }

    fun onHouseClick(houseId: Int) = validationViewModel.onHouseClick(this, houseId)

    fun setRemoteAgent(agent: AgentData?) = remoteAgentDelegate.setRemoteAgent(
        scope = viewModelScope,
        state = this,
        agent = agent,
        localAgentNameBackup = _localAgentNameBackup,
        onBackupChanged = { _localAgentNameBackup = it }
    )

    fun deduplicateCurrentDay() = remoteAgentDelegate.deduplicateCurrentDay(viewModelScope, this)

    fun addNewHouseAt(afterId: Int) = houseEditDelegate.addNewHouseAt(viewModelScope, this, afterId, houses.value)

    fun addNewHouse() = houseEditDelegate.addNewHouse(
        scope = viewModelScope,
        state = this,
        latestHousesList = houses.value,
        validateCurrentDay = { validateCurrentDay(it) },
        triggerDelayedValidation = { triggerDelayedValidation() },
        onHouseClick = { onHouseClick(it) }
    )

    fun updateHouse(house: House) {
        houseEditDelegate.updateHouse(
            scope = viewModelScope,
            state = this,
            house = house,
            latestHousesList = houses.value,
            triggerDelayedValidation = { triggerDelayedValidation() }
        )
    }

    fun updateHouseField(houseId: Int, update: (House) -> House) {
        houseEditDelegate.updateHouseField(
            scope = viewModelScope,
            state = this,
            houseId = houseId,
            latestHousesList = houses.value,
            triggerDelayedValidation = { triggerDelayedValidation() },
            update = update
        )
    }

    fun confirmDuplicateMerge() = houseEditDelegate.confirmDuplicateMerge(viewModelScope, this)
    fun dismissDuplicateConfirmation() = houseEditDelegate.dismissDuplicateConfirmation(this, { validateCurrentDay(it) })
    fun deleteHouse(house: House) = houseEditDelegate.deleteHouse(viewModelScope, this, house, houses.value)
    fun restoreDeletedHouse() = houseEditDelegate.restoreDeletedHouse(viewModelScope, this, houses.value)
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
            val uid = remoteAgentUid.value ?: currentUserUid.value
            if (uid != null) {
                syncViewModel.forcePull(uid)
            }
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
