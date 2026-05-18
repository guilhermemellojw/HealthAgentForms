package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.domain.model.TreatmentData
import com.antigravity.healthagent.data.repository.HouseRepository
import com.antigravity.healthagent.data.repository.StreetRepository
import com.antigravity.healthagent.utils.formatStreetName
import com.antigravity.healthagent.utils.normalize
import com.antigravity.healthagent.utils.removeAccents
import com.antigravity.healthagent.utils.toDashDate
import javax.inject.Inject

class SaveHouseUseCase @Inject constructor(
    private val repository: HouseRepository,
    private val streetRepository: StreetRepository,
    private val recalculateVisitSegmentsUseCase: RecalculateVisitSegmentsUseCase
) {

    suspend fun insertHouse(house: House, allHouses: List<House>, force: Boolean = false): Long {
        return repository.runInTransaction {
            val sanitized = sanitizeHouse(house)
            val id = repository.insertHouse(sanitized, force)
            
            val normalizedData = sanitized.data.toDashDate()
            val dayHouses = allHouses.filter { it.data.toDashDate() == normalizedData }
            val withNewHouse = (dayHouses + sanitized.copy(id = id.toInt())).sortedBy { it.listOrder }
            val recalculated = recalculateVisitSegmentsUseCase.recalculateVisitSegments(withNewHouse)
            repository.updateHouses(recalculated, force)
            streetRepository.saveCustomStreet(sanitized.address.streetName, sanitized.address.bairro)
            id
        }
    }

    suspend fun updateHouse(house: House, allHouses: List<House>, force: Boolean = false) {
        repository.runInTransaction {
            val sanitized = sanitizeHouse(house)
            val originalHouse = allHouses.find { it.id == house.id }
            
            val affectedDates = mutableSetOf(sanitized.data.toDashDate())
            originalHouse?.let { affectedDates.add(it.data.toDashDate()) }
            
            val housesToUpdate = allHouses.filter { it.data.toDashDate() in affectedDates }.map {
                if (it.id == house.id) sanitized else it
            }
            
            val finalUpdated = housesToUpdate.groupBy { it.data }.flatMap { (date, dayHouses) ->
                recalculateVisitSegmentsUseCase.recalculateVisitSegments(dayHouses.sortedBy { it.listOrder })
            }
            
            repository.updateHouses(finalUpdated, force)
            streetRepository.saveCustomStreet(sanitized.address.streetName, sanitized.address.bairro)
        }
    }

    suspend fun updateHouses(houses: List<House>, force: Boolean = false) {
        // Assume these are already recalculated if coming from a flow that does it
        repository.updateHouses(houses, force)
    }

    suspend fun deleteHouse(house: House, allHouses: List<House>, force: Boolean = false) {
        repository.runInTransaction {
            val normalizedData = house.data.toDashDate()
            val dayHouses = allHouses.filter { it.data.toDashDate() == normalizedData }
            repository.deleteHouse(house, force)
            val remaining = dayHouses.filter { it.id != house.id }.sortedBy { it.listOrder }
            val recalculated = recalculateVisitSegmentsUseCase.recalculateVisitSegments(remaining)
            repository.updateHouses(recalculated, force)
        }
    }

    suspend fun deleteProduction(date: String, agentUid: String, force: Boolean = false) {
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
    ): HouseUpdateResult {
        val originalHouse = baselineHouse ?: allHouses.find { it.id == house.id }
        
        // 1. Sanitize Data
        val sanitized = sanitizeHouse(house)
        
        // 2. Check for localization changes
        val localizationChanged = originalHouse != null && (
            originalHouse.address.bairro != sanitized.address.bairro ||
            originalHouse.address.blockNumber != sanitized.address.blockNumber ||
            originalHouse.address.blockSequence != sanitized.address.blockSequence ||
            originalHouse.address.streetName != sanitized.address.streetName
        )
        
        val sequenceUpdated = if (localizationChanged) {
            allHouses.map { h ->
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
                        val identityClash = allHouses.any { other ->
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
            allHouses.map { if (it.id == house.id) sanitized else it }
        }

        // 3. Recalculate segments ONLY if street name changed or specifically requested
        val dayToRecalculate = sanitized.data.toDashDate()
        val affectedHouses = sequenceUpdated.filter { it.data.toDashDate() == dayToRecalculate }.sortedBy { it.listOrder }
        val recalculatedDay = if (localizationChanged) {
            recalculateVisitSegmentsUseCase.recalculateVisitSegments(affectedHouses)
        } else {
            affectedHouses
        }
        
        // Find the specific updated house in the recalculated list
        val finalUpdatedHouse = recalculatedDay.find { it.id == house.id } ?: sanitized
        
        // The list of "houses to update" should be those that changed
        val housesThatChanged = recalculatedDay.filter { dayHouse ->
            val original = allHouses.find { it.id == dayHouse.id }
            original == null || original != dayHouse
        }
        
        return HouseUpdateResult(finalUpdatedHouse, housesThatChanged, localizationChanged)
    }

    private fun sanitizeHouse(house: House): House {
        val totalDeposits = house.treatment.totalDeposits
        val hasTreatment = totalDeposits > 0 || house.treatment.eliminados > 0 || house.treatment.larvicida > 0.0

        var situation = house.situation
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

        // Strict Consistency: ONLY Situation.NONE (Worked) can have treatment
        if (situation != Situation.NONE && hasTreatment) {
            // Clear treatment if situation changed from NONE to something else
            a1 = 0; a2 = 0; b = 0; c = 0; d1 = 0; d2 = 0; e = 0; elims = 0; larv = 0.0
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
            treatment = TreatmentData(a1 = a1, a2 = a2, b = b, c = c, d1 = d1, d2 = d2, e = e, eliminados = elims, larvicida = larv)
        )
    }
}
