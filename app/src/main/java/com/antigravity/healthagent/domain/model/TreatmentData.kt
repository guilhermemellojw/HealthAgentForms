package com.antigravity.healthagent.domain.model

import androidx.room.ColumnInfo

/**
 * Value Object para agrupar os parâmetros de produção/tratamento de um imóvel.
 * Isso ajuda a limpar a passagem de parâmetros e centralizar cálculos de totais.
 * 
 * Used as @Embedded in the House entity — column names match the SQLite schema.
 */
data class TreatmentData(
    @ColumnInfo(defaultValue = "0") val a1: Int = 0,
    @ColumnInfo(defaultValue = "0") val a2: Int = 0,
    @ColumnInfo(defaultValue = "0") val b: Int = 0,
    @ColumnInfo(defaultValue = "0") val c: Int = 0,
    @ColumnInfo(defaultValue = "0") val d1: Int = 0,
    @ColumnInfo(defaultValue = "0") val d2: Int = 0,
    @ColumnInfo(defaultValue = "0") val e: Int = 0,
    @ColumnInfo(defaultValue = "0") val eliminados: Int = 0,
    @ColumnInfo(defaultValue = "0.0") val larvicida: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val comFoco: Boolean = false
) {
    val hasAnyTreatment: Boolean 
        get() = (a1 + a2 + b + c + d1 + d2 + e + eliminados) > 0 || larvicida > 0.0 || comFoco

    val totalDeposits: Int
        get() = a1 + a2 + b + c + d1 + d2 + e

    val formattedSummary: String
        get() {
            val parts = mutableListOf<String>()
            if (a1 > 0) parts.add("A1: $a1")
            if (a2 > 0) parts.add("A2: $a2")
            if (b > 0) parts.add("B: $b")
            if (c > 0) parts.add("C: $c")
            if (d1 > 0) parts.add("D1: $d1")
            if (d2 > 0) parts.add("D2: $d2")
            if (e > 0) parts.add("E: $e")
            if (eliminados > 0) parts.add("Elim: $eliminados")
            if (larvicida > 0.0) parts.add("Larv: ${larvicida}g")
            return parts.joinToString(" | ")
        }
}
