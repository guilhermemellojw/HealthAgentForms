package com.antigravity.healthagent.domain.repository

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.DayActivity

data class AgentData(
    val uid: String,
    val email: String,
    val agentName: String? = null,
    val houses: List<House>,
    val activities: List<DayActivity>,
    val lastSyncTime: Long = 0L,
    val lastSyncError: String? = null
)
