package com.antigravity.healthagent.data.repository

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.Tombstone
import com.antigravity.healthagent.data.local.model.TombstoneType
import com.antigravity.healthagent.data.settings.SettingsManager
import com.antigravity.healthagent.data.sync.DeletionHandler
import com.antigravity.healthagent.data.sync.SyncScheduler
import com.antigravity.healthagent.domain.logger.AppLogger
import com.antigravity.healthagent.domain.repository.HouseRepository
import com.antigravity.healthagent.data.util.nowIsoUtc
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Exclusões no Supabase: soft-delete (deleted_at) em vez de batch + arrays.
 * Tombstones locais (Room) idênticos ao Firebase. Sem loop legado multi-doc
 * (identidade única uuid — limpeza intencional).
 */
@Singleton
class SupabaseDeletionHandler @Inject constructor(
    private val supabase: SupabaseClient,
    private val houseRepository: HouseRepository,
    private val settingsManager: SettingsManager,
    private val syncSchedulerProvider: Provider<SyncScheduler>
) : DeletionHandler {

    override suspend fun deleteAgentHouse(agentUid: String, houseId: String): Result<Unit> {
        return try {
            supabase.from("houses").update(buildJsonObject { put("deleted_at", nowIsoUtc()) }) {
                filter { eq("agent_id", agentUid); eq("natural_key", houseId) }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteAgentActivity(agentUid: String, activityDate: String): Result<Unit> {
        return try {
            val dashDate = activityDate.replace("/", "-")
            supabase.from("day_activities").update(buildJsonObject { put("deleted_at", nowIsoUtc()) }) {
                filter { eq("agent_id", agentUid); eq("date_text", dashDate) }
            }
            // Casas do dia acompanham a exclusão (paridade deleteAgentActivity).
            val keys = supabase.from("houses").select(Columns.list("natural_key")) {
                filter { eq("agent_id", agentUid); eq("data_text", dashDate) }
            }.decodeList<kotlinx.serialization.json.JsonObject>()
                .mapNotNull { (it["natural_key"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            for (kc in keys.chunked(100)) {
                supabase.from("houses").update(buildJsonObject { put("deleted_at", nowIsoUtc()) }) {
                    filter { eq("agent_id", agentUid); isIn("natural_key", kc) }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun recordHouseDeletion(house: House): Result<Unit> {
        return try {
            val naturalId = house.generateNaturalKey()
            houseRepository.insertTombstone(
                Tombstone(
                    type = TombstoneType.HOUSE,
                    naturalKey = naturalId,
                    agentName = house.agentName,
                    agentUid = house.agentUid
                )
            )
            syncSchedulerProvider.get().scheduleSync()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun recordActivityDeletion(date: String, agentUid: String): Result<Unit> {
        return try {
            houseRepository.insertTombstone(
                Tombstone(
                    type = TombstoneType.ACTIVITY,
                    naturalKey = "$date|$agentUid",
                    agentUid = agentUid,
                    dataDate = date
                )
            )
            syncSchedulerProvider.get().scheduleSync()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun recordBulkDeletions(houseKeys: List<String>, activityDates: List<String>, targetUid: String?): Result<Unit> {
        val currentUid = supabase.auth.currentUserOrNull()?.id
        val isLocalUser = targetUid == null || targetUid == currentUid

        if (isLocalUser) {
            return try {
                val cached = settingsManager.cachedUser.firstOrNull()
                val currentAgentName = cached?.agentName?.uppercase()
                    ?: currentUid?.let { agentNameOf(it) }
                    ?: ""
                val uid = currentUid ?: ""
                val houseTombstones = houseKeys.map {
                    Tombstone(type = TombstoneType.HOUSE, naturalKey = it, agentName = currentAgentName, agentUid = uid)
                }
                val activityTombstones = activityDates.map {
                    Tombstone(type = TombstoneType.ACTIVITY, naturalKey = it, agentName = currentAgentName, agentUid = uid)
                }
                houseRepository.runInTransaction {
                    houseRepository.insertTombstones(houseTombstones)
                    houseRepository.insertTombstones(activityTombstones)
                }
                syncSchedulerProvider.get().scheduleSync()
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        return try {
            val uid = targetUid ?: currentUid ?: return Result.failure(Exception("User not authenticated"))
            if (houseKeys.isNotEmpty()) {
                for (kc in houseKeys.chunked(100)) {
                    supabase.from("houses").update(buildJsonObject { put("deleted_at", nowIsoUtc()) }) {
                        filter { eq("agent_id", uid); isIn("natural_key", kc) }
                    }
                }
            }
            if (activityDates.isNotEmpty()) {
                for (dc in activityDates.chunked(100)) {
                    val dates = dc.map { it.split("|")[0].replace("/", "-") }
                    supabase.from("day_activities").update(buildJsonObject { put("deleted_at", nowIsoUtc()) }) {
                        filter { eq("agent_id", uid); isIn("date_text", dates) }
                    }
                    for (d in dates) {
                        val keys = supabase.from("houses").select(Columns.list("natural_key")) {
                            filter { eq("agent_id", uid); eq("data_text", d) }
                        }.decodeList<kotlinx.serialization.json.JsonObject>()
                            .mapNotNull { (it["natural_key"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                        for (kc in keys.chunked(100)) {
                            supabase.from("houses").update(buildJsonObject { put("deleted_at", nowIsoUtc()) }) {
                                filter { eq("agent_id", uid); isIn("natural_key", kc) }
                            }
                        }
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteHousesSurgically(agentUid: String, houses: List<House>): Result<Unit> {
        if (houses.isEmpty()) return Result.success(Unit)
        return try {
            val houseKeys = houses.map { it.cloudId ?: it.generateNaturalKey() }
            recordBulkDeletions(houseKeys, emptyList(), agentUid)
            houseRepository.runInTransaction {
                houses.forEach { house ->
                    houseRepository.deleteHouseById(house.id)
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun agentNameOf(uid: String): String? {
        return try {
            val row = supabase.from("agents").select(Columns.list("agent_name")) {
                filter { eq("id", uid) }
            }.decodeSingleOrNull<kotlinx.serialization.json.JsonObject>()
            (row?.get("agent_name") as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it.isString }?.content?.uppercase()
        } catch (e: Exception) {
            AppLogger.w("SupabaseDeletion", "agentNameOf failed: ${e.message}")
            null
        }
    }
}
