package com.antigravity.healthagent.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class DailyContext(
    val municipio: String = "Bom Jardim",
    val categoria: String = "BRR",
    val zona: String = "URB",
    val tipo: Int = 2,
    val ciclo: String = "1º",
    val atividade: Int = 4
)
