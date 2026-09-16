package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.House
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized clash (duplicate natural key) detection for Houses.
 *
 * Replaces 4+ duplicate implementations scattered across HomeViewModel:
 * - addNewHouse()
 * - addNewHouseAt()
 * - updateHouse()
 * - moveHouseToDate() / performMoveHouse()
 *
 * A "clash" means two different House IDs share the same logical address identity
 * for the same agent on the same day, which would cause a REPLACE/merge via Room's
 * unique index and silently delete one of the records.
 */
@Singleton
class ClashDetector @Inject constructor() {

    /**
     * Finds an existing house that clashes with [candidate] in the given [existingHouses] list.
     *
     * @param candidate The house being inserted or updated.
     * @param existingHouses Current houses (DB + drafts merged) to check against.
     * @param includeVisitSegment Whether to include visitSegment in the comparison.
     *        Defaults to `false` because visitSegment is recalculated and should not
     *        cause false non-matches for the same physical address.
     * @return The clashing house, or null if no clash exists.
     */
    fun findClash(
        candidate: House,
        existingHouses: List<House>,
        includeVisitSegment: Boolean = false
    ): House? {
        return existingHouses.find { existing ->
            existing.id != candidate.id &&
            existing.data == candidate.data &&
            existing.agentUid == candidate.agentUid &&
            existing.agentName.equals(candidate.agentName, ignoreCase = true) &&
            existing.address.blockNumber.equals(candidate.address.blockNumber, ignoreCase = true) &&
            existing.address.blockSequence.equals(candidate.address.blockSequence, ignoreCase = true) &&
            existing.address.streetName.equals(candidate.address.streetName, ignoreCase = true) &&
            existing.address.number.equals(candidate.address.number, ignoreCase = true) &&
            existing.address.sequence == candidate.address.sequence &&
            existing.address.complement == candidate.address.complement &&
            existing.address.bairro.equals(candidate.address.bairro, ignoreCase = true) &&
            (!includeVisitSegment || existing.visitSegment == candidate.visitSegment)
        }
    }

    /**
     * Auto-increments the sequence/complement of [house] to avoid clashing with [existingHouses].
     *
     * If the house has a non-blank number or complement > 0, increments complement.
     * Otherwise, increments sequence.
     *
     * @return A copy of [house] with adjusted sequence/complement to be unique.
     */
    fun autoIncrementToAvoidClash(
        house: House,
        existingHouses: List<House>,
        includeVisitSegment: Boolean = false
    ): House {
        var finalSequence = house.address.sequence
        var finalComplement = house.address.complement

        while (existingHouses.any { existing ->
            existing.data == house.data &&
            existing.agentUid == house.agentUid &&
            existing.agentName.equals(house.agentName, ignoreCase = true) &&
            existing.address.blockNumber.equals(house.address.blockNumber, ignoreCase = true) &&
            existing.address.blockSequence.equals(house.address.blockSequence, ignoreCase = true) &&
            existing.address.streetName.equals(house.address.streetName, ignoreCase = true) &&
            existing.address.number.equals(house.address.number, ignoreCase = true) &&
            existing.address.sequence == finalSequence &&
            existing.address.complement == finalComplement &&
            existing.address.bairro.equals(house.address.bairro, ignoreCase = true) &&
            (!includeVisitSegment || existing.visitSegment == house.visitSegment)
        }) {
            if (house.address.complement > 0 || house.address.number.isNotBlank()) {
                finalComplement++
            } else {
                finalSequence++
            }
        }

        return if (finalSequence != house.address.sequence || finalComplement != house.address.complement) {
            house.copy(address = house.address.copy(sequence = finalSequence, complement = finalComplement))
        } else {
            house
        }
    }
}
