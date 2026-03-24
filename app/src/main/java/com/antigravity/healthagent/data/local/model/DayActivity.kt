package com.antigravity.healthagent.data.local.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "day_activities", primaryKeys = ["date", "agentName", "agentUid"])
data class DayActivity(
    val date: String = "", // Format: DD-MM-YYYY
    val status: String = "",
    val isClosed: Boolean = false,
    val agentName: String = "",
    val agentUid: String = "",
    val isSynced: Boolean = false,
    @get:com.google.firebase.firestore.Exclude
    val lastUpdated: Long = System.currentTimeMillis()
) {
    fun toFirestoreMap(): Map<String, Any?> {
        return mapOf(
            "date" to date.replace("/", "-"),
            "status" to status,
            "isClosed" to isClosed,
            "agentName" to agentName.uppercase(),
            "agentUid" to agentUid,
            "lastUpdated" to com.google.firebase.firestore.FieldValue.serverTimestamp()
        )
    }
}
