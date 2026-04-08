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
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
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
                // This prevents "Full Sync" from wiping historical data not present in the current session.
                val backupDates = (housesToPush.map { it.data } + activitiesToPush.map { it.date }).toSet()
                
                if (backupDates.isNotEmpty()) {
                    val existingHouses = userDocRef.collection("houses").get().await()
                    val existingActivities = userDocRef.collection("day_activities").get().await()
                    
                    val toDelete = (existingHouses.documents.filter { it.getString("data")?.replace("/", "-") in backupDates } +
                                  existingActivities.documents.filter { it.getString("date")?.replace("/", "-") in backupDates })
                                  .map { it.reference }

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

            val operations = mutableListOf<Pair<com.google.firebase.firestore.DocumentReference, Any>>()
            
            // Houses
            // 1. Convert Houses to Firestore Maps (Using existing VisitSegments for Natural Key stability)
            if (housesToPush.isNotEmpty()) {
                housesToPush.forEach { house ->
                    // Consistency: Use official agent name for both the Natural Key and the document content.
                    // This ensures that even if local agentName was slightly different, the cloud ID remains stable and matches the pulled content.
                    val officialHouse = house.copy(agentName = officialAgentName ?: house.agentName)
                    val houseData = officialHouse.toFirestoreMap()
                    
                    operations.add(userDocRef.collection("houses").document(officialHouse.generateNaturalKey()) to houseData)
                }
                
                // CRITICAL recovery: If we are pushing a house, ensure its natural key is NOT in the deleted_house_ids array anymore.
                // This prevents re-added houses from being immediately deleted by stale cloud tombstones on other devices (or on re-pull).
                if (!shouldReplace) {
                    val pushedKeys = housesToPush.map { it.generateNaturalKey() }.distinct()
                    operations.add(userDocRef to mapOf("deleted_house_ids" to com.google.firebase.firestore.FieldValue.arrayRemove(*pushedKeys.toTypedArray())))
                }
            }

            // Activities
            if (activitiesToPush.isNotEmpty()) {
                activitiesToPush.forEach { activity ->
                    val activityData = activity.copy(agentName = officialAgentName, agentUid = uid).toFirestoreMap()
                    operations.add(userDocRef.collection("day_activities").document(activity.date.replace("/", "-")) to activityData)
                }
                
                if (!shouldReplace) {
                    val pushedDates = activitiesToPush.map { it.date.replace("/", "-") }.distinct()
                    operations.add(userDocRef to mapOf("deleted_activity_dates" to com.google.firebase.firestore.FieldValue.arrayRemove(*pushedDates.toTypedArray())))
                }
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
            if (cloudDeletedHouses.isNotEmpty()) {
                 operations.add(userDocRef to mapOf("deleted_house_ids" to com.google.firebase.firestore.FieldValue.arrayUnion(*cloudDeletedHouses.toTypedArray())))
            }
            if (cloudDeletedActivities.isNotEmpty()) {
                 operations.add(userDocRef to mapOf("deleted_activity_dates" to com.google.firebase.firestore.FieldValue.arrayUnion(*cloudDeletedActivities.toTypedArray())))
            }
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

    override suspend fun fetchAllAgentsData(sinceTimestamp: Long): Result<List<AgentData>> {
        return try {
            val agentsSnapshot = firestore.collection("agents").get().await()
            val allAgents = mutableListOf<AgentData>()
            
            val timestampThreshold = com.google.firebase.Timestamp(sinceTimestamp / 1000, ((sinceTimestamp % 1000) * 1000000).toInt())

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

                val housesQuery = if (sinceTimestamp > 0) {
                    agentDoc.reference.collection("houses").whereGreaterThanOrEqualTo("lastUpdated", timestampThreshold)
                } else {
                    agentDoc.reference.collection("houses")
                }
                val housesSnapshot = housesQuery.get().await()
                val houses = housesSnapshot.documents.mapNotNull { it.toHouseSafe(uid) }

                val activitiesQuery = if (sinceTimestamp > 0) {
                    agentDoc.reference.collection("day_activities").whereGreaterThanOrEqualTo("lastUpdated", timestampThreshold)
                } else {
                    agentDoc.reference.collection("day_activities")
                }
                val activitiesSnapshot = activitiesQuery.get().await()
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
      override suspend fun pullCloudDataToLocal(targetUid: String?, force: Boolean): Result<Unit> {
        return try {
            val uid = targetUid ?: auth.currentUser?.uid ?: return Result.failure(Exception("Not logged in"))
            val isTargetDifferentUser = targetUid != null && targetUid != auth.currentUser?.uid
            
            // 1. Basic Setup
            val userDoc = firestore.collection("users").document(uid).get().await()
            val email = userDoc.getString("email") ?: auth.currentUser?.email ?: return Result.failure(Exception("User email not found"))
            val profileAgentName = userDoc.getString("agentName")?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
            val profileDisplayName = userDoc.getString("displayName")?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
            val finalAgentName = profileAgentName ?: profileDisplayName ?: email.substringBefore("@").uppercase()
            
            // BUG FIX: Detect if lastSync is in the future (Device clock skew) and reset to 0 to force recovery
            val cachedLastSync = if (isTargetDifferentUser || force) 0L else settingsManager.lastSyncTimestamp.first()
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

            for (agentDocRef in possibleAgentDocs) {
                val agentDoc = try { agentDocRef.get().await() } catch(e: Exception) { null }
                val housesCollection = agentDocRef.collection("houses")
                val activitiesCollection = agentDocRef.collection("day_activities")

                val houses = if (lastSync > 0) {
                    housesCollection.whereGreaterThan("lastUpdated", com.google.firebase.Timestamp(lastSync / 1000, ((lastSync % 1000) * 1000000).toInt()))
                        .get().await()
                        .documents.mapNotNull { it.toHouseSafe(uid, finalAgentName) }
                } else {
                    housesCollection.get().await()
                        .documents.mapNotNull { it.toHouseSafe(uid, finalAgentName) }
                }

                val activities = if (lastSync > 0) {
                    activitiesCollection.whereGreaterThan("lastUpdated", com.google.firebase.Timestamp(lastSync / 1000, ((lastSync % 1000) * 1000000).toInt()))
                        .get().await()
                        .documents.mapNotNull { it.toDayActivitySafe(uid, finalAgentName) }
                } else {
                    activitiesCollection.get().await()
                        .documents.mapNotNull { it.toDayActivitySafe(uid, finalAgentName) }
                }

                // Discovery of Deletions from Metadata Arrays (Correct way)
                @Suppress("UNCHECKED_CAST")
                val deletedHouses = (agentDoc?.get("deleted_house_ids") as? List<String> ?: emptyList())
                    .map { it.replace("/", "-") }
                @Suppress("UNCHECKED_CAST")
                val deletedActivities = (agentDoc?.get("deleted_activity_dates") as? List<String> ?: emptyList())
                    .map { it.replace("/", "-") }
                
                cloudHouses.addAll(houses)
                cloudDayActivities.addAll(activities)
                cloudDeletedHouses.addAll(deletedHouses)
                cloudDeletedActivities.addAll(deletedActivities)
            }

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
                val dateKey = "${house.data}|${house.agentName.uppercase()}"
                
                // BUG FIX: Only delete local houses if they are already marked as synced. 
                // If isSynced=false, it means the local state is newer/different from the cloud's awareness of it, 
                // or it's a re-added house. We should never delete unsynced/new local houses via cloud tombstones.
                key in cloudDeletedHouses && house.isSynced && dateKey !in lockedKeys
            }

            val allLocalActivities = dayActivityDao.getAllDayActivities(finalAgentName, uid)
            val activitiesToDelete = allLocalActivities.filter { activity ->
                val dateKey = "${activity.date}|${activity.agentName.uppercase()}"
                // Consistency check: also guard activity deletions with isSynced
                dateKey in cloudDeletedActivities && activity.isSynced && dateKey !in lockedKeys
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
                    val dateKey = "${cloudHouse.data}|${finalAgentName.uppercase()}"
                    
                    if (dateKey in lockedKeys) {
                        return@mapNotNull null
                    }

                    val existing = localHouses[key]?.firstOrNull()
                    if (existing != null && !existing.isSynced && existing.lastUpdated >= cloudHouse.lastUpdated) {
                        return@mapNotNull null
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
                        agentUid = uid, // Ensure UID is enforced even if derived from cloudHouse
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
                    if (dateKey in lockedKeys) {
                         return@mapNotNull null
                    }

                    if (existing != null && !existing.isSynced && existing.lastUpdated >= activity.lastUpdated) {
                        return@mapNotNull null
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
            Result.success(names.map { it.uppercase().trim() }.sorted())
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
                    it.copy(
                        agentName = finalAgentName.uppercase(),
                        agentUid = finalUid,
                        date = it.date.replace("/", "-")
                    ) 
                }
                val housesToUpsert = mutableListOf<House>()

                houses.forEach { restoredHouse ->
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

    // --- Super Admin / Dynamic Configuration ---

    override suspend fun fetchBairros(): Result<List<String>> {
        return try {
            val snapshot = withTimeoutOrNull(2500) {
                firestore.collection("metadata").document("locations")
                    .get().await()
            }
            
            if (snapshot == null || !snapshot.exists()) {
                return Result.success(com.antigravity.healthagent.utils.AppConstants.BAIRROS)
            }
            
            val bairros = (snapshot.get("bairros") as? List<*>)?.filterIsInstance<String>()?.filter { it.isNotBlank() } ?: emptyList()
            
            if (bairros.isEmpty()) {
                Result.success(com.antigravity.healthagent.utils.AppConstants.BAIRROS)
            } else {
                Result.success(bairros.map { it.uppercase().trim() }.sorted())
            }
        } catch (e: Exception) {
            android.util.Log.w("SyncRepository", "fetchBairros offline fallback: ${e.message}")
            Result.success(com.antigravity.healthagent.utils.AppConstants.BAIRROS)
        }
    }

    override suspend fun addBairro(name: String): Result<Unit> {
        return try {
            val normalizedName = name.trim().uppercase()
            if (normalizedName.isBlank()) return Result.failure(Exception("Nome do bairro não pode ser vazio"))
            
            val docRef = firestore.collection("metadata").document("locations")
            val snapshot = docRef.get().await()
            
            // If the document doesn't exist or is empty, we SEED it with the current built-in list + the new one
            val current = (snapshot.get("bairros") as? List<*>)?.filterIsInstance<String>()?.toMutableList() 
                ?: com.antigravity.healthagent.utils.AppConstants.BAIRROS.toMutableList()
            
            if (!current.contains(normalizedName)) {
                current.add(normalizedName)
                docRef.set(mapOf("bairros" to current.map { it.uppercase().trim() }.sorted())).await()
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
            
            // If we are deleting from something that doesn't exist in cloud yet, we start from built-in list
            val current = (snapshot.get("bairros") as? List<*>)?.filterIsInstance<String>()?.toMutableList()
                ?: com.antigravity.healthagent.utils.AppConstants.BAIRROS.toMutableList()
            
            if (current.remove(name.trim().uppercase())) {
                docRef.set(mapOf("bairros" to current.map { it.uppercase().trim() }.sorted())).await()
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
    
    private fun com.google.firebase.firestore.DocumentSnapshot.toHouseSafe(uid: String, agentName: String = ""): House? {
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

            // --- CRITICAL FIX: Explicitly map sequence and complement ---
            // Firestore often stores numbers as Long, causing toObject() to ignore Int fields.
            // This was causing all sequences/complements to become 0, leading to Natural Key collisions.
            val finalSequence = (this.get("sequence") as? Long)?.toInt() ?: house.sequence
            val finalComplement = (this.get("complement") as? Long)?.toInt() ?: house.complement

            // CRITICAL: Enforce the standardized agentName and Uid from the session/profile
            // Also ensure Municipality and Bairro are not blank to prevent future deadlocks
            val finalAgentName = (if (agentName.isNotBlank()) agentName else (house.agentName.ifBlank { "" })).trim().uppercase()
            val finalMunicipio = if (house.municipio.isBlank()) "BOM JARDIM" else house.municipio
            val finalBairro = if (house.bairro.isBlank()) "" else house.bairro
            
            house.copy(
                sequence = finalSequence,
                complement = finalComplement,
                data = house.data.replace("/", "-"),
                createdAt = createdAt, 
                lastUpdated = lastUpdated, 
                agentUid = uid, 
                agentName = finalAgentName,
                municipio = finalMunicipio,
                bairro = finalBairro
            )
        } catch (e: Exception) {
            android.util.Log.e("SyncRepository", "toHouseSafe: Error mapping ${this.id}", e)
            null
        }
    }

    private fun com.google.firebase.firestore.DocumentSnapshot.toDayActivitySafe(uid: String, agentName: String = ""): DayActivity? {
        return try {
            val activity = this.toObject(DayActivity::class.java) ?: return null
            val lastUpdatedRaw = this.get("lastUpdated")
            
            val lastUpdated = when(lastUpdatedRaw) {
                is com.google.firebase.Timestamp -> lastUpdatedRaw.toDate().time
                is Long -> lastUpdatedRaw
                else -> activity.lastUpdated
            }
            // CRITICAL: Robust Boolean Mapping for Firestore/Kotlin "is" prefix issues
            // This ensures isClosed and isManualUnlock are correctly captured even if toObject fails to map them.
            val isClosed = this.getBoolean("isClosed") ?: activity.isClosed
            val isManualUnlock = this.getBoolean("isManualUnlock") ?: activity.isManualUnlock

            // CRITICAL: Explicit Status Mapping to avoid serialization gaps
            val finalStatus = this.getString("status") ?: activity.status

            // CRITICAL: Enforce the standardized agentName and Uid from the session/profile
            val finalAgentName = (if (agentName.isNotBlank()) agentName else (activity.agentName.ifBlank { "" })).trim().uppercase()
            activity.copy(
                status = finalStatus,
                date = activity.date.replace("/", "-"),
                isClosed = isClosed,
                isManualUnlock = isManualUnlock,
                lastUpdated = lastUpdated, 
                agentUid = uid, 
                agentName = finalAgentName
            )

        } catch (e: Exception) {
            android.util.Log.e("SyncRepository", "toDayActivitySafe: Error mapping ${this.id}", e)
            null
        }
    }
}
