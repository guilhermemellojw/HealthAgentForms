package com.antigravity.healthagent.data.local.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@com.google.firebase.firestore.IgnoreExtraProperties
@Entity(tableName = "day_activities", primaryKeys = ["date", "agentName", "agentUid"])
data class DayActivity(
    val date: String = "", // Format: DD-MM-YYYY
    val status: String = "",
    @ColumnInfo(defaultValue = "0") val isClosed: Boolean = false,
    @ColumnInfo(defaultValue = "0") val isManualUnlock: Boolean = false,
    val agentName: String = "",
    @ColumnInfo(defaultValue = "''") val agentUid: String = "",
    @ColumnInfo(defaultValue = "0") val isSynced: Boolean = false,
    @ColumnInfo(defaultValue = "0") val editedByAdmin: Boolean = false,
    @get:com.google.firebase.firestore.Exclude
    @ColumnInfo(defaultValue = "0") val lastUpdated: Long = System.currentTimeMillis()
) {
    fun toFirestoreMap(): Map<String, Any?> {
        return mapOf(
            "date" to date.replace("/", "-"),
            "status" to status,
            "isClosed" to isClosed,
            "isManualUnlock" to isManualUnlock,
            "agentName" to agentName.uppercase(),
            "agentUid" to agentUid,
            "lastUpdated" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
            "editedByAdmin" to editedByAdmin
        )
    }
}
