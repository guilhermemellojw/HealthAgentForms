package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import javax.inject.Inject

class HouseValidationUseCase @Inject constructor() {

    data class ValidationResult(
        val isValid: Boolean,
        val errorHouseIds: Set<Int> = emptySet(),
        val dialogMessage: String? = null
    )

    fun validateCurrentDay(currentDate: String, allHouses: List<House>, strict: Boolean = true): ValidationResult {
        val currentHouses = allHouses.filter { it.data == currentDate }

        if (currentHouses.isEmpty()) {
            return ValidationResult(isValid = true)
        }

        // First house exemption if untouched (even in strict mode)
        if (currentHouses.size == 1 && currentHouses.first().situation == Situation.EMPTY) {
            return ValidationResult(isValid = true)
        }

        // 1. Field Validation
        val incompleteHouses = currentHouses.filter { !isHouseValid(it, strict = strict) }

        if (incompleteHouses.isNotEmpty()) {
            val sb = StringBuilder()
            sb.append("Foram encontradas pendências nos seguintes imóveis:\n\n")
            
            incompleteHouses.forEach { house ->
                val missingFields = mutableListOf<String>()
                if (house.number.isBlank() && (house.sequence == null || house.sequence == 0)) missingFields.add("Número/Sequência")
                if (house.propertyType == PropertyType.EMPTY) missingFields.add("Tipo")
                if (house.situation == Situation.EMPTY) missingFields.add("Situação")
                if (house.agentName.isBlank()) missingFields.add("Agente")
                if (house.bairro.isBlank()) missingFields.add("Bairro")
                if (house.streetName.isBlank()) missingFields.add("Logradouro")
                if (house.blockNumber.isBlank()) missingFields.add("Quarteirão")
                
                val totalDeposits = house.a1 + house.a2 + house.b + house.c + house.d1 + house.d2 + house.e
                val hasTreatment = totalDeposits > 0 || house.eliminados > 0 || house.larvicida > 0.0 || house.comFoco
                
                if (house.situation != Situation.NONE && hasTreatment) {
                    missingFields.add("Tratamento em Imóvel não Trabalhado")
                }
                if (house.larvicida > 0.0 && totalDeposits == 0) {
                    missingFields.add("Larvicida sem Depósito Inspecionado")
                }
                
                val location = if (house.number.isNotBlank()) "Nº ${house.number}" else "Seq. ${house.sequence ?: "?"}"
                sb.append("• ${house.streetName} ($location): Falta ${missingFields.joinToString(", ")}\n")
            }
            
            return ValidationResult(
                isValid = false,
                errorHouseIds = incompleteHouses.map { it.id }.toSet(),
                dialogMessage = sb.toString()
            )
        }


        return ValidationResult(isValid = true)
    }

    // Extracted from private HomeViewModel method
    fun isHouseValid(house: House, strict: Boolean = true): Boolean {
        return getInvalidFields(house, strict).isEmpty()
    }

    fun getInvalidFields(house: House, strict: Boolean = true): List<String> {
        val invalidFields = mutableListOf<String>()
        
        val hasNumberOrSeq = house.number.isNotBlank() || (house.sequence != null && house.sequence > 0)
        if (!hasNumberOrSeq) invalidFields.add("number")
        
        if (house.propertyType == PropertyType.EMPTY) invalidFields.add("propertyType")
        
        if (strict && house.situation == Situation.EMPTY) {
            invalidFields.add("situation")
        }

        if (house.agentName.isBlank()) invalidFields.add("agentName")
        if (house.bairro.isBlank()) invalidFields.add("bairro")
        if (house.streetName.isBlank()) invalidFields.add("streetName")
        if (house.blockNumber.isBlank()) invalidFields.add("blockNumber")

        val totalDeposits = house.a1 + house.a2 + house.b + house.c + house.d1 + house.d2 + house.e
        val hasTreatment = totalDeposits > 0 || house.eliminados > 0 || house.larvicida > 0.0 || house.comFoco
        
        if (house.situation != Situation.NONE && hasTreatment) {
            invalidFields.add("situation_treatment")
        }

        if (house.larvicida > 0.0 && totalDeposits == 0) {
            invalidFields.add("larvicide_inspection")
        }

        return invalidFields
    }
    
}
