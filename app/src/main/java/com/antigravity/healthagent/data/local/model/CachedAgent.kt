package com.antigravity.healthagent.data.local.model

import androidx.room.*

@Entity(tableName = "cached_agents")
data class CachedAgent(
    @PrimaryKey val uid: String,
    val email: String,
    val agentName: String?,
    val lastSyncTime: Long,
    val lastSyncError: String?,
    val photoUrl: String?,
    val cachedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "cached_agent_summaries",
    primaryKeys = ["agentUid", "monthYear"]
)
data class CachedAgentSummary(
    val agentUid: String,
    val monthYear: String,
    val treatedCount: Int,
    val focusCount: Int,
    val totalHouses: Int,
    val daysWorked: Int,
    val lastUpdated: Long,
    val situationCounts: Map<String, Int>,
    val propertyTypeCounts: Map<String, Int>
)
