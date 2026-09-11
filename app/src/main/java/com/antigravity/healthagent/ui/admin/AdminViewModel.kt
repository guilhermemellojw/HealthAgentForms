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
    private val accessControlRepository: com.antigravity.healthagent.domain.repository.AccessControlRepository,
    private val restoreDataUseCase: RestoreDataUseCase,
    private val syncDataUseCase: SyncDataUseCase,
    private val getTimelineUseCase: com.antigravity.healthagent.domain.usecase.GetTimelineUseCase,
    private val restoreFromTimelineUseCase: com.antigravity.healthagent.domain.usecase.RestoreFromTimelineUseCase,
    private val cleanupBrokenHousesUseCase: com.antigravity.healthagent.domain.usecase.CleanupBrokenHousesUseCase,
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

    private val _isRenaming = MutableStateFlow(false)
    val isRenaming: StateFlow<Boolean> = _isRenaming.asStateFlow()

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

        // Linking rules live in UnifiedProfilesMapper (pure + unit-tested):
        // pre_ + real with the same email merge into ONE card owned by the
        // real UID; the real user is never dropped.
        UnifiedProfilesMapper.buildUnifiedProfiles(
            usersList = usersList,
            agentsList = agentsList,
            namesList = namesList,
            query = query
        )
    }
    .flowOn(kotlinx.coroutines.Dispatchers.Default)
    .distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        refreshAll()
        
        // Collect real-time access requests once and for all
        viewModelScope.launch {
            accessControlRepository.pendingAccessRequests.collect { requests ->
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
            if (!accessControlRepository.isUserAdmin()) {
                _uiEvent.emit("Permissão negada")
                return@launch
            }
            val result = accessControlRepository.respondToAccessRequest(requestId, true, agentName)
            if (result.isSuccess) {
                loadUsers()
                _uiEvent.emit("Acesso aprovado")
            } else {
                _uiEvent.emit("Erro ao aprovar: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    fun rejectAccess(requestId: String) {
        viewModelScope.launch {
            if (!accessControlRepository.isUserAdmin()) {
                _uiEvent.emit("Permissão negada")
                return@launch
            }
            val result = accessControlRepository.respondToAccessRequest(requestId, false)
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
        val result = accessControlRepository.fetchAllUsers()
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
            if (!accessControlRepository.isUserAdmin()) {
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
            if (!accessControlRepository.isUserAdmin()) {
                _uiEvent.emit("Permissão negada")
                return@launch
            }
            val canon = name.trim().uppercase()
            if (canon.isBlank()) {
                _uiEvent.emit("Nome inválido")
                return@launch
            }
            // Trava à prova de erros: não excluir nome vinculado a um usuário.
            val owner = try {
                accessControlRepository.isAgentNameTaken(canon).getOrNull()
            } catch (_: Exception) { null }
            if (owner != null) {
                _uiEvent.emit("Nome vinculado a $owner — desvincule primeiro")
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

    /**
     * Renomeia a lista mestra e propaga para todo o histórico (casas + atividades)
     * reutilizando o padrão move do transferAgentData (set newKey + delete oldKey).
     *
     * Ordem à prova de falha parcial:
     * 1) valida + checa duplicata (mestra e vínculo 1:1)
     * 2) move casas/atividades na nuvem (abortável, idempotente)
     * 3) lista mestra: rename transacional (caminho mestra) ou add do destino
     *    (caminho vinculado; nunca remove o antigo automaticamente)
     * 4) atualiza users/{uid}.agentName vinculados
     * 5) refreshAll para convergir o dashboard
     *
     * @param targetUid quando fornecido, propaga para esse UID mesmo que o vínculo
     * atual ainda não reflita o nome antigo; quando null, resolve pelos usuários
     * cujo agentName normalizado == oldName (caso uid==null não tem casas).
     */
    fun renameMasterAgentName(oldName: String, newName: String, targetUid: String? = null) {
        if (_isRenaming.value) return
        viewModelScope.launch {
            if (!accessControlRepository.isUserAdmin()) {
                _uiEvent.emit("Permissão negada")
                return@launch
            }
            val oldCanon = oldName.trim().uppercase().takeIf { it.isNotBlank() }
            val newCanon = newName.trim().uppercase().takeIf { it.isNotBlank() }
            if (oldCanon == null || newCanon == null) {
                _uiEvent.emit("Nome inválido")
                return@launch
            }
            if (oldCanon == newCanon) {
                _uiEvent.emit("Nomes iguais")
                return@launch
            }
            val masterHasOld = _agentNames.value.any { it.trim().uppercase() == oldCanon }
            val masterHasNew = _agentNames.value.any { it.trim().uppercase() == newCanon }
            if (targetUid == null) {
                // Rename puro da mestra (chips da Config./órfãos): old precisa existir, new não.
                if (!masterHasOld) {
                    _uiEvent.emit("Nome não encontrado na lista")
                    return@launch
                }
                if (masterHasNew) {
                    _uiEvent.emit("Este nome já existe na lista")
                    return@launch
                }
            }
            // No caminho vinculado (targetUid != null) o destino pode/deve ser um nome
            // já existente da mestra (autocomplete) — só o vínculo 1:1 bloqueia.
            val takenOwner = try {
                accessControlRepository.isAgentNameTaken(newCanon, exceptUid = targetUid).getOrNull()
            } catch (_: Exception) { null }
            if (takenOwner != null) {
                _uiEvent.emit("Este nome já está vinculado a $takenOwner")
                return@launch
            }

            _isRenaming.value = true
            try {
                // Resolve UIDs vinculados ao nome antigo (auto-update do plano).
                val uidsToMigrate: List<String> = if (targetUid != null) {
                    listOf(targetUid)
                } else {
                    _users.value
                        .filter { it.agentName?.trim()?.uppercase() == oldCanon }
                        .map { it.uid }
                        .distinct()
                }.filter { !it.startsWith("pre_") }

                // Passo 1: mover histórico na nuvem (aborta antes de tocar a mestra).
                var housesMoved = 0
                var activitiesMoved = 0
                for (uid in uidsToMigrate) {
                    val dataResult = agentRepository.renameAgentData(uid, newCanon)
                    if (dataResult.isFailure) {
                        _uiEvent.emit("Erro ao mover casas: ${dataResult.exceptionOrNull()?.message}. Rename abortado.")
                        loadAgentsData(_selectedYear.value, _selectedMonth.value)
                        return@launch
                    }
                    dataResult.getOrNull()?.let {
                        housesMoved += it.housesMoved
                        activitiesMoved += it.activitiesMoved
                    }
                }

                // Passo 2: lista mestra.
                // - Caminho mestra (targetUid==null): rename transacional old->new.
                // - Caminho vinculado: nunca remove o old automaticamente (pode ser
                //   typo fora da lista ou entrada ainda usada); só garante o new na lista.
                if (targetUid == null) {
                    val masterResult = agentRepository.renameAgentName(oldCanon, newCanon)
                    if (masterResult.isFailure) {
                        val msg = masterResult.exceptionOrNull()?.message
                        if (housesMoved > 0 || activitiesMoved > 0) {
                            _uiEvent.emit("Casas movidas, mas a lista falhou ($msg). Tente renomear de novo.")
                        } else {
                            _uiEvent.emit("Erro ao renomear: $msg")
                        }
                        loadAgentNames()
                        loadAgentsData(_selectedYear.value, _selectedMonth.value)
                        return@launch
                    }
                } else if (!masterHasNew) {
                    val addResult = agentRepository.addAgentName(newCanon)
                    if (addResult.isFailure) {
                        _uiEvent.emit("Casas movidas, mas não foi possível registrar o nome na lista. Verifique.")
                        loadAgentNames()
                    }
                }

                // Passo 3: atualizar perfis vinculados (converge SyncPush.officialAgentName).
                var linkFailures = 0
                for (uid in uidsToMigrate) {
                    val upd = accessControlRepository.updateUserProfile(uid, mapOf("agentName" to newCanon))
                    if (upd.isFailure) linkFailures++
                }

                refreshAll()
                if (linkFailures > 0) {
                    _uiEvent.emit("Lista renomeada, mas $linkFailures vínculo(s) falharam — verifique")
                } else if (uidsToMigrate.isEmpty()) {
                    _uiEvent.emit("Nome atualizado na lista mestra")
                } else {
                    val parts = mutableListOf<String>()
                    if (housesMoved > 0) parts.add("$housesMoved imóveis")
                    if (activitiesMoved > 0) parts.add("$activitiesMoved dias")
                    val detail = if (parts.isEmpty()) "" else " (${parts.joinToString(" + ")})"
                    _uiEvent.emit("Nome atualizado$detail")
                }
            } finally {
                _isRenaming.value = false
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
            if (!accessControlRepository.isUserAdmin()) {
                _uiEvent.emit("Permissão negada")
                return@launch
            }
            val result = accessControlRepository.authorizeUser(uid, isAuthorized)
            if (result.isSuccess) {
                loadUsers()
                if (isAuthorized) {
                    // Fase 2: authorizing never migrates implicitly. Hint the admin
                    // when a pre-registered profile is waiting for explicit migration.
                    val pending = accessControlRepository.findPendingPreMigration(uid).getOrNull()
                    if (pending != null) {
                        _uiEvent.emit("Existe pré-registro pendente. Use Migrar Dados para concluir.")
                    }
                }
            } else {
                _uiEvent.emit("Erro ao autorizar: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    fun changeUserRole(uid: String, role: com.antigravity.healthagent.domain.repository.UserRole) {
        viewModelScope.launch {
            if (!accessControlRepository.isUserAdmin()) {
                _uiEvent.emit("Permissão negada")
                return@launch
            }
            val result = accessControlRepository.changeUserRole(uid, role)
            if (result.isSuccess) {
                loadUsers()
            }
        }
    }

    fun updateUserProfile(uid: String, updates: Map<String, Any?>) {
        viewModelScope.launch {
            if (!accessControlRepository.isUserAdmin()) {
                _uiEvent.emit("Permissão negada")
                return@launch
            }
            val result = accessControlRepository.updateUserProfile(uid, updates)
            if (result.isSuccess) {
                loadUsers()
                if (updates.containsKey("agentName")) {
                    _uiEvent.emit("Vínculo atualizado")
                }
            } else {
                _uiEvent.emit("Erro ao vincular: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    fun createUser(email: String, role: com.antigravity.healthagent.domain.repository.UserRole, agentName: String?, isAuthorized: Boolean) {
        viewModelScope.launch {
            if (!accessControlRepository.isUserAdmin()) {
                _uiEvent.emit("Permissão negada")
                return@launch
            }
            val result = accessControlRepository.createUserProfile(email, role, agentName, isAuthorized)
            if (result.isSuccess) {
                loadUsers()
                _uiEvent.emit("Perfil criado com sucesso")
            } else {
                _uiEvent.emit("Erro ao criar perfil: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    fun createAgent(email: String, agentName: String?) {
        viewModelScope.launch {
            if (!accessControlRepository.isUserAdmin()) {
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
            if (!accessControlRepository.isUserAdmin()) {
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
                
                val result = accessControlRepository.deleteUser(uid)
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
            if (!accessControlRepository.isUserAdmin()) {
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
            if (!accessControlRepository.isUserAdmin()) {
                _uiEvent.emit("Permissão negada")
                return@launch
            }
            try {
                // 1. Purge cloud data (houses and activities)
                val cloudResult = agentRepository.deleteAgent(uid)
                
                // 2. Set flag to force local device to clear database on next sync
                accessControlRepository.updateUserProfile(uid, mapOf("requireDataReset" to true))
                
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
            if (!accessControlRepository.isUserAdmin()) {
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

    /**
     * Explicit, admin-confirmed migration (Fase 2 decision: never implicit).
     * The UI must confirm before calling. [resolvedAgentName] is the
     * admin-confirmed link name (dialog choice > pre_ name).
     */
    fun migrateData(authUser: com.antigravity.healthagent.domain.repository.AuthUser, resolvedAgentName: String? = null) {
        viewModelScope.launch {
            if (!accessControlRepository.isUserAdmin()) {
                _uiEvent.emit("Permissão negada")
                return@launch
            }
            val pending = accessControlRepository.findPendingPreMigration(authUser.uid).getOrNull()
            if (pending == null) {
                _uiEvent.emit("Nenhum pré-registro pendente para este usuário")
                return@launch
            }
            val result = accessControlRepository.migratePreRegistrationExplicit(
                preUid = pending.preUid,
                targetUid = authUser.uid,
                resolvedAgentName = resolvedAgentName ?: pending.preAgentName ?: authUser.agentName
            )
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
            if (!accessControlRepository.isUserAdmin()) {
                _uiEvent.emit("Permissão negada")
                return@launch
            }
            _uiState.value = AdminUiState.Loading
            val result = agentRepository.transferAgentData(fromUid, toUid)
            if (result.isSuccess) {
                // Set flag to force source device to clear local data on next sync
                accessControlRepository.updateUserProfile(fromUid, mapOf("requireDataReset" to true))
                
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

    // --- Timeline Backup Shift ---

    private val _timeline = MutableStateFlow<List<com.antigravity.healthagent.domain.repository.BackupMetadata>>(emptyList())
    val timeline: StateFlow<List<com.antigravity.healthagent.domain.repository.BackupMetadata>> = _timeline.asStateFlow()

    private val _isTimelineLoading = MutableStateFlow(false)
    val isTimelineLoading: StateFlow<Boolean> = _isTimelineLoading.asStateFlow()

    fun loadTimeline(uid: String) {
        viewModelScope.launch {
            _isTimelineLoading.value = true
            val result = getTimelineUseCase(uid)
            if (result.isSuccess) {
                _timeline.value = result.getOrNull() ?: emptyList()
            } else {
                _uiEvent.emit("Erro ao carregar timeline: ${result.exceptionOrNull()?.message}")
            }
            _isTimelineLoading.value = false
        }
    }

    fun restoreFromTimeline(agentUid: String, storagePath: String) {
        viewModelScope.launch {
            if (!accessControlRepository.isUserAdmin()) {
                _uiEvent.emit("Permissão negada")
                return@launch
            }
            _isTimelineLoading.value = true
            val result = restoreFromTimelineUseCase(agentUid, storagePath)
            if (result.isSuccess) {
                _uiEvent.emit("Restauração concluída com sucesso")
                loadTimeline(agentUid) // Refresh metadata (like agentName update if it changed)
                loadAgentsData(_selectedYear.value, _selectedMonth.value) // Refresh dashboard
            } else {
                _uiEvent.emit("Erro na restauração: ${result.exceptionOrNull()?.message}")
            }
            _isTimelineLoading.value = false
        }
    }

    fun performSurgicalCleanup(uid: String) {
        viewModelScope.launch {
            if (!accessControlRepository.isUserAdmin()) {
                _uiEvent.emit("Permissão negada")
                return@launch
            }
            _isLoading.value = true
            val result = cleanupBrokenHousesUseCase(uid)
            if (result.isSuccess) {
                val count = result.getOrNull() ?: 0
                _uiEvent.emit("Limpeza concluída: $count registros removidos")
                loadAgentsData(_selectedYear.value, _selectedMonth.value)
            } else {
                _uiEvent.emit("Erro na limpeza: ${result.exceptionOrNull()?.message}")
            }
            _isLoading.value = false
        }
    }
}

data class UnifiedProfile(
    val uid: String?,
    val email: String?,
    val agentName: String?,
    val role: UserRole,
    val isAuthorized: Boolean,
    val isPreRegistered: Boolean,
    val agentData: AgentData? = null,
    // True when a pre-registered doc exists for the same email but the card
    // is owned by the real UID (migration pending, explicit confirmation required).
    val hasPendingPreMigration: Boolean = false,
    // True when the email fallback found >1 candidate agent doc and attached none.
    val hasAmbiguousLink: Boolean = false
)

sealed class AdminUiState {
    object Loading : AdminUiState()
    data class Success(val agents: List<AgentData>) : AdminUiState()
    data class Error(val message: String) : AdminUiState()
}
