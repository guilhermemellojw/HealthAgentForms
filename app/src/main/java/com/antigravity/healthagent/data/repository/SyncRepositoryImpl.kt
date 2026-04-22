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
import com.antigravity.healthagent.utils.*
import com.antigravity.healthagent.utils.withRetry
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import android.content.pm.PackageInfo

@Singleton
class SyncRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val houseDao: com.antigravity.healthagent.data.local.dao.HouseDao,
    private val dayActivityDao: com.antigravity.healthagent.data.local.dao.DayActivityDao,
    private val tombstoneDao: com.antigravity.healthagent.data.local.dao.TombstoneDao,
    private val settingsManager: com.antigravity.healthagent.data.settings.SettingsManager,
    private val database: com.antigravity.healthagent.data.local.AppDatabase,
    private val syncSchedulerProvider: javax.inject.Provider<com.antigravity.healthagent.data.sync.SyncScheduler>
) : SyncRepository {

    private suspend fun <T> runInTransactionWithRetry(block: suspend () -> T): T {
        return database.withRetry(maxAttempts = 3) {
            database.withTransaction { block() }
        }
    }

    private val syncMutex = Mutex()

    override suspend fun pushLocalDataToCloud(
        houses: List<House>,
        activities: List<DayActivity>,
        targetUid: String?,
        shouldReplace: Boolean
    ): Result<Unit> {
        val result = withTimeoutOrNull(180000L) {
            syncMutex.withLock {
                try {
                    val uid = targetUid ?: auth.currentUser?.uid ?: return@withLock Result.failure(Exception("User not authenticated"))
                    
                    // --- WIPE SAFETY CHECK ---
                    // Prevent "data resurrection": if a remote wipe is pending OR the agent doc was purged
                    // but the devices still has sync history, we block pushes to force a local wipe.
                    val userDocSnapshot = try { firestore.collection("users").document(uid).get().await() } catch(e: Exception) { null }
                    val agentDocExists = try { firestore.collection("agents").document(uid).get().await().exists() } catch(e: Exception) { true }
                    val hasSyncHistory = settingsManager.lastSyncTimestamp.first() > 0

                    if (targetUid == null && (userDocSnapshot?.getBoolean("requireDataReset") == true || (hasSyncHistory && !agentDocExists))) {
                        android.util.Log.w("SyncRepository", "Push blocked for $uid: Remote wipe pending or data purged.")
                        return@withLock Result.failure(Exception("Sincronização bloqueada: Uma limpeza de dados foi solicitada. Por favor, realize um 'Receber Dados' primeiro."))
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
            val unsyncedHouses = houseDao.getUnsyncedHouses(officialAgentName, uid)
            val unsyncedActivities = dayActivityDao.getUnsyncedActivities(officialAgentName, uid)
            val tombstones = tombstoneDao.getAllTombstones(officialAgentName, uid)

            // Optimistic return if nothing to do (and not a forced replacement)
                if (unsyncedHouses.isEmpty() && unsyncedActivities.isEmpty() && tombstones.isEmpty() && !shouldReplace) {
                    android.util.Log.i("SyncRepository", "Push: Nothing to sync (incremental).")
                    return@withLock Result.success(Unit)
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


            // Prepare Operations
            val pInfo = try { context.packageManager.getPackageInfo(context.packageName, 0) } catch (e: Exception) { null }
            val metadata = mutableMapOf<String, Any>(
                "lastSyncTime" to System.currentTimeMillis(),
                "lastUpdated" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                "appVersionCode" to (pInfo?.versionCode ?: 0),
                "appVersionName" to (pInfo?.versionName ?: "Unknown")
            )

            if (shouldReplace) {
                // SURGICAL WIPE: Only delete cloud data for the dates we are actually pushing.
                // IMPROVEMENT: Include dates from tombstones in the wipe to ensure moved/deleted 
                // production does not "ghost" back from the cloud during a Full Sync.
                val backupDates = (
                    housesToPush.map { it.data.replace("/", "-") } + 
                    activitiesToPush.map { it.date.replace("/", "-") } +
                    tombstones.filter { it.type == com.antigravity.healthagent.data.local.model.TombstoneType.ACTIVITY }.map { it.naturalKey.split("|")[0].replace("/", "-") } +
                    tombstones.filter { it.type == com.antigravity.healthagent.data.local.model.TombstoneType.HOUSE }.mapNotNull { tk -> 
                        // Robustly extract date (DD-MM-YYYY) from natural key
                        val dateRegex = Regex("\\d{2}-\\d{2}-\\d{4}")
                        dateRegex.find(tk.naturalKey.replace("/", "-"))?.value
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

            // --- ADMIN RESTORE TRIGGER ---
            // If an administrator is performing a "Replace All" (shouldReplace) for a target user,
            // we signal the target device to reset its local database on the next sync.
            if (shouldReplace && targetUid != null && targetUid != auth.currentUser?.uid) {
                metadata["requireDataReset"] = true
                android.util.Log.i("SyncRepository", "Admin Restore: Signalling target device ($uid) for local reset.")
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
                    runInTransactionWithRetry {
                        tombstoneDao.deleteTombstones(chunk.map { it.id })
                    }
                    cloudDeletedHouses.clear()
                    cloudDeletedActivities.clear()
                }
            }

            // 2. HOUSES
            if (housesToPush.isNotEmpty()) {
                // --- CLOUD DEDUPLICATION (KEY-SHIFT PROTECTION) ---
                // For incremental sync, we actively clean up shifted keys for the dates we are touching.
                // This prevents "ghost" duplicates in Firestore after visitSegment recalculations.
                if (!shouldReplace) {
                    val affectedDates = housesToPush.map { it.data.replace("/", "-") }.toSet()
                    for (date in affectedDates) {
                        try {
                            val cloudSnapshot = userDocRef.collection("houses")
                                .whereEqualTo("data", date)
                                .get().await()
                            
                            val localHousesForDate = houseDao.getHousesByDateAndAgent(date, officialAgentName, uid)
                            val localKeys = localHousesForDate.map { it.generateNaturalKey() }.toSet()
                            val localIdentities = localHousesForDate.map { it.generateIdentityKey() }.toSet()
                            
                            val toDelete = cloudSnapshot.documents.filter { doc ->
                                val cloudHouse = doc.toHouseSafe(uid, officialAgentName) ?: return@filter false
                                val cloudKey = doc.id
                                val cloudIdentity = cloudHouse.generateIdentityKey()
                                
                                // Delete if: 1. Key is stale locally AND 2. Identity still exists locally (Shifted)
                                cloudKey !in localKeys && cloudIdentity in localIdentities
                            }
                            
                            if (toDelete.isNotEmpty()) {
                                val purgedKeys = toDelete.joinToString { it.id }
                                android.util.Log.i("SyncRepository", "Cloud Deduplication: Purging ${toDelete.size} ghost records for $date. Keys: $purgedKeys")
                                toDelete.chunked(400).forEach { chunk ->
                                    val delBatch = firestore.batch()
                                    chunk.forEach { delBatch.delete(it.reference) }
                                    delBatch.commit().await()
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("SyncRepository", "Cloud Deduplication context skip for $date: ${e.message}")
                        }
                    }
                }

                housesToPush.chunked(400).forEach { chunk ->
                    val batch = firestore.batch()
                    val pushedKeys = mutableListOf<String>()
                    
                    chunk.forEach { house ->
                        val isAdminPush = shouldReplace && targetUid != null && targetUid != auth.currentUser?.uid
                        val officialHouse = house.copy(
                            agentName = officialAgentName ?: house.agentName,
                            editedByAdmin = isAdminPush || house.editedByAdmin
                        )
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
                    runInTransactionWithRetry {
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
                        val isAdminPush = shouldReplace && targetUid != null && targetUid != auth.currentUser?.uid
                        val officialActivity = activity.copy(
                            agentName = officialAgentName, 
                            agentUid = uid,
                            editedByAdmin = isAdminPush || activity.editedByAdmin
                        )
                        val activityData = officialActivity.toFirestoreMap()
                        val dateKey = activity.date.replace("/", "-")
                        batch.set(userDocRef.collection("day_activities").document(dateKey), activityData, com.google.firebase.firestore.SetOptions.merge())
                        pushedDates.add(dateKey)
                    }
                    
                    if (!shouldReplace) {
                        batch.update(userDocRef, "deleted_activity_dates", com.google.firebase.firestore.FieldValue.arrayRemove(*pushedDates.toTypedArray()))
                    }
                    
                    batch.commit().await()
                    
                    // Local confirmation
                    runInTransactionWithRetry {
                        chunk.forEach { activity ->
                            dayActivityDao.markAsSyncedWithTimestamp(activity.date, officialAgentName, uid, activity.lastUpdated)
                        }
                    }
                }
            }

            // 4. SUMMARY AGGREGATION (Recalculate from local ground truth for accuracy)
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
                
                val todayStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
                val todayInt = todayStr.toInt()
                
                val housesInMonthRaw = if (isProxyPush && shouldReplace) {
                    housesToPush.filter { it.data.replace("/", "-").endsWith(monthYear) }
                } else {
                    houseDao.getHousesByMonth(officialAgentName ?: "", uid, monthYear)
                }
                
                val housesInMonth = housesInMonthRaw.filter { house ->
                    try {
                        val parts = house.data.replace("/", "-").split("-")
                        if (parts.size == 3) {
                            String.format("%04d%02d%02d", parts[2].toInt(), parts[1].toInt(), parts[0].toInt()).toInt() <= todayInt
                        } else true
                    } catch(e: Exception) { true }
                }
                
                val activitiesInMonthRaw = if (isProxyPush && shouldReplace) {
                    activitiesToPush.filter { it.date.replace("/", "-").endsWith(monthYear) }
                } else {
                    dayActivityDao.getDayActivitiesByMonth(officialAgentName ?: "", uid, monthYear)
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
        } catch (e: Exception) {
            android.util.Log.e("SyncRepository", "Push failed: ${e.message}", e)
            Result.failure(e)
        }
    } } ?: Result.failure(Exception("Sincronização atingiu o tempo limite. Verifique sua conexão ou tente novamente se o backup for muito grande."))
    return result
}


    override suspend fun pullCloudDataToLocal(targetUid: String?, force: Boolean): Result<Unit> {
        val result = withTimeoutOrNull(180000L) {
            syncMutex.withLock {
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
            
            // --- VERSION SAFETY CHECK ---
            val sysSettings = fetchSystemSettings().getOrDefault(emptyMap())
            val minVersion = (sysSettings["minAppVersion"] as? Number)?.toInt() ?: AppConstants.MIN_VERSION_CODE
            val pInfo = try { context.packageManager.getPackageInfo(context.packageName, 0) } catch (e: Exception) { null }
            val currentVersion = pInfo?.versionCode ?: 0
            
            if (currentVersion < minVersion) {
                android.util.Log.e("SyncRepository", "Version Enforcement: App version ($currentVersion) is below minimum required ($minVersion)")
                return@withContext Result.failure(Exception("Versão do aplicativo desatualizada. Por favor, atualize o 'Eu ACE' na Play Store para continuar sincronizando seus dados."))
            }
            
            // --- REMOTE WIPE CHECK (Multi-device Safety) ---
            val requireResetFromFlag = userDoc.getBoolean("requireDataReset") ?: false
            
            // If the primary agent document in cloud is missing but we have local sync history,
            // it means an Admin likely performed a 'Remote Wipe'. We must reset local state.
            val hasSyncHistory = settingsManager.lastSyncTimestamp.first() > 0
            val agentDocExists = try { firestore.collection("agents").document(uid).get().await().exists() } catch(e: Exception) { true }
            
            val requireReset = requireResetFromFlag || (hasSyncHistory && !agentDocExists)

            if (requireReset) {
                android.util.Log.w("SyncRepository", "Remote Wipe Triggered for UID: $uid")
                // CRITICAL: Call internal version to avoid re-acquiring syncMutex (Deadlock)
                val wipeResult = clearLocalDataInternal() 
                if (wipeResult.isSuccess) {
                    // Reset flag in Firestore ONLY if wipe succeeded
                    firestore.collection("users").document(uid).update("requireDataReset", false)
                } else {
                    android.util.Log.e("SyncRepository", "Remote Wipe Failed: ${wipeResult.exceptionOrNull()?.message}")
                    // We return early to prevent pushing/pulling on top of dirty state that should have been wiped
                    return@withContext Result.failure(wipeResult.exceptionOrNull() ?: Exception("Falha ao realizar wipe local"))
                }
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

            // Create a set of Identity Keys from cloudDeletedHouses to catch shifted "ghost" keys
            val cloudDeletedIdentities = cloudDeletedHouses.mapNotNull { deletedKey ->
                val parts = deletedKey.split("|")
                if (parts.size >= 10) {
                    // Identity Key format: DATE|BAIRRO|BLOCK|BSEQ|STREET|NUM|SEQ|COMP
                    "${parts[0]}|${parts[8]}|${parts[2]}|${parts[3]}|${parts[4]}|${parts[5]}|${parts[6]}|${parts[7]}"
                } else null
            }.toSet()

            val housesToDelete = allLocalHouses.filter { house ->
                val key = house.generateNaturalKey()
                val identityKey = house.generateIdentityKey()
                
                // BUG FIX: Only delete local houses if they are already marked as synced. 
                // EXCEPTION 1: If the house is in cloudDeletedHouses and the local lastUpdated is significantly
                // older than the current sync (more than 1 hour), we assume it's a "Ghost" and delete it.
                // EXCEPTION 2 (ADMIN AUTHORITY): If the household matches a tombstone and the local record 
                // was last updated more than 15 minutes ago, we assume the Admin has priority and delete it even if unsynced.
                val dateKey = "${house.data.replace("/", "-")}|${house.agentName.uppercase()}"
                
                if (key in cloudDeletedHouses || identityKey in cloudDeletedIdentities || dateKey in cloudDeletedActivities) {
                    val timeSinceLastUpdate = System.currentTimeMillis() - house.lastUpdated
                    if (house.isSynced) {
                        true
                    } else if (timeSinceLastUpdate > 900000L) { // 15 mins (Admin Authority / Ghost Cleanup)
                        android.util.Log.i("SyncRepository", "Admin Authority / Ghost Cleanup: Deleting unsynced house ${house.id} due to cloud deletion.")
                        true
                    } else {
                        // AGENT PRIORITY: Protect actively typed unsynced work.
                        android.util.Log.i("SyncRepository", "Agent Priority: Preserving actively typed house ${house.id} despite cloud deletion.")
                        false
                    }
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
                    val timeSinceLastUpdate = System.currentTimeMillis() - activity.lastUpdated
                    if (activity.isSynced) {
                        true
                    } else if (timeSinceLastUpdate > 900000L) { // 15 mins
                        android.util.Log.i("SyncRepository", "Admin Authority / Ghost Cleanup: Deleting unsynced activity ${activity.date} due to cloud deletion.")
                        true
                    } else {
                        android.util.Log.i("SyncRepository", "Agent Priority: Preserving actively typed activity ${activity.date} despite cloud deletion.")
                        false
                    }
                } else false
            }

            // 4. Reconciliation
            val localHouses = houseDao.getAllHousesSnapshot().groupBy { it.generateNaturalKey() }
            val housesDelta = cloudHouses.filter { it.generateNaturalKey() !in cloudDeletedHouses }
            
            val localActivities = dayActivityDao.getAllDayActivities(finalAgentName, uid).groupBy { "${it.date.replace("/", "-")}|${it.agentName}" }
            val activitiesDelta = cloudDayActivities

            runInTransactionWithRetry {
                // Delete Houses
                if (housesToDelete.isNotEmpty()) {
                    val tombstonesToInsert = mutableListOf<com.antigravity.healthagent.data.local.model.Tombstone>()
                    for (house in housesToDelete) {
                        houseDao.deleteHouse(house)
                        tombstonesToInsert.add(
                            com.antigravity.healthagent.data.local.model.Tombstone(
                                type = com.antigravity.healthagent.data.local.model.TombstoneType.HOUSE,
                                naturalKey = house.generateNaturalKey(),
                                agentName = house.agentName,
                                agentUid = house.agentUid,
                                dataDate = house.data
                            )
                        )
                    }
                    if (tombstonesToInsert.isNotEmpty()) {
                        tombstoneDao.insertTombstones(tombstonesToInsert)
                    }
                }

                // Delete Activities
                if (activitiesToDelete.isNotEmpty()) {
                    val tombstonesToInsert = mutableListOf<com.antigravity.healthagent.data.local.model.Tombstone>()
                    for (activity in activitiesToDelete) {
                        dayActivityDao.deleteDayActivity(activity.date, activity.agentName, activity.agentUid)
                        tombstonesToInsert.add(
                            com.antigravity.healthagent.data.local.model.Tombstone(
                                type = com.antigravity.healthagent.data.local.model.TombstoneType.ACTIVITY,
                                naturalKey = "${activity.date.replace("/", "-")}|${activity.agentName.uppercase()}",
                                agentName = activity.agentName,
                                agentUid = activity.agentUid,
                                dataDate = activity.date
                            )
                        )
                    }
                    if (tombstonesToInsert.isNotEmpty()) {
                        tombstoneDao.insertTombstones(tombstonesToInsert)
                    }
                }

                // Upsert Houses
                // OPTIMIZATION: Index all local houses by their Logical Identity Key
                // for O(1) identity healing lookup instead of O(N) searching.
                val localIdentityMap = allLocalHouses.groupBy { it.generateIdentityKey() }

                val housesToUpsert = housesDelta.mapNotNull { cloudHouse ->
                    val key = cloudHouse.generateNaturalKey()
                    var existing = localHouses[key]?.firstOrNull()
                    
                    // --- IDENTITY HEALING (KEY-SHIFT PROTECTION) ---
                    // If no direct natural key match, try an identity match to detect corrections (street rename, segment shift, etc.)
                    if (existing == null) {
                        val identityKey = cloudHouse.generateIdentityKey()
                        // IDENTITY HEALING: Protect against Key-Shifts (e.g. street renames).
                        // If no direct natural key match, we correlate via logical identity.
                        existing = localIdentityMap[identityKey]?.find { it.agentUid == cloudHouse.agentUid }
                        
                        if (existing != null) {
                            android.util.Log.i("SyncRepository", "Identity Healing: Stabilized key shift for house ${existing.id} ($key)")
                        } else {
                            // DAY-LOCK ENFORCEMENT
                            // If it's a completely new house (no natural key, no identity key match),
                            // we must check if the day is locked locally. If it is, we block the pull
                            // to prevent "zombie" or duplicate ghost houses from reappearing on finished days,
                            // UNLESS the pull is triggered by an Admin (shouldReplace = true).
                            val normalizedDate = cloudHouse.data.replace("/", "-")
                            val dateKey = "$normalizedDate|$finalAgentName"
                            val dayActivity = localActivities[dateKey]?.firstOrNull()
                            val cloudActivity = activitiesDelta.find { it.date.replace("/", "-") == normalizedDate }
                            
                            val isCloudUnlocked = cloudActivity?.isManualUnlock == true
                            val isLocallyClosed = dayActivity?.isClosed == true && dayActivity.isManualUnlock != true
                            
                            // Multi-device safety: If the day is locked locally but was UNLOCKED in the cloud (e.g. from another device), allow the pull.
                            if (isLocallyClosed && !isCloudUnlocked && !cloudHouse.editedByAdmin && !isTargetDifferentUser) {
                                android.util.Log.w("SyncRepository", "Blocked Pull: Prevented creation of new house ($key) because day $normalizedDate is LOCKED locally and not unlocked in cloud.")
                                return@mapNotNull null
                            }
                        }
                    }

                    // CONFLICT RESOLUTION: Last Side to Change Wins (LWW)
                    if (existing != null && !existing.isSynced) {
                        // Priority 1: Admin Authority with Typing Protection. 
                        // If cloud record was edited by admin, it OVERWRITES local change 
                        // UNLESS the agent typed within the last 2 minutes.
                        val isAdminOverride = cloudHouse.editedByAdmin && !existing.editedByAdmin && 
                            (System.currentTimeMillis() - existing.lastUpdated > 120000L) // 2 mins typing protection

                        if (isAdminOverride) {
                            android.util.Log.i("SyncRepository", "Admin Authority: Priority overwrite for house ${existing.id} ($key)")
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
                         val isAdminOverride = activity.editedByAdmin && !existing.editedByAdmin && 
                            (System.currentTimeMillis() - existing.lastUpdated > 120000L) // 2 mins

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
                // BUG FIX: Use 10-minute safety buffer (instead of 1min) to account for significant clock skew.
                // A larger buffer prevents permanent record skipping if the device clock is ahead of Firestore.
                val maxObservedTime = (cloudHouses.map { it.lastUpdated } + cloudDayActivities.map { it.lastUpdated }).maxOrNull() ?: 0L
                val safetyAnchor = serverTime - 600000L // 10 mins buffer
                
                settingsManager.setLastSyncTimestamp(maxOf(maxObservedTime, safetyAnchor))
            }

                Result.success(Unit)
            } catch (e: Exception) {
                android.util.Log.e("SyncRepository", "Pull failed", e)
                Result.failure(e)
            }
        }
    } } ?: Result.failure(Exception("O download de dados atingiu o tempo limite. Verifique sua conexão ou tente novamente."))
    return result
}


    private suspend fun clearLocalDataInternal(): Result<Unit> {
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

    override suspend fun clearLocalData(): Result<Unit> {
        return withTimeoutOrNull(15000L) {
            syncMutex.withLock {
                clearLocalDataInternal()
            }
        } ?: Result.failure(Exception("Tempo esgotado ao tentar limpar dados locais (Database busy)"))
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
            runInTransactionWithRetry {
                // 1. Identify all dates being restored (normalized)
                val restoredDatesList = (houses.map { it.data.replace("/", "-") } + activities.map { it.date.replace("/", "-") }).distinct()
                
                // 2. Perform Atomic CLEANUP of local data for these dates FIRST
                // This ensures a true "Full Replace" and prevents casing-based duplicates ("John Doe" vs "JOHN DOE")
                // while avoiding self-deletion bugs.
                if (restoredDatesList.isNotEmpty()) {
                    android.util.Log.i("SyncRepository", "Restoration: Atomic purge of ${restoredDatesList.size} dates for $agentName")
                    houseDao.deleteByAgentAndDates(agentName, finalUid, restoredDatesList)
                    dayActivityDao.deleteByAgentAndDates(agentName, finalUid, restoredDatesList)
                }

                // 3. Normalize and Prepare Data
                val normalizedActivities = activities.map { 
                    val finalAgentName = if (it.agentName.isNotBlank()) it.agentName else agentName
                    val normalized = it.copy(
                        agentName = finalAgentName.uppercase(),
                        agentUid = finalUid,
                        date = it.date.replace("/", "-"),
                        isSynced = false,
                        lastUpdated = System.currentTimeMillis()
                    ) 
                    // Clear local tombstone for restored activity
                    tombstoneDao.deleteByNaturalKey("${normalized.date}|${normalized.agentName}", normalized.agentName, normalized.agentUid)
                    normalized
                }

                val housesToUpsert = houses.map { restoredHouse ->
                    val finalAgentName = if (restoredHouse.agentName.isNotBlank()) restoredHouse.agentName else agentName
                    
                    // Healing logic: Default EMPTY to NONE (Aberto) for restored data
                    val finalSituation = if (restoredHouse.situation == com.antigravity.healthagent.data.local.model.Situation.EMPTY) {
                        com.antigravity.healthagent.data.local.model.Situation.NONE
                    } else restoredHouse.situation

                    val finalHouse = restoredHouse.copy(
                        id = 0, // Reset ID to allow auto-generation and prevent collision with unrelated local records
                        agentName = finalAgentName.uppercase(),
                        agentUid = finalUid,
                        data = restoredHouse.data.replace("/", "-"),
                        situation = finalSituation,
                        isSynced = false,
                        lastUpdated = System.currentTimeMillis()
                    )
                    
                    // Clear local tombstone for restored house
                    tombstoneDao.deleteByNaturalKey(finalHouse.generateNaturalKey(), finalHouse.agentName, finalHouse.agentUid)
                    
                    finalHouse
                }

                // 4. Upsert Data
                houseDao.upsertHouses(housesToUpsert)
                dayActivityDao.upsertDayActivities(normalizedActivities)
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }


    override suspend fun fetchSystemSettings(): Result<Map<String, Any>> {
        return try {
            val snapshot = withTimeoutOrNull(5000) {
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
            syncSchedulerProvider.get().scheduleSync()
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
            syncSchedulerProvider.get().scheduleSync()
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
                
                activityDates.chunked(100).forEach { chunk ->
                    val batch = firestore.batch()
                    chunk.forEach { tombstone ->
                        val dateKey = tombstone.split("|")[0]
                        batch.delete(docRef.collection("day_activities").document(dateKey))
                        
                        // CASCADING CLOUD CLEANUP: Also find and delete houses for this date in cloud
                        try {
                            // Firestore data is stored with dashes DD-MM-YYYY
                            val activityDateDashed = dateKey.replace("/", "-")
                            val activityHouses = docRef.collection("houses")
                                .whereEqualTo("data", activityDateDashed)
                                .get().await()
                            activityHouses.documents.forEach { batch.delete(it.reference) }
                        } catch (e: Exception) { /* Ignore query errors */ }
                    }
                    // Use standardized full keys for tombstones
                    val chunkKeys = chunk.map { 
                        if (it.contains("|")) it else if (agentName.isNotBlank()) "$it|${agentName.uppercase()}" else it
                    }
                    batch.update(docRef, "deleted_activity_dates", com.google.firebase.firestore.FieldValue.arrayUnion(*chunkKeys.toTypedArray()))
                    // Trigger cache invalidation for supervisors
                    batch.update(docRef, "lastSyncTime", System.currentTimeMillis())
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
                        batch.update(legacyDoc.reference, "lastSyncTime", System.currentTimeMillis())
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

private fun DocumentSnapshot.toHouseSafe(agentUid: String, agentName: String): House? {
    return try {
        val house = this.toObject(House::class.java) ?: return null
        // CRITICAL: Manually extract lastUpdated because it is @Excluded in the data model
        // to prevent local timestamps from being pushed to Firestore.
        val firestoreLastUpdated = this.getTimestamp("lastUpdated")?.toDate()?.time ?: house.lastUpdated
        
        house.copy(
            agentUid = agentUid,
            agentName = agentName.uppercase(),
            data = (this.getString("data") ?: house.data).replace("/", "-"),
            lastUpdated = firestoreLastUpdated
        )
    } catch (e: Exception) {
        android.util.Log.e("SyncRepository", "toHouseSafe error for doc ${this.id}: ${e.message}")
        null
    }
}

private fun DocumentSnapshot.toDayActivitySafe(agentUid: String, agentName: String): DayActivity? {
    return try {
        val activity = this.toObject(DayActivity::class.java) ?: return null
        // CRITICAL: Manually extract lastUpdated to bypass @Exclude
        val firestoreLastUpdated = this.getTimestamp("lastUpdated")?.toDate()?.time ?: activity.lastUpdated
        
        activity.copy(
            agentUid = agentUid,
            agentName = agentName.uppercase(),
            date = (this.getString("date") ?: activity.date).replace("/", "-"),
            lastUpdated = firestoreLastUpdated
        )
    } catch (e: Exception) {
        android.util.Log.e("SyncRepository", "toDayActivitySafe error for doc ${this.id}: ${e.message}")
        null
    }
}
