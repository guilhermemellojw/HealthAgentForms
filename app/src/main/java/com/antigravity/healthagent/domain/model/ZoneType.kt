package com.antigravity.healthagent.domain.model

enum class ZoneType(val displayValue: String) {
    URB("URB"),
    RUR("RUR");

    companion object {
        fun fromString(value: String): ZoneType {
            return values().firstOrNull { it.displayValue.uppercase() == value.uppercase() } ?: URB
        }
    }
}
