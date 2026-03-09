package com.antigravity.healthagent.data.local.model

enum class PropertyType(val code: String, val description: String) {
    EMPTY("", "Não Informado"),
    R("R", "Residência"),
    C("C", "Comércio"),
    TB("TB", "Terreno Baldio"),
    O("O", "Outros"),
    PE("PE", "Ponto Estratégico");

    override fun toString(): String = code
    val displayValue: String get() = if (code.isEmpty()) description else "$code - $description"
}

enum class Situation(val code: String, val description: String) {
    EMPTY("", "Não Informado"),
    NONE("—", "Aberto"),
    F("F", "Fechado"),
    REC("REC", "Recusado"),
    A("A", "Abandonado"),
    V("V", "Vazio");

    override fun toString(): String = code
    val displayValue: String get() = if (code.isEmpty() || code == "—") description else "$code - $description"
}

enum class Treatment(val code: String) {
    A1("A1"),
    A2("A2"),
    B("B"),
    C("C"),
    D1("D1"),
    D2("D2"),
    E("E"),
    ELIMINADOS("ELIMINADOS"),
    TRATADOS("TRATADOS");

    override fun toString(): String = code
}
