package com.antigravity.healthagent.ui.semanal

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.repository.HouseRepository
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
class WeeklySummaryViewModel @Inject constructor(
    private val repository: HouseRepository,
    private val settingsManager: SettingsManager,
    private val soundManager: SoundManager,
    private val dayManagementUseCase: DayManagementUseCase,
    private val syncRepository: SyncRepository
) : ViewModel() {

    private val dateFormatter = SimpleDateFormat("dd-MM-yyyy", Locale.US)
    private val displayDateFormatter = SimpleDateFormat("dd/MM", Locale.US)

    private val _syncState = MutableStateFlow<SyncUiState>(SyncUiState.Idle())
    val syncState: StateFlow<SyncUiState> = _syncState.asStateFlow()

    private val _agentName = MutableStateFlow("")
    val agentName: StateFlow<String> = _agentName.asStateFlow()

    private val _currentUserUid = MutableStateFlow<String?>(null)
    private val _remoteAgentUid = MutableStateFlow<String?>(null)
    private val _isAdmin = MutableStateFlow(false)

    private val _uiEvent = MutableStateFlow<String?>(null)
    val uiEvent: StateFlow<String?> = _uiEvent.asStateFlow()

    val isEasyMode: StateFlow<Boolean> = settingsManager.easyMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isSolarMode: StateFlow<Boolean> = settingsManager.solarMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _currentWeekStart = MutableStateFlow(Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
    })

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
                    _isAdmin.value = it.role == UserRole.ADMIN
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
            
            DaySummary(date, dayHouses.size, totalWorked, status)
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
                var wasWorkingChange: Pair<Boolean, Boolean>? = null
                val currentUid = _remoteAgentUid.value ?: _currentUserUid.value
                
                repository.runInTransaction {
                    val currentAgent = _agentName.value
                    
                    val allActivities = repository.getAllDayActivitiesOnce(currentUid ?: "")
                        .associateBy { it.date.replace("/", "-") }
                    
                    val existing = allActivities[date]
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
                        
                        _uiEvent.value = "Calculando efeito cascata (movimentação de imóveis)..."
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
                        
                        _uiEvent.value = "Casas identificadas para mover: ${housesToShift.size}"
                        
                        if (housesToShift.isNotEmpty()) {
                            val productionDates = housesToShift.map { it.data }.distinct()
                            val productionDatesSet = productionDates.map { it.replace("/", "-") }.toSet()
                            val dateToOffset = mutableMapOf<String, Int>()
                            
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
                android.util.Log.e("WeeklySummaryViewModel", "Day locked during ripple: ${e.message}")
                _uiEvent.value = rippleError ?: "Erro: Alguns dias estão bloqueados para Auditoria."
                soundManager.playWarning()
            } catch (e: Exception) {
                android.util.Log.e("WeeklySummaryViewModel", "Error updating day status", e)
                _uiEvent.value = "Erro ao atualizar status: ${e.message}"
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
            val currentAgent = _agentName.value
            
            // Collect houses for the week
            val effectiveUid = _remoteAgentUid.value ?: _currentUserUid.value ?: ""
            val filteredHouses = repository.getAllHousesOnce(effectiveUid)
                .filter { it.agentName.uppercase() == currentAgent.uppercase() }
                
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
            
            val effectiveUid = _remoteAgentUid.value ?: _currentUserUid.value ?: ""
            val filteredHouses = repository.getAllHousesOnce(effectiveUid)
                .filter { it.agentName.uppercase() == currentAgent.uppercase() && dates.contains(it.data) }
                
            val weeklyData = filteredHouses.groupBy { it.data }
            val activities = weeklySummary.value.associate { it.date to it.status }
            
            BoletimPdfGenerator.generateWeeklyBatchPdf(context, weeklyData, currentAgent, activities, dates)
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
            android.util.Log.e("WeeklySummaryViewModel", "Error clearing old PDFs with prefix $prefix", e)
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
                android.util.Log.e("WeeklySummaryViewModel", "Sync failed", e)
                _syncState.value = SyncUiState.Error(e.message ?: "Erro na sincronização")
            } finally {
                kotlinx.coroutines.delay(2000)
                _syncState.value = SyncUiState.Idle(_syncState.value.lastSyncTime)
            }
        }
    }
}
