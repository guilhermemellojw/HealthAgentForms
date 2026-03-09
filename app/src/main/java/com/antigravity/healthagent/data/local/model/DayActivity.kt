package com.antigravity.healthagent.data.local.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "day_activities", primaryKeys = ["date", "agentName"])
data class DayActivity(
    val date: String, // Format: DD-MM-YYYY
    val status: String = "",
    val isClosed: Boolean = false,
    val agentName: String = ""
)
