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
            
            // Fetch existing data to avoid overwriting email with "Remote Sync"
            val existingDoc = try { userDocRef.get().await() } catch(e: Exception) { null }
            val existingEmail = existingDoc?.getString("email")
            val existingAgentName = existingDoc?.getString("agentName") ?: ""
            
            // IMPROVEMENT: If we have a targetUid (Admin mode), prioritize the existing email.
            // DO NOT use "Remote Sync" as a persistent value if we can avoid it.
            val userEmail = if (targetUid != null) {
                if (existingEmail.isNullOrBlank()) {
                    // Try to fetch email from 'users' collection if missing in 'agents'
                    try { 
                        firestore.collection("users").document(uid).get().await().getString("email") ?: "Remote Sync"
                    } catch(e: Exception) { "Remote Sync" }
                } else {
                    existingEmail
                }
            } else {
                auth.currentUser?.email ?: "Unknown Email"
            }
            
            var officialAgentName = existingAgentName.uppercase()

            // FETCH TOMBSTONES
            val deletedHouseIds = (existingDoc?.get("deleted_house_ids") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            val deletedActivityDates = (existingDoc?.get("deleted_activity_dates") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            
            val finalHouses = houses.filter { house -> 
                val naturalId = house.generateNaturalKey()
                // DEBT: If shouldReplace is true, we ignore tombstones because we are performing a full restoration.
                if (shouldReplace) return@filter true
                
                naturalId !in deletedHouseIds && house.data !in deletedActivityDates
            }
            val finalActivities = activities.filter { activity ->
                if (shouldReplace) return@filter true
                
                // Tombstones for activities can be just "date" or "date|agentName"
                val isTombstoned = deletedActivityDates.any { tombstone ->
                    if (tombstone.contains("|")) {
                        val parts = tombstone.split("|")
                        parts[0] == activity.date && parts[1].equals(officialAgentName, ignoreCase = true)
                    } else {
                        tombstone == activity.date
                    }
                }
                !isTombstoned
            }

            // IMPROVEMENT: If name is missing in cloud, try to recover it from the data itself
            if (officialAgentName.isBlank()) {
                val recoveredName = finalHouses.firstOrNull { it.agentName.isNotBlank() }?.agentName 
                    ?: finalActivities.firstOrNull { it.agentName.isNotBlank() }?.agentName
                
                if (!recoveredName.isNullOrBlank()) {
                    officialAgentName = recoveredName.uppercase()
                    android.util.Log.i("SyncRepository", "Recovering agent name '$officialAgentName' from data and updating cloud metadata")
                    try {
                        // Update both 'agents' and 'users' collections to ensure consistent attribution
                        val batch = firestore.batch()
                        batch.update(userDocRef, "agentName", officialAgentName)
                        
                        // Also update the users collection if the document exists
                        val userProfileRef = firestore.collection("users").document(uid)
                        batch.update(userProfileRef, "agentName", officialAgentName)
                        
                        batch.commit().await()
                    } catch (e: Exception) {
                        android.util.Log.e("SyncRepository", "Failed to update recovered agent name in cloud collections", e)
                        // Fallback: try individual update if batch fails (e.g. one doc doesn't exist)
                        try { userDocRef.update("agentName", officialAgentName).await() } catch(e2: Exception) {}
                    }
                }
            }

            if (shouldReplace) {
                // Surgical Restoration: Only wipe dates in the cloud that are present in the backup
                // or older than the latest backup date. This protects "future workdays".
                
                val backupDates = (finalHouses.map { it.data } + finalActivities.map { it.date })
                    .filter { it.isNotBlank() }
                    .toSet()
                
                val latestBackupDateTs = backupDates.map { 
                    try { SimpleDateFormat("dd-MM-yyyy", Locale.US).parse(it)?.time ?: 0L } catch(e: Exception) { 0L }
                }.maxOrNull() ?: 0L

                android.util.Log.i("SyncRepository", "Replacement requested. Surgical wipe for ${backupDates.size} dates up to $latestBackupDateTs")

                // 1. Clear houses subcollection for dates in backup
                val housesSnap = userDocRef.collection("houses").get().await()
                housesSnap.documents.chunked(400).forEach { chunk ->
                    val batch = firestore.batch()
                    chunk.forEach { doc ->
                        val houseDate = (doc.getString("data") ?: "").replace("/", "-")
                        
                        // ONLY delete if date matches a date present in the backup.
                        // We DO NOT wipe based on age anymore, to protect historical data not in the backup.
                        if (backupDates.contains(houseDate)) {
                            batch.delete(doc.reference)
                        }
                    }
                    batch.commit().await()
                }
                
                // 2. Clear activities subcollection for dates in backup
                val activitiesSnap = userDocRef.collection("day_activities").get().await()
                activitiesSnap.documents.chunked(400).forEach { chunk ->
                    val batch = firestore.batch()
                    chunk.forEach { doc ->
                        val activityDate = doc.id.replace("/", "-")
                        if (backupDates.contains(activityDate)) {
                            batch.delete(doc.reference)
                        }
                    }
                    batch.commit().await()
                }
            }

            val metadata = mutableMapOf<String, Any>(
                "lastSyncTime" to System.currentTimeMillis()
            )

            // If we are replacing everything, also clear the tombstones in the cloud 
            // so they don't block future restorations or syncs of the same data.
            if (shouldReplace) {
                metadata["deleted_house_ids"] = com.google.firebase.firestore.FieldValue.delete()
                metadata["deleted_activity_dates"] = com.google.firebase.firestore.FieldValue.delete()
            }
            // Only update email if it's sync'ing for SELF or if cloud email is missing AND we have a valid value.
            if (targetUid == null) {
                metadata["email"] = userEmail
            } else if (existingEmail.isNullOrBlank() && userEmail != "Remote Sync") {
                metadata["email"] = userEmail
            }

            // 1. Metadata op (stored in a map to keep it consistent with chunking pattern)
            val metadataOp = userDocRef to metadata

            // 2. Deduplicate Houses by Natural Multi-Field Key (Critical for restoration consistency)
            val houseOps = mutableMapOf<String, House>()
            val normalizedHouses = com.antigravity.healthagent.utils.HouseNormalizationUtils.normalizeHouses(finalHouses)
            
            normalizedHouses.forEach { house ->
                val normalizedHouse = house.copy(agentName = officialAgentName.uppercase())
                // Use a stable identifier to avoid duplicates between local DB (numeric IDs)
                // and restored backups (ID 0). Sanitized to avoid Firestore path issues.
                val naturalId = normalizedHouse.generateNaturalKey()
                val docId = naturalId
                
                houseOps[docId] = normalizedHouse
            }

            // 3. Deduplicate Activities by Date (prevents duplication with inconsistent names)
            val activityOps = mutableMapOf<String, DayActivity>()
            for (activity in finalActivities) {
                val normalizedActivity = activity.copy(agentName = officialAgentName.uppercase())
                // Use ONLY date as docId to ensure one entry per day per agent
                activityOps[normalizedActivity.date] = normalizedActivity
            }

            // Build the final list of operations
            val operations = mutableListOf<Pair<com.google.firebase.firestore.DocumentReference, Any>>()
            operations.add(metadataOp)
            
            for ((id, house) in houseOps) {
                operations.add(userDocRef.collection("houses").document(id) to house)
            }
            
            for ((date, activity) in activityOps) {
                operations.add(userDocRef.collection("day_activities").document(date) to activity)
            }

            android.util.Log.d("SyncRepository", "Starting sync: ${operations.size} operations")

            // Execute in chunks of 400 to stay well within the 500 limit
            operations.chunked(400).forEachIndexed { index, chunk ->
                val batch = firestore.batch()
                for ((docRef, data) in chunk) {
                    if (docRef == userDocRef) {
                        // Use update if document exists to avoid FieldValue.delete() errors with set(merge)
                        // documented in cloud logs. fallback to set(merge) only if first sync.
                        if (existingDoc?.exists() == true) {
                            @Suppress("UNCHECKED_CAST")
                            batch.update(docRef, data as Map<String, Any>)
                        } else {
                            batch.set(docRef, data, com.google.firebase.firestore.SetOptions.merge())
                        }
                    } else {
                        batch.set(docRef, data)
                    }
                }
                
                android.util.Log.d("SyncRepository", "Committing batch ${index + 1}/${(operations.size + 399) / 400}")
                
                // Increase timeout for large batches
                kotlinx.coroutines.withTimeout(30000L) {
                    batch.commit().await()
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
                val houses = housesSnapshot.toObjects(House::class.java)

                val activitiesSnapshot = agentDoc.reference.collection("day_activities").get().await()
                val activities = activitiesSnapshot.toObjects(DayActivity::class.java)

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
            val housesSnapshot = userDocRef.collection("houses").get().await()
            val houses = housesSnapshot.toObjects(House::class.java)
            val activitiesSnapshot = userDocRef.collection("day_activities").get().await()
            val activities = activitiesSnapshot.toObjects(DayActivity::class.java)

            // Strategy: If pulling for a DIFFERENT user (e.g. Admin editing someone else),
            // we MUST clear local data to avoid mixing.
            // If pulling for SELF, we use Upsert to protect unsynced local work.
            val isTargetDifferentUser = targetUid != null && targetUid != auth.currentUser?.uid
            
            database.withTransaction {
                if (isTargetDifferentUser) {
                    android.util.Log.i("SyncRepository", "Pulling for different user ($targetUid). Clearing local data first.")
                    houseDao.deleteAll()
                    dayActivityDao.deleteAll()
                } else {
                    // RECONCILE TOMBSTONES: If pulling for SELF, check if any synced items were deleted in cloud
                    val agentDoc = userDocRef.get().await()
                    val deletedHouseIds = (agentDoc.get("deleted_house_ids") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                    val deletedActivityDates = (agentDoc.get("deleted_activity_dates") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                    
                    if (deletedHouseIds.isNotEmpty() || deletedActivityDates.isNotEmpty()) {
                        android.util.Log.i("SyncRepository", "Reconciling tombstones on device: ${deletedHouseIds.size} houses, ${deletedActivityDates.size} activities")
                        
                        val agentNameFromCloud = houses.firstOrNull()?.agentName?.uppercase()
                            ?: activities.firstOrNull()?.agentName?.uppercase()
                            ?: auth.currentUser?.displayName?.uppercase()
                            ?: ""

                        deletedActivityDates.forEach { tombstone ->
                            val parts = tombstone.split("|")
                            val date = parts[0]
                            val agent = if (parts.size > 1) parts[1].uppercase() else agentNameFromCloud.uppercase()
                            
                            dayActivityDao.deleteDayActivity(date, agent)
                            houseDao.deleteHousesByDateAndAgent(date, agent)
                        }
                        
                        val allLocalHouses = houseDao.getAllHouses().first()
                        for (house in allLocalHouses) {
                            val naturalId = house.generateNaturalKey()
                            if (naturalId in deletedHouseIds) {
                                houseDao.deleteHouse(house)
                            }
                        }
                        
                        // Clear tombstones from cloud after successful application to phone
                        userDocRef.update(
                            "deleted_house_ids", com.google.firebase.firestore.FieldValue.delete(),
                            "deleted_activity_dates", com.google.firebase.firestore.FieldValue.delete()
                        ).await()
                    }
                }
                
                // Deduplicate Houses locally to prevent auto-generated ID duplicates
                val localHouses = houseDao.getAllHouses().first()
                val localHouseGroups = localHouses.groupBy { it.generateNaturalKey() }
                
                val idsToKeep = mutableSetOf<Int>()
                val housesToUpsert = mutableListOf<House>()

                val normalizedCloudHouses = com.antigravity.healthagent.utils.HouseNormalizationUtils.normalizeHouses(houses)

                normalizedCloudHouses.forEach { cloudHouse ->
                    val key = cloudHouse.generateNaturalKey()
                    val matches = localHouseGroups[key] ?: emptyList()
                    
                    val existingId = if (matches.isNotEmpty()) {
                        val chosen = matches.first()
                        idsToKeep.add(chosen.id)
                        chosen.id
                    } else 0
                    
                    housesToUpsert.add(cloudHouse.copy(
                        id = existingId,
                        agentName = cloudHouse.agentName.uppercase()
                    ))
                    
                    // Identify potential "ghost" duplicates to delete
                    if (matches.size > 1) {
                        val ghosts = matches.drop(1)
                        android.util.Log.w("SyncRepository", "Found ${matches.size} local matches for key $key. Marking ${ghosts.size} as ghosts.")
                        // We will delete these after upsert to be safe
                    }
                }

                val normalizedActivities = activities.map { activity ->
                    activity.copy(
                        date = activity.date.replace("/", "-"),
                        agentName = activity.agentName.uppercase()
                    )
                }

                houseDao.upsertHouses(housesToUpsert)
                dayActivityDao.upsertDayActivities(normalizedActivities)

                // Cleanup ghost duplicates: houses in local DB with same key but different IDs than the ones we kept
                if (!isTargetDifferentUser) {
                    for ((key, matches) in localHouseGroups) {
                        if (matches.size > 1) {
                            val keptIdForThisKey = housesToUpsert.find { it.generateNaturalKey() == key }?.id
                            matches.forEach { match ->
                                if (match.id != keptIdForThisKey && match.id != 0) {
                                    android.util.Log.i("SyncRepository", "Deleting ghost duplicate: ID ${match.id} for key $key")
                                    houseDao.deleteHouse(match)
                                }
                            }
                        }
                    }
                }
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            if (e.message?.contains("PERMISSION_DENIED", ignoreCase = true) == true) {
                Result.failure(Exception("Acesso negado ao baixar dados. Verifique se sua conta foi autorizada e se as regras do Firestore permitem a leitura."))
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
                "agentName" to agentName,
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
            val snapshot = firestore.collection("metadata").document("agent_info")
                .get().await()
            
            val names = (snapshot.get("names") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            Result.success(names.sorted())
        } catch (e: Exception) {
            // Fallback to AppConstants if Firestore metadata doesn't exist yet
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
            val doc = firestore.collection("metadata").document("locations").get().await()
            val bairros = (doc.get("bairros") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            if (bairros.isEmpty()) {
                // Return default from AppConstants if cloud is empty
                Result.success(com.antigravity.healthagent.utils.AppConstants.BAIRROS)
            } else {
                Result.success(bairros.sorted())
            }
        } catch (e: Exception) {
            // Fallback
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
            val doc = firestore.collection("metadata").document("settings").get().await()
            Result.success(doc.data ?: emptyMap())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateSystemSetting(key: String, value: Any): Result<Unit> {
        return try {
            firestore.collection("metadata").document("settings")
                .update(key, value)
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            // If document doesn't exist, create it
            try {
                firestore.collection("metadata").document("settings")
                    .set(mapOf(key to value))
                    .await()
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
            val uid = auth.currentUser?.uid ?: return Result.failure(Exception("User not authenticated"))
            val naturalId = house.generateNaturalKey()
            firestore.collection("agents").document(uid)
                .update("deleted_house_ids", com.google.firebase.firestore.FieldValue.arrayUnion(naturalId))
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun recordActivityDeletion(date: String, agentName: String): Result<Unit> {
        return try {
            val uid = auth.currentUser?.uid ?: return Result.failure(Exception("User not authenticated"))
            // Note: We use the date as the key for activity tombstones. 
            // If multiple agents exist in the same account (legacy), this wipes the date for all local entries.
            val tombstone = "$date|$agentName"
            firestore.collection("agents").document(uid)
                .update("deleted_activity_dates", com.google.firebase.firestore.FieldValue.arrayUnion(tombstone))
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun recordBulkDeletions(houseKeys: List<String>, activityDates: List<String>): Result<Unit> {
        return try {
            val uid = auth.currentUser?.uid ?: return Result.failure(Exception("User not authenticated"))
            val docRef = firestore.collection("agents").document(uid)

            // Firestore limits arrayUnion/update to some extent, but mainly chunking for safety 
            // and to avoid payload size limits if lists are massive.
            if (houseKeys.isNotEmpty()) {
                houseKeys.chunked(400).forEach { chunk ->
                    docRef.update("deleted_house_ids", com.google.firebase.firestore.FieldValue.arrayUnion(*chunk.toTypedArray())).await()
                }
            }

            if (activityDates.isNotEmpty()) {
                activityDates.chunked(400).forEach { chunk ->
                    docRef.update("deleted_activity_dates", com.google.firebase.firestore.FieldValue.arrayUnion(*chunk.toTypedArray())).await()
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
