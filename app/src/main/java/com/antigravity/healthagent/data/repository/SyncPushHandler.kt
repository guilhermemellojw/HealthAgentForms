package com.antigravity.healthagent.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.antigravity.healthagent.data.local.AppDatabase
import com.antigravity.healthagent.data.local.dao.DayActivityDao
import com.antigravity.healthagent.data.local.dao.HouseDao
import com.antigravity.healthagent.data.local.dao.TombstoneDao
import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.TombstoneType
import com.antigravity.healthagent.data.settings.SettingsManager
import com.antigravity.healthagent.data.util.toDayActivitySafe
import com.antigravity.healthagent.data.util.toHouseSafe
import com.antigravity.healthagent.utils.toDashDate
import com.antigravity.healthagent.utils.toSlashDate
import com.antigravity.healthagent.utils.withRetry
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncPushHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: FirebaseAuth,
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

    suspend fun pushLocalDataToCloud(
        houses: List<House>,
        activities: List<DayActivity>,
        targetUid: String?,
        shouldReplace: Boolean,
        syncMutex: Mutex
    ): Result<Unit> {
        val result = withTimeoutOrNull(600000L) {
            syncMutex.withLock {
                withContext(Dispatchers.IO) {
                    try {
                        val uid = targetUid ?: auth.currentUser?.uid ?: return@withContext Result.failure(Exception("User not authenticated"))
                        
                        // --- WIPE SAFETY CHECK ---
                        val agentDocSnapshot = try { firestore.collection("agents").document(uid).get().await() } catch(e: Exception) { null }
                        val agentDocExists = agentDocSnapshot?.exists() ?: true
                        val hasSyncHistory = settingsManager.lastSyncTimestamp.first() > 0

                        if (targetUid == null && (agentDocSnapshot?.getBoolean("requireDataReset") == true || (hasSyncHistory && !agentDocExists))) {
                            android.util.Log.w("SyncPushHandler", "Push blocked for $uid: Remote wipe pending or data purged.")
                            return@withContext Result.failure(Exception("Sincronização bloqueada: Uma limpeza de dados foi solicitada. Por favor, realize um 'Receber Dados' primeiro."))
                        }

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
                        val unsyncedHouses = houseDao.getUnsyncedHouses(uid)
                        val unsyncedActivities = dayActivityDao.getUnsyncedActivities(uid)
                        val tombstones = tombstoneDao.getAllTombstones(uid)

                        // Optimistic return if nothing to do (and not a forced replacement)
                        if (unsyncedHouses.isEmpty() && unsyncedActivities.isEmpty() && tombstones.isEmpty() && !shouldReplace) {
                            android.util.Log.i("SyncPushHandler", "Push: Nothing to sync (incremental).")
                            return@withContext Result.success(Unit)
                        }

                        // Decide which data to push
                        var housesToPush = if (shouldReplace) houses else unsyncedHouses
                        var activitiesToPush = if (shouldReplace) activities else unsyncedActivities

                        // SURGICAL PROTECTION: Identity Isolation Guard
                        if (uid.isNotBlank()) {
                            val isProxyPush = targetUid != null
                            
                            val crossUidHouses = housesToPush.filter { it.agentUid.isNotBlank() && it.agentUid != uid }
                            if (crossUidHouses.isNotEmpty()) {
                                android.util.Log.e("SyncPushHandler", "IDENTITY LEAK PREVENTED: Filtered out ${crossUidHouses.size} houses with mismatching UIDs.")
                            }
                            
                            housesToPush = if (isProxyPush) {
                                housesToPush.filter { it.agentUid == uid }
                            } else {
                                housesToPush.filter { it.agentUid.isBlank() || it.agentUid == uid }
                            }
                            
                            // DISCARD BROKEN HOUSES: Never push records missing core address fields
                            housesToPush = housesToPush.filter { h ->
                                h.address.bairro.isNotBlank() && 
                                h.address.streetName.isNotBlank() && 
                                h.address.blockNumber.isNotBlank() &&
                                (h.address.number.isNotBlank() || h.address.sequence > 0)
                            }

                            val crossUidActivities = activitiesToPush.filter { it.agentUid.isNotBlank() && it.agentUid != uid }
                            if (crossUidActivities.isNotEmpty()) {
                                android.util.Log.e("SyncPushHandler", "IDENTITY LEAK PREVENTED: Filtered out ${crossUidActivities.size} activities with mismatching UIDs.")
                            }
                            
                            activitiesToPush = if (isProxyPush) {
                                activitiesToPush.filter { it.agentUid == uid }
                            } else {
                                activitiesToPush.filter { it.agentUid.isBlank() || it.agentUid == uid }
                            }
                        }

                        // Recover agent name if missing
                        if (officialAgentName.isBlank()) {
                            officialAgentName = (housesToPush.firstOrNull { it.agentName.isNotBlank() }?.agentName 
                                ?: activitiesToPush.firstOrNull { it.agentName.isNotBlank() }?.agentName
                                ?: "").uppercase()
                            
                            if (officialAgentName.isNotBlank()) {
                                userDocRef.set(mapOf("agentName" to officialAgentName), SetOptions.merge()).await()
                            }
                        }

                        // Prepare Operations
                        val pInfo = try { context.packageManager.getPackageInfo(context.packageName, 0) } catch (e: Exception) { null }
                        val metadata = mutableMapOf<String, Any>(
                            "lastSyncTime" to com.antigravity.healthagent.utils.TimeManager.currentTimeMillis(),
                            "lastUpdated" to FieldValue.serverTimestamp(),
                            "appVersionCode" to (pInfo?.versionCode ?: 0),
                            "appVersionName" to (pInfo?.versionName ?: "Unknown")
                        )

                        if (shouldReplace) {
                            val backupDates = (
                                housesToPush.map { it.data.toDashDate() } + 
                                activitiesToPush.map { it.date.toDashDate() } +
                                tombstones.filter { it.type == TombstoneType.ACTIVITY }.map { it.naturalKey.split("|")[0].toDashDate() } +
                                tombstones.filter { it.type == TombstoneType.HOUSE }.mapNotNull { tk -> 
                                    val parts = tk.naturalKey.split("_")
                                    if (parts.size >= 3) parts[2].toDashDate() else null
                                }
                            ).toSet() 

                            val backupDatesWithAlternates = backupDates.flatMap { listOf(it, it.toSlashDate()) }.toSet()
                            
                            if (backupDatesWithAlternates.isNotEmpty()) {
                                val toDelete = mutableListOf<DocumentReference>()
                                backupDatesWithAlternates.toList().chunked(30).forEach { dateChunk ->
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

                            metadata["deleted_house_ids"] = FieldValue.delete()
                            metadata["deleted_activity_dates"] = FieldValue.delete()
                        }

                        if (shouldReplace && targetUid != null && targetUid != auth.currentUser?.uid) {
                            metadata["requireDataReset"] = true
                            android.util.Log.i("SyncPushHandler", "Admin Restore: Signalling target device ($uid) for local reset.")
                        }

                        if (targetUid == null || (existingEmail.isNullOrBlank() && userEmail != "Remote Sync")) {
                            metadata["email"] = userEmail
                        }
                        
                        val photoUrl = if (targetUid != null) {
                            try { firestore.collection("users").document(uid).get().await().getString("photoUrl") } catch(e: Exception) { null }
                        } else { auth.currentUser?.photoUrl?.toString() }
                        
                        if (photoUrl != null) metadata["photoUrl"] = photoUrl
                        if (officialAgentName.isNotBlank()) metadata["agentName"] = officialAgentName

                        // 1. TOMBSTONES
                        if (tombstones.isNotEmpty()) {
                            val cloudDeletedHouses = mutableListOf<String>()
                            val cloudDeletedActivities = mutableListOf<String>()
                            
                            tombstones.chunked(400).forEach { chunk ->
                                val batch = firestore.batch()
                                chunk.forEach { t ->
                                    if (t.type == TombstoneType.HOUSE) {
                                        batch.delete(userDocRef.collection("houses").document(t.naturalKey))
                                        cloudDeletedHouses.add(t.naturalKey)
                                    } else {
                                        batch.delete(userDocRef.collection("day_activities").document(t.naturalKey.split("|")[0]))
                                        cloudDeletedActivities.add(t.naturalKey)
                                    }
                                }
                                
                                if (cloudDeletedHouses.isNotEmpty()) {
                                    batch.update(userDocRef, "deleted_house_ids", FieldValue.arrayUnion(*cloudDeletedHouses.toTypedArray()))
                                }
                                if (cloudDeletedActivities.isNotEmpty()) {
                                    batch.update(userDocRef, "deleted_activity_dates", FieldValue.arrayUnion(*cloudDeletedActivities.toTypedArray()))
                                }
                                
                                batch.commit().await()
                                runInTransactionWithRetry {
                                    tombstoneDao.deleteTombstones(chunk.map { it.id })
                                }
                                cloudDeletedHouses.clear()
                                cloudDeletedActivities.clear()
                            }
                        }

                        // 2. HOUSES
                        if (housesToPush.isNotEmpty()) {
                            if (!shouldReplace) {
                                val affectedDates = housesToPush.map { it.data.toDashDate() }.toSet()
                                for (date in affectedDates) {
                                    try {
                                        val cloudSnapshot = userDocRef.collection("houses")
                                            .whereEqualTo("data", date)
                                            .get().await()
                                        
                                        val localHousesForDate = houseDao.getHousesByDateAndAgent(date, uid)
                                        val localKeys = localHousesForDate.map { it.generateNaturalKey() }.toSet()
                                        val localIdentities = localHousesForDate.map { it.generateIdentityKey() }.toSet()
                                        
                                        val toDelete = cloudSnapshot.documents.filter { doc ->
                                            val cloudHouse = doc.toHouseSafe(uid, officialAgentName) ?: return@filter false
                                            val cloudKey = doc.id
                                            val cloudIdentity = cloudHouse.generateIdentityKey()
                                            cloudKey !in localKeys && cloudIdentity in localIdentities
                                        }
                                        
                                        if (toDelete.isNotEmpty()) {
                                            toDelete.chunked(400).forEach { chunk ->
                                                val delBatch = firestore.batch()
                                                chunk.forEach { delBatch.delete(it.reference) }
                                                delBatch.commit().await()
                                            }
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.w("SyncPushHandler", "Cloud Deduplication context skip for $date: ${e.message}")
                                    }
                                }
                            }

                            housesToPush.chunked(400).forEach { chunk ->
                                val batch = firestore.batch()
                                val pushedKeys = mutableListOf<String>()
                                
                                chunk.forEach { house ->
                                    val isAdminPush = shouldReplace && targetUid != null && targetUid != auth.currentUser?.uid
                                    val officialHouse = house.copy(
                                        agentName = officialAgentName.ifBlank { house.agentName },
                                        agentUid = uid,
                                        editedByAdmin = isAdminPush || house.editedByAdmin
                                    )
                                    val houseData = officialHouse.toFirestoreMap()
                                    val key = officialHouse.generateNaturalKey()
                                    batch.set(userDocRef.collection("houses").document(key), houseData, SetOptions.merge())
                                    pushedKeys.add(key)
                                }
                                
                                batch.update(userDocRef, "deleted_house_ids", FieldValue.arrayRemove(*pushedKeys.toTypedArray()))
                                
                                batch.commit().await()
                                runInTransactionWithRetry {
                                    chunk.forEach { house ->
                                        houseDao.markAsSyncedWithTimestamp(house.id, house.lastUpdated, uid, officialAgentName)
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
                                    val isAdminPush = shouldReplace && targetUid != null && targetUid != auth.currentUser?.uid
                                    val officialActivity = activity.copy(
                                        agentName = officialAgentName, 
                                        agentUid = uid,
                                        editedByAdmin = isAdminPush || activity.editedByAdmin
                                    )
                                    val activityData = officialActivity.toFirestoreMap()
                                    val dateKey = activity.date.replace("/", "-")
                                    batch.set(userDocRef.collection("day_activities").document(dateKey), activityData, SetOptions.merge())
                                    pushedDates.add(dateKey)
                                }
                                
                                val datesToRemove = pushedDates.map { "$it|${officialAgentName.uppercase()}" } + pushedDates
                                batch.update(userDocRef, "deleted_activity_dates", FieldValue.arrayRemove(*datesToRemove.toTypedArray()))
                                
                                batch.commit().await()
                                runInTransactionWithRetry {
                                    chunk.forEach { activity ->
                                        dayActivityDao.markAsSyncedWithTimestamp(activity.date, officialAgentName, uid, activity.lastUpdated)
                                    }
                                }
                            }
                        }

                        // 4. SUMMARY AGGREGATION
                        val allAffectedDates = (
                            housesToPush.map { it.data } + 
                            activitiesToPush.map { it.date } + 
                            tombstones.map { it.dataDate }.filter { it.isNotBlank() }
                        ).map { it.replace("/", "-") }.distinct()
                        val monthsToUpdate = allAffectedDates.map { it.split("-").let { if(it.size >= 3) "${it[1]}-${it[2]}" else "" } }
                            .filter { it.isNotBlank() }
                            .toSet()

                        for (monthYear in monthsToUpdate) {
                            val isProxyPush = targetUid != null && targetUid != auth.currentUser?.uid
                            val todayInt = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date()).toInt()
                            
                            val housesInMonthRaw = if (isProxyPush && !shouldReplace) {
                                val monthParts = monthYear.split("-")
                                val month = monthParts[0].toIntOrNull() ?: 1
                                val year = monthParts[1].toIntOrNull() ?: 2024
                                
                                val calendar = java.util.Calendar.getInstance()
                                calendar.set(year, month - 1, 1)
                                val daysInMonth = calendar.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
                                
                                val allDays = (1..daysInMonth).map { day -> String.format("%02d-%s", day, monthYear) }
                                
                                val allDocs = allDays.chunked(30).flatMap { chunk ->
                                    if (chunk.isNotEmpty()) {
                                        userDocRef.collection("houses")
                                            .whereIn("data", chunk)
                                            .get().await().documents
                                    } else emptyList()
                                }
                                
                                allDocs.mapNotNull { it.toHouseSafe(uid, officialAgentName) }
                            } else {
                                if (shouldReplace) {
                                    housesToPush.filter { it.data.replace("/", "-").contains(monthYear) }
                                } else {
                                    houseDao.getHousesByMonth(uid, monthYear)
                                }
                            }
                            
                            val housesInMonth = housesInMonthRaw.filter { house ->
                                try {
                                    val parts = house.data.replace("/", "-").split("-")
                                    if (parts.size == 3) {
                                        String.format("%04d%02d%02d", parts[2].toInt(), parts[1].toInt(), parts[0].toInt()).toInt() <= todayInt
                                    } else true
                                } catch(e: Exception) { true }
                            }
                            
                            val activitiesInMonthRaw = if (isProxyPush && !shouldReplace) {
                                val calendar = java.util.Calendar.getInstance()
                                val monthParts = monthYear.split("-")
                                val month = monthParts[0].toIntOrNull() ?: 1
                                val year = monthParts[1].toIntOrNull() ?: 2024
                                calendar.set(year, month - 1, 1)
                                val daysInMonth = calendar.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)

                                val allDays = (1..daysInMonth).map { day -> String.format("%02d-%s", day, monthYear) }
                                
                                allDays.chunked(30).flatMap { chunk ->
                                    userDocRef.collection("day_activities").whereIn("date", chunk).get().await().documents
                                }.mapNotNull { it.toDayActivitySafe(uid, officialAgentName) }
                            } else {
                                if (shouldReplace) {
                                    activitiesToPush.filter { it.date.replace("/", "-").contains(monthYear) }
                                } else {
                                    dayActivityDao.getDayActivitiesByMonth(uid, monthYear)
                                }
                            }
                            
                            val activitiesInMonth = activitiesInMonthRaw.filter { activity ->
                                try {
                                    val parts = activity.date.replace("/", "-").split("-")
                                    if (parts.size == 3) {
                                        String.format("%04d%02d%02d", parts[2].toInt(), parts[1].toInt(), parts[0].toInt()).toInt() <= todayInt
                                    } else true
                                } catch(e: Exception) { true }
                            }
                            
                            val summary = mapOf(
                                "monthYear" to monthYear,
                                "treatedCount" to housesInMonth.count { house ->
                                    (house.treatment.a1 + house.treatment.a2 + house.treatment.b + house.treatment.c + house.treatment.d1 + house.treatment.d2 + house.treatment.e + house.treatment.eliminados) > 0 ||
                                    house.treatment.larvicida > 0.0 || house.treatment.comFoco
                                },
                                "focusCount" to housesInMonth.count { it.treatment.comFoco },
                                "situationCounts" to housesInMonth.groupingBy { 
                                    if (it.situation == com.antigravity.healthagent.data.local.model.Situation.EMPTY) "NONE" else it.situation.name 
                                }.eachCount(),
                                "propertyTypeCounts" to housesInMonth.groupingBy { it.propertyType.name }.eachCount(),
                                "totalHouses" to housesInMonth.size,
                                "daysWorked" to activitiesInMonth.size,
                                "lastUpdated" to com.antigravity.healthagent.utils.TimeManager.currentTimeMillis()
                            )
                            
                            userDocRef.collection("monthly_summaries").document(monthYear).set(summary).await()
                        }

                        userDocRef.update(metadata + mapOf(
                            "lastSyncTime" to com.antigravity.healthagent.utils.TimeManager.currentTimeMillis(),
                            "lastSyncError" to FieldValue.delete()
                        )).await()

                        // 6. TIMELINE BACKUP
                        if (uid.isNotEmpty()) {
                            try {
                                val workRequest = androidx.work.OneTimeWorkRequestBuilder<com.antigravity.healthagent.data.backup.TimelineBackupWorker>()
                                    .setInputData(androidx.work.workDataOf("uid" to uid, "officialAgentName" to officialAgentName))
                                    .build()
                                androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                                    "TimelineBackup_$uid", 
                                    androidx.work.ExistingWorkPolicy.REPLACE, 
                                    workRequest
                                )
                                android.util.Log.i("SyncPushHandler", "Timeline Backup worker enqueued for $uid")
                            } catch (e: Exception) {
                                android.util.Log.w("SyncPushHandler", "Timeline Backup enqueue failed (non-critical): ${e.message}")
                            }
                        }

                        Result.success(Unit)
                    } catch (e: Exception) {
                        android.util.Log.e("SyncPushHandler", "Push failed: ${e.message}", e)
                        Result.failure(e)
                    }
                }
            }
        } ?: Result.failure(Exception("Sincronização atingiu o tempo limite. Verifique sua conexão ou tente novamente se o backup for muito grande."))
        return result
    }
}
