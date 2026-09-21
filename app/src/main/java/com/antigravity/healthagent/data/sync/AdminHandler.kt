package com.antigravity.healthagent.data.sync

import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.local.model.House

/**
 * Operações administrativas/locais do sync — backend-neutro.
 */
interface AdminHandler {
    suspend fun clearLocalDataInternal(): Result<Unit>
    suspend fun clearAgentDataInternal(agentUid: String): Result<Unit>
    suspend fun performDataCleanup(): Result<Unit>
    suspend fun restoreLocalData(houses: List<House>, activities: List<DayActivity>, agentUid: String?): Result<Unit>
    suspend fun fetchSystemSettings(): Result<Map<String, Any>>
    suspend fun updateSystemSetting(key: String, value: Any): Result<Unit>
    suspend fun deleteAllCloudData(): Result<Unit>
    suspend fun clearSyncError(uid: String): Result<Unit>
    suspend fun pruneOldTombstones(): Result<Unit>
}
