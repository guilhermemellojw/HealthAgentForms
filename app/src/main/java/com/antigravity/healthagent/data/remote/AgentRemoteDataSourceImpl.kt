package com.antigravity.healthagent.data.remote

import com.antigravity.healthagent.data.local.model.CachedAgent
import com.antigravity.healthagent.domain.repository.AgentSummary
import com.google.firebase.auth.FirebaseAuth
import com.antigravity.healthagent.domain.logger.AppLogger
import com.antigravity.healthagent.utils.CollectionNames
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentRemoteDataSourceImpl @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val storage: FirebaseStorage
) : AgentRemoteDataSource {

    private val NAMES_CACHE_TTL = 300_000L // 5 minutes

    override suspend fun createAgent(email: String, agentName: String?): Result<Unit> {
        return try {
            val normalizedEmail = email.trim().lowercase()
            val existing = firestore.collection(CollectionNames.AGENTS)
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
            
            firestore.collection(CollectionNames.AGENTS).document(docId).set(agentData).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteAgent(uid: String): Result<Unit> {
        return try {
            val agentRef = firestore.collection(CollectionNames.AGENTS).document(uid)

            val houses = agentRef.collection(CollectionNames.HOUSES).get().await()
            val activities = agentRef.collection(CollectionNames.DAY_ACTIVITIES).get().await()
            val summaries = agentRef.collection(CollectionNames.MONTHLY_SUMMARIES).get().await()
            
            val operations = (houses.documents + activities.documents + summaries.documents).map { it.reference }.toMutableList()
            
            val wipeMetadata = mapOf(
                "lastSyncTime" to com.antigravity.healthagent.utils.TimeManager.currentTimeMillis(),
                "deleted_house_ids" to FieldValue.delete(),
                "deleted_activity_dates" to FieldValue.delete(),
                "lastSyncError" to FieldValue.delete()
            )

            if (operations.isNotEmpty()) {
                operations.chunked(400).forEach { chunk ->
                    val batch = firestore.batch()
                    for (ref in chunk) {
                        batch.delete(ref)
                    }
                    if (chunk == operations.chunked(400).last()) {
                        batch.set(agentRef, wipeMetadata, SetOptions.merge())
                    }
                    batch.commit().await()
                }
            } else {
                agentRef.set(wipeMetadata, SetOptions.merge()).await()
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun purgeAgentCompletely(uid: String): Result<Unit> {
        return try {
            val agentRef = firestore.collection(CollectionNames.AGENTS).document(uid)

            val houses = agentRef.collection(CollectionNames.HOUSES).get().await()
            val activities = agentRef.collection(CollectionNames.DAY_ACTIVITIES).get().await()
            val summaries = agentRef.collection(CollectionNames.MONTHLY_SUMMARIES).get().await()
            val backups = agentRef.collection(CollectionNames.BACKUPS).get().await()
            
            val operations = (houses.documents + activities.documents + summaries.documents + backups.documents)
                .map { it.reference }.toMutableList()
            
            if (operations.isNotEmpty()) {
                operations.chunked(400).forEach { chunk ->
                    val batch = firestore.batch()
                    for (ref in chunk) {
                        batch.delete(ref)
                    }
                    batch.commit().await()
                }
            }
            
            agentRef.delete().await()

            try {
                val storageRef = storage.reference.child("backups/$uid")
                val listResult = storageRef.listAll().await()
                listResult.items.forEach { fileRef ->
                    fileRef.delete().await()
                }
            } catch (storageEx: Exception) {
                AppLogger.e("AgentRemoteDataSource", "Failed to clear storage backups for $uid", storageEx)
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
            
            val result = if (names.isEmpty()) {
                com.antigravity.healthagent.utils.AppConstants.AGENT_NAMES.map { it.uppercase().trim() }.sorted()
            } else {
                names.map { it.uppercase().trim() }.sorted()
            }
            
            Result.success(result)
        } catch (e: Exception) {
            Result.success(com.antigravity.healthagent.utils.AppConstants.AGENT_NAMES.map { it.uppercase().trim() }.sorted())
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

    override suspend fun fetchUpdatedAgents(latestCacheUpdate: Long): Result<List<CachedAgent>> {
        return try {
            val agentsQuery = firestore.collection(CollectionNames.AGENTS)
            val agentsSnapshot = if (latestCacheUpdate > 0) {
                agentsQuery.whereGreaterThan("lastSyncTime", latestCacheUpdate - 500).get().await()
            } else {
                agentsQuery.get().await()
            }
            val agents = agentsSnapshot.documents.map { doc ->
                val email = doc.getString("email") ?: "Unknown"
                val cloudName = doc.getString("agentName")?.uppercase()
                CachedAgent(
                    uid = doc.id,
                    email = email,
                    agentName = cloudName,
                    lastSyncTime = doc.getLong("lastSyncTime") ?: 0L,
                    lastSyncError = doc.getString("lastSyncError"),
                    photoUrl = doc.getString("photoUrl")
                )
            }
            Result.success(agents)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun fetchMonthlySummary(uid: String, monthYear: String): Result<AgentSummary?> {
        return try {
            val agentRef = firestore.collection(CollectionNames.AGENTS).document(uid)
            val alternativeMonth = monthYear.replace("-", "/")
            
            var summaryDoc = agentRef.collection(CollectionNames.MONTHLY_SUMMARIES).document(monthYear).get().await()
            if (!summaryDoc.exists()) {
                summaryDoc = agentRef.collection(CollectionNames.MONTHLY_SUMMARIES).document(alternativeMonth).get().await()
            }
            
            if (summaryDoc.exists()) {
                Result.success(parseSummary(summaryDoc, uid, monthYear))
            } else {
                Result.success(null)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun fetchYearlySummaries(uid: String, year: String): Result<List<AgentSummary>> {
        return try {
            val agentRef = firestore.collection(CollectionNames.AGENTS).document(uid)
            val yearSuffixHyphen = "-$year"
            val yearSuffixSlash = "/$year"
            
            val allSummaries = agentRef.collection(CollectionNames.MONTHLY_SUMMARIES).get().await()
            val yearSummaries = allSummaries.documents.filter { 
                it.id.endsWith(yearSuffixHyphen) || it.id.endsWith(yearSuffixSlash)
            }.map { parseSummary(it, uid, it.id) }
            
            Result.success(yearSummaries)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteAgentSummary(uid: String, monthYear: String): Result<Unit> {
        return try {
            val agentRef = firestore.collection(CollectionNames.AGENTS).document(uid)
            val alternativeMonth = monthYear.replace("-", "/")
            agentRef.collection(CollectionNames.MONTHLY_SUMMARIES).document(monthYear).delete().await()
            agentRef.collection(CollectionNames.MONTHLY_SUMMARIES).document(alternativeMonth).delete().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun clearSyncError(uid: String): Result<Unit> {
        return try {
            firestore.collection(CollectionNames.AGENTS).document(uid).update(
                "lastSyncError", FieldValue.delete()
            ).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseSummary(doc: DocumentSnapshot, uid: String, monthYear: String): AgentSummary {
        val treatedCount = (doc.get("treatedCount") as? Number ?: 0).toInt()
        val focusCount = (doc.get("focusCount") as? Number ?: 0).toInt()
        val totalHouses = (doc.get("totalHouses") as? Number ?: 0).toInt()
        val daysWorked = (doc.get("daysWorked") as? Number ?: 0).toInt()
        
        val lastUpdatedRaw = doc.get("lastUpdated")
        val lastUpdated = when (lastUpdatedRaw) {
            is com.google.firebase.Timestamp -> lastUpdatedRaw.toDate().time
            is Number -> lastUpdatedRaw.toLong()
            else -> 0L
        }
        val situationCounts = (doc.get("situationCounts") as? Map<*, *> ?: emptyMap<Any, Any>())
            .entries.associate { it.key.toString() to (it.value as? Number ?: 0).toInt() }
        val propertyTypeCounts = (doc.get("propertyTypeCounts") as? Map<*, *> ?: emptyMap<Any, Any>())
            .entries.associate { it.key.toString() to (it.value as? Number ?: 0).toInt() }

        return AgentSummary(
            monthYear = monthYear.replace("/", "-"),
            treatedCount = treatedCount,
            focusCount = focusCount,
            situationCounts = situationCounts,
            propertyTypeCounts = propertyTypeCounts,
            totalHouses = totalHouses,
            daysWorked = daysWorked,
            lastUpdated = lastUpdated
        )
    }
}
