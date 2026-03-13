package com.antigravity.healthagent.domain.repository

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.DayActivity
import kotlinx.coroutines.flow.Flow

interface SyncRepository {
    /** 
     * Pushes all local Room data (Houses, DayActivities) to the logged-in user's Firestore document.
     * Usually called manually, or after finishing a day.
     */
    suspend fun pushLocalDataToCloud(
        houses: List<House>, 
        activities: List<DayActivity>, 
        targetUid: String? = null,
        shouldReplace: Boolean = false
    ): Result<Unit>
    
    /**
     * For Admin users to view all data submitted by all agents.
     */
    suspend fun fetchAllAgentsData(): Result<List<AgentData>>

    /**
     * Pulls data from Firestore and replaces the local Room database content.
     */
    suspend fun pullCloudDataToLocal(targetUid: String? = null): Result<Unit>

    /**
     * Pre-creates an agent entry in the cloud.
     */
    suspend fun createAgent(email: String, agentName: String? = null): Result<Unit>

    /**
     * Deletes an agent entry from the cloud.
     */
    suspend fun deleteAgent(uid: String): Result<Unit>

    /**
     * Fetches all registered agent names from the cloud.
     */
    suspend fun fetchAgentNames(): Result<List<String>>

    /**
     * Adds a new agent name to the cloud registry.
     */
    suspend fun addAgentName(name: String): Result<Unit>

    /**
     * Deletes an agent name from the cloud registry.
     */
    suspend fun deleteAgentName(name: String): Result<Unit>

    /**
     * Clears all local Room data (Houses, DayActivities).
     */
    suspend fun clearLocalData(): Result<Unit>

    /**
     * Surgically restores houses and activities into local database for a specific agent.
     */
    suspend fun restoreLocalData(agentName: String, houses: List<House>, activities: List<DayActivity>): Result<Unit>

    /**
     * Dynamic Configuration (Super Admin)
     */
    suspend fun fetchBairros(): Result<List<String>>
    suspend fun addBairro(name: String): Result<Unit>
    suspend fun deleteBairro(name: String): Result<Unit>
    
    suspend fun fetchSystemSettings(): Result<Map<String, Any>>
    suspend fun updateSystemSetting(key: String, value: Any): Result<Unit>

    // Granular Data Management (Admin)
    suspend fun deleteAgentHouse(agentUid: String, houseId: String): Result<Unit>
    suspend fun deleteAgentActivity(agentUid: String, activityDate: String): Result<Unit>
}

data class AgentData(
    val uid: String,
    val email: String,
    val agentName: String? = null,
    val houses: List<House>,
    val activities: List<DayActivity>,
    val lastSyncTime: Long = 0L
)
