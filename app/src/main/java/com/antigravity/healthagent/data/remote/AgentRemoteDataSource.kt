package com.antigravity.healthagent.data.remote

import com.antigravity.healthagent.data.local.model.CachedAgent
import com.antigravity.healthagent.domain.repository.AgentSummary

interface AgentRemoteDataSource {
    suspend fun createAgent(email: String, agentName: String?): Result<Unit>
    suspend fun deleteAgent(uid: String): Result<Unit>
    suspend fun purgeAgentCompletely(uid: String): Result<Unit>
    suspend fun fetchAgentNames(): Result<List<String>>
    suspend fun addAgentName(name: String): Result<Unit>
    suspend fun deleteAgentName(name: String): Result<Unit>
    suspend fun fetchUpdatedAgents(latestCacheUpdate: Long): Result<List<CachedAgent>>
    suspend fun fetchMonthlySummary(uid: String, monthYear: String): Result<AgentSummary?>
    suspend fun fetchYearlySummaries(uid: String, year: String): Result<List<AgentSummary>>
    suspend fun deleteAgentSummary(uid: String, monthYear: String): Result<Unit>
    suspend fun clearSyncError(uid: String): Result<Unit>
}
