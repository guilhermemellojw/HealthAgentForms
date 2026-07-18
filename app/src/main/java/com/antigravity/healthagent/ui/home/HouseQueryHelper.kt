package com.antigravity.healthagent.ui.home

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.utils.DateUtils
import com.antigravity.healthagent.utils.formatStreetName
import com.antigravity.healthagent.utils.normalize as stringNormalize
import java.util.Date

object HouseQueryHelper {

    fun getHousesForDate(
        houses: List<House>,
        date: String,
        agentName: String,
        targetUid: String?,
        targetName: String?
    ): List<House> {
        val effectiveName = targetName ?: agentName
        return houses.filter { house ->
            house.data == date && (
                (targetUid.isNullOrBlank().not() && house.agentUid == targetUid) ||
                (effectiveName.isNotBlank() && house.agentName.uppercase() == effectiveName.uppercase())
            )
        }
    }

    fun generateHouseKey(hh: House): String {
        val b = hh.address.bairro.stringNormalize()
        val bn = hh.address.blockNumber.stringNormalize()
        val bs = hh.address.blockSequence.stringNormalize()
        val sn = hh.address.streetName.formatStreetName()
        val n = hh.address.number.stringNormalize()
        val c = hh.address.complement.toString().stringNormalize()
        return "$b|$bn|$bs|$sn|$n|${hh.address.sequence}|$c".uppercase()
    }

    fun calculateCicloFromDate(dateStr: String): String {
        val parts = dateStr.replace("/", "-").split("-")
        if (parts.size >= 2) {
            val month = parts[1].toIntOrNull() ?: 1
            val cicloNum = ((month - 1) / 2) + 1
            return "${cicloNum}º"
        }
        return "1º"
    }

    fun getTimestamp(date: String): Long {
        return try { DateUtils.DASH_DATE.get().parse(date)?.time ?: 0L } catch (e: Exception) { 0L }
    }

    fun parseDate(date: String): Date? = try { DateUtils.DASH_DATE.get().parse(date) } catch (e: Exception) { null }
}
