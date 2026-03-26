package com.antigravity.healthagent.data.local.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(
    tableName = "houses",
    indices = [
        Index(
            value = ["agentUid", "agentName", "data", "blockNumber", "blockSequence", "streetName", "number", "sequence", "complement", "bairro", "visitSegment"],
            unique = true
        )
    ]
)
data class House(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val blockNumber: String = "",
    val streetName: String = "",
    val number: String = "",
    val sequence: Int? = null,
    val complement: Int? = null,
    val propertyType: PropertyType = PropertyType.EMPTY,
    val situation: Situation = Situation.EMPTY,
    // Daily Header Context
    val municipio: String = "Bom Jardim",
    val bairro: String = "",
    val categoria: String = "BRR",
    val zona: String = "URB",
    val tipo: Int = 2,
    val data: String = "",
    val ciclo: String = "1º",
    val atividade: Int = 4,
    val agentName: String = "",
    // Treatment Details
    val a1: Int = 0,
    val a2: Int = 0,
    val b: Int = 0,
    val c: Int = 0,
    val d1: Int = 0,
    val d2: Int = 0,
    val e: Int = 0,
    val eliminados: Int = 0,
    val larvicida: Double = 0.0,
    val comFoco: Boolean = false,
    val localidadeConcluida: Boolean = false,
    val blockSequence: String = "",
    val quarteiraoConcluido: Boolean = false,
    val listOrder: Long = 0, // For manual reordering
    val visitSegment: Int = 0, // To distinguish return trips to the same street
    val agentUid: String = "", // Crucial for multi-agent data isolation
    val observation: String = "", // Agent notes for the visit
    val createdAt: Long = System.currentTimeMillis(),
    val isSynced: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val focusCaptureTime: Long? = null,
    @get:com.google.firebase.firestore.Exclude
    val lastUpdated: Long = System.currentTimeMillis()
) {
    @com.google.firebase.firestore.DocumentId
    @androidx.room.Ignore
    var cloudId: String? = null
    /**
     * Generates a stable natural key for synchronization and deduplication.
     * WARNING: Any modification to the fields used in this key (agentName, agentUid, data, block, street, number, etc.)
     * will break synchronization by changing the document ID in Firestore, leading to duplicates.
     */
    fun generateNaturalKey(): String {
        val normalizedDate = data.replace("/", "-")
        val normalizedStreet = streetName.trim().replace(Regex("\\s+"), " ")
        val normalizedBairro = bairro.trim().replace(Regex("\\s+"), " ")
        val normalizedAgent = agentName.trim().replace(Regex("\\s+"), " ")
        
        // Uniqueness is guaranteed by agentUid + normalizedAgent + date + address details + visitSegment.
        // We remove createdAt to ensure the key is stable even if the house object is recreated.
        return "${agentUid}_${normalizedAgent}_${normalizedDate}_${blockNumber.trim()}_${blockSequence.trim()}_${normalizedStreet}_${number.trim()}_${sequence ?: 0}_${complement ?: 0}_${normalizedBairro}_${visitSegment}".uppercase()
    }

    fun toFirestoreMap(): Map<String, Any?> {
        return mapOf(
            "blockNumber" to blockNumber,
            "streetName" to streetName,
            "number" to number,
            "sequence" to sequence,
            "complement" to complement,
            "propertyType" to propertyType.name,
            "situation" to situation.name,
            "municipio" to municipio,
            "bairro" to bairro,
            "categoria" to categoria,
            "zona" to zona,
            "tipo" to tipo,
            "data" to data.replace("/", "-"),
            "ciclo" to ciclo,
            "atividade" to atividade,
            "agentName" to agentName.uppercase(),
            "a1" to a1, "a2" to a2, "b" to b, "c" to c, "d1" to d1, "d2" to d2, "e" to e,
            "eliminados" to eliminados,
            "larvicida" to larvicida,
            "comFoco" to comFoco,
            "localidadeConcluida" to localidadeConcluida,
            "blockSequence" to blockSequence,
            "quarteiraoConcluido" to quarteiraoConcluido,
            "listOrder" to listOrder,
            "visitSegment" to visitSegment,
            "agentUid" to agentUid,
            "lastSyncTime" to System.currentTimeMillis(),
            "observation" to observation,
            "latitude" to latitude,
            "longitude" to longitude,
            "focusCaptureTime" to focusCaptureTime,
            "lastUpdated" to com.google.firebase.firestore.FieldValue.serverTimestamp()
        )
    }
}
