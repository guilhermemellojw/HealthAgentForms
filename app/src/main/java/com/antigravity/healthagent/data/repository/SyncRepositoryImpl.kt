package com.antigravity.healthagent.data.repository

import androidx.room.withTransaction
import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.domain.repository.AgentData
import com.antigravity.healthagent.domain.repository.SyncRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.*
import com.antigravity.healthagent.data.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncRepositoryImpl @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val houseDao: com.antigravity.healthagent.data.local.dao.HouseDao,
    private val dayActivityDao: com.antigravity.healthagent.data.local.dao.DayActivityDao,
    private val tombstoneDao: com.antigravity.healthagent.data.local.dao.TombstoneDao,
    private val settingsManager: com.antigravity.healthagent.data.settings.SettingsManager,
    private val database: com.antigravity.healthagent.data.local.AppDatabase,
    private val syncScheduler: com.antigravity.healthagent.data.sync.SyncScheduler
) : SyncRepository {

    private val syncMutex = Mutex()

    override suspend fun pushLocalDataToCloud(
        houses: List<House>,
        activities: List<DayActivity>,
        targetUid: String?,
        shouldReplace: Boolean
    ): Result<Unit> = syncMutex.withLock {
        return try {
            val uid = targetUid ?: auth.currentUser?.uid ?: return Result.failure(Exception("User not authenticated"))
            val userDocRef = firestore.collection("agents").document(uid)
            
            // Fetch existing cloud metadata
            val existingDoc = try { userDocRef.get().await() } catch(e: Exception) { null }
            val existingEmail = existingDoc?.getString("email")
            val existingAgentName = existingDoc?.getString("agentName") ?: ""
            
            val userEmail = if (targetUid != null) {
                if (existingEmail.isNullOrBlank()) {
                    try { firestore.collection("users").document(uid).get().await().getString("email") ?: "Remote Sync" } catch(e: Exception) { "Remote Sync" }
                } else { existingEmail }
            } else { auth.currentUser?.email ?: "Unknown Email" }
            
            var officialAgentName = existingAgentName.uppercase()

            // 1. Fetch Local Data and Tombstones
            val unsyncedHouses = houseDao.getUnsyncedHouses(officialAgentName, uid)
            val unsyncedActivities = dayActivityDao.getUnsyncedActivities(officialAgentName, uid)
            val tombstones = tombstoneDao.getAllTombstones(officialAgentName, uid)

            // Optimistic return if nothing to do (and not a forced replacement)
            if (unsyncedHouses.isEmpty() && unsyncedActivities.isEmpty() && tombstones.isEmpty() && !shouldReplace) {
                android.util.Log.i("SyncRepository", "Push: Nothing to sync (incremental).")
                return Result.success(Unit)
            }

            // Decide which data to push
            val housesToPush = if (shouldReplace) houses else unsyncedHouses
            val activitiesToPush = if (shouldReplace) activities else unsyncedActivities

            // Recover agent name if missing
            if (officialAgentName.isBlank()) {
                officialAgentName = (housesToPush.firstOrNull { it.agentName.isNotBlank() }?.agentName 
                    ?: activitiesToPush.firstOrNull { it.agentName.isNotBlank() }?.agentName
                    ?: "").uppercase()
                
                if (officialAgentName.isNotBlank()) {
                    userDocRef.set(mapOf("agentName" to officialAgentName), com.google.firebase.firestore.SetOptions.merge()).await()
                }
            }

            if (shouldReplace) {
                // Perform surgical wipe in cloud for dates present in the full backup
                val backupDates = (housesToPush.map { it.data } + activitiesToPush.map { it.date }).toSet()
                
                // Clear cloud subcollections for these dates
                suspend fun clearOldData(col: String, field: String) {
                    val snapshot = userDocRef.collection(col).get().await()
                    snapshot.documents.forEach { doc ->
                        if (backupDates.contains(doc.getString(field)?.replace("/", "-"))) {
                            doc.reference.delete()
                        }
                    }
                }
                
                // Note: Not very efficient for huge collections, but standard for 'Replace' logic here
                clearOldData("houses", "data")
                clearOldData("day_activities", "date")
            }

            // Prepare Operations
            val metadata = mutableMapOf<String, Any>(
                "lastSyncTime" to System.currentTimeMillis(),
                "lastUpdated" to com.google.firebase.firestore.FieldValue.serverTimestamp()
            )

            if (shouldReplace) {
                // SURGICAL WIPE: Only delete cloud data for the dates we are actually pushing.
                // IMPROVEMENT: Include dates from tombstones in the wipe to ensure moved/deleted 
                // production does not "ghost" back from the cloud during a Full Sync.
                val backupDates = (
                    housesToPush.map { it.data } + 
                    activitiesToPush.map { it.date } +
                    tombstones.filter { it.type == com.antigravity.healthagent.data.local.model.TombstoneType.ACTIVITY }.map { it.naturalKey.split("|")[0] } +
                    tombstones.filter { it.type == com.antigravity.healthagent.data.local.model.TombstoneType.HOUSE }.mapNotNull { tk -> 
                        // Robustly extract date (DD-MM-YYYY) from natural key
                        val dateRegex = Regex("\\d{2}-\\d{2}-\\d{4}")
                        dateRegex.find(tk.naturalKey)?.value
                    }
                ).toSet()
                
                if (backupDates.isNotEmpty()) {
                    val toDelete = mutableListOf<DocumentReference>()
                    
                    // We can use whereIn for up to 30 values at a time in Firestore
                    backupDates.toList().chunked(30).forEach { dateChunk ->
                        val houseDocs = userDocRef.collection("houses")
                            .whereIn("data", dateChunk)
                            .get().await()
                        toDelete.addAll(houseDocs.documents.map { it.reference })
                        
                        val activityDocs = userDocRef.collection("day_activities")
                            .whereIn("date", dateChunk)
                            .get().await()
                        toDelete.addAll(activityDocs.documents.map { it.reference })
                    }

                    if (toDelete.isNotEmpty()) {
                        toDelete.chunked(400).forEach { chunk ->
                            val batch = firestore.batch()
                            chunk.forEach { batch.delete(it) }
                            batch.commit().await()
                        }
                    }
                }

                metadata["deleted_house_ids"] = com.google.firebase.firestore.FieldValue.delete()
                metadata["deleted_activity_dates"] = com.google.firebase.firestore.FieldValue.delete()
            }
            if (targetUid == null || (existingEmail.isNullOrBlank() && userEmail != "Remote Sync")) {
                metadata["email"] = userEmail
            }
            
            val photoUrl = if (targetUid != null) {
                try { firestore.collection("users").document(uid).get().await().getString("photoUrl") } catch(e: Exception) { null }
            } else { auth.currentUser?.photoUrl?.toString() }
            
            if (photoUrl != null) metadata["photoUrl"] = photoUrl
            if (officialAgentName != null) metadata["agentName"] = officialAgentName

            // PREPARE AND COMMIT DATA IN ATOMIC BATCHES
            // To ensure atomicity and prevent duplicate pushes on partial failures, we commit and locally confirm each batch immediately.
            
            // 1. TOMBSTONES (Deletions)
            if (tombstones.isNotEmpty()) {
                val cloudDeletedHouses = mutableListOf<String>()
                val cloudDeletedActivities = mutableListOf<String>()
                
                tombstones.chunked(400).forEach { chunk ->
                    val batch = firestore.batch()
                    chunk.forEach { t ->
                        if (t.type == com.antigravity.healthagent.data.local.model.TombstoneType.HOUSE) {
                            batch.delete(userDocRef.collection("houses").document(t.naturalKey))
                            cloudDeletedHouses.add(t.naturalKey)
                        } else {
                            batch.delete(userDocRef.collection("day_activities").document(t.naturalKey.split("|")[0]))
                            cloudDeletedActivities.add(t.naturalKey)
                        }
                    }
                    
                    // Update cloud metadata arrays for tombstones 
                    if (cloudDeletedHouses.isNotEmpty()) {
                        batch.update(userDocRef, "deleted_house_ids", com.google.firebase.firestore.FieldValue.arrayUnion(*cloudDeletedHouses.toTypedArray()))
                    }
                    if (cloudDeletedActivities.isNotEmpty()) {
                        batch.update(userDocRef, "deleted_activity_dates", com.google.firebase.firestore.FieldValue.arrayUnion(*cloudDeletedActivities.toTypedArray()))
                    }
                    
                    batch.commit().await()
                    
                    // Local confirmation
                    database.withTransaction {
                        tombstoneDao.deleteTombstones(chunk.map { it.id })
                    }
                    cloudDeletedHouses.clear()
                    cloudDeletedActivities.clear()
                }
            }

            // 2. HOUSES
            if (housesToPush.isNotEmpty()) {
                housesToPush.chunked(400).forEach { chunk ->
                    val batch = firestore.batch()
                    val pushedKeys = mutableListOf<String>()
                    
                    chunk.forEach { house ->
                        val officialHouse = house.copy(agentName = officialAgentName ?: house.agentName)
                        val houseData = officialHouse.toFirestoreMap()
                        val key = officialHouse.generateNaturalKey()
                        batch.set(userDocRef.collection("houses").document(key), houseData, com.google.firebase.firestore.SetOptions.merge())
                        pushedKeys.add(key)
                    }
                    
                    // CRITICAL recovery: ensure re-added houses aren't in deleted_house_ids
                    if (!shouldReplace) {
                        batch.update(userDocRef, "deleted_house_ids", com.google.firebase.firestore.FieldValue.arrayRemove(*pushedKeys.toTypedArray()))
                    }
                    
                    batch.commit().await()
                    
                    // Local confirmation
                    database.withTransaction {
                        chunk.forEach { house ->
                            houseDao.markAsSyncedWithTimestamp(house.id, house.lastUpdated)
                        }
                    }
                }
            }

            // 3. ACTIVITIES
            if (activitiesToPush.isNotEmpty()) {
                activitiesToPush.chunked(400).forEach { chunk ->
                    val batch = firestore.batch()
                    val pushedDates = mutableListOf<String>()
                    
                    chunk.forEach { activity ->
                        val activityData = activity.copy(agentName = officialAgentName, agentUid = uid).toFirestoreMap()
                        val dateKey = activity.date.replace("/", "-")
                        batch.set(userDocRef.collection("day_activities").document(dateKey), activityData, com.google.firebase.firestore.SetOptions.merge())
                        pushedDates.add(dateKey)
                    }
                    
                    if (!shouldReplace) {
                        batch.update(userDocRef, "deleted_activity_dates", com.google.firebase.firestore.FieldValue.arrayRemove(*pushedDates.toTypedArray()))
                    }
                    
                    batch.commit().await()
                    
                    // Local confirmation
                    database.withTransaction {
                        chunk.forEach { activity ->
                            dayActivityDao.markAsSyncedWithTimestamp(activity.date, officialAgentName, uid, activity.lastUpdated)
                        }
                    }
                }
            }

            // 4. SUMMARY AGGREGATION (Recalculate from local ground truth for accuracy)
            val allAffectedDates = (housesToPush.map { it.data } + activitiesToPush.map { it.date }).map { it.replace("/", "-") }
            val monthsToUpdate = allAffectedDates.map { it.split("-").let { if(it.size >= 3) "${it[1]}-${it[2]}" else "" } }
                .filter { it.isNotBlank() }
                .toSet()

            for (monthYear in monthsToUpdate) {
                val isProxyPush = targetUid != null && targetUid != auth.currentUser?.uid
                
                val housesInMonth = if (isProxyPush && shouldReplace) {
                    housesToPush.filter { it.data.replace("/", "-").endsWith(monthYear) }
                } else {
                    houseDao.getHousesByMonth(officialAgentName ?: "", uid, monthYear)
                }
                
                val activitiesInMonth = if (isProxyPush && shouldReplace) {
                    activitiesToPush.filter { it.date.replace("/", "-").endsWith(monthYear) }
                } else {
                    dayActivityDao.getDayActivitiesByMonth(officialAgentName ?: "", uid, monthYear)
                }
                
                val summary = mapOf(
                    "monthYear" to monthYear,
                    "treatedCount" to housesInMonth.count { house ->
                        (house.a1 + house.a2 + house.b + house.c + house.d1 + house.d2 + house.e + house.eliminados) > 0 ||
                        house.larvicida > 0.0 || house.comFoco
                    },
                    "focusCount" to housesInMonth.count { it.comFoco },
                    "situationCounts" to housesInMonth.groupingBy { 
                        if (it.situation == com.antigravity.healthagent.data.local.model.Situation.EMPTY) "NONE" else it.situation.name 
                    }.eachCount(),
                    "propertyTypeCounts" to housesInMonth.groupingBy { it.propertyType.name }.eachCount(),
                    "totalHouses" to housesInMonth.size,
                    "daysWorked" to activitiesInMonth.size,
                    "lastUpdated" to System.currentTimeMillis()
                )
                
                userDocRef.collection("monthly_summaries").document(monthYear).set(summary).await()
            }

            // 5. METADATA (Final Step)
            userDocRef.update(metadata + mapOf(
                "lastSyncTime" to System.currentTimeMillis(),
                "lastSyncError" to com.google.firebase.firestore.FieldValue.delete()
            )).await()

            Result.success(Unit)
        } catch (e: com.google.firebase.firestore.FirebaseFirestoreException) {
            android.util.Log.e("SyncRepository", "Firestore error: ${e.code}, ${e.message}", e)
            val errorMsg = if (e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                "Acesso negado ao sincronizar. Verifique se sua conta foi autorizada."
            } else e.message ?: "Erro Firestore"
            
            // Record failure in cloud if possible
            val uid = targetUid ?: auth.currentUser?.uid
            if (uid != null) {
                firestore.collection("agents").document(uid).update("lastSyncError", errorMsg).await()
            }

            Result.failure(Exception(errorMsg))
        } catch (e: Exception) {
            android.util.Log.e("SyncRepository", "General sync error: ${e.message}", e)
            
            // Record failure in cloud if possible
            val uid = targetUid ?: auth.currentUser?.uid
            if (uid != null) {
                firestore.collection("agents").document(uid).update("lastSyncError", e.message ?: "Erro desconhecido").await()
            }
            
            Result.failure(e)
        }
    }


    override suspend fun pullCloudDataToLocal(targetUid: String?, force: Boolean): Result<Unit> = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val uid = targetUid ?: auth.currentUser?.uid ?: return@withContext Result.failure(Exception("Not logged in"))
                val isTargetDifferentUser = targetUid != null && targetUid != auth.currentUser?.uid
            
            // 1. Basic Setup
            val userDoc = firestore.collection("users").document(uid).get().await()
            val email = userDoc.getString("email") ?: auth.currentUser?.email ?: return@withContext Result.failure(Exception("User email not found"))
            val profileAgentName = userDoc.getString("agentName")?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
            val profileDisplayName = userDoc.getString("displayName")?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
            val finalAgentName = profileAgentName ?: profileDisplayName ?: email.substringBefore("@").uppercase()
            
            // --- REMOTE WIPE CHECK (Data Transfer Safety) ---
            val requireReset = userDoc.getBoolean("requireDataReset") ?: false
            if (requireReset) {
                android.util.Log.w("SyncRepository", "Remote Wipe Triggered for UID: $uid")
                clearLocalData() // Wipes local SQLite
                // Reset flag in Firestore
                firestore.collection("users").document(uid).update("requireDataReset", false)
            }

            // BUG FIX: Detect if lastSync is in the future (Device clock skew) and reset to 0 to force recovery
            val cachedLastSync = if (isTargetDifferentUser || force || requireReset) 0L else settingsManager.lastSyncTimestamp.first()
            val now = System.currentTimeMillis()
            val lastSync = if (cachedLastSync > now + 3600000L) 0L else cachedLastSync // Buffer of 1h
            
            val serverTime = now

            // 2. Fetchers (EXHAUSTIVE DISCOVERY)
            // We search for ALL documents in 'agents' that match this UID OR this email
            // This handles cases where history is stored under legacy naming or previous UIDs.
            val possibleAgentDocs = mutableListOf(firestore.collection("agents").document(uid))
            
            if (email.isNotBlank()) {
                try {
                    val matchingEmailDocs = firestore.collection("agents")
                        .whereEqualTo("email", email)
                        .get().await()
                    
                    matchingEmailDocs.documents.forEach { doc ->
                        if (doc.id != uid) {
                            possibleAgentDocs.add(doc.reference)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("SyncRepository", "Failed to search legacy agent docs", e)
                }
            }

            val cloudHouses = mutableListOf<House>()
            val cloudDayActivities = mutableListOf<DayActivity>()
            val cloudDeletedHouses = mutableSetOf<String>()

            val cloudDeletedActivities = mutableSetOf<String>()

            val discoveryResults = coroutineScope {
                possibleAgentDocs.map { agentDocRef ->
                    async {
                        val agentDoc = try { agentDocRef.get().await() } catch(e: Exception) { null }
                        val housesCollection = agentDocRef.collection("houses")
                        val activitiesCollection = agentDocRef.collection("day_activities")

                        val housesJob = async {
                            if (lastSync > 0) {
                                housesCollection.whereGreaterThan("lastUpdated", com.google.firebase.Timestamp(lastSync / 1000, ((lastSync % 1000) * 1000000).toInt()))
                                    .get().await()
                                    .documents.mapNotNull { it.toHouseSafe(uid, finalAgentName) }
                            } else {
                                housesCollection.get().await()
                                    .documents.mapNotNull { it.toHouseSafe(uid, finalAgentName) }
                            }
                        }

                        val activitiesJob = async {
                            if (lastSync > 0) {
                                activitiesCollection.whereGreaterThan("lastUpdated", com.google.firebase.Timestamp(lastSync / 1000, ((lastSync % 1000) * 1000000).toInt()))
                                    .get().await()
                                    .documents.mapNotNull { it.toDayActivitySafe(uid, finalAgentName) }
                            } else {
                                activitiesCollection.get().await()
                                    .documents.mapNotNull { it.toDayActivitySafe(uid, finalAgentName) }
                            }
                        }

                        val houses = housesJob.await()
                        val activities = activitiesJob.await()

                        @Suppress("UNCHECKED_CAST")
                        val deletedHouses = (agentDoc?.get("deleted_house_ids") as? List<String> ?: emptyList())
                            .map { it.replace("/", "-") }
                        @Suppress("UNCHECKED_CAST")
                        val deletedActivities = (agentDoc?.get("deleted_activity_dates") as? List<String> ?: emptyList())
                            .map { it.replace("/", "-") }
                        
                        Triple(houses, activities, deletedHouses to deletedActivities)
                    }
                }.awaitAll()
            }

            for (result in discoveryResults) {
                cloudHouses.addAll(result.first)
                cloudDayActivities.addAll(result.second)
                cloudDeletedHouses.addAll(result.third.first)
                cloudDeletedActivities.addAll(result.third.second)
            }

            // --- CRITICAL PROTECTION: Data Trumps Deletions ---
            // If a house or activity exists in the cloud as valid data, it should NEVER be considered deleted.
            // This prevents "fragmented" agent records (old UIDs/docs for same email) from using stale tombstones
            // to delete valid work found in other documents.
            val validCloudHouseKeys = cloudHouses.map { it.generateNaturalKey() }.toSet()
            val validCloudActivityKeys = cloudDayActivities.map { "${it.date.replace("/", "-")}|${it.agentName.uppercase()}" }.toSet()
            
            // BUG FIX: Only remove from deleted list if the data is found in a doc with RECENT activity.
            // If we found a tombstone in the primary doc, we should favor it unless the data is found in multiple docs.
            cloudDeletedHouses.removeAll { it in validCloudHouseKeys }
            cloudDeletedActivities.removeAll { it in validCloudActivityKeys }

            // 3. Deletion logic
            val allLocalHouses = houseDao.getAllHousesSnapshot()
            
            // Protection: Identified Locked Days
            val lockedKeys = if (finalAgentName.isNotBlank()) {
                try {
                    dayActivityDao.getAllDayActivities(finalAgentName, uid)
                        .filter { it.isClosed }
                        .map { "${it.date}|${it.agentName.uppercase()}" }
                        .toSet()
                } catch (e: Exception) { emptySet() }
            } else emptySet()

            val housesToDelete = allLocalHouses.filter { house ->
                val key = house.generateNaturalKey()
                
                // BUG FIX: Only delete local houses if they are already marked as synced. 
                // EXCEPTION 1: If the house is in cloudDeletedHouses and the local lastUpdated is significantly
                // older than the current sync (more than 1 hour), we assume it's a "Ghost" and delete it.
                // EXCEPTION 2 (ADMIN AUTHORITY): If the household matches a tombstone and the local record 
                // was last updated more than 15 minutes ago, we assume the Admin has priority and delete it even if unsynced.
                if (key in cloudDeletedHouses) {
                    val fifteenMinutesAgo = System.currentTimeMillis() - 900000L // 15 mins
                    if (house.isSynced) true
                    else if (house.lastUpdated < fifteenMinutesAgo) true
                    else (System.currentTimeMillis() - house.lastUpdated > 3600000L) // 1h ghost check
                } else false
            }

            val allLocalActivities = dayActivityDao.getAllDayActivities(finalAgentName, uid)
            
            // HEALING: Delete any activities with empty dates (corruption from previous sync versions)
            val corruptActivities = allLocalActivities.filter { it.date.isBlank() }
            if (corruptActivities.isNotEmpty()) {
                android.util.Log.w("SyncRepository", "Healing: Cleaning up ${corruptActivities.size} records with empty dates")
                corruptActivities.forEach { 
                    dayActivityDao.deleteDayActivity(it.date, it.agentName, it.agentUid)
                }
            }

            val activitiesToDelete = allLocalActivities.filter { activity ->
                val dateKey = "${activity.date}|${activity.agentName.uppercase()}"
                
                if (dateKey in cloudDeletedActivities) {
                    val fifteenMinutesAgo = System.currentTimeMillis() - 900000L // 15 mins
                    if (activity.isSynced) true
                    else if (activity.lastUpdated < fifteenMinutesAgo) true
                    else (System.currentTimeMillis() - activity.lastUpdated > 3600000L) // 1h ghost check
                } else false
            }

            // 4. Reconciliation
            val localHouses = houseDao.getAllHousesSnapshot().groupBy { it.generateNaturalKey() }
            val housesDelta = cloudHouses.filter { it.generateNaturalKey() !in cloudDeletedHouses }
            
            val localActivities = dayActivityDao.getAllDayActivities(finalAgentName, uid).groupBy { "${it.date.replace("/", "-")}|${it.agentName}" }
            val activitiesDelta = cloudDayActivities

            database.withTransaction {
                // Delete Houses
                if (housesToDelete.isNotEmpty()) {
                    for (house in housesToDelete) {
                        houseDao.deleteHouse(house)
                    }
                }

                // Delete Activities
                if (activitiesToDelete.isNotEmpty()) {
                    for (activity in activitiesToDelete) {
                        dayActivityDao.deleteDayActivity(activity.date, activity.agentName, activity.agentUid)
                    }
                }

                // Upsert Houses
                val housesToUpsert = housesDelta.mapNotNull { cloudHouse ->
                    val key = cloudHouse.generateNaturalKey()
                    var existing = localHouses[key]?.firstOrNull()
                    
                    // --- IDENTITY HEALING (KEY-SHIFT PROTECTION) ---
                    // If cloud house was edited by Admin but doesn't exist locally by key, 
                    // check if a house exists with the same (Data, Block, Sequence, Street, Number).
                    // This handles cases where Admin corrects a typo in the street name or block.
                    if (existing == null && cloudHouse.editedByAdmin) {
                        existing = allLocalHouses.find { h ->
                            h.data == cloudHouse.data && 
                            h.blockNumber == cloudHouse.blockNumber && 
                            h.number == cloudHouse.number &&
                            h.sequence == cloudHouse.sequence &&
                            h.complement == cloudHouse.complement
                        }
                        if (existing != null) {
                            android.util.Log.i("SyncRepository", "Healing: Key shift detected for Admin edit. Merging ${existing.id}")
                            // We will delete the "old" local house with the wrong key
                            houseDao.deleteHouse(existing)
                        }
                    }

                    // CONFLICT RESOLUTION: Last Side to Change Wins (LWW)
                    if (existing != null && !existing.isSynced) {
                        // Priority 1: Admin Authority. If cloud record was edited by admin, it wins immediately
                        // unless local record is VERY fresh (< 5 mins).
                        if (cloudHouse.editedByAdmin && (System.currentTimeMillis() - existing.lastUpdated > 300000L)) {
                            // Cloud wins
                        } else {
                            // Priority 2: Standard LWW
                            // Cloud wins if it is newer than (Local + threshold).
                            val threshold = com.antigravity.healthagent.utils.AppConstants.SYNC_CONFLICT_THRESHOLD_MS
                            if (existing.lastUpdated > (cloudHouse.lastUpdated + threshold)) {
                                return@mapNotNull null
                            }
                        }
                    }

                    val finalListOrder = if (cloudHouse.listOrder == 0L && existing != null && existing.listOrder != 0L) {
                        existing.listOrder
                    } else {
                        cloudHouse.listOrder
                    }

                    cloudHouse.copy(
                        id = existing?.id ?: 0,
                        listOrder = finalListOrder,
                        agentName = finalAgentName,
                        agentUid = uid,
                        isSynced = true
                    )
                }
                
                // Upsert Activities
                val activitiesToUpsert = activitiesDelta.mapNotNull { activity ->
                    val normalizedDate = activity.date.replace("/", "-")
                    val key = "$normalizedDate|$finalAgentName"
                    val dateKey = "$normalizedDate|${finalAgentName.uppercase()}"
                    
                    if (dateKey in cloudDeletedActivities) {
                        return@mapNotNull null // Don't restore something that was explicitly deleted in the cloud
                    }
                    
                    val existing = localActivities[key]?.firstOrNull()

                    if (existing != null && !existing.isSynced) {
                         // Protect local unsynced workday metadata using LWW
                         // Priority 1: Remote "Manual Unlock" always wins over local lock.
                         val isRemoteUnlock = activity.isManualUnlock && !existing.isManualUnlock
                         
                         // Priority 2: Admin Authority
                         val isAdminOverride = activity.editedByAdmin && (System.currentTimeMillis() - existing.lastUpdated > 300000L)

                         val threshold = com.antigravity.healthagent.utils.AppConstants.SYNC_CONFLICT_THRESHOLD_MS
                         
                         if (!isRemoteUnlock && !isAdminOverride && existing.lastUpdated > (activity.lastUpdated + threshold)) {
                             return@mapNotNull null
                         }
                    }

                    activity.copy(
                        date = normalizedDate,
                        agentName = finalAgentName,
                        agentUid = uid, // CRITICAL: Fix missing agentUid tagging in activities
                        isSynced = true
                    )
                }

                houseDao.upsertHouses(housesToUpsert)
                dayActivityDao.upsertDayActivities(activitiesToUpsert)
            }

            // 5. Update local sync tracking
            if (!isTargetDifferentUser) {
                // BUG FIX: Subtract 1 minute safety buffer to account for clock skew/latency
                settingsManager.setLastSyncTimestamp(serverTime - 60000L)
            }

                Result.success(Unit)
            } catch (e: Exception) {
                android.util.Log.e("SyncRepository", "Pull failed", e)
                if (e.message?.contains("PERMISSION_DENIED") == true) {
                    Result.failure(Exception("Acesso negado. Tente fazer login novamente."))
                } else {
                    Result.failure(e)
                }
            }
        }
    }


    override suspend fun clearLocalData(): Result<Unit> {
        return try {
            database.withTransaction {
                houseDao.deleteAll()
                dayActivityDao.deleteAll()
                tombstoneDao.deleteAll()
            }
            settingsManager.setLastSyncTimestamp(0L)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun performDataCleanup(): Result<Unit> {
        return try {
            houseDao.cleanupZeroValues()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun restoreLocalData(agentName: String, houses: List<House>, activities: List<DayActivity>, agentUid: String?): Result<Unit> {
        val finalUid = agentUid ?: ""
        return try {
            database.withTransaction {
                // Deduplicate Houses locally to prevent auto-generated ID duplicates.
                // We group by the natural key and store a mutable list of existing IDs 
                // to ensure a 1-to-1 mapping during restoration.
                val localHouses = houseDao.getAllHouses(agentName, finalUid).first()
                val localHouseGroups = localHouses.groupBy { it.generateNaturalKey() }
                    .mapValues { (_, houses) -> houses.toMutableList() }
                
                val normalizedActivities = activities.map { 
                    val finalAgentName = if (it.agentName.isNotBlank()) it.agentName else agentName
                    val normalized = it.copy(
                        agentName = finalAgentName.uppercase(),
                        agentUid = finalUid,
                        date = it.date.replace("/", "-")
                    ) 
                    // CRITICAL: Clear local tombstone for restored activity
                    tombstoneDao.deleteByNaturalKey("${normalized.date}|${normalized.agentName}", normalized.agentName, normalized.agentUid)
                    normalized
                }
                val housesToUpsert = mutableListOf<House>()

                houses.forEach { restoredHouse ->
                    val key = restoredHouse.generateNaturalKey()
                    // 1-to-1 Mapping: Consume an existing ID if available for this specific key.
                    // This prevents multiple houses from the backup overwriting the same local record.
                    val finalAgentName = if (restoredHouse.agentName.isNotBlank()) restoredHouse.agentName else agentName
                    val existingId = localHouseGroups[key]?.removeFirstOrNull()?.id ?: 0
                    
                    // Healing logic: Default EMPTY to NONE (Aberto) for restored data
                    val finalSituation = if (restoredHouse.situation == com.antigravity.healthagent.data.local.model.Situation.EMPTY) {
                        com.antigravity.healthagent.data.local.model.Situation.NONE
                    } else restoredHouse.situation

                    val finalHouse = restoredHouse.copy(
                        id = existingId,
                        agentName = finalAgentName.uppercase(),
                        agentUid = finalUid,
                        data = restoredHouse.data.replace("/", "-"),
                        situation = finalSituation
                    )
                    
                    // CRITICAL: Clear local tombstone for restored house
                    tombstoneDao.deleteByNaturalKey(finalHouse.generateNaturalKey(), finalHouse.agentName, finalHouse.agentUid)
                    
                    housesToUpsert.add(finalHouse)
                }

                houseDao.upsertHouses(housesToUpsert)
                dayActivityDao.upsertDayActivities(normalizedActivities)
                
                // Cleanup ghosts ONLY for the dates being restored
                val restoredDates = (housesToUpsert.map { it.data.replace("/", "-") } + normalizedActivities.map { it.date.replace("/", "-") }).toSet()
                
                for ((key, matches) in localHouseGroups) {
                    val houseDate = matches.firstOrNull()?.data?.replace("/", "-") ?: continue
                    if (houseDate in restoredDates) {
                        if (matches.isNotEmpty()) {
                            val keptId = housesToUpsert.find { it.generateNaturalKey() == key }?.id
                            for (house in matches) {
                                if (house.id != keptId && house.id != 0) {
                                    houseDao.deleteHouse(house)
                                }
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


    override suspend fun fetchSystemSettings(): Result<Map<String, Any>> {
        return try {
            val snapshot = withTimeoutOrNull(1500) {
                firestore.collection("metadata").document("settings")
                    .get().await()
            }
            
            if (snapshot == null) return Result.success(emptyMap<String, Any>())
            
            val settings = snapshot.data ?: emptyMap<String, Any>()
            Result.success(settings)
        } catch (e: Exception) {
            android.util.Log.w("SyncRepository", "fetchSystemSettings offline fallback: ${e.message}")
            Result.success(emptyMap<String, Any>())
        }
    }

    override suspend fun updateSystemSetting(key: String, value: Any): Result<Unit> {
        return try {
            firestore.collection("metadata").document("settings")
                .update(key, value)
                .await()
            
            // Sync locally if it's a known setting
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
            // If document doesn't exist, create it
            try {
                firestore.collection("metadata").document("settings")
                    .set(mapOf(key to value))
                    .await()
                
                // Sync locally if it's a known setting
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

    override suspend fun deleteAgentHouse(agentUid: String, houseId: String): Result<Unit> {
        return try {
            val agentRef = firestore.collection("agents").document(agentUid)
            val batch = firestore.batch()
            
            // 1. Delete the doc
            batch.delete(agentRef.collection("houses").document(houseId))
            
            // 2. Record tombstone in metadata to prevent overwrite on next agent sync
            batch.update(agentRef, "deleted_house_ids", com.google.firebase.firestore.FieldValue.arrayUnion(houseId))
            
            batch.commit().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteAgentActivity(agentUid: String, activityDate: String): Result<Unit> {
        return try {
            val agentRef = firestore.collection("agents").document(agentUid)
            
            // 1. Delete the activity doc
            firestore.collection("agents").document(agentUid)
                .collection("day_activities").document(activityDate)
                .delete()
                .await()
            
            // 2. Surgically delete houses for this date
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
                
            // 3. Record tombstone (BUG FIX: Use full key format DATE|AGENT_NAME)
            val agentDoc = agentRef.get().await()
            val agentName = agentDoc.getString("agentName") ?: ""
            val fullKey = if (agentName.isNotBlank()) "$activityDate|${agentName.uppercase()}" else activityDate
            
            agentRef.update("deleted_activity_dates", com.google.firebase.firestore.FieldValue.arrayUnion(fullKey)).await()
            
            // 4. Also perform wipe on legacy documents for this user (Same Email discovery)
            val email = agentDoc.getString("email")
            if (!email.isNullOrBlank()) {
                 val legacyDocs = firestore.collection("agents").whereEqualTo("email", email).get().await()
                 legacyDocs.documents.forEach { doc ->
                     if (doc.id != agentUid) {
                         val batch = firestore.batch()
                         batch.delete(doc.reference.collection("day_activities").document(activityDate))
                         batch.update(doc.reference, "deleted_activity_dates", com.google.firebase.firestore.FieldValue.arrayUnion(fullKey))
                         
                         // Also clear houses for this date in legacy docs
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

    override suspend fun recordHouseDeletion(house: House): Result<Unit> {
        return try {
            val naturalId = house.generateNaturalKey()
            tombstoneDao.insertTombstone(
                com.antigravity.healthagent.data.local.model.Tombstone(
                    type = com.antigravity.healthagent.data.local.model.TombstoneType.HOUSE,
                    naturalKey = naturalId,
                    agentName = house.agentName,
                    agentUid = house.agentUid
                )
            )
            syncScheduler.scheduleSync()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun recordActivityDeletion(date: String, agentName: String, agentUid: String): Result<Unit> {
        return try {
            tombstoneDao.insertTombstone(
                com.antigravity.healthagent.data.local.model.Tombstone(
                    type = com.antigravity.healthagent.data.local.model.TombstoneType.ACTIVITY,
                    naturalKey = "$date|$agentName",
                    agentName = agentName,
                    agentUid = agentUid
                )
            )
            syncScheduler.scheduleSync()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }


    override suspend fun recordBulkDeletions(houseKeys: List<String>, activityDates: List<String>, targetUid: String?): Result<Unit> {
        val isLocalUser = targetUid == null || targetUid == auth.currentUser?.uid
        
        if (isLocalUser) {
            return try {
                // Determine identity for tombstones (crucial for data isolation)
                // Use cache fallback for robust offline deletion support
                val cached = settingsManager.cachedUser.firstOrNull()
                val currentAgentName = (cached?.agentName?.uppercase()) 
                    ?: (try { firestore.collection("agents").document(auth.currentUser?.uid ?: "").get().await().getString("agentName")?.uppercase() } catch(e: Exception) { null }) 
                    ?: ""
                val currentUid = auth.currentUser?.uid ?: ""

                val houseTombstones = houseKeys.map { 
                    com.antigravity.healthagent.data.local.model.Tombstone(
                        type = com.antigravity.healthagent.data.local.model.TombstoneType.HOUSE,
                        naturalKey = it,
                        agentName = currentAgentName,
                        agentUid = currentUid
                    )
                }
                val activityTombstones = activityDates.map { 
                    com.antigravity.healthagent.data.local.model.Tombstone(
                        type = com.antigravity.healthagent.data.local.model.TombstoneType.ACTIVITY,
                        naturalKey = it,
                        agentName = currentAgentName,
                        agentUid = currentUid
                    )
                }
                database.withTransaction {
                    tombstoneDao.insertTombstones(houseTombstones)
                    tombstoneDao.insertTombstones(activityTombstones)
                }
                syncScheduler.scheduleSync()
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        // Direct Cloud Operations (for Admin/Global Cleanup)
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
                    batch.update(docRef, "deleted_house_ids", com.google.firebase.firestore.FieldValue.arrayUnion(*chunk.toTypedArray()))
                    batch.commit().await()
                }
            }

            if (activityDates.isNotEmpty()) {
                val fullKeys = activityDates.map { 
                    if (it.contains("|")) it else if (agentName.isNotBlank()) "$it|${agentName.uppercase()}" else it
                }
                
                activityDates.chunked(400).forEach { chunk ->
                    val batch = firestore.batch()
                    chunk.forEach { tombstone ->
                        val dateKey = tombstone.split("|")[0]
                        batch.delete(docRef.collection("day_activities").document(dateKey))
                    }
                    // Use standardized full keys for tombstones
                    val chunkKeys = chunk.map { 
                        if (it.contains("|")) it else if (agentName.isNotBlank()) "$it|${agentName.uppercase()}" else it
                    }
                    batch.update(docRef, "deleted_activity_dates", com.google.firebase.firestore.FieldValue.arrayUnion(*chunkKeys.toTypedArray()))
                    batch.commit().await()
                }
            }
            
            // GLOBAL WIPE: Clear legacy records for same email
            if (!email.isNullOrBlank()) {
                val legacyDocs = firestore.collection("agents").whereEqualTo("email", email).get().await()
                for (legacyDoc in legacyDocs.documents) {
                    if (legacyDoc.id == uid) continue
                    
                    val batch = firestore.batch()
                    // Houses
                    if (houseKeys.isNotEmpty()) {
                        houseKeys.forEach { batch.delete(legacyDoc.reference.collection("houses").document(it)) }
                        batch.update(legacyDoc.reference, "deleted_house_ids", com.google.firebase.firestore.FieldValue.arrayUnion(*houseKeys.toTypedArray()))
                    }
                    // Activities
                    if (activityDates.isNotEmpty()) {
                        activityDates.forEach { 
                            val dateKey = it.split("|")[0]
                            batch.delete(legacyDoc.reference.collection("day_activities").document(dateKey)) 
                        }
                        val fullKeys = activityDates.map { 
                             if (it.contains("|")) it else if (agentName.isNotBlank()) "$it|${agentName.uppercase()}" else it
                        }
                        batch.update(legacyDoc.reference, "deleted_activity_dates", com.google.firebase.firestore.FieldValue.arrayUnion(*fullKeys.toTypedArray()))
                    }
                    batch.commit().await()
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteAllCloudData(): Result<Unit> {
        return try {
            val agentsSnapshot = firestore.collection("agents").get().await()
            for (agentDoc in agentsSnapshot.documents) {
                val agentRef = agentDoc.reference
                
                // 1. Delete Houses
                val houses = agentRef.collection("houses").get().await()
                houses.documents.chunked(400).forEach { chunk ->
                    val batch = firestore.batch()
                    chunk.forEach { batch.delete(it.reference) }
                    batch.commit().await()
                }

                // 2. Delete Activities
                val activities = agentRef.collection("day_activities").get().await()
                activities.documents.chunked(400).forEach { chunk ->
                    val batch = firestore.batch()
                    chunk.forEach { batch.delete(it.reference) }
                    batch.commit().await()
                }

                // 3. Delete Agent Doc
                agentRef.delete().await()
            }
            
            // 4. Also optionally clear global metadata if needed, but for now we focus on productive data
            // firestore.collection("metadata").document("agent_info").delete().await()
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun clearSyncError(uid: String): Result<Unit> {
        return try {
            firestore.collection("agents").document(uid)
                .update("lastSyncError", com.google.firebase.firestore.FieldValue.delete())
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
}
