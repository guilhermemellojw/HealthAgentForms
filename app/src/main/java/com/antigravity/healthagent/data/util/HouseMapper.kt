package com.antigravity.healthagent.data.util

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.domain.logger.AppLogger
import com.google.firebase.firestore.DocumentSnapshot
import com.antigravity.healthagent.utils.normalize
import com.antigravity.healthagent.utils.formatStreetName
import com.antigravity.healthagent.utils.toDashDate

fun DocumentSnapshot.toHouseSafe(agentUid: String, agentName: String = ""): House? {
    return try {
        val firestoreLastUpdated = this.getTimestamp("lastUpdated")?.toDate()?.time ?: 0L
        val rawData = (this.getString("data") ?: "").replace("/", "-")
        val sourceName = this.getString("agentName") ?: agentName
        val rawAgentName = normalizeAgentName(sourceName)
        
        val baseHouse = House(
            address = com.antigravity.healthagent.domain.model.VisitAddress(
                blockNumber = (this.getString("blockNumber") ?: "").normalize(),
                streetName = (this.getString("streetName") ?: "").trim().formatStreetName(),
                number = (this.getString("number") ?: "").trim().uppercase(),
                sequence = when (val s = this.get("sequence")) {
                    is Number -> s.toInt()
                    is String -> s.toIntOrNull() ?: 0
                    else -> 0
                },
                complement = when (val c = this.get("complement")) {
                    is Number -> c.toInt()
                    is String -> c.toIntOrNull() ?: 0
                    else -> 0
                },
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
        ).copy(id = 0, uuid = this@toHouseSafe.getString("uuid") ?: "")

        val createdAtRaw = this.get("createdAt")
        val createdAt = when(createdAtRaw) {
            is com.google.firebase.Timestamp -> createdAtRaw.toDate().time
            is Long -> createdAtRaw
            else -> baseHouse.createdAt
        }

        val rawSituation = this.getString("situation")
        val coercedSituation = coerceSituation(rawSituation)
        val finalSituation = if (coercedSituation == Situation.EMPTY) Situation.NONE else coercedSituation

        val finalHouse = baseHouse.apply {
            this.cloudId = this@toHouseSafe.id
        }.copy(
            agentUid = agentUid,
            agentName = rawAgentName,
            data = rawData,
            createdAt = createdAt,
            lastUpdated = firestoreLastUpdated,
            propertyType = coercePropertyType(this.getString("propertyType")),
            situation = finalSituation
        ).apply {
            this.cloudId = this@toHouseSafe.id
        }

        val isBroken = finalHouse.address.streetName.isBlank() && 
                       finalHouse.address.bairro.isBlank() && 
                       finalHouse.address.number.isBlank() && 
                       finalHouse.address.sequence <= 0
        
        if (isBroken) {
            AppLogger.w("HouseMapper", "Discarded broken house from cloud: ${this.id}")
            return null
        }

        return finalHouse
    } catch (e: Exception) {
        AppLogger.e("HouseMapper", "toHouseSafe CRITICAL error for doc ${this.id}: ${e.message}")
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
        AppLogger.e("HouseMapper", "toDayActivitySafe: Error mapping ${this.id}", e)
        null
    }
}

fun House.toFirestoreMap(): Map<String, Any?> {
    return mapOf(
        "blockNumber" to address.blockNumber,
        "streetName" to address.streetName,
        "number" to address.number,
        "sequence" to address.sequence,
        "complement" to address.complement,
        "bairro" to address.bairro,
        "blockSequence" to address.blockSequence,
        "propertyType" to propertyType.name,
        "situation" to situation.name,
        "municipio" to context.municipio,
        "categoria" to context.categoria,
        "zona" to context.zona,
        "tipo" to context.tipo,
        "data" to data.toDashDate(),
        "ciclo" to context.ciclo,
        "atividade" to context.atividade,
        "agentName" to agentName.uppercase(),
        "a1" to treatment.a1, "a2" to treatment.a2, "b" to treatment.b, "c" to treatment.c,
        "d1" to treatment.d1, "d2" to treatment.d2, "e" to treatment.e,
        "eliminados" to treatment.eliminados,
        "larvicida" to treatment.larvicida,
        "comFoco" to treatment.comFoco,
        "localidadeConcluida" to localidadeConcluida,
        "quarteiraoConcluido" to quarteiraoConcluido,
        "listOrder" to listOrder,
        "visitSegment" to visitSegment,
        "agentUid" to agentUid,
        "uuid" to uuid,
        "lastSyncTime" to com.antigravity.healthagent.utils.TimeManager.currentTimeMillis(),
        "createdAt" to createdAt,
        "observation" to observation,
        "latitude" to geo.latitude,
        "longitude" to geo.longitude,
        "focusCaptureTime" to geo.focusCaptureTime,
        "lastUpdated" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
        "editedByAdmin" to editedByAdmin
    )
}

fun DayActivity.toFirestoreMap(): Map<String, Any?> {
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

private fun normalizeAgentName(name: String): String {
    if (name.isBlank()) return ""
    val effectiveName = if (name.contains("@")) name.substringBefore("@") else name
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
