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
    private val authRepository: com.antigravity.healthagent.domain.repository.AuthRepository,
    private val restoreDataUseCase: com.antigravity.healthagent.domain.usecase.RestoreDataUseCase,
    private val settingsManager: com.antigravity.healthagent.data.settings.SettingsManager
) : ViewModel() {

    private val _uiEvent = MutableStateFlow<String?>(null)
    val uiEvent: StateFlow<String?> = _uiEvent.asStateFlow()

    val solarMode: StateFlow<Boolean> = settingsManager.solarMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun clearUiEvent() { _uiEvent.value = null }

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
        val sdf = SimpleDateFormat("dd/MM", Locale.US)
        "${sdf.format(start)} - ${sdf.format(end)}"
    }.flowOn(kotlinx.coroutines.Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val aggregatedWeeklySummary = combine(_agents, _currentWeekStart) { agents, weekStart ->
        calculateAggregateSummary(agents, weekStart)
    }.flowOn(kotlinx.coroutines.Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AggregateSummary())

    init {
        refreshData()
    }

    fun refreshData() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
            val result = syncRepository.fetchAllAgentsData(thirtyDaysAgo)
            if (result.isSuccess) {
                _agents.value = result.getOrNull() ?: emptyList()
            } else {
                _errorMessage.value = result.exceptionOrNull()?.message ?: "Erro desconhecido ao carregar dados"
            }
            _isLoading.value = false
        }
    }

    fun restoreAgentData(context: android.content.Context, agentUid: String, fileUri: android.net.Uri, targetDate: String? = null) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val agent = _agents.value.find { it.uid == agentUid }
                val existingDates = agent?.activities?.map { it.date.replace("/", "-") } ?: emptyList()
                
                val result = restoreDataUseCase(context, agentUid, fileUri, targetDate, existingDates)
                if (result.isSuccess) {
                    _uiEvent.value = "Dados restaurados com sucesso para o agente selecionado!"
                    refreshData() // Reload summary to reflect changes
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
            SimpleDateFormat("dd-MM-yyyy", Locale.US).format(cal.time)
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

        agents.forEach { agent ->
            // Filter activities for this week
            val weekActivities = agent.activities.filter { it.date in weekDates }
            if (weekActivities.isNotEmpty()) {
                activeAgentsCount++
                
                val weekHouses = agent.houses.filter { it.data in weekDates }
                
                // VISITAS (Total non-empty)
                val visitCount = weekHouses.size
                if (visitCount > 0) {
                    totalVisits += visitCount
                    visitsDetails.add(StatDetail(agent.agentName ?: agent.email, agent.email, agent.uid, visitCount))
                }

                // ABERTOS / TRABALHADOS (NONE situation)
                val workedCount = weekHouses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.NONE }
                if (workedCount > 0) {
                    totalWorked += workedCount
                    housesDetails.add(StatDetail(agent.agentName ?: agent.email, agent.email, agent.uid, workedCount))
                }
                
                val fociCount = weekHouses.count { it.comFoco }
                if (fociCount > 0) {
                    totalFoci += fociCount
                    fociDetails.add(StatDetail(agent.agentName ?: agent.email, agent.email, agent.uid, fociCount))
                }
                
                val treatedCount = weekHouses.count { house ->
                    (house.a1 + house.a2 + house.b + house.c + house.d1 + house.d2 + house.e + house.eliminados) > 0 ||
                    house.larvicida > 0.0 || house.comFoco
                }
                if (treatedCount > 0) {
                    totalTratados += treatedCount
                    tratadosDetails.add(StatDetail(agent.agentName ?: agent.email, agent.email, agent.uid, treatedCount))
                }

                val closedCount = weekHouses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.F }
                if (closedCount > 0) {
                    totalFechados += closedCount
                    fechadosDetails.add(StatDetail(agent.agentName ?: agent.email, agent.email, agent.uid, closedCount))
                }

                val abandonedCount = weekHouses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.A }
                if (abandonedCount > 0) {
                    totalAbandonados += abandonedCount
                    abandonadosDetails.add(StatDetail(agent.agentName ?: agent.email, agent.email, agent.uid, abandonedCount))
                }

                val recusedCount = weekHouses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.REC }
                if (recusedCount > 0) {
                    totalRecusados += recusedCount
                    recusadosDetails.add(StatDetail(agent.agentName ?: agent.email, agent.email, agent.uid, recusedCount))
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

data class StatDetail(
    val agentName: String,
    val agentEmail: String,
    val agentUid: String,
    val count: Int
)

data class AggregateSummary(
    val totalWorked: Int = 0, // Abertos
    val housesDetails: List<StatDetail> = emptyList(),
    val totalVisits: Int = 0, // Visitas
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
