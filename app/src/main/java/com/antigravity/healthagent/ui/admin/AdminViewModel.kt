package com.antigravity.healthagent.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.healthagent.domain.repository.AgentData
import com.antigravity.healthagent.domain.repository.SyncRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.content.Context
import android.net.Uri
import com.antigravity.healthagent.data.backup.BackupManager

@HiltViewModel
class AdminViewModel @Inject constructor(
    private val syncRepository: SyncRepository,
    private val authRepository: com.antigravity.healthagent.domain.repository.AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<AdminUiState>(AdminUiState.Loading)
    val uiState: StateFlow<AdminUiState> = _uiState.asStateFlow()

    private val _users = MutableStateFlow<List<com.antigravity.healthagent.domain.repository.AuthUser>>(emptyList())
    val users: StateFlow<List<com.antigravity.healthagent.domain.repository.AuthUser>> = _users.asStateFlow()

    private val _uiEvent = MutableSharedFlow<String>()
    val uiEvent: SharedFlow<String> = _uiEvent

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

    fun updateUserProfile(uid: String, updates: Map<String, Any?>) {
        viewModelScope.launch {
            val result = authRepository.updateUserProfile(uid, updates)
            if (result.isSuccess) {
                loadUsers()
            }
        }
    }

    fun createUser(email: String, role: com.antigravity.healthagent.domain.repository.UserRole, agentName: String?, isAuthorized: Boolean) {
        viewModelScope.launch {
            val result = authRepository.createUserProfile(email, role, agentName, isAuthorized)
            if (result.isSuccess) {
                loadUsers()
            }
        }
    }

    fun createAgent(email: String, agentName: String?) {
        viewModelScope.launch {
            val result = syncRepository.createAgent(email, agentName)
            if (result.isSuccess) {
                loadAgentsData()
            }
        }
    }

    fun deleteUser(uid: String) {
        viewModelScope.launch {
            val result = authRepository.deleteUser(uid)
            if (result.isSuccess) {
                loadUsers()
            }
        }
    }

    fun deleteAgent(uid: String) {
        viewModelScope.launch {
            val result = syncRepository.deleteAgent(uid)
            if (result.isSuccess) {
                loadAgentsData()
            }
        }
    }

    fun restoreAgentBackup(context: Context, uid: String, uri: Uri) {
        viewModelScope.launch {
            try {
                // Parse the JSON file into BackupData
                val backupData = BackupManager().importData(context, uri)
                
                // Push the parsed data directly to the given agent's cloud collection
                val result = syncRepository.pushLocalDataToCloud(
                    houses = backupData.houses,
                    activities = backupData.dayActivities,
                    targetUid = uid,
                    shouldReplace = true
                )
                
                if (result.isSuccess) {
                    _uiEvent.emit("Backup restaurado com sucesso!")
                    // Refresh data after successful upload
                    loadAgentsData()
                } else {
                    val errorMsg = result.exceptionOrNull()?.message ?: "Erro desconhecido"
                    _uiEvent.emit("Erro ao restaurar para nuvem: $errorMsg")
                }
            } catch (e: Exception) {
                _uiEvent.emit("Falha ao ler o arquivo de backup: ${e.message}")
            }
        }
    }
}

sealed class AdminUiState {
    object Loading : AdminUiState()
    data class Success(val agents: List<AgentData>) : AdminUiState()
    data class Error(val message: String) : AdminUiState()
}
