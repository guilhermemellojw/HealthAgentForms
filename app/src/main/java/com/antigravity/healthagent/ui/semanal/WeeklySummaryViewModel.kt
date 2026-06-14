package com.antigravity.healthagent.ui.semanal

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.domain.repository.HouseRepository
import com.antigravity.healthagent.domain.repository.SyncRepository
import com.antigravity.healthagent.domain.usecase.DayManagementUseCase
import com.antigravity.healthagent.utils.SoundManager
import com.antigravity.healthagent.data.settings.SettingsManager
import com.antigravity.healthagent.ui.home.DaySummary
import com.antigravity.healthagent.ui.home.WeeklySummaryTotals
import com.antigravity.healthagent.utils.BoletimPdfGenerator
import com.antigravity.healthagent.utils.SemanalPdfGenerator
import com.antigravity.healthagent.domain.repository.UserRole
import com.antigravity.healthagent.ui.state.SyncUiState
import com.antigravity.healthagent.domain.usecase.RoleEnforcer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.antigravity.healthagent.domain.logger.AppLogger
import com.antigravity.healthagent.utils.DateUtils
import java.io.File
import java.util.*
import javax.inject.Inject

@HiltViewModel
class WeeklySummaryViewModel @Inject constructor(
    private val repository: HouseRepository,
    private val settingsManager: SettingsManager,
    private val soundManager: SoundManager,
    private val dayManagementUseCase: DayManagementUseCase,
    private val syncRepository: SyncRepository,
    private val roleEnforcer: RoleEnforcer
) : ViewModel() {

    private val dateFormatter get() = DateUtils.DASH_DATE.get()
    private val displayDateFormatter get() = DateUtils.SLASH_DATE.get()

    private val _syncState = MutableStateFlow<SyncUiState>(SyncUiState.Idle())
    val syncState: StateFlow<SyncUiState> = _syncState.asStateFlow()

    private val _agentName = MutableStateFlow("")
    val agentName: StateFlow<String> = _agentName.asStateFlow()

    private val _currentUserUid = MutableStateFlow<String?>(null)
    private val _remoteAgentUid = MutableStateFlow<String?>(null)
    private val _isAdmin = MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()
    private val _isSupervisor = MutableStateFlow(false)
    val isSupervisor: StateFlow<Boolean> = _isSupervisor.asStateFlow()

    private val _uiEvent = MutableStateFlow<String?>(null)
    val uiEvent: StateFlow<String?> = _uiEvent.asStateFlow()

    val isEasyMode: StateFlow<Boolean> = settingsManager.easyMode
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isSolarMode: StateFlow<Boolean> = settingsManager.solarMode
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _currentWeekStart = MutableStateFlow(Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
    })

    init {
        // Unified observer to prevent race conditions or supervisor overwriting the remote agent name
        viewModelScope.launch {
            combine(
                settingsManager.cachedUser,
                settingsManager.remoteAgentName,
                settingsManager.remoteAgentUid
            ) { user, remoteName, remoteUid ->
                Triple(user, remoteName, remoteUid)
            }.collect { (user, remoteName, remoteUid) ->
                val activeRemoteUid = remoteUid?.ifBlank { null }
                val activeRemoteName = remoteName?.ifBlank { null }
                
                if (activeRemoteUid != null && activeRemoteName != null) {
                    _agentName.value = activeRemoteName.uppercase()
                    _remoteAgentUid.value = activeRemoteUid
                } else {
                    _remoteAgentUid.value = null
                    user?.let {
                        val name = it.agentName?.uppercase()?.ifBlank { null }
                            ?: it.email?.substringBefore("@")?.uppercase()
                            ?: "DESCONHECIDO"
                        _agentName.value = name
                    }
                }
                user?.let {
                    _currentUserUid.value = it.uid
                    _isAdmin.value = it.role == UserRole.ADMIN
                    _isSupervisor.value = it.role == UserRole.SUPERVISOR
                }
            }
        }
    }

    private val allHousesFlow: Flow<List<House>> = combine(
        _agentName, _remoteAgentUid, _currentUserUid
    ) { name, remoteUid, currentUid ->
        val effectiveUid = remoteUid ?: currentUid ?: ""
        repository.getHousesByAgentSnapshotFlow(effectiveUid)
    }.flatMapLatest { it }
    .flowOn(Dispatchers.Default)

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
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val sunday = cal.time
        cal.add(Calendar.DAY_OF_YEAR, 6)
        val saturday = cal.time
        
        "${displayDateFormatter.format(sunday)} a ${displayDateFormatter.format(saturday)}"
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    private val weekActivitiesFlow: StateFlow<List<DayActivity>> = combine(currentWeekDates, _remoteAgentUid, _currentUserUid) { dates, remote, current ->
        repository.getDayActivities(dates, remote ?: current)
    }.flatMapLatest { it }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val weeklySummary: StateFlow<List<DaySummary>> = combine(
        allHousesFlow, currentWeekDates, weekActivitiesFlow, _agentName, _remoteAgentUid, _currentUserUid
    ) { args ->
        val all = args[0] as List<House>
        val dates = args[1] as List<String>
        val activities = args[2] as List<DayActivity>
        val name = args[3] as String
        val remoteUid = args[4] as String?
        val currentUid = args[5] as String?
        
        val targetUid = remoteUid ?: currentUid
        dates.map { date ->
            val dayHouses = all.filter { it.data == date && (it.agentUid == targetUid || it.agentName == name) }
            val totalWorked = dayHouses.count { it.situation == Situation.NONE || it.situation == Situation.EMPTY }
            
            val activity = activities.find { it.date.replace("/", "-") == date.replace("/", "-") }
            val status = activity?.status?.ifBlank { "NORMAL" } ?: "NORMAL"
            val editedByAdmin = activity?.editedByAdmin ?: false
            val isClosed = activity?.isClosed ?: false
            val isManualUnlock = activity?.isManualUnlock ?: false
            
            DaySummary(date, dayHouses.size, totalWorked, status, editedByAdmin, isClosed, isManualUnlock)
        }
    }
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val weeklySummaryTotals: StateFlow<WeeklySummaryTotals> = combine(allHousesFlow, currentWeekDates, _agentName, _currentUserUid, _remoteAgentUid) { list, dates, name, currentUid, remoteUid ->
        val targetUid = remoteUid ?: currentUid
        val weekHouses = list.filter { it.data in dates && (it.agentUid == targetUid || it.agentName == name) }
        
        WeeklySummaryTotals(
            totalHouses = weekHouses.size,
            totalTratados = weekHouses.count { it.treatment.hasAnyTreatment },
            totalFoci = weekHouses.count { it.treatment.comFoco },
            totalWorked = weekHouses.count { it.situation == Situation.NONE || it.situation == Situation.EMPTY },
            totalFechados = weekHouses.count { it.situation == Situation.F },
            totalRecusados = weekHouses.count { it.situation == Situation.REC },
            totalAbsent = weekHouses.count { it.situation == Situation.A },
            totalVacant = weekHouses.count { it.situation == Situation.V }
        )
    }
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WeeklySummaryTotals())

    val weeklyObservations: StateFlow<List<House>> = combine(allHousesFlow, currentWeekDates) { list, dates ->
        list.filter { dates.contains(it.data) && it.observation.isNotBlank() }
            .sortedByDescending { it.lastUpdated }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val customActivities: StateFlow<Set<String>> = settingsManager.customActivities
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val activityOptions: StateFlow<List<String>> = settingsManager.customActivities.map { custom ->
        (listOf(
            "NORMAL", "FERIADO", "PONTO FACULTATIVO", "REUNIÃO", "TREINAMENTO",
            "FÉRIAS", "LICENÇA", "ATESTADO", "FOLGA", "TEMPO CHUVOSO", "OUTROS"
        ) + custom.toList()).distinct()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf(
        "NORMAL", "FERIADO", "PONTO FACULTATIVO", "REUNIÃO", "TREINAMENTO",
        "FÉRIAS", "LICENÇA", "ATESTADO", "FOLGA", "TEMPO CHUVOSO", "OUTROS"
    ))

    fun previousWeek() {
        _currentWeekStart.value = (_currentWeekStart.value.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -7) }
    }

    fun nextWeek() {
        _currentWeekStart.value = (_currentWeekStart.value.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 7) }
    }

    fun goToCurrentWeek() {
        _currentWeekStart.value = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        }
    }

    fun addNewActivity(name: String) {
        viewModelScope.launch { settingsManager.addCustomActivity(name) }
    }

    fun removeActivity(name: String) {
        viewModelScope.launch { settingsManager.removeCustomActivity(name) }
    }

    fun clearUiEvent() {
        _uiEvent.value = null
    }

    fun updateDayStatus(originalDate: String, status: String) {
        val date = originalDate.replace("/", "-")
        viewModelScope.launch {
            var rippleError: String? = null
            _uiEvent.value = "Iniciando atualização de status..."
            try {
                // 1. Role Enforcement Check
                val roleResult = roleEnforcer.enforce(_isSupervisor.value, _isAdmin.value, "alterar o status do dia")
                if (roleResult is RoleEnforcer.RoleResult.Blocked) {
                    rippleError = roleResult.message
                    throw Exception(rippleError)
                }

                var wasWorkingChange: Pair<Boolean, Boolean>? = null
                val currentUid = _remoteAgentUid.value ?: _currentUserUid.value
                
                repository.runInTransaction {
                    val currentAgent = _agentName.value
                    
                    val allActivities = repository.getAllDayActivitiesOnce(currentUid ?: "")
                        .associateBy { it.date.replace("/", "-") }
                    
                    val existing = allActivities[date]
                    
                    if (existing?.editedByAdmin == true && !_isAdmin.value && !existing.isManualUnlock) {
                        throw Exception("Este dia foi homologado por um administrador. Desbloqueie o dia para alterar o status.")
                    }
                    
                    val oldStatus = existing?.status ?: "NORMAL"
                    _uiEvent.value = "Status atual: $oldStatus. Novo: $status"
                    
                    val wasWorking = (oldStatus.trim().uppercase() == "NORMAL") || oldStatus.isBlank()
                    val isWorking = (status.trim().uppercase() == "NORMAL") || status.isBlank()
                    
                    if (wasWorking != isWorking) {
                        wasWorkingChange = wasWorking to isWorking
                        _uiEvent.value = "Mudança de tipo de dia detectada (${if(wasWorking) "Trabalho" else "Folga"} -> ${if(isWorking) "Trabalho" else "Folga"})"
                    }
                    
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
                        val updatedStatusMap = allActivities.toMutableMap()
                        updatedStatusMap[date] = updatedActivity
                        
                        _uiEvent.value = "Calculando efeito cascata..."
                        val allAgentHouses = repository.getAllHousesOnce(currentUid ?: "")
                        val targetDateObj = try { dateFormatter.parse(date) } catch (e: Exception) { null }
                        
                        if (targetDateObj == null) {
                            _uiEvent.value = "Erro: Data $date inválida para cálculo."
                            return@runInTransaction
                        }
                        
                        val housesToShift = allAgentHouses.filter {
                            val houseDate = try { dateFormatter.parse(it.data.replace("/", "-")) } catch (e: Exception) { null }
                            houseDate != null && !houseDate.before(targetDateObj)
                        }
                        
                        val activitiesToShift = allActivities.values.filter { act ->
                            val actDate = try { dateFormatter.parse(act.date.replace("/", "-")) } catch (e: Exception) { null }
                            actDate != null && actDate.after(targetDateObj) && (act.status.trim().uppercase() == "NORMAL" || act.status.isBlank())
                        }
                        
                        _uiEvent.value = "Identificados: ${housesToShift.size} imóveis e ${activitiesToShift.size} dias para mover."
                        
                        if (housesToShift.isNotEmpty() || activitiesToShift.isNotEmpty()) {
                            val rawDatesToShift = (housesToShift.map { it.data } + activitiesToShift.map { it.date }).distinct()
                            val rawDatesToShiftSet = rawDatesToShift.map { it.replace("/", "-") }.toSet()
                            val dateToOffset = mutableMapOf<String, Int>()
                            
                            rawDatesToShift.forEach { rDate ->
                                var offset = 0
                                val cal = Calendar.getInstance()
                                cal.time = targetDateObj
                                val rDateObj = try { dateFormatter.parse(rDate.replace("/", "-")) } catch (e: Exception) { null } ?: return@forEach
                                
                                while (cal.time.before(rDateObj)) {
                                    val currentDateStr = dateFormatter.format(cal.time)
                                    if (isWorkingDay(cal, allActivities) || rawDatesToShiftSet.contains(currentDateStr)) {
                                        offset++
                                    }
                                    cal.add(Calendar.DAY_OF_YEAR, 1)
                                }
                                dateToOffset[rDate] = offset
                            }
                            
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
                            
                            // 1. Shift and update houses
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
                            
                            // 2. Shift and update DayActivity records
                            val updatedActivities = activitiesToShift.mapNotNull { act ->
                                val offset = dateToOffset[act.date]
                                val newDate = offsetToNewDate[offset]
                                if (newDate != null && newDate != act.date.replace("/", "-")) {
                                    act.copy(
                                        date = newDate,
                                        isSynced = false,
                                        lastUpdated = System.currentTimeMillis()
                                    )
                                } else null
                            }
                            
                            // Apply DayActivity shifts
                            if (activitiesToShift.isNotEmpty()) {
                                _uiEvent.value = "Ajustando ${activitiesToShift.size} dias homologados..."
                                activitiesToShift.forEach { act ->
                                    repository.deleteDayActivity(act.date, act.agentUid)
                                }
                                updatedActivities.forEach { act ->
                                    repository.updateDayActivity(act, force = true)
                                }
                            }
                            
                            // Apply house shifts
                            if (updatedHouses.isNotEmpty()) {
                                _uiEvent.value = "Atualizando ${updatedHouses.size} imóveis..."
                                repository.updateHouses(updatedHouses, force = true)
                                _uiEvent.value = "Movimentação concluída com sucesso."
                            } else if (housesToShift.isNotEmpty() || activitiesToShift.isNotEmpty()) {
                                _uiEvent.value = "Movimentação concluída (dias ajustados)."
                            }
                        }
                    }
                }
                
                wasWorkingChange?.let { (wasWorking, isWorking) ->
                    // Navigation will be handled in UI via onNavigateToDate
                    if (isWorking) {
                        _uiEvent.value = "SUCCESS_NAVIGATE_TO:$date"
                    } else {
                        val next = dayManagementUseCase.getNextBusinessDay(date, currentUid)
                        if (next.isNotBlank()) {
                            _uiEvent.value = "SUCCESS_NAVIGATE_TO:$next"
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
                AppLogger.e("WeeklySummaryViewModel", "Day locked during ripple: ${e.message}")
                _uiEvent.value = rippleError ?: "Erro: Alguns dias estão bloqueados para Auditoria."
                soundManager.playWarning()
            } catch (e: Exception) {
                AppLogger.e("WeeklySummaryViewModel", "Error updating day status", e)
                _uiEvent.value = "Erro ao atualizar status: ${e.message}"
            }
        }
    }

    fun toggleDayLock(date: String) {
        val normalizedDate = date.replace("/", "-")
        viewModelScope.launch {
            try {
                // Role Enforcement Check
                val roleResult = roleEnforcer.enforce(_isSupervisor.value, _isAdmin.value, "alterar a trava do dia")
                if (roleResult is RoleEnforcer.RoleResult.Blocked) {
                    _uiEvent.value = roleResult.message
                    soundManager.playWarning()
                    return@launch
                }

                val currentUid = _remoteAgentUid.value ?: _currentUserUid.value
                val existing = repository.getDayActivity(normalizedDate, currentUid)
                val currentAgent = _agentName.value
                
                val activity = existing ?: DayActivity(
                    date = normalizedDate,
                    status = "NORMAL",
                    agentName = currentAgent,
                    agentUid = currentUid ?: ""
                )
                
                val newManualUnlock = !activity.isManualUnlock
                val newActivity = activity.copy(
                    isManualUnlock = newManualUnlock,
                    isClosed = if (newManualUnlock) false else activity.isClosed
                )
                
                repository.updateDayActivity(newActivity, _isAdmin.value)
                
                if (newManualUnlock) {
                    _uiEvent.value = "Edição extra habilitada para ${date}."
                } else {
                    _uiEvent.value = "Edição extra desabilitada para ${date}."
                }
                
                soundManager.playPop()
            } catch (e: Exception) {
                _uiEvent.value = "Erro ao alterar trava: ${e.message}"
                soundManager.playWarning()
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

    suspend fun exportSemanalPdf(context: Context): File {
        return withContext(Dispatchers.IO) {
            clearOldPdfs(context, "Semanal_")
            val summary = weeklySummary.value
            val weekDates = summary.map { it.date }
            val currentAgentName = _agentName.value
            
            // Collect houses for the week
            val effectiveUid = _remoteAgentUid.value ?: _currentUserUid.value ?: ""
            val rawHouses = repository.getAllHousesOnce(effectiveUid)
            
            // Extract the real agent name from the database houses if available, preventing fallback or supervisor names from interfering
            val resolvedAgentName = rawHouses.firstOrNull { it.agentUid == effectiveUid && it.agentName.isNotBlank() }?.agentName
                ?: rawHouses.firstOrNull { it.agentName.isNotBlank() }?.agentName
                ?: currentAgentName

            val filteredHouses = rawHouses.filter { 
                (it.agentUid == effectiveUid || it.agentName.uppercase() == resolvedAgentName.uppercase()) && weekDates.contains(it.data)
            }
                
            val activities = summary.associate { it.date to it.status }
            SemanalPdfGenerator.generatePdf(context, weekDates, filteredHouses, activities, resolvedAgentName)
        }
    }

    suspend fun exportWeeklyBatchPdf(context: Context): File {
        return withContext(Dispatchers.IO) {
            clearOldPdfs(context, "Produção_")
            clearOldPdfs(context, "Boletim_")
            val dates = currentWeekDates.value
            val currentAgentName = _agentName.value
            
            val effectiveUid = _remoteAgentUid.value ?: _currentUserUid.value ?: ""
            val rawHouses = repository.getAllHousesOnce(effectiveUid)
            
            // Extract the real agent name from the database houses if available, preventing fallback or supervisor names from interfering
            val resolvedAgentName = rawHouses.firstOrNull { it.agentUid == effectiveUid && it.agentName.isNotBlank() }?.agentName
                ?: rawHouses.firstOrNull { it.agentName.isNotBlank() }?.agentName
                ?: currentAgentName

            val filteredHouses = rawHouses.filter { 
                (it.agentUid == effectiveUid || it.agentName.uppercase() == resolvedAgentName.uppercase()) && dates.contains(it.data)
            }
                
            val weeklyData = filteredHouses.groupBy { it.data }
            val activities = weeklySummary.value.associate { it.date to it.status }
            
            BoletimPdfGenerator.generateWeeklyBatchPdf(context, weeklyData, resolvedAgentName, activities, dates)
        }
    }

    private fun clearOldPdfs(context: Context, prefix: String) {
        try {
            context.cacheDir.listFiles()?.forEach {
                if (it.isFile && it.name.startsWith(prefix, ignoreCase = true) && it.name.endsWith(".pdf", ignoreCase = true)) {
                    it.delete()
                }
            }
        } catch (e: Exception) {
            AppLogger.e("WeeklySummaryViewModel", "Error clearing old PDFs with prefix $prefix", e)
        }
    }

    fun syncDataToCloud() {
        if (_syncState.value is SyncUiState.Syncing) return
        _syncState.value = SyncUiState.Syncing(progress = 0.5f, message = "Sincronizando...")
        viewModelScope.launch {
            try {
                val currentUid = _remoteAgentUid.value ?: _currentUserUid.value
                if (currentUid != null) {
                    val houses = repository.getAllHousesOnce(currentUid)
                    val activities = repository.getAllDayActivitiesOnce(currentUid)
                    
                    val pushResult = syncRepository.pushLocalDataToCloud(houses, activities, currentUid)
                    if (pushResult.isSuccess) {
                        syncRepository.pruneOldTombstones()
                    }
                    syncRepository.pullCloudDataToLocal(currentUid)
                    _syncState.value = SyncUiState.Success(System.currentTimeMillis())
                }
            } catch (e: Exception) {
                AppLogger.e("WeeklySummaryViewModel", "Sync failed", e)
                _syncState.value = SyncUiState.Error(e.message ?: "Erro na sincronização")
            } finally {
                kotlinx.coroutines.delay(2000)
                _syncState.value = SyncUiState.Idle(_syncState.value.lastSyncTime)
            }
        }
    }
}
