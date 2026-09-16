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

    fun renameMasterAgentName(scope: CoroutineScope, state: AdminState, oldName: String, newName: String, targetUid: String? = null) {
        scope.launch {
            if (!accessControlRepository.isUserAdmin()) {
                state.uiEvent.emit("Permissão negada")
                return@launch
            }
            val oldCanon = oldName.trim().uppercase().takeIf { it.isNotBlank() }
            val newCanon = newName.trim().uppercase().takeIf { it.isNotBlank() }
            if (oldCanon == null || newCanon == null) {
                state.uiEvent.emit("Nome inválido")
                return@launch
            }
            if (oldCanon == newCanon) {
                state.uiEvent.emit("Nomes iguais")
                return@launch
            }
            val masterHasOld = state.agentNames.value.any { it.trim().uppercase() == oldCanon }
            val masterHasNew = state.agentNames.value.any { it.trim().uppercase() == newCanon }
            if (targetUid == null) {
                // Rename puro da mestra (chips da Config./órfãos): old precisa existir, new não.
                if (!masterHasOld) {
                    state.uiEvent.emit("Nome não encontrado na lista")
                    return@launch
                }
                if (masterHasNew) {
                    state.uiEvent.emit("Este nome já existe na lista")
                    return@launch
                }
            }
            // No caminho vinculado (targetUid != null) o destino pode/deve ser um nome
            // já existente da mestra (autocomplete) — só o vínculo 1:1 bloqueia.
            val takenOwner = try {
                accessControlRepository.isAgentNameTaken(newCanon, exceptUid = targetUid).getOrNull()
            } catch (_: Exception) { null }
            if (takenOwner != null) {
                state.uiEvent.emit("Este nome já está vinculado a $takenOwner")
                return@launch
            }

            try {
                val uidsToMigrate: List<String> = if (targetUid != null) {
                    listOf(targetUid)
                } else {
                    state.users.value
                        .filter { it.agentName?.trim()?.uppercase() == oldCanon }
                        .map { it.uid }
                        .distinct()
                }.filter { !it.startsWith("pre_") }

                var housesMoved = 0
                var activitiesMoved = 0
                for (uid in uidsToMigrate) {
                    val dataResult = agentRepository.renameAgentData(uid, newCanon)
                    if (dataResult.isFailure) {
                        state.uiEvent.emit("Erro ao mover casas: ${dataResult.exceptionOrNull()?.message}. Rename abortado.")
                        loadAgentsData(state, state.selectedYear.value, state.selectedMonth.value)
                        return@launch
                    }
                    dataResult.getOrNull()?.let {
                        housesMoved += it.housesMoved
                        activitiesMoved += it.activitiesMoved
                    }
                }

                if (targetUid == null) {
                    val masterResult = agentRepository.renameAgentName(oldCanon, newCanon)
                    if (masterResult.isFailure) {
                        val msg = masterResult.exceptionOrNull()?.message
                        if (housesMoved > 0 || activitiesMoved > 0) {
                            state.uiEvent.emit("Casas movidas, mas a lista falhou ($msg). Tente renomear de novo.")
                        } else {
                            state.uiEvent.emit("Erro ao renomear: $msg")
                        }
                        loadAgentNames(state)
                        loadAgentsData(state, state.selectedYear.value, state.selectedMonth.value)
                        return@launch
                    }
                } else if (!masterHasNew) {
                    val addResult = agentRepository.addAgentName(newCanon)
                    if (addResult.isFailure) {
                        state.uiEvent.emit("Casas movidas, mas não foi possível registrar o nome na lista. Verifique.")
                        loadAgentNames(state)
                    }
                }

                var linkFailures = 0
                for (uid in uidsToMigrate) {
                    val upd = accessControlRepository.updateUserProfile(uid, mapOf("agentName" to newCanon))
                    if (upd.isFailure) linkFailures++
                }

                loadUsers(state)
                loadAgentNames(state)
                loadAgentsData(state, state.selectedYear.value, state.selectedMonth.value)
                if (linkFailures > 0) {
                    state.uiEvent.emit("Lista renomeada, mas $linkFailures vínculo(s) falharam — verifique")
                } else if (uidsToMigrate.isEmpty()) {
                    state.uiEvent.emit("Nome atualizado na lista mestra")
                } else {
                    val parts = mutableListOf<String>()
                    if (housesMoved > 0) parts.add("$housesMoved imóveis")
                    if (activitiesMoved > 0) parts.add("$activitiesMoved dias")
                    val detail = if (parts.isEmpty()) "" else " (${parts.joinToString(" + ")})"
                    state.uiEvent.emit("Nome atualizado$detail")
                }
            } catch (e: Exception) {
                state.uiEvent.emit("Erro no rename: ${e.message}")
            }
        }
    }

    fun migrateData(scope: CoroutineScope, state: AdminState, authUser: AuthUser, resolvedAgentName: String? = null, onRefreshAll: () -> Unit) {
        scope.launch {
            if (!accessControlRepository.isUserAdmin()) {
                state.uiEvent.emit("Permissão negada")
                return@launch
            }
            val pending = accessControlRepository.findPendingPreMigration(authUser.uid).getOrNull()
            if (pending == null) {
                state.uiEvent.emit("Nenhum pré-registro pendente para este usuário")
                return@launch
            }
            val result = accessControlRepository.migratePreRegistrationExplicit(
                preUid = pending.preUid,
                targetUid = authUser.uid,
                resolvedAgentName = resolvedAgentName ?: pending.preAgentName ?: authUser.agentName
            )
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
