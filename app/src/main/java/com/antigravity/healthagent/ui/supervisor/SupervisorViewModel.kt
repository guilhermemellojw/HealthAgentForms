package com.antigravity.healthagent.ui.supervisor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.healthagent.domain.repository.AgentData
import com.antigravity.healthagent.domain.repository.AgentRepository
import com.antigravity.healthagent.domain.usecase.RestoreDataUseCase
import com.antigravity.healthagent.ui.state.SyncUiState
import com.antigravity.healthagent.ui.supervisor.delegates.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject

data class StatDetail(
    val agentName: String,
    val agentEmail: String,
    val agentUid: String,
    val count: Int,
    val photoUrl: String? = null
)

data class AggregateSummary(
    val totalWorked: Int = 0,
    val housesDetails: List<StatDetail> = emptyList(),
    val totalVisits: Int = 0,
    val visitsDetails: List<StatDetail> = emptyList(),
    val totalFoci: Int = 0,
    val fociDetails: List<StatDetail> = emptyList(),
    val totalTratados: Int = 0,
    val tratadosDetails: List<StatDetail> = emptyList(),
    val totalFechados: Int = 0,
    val fechadosDetails: List<StatDetail> = emptyList(),
    val totalAbandonados: Int = 0,
    val abandonadosDetails: List<StatDetail> = emptyList(),
    val totalRecusados: Int = 0,
    val recusadosDetails: List<StatDetail> = emptyList(),
    val activeAgents: Int = 0,
    val totalAgents: Int = 0
)

