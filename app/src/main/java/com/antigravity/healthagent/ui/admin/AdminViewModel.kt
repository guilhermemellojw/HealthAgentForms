package com.antigravity.healthagent.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.healthagent.domain.repository.AgentData
import com.antigravity.healthagent.domain.repository.AuthUser
import com.antigravity.healthagent.domain.repository.UserRole
import com.antigravity.healthagent.ui.admin.delegates.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.content.Context
import android.net.Uri

import com.antigravity.healthagent.data.sync.SyncFeedbackManager

@HiltViewModel
class AdminViewModel @Inject constructor(
    private val authRepository: com.antigravity.healthagent.domain.repository.AuthRepository,
    private val accessControlRepository: com.antigravity.healthagent.domain.repository.AccessControlRepository,
    private val settingsManager: com.antigravity.healthagent.data.settings.SettingsManager,
    private val stateDelegate: AdminStateDelegate,
    private val usersDelegate: AdminUsersDelegate,
    private val backupDelegate: AdminBackupDelegate,
    private val feedbackManager: SyncFeedbackManager
) : ViewModel(), AdminState by stateDelegate {

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

    val unifiedProfiles: StateFlow<List<UnifiedProfile>> = combine(
        users,
        uiState,
        agentNames,
        searchQuery
    ) { usersList, agentsState, namesList, query ->
        val agentsList = if (agentsState is AdminUiState.Success) {
            agentsState.agents
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

    val maxOpenHouses: StateFlow<Int> = systemSettings.map { settings ->
        val raw = settings["max_open_houses"]
        when(raw) {
            is Long -> raw.toInt()
            is Int -> raw
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull() ?: 25
            else -> 25
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 25)

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
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    init {
        refreshAll()
        
        viewModelScope.launch {
            accessControlRepository.pendingAccessRequests.collect { requests ->
                accessRequests.value = requests
            }
        }
    }

    fun updateSearchQuery(query: String) { searchQuery.value = query }

    fun getFilteredMonths(): List<String> {
        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val currentMonth = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH)
        
        return if (selectedYear.value == currentYear) {
            availableMonths.take(currentMonth + 2)
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

    fun updateYear(year: Int) {
        selectedYear.value = year
        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val currentMonth = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH)
        if (year == currentYear && selectedMonth.value > currentMonth) {
            selectedMonth.value = currentMonth
        }
        refreshAll()
    }

    fun updateMonth(monthIndex: Int) {
        selectedMonth.value = monthIndex
        refreshAll()
    }

    fun refreshAll() {
        if (feedbackManager.isSyncing) return
        feedbackManager.syncing(progress = 0.5f, message = "Atualizando dados...")
        viewModelScope.launch {
            isLoading.value = true
            try {
                usersDelegate.loadAgentsData(stateDelegate, selectedYear.value, selectedMonth.value)
                usersDelegate.loadUsers(stateDelegate)
                usersDelegate.loadAgentNames(stateDelegate)
                usersDelegate.loadBairros(stateDelegate)
                usersDelegate.loadSystemSettings(stateDelegate)
                
                feedbackManager.success()
            } catch (e: java.lang.Exception) {
                feedbackManager.error(message = e.message ?: "Erro ao atualizar dados")
                stateDelegate.uiEvent.emit(e.message ?: "Erro ao atualizar dados")
            } finally {
                isLoading.value = false
            }
        }
    }

    fun selectAgentForEdit(agent: AgentData?) {
        selectedAgentForEdit.value = agent
    }

    fun approveAccess(requestId: String, agentName: String?) = usersDelegate.approveAccess(viewModelScope, stateDelegate, requestId, agentName)
    fun rejectAccess(requestId: String) = usersDelegate.rejectAccess(viewModelScope, stateDelegate, requestId)
    fun addAgentName(name: String) = usersDelegate.addAgentName(viewModelScope, stateDelegate, name)
    fun removeAgentName(name: String) = usersDelegate.removeAgentName(viewModelScope, stateDelegate, name)
    fun authorizeUser(uid: String, isAuthorized: Boolean) = usersDelegate.authorizeUser(viewModelScope, stateDelegate, uid, isAuthorized)
    fun changeUserRole(uid: String, role: UserRole) = usersDelegate.changeUserRole(viewModelScope, stateDelegate, uid, role)
    fun updateUserProfile(uid: String, updates: Map<String, Any?>) = usersDelegate.updateUserProfile(viewModelScope, stateDelegate, uid, updates)
    fun createUser(email: String, role: UserRole, agentName: String?, isAuthorized: Boolean) = usersDelegate.createUser(viewModelScope, stateDelegate, email, role, agentName, isAuthorized)
    fun deleteUser(uid: String, deleteCloudData: Boolean = false) = usersDelegate.deleteUser(viewModelScope, stateDelegate, uid, deleteCloudData)
    fun deleteAgent(uid: String) = usersDelegate.deleteAgent(viewModelScope, stateDelegate, uid)
    fun remoteWipeAgentData(uid: String) = usersDelegate.remoteWipeAgentData(viewModelScope, stateDelegate, uid)
    fun addBairro(name: String) = usersDelegate.addBairro(viewModelScope, stateDelegate, name)
    fun deleteBairro(name: String) = usersDelegate.deleteBairro(viewModelScope, stateDelegate, name)
    fun updateSystemSetting(key: String, value: Any) = usersDelegate.updateSystemSetting(viewModelScope, stateDelegate, key, value)
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
    fun migrateData(authUser: AuthUser) = usersDelegate.migrateData(viewModelScope, stateDelegate, authUser) { refreshAll() }
    fun transferData(fromUid: String, toUid: String) = usersDelegate.transferData(viewModelScope, stateDelegate, fromUid, toUid) { refreshAll() }

    fun getCurrentUserUid(): String? = authRepository.getCurrentUserUid()

    fun restoreToSelf(context: Context, uri: Uri) = backupDelegate.restoreToSelf(viewModelScope, stateDelegate, context, uri)
    fun restoreAgentBackup(context: Context, agentUid: String, uri: Uri, targetDate: String? = null, autoShift: Boolean = false) =
        backupDelegate.restoreAgentBackup(viewModelScope, stateDelegate, context, agentUid, uri, targetDate, autoShift) {
            usersDelegate.loadAgentsData(stateDelegate, selectedYear.value, selectedMonth.value)
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
    fun deleteAgentActivity(agentUid: String, activityDate: String) =
        backupDelegate.deleteAgentActivity(viewModelScope, stateDelegate, agentUid, activityDate) {
            usersDelegate.loadAgentsData(stateDelegate, selectedYear.value, selectedMonth.value)
        }
    fun clearSyncError(uid: String) =
        backupDelegate.clearSyncError(viewModelScope, stateDelegate, uid) {
            usersDelegate.loadAgentsData(stateDelegate, selectedYear.value, selectedMonth.value)
        }
    fun loadTimeline(uid: String) = backupDelegate.loadTimeline(viewModelScope, stateDelegate, uid)
    fun restoreFromTimeline(agentUid: String, storagePath: String) =
        backupDelegate.restoreFromTimeline(viewModelScope, stateDelegate, agentUid, storagePath) {
            usersDelegate.loadAgentsData(stateDelegate, selectedYear.value, selectedMonth.value)
        }
    fun performSurgicalCleanup(uid: String) =
        backupDelegate.performSurgicalCleanup(viewModelScope, stateDelegate, uid) {
            usersDelegate.loadAgentsData(stateDelegate, selectedYear.value, selectedMonth.value)
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
