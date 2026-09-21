package com.antigravity.healthagent.data.util

import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.domain.model.DailyContext
import com.antigravity.healthagent.domain.model.GeoCapture
import com.antigravity.healthagent.domain.model.TreatmentData
import com.antigravity.healthagent.domain.model.VisitAddress
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseWriteMapperTest {

    private fun sampleHouse() = House(
        address = VisitAddress(blockNumber = "12", streetName = "Rua A", number = "10", sequence = 1, bairro = "CENTRO"),
        context = DailyContext(tipo = 2, atividade = 4),
        treatment = TreatmentData(a1 = 1, larvicida = 0.5, comFoco = true),
        data = "10/08/2026",
        agentName = "Teste",
        agentUid = "uid-1",
        uuid = "uuid-1",
        observation = "obs",
        geo = GeoCapture(latitude = -22.5, longitude = -42.1),
        createdAt = 1786726376991L
    )

    @Test
    fun houseToSupabaseRow() {
        val row = sampleHouse().toSupabaseRow("uid-1", "TESTE", false)
        assertEquals("uid-1", (row["agent_id"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("10-08-2026", (row["data_text"] as kotlinx.serialization.json.JsonPrimitive).content)
        // tipo/atividade como TEXTO (colunas TEXT)
        assertEquals("2", (row["tipo"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("4", (row["atividade"] as kotlinx.serialization.json.JsonPrimitive).content)
        // fração preservada
        assertEquals(0.5, (row["larvicida"] as kotlinx.serialization.json.JsonPrimitive).doubleOrNull)
        assertEquals("TESTE", (row["agent_name"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("uuid-1", (row["client_uuid"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertTrue((row["natural_key"] as kotlinx.serialization.json.JsonPrimitive).content.isNotBlank())
        // trigger deriva: nunca enviado
        assertTrue(row["updated_at"] == null)
        assertTrue(row["data_date"] == null)
        assertTrue(row["deleted_at"] == null)
        // nulos explícitos (limpam valores obsoletos no upsert)
        assertTrue(row["focus_capture_time"] == JsonNull)
    }

    @Test
    fun activityToSupabaseRow() {
        val a = DayActivity(date = "10/08/2026", status = "OPEN", isClosed = true)
        val row = a.toSupabaseRow("uid-1", "TESTE", true)
        assertEquals("10-08-2026", (row["date_text"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("uid-1", (row["agent_id"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(true, (row["is_closed"] as kotlinx.serialization.json.JsonPrimitive).booleanOrNull)
        assertEquals(true, (row["edited_by_admin"] as kotlinx.serialization.json.JsonPrimitive).booleanOrNull)
    }
}
