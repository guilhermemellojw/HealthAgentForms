package com.antigravity.healthagent.utils

object AppConstants {
    val BAIRROS = listOf(
        "ALTO SÃO JOSÉ I",
        "ALTO SÃO JOSÉ II",
        "ALVES",
        "BANQUETE",
        "BARRA ALEGRE",
        "BEM-TE-VI",
        "CAMPO BELO",
        "CENTRO",
        "JARDIM BOA ESPERANÇA",
        "JARDIM ORNELAS",
        "NOVO MUNDO",
        "SANTO ANTÔNIO",
        "SÃO JOSÉ",
        "SÃO MIGUEL",
        "VELOSO",
        "VIVENDAS MARCIA",
        "BELA VISTA",
        "JARDIM OURO VERDE"
    ).sorted()

    val AGENT_NAMES = listOf(
        "BEATRIZ MONTEIRO",
        "EDIMAR",
        "FLÁVIO CALDEIRA",
        "GUILHERME MELLO",
        "IANÊ ALVES",
        "LUIZ CARLOS JUNIOR",
        "RÔMULO GRATIVOL",
        "SYNARA GASPAR",
        "TIAGO MADUREIRA",
        "UBIRATAN",
        "VINÍCIUS CHAVES"
    ).sorted()

    val TIPO_OPTIONS = listOf(
        "1 - SEDE",
        "2 - OUTROS"
    )

    val ATIVIDADE_OPTIONS = listOf(
        "1 - LI",
        "2 - LI+T",
        "3 - PE",
        "4 - T",
        "5 - DF",
        "6 - PVE"
    )

    const val MAPS_URL = "https://www.google.com/maps/d/embed?mid=1NpRquCEtQSu0aM13rHr8em0FYmerTDs"

    // 10 seconds buffer to prevent clock skew overwriting local data
    const val SYNC_CONFLICT_THRESHOLD_MS = 10 * 1000L 

    // Version safety: minimum version allowed to sync if enforced by admin
    const val MIN_VERSION_CODE = 3 // Represents version 2.0
}
