package com.antigravity.healthagent.data.local.dao

import androidx.room.*
import com.antigravity.healthagent.data.local.model.CachedAgent
import com.antigravity.healthagent.data.local.model.CachedAgentSummary

@Dao
interface AgentCacheDao {
    @Query("SELECT * FROM cached_agents")
    suspend fun getAllCachedAgents(): List<CachedAgent>

    @Query("SELECT * FROM cached_agents WHERE uid = :uid")
    suspend fun getCachedAgent(uid: String): CachedAgent?

    @Query("SELECT * FROM cached_agent_summaries WHERE agentUid = :uid")
    suspend fun getSummariesForAgent(uid: String): List<CachedAgentSummary>

    @Query("SELECT * FROM cached_agent_summaries WHERE agentUid = :uid AND monthYear = :monthYear")
    suspend fun getSummary(uid: String, monthYear: String): CachedAgentSummary?

    @Upsert
    suspend fun upsertAgents(agents: List<CachedAgent>)

    @Upsert
    suspend fun upsertSummaries(summaries: List<CachedAgentSummary>)

    @Query("DELETE FROM cached_agents WHERE uid = :uid")
    suspend fun deleteAgentCache(uid: String)

    @Query("DELETE FROM cached_agent_summaries WHERE agentUid = :uid")
    suspend fun deleteAgentSummaries(uid: String)

    @Query("DELETE FROM cached_agent_summaries WHERE agentUid = :uid AND monthYear = :monthYear")
    suspend fun deleteAgentSummary(uid: String, monthYear: String)

    @Query("DELETE FROM cached_agents")
    suspend fun clearAgents()

    @Query("DELETE FROM cached_agent_summaries")
    suspend fun clearSummaries()
}
