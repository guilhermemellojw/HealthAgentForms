package com.antigravity.healthagent.data.remote.supabase

import com.antigravity.healthagent.data.util.parseRemoteTimestamp
import com.antigravity.healthagent.domain.repository.DayTransfer
import com.antigravity.healthagent.domain.repository.DayTransferStatus
import kotlinx.serialization.json.JsonObject

/**
 * Linha day_transfers -> DayTransfer (testável sem rede).
 * timestamptz (ISO) -> epoch ms; "" ausente -> "".
 */
fun JsonObject.toDayTransfer(id: String): DayTransfer {
    val map = toAnyMap()
    fun str(key: String): String = (map[key] as? String) ?: ""
    return DayTransfer(
        id = id,
        fromUid = str("from_agent_id"),
        fromName = str("from_name"),
        toUid = str("to_agent_id"),
        toAgentName = str("to_name"),
        toKey = str("to_key"),
        fromDate = str("from_date_text"),
        finalDate = str("final_date_text"),
        status = str("status").ifBlank { DayTransferStatus.PENDING.name },
        houseCount = (map["house_count"] as? Number)?.toInt() ?: 0,
        offeredAt = parseRemoteTimestamp(map["offered_at"], 0L),
        acceptedAt = parseRemoteTimestamp(map["accepted_at"], 0L)
    )
}
