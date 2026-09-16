package com.antigravity.healthagent.data.repository

import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.domain.repository.HouseRepository
import com.antigravity.healthagent.data.settings.SettingsManager
import com.antigravity.healthagent.domain.logger.AppLogger
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

class SyncAdminHandler @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val houseRepository: HouseRepository,
    private val settingsManager: SettingsManager
) {

    suspend fun clearLocalDataInternal(): Result<Unit> {
        return try {
            houseRepository.clearAllData()
            settingsManager.setLastSyncTimestamp(0L)
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e("SyncRepository", "Internal Wipe Failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun clearAgentDataInternal(agentUid: String): Result<Unit> {
        return try {
            houseRepository.clearAgentData(agentUid)
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e("SyncRepository", "Surgical Wipe Failed for $agentUid: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun performDataCleanup(): Result<Unit> {
        return try {
            houseRepository.cleanupZeroValues()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun restoreLocalData(houses: List<House>, activities: List<DayActivity>, agentUid: String?): Result<Unit> {
        return try {
            houseRepository.restoreAgentData(houses, activities, agentUid)
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
            AppLogger.w("SyncRepository", "fetchSystemSettings offline fallback: ${e.message}")
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
            houseRepository.pruneOldTombstones(thirtyDaysAgo)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
