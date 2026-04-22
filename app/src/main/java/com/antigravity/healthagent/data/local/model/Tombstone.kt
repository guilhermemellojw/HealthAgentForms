package com.antigravity.healthagent.data.local.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(tableName = "tombstones")
data class Tombstone(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val type: TombstoneType,
    val naturalKey: String,
    @ColumnInfo(defaultValue = "''") val agentName: String = "",
    @ColumnInfo(defaultValue = "''") val agentUid: String = "",
    @ColumnInfo(defaultValue = "''") val dataDate: String = "", // Added to track which month summary needs recalculation
    val deletedAt: Long = System.currentTimeMillis()
)

enum class TombstoneType {
    HOUSE,
    ACTIVITY
}