@HiltViewModel
class SupervisorViewModel @Inject constructor(
    private val agentRepository: AgentRepository,
    private val accessControlRepository: com.antigravity.healthagent.domain.repository.AccessControlRepository,
    private val settingsManager: com.antigravity.healthagent.data.settings.SettingsManager,
    private val stateDelegate: SupervisorStateDelegate,
    private val statsDelegate: SupervisorStatsDelegate,
    private val filterDelegate: SupervisorFilterDelegate
) : ViewModel(), SupervisorState by stateDelegate {

    private val tz = TimeZone.getTimeZone("America/Sao_Paulo")
    
    val solarMode: StateFlow<Boolean> = settingsManager.solarMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val availableYears = (2025..Calendar.getInstance(tz).get(Calendar.YEAR)).reversed().toList()
    val availableMonths = listOf("Ano Todo", "Jan", "Fev", "Mar", "Abr", "Mai", "Jun", "Jul", "Ago", "Set", "Out", "Nov", "Dez")

    val agents: StateFlow<List<AgentData>> = combine(rawAgents, searchQuery) { raw, query ->
        val filteredByDate = filterDelegate.filterFutureData(raw)
        val filteredBySearch = if (query.isBlank()) filteredByDate
        else filteredByDate.filter { 
            it.agentName?.contains(query, ignoreCase = true) == true || 
            it.email.contains(query, ignoreCase = true)
        }
        
        val collator = java.text.Collator.getInstance(Locale("pt", "BR"))
        filteredBySearch.sortedWith { a, b ->
            collator.compare(a.agentName ?: a.email, b.agentName ?: b.email)
        }
    }
    .flowOn(kotlinx.coroutines.Dispatchers.Default)
    .distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val weeksInMonth: StateFlow<List<WeekRange>> = combine(selectedYear, selectedMonth) { year, month ->
        if (month == -1) emptyList()
        else filterDelegate.generateWeeksForMonth(year, month)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val weekRangeText: StateFlow<String> = combine(currentWeekStart, weeksInMonth, selectedWeekIndex, selectedMonth, selectedYear) { start, weeks, weekIndex, month, year ->
        if (weekIndex != -1 && weeks.isNotEmpty()) {
            val week = weeks.getOrNull(weekIndex)
            if (week != null) {
                val sdf = com.antigravity.healthagent.utils.DateUtils.SLASH_DATE.get().apply { timeZone = tz }
                return@combine "${sdf.format(week.start)} - ${sdf.format(week.end)}"
            }
        }
        
        if (month == -1) "Todo o Ano $year"
        else "${availableMonths[month + 1]} de $year"
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val aggregatedSummary: StateFlow<AggregateSummary> = combine(agents, weeksInMonth, selectedWeekIndex, selectedMonth, selectedYear) { filteredAgents, weeks, weekIndex, month, year ->
        if (weekIndex != -1 && weeks.isNotEmpty()) {
            val week = weeks.getOrNull(weekIndex)
            if (week != null) {
                return@combine statsDelegate.calculateAggregateSummary(stateDelegate, filteredAgents, week.start, week.end)
            }
        }
        statsDelegate.calculateAggregateSummary(stateDelegate, filteredAgents, null, null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AggregateSummary())

    init {
        viewModelScope.launch {
            settingsManager.lastSyncTimestamp.collect { ts ->
                syncState.value = SyncUiState.Idle(lastSyncTime = if (ts > 0L) ts else null)
            }
        }
        refreshData()
    }

    fun clearUiEvent() { uiEvent.value = null }
    fun clearError() { errorMessage.value = null }

    fun toggleAgentExpanded(uid: String) = filterDelegate.toggleAgentExpanded(stateDelegate, uid)
    fun updateSearchQuery(query: String) = filterDelegate.updateSearchQuery(stateDelegate, query)
    fun getFilteredMonths(): List<String> = filterDelegate.getFilteredMonths(stateDelegate)

    fun updateYear(year: Int) = filterDelegate.updateYear(stateDelegate, year) { refreshData() }
    fun updateMonth(monthIndex: Int) = filterDelegate.updateMonth(stateDelegate, monthIndex) { refreshData() }
    fun updateWeek(weekIndex: Int) = filterDelegate.updateWeek(stateDelegate, weekIndex) { refreshData() }

    fun nextWeek() = filterDelegate.nextWeek(stateDelegate)
    fun previousWeek() = filterDelegate.previousWeek(stateDelegate)

    fun startLiveInspection(uid: String) = statsDelegate.startLiveInspection(viewModelScope, stateDelegate, uid) {
        filterDelegate.filterFutureData(it)
    }
    fun stopLiveInspection() = statsDelegate.stopLiveInspection(stateDelegate)

    fun restoreAgentData(context: android.content.Context, agentUid: String, fileUri: android.net.Uri, targetDate: String? = null) =
        statsDelegate.restoreAgentData(viewModelScope, stateDelegate, context, agentUid, fileUri, targetDate) {
            refreshData()
        }

    fun refreshData() {
        if (syncState.value is SyncUiState.Syncing) return
        syncState.value = SyncUiState.Syncing(progress = 0.5f, message = "Atualizando dados...", lastSyncTime = syncState.value.lastSyncTime)
        viewModelScope.launch {
            isLoading.value = true
            errorMessage.value = null
            
            val year = selectedYear.value
            val month = selectedMonth.value
            val weekIndex = selectedWeekIndex.value
            
            val (since, until) = if (weekIndex != -1) {
                val weeks = weeksInMonth.value
                val week = weeks.getOrNull(weekIndex)
                if (week != null) {
                    week.start.time to week.end.time
                } else {
                    -1L to -1L
                }
            } else if (month != -1) {
                val now = Calendar.getInstance(tz)
                val isCurrentMonthYear = (month == now.get(Calendar.MONTH) && year == now.get(Calendar.YEAR))
                if (isCurrentMonthYear) {
                    val cal = Calendar.getInstance(tz)
                    cal.set(year, month, 1, 0, 0, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    val start = cal.timeInMillis
                    
                    cal.set(year, month, cal.getActualMaximum(Calendar.DAY_OF_MONTH), 23, 59, 59)
                    cal.set(Calendar.MILLISECOND, 999)
                    val end = cal.timeInMillis
                    start to end
                } else {
                    -1L to -1L
                }
            } else {
                val now = Calendar.getInstance(tz)
                if (year == now.get(Calendar.YEAR)) {
                    val currentMonth = now.get(Calendar.MONTH)
                    val cal = Calendar.getInstance(tz)
                    cal.set(year, currentMonth, 1, 0, 0, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    val start = cal.timeInMillis
                    
                    cal.set(year, currentMonth, cal.getActualMaximum(Calendar.DAY_OF_MONTH), 23, 59, 59)
                    cal.set(Calendar.MILLISECOND, 999)
                    val end = cal.timeInMillis
                    start to end
                } else {
                    -1L to -1L
                }
            }

            val datePattern = if (month == -1) {
                "-$year"
            } else {
                val monthStr = String.format("%02d", month + 1)
                "-$monthStr-$year"
            }

            try {
                val result = agentRepository.fetchAllAgentsData(since, until, datePattern)
                if (result.isSuccess) {
                    rawAgents.value = result.getOrNull() ?: emptyList()
                    val now = System.currentTimeMillis()
                    settingsManager.setLastSyncTimestamp(now)
                    syncState.value = SyncUiState.Success(lastSyncTime = now)
                } else {
                    val errMsg = result.exceptionOrNull()?.message ?: "Erro desconhecido ao carregar dados"
                    errorMessage.value = errMsg
                    syncState.value = SyncUiState.Error(message = errMsg, lastSyncTime = syncState.value.lastSyncTime)
                }
            } catch (e: Exception) {
                val errMsg = e.message ?: "Erro ao atualizar dados"
                errorMessage.value = errMsg
                syncState.value = SyncUiState.Error(message = errMsg, lastSyncTime = syncState.value.lastSyncTime)
            } finally {
                isLoading.value = false
                syncState.value = SyncUiState.Idle(lastSyncTime = syncState.value.lastSyncTime)
            }
        }
    }

    data class WeekRange(val label: String, val start: Date, val end: Date)
}
