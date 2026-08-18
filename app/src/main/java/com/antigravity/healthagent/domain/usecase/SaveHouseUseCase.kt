package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.domain.model.heal
import com.antigravity.healthagent.domain.model.TreatmentData
import com.antigravity.healthagent.domain.repository.HouseRepository
import com.antigravity.healthagent.domain.repository.StreetRepository
import com.antigravity.healthagent.utils.formatStreetName
import com.antigravity.healthagent.utils.normalize
import com.antigravity.healthagent.utils.removeAccents
import com.antigravity.healthagent.utils.toDashDate
import com.antigravity.healthagent.domain.logger.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class SaveHouseUseCase @Inject constructor(
    private val repository: HouseRepository,
    private val streetRepository: StreetRepository,
    private val recalculateVisitSegmentsUseCase: RecalculateVisitSegmentsUseCase,
    private val dayLockEnforcerUseCase: DayLockEnforcerUseCase
) {

    suspend fun insertHouse(house: House, allHouses: List<House>, force: Boolean = false): Long = withContext(Dispatchers.IO) {
        dayLockEnforcerUseCase.ensureDayNotLocked(house.data, house.agentUid, force)
        repository.runInTransaction {
            val sanitized = sanitizeHouse(house)

            // DUPLICATE GUARD: Prevent DB-level duplicates from stale in-memory state
            // (e.g. rapid adds where Room Flow hasn't emitted yet).
            // Uses the PHYSICAL key (never uuid) so a freshly-random-UUID'd house that
            // matches an existing address is detected. On a match we advance the
            // prediction to the next free house instead of silently skipping, keeping
            // rapid one-tap adds useful (51 -> 52 -> 53...).
            val normalizedData = sanitized.data.toDashDate()
            val tGuard = System.currentTimeMillis()
            val existingHouses = repository.getHousesByDateAndAgent(normalizedData, sanitized.agentUid)
            val existingDuplicate = existingHouses.find {
                it.id != sanitized.id &&
                it.address.generateAddressSignature() == sanitized.address.generateAddressSignature() &&
                it.visitSegment == sanitized.visitSegment
            }
            var toInsert = sanitized
            if (existingDuplicate != null) {
                AppLogger.w("PERSIST_DEBUG", "DUPLICATE_GUARD: found existing id=${existingDuplicate.id} signature=${sanitized.address.generateAddressSignature()}. Advancing to next free house.")
                val bumped = advanceToNextFree(sanitized, existingHouses)
                if (bumped == null) {
                    AppLogger.w("PERSIST_DEBUG", "DUPLICATE_GUARD: bump exhausted, returning existing id=${existingDuplicate.id}")
                    return@runInTransaction existingDuplicate.id.toLong()
                }
                toInsert = bumped
            }
            val guardMs = System.currentTimeMillis() - tGuard

            val id = repository.insertHouse(toInsert, force)
            
            // Re-query DB inside transaction to avoid in-flight houses (id=0)
            // leaking into the upsert and creating duplicates
            val dbHousesToday = repository.getHousesByDateAndAgent(normalizedData, sanitized.agentUid)
            val recalculated = recalculateVisitSegmentsUseCase.recalculateVisitSegments(
                dbHousesToday.sortedBy { it.listOrder }
            )
            repository.updateHouses(recalculated.filter { it.id > 0 }, force)
            streetRepository.saveCustomStreet(toInsert.address.streetName, toInsert.address.bairro)
            AppLogger.d("PERF", "ADD_TOTAL ms=${System.currentTimeMillis() - tGuard} guard=$guardMs day=${dbHousesToday.size}")
            id
        }
    }

    private val bumpAttempts = 12

    /**
     * Advances a house that collides with an existing one (same physical key) to the
     * next free number/sequence/complement, mirroring the prediction progression
     * (number+1, else sequence+1, else complement+1). Returns null when no free
     * candidate is found within [bumpAttempts].
     */
    private fun advanceToNextFree(house: House, existing: List<House>): House? {
        val occupiedSignatures = existing.mapTo(HashSet()) { "${it.address.generateAddressSignature()}_${it.visitSegment}" }
        var candidate = house
        repeat(bumpAttempts) {
            candidate = nextOf(candidate)
            if ("${candidate.address.generateAddressSignature()}_${candidate.visitSegment}" !in occupiedSignatures) {
                return sanitizeHouse(candidate)
            }
        }
        return null
    }

    private fun nextOf(house: House): House {
        val number = house.address.number
        val digits = number.filter { it.isDigit() }
        val numeric = digits.toLongOrNull()
        val suffix = number.filter { !it.isDigit() }
        return when {
            house.address.complement > 0 -> house.copy(
                address = house.address.copy(complement = house.address.complement + 1)
            )
            house.address.sequence > 0 -> house.copy(
                address = house.address.copy(sequence = house.address.sequence + 1, complement = 0)
            )
            numeric != null && numeric > 0 -> house.copy(
                address = house.address.copy(
                    number = if (suffix.isEmpty()) (numeric + 1).toString() else "${numeric + 1}$suffix",
                    sequence = 0,
                    complement = 0
                )
            )
            else -> house.copy(
                address = house.address.copy(sequence = house.address.sequence + 1, complement = 0)
            )
        }
    }

    suspend fun updateHouse(house: House, allHouses: List<House>, force: Boolean = false) = withContext(Dispatchers.IO) {
        AppLogger.d("PERSIST_DEBUG", "SAVE_UPDATEHOUSE_ENTER: id=${house.id} n='${house.address.number}' s=${house.address.sequence} c=${house.address.complement} pt=${house.propertyType.code} force=$force")
        try {
            dayLockEnforcerUseCase.ensureDayNotLocked(house.data, house.agentUid, force)
        } catch (e: Exception) {
            AppLogger.e("PERSIST_DEBUG", "SAVE_DAYLOCKED: date=${house.data} uid=${house.agentUid} msg=${e.message}")
            throw e
        }
        repository.runInTransaction {
            val sanitized = sanitizeHouse(house)
            val affectedDate = sanitized.data.toDashDate()
            
            // Re-query current DB state INSIDE the transaction to prevent
            // concurrent writes from overwriting each other's changes.
            val dbHousesToday = repository.getHousesByDateAndAgent(affectedDate, sanitized.agentUid)
            
            val housesToUpdate = dbHousesToday.map { if (it.id == house.id) sanitized else it }
            
            val finalUpdated = recalculateVisitSegmentsUseCase.recalculateVisitSegments(
                housesToUpdate.sortedBy { it.listOrder }
            )
            
            val finalTarget = finalUpdated.find { it.id == house.id } ?: sanitized
            
            AppLogger.d("PERSIST_DEBUG", "SAVE_UPDATEHOUSE_WRITE: id=${sanitized.id} n='${sanitized.address.number}' s=${sanitized.address.sequence} c=${sanitized.address.complement} pt=${sanitized.propertyType.code}")
            repository.updateHouse(finalTarget, force)
            streetRepository.saveCustomStreet(sanitized.address.streetName, sanitized.address.bairro)
        }
        AppLogger.d("PERSIST_DEBUG", "SAVE_UPDATEHOUSE_EXIT: id=${house.id}")
    }

    suspend fun updateHouses(houses: List<House>, force: Boolean = false) = withContext(Dispatchers.IO) {
        repository.runInTransaction {
            val affectedDates = houses.map { it.data.toDashDate() }.distinct()
            val affectedUids = houses.map { it.agentUid }.distinct()
            
            val dbHouses = affectedDates.flatMap { date ->
                affectedUids.flatMap { uid ->
                    repository.getHousesByDateAndAgent(date, uid)
                }
            }
            
            // CRITICAL FIX: For existing houses, preserve DB values for user-editable fields.
            // Batch operations (reorder, recalculate, address propagation) only intend to
            // change listOrder, visitSegment, and (occasionally) address. Using incoming
            // values for ALL fields would overwrite concurrent user edits to propertyType,
            // situation, treatment, etc. — confirmed race condition bug (REPO_UPSERT overwrite).
            val merged = dbHouses.map { dbHouse ->
                val incoming = houses.find { it.id == dbHouse.id }
                if (incoming != null) {
                    val addressChanged = incoming.address != dbHouse.address
                    dbHouse.copy(
                        listOrder = incoming.listOrder,
                        visitSegment = incoming.visitSegment,
                        address = if (addressChanged) incoming.address else dbHouse.address,
                        isSynced = false
                    )
                } else {
                    dbHouse
                }
            }
            
            repository.updateHouses(merged, force)
        }
    }

    suspend fun deleteHouse(house: House, allHouses: List<House>, force: Boolean = false) = withContext(Dispatchers.IO) {
        dayLockEnforcerUseCase.ensureDayNotLocked(house.data, house.agentUid, force)
        repository.runInTransaction {
            val normalizedData = house.data.toDashDate()
            val dayHouses = allHouses.filter { it.data.toDashDate() == normalizedData }
            repository.deleteHouse(house, force)
            val remaining = dayHouses.filter { it.id != house.id }.sortedBy { it.listOrder }
            val recalculated = recalculateVisitSegmentsUseCase.recalculateVisitSegments(remaining)
            repository.updateHouses(recalculated.filter { it.id > 0 }, force)
        }
    }

    suspend fun deleteProduction(date: String, agentUid: String, force: Boolean = false) = withContext(Dispatchers.IO) {
        dayLockEnforcerUseCase.ensureDayNotLocked(date, agentUid, force)
        repository.deleteProduction(date, agentUid, force)
    }

    data class HouseUpdateResult(
        val updatedHouse: House,
        val subsequentHouses: List<House>,
        val localizationChanged: Boolean
    )

    suspend fun updateHouseWithContext(
        house: House,
        allHouses: List<House>,
        baselineHouse: House? = null
    ): HouseUpdateResult = withContext(Dispatchers.IO) {
        // 1. Sanitize Data
        val sanitized = sanitizeHouse(house)
        
        // 2. Re-read fresh DB state for the affected day to avoid propagating stale draft values
        val affectedDate = sanitized.data.toDashDate()
        val freshDbHouses = repository.getHousesByDateAndAgent(affectedDate, sanitized.agentUid)
        
        // 3. Find original house from fresh DB (not draft-overlaid snapshot)
        val originalHouse = baselineHouse ?: freshDbHouses.find { it.id == house.id }
        
        // 4. Check for localization changes using fresh DB baseline
        val localizationChanged = originalHouse != null && (
            originalHouse.address.bairro != sanitized.address.bairro ||
            originalHouse.address.blockNumber != sanitized.address.blockNumber ||
            originalHouse.address.blockSequence != sanitized.address.blockSequence ||
            originalHouse.address.streetName != sanitized.address.streetName
        )
        
        // 5. Propagate using fresh DB houses as baseline (not draft-overlaid allHouses)
        val sequenceUpdated = if (localizationChanged) {
            freshDbHouses.map { h ->
                if (h.id == house.id) {
                    sanitized
                } else if (h.listOrder > sanitized.listOrder && originalHouse != null) {
                    // Propagate location change ONLY IF the subsequent house has the exact same ORIGINAL address
                    val matchesOriginal = h.address.bairro.equals(originalHouse.address.bairro, ignoreCase = true) &&
                                        h.address.blockNumber.equals(originalHouse.address.blockNumber, ignoreCase = true) &&
                                        h.address.blockSequence.equals(originalHouse.address.blockSequence, ignoreCase = true) &&
                                        h.address.streetName.equals(originalHouse.address.streetName, ignoreCase = true)
                    
                    if (matchesOriginal) {
                        val updated = h.copy(
                            address = h.address.copy(
                                bairro = sanitized.address.bairro,
                                blockNumber = sanitized.address.blockNumber,
                                blockSequence = sanitized.address.blockSequence,
                                streetName = sanitized.address.streetName
                            ),
                            isSynced = false,
                            lastUpdated = System.currentTimeMillis()
                        )
                        
                        // DEFERRED CLASH CHECK: Only propagate if it doesn't create a duplication in the DB.
                        val identityClash = freshDbHouses.any { other ->
                            other.id != updated.id &&
                            other.data == updated.data &&
                            other.agentUid == updated.agentUid &&
                            other.agentName.equals(updated.agentName, ignoreCase = true) &&
                            other.address.blockNumber.equals(updated.address.blockNumber, ignoreCase = true) &&
                            other.address.blockSequence.equals(updated.address.blockSequence, ignoreCase = true) &&
                            other.address.streetName.equals(updated.address.streetName, ignoreCase = true) &&
                            other.address.number.equals(updated.address.number, ignoreCase = true) &&
                            other.address.sequence == updated.address.sequence &&
                            other.address.complement == updated.address.complement &&
                            other.address.bairro.equals(updated.address.bairro, ignoreCase = true) &&
                            other.visitSegment == updated.visitSegment
                        }
                        
                        if (!identityClash) {
                            updated // Use the fully updated object (isSynced=false)
                        } else {
                            h
                        }
                    } else {
                        h
                    }
                } else {
                    h
                }
            }
        } else {
            freshDbHouses.map { if (it.id == house.id) sanitized else it }
        }

        // 6. Recalculate segments ONLY if street name changed or specifically requested
        val dayToRecalculate = sanitized.data.toDashDate()
        val affectedHouses = sequenceUpdated.filter { it.data.toDashDate() == dayToRecalculate }.sortedBy { it.listOrder }
        val recalculatedDay = if (localizationChanged) {
            recalculateVisitSegmentsUseCase.recalculateVisitSegments(affectedHouses)
        } else {
            affectedHouses
        }
        
        // Find the specific updated house in the recalculated list
        val finalUpdatedHouse = recalculatedDay.find { it.id == house.id } ?: sanitized
        
        // The list of "houses to update" should be those that changed (compared to fresh DB)
        val housesThatChanged = recalculatedDay.filter { dayHouse ->
            val original = freshDbHouses.find { it.id == dayHouse.id }
            original == null || original != dayHouse
        }
        
        HouseUpdateResult(finalUpdatedHouse, housesThatChanged, localizationChanged)
    }

    fun sanitizeHouse(house: House): House {
        val totalDeposits = house.treatment.totalDeposits
        val hasTreatment = totalDeposits > 0 || house.treatment.eliminados > 0 || house.treatment.larvicida > 0.0

        var situation = house.situation.heal()
        val treatment = house.treatment
        var a1 = treatment.a1
        var a2 = treatment.a2
        var b = treatment.b
        var c = treatment.c
        var d1 = treatment.d1
        var d2 = treatment.d2
        var e = treatment.e
        var elims = treatment.eliminados
        var larv = treatment.larvicida
        var comFoco = treatment.comFoco

        // Strict Consistency: ONLY Situation.NONE (Worked) can have treatment
        if (situation != Situation.NONE && hasTreatment) {
            // Clear treatment if situation changed from NONE to something else
            a1 = 0; a2 = 0; b = 0; c = 0; d1 = 0; d2 = 0; e = 0; elims = 0; larv = 0.0; comFoco = false
        }

        val normalizedNumber = house.address.number.trim().uppercase() // Don't clear "0" while editing
        val normalizedSequence = house.address.sequence
        val normalizedComplement = house.address.complement

        val normalizedBairro = house.address.bairro.normalize()
        // SURGICAL HEALING: Restore accents to Bairros by matching against AppConstants
        val healedBairro = com.antigravity.healthagent.utils.AppConstants.BAIRROS.find { 
            it.removeAccents().equals(normalizedBairro.removeAccents(), ignoreCase = true) 
        } ?: normalizedBairro

        return house.copy(
            address = house.address.copy(
                blockNumber = house.address.blockNumber.normalize(),
                blockSequence = house.address.blockSequence.normalize(),
                streetName = house.address.streetName.trim().formatStreetName(),
                number = normalizedNumber,
                sequence = normalizedSequence,
                complement = normalizedComplement,
                bairro = healedBairro
            ),
            context = house.context.copy(
                municipio = house.context.municipio.normalize(),
                categoria = house.context.categoria.normalize(),
                zona = house.context.zona.normalize(),
                ciclo = house.context.ciclo.normalize()
            ),
            agentName = com.antigravity.healthagent.utils.AppConstants.AGENT_NAMES.find { 
                it.removeAccents().equals(house.agentName.removeAccents(), ignoreCase = true) 
            } ?: house.agentName.normalize(),
            situation = situation,
            data = house.data.toDashDate().trim(),
            isSynced = false, // Force re-sync on every local edit
            lastUpdated = if (house.lastUpdated > 0) house.lastUpdated else System.currentTimeMillis(),
            treatment = TreatmentData(a1 = a1, a2 = a2, b = b, c = c, d1 = d1, d2 = d2, e = e, eliminados = elims, larvicida = larv, comFoco = comFoco)
        )
    }
}
