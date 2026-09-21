package com.antigravity.healthagent.data.repository

import android.content.Context
import android.net.Uri
import com.antigravity.healthagent.data.backup.BackupData
import com.antigravity.healthagent.data.backup.BackupManager
import com.antigravity.healthagent.data.remote.supabase.toAnyMap
import com.antigravity.healthagent.domain.logger.AppLogger
import com.antigravity.healthagent.domain.repository.BackupMetadata
import com.antigravity.healthagent.domain.repository.BackupRepository
import com.antigravity.healthagent.utils.TimeManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backups no Supabase em DUPLO FORMATO: `{ts}.json` puro (restaurável no
 * portal e no app) + `{ts}.aes` vinculado ao aparelho (só app). A linha
 * `backups` aponta para o JSON. Retenção 50/agente (linhas + arquivos).
 */
@Singleton
class SupabaseBackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val supabase: SupabaseClient,
    private val backupManager: BackupManager
) : BackupRepository {

    companion object {
        const val BUCKET = "backups"
        const val RETENTION_PER_AGENT = 50
    }

    override suspend fun uploadTimelineBackup(uid: String, data: BackupData): Result<Unit> {
        return try {
            val timestamp = TimeManager.currentTimeMillis()
            val jsonPath = "$uid/$timestamp.json"
            val aesPath = "$uid/$timestamp.aes"

            // 1. JSON puro (portal + app)
            val jsonBytes = backupManager.toPlainJson(data).toByteArray(Charsets.UTF_8)
            supabase.storage.from(BUCKET).upload(jsonPath, jsonBytes) { upsert = true }

            // 2. AES vinculado ao aparelho (restauração local futura)
            val tempFile = File(context.cacheDir, "temp_backup_$timestamp.json")
            try {
                backupManager.exportToFile(context, tempFile, data)
                supabase.storage.from(BUCKET).upload(aesPath, tempFile.readBytes()) { upsert = true }
            } finally {
                if (tempFile.exists()) tempFile.delete()
            }

            // 3. Metadados (apontam para o JSON restaurável)
            supabase.from("backups").upsert(buildJsonObject {
                put("agent_id", uid)
                put("ts", timestamp)
                put("storage_path", "$BUCKET/$jsonPath")
                put("house_count", data.houses.size)
                put("activity_count", data.dayActivities.size)
                put("agent_name", data.sourceAgentName ?: "Desconhecido")
            }) { onConflict = "agent_id,ts" }

            // 4. Retenção (best-effort)
            try {
                enforceRetention(uid)
            } catch (e: Exception) {
                AppLogger.w("SupabaseBackup", "Retention prune failed: ${e.message}")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun fetchTimeline(uid: String): Result<List<BackupMetadata>> {
        return try {
            val rows = supabase.from("backups").select {
                filter { eq("agent_id", uid) }
                order("ts", Order.DESCENDING)
            }.decodeList<JsonObject>()
            Result.success(rows.map { row ->
                val map = row.toAnyMap()
                BackupMetadata(
                    id = map["ts"].toString(),
                    timestamp = (map["ts"] as? Number)?.toLong() ?: 0L,
                    storagePath = (map["storage_path"] as? String) ?: "",
                    houseCount = (map["house_count"] as? Number)?.toInt() ?: 0,
                    activityCount = (map["activity_count"] as? Number)?.toInt() ?: 0,
                    agentName = (map["agent_name"] as? String) ?: ""
                )
            })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun downloadBackup(storagePath: String): Result<BackupData> {
        return try {
            val rel = storagePath.removePrefix("$BUCKET/")
            val bytes = withContext(Dispatchers.IO) {
                supabase.storage.from(BUCKET).downloadAuthenticated(rel)
            }
            val text = bytes.toString(Charsets.UTF_8)
            if (text.trimStart().startsWith("{")) {
                Result.success(backupManager.importPlainJson(text))
            } else {
                // Legado AES: via arquivo temp + import com chave do aparelho.
                val tempFile = File(context.cacheDir, "dl_backup_${TimeManager.currentTimeMillis()}.bin")
                try {
                    withContext(Dispatchers.IO) { tempFile.writeBytes(bytes) }
                    Result.success(backupManager.importData(context, Uri.fromFile(tempFile)))
                } finally {
                    if (tempFile.exists()) tempFile.delete()
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun enforceRetention(uid: String) {
        val rows = supabase.from("backups").select(Columns.list("ts")) {
            filter { eq("agent_id", uid) }
            order("ts", Order.DESCENDING)
        }.decodeList<JsonObject>()
        if (rows.size <= RETENTION_PER_AGENT) return
        val stale = rows.drop(RETENTION_PER_AGENT).mapNotNull {
            (it["ts"] as? JsonPrimitive)?.takeIf { p -> p.isString || p.toString().toLongOrNull() != null }
                ?.let { p -> p.content.toLongOrNull() ?: p.toString().toLongOrNull() }
        }
        if (stale.isEmpty()) return
        for (kc in stale.chunked(100)) {
            supabase.from("backups").delete { filter { eq("agent_id", uid); isIn("ts", kc) } }
        }
        val files = stale.flatMap { ts -> listOf("$uid/$ts.json", "$uid/$ts.aes") }
        for (fc in files.chunked(100)) {
            try {
                supabase.storage.from(BUCKET).delete(fc)
            } catch (e: Exception) {
                AppLogger.w("SupabaseBackup", "Storage prune failed: ${e.message}")
            }
        }
    }
}
