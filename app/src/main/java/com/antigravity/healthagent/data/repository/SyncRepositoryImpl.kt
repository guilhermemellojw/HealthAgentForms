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
    private val database: com.antigravity.healthagent.data.local.AppDatabase
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
            val unsyncedHouses = houseDao.getUnsyncedHouses()
            val unsyncedActivities = dayActivityDao.getUnsyncedActivities()
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
                val activityData = activity.copy(agentName = officialAgentName).toFirestoreMap()
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
            operations.chunked(400).forEach { chunk ->
                val batch = firestore.batch()
                chunk.forEach { (ref, data) ->
                    when (data) {
                        is com.google.firebase.firestore.FieldValue -> batch.delete(ref)
                        is Map<*, *> -> {
                            // Using set(merge=true) for both metadata and data documents to handle missing/new docs gracefully.
                            @Suppress("UNCHECKED_CAST")
                            batch.set(ref, data as Map<String, Any>, com.google.firebase.firestore.SetOptions.merge())
                        }
                    }
                }
                kotlinx.coroutines.withTimeout(60000L) { batch.commit().await() }
            }

            // Post-Sync Success: Update local state
            if (!shouldReplace) {
                database.withTransaction {
                    if (unsyncedHouses.isNotEmpty()) houseDao.markAsSynced(unsyncedHouses.map { it.id })
                    if (unsyncedActivities.isNotEmpty()) dayActivityDao.markAsSynced(unsyncedActivities.map { it.date }, officialAgentName)
                    if (tombstones.isNotEmpty()) tombstoneDao.deleteTombstones(tombstones.map { it.id })
                }
            }

            Result.success(Unit)
        } catch (e: com.google.firebase.firestore.FirebaseFirestoreException) {
            android.util.Log.e("SyncRepository", "Firestore error: ${e.code}, ${e.message}", e)
            if (e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                Result.failure(Exception("Acesso negado ao sincronizar. Verifique se sua conta foi autorizada e se as regras de segurança do Firestore permitem a escrita."))
            } else {
                Result.failure(e)
            }
        } catch (e: Exception) {
            android.util.Log.e("SyncRepository", "General sync error: ${e.message}", e)
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
                val uid = agentDoc.id

                // If email is missing, try to fetch it from the 'users' collection to avoid "Unknown" cards
                if (email.isBlank() || email == "Unknown") {
                    email = try { 
                        firestore.collection("users").document(uid).get().await().getString("email") ?: "Unknown"
                    } catch(e: Exception) { "Unknown" }
                }

                val housesSnapshot = agentDoc.reference.collection("houses").get().await()
                val houses = housesSnapshot.documents.mapNotNull { it.toHouseSafe() }

                val activitiesSnapshot = agentDoc.reference.collection("day_activities").get().await()
                val activities = activitiesSnapshot.documents.mapNotNull { it.toDayActivitySafe() }

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
                        lastSyncTime = lastSyncTime
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
            val lastSync = if (isTargetDifferentUser) 0L else settingsManager.lastSyncTimestamp.first()
            
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
            val housesDelta = housesQuery.get().await().documents.mapNotNull { it.toHouseSafe() }
            
            val activitiesQuery = if (lastSync == 0L) {
                userDocRef.collection("day_activities")
            } else {
                userDocRef.collection("day_activities")
                    .whereGreaterThan("lastUpdated", com.google.firebase.Timestamp(lastSync / 1000, ((lastSync % 1000) * 1000000).toInt()))
            }
            val activitiesDelta = activitiesQuery.get().await().documents.mapNotNull { it.toDayActivitySafe() }

            // 4. Fetch Agent Doc for Tombstones and Server Time
            val agentDoc = userDocRef.get().await()
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
                        val allLocalHouses = houseDao.getAllHouses().first()
                        allLocalHouses.forEach { house ->
                            if (house.generateNaturalKey() in cloudDeletedHouses) houseDao.deleteHouse(house)
                        }
                        
                        cloudDeletedActivities.forEach { tombstone ->
                            val parts = tombstone.split("|")
                            val date = parts[0]
                            val agentName = if (parts.size > 1) parts[1] else agentDoc.getString("agentName") ?: ""
                            dayActivityDao.deleteDayActivity(date, agentName)
                        }
                        
                        // Clear cloud tombstones once applied locally
                        userDocRef.update(
                            "deleted_house_ids", com.google.firebase.firestore.FieldValue.delete(),
                            "deleted_activity_dates", com.google.firebase.firestore.FieldValue.delete()
                        ).await()
                    }
                }

                // 5. Apply Delta Locally
                val currentAgentName = agentDoc.getString("agentName") ?: ""
                
                // Propagate agentName to 'users' collection so AuthRepository emits the updated name
                if (currentAgentName.isNotBlank() && !isTargetDifferentUser) {
                    try {
                        firestore.collection("users").document(uid)
                            .update("agentName", currentAgentName.uppercase())
                            .await()
                    } catch (e: Exception) {
                        android.util.Log.e("SyncRepository", "Failed to propagate agentName to user doc", e)
                    }
                }
                
                val localHouses = houseDao.getAllHouses().first().groupBy { it.generateNaturalKey() }
                val housesToUpsert = housesDelta.map { cloudHouse ->
                    val key = cloudHouse.generateNaturalKey()
                    val existing = localHouses[key]?.firstOrNull()
                    cloudHouse.copy(
                        id = existing?.id ?: 0,
                        agentName = currentAgentName.uppercase(),
                        isSynced = true // Pulled data is synced
                    )
                }
                
                val activitiesToUpsert = activitiesDelta.map { activity ->
                    activity.copy(
                        date = activity.date.replace("/", "-"),
                        agentName = currentAgentName.uppercase(),
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
            val snapshot = withTimeoutOrNull(2500) {
                firestore.collection("metadata").document("agent_info")
                    .get().await()
            }
            
            if (snapshot == null) {
                return Result.success(com.antigravity.healthagent.utils.AppConstants.AGENT_NAMES)
            }
            
            val names = (snapshot.get("names") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            Result.success(names.sorted())
        } catch (e: Exception) {
            // Fallback to AppConstants if Firestore metadata doesn't exist yet or is unreachable
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

    override suspend fun restoreLocalData(agentName: String, houses: List<House>, activities: List<DayActivity>): Result<Unit> {
        return try {
            database.withTransaction {
                // Deduplicate Houses locally to prevent auto-generated ID duplicates
                val localHouses = houseDao.getAllHouses().first()
                val localHouseGroups = localHouses.groupBy { it.generateNaturalKey() }
                
                val normalizedActivities = activities.map { 
                    val finalAgentName = if (it.agentName.isNotBlank()) it.agentName else agentName
                    it.copy(
                        agentName = finalAgentName.uppercase(),
                        date = it.date.replace("/", "-")
                    ) 
                }
                val housesToUpsert = mutableListOf<House>()
                val normalizedHouses = com.antigravity.healthagent.utils.HouseNormalizationUtils.normalizeHouses(houses)

                normalizedHouses.forEach { restoredHouse ->
                    val key = restoredHouse.generateNaturalKey()
                    val existingId = localHouseGroups[key]?.firstOrNull()?.id ?: 0
                    val finalAgentName = if (restoredHouse.agentName.isNotBlank()) restoredHouse.agentName else agentName
                    
                    housesToUpsert.add(restoredHouse.copy(
                        id = existingId,
                        agentName = finalAgentName.uppercase(),
                        data = restoredHouse.data.replace("/", "-")
                    ))
                }

                houseDao.upsertHouses(housesToUpsert)
                dayActivityDao.upsertDayActivities(normalizedActivities)
                
                // Cleanup ghosts
                for ((key, matches) in localHouseGroups) {
                    if (matches.size > 1) {
                        val keptId = housesToUpsert.find { it.generateNaturalKey() == key }?.id
                        matches.forEach { if (it.id != keptId && it.id != 0) houseDao.deleteHouse(it) }
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
                firestore.collection("metadata").document("bairros")
                    .get().await()
            }
            
            if (snapshot == null) {
                return Result.success(com.antigravity.healthagent.utils.AppConstants.BAIRROS)
            }
            
            val bairros = (snapshot.get("names") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            Result.success(bairros.sorted())
        } catch (e: Exception) {
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
            val snapshot = withTimeoutOrNull(2500) {
                firestore.collection("metadata").document("system_settings")
                    .get().await()
            }
            
            if (snapshot == null) return Result.success(emptyMap<String, Any>())
            
            val settings = snapshot.data ?: emptyMap<String, Any>()
            Result.success(settings)
        } catch (e: Exception) {
            Result.failure(e)
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
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun recordActivityDeletion(date: String, agentName: String): Result<Unit> {
        return try {
            val tombstone = "$date|$agentName"
            tombstoneDao.insertTombstone(
                com.antigravity.healthagent.data.local.model.Tombstone(
                    type = com.antigravity.healthagent.data.local.model.TombstoneType.ACTIVITY,
                    naturalKey = tombstone
                )
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun recordBulkDeletions(houseKeys: List<String>, activityDates: List<String>, targetUid: String?): Result<Unit> {
        return try {
            val uid = targetUid ?: auth.currentUser?.uid ?: return Result.failure(Exception("User not authenticated"))
            val docRef = firestore.collection("agents").document(uid)

            // Prepare local batch for actual document deletions
            if (houseKeys.isNotEmpty()) {
                houseKeys.chunked(400).forEach { chunk ->
                    val batch = firestore.batch()
                    chunk.forEach { key ->
                        batch.delete(docRef.collection("houses").document(key))
                    }
                    // Record tombstones in metadata
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
                    // Record tombstones in metadata
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

    private fun com.google.firebase.firestore.DocumentSnapshot.toHouseSafe(): House? {
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
            house.copy(createdAt = createdAt, lastUpdated = lastUpdated)
        } catch (e: Exception) {
            android.util.Log.e("SyncRepository", "toHouseSafe: Error mapping ${this.id}", e)
            null
        }
    }

    private fun com.google.firebase.firestore.DocumentSnapshot.toDayActivitySafe(): DayActivity? {
        return try {
            val activity = this.toObject(DayActivity::class.java) ?: return null
            val lastUpdatedRaw = this.get("lastUpdated")
            
            val lastUpdated = when(lastUpdatedRaw) {
                is com.google.firebase.Timestamp -> lastUpdatedRaw.toDate().time
                is Long -> lastUpdatedRaw
                else -> activity.lastUpdated
            }
            activity.copy(lastUpdated = lastUpdated)
        } catch (e: Exception) {
            android.util.Log.e("SyncRepository", "toDayActivitySafe: Error mapping ${this.id}", e)
            null
        }
    }
}
