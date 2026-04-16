package com.antigravity.healthagent.ui.supervisor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.healthagent.domain.repository.AgentData
import com.antigravity.healthagent.domain.repository.AgentRepository
import com.antigravity.healthagent.domain.repository.AuthRepository
import com.antigravity.healthagent.domain.usecase.RestoreDataUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
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
    private val authRepository: AuthRepository,
    private val restoreDataUseCase: RestoreDataUseCase,
    private val settingsManager: com.antigravity.healthagent.data.settings.SettingsManager
) : ViewModel() {

    private val _uiEvent = MutableStateFlow<String?>(null)
    val uiEvent: StateFlow<String?> = _uiEvent.asStateFlow()

    val solarMode: StateFlow<Boolean> = settingsManager.solarMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun clearUiEvent() { _uiEvent.value = null }

    private val _rawAgents = MutableStateFlow<List<AgentData>>(emptyList())
    
    // LIVE INSPECTION STATE
    private val _focusedAgentUid = MutableStateFlow<String?>(null)
    private val _liveAgentData = MutableStateFlow<AgentData?>(null)
    private var liveJob: kotlinx.coroutines.Job? = null

    val agents: StateFlow<List<AgentData>> = _rawAgents
        .map { filterFutureData(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _selectedYear = MutableStateFlow(Calendar.getInstance().get(Calendar.YEAR))
    val selectedYear = _selectedYear.asStateFlow()

    private val _selectedMonth = MutableStateFlow(Calendar.getInstance().get(Calendar.MONTH)) // Default to current month
    val selectedMonth = _selectedMonth.asStateFlow()

    private val _selectedWeekIndex = MutableStateFlow(-1) // -1 for "Mês Todo"
    val selectedWeekIndex = _selectedWeekIndex.asStateFlow()

    val availableYears = (2025..Calendar.getInstance().get(Calendar.YEAR)).reversed().toList()
    
    val availableMonths = listOf("Ano Todo", "Jan", "Fev", "Mar", "Abr", "Mai", "Jun", "Jul", "Ago", "Set", "Out", "Nov", "Dez")

    fun getFilteredMonths(): List<String> {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val currentMonth = Calendar.getInstance().get(Calendar.MONTH)
        
        return if (_selectedYear.value >= currentYear) {
            // Only months up to now + "Ano Todo"
            availableMonths.take(currentMonth + 2) // +1 for "Ano Todo", +1 for current month index (0-based)
        } else {
            availableMonths
        }
    }

    fun updateYear(year: Int) {
        _selectedYear.value = year
        // If selecting a year that makes current month invalid, reset to current month or "Ano Todo"
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val currentMonth = Calendar.getInstance().get(Calendar.MONTH)
        if (year == currentYear && _selectedMonth.value > currentMonth) {
            _selectedMonth.value = currentMonth
        }
        _selectedWeekIndex.value = -1
        refreshData()
    }

    fun updateMonth(monthIndex: Int) {
        _selectedMonth.value = monthIndex
        _selectedWeekIndex.value = -1
        refreshData()
    }

    fun updateWeek(weekIndex: Int) {
        _selectedWeekIndex.value = weekIndex
        refreshData()
    }

    data class WeekRange(val label: String, val start: Date, val end: Date)

    val weeksInMonth = combine(_selectedYear, _selectedMonth) { year, month ->
        if (month == -1) emptyList<WeekRange>()
        else generateWeeksForMonth(year, month)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentWeekStart = MutableStateFlow(getMondayOfCurrentWeek())

    val weekRangeText = combine(_currentWeekStart, weeksInMonth, _selectedWeekIndex, _selectedMonth, _selectedYear) { start, weeks, weekIndex, month, year ->
        if (weekIndex != -1 && weeks.isNotEmpty()) {
            val week = weeks.getOrNull(weekIndex)
            if (week != null) {
                val sdf = SimpleDateFormat("dd/MM", Locale.US)
                return@combine "${sdf.format(week.start)} - ${sdf.format(week.end)}"
            }
        }
        
        if (month == -1) "Todo o Ano $year"
        else "${availableMonths[month + 1]} de $year"
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val aggregatedSummary = combine(agents, weeksInMonth, _selectedWeekIndex, _selectedMonth, _selectedYear) { filteredAgents, weeks, weekIndex, month, year ->
        if (weekIndex != -1 && weeks.isNotEmpty()) {
            val week = weeks.getOrNull(weekIndex)
            if (week != null) {
                return@combine calculateAggregateSummary(filteredAgents, week.start, week.end)
            }
        }
        
        if (month != -1) {
            // pass null bounds to prioritize pre-calculated summaries for the whole month
            return@combine calculateAggregateSummary(filteredAgents, null, null)
        }
        
        // Year Summary
        calculateAggregateSummary(filteredAgents, null, null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AggregateSummary())

    init {
        refreshData()
    }

    fun startLiveInspection(uid: String) {
        if (_focusedAgentUid.value == uid) return
        
        _focusedAgentUid.value = uid
        liveJob?.cancel()
        liveJob = viewModelScope.launch {
            val datePattern = if (_selectedMonth.value == -1) "-${_selectedYear.value}" 
                             else String.format("-%02d-%d", _selectedMonth.value + 1, _selectedYear.value)
            
            agentRepository.observeAgentProduction(uid, datePattern)
                .collect { data ->
                    _liveAgentData.value = data
                }
        }
    }

    fun stopLiveInspection() {
        _focusedAgentUid.value = null
        _liveAgentData.value = null
        liveJob?.cancel()
        liveJob = null
    }

    val liveAgentData: StateFlow<AgentData?> = _liveAgentData.asStateFlow()
    val focusedAgentUid: StateFlow<String?> = _focusedAgentUid.asStateFlow()

    fun refreshData() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            
            val calendar = Calendar.getInstance()
            val year = _selectedYear.value
            val month = _selectedMonth.value
            val weekIndex = _selectedWeekIndex.value
            
            val (since, until) = if (weekIndex != -1) {
                // Fetch ONLY raw data for the specific week
                val weeks = weeksInMonth.value
                val week = weeks.getOrNull(weekIndex)
                if (week != null) {
                    week.start.time to week.end.time
                } else {
                    -1L to -1L
                }
            } else {
                // For whole month or whole year, we prefer summaries only (lazy load houses/activities) 
                // Passing -1 ensures the repository skips fetching raw houses/activities
                -1L to -1L
            }

            val datePattern = if (month == -1) {
                "-$year"
            } else {
                val monthStr = String.format("%02d", month + 1)
                "-$monthStr-$year"
            }

            // fetchAllAgentsData handles delta agent fetch + targeted summary fetch + optional raw data fetch
            val result = agentRepository.fetchAllAgentsData(since, until, datePattern)
            if (result.isSuccess) {
                _rawAgents.value = result.getOrNull() ?: emptyList()
            } else {
                _errorMessage.value = result.exceptionOrNull()?.message ?: "Erro desconhecido ao carregar dados"
            }
            _isLoading.value = false
        }
    }

    private fun filterFutureData(agents: List<AgentData>): List<AgentData> {
        val today = Calendar.getInstance()
        val todayStr = SimpleDateFormat("yyyyMMdd", Locale.US).format(today.time)
        val todayInt = todayStr.toInt()

        return agents.map { agent ->
            val filteredHouses = agent.houses.filter { house ->
                try {
                    val dateStr = house.data.replace("/", "-")
                    val parts = dateStr.split("-")
                    if (parts.size == 3) {
                        // Bug Fix: Zero-pad day and month for correct string comparison
                        val houseDateStr = String.format("%04d%02d%02d", parts[2].toInt(), parts[1].toInt(), parts[0].toInt())
                        houseDateStr.toInt() <= todayInt
                    } else true 
                } catch (e: Exception) { true }
            }
            
            val filteredActivities = agent.activities.filter { activity ->
                try {
                    val dateStr = activity.date.replace("/", "-")
                    val parts = dateStr.split("-")
                    if (parts.size == 3) {
                        val activityDateStr = String.format("%04d%02d%02d", parts[2].toInt(), parts[1].toInt(), parts[0].toInt())
                        activityDateStr.toInt() <= todayInt
                    } else true
                } catch (e: Exception) { true }
            }
            
            // Smart Summary Preservation:
            // Always preserve the summary as the source of truth for dashboarding.
            // Our calculateAggregateSummary logic will decide whether to use it or fallback to raw data.
            val updatedSummary = agent.summary

            agent.copy(houses = filteredHouses, activities = filteredActivities, summary = updatedSummary)
        }
    }

    private fun generateWeeksForMonth(year: Int, month: Int): List<WeekRange> {
        val weeks = mutableListOf<WeekRange>()
        val cal = Calendar.getInstance()
        cal.set(year, month, 1, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        
        val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val sdf = SimpleDateFormat("dd/MM", Locale.US)
        val now = Calendar.getInstance().timeInMillis

        var weekNum = 1
        while (cal.get(Calendar.DAY_OF_MONTH) <= maxDay) {
            // Find Monday of this week (or 1st of month if 1st is not Monday)
            // Actually, we'll just group by 7 days or use standard week logic.
            // Let's use 1-7, 8-14... simplified as desired for reports
            val start = cal.time
            
            // But we must NOT allow weeks that start in the future
            if (start.time > now) break
            
            val weekEnd = Calendar.getInstance().apply {
                time = start
                add(Calendar.DAY_OF_MONTH, 6)
                if (get(Calendar.MONTH) != month) {
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, maxDay)
                }
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
            }
            
            weeks.add(WeekRange("Semana $weekNum (${sdf.format(start)} - ${sdf.format(weekEnd.time)})", start, weekEnd.time))
            
            cal.time = weekEnd.time
            cal.add(Calendar.DAY_OF_MONTH, 1)
            if (cal.get(Calendar.MONTH) != month || cal.get(Calendar.DAY_OF_MONTH) > maxDay) break
            weekNum++
        }
        return weeks
    }

    fun restoreAgentData(context: android.content.Context, agentUid: String, fileUri: android.net.Uri, targetDate: String? = null) {
        viewModelScope.launch {
            if (!authRepository.isUserAdmin()) {
                _uiEvent.emit("Permissão negada")
                return@launch
            }
            _isLoading.value = true
            try {
                val agent = _rawAgents.value.find { it.uid == agentUid }
                val existingDates = agent?.activities?.map { it.date.replace("/", "-") } ?: emptyList()
                
                val result = restoreDataUseCase(context, agentUid, fileUri, targetDate, existingDates)
                if (result.isSuccess) {
                    _uiEvent.value = "Dados restaurados com sucesso para o agente selecionado!"
                    refreshData()
                } else {
                    _uiEvent.value = "Falha na restauração: ${result.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                _uiEvent.value = "Erro durante restauração: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun nextWeek() {
        val cal = Calendar.getInstance().apply {
            time = _currentWeekStart.value
            add(Calendar.DAY_OF_YEAR, 7)
        }
        _currentWeekStart.value = cal.time
    }

    fun previousWeek() {
        val cal = Calendar.getInstance().apply {
            time = _currentWeekStart.value
            add(Calendar.DAY_OF_YEAR, -7)
        }
        _currentWeekStart.value = cal.time
    }

    private fun getMondayOfCurrentWeek(): Date {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        if (dayOfWeek == Calendar.SUNDAY) {
            cal.add(Calendar.DAY_OF_YEAR, -6)
        } else {
            cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        }
        return cal.time
    }

    private fun calculateAggregateSummary(agents: List<AgentData>, weekStart: Date?, weekEnd: Date?): AggregateSummary {
        val sdfPattern = SimpleDateFormat("dd-MM-yyyy", Locale.US)
        
        // Define the date filter if week range is provided
        val dateFilter: (String) -> Boolean = if (weekStart != null && weekEnd != null) {
            { dateStr ->
                try {
                    val date = sdfPattern.parse(dateStr.replace("/", "-"))
                    date != null && !date.before(weekStart) && !date.after(weekEnd)
                } catch (e: Exception) { false }
            }
        } else { { true } }

        var totalWorked = 0
        var totalVisits = 0
        val housesDetails = mutableListOf<StatDetail>()
        val visitsDetails = mutableListOf<StatDetail>()
        var totalFoci = 0
        val fociDetails = mutableListOf<StatDetail>()
        var totalTratados = 0
        val tratadosDetails = mutableListOf<StatDetail>()
        var totalFechados = 0
        val fechadosDetails = mutableListOf<StatDetail>()
        var totalAbandonados = 0
        val abandonadosDetails = mutableListOf<StatDetail>()
        var totalRecusados = 0
        val recusadosDetails = mutableListOf<StatDetail>()
        var activeAgentsCount = 0

        agents.forEach { agent ->
            val summary = agent.summary
            // We use the pre-calculated summary if available and we're NOT in a specific week view
            // This is crucial because raw houses/activities may be empty due to lazy loading optimizations.
            val useSummary = summary != null && (weekStart == null || weekEnd == null)
            
            if (useSummary && summary != null) {
                if (summary.totalHouses > 0 || summary.daysWorked > 0) {
                    activeAgentsCount++
                    
                    val visitCount = summary.totalHouses
                    if (visitCount > 0) {
                        totalVisits += visitCount
                        visitsDetails.add(StatDetail(agent.agentName ?: agent.email, agent.email, agent.uid, visitCount, agent.photoUrl))
                    }

                    val workedCount = (summary.situationCounts["NONE"] ?: 0) + (summary.situationCounts["EMPTY"] ?: 0)
                    if (workedCount > 0) {
                        totalWorked += workedCount
                        housesDetails.add(StatDetail(agent.agentName ?: agent.email, agent.email, agent.uid, workedCount, agent.photoUrl))
                    }
                    
                    val fociCount = summary.focusCount
                    if (fociCount > 0) {
                        totalFoci += fociCount
                        fociDetails.add(StatDetail(agent.agentName ?: agent.email, agent.email, agent.uid, fociCount, agent.photoUrl))
                    }
                    
                    val treatedCount = summary.treatedCount
                    if (treatedCount > 0) {
                        totalTratados += treatedCount
                        tratadosDetails.add(StatDetail(agent.agentName ?: agent.email, agent.email, agent.uid, treatedCount, agent.photoUrl))
                    }

                    val closedCount = summary.situationCounts["F"] ?: 0
                    if (closedCount > 0) {
                        totalFechados += closedCount
                        fechadosDetails.add(StatDetail(agent.agentName ?: agent.email, agent.email, agent.uid, closedCount, agent.photoUrl))
                    }

                    val abandonedCount = summary.situationCounts["A"] ?: 0
                    if (abandonedCount > 0) {
                        totalAbandonados += abandonedCount
                        abandonadosDetails.add(StatDetail(agent.agentName ?: agent.email, agent.email, agent.uid, abandonedCount, agent.photoUrl))
                    }

                    val recusedCount = summary.situationCounts["REC"] ?: 0
                    if (recusedCount > 0) {
                        totalRecusados += recusedCount
                        recusadosDetails.add(StatDetail(agent.agentName ?: agent.email, agent.email, agent.uid, recusedCount, agent.photoUrl))
                    }
                }
            } else {
                // FALLBACK TO RAW DATA (Required for Week views or when summary is missing)
                val periodActivities = agent.activities.filter { dateFilter(it.date) }
                val periodHouses = agent.houses.filter { dateFilter(it.data) }
                
                if (periodActivities.isNotEmpty() || periodHouses.isNotEmpty()) {
                    activeAgentsCount++
                    
                    val visitCount = periodHouses.size
                    if (visitCount > 0) {
                        totalVisits += visitCount
                        visitsDetails.add(StatDetail(agent.agentName ?: agent.email, agent.email, agent.uid, visitCount, agent.photoUrl))
                    }

                    val workedCount = periodHouses.count { 
                        it.situation == com.antigravity.healthagent.data.local.model.Situation.NONE || 
                        it.situation == com.antigravity.healthagent.data.local.model.Situation.EMPTY 
                    }
                    if (workedCount > 0) {
                        totalWorked += workedCount
                        housesDetails.add(StatDetail(agent.agentName ?: agent.email, agent.email, agent.uid, workedCount, agent.photoUrl))
                    }
                    
                    val fociCount = periodHouses.count { it.comFoco }
                    if (fociCount > 0) {
                        totalFoci += fociCount
                        fociDetails.add(StatDetail(agent.agentName ?: agent.email, agent.email, agent.uid, fociCount, agent.photoUrl))
                    }
                    
                    val treatedCount = periodHouses.count { house ->
                        (house.a1 + house.a2 + house.b + house.c + house.d1 + house.d2 + house.e + house.eliminados) > 0 ||
                        house.larvicida > 0.0 || house.comFoco
                    }
                    if (treatedCount > 0) {
                        totalTratados += treatedCount
                        tratadosDetails.add(StatDetail(agent.agentName ?: agent.email, agent.email, agent.uid, treatedCount, agent.photoUrl))
                    }

                    val closedCount = periodHouses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.F }
                    if (closedCount > 0) {
                        totalFechados += closedCount
                        fechadosDetails.add(StatDetail(agent.agentName ?: agent.email, agent.email, agent.uid, closedCount, agent.photoUrl))
                    }

                    val abandonedCount = periodHouses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.A }
                    if (abandonedCount > 0) {
                        totalAbandonados += abandonedCount
                        abandonadosDetails.add(StatDetail(agent.agentName ?: agent.email, agent.email, agent.uid, abandonedCount, agent.photoUrl))
                    }

                    val recusedCount = periodHouses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.REC }
                    if (recusedCount > 0) {
                        totalRecusados += recusedCount
                        recusadosDetails.add(StatDetail(agent.agentName ?: agent.email, agent.email, agent.uid, recusedCount, agent.photoUrl))
                    }
                }
            }
        }

        return AggregateSummary(
            totalWorked = totalWorked,
            housesDetails = housesDetails.sortedByDescending { it.count },
            totalVisits = totalVisits,
            visitsDetails = visitsDetails.sortedByDescending { it.count },
            totalFoci = totalFoci,
            fociDetails = fociDetails.sortedByDescending { it.count },
            totalTratados = totalTratados,
            tratadosDetails = tratadosDetails.sortedByDescending { it.count },
            totalFechados = totalFechados,
            fechadosDetails = fechadosDetails.sortedByDescending { it.count },
            totalAbandonados = totalAbandonados,
            abandonadosDetails = abandonadosDetails.sortedByDescending { it.count },
            totalRecusados = totalRecusados,
            recusadosDetails = recusadosDetails.sortedByDescending { it.count },
            activeAgents = activeAgentsCount,
            totalAgents = agents.size
        )
    }
}
