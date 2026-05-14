package com.antigravity.healthagent.domain.model

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import org.junit.Assert.assertEquals
import org.junit.Test

class HouseIntegrityTest {

    @Test
    fun `ensure toFirestoreMap maintains flat structure for cloud compatibility`() {
        val house = House(
            id = 1,
            address = com.antigravity.healthagent.domain.model.VisitAddress(
                blockNumber = "123",
                streetName = "Rua Teste",
                number = "456",
                sequence = 1,
                complement = 2,
                bairro = "Centro"
            ),
            propertyType = PropertyType.R,
            situation = Situation.NONE,
            context = DailyContext(
                municipio = "Bom Jardim",
                categoria = "BRR",
                zona = "URB",
                tipo = 2,
                ciclo = "1º",
                atividade = 4
            ),
            data = "13-05-2026",
            agentName = "AGENTE TESTE",
            agentUid = "uid123",
            treatment = TreatmentData(a1 = 1, larvicida = 10.5, comFoco = true),
            observation = "Nota de teste"
        )

        val map = house.toFirestoreMap()

        // This list MUST match exactly the keys expected by the Firestore schema
        val expectedKeys = listOf(
            "blockNumber", "streetName", "number", "sequence", "complement",
            "propertyType", "situation", "municipio", "bairro", "categoria",
            "zona", "tipo", "data", "ciclo", "atividade", "agentName",
            "a1", "a2", "b", "c", "d1", "d2", "e", "eliminados",
            "larvicida", "comFoco", "localidadeConcluida", "blockSequence",
            "quarteiraoConcluido", "listOrder", "visitSegment", "agentUid",
            "lastSyncTime", "createdAt", "observation", "latitude", "longitude",
            "focusCaptureTime", "lastUpdated", "editedByAdmin"
        )

        expectedKeys.forEach { key ->
            assertTrue("Missing key in Firestore map: $key", map.containsKey(key))
        }
        
        assertEquals("123", map["blockNumber"])
        assertEquals("Rua Teste", map["streetName"])
        assertEquals("456", map["number"])
        assertEquals(1, map["sequence"])
        assertEquals(1, map["a1"])
        assertEquals(10.5, map["larvicida"])
        assertEquals(true, map["comFoco"])
        assertEquals("AGENTE TESTE", map["agentName"])
    }

    private fun assertTrue(message: String, condition: Boolean) {
        if (!condition) throw AssertionError(message)
    }
}
