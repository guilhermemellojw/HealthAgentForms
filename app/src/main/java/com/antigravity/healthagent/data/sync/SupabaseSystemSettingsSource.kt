package com.antigravity.healthagent.data.sync

import com.antigravity.healthagent.data.remote.supabase.toAnyMap
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * metadata.settings do Supabase (value JSONB) — mesma forma Map da interface.
 */
@Singleton
class SupabaseSystemSettingsSource @Inject constructor(
    private val supabase: SupabaseClient
) : SystemSettingsSource {
    override suspend fun fetchSystemSettings(): Result<Map<String, Any>> {
        return try {
            val rows = supabase.from("metadata").select {
                filter { eq("key", "settings") }
            }.decodeList<kotlinx.serialization.json.JsonObject>()
            val value = rows.firstOrNull()?.get("value")?.jsonObject?.toAnyMap()
            @Suppress("UNCHECKED_CAST")
            Result.success((value as? Map<String, Any>) ?: emptyMap())
        } catch (e: Exception) {
            Result.success(emptyMap())
        }
    }
}
