package com.antigravity.healthagent.data.backup

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.DayActivity
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupManagerTest {

    private val gson: Gson = GsonBuilder()
        .setLenient()
        .serializeSpecialFloatingPointValues()
        .registerTypeAdapter(Boolean::class.java, SafeBooleanAdapter())
        .registerTypeAdapter(java.lang.Boolean::class.java, SafeBooleanAdapter())
        .registerTypeAdapter(Int::class.java, SafeIntAdapter())
        .registerTypeAdapter(java.lang.Integer::class.java, SafeIntAdapter())
        .registerTypeAdapter(Long::class.java, SafeLongAdapter())
        .registerTypeAdapter(java.lang.Long::class.java, SafeLongAdapter())
        .registerTypeAdapter(Double::class.java, SafeDoubleAdapter())
        .registerTypeAdapter(java.lang.Double::class.java, SafeDoubleAdapter())
        .registerTypeAdapter(House::class.java, BackupManager.HouseDeserializer())
        .create()

    @Test
    fun testLegacyFlatStructure() {
        // This JSON simulates the old flat structure where fields are at the root
        val json = """
            {
                "houses": [
                    {
                        "a1": 10,
                        "larvicida": 1.5,
                        "municipio": "TEST CITY",
                        "blockNumber": "100",
                        "streetName": "TEST STREET",
                        "data": "10-01-2024",
                        "agentName": "TEST AGENT",
                        "propertyType": "R",
                        "situation": "NONE"
                    }
                ]
            }
        """.trimIndent()

        val backupData = parseNew(json)
        val house = backupData.houses[0]

        // Assert nested objects are correctly populated from flat fields
        assertEquals(10, house.treatment.a1)
        assertEquals(1.5, house.treatment.larvicida, 0.001)
        assertEquals("TEST CITY", house.context.municipio)
        assertEquals("100", house.address.blockNumber)
        assertEquals("TEST STREET", house.address.streetName)
    }

    @Test
    fun testPortugueseLegacyNames() {
        // This JSON simulates older Portuguese field names used in early versions
        val json = """
            {
                "houses": [
                    {
                        "quarteirao": "42",
                        "logradouro": "RUA DAS FLORES",
                        "numero": "123",
                        "localidade": "BAIRRO NOVO",
                        "situacao": "V",
                        "tipo_imovel": "TB",
                        "data_visita": "12-05-2026",
                        "agente": "MARCOS AGENTE",
                        "foco": 1,
                        "gramas": 10.5
                    }
                ]
            }
        """.trimIndent()

        val backupData = parseNew(json)
        val house = backupData.houses[0]

        assertEquals("42", house.address.blockNumber)
        assertEquals("RUA DAS FLORES", house.address.streetName)
        assertEquals("123", house.address.number)
        assertEquals("BAIRRO NOVO", house.address.bairro)
        assertEquals(com.antigravity.healthagent.data.local.model.Situation.V, house.situation)
        assertEquals(com.antigravity.healthagent.data.local.model.PropertyType.TB, house.propertyType)
        assertEquals("12-05-2026", house.data)
        assertEquals("MARCOS AGENTE", house.agentName)
        assertEquals(true, house.treatment.comFoco)
        assertEquals(10.5, house.treatment.larvicida, 0.001)
    }

    // --- Custom Type Adapters for Legacy Compatibility (Copied from BackupManager for testing) ---

    private class SafeBooleanAdapter : TypeAdapter<Boolean>() {
        override fun write(out: JsonWriter, value: Boolean?) { out.value(value) }
        override fun read(reader: JsonReader): Boolean? {
            return when (reader.peek()) {
                JsonToken.BOOLEAN -> reader.nextBoolean()
                JsonToken.NUMBER -> reader.nextInt() != 0
                JsonToken.STRING -> {
                    val s = reader.nextString().lowercase()
                    s == "true" || s == "1"
                }
                JsonToken.NULL -> { reader.nextNull(); false }
                else -> { reader.skipValue(); false }
            }
        }
    }

    private class SafeIntAdapter : TypeAdapter<Int>() {
        override fun write(out: JsonWriter, value: Int?) { out.value(value) }
        override fun read(reader: JsonReader): Int? {
            return when (reader.peek()) {
                JsonToken.NUMBER -> reader.nextInt()
                JsonToken.STRING -> {
                    val s = reader.nextString()
                    if (s.isEmpty()) 0 else try { s.toDouble().toInt() } catch(e: Exception) { 0 }
                }
                JsonToken.NULL -> { reader.nextNull(); 0 }
                else -> { reader.skipValue(); 0 }
            }
        }
    }

    private class SafeLongAdapter : TypeAdapter<Long>() {
        override fun write(out: JsonWriter, value: Long?) { out.value(value) }
        override fun read(reader: JsonReader): Long? {
            return when (reader.peek()) {
                JsonToken.NUMBER -> reader.nextLong()
                JsonToken.STRING -> {
                    val s = reader.nextString()
                    if (s.isEmpty()) 0L else try { s.toDouble().toLong() } catch(e: Exception) { 0L }
                }
                JsonToken.NULL -> { reader.nextNull(); 0L }
                else -> { reader.skipValue(); 0L }
            }
        }
    }

    private class SafeDoubleAdapter : TypeAdapter<Double>() {
        override fun write(out: JsonWriter, value: Double?) { out.value(value) }
        override fun read(reader: JsonReader): Double? {
            return when (reader.peek()) {
                JsonToken.NUMBER -> reader.nextDouble()
                JsonToken.STRING -> {
                    val s = reader.nextString()
                    if (s.isEmpty() || s == "NaN" || s == "Infinity") 0.0 else try { s.toDouble() } catch(e: Exception) { 0.0 }
                }
                JsonToken.NULL -> { reader.nextNull(); 0.0 }
                else -> { reader.skipValue(); 0.0 }
            }
        }
    }

    @Test
    fun testLegacyBooleanAsInt() {
        val json = """
            {
                "houses": [
                    {
                        "address": {"blockNumber": "1", "streetName": "Rua A", "bairro": "CENTRO"},
                        "data": "10-01-2024", 
                        "comFoco": 1, 
                        "isSynced": 0
                    }
                ]
            }
        """.trimIndent()
        
        val backupData = parseNew(json)
        val house = backupData.houses[0]
        assertEquals(true, house.treatment.comFoco)
        assertEquals(false, house.isSynced)
    }

    @Test
    fun testLegacyEmptyStringsForNumbers() {
        val json = """
            {
                "houses": [
                    {
                        "address": {"blockNumber": "1", "streetName": "Rua A", "sequence": "", "complement": ""},
                        "data": "10-01-2024", 
                        "larvicida": ""
                    }
                ]
            }
        """.trimIndent()
        
        val backupData = parseNew(json)
        val house = backupData.houses[0]
        assertEquals(0, house.address.sequence)
        assertEquals(0, house.address.complement)
        assertEquals(0.0, house.treatment.larvicida, 0.001)
    }

    @Test
    fun testLegacyNaNValues() {
        val json = """
            {
                "houses": [
                    {
                        "address": {"blockNumber": "1", "streetName": "Rua A"},
                        "data": "10-01-2024", 
                        "latitude": "NaN", 
                        "longitude": "Infinity"
                    }
                ]
            }
        """.trimIndent()
        
        val backupData = parseNew(json)
        val house = backupData.houses[0]
        assertEquals(0.0, house.geo.latitude ?: 0.0, 0.001)
        assertEquals(0.0, house.geo.longitude ?: 0.0, 0.001)
    }

    @Test
    fun testMissingAgentUidSanitization() {
        val json = """
            {
                "houses": [
                    {
                        "address": {"blockNumber": "1", "streetName": "Rua A"},
                        "data": "10-01-2024"
                    }
                ],
                "dayActivities": [
                    {"date": "10-01-2024", "status": "NORMAL"}
                ]
            }
        """.trimIndent()
        
        val backupData = parseNew(json)
        assertEquals("", backupData.houses[0].agentUid)
        assertEquals("", backupData.dayActivities[0].agentUid)
    }

    // Helper to mimic BackupManager logic
    private fun parseLegacy(json: String): List<House> {
        val typeList = object : TypeToken<List<House>>() {}.type
        val houses: List<House>? = gson.fromJson(json, typeList)
        return sanitizeHouses(houses ?: emptyList())
    }

    private fun parseNew(json: String): BackupData {
        val type = object : TypeToken<BackupData>() {}.type
        val backupData: BackupData? = gson.fromJson(json, type)
        val rawHouses = backupData?.houses ?: emptyList()
        val rawActivities = backupData?.dayActivities ?: emptyList()
        
        // Use the actual sanitization logic (mimicked here or we should make it internal in BackupManager)
        return BackupData(sanitizeHouses(rawHouses), sanitizeActivities(rawActivities))
    }

    private fun sanitizeHouses(houses: List<House>): List<House> {
        return houses.map { house ->
            // Mimic REAL healing logic from BackupManager.kt
            @Suppress("SENSELESS_COMPARISON")
            var finalSituation = if (house == null || house.situation == null) com.antigravity.healthagent.data.local.model.Situation.EMPTY else house.situation
            if (finalSituation == com.antigravity.healthagent.data.local.model.Situation.EMPTY) {
                finalSituation = com.antigravity.healthagent.data.local.model.Situation.NONE
            }

            @Suppress("SENSELESS_COMPARISON")
            val safeAgentUid = if (house == null || house.agentUid == null) "" else house.agentUid

            house.copy(
                agentName = house.agentName?.trim()?.uppercase() ?: "",
                agentUid = safeAgentUid,
                context = house.context.copy(
                    municipio = house.context.municipio?.trim() ?: "BOM JARDIM"
                ),
                address = house.address.copy(
                    bairro = house.address.bairro?.trim()?.uppercase() ?: "",
                    blockNumber = house.address.blockNumber?.trim() ?: "",
                    blockSequence = house.address.blockSequence?.trim() ?: "",
                    streetName = house.address.streetName?.trim() ?: "",
                    number = if (house.address.number?.trim() == "0") "" else house.address.number?.trim() ?: "",
                    sequence = house.address.sequence,
                    complement = house.address.complement
                ),
                data = house.data?.trim() ?: "",
                situation = finalSituation
            )
        }
    }

    private fun sanitizeActivities(activities: List<DayActivity>): List<DayActivity> {
        return activities.map { activity ->
            @Suppress("SENSELESS_COMPARISON")
            val safeAgentUid = if (activity == null || activity.agentUid == null) "" else activity.agentUid

            activity.copy(
                agentName = activity.agentName?.trim()?.uppercase() ?: "",
                agentUid = safeAgentUid,
                date = activity.date?.trim() ?: "",
                status = activity.status?.trim() ?: "NORMAL"
            )
        }
    }
}
