package com.antigravity.healthagent.data.repository

import android.content.Context
import com.antigravity.healthagent.domain.logger.AppLogger
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.domain.repository.HouseRepository
import com.antigravity.healthagent.data.settings.SettingsManager
import com.antigravity.healthagent.data.util.toDayActivitySafe
import com.antigravity.healthagent.data.util.toHouseSafe
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
import com.antigravity.healthagent.data.sync.VersionChecker
import com.antigravity.healthagent.data.sync.IdentityDiscoveryService
import com.antigravity.healthagent.data.sync.TeamworkSyncHandler
import com.antigravity.healthagent.data.sync.SyncReconciler
import com.antigravity.healthagent.domain.repository.SyncRepository

@Singleton
class SyncPullHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val houseRepository: HouseRepository,
    private val settingsManager: SettingsManager,
    private val versionChecker: VersionChecker,
    private val identityDiscoveryService: IdentityDiscoveryService,
    private val teamworkSyncHandler: TeamworkSyncHandler,
    private val syncReconciler: SyncReconciler
) {

    suspend fun pullCloudDataToLocal(targetUid: String?, force: Boolean, syncMutex: Mutex): Result<SyncRepository.SyncResult> {
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
                        val sysSettings = versionChecker.fetchSystemSettings().getOrDefault(emptyMap())
                        versionChecker.checkVersion(context, sysSettings).onFailure {
                            return@withContext Result.failure(it)
                        }
                        
                        // --- REMOTE WIPE CHECK (Multi-device Safety) ---
                        val hasSyncHistory = settingsManager.lastSyncTimestamp.first() > 0
                        val localUnsyncedCount = houseRepository.getUnsyncedHouses(uid).size + houseRepository.getUnsyncedActivities(uid).size
                        
                        val requireReset = versionChecker.isWipeRequired(
                            userDoc = userDoc,
                            agentDocSnapshot = agentDocSnapshot,
                            hasSyncHistory = hasSyncHistory,
                            isTargetDifferentUser = isTargetDifferentUser,
                            localUnsyncedCount = localUnsyncedCount
                        )

                        val localCount = houseRepository.countHouses()

                        if (requireReset) {
                            AppLogger.w("SyncPullHandler", "Remote Wipe Triggered for UID: $uid")
                            val wipeResult = try {
                                houseRepository.clearAgentData(uid)
                                if (!isTargetDifferentUser) {
                                    settingsManager.setLastSyncTimestamp(0L)
                                }
                                Result.success(Unit)
                            } catch (e: Exception) {
                                Result.failure(e)
                            }
                            if (wipeResult.isSuccess) {
                                val requireResetFromUser = userDoc.getBoolean("requireDataReset") ?: false
                                val requireResetFromAgent = agentDocSnapshot.getBoolean("requireDataReset") ?: false
                                if (requireResetFromUser) firestore.collection("users").document(uid).update("requireDataReset", false)
                                if (requireResetFromAgent) firestore.collection("agents").document(uid).update("requireDataReset", false)
                            } else {
                                AppLogger.e("SyncPullHandler", "Remote Wipe Failed: ${wipeResult.exceptionOrNull()?.message}")
                                return@withContext Result.failure(wipeResult.exceptionOrNull() ?: Exception("Falha ao realizar wipe local"))
                            }
                        }

                        val cachedLastSync = if (isTargetDifferentUser || force || requireReset || localCount == 0) 0L else settingsManager.lastSyncTimestamp.first()
                        val now = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis()
                        val lastSync = if (cachedLastSync > now + 3600000L) 0L else cachedLastSync
                        val serverTime = now

                        // ALWAYS perform discovery to robustly heal identity and retrieve legacy cloud data
                        val possibleAgentDocs = identityDiscoveryService.discoverAndHealAgentDocs(
                            uid = uid,
                            discoveryEmails = discoveryEmails,
                            profileAgentName = profileAgentName
                        )

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
                                        AppLogger.w("SyncPullHandler", "Clock Skew: Device is ${if(skew < 0) "AHEAD" else "BEHIND"} by ${kotlin.math.abs(skew)} ms.")
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
                        val teammateHouses = teamworkSyncHandler.performTeamworkSync(
                            uid = uid,
                            cloudHouses = cloudHouses,
                            isTargetDifferentUser = isTargetDifferentUser
                        )

                        // --- PHASE 3: RECONCILIATION ---
                        syncReconciler.reconcile(
                            uid = uid,
                            finalAgentName = finalAgentName,
                            isTargetDifferentUser = isTargetDifferentUser,
                            cloudHouses = cloudHouses,
                            cloudDayActivities = cloudDayActivities,
                            cloudDeletedHouses = cloudDeletedHouses,
                            cloudDeletedActivities = cloudDeletedActivities,
                            teammateHouses = teammateHouses
                        ).onFailure {
                            return@withContext Result.failure(it)
                        }

                        if (!isTargetDifferentUser) {
                            val maxObservedTime = (cloudHouses.map { it.lastUpdated } + cloudDayActivities.map { it.lastUpdated }).maxOrNull() ?: 0L
                            val safetyAnchor = serverTime - 600000L 
                            val finalSyncTime = maxOf(maxObservedTime, safetyAnchor)
                            settingsManager.setLastSyncTimestamp(finalSyncTime)
                            
                            val currentDeviceTime = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis()
                            val skew = maxObservedTime - currentDeviceTime
                            settingsManager.setClockSkewMs(skew)
                            
                            Result.success(SyncRepository.SyncResult(
                                cloudMaxTime = finalSyncTime,
                                clockSkewMs = skew
                            ))
                        } else {
                            Result.success(SyncRepository.SyncResult())
                        }
                    } catch (e: Exception) {
                        AppLogger.e("SyncPullHandler", "Pull failed", e)
                        Result.failure(e)
                    }
                }
            }
        } ?: Result.failure(Exception("O download de dados atingiu o tempo limite. Verifique sua conexão ou tente novamente."))
        return result
    }
}
