package com.antigravity.healthagent.domain.repository

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.DayActivity

data class AgentSummary(
    val monthYear: String = "", // e.g. "01-2025"
    val treatedCount: Int = 0,
    val focusCount: Int = 0,
    val situationCounts: Map<String, Int> = emptyMap(),
    val propertyTypeCounts: Map<String, Int> = emptyMap(),
    val totalHouses: Int = 0,
    val daysWorked: Int = 0,
    val lastUpdated: Long = System.currentTimeMillis()
)

data class AgentData(
    val uid: String,
    val email: String,
    val agentName: String? = null,
    val houses: List<House>,
    val activities: List<DayActivity>,
    val summary: AgentSummary? = null,
    val lastSyncTime: Long = 0L,
    val lastSyncError: String? = null,
    val photoUrl: String? = null
)
