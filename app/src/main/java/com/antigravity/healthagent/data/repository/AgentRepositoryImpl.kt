package com.antigravity.healthagent.data.repository

import com.antigravity.healthagent.domain.repository.AgentData
import com.antigravity.healthagent.domain.repository.AgentRepository
import com.antigravity.healthagent.data.util.toHouseSafe
import com.antigravity.healthagent.data.util.toDayActivitySafe
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
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

    override suspend fun fetchAllAgentsData(sinceTimestamp: Long, untilTimestamp: Long, datePattern: String?): Result<List<AgentData>> = coroutineScope {
        try {
            val startTime = System.currentTimeMillis()
            val agentsSnapshot = firestore.collection("agents").get().await()
            val sinceThreshold = com.google.firebase.Timestamp(sinceTimestamp / 1000, ((sinceTimestamp % 1000) * 1000000).toInt())

            val agentDeferred = agentsSnapshot.documents.map { agentDoc ->
                async {
                    val uid = agentDoc.id
                    var email = agentDoc.getString("email") ?: "Unknown"
                    var agentName = agentDoc.getString("agentName")?.uppercase()
                    val lastSyncTime = agentDoc.getLong("lastSyncTime") ?: 0L
                    val lastSyncError = agentDoc.getString("lastSyncError")
                    var photoUrl = agentDoc.getString("photoUrl")
                    
                    // Parallel Metadata Fallback
                    if (email == "Unknown" || photoUrl == null || agentName == null) {
                        try {
                            val userDoc = firestore.collection("users").document(uid).get().await()
                            if (userDoc.exists()) {
                                if (email == "Unknown") email = userDoc.getString("email") ?: "Unknown"
                                if (agentName == null) agentName = userDoc.getString("displayName")?.uppercase()
                                if (photoUrl == null) photoUrl = userDoc.getString("photoUrl")
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("AgentRepository", "Fallback metadata fetch failed for $uid")
                        }
                    }

                    // 1. Try to fetch Monthly Summary first for lightning-fast totals
                    var agentSummary: com.antigravity.healthagent.domain.repository.AgentSummary? = null
                    val cleanMonthYear = datePattern?.removePrefix("-")
                    val isSpecificMonth = cleanMonthYear != null && cleanMonthYear.length == 7 // e.g., "01-2025"
                    
                    if (isSpecificMonth) {
                        try {
                            val summaryDoc = agentDoc.reference.collection("monthly_summaries").document(cleanMonthYear!!).get().await()
                            if (summaryDoc.exists()) {
                                agentSummary = com.antigravity.healthagent.domain.repository.AgentSummary(
                                    monthYear = datePattern,
                                    treatedCount = (summaryDoc.getLong("treatedCount") ?: 0L).toInt(),
                                    focusCount = (summaryDoc.getLong("focusCount") ?: 0L).toInt(),
                                    situationCounts = (summaryDoc.get("situationCounts") as? Map<String, Long> ?: emptyMap()).mapValues { it.value.toInt() },
                                    propertyTypeCounts = (summaryDoc.get("propertyTypeCounts") as? Map<String, Long> ?: emptyMap()).mapValues { it.value.toInt() },
                                    totalHouses = (summaryDoc.getLong("totalHouses") ?: 0L).toInt(),
                                    daysWorked = (summaryDoc.getLong("daysWorked") ?: 0L).toInt(),
                                    lastUpdated = summaryDoc.getLong("lastUpdated") ?: 0L
                                )
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("AgentRepository", "Summary fetch failed for $uid: ${e.message}")
                        }
                    }

                    // 2. sub-collection fetches (Parallel)
                    val housesJob = async {
                        try {
                            if (isSpecificMonth && cleanMonthYear != null) {
                                // PRECISION FILTERING: Use whereIn on actual production dates
                                // This bypasses sync-time and clock-skew issues.
                                val monthParts = cleanMonthYear.split("-")
                                val mm = monthParts[0]
                                val yyyy = monthParts[1]
                                
                                val daysInMonth = java.util.Calendar.getInstance().apply {
                                    set(yyyy.toInt(), mm.toInt() - 1, 1)
                                }.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
                                
                                val allDates = (1..daysInMonth).map { String.format("%02d-%s-%s", it, mm, yyyy) }
                                
                                // Firestore whereIn limit is 30, so we split into two queries for a full month
                                val chunk1 = allDates.take(15)
                                val chunk2 = allDates.drop(15)
                                
                                val results1 = agentDoc.reference.collection("houses")
                                    .whereIn("data", chunk1)
                                    .get().await().documents.mapNotNull { it.toHouseSafe(uid) }
                                
                                val results2 = agentDoc.reference.collection("houses")
                                    .whereIn("data", chunk2)
                                    .get().await().documents.mapNotNull { it.toHouseSafe(uid) }
                                
                                (results1 + results2)
                            } else {
                                // LONG RANGE / LEGACY: Fetch by update time but WITHOUT truncation
                                agentDoc.reference.collection("houses").let { collection ->
                                    var q: com.google.firebase.firestore.Query = collection
                                    if (sinceTimestamp > 0) q = q.whereGreaterThanOrEqualTo("lastUpdated", sinceThreshold)
                                    q
                                }.get().await().documents.mapNotNull { it.toHouseSafe(uid) }
                                .let { list -> 
                                    if (datePattern != null) {
                                        val target = datePattern.replace("/", "-")
                                        list.filter { it.data.replace("/", "-").endsWith(target) } 
                                    } else list 
                                }
                            }
                        } catch (e: Exception) { 
                            android.util.Log.e("AgentRepository", "House fetch failed for $uid", e)
                            emptyList() 
                        }
                    }

                    val activitiesJob = async {
                        try {
                            if (isSpecificMonth && cleanMonthYear != null) {
                                val monthParts = cleanMonthYear.split("-")
                                val mm = monthParts[0]
                                val yyyy = monthParts[1]
                                val daysInMonth = java.util.Calendar.getInstance().apply {
                                    set(yyyy.toInt(), mm.toInt() - 1, 1)
                                }.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
                                val allDates = (1..daysInMonth).map { String.format("%02d-%s-%s", it, mm, yyyy) }
                                
                                val chunk1 = allDates.take(15)
                                val chunk2 = allDates.drop(15)
                                
                                val results1 = agentDoc.reference.collection("day_activities")
                                    .whereIn("date", chunk1)
                                    .get().await().documents.mapNotNull { it.toDayActivitySafe(uid) }
                                
                                val results2 = agentDoc.reference.collection("day_activities")
                                    .whereIn("date", chunk2)
                                    .get().await().documents.mapNotNull { it.toDayActivitySafe(uid) }
                                
                                (results1 + results2)
                            } else {
                                agentDoc.reference.collection("day_activities").let { collection ->
                                    var q: com.google.firebase.firestore.Query = collection
                                    if (sinceTimestamp > 0) q = q.whereGreaterThanOrEqualTo("lastUpdated", sinceThreshold)
                                    q
                                }.get().await().documents.mapNotNull { it.toDayActivitySafe(uid) }
                                .let { list ->
                                    if (datePattern != null) {
                                        val target = datePattern.replace("/", "-")
                                        list.filter { it.date.replace("/", "-").endsWith(target) }
                                    } else list
                                }
                            }
                        } catch (e: Exception) { 
                            android.util.Log.e("AgentRepository", "Activity fetch failed for $uid", e)
                            emptyList() 
                        }
                    }

                    val houses = housesJob.await()
                    val activities = activitiesJob.await()

                    if (agentName.isNullOrBlank()) {
                        agentName = houses.firstOrNull { it.agentName.isNotBlank() }?.agentName?.uppercase()
                            ?: activities.firstOrNull { it.agentName.isNotBlank() }?.agentName?.uppercase()
                    }

                    AgentData(uid, email, agentName, houses, activities, agentSummary, lastSyncTime, lastSyncError, photoUrl)
                }
            }

            val allAgents = agentDeferred.awaitAll()
            android.util.Log.d("AgentRepository", "fetchAllAgentsData took ${System.currentTimeMillis() - startTime}ms for ${allAgents.size} agents")
            Result.success(allAgents)
        } catch (e: Exception) {
            android.util.Log.e("AgentRepository", "Failed to fetch all agents data", e)
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

    override suspend fun transferAgentData(fromUid: String, toUid: String): Result<Unit> {
        return try {
            val fromRef = firestore.collection("agents").document(fromUid)
            val toRef = firestore.collection("agents").document(toUid)
            
            // Step 0: Get target agent info (name) for mapping
            val targetAgentDoc = toRef.get().await()
            val targetAgentName = targetAgentDoc.getString("agentName")?.uppercase() ?: ""

            val houses = fromRef.collection("houses").get().await()
            val activities = fromRef.collection("day_activities").get().await()
            
            val totalOps = houses.documents.size + activities.documents.size
            if (totalOps == 0) return Result.success(Unit)

            // Step 1: Transfer houses with ID re-calculation
            houses.documents.chunked(100).forEach { chunk ->
                val batch = firestore.batch()
                chunk.forEach { docSnapshot ->
                    // Map to object, update UID and compute NEW key
                    val houseObj = docSnapshot.toHouseSafe(toUid, targetAgentName)
                    if (houseObj != null) {
                        val newKey = houseObj.generateNaturalKey()
                        val targetDocRef = toRef.collection("houses").document(newKey)
                        
                        batch.set(targetDocRef, houseObj.toFirestoreMap())
                        batch.delete(docSnapshot.reference)
                    }
                }
                batch.commit().await()
            }

            // Step 2: Transfer activities with updated UID
            activities.documents.chunked(100).forEach { chunk ->
                val batch = firestore.batch()
                chunk.forEach { docSnapshot ->
                    val activityObj = docSnapshot.toDayActivitySafe(toUid, targetAgentName)
                    if (activityObj != null) {
                        val dateKey = activityObj.date.replace("/", "-")
                        val targetDocRef = toRef.collection("day_activities").document(dateKey)
                        
                        batch.set(targetDocRef, activityObj.toFirestoreMap())
                        batch.delete(docSnapshot.reference)
                    }
                }
                batch.commit().await()
            }

            // Step 3: Update target's lastSyncTime to trigger a fresh sync on the client
            toRef.update("lastSyncTime", System.currentTimeMillis()).await()
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
