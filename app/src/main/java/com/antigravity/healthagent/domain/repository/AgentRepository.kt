package com.antigravity.healthagent.domain.repository

import com.antigravity.healthagent.domain.repository.AgentData

interface AgentRepository {
    suspend fun createAgent(email: String, agentName: String?): Result<Unit>
    suspend fun deleteAgent(uid: String): Result<Unit>
    suspend fun fetchAgentNames(): Result<List<String>>
    suspend fun addAgentName(name: String): Result<Unit>
    suspend fun deleteAgentName(name: String): Result<Unit>
    /**
     * Renomeia uma entrada da lista mestra (metadata/agent_info.names).
     * Normaliza trim().uppercase(), falha se origem ausente ou destino já existe.
     * Implementação deve usar transação para evitar lost-update.
     */
    suspend fun renameAgentName(oldName: String, newName: String): Result<Unit>
    /**
     * Propaga o rename para todo o histórico do agente (mesmo UID),
     * reutilizando o padrão move do [transferAgentData]:
     * recalcula naturalKey via toHouseSafe + generateNaturalKey,
     * faz set(new)+delete(old) em batches, atualiza agents/{uid}.agentName.
     * Idempotente: pode ser retomado após falha parcial.
     */
    suspend fun renameAgentData(uid: String, newName: String): Result<RenameReport>
    suspend fun fetchAllAgentsData(sinceTimestamp: Long = 0L, untilTimestamp: Long = 0L, datePattern: String? = null): Result<List<AgentData>>
    
    // Administrative Cloud Operations
    suspend fun deleteAgentHouse(uid: String, houseId: String): Result<Unit>
    suspend fun deleteAgentActivity(uid: String, activityDate: String): Result<Unit>
    suspend fun clearSyncError(uid: String): Result<Unit>
    suspend fun transferAgentData(fromUid: String, toUid: String): Result<Unit>

    /**
     * Provides a real-time stream of an agent's data for administrative inspection.
     * Uses a snapshot listener for cost-efficiency (only emits on changes).
     */
    fun observeAgentProduction(uid: String, datePattern: String?): kotlinx.coroutines.flow.Flow<AgentData>
}

data class RenameReport(
    val housesMoved: Int = 0,
    val activitiesMoved: Int = 0
)
