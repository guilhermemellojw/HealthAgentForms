package com.antigravity.healthagent.data.sync

import com.antigravity.healthagent.data.local.model.House

/**
 * Exclusões com efeito na nuvem — backend-neutro. Firebase usa batch + arrays
 * de tombstone; Supabase usa soft-delete (deleted_at). Tombstones locais
 * (Room) são iguais nos dois.
 */
interface DeletionHandler {
    suspend fun deleteAgentHouse(agentUid: String, houseId: String): Result<Unit>
    suspend fun deleteAgentActivity(agentUid: String, activityDate: String): Result<Unit>
    suspend fun recordHouseDeletion(house: House): Result<Unit>
    suspend fun recordActivityDeletion(date: String, agentUid: String): Result<Unit>
    suspend fun recordBulkDeletions(houseKeys: List<String>, activityDates: List<String>, targetUid: String?): Result<Unit>
    suspend fun deleteHousesSurgically(agentUid: String, houses: List<House>): Result<Unit>
}
