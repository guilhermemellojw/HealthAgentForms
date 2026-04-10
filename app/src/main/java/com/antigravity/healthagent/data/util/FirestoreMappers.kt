package com.antigravity.healthagent.data.util

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.DayActivity
import com.google.firebase.firestore.DocumentSnapshot

fun DocumentSnapshot.toHouseSafe(uid: String, agentName: String = ""): House? {
    return try {
        val house = this.toObject(House::class.java) ?: return null
        val createdAtRaw = this.get("createdAt")
        val lastUpdatedRaw = this.get("lastUpdated")
        
        val createdAt = when(createdAtRaw) {
            is com.google.firebase.Timestamp -> createdAtRaw.toDate().time
            is Long -> createdAtRaw
            else -> house.createdAt
        }
        val lastUpdated = when(lastUpdatedRaw) {
            is com.google.firebase.Timestamp -> lastUpdatedRaw.toDate().time
            is Long -> lastUpdatedRaw
            else -> house.lastUpdated
        }

        val finalSequence = (this.get("sequence") as? Long)?.toInt() ?: house.sequence
        val finalComplement = (this.get("complement") as? Long)?.toInt() ?: house.complement

        val finalAgentName = (if (agentName.isNotBlank()) agentName else (house.agentName.ifBlank { "" })).trim().uppercase()
        val finalMunicipio = if (house.municipio.isBlank()) "BOM JARDIM" else house.municipio
        val finalBairro = if (house.bairro.isBlank()) "" else house.bairro
        
        house.copy(
            sequence = finalSequence,
            complement = finalComplement,
            data = house.data.replace("/", "-"),
            createdAt = createdAt, 
            lastUpdated = lastUpdated, 
            agentUid = uid, 
            agentName = finalAgentName,
            municipio = finalMunicipio,
            bairro = finalBairro
        )
    } catch (e: Exception) {
        android.util.Log.e("FirestoreMappers", "toHouseSafe: Error mapping ${this.id}", e)
        null
    }
}

fun DocumentSnapshot.toDayActivitySafe(uid: String, agentName: String = ""): DayActivity? {
    return try {
        val activity = this.toObject(DayActivity::class.java) ?: return null
        val lastUpdatedRaw = this.get("lastUpdated")
        
        val lastUpdated = when(lastUpdatedRaw) {
            is com.google.firebase.Timestamp -> lastUpdatedRaw.toDate().time
            is Long -> lastUpdatedRaw
            else -> activity.lastUpdated
        }
        val isClosed = this.getBoolean("isClosed") ?: activity.isClosed
        val isManualUnlock = this.getBoolean("isManualUnlock") ?: activity.isManualUnlock
        val finalStatus = this.getString("status") ?: activity.status

        val finalAgentName = (if (agentName.isNotBlank()) agentName else (activity.agentName.ifBlank { "" })).trim().uppercase()
        activity.copy(
            status = finalStatus,
            date = activity.date.replace("/", "-"),
            isClosed = isClosed,
            isManualUnlock = isManualUnlock,
            lastUpdated = lastUpdated, 
            agentUid = uid, 
            agentName = finalAgentName
        )
    } catch (e: Exception) {
        android.util.Log.e("FirestoreMappers", "toDayActivitySafe: Error mapping ${this.id}", e)
        null
    }
}
