package com.antigravity.healthagent.domain.model

enum class ActivityType(val displayValue: String) {
    NORMAL("NORMAL"),
    FERIADO("FERIADO"),
    PONTO_FACULTATIVO("PONTO FACULTATIVO"),
    REUNIAO("REUNIÃO"),
    TREINAMENTO("TREINAMENTO");

    companion object {
        fun fromString(value: String): ActivityType {
            return values().firstOrNull { it.displayValue.uppercase() == value.uppercase() } ?: NORMAL
        }
    }
}
