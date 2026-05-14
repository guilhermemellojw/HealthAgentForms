package com.antigravity.healthagent.domain.model

import androidx.room.ColumnInfo

/**
 * Value Object para agrupar os campos de contexto diário do cabeçalho do boletim.
 * Estes campos raramente mudam durante uma sessão de trabalho e são
 * propagados automaticamente para cada nova casa inserida no dia.
 *
 * Used as @Embedded in the House entity — column names match the SQLite schema.
 */
data class DailyContext(
    @ColumnInfo(defaultValue = "'Bom Jardim'") val municipio: String = "Bom Jardim",
    @ColumnInfo(defaultValue = "'BRR'") val categoria: String = "BRR",
    @ColumnInfo(defaultValue = "'URB'") val zona: String = "URB",
    @ColumnInfo(defaultValue = "2") val tipo: Int = 2,
    @ColumnInfo(defaultValue = "'1º'") val ciclo: String = "1º",
    @ColumnInfo(defaultValue = "4") val atividade: Int = 4
)
