package com.antigravity.healthagent.data.repository

import com.antigravity.healthagent.domain.repository.AgentData
import com.antigravity.healthagent.domain.repository.AgentRepository
import com.antigravity.healthagent.data.util.toHouseSafe
import com.antigravity.healthagent.data.util.toDayActivitySafe
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentRepositoryImpl @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore
) : AgentRepository {

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
            val houses = agentRef.collection("houses").get().await()
            val activities = agentRef.collection("day_activities").get().await()
            
            val operations = (houses.documents + activities.documents).map { it.reference }.toMutableList()
            operations.add(agentRef)
            
            if (operations.isNotEmpty()) {
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
            val snapshot = firestore.collection("metadata").document("agent_info").get().await()
            val names = (snapshot.get("names") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            Result.success(names.map { it.uppercase().trim() }.sorted())
        } catch (e: Exception) {
            // Fallback to constants if needed (could be injected separately if strictly required)
            Result.success(com.antigravity.healthagent.utils.AppConstants.AGENT_NAMES)
        }
    }

    override suspend fun addAgentName(name: String): Result<Unit> {
        return try {
            val normalizedName = name.trim().uppercase()
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

    override suspend fun fetchAllAgentsData(sinceTimestamp: Long): Result<List<AgentData>> {
        return try {
            val agentsSnapshot = firestore.collection("agents").get().await()
            val allAgents = mutableListOf<AgentData>()
            val threshold = com.google.firebase.Timestamp(sinceTimestamp / 1000, ((sinceTimestamp % 1000) * 1000000).toInt())

            for (agentDoc in agentsSnapshot.documents) {
                var email = agentDoc.getString("email") ?: ""
                var agentName = agentDoc.getString("agentName")?.uppercase()
                val lastSyncTime = agentDoc.getLong("lastSyncTime") ?: 0L
                val lastSyncError = agentDoc.getString("lastSyncError")
                val uid = agentDoc.id

                if (email.isBlank() || email == "Unknown") {
                    email = try { firestore.collection("users").document(uid).get().await().getString("email") ?: "Unknown" } catch(e: Exception) { "Unknown" }
                }

                val houses = agentDoc.reference.collection("houses")
                    .let { if (sinceTimestamp > 0) it.whereGreaterThanOrEqualTo("lastUpdated", threshold) else it }
                    .get().await().documents.mapNotNull { it.toHouseSafe(uid) }

                val activities = agentDoc.reference.collection("day_activities")
                    .let { if (sinceTimestamp > 0) it.whereGreaterThanOrEqualTo("lastUpdated", threshold) else it }
                    .get().await().documents.mapNotNull { it.toDayActivitySafe(uid) }

                if (agentName.isNullOrBlank()) {
                    agentName = houses.firstOrNull { it.agentName.isNotBlank() }?.agentName?.uppercase()
                        ?: activities.firstOrNull { it.agentName.isNotBlank() }?.agentName?.uppercase()
                }

                allAgents.add(AgentData(uid, email, agentName, houses, activities, lastSyncTime, lastSyncError))
            }
            Result.success(allAgents)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteAgentHouse(uid: String, houseId: String): Result<Unit> {
        return try {
            val docRef = firestore.collection("agents").document(uid).collection("houses").document(houseId)
            docRef.delete().await()
            // Also add to cloud tombstone for this agent
            firestore.collection("agents").document(uid).update(
                "deleted_house_ids", com.google.firebase.firestore.FieldValue.arrayUnion(houseId)
            ).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteAgentActivity(uid: String, activityDate: String): Result<Unit> {
        return try {
            val dateKey = activityDate.replace("/", "-")
            val docRef = firestore.collection("agents").document(uid).collection("day_activities").document(dateKey)
            docRef.delete().await()
            // Also add to cloud tombstone for this agent
            firestore.collection("agents").document(uid).update(
                "deleted_activity_dates", com.google.firebase.firestore.FieldValue.arrayUnion(dateKey)
            ).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun clearSyncError(uid: String): Result<Unit> {
        return try {
            firestore.collection("agents").document(uid).update(
                "lastSyncError", com.google.firebase.firestore.FieldValue.delete()
            ).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
