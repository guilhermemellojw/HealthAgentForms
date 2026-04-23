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
     * Pulls data from Firestore and replaces the local Room database content.
     */
    suspend fun pullCloudDataToLocal(targetUid: String? = null, force: Boolean = false): Result<Unit>


    /**
     * Clears all local Room data (Houses, DayActivities).
     */
    suspend fun clearLocalData(): Result<Unit>
    suspend fun clearAgentData(agentName: String, agentUid: String): Result<Unit>

    /**
     * Performs a one-time cleanup of corrupted data (e.g., '0' in number/sequence/complement).
     */
    suspend fun performDataCleanup(): Result<Unit>

    /**
     * Surgically restores houses and activities into local database for a specific agent.
     */
    suspend fun restoreLocalData(agentName: String, houses: List<House>, activities: List<DayActivity>, agentUid: String? = null): Result<Unit>

    suspend fun fetchSystemSettings(): Result<Map<String, Any>>
    suspend fun updateSystemSetting(key: String, value: Any): Result<Unit>

    // Granular Data Management (Admin)
    suspend fun deleteAgentHouse(agentUid: String, houseId: String): Result<Unit>
    suspend fun deleteAgentActivity(agentUid: String, activityDate: String): Result<Unit>

    // Local-to-Cloud Deletion Synchronization (Tombstones)
    suspend fun recordHouseDeletion(house: House): Result<Unit>
    suspend fun recordActivityDeletion(date: String, agentName: String, agentUid: String): Result<Unit>
    suspend fun recordBulkDeletions(houseKeys: List<String>, activityDates: List<String>, targetUid: String? = null): Result<Unit>

    /**
     * NUCLEAR OPTION: Deletes ALL data for ALL agents in the cloud.
     */
    suspend fun deleteAllCloudData(): Result<Unit>

    /**
     * Clears the sync error message for a specific agent.
     */
    suspend fun clearSyncError(uid: String): Result<Unit>
}

