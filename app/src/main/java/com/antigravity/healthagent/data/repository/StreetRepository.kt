package com.antigravity.healthagent.data.repository

import com.antigravity.healthagent.data.local.StreetData
import com.antigravity.healthagent.data.local.dao.CustomStreetDao
import com.antigravity.healthagent.data.local.dao.HouseDao
import com.antigravity.healthagent.data.local.model.CustomStreet
import com.antigravity.healthagent.utils.formatStreetName
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StreetRepository @Inject constructor(
    private val houseDao: HouseDao,
    private val customStreetDao: CustomStreetDao
) {
    /**
     * Combines the static Bom Jardim street database with historical street names
     * from the local database.
     */
    fun getStreetSuggestions(bairro: String, agentName: String, agentUid: String): Flow<List<String>> {
        val housesFlow = houseDao.getHousesByAgentSnapshotFlow(agentName, agentUid)
        val customStreetsFlow = customStreetDao.getAllCustomStreets()

        return combine(housesFlow, customStreetsFlow) { houses, customStreets ->
            val historicalStreets = if (bairro.isBlank()) {
                houses.map { it.streetName }
            } else {
                houses.filter { it.bairro.equals(bairro, ignoreCase = true) }
                    .map { it.streetName }
            }

            val userCustomStreets = if (bairro.isBlank()) {
                customStreets.map { it.name }
            } else {
                customStreets.filter { it.bairro.equals(bairro, ignoreCase = true) }
                    .map { it.name }
            }

            (StreetData.BOM_JARDIM_STREETS + historicalStreets + userCustomStreets)
                .map { it.trim().formatStreetName() }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
        }
    }

    suspend fun saveCustomStreet(name: String, bairro: String) {
        if (name.isBlank() || bairro.isBlank()) return
        val formattedName = name.trim().formatStreetName()
        val formattedBairro = bairro.trim().uppercase()
        if (StreetData.BOM_JARDIM_STREETS.contains(formattedName)) return
        
        customStreetDao.insertCustomStreet(CustomStreet(formattedName, formattedBairro))
    }
}
