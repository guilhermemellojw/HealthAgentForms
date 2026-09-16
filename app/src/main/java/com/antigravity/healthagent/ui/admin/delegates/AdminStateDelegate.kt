package com.antigravity.healthagent.ui.admin.delegates

import com.antigravity.healthagent.domain.repository.AuthUser
import com.antigravity.healthagent.domain.repository.AgentData
import com.antigravity.healthagent.domain.repository.AccessRequest
import com.antigravity.healthagent.domain.repository.BackupMetadata
import com.antigravity.healthagent.ui.admin.AdminUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.Calendar
import javax.inject.Inject

class AdminStateDelegate @Inject constructor() : AdminState {
    override val uiState = MutableStateFlow<AdminUiState>(AdminUiState.Loading)
    override val users = MutableStateFlow<List<AuthUser>>(emptyList())
    override val isLoading = MutableStateFlow(false)
    override val uiEvent = MutableSharedFlow<String>()
    override val bairros = MutableStateFlow<List<String>>(emptyList())
    override val systemSettings = MutableStateFlow<Map<String, Any>>(emptyMap())
    override val agentNames = MutableStateFlow<List<String>>(emptyList())
    override val searchQuery = MutableStateFlow("")
    override val selectedYear = MutableStateFlow(Calendar.getInstance().get(Calendar.YEAR))
    override val selectedMonth = MutableStateFlow(Calendar.getInstance().get(Calendar.MONTH))
    override val accessRequests = MutableStateFlow<List<AccessRequest>>(emptyList())
    override val selectedAgentForEdit = MutableStateFlow<AgentData?>(null)
    override val timeline = MutableStateFlow<List<BackupMetadata>>(emptyList())
    override val isTimelineLoading = MutableStateFlow(false)
}
