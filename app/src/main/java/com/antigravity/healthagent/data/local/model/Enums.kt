package com.antigravity.healthagent.data.local.model
import com.google.gson.annotations.SerializedName

enum class PropertyType(val code: String, val description: String) {
    @SerializedName("") EMPTY("", "Não Informado"),
    @SerializedName("R") R("R", "Residência"),
    @SerializedName("C") C("C", "Comércio"),
    @SerializedName("TB") TB("TB", "Terreno Baldio"),
    @SerializedName("O") O("O", "Outros"),
    @SerializedName("PE") PE("PE", "Ponto Estratégico");

    override fun toString(): String = code
    val displayValue: String get() = if (code.isEmpty()) description else "$code - $description"
}

enum class Situation(val code: String, val description: String) {
    @SerializedName("") EMPTY("—", "Aberto"),
    @SerializedName("—") NONE("—", "Aberto"),
    @SerializedName("F") F("F", "Fechado"),
    @SerializedName("REC") REC("REC", "Recusado"),
    @SerializedName("A") A("A", "Abandonado"),
    @SerializedName("V") V("V", "Vazio");

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
