package com.antigravity.healthagent.data.util

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import com.google.firebase.firestore.DocumentSnapshot
import com.antigravity.healthagent.utils.normalize
import com.antigravity.healthagent.utils.formatStreetName
import com.antigravity.healthagent.utils.toDashDate

fun DocumentSnapshot.toHouseSafe(agentUid: String, agentName: String = ""): House? {
    return try {
        // 1. Try standard Firestore mapping first
        val house = try { this.toObject(House::class.java) } catch (e: Exception) { null }
        
        // 2. Manually extract critical metadata to bypass @Exclude and handle mismatches
        val firestoreLastUpdated = this.getTimestamp("lastUpdated")?.toDate()?.time ?: 0L
        val rawData = (this.getString("data") ?: house?.data ?: "").replace("/", "-")
        val sourceName = this.getString("agentName") ?: house?.agentName ?: agentName
        val rawAgentName = normalizeAgentName(sourceName)
        
        // 3. Robust Construction: Fallback to manual field extraction if toObject failed
        val baseHouse = (house ?: House(
            address = com.antigravity.healthagent.domain.model.VisitAddress(
                blockNumber = (this.getString("blockNumber") ?: "").normalize(),
                streetName = (this.getString("streetName") ?: "").trim().formatStreetName(),
                number = (this.getString("number") ?: "").trim().uppercase(),
                sequence = (this.get("sequence") as? Number)?.toInt() ?: 0,
                complement = (this.get("complement") as? Number)?.toInt() ?: 0,
                bairro = (this.getString("bairro") ?: "").normalize(),
                blockSequence = (this.getString("blockSequence") ?: "").normalize()
            ),
            context = com.antigravity.healthagent.domain.model.DailyContext(
                municipio = this.getString("municipio") ?: "Bom Jardim",
                categoria = this.getString("categoria") ?: "BRR",
                zona = this.getString("zona") ?: "URB",
                tipo = (this.get("tipo") as? Number)?.toInt() ?: 2,
                ciclo = this.getString("ciclo") ?: "1º",
                atividade = (this.get("atividade") as? Number)?.toInt() ?: 4
            ),
            treatment = com.antigravity.healthagent.domain.model.TreatmentData(
                a1 = (this.get("a1") as? Number)?.toInt() ?: 0,
                a2 = (this.get("a2") as? Number)?.toInt() ?: 0,
                b = (this.get("b") as? Number)?.toInt() ?: 0,
                c = (this.get("c") as? Number)?.toInt() ?: 0,
                d1 = (this.get("d1") as? Number)?.toInt() ?: 0,
                d2 = (this.get("d2") as? Number)?.toInt() ?: 0,
                e = (this.get("e") as? Number)?.toInt() ?: 0,
                eliminados = (this.get("eliminados") as? Number)?.toInt() ?: 0,
                larvicida = (this.get("larvicida") as? Number)?.toDouble() ?: 0.0,
                comFoco = this.getBoolean("comFoco") ?: false
            ),
            localidadeConcluida = this.getBoolean("localidadeConcluida") ?: false,
            quarteiraoConcluido = this.getBoolean("quarteiraoConcluido") ?: false,
            listOrder = (this.get("listOrder") as? Number)?.toLong() ?: 0L,
            visitSegment = (this.get("visitSegment") as? Number)?.toInt() ?: 0,
            observation = this.getString("observation") ?: "",
            geo = com.antigravity.healthagent.domain.model.GeoCapture(
                latitude = this.get("latitude") as? Double,
                longitude = this.get("longitude") as? Double,
                focusCaptureTime = (this.get("focusCaptureTime") as? Number)?.toLong()
            ),
            editedByAdmin = this.getBoolean("editedByAdmin") ?: false
        )).copy(id = 0) // RESET ID to allow local auto-generation

        // Extract createdAt robustly
        val createdAtRaw = this.get("createdAt")
        val createdAt = when(createdAtRaw) {
            is com.google.firebase.Timestamp -> createdAtRaw.toDate().time
            is Long -> createdAtRaw
            else -> baseHouse.createdAt
        }

        // Healing logic: Default EMPTY to NONE for situation
        val rawSituation = this.getString("situation") ?: house?.situation?.name
        val coercedSituation = coerceSituation(rawSituation)
        val finalSituation = if (coercedSituation == Situation.EMPTY) Situation.NONE else coercedSituation

        val finalHouse = baseHouse.apply {
            this.cloudId = this@toHouseSafe.id
        }.copy(
            agentUid = house?.agentUid?.ifBlank { agentUid } ?: agentUid,
            agentName = rawAgentName,
            data = rawData,
            createdAt = createdAt,
            lastUpdated = firestoreLastUpdated,
            propertyType = coercePropertyType(this.getString("propertyType") ?: house?.propertyType?.name),
            situation = finalSituation
        ).apply {
            this.cloudId = this@toHouseSafe.id
        }

        // SURGICAL FILTER: Discard "Zombies" (Houses without core address info)
        val isBroken = finalHouse.address.streetName.isBlank() && 
                       finalHouse.address.bairro.isBlank() && 
                       finalHouse.address.number.isBlank() && 
                       finalHouse.address.sequence <= 0
        
        if (isBroken) {
            android.util.Log.w("FirestoreMapper", "Discarded broken house from cloud: ${this.id}")
            return null
        }

        return finalHouse
    } catch (e: Exception) {
        android.util.Log.e("FirestoreMapper", "toHouseSafe CRITICAL error for doc ${this.id}: ${e.message}")
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
        
        val finalAgentUid = activity.agentUid.ifBlank { uid }
        
        activity.copy(
            status = finalStatus,
            date = finalDate,
            isClosed = isClosed,
            isManualUnlock = isManualUnlock,
            lastUpdated = lastUpdated, 
            agentUid = finalAgentUid, 
            agentName = finalAgentName,
            editedByAdmin = this.getBoolean("editedByAdmin") ?: activity.editedByAdmin
        )
    } catch (e: Exception) {
        android.util.Log.e("FirestoreMapper", "toDayActivitySafe: Error mapping ${this.id}", e)
        null
    }
}

