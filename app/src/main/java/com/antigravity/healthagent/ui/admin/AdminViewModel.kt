package com.antigravity.healthagent.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.healthagent.domain.repository.AgentData
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import android.view.View
import android.content.Context
import com.antigravity.healthagent.domain.repository.AgentRepository
import com.antigravity.healthagent.domain.repository.LocalizationRepository
import com.antigravity.healthagent.domain.repository.AuthRepository
import com.antigravity.healthagent.domain.repository.AccessRequest
import com.antigravity.healthagent.domain.repository.AuthUser
import com.antigravity.healthagent.domain.repository.UserRole
import kotlinx.coroutines.flow.combine
import android.net.Uri

@HiltViewModel
class AdminViewModel @Inject constructor(
    private val agentRepository: AgentRepository,
    private val localizationRepository: LocalizationRepository,
    private val authRepository: AuthRepository,
    private val restoreDataUseCase: RestoreDataUseCase,
    private val syncDataUseCase: SyncDataUseCase,
    private val settingsManager: com.antigravity.healthagent.data.settings.SettingsManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<AdminUiState>(AdminUiState.Loading)
    val uiState: StateFlow<AdminUiState> = _uiState.asStateFlow()

    private val _users = MutableStateFlow<List<AuthUser>>(emptyList())
    val users: StateFlow<List<AuthUser>> = _users.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _uiEvent = MutableSharedFlow<String>()
    val uiEvent: SharedFlow<String> = _uiEvent

    val solarMode: StateFlow<Boolean> = settingsManager.solarMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _bairros = MutableStateFlow<List<String>>(emptyList())
    val bairros: StateFlow<List<String>> = _bairros.asStateFlow()

    private val _systemSettings = MutableStateFlow<Map<String, Any>>(emptyMap())
    val systemSettings: StateFlow<Map<String, Any>> = _systemSettings.asStateFlow()

    private val _agentNames = MutableStateFlow<List<String>>(emptyList())
    val agentNames: StateFlow<List<String>> = _agentNames.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    fun updateSearchQuery(query: String) { _searchQuery.value = query }
    
    // Filtering State
    private val _selectedYear = MutableStateFlow(java.util.Calendar.getInstance().get(java.util.Calendar.YEAR))
    val selectedYear = _selectedYear.asStateFlow()

    private val _selectedMonth = MutableStateFlow(java.util.Calendar.getInstance().get(java.util.Calendar.MONTH)) // Default current month
    val selectedMonth = _selectedMonth.asStateFlow()

    val availableYears = (2025..java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)).reversed().toList()
    val availableMonths = listOf("Ano Todo", "Jan", "Fev", "Mar", "Abr", "Mai", "Jun", "Jul", "Ago", "Set", "Out", "Nov", "Dez")

    fun getFilteredMonths(): List<String> {
        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val currentMonth = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH)
        
        return if (_selectedYear.value >= currentYear) {
             // Only months up to now + "Ano Todo"
            availableMonths.take(currentMonth + 2) // +1 for "Ano Todo", +1 for current month index (0-based)
        } else {
            availableMonths
        }
    }

    fun updateYear(year: Int) {
        _selectedYear.value = year
        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val currentMonth = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH)
        if (year == currentYear && _selectedMonth.value > currentMonth) {
            _selectedMonth.value = currentMonth
        }
        refreshAll()
    }

    fun updateMonth(monthIndex: Int) {
        _selectedMonth.value = monthIndex
        refreshAll()
    }

    private val _accessRequests = MutableStateFlow<List<AccessRequest>>(emptyList())
    val accessRequests: StateFlow<List<AccessRequest>> = _accessRequests.asStateFlow()


    private var lastSuccessfulAgentsList: List<AgentData> = emptyList()

    val unifiedProfiles: StateFlow<List<UnifiedProfile>> = combine(
        users,
        uiState,
        agentNames,
        _searchQuery
    ) { usersList, agentsState, namesList, query ->
        val agentsList = if (agentsState is AdminUiState.Success) {
            lastSuccessfulAgentsList = agentsState.agents
            agentsState.agents
        } else if (agentsState is AdminUiState.Loading) {
            // Keep the last data while loading to prevent card data from disappearing
            lastSuccessfulAgentsList
        } else {
            emptyList()
        }
        
        // Use maps for faster lookup instead of nested find/none
        val agentsByUid = agentsList.associateBy { it.uid }
        val agentsByEmail = agentsList.filter { it.uid == null || it.uid.startsWith("pre_") }.associateBy { it.email }

        // Build the unified list
        val result = mutableListOf<UnifiedProfile>()
        val processedAgentUids = mutableSetOf<String?>()
        val processedEmails = mutableSetOf<String?>()
        
        // 1. Start with users who have accounts
        usersList.forEach { user ->
            // Prioritize UID match
            var agentData = agentsByUid[user.uid]
            
            // Fallback to email match ONLY if no UID-linked data exists
            if (agentData == null && user.email != null) {
                agentData = agentsByEmail[user.email]
            }

            result.add(
                UnifiedProfile(
                    uid = user.uid,
                    email = user.email,
                    // Prioritize Firestore agentName over Auth displayName
                    agentName = agentData?.agentName ?: user.agentName,
                    role = user.role,
                    isAuthorized = user.isAuthorized,
                    isPreRegistered = false,
                    agentData = agentData
                )
            )
            processedAgentUids.add(user.uid)
            processedEmails.add(user.email)
            agentData?.uid?.let { processedAgentUids.add(it) }
            agentData?.email?.let { processedEmails.add(it) }
        }
        
        // 2. Add Pre-registered "agents" from Firestore who don't have a user account yet
        agentsList.forEach { agent ->
            if (!processedAgentUids.contains(agent.uid) && !processedEmails.contains(agent.email)) {
                result.add(
                    UnifiedProfile(
                        uid = agent.uid,
                        email = agent.email,
                        agentName = agent.agentName,
                        role = UserRole.AGENT, 
                        isAuthorized = true,
                        isPreRegistered = true,
                        agentData = agent
                    )
                )
                processedAgentUids.add(agent.uid)
                processedEmails.add(agent.email)
            }
        }

        // 3. Add names from the Master List that haven't been linked yet
        val existingNamesUpperCase = result.mapNotNull { it.agentName?.trim()?.uppercase() }.toSet()
        namesList.forEach { name ->
            val normalizedName = name.trim().uppercase()
            if (!existingNamesUpperCase.contains(normalizedName)) {
                result.add(
                    UnifiedProfile(
                        uid = null,
                        email = null,
                        agentName = name,
                        role = UserRole.AGENT,
                        isAuthorized = false,
                        isPreRegistered = true,
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
    }
    .flowOn(kotlinx.coroutines.Dispatchers.Default)
    .distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        refreshAll()
        
        // Collect real-time access requests once and for all
        viewModelScope.launch {
            authRepository.pendingAccessRequests.collect { requests ->
                _accessRequests.value = requests
            }
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            _isLoading.value = true
            loadAgentsData(_selectedYear.value, _selectedMonth.value)
            loadUsers()
            loadAgentNames()
            loadBairros()
            loadSystemSettings()
            _isLoading.value = false
        }
    }

    // loadAccessRequests is now handled by the real-time collector in refreshAll

    fun approveAccess(requestId: String, agentName: String?) {
        viewModelScope.launch {
            if (!authRepository.isUserAdmin()) {
                _uiEvent.emit("Permissão negada")
                return@launch
            }
            val result = authRepository.respondToAccessRequest(requestId, true, agentName)
            if (result.isSuccess) {
                loadUsers()
                _uiEvent.emit("Acesso aprovado")
            }
        }
    }

    fun rejectAccess(requestId: String) {
        viewModelScope.launch {
            if (!authRepository.isUserAdmin()) {
                _uiEvent.emit("Permissão negada")
                return@launch
            }
            val result = authRepository.respondToAccessRequest(requestId, false)
            if (result.isSuccess) {
                _uiEvent.emit("Acesso rejeitado")
            }
        }
    }

    private suspend fun loadAgentsData(year: Int, month: Int) {
        // Construct date pattern based on filters
        val datePattern = if (month == -1) {
            "-$year"
        } else {
            val monthStr = String.format("%02d", month + 1)
            "-$monthStr-$year"
        }

        // Avoid resetting Success state to Loading if we already have data to prevent flicker
        if (_uiState.value !is AdminUiState.Success) {
            _uiState.value = AdminUiState.Loading
        }

        val result = agentRepository.fetchAllAgentsData(datePattern = datePattern)
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


    private suspend fun loadAgentNames() {
        val result = agentRepository.fetchAgentNames()
        if (result.isSuccess) {
            _agentNames.value = result.getOrNull() ?: emptyList()
        }
    }

    fun addAgentName(name: String) {
        viewModelScope.launch {
            if (!authRepository.isUserAdmin()) {
                _uiEvent.emit("Permissão negada")
                return@launch
            }
            val result = agentRepository.addAgentName(name)
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
            if (!authRepository.isUserAdmin()) {
                _uiEvent.emit("Permissão negada")
                return@launch
            }
            val result = agentRepository.deleteAgentName(name)
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
            if (!authRepository.isUserAdmin()) {
                _uiEvent.emit("Permissão negada")
                return@launch
            }
            val result = authRepository.authorizeUser(uid, isAuthorized)
            if (result.isSuccess) {
                loadUsers()
            }
        }
    }

    fun changeUserRole(uid: String, role: com.antigravity.healthagent.domain.repository.UserRole) {
        viewModelScope.launch {
            if (!authRepository.isUserAdmin()) {
                _uiEvent.emit("Permissão negada")
                return@launch
            }
            val result = authRepository.changeUserRole(uid, role)
            if (result.isSuccess) {
                loadUsers()
            }
        }
    }

    fun updateUserProfile(uid: String, updates: Map<String, Any?>) {
        viewModelScope.launch {
            if (!authRepository.isUserAdmin()) {
                _uiEvent.emit("Permissão negada")
                return@launch
            }
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
            if (!authRepository.isUserAdmin()) {
                _uiEvent.emit("Permissão negada")
                return@launch
            }
            try {
                val result = agentRepository.createAgent(email, agentName)
                if (result.isSuccess) {
                    loadAgentsData(_selectedYear.value, _selectedMonth.value)
                }
            } catch (e: Exception) {
                _uiEvent.emit("Erro ao criar agente: ${e.message}")
            }
        }
    }

    fun deleteUser(uid: String, deleteCloudData: Boolean = false) {
        viewModelScope.launch {
            if (!authRepository.isUserAdmin()) {
                _uiEvent.emit("Permissão negada")
                return@launch
            }
            try {
                if (deleteCloudData) {
                    val syncResult = agentRepository.deleteAgent(uid)
                    if (syncResult.isFailure) {
                        _uiEvent.emit("Aviso: Falha ao excluir dados da nuvem")
                    }
                }
                
                val result = authRepository.deleteUser(uid)
                if (result.isSuccess) {
                    _uiEvent.emit("Perfil excluído com sucesso")
                    loadUsers()
                    loadAgentsData(_selectedYear.value, _selectedMonth.value)
                } else {
                    _uiEvent.emit("Erro ao excluir perfil: ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                _uiEvent.emit("Erro inesperado: ${e.message}")
            }
        }
    }

    fun deleteAgent(uid: String) {
        viewModelScope.launch {
            if (!authRepository.isUserAdmin()) {
                _uiEvent.emit("Permissão negada")
                return@launch
            }
            val result = agentRepository.deleteAgent(uid)
            if (result.isSuccess) {
                loadAgentsData(_selectedYear.value, _selectedMonth.value)
            }
        }
    }

    fun remoteWipeAgentData(uid: String) {
        viewModelScope.launch {
            if (!authRepository.isUserAdmin()) {
                _uiEvent.emit("Permissão negada")
                return@launch
            }
            try {
                // 1. Purge cloud data (houses and activities)
                val cloudResult = agentRepository.deleteAgent(uid)
                
                // 2. Set flag to force local device to clear database on next sync
                authRepository.updateUserProfile(uid, mapOf("requireDataReset" to true))
                
                if (cloudResult.isSuccess) {
                    _uiEvent.emit("Wipe remoto concluído (Nuvem e Local)")
                } else {
                    _uiEvent.emit("Wipe local agendado, mas houve erro na nuvem")
                }
                
                loadAgentsData(_selectedYear.value, _selectedMonth.value)
            } catch (e: Exception) {
                _uiEvent.emit("Erro no wipe remoto: ${e.message}")
            }
        }
    }

    // --- Super Admin Settings ---


    private suspend fun loadBairros() {
        val result = localizationRepository.fetchBairros()
        if (result.isSuccess) {
            _bairros.value = result.getOrNull() ?: emptyList()
        }
    }

    private suspend fun loadSystemSettings() {
        val result = localizationRepository.fetchSystemSettings()
        if (result.isSuccess) {
            _systemSettings.value = result.getOrNull() ?: emptyMap()
        }
    }

    fun addBairro(name: String) {
        viewModelScope.launch {
            val result = localizationRepository.addBairro(name)
            if (result.isSuccess) {
                loadBairros()
                _uiEvent.emit("Bairro adicionado")
            }
        }
    }

    fun deleteBairro(name: String) {
        viewModelScope.launch {
            val result = localizationRepository.deleteBairro(name)
            if (result.isSuccess) {
                loadBairros()
                _uiEvent.emit("Bairro removido")
            }
        }
    }

    fun updateSystemSetting(key: String, value: Any) {
        viewModelScope.launch {
            val result = localizationRepository.updateSystemSetting(key, value)
            if (result.isSuccess) {
                loadSystemSettings()
                _uiEvent.emit("Configuração atualizada: $key = $value")
            }
        }
    }

    val maxOpenHouses: StateFlow<Int> = _systemSettings.map { settings ->
        val raw = settings["max_open_houses"]
        when(raw) {
            is Long -> raw.toInt()
            is Int -> raw
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull() ?: 25
            else -> 25
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 25)

    val globalCustomActivities: StateFlow<Set<String>> = _systemSettings.map { settings ->
        val raw = settings["custom_activities"]
        when(raw) {
            is List<*> -> raw.mapNotNull { it?.toString() }.toSet()
            is String -> raw.split(",").filter { it.isNotBlank() }.toSet()
            else -> emptySet()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    fun addGlobalActivity(activity: String) {
        val current = globalCustomActivities.value
        if (activity !in current) {
            updateSystemSetting("custom_activities", (current + activity).toList())
        }
    }

    fun removeGlobalActivity(activity: String) {
        val current = globalCustomActivities.value
        if (activity in current) {
            updateSystemSetting("custom_activities", (current - activity).toList())
        }
    }

    fun restoreToSelf(context: Context, uri: Uri) {
        val myUid = authRepository.getCurrentUserUid() ?: return
        restoreAgentBackup(context, myUid, uri)
    }

    fun restoreAgentBackup(context: Context, agentUid: String, uri: Uri, targetDate: String? = null, autoShift: Boolean = false) {
        viewModelScope.launch {
            if (!authRepository.isUserAdmin()) {
                _uiEvent.emit("Permissão negada")
                return@launch
            }
            val agents = if (uiState.value is AdminUiState.Success) (uiState.value as AdminUiState.Success).agents else emptyList()
            val agent = agents.find { it.uid == agentUid }
            val existingDates = agent?.activities?.map { it.date.replace("/", "-") } ?: emptyList()

            val result = restoreDataUseCase(context, agentUid, uri, targetDate, existingDates, isSingleDayImport = autoShift)
            if (result.isSuccess) {
                _uiEvent.emit("Backup restaurado com sucesso para o agente")
                loadAgentsData(_selectedYear.value, _selectedMonth.value)
            } else {
                _uiEvent.emit("Erro ao restaurar backup: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    fun deleteAgentHouse(agentUid: String, houseId: String) {
        viewModelScope.launch {
            val result = agentRepository.deleteAgentHouse(agentUid, houseId)
            if (result.isSuccess) {
                loadAgentsData(_selectedYear.value, _selectedMonth.value)
                _uiEvent.emit("Registro de imóvel excluído")
            }
        }
    }

    fun deleteAgentActivity(agentUid: String, activityDate: String) {
        viewModelScope.launch {
            val result = agentRepository.deleteAgentActivity(agentUid, activityDate)
            if (result.isSuccess) {
                loadAgentsData(_selectedYear.value, _selectedMonth.value)
                _uiEvent.emit("Registro de atividade excluído")
            }
        }
    }

    fun clearSyncError(uid: String) {
        viewModelScope.launch {
            val result = agentRepository.clearSyncError(uid)
            if (result.isSuccess) {
                _uiEvent.emit("Erro de sincronização limpo")
                loadAgentsData(_selectedYear.value, _selectedMonth.value)
            }
        }
    }

    fun migrateData(authUser: com.antigravity.healthagent.domain.repository.AuthUser) {
        viewModelScope.launch {
            val result = authRepository.migratePreRegistration(authUser)
            if (result.isSuccess) {
                _uiEvent.emit("Dados migrados com sucesso")
                refreshAll()
            } else {
                _uiEvent.emit("Erro ao migrar dados: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    fun transferData(fromUid: String, toUid: String) {
        viewModelScope.launch {
            if (!authRepository.isUserAdmin()) {
                _uiEvent.emit("Permissão negada")
                return@launch
            }
            _uiState.value = AdminUiState.Loading
            val result = agentRepository.transferAgentData(fromUid, toUid)
            if (result.isSuccess) {
                // Set flag to force source device to clear local data on next sync
                authRepository.updateUserProfile(fromUid, mapOf("requireDataReset" to true))
                
                _uiEvent.emit("Dados transferidos com sucesso")
                refreshAll()
            } else {
                _uiEvent.emit("Erro ao transferir dados: ${result.exceptionOrNull()?.message}")
                loadAgentsData(_selectedYear.value, _selectedMonth.value)
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
