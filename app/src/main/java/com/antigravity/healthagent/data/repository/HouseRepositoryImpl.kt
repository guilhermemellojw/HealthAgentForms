package com.antigravity.healthagent.data.repository

import com.antigravity.healthagent.data.local.dao.HouseDao
import com.antigravity.healthagent.data.local.dao.DayActivityDao
import androidx.room.withTransaction
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.DayActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject

import com.antigravity.healthagent.data.sync.SyncScheduler
import com.antigravity.healthagent.data.local.dao.TombstoneDao
import com.antigravity.healthagent.data.local.model.Tombstone
import com.antigravity.healthagent.data.local.model.TombstoneType
import com.antigravity.healthagent.utils.withRetry

class HouseRepositoryImpl @Inject constructor(
    private val houseDao: HouseDao,
    private val dayActivityDao: DayActivityDao,
    private val tombstoneDao: TombstoneDao,
    private val database: com.antigravity.healthagent.data.local.AppDatabase,
    private val syncSchedulerProvider: javax.inject.Provider<SyncScheduler>
) : HouseRepository {

    private suspend fun <T> runInTransactionWithRetry(block: suspend () -> T): T {
        return database.withRetry {
            database.withTransaction { block() }
        }
    }

    private suspend fun ensureDayNotLocked(originalDate: String, agentName: String, agentUid: String? = "", force: Boolean = false) {
        if (force) return // Admin bypass
        val date = originalDate.replace("/", "-")
        val activity = dayActivityDao.getDayActivity(date, agentName.uppercase(), agentUid ?: "")
        if (activity?.isClosed == true && !activity.isManualUnlock) {
            throw IllegalStateException("Este dia ($date) está bloqueado para edições (Auditoria Concluída).")
        }
    }

    override fun getAllHouses(agentName: String, agentUid: String): Flow<List<House>> {
        return houseDao.getAllHouses(agentName, agentUid)
    }

    override fun getDistinctAgentNames(): Flow<List<String>> {
        return houseDao.getDistinctAgentNames()
    }

    override fun getAllHousesOrderedByBlock(agentName: String, agentUid: String): Flow<List<House>> {
        return houseDao.getAllHousesOrderedByBlock(agentName, agentUid)
    }

    override suspend fun getHouseById(id: Long): House? {
        return houseDao.getHouseById(id)
    }

    override suspend fun getAllHousesOnce(agentName: String, agentUid: String): List<House> {
        return houseDao.getAllHouses(agentName, agentUid).first()
    }

    override suspend fun getAllHousesSnapshot(): List<House> {
        return houseDao.getAllHousesSnapshot()
    }

    override fun getAllHousesSnapshotFlow(): Flow<List<House>> {
        return houseDao.getAllHousesSnapshotFlow()
    }

    override suspend fun insertHouse(house: House, force: Boolean): Long {
        ensureDayNotLocked(house.data, house.agentName, house.agentUid, force)
        val id = runInTransactionWithRetry {
            val clashCount = houseDao.checkNaturalKeyConflict(
                excludeId = 0,
                date = house.data,
                agentName = house.agentName,
                agentUid = house.agentUid,
                blockNumber = house.blockNumber,
                blockSequence = house.blockSequence,
                streetName = house.streetName,
                number = house.number,
                sequence = house.sequence,
                complement = house.complement,
                bairro = house.bairro,
                visitSegment = house.visitSegment
            )
            if (clashCount > 0 && !force) {
                throw IllegalStateException("Este endereço (${house.streetName}, ${house.number}) já existe neste bairro/quarteirão para este dia.")
            }

            // Healing logic: Prevent EMPTY from being stored locally
            val finalSituation = if (house.situation == com.antigravity.healthagent.data.local.model.Situation.EMPTY) {
                com.antigravity.healthagent.data.local.model.Situation.NONE
            } else house.situation

            // CRITICAL: Cleanup any stale local tombstone for this same house key
            tombstoneDao.deleteByNaturalKey(house.generateNaturalKey(), house.agentName, house.agentUid)
            
            // ADMIN AUTHORITY: Mark as edited by admin if forced
            val houseToInsert = house.copy(
                situation = finalSituation,
                isSynced = false, 
                editedByAdmin = force,
                lastUpdated = System.currentTimeMillis()
            )
            
            houseDao.insertHouse(houseToInsert)
        }
        syncSchedulerProvider.get().scheduleSync()
        return id
    }

    override suspend fun updateHouse(house: House, force: Boolean) {
        ensureDayNotLocked(house.data, house.agentName, house.agentUid, force)
        runInTransactionWithRetry {
            val clashCount = houseDao.checkNaturalKeyConflict(
                excludeId = house.id,
                date = house.data,
                agentName = house.agentName,
                agentUid = house.agentUid,
                blockNumber = house.blockNumber,
                blockSequence = house.blockSequence,
                streetName = house.streetName,
                number = house.number,
                sequence = house.sequence,
                complement = house.complement,
                bairro = house.bairro,
                visitSegment = house.visitSegment
            )
            if (clashCount > 0 && !force) {
                throw IllegalStateException("Alteração impedida: o endereço (${house.streetName}, ${house.number}) já pertence a outro registro neste quarteirão. Use o botão de Mesclar se desejar unir os dados.")
            }

            val existing = houseDao.getHouseById(house.id.toLong())
            if (existing != null) {
                val oldKey = existing.generateNaturalKey()
                val newKey = house.generateNaturalKey()
                if (oldKey != newKey) {
                    tombstoneDao.insertTombstone(Tombstone(type = TombstoneType.HOUSE, naturalKey = oldKey, agentName = existing.agentName, agentUid = existing.agentUid))
                }
                // Also ensures NO tombstone exists for the NEW key (re-added or restored)
                tombstoneDao.deleteByNaturalKey(newKey, house.agentName, house.agentUid)
            }

            // Healing logic: Prevent EMPTY from being stored locally
            val finalSituation = if (house.situation == com.antigravity.healthagent.data.local.model.Situation.EMPTY) {
                com.antigravity.healthagent.data.local.model.Situation.NONE
            } else house.situation

            houseDao.updateHouse(house.copy(
                situation = finalSituation,
                isSynced = false, 
                editedByAdmin = force,
                lastUpdated = System.currentTimeMillis()
            ))
        }
        syncSchedulerProvider.get().scheduleSync()
    }

    override suspend fun updateHouses(houses: List<House>, force: Boolean) {
        if (houses.isEmpty()) return
        
        // Ensure all affected days are not locked
        val distinctContexts = houses.map { Triple(it.data, it.agentName, it.agentUid) }.distinct()
        distinctContexts.forEach { (date, agent, uid) ->
            ensureDayNotLocked(date, agent, uid, force)
        }
        
        runInTransactionWithRetry {
            houses.forEach { house ->
                val clashCount = houseDao.checkNaturalKeyConflict(
                    excludeId = house.id,
                    date = house.data,
                    agentName = house.agentName,
                    agentUid = house.agentUid,
                    blockNumber = house.blockNumber,
                    blockSequence = house.blockSequence,
                    streetName = house.streetName,
                    number = house.number,
                    sequence = house.sequence,
                    complement = house.complement,
                    bairro = house.bairro,
                    visitSegment = house.visitSegment
                )
                if (clashCount > 0 && !force) {
                    throw IllegalStateException("Erro ao atualizar lote: conflito de endereço para o imóvel ID ${house.id}.")
                }

                val existing = houseDao.getHouseById(house.id.toLong())
                if (existing != null) {
                    val oldKey = existing.generateNaturalKey()
                    val newKey = house.generateNaturalKey()
                    if (oldKey != newKey) {
                        tombstoneDao.insertTombstone(Tombstone(type = TombstoneType.HOUSE, naturalKey = oldKey, agentName = existing.agentName, agentUid = existing.agentUid))
                    }
                    // Also clear any tombstone for the new key
                    tombstoneDao.deleteByNaturalKey(newKey, house.agentName, house.agentUid)
                }
            }
            val healedHouses = houses.map { house ->
                val finalSituation = if (house.situation == com.antigravity.healthagent.data.local.model.Situation.EMPTY) {
                    com.antigravity.healthagent.data.local.model.Situation.NONE
                } else house.situation

                house.copy(
                    situation = finalSituation,
                    isSynced = false, 
                    editedByAdmin = force,
                    lastUpdated = System.currentTimeMillis()
                )
            }
            houseDao.upsertHouses(healedHouses)
        }
        syncSchedulerProvider.get().scheduleSync()
    }

    override suspend fun updateHousesDate(oldDate: String, newDate: String, agentName: String, agentUid: String?, force: Boolean) {
        val finalUid = agentUid ?: ""
        ensureDayNotLocked(oldDate, agentName, finalUid, force)
        ensureDayNotLocked(newDate, agentName, finalUid, force)
        
        runInTransactionWithRetry {
            val houses = houseDao.getHousesByDateAndAgent(oldDate, agentName.uppercase(), finalUid)
            val tombstones = houses.map { Tombstone(type = TombstoneType.HOUSE, naturalKey = it.generateNaturalKey(), agentName = it.agentName, agentUid = it.agentUid) }
            tombstoneDao.insertTombstones(tombstones)
            
            houseDao.updateHousesDate(oldDate, newDate, agentName, finalUid)
        }
        syncSchedulerProvider.get().scheduleSync()
    }

    override suspend fun deleteHouse(house: House, force: Boolean) {
        ensureDayNotLocked(house.data, house.agentName, house.agentUid, force)
        runInTransactionWithRetry {
            houseDao.deleteHouse(house)
            tombstoneDao.insertTombstone(Tombstone(type = TombstoneType.HOUSE, naturalKey = house.generateNaturalKey(), agentName = house.agentName, agentUid = house.agentUid, dataDate = house.data))
        }
        syncSchedulerProvider.get().scheduleSync()
    }

    override suspend fun replaceAllHouses(houses: List<House>) {
        houseDao.replaceHouses(houses)
        syncSchedulerProvider.get().scheduleSync()
    }

    override suspend fun getHousesByDateAndAgent(date: String, agentName: String, agentUid: String): List<House> {
        return houseDao.getHousesByDateAndAgent(date, agentName.uppercase(), agentUid)
    }

    override fun getDayActivities(dates: List<String>, agentName: String, agentUid: String?): Flow<List<DayActivity>> {
        return dayActivityDao.getDayActivities(dates, agentName, agentUid ?: "")
    }

    override fun getDayActivityFlow(date: String, agentName: String, agentUid: String?): Flow<DayActivity?> {
        return dayActivityDao.getDayActivityFlow(date, agentName, agentUid ?: "")
    }

    override suspend fun updateDayActivity(dayActivity: DayActivity, force: Boolean) {
        val upperName = dayActivity.agentName.uppercase()
        runInTransactionWithRetry {
            // Tombstone cleanup for re-opened/re-added activities
            tombstoneDao.deleteByNaturalKey("${dayActivity.date}|${upperName}", upperName, dayActivity.agentUid)
            
            dayActivityDao.insertDayActivity(dayActivity.copy(
                date = dayActivity.date.replace("/", "-"),
                agentName = upperName,
                isSynced = false, 
                editedByAdmin = force,
                lastUpdated = System.currentTimeMillis()
            ))
        }
        syncSchedulerProvider.get().scheduleSync()
    }
    
    override suspend fun deleteDayActivity(date: String, agentName: String, agentUid: String?) {
        dayActivityDao.deleteDayActivity(date, agentName.uppercase(), agentUid ?: "")
    }
    
    override suspend fun <T> runInTransaction(block: suspend () -> T): T {
        return runInTransactionWithRetry { block() }
    }

    override suspend fun getDayActivity(date: String, agentName: String, agentUid: String?): DayActivity? {
        return dayActivityDao.getDayActivity(date, agentName, agentUid ?: "")
    }

    override suspend fun getAllDayActivitiesOnce(agentName: String, agentUid: String): List<DayActivity> {
        return dayActivityDao.getAllDayActivities(agentName, agentUid)
    }

    override suspend fun getAllDayActivitiesSnapshot(): List<DayActivity> {
        return dayActivityDao.getAllDayActivitiesSnapshot()
    }

    override suspend fun replaceAllDayActivities(activities: List<DayActivity>) {
        dayActivityDao.replaceDayActivities(activities)
        syncSchedulerProvider.get().scheduleSync()
    }

    override suspend fun restoreAgentData(agentName: String, houses: List<House>, activities: List<DayActivity>, agentUid: String?) {
        val finalUid = agentUid ?: ""
        runInTransactionWithRetry {
            // 1. Identify all dates being restored (normalized)
            val restoredDatesList = (houses.map { it.data.replace("/", "-") } + activities.map { it.date.replace("/", "-") }).distinct()

            // 2. Perform Atomic CLEANUP of local data for these dates FIRST
            // This ensures a true "Full Replace" and prevents casing-based duplicates ("John Doe" vs "JOHN DOE")
            // while avoiding self-deletion bugs.
            if (restoredDatesList.isNotEmpty()) {
                android.util.Log.i("HouseRepository", "Local Restoration: Atomic purge of ${restoredDatesList.size} dates for $agentName")
                houseDao.deleteByAgentAndDates(agentName, finalUid, restoredDatesList)
                dayActivityDao.deleteByAgentAndDates(agentName, finalUid, restoredDatesList)
            }

            // 3. Normalize and Prepare Data
            val normalizedActivities = activities.map { 
                val upperName = agentName.uppercase()
                val normalized = it.copy(
                    agentName = upperName,
                    agentUid = finalUid,
                    date = it.date.replace("/", "-"),
                    isSynced = false,
                    lastUpdated = System.currentTimeMillis()
                ) 
                // Clear local tombstone for restored activity
                tombstoneDao.deleteByNaturalKey("${normalized.date}|${normalized.agentName}", normalized.agentName, normalized.agentUid)
                normalized
            }

            val housesToUpsert = houses.map { restoredHouse ->
                val upperName = agentName.uppercase()
                
                // Healing logic: Default EMPTY to NONE (Aberto) for restored data
                val finalSituation = if (restoredHouse.situation == com.antigravity.healthagent.data.local.model.Situation.EMPTY) {
                    com.antigravity.healthagent.data.local.model.Situation.NONE
                } else restoredHouse.situation

                val finalHouse = restoredHouse.copy(
                    id = 0, // Reset ID to allow auto-generation and prevent collision with unrelated local records
                    agentName = upperName,
                    agentUid = finalUid,
                    data = restoredHouse.data.replace("/", "-"),
                    situation = finalSituation,
                    isSynced = false,
                    lastUpdated = System.currentTimeMillis()
                )
                
                // Clear local tombstone for restored house
                tombstoneDao.deleteByNaturalKey(finalHouse.generateNaturalKey(), finalHouse.agentName, finalHouse.agentUid)
                
                finalHouse
            }

            // 4. Upsert Data
            houseDao.upsertHouses(housesToUpsert)
            dayActivityDao.upsertDayActivities(normalizedActivities)
        }
        syncSchedulerProvider.get().scheduleSync()
    }

    override suspend fun deleteProduction(originalDate: String, agentName: String, agentUid: String?, force: Boolean) {
        val date = originalDate.replace("/", "-")
        val finalUid = agentUid ?: ""
        ensureDayNotLocked(date, agentName, finalUid, force)
        val upperName = agentName.uppercase()
        runInTransactionWithRetry {
            // 1. Get all houses for this date to record tombstones
            val housesToDelete = houseDao.getHousesByDateAndAgent(date, upperName, finalUid)
            val houseTombstones = housesToDelete.map { Tombstone(type = TombstoneType.HOUSE, naturalKey = it.generateNaturalKey(), agentName = it.agentName, agentUid = it.agentUid) }
            
            // 2. Record activity tombstone
            val activityTombstone = Tombstone(type = TombstoneType.ACTIVITY, naturalKey = "$date|$upperName", agentName = upperName, agentUid = finalUid)
            
            // 3. Perform Deletions
            dayActivityDao.deleteDayActivity(date, upperName, finalUid)
            houseDao.deleteHousesByDateAndAgent(date, upperName, finalUid)
            
            // 4. Record Tombstones
            tombstoneDao.insertTombstones(houseTombstones)
            tombstoneDao.insertTombstone(activityTombstone)
        }
        syncSchedulerProvider.get().scheduleSync()
    }

    override suspend fun deleteByAgentAndDates(agentName: String, originalDates: List<String>, agentUid: String?, force: Boolean) {
        val dates = originalDates.map { it.replace("/", "-") }
        val upperName = agentName.uppercase()
        val finalUid = agentUid ?: ""
        // Lock Check: Verify all dates
        dates.forEach { ensureDayNotLocked(it, upperName, finalUid, force) }

        runInTransactionWithRetry {
            // 1. Get all houses and activities for these dates to record tombstones
            val housesToDelete = houseDao.getHousesByAgentAndDates(upperName, finalUid, dates)
            val houseTombstones = housesToDelete.map { Tombstone(type = TombstoneType.HOUSE, naturalKey = it.generateNaturalKey(), agentName = it.agentName, agentUid = it.agentUid, dataDate = it.data) }
            
            val activityTombstones = dates.map { Tombstone(type = TombstoneType.ACTIVITY, naturalKey = "$it|$upperName", agentName = upperName, agentUid = finalUid, dataDate = it) }
            
            // 2. Perform Deletions
            dayActivityDao.deleteByAgentAndDates(upperName, finalUid, dates)
            houseDao.deleteByAgentAndDates(upperName, finalUid, dates)
            
            // 3. Record Tombstones
            tombstoneDao.insertTombstones(houseTombstones)
            tombstoneDao.insertTombstones(activityTombstones)
        }
        syncSchedulerProvider.get().scheduleSync()
    }

    override suspend fun countOpenDays(agentName: String, agentUid: String?): Int {
        return dayActivityDao.countOpenDays(agentName, agentUid ?: "")
    }

    override suspend fun closeAllDays(agentName: String, agentUid: String?) {
        val upperName = agentName.uppercase()
        val finalUid = agentUid ?: ""
        runInTransactionWithRetry {
            // 1. Close all existing activity records for this agent
            dayActivityDao.closeAllActivities(upperName, finalUid)

            // 2. Ensure days with houses have a (closed) activity record
            val dates = houseDao.getDistinctDates(upperName, finalUid)
            
            dates.forEach { rawDate ->
                val date = rawDate.replace("/", "-")
                val activity = dayActivityDao.getDayActivity(date, upperName, finalUid)
                if (activity == null) {
                    dayActivityDao.insertDayActivity(DayActivity(
                        date = date, 
                        status = "NORMAL", 
                        isClosed = true, 
                        isManualUnlock = false,
                        agentName = upperName, 
                        agentUid = finalUid, 
                        isSynced = false,
                        lastUpdated = System.currentTimeMillis()
                    ))
                } else if (!activity.isClosed || activity.isManualUnlock) {
                    dayActivityDao.insertDayActivity(activity.copy(
                        date = date, 
                        isClosed = true, 
                        isManualUnlock = false,
                        agentName = upperName, 
                        agentUid = finalUid, 
                        isSynced = false, 
                        lastUpdated = System.currentTimeMillis()
                    ))
                }
            }
        }
        syncSchedulerProvider.get().scheduleSync()
    }


    override suspend fun clearAllData() {
        runInTransactionWithRetry {
            houseDao.deleteAll()
            dayActivityDao.deleteAll()
        }
    }

    override suspend fun migrateLocalData(agentName: String, email: String, targetUid: String) {
        if (targetUid.isBlank()) return
        runInTransactionWithRetry {
            try {
                // 0. If current name is email prefix and we have a better name, migrate local records
                val emailPrefix = email.substringBefore("@").uppercase()
                val properName = agentName.trim().ifBlank { emailPrefix }.uppercase()
                
                // CRITICAL IMPROVEMENT: Fix any records that have an email as agentName if we have a proper name (Bug Fix #11)
                if (properName.isNotBlank() && !properName.contains("@")) {
                    houseDao.fixEmailNamesForUid(targetUid, properName)
                    dayActivityDao.fixEmailNamesForUid(targetUid, properName)
                }

                if (properName.isNotBlank() && properName != emailPrefix) {
                    houseDao.updateAgentNameForAll(emailPrefix, properName, targetUid)
                    dayActivityDao.updateAgentNameForAll(emailPrefix, properName, targetUid)
                }

                // 1. Standardize Houses (Lenient/Aggressive migration)
                val allOrphans = houseDao.getAllOrphanHouses()
                allOrphans.forEach { house ->
                    val isPossibleMatch = house.agentName.isBlank() || 
                        house.agentName.equals("AGENTE", true) ||
                        house.agentName.equals(agentName, true) ||
                        house.agentName.equals(email, true) ||
                        house.agentName.equals(emailPrefix, true)

                    if (isPossibleMatch) {
                        val hasClash = houseDao.checkClash(
                            targetUid, properName, house.data, house.blockNumber, house.blockSequence, 
                            house.streetName, house.number, house.sequence, house.complement, house.bairro, house.visitSegment
                        ) > 0
                        
                        if (hasClash) {
                            houseDao.deleteHouseById(house.id)
                        } else {
                            houseDao.updateHouseIdentity(house.id, targetUid, properName)
                        }
                    }
                }

                // 2. Smarter DayActivity Migration
                val orphanActivities = dayActivityDao.getAllOrphanActivities()
                orphanActivities.forEach { local ->
                    val isPossibleMatch = local.agentName.isBlank() || 
                        local.agentName.equals("AGENTE", true) ||
                        local.agentName.equals(agentName, true) ||
                        local.agentName.equals(email, true) ||
                        local.agentName.equals(emailPrefix, true)

                    if (isPossibleMatch) {
                        val conflict = getDayActivity(local.date, agentName, targetUid)
                        if (conflict != null) {
                            // Conflict resolution: Keep local if it has a specific status AND cloud is default/empty
                            if (local.status != "NORMAL" && (conflict.status == "NORMAL" || conflict.status.isBlank())) {
                                dayActivityDao.deleteDayActivity(conflict.date, conflict.agentName, conflict.agentUid)
                                dayActivityDao.insertDayActivity(local.copy(agentName = properName, agentUid = targetUid))
                            } else {
                                // Prefer incoming cloud data for conflict (already in DB)
                                dayActivityDao.deleteDayActivity(local.date, local.agentName, local.agentUid)
                            }
                        } else {
                            dayActivityDao.insertDayActivity(local.copy(agentName = properName, agentUid = targetUid))
                        }
                    }
                }
                
                // 3. Final Deduplication Pass
                deduplicateAgentData(properName, targetUid)
            } catch (e: Exception) {
                android.util.Log.e("HouseRepository", "Error during migration", e)
            }
        }
    }

    override suspend fun fixEmailNamesForUid(uid: String, properName: String) {
        if (uid.isBlank() || properName.isBlank()) return
        runInTransactionWithRetry {
            val upperName = properName.trim().uppercase()
            houseDao.fixEmailNamesForUid(uid, upperName)
            dayActivityDao.fixEmailNamesForUid(uid, upperName)
        }
    }

    override suspend fun deduplicateAgentData(agentName: String, agentUid: String) {
        runInTransactionWithRetry {
            val allHouses = getAllHousesOnce(agentName, agentUid)
            if (allHouses.isEmpty()) return@runInTransactionWithRetry

            val groups = allHouses.groupBy { it.generateNaturalKey() }
            val toDelete = mutableListOf<House>()

            groups.forEach { (_, matches) ->
                if (matches.size > 1) {
                    // Keep the best record: prioritize synced, then most recent
                    val kept = matches.sortedWith(
                        compareByDescending<House> { it.isSynced }
                            .thenByDescending { it.lastUpdated }
                    ).first()
                    
                    matches.forEach { house ->
                        if (house.id != kept.id) {
                            toDelete.add(house)
                        }
                    }
                }
            }

            if (toDelete.isNotEmpty()) {
                toDelete.forEach { houseDao.deleteHouse(it) }
                syncSchedulerProvider.get().scheduleSync()
            }
        }
    }

    override suspend fun normalizeLocalDates() {
        runInTransactionWithRetry {
            val allHouses = houseDao.getAllHousesSnapshot()
            val housesToUpdate = allHouses.filter { it.data.contains("/") }
            if (housesToUpdate.isNotEmpty()) {
                val updated = housesToUpdate.map { 
                    it.copy(
                        data = it.data.replace("/", "-"),
                        isSynced = false,
                        lastUpdated = System.currentTimeMillis()
                    )
                }
                houseDao.upsertHouses(updated)
            }

            val allActivities = dayActivityDao.getAllDayActivitiesSnapshot()
            val activitiesToMigrate = allActivities.filter { it.date.contains("/") }
            if (activitiesToMigrate.isNotEmpty()) {
                for (activity in activitiesToMigrate) {
                    val newActivity = activity.copy(
                        date = activity.date.replace("/", "-"),
                        isSynced = false,
                        lastUpdated = System.currentTimeMillis()
                    )
                    dayActivityDao.deleteDayActivity(activity.date, activity.agentName, activity.agentUid)
                    dayActivityDao.insertDayActivity(newActivity)
                }
            }
        }
    }
}
