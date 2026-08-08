package com.antigravity.healthagent.ui.supervisor.delegates

import com.antigravity.healthagent.domain.repository.AgentData
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import javax.inject.Inject

class SupervisorStateDelegate @Inject constructor() : SupervisorState {
    private val tz = TimeZone.getTimeZone("America/Sao_Paulo")

    override val uiEvent = MutableStateFlow<String?>(null)
    override val rawAgents = MutableStateFlow<List<AgentData>>(emptyList())
    override val focusedAgentUid = MutableStateFlow<String?>(null)
    override val liveAgentData = MutableStateFlow<AgentData?>(null)
    override val searchQuery = MutableStateFlow("")
    override val expandedUids = MutableStateFlow<Set<String>>(emptySet())
    override val isLoading = MutableStateFlow(false)
    override val errorMessage = MutableStateFlow<String?>(null)
    override val selectedYear = MutableStateFlow(Calendar.getInstance(tz).get(Calendar.YEAR))
    override val selectedMonth = MutableStateFlow(Calendar.getInstance(tz).get(Calendar.MONTH))
    override val selectedWeekIndex = MutableStateFlow(-1)
    override val currentWeekStart = MutableStateFlow(getMondayOfCurrentWeek())

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
}
