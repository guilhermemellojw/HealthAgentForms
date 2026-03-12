package com.antigravity.healthagent.data.repository

import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.domain.repository.AgentData
import com.antigravity.healthagent.domain.repository.SyncRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncRepositoryImpl @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val houseDao: com.antigravity.healthagent.data.local.dao.HouseDao,
    private val dayActivityDao: com.antigravity.healthagent.data.local.dao.DayActivityDao
) : SyncRepository {

    override suspend fun pushLocalDataToCloud(
        houses: List<House>,
        activities: List<DayActivity>,
        targetUid: String?,
        shouldReplace: Boolean
    ): Result<Unit> {
        return try {
            val uid = targetUid ?: auth.currentUser?.uid ?: return Result.failure(Exception("User not authenticated"))
            val userEmail = if (targetUid != null) "Remote Sync" else (auth.currentUser?.email ?: "Unknown Email")

            val userDocRef = firestore.collection("agents").document(uid)
            
            // Re-fetch official agent name to ensure consistency and prevent duplications
            val agentDoc = try { 
                userDocRef.get().await() 
            } catch(e: Exception) { 
                android.util.Log.e("SyncRepository", "Failed to fetch agent doc for name normalization", e)
                null 
            }
            
            var officialAgentName = agentDoc?.getString("agentName") ?: ""

            // IMPROVEMENT: If name is missing in cloud, try to recover it from the data itself
            if (officialAgentName.isBlank()) {
                val recoveredName = houses.firstOrNull { it.agentName.isNotBlank() }?.agentName 
                    ?: activities.firstOrNull { it.agentName.isNotBlank() }?.agentName
                
                if (!recoveredName.isNullOrBlank()) {
                    officialAgentName = recoveredName
                    android.util.Log.i("SyncRepository", "Recovering agent name '$officialAgentName' from data and updating cloud metadata")
                    try {
                        userDocRef.update("agentName", officialAgentName).await()
                    } catch (e: Exception) {
                        android.util.Log.e("SyncRepository", "Failed to update recovered agent name in cloud", e)
                    }
                }
            }

            if (shouldReplace) {
                android.util.Log.i("SyncRepository", "Replacement requested. Clearing existing subcollections before push.")
                // 1. Clear houses subcollection
                val housesSnap = userDocRef.collection("houses").get().await()
                housesSnap.documents.chunked(400).forEach { chunk ->
                    val batch = firestore.batch()
                    chunk.forEach { batch.delete(it.reference) }
                    batch.commit().await()
                }
                
                // 2. Clear activities subcollection
                val activitiesSnap = userDocRef.collection("day_activities").get().await()
                activitiesSnap.documents.chunked(400).forEach { chunk ->
                    val batch = firestore.batch()
                    chunk.forEach { batch.delete(it.reference) }
                    batch.commit().await()
                }
            }

            val metadata = mapOf(
                "email" to userEmail,
                "lastSyncTime" to System.currentTimeMillis()
            )

            // 1. Metadata op (stored in a map to keep it consistent with chunking pattern)
            val metadataOp = userDocRef to metadata

            // 2. Deduplicate Houses by ID
            val houseOps = mutableMapOf<String, House>()
            for (house in houses) {
                val normalizedHouse = house.copy(agentName = officialAgentName)
                houseOps[normalizedHouse.id.toString()] = normalizedHouse
            }

            // 3. Deduplicate Activities by Date (prevents duplication with inconsistent names)
            val activityOps = mutableMapOf<String, DayActivity>()
            for (activity in activities) {
                val normalizedActivity = activity.copy(agentName = officialAgentName)
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
                    batch.set(docRef, data)
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
                val email = agentDoc.getString("email") ?: "Unknown"
                val agentName = agentDoc.getString("agentName")
                val lastSyncTime = agentDoc.getLong("lastSyncTime") ?: 0L
                val uid = agentDoc.id

                val housesSnapshot = agentDoc.reference.collection("houses").get().await()
                val houses = housesSnapshot.toObjects(House::class.java)

                val activitiesSnapshot = agentDoc.reference.collection("day_activities").get().await()
                val activities = activitiesSnapshot.toObjects(DayActivity::class.java)

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
            
            // Fix: Always replace local data to avoid "ghost data" from previous users
            houseDao.replaceHouses(houses)
            dayActivityDao.replaceDayActivities(activities)
            
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

            val docId = "pre_${normalizedEmail.hashCode()}"
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
            
            val names = snapshot.get("names") as? List<String> ?: emptyList()
            Result.success(names.sorted())
        } catch (e: Exception) {
            // Fallback to AppConstants if Firestore metadata doesn't exist yet
            Result.success(com.antigravity.healthagent.utils.AppConstants.AGENT_NAMES)
        }
    }

    override suspend fun addAgentName(name: String): Result<Unit> {
        return try {
            val normalizedName = name.trim()
            if (normalizedName.isBlank()) return Result.failure(Exception("Nome não pode ser vazio"))

            val docRef = firestore.collection("metadata").document("agent_info")
            val snapshot = docRef.get().await()
            
            val currentNames = (snapshot.get("names") as? List<String> ?: emptyList()).toMutableList()
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
            
            val currentNames = (snapshot.get("names") as? List<String> ?: emptyList()).toMutableList()
            if (currentNames.remove(name)) {
                docRef.set(mapOf("names" to currentNames.sorted())).await()
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
