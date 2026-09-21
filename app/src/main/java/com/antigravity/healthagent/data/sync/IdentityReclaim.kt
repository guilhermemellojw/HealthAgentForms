package com.antigravity.healthagent.data.sync

import com.antigravity.healthagent.data.local.dao.DayActivityDao
import com.antigravity.healthagent.data.local.dao.HouseDao
import com.antigravity.healthagent.domain.logger.AppLogger

/**
 * Reivindicação de dados locais órfãos (DB Room) para o UID real — 100% local,
 * compartilhado pelos backends Firebase e Supabase.
 * Extraído de AuthRepositoryImpl (comportamento idêntico).
 */
suspend fun reclaimLocalIdentity(
    houseDao: HouseDao,
    activityDao: DayActivityDao,
    email: String,
    uid: String,
    displayName: String?,
    standardName: String
) {
    if (email.isBlank()) return
    try {
        val emailPrefix = email.substringBefore("@").uppercase()
        val properName = standardName

        // 1. House Migration & Reclamation
        val misattributedHouses = houseDao.getHousesToReclaim(email, emailPrefix, uid, properName)
        for (house in misattributedHouses) {
            val dateDash = house.data.replace("/", "-")
            val conflicts = houseDao.getHousesByDateAndAgent(house.data, uid)
            val conflict = conflicts.find { conflict ->
                conflict.address.blockNumber.equals(house.address.blockNumber, ignoreCase = true) &&
                conflict.address.blockSequence.equals(house.address.blockSequence, ignoreCase = true) &&
                conflict.address.streetName.equals(house.address.streetName, ignoreCase = true) &&
                conflict.address.number.equals(house.address.number, ignoreCase = true) &&
                conflict.address.sequence == house.address.sequence &&
                conflict.address.complement == house.address.complement &&
                conflict.address.bairro.equals(house.address.bairro, ignoreCase = true) &&
                conflict.visitSegment == house.visitSegment
            }

            if (conflict != null) {
                val isHouseClosed = activityDao.getDayActivity(dateDash, house.agentUid)?.let { it.isClosed && !it.isManualUnlock } ?: false
                val isConflictClosed = activityDao.getDayActivity(dateDash, uid)?.let { it.isClosed && !it.isManualUnlock } ?: false

                if (isHouseClosed || isConflictClosed) {
                    // CLOSED-DAY GUARD: skip deletion of duplicate conflict or house
                    if (!isHouseClosed) {
                        houseDao.updateHouseIdentity(house.id, uid, properName)
                    }
                } else {
                    // MERGE LOGIC: Prefer the house that has actual fieldwork data (treatment)
                    val localHasWork = house.treatment.a1 > 0 || house.treatment.a2 > 0 || house.treatment.comFoco || house.observation.isNotBlank()
                    val conflictHasWork = conflict.treatment.a1 > 0 || conflict.treatment.a2 > 0 || conflict.treatment.comFoco || conflict.observation.isNotBlank()

                    if (localHasWork && !conflictHasWork) {
                        AppLogger.i("IdentityReclaim", "Migration: Overwriting empty cloud skeleton with local production for ${house.id}")
                        houseDao.deleteHouse(conflict)
                        houseDao.updateHouseIdentity(house.id, uid, properName)
                    } else {
                        // Conflict already has work or local is also empty
                        houseDao.deleteHouseById(house.id)
                    }
                }
            } else {
                houseDao.updateHouseIdentity(house.id, uid, properName)
            }
        }

        // 2. Day Activity Migration & Reclamation
        val activitiesToReclaim = activityDao.getActivitiesToReclaim(email, emailPrefix, uid, properName)
        if (activitiesToReclaim.isNotEmpty()) {
            AppLogger.i("IdentityReclaim", "Reclaiming ${activitiesToReclaim.size} activities for $email")
            activityDao.reclaimActivities(displayName ?: "", email, emailPrefix, uid)
        }
    } catch (e: Exception) {
        AppLogger.e("IdentityReclaim", "Proactive migration failed", e)
    }
}
