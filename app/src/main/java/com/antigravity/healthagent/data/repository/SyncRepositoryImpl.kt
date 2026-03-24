package com.antigravity.healthagent.data.repository

import androidx.room.withTransaction
import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.domain.repository.AgentData
import com.antigravity.healthagent.domain.repository.SyncRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.*
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

    override suspend fun pushLocalDataToCloud(
        houses: List<House>,
        activities: List<DayActivity>,
        targetUid: String?,
        shouldReplace: Boolean
    ): Result<Unit> {
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
            val tombstones = tombstoneDao.getAllTombstones()

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
                metadata["deleted_house_ids"] = com.google.firebase.firestore.FieldValue.delete()
                metadata["deleted_activity_dates"] = com.google.firebase.firestore.FieldValue.delete()
                
                // SURGICAL WIPE: Delete all existing cloud houses and activities for this user
                // to ensure a true REPLACE instead of a MERGE.
                val existingHouses = userDocRef.collection("houses").get().await()
                val existingActivities = userDocRef.collection("day_activities").get().await()
                
                val wipeOperations = (existingHouses.documents + existingActivities.documents).map { it.reference }
                if (wipeOperations.isNotEmpty()) {
                    wipeOperations.chunked(400).forEach { chunk ->
                        val batch = firestore.batch()
                        chunk.forEach { batch.delete(it) }
                        batch.commit().await()
                    }
                }
            }
            if (targetUid == null || (existingEmail.isNullOrBlank() && userEmail != "Remote Sync")) {
                metadata["email"] = userEmail
            }

            val operations = mutableListOf<Pair<com.google.firebase.firestore.DocumentReference, Any>>()
            
            // Houses
            val normalizedHouses = com.antigravity.healthagent.utils.HouseNormalizationUtils.normalizeHouses(housesToPush)
            normalizedHouses.forEach { house ->
                val houseData = house.copy(agentName = officialAgentName).toFirestoreMap()
                operations.add(userDocRef.collection("houses").document(house.generateNaturalKey()) to houseData)
            }

            // Activities
            activitiesToPush.forEach { activity ->
                val activityData = activity.copy(agentName = officialAgentName, agentUid = uid).toFirestoreMap()
                operations.add(userDocRef.collection("day_activities").document(activity.date.replace("/", "-")) to activityData)
            }

            // Deletions (Tombstones)
            val cloudDeletedHouses = mutableListOf<String>()
            val cloudDeletedActivities = mutableListOf<String>()
            tombstones.forEach { t ->
                if (t.type == com.antigravity.healthagent.data.local.model.TombstoneType.HOUSE) {
                    operations.add(userDocRef.collection("houses").document(t.naturalKey) to com.google.firebase.firestore.FieldValue.delete())
                    cloudDeletedHouses.add(t.naturalKey)
                } else {
                    operations.add(userDocRef.collection("day_activities").document(t.naturalKey.split("|")[0]) to com.google.firebase.firestore.FieldValue.delete())
                    cloudDeletedActivities.add(t.naturalKey)
                }
            }

            // Metadata update including cloud-side tombstones
            if (cloudDeletedHouses.isNotEmpty()) metadata["deleted_house_ids"] = com.google.firebase.firestore.FieldValue.arrayUnion(*cloudDeletedHouses.toTypedArray())
            if (cloudDeletedActivities.isNotEmpty()) metadata["deleted_activity_dates"] = com.google.firebase.firestore.FieldValue.arrayUnion(*cloudDeletedActivities.toTypedArray())
            operations.add(0, userDocRef to metadata)

            // Commit in Batches
            val chunks = operations.chunked(400)
            chunks.forEachIndexed { index, chunk ->
                val batch = firestore.batch()
                chunk.forEach { (ref, data) ->
                    when (data) {
                        is com.google.firebase.firestore.FieldValue -> batch.delete(ref)
                        is Map<*, *> -> {
                            @Suppress("UNCHECKED_CAST")
                            batch.set(ref, data as Map<String, Any>, com.google.firebase.firestore.SetOptions.merge())
                        }
                    }
                }
                
                // OPTIMIZATION: Include final metadata update in the LAST batch to save 1 write operation
                if (index == chunks.size - 1) {
                    batch.update(userDocRef, mapOf(
                        "lastSyncTime" to System.currentTimeMillis(),
                        "lastSyncError" to com.google.firebase.firestore.FieldValue.delete()
                    ))
                }
                
                kotlinx.coroutines.withTimeout(60000L) { batch.commit().await() }
            }

            // Post-Sync Success: Update local state
            database.withTransaction {
                if (housesToPush.isNotEmpty()) {
                    // Note: If shouldReplace was true, we push full list. If false, only unsynced.
                    // Either way, these are the ones now successfully in the cloud.
                    houseDao.markAsSynced(housesToPush.map { it.id })
                }
                if (activitiesToPush.isNotEmpty()) {
                    dayActivityDao.markAsSynced(activitiesToPush.map { it.date }, officialAgentName, uid)
                }
                if (tombstones.isNotEmpty()) {
                    tombstoneDao.deleteTombstones(tombstones.map { it.id })
                }
            }

            // Final Metadata Update: Mark success and clear Error (Handled in batch above if operations existed)
            if (operations.isEmpty()) {
                userDocRef.update(
                    mapOf(
                        "lastSyncTime" to System.currentTimeMillis(),
                        "lastSyncError" to com.google.firebase.firestore.FieldValue.delete()
                    )
                ).await()
            }

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

    override suspend fun fetchAllAgentsData(): Result<List<AgentData>> {
        return try {
            val agentsSnapshot = firestore.collection("agents").get().await()
            val allAgents = mutableListOf<AgentData>()

            for (agentDoc in agentsSnapshot.documents) {
                var email = agentDoc.getString("email") ?: ""
                var agentName = agentDoc.getString("agentName")?.uppercase()
                val lastSyncTime = agentDoc.getLong("lastSyncTime") ?: 0L
                val lastSyncError = agentDoc.getString("lastSyncError")
                val uid = agentDoc.id

                // If email is missing, try to fetch it from the 'users' collection to avoid "Unknown" cards
                if (email.isBlank() || email == "Unknown") {
                    email = try { 
                        firestore.collection("users").document(uid).get().await().getString("email") ?: "Unknown"
                    } catch(e: Exception) { "Unknown" }
                }

                val housesSnapshot = agentDoc.reference.collection("houses").get().await()
                val houses = housesSnapshot.documents.mapNotNull { it.toHouseSafe(uid) }

                val activitiesSnapshot = agentDoc.reference.collection("day_activities").get().await()
                val activities = activitiesSnapshot.documents.mapNotNull { it.toDayActivitySafe(uid) }

                // IMPROVEMENT: If agentName is missing in the main doc, recover it from the data
                if (agentName.isNullOrBlank()) {
                    agentName = houses.firstOrNull { it.agentName.isNotBlank() }?.agentName?.uppercase()
                        ?: activities.firstOrNull { it.agentName.isNotBlank() }?.agentName?.uppercase()
                }

                allAgents.add(
                    AgentData(
                        uid = uid,
                        email = email,
                        agentName = agentName,
                        houses = houses,
                        activities = activities,
                        lastSyncTime = lastSyncTime,
                        lastSyncError = lastSyncError
                    )
                )
            }
            Result.success(allAgents)
        } catch (e: Exception) {
            if (e.message?.contains("PERMISSION_DENIED", ignoreCase = true) == true) {
                Result.failure(Exception("Acesso negado ao carregar dados dos agentes. Verifique se você é um administrador ou supervisor e se as regras do Firestore permitem a leitura desta coleção."))
            } else {
                Result.failure(e)
            }
        }
    }
    override suspend fun pullCloudDataToLocal(targetUid: String?): Result<Unit> {
        return try {
            val uid = targetUid ?: auth.currentUser?.uid ?: return Result.failure(Exception("User not authenticated"))
            val userDocRef = firestore.collection("agents").document(uid)
            
            // 1. Check for different user (Admin mode)
            val isTargetDifferentUser = targetUid != null && targetUid != auth.currentUser?.uid
            
            // 2. Fetch Sync State
            var lastSync = if (isTargetDifferentUser) 0L else settingsManager.lastSyncTimestamp.first()
            
            // RECOVERY: If we think we have a lastSync but the local database is suspiciously empty, FORCE a full pull.
            // This handles cases like re-installs where DataStore (lastSync) is restored via AutoBackup but Room (DB) is not.
            if (lastSync > 0 && !isTargetDifferentUser && houseDao.count() == 0) {
                android.util.Log.w("SyncRepository", "Local database is empty but lastSync is $lastSync. Forcing full pull.")
                lastSync = 0L
            }
            
            android.util.Log.i("SyncRepository", "Pulling: Incremental from $lastSync")
            
            // 2.5 Fetch and apply global system settings (Admin-defined)
            try {
                val settingsSnap = firestore.collection("metadata").document("settings").get().await()
                if (settingsSnap.exists()) {
                    (settingsSnap.get("max_open_houses") as? Long)?.let { 
                        settingsManager.setMaxOpenHouses(it.toInt()) 
                    }
                    // Add other global settings sync here if needed
                }
            } catch (e: Exception) {
                android.util.Log.w("SyncRepository", "Failed to sync system settings: ${e.message}")
            }

            // 3. Fetch Cloud Data Delta
            val housesQuery = if (lastSync == 0L) {
                userDocRef.collection("houses")
            } else {
                userDocRef.collection("houses")
                    .whereGreaterThan("lastUpdated", com.google.firebase.Timestamp(lastSync / 1000, ((lastSync % 1000) * 1000000).toInt()))
            }
            val housesDelta = housesQuery.get().await().documents.mapNotNull { it.toHouseSafe(uid) }
            
            val activitiesQuery = if (lastSync == 0L) {
                userDocRef.collection("day_activities")
            } else {
                userDocRef.collection("day_activities")
                    .whereGreaterThan("lastUpdated", com.google.firebase.Timestamp(lastSync / 1000, ((lastSync % 1000) * 1000000).toInt()))
            }
            val activitiesDelta = activitiesQuery.get().await().documents.mapNotNull { it.toDayActivitySafe(uid) }

            // 4. Fetch Agent Doc and Recover Metadata if missing
            val agentDoc = userDocRef.get().await()
            var currentAgentName = agentDoc.getString("agentName") ?: ""
            
            // RECOVERY: If agentName is missing in the 'agents' doc, try the 'users' doc (Set by AuthRepository)
            if (currentAgentName.isBlank()) {
                currentAgentName = try {
                    firestore.collection("users").document(uid).get().await().getString("agentName") ?: ""
                } catch (e: Exception) { "" }
                
                // If recovered, store it back to the 'agents' doc for future efficiency
                if (currentAgentName.isNotBlank()) {
                    userDocRef.set(mapOf("agentName" to currentAgentName.uppercase()), com.google.firebase.firestore.SetOptions.merge())
                }
            }

            val serverTime = agentDoc.getTimestamp("lastUpdated")?.toDate()?.time ?: System.currentTimeMillis()
            
            database.withTransaction {
                if (isTargetDifferentUser) {
                    android.util.Log.i("SyncRepository", "Pull: Different user. Clearing local data.")
                    houseDao.deleteAll()
                    dayActivityDao.deleteAll()
                    tombstoneDao.deleteAll()
                } else {
                    // Reconcile Cloud Deletions (Tombstones)
                    val cloudDeletedHouses = (agentDoc.get("deleted_house_ids") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                    val cloudDeletedActivities = (agentDoc.get("deleted_activity_dates") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                    
                    if (cloudDeletedHouses.isNotEmpty() || cloudDeletedActivities.isNotEmpty()) {
                        android.util.Log.i("SyncRepository", "Reconciling ${cloudDeletedHouses.size} cloud deletions.")
                        
                        // Delete cloud-tombstoned items locally
                        // Optimization: Avoid full list iteration if tombstone list is small
                        if (cloudDeletedHouses.isNotEmpty()) {
                            val allLocalHouses = houseDao.getAllHouses(currentAgentName, uid).first()
                            val housesToDelete = allLocalHouses.filter { it.generateNaturalKey() in cloudDeletedHouses }
                            if (housesToDelete.isNotEmpty()) {
                                housesToDelete.forEach { houseDao.deleteHouse(it) }
                            }
                        }
                        
                        if (cloudDeletedActivities.isNotEmpty()) {
                             cloudDeletedActivities.forEach { tombstone ->
                                val parts = tombstone.split("|")
                                val date = parts[0]
                                val agentName = if (parts.size > 1) parts[1] else agentDoc.getString("agentName") ?: ""
                                dayActivityDao.deleteDayActivity(date, agentName, uid)
                            }
                        }
                        
                        // Clear cloud tombstones once applied locally
                        userDocRef.update(
                            "deleted_house_ids", com.google.firebase.firestore.FieldValue.delete(),
                            "deleted_activity_dates", com.google.firebase.firestore.FieldValue.delete()
                        ).await()
                    }
                }

                // RECOVERY FROM DATA: If currentAgentName is still blank, try to peek into the pulled data
                var finalAgentName = currentAgentName.uppercase()
                if (finalAgentName.isBlank()) {
                    finalAgentName = (housesDelta.firstOrNull { it.agentName.isNotBlank() }?.agentName 
                        ?: activitiesDelta.firstOrNull { it.agentName.isNotBlank() }?.agentName
                        ?: "").uppercase()
                }

                // 5. Apply Delta Locally
                // Propagate recovered agentName to 'users' collection so AuthRepository/UI emits the updated name
                if (finalAgentName.isNotBlank() && currentAgentName.isBlank() && !isTargetDifferentUser) {
                    try {
                        firestore.collection("users").document(uid)
                            .update("agentName", finalAgentName)
                            .await()
                    } catch (e: Exception) {
                        android.util.Log.e("SyncRepository", "Failed to propagate recovered agentName to user doc", e)
                    }
                }

                val localHouses = houseDao.getAllHouses(finalAgentName, uid).first().groupBy { it.generateNaturalKey() }
                val localActivities = dayActivityDao.getAllDayActivities(finalAgentName, uid).groupBy { "${it.date}|${it.agentName.uppercase()}" }
                
                // PERFORMANCE: Pre-calculate locked dates to avoid repeated lookups
                val lockedKeys = localActivities.filter { it.value.any { act -> act.isClosed } }.keys
                
                val housesToUpsert = housesDelta.mapNotNull { cloudHouse ->
                    val key = cloudHouse.generateNaturalKey()
                    val dateKey = "${cloudHouse.data}|${finalAgentName}"
                    
                    // Protection 1: Locked Day Protection
                    if (dateKey in lockedKeys) {
                        android.util.Log.i("SyncRepository", "Pull: Skipping update for house $key - Day is LOCKED locally.")
                        return@mapNotNull null
                    }

                    val existing = localHouses[key]?.firstOrNull()
                    
                    // Protection 2: If local record is 'dirty' (unsynced) and newer, don't overwrite it with stale cloud data
                    if (existing != null && !existing.isSynced && existing.lastUpdated >= cloudHouse.lastUpdated) {
                        android.util.Log.d("SyncRepository", "Pull: Skipping overwrite of dirty house $key (Local is newer or same)")
                        return@mapNotNull null
                    }

                    // REordering Protection: If cloud has listOrder=0 (likely missing field), preserve local order if available
                    val finalListOrder = if (cloudHouse.listOrder == 0L && existing != null && existing.listOrder != 0L) {
                        existing.listOrder
                    } else {
                        cloudHouse.listOrder
                    }

                    cloudHouse.copy(
                        id = existing?.id ?: 0,
                        listOrder = finalListOrder,
                        agentName = finalAgentName,
                        isSynced = true // Pulled data is synced
                    )
                }
                
                val activitiesToUpsert = activitiesDelta.mapNotNull { activity ->
                    val normalizedDate = activity.date.replace("/", "-")
                    val key = "$normalizedDate|$finalAgentName"
                    
                    // Protection 1: Locked Day Protection
                    val existing = localActivities[key]?.firstOrNull()
                    if (existing?.isClosed == true) {
                         android.util.Log.i("SyncRepository", "Pull: Skipping update for activity $key - Day is LOCKED locally.")
                         return@mapNotNull null
                    }

                    // Protection 2: Dirty Data Protection
                    if (existing != null && !existing.isSynced && existing.lastUpdated >= activity.lastUpdated) {
                        android.util.Log.d("SyncRepository", "Pull: Skipping overwrite of dirty activity $key (Local is newer or same)")
                        return@mapNotNull null
                    }

                    activity.copy(
                        date = normalizedDate,
                        agentName = finalAgentName,
                        isSynced = true
                    )
                }

                houseDao.upsertHouses(housesToUpsert)
                dayActivityDao.upsertDayActivities(activitiesToUpsert)
            }

            // 6. Update local sync tracking
            if (!isTargetDifferentUser) {
                settingsManager.setLastSyncTimestamp(serverTime)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("SyncRepository", "Pull failed", e)
            if (e.message?.contains("PERMISSION_DENIED") == true) {
                Result.failure(Exception("Acesso negado. Tente fazer login novamente."))
            } else Result.failure(e)
        }
    }

    override suspend fun createAgent(email: String, agentName: String?): Result<Unit> {
        return try {
            val normalizedEmail = email.trim().lowercase()
            // Check if agent already exists
            val existing = firestore.collection("agents")
                .whereEqualTo("email", normalizedEmail)
                .get()
                .await()
            
            if (!existing.isEmpty) {
                return Result.failure(Exception("Agente já cadastrado com este e-mail"))
            }

            val docId = "pre_${normalizedEmail.replace(".", "_").replace("@", "_")}"
            val agentData = mapOf(
                "email" to normalizedEmail,
                "agentName" to agentName?.trim()?.uppercase()?.takeIf { it.isNotBlank() },
                "lastSyncTime" to 0L,
                "isPreRegistered" to true
            )
            
            firestore.collection("agents").document(docId).set(agentData).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteAgent(uid: String): Result<Unit> {
        return try {
            val agentRef = firestore.collection("agents").document(uid)
            
            // Firestore delete doesn't cascade to subcollections, we must delete them manually
            val houses = agentRef.collection("houses").get().await()
            val activities = agentRef.collection("day_activities").get().await()
            
            val operations = (houses.documents + activities.documents).map { it.reference }.toMutableList()
            operations.add(agentRef)
            
            // Also check for 'users' document associated if any (though usually handled by AuthRepository)
            // For robustness, we focus on the AGENT data here.
            
            if (operations.isNotEmpty()) {
                // Chunk deletions to stay within 500 limit
                operations.chunked(400).forEach { chunk ->
                    val batch = firestore.batch()
                    for (ref in chunk) {
                        batch.delete(ref)
                    }
                    batch.commit().await()
                }
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun fetchAgentNames(): Result<List<String>> {
        return try {
            // OPTIMIZATION: Try cache first, then server in background or with short timeout
            val snapshot = withTimeoutOrNull(1500) {
                firestore.collection("metadata").document("agent_info")
                    .get().await()
            }
            
            if (snapshot == null) {
                return Result.success(com.antigravity.healthagent.utils.AppConstants.AGENT_NAMES)
            }
            
            val names = (snapshot.get("names") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            Result.success(names.sorted())
        } catch (e: Exception) {
            android.util.Log.w("SyncRepository", "fetchAgentNames offline fallback: ${e.message}")
            Result.success(com.antigravity.healthagent.utils.AppConstants.AGENT_NAMES)
        }
    }

    override suspend fun addAgentName(name: String): Result<Unit> {
        return try {
            val normalizedName = name.trim().uppercase()
            if (normalizedName.isBlank()) return Result.failure(Exception("Nome não pode ser vazio"))

            val docRef = firestore.collection("metadata").document("agent_info")
            val snapshot = docRef.get().await()
            
            val currentNames = (snapshot.get("names") as? List<*>)?.filterIsInstance<String>()?.toMutableList() ?: mutableListOf()
            if (!currentNames.contains(normalizedName)) {
                currentNames.add(normalizedName)
                docRef.set(mapOf("names" to currentNames.sorted())).await()
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteAgentName(name: String): Result<Unit> {
        return try {
            val docRef = firestore.collection("metadata").document("agent_info")
            val snapshot = docRef.get().await()
            
            val currentNames = (snapshot.get("names") as? List<*>)?.filterIsInstance<String>()?.toMutableList() ?: mutableListOf()
            if (currentNames.remove(name)) {
                docRef.set(mapOf("names" to currentNames.sorted())).await()
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun clearLocalData(): Result<Unit> {
        return try {
            database.withTransaction {
                houseDao.deleteAll()
                dayActivityDao.deleteAll()
            }
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
                    it.copy(
                        agentName = finalAgentName.uppercase(),
                        agentUid = finalUid,
                        date = it.date.replace("/", "-")
                    ) 
                }
                val housesToUpsert = mutableListOf<House>()
                val normalizedHouses = com.antigravity.healthagent.utils.HouseNormalizationUtils.normalizeHouses(houses)

                normalizedHouses.forEach { restoredHouse ->
                    val key = restoredHouse.generateNaturalKey()
                    // 1-to-1 Mapping: Consume an existing ID if available for this specific key.
                    // This prevents multiple houses from the backup overwriting the same local record.
                    val existingId = localHouseGroups[key]?.removeFirstOrNull()?.id ?: 0
                    val finalAgentName = if (restoredHouse.agentName.isNotBlank()) restoredHouse.agentName else agentName
                    
                    housesToUpsert.add(restoredHouse.copy(
                        id = existingId,
                        agentName = finalAgentName.uppercase(),
                        agentUid = finalUid,
                        data = restoredHouse.data.replace("/", "-")
                    ))
                }

                houseDao.upsertHouses(housesToUpsert)
                dayActivityDao.upsertDayActivities(normalizedActivities)
                
                // Cleanup ghosts
                for ((key, matches) in localHouseGroups) {
                    if (matches.isNotEmpty()) {
                        val keptId = housesToUpsert.find { it.generateNaturalKey() == key }?.id
                        matches.forEach { house -> if (house.id != keptId && house.id != 0) houseDao.deleteHouse(house) }
                    }
                }
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Super Admin / Dynamic Configuration ---

    override suspend fun fetchBairros(): Result<List<String>> {
        return try {
            val snapshot = withTimeoutOrNull(1500) {
                firestore.collection("metadata").document("bairros")
                    .get().await()
            }
            
            if (snapshot == null) {
                return Result.success(com.antigravity.healthagent.utils.AppConstants.BAIRROS)
            }
            
            val bairros = (snapshot.get("names") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            Result.success(bairros.sorted())
        } catch (e: Exception) {
            android.util.Log.w("SyncRepository", "fetchBairros offline fallback: ${e.message}")
            Result.success(com.antigravity.healthagent.utils.AppConstants.BAIRROS)
        }
    }

    override suspend fun addBairro(name: String): Result<Unit> {
        return try {
            val normalizedName = name.trim()
            if (normalizedName.isBlank()) return Result.failure(Exception("Nome do bairro não pode ser vazio"))
            
            val docRef = firestore.collection("metadata").document("locations")
            val snapshot = docRef.get().await()
            val current = (snapshot.get("bairros") as? List<*>)?.filterIsInstance<String>()?.toMutableList() ?: mutableListOf()
            
            if (!current.contains(normalizedName)) {
                current.add(normalizedName)
                docRef.set(mapOf("bairros" to current.sorted())).await()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteBairro(name: String): Result<Unit> {
        return try {
            val docRef = firestore.collection("metadata").document("locations")
            val snapshot = docRef.get().await()
            val current = (snapshot.get("bairros") as? List<*>)?.filterIsInstance<String>()?.toMutableList() ?: mutableListOf()
            
            if (current.remove(name)) {
                docRef.set(mapOf("bairros" to current.sorted())).await()
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
                
            // 3. Record tombstone
            agentRef.update("deleted_activity_dates", com.google.firebase.firestore.FieldValue.arrayUnion(activityDate)).await()
            
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
                    naturalKey = naturalId
                )
            )
            syncScheduler.scheduleSync()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun recordActivityDeletion(date: String, agentName: String): Result<Unit> {
        return try {
            val upperName = agentName.trim().uppercase()
            val tombstone = "$date|$upperName"
            tombstoneDao.insertTombstone(
                com.antigravity.healthagent.data.local.model.Tombstone(
                    type = com.antigravity.healthagent.data.local.model.TombstoneType.ACTIVITY,
                    naturalKey = tombstone
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
                val houseTombstones = houseKeys.map { 
                    com.antigravity.healthagent.data.local.model.Tombstone(
                        type = com.antigravity.healthagent.data.local.model.TombstoneType.HOUSE,
                        naturalKey = it
                    )
                }
                val activityTombstones = activityDates.map { 
                    com.antigravity.healthagent.data.local.model.Tombstone(
                        type = com.antigravity.healthagent.data.local.model.TombstoneType.ACTIVITY,
                        naturalKey = it
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
                activityDates.chunked(400).forEach { chunk ->
                    val batch = firestore.batch()
                    chunk.forEach { tombstone ->
                        val dateKey = tombstone.split("|")[0]
                        batch.delete(docRef.collection("day_activities").document(dateKey))
                    }
                    batch.update(docRef, "deleted_activity_dates", com.google.firebase.firestore.FieldValue.arrayUnion(*chunk.toTypedArray()))
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
    
    private fun com.google.firebase.firestore.DocumentSnapshot.toHouseSafe(uid: String): House? {
        return try {
            val house = this.toObject(House::class.java) ?: return null
            val createdAtRaw = this.get("createdAt")
            val lastUpdatedRaw = this.get("lastUpdated")
            
            val createdAt = when(createdAtRaw) {
                is com.google.firebase.Timestamp -> createdAtRaw.toDate().time
                is Long -> createdAtRaw
                else -> house.createdAt
            }
            val lastUpdated = when(lastUpdatedRaw) {
                is com.google.firebase.Timestamp -> lastUpdatedRaw.toDate().time
                is Long -> lastUpdatedRaw
                else -> house.lastUpdated
            }
            house.copy(createdAt = createdAt, lastUpdated = lastUpdated, agentUid = uid)
        } catch (e: Exception) {
            android.util.Log.e("SyncRepository", "toHouseSafe: Error mapping ${this.id}", e)
            null
        }
    }

    private fun com.google.firebase.firestore.DocumentSnapshot.toDayActivitySafe(uid: String): DayActivity? {
        return try {
            val activity = this.toObject(DayActivity::class.java) ?: return null
            val lastUpdatedRaw = this.get("lastUpdated")
            
            val lastUpdated = when(lastUpdatedRaw) {
                is com.google.firebase.Timestamp -> lastUpdatedRaw.toDate().time
                is Long -> lastUpdatedRaw
                else -> activity.lastUpdated
            }
            activity.copy(lastUpdated = lastUpdated, agentUid = uid)
        } catch (e: Exception) {
            android.util.Log.e("SyncRepository", "toDayActivitySafe: Error mapping ${this.id}", e)
            null
        }
    }
}
