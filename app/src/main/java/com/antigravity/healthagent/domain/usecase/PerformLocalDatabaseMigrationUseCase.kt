package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.repository.HouseRepository
import com.antigravity.healthagent.utils.formatStreetName
import com.antigravity.healthagent.utils.toDashDate
import javax.inject.Inject

class PerformLocalDatabaseMigrationUseCase @Inject constructor(
    private val repository: HouseRepository
) {

    suspend fun migrateStreetNamesToFormat() {
        val allHouses = repository.getAllHousesSnapshot()
        val toUpdate = allHouses.filter { it.address.streetName != it.address.streetName.formatStreetName() }
        if (toUpdate.isNotEmpty()) {
            val updated = toUpdate.map { it.copy(address = it.address.copy(streetName = it.address.streetName.formatStreetName())) }
            repository.updateHouses(updated, force = true)
        }
    }

    suspend fun migrateBairrosToUppercase() {
        val allHouses = repository.getAllHousesSnapshot()
        val toUpdate = allHouses.filter { it.address.bairro != it.address.bairro.trim().uppercase() }
        if (toUpdate.isNotEmpty()) {
            val updated = toUpdate.map { it.copy(address = it.address.copy(bairro = it.address.bairro.trim().uppercase())) }
            repository.updateHouses(updated, force = true)
        }
    }

    suspend fun migrateDateFormats() {
        val allHouses = repository.getAllHousesSnapshot()
        val housesToUpdate = allHouses.filter { it.data.contains("/") }
        
        if (housesToUpdate.isNotEmpty()) {
            android.util.Log.i("HouseManagement", "Migrating ${housesToUpdate.size} legacy date formats (/) to standard (-)")
            val updatedHouses = housesToUpdate.map { it.copy(data = it.data.toDashDate()) }
            repository.updateHouses(updatedHouses, force = true)
        }
        
        val allActivities = repository.getAllDayActivitiesSnapshot()
        val activitiesToUpdate = allActivities.filter { it.date.contains("/") }
        
        if (activitiesToUpdate.isNotEmpty()) {
             android.util.Log.i("HouseManagement", "Migrating ${activitiesToUpdate.size} legacy activity dates (/) to standard (-)")
             activitiesToUpdate.forEach { activity ->
                 val newDate = activity.date.toDashDate()
                  repository.runInTransaction {
                      repository.deleteProduction(activity.date, activity.agentUid, force = true)
                      repository.updateDayActivity(activity.copy(date = newDate))
                  }
             }
        }
    }
}
