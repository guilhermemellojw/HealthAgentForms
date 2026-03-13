package com.antigravity.healthagent.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.healthagent.domain.repository.AgentData
import com.antigravity.healthagent.domain.repository.SyncRepository
import com.antigravity.healthagent.domain.usecase.RestoreDataUseCase
import com.antigravity.healthagent.domain.usecase.SyncDataUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.view.View
import com.antigravity.healthagent.domain.repository.AuthUser
import kotlinx.coroutines.flow.combine
import android.net.Uri

@HiltViewModel
class AdminViewModel @Inject constructor(
    private val syncRepository: SyncRepository,
    private val authRepository: com.antigravity.healthagent.domain.repository.AuthRepository,
    private val restoreDataUseCase: RestoreDataUseCase,
    private val syncDataUseCase: SyncDataUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow<AdminUiState>(AdminUiState.Loading)
    val uiState: StateFlow<AdminUiState> = _uiState.asStateFlow()

    private val _users = MutableStateFlow<List<com.antigravity.healthagent.domain.repository.AuthUser>>(emptyList())
    val users: StateFlow<List<com.antigravity.healthagent.domain.repository.AuthUser>> = _users.asStateFlow()

    private val _uiEvent = MutableSharedFlow<String>()
    val uiEvent: SharedFlow<String> = _uiEvent

    private val _bairros = MutableStateFlow<List<String>>(emptyList())
    val bairros: StateFlow<List<String>> = _bairros.asStateFlow()

    private val _systemSettings = MutableStateFlow<Map<String, Any>>(emptyMap())
    val systemSettings: StateFlow<Map<String, Any>> = _systemSettings.asStateFlow()

    private val _agentNames = MutableStateFlow<List<String>>(emptyList())
    val agentNames: StateFlow<List<String>> = _agentNames.asStateFlow()

    fun updateSearchQuery(query: String) { _searchQuery.value = query }

    private val _accessRequests = MutableStateFlow<List<com.antigravity.healthagent.domain.repository.AccessRequest>>(emptyList())
    val accessRequests: StateFlow<List<com.antigravity.healthagent.domain.repository.AccessRequest>> = _accessRequests.asStateFlow()

    val unifiedProfiles: StateFlow<List<UnifiedProfile>> = combine(
        users,
        uiState,
        agentNames,
        _searchQuery
    ) { usersList, agentsState, namesList, query ->
        val agentsList = (agentsState as? AdminUiState.Success)?.agents ?: emptyList()
        
        // Build the unified list
        val result = mutableListOf<UnifiedProfile>()
        
        // 1. Start with users who have accounts
        usersList.forEach { user ->
            val agentData = agentsList.find { it.uid == user.uid || it.email == user.email }
            result.add(
                UnifiedProfile(
                    uid = user.uid,
                    email = user.email,
                    agentName = user.agentName,
                    role = user.role,
                    isAuthorized = user.isAuthorized,
                    isPreRegistered = false,
                    agentData = agentData
                )
            )
        }
        
        // 2. Add Pre-registered "agents" who don't have a user account yet
        agentsList.forEach { agent ->
            if (result.none { it.uid == agent.uid || it.email == agent.email }) {
                result.add(
                    UnifiedProfile(
                        uid = agent.uid,
                        email = agent.email,
                        agentName = agent.agentName,
                        role = UserRole.AGENT, 
                        isAuthorized = false,
                        isPreRegistered = true,
                        agentData = agent
                    )
                )
            }
        }

        // 3. Add names from the Master List that haven't been linked yet
        namesList.forEach { name ->
            if (result.none { it.agentName == name }) {
                result.add(
                    UnifiedProfile(
                        uid = null,
                        email = null,
                        agentName = name,
                        role = UserRole.AGENT,
                        isAuthorized = false,
                        isPreRegistered = true, // Treat as pre-registered/unlinked
                        agentData = null
                    )
                )
            }
        }

        // Filter based on query
        if (query.isBlank()) result
        else result.filter { 
            it.email?.contains(query, true) == true || 
            it.agentName?.contains(query, true) == true 
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        refreshAll()
    }

    fun refreshAll() {
        _uiState.value = AdminUiState.Loading
        viewModelScope.launch {
            loadAgentsData()
            loadUsers()
            loadAgentNames()
            loadBairros()
            loadSystemSettings()
            loadAccessRequests()
        }
    }

    private suspend fun loadAccessRequests() {
        val result = authRepository.fetchAccessRequests()
        if (result.isSuccess) {
            _accessRequests.value = result.getOrNull() ?: emptyList()
        }
    }

    fun approveAccess(requestId: String, agentName: String?) {
        viewModelScope.launch {
            val result = authRepository.respondToAccessRequest(requestId, true, agentName)
            if (result.isSuccess) {
                loadAccessRequests()
                loadUsers()
                _uiEvent.emit("Acesso aprovado")
            }
        }
    }

    fun rejectAccess(requestId: String) {
        viewModelScope.launch {
            val result = authRepository.respondToAccessRequest(requestId, false)
            if (result.isSuccess) {
                loadAccessRequests()
                _uiEvent.emit("Acesso rejeitado")
            }
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

    private val _agentNames = MutableStateFlow<List<String>>(emptyList())
    val agentNames: StateFlow<List<String>> = _agentNames.asStateFlow()

    private suspend fun loadAgentNames() {
        val result = syncRepository.fetchAgentNames()
        if (result.isSuccess) {
            _agentNames.value = result.getOrNull() ?: emptyList()
        }
    }

    fun addAgentName(name: String) {
        viewModelScope.launch {
            val result = syncRepository.addAgentName(name)
            if (result.isSuccess) {
                loadAgentNames()
                _uiEvent.emit("Nome adicionado com sucesso")
            } else {
                _uiEvent.emit("Erro ao adicionar nome")
            }
        }
    }

    fun removeAgentName(name: String) {
        viewModelScope.launch {
            val result = syncRepository.deleteAgentName(name)
            if (result.isSuccess) {
                loadAgentNames()
                _uiEvent.emit("Nome removido com sucesso")
            } else {
                _uiEvent.emit("Erro ao remover nome")
            }
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

    // --- Super Admin Settings ---

    private suspend fun loadBairros() {
        val result = syncRepository.fetchBairros()
        if (result.isSuccess) {
            _bairros.value = result.getOrNull() ?: emptyList()
        }
    }

    private suspend fun loadSystemSettings() {
        val result = syncRepository.fetchSystemSettings()
        if (result.isSuccess) {
            _systemSettings.value = result.getOrNull() ?: emptyMap()
        }
    }

    fun addBairro(name: String) {
        viewModelScope.launch {
            val result = syncRepository.addBairro(name)
            if (result.isSuccess) {
                loadBairros()
                _uiEvent.emit("Bairro adicionado")
            }
        }
    }

    fun deleteBairro(name: String) {
        viewModelScope.launch {
            val result = syncRepository.deleteBairro(name)
            if (result.isSuccess) {
                loadBairros()
                _uiEvent.emit("Bairro removido")
            }
        }
    }

    fun updateSystemSetting(key: String, value: Any) {
        viewModelScope.launch {
            val result = syncRepository.updateSystemSetting(key, value)
            if (result.isSuccess) {
                loadSystemSettings()
                _uiEvent.emit("Configuração atualizada: $key = $value")
            }
        }
    }

    val maxOpenHouses: StateFlow<Int> = _systemSettings.map { settings ->
        (settings["max_open_houses"] as? Long)?.toInt() ?: 25
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 25)

    fun restoreToSelf(context: Context, uri: Uri) {
        val myUid = authRepository.getCurrentUserUid() ?: return
        restoreAgentBackup(context, myUid, uri)
    }

    fun restoreAgentBackup(context: Context, agentUid: String, uri: Uri) {
        viewModelScope.launch {
            val result = restoreDataUseCase(context, agentUid, uri)
            if (result.isSuccess) {
                _uiEvent.emit("Backup restaurado com sucesso para o agente")
                loadAgentsData()
            } else {
                _uiEvent.emit("Erro ao restaurar backup: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    fun deleteAgentHouse(agentUid: String, houseId: String) {
        viewModelScope.launch {
            val result = syncRepository.deleteAgentHouse(agentUid, houseId)
            if (result.isSuccess) {
                loadAgentsData()
                _uiEvent.emit("Registro de imóvel excluído")
            }
        }
    }

    fun deleteAgentActivity(agentUid: String, activityDate: String) {
        viewModelScope.launch {
            val result = syncRepository.deleteAgentActivity(agentUid, activityDate)
            if (result.isSuccess) {
                loadAgentsData()
                _uiEvent.emit("Registro de atividade excluído")
            }
        }
    }

    fun getCurrentUserUid(): String? {
        return authRepository.getCurrentUserUid()
    }
}

data class UnifiedProfile(
    val uid: String?,
    val email: String?,
    val agentName: String?,
    val role: UserRole,
    val isAuthorized: Boolean,
    val isPreRegistered: Boolean,
    val agentData: AgentData? = null
)

sealed class AdminUiState {
    object Loading : AdminUiState()
    data class Success(val agents: List<AgentData>) : AdminUiState()
    data class Error(val message: String) : AdminUiState()
}
