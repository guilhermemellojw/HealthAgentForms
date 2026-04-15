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
    private val firestore: FirebaseFirestore,
    private val agentCacheDao: com.antigravity.healthagent.data.local.dao.AgentCacheDao
) : AgentRepository {

    // Session cache for agent names to further reduce reads
    private var cachedNamesList: List<String>? = null
    private var lastNamesFetch: Long = 0L
    private val NAMES_CACHE_TTL = 300_000L // 5 minutes

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
        val now = System.currentTimeMillis()
        if (cachedNamesList != null && (now - lastNamesFetch) < NAMES_CACHE_TTL) {
            return Result.success(cachedNamesList!!)
        }

        return try {
            val snapshot = firestore.collection("metadata").document("agent_info").get().await()
            val names = (snapshot.get("names") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            
            val result = if (names.isEmpty()) {
                com.antigravity.healthagent.utils.AppConstants.AGENT_NAMES.map { it.uppercase().trim() }.sorted()
            } else {
                names.map { it.uppercase().trim() }.sorted()
            }
            
            cachedNamesList = result
            lastNamesFetch = now
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
                cachedNamesList = null // Invalidate cache
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun fetchAllAgentsData(sinceTimestamp: Long, untilTimestamp: Long, datePattern: String?): Result<List<AgentData>> = coroutineScope {
        try {
            val startTime = System.currentTimeMillis()
            
            // 1. LOAD FROM LOCAL CACHE FIRST
            val cachedAgents = agentCacheDao.getAllCachedAgents()
            val latestCacheUpdate = cachedAgents.maxOfOrNull { it.lastSyncTime } ?: 0L
            
            android.util.Log.d("AgentRepository", "Cache Check: Found ${cachedAgents.size} agents. Latest cache update: $latestCacheUpdate")

            // 2. FETCH DELTAS FROM FIRESTORE
            // Only pull agents that have been updated since our last cache update to find NEW or MODIFIED agents
            val agentsQuery = firestore.collection("agents")
            val agentsSnapshot = if (latestCacheUpdate > 0) {
                agentsQuery.whereGreaterThan("lastSyncTime", latestCacheUpdate).get().await()
            } else {
                agentsQuery.get().await()
            }
            
            // Collect IDs of modified agents to trigger summary refresh
            val modifiedAgentIds = agentsSnapshot.documents.map { it.id }.toSet()
            
            // Update cache with new/modified agents
            if (!agentsSnapshot.isEmpty) {
                android.util.Log.i("AgentRepository", "Delta Fetch: Found ${agentsSnapshot.size()} new/modified agents in cloud.")
                val agentsToUpsert = agentsSnapshot.documents.map { doc ->
                    com.antigravity.healthagent.data.local.model.CachedAgent(
                        uid = doc.id,
                        email = doc.getString("email") ?: "Unknown",
                        agentName = doc.getString("agentName")?.uppercase(),
                        lastSyncTime = doc.getLong("lastSyncTime") ?: 0L,
                        lastSyncError = doc.getString("lastSyncError"),
                        photoUrl = doc.getString("photoUrl")
                    )
                }
                agentCacheDao.upsertAgents(agentsToUpsert)
            }

            // 3. TARGETED DATA FETCH (Summaries and Raw Data)
            // We now look at ALL agents we know about (from cache + the new ones)
            val allAgents = agentCacheDao.getAllCachedAgents()
            val cleanMonthYear = datePattern?.removePrefix("-")
            val isSpecificMonth = cleanMonthYear != null && cleanMonthYear.length == 7 
            val isYearOnly = cleanMonthYear != null && cleanMonthYear.length == 4

            // Process agents in chunks to limit concurrency and memory pressure
            val finalAgents = mutableListOf<com.antigravity.healthagent.domain.repository.AgentData>()
            allAgents.chunked(5).forEach { chunk ->
                val chunkDeferred = chunk.map { agent ->
                    async {
                        val uid = agent.uid
                        val isModified = modifiedAgentIds.contains(uid)
                        
                        // FETCH SUMMARY IF MISSING IN CACHE OR IF AGENT WAS RECENTLY MODIFIED
                        if (isSpecificMonth) {
                            val targetMonth = cleanMonthYear!!
                            val cachedSummary = agentCacheDao.getSummary(uid, targetMonth)
                            
                            // Force cloud fetch if modified in cloud or missing in local cache
                            if (isModified || cachedSummary == null) {
                                try {
                                    val agentRef = firestore.collection("agents").document(uid)
                                    val alternativeMonth = targetMonth.replace("-", "/")
                                    
                                    var summaryDoc = agentRef.collection("monthly_summaries").document(targetMonth).get().await()
                                    if (!summaryDoc.exists()) {
                                        summaryDoc = agentRef.collection("monthly_summaries").document(alternativeMonth).get().await()
                                    }
                                    
                                    if (summaryDoc.exists()) {
                                        val summary = parseSummary(summaryDoc, uid, targetMonth)
                                        agentCacheDao.upsertSummaries(listOf(summary.toCached(uid)))
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("AgentRepository", "Summary fetch error for $uid ($targetMonth): ${e.message}")
                                }
                            }
                        } else if (isYearOnly) {
                            // Optimization: Only fetch if we have 0 summaries for this year in cache
                            // OR if the agent was modified in cloud (they might have new months)
                            val yearSuffixHyphen = "-$cleanMonthYear"
                            val yearSuffixSlash = "/$cleanMonthYear"
                            val cachedSummaries = agentCacheDao.getSummariesForAgent(uid)
                                .filter { it.monthYear.endsWith(yearSuffixHyphen) || it.monthYear.endsWith(yearSuffixSlash) }
                            
                            if (isModified || cachedSummaries.isEmpty()) {
                                try {
                                    val agentRef = firestore.collection("agents").document(uid)
                                    val allSummaries = agentRef.collection("monthly_summaries").get().await()
                                    val yearSummaries = allSummaries.documents.filter { 
                                        it.id.endsWith(yearSuffixHyphen) || it.id.endsWith(yearSuffixSlash)
                                    }.map { parseSummary(it, uid, it.id) }
                                    
                                    if (yearSummaries.isNotEmpty()) {
                                        agentCacheDao.upsertSummaries(yearSummaries.map { it.toCached(uid) })
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("AgentRepository", "Yearly summaries fetch error for $uid: ${e.message}")
                                }
                            }
                        }

                        // FETCH RAW HOUSES AND ACTIVITIES IF RANGE PROVIDED (e.g. for Weekly View)
                        var houses = emptyList<com.antigravity.healthagent.data.local.model.House>()
                        var activities = emptyList<com.antigravity.healthagent.data.local.model.DayActivity>()

                        if (sinceTimestamp > 0 && untilTimestamp > 0 && sinceTimestamp < untilTimestamp) {
                            try {
                                val agentRef = firestore.collection("agents").document(uid)
                                val sinceT = com.google.firebase.Timestamp(sinceTimestamp / 1000, 0)
                                val untilT = com.google.firebase.Timestamp(untilTimestamp / 1000, 999999999)

                                // Fetch houses in range
                                val houseDocs = agentRef.collection("houses")
                                    .whereGreaterThanOrEqualTo("lastUpdated", sinceT)
                                    .whereLessThanOrEqualTo("lastUpdated", untilT)
                                    .get().await()
                                
                                houses = houseDocs.documents.mapNotNull { it.toHouseSafe(uid, agent.agentName ?: "") }

                                // Fetch activities in range
                                val activityDocs = agentRef.collection("day_activities")
                                    .whereGreaterThanOrEqualTo("lastUpdated", sinceT)
                                    .whereLessThanOrEqualTo("lastUpdated", untilT)
                                    .get().await()
                                
                                activities = activityDocs.documents.mapNotNull { it.toDayActivitySafe(uid, agent.agentName ?: "") }
                            } catch (e: Exception) {
                                android.util.Log.e("AgentRepository", "Raw data fetch error for $uid: ${e.message}")
                            }
                        }

                        // Final AgentData for this agent
                        val summary = if (isYearOnly) {
                            val targetStr = cleanMonthYear!!
                            val yearSummaries = agentCacheDao.getSummariesForAgent(uid)
                                .filter { it.monthYear.endsWith("-$targetStr") || it.monthYear.endsWith("/$targetStr") }
                            
                            if (yearSummaries.isNotEmpty()) {
                                val sitCounts = mutableMapOf<String, Int>()
                                val propCounts = mutableMapOf<String, Int>()
                                yearSummaries.forEach { s ->
                                    s.situationCounts.forEach { (k, v) -> sitCounts[k] = (sitCounts[k] ?: 0) + v }
                                    s.propertyTypeCounts.forEach { (k, v) -> propCounts[k] = (propCounts[k] ?: 0) + v }
                                }
                                
                                com.antigravity.healthagent.domain.repository.AgentSummary(
                                    monthYear = targetStr,
                                    treatedCount = yearSummaries.sumOf { it.treatedCount },
                                    focusCount = yearSummaries.sumOf { it.focusCount },
                                    situationCounts = sitCounts,
                                    propertyTypeCounts = propCounts,
                                    totalHouses = yearSummaries.sumOf { it.totalHouses },
                                    daysWorked = yearSummaries.sumOf { it.daysWorked },
                                    lastUpdated = yearSummaries.maxOf { it.lastUpdated }
                                )
                            } else null
                        } else if (isSpecificMonth) {
                            agentCacheDao.getSummary(uid, cleanMonthYear!!)?.let { s ->
                                com.antigravity.healthagent.domain.repository.AgentSummary(
                                    monthYear = s.monthYear,
                                    treatedCount = s.treatedCount,
                                    focusCount = s.focusCount,
                                    situationCounts = s.situationCounts,
                                    propertyTypeCounts = s.propertyTypeCounts,
                                    totalHouses = s.totalHouses,
                                    daysWorked = s.daysWorked,
                                    lastUpdated = s.lastUpdated
                                )
                            }
                        } else null

                        com.antigravity.healthagent.domain.repository.AgentData(
                            uid = uid,
                            email = agent.email,
                            agentName = agent.agentName,
                            houses = houses,
                            activities = activities,
                            summary = summary,
                            lastSyncTime = agent.lastSyncTime,
                            lastSyncError = agent.lastSyncError,
                            photoUrl = agent.photoUrl
                        )
                    }
                }
                finalAgents.addAll(chunkDeferred.awaitAll())
            }
            android.util.Log.d("AgentRepository", "fetchAllAgentsData took ${System.currentTimeMillis() - startTime}ms. Final Count: ${finalAgents.size}")
            Result.success(finalAgents)
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
            val batch = firestore.batch()
            batch.delete(docRef)
            batch.update(firestore.collection("agents").document(uid), 
                mapOf(
                    "deleted_house_ids" to com.google.firebase.firestore.FieldValue.arrayUnion(houseId),
                    "lastSyncTime" to System.currentTimeMillis()
                )
            )
            batch.commit().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteAgentActivity(uid: String, activityDate: String): Result<Unit> {
        return try {
            val agentRef = firestore.collection("agents").document(uid)
            val dateKey = activityDate.replace("/", "-")
            val docRef = agentRef.collection("day_activities").document(dateKey)
            
            // Cascading deletion: Find and delete all houses for this date in cloud
            // Firestore data is stored with dashes DD-MM-YYYY
            val activityDateDashed = dateKey.replace("/", "-")
            val houses = agentRef.collection("houses")
                .whereEqualTo("data", activityDateDashed)
                .get().await()
            
            val batch = firestore.batch()
            batch.delete(docRef)
            houses.documents.forEach { batch.delete(it.reference) }
            
            // Also add to cloud tombstone for this agent
            batch.update(agentRef, "deleted_activity_dates", com.google.firebase.firestore.FieldValue.arrayUnion(dateKey))
            // Trigger cache invalidation for supervisors
            batch.update(agentRef, "lastSyncTime", System.currentTimeMillis())
            
            batch.commit().await()
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
            val transferredHouseIds = mutableListOf<String>()
            val transferredDates = mutableSetOf<String>()

            houses.documents.chunked(100).forEach { chunk ->
                val batch = firestore.batch()
                chunk.forEach { docSnapshot ->
                    val houseObj = docSnapshot.toHouseSafe(toUid, targetAgentName)
                    if (houseObj != null) {
                        val newKey = houseObj.generateNaturalKey()
                        val targetDocRef = toRef.collection("houses").document(newKey)
                        
                        batch.set(targetDocRef, houseObj.toFirestoreMap())
                        batch.delete(docSnapshot.reference)
                        
                        transferredHouseIds.add(docSnapshot.id)
                        transferredDates.add(houseObj.data.replace("/", "-"))
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
                        
                        transferredDates.add(dateKey)
                    }
                }
                batch.commit().await()
            }

            // Step 3: Record deletions in the source agent's tombstones 
            // This is CRITICAL to prevent the source device from re-syncing this data back to the cloud
            if (transferredHouseIds.isNotEmpty() || transferredDates.isNotEmpty()) {
                val updates = mutableMapOf<String, Any>()
                if (transferredHouseIds.isNotEmpty()) {
                    updates["deleted_house_ids"] = com.google.firebase.firestore.FieldValue.arrayUnion(*transferredHouseIds.toTypedArray())
                }
                if (transferredDates.isNotEmpty()) {
                    updates["deleted_activity_dates"] = com.google.firebase.firestore.FieldValue.arrayUnion(*transferredDates.toTypedArray())
                }
                fromRef.update(updates).await()
            }

            // Step 4: Update target's lastSyncTime to trigger a fresh sync on the client
            toRef.update("lastSyncTime", System.currentTimeMillis()).await()
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseSummary(doc: com.google.firebase.firestore.DocumentSnapshot, uid: String, monthYear: String): com.antigravity.healthagent.domain.repository.AgentSummary {
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

        return com.antigravity.healthagent.domain.repository.AgentSummary(
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

    private fun com.antigravity.healthagent.domain.repository.AgentSummary.toCached(agentUid: String): com.antigravity.healthagent.data.local.model.CachedAgentSummary {
        return com.antigravity.healthagent.data.local.model.CachedAgentSummary(
            agentUid = agentUid,
            monthYear = this.monthYear,
            treatedCount = this.treatedCount,
            focusCount = this.focusCount,
            totalHouses = this.totalHouses,
            daysWorked = this.daysWorked,
            lastUpdated = this.lastUpdated,
            situationCounts = this.situationCounts,
            propertyTypeCounts = this.propertyTypeCounts
        )
    }
}
