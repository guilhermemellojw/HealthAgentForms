package com.antigravity.healthagent.data.repository

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.domain.repository.AgentData
import com.antigravity.healthagent.domain.repository.AgentRepository
import com.antigravity.healthagent.data.remote.AgentRemoteDataSource
import com.antigravity.healthagent.data.remote.HouseRemoteDataSource
import com.antigravity.healthagent.domain.logger.AppLogger
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentRepositoryImpl @Inject constructor(
    private val agentRemoteDataSource: AgentRemoteDataSource,
    private val houseRemoteDataSource: HouseRemoteDataSource,
    private val agentCacheDao: com.antigravity.healthagent.data.local.dao.AgentCacheDao
) : AgentRepository {

    private var cachedNamesList: List<String>? = null
    private var lastNamesFetch: Long = 0L
    private val NAMES_CACHE_TTL = 300_000L // 5 minutes

    override suspend fun createAgent(email: String, agentName: String?): Result<Unit> {
        return agentRemoteDataSource.createAgent(email, agentName)
    }

    override suspend fun deleteAgent(uid: String): Result<Unit> {
        agentCacheDao.deleteAgentCache(uid)
        agentCacheDao.deleteAgentSummaries(uid)
        return agentRemoteDataSource.deleteAgent(uid)
    }

    override suspend fun purgeAgentCompletely(uid: String): Result<Unit> {
        agentCacheDao.deleteAgentCache(uid)
        agentCacheDao.deleteAgentSummaries(uid)
        return agentRemoteDataSource.purgeAgentCompletely(uid)
    }

    override suspend fun fetchAgentNames(): Result<List<String>> {
        val now = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis()
        if (cachedNamesList != null && (now - lastNamesFetch) < NAMES_CACHE_TTL) {
            return Result.success(cachedNamesList!!)
        }

        val result = agentRemoteDataSource.fetchAgentNames().getOrElse {
            com.antigravity.healthagent.utils.AppConstants.AGENT_NAMES.map { it.uppercase().trim() }.sorted()
        }

        cachedNamesList = result
        lastNamesFetch = now
        return Result.success(result)
    }

    override suspend fun addAgentName(name: String): Result<Unit> {
        val result = agentRemoteDataSource.addAgentName(name)
        if (result.isSuccess) {
            cachedNamesList = null
        }
        return result
    }

    override suspend fun deleteAgentName(name: String): Result<Unit> {
        val result = agentRemoteDataSource.deleteAgentName(name)
        if (result.isSuccess) {
            cachedNamesList = null
        }
        return result
    }

    override suspend fun fetchAllAgentsData(sinceTimestamp: Long, untilTimestamp: Long, datePattern: String?): Result<List<AgentData>> = coroutineScope {
        try {
            val startTime = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis()
            
            // 1. LOAD FROM LOCAL CACHE FIRST
            val cachedAgents = agentCacheDao.getAllCachedAgents()
            val latestCacheUpdate = cachedAgents.maxOfOrNull { it.lastSyncTime } ?: 0L
            
            AppLogger.d("AgentRepository", "Cache Check: Found ${cachedAgents.size} agents. Latest cache update: $latestCacheUpdate")

            // 2. FETCH DELTAS FROM REMOTE
            val updatedAgentsResult = agentRemoteDataSource.fetchUpdatedAgents(latestCacheUpdate)
            val updatedAgents = updatedAgentsResult.getOrNull() ?: emptyList()
            val modifiedAgentIds = updatedAgents.map { it.uid }.toSet()
            
            if (updatedAgents.isNotEmpty()) {
                AppLogger.i("AgentRepository", "Delta Fetch: Found ${updatedAgents.size} new/modified agents in cloud.")
                val existingProfiles = agentCacheDao.getAllCachedAgents().associateBy { it.uid }

                val agentsToUpsert = updatedAgents.map { cloudAgent ->
                    val cached = existingProfiles[cloudAgent.uid]
                    val bestName = if ((cloudAgent.agentName == null || cloudAgent.agentName.contains("@")) && cached?.agentName?.isNotBlank() == true && !cached.agentName.contains("@")) {
                        cached.agentName
                    } else {
                        cloudAgent.agentName
                    }
                    cloudAgent.copy(agentName = bestName)
                }
                agentCacheDao.upsertAgents(agentsToUpsert)
            }

            // 3. TARGETED DATA FETCH (Summaries and Raw Data)
            val allAgents = agentCacheDao.getAllCachedAgents()
            val cleanMonthYear = datePattern?.removePrefix("-")
            val isSpecificMonth = cleanMonthYear != null && cleanMonthYear.length == 7 
            val isYearOnly = cleanMonthYear != null && cleanMonthYear.length == 4

            val finalAgents = mutableListOf<AgentData>()
            allAgents.chunked(5).forEach { chunk ->
                val chunkDeferred = chunk.map { agent ->
                    async {
                        val uid = agent.uid
                        val isModified = modifiedAgentIds.contains(uid)
                        
                        if (isSpecificMonth) {
                           val targetMonth = cleanMonthYear!!
                           val cachedSummary = agentCacheDao.getSummary(uid, targetMonth)
                           val cacheTtlExceeded = cachedSummary != null && (System.currentTimeMillis() - cachedSummary.lastUpdated) > NAMES_CACHE_TTL
                           if (isModified || cachedSummary == null || cacheTtlExceeded) {
                               agentRemoteDataSource.fetchMonthlySummary(uid, targetMonth).onSuccess { summary ->
                                   if (summary != null) {
                                       agentCacheDao.upsertSummaries(listOf(summary.toCached(uid)))
                                   } else {
                                       agentCacheDao.deleteAgentSummary(uid, targetMonth)
                                   }
                               }.onFailure { e ->
                                   AppLogger.e("AgentRepository", "Summary fetch error for $uid ($targetMonth): ${e.message}")
                               }
                           }
                        } else if (isYearOnly) {
                           val yearSuffixHyphen = "-$cleanMonthYear"
                           val yearSuffixSlash = "/$cleanMonthYear"
                           val cachedSummaries = agentCacheDao.getSummariesForAgent(uid)
                               .filter { it.monthYear.endsWith(yearSuffixHyphen) || it.monthYear.endsWith(yearSuffixSlash) }
                           
                           if (isModified || cachedSummaries.isEmpty()) {
                               agentRemoteDataSource.fetchYearlySummaries(uid, cleanMonthYear!!).onSuccess { yearSummaries ->
                                   if (yearSummaries.isNotEmpty()) {
                                       agentCacheDao.upsertSummaries(yearSummaries.map { it.toCached(uid) })
                                   }
                               }.onFailure { e ->
                                   AppLogger.e("AgentRepository", "Yearly summaries fetch error for $uid: ${e.message}")
                               }
                           }
                        }

                        var houses = emptyList<House>()
                        var activities = emptyList<DayActivity>()

                        if (sinceTimestamp > 0 && untilTimestamp > 0 && sinceTimestamp < untilTimestamp) {
                           val dateStrings = getDateStringsInRange(sinceTimestamp, untilTimestamp)
                           houseRemoteDataSource.fetchHousesInRange(uid, dateStrings, agent.agentName ?: "").onSuccess {
                               houses = it
                           }.onFailure { e ->
                               AppLogger.e("AgentRepository", "Raw houses fetch error for $uid: ${e.message}")
                           }

                           houseRemoteDataSource.fetchActivitiesInRange(uid, dateStrings, agent.agentName ?: "").onSuccess {
                               activities = it
                           }.onFailure { e ->
                               AppLogger.e("AgentRepository", "Raw activities fetch error for $uid: ${e.message}")
                           }
                        }

                        val summary = if (isYearOnly) {
                           val targetStr = cleanMonthYear!!
                           val tz = java.util.TimeZone.getTimeZone("America/Sao_Paulo")
                           val now = java.util.Calendar.getInstance(tz)
                           val isCurrentYear = targetStr == now.get(java.util.Calendar.YEAR).toString()
                           val currentMonthStr = String.format("%02d", now.get(java.util.Calendar.MONTH) + 1)
                           
                           val yearSummaries = agentCacheDao.getSummariesForAgent(uid)
                               .filter { it.monthYear.endsWith("-$targetStr") || it.monthYear.endsWith("/$targetStr") }
                               .filter { !isCurrentYear || (!it.monthYear.startsWith("$currentMonthStr-") && !it.monthYear.startsWith("$currentMonthStr/")) }
                           
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

                        AgentData(
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
            AppLogger.d("AgentRepository", "fetchAllAgentsData took ${System.currentTimeMillis() - startTime}ms. Final Count: ${finalAgents.size}")
            Result.success(finalAgents)
        } catch (e: Exception) {
            AppLogger.e("AgentRepository", "Failed to fetch all agents data", e)
            Result.failure(e)
        }
    }

    override suspend fun deleteAgentHouse(uid: String, houseId: String): Result<Unit> {
        return houseRemoteDataSource.deleteAgentHouse(uid, houseId, null)
    }

    override suspend fun deleteAgentActivity(uid: String, activityDate: String): Result<Unit> {
        val dateKey = activityDate.replace("/", "-")
        val monthYear = if (dateKey.isNotBlank()) {
            val parts = dateKey.split("-")
            if (parts.size == 3) {
                val mm = String.format("%02d", parts[1].toInt())
                val yyyy = parts[2]
                "$mm-$yyyy"
            } else null
        } else null
        return houseRemoteDataSource.deleteAgentActivity(uid, activityDate, monthYear)
    }

    override suspend fun clearSyncError(uid: String): Result<Unit> {
        return agentRemoteDataSource.clearSyncError(uid)
    }

    override suspend fun transferAgentData(fromUid: String, toUid: String): Result<Unit> {
        val targetAgent = agentCacheDao.getCachedAgent(toUid)
        val targetAgentName = targetAgent?.agentName?.uppercase() ?: ""
        return houseRemoteDataSource.transferAgentData(fromUid, toUid, targetAgentName)
    }

    override fun observeAgentProduction(uid: String, datePattern: String?): Flow<AgentData> = kotlinx.coroutines.flow.flow {
        val cached = agentCacheDao.getCachedAgent(uid)
        houseRemoteDataSource.observeAgentProduction(uid, cached?.agentName ?: "", datePattern)
            .collect { (houses, activities) ->
                emit(
                    AgentData(
                        uid = uid,
                        email = cached?.email ?: "",
                        agentName = cached?.agentName,
                        lastSyncTime = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis(),
                        houses = houses,
                        activities = activities,
                        photoUrl = cached?.photoUrl
                    )
                )
            }
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

    private fun getDateStringsInRange(start: Long, end: Long): List<String> {
        val dates = mutableListOf<String>()
        val tz = java.util.TimeZone.getTimeZone("America/Sao_Paulo")
        val cal = java.util.Calendar.getInstance(tz)
        cal.timeInMillis = start
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)

        val sdf = com.antigravity.healthagent.utils.DateUtils.DASH_DATE.get().apply { timeZone = tz }
        
        var count = 0
        while (cal.timeInMillis <= end && count < 31) {
            dates.add(sdf.format(cal.time))
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            count++
        }
        return dates
    }
}
