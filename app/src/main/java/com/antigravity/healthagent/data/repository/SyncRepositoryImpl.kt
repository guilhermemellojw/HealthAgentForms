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
    private val backupRepository: com.antigravity.healthagent.domain.repository.BackupRepository,
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
        val result = withTimeoutOrNull(600000L) {
            syncMutex.withLock {
                withContext(Dispatchers.IO) {
                    try {
                        val uid = targetUid ?: auth.currentUser?.uid ?: return@withContext Result.failure(Exception("User not authenticated"))
                        
                        // --- WIPE SAFETY CHECK ---
                        // BUG FIX: requireDataReset is written to 'agents' collection (line 198), so we MUST read it from 'agents' too.
                        val agentDocSnapshot = try { firestore.collection("agents").document(uid).get().await() } catch(e: Exception) { null }
                        val agentDocExists = agentDocSnapshot?.exists() ?: true
                        val hasSyncHistory = settingsManager.lastSyncTimestamp.first() > 0

                        if (targetUid == null && (agentDocSnapshot?.getBoolean("requireDataReset") == true || (hasSyncHistory && !agentDocExists))) {
                            android.util.Log.w("SyncRepository", "Push blocked for $uid: Remote wipe pending or data purged.")
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
                            android.util.Log.i("SyncRepository", "Push: Nothing to sync (incremental).")
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
                                android.util.Log.e("SyncRepository", "IDENTITY LEAK PREVENTED: Filtered out ${crossUidHouses.size} houses with mismatching UIDs.")
                            }
                            
                            // BUG FIX: During proxy sessions, NEVER allow blank UIDs to leak into the agent's account.
                            // Orphan data (blank UID) should only be allowed if we are pushing the Admin's own local production.
                            housesToPush = if (isProxyPush) {
                                housesToPush.filter { it.agentUid == uid }
                            } else {
                                housesToPush.filter { it.agentUid.isBlank() || it.agentUid == uid }
                            }
                            
                            // DISCARD BROKEN HOUSES: Never push records missing core address fields
                            // RELAXED: Only discard if it's truly empty (missing Bairro, Street, or BOTH Number and Sequence)
                            housesToPush = housesToPush.filter { h ->
                                h.address.bairro.isNotBlank() && 
                                h.address.streetName.isNotBlank() && 
                                h.address.blockNumber.isNotBlank() &&
                                (h.address.number.isNotBlank() || h.address.sequence > 0)
                            }

                            val crossUidActivities = activitiesToPush.filter { it.agentUid.isNotBlank() && it.agentUid != uid }
                            if (crossUidActivities.isNotEmpty()) {
                                android.util.Log.e("SyncRepository", "IDENTITY LEAK PREVENTED: Filtered out ${crossUidActivities.size} activities with mismatching UIDs.")
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
                                userDocRef.set(mapOf("agentName" to officialAgentName), com.google.firebase.firestore.SetOptions.merge()).await()
                            }
                        }

                        // Prepare Operations
                        val pInfo = try { context.packageManager.getPackageInfo(context.packageName, 0) } catch (e: Exception) { null }
                        val metadata = mutableMapOf<String, Any>(
                            "lastSyncTime" to com.antigravity.healthagent.utils.TimeManager.currentTimeMillis(),
                            "lastUpdated" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                            "appVersionCode" to (pInfo?.versionCode ?: 0),
                            "appVersionName" to (pInfo?.versionName ?: "Unknown")
                        )

                        if (shouldReplace) {
                            val backupDates = (
                                housesToPush.map { it.data.replace("/", "-") } + 
                                activitiesToPush.map { it.date.replace("/", "-") } +
                                tombstones.filter { it.type == com.antigravity.healthagent.data.local.model.TombstoneType.ACTIVITY }.map { it.naturalKey.split("|")[0].replace("/", "-") } +
                                tombstones.filter { it.type == com.antigravity.healthagent.data.local.model.TombstoneType.HOUSE }.mapNotNull { tk -> 
                                    // BUG FIX: Robust extraction using delimiter split instead of fragile regex
                                    val parts = tk.naturalKey.split("_")
                                    if (parts.size >= 3) parts[2].replace("/", "-") else null
                                }
                            ).toSet() 

                            // BUG FIX: Legacy support for both formats (/ and -) during cloud cleanup
                            val backupDatesWithAlternates = backupDates.flatMap { listOf(it, it.replace("-", "/")) }.toSet()
                            
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

                            metadata["deleted_house_ids"] = com.google.firebase.firestore.FieldValue.delete()
                            metadata["deleted_activity_dates"] = com.google.firebase.firestore.FieldValue.delete()
                        }

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

                        // 1. TOMBSTONES
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
                                
                                if (cloudDeletedHouses.isNotEmpty()) {
                                    batch.update(userDocRef, "deleted_house_ids", com.google.firebase.firestore.FieldValue.arrayUnion(*cloudDeletedHouses.toTypedArray()))
                                }
                                if (cloudDeletedActivities.isNotEmpty()) {
                                    batch.update(userDocRef, "deleted_activity_dates", com.google.firebase.firestore.FieldValue.arrayUnion(*cloudDeletedActivities.toTypedArray()))
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
                                val affectedDates = housesToPush.map { it.data.replace("/", "-") }.toSet()
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
                                        agentUid = uid,
                                        editedByAdmin = isAdminPush || house.editedByAdmin
                                    )
                                    val houseData = officialHouse.toFirestoreMap()
                                    val key = officialHouse.generateNaturalKey()
                                    batch.set(userDocRef.collection("houses").document(key), houseData, com.google.firebase.firestore.SetOptions.merge())
                                    pushedKeys.add(key)
                                }
                                
                                batch.update(userDocRef, "deleted_house_ids", com.google.firebase.firestore.FieldValue.arrayRemove(*pushedKeys.toTypedArray()))
                                
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
                                    batch.set(userDocRef.collection("day_activities").document(dateKey), activityData, com.google.firebase.firestore.SetOptions.merge())
                                    pushedDates.add(dateKey)
                                }
                                
                                val datesToRemove = pushedDates.map { "$it|${officialAgentName.uppercase()}" } + pushedDates
                                batch.update(userDocRef, "deleted_activity_dates", com.google.firebase.firestore.FieldValue.arrayRemove(*datesToRemove.toTypedArray()))
                                
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
                                // Proxy Pushes (Admin edits) lack the full month of data locally.
                                // We must fetch the full month from the cloud to prevent zeroing out the summary.
                                // IMPROVEMENT: Generate correct number of days for the specific month.
                                val monthParts = monthYear.split("-")
                                val month = monthParts[0].toIntOrNull() ?: 1
                                val year = monthParts[1].toIntOrNull() ?: 2024
                                
                                val calendar = java.util.Calendar.getInstance()
                                calendar.set(year, month - 1, 1)
                                val daysInMonth = calendar.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
                                
                                val allDays = (1..daysInMonth).map { day -> String.format("%02d-%s", day, monthYear) }
                                
                                // BUG FIX: Firestore 'whereIn' fails if the list is empty.
                                // We use chunked(30) and flatMap to safely handle any number of days.
                                val allDocs = allDays.chunked(30).flatMap { chunk ->
                                    if (chunk.isNotEmpty()) {
                                        userDocRef.collection("houses")
                                            .whereIn("data", chunk)
                                            .get().await().documents
                                    } else emptyList()
                                }
                                
                                allDocs.mapNotNull { it.toHouseSafe(uid, officialAgentName ?: "") }
                            } else {
                                // For normal syncs OR Full Replacements (Restoration), use the provided data.
                                // If shouldReplace is true, housesToPush contains the FULL dataset for the restoration.
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
                                }.mapNotNull { it.toDayActivitySafe(uid, officialAgentName ?: "") }
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
                            "lastSyncError" to com.google.firebase.firestore.FieldValue.delete()
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
                                android.util.Log.i("SyncRepository", "Timeline Backup worker enqueued for $uid")
                            } catch (e: Exception) {
                                android.util.Log.w("SyncRepository", "Timeline Backup enqueue failed (non-critical): ${e.message}")
                            }
                        }

                        Result.success(Unit)
                    } catch (e: Exception) {
                        android.util.Log.e("SyncRepository", "Push failed: ${e.message}", e)
                        Result.failure(e)
                    }
                }
            }
        } ?: Result.failure(Exception("Sincronização atingiu o tempo limite. Verifique sua conexão ou tente novamente se o backup for muito grande."))
        return result
    }
    override suspend fun pullCloudDataToLocal(targetUid: String?, force: Boolean): Result<Unit> {
        val result = withTimeoutOrNull(600000L) {
            syncMutex.withLock {
                withContext(Dispatchers.IO) {
                    try {
                        val uid = targetUid ?: auth.currentUser?.uid ?: return@withContext Result.failure(Exception("Not logged in"))
                        val isTargetDifferentUser = targetUid != null && targetUid != auth.currentUser?.uid
                    
                        // 1. Basic Setup
                        val userDoc = firestore.collection("users").document(uid).get().await()
                        val agentDocSnapshot = firestore.collection("agents").document(uid).get().await()
                        
                        val email = userDoc.getString("email") 
                            ?: agentDocSnapshot.getString("email") 
                            ?: auth.currentUser?.email 
                            ?: return@withContext Result.failure(Exception("User email not found"))
                        
                        val profileAgentName = agentDocSnapshot.getString("agentName")?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
                            ?: userDoc.getString("agentName")?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
                        val profileDisplayName = userDoc.getString("displayName")?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
                        
                        // BUG FIX: If we have multiple possible emails (raw vs lowercase), use them all in discovery
                        // IMPORTANT: If inspecting a different user, do NOT use the Admin's email in discovery
                        // to prevent pulling the Admin's production into the agent's view.
                        val authEmail = if (!isTargetDifferentUser) auth.currentUser?.email?.trim() ?: "" else ""
                        val discoveryEmails = setOf(email, authEmail, email.lowercase(), authEmail.lowercase()).filter { it.isNotBlank() }

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
                        val requireResetFromUser = userDoc.getBoolean("requireDataReset") ?: false
                        val requireResetFromAgent = agentDocSnapshot.getBoolean("requireDataReset") ?: false
                        
                        // If the primary agent document in cloud is missing but we have local sync history,
                        // it means an Admin likely performed a 'Remote Wipe'. We must reset local state.
                        // IMPORTANT: Only trigger this if we are NOT inspecting another user, to prevent
                        // accidental wipe of an agent's local data during administrative sessions.
                        val hasSyncHistory = settingsManager.lastSyncTimestamp.first() > 0
                        val agentDocExists = agentDocSnapshot.exists()
                        
                        val requireReset = requireResetFromUser || requireResetFromAgent || (!isTargetDifferentUser && hasSyncHistory && !agentDocExists)

                        // --- OPTIMIZATION: Incremental vs Full Sync logic ---
                        val localCount = houseDao.count()
                        val isIncremental = !force && !isTargetDifferentUser && !requireReset && settingsManager.lastSyncTimestamp.first() > 0 && localCount > 0

                        if (requireReset) {
                            android.util.Log.w("SyncRepository", "Remote Wipe Triggered for UID: $uid")
                            // SURGICAL WIPE: Only clear data for the target agent to prevent Admin data loss
                            val wipeResult = runInTransactionWithRetry {
                                houseDao.deleteByAgent(uid)
                                dayActivityDao.deleteByAgent(uid)
                                tombstoneDao.deleteByAgent(uid)
                                
                                // Reset sync state ONLY if this is the current authenticated user
                                if (!isTargetDifferentUser) {
                                    settingsManager.setLastSyncTimestamp(0L)
                                }
                                Result.success(Unit)
                            }
                            if (wipeResult.isSuccess) {
                                if (requireResetFromUser) firestore.collection("users").document(uid).update("requireDataReset", false)
                                if (requireResetFromAgent) firestore.collection("agents").document(uid).update("requireDataReset", false)
                            } else {
                                android.util.Log.e("SyncRepository", "Remote Wipe Failed: ${wipeResult.exceptionOrNull()?.message}")
                                return@withContext Result.failure(wipeResult.exceptionOrNull() ?: Exception("Falha ao realizar wipe local"))
                            }
                        }

                        val cachedLastSync = if (isTargetDifferentUser || force || requireReset) 0L else settingsManager.lastSyncTimestamp.first()
                        val now = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis()
                        val lastSync = if (cachedLastSync > now + 3600000L) 0L else cachedLastSync
                        val serverTime = now

                        // 2. Fetchers (EXHAUSTIVE DISCOVERY)
                        // RISK MITIGATION: Skip email-based discovery for Admins/Supervisors to prevent
                        // accidental data merging from different accounts sharing the same administrative email.
                        val isHighPrivilegeUser = auth.currentUser?.uid?.let { 
                            try { firestore.collection("users").document(it).get().await().getString("role")?.let { r -> r == "ADMIN" || r == "SUPERVISOR" } } catch(e: Exception) { false }
                        } ?: false

                        val possibleAgentDocs = mutableListOf(firestore.collection("agents").document(uid))
                        
                        if (!isIncremental && !isHighPrivilegeUser) {
                            discoveryEmails.forEach { dEmail ->
                                try {
                                    val matchingEmailDocs = firestore.collection("agents")
                                        .whereEqualTo("email", dEmail)
                                        .get().await()
                                    
                                    matchingEmailDocs.documents.forEach { doc ->
                                        if (doc.id != uid) {
                                            possibleAgentDocs.add(doc.reference)
                                            
                                            val legacyName = doc.getString("agentName")
                                            if (profileAgentName == null && !legacyName.isNullOrBlank()) {
                                                android.util.Log.i("SyncRepository", "Identity Healing: Adopting legacy agentName '$legacyName' from ${doc.id} for $uid")
                                                try {
                                                    firestore.collection("agents").document(uid).update("agentName", legacyName)
                                                    firestore.collection("users").document(uid).update("agentName", legacyName)
                                                } catch (e: Exception) {
                                                    android.util.Log.w("SyncRepository", "Identity Healing failed to update cloud profile: ${e.message}")
                                                }
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.w("SyncRepository", "Failed to search legacy agent docs for $dEmail", e)
                                }
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
                                        val snapshots = if (lastSync > 0) {
                                            val safetyLastSync = maxOf(0L, lastSync - 300000L)
                                            housesCollection.whereGreaterThan("lastUpdated", com.google.firebase.Timestamp(safetyLastSync / 1000, ((safetyLastSync % 1000) * 1000000).toInt()))
                                                .get().await()
                                                .documents
                                        } else {
                                            housesCollection.get().await()
                                                .documents
                                        }
                                        
                                        if (snapshots.size > 200) {
                                            snapshots.chunked(100).map { chunk ->
                                                async { chunk.mapNotNull { it.toHouseSafe(uid, finalAgentName) } }
                                            }.awaitAll().flatten()
                                        } else {
                                            snapshots.mapNotNull { it.toHouseSafe(uid, finalAgentName) }
                                        }
                                    }

                                    val activitiesJob = async {
                                        val snapshots = if (lastSync > 0) {
                                            val safetyLastSync = maxOf(0L, lastSync - 300000L)
                                            activitiesCollection.whereGreaterThan("lastUpdated", com.google.firebase.Timestamp(safetyLastSync / 1000, ((safetyLastSync % 1000) * 1000000).toInt()))
                                                .get().await()
                                                .documents
                                        } else {
                                            activitiesCollection.get().await()
                                                .documents
                                        }
                                        
                                        if (snapshots.size > 200) {
                                            snapshots.chunked(100).map { chunk ->
                                                async { chunk.mapNotNull { it.toDayActivitySafe(uid, finalAgentName) } }
                                            }.awaitAll().flatten()
                                        } else {
                                            snapshots.mapNotNull { it.toDayActivitySafe(uid, finalAgentName) }
                                        }
                                    }

                                    val houses = housesJob.await()
                                    val activities = activitiesJob.await()

                                    // CLOCK SKEW DETECTION:
                                    // If we received records with a 'lastUpdated' significantly in the future,
                                    // the device clock is likely behind.
                                    val currentDeviceTime = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis()
                                    var maxCloudTime = 0L
                                    houses.forEach { if (it.lastUpdated > maxCloudTime) maxCloudTime = it.lastUpdated }
                                    activities.forEach { if (it.lastUpdated > maxCloudTime) maxCloudTime = it.lastUpdated }
                                    
                                    val skew = maxCloudTime - currentDeviceTime
                                    if (kotlin.math.abs(skew) > 120000) { // 2 minute threshold
                                        settingsManager.setClockSkewMs(skew)
                                        android.util.Log.w("SyncRepository", "Clock Skew: Device is ${if(skew < 0) "AHEAD" else "BEHIND"} by ${kotlin.math.abs(skew)} ms.")
                                    } else {
                                        settingsManager.setClockSkewMs(0L)
                                    }

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

                        for (discoveryResult in discoveryResults) {
                            cloudHouses.addAll(discoveryResult.first)
                            cloudDayActivities.addAll(discoveryResult.second)
                            cloudDeletedHouses.addAll(discoveryResult.third.first)
                            // Populate with Identity-Scoped keys (date|NAME) for accurate local cleanup
                            discoveryResult.third.second.forEach { entry ->
                                val key = if (entry.contains("|")) entry.replace("/", "-") 
                                          else "${entry.replace("/", "-")}|${finalAgentName.uppercase()}"
                                cloudDeletedActivities.add(key)
                            }
                        }

                        // --- PHASE 2: TEAMWORK SYNC (AUTOMATIC) ---
                        // We pull production from other agents for the blocks currently active on this device.
                        // This ensures the RG is complete even when fieldwork is distributed among a team.
                        if (!isTargetDifferentUser) {
                            try {
                                val activeBlocks = houseDao.getActiveBlockNumbers()
                                if (activeBlocks.isNotEmpty()) {
                                    val currentCiclo = cloudHouses.firstOrNull()?.context?.ciclo ?: run {
                                        val cal = java.util.Calendar.getInstance()
                                        val month = cal.get(java.util.Calendar.MONTH)
                                        when (month) {
                                            java.util.Calendar.JANUARY, java.util.Calendar.FEBRUARY -> "1º"
                                            java.util.Calendar.MARCH, java.util.Calendar.APRIL -> "2º"
                                            java.util.Calendar.MAY, java.util.Calendar.JUNE -> "3º"
                                            java.util.Calendar.JULY, java.util.Calendar.AUGUST -> "4º"
                                            java.util.Calendar.SEPTEMBER, java.util.Calendar.OCTOBER -> "5º"
                                            java.util.Calendar.NOVEMBER, java.util.Calendar.DECEMBER -> "6º"
                                            else -> "1º"
                                        }
                                    }
                                    
                                    // Fetch all houses for these blocks across all agents
                                    val teamHouses = activeBlocks.chunked(10).flatMap { blockChunk ->
                                        firestore.collectionGroup("houses")
                                            .whereIn("blockNumber", blockChunk)
                                            .whereEqualTo("ciclo", currentCiclo)
                                            .get().await().documents
                                    }.mapNotNull { it.toHouseSafe(it.getString("agentUid") ?: "", it.getString("agentName") ?: "") }
                                    
                                    // Identify foreign houses (not mine)
                                    val remoteForeignHouses = teamHouses.filter { it.agentUid != uid }
                                    
                                    // IDENTIFY TEAM DELETIONS: 
                                    // If a foreign house is in our local DB for these blocks but NOT in the cloud,
                                    // it means the colleague deleted it. We must remove it locally too.
                                    // SAFETY GUARD 1: Never delete local houses that have a BLANK agentUid (Orphans).
                                    // These might be the current agent's own data waiting for migration or push.
                                    // SAFETY GUARD 2: ONLY delete local houses from the SAME CICLO as the cloud pull.
                                    // This prevents the sync from purging historical records of the same block from previous cycles.
                                    val localTeamHouses = houseDao.getHousesByBlocks(activeBlocks).filter { 
                                        it.agentUid.isNotBlank() && it.agentUid != uid && it.context.ciclo == currentCiclo 
                                    }
                                    val remoteKeys = remoteForeignHouses.map { it.generateNaturalKey() }.toSet()
                                    
                                    val housesDeletedByTeam = localTeamHouses.filter { it.generateNaturalKey() !in remoteKeys }
                                    if (housesDeletedByTeam.isNotEmpty()) {
                                        android.util.Log.i("SyncRepository", "Team Sync: Deleting ${housesDeletedByTeam.size} houses removed by colleagues.")
                                        runInTransactionWithRetry {
                                            housesDeletedByTeam.forEach { houseDao.deleteHouse(it) }
                                        }
                                    }
                                    
                                    // Add foreign houses to the main list for reconciliation/upsert
                                    cloudHouses.addAll(remoteForeignHouses)
                                }
                            } catch (e: Exception) {
                                android.util.Log.w("SyncRepository", "Teamwork sync failed (skipping): ${e.message}")
                            }
                        }

                        // 3. Deletion logic
                        var cloudHousesWithKeys = cloudHouses.map { HouseWithKeys(it) }
                        val validCloudHouseKeys = cloudHousesWithKeys.map { it.naturalKey }.toSet()
                        val validCloudActivityKeys = cloudDayActivities.map { "${it.date.replace("/", "-")}|${it.agentName.uppercase()}" }.toSet()
                        
                        // SELF-HEALING: Clean up zombie tombstones in Firestore
                        // We run this on EVERY pull to ensure that if data is present in cloud, 
                        // any conflicting deletion markers are permanently removed.
                        val zombieActivities = cloudDeletedActivities.filter { tombstoneKey ->
                            val datePart = tombstoneKey.split("|")[0].replace("/", "-")
                            validCloudActivityKeys.any { it.startsWith(datePart) }
                        }
                        val zombieHouses = cloudDeletedHouses.filter { it in validCloudHouseKeys }
                        
                        if (zombieActivities.isNotEmpty() || zombieHouses.isNotEmpty()) {
                            // CRITICAL FIX: Remove zombies from the local list immediately so they don't trigger local deletion
                            cloudDeletedActivities.removeAll(zombieActivities.toSet())
                            cloudDeletedHouses.removeAll(zombieHouses.toSet())
                            
                            try {
                                val batch = firestore.batch()
                                val docRef = firestore.collection("agents").document(uid)
                                if (zombieActivities.isNotEmpty()) {
                                    // Remove both the specific piped key and any potential legacy date-only keys
                                    val dateOnlyZombies = zombieActivities.map { it.split("|")[0] }
                                    batch.update(docRef, "deleted_activity_dates", com.google.firebase.firestore.FieldValue.arrayRemove(*(zombieActivities + dateOnlyZombies).toTypedArray()))
                                }
                                if (zombieHouses.isNotEmpty()) {
                                    batch.update(docRef, "deleted_house_ids", com.google.firebase.firestore.FieldValue.arrayRemove(*zombieHouses.toTypedArray()))
                                }
                                batch.commit().await()
                                android.util.Log.i("SyncRepository", "Self-Healing: Removed ${zombieActivities.size} zombie activity tombstones and ${zombieHouses.size} house tombstones from Firestore.")
                            } catch (e: Exception) {
                                android.util.Log.w("SyncRepository", "Self-Healing failed: ${e.message}")
                            }
                        }

                        cloudDeletedHouses.removeAll { it in validCloudHouseKeys }
                        cloudDeletedActivities.removeAll { it in validCloudActivityKeys }
                        
                        val allLocalHouses = houseDao.getHousesByAgentSnapshot(uid)
                        var allLocalHousesWithKeys = allLocalHouses.map { HouseWithKeys(it) }
                        
                        val cloudDeletedIdentities = cloudDeletedHouses.mapNotNull { deletedKey ->
                            val parts = deletedKey.split("_")
                            if (parts.size >= 11) {
                                try {
                                    // RECONSTRUCTION FIX: Must match House.generateIdentityKey() exactly.
                                    // Parts index map from naturalKey: 
                                    // 0:uid, 2:date, 3:block, 4:blockSeq, 5:street, 6:number, 7:seq, 8:compl, 9:bairro
                                    "${parts[0]}_${parts[2]}_${parts[3]}_${parts[4]}_${parts[5]}_${parts[6]}_${parts[7]}_${parts[8]}_${parts[9]}".uppercase()
                                } catch (e: Exception) { null }
                            } else null
                        }.toSet()

                        val housesToDelete = allLocalHousesWithKeys.filter { wrapper ->
                            val house = wrapper.house
                            val key = wrapper.naturalKey
                            val identityKey = wrapper.identityKey
                            
                            if (key in cloudDeletedHouses || identityKey in cloudDeletedIdentities || "${house.data.replace("/", "-")}|${house.agentName.uppercase()}" in cloudDeletedActivities) {
                                val timeSinceLastUpdate = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis() - house.lastUpdated
                                if (house.isSynced) {
                                    true
                                } else if (timeSinceLastUpdate > 900000L) {
                                    android.util.Log.i("SyncRepository", "Admin Authority / Ghost Cleanup: Deleting unsynced house ${house.id} due to cloud deletion.")
                                    true
                                        } else {
                                            android.util.Log.i("SyncRepository", "Agent Priority: Preserving actively typed house ${house.id} despite cloud deletion.")
                                            false
                                        }
                                    } else false
                                }.map { it.house }

                                val allLocalActivities = dayActivityDao.getAllDayActivities(uid)
                                
                                allLocalActivities.filter { it.date.replace("/", "-") in cloudDeletedActivities }.forEach {
                                    android.util.Log.i("SyncRepository", "Cloud Deletion Sync: Deleting local activity ${it.date} for $finalAgentName")
                                    runInTransactionWithRetry {
                                        dayActivityDao.deleteDayActivity(it.date, it.agentUid)
                                    }
                                }

                                val activitiesToDelete = allLocalActivities.filter { activity ->
                                    val dateKey = "${activity.date}|${activity.agentUid}"
                                    
                                    if (dateKey in cloudDeletedActivities) {
                                        val timeSinceLastUpdate = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis() - activity.lastUpdated
                                        if (activity.isSynced) {
                                            true
                                        } else if (timeSinceLastUpdate > 900000L) {
                                            android.util.Log.i("SyncRepository", "Admin Authority / Ghost Cleanup: Deleting unsynced activity ${activity.date} due to cloud deletion.")
                                            true
                                        } else {
                                            android.util.Log.i("SyncRepository", "Agent Priority: Preserving actively typed activity ${activity.date} despite cloud deletion.")
                                            false
                                        }
                                    } else false
                                }

                                // 4. Reconciliation
                                val localHousesByNaturalKey = allLocalHousesWithKeys.associateBy { it.naturalKey }
                                val localActivities = allLocalActivities.groupBy { "${it.date.replace("/", "-")}|${it.agentUid}" }
                                
                                val localTombstones = tombstoneDao.getAllTombstones(uid)
                                val localHouseTombstoneKeys = localTombstones.filter { it.type == com.antigravity.healthagent.data.local.model.TombstoneType.HOUSE }.map { it.naturalKey }.toSet()
                                val localActivityTombstoneKeys = localTombstones.filter { it.type == com.antigravity.healthagent.data.local.model.TombstoneType.ACTIVITY }.map { it.naturalKey }.toSet()

                                val housesDelta = cloudHousesWithKeys.filter { 
                                    it.naturalKey !in cloudDeletedHouses && it.naturalKey !in localHouseTombstoneKeys 
                                }
                                val activitiesDelta = cloudDayActivities.filter {
                                    val dateKey = "${it.date.replace("/", "-")}|${it.agentUid}"
                                    dateKey !in cloudDeletedActivities && dateKey !in localActivityTombstoneKeys
                                }

                                runInTransactionWithRetry {
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

                                    if (activitiesToDelete.isNotEmpty()) {
                                        val tombstonesToInsert = mutableListOf<com.antigravity.healthagent.data.local.model.Tombstone>()
                                        for (activity in activitiesToDelete) {
                                            dayActivityDao.deleteDayActivity(activity.date, activity.agentUid)
                                            tombstonesToInsert.add(
                                                com.antigravity.healthagent.data.local.model.Tombstone(
                                                    type = com.antigravity.healthagent.data.local.model.TombstoneType.ACTIVITY,
                                                    naturalKey = "${activity.date.replace("/", "-")}|${activity.agentUid}",
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

                                    val localIdentityMap = if (housesDelta.isNotEmpty()) allLocalHousesWithKeys.groupBy { it.identityKey } else emptyMap()

                                    val housesToUpsert = housesDelta.mapNotNull { cloudWrapper ->
                                        val cloudHouse = cloudWrapper.house
                                        val key = cloudWrapper.naturalKey
                                        var existing = localHousesByNaturalKey[key]?.house
                                        
                                        if (existing == null) {
                                            val identityKey = cloudWrapper.identityKey
                                            existing = localIdentityMap[identityKey]?.find { it.house.agentUid == cloudHouse.agentUid }?.house
                                            
                                            if (existing == null) {
                                                val normalizedDate = cloudHouse.data.replace("/", "-")
                                                val dateKey = "$normalizedDate|$uid"
                                                val dayActivity = localActivities[dateKey]?.firstOrNull()
                                                val cloudActivity = activitiesDelta.find { it.date.replace("/", "-") == normalizedDate }
                                                
                                                val isCloudUnlocked = cloudActivity?.isManualUnlock == true
                                                val isLocallyClosed = dayActivity?.isClosed == true && dayActivity.isManualUnlock != true
                                                
                                                if (isLocallyClosed && !isCloudUnlocked && !cloudHouse.editedByAdmin && !isTargetDifferentUser) {
                                                    return@mapNotNull null
                                                }
                                            }
                                        }

                                        if (existing != null && !existing.isSynced) {
                                            val isAdminOverride = cloudHouse.editedByAdmin && !existing.editedByAdmin && 
                                                (System.currentTimeMillis() - existing.lastUpdated > 120000L)

                                            if (!isAdminOverride) {
                                                val threshold = com.antigravity.healthagent.utils.AppConstants.SYNC_CONFLICT_THRESHOLD_MS
                                                if (existing.lastUpdated > (cloudHouse.lastUpdated + threshold)) {
                                                    return@mapNotNull null
                                                }
                                            }
                                        }

                                        cloudHouse.copy(
                                            id = existing?.id ?: 0,
                                            agentName = finalAgentName,
                                            agentUid = uid,
                                            isSynced = true
                                        )
                                    }
                                    
                                    val activitiesToUpsert = activitiesDelta.mapNotNull { activity ->
                                        val normalizedDate = activity.date.replace("/", "-")
                                        val key = "$normalizedDate|$uid"
                                        val dateKey = "$normalizedDate|$uid"
                                        
                                        if (dateKey in cloudDeletedActivities) {
                                            return@mapNotNull null
                                        }
                                        
                                        val existing = localActivities[key]?.firstOrNull()

                                        if (existing != null && !existing.isSynced) {
                                             val isRemoteUnlock = activity.isManualUnlock && !existing.isManualUnlock
                                             val isAdminOverride = activity.editedByAdmin && !existing.editedByAdmin && 
                                                (System.currentTimeMillis() - existing.lastUpdated > 120000L)

                                             val threshold = com.antigravity.healthagent.utils.AppConstants.SYNC_CONFLICT_THRESHOLD_MS
                                             
                                             if (!isRemoteUnlock && !isAdminOverride && existing.lastUpdated > (activity.lastUpdated + threshold)) {
                                                 return@mapNotNull null
                                             }
                                        }

                                        activity.copy(
                                            date = normalizedDate,
                                            agentName = finalAgentName,
                                            agentUid = uid,
                                            isSynced = true
                                        )
                                    }

                                    houseDao.upsertHouses(housesToUpsert)
                                    dayActivityDao.upsertDayActivities(activitiesToUpsert)

                                    housesToUpsert.forEach { house ->
                                        tombstoneDao.deleteByNaturalKey(house.generateNaturalKey(), house.agentUid)
                                    }
                                    activitiesToUpsert.forEach { activity ->
                                        tombstoneDao.deleteByNaturalKey("${activity.date.replace("/", "-")}|${activity.agentUid}", activity.agentUid)
                                    }

                                    housesToUpsert.forEach { pulledHouse ->
                                        val key = pulledHouse.generateNaturalKey()
                                        val localMatches = allLocalHousesWithKeys.filter { it.naturalKey == key && it.house.id != pulledHouse.id && it.house.id != 0 }
                                        localMatches.forEach { match ->
                                            // DEDUPLICATION SAFETY: Never delete unsynced local records during a pull.
                                            // This prevents the 'houses removed' regression where pending work is lost.
                                            if (match.house.isSynced) {
                                                houseDao.deleteHouse(match.house)
                                            }
                                        }
                                    }
                                }

                                if (!isTargetDifferentUser) {
                                    val maxObservedTime = (cloudHouses.map { it.lastUpdated } + cloudDayActivities.map { it.lastUpdated }).maxOrNull() ?: 0L
                                    val safetyAnchor = serverTime - 600000L 
                                    settingsManager.setLastSyncTimestamp(maxOf(maxObservedTime, safetyAnchor))
                                }
                                
                                allLocalHousesWithKeys = emptyList()
                                cloudHousesWithKeys = emptyList()

                                Result.success(Unit)
                            } catch (e: Exception) {
                                android.util.Log.e("SyncRepository", "Pull failed", e)
                                Result.failure(e)
                            }
                        }
                    }
                } ?: Result.failure(Exception("O download de dados atingiu o tempo limite. Verifique sua conexão ou tente novamente."))
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

    override suspend fun clearAgentData(agentUid: String): Result<Unit> {
        return withTimeoutOrNull(15000L) {
            syncMutex.withLock {
                try {
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

    override suspend fun restoreLocalData(houses: List<House>, activities: List<DayActivity>, agentUid: String?): Result<Unit> {
        val finalUid = agentUid ?: ""
        return try {
            runInTransactionWithRetry {
                val restoredDatesList = activities.map { it.date.replace("/", "-") }.distinct()

                if (restoredDatesList.isNotEmpty()) {
                    android.util.Log.i("SyncRepository", "Restoration: Atomic purge of ${restoredDatesList.size} dates for $finalUid")
                    houseDao.deleteByAgentAndDates(finalUid, restoredDatesList)
                    dayActivityDao.deleteByAgentAndDates(finalUid, restoredDatesList)
                }

                // 3. Normalize and Prepare Data
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
                    // Clear local tombstone for restored activity
                    tombstoneDao.deleteByNaturalKey("${normalized.date}|${normalized.agentUid}", normalized.agentUid)
                }

                val housesToUpsert = houses.map { restoredHouse ->
                    
                    // Healing logic: Default EMPTY to NONE (Aberto) for restored data
                    val finalSituation = if (restoredHouse.situation == com.antigravity.healthagent.data.local.model.Situation.EMPTY) {
                        com.antigravity.healthagent.data.local.model.Situation.NONE
                    } else restoredHouse.situation

                    val finalHouse = restoredHouse.copy(
                        id = 0, // Reset ID to allow auto-generation and prevent collision with unrelated local records
                        agentUid = finalUid,
                        data = restoredHouse.data.replace("/", "-"),
                        situation = finalSituation,
                        isSynced = false,
                        lastUpdated = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis()
                    )
                    
                    finalHouse
                }
                
                housesToUpsert.forEach { finalHouse ->
                    // Clear local tombstone for restored house
                    tombstoneDao.deleteByNaturalKey(finalHouse.generateNaturalKey(), finalHouse.agentUid)
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
            
            val settings = snapshot?.data ?: emptyMap<String, Any>()
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

    override suspend fun recordActivityDeletion(date: String, agentUid: String): Result<Unit> {
        return try {
            tombstoneDao.insertTombstone(
                com.antigravity.healthagent.data.local.model.Tombstone(
                    type = com.antigravity.healthagent.data.local.model.TombstoneType.ACTIVITY,
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
                    // Use standardized full keys for tombstones in cloud
                    val chunkKeys = chunk.map { 
                        if (it.contains("|")) it.replace("/", "-") 
                        else if (agentName.isNotBlank()) "${it.replace("/", "-")}|${agentName.uppercase()}" 
                        else it.replace("/", "-")
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

    override suspend fun deleteHousesSurgically(agentUid: String, houses: List<House>): Result<Unit> {
        if (houses.isEmpty()) return Result.success(Unit)
        
        return try {
            val houseKeys = houses.map { it.cloudId ?: it.generateNaturalKey() }
            
            // 1. Create Tombstones to ensure cloud deletion
            recordBulkDeletions(houseKeys, emptyList(), agentUid)
            
            // 2. Delete locally
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
    

    private class HouseWithKeys(
        val house: House,
        val naturalKey: String = house.generateNaturalKey(),
        val identityKey: String = house.generateIdentityKey()
    )

    private fun DocumentSnapshot.toHouseSafe(agentUid: String, agentName: String): House? {
    return try {
        // 1. Try standard Firestore mapping first
        val house = try { this.toObject(House::class.java) } catch (e: Exception) { null }
        
        // 2. Manually extract critical metadata to bypass @Exclude and handle mismatches
        val firestoreLastUpdated = this.getTimestamp("lastUpdated")?.toDate()?.time ?: 0L
        val rawData = (this.getString("data") ?: house?.data ?: "").replace("/", "-")
        val rawAgentName = (this.getString("agentName") ?: agentName).normalize()
        
        // 3. Robust Construction: Fallback to manual field extraction if toObject failed
        val baseHouse = (house ?: House(
            address = com.antigravity.healthagent.domain.model.VisitAddress(
                blockNumber = (this.getString("blockNumber") ?: "").normalize(),
                streetName = (this.getString("streetName") ?: "").trim().formatStreetName(),
                number = (this.getString("number") ?: "").trim().uppercase(),
                sequence = (this.get("sequence") as? Number)?.toInt() ?: 0,
                complement = (this.get("complement") as? Number)?.toInt() ?: 0,
                bairro = (this.getString("bairro") ?: "").normalize(),
                blockSequence = (this.getString("blockSequence") ?: "").normalize()
            ),
            context = com.antigravity.healthagent.domain.model.DailyContext(
                municipio = this.getString("municipio") ?: "Bom Jardim",
                categoria = this.getString("categoria") ?: "BRR",
                zona = this.getString("zona") ?: "URB",
                tipo = (this.get("tipo") as? Number)?.toInt() ?: 2,
                ciclo = this.getString("ciclo") ?: "1º",
                atividade = (this.get("atividade") as? Number)?.toInt() ?: 4
            ),
            treatment = com.antigravity.healthagent.domain.model.TreatmentData(
                a1 = (this.get("a1") as? Number)?.toInt() ?: 0,
                a2 = (this.get("a2") as? Number)?.toInt() ?: 0,
                b = (this.get("b") as? Number)?.toInt() ?: 0,
                c = (this.get("c") as? Number)?.toInt() ?: 0,
                d1 = (this.get("d1") as? Number)?.toInt() ?: 0,
                d2 = (this.get("d2") as? Number)?.toInt() ?: 0,
                e = (this.get("e") as? Number)?.toInt() ?: 0,
                eliminados = (this.get("eliminados") as? Number)?.toInt() ?: 0,
                larvicida = (this.get("larvicida") as? Number)?.toDouble() ?: 0.0,
                comFoco = this.getBoolean("comFoco") ?: false
            ),
            localidadeConcluida = this.getBoolean("localidadeConcluida") ?: false,
            quarteiraoConcluido = this.getBoolean("quarteiraoConcluido") ?: false,
            listOrder = (this.get("listOrder") as? Number)?.toLong() ?: 0L,
            visitSegment = (this.get("visitSegment") as? Number)?.toInt() ?: 0,
            observation = this.getString("observation") ?: "",
            geo = com.antigravity.healthagent.domain.model.GeoCapture(
                latitude = this.get("latitude") as? Double,
                longitude = this.get("longitude") as? Double,
                focusCaptureTime = (this.get("focusCaptureTime") as? Number)?.toLong()
            ),
            editedByAdmin = this.getBoolean("editedByAdmin") ?: false
        )).copy(id = 0) // RESET ID to allow local auto-generation

        val finalHouse = baseHouse.apply {
            this.cloudId = this@toHouseSafe.id
        }.copy(
            agentUid = agentUid,
            agentName = rawAgentName,
            data = rawData,
            lastUpdated = firestoreLastUpdated,
            propertyType = coercePropertyType(this.getString("propertyType") ?: house?.propertyType?.name),
            situation = coerceSituation(this.getString("situation") ?: house?.situation?.name)
        ).apply {
            this.cloudId = this@toHouseSafe.id
        }

        // SURGICAL FILTER: Discard "Zombies" (Houses without core address info)
        val isBroken = finalHouse.address.streetName.isBlank() && 
                       finalHouse.address.bairro.isBlank() && 
                       finalHouse.address.number.isBlank() && 
                       finalHouse.address.sequence <= 0
        
        if (isBroken) {
            android.util.Log.w("SyncRepository", "Discarded broken house from cloud: ${this.id}")
            return null
        }

        return finalHouse
    } catch (e: Exception) {
        android.util.Log.e("SyncRepository", "toHouseSafe CRITICAL error for doc ${this.id}: ${e.message}")
        null
    }
}

    private fun DocumentSnapshot.toDayActivitySafe(agentUid: String, agentName: String): com.antigravity.healthagent.data.local.model.DayActivity? {
        return try {
            val activity = try { this.toObject(com.antigravity.healthagent.data.local.model.DayActivity::class.java) } catch (e: Exception) { null }
            val firestoreLastUpdated = this.getTimestamp("lastUpdated")?.toDate()?.time ?: 0L
            val rawDate = (this.getString("date") ?: activity?.date ?: "").replace("/", "-")
            
            val baseActivity = activity ?: com.antigravity.healthagent.data.local.model.DayActivity(
                status = this.getString("status") ?: "",
                isClosed = this.getBoolean("isClosed") ?: false,
                isManualUnlock = this.getBoolean("isManualUnlock") ?: false,
                editedByAdmin = this.getBoolean("editedByAdmin") ?: false
            )
            
            baseActivity.copy(
                agentUid = agentUid,
                agentName = this.getString("agentName")?.uppercase() ?: agentName.uppercase(),
                date = rawDate,
                lastUpdated = firestoreLastUpdated
            )
        } catch (e: Exception) {
            android.util.Log.e("SyncRepository", "toDayActivitySafe error for doc ${this.id}: ${e.message}")
            null
        }
    }

    private fun coercePropertyType(raw: String?): com.antigravity.healthagent.data.local.model.PropertyType {
        if (raw == null) return com.antigravity.healthagent.data.local.model.PropertyType.EMPTY
        return try {
            com.antigravity.healthagent.data.local.model.PropertyType.valueOf(raw.uppercase())
        } catch (e: Exception) {
            // Fallback for legacy string descriptions
            when (raw.uppercase()) {
                "RESIDÊNCIA", "RESIDENCIA" -> com.antigravity.healthagent.data.local.model.PropertyType.R
                "COMÉRCIO", "COMERCIO" -> com.antigravity.healthagent.data.local.model.PropertyType.C
                "TERRENO BALDIO" -> com.antigravity.healthagent.data.local.model.PropertyType.TB
                "OUTROS" -> com.antigravity.healthagent.data.local.model.PropertyType.O
                "PONTO ESTRATÉGICO", "PONTO ESTRATEGICO" -> com.antigravity.healthagent.data.local.model.PropertyType.PE
                else -> com.antigravity.healthagent.data.local.model.PropertyType.EMPTY
            }
        }
    }

    private fun coerceSituation(raw: String?): com.antigravity.healthagent.data.local.model.Situation {
        if (raw == null) return com.antigravity.healthagent.data.local.model.Situation.EMPTY
        return try {
            com.antigravity.healthagent.data.local.model.Situation.valueOf(raw.uppercase())
        } catch (e: Exception) {
            when (raw.uppercase()) {
                "FECHADO" -> com.antigravity.healthagent.data.local.model.Situation.F
                "RECUSADO" -> com.antigravity.healthagent.data.local.model.Situation.REC
                "ABANDONADO" -> com.antigravity.healthagent.data.local.model.Situation.A
                "VAZIO" -> com.antigravity.healthagent.data.local.model.Situation.V
                "ABERTO" -> com.antigravity.healthagent.data.local.model.Situation.EMPTY
                else -> com.antigravity.healthagent.data.local.model.Situation.EMPTY
            }
        }
    }

    override suspend fun pruneOldTombstones(): Result<Unit> {
        return try {
            val thirtyDaysAgo = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
            tombstoneDao.deleteOldTombstones(thirtyDaysAgo)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
