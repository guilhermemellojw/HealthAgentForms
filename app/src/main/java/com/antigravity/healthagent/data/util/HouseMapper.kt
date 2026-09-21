package com.antigravity.healthagent.data.util

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.domain.logger.AppLogger
import com.antigravity.healthagent.utils.TimeManager
import com.google.firebase.firestore.DocumentSnapshot
import com.antigravity.healthagent.utils.normalize
import com.antigravity.healthagent.utils.formatStreetName
import com.antigravity.healthagent.utils.toDashDate
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

private val isoFormats: List<ThreadLocal<SimpleDateFormat>> = listOf(
    "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
    "yyyy-MM-dd'T'HH:mm:ssZ",
    "yyyy-MM-dd'T'HH:mm:ss.SSS",
    "yyyy-MM-dd'T'HH:mm:ss"
).map { pattern ->
    ThreadLocal.withInitial {
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }
}

/**
 * Timestamp remoto (Firestore Timestamp, epoch ms/segundos ou ISO-8601 do
 * Supabase) -> epoch ms. Usado pelo pull das duas fontes.
 */
fun parseRemoteTimestamp(value: Any?, fallback: Long = 0L): Long {
    return parseRemoteTimestampOrNull(value) ?: fallback
}

fun parseRemoteTimestampOrNull(value: Any?): Long? {
    return try {
        when (value) {
            null -> null
            is com.google.firebase.Timestamp -> value.toDate().time
            is Number -> {
                val n = value.toLong()
                if (n in 1..9999999999L) n * 1000 else n // segundos -> ms
            }
            is String -> {
                val s = value.trim()
                if (s.isEmpty()) return null
                s.toLongOrNull()?.let { return if (it in 1..9999999999L) it * 1000 else it }
                // Normaliza só o timezone: "Z" -> "+0000", "+HH:MM" -> "+HHMM".
                // (Nunca toca nos ":" das horas.)
                var norm = s
                if (norm.endsWith("Z", ignoreCase = true)) norm = norm.dropLast(1) + "+0000"
                norm = norm.replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
                for (f in isoFormats) {
                    try {
                        val ms = f.get()?.parse(norm)?.time
                        if (ms != null) return ms
                    } catch (_: Exception) { }
                }
                null
            }
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

private fun Map<String, Any?>.mapInt(key: String, default: Int): Int {
    return when (val v = this[key]) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull() ?: default
        else -> default
    }
}

private fun Map<String, Any?>.mapLong(key: String, default: Long): Long {
    return when (val v = this[key]) {
        is Number -> v.toLong()
        is String -> v.toLongOrNull() ?: default
        else -> default
    }
}

private fun Map<String, Any?>.mapDouble(key: String, default: Double): Double {
    return when (val v = this[key]) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull() ?: default
        else -> default
    }
}

/**
 * Chaves de tombstone (deleted_house_ids / deleted_activity_dates) — backend-neutro.
 * No Supabase virão das linhas com deleted_at; mesma forma de saída.
 */
fun Map<String, Any?>.tombstoneKeys(field: String): Set<String> {
    @Suppress("UNCHECKED_CAST")
    return ((this[field] as? List<String>) ?: emptyList())
        .map { it.replace("/", "-") }
        .toSet()
}

fun DocumentSnapshot.tombstoneKeys(field: String): Set<String> {
    return (this.data ?: emptyMap()).tombstoneKeys(field)
}

fun Map<String, Any?>.toHouseSafe(docId: String, agentUid: String, agentName: String = ""): House? {
    return try {
        val firestoreLastUpdated = parseRemoteTimestamp(this["lastUpdated"], 0L)
        val rawData = ((this["data"] as? String) ?: "").replace("/", "-")
        val sourceName = (this["agentName"] as? String) ?: agentName
        val rawAgentName = normalizeAgentName(sourceName)

        val baseHouse = House(
            address = com.antigravity.healthagent.domain.model.VisitAddress(
                blockNumber = ((this["blockNumber"] as? String) ?: "").normalize(),
                streetName = ((this["streetName"] as? String) ?: "").trim().formatStreetName(),
                number = ((this["number"] as? String) ?: "").trim().uppercase(),
                sequence = this.mapInt("sequence", 0),
                complement = this.mapInt("complement", 0),
                bairro = ((this["bairro"] as? String) ?: "").normalize(),
                blockSequence = ((this["blockSequence"] as? String) ?: "").normalize()
            ),
            context = com.antigravity.healthagent.domain.model.DailyContext(
                municipio = this["municipio"] as? String ?: "Bom Jardim",
                categoria = this["categoria"] as? String ?: "BRR",
                zona = this["zona"] as? String ?: "URB",
                tipo = this.mapInt("tipo", 2),
                ciclo = this["ciclo"] as? String ?: "1º",
                atividade = this.mapInt("atividade", 4)
            ),
            treatment = com.antigravity.healthagent.domain.model.TreatmentData(
                a1 = this.mapInt("a1", 0),
                a2 = this.mapInt("a2", 0),
                b = this.mapInt("b", 0),
                c = this.mapInt("c", 0),
                d1 = this.mapInt("d1", 0),
                d2 = this.mapInt("d2", 0),
                e = this.mapInt("e", 0),
                eliminados = this.mapInt("eliminados", 0),
                larvicida = this.mapDouble("larvicida", 0.0),
                comFoco = this["comFoco"] as? Boolean ?: false
            ),
            localidadeConcluida = this["localidadeConcluida"] as? Boolean ?: false,
            quarteiraoConcluido = this["quarteiraoConcluido"] as? Boolean ?: false,
            listOrder = this.mapLong("listOrder", 0L),
            visitSegment = this.mapInt("visitSegment", 0),
            observation = this["observation"] as? String ?: "",
            geo = com.antigravity.healthagent.domain.model.GeoCapture(
                latitude = (this["latitude"] as? Number)?.toDouble(),
                longitude = (this["longitude"] as? Number)?.toDouble(),
                focusCaptureTime = parseRemoteTimestampOrNull(this["focusCaptureTime"])
            ),
            editedByAdmin = this["editedByAdmin"] as? Boolean ?: false
        ).copy(id = 0, uuid = this["uuid"] as? String ?: "")

        val createdAt = parseRemoteTimestamp(this["createdAt"], baseHouse.createdAt)

        val rawSituation = this["situation"] as? String
        val coercedSituation = coerceSituation(rawSituation)
        val finalSituation = if (coercedSituation == Situation.EMPTY) Situation.NONE else coercedSituation

        val finalHouse = baseHouse.apply {
            this.cloudId = docId
        }.copy(
            agentUid = agentUid,
            agentName = rawAgentName,
            data = rawData,
            createdAt = createdAt,
            lastUpdated = firestoreLastUpdated,
            propertyType = coercePropertyType(this["propertyType"] as? String),
            situation = finalSituation
        ).apply {
            this.cloudId = docId
        }

        val isBroken = finalHouse.address.streetName.isBlank() && 
                       finalHouse.address.bairro.isBlank() && 
                       finalHouse.address.number.isBlank() && 
                       finalHouse.address.sequence <= 0
        
        if (isBroken) {
            AppLogger.w("HouseMapper", "Discarded broken house from cloud: $docId")
            return null
        }

        return finalHouse
    } catch (e: Exception) {
        AppLogger.e("HouseMapper", "toHouseSafe CRITICAL error for doc $docId: ${e.message}")
        null
    }
}

fun DocumentSnapshot.toHouseSafe(agentUid: String, agentName: String = ""): House? {
    return (this.data ?: emptyMap()).toHouseSafe(this.id, agentUid, agentName)
}

fun Map<String, Any?>.toDayActivitySafe(docId: String, uid: String, agentName: String = ""): DayActivity? {
    return try {
        val rawDate = ((this["date"] as? String) ?: "").ifBlank { docId }.replace("/", "-")
        val sourceName = if (agentName.isNotBlank()) agentName else ((this["agentName"] as? String) ?: "")
        DayActivity(
            date = rawDate,
            status = (this["status"] as? String) ?: "",
            isClosed = this["isClosed"] as? Boolean ?: false,
            isManualUnlock = this["isManualUnlock"] as? Boolean ?: false,
            agentName = normalizeAgentName(sourceName),
            agentUid = uid,
            editedByAdmin = this["editedByAdmin"] as? Boolean ?: false,
            lastUpdated = parseRemoteTimestamp(this["lastUpdated"], TimeManager.currentTimeMillis())
        )
    } catch (e: Exception) {
        AppLogger.e("HouseMapper", "toDayActivitySafe: Error mapping $docId", e)
        null
    }
}

fun DocumentSnapshot.toDayActivitySafe(uid: String, agentName: String = ""): DayActivity? {
    return (this.data ?: emptyMap()).toDayActivitySafe(this.id, uid, agentName)
}

fun House.toFirestoreMap(): Map<String, Any?> {
    return mapOf(
        "blockNumber" to address.blockNumber,
        "streetName" to address.streetName,
        "number" to address.number,
        "sequence" to address.sequence,
        "complement" to address.complement,
        "bairro" to address.bairro.normalize(),
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

private val isoWriteFmt = ThreadLocal.withInitial {
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
}

fun epochMsToIso(ms: Long): String = isoWriteFmt.get()!!.format(java.util.Date(ms))

fun nowIsoUtc(): String = epochMsToIso(com.antigravity.healthagent.utils.TimeManager.currentTimeMillis())

/**
 * House -> linha houses (snake_case) p/ upsert PostgREST.
 * Sem updated_at (trigger carimba), sem data_date (derivada), sem deleted_at
 * (linhas ativas). tipo/atividade como texto (colunas TEXT).
 */
fun House.toSupabaseRow(agentUid: String, agentName: String, editedByAdmin: Boolean): kotlinx.serialization.json.JsonObject {
    return kotlinx.serialization.json.buildJsonObject {
        put("agent_id", agentUid)
        put("natural_key", generateNaturalKey())
        put("data_text", data.toDashDate())
        put("street_name", address.streetName)
        put("number", address.number)
        put("block_number", address.blockNumber)
        put("block_sequence", address.blockSequence)
        put("sequence", address.sequence)
        put("complement", address.complement)
        put("visit_segment", visitSegment)
        put("list_order", listOrder)
        put("situation", situation.name)
        put("property_type", propertyType.name)
        put("com_foco", treatment.comFoco)
        put("a1", treatment.a1)
        put("a2", treatment.a2)
        put("b", treatment.b)
        put("c", treatment.c)
        put("d1", treatment.d1)
        put("d2", treatment.d2)
        put("e", treatment.e)
        put("eliminados", treatment.eliminados)
        put("larvicida", treatment.larvicida)
        if (geo.latitude != null) put("latitude", geo.latitude!!) else put("latitude", kotlinx.serialization.json.JsonNull)
        if (geo.longitude != null) put("longitude", geo.longitude!!) else put("longitude", kotlinx.serialization.json.JsonNull)
        if (geo.focusCaptureTime != null) put("focus_capture_time", epochMsToIso(geo.focusCaptureTime!!)) else put("focus_capture_time", kotlinx.serialization.json.JsonNull)
        put("observation", observation)
        put("municipio", context.municipio)
        put("bairro", address.bairro)
        put("categoria", context.categoria)
        put("zona", context.zona)
        put("tipo", context.tipo.toString())
        put("atividade", context.atividade.toString())
        put("ciclo", context.ciclo)
        put("localidade_concluida", localidadeConcluida)
        put("quarteirao_concluido", quarteiraoConcluido)
        put("agent_name", agentName)
        put("agent_uid", agentUid)
        put("client_uuid", uuid)
        put("edited_by_admin", editedByAdmin)
        put("created_at", epochMsToIso(createdAt))
    }
}

fun DayActivity.toSupabaseRow(agentUid: String, agentName: String, editedByAdmin: Boolean): kotlinx.serialization.json.JsonObject {
    return kotlinx.serialization.json.buildJsonObject {
        put("agent_id", agentUid)
        put("date_text", date.replace("/", "-"))
        put("status", status)
        put("is_closed", isClosed)
        put("is_manual_unlock", isManualUnlock)
        put("agent_name", agentName)
        put("agent_uid", agentUid)
        put("edited_by_admin", editedByAdmin)
    }
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
