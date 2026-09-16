package com.antigravity.healthagent.ui.supervisor.delegates

import com.antigravity.healthagent.domain.repository.AgentData
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Date

interface SupervisorState {
    val uiEvent: MutableStateFlow<String?>
    val rawAgents: MutableStateFlow<List<AgentData>>
    val focusedAgentUid: MutableStateFlow<String?>
    val liveAgentData: MutableStateFlow<AgentData?>
    val searchQuery: MutableStateFlow<String>
    val expandedUids: MutableStateFlow<Set<String>>
    val isLoading: MutableStateFlow<Boolean>
    val errorMessage: MutableStateFlow<String?>
    val selectedYear: MutableStateFlow<Int>
    val selectedMonth: MutableStateFlow<Int>
    val selectedWeekIndex: MutableStateFlow<Int>
    val currentWeekStart: MutableStateFlow<Date>
}
