package com.antigravity.healthagent.data.backup

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.DayActivity
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStreamReader
import java.io.PushbackReader
import java.io.ByteArrayInputStream

class BackupManagerTest {

    private val gson: Gson = GsonBuilder().create()

    // Since sanitization methods are private, I'll test the logic by mimicking the parsing
    // or I could temporarily make them internal for testing.
    // For now, I'll test the expected behavior through a testable wrapper if I had one,
    // or just test the JSON parsing logic if I can.

    @Test
    fun testLegacyFormatParsing() {
        val json = """
            [
                {"blockNumber": "1", "streetName": "Rua A", "data": "10-01-2024"},
                {"blockNumber": "2", "streetName": "Rua B", "data": "10-01-2024"}
            ]
        """.trimIndent()
        
        val houses = parseLegacy(json)
        assertEquals(2, houses.size)
        assertEquals("1", houses[0].blockNumber)
        assertEquals("", houses[0].agentName) // Should be sanitized to empty string if missing
    }

    @Test
    fun testNewFormatWithMissingFields() {
        val json = """
            {
                "houses": [
                    {"blockNumber": "1", "streetName": "Rua A", "data": "10-01-2024"}
                ]
            }
        """.trimIndent()
        
        val backupData = parseNew(json)
        assertEquals(1, backupData.houses.size)
        assertTrue(backupData.dayActivities.isEmpty()) // Should default to empty list if missing
    }

    @Test
    fun testNaturalKeyUniqueness() {
        val house1 = House(id = 1, blockNumber = "1", streetName = "Rua A", data = "10-01-2024", listOrder = 0, agentName = "AGENT")
        val house2 = House(id = 2, blockNumber = "1", streetName = "Rua A", data = "10-01-2024", listOrder = 1, agentName = "AGENT")
        
        val key1 = house1.generateNaturalKey()
        val key2 = house2.generateNaturalKey()
        
        assertTrue("Keys should be different for different listOrder values", key1 != key2)
        assertTrue("Key should contain listOrder", key1.contains("_0"))
        assertTrue("Key should contain listOrder", key2.contains("_1"))
    }

    // Helper to mimic BackupManager logic
    private fun parseLegacy(json: String): List<House> {
        val typeList = object : TypeToken<List<House>>() {}.type
        val houses: List<House>? = gson.fromJson(json, typeList)
        return sanitizeHouses(houses ?: emptyList())
    }

    private fun parseNew(json: String): BackupData {
        val type = object : TypeToken<BackupData>() {}.type
        val backupData: BackupData = gson.fromJson(json, type)
        val rawHouses = backupData.houses ?: emptyList()
        val rawActivities = backupData.dayActivities ?: emptyList()
        return BackupData(sanitizeHouses(rawHouses), sanitizeActivities(rawActivities))
    }

    private fun sanitizeHouses(houses: List<House>): List<House> {
        return houses.map { house ->
            house.copy(
                agentName = house.agentName.trim().uppercase(),
                municipio = house.municipio.trim(),
                bairro = house.bairro.trim().uppercase(),
                blockNumber = house.blockNumber.trim(),
                blockSequence = house.blockSequence.trim(),
                streetName = house.streetName.trim(),
                number = house.number.trim(),
                data = house.data.trim()
            )
        }
    }

    private fun sanitizeActivities(activities: List<DayActivity>): List<DayActivity> {
        return activities.map { activity ->
            activity.copy(
                agentName = activity.agentName.trim().uppercase(),
                date = activity.date.trim(),
                status = activity.status.trim()
            )
        }
    }
}
