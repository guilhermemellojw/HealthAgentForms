package com.antigravity.healthagent.data.repository

import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.remote.supabase.toAnyMap
import com.antigravity.healthagent.data.settings.SettingsManager
import com.antigravity.healthagent.data.sync.AdminHandler
import com.antigravity.healthagent.data.sync.SupabaseSystemSettingsSource
import com.antigravity.healthagent.domain.logger.AppLogger
import com.antigravity.healthagent.domain.repository.HouseRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Admin Supabase: locais idênticos; settings via metadata; wipes via deletes
 * relacionais; clearSyncError é no-op (sem coluna no schema — documentado).
 */
@Singleton
class SupabaseAdminHandler @Inject constructor(
    private val supabase: SupabaseClient,
    private val houseRepository: HouseRepository,
    private val settingsManager: SettingsManager
) : AdminHandler {

    private val settings = SupabaseSystemSettingsSource(supabase)

    override suspend fun clearLocalDataInternal(): Result<Unit> {
        return try {
            houseRepository.clearAllData()
            settingsManager.setLastSyncTimestamp(0L)
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e("SupabaseAdmin", "Internal Wipe Failed: ${e.message}")
            Result.failure(e)
        }
    }

    override suspend fun clearAgentDataInternal(agentUid: String): Result<Unit> {
        return try {
            houseRepository.clearAgentData(agentUid)
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e("SupabaseAdmin", "Surgical Wipe Failed for $agentUid: ${e.message}")
            Result.failure(e)
        }
    }

    override suspend fun performDataCleanup(): Result<Unit> {
        return try {
            houseRepository.cleanupZeroValues()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun restoreLocalData(houses: List<House>, activities: List<DayActivity>, agentUid: String?): Result<Unit> {
        return try {
            houseRepository.restoreAgentData(houses, activities, agentUid)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun fetchSystemSettings(): Result<Map<String, Any>> {
        return settings.fetchSystemSettings()
    }

    override suspend fun updateSystemSetting(key: String, value: Any): Result<Unit> {
        return try {
            val current = supabase.from("metadata").select {
                filter { eq("key", "settings") }
            }.decodeSingleOrNull<JsonObject>()?.toAnyMap()?.toMutableMap() ?: mutableMapOf()
            current[key] = value
            supabase.from("metadata").upsert(buildJsonObject {
                put("key", "settings")
                put("value", toJsonElement(value))
            }) { onConflict = "key" }
            if (key == "max_open_houses") {
                settingsManager.setMaxOpenHouses(toIntOr25(value))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteAllCloudData(): Result<Unit> {
        return try {
            val agents = supabase.from("agents").select {
                // sem filtro: admin varre tudo (RLS admin)
            }.decodeList<JsonObject>()
            for (chunk in agents.chunked(50)) {
                for (row in chunk) {
                    val id = (row["id"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: continue
                    for (table in listOf("houses", "day_activities", "monthly_summaries", "backups")) {
                        supabase.from(table).delete { filter { eq("agent_id", id) } }
                    }
                    supabase.from("day_transfers").delete { filter { eq("from_agent_id", id) } }
                    supabase.from("agents").delete { filter { eq("id", id) } }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun clearSyncError(uid: String): Result<Unit> {
        // Sem coluna last_sync_error no schema: updated_at carimba progresso.
        // Mantido como no-op de sucesso para o contrato.
        return Result.success(Unit)
    }

    override suspend fun pruneOldTombstones(): Result<Unit> {
        return try {
            val thirtyDaysAgo = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
            houseRepository.pruneOldTombstones(thirtyDaysAgo)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun toIntOr25(value: Any): Int {
        return when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: 25
            else -> 25
        }
    }

    private fun toJsonElement(value: Any): kotlinx.serialization.json.JsonElement {
        return when (value) {
            is Number -> kotlinx.serialization.json.JsonPrimitive(value.toDouble())
            is Boolean -> kotlinx.serialization.json.JsonPrimitive(value)
            is String -> kotlinx.serialization.json.JsonPrimitive(value)
            is Map<*, *> -> buildJsonObject {
                value.forEach { (k, v) -> if (k is String && v != null) put(k, toJsonElement(v)) }
            }
            is List<*> -> kotlinx.serialization.json.JsonArray(value.mapNotNull { v -> v?.let { toJsonElement(it) } })
            else -> kotlinx.serialization.json.JsonPrimitive(value.toString())
        }
    }
}