private fun normalizeAgentName(name: String): String {
    if (name.isBlank()) return ""
    // If it's an email, take the part before @ to avoid displaying emails as names in the UI
    val effectiveName = if (name.contains("@")) name.substringBefore("@") else name
    
    // SURGICAL FIX: Stop stripping accents from names.
    return effectiveName.trim()
        .uppercase()
        .replace(Regex("\\s+"), " ")
}

private fun coercePropertyType(raw: String?): PropertyType {
    if (raw == null) return PropertyType.EMPTY
    return try {
        PropertyType.valueOf(raw.uppercase())
    } catch (e: Exception) {
        when (raw.uppercase()) {
            "RESIDÊNCIA", "RESIDENCIA" -> PropertyType.R
            "COMÉRCIO", "COMERCIO" -> PropertyType.C
            "TERRENO BALDIO" -> PropertyType.TB
            "OUTROS" -> PropertyType.O
            "PONTO ESTRATÉGICO", "PONTO ESTRATEGICO" -> PropertyType.PE
            else -> PropertyType.EMPTY
        }
    }
}

private fun coerceSituation(raw: String?): Situation {
    if (raw == null) return Situation.EMPTY
    return try {
        Situation.valueOf(raw.uppercase())
    } catch (e: Exception) {
        when (raw.uppercase()) {
            "FECHADO" -> Situation.F
            "RECUSADO" -> Situation.REC
            "ABANDONADO" -> Situation.A
            "VAZIO" -> Situation.V
            "ABERTO" -> Situation.EMPTY
            else -> Situation.EMPTY
        }
    }
}
