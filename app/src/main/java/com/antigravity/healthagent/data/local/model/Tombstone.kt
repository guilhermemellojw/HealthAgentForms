package com.antigravity.healthagent.data.local.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tombstones")
data class Tombstone(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val type: TombstoneType,
    val naturalKey: String,
    val agentName: String = "",
    val agentUid: String = "",
    val deletedAt: Long = System.currentTimeMillis()
)

enum class TombstoneType {
    HOUSE,
    ACTIVITY
}
