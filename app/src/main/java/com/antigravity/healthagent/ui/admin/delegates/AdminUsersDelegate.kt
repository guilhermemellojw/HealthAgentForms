package com.antigravity.healthagent.ui.admin.delegates

import com.antigravity.healthagent.domain.repository.AgentRepository
import com.antigravity.healthagent.domain.repository.LocalizationRepository
import com.antigravity.healthagent.domain.repository.AuthRepository
import com.antigravity.healthagent.domain.repository.AccessControlRepository
import com.antigravity.healthagent.domain.repository.UserRole
import com.antigravity.healthagent.domain.repository.AuthUser
import com.antigravity.healthagent.ui.admin.AdminUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AdminUsersDelegate @Inject constructor(
    private val agentRepository: AgentRepository,
    private val localizationRepository: LocalizationRepository,
    private val authRepository: AuthRepository,
    private val accessControlRepository: AccessControlRepository
) {

    fun approveAccess(scope: CoroutineScope, state: AdminState, requestId: String, agentName: String?) {
        scope.launch {
            if (!accessControlRepository.isUserAdmin()) {
                state.uiEvent.emit("Permissão negada")
                return@launch
            }
            val result = accessControlRepository.respondToAccessRequest(requestId, true, agentName)
            if (result.isSuccess) {
                loadUsers(state)
                state.uiEvent.emit("Acesso aprovado")
            }
        }
    }

    fun rejectAccess(scope: CoroutineScope, state: AdminState, requestId: String) {
        scope.launch {
            if (!accessControlRepository.isUserAdmin()) {
                state.uiEvent.emit("Permissão negada")
                return@launch
            }
            val result = accessControlRepository.respondToAccessRequest(requestId, false)
            if (result.isSuccess) {
                state.uiEvent.emit("Acesso rejeitado")
            }
        }
    }

    suspend fun loadAgentsData(state: AdminState, year: Int, month: Int) {
        val datePattern = if (month == -1) {
            "-$year"
        } else {
            val monthStr = String.format("%02d", month + 1)
            "-$monthStr-$year"
        }

        if (state.uiState.value !is AdminUiState.Success) {
            state.uiState.value = AdminUiState.Loading
        }

        val result = agentRepository.fetchAllAgentsData(datePattern = datePattern)
        if (result.isSuccess) {
            state.uiState.value = AdminUiState.Success(result.getOrNull() ?: emptyList())
        } else {
            state.uiState.value = AdminUiState.Error(result.exceptionOrNull()?.message ?: "Erro ao carregar dados dos agentes")
        }
    }

    suspend fun loadUsers(state: AdminState) {
        val result = accessControlRepository.fetchAllUsers()
        if (result.isSuccess) {
            state.users.value = result.getOrNull() ?: emptyList()
        }
    }

    suspend fun loadAgentNames(state: AdminState) {
        val result = agentRepository.fetchAgentNames()
        if (result.isSuccess) {
            state.agentNames.value = result.getOrNull() ?: emptyList()
        }
    }

    fun addAgentName(scope: CoroutineScope, state: AdminState, name: String) {
        scope.launch {
            if (!accessControlRepository.isUserAdmin()) {
                state.uiEvent.emit("Permissão negada")
                return@launch
            }
            val result = agentRepository.addAgentName(name)
            if (result.isSuccess) {
                loadAgentNames(state)
                state.uiEvent.emit("Nome adicionado com sucesso")
            } else {
                state.uiEvent.emit("Erro ao adicionar nome")
            }
        }
    }

    fun removeAgentName(scope: CoroutineScope, state: AdminState, name: String) {
        scope.launch {
            if (!accessControlRepository.isUserAdmin()) {
                state.uiEvent.emit("Permissão negada")
                return@launch
            }
            val result = agentRepository.deleteAgentName(name)
            if (result.isSuccess) {
                loadAgentNames(state)
                state.uiEvent.emit("Nome removido com sucesso")
            } else {
                state.uiEvent.emit("Erro ao remover nome")
            }
        }
    }

    fun authorizeUser(scope: CoroutineScope, state: AdminState, uid: String, isAuthorized: Boolean) {
        scope.launch {
            if (!accessControlRepository.isUserAdmin()) {
                state.uiEvent.emit("Permissão negada")
                return@launch
            }
            val result = accessControlRepository.authorizeUser(uid, isAuthorized)
            if (result.isSuccess) {
                loadUsers(state)
            } else {
                state.uiEvent.emit("Erro ao alterar autorização")
            }
        }
    }

    fun changeUserRole(scope: CoroutineScope, state: AdminState, uid: String, role: UserRole) {
        scope.launch {
            if (!accessControlRepository.isUserAdmin()) {
                state.uiEvent.emit("Permissão negada")
                return@launch
            }
            val result = accessControlRepository.changeUserRole(uid, role)
            if (result.isSuccess) {
                loadUsers(state)
            } else {
                state.uiEvent.emit("Erro ao alterar função")
            }
        }
    }

    fun updateUserProfile(scope: CoroutineScope, state: AdminState, uid: String, updates: Map<String, Any?>) {
        scope.launch {
            if (!accessControlRepository.isUserAdmin()) {
                state.uiEvent.emit("Permissão negada")
                return@launch
            }
            val result = accessControlRepository.updateUserProfile(uid, updates)
            if (result.isSuccess) {
                loadUsers(state)
            } else {
                state.uiEvent.emit("Erro ao atualizar perfil")
            }
        }
    }

    fun createUser(scope: CoroutineScope, state: AdminState, email: String, role: UserRole, agentName: String?, isAuthorized: Boolean) {
        scope.launch {
            if (!accessControlRepository.isUserAdmin()) {
                state.uiEvent.emit("Permissão negada")
                return@launch
            }
            val result = accessControlRepository.createUserProfile(email, role, agentName, isAuthorized)
            if (result.isSuccess) {
                loadUsers(state)
            } else {
                state.uiEvent.emit("Erro ao criar usuário")
            }
        }
    }

    fun deleteUser(scope: CoroutineScope, state: AdminState, uid: String, deleteCloudData: Boolean = false) {
        scope.launch {
            if (!accessControlRepository.isUserAdmin()) {
                state.uiEvent.emit("Permissão negada")
                return@launch
            }
            try {
                if (deleteCloudData) {
                    val syncResult = agentRepository.deleteAgent(uid)
                    if (syncResult.isFailure) {
                        state.uiEvent.emit("Aviso: Falha ao excluir dados da nuvem")
                    }
                }
                
                val result = accessControlRepository.deleteUser(uid)
                if (result.isSuccess) {
                    state.uiEvent.emit("Perfil excluído com sucesso")
                    loadUsers(state)
                    loadAgentsData(state, state.selectedYear.value, state.selectedMonth.value)
                } else {
                    state.uiEvent.emit("Erro ao excluir perfil: ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                state.uiEvent.emit("Erro inesperado: ${e.message}")
            }
        }
    }

    fun deleteAgent(scope: CoroutineScope, state: AdminState, uid: String) {
        scope.launch {
            if (!accessControlRepository.isUserAdmin()) {
                state.uiEvent.emit("Permissão negada")
                return@launch
            }
            val result = agentRepository.deleteAgent(uid)
            if (result.isSuccess) {
                state.uiEvent.emit("Agente excluído com sucesso")
                loadAgentsData(state, state.selectedYear.value, state.selectedMonth.value)
            } else {
                state.uiEvent.emit("Erro ao excluir agente: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    fun remoteWipeAgentData(scope: CoroutineScope, state: AdminState, uid: String) {
        scope.launch {
            if (!accessControlRepository.isUserAdmin()) {
                state.uiEvent.emit("Permissão negada")
                return@launch
            }
            try {
                val cloudResult = agentRepository.deleteAgent(uid)
                accessControlRepository.updateUserProfile(uid, mapOf("requireDataReset" to true))
                
                if (cloudResult.isSuccess) {
                    state.uiEvent.emit("Wipe remoto concluído (Nuvem e Local)")
                } else {
                    state.uiEvent.emit("Wipe local agendado, mas houve erro na nuvem")
                }
                loadAgentsData(state, state.selectedYear.value, state.selectedMonth.value)
            } catch (e: Exception) {
                state.uiEvent.emit("Erro no wipe remoto: ${e.message}")
            }
        }
    }

    fun addBairro(scope: CoroutineScope, state: AdminState, name: String) {
        scope.launch {
            if (!accessControlRepository.isUserAdmin()) {
                state.uiEvent.emit("Permissão negada")
                return@launch
            }
            val result = localizationRepository.addBairro(name)
            if (result.isSuccess) {
                loadBairros(state)
                state.uiEvent.emit("Bairro adicionado")
            }
        }
    }

    fun deleteBairro(scope: CoroutineScope, state: AdminState, name: String) {
        scope.launch {
            if (!accessControlRepository.isUserAdmin()) {
                state.uiEvent.emit("Permissão negada")
                return@launch
            }
            val result = localizationRepository.deleteBairro(name)
            if (result.isSuccess) {
                loadBairros(state)
                state.uiEvent.emit("Bairro removido")
            }
        }
    }

    fun updateSystemSetting(scope: CoroutineScope, state: AdminState, key: String, value: Any) {
        scope.launch {
            if (!accessControlRepository.isUserAdmin()) {
                state.uiEvent.emit("Permissão negada")
                return@launch
            }
            val result = localizationRepository.updateSystemSetting(key, value)
            if (result.isSuccess) {
                loadSystemSettings(state)
                state.uiEvent.emit("Configuração atualizada: $key = $value")
            }
        }
    }

    fun migrateData(scope: CoroutineScope, state: AdminState, authUser: AuthUser, onRefreshAll: () -> Unit) {
        scope.launch {
            if (!accessControlRepository.isUserAdmin()) {
                state.uiEvent.emit("Permissão negada")
                return@launch
            }
            val result = authRepository.migratePreRegistration(authUser)
            if (result.isSuccess) {
                state.uiEvent.emit("Dados migrados com sucesso")
                onRefreshAll()
            } else {
                state.uiEvent.emit("Erro ao migrar dados: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    fun transferData(scope: CoroutineScope, state: AdminState, fromUid: String, toUid: String, onRefreshAll: () -> Unit) {
        scope.launch {
            if (!accessControlRepository.isUserAdmin()) {
                state.uiEvent.emit("Permissão negada")
                return@launch
            }
            state.uiState.value = AdminUiState.Loading
            val result = agentRepository.transferAgentData(fromUid, toUid)
            if (result.isSuccess) {
                accessControlRepository.updateUserProfile(fromUid, mapOf("requireDataReset" to true))
                state.uiEvent.emit("Dados transferidos com sucesso")
                onRefreshAll()
            } else {
                state.uiEvent.emit("Erro ao transferir dados: ${result.exceptionOrNull()?.message}")
                loadAgentsData(state, state.selectedYear.value, state.selectedMonth.value)
            }
        }
    }

    suspend fun loadBairros(state: AdminState) {
        val result = localizationRepository.fetchBairros()
        if (result.isSuccess) {
            state.bairros.value = result.getOrNull() ?: emptyList()
        }
    }

    suspend fun loadSystemSettings(state: AdminState) {
        val result = localizationRepository.fetchSystemSettings()
        if (result.isSuccess) {
            state.systemSettings.value = result.getOrNull() ?: emptyMap()
        }
    }
}
