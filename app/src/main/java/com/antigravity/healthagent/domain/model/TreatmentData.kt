package com.antigravity.healthagent.domain.model

/**
 * Value Object para agrupar os parâmetros de produção/tratamento de um imóvel.
 * Isso ajuda a limpar a passagem de parâmetros e centralizar cálculos de totais.
 */
data class TreatmentData(
    val a1: Int = 0,
    val a2: Int = 0,
    val b: Int = 0,
    val c: Int = 0,
    val d1: Int = 0,
    val d2: Int = 0,
    val e: Int = 0,
    val eliminados: Int = 0,
    val larvicida: Double = 0.0,
    val comFoco: Boolean = false
) {
    val hasAnyTreatment: Boolean 
        get() = (a1 + a2 + b + c + d1 + d2 + e + eliminados) > 0 || larvicida > 0.0 || comFoco
}
