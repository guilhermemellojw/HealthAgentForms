package com.antigravity.healthagent.ui.admin.delegates

import com.antigravity.healthagent.domain.repository.AuthUser
import com.antigravity.healthagent.domain.repository.AgentData
import com.antigravity.healthagent.domain.repository.AccessRequest
import com.antigravity.healthagent.domain.repository.BackupMetadata
import com.antigravity.healthagent.ui.state.SyncUiState
import com.antigravity.healthagent.ui.admin.AdminUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow

interface AdminState {
    val uiState: MutableStateFlow<AdminUiState>
    val syncState: MutableStateFlow<SyncUiState>
    val users: MutableStateFlow<List<AuthUser>>
    val isLoading: MutableStateFlow<Boolean>
    val uiEvent: MutableSharedFlow<String>
    val bairros: MutableStateFlow<List<String>>
    val systemSettings: MutableStateFlow<Map<String, Any>>
    val agentNames: MutableStateFlow<List<String>>
    val searchQuery: MutableStateFlow<String>
    val selectedYear: MutableStateFlow<Int>
    val selectedMonth: MutableStateFlow<Int>
    val accessRequests: MutableStateFlow<List<AccessRequest>>
    val selectedAgentForEdit: MutableStateFlow<AgentData?>
    val timeline: MutableStateFlow<List<BackupMetadata>>
    val isTimelineLoading: MutableStateFlow<Boolean>
}
