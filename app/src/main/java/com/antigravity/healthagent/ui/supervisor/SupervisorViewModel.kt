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
            val result = syncRepository.fetchAllAgentsData()
            if (result.isSuccess) {
                _agents.value = result.getOrNull() ?: emptyList()
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
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            if (time > Date()) {
                add(Calendar.DAY_OF_YEAR, -7)
            }
        }.time
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
        var activeAgentsCount = 0
        val agentEmailSet = mutableSetOf<String>()

        agents.forEach { agent ->
            // Filter activities for this week
            val weekActivities = agent.activities.filter { it.date in weekDates }
            if (weekActivities.isNotEmpty()) {
                activeAgentsCount++
                agentEmailSet.add(agent.email)
                
                weekActivities.forEach { activity ->
                    totalHouses += activity.totalHouses
                    // Note: We need focus data. agent.houses has comFoco.
                    // But agent.houses is the CURRENT stock of houses. 
                    // activities usually represent a day's report.
                    // If House object has the date it was visited, we can count focos per week.
                }
                
                // Aggregate focos from houses visited this week
                totalFoci += agent.houses.count { house ->
                    // Assuming house.data is the visit date in "dd-MM-yyyy"
                    house.data in weekDates && house.comFoco
                }
            }
        }

        return AggregateSummary(
            totalHouses = totalHouses,
            totalFoci = totalFoci,
            activeAgents = activeAgentsCount,
            totalAgents = agents.size
        )
    }
}

data class AggregateSummary(
    val totalHouses: Int = 0,
    val totalFoci: Int = 0,
    val activeAgents: Int = 0,
    val totalAgents: Int = 0
)
