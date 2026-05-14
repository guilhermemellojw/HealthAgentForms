package com.antigravity.healthagent.domain.model

/**
 * Value Object para agrupar os campos de geolocalização de focos.
 * Estes campos são nulláveis e só são preenchidos quando o agente
 * registra um foco com captura GPS ou seleção manual no mapa.
 *
 * Used as @Embedded in the House entity — column names match the SQLite schema.
 */
data class GeoCapture(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val focusCaptureTime: Long? = null
) {
    val hasLocation: Boolean
        get() = latitude != null && longitude != null
}
