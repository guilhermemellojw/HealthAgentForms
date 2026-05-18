package com.antigravity.healthagent.data.repository

import com.antigravity.healthagent.data.local.AppDatabase
import com.antigravity.healthagent.data.local.dao.DayActivityDao
import com.antigravity.healthagent.data.local.dao.HouseDao
import com.antigravity.healthagent.data.local.dao.TombstoneDao
import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.settings.SettingsManager
import com.antigravity.healthagent.utils.withRetry
import androidx.room.withTransaction
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

class SyncAdminHandler @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val houseDao: HouseDao,
    private val dayActivityDao: DayActivityDao,
    private val tombstoneDao: TombstoneDao,
    private val settingsManager: SettingsManager,
    private val database: AppDatabase
) {
    private suspend fun <T> runInTransactionWithRetry(block: suspend () -> T): T {
        return database.withRetry(maxAttempts = 3) {
            database.withTransaction { block() }
        }
    }

    suspend fun clearLocalDataInternal(): Result<Unit> {
        return try {
            runInTransactionWithRetry {
                houseDao.deleteAll()
                dayActivityDao.deleteAll()
                tombstoneDao.deleteAll()
                database.customStreetDao().deleteAll()
                database.agentCacheDao().clearAgents()
                database.agentCacheDao().clearSummaries()
            }
            settingsManager.setLastSyncTimestamp(0L)
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("SyncRepository", "Internal Wipe Failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun clearAgentDataInternal(agentUid: String): Result<Unit> {
        return try {
            runInTransactionWithRetry {
                houseDao.deleteByAgent(agentUid)
                dayActivityDao.deleteByAgent(agentUid)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("SyncRepository", "Surgical Wipe Failed for $agentUid: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun performDataCleanup(): Result<Unit> {
        return try {
            houseDao.cleanupZeroValues()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun restoreLocalData(houses: List<House>, activities: List<DayActivity>, agentUid: String?): Result<Unit> {
        val finalUid = agentUid ?: ""
        return try {
            runInTransactionWithRetry {
                val restoredDatesList = activities.map { it.date.replace("/", "-") }.distinct()

                if (restoredDatesList.isNotEmpty()) {
                    android.util.Log.i("SyncRepository", "Restoration: Atomic purge of ${restoredDatesList.size} dates for $finalUid")
                    houseDao.deleteByAgentAndDates(finalUid, restoredDatesList)
                    dayActivityDao.deleteByAgentAndDates(finalUid, restoredDatesList)
                }

                val normalizedActivities = activities.map { 
                    val normalized = it.copy(
                        agentUid = finalUid,
                        date = it.date.replace("/", "-"),
                        isSynced = false,
                        lastUpdated = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis()
                    ) 
                    normalized
                }
                
                normalizedActivities.forEach { normalized ->
                    tombstoneDao.deleteByNaturalKey("${normalized.date}|${normalized.agentUid}", normalized.agentUid)
                }

                val housesToUpsert = houses.map { restoredHouse ->
                    val finalSituation = if (restoredHouse.situation == com.antigravity.healthagent.data.local.model.Situation.EMPTY) {
                        com.antigravity.healthagent.data.local.model.Situation.NONE
                    } else restoredHouse.situation

                    val finalHouse = restoredHouse.copy(
                        id = 0,
                        agentUid = finalUid,
                        data = restoredHouse.data.replace("/", "-"),
                        situation = finalSituation,
                        isSynced = false,
                        lastUpdated = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis()
                    )
                    
                    finalHouse
                }
                
                housesToUpsert.forEach { finalHouse ->
                    tombstoneDao.deleteByNaturalKey(finalHouse.generateNaturalKey(), finalHouse.agentUid)
                }

                houseDao.upsertHouses(housesToUpsert)
                dayActivityDao.upsertDayActivities(normalizedActivities)
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchSystemSettings(): Result<Map<String, Any>> {
        return try {
            val snapshot = withTimeoutOrNull(5000) {
                firestore.collection("metadata").document("settings")
                    .get().await()
            }
            
            if (snapshot == null) return Result.success(emptyMap())
            
            val settings = snapshot.data ?: emptyMap()
            Result.success(settings)
        } catch (e: Exception) {
            android.util.Log.w("SyncRepository", "fetchSystemSettings offline fallback: ${e.message}")
            Result.success(emptyMap())
        }
    }

    suspend fun updateSystemSetting(key: String, value: Any): Result<Unit> {
        return try {
            firestore.collection("metadata").document("settings")
                .update(key, value)
                .await()
            
            if (key == "max_open_houses") {
                val intVal = when(value) {
                    is Long -> value.toInt()
                    is Int -> value
                    is Float -> value.toInt()
                    is Double -> value.toInt()
                    is String -> value.toIntOrNull() ?: 25
                    else -> 25
                }
                settingsManager.setMaxOpenHouses(intVal)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            try {
                firestore.collection("metadata").document("settings")
                    .set(mapOf(key to value))
                    .await()
                
                if (key == "max_open_houses") {
                    val intVal = when(value) {
                        is Long -> value.toInt()
                        is Int -> value
                        is Float -> value.toInt()
                        is Double -> value.toInt()
                        is String -> value.toIntOrNull() ?: 25
                        else -> 25
                    }
                    settingsManager.setMaxOpenHouses(intVal)
                }
                Result.success(Unit)
            } catch (e2: Exception) {
                Result.failure(e2)
            }
        }
    }

    suspend fun deleteAllCloudData(): Result<Unit> {
        return try {
            val agentsSnapshot = firestore.collection("agents").get().await()
            for (agentDoc in agentsSnapshot.documents) {
                val agentRef = agentDoc.reference
                
                val houses = agentRef.collection("houses").get().await()
                houses.documents.chunked(400).forEach { chunk ->
                    val batch = firestore.batch()
                    chunk.forEach { batch.delete(it.reference) }
                    batch.commit().await()
                }

                val activities = agentRef.collection("day_activities").get().await()
                activities.documents.chunked(400).forEach { chunk ->
                    val batch = firestore.batch()
                    chunk.forEach { batch.delete(it.reference) }
                    batch.commit().await()
                }

                agentRef.delete().await()
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun clearSyncError(uid: String): Result<Unit> {
        return try {
            firestore.collection("agents").document(uid)
                .update("lastSyncError", FieldValue.delete())
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun pruneOldTombstones(): Result<Unit> {
        return try {
            val thirtyDaysAgo = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
            tombstoneDao.deleteOldTombstones(thirtyDaysAgo)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
