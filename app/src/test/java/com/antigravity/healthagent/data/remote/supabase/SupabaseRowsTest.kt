package com.antigravity.healthagent.data.remote.supabase

import com.antigravity.healthagent.data.util.parseRemoteTimestamp
import com.antigravity.healthagent.data.util.parseRemoteTimestampOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseRowsTest {

    @Test
    fun jsonObjectToAnyMap() {
        val obj = Json.parseToJsonElement(
            """{"s":"x","i":3,"f":0.5,"b":true,"n":null,"a":[1,"z"],"o":{"k":9}}"""
        ) as JsonObject
        val map = obj.toAnyMap()
        assertEquals("x", map["s"])
        assertEquals(3L, map["i"])
        assertEquals(0.5, map["f"])
        assertEquals(true, map["b"])
        assertNull(map["n"])
        assertEquals(listOf(1L, "z"), map["a"])
        assertEquals(mapOf("k" to 9L), map["o"])
    }

    @Test
    fun parseRemoteTimestampVariants() {
        // epoch ms
        assertEquals(1786726380330L, parseRemoteTimestamp(1786726380330L))
        // epoch segundos -> ms
        assertEquals(1786726380000L, parseRemoteTimestamp(1786726380L))
        // ISO-8601 Supabase
        assertEquals(1786726380909L, parseRemoteTimestamp("2026-08-14T16:53:00.909Z"))
        assertEquals(1786726380000L, parseRemoteTimestamp("2026-08-14T16:53:00+00:00"))
        // inválidos
        assertEquals(0L, parseRemoteTimestamp(null))
        assertEquals(0L, parseRemoteTimestamp(""))
        assertEquals(0L, parseRemoteTimestamp("não-data"))
        assertNull(parseRemoteTimestampOrNull("não-data"))
        assertTrue(parseRemoteTimestampOrNull(null) == null)
    }
}
