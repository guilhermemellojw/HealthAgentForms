package com.antigravity.healthagent.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.antigravity.healthagent.data.local.AppDatabase
import com.antigravity.healthagent.data.local.dao.DayActivityDao
import com.antigravity.healthagent.data.local.dao.HouseDao
import com.antigravity.healthagent.data.local.dao.TombstoneDao
import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.Tombstone
import com.antigravity.healthagent.data.local.model.TombstoneType
import com.antigravity.healthagent.data.settings.SettingsManager
import com.antigravity.healthagent.data.util.toDayActivitySafe
import com.antigravity.healthagent.data.util.toHouseSafe
import com.antigravity.healthagent.utils.AppConstants
import com.antigravity.healthagent.utils.withRetry
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncPullHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val houseDao: HouseDao,
    private val dayActivityDao: DayActivityDao,
    private val tombstoneDao: TombstoneDao,
    private val settingsManager: SettingsManager,
    private val database: AppDatabase
) {

    private class HouseWithKeys(
        val house: House,
        val naturalKey: String = house.generateNaturalKey(),
        val identityKey: String = house.generateIdentityKey()
    )

    private suspend fun <T> runInTransactionWithRetry(block: suspend () -> T): T {
        return database.withRetry(maxAttempts = 3) {
            database.withTransaction { block() }
        }
    }

    private suspend fun fetchSystemSettings(): Result<Map<String, Any>> {
        return try {
            val snapshot = withTimeoutOrNull(5000) {
                firestore.collection("metadata").document("settings")
                    .get().await()
            }
            if (snapshot == null) return Result.success(emptyMap())
            val settings = snapshot.data ?: emptyMap()
            Result.success(settings)
        } catch (e: Exception) {
            android.util.Log.w("SyncPullHandler", "fetchSystemSettings offline fallback: ${e.message}")
            Result.success(emptyMap())
        }
    }

    suspend fun pullCloudDataToLocal(targetUid: String?, force: Boolean, syncMutex: Mutex): Result<Unit> {
        val result = withTimeoutOrNull(600000L) {
            syncMutex.withLock {
                withContext(Dispatchers.IO) {
                    try {
                        val uid = targetUid ?: auth.currentUser?.uid ?: return@withContext Result.failure(Exception("Not logged in"))
                        val isTargetDifferentUser = targetUid != null && targetUid != auth.currentUser?.uid
                    
                        // 1. Basic Setup
                        val userDoc = firestore.collection("users").document(uid).get().await()
                        val agentDocSnapshot = firestore.collection("agents").document(uid).get().await()
                        
                        val email = userDoc.getString("email") 
                            ?: agentDocSnapshot.getString("email") 
                            ?: auth.currentUser?.email 
                            ?: return@withContext Result.failure(Exception("User email not found"))
                        
                        val profileAgentName = agentDocSnapshot.getString("agentName")?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
                            ?: userDoc.getString("agentName")?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
                        val profileDisplayName = userDoc.getString("displayName")?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
                        
                        val authEmail = if (!isTargetDifferentUser) auth.currentUser?.email?.trim() ?: "" else ""
                        val discoveryEmails = setOf(email, authEmail, email.lowercase(), authEmail.lowercase()).filter { it.isNotBlank() }

                        val finalAgentName = profileAgentName ?: profileDisplayName ?: email.substringBefore("@").uppercase()
                        
                        // --- VERSION SAFETY CHECK ---
                        val sysSettings = fetchSystemSettings().getOrDefault(emptyMap())
                        val minVersion = (sysSettings["minAppVersion"] as? Number)?.toInt() ?: AppConstants.MIN_VERSION_CODE
                        val pInfo = try { context.packageManager.getPackageInfo(context.packageName, 0) } catch (e: Exception) { null }
                        val currentVersion = pInfo?.versionCode ?: 0
                        
                        if (currentVersion < minVersion) {
                            android.util.Log.e("SyncPullHandler", "Version Enforcement: App version ($currentVersion) is below minimum required ($minVersion)")
                            return@withContext Result.failure(Exception("Versão do aplicativo desatualizada. Por favor, atualize o 'Eu ACE' na Play Store para continuar sincronizando seus dados."))
                        }
                        
                        // --- REMOTE WIPE CHECK (Multi-device Safety) ---
                        val requireResetFromUser = userDoc.getBoolean("requireDataReset") ?: false
                        val requireResetFromAgent = agentDocSnapshot.getBoolean("requireDataReset") ?: false
                        
                        val hasSyncHistory = settingsManager.lastSyncTimestamp.first() > 0
                        val agentDocExists = agentDocSnapshot.exists()
                        
                        val requireReset = requireResetFromUser || requireResetFromAgent || (!isTargetDifferentUser && hasSyncHistory && !agentDocExists)

                        // --- OPTIMIZATION: Incremental vs Full Sync logic ---
                        val localCount = houseDao.count()
                        val isIncremental = !force && !isTargetDifferentUser && !requireReset && settingsManager.lastSyncTimestamp.first() > 0 && localCount > 0

                        if (requireReset) {
                            android.util.Log.w("SyncPullHandler", "Remote Wipe Triggered for UID: $uid")
                            val wipeResult = runInTransactionWithRetry {
                                houseDao.deleteByAgent(uid)
                                dayActivityDao.deleteByAgent(uid)
                                tombstoneDao.deleteByAgent(uid)
                                
                                if (!isTargetDifferentUser) {
                                    settingsManager.setLastSyncTimestamp(0L)
                                }
                                Result.success(Unit)
                            }
                            if (wipeResult.isSuccess) {
                                if (requireResetFromUser) firestore.collection("users").document(uid).update("requireDataReset", false)
                                if (requireResetFromAgent) firestore.collection("agents").document(uid).update("requireDataReset", false)
                            } else {
                                android.util.Log.e("SyncPullHandler", "Remote Wipe Failed: ${wipeResult.exceptionOrNull()?.message}")
                                return@withContext Result.failure(wipeResult.exceptionOrNull() ?: Exception("Falha ao realizar wipe local"))
                            }
                        }

                        val cachedLastSync = if (isTargetDifferentUser || force || requireReset) 0L else settingsManager.lastSyncTimestamp.first()
                        val now = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis()
                        val lastSync = if (cachedLastSync > now + 3600000L) 0L else cachedLastSync
                        val serverTime = now

                        // 2. Fetchers (EXHAUSTIVE DISCOVERY)
                        val isHighPrivilegeUser = auth.currentUser?.uid?.let { 
                            try { firestore.collection("users").document(it).get().await().getString("role")?.let { r -> r == "ADMIN" || r == "SUPERVISOR" } } catch(e: Exception) { false }
                        } ?: false

                        val possibleAgentDocs = mutableListOf(firestore.collection("agents").document(uid))
                        
                        if (!isIncremental && !isHighPrivilegeUser) {
                            discoveryEmails.forEach { dEmail ->
                                try {
                                    val matchingEmailDocs = firestore.collection("agents")
                                        .whereEqualTo("email", dEmail)
                                        .get().await()
                                    
                                    matchingEmailDocs.documents.forEach { doc ->
                                        if (doc.id != uid) {
                                            possibleAgentDocs.add(doc.reference)
                                            
                                            val legacyName = doc.getString("agentName")
                                            if (profileAgentName == null && !legacyName.isNullOrBlank()) {
                                                android.util.Log.i("SyncPullHandler", "Identity Healing: Adopting legacy agentName '$legacyName' from ${doc.id} for $uid")
                                                try {
                                                    firestore.collection("agents").document(uid).update("agentName", legacyName)
                                                    firestore.collection("users").document(uid).update("agentName", legacyName)
                                                } catch (e: Exception) {
                                                    android.util.Log.w("SyncPullHandler", "Identity Healing failed to update cloud profile: ${e.message}")
                                                }
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.w("SyncPullHandler", "Failed to search legacy agent docs for $dEmail", e)
                                }
                            }
                        }

                        val cloudHouses = mutableListOf<House>()
                        val cloudDayActivities = mutableListOf<DayActivity>()
                        val cloudDeletedHouses = mutableSetOf<String>()
                        val cloudDeletedActivities = mutableSetOf<String>()

                        val discoveryResults = coroutineScope {
                            possibleAgentDocs.map { agentDocRef ->
                                async {
                                    val agentDoc = try { agentDocRef.get().await() } catch(e: Exception) { null }
                                    val housesCollection = agentDocRef.collection("houses")
                                    val activitiesCollection = agentDocRef.collection("day_activities")

                                    val housesJob = async {
                                        val snapshots = if (lastSync > 0) {
                                            val safetyLastSync = maxOf(0L, lastSync - 300000L)
                                            housesCollection.whereGreaterThan("lastUpdated", com.google.firebase.Timestamp(safetyLastSync / 1000, ((safetyLastSync % 1000) * 1000000).toInt()))
                                                .get().await()
                                                .documents
                                        } else {
                                            housesCollection.get().await()
                                                .documents
                                        }
                                        
                                        if (snapshots.size > 200) {
                                            snapshots.chunked(100).map { chunk ->
                                                async { chunk.mapNotNull { it.toHouseSafe(uid, finalAgentName) } }
                                            }.awaitAll().flatten()
                                        } else {
                                            snapshots.mapNotNull { it.toHouseSafe(uid, finalAgentName) }
                                        }
                                    }

                                    val activitiesJob = async {
                                        val snapshots = if (lastSync > 0) {
                                            val safetyLastSync = maxOf(0L, lastSync - 300000L)
                                            activitiesCollection.whereGreaterThan("lastUpdated", com.google.firebase.Timestamp(safetyLastSync / 1000, ((safetyLastSync % 1000) * 1000000).toInt()))
                                                .get().await()
                                                .documents
                                        } else {
                                            activitiesCollection.get().await()
                                                .documents
                                        }
                                        
                                        if (snapshots.size > 200) {
                                            snapshots.chunked(100).map { chunk ->
                                                async { chunk.mapNotNull { it.toDayActivitySafe(uid, finalAgentName) } }
                                            }.awaitAll().flatten()
                                        } else {
                                            snapshots.mapNotNull { it.toDayActivitySafe(uid, finalAgentName) }
                                        }
                                    }

                                    val houses = housesJob.await()
                                    val activities = activitiesJob.await()

                                    // CLOCK SKEW DETECTION
                                    val currentDeviceTime = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis()
                                    var maxCloudTime = 0L
                                    houses.forEach { if (it.lastUpdated > maxCloudTime) maxCloudTime = it.lastUpdated }
                                    activities.forEach { if (it.lastUpdated > maxCloudTime) maxCloudTime = it.lastUpdated }
                                    
                                    val skew = maxCloudTime - currentDeviceTime
                                    if (kotlin.math.abs(skew) > 120000) { // 2 minute threshold
                                        settingsManager.setClockSkewMs(skew)
                                        android.util.Log.w("SyncPullHandler", "Clock Skew: Device is ${if(skew < 0) "AHEAD" else "BEHIND"} by ${kotlin.math.abs(skew)} ms.")
                                    } else {
                                        settingsManager.setClockSkewMs(0L)
                                    }

                                    @Suppress("UNCHECKED_CAST")
                                    val deletedHouses = (agentDoc?.get("deleted_house_ids") as? List<String> ?: emptyList())
                                        .map { it.replace("/", "-") }
                                    @Suppress("UNCHECKED_CAST")
                                    val deletedActivities = (agentDoc?.get("deleted_activity_dates") as? List<String> ?: emptyList())
                                        .map { it.replace("/", "-") }
                                    
                                    Triple(houses, activities, deletedHouses to deletedActivities)
                                }
                            }.awaitAll()
                        }

                        for (discoveryResult in discoveryResults) {
                            cloudHouses.addAll(discoveryResult.first)
                            cloudDayActivities.addAll(discoveryResult.second)
                            cloudDeletedHouses.addAll(discoveryResult.third.first)
                            discoveryResult.third.second.forEach { entry ->
                                val key = if (entry.contains("|")) entry.replace("/", "-") 
                                          else "${entry.replace("/", "-")}|${finalAgentName.uppercase()}"
                                cloudDeletedActivities.add(key)
                            }
                        }

                        // --- PHASE 2: TEAMWORK SYNC (AUTOMATIC) ---
                        if (!isTargetDifferentUser) {
                            try {
                                val activeBlocks = houseDao.getActiveBlockNumbers()
                                if (activeBlocks.isNotEmpty()) {
                                    val currentCiclo = cloudHouses.firstOrNull()?.context?.ciclo ?: run {
                                        val cal = java.util.Calendar.getInstance()
                                        val month = cal.get(java.util.Calendar.MONTH)
                                        when (month) {
                                            java.util.Calendar.JANUARY, java.util.Calendar.FEBRUARY -> "1º"
                                            java.util.Calendar.MARCH, java.util.Calendar.APRIL -> "2º"
                                            java.util.Calendar.MAY, java.util.Calendar.JUNE -> "3º"
                                            java.util.Calendar.JULY, java.util.Calendar.AUGUST -> "4º"
                                            java.util.Calendar.SEPTEMBER, java.util.Calendar.OCTOBER -> "5º"
                                            java.util.Calendar.NOVEMBER, java.util.Calendar.DECEMBER -> "6º"
                                            else -> "1º"
                                        }
                                    }
                                    
                                    val teamHouses = activeBlocks.chunked(10).flatMap { blockChunk ->
                                        firestore.collectionGroup("houses")
                                            .whereIn("blockNumber", blockChunk)
                                            .whereEqualTo("ciclo", currentCiclo)
                                            .get().await().documents
                                    }.mapNotNull { it.toHouseSafe(it.getString("agentUid") ?: "", it.getString("agentName") ?: "") }
                                    
                                    val remoteForeignHouses = teamHouses.filter { it.agentUid != uid }
                                    
                                    val localTeamHouses = houseDao.getHousesByBlocks(activeBlocks).filter { 
                                        it.agentUid.isNotBlank() && it.agentUid != uid && it.context.ciclo == currentCiclo 
                                    }
                                    val remoteKeys = remoteForeignHouses.map { it.generateNaturalKey() }.toSet()
                                    
                                    val housesDeletedByTeam = localTeamHouses.filter { it.generateNaturalKey() !in remoteKeys }
                                    if (housesDeletedByTeam.isNotEmpty()) {
                                        android.util.Log.i("SyncPullHandler", "Team Sync: Deleting ${housesDeletedByTeam.size} houses removed by colleagues.")
                                        runInTransactionWithRetry {
                                            housesDeletedByTeam.forEach { houseDao.deleteHouse(it) }
                                        }
                                    }
                                    
                                    cloudHouses.addAll(remoteForeignHouses)
                                }
                            } catch (e: Exception) {
                                android.util.Log.w("SyncPullHandler", "Teamwork sync failed (skipping): ${e.message}")
                            }
                        }

                        // 3. Deletion logic
                        var cloudHousesWithKeys = cloudHouses.map { HouseWithKeys(it) }
                        val validCloudHouseKeys = cloudHousesWithKeys.map { it.naturalKey }.toSet()
                        val validCloudActivityKeys = cloudDayActivities.map { "${it.date.replace("/", "-")}|${it.agentName.uppercase()}" }.toSet()
                        
                        // SELF-HEALING: Clean up zombie tombstones in Firestore
                        val zombieActivities = cloudDeletedActivities.filter { tombstoneKey ->
                            val datePart = tombstoneKey.split("|")[0].replace("/", "-")
                            validCloudActivityKeys.any { it.startsWith(datePart) }
                        }
                        val zombieHouses = cloudDeletedHouses.filter { it in validCloudHouseKeys }
                        
                        if (zombieActivities.isNotEmpty() || zombieHouses.isNotEmpty()) {
                            cloudDeletedActivities.removeAll(zombieActivities.toSet())
                            cloudDeletedHouses.removeAll(zombieHouses.toSet())
                            
                            try {
                                val batch = firestore.batch()
                                val docRef = firestore.collection("agents").document(uid)
                                if (zombieActivities.isNotEmpty()) {
                                    val dateOnlyZombies = zombieActivities.map { it.split("|")[0] }
                                    batch.update(docRef, "deleted_activity_dates", com.google.firebase.firestore.FieldValue.arrayRemove(*(zombieActivities + dateOnlyZombies).toTypedArray()))
                                }
                                if (zombieHouses.isNotEmpty()) {
                                    batch.update(docRef, "deleted_house_ids", com.google.firebase.firestore.FieldValue.arrayRemove(*zombieHouses.toTypedArray()))
                                }
                                batch.commit().await()
                                android.util.Log.i("SyncPullHandler", "Self-Healing: Removed ${zombieActivities.size} zombie activity tombstones and ${zombieHouses.size} house tombstones from Firestore.")
                            } catch (e: Exception) {
                                android.util.Log.w("SyncPullHandler", "Self-Healing failed: ${e.message}")
                            }
                        }

                        cloudDeletedHouses.removeAll { it in validCloudHouseKeys }
                        cloudDeletedActivities.removeAll { it in validCloudActivityKeys }
                        
                        val allLocalHouses = houseDao.getHousesByAgentSnapshot(uid)
                        var allLocalHousesWithKeys = allLocalHouses.map { HouseWithKeys(it) }
                        
                        val cloudDeletedIdentities = cloudDeletedHouses.mapNotNull { deletedKey ->
                            val parts = deletedKey.split("_")
                            if (parts.size >= 11) {
                                try {
                                    "${parts[0]}_${parts[2]}_${parts[3]}_${parts[4]}_${parts[5]}_${parts[6]}_${parts[7]}_${parts[8]}_${parts[9]}".uppercase()
                                } catch (e: Exception) { null }
                            } else null
                        }.toSet()

                        val housesToDelete = allLocalHousesWithKeys.filter { wrapper ->
                            val house = wrapper.house
                            val key = wrapper.naturalKey
                            val identityKey = wrapper.identityKey
                            
                            if (key in cloudDeletedHouses || identityKey in cloudDeletedIdentities || "${house.data.replace("/", "-")}|${house.agentName.uppercase()}" in cloudDeletedActivities) {
                                val timeSinceLastUpdate = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis() - house.lastUpdated
                                if (house.isSynced) {
                                    true
                                } else if (timeSinceLastUpdate > 900000L) {
                                    android.util.Log.i("SyncPullHandler", "Admin Authority / Ghost Cleanup: Deleting unsynced house ${house.id} due to cloud deletion.")
                                    true
                                } else {
                                    android.util.Log.i("SyncPullHandler", "Agent Priority: Preserving actively typed house ${house.id} despite cloud deletion.")
                                    false
                                }
                            } else false
                        }.map { it.house }

                        val allLocalActivities = dayActivityDao.getAllDayActivities(uid)
                        
                        allLocalActivities.filter { it.date.replace("/", "-") in cloudDeletedActivities }.forEach {
                            android.util.Log.i("SyncPullHandler", "Cloud Deletion Sync: Deleting local activity ${it.date} for $finalAgentName")
                            runInTransactionWithRetry {
                                dayActivityDao.deleteDayActivity(it.date, it.agentUid)
                            }
                        }

                        val activitiesToDelete = allLocalActivities.filter { activity ->
                            val dateKey = "${activity.date}|${activity.agentUid}"
                            
                            if (dateKey in cloudDeletedActivities) {
                                val timeSinceLastUpdate = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis() - activity.lastUpdated
                                if (activity.isSynced) {
                                    true
                                } else if (timeSinceLastUpdate > 900000L) {
                                    android.util.Log.i("SyncPullHandler", "Admin Authority / Ghost Cleanup: Deleting unsynced activity ${activity.date} due to cloud deletion.")
                                    true
                                } else {
                                    android.util.Log.i("SyncPullHandler", "Agent Priority: Preserving actively typed activity ${activity.date} despite cloud deletion.")
                                    false
                                }
                            } else false
                        }

                        // 4. Reconciliation
                        val localHousesByNaturalKey = allLocalHousesWithKeys.associateBy { it.naturalKey }
                        val localActivities = allLocalActivities.groupBy { "${it.date.replace("/", "-")}|${it.agentUid}" }
                        
                        val localTombstones = tombstoneDao.getAllTombstones(uid)
                        val localHouseTombstoneKeys = localTombstones.filter { it.type == TombstoneType.HOUSE }.map { it.naturalKey }.toSet()
                        val localActivityTombstoneKeys = localTombstones.filter { it.type == TombstoneType.ACTIVITY }.map { it.naturalKey }.toSet()

                        val housesDelta = cloudHousesWithKeys.filter { 
                            it.naturalKey !in cloudDeletedHouses && it.naturalKey !in localHouseTombstoneKeys 
                        }
                        val activitiesDelta = cloudDayActivities.filter {
                            val dateKey = "${it.date.replace("/", "-")}|${it.agentUid}"
                            dateKey !in cloudDeletedActivities && dateKey !in localActivityTombstoneKeys
                        }

                        runInTransactionWithRetry {
                            if (housesToDelete.isNotEmpty()) {
                                val tombstonesToInsert = mutableListOf<Tombstone>()
                                for (house in housesToDelete) {
                                    houseDao.deleteHouse(house)
                                    tombstonesToInsert.add(
                                        Tombstone(
                                            type = TombstoneType.HOUSE,
                                            naturalKey = house.generateNaturalKey(),
                                            agentName = house.agentName,
                                            agentUid = house.agentUid,
                                            dataDate = house.data
                                        )
                                    )
                                }
                                if (tombstonesToInsert.isNotEmpty()) {
                                    tombstoneDao.insertTombstones(tombstonesToInsert)
                                }
                            }

                            if (activitiesToDelete.isNotEmpty()) {
                                val tombstonesToInsert = mutableListOf<Tombstone>()
                                for (activity in activitiesToDelete) {
                                    dayActivityDao.deleteDayActivity(activity.date, activity.agentUid)
                                    tombstonesToInsert.add(
                                        Tombstone(
                                            type = TombstoneType.ACTIVITY,
                                            naturalKey = "${activity.date.replace("/", "-")}|${activity.agentUid}",
                                            agentName = activity.agentName,
                                            agentUid = activity.agentUid,
                                            dataDate = activity.date
                                        )
                                    )
                                }
                                if (tombstonesToInsert.isNotEmpty()) {
                                    tombstoneDao.insertTombstones(tombstonesToInsert)
                                }
                            }

                            val localIdentityMap = if (housesDelta.isNotEmpty()) allLocalHousesWithKeys.groupBy { it.identityKey } else emptyMap()

                            val housesToUpsert = housesDelta.mapNotNull { cloudWrapper ->
                                val cloudHouse = cloudWrapper.house
                                val key = cloudWrapper.naturalKey
                                var existing = localHousesByNaturalKey[key]?.house
                                
                                if (existing == null) {
                                    val identityKey = cloudWrapper.identityKey
                                    existing = localIdentityMap[identityKey]?.find { it.house.agentUid == cloudHouse.agentUid }?.house
                                    
                                    if (existing == null) {
                                        val normalizedDate = cloudHouse.data.replace("/", "-")
                                        val dateKey = "$normalizedDate|$uid"
                                        val dayActivity = localActivities[dateKey]?.firstOrNull()
                                        val cloudActivity = activitiesDelta.find { it.date.replace("/", "-") == normalizedDate }
                                        
                                        val isCloudUnlocked = cloudActivity?.isManualUnlock == true
                                        val isLocallyClosed = dayActivity?.isClosed == true && dayActivity.isManualUnlock != true
                                        
                                        if (isLocallyClosed && !isCloudUnlocked && !cloudHouse.editedByAdmin && !isTargetDifferentUser) {
                                            return@mapNotNull null
                                        }
                                    }
                                }

                                if (existing != null && !existing.isSynced) {
                                    val isAdminOverride = cloudHouse.editedByAdmin && !existing.editedByAdmin && 
                                        (System.currentTimeMillis() - existing.lastUpdated > 120000L)

                                    if (!isAdminOverride) {
                                        val threshold = AppConstants.SYNC_CONFLICT_THRESHOLD_MS
                                        if (existing.lastUpdated > (cloudHouse.lastUpdated + threshold)) {
                                            return@mapNotNull null
                                        }
                                    }
                                }

                                cloudHouse.copy(
                                    id = existing?.id ?: 0,
                                    agentName = finalAgentName,
                                    agentUid = uid,
                                    isSynced = true
                                )
                            }
                            
                            val activitiesToUpsert = activitiesDelta.mapNotNull { activity ->
                                val normalizedDate = activity.date.replace("/", "-")
                                val key = "$normalizedDate|$uid"
                                val dateKey = "$normalizedDate|$uid"
                                
                                if (dateKey in cloudDeletedActivities) {
                                    return@mapNotNull null
                                }
                                
                                val existing = localActivities[key]?.firstOrNull()

                                if (existing != null && !existing.isSynced) {
                                     val isRemoteUnlock = activity.isManualUnlock && !existing.isManualUnlock
                                     val isAdminOverride = activity.editedByAdmin && !existing.editedByAdmin && 
                                        (System.currentTimeMillis() - existing.lastUpdated > 120000L)

                                     val threshold = AppConstants.SYNC_CONFLICT_THRESHOLD_MS
                                     
                                     if (!isRemoteUnlock && !isAdminOverride && existing.lastUpdated > (activity.lastUpdated + threshold)) {
                                         return@mapNotNull null
                                     }
                                }

                                activity.copy(
                                    date = normalizedDate,
                                    agentName = finalAgentName,
                                    agentUid = uid,
                                    isSynced = true
                                )
                            }

                            houseDao.upsertHouses(housesToUpsert)
                            dayActivityDao.upsertDayActivities(activitiesToUpsert)

                            housesToUpsert.forEach { house ->
                                tombstoneDao.deleteByNaturalKey(house.generateNaturalKey(), house.agentUid)
                            }
                            activitiesToUpsert.forEach { activity ->
                                tombstoneDao.deleteByNaturalKey("${activity.date.replace("/", "-")}|${activity.agentUid}", activity.agentUid)
                            }

                            housesToUpsert.forEach { pulledHouse ->
                                val key = pulledHouse.generateNaturalKey()
                                val localMatches = allLocalHousesWithKeys.filter { it.naturalKey == key && it.house.id != pulledHouse.id && it.house.id != 0 }
                                localMatches.forEach { match ->
                                    if (match.house.isSynced) {
                                        houseDao.deleteHouse(match.house)
                                    }
                                }
                            }
                        }

                        if (!isTargetDifferentUser) {
                            val maxObservedTime = (cloudHouses.map { it.lastUpdated } + cloudDayActivities.map { it.lastUpdated }).maxOrNull() ?: 0L
                            val safetyAnchor = serverTime - 600000L 
                            settingsManager.setLastSyncTimestamp(maxOf(maxObservedTime, safetyAnchor))
                        }
                        
                        allLocalHousesWithKeys = emptyList()
                        cloudHousesWithKeys = emptyList()

                        Result.success(Unit)
                    } catch (e: Exception) {
                        android.util.Log.e("SyncPullHandler", "Pull failed", e)
                        Result.failure(e)
                    }
                }
            }
        } ?: Result.failure(Exception("O download de dados atingiu o tempo limite. Verifique sua conexão ou tente novamente."))
        return result
    }
}
