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

        val sourceName = if (agentName.isNotBlank()) agentName else (house.agentName.ifBlank { "" })
        val finalAgentName = normalizeAgentName(sourceName)
        
        val finalMunicipio = if (house.municipio.isBlank()) "BOM JARDIM" else house.municipio
        val finalBairro = if (house.bairro.isBlank()) "" else house.bairro
        
        // Healing logic: Default EMPTY to NONE (Aberto) for cloud data
        val finalSituation = if (house.situation == com.antigravity.healthagent.data.local.model.Situation.EMPTY) {
            com.antigravity.healthagent.data.local.model.Situation.NONE
        } else house.situation

        house.copy(
            sequence = finalSequence,
            complement = finalComplement,
            data = house.data.replace("/", "-"),
            createdAt = createdAt, 
            lastUpdated = lastUpdated, 
            agentUid = uid, 
            agentName = finalAgentName,
            municipio = finalMunicipio,
            bairro = finalBairro,
            situation = finalSituation,
            editedByAdmin = this.getBoolean("editedByAdmin") ?: house.editedByAdmin
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

        val rawDate = this.getString("date") ?: activity.date
        val finalDate = rawDate.ifBlank { this.id }.replace("/", "-")

        val sourceName = if (agentName.isNotBlank()) agentName else (activity.agentName.ifBlank { "" })
        val finalAgentName = normalizeAgentName(sourceName)
        
        activity.copy(
            status = finalStatus,
            date = finalDate,
            isClosed = isClosed,
            isManualUnlock = isManualUnlock,
            lastUpdated = lastUpdated, 
            agentUid = uid, 
            agentName = finalAgentName,
            editedByAdmin = this.getBoolean("editedByAdmin") ?: activity.editedByAdmin
        )
    } catch (e: Exception) {
        android.util.Log.e("FirestoreMappers", "toDayActivitySafe: Error mapping ${this.id}", e)
        null
    }
}

private fun normalizeAgentName(name: String): String {
    if (name.isBlank()) return ""
    // If it's an email, take the part before @ to avoid displaying emails as names in the UI
    val effectiveName = if (name.contains("@")) name.substringBefore("@") else name
    
    val normalized = java.text.Normalizer.normalize(effectiveName, java.text.Normalizer.Form.NFD)
    return Regex("\\p{InCombiningDiacriticalMarks}+").replace(normalized, "")
        .trim()
        .uppercase()
        .replace(Regex("\\s+"), " ")
}
