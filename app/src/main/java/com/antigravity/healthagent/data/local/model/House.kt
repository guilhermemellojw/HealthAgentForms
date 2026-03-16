package com.antigravity.healthagent.data.local.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "houses")
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
    val createdAt: Long = System.currentTimeMillis()
) {
    @com.google.firebase.firestore.DocumentId
    @androidx.room.Ignore
    var cloudId: String? = null
    // Helper to generate a consistent natural key for deduplication
    fun generateNaturalKey(): String {
        val normalizedDate = data.replace("/", "-")
        val normalizedStreet = streetName.trim().replace(Regex("\\s+"), " ")
        val normalizedBairro = bairro.trim().replace(Regex("\\s+"), " ")
        val normalizedAgent = agentName.trim().replace(Regex("\\s+"), " ")
        
        return "${normalizedAgent}_${normalizedDate}_${blockNumber.trim()}_${blockSequence.trim()}_${normalizedStreet}_${number.trim()}_${sequence ?: 0}_${complement ?: 0}_${normalizedBairro}_${visitSegment}".uppercase()
    }
}
