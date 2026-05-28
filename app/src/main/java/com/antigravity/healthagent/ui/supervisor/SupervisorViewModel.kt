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
import com.antigravity.healthagent.ui.state.SyncUiState

import com.antigravity.healthagent.utils.toNumericDate

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
    private val restoreDataUseCase: RestoreDataUseCase,
    private val settingsManager: com.antigravity.healthagent.data.settings.SettingsManager
) : ViewModel() {

    private val _uiEvent = MutableStateFlow<String?>(null)
    val uiEvent: StateFlow<String?> = _uiEvent.asStateFlow()

    val solarMode: StateFlow<Boolean> = settingsManager.solarMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun clearUiEvent() { _uiEvent.value = null }

    fun clearError() { _errorMessage.value = null }

    private val _rawAgents = MutableStateFlow<List<AgentData>>(emptyList())
    
    // LIVE INSPECTION STATE
    private val _focusedAgentUid = MutableStateFlow<String?>(null)
    private val _liveAgentData = MutableStateFlow<AgentData?>(null)
    private var liveJob: kotlinx.coroutines.Job? = null
    
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _expandedUids = MutableStateFlow<Set<String>>(emptySet())
    val expandedUids = _expandedUids.asStateFlow()

    fun toggleAgentExpanded(uid: String) {
        val current = _expandedUids.value
        _expandedUids.value = if (current.contains(uid)) current - uid else current + uid
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    val agents: StateFlow<List<AgentData>> = combine(_rawAgents, _searchQuery) { raw, query ->
        val filteredByDate = filterFutureData(raw)
        val filteredBySearch = if (query.isBlank()) filteredByDate
        else filteredByDate.filter { 
            it.agentName?.contains(query, ignoreCase = true) == true || 
            it.email.contains(query, ignoreCase = true)
        }
        
        // Locale-aware sorting (PT-BR)
        val collator = java.text.Collator.getInstance(java.util.Locale("pt", "BR"))
        filteredBySearch.sortedWith { a, b ->
            collator.compare(a.agentName ?: a.email, b.agentName ?: b.email)
        }
    }
    .flowOn(kotlinx.coroutines.Dispatchers.Default)
    .distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _syncState = MutableStateFlow<SyncUiState>(SyncUiState.Idle())
    val syncState: StateFlow<SyncUiState> = _syncState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val tz = java.util.TimeZone.getTimeZone("America/Sao_Paulo")

    private val _selectedYear = MutableStateFlow(Calendar.getInstance(tz).get(Calendar.YEAR))
    val selectedYear = _selectedYear.asStateFlow()

    private val _selectedMonth = MutableStateFlow(Calendar.getInstance(tz).get(Calendar.MONTH)) // Default to current month
    val selectedMonth = _selectedMonth.asStateFlow()

    private val _selectedWeekIndex = MutableStateFlow(-1) // -1 for "Mês Todo"
    val selectedWeekIndex = _selectedWeekIndex.asStateFlow()

    val availableYears = (2025..Calendar.getInstance(tz).get(Calendar.YEAR)).reversed().toList()
    
    val availableMonths = listOf("Ano Todo", "Jan", "Fev", "Mar", "Abr", "Mai", "Jun", "Jul", "Ago", "Set", "Out", "Nov", "Dez")

    fun getFilteredMonths(): List<String> {
        val currentYear = Calendar.getInstance(tz).get(Calendar.YEAR)
        val currentMonth = Calendar.getInstance(tz).get(Calendar.MONTH)
        
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
        val currentYear = Calendar.getInstance(tz).get(Calendar.YEAR)
        val currentMonth = Calendar.getInstance(tz).get(Calendar.MONTH)
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
                val sdf = SimpleDateFormat("dd/MM", Locale.US).apply { timeZone = tz }
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
        viewModelScope.launch {
            settingsManager.lastSyncTimestamp.collect { ts ->
                _syncState.value = SyncUiState.Idle(lastSyncTime = if (ts > 0L) ts else null)
            }
        }
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
                    _liveAgentData.value = data?.let { filterFutureData(listOf(it)).firstOrNull() }
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
        if (_syncState.value is SyncUiState.Syncing) return
        _syncState.value = SyncUiState.Syncing(progress = 0.5f, message = "Atualizando dados...", lastSyncTime = _syncState.value.lastSyncTime)
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            
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
            } else if (month != -1) {
                // For whole month: if it is the CURRENT month/year, we MUST fetch raw data
                // because we force recalculation from raw data.
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
                // For whole year ("Ano Todo"):
                // If it is the CURRENT year, we must fetch raw data for the current month
                // so we can recalculate the current month's totals with the embargo limit.
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
                // fetchAllAgentsData handles delta agent fetch + targeted summary fetch + optional raw data fetch
                val result = agentRepository.fetchAllAgentsData(since, until, datePattern)
                if (result.isSuccess) {
                    _rawAgents.value = result.getOrNull() ?: emptyList()
                    val now = System.currentTimeMillis()
                    settingsManager.setLastSyncTimestamp(now)
                    _syncState.value = SyncUiState.Success(lastSyncTime = now)
                } else {
                    val errMsg = result.exceptionOrNull()?.message ?: "Erro desconhecido ao carregar dados"
                    _errorMessage.value = errMsg
                    _syncState.value = SyncUiState.Error(message = errMsg, lastSyncTime = _syncState.value.lastSyncTime)
                }
            } catch (e: Exception) {
                val errMsg = e.message ?: "Erro ao atualizar dados"
                _errorMessage.value = errMsg
                _syncState.value = SyncUiState.Error(message = errMsg, lastSyncTime = _syncState.value.lastSyncTime)
            } finally {
                _isLoading.value = false
                _syncState.value = SyncUiState.Idle(lastSyncTime = _syncState.value.lastSyncTime)
            }
        }
    }

    private fun filterFutureData(agents: List<AgentData>): List<AgentData> {
        val tz = java.util.TimeZone.getTimeZone("America/Sao_Paulo")
        val cal = Calendar.getInstance(tz)
        val hourOfDay = cal.get(Calendar.HOUR_OF_DAY)
        
        val filterCal = Calendar.getInstance(tz)
        if (hourOfDay < 12) {
            filterCal.add(Calendar.DAY_OF_YEAR, -1) // Limit to yesterday
        }
        
        val limitStr = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = tz }.format(filterCal.time)
        val limitInt = limitStr.toInt()

        return agents.map { agent ->
            val filteredHouses = agent.houses.filter { house ->
                val numericDate = house.data.toNumericDate()
                numericDate != null && numericDate <= limitInt
            }
            
            val filteredActivities = agent.activities.filter { activity ->
                val numericDate = activity.date.toNumericDate()
                numericDate != null && numericDate <= limitInt
            }
            
            // Smart Summary Privacy:
            // We only show the summary if it belongs to a PAST month.
            // If it's the current month, we nullify it to force recalculation from raw data,
            // ensuring the 12:00 PM embargo and future production filters are respected.
            val now = Calendar.getInstance(tz)
            val cMonth = now.get(Calendar.MONTH) + 1
            val cYear = now.get(Calendar.YEAR)
            
            val updatedSummary = agent.summary?.let { s ->
                val parts = s.monthYear.split("-")
                if (parts.size == 2) {
                    val sMonth = parts[0].toInt()
                    val sYear = parts[1].toInt()
                    // Safe ONLY if year < current OR (year == current AND month < current)
                    val isSafe = sYear < cYear || (sYear == cYear && sMonth < cMonth)
                    if (isSafe) s else null
                } else {
                    // Yearly summary (e.g. "2026")
                    try {
                        val sYear = s.monthYear.toInt()
                        if (sYear <= cYear) s else null
                    } catch (e: Exception) {
                        null
                    }
                }
            }

            agent.copy(houses = filteredHouses, activities = filteredActivities, summary = updatedSummary)
        }
    }

    private fun generateWeeksForMonth(year: Int, month: Int): List<WeekRange> {
        val weeks = mutableListOf<WeekRange>()
        val cal = Calendar.getInstance(tz)
        cal.set(year, month, 1, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        
        // Find the Sunday that starts the week containing the 1st of the month
        while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY) {
            cal.add(Calendar.DAY_OF_MONTH, -1)
        }
        
        val maxDayOfMonth = Calendar.getInstance(tz).apply { set(year, month, 1) }.getActualMaximum(Calendar.DAY_OF_MONTH)
        val endOfMonth = Calendar.getInstance(tz).apply {
            set(year, month, maxDayOfMonth, 23, 59, 59)
            set(Calendar.MILLISECOND, 999)
        }

        val sdf = SimpleDateFormat("dd/MM", Locale.US).apply { timeZone = tz }
        val now = Calendar.getInstance(tz).timeInMillis

        var weekNum = 1
        while (cal.timeInMillis <= endOfMonth.timeInMillis) {
            val start = cal.time
            if (start.time > now) break
            
            val weekEnd = Calendar.getInstance(tz).apply {
                time = start
                add(Calendar.DAY_OF_MONTH, 6)
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
            }
            
            weeks.add(WeekRange("Semana $weekNum (${sdf.format(start)} - ${sdf.format(weekEnd.time)})", start, weekEnd.time))
            
            cal.time = weekEnd.time
            cal.add(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            
            weekNum++
        }
        return weeks
    }

    fun restoreAgentData(context: android.content.Context, agentUid: String, fileUri: android.net.Uri, targetDate: String? = null) {
        viewModelScope.launch {
            if (!accessControlRepository.isUserAdmin()) {
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
        val cal = Calendar.getInstance(tz).apply {
            time = _currentWeekStart.value
            add(Calendar.DAY_OF_YEAR, 7)
        }
        _currentWeekStart.value = cal.time
    }

    fun previousWeek() {
        val cal = Calendar.getInstance(tz).apply {
            time = _currentWeekStart.value
            add(Calendar.DAY_OF_YEAR, -7)
        }
        _currentWeekStart.value = cal.time
    }

    private fun getMondayOfCurrentWeek(): Date {
        val cal = Calendar.getInstance(tz)
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
        
        // Optimize: Convert Date boundaries to Long once for faster comparison
        val startT = weekStart?.time ?: 0L
        val endT = weekEnd?.time ?: Long.MAX_VALUE
        
        // OPTIMIZATION: Calculate embargo limit ONCE outside the filter loop
        val tz = java.util.TimeZone.getTimeZone("America/Sao_Paulo")
        val nowCal = Calendar.getInstance(tz)
        val hourOfDay = nowCal.get(Calendar.HOUR_OF_DAY)
        val limitCal = Calendar.getInstance(tz)
        if (hourOfDay < 12) {
            limitCal.add(Calendar.DAY_OF_YEAR, -1)
        }
        val limitNumeric = limitCal.get(Calendar.YEAR).toLong() * 10000 + (limitCal.get(Calendar.MONTH) + 1).toLong() * 100 + limitCal.get(Calendar.DAY_OF_MONTH).toLong()

        // Cache weekly boundaries in numeric format for performance
        val startNumeric = if (weekStart != null) {
            val c = Calendar.getInstance(tz).apply { time = Date(startT) }
            c.get(Calendar.YEAR).toLong() * 10000 + (c.get(Calendar.MONTH) + 1).toLong() * 100 + c.get(Calendar.DAY_OF_MONTH).toLong()
        } else 0L

        val endNumeric = if (weekEnd != null) {
            val c = Calendar.getInstance(tz).apply { time = Date(endT) }
            c.get(Calendar.YEAR).toLong() * 10000 + (c.get(Calendar.MONTH) + 1).toLong() * 100 + c.get(Calendar.DAY_OF_MONTH).toLong()
        } else Long.MAX_VALUE

        val year = _selectedYear.value
        val month = _selectedMonth.value

        val selectedMonthStartNumeric = if (month != -1) {
            year.toLong() * 10000 + (month + 1).toLong() * 100 + 1
        } else 0L

        val selectedMonthEndNumeric = if (month != -1) {
            year.toLong() * 10000 + (month + 1).toLong() * 100 + 31
        } else Long.MAX_VALUE

        val selectedYearStartNumeric = year.toLong() * 10000 + 101
        val selectedYearEndNumeric = year.toLong() * 10000 + 1231

        // Define the date filter
        val dateFilter: (String) -> Boolean = { dateStr ->
            val numericDate = dateStr.toNumericDate()
            if (numericDate != null) {
                // Filter out any data beyond the allowed limit (Safe by default: > limitNumeric is hidden)
                if (numericDate > limitNumeric) {
                    false
                } else if (weekStart != null && weekEnd != null) {
                    // If in weekly view, also check range
                    numericDate in startNumeric..endNumeric
                } else if (month != -1) {
                    // Restrict strictly to selected month of the selected year
                    numericDate in selectedMonthStartNumeric..selectedMonthEndNumeric
                } else {
                    // Restrict strictly to the selected year
                    numericDate in selectedYearStartNumeric..selectedYearEndNumeric
                }
            } else false // Malformed date: hide it
        }

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

        val now = Calendar.getInstance(tz)
        val currentYear = now.get(Calendar.YEAR)
        val currentMonth = now.get(Calendar.MONTH)
        
        agents.forEach { agent ->
            val summary = agent.summary
            // PRIVACY LOGIC: We can only use the cloud summary if the month is in the PAST.
            // If the summary is for the current month (or a future one), we MUST recalculate 
            // from raw data to respect the 12:00 PM embargo and hide future productions.
            val isSummarySafe = if (summary != null) {
                val parts = summary.monthYear.split("-")
                if (parts.size == 2) {
                    val sMonth = parts[0].toInt() - 1 // 0-based
                    val sYear = parts[1].toInt()
                    // Safe ONLY if year < current OR (year == current AND month < current)
                    sYear < currentYear || (sYear == currentYear && sMonth < currentMonth)
                } else {
                    // Yearly summary (e.g. "2026")
                    try {
                        val sYear = summary.monthYear.toInt()
                        sYear <= currentYear
                    } catch (e: Exception) {
                        false
                    }
                }
            } else false

            val useSummary = summary != null && (weekStart == null || weekEnd == null) && isSummarySafe
            
            if (useSummary && summary != null) {
                if (summary.totalHouses > 0 || summary.daysWorked > 0) {
                    activeAgentsCount++
                    val displayName = pickBestDisplayName(agent.agentName, agent.email)
                    
                    var visitCount = summary.totalHouses
                    var workedCount = (summary.situationCounts["NONE"] ?: 0) + (summary.situationCounts["EMPTY"] ?: 0)
                    var fCount = summary.focusCount
                    var tCount = summary.treatedCount
                    var fclosedCount = summary.situationCounts["F"] ?: 0
                    var abandonedCount = summary.situationCounts["A"] ?: 0
                    var recusadosCount = summary.situationCounts["REC"] ?: 0
                    var vaziosCount = summary.situationCounts["V"] ?: 0

                    val isCurrentYearSummary = !summary.monthYear.contains("-") && summary.monthYear.toInt() == currentYear
                    if (isCurrentYearSummary) {
                        // Current year! Add the current month's raw houses (which are fetched and filtered by dateFilter/embargo)
                        val currentMonthHouses = agent.houses.filter { dateFilter(it.data) }
                        
                        visitCount += currentMonthHouses.size
                        workedCount += currentMonthHouses.count { 
                            it.situation == com.antigravity.healthagent.data.local.model.Situation.NONE || 
                            it.situation == com.antigravity.healthagent.data.local.model.Situation.EMPTY 
                        }
                        fCount += currentMonthHouses.count { it.treatment.comFoco }
                        tCount += currentMonthHouses.count { it.treatment.hasAnyTreatment }
                        fclosedCount += currentMonthHouses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.F }
                        abandonedCount += currentMonthHouses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.A }
                        recusadosCount += currentMonthHouses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.REC }
                        vaziosCount += currentMonthHouses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.V }
                    }

                    if (visitCount > 0) {
                        totalVisits += visitCount
                        visitsDetails.add(StatDetail(displayName, agent.email, agent.uid, visitCount, agent.photoUrl))
                    }

                    if (workedCount > 0) {
                        totalWorked += workedCount
                        housesDetails.add(StatDetail(displayName, agent.email, agent.uid, workedCount, agent.photoUrl))
                    }
                    
                    if (fCount > 0) {
                        totalFoci += fCount
                        fociDetails.add(StatDetail(displayName, agent.email, agent.uid, fCount, agent.photoUrl))
                    }
                    
                    if (tCount > 0) {
                        totalTratados += tCount
                        tratadosDetails.add(StatDetail(displayName, agent.email, agent.uid, tCount, agent.photoUrl))
                    }

                    if (fclosedCount > 0) {
                        totalFechados += fclosedCount
                        fechadosDetails.add(StatDetail(displayName, agent.email, agent.uid, fclosedCount, agent.photoUrl))
                    }

                    if (abandonedCount > 0) {
                        totalAbandonados += abandonedCount
                        abandonadosDetails.add(StatDetail(displayName, agent.email, agent.uid, abandonedCount, agent.photoUrl))
                    }

                    if (recusadosCount > 0) {
                        totalRecusados += recusadosCount
                        recusadosDetails.add(StatDetail(displayName, agent.email, agent.uid, recusadosCount, agent.photoUrl))
                    }
                }
            } else {
                // FALLBACK TO RAW DATA (Filtered by date to hide future productions)
                val periodActivities = agent.activities.filter { dateFilter(it.date) }
                val periodHouses = agent.houses.filter { dateFilter(it.data) }
                
                if (periodActivities.isNotEmpty() || periodHouses.isNotEmpty()) {
                    activeAgentsCount++
                    val displayName = pickBestDisplayName(agent.agentName, agent.email)
                    
                    val visitCount = periodHouses.size
                    if (visitCount > 0) {
                        totalVisits += visitCount
                        visitsDetails.add(StatDetail(displayName, agent.email, agent.uid, visitCount, agent.photoUrl))
                    }

                    val workedCount = periodHouses.count { 
                        it.situation == com.antigravity.healthagent.data.local.model.Situation.NONE || 
                        it.situation == com.antigravity.healthagent.data.local.model.Situation.EMPTY 
                    }
                    if (workedCount > 0) {
                        totalWorked += workedCount
                        housesDetails.add(StatDetail(displayName, agent.email, agent.uid, workedCount, agent.photoUrl))
                    }
                    
                    val fociCount = periodHouses.count { it.treatment.comFoco }
                    if (fociCount > 0) {
                        totalFoci += fociCount
                        fociDetails.add(StatDetail(displayName, agent.email, agent.uid, fociCount, agent.photoUrl))
                    }

                    val treatedCount = periodHouses.count { it.treatment.hasAnyTreatment }
                    if (treatedCount > 0) {
                        totalTratados += treatedCount
                        tratadosDetails.add(StatDetail(displayName, agent.email, agent.uid, treatedCount, agent.photoUrl))
                    }

                    val closedCount = periodHouses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.F }
                    if (closedCount > 0) {
                        totalFechados += closedCount
                        fechadosDetails.add(StatDetail(displayName, agent.email, agent.uid, closedCount, agent.photoUrl))
                    }

                    val abandonedCount = periodHouses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.A }
                    if (abandonedCount > 0) {
                        totalAbandonados += abandonedCount
                        abandonadosDetails.add(StatDetail(displayName, agent.email, agent.uid, abandonedCount, agent.photoUrl))
                    }

                    val recusedCount = periodHouses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.REC }
                    if (recusedCount > 0) {
                        totalRecusados += recusedCount
                        recusadosDetails.add(StatDetail(displayName, agent.email, agent.uid, recusedCount, agent.photoUrl))
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

    private fun pickBestDisplayName(name: String?, email: String): String {
        val n = name?.trim() ?: ""
        return if (n.isBlank() || n.contains("@")) {
            email.substringBefore("@").uppercase()
        } else {
            n.uppercase()
        }
    }
}
