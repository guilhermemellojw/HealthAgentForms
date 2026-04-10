package com.antigravity.healthagent.domain.repository

import com.antigravity.healthagent.domain.repository.AgentData

interface AgentRepository {
    suspend fun createAgent(email: String, agentName: String?): Result<Unit>
    suspend fun deleteAgent(uid: String): Result<Unit>
    suspend fun fetchAgentNames(): Result<List<String>>
    suspend fun addAgentName(name: String): Result<Unit>
    suspend fun deleteAgentName(name: String): Result<Unit>
    suspend fun fetchAllAgentsData(sinceTimestamp: Long = 0L, untilTimestamp: Long = 0L, datePattern: String? = null): Result<List<AgentData>>
    
    // Administrative Cloud Operations
    suspend fun deleteAgentHouse(uid: String, houseId: String): Result<Unit>
    suspend fun deleteAgentActivity(uid: String, activityDate: String): Result<Unit>
    suspend fun clearSyncError(uid: String): Result<Unit>
    suspend fun transferAgentData(fromUid: String, toUid: String): Result<Unit>
}
