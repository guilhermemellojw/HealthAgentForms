package com.antigravity.healthagent.ui.supervisor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.healthagent.domain.repository.AgentData
import com.antigravity.healthagent.domain.repository.SyncRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@HiltViewModel
class SupervisorViewModel @Inject constructor(
    private val syncRepository: SyncRepository,
    private val authRepository: com.antigravity.healthagent.domain.repository.AuthRepository
) : ViewModel() {

    private val _agents = MutableStateFlow<List<AgentData>>(emptyList())
    val agents: StateFlow<List<AgentData>> = _agents.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _currentWeekStart = MutableStateFlow(getMondayOfCurrentWeek())
    
    val weekRangeText = _currentWeekStart.map { start ->
        val end = Calendar.getInstance().apply {
            time = start
            add(Calendar.DAY_OF_YEAR, 6)
        }.time
        val sdf = SimpleDateFormat("dd/MM", Locale.getDefault())
        "${sdf.format(start)} - ${sdf.format(end)}"
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val aggregatedWeeklySummary = combine(_agents, _currentWeekStart) { agents, weekStart ->
        calculateAggregateSummary(agents, weekStart)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AggregateSummary())

    init {
        refreshData()
    }

    fun refreshData() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            val result = syncRepository.fetchAllAgentsData()
            if (result.isSuccess) {
                _agents.value = result.getOrNull() ?: emptyList()
            } else {
                _errorMessage.value = result.exceptionOrNull()?.message ?: "Erro desconhecido ao carregar dados"
            }
            _isLoading.value = false
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
        
        // In Brazil/Latin settings, sometimes Sunday is the first day of the week.
        // We want the Monday of the current "work week".
        // If today is Sunday, we might want the Monday of the week that just ended.
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        if (dayOfWeek == Calendar.SUNDAY) {
            cal.add(Calendar.DAY_OF_YEAR, -6) // Go to previous Monday
        } else {
            cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        }
        return cal.time
    }

    private fun calculateAggregateSummary(agents: List<AgentData>, weekStart: Date): AggregateSummary {
        val cal = Calendar.getInstance()
        val weekDates = (0..6).map { i ->
            cal.time = weekStart
            cal.add(Calendar.DAY_OF_YEAR, i)
            SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(cal.time)
        }

        var totalHouses = 0
        var totalFoci = 0
        var totalTratados = 0
        var totalFechados = 0
        var totalAbandonados = 0
        var totalRecusados = 0
        var activeAgentsCount = 0

        agents.forEach { agent ->
            // Filter activities for this week
            val weekActivities = agent.activities.filter { it.date in weekDates }
            if (weekActivities.isNotEmpty()) {
                activeAgentsCount++
                
                val weekHouses = agent.houses.filter { it.data in weekDates }
                
                totalHouses += weekHouses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.NONE }
                
                totalFoci += weekHouses.count { it.comFoco }
                
                totalTratados += weekHouses.count { house ->
                    house.a1 > 0 || house.a2 > 0 || house.b > 0 || house.c > 0 ||
                    house.d1 > 0 || house.d2 > 0 || house.e > 0 || house.eliminados > 0
                }

                totalFechados += weekHouses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.F }
                totalAbandonados += weekHouses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.A }
                totalRecusados += weekHouses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.REC }
            }
        }

        return AggregateSummary(
            totalHouses = totalHouses,
            totalFoci = totalFoci,
            totalTratados = totalTratados,
            totalFechados = totalFechados,
            totalAbandonados = totalAbandonados,
            totalRecusados = totalRecusados,
            activeAgents = activeAgentsCount,
            totalAgents = agents.size
        )
    }
}

data class AggregateSummary(
    val totalHouses: Int = 0,
    val totalFoci: Int = 0,
    val totalTratados: Int = 0,
    val totalFechados: Int = 0,
    val totalAbandonados: Int = 0,
    val totalRecusados: Int = 0,
    val activeAgents: Int = 0,
    val totalAgents: Int = 0
)
