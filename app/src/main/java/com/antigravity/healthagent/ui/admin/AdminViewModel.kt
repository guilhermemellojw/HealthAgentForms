package com.antigravity.healthagent.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.healthagent.domain.repository.AgentData
import com.antigravity.healthagent.domain.repository.SyncRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AdminViewModel @Inject constructor(
    private val syncRepository: SyncRepository,
    private val authRepository: com.antigravity.healthagent.domain.repository.AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<AdminUiState>(AdminUiState.Loading)
    val uiState: StateFlow<AdminUiState> = _uiState.asStateFlow()

    private val _users = MutableStateFlow<List<com.antigravity.healthagent.domain.repository.AuthUser>>(emptyList())
    val users: StateFlow<List<com.antigravity.healthagent.domain.repository.AuthUser>> = _users.asStateFlow()

    init {
        refreshAll()
    }

    fun refreshAll() {
        _uiState.value = AdminUiState.Loading
        viewModelScope.launch {
            loadAgentsData()
            loadUsers()
        }
    }

    private suspend fun loadAgentsData() {
        val result = syncRepository.fetchAllAgentsData()
        if (result.isSuccess) {
            _uiState.value = AdminUiState.Success(result.getOrNull() ?: emptyList())
        } else {
            _uiState.value = AdminUiState.Error(result.exceptionOrNull()?.message ?: "Erro ao carregar dados dos agentes")
        }
    }

    private suspend fun loadUsers() {
        val result = authRepository.fetchAllUsers()
        if (result.isSuccess) {
            _users.value = result.getOrNull() ?: emptyList()
        }
    }

    private val _selectedAgentForEdit = MutableStateFlow<AgentData?>(null)
    val selectedAgentForEdit: StateFlow<AgentData?> = _selectedAgentForEdit.asStateFlow()

    fun selectAgentForEdit(agent: AgentData?) {
        _selectedAgentForEdit.value = agent
    }

    fun authorizeUser(uid: String, isAuthorized: Boolean) {
        viewModelScope.launch {
            val result = authRepository.authorizeUser(uid, isAuthorized)
            if (result.isSuccess) {
                loadUsers()
            }
        }
    }

    fun changeUserRole(uid: String, role: com.antigravity.healthagent.domain.repository.UserRole) {
        viewModelScope.launch {
            val result = authRepository.changeUserRole(uid, role)
            if (result.isSuccess) {
                loadUsers()
            }
        }
    }
}

sealed class AdminUiState {
    object Loading : AdminUiState()
    data class Success(val agents: List<AgentData>) : AdminUiState()
    data class Error(val message: String) : AdminUiState()
}
