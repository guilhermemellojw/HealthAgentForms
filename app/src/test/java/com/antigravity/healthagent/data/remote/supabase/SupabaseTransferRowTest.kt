package com.antigravity.healthagent.data.remote.supabase

import com.antigravity.healthagent.domain.repository.DayTransferStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SupabaseTransferRowTest {

    @Test
    fun mapsDeclinedRowWithNulls() {
        // Espelha a linha real seedada (DECLINED, toUid "", acceptedAt NULL).
        val row = Json.parseToJsonElement(
            """{
              "id": "abc_16-09-2026_X",
              "from_agent_id": "uid-from",
              "from_name": "GUILHERME MELLO",
              "to_agent_id": null,
              "to_name": "DANIELLE MELLO",
              "to_key": "DANIELLE MELLO",
              "from_date_text": "16-09-2026",
              "final_date_text": null,
              "status": "DECLINED",
              "house_count": 25,
              "offered_at": "2026-09-16T10:00:00+00:00",
              "accepted_at": null
            }"""
        ) as JsonObject
        val t = row.toDayTransfer("abc_16-09-2026_X")
        assertEquals("abc_16-09-2026_X", t.id)
        assertEquals("uid-from", t.fromUid)
        assertEquals("", t.toUid)
        assertEquals("DANIELLE MELLO", t.toKey)
        assertEquals("16-09-2026", t.fromDate)
        assertEquals("", t.finalDate)
        assertEquals(DayTransferStatus.DECLINED, t.statusEnum)
        assertEquals(25, t.houseCount)
        assertEquals(1789552800000L, t.offeredAt)
        assertEquals(0L, t.acceptedAt)
    }

    @Test
    fun blankStatusDefaultsToPending() {
        val row = Json.parseToJsonElement("""{"from_agent_id": "u", "status": ""}""") as JsonObject
        val t = row.toDayTransfer("x")
        assertEquals(DayTransferStatus.PENDING, t.statusEnum)
        assertEquals("u", t.fromUid)
    }
}
