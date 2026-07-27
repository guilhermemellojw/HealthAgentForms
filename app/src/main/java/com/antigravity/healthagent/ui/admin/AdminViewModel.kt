package com.antigravity.healthagent.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.healthagent.domain.repository.AgentData
import com.antigravity.healthagent.domain.repository.AuthUser
import com.antigravity.healthagent.domain.repository.UserRole
import com.antigravity.healthagent.ui.state.SyncUiState
import com.antigravity.healthagent.ui.admin.delegates.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.content.Context
import android.net.Uri

@HiltViewModel
class AdminViewModel @Inject constructor(
    private val authRepository: com.antigravity.healthagent.domain.repository.AuthRepository,
    private val accessControlRepository: com.antigravity.healthagent.domain.repository.AccessControlRepository,
    private val settingsManager: com.antigravity.healthagent.data.settings.SettingsManager,
    private val stateDelegate: AdminStateDelegate,
    private val usersDelegate: AdminUsersDelegate,
    private val backupDelegate: AdminBackupDelegate
) : ViewModel(), AdminState by stateDelegate {

    val solarMode: StateFlow<Boolean> = settingsManager.solarMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

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
        
        val agentsByUid = agentsList.associateBy { it.uid }
        val agentsByEmail = agentsList.filter { it.uid == null || it.uid.startsWith(com.antigravity.healthagent.utils.AppConstants.PRE_PREFIX) }.associateBy { it.email }

        val result = mutableListOf<UnifiedProfile>()
        val processedAgentUids = mutableSetOf<String?>()
        val processedEmails = mutableSetOf<String?>()
        
        val sortedUsers = usersList.sortedWith(compareBy { it.uid.startsWith(com.antigravity.healthagent.utils.AppConstants.PRE_PREFIX) })
        sortedUsers.forEach { user ->
            val normalizedEmail = user.email?.trim()?.lowercase()
            if (normalizedEmail != null && processedEmails.contains(normalizedEmail)) {
                return@forEach
            }
            
            var agentData = agentsByUid[user.uid]
            if (agentData == null && user.email != null) {
                agentData = agentsByEmail[user.email]
            }

            result.add(
                UnifiedProfile(
                    uid = user.uid,
                    email = user.email,
                    agentName = agentData?.agentName ?: user.agentName,
                    role = user.role,
                    isAuthorized = user.isAuthorized,
                    isPreRegistered = user.uid.startsWith(com.antigravity.healthagent.utils.AppConstants.PRE_PREFIX),
                    agentData = agentData
                )
            )
            processedAgentUids.add(user.uid)
            processedEmails.add(normalizedEmail)
            agentData?.uid?.let { processedAgentUids.add(it) }
            agentData?.email?.trim()?.lowercase()?.let { processedEmails.add(it) }
        }
        
        agentsList.forEach { agent ->
            val normalizedEmail = agent.email?.trim()?.lowercase()
            if (!processedAgentUids.contains(agent.uid) && !processedEmails.contains(normalizedEmail)) {
                val isPre = agent.uid?.startsWith(com.antigravity.healthagent.utils.AppConstants.PRE_PREFIX) == true
                result.add(
                    UnifiedProfile(
                        uid = agent.uid,
                        email = agent.email,
                        agentName = agent.agentName,
                        role = UserRole.AGENT, 
                        isAuthorized = true,
                        isPreRegistered = isPre,
                        agentData = agent
                    )
                )
                processedAgentUids.add(agent.uid)
                processedEmails.add(normalizedEmail)
            }
        }
        
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

        if (query.isBlank()) result
        else result.filter { 
            it.email?.contains(query, true) == true || 
            it.agentName?.contains(query, true) == true 
        }
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

    val globalCustomActivities: StateFlow<Set<String>> = systemSettings.map { settings ->
        val raw = settings["custom_activities"]
        when(raw) {
            is List<*> -> raw.mapNotNull { it?.toString() }.toSet()
            is String -> raw.split(",").filter { it.isNotBlank() }.toSet()
            else -> emptySet()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    init {
        refreshAll()
        
        viewModelScope.launch {
            accessControlRepository.pendingAccessRequests.collect { requests ->
                accessRequests.value = requests
            }
        }

        viewModelScope.launch {
            settingsManager.lastSyncTimestamp.collect { ts ->
                syncState.value = SyncUiState.Idle(lastSyncTime = if (ts > 0L) ts else null)
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
            availableMonths
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
        if (syncState.value is SyncUiState.Syncing) return
        syncState.value = SyncUiState.Syncing(progress = 0.5f, message = "Atualizando dados...", lastSyncTime = syncState.value.lastSyncTime)
        viewModelScope.launch {
            isLoading.value = true
            try {
                usersDelegate.loadAgentsData(stateDelegate, selectedYear.value, selectedMonth.value)
                usersDelegate.loadUsers(stateDelegate)
                usersDelegate.loadAgentNames(stateDelegate)
                usersDelegate.loadBairros(stateDelegate)
                usersDelegate.loadSystemSettings(stateDelegate)
                
                val now = System.currentTimeMillis()
                settingsManager.setLastSyncTimestamp(now)
                syncState.value = SyncUiState.Success(lastSyncTime = now)
            } catch (e: java.lang.Exception) {
                syncState.value = SyncUiState.Error(message = e.message ?: "Erro ao atualizar dados", lastSyncTime = syncState.value.lastSyncTime)
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
    fun createAgent(email: String, agentName: String?) = usersDelegate.createAgent(viewModelScope, stateDelegate, email, agentName)
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
    fun deleteAgentHouse(agentUid: String, houseId: String) =
        backupDelegate.deleteAgentHouse(viewModelScope, stateDelegate, agentUid, houseId) {
            usersDelegate.loadAgentsData(stateDelegate, selectedYear.value, selectedMonth.value)
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
    val agentData: AgentData? = null
)

sealed class AdminUiState {
    object Loading : AdminUiState()
    data class Success(val agents: List<AgentData>) : AdminUiState()
    data class Error(val message: String) : AdminUiState()
}
