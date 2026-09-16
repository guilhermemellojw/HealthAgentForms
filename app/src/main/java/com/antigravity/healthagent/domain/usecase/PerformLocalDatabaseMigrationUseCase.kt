package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.settings.SettingsManager
import com.antigravity.healthagent.domain.repository.HouseRepository
import com.antigravity.healthagent.utils.formatStreetName
import com.antigravity.healthagent.utils.toDashDate
import com.antigravity.healthagent.domain.logger.AppLogger
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class PerformLocalDatabaseMigrationUseCase @Inject constructor(
    private val repository: HouseRepository,
    private val settingsManager: SettingsManager
) {

    companion object {
        const val CURRENT_MIGRATION_VERSION = 1
    }

    suspend fun runMigrationsIfNeeded() {
        val currentVersion = settingsManager.dataMigrationVersion.first()
        if (currentVersion >= CURRENT_MIGRATION_VERSION) {
            AppLogger.i("PerformLocalDatabaseMigrationUseCase", "Migrations already applied (v$currentVersion), skipping.")
            return
        }

        AppLogger.i("PerformLocalDatabaseMigrationUseCase", "Running data migrations (v$currentVersion -> v$CURRENT_MIGRATION_VERSION)...")
        migrateStreetNamesToFormat()
        migrateBairrosToUppercase()
        migrateDateFormats()

        settingsManager.setDataMigrationVersion(CURRENT_MIGRATION_VERSION)
        AppLogger.i("PerformLocalDatabaseMigrationUseCase", "Data migrations completed, version set to $CURRENT_MIGRATION_VERSION.")
    }

    suspend fun migrateStreetNamesToFormat() {
        val allHouses = repository.getAllHousesSnapshot()
        val toUpdate = allHouses.filter { it.address.streetName != it.address.streetName.formatStreetName() }
        if (toUpdate.isNotEmpty()) {
            val updated = toUpdate.map { it.copy(address = it.address.copy(streetName = it.address.streetName.formatStreetName())) }
            repository.updateHouses(updated, force = true)
            AppLogger.i("HouseManagement", "Migrated ${toUpdate.size} street names to format.")
        }
    }

    suspend fun migrateBairrosToUppercase() {
        val allHouses = repository.getAllHousesSnapshot()
        val toUpdate = allHouses.filter { it.address.bairro != it.address.bairro.trim().uppercase() }
        if (toUpdate.isNotEmpty()) {
            val updated = toUpdate.map { it.copy(address = it.address.copy(bairro = it.address.bairro.trim().uppercase())) }
            repository.updateHouses(updated, force = true)
            AppLogger.i("HouseManagement", "Migrated ${toUpdate.size} bairros to uppercase.")
        }
    }

    suspend fun migrateDateFormats() {
        val allHouses = repository.getAllHousesSnapshot()
        val housesToUpdate = allHouses.filter { it.data.contains("/") }
        
        if (housesToUpdate.isNotEmpty()) {
            AppLogger.i("HouseManagement", "Migrating ${housesToUpdate.size} legacy date formats (/) to standard (-)")
            val updatedHouses = housesToUpdate.map { it.copy(data = it.data.toDashDate()) }
            repository.updateHouses(updatedHouses, force = true)
        }
        
        val allActivities = repository.getAllDayActivitiesSnapshot()
        val activitiesToUpdate = allActivities.filter { it.date.contains("/") }
        
        if (activitiesToUpdate.isNotEmpty()) {
            AppLogger.i("HouseManagement", "Migrating ${activitiesToUpdate.size} legacy activity dates (/) to standard (-)")
            repository.runInTransaction {
                activitiesToUpdate.forEach { activity ->
                    val newDate = activity.date.toDashDate()
                    repository.deleteProduction(activity.date, activity.agentUid, force = true)
                    repository.updateDayActivity(activity.copy(date = newDate))
                }
            }
        }
    }
}
