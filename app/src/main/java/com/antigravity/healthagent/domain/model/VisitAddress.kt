package com.antigravity.healthagent.domain.model

import com.antigravity.healthagent.utils.normalize

data class VisitAddress(
    val blockNumber: String = "",
    val blockSequence: String = "",
    val streetName: String = "",
    val number: String = "",
    val sequence: Int = 0,
    val complement: Int = 0,
    val bairro: String = ""
) {
    /**
     * Generates a displayable string for the house identification.
     * Example: "123-S1-C2" or "S/N"
     */
    val fullIdDisplay: String
        get() {
            val parts = mutableListOf<String>()
            if (number.isNotBlank()) parts.add(number)
            if (sequence > 0) parts.add("S$sequence")
            if (complement > 0) parts.add("C$complement")
            return parts.joinToString("-").ifBlank { "S/N" }
        }

    /**
     * Generates the address part of the identity keys.
     */
    fun generateAddressSignature(): String {
        return "${blockNumber.normalize()}_${blockSequence.normalize()}_${streetName.normalize()}_${number.normalize()}_${sequence}_${complement}_${bairro.normalize()}"
    }

    /**
     * Simple check for basic address completeness.
     */
    val isComplete: Boolean
        get() = bairro.isNotBlank() && 
                streetName.isNotBlank() && 
                blockNumber.isNotBlank() && 
                (number.isNotBlank() || sequence > 0)
}
