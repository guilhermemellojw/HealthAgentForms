package com.antigravity.healthagent.data.repository

import androidx.room.withTransaction
import com.antigravity.healthagent.data.local.AppDatabase
import com.antigravity.healthagent.data.local.dao.HouseDao
import com.antigravity.healthagent.data.local.dao.TombstoneDao
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.Tombstone
import com.antigravity.healthagent.data.local.model.TombstoneType
import com.antigravity.healthagent.data.settings.SettingsManager
import com.antigravity.healthagent.data.sync.SyncScheduler
import com.antigravity.healthagent.utils.withRetry
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Provider

class SyncDeletionHandler @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val houseDao: HouseDao,
    private val tombstoneDao: TombstoneDao,
    private val settingsManager: SettingsManager,
    private val database: AppDatabase,
    private val syncSchedulerProvider: Provider<SyncScheduler>
) {
    private suspend fun <T> runInTransactionWithRetry(block: suspend () -> T): T {
        return database.withRetry(maxAttempts = 3) {
            database.withTransaction { block() }
        }
    }

    suspend fun deleteAgentHouse(agentUid: String, houseId: String): Result<Unit> {
        return try {
            val agentRef = firestore.collection("agents").document(agentUid)
            val batch = firestore.batch()
            
            batch.delete(agentRef.collection("houses").document(houseId))
            batch.update(agentRef, "deleted_house_ids", FieldValue.arrayUnion(houseId))
            
            batch.commit().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteAgentActivity(agentUid: String, activityDate: String): Result<Unit> {
        return try {
            val agentRef = firestore.collection("agents").document(agentUid)
            
            firestore.collection("agents").document(agentUid)
                .collection("day_activities").document(activityDate)
                .delete()
                .await()
            
            val housesSnap = agentRef.collection("houses")
                .whereEqualTo("data", activityDate)
                .get()
                .await()
            
            if (!housesSnap.isEmpty) {
                housesSnap.documents.chunked(500).forEach { chunk ->
                    val batch = firestore.batch()
                    chunk.forEach { batch.delete(it.reference) }
                    batch.commit().await()
                }
            }
                
            val agentDoc = agentRef.get().await()
            val agentName = agentDoc.getString("agentName") ?: ""
            val fullKey = if (agentName.isNotBlank()) "$activityDate|${agentName.uppercase()}" else activityDate
            
            agentRef.update("deleted_activity_dates", FieldValue.arrayUnion(fullKey)).await()
            
            val email = agentDoc.getString("email")
            if (!email.isNullOrBlank()) {
                 val legacyDocs = firestore.collection("agents").whereEqualTo("email", email).get().await()
                 legacyDocs.documents.forEach { doc ->
                     if (doc.id != agentUid) {
                          val batch = firestore.batch()
                          batch.delete(doc.reference.collection("day_activities").document(activityDate))
                          batch.update(doc.reference, "deleted_activity_dates", FieldValue.arrayUnion(fullKey))
                          
                          val legacyHouses = doc.reference.collection("houses").whereEqualTo("data", activityDate).get().await()
                          legacyHouses.documents.forEach { h -> batch.delete(h.reference) }
                          
                          batch.commit().await()
                     }
                 }
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun recordHouseDeletion(house: House): Result<Unit> {
        return try {
            val naturalId = house.generateNaturalKey()
            tombstoneDao.insertTombstone(
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

    suspend fun recordActivityDeletion(date: String, agentUid: String): Result<Unit> {
        return try {
            tombstoneDao.insertTombstone(
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

    suspend fun recordBulkDeletions(houseKeys: List<String>, activityDates: List<String>, targetUid: String?): Result<Unit> {
        val isLocalUser = targetUid == null || targetUid == auth.currentUser?.uid
        
        if (isLocalUser) {
            return try {
                val cached = settingsManager.cachedUser.firstOrNull()
                val currentAgentName = (cached?.agentName?.uppercase()) 
                    ?: (try { firestore.collection("agents").document(auth.currentUser?.uid ?: "").get().await().getString("agentName")?.uppercase() } catch(e: Exception) { null }) 
                    ?: ""
                val currentUid = auth.currentUser?.uid ?: ""

                val houseTombstones = houseKeys.map { 
                    Tombstone(
                        type = TombstoneType.HOUSE,
                        naturalKey = it,
                        agentName = currentAgentName,
                        agentUid = currentUid
                    )
                }
                val activityTombstones = activityDates.map { 
                    Tombstone(
                        type = TombstoneType.ACTIVITY,
                        naturalKey = it,
                        agentName = currentAgentName,
                        agentUid = currentUid
                    )
                }
                runInTransactionWithRetry {
                    tombstoneDao.insertTombstones(houseTombstones)
                    tombstoneDao.insertTombstones(activityTombstones)
                }
                syncSchedulerProvider.get().scheduleSync()
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        return try {
            val uid = targetUid ?: auth.currentUser?.uid ?: return Result.failure(Exception("User not authenticated"))
            val docRef = firestore.collection("agents").document(uid)
            val agentDoc = docRef.get().await()
            val agentName = agentDoc.getString("agentName") ?: ""
            val email = agentDoc.getString("email")

            if (houseKeys.isNotEmpty()) {
                houseKeys.chunked(400).forEach { chunk ->
                    val batch = firestore.batch()
                    chunk.forEach { key ->
                        batch.delete(docRef.collection("houses").document(key))
                    }
                    batch.update(docRef, "deleted_house_ids", FieldValue.arrayUnion(*chunk.toTypedArray()))
                    batch.commit().await()
                }
            }

            if (activityDates.isNotEmpty()) {
                activityDates.chunked(100).forEach { chunk ->
                    val batch = firestore.batch()
                    chunk.forEach { tombstone ->
                        val dateKey = tombstone.split("|")[0]
                        batch.delete(docRef.collection("day_activities").document(dateKey))
                        
                        try {
                            val activityDateDashed = dateKey.replace("/", "-")
                            val activityHouses = docRef.collection("houses")
                                .whereEqualTo("data", activityDateDashed)
                                .get().await()
                            activityHouses.documents.forEach { batch.delete(it.reference) }
                        } catch (e: Exception) { /* Ignore query errors */ }
                    }
                    val chunkKeys = chunk.map { 
                        if (it.contains("|")) it.replace("/", "-") 
                        else if (agentName.isNotBlank()) "${it.replace("/", "-")}|${agentName.uppercase()}" 
                        else it.replace("/", "-")
                    }
                    batch.update(docRef, "deleted_activity_dates", FieldValue.arrayUnion(*chunkKeys.toTypedArray()))
                    batch.update(docRef, "lastSyncTime", System.currentTimeMillis())
                    batch.commit().await()
                }
            }
            
            if (!email.isNullOrBlank()) {
                val legacyDocs = firestore.collection("agents").whereEqualTo("email", email).get().await()
                for (legacyDoc in legacyDocs.documents) {
                    if (legacyDoc.id == uid) continue
                    
                    val batch = firestore.batch()
                    if (houseKeys.isNotEmpty()) {
                        houseKeys.forEach { batch.delete(legacyDoc.reference.collection("houses").document(it)) }
                        batch.update(legacyDoc.reference, "deleted_house_ids", FieldValue.arrayUnion(*houseKeys.toTypedArray()))
                        batch.update(legacyDoc.reference, "lastSyncTime", System.currentTimeMillis())
                    }
                    if (activityDates.isNotEmpty()) {
                        activityDates.forEach { 
                            val dateKey = it.split("|")[0]
                            batch.delete(legacyDoc.reference.collection("day_activities").document(dateKey)) 
                        }
                        val fullKeys = activityDates.map { 
                             if (it.contains("|")) it else if (agentName.isNotBlank()) "$it|${agentName.uppercase()}" else it
                        }
                        batch.update(legacyDoc.reference, "deleted_activity_dates", FieldValue.arrayUnion(*fullKeys.toTypedArray()))
                    }
                    batch.commit().await()
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteHousesSurgically(agentUid: String, houses: List<House>): Result<Unit> {
        if (houses.isEmpty()) return Result.success(Unit)
        
        return try {
            val houseKeys = houses.map { it.cloudId ?: it.generateNaturalKey() }
            
            recordBulkDeletions(houseKeys, emptyList(), agentUid)
            
            runInTransactionWithRetry {
                houses.forEach { house ->
                    houseDao.deleteHouseById(house.id)
                }
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
