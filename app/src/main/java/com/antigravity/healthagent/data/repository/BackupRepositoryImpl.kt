package com.antigravity.healthagent.data.repository

import com.antigravity.healthagent.data.backup.BackupData
import com.antigravity.healthagent.data.backup.BackupManager
import com.antigravity.healthagent.domain.repository.BackupMetadata
import com.antigravity.healthagent.domain.repository.BackupRepository
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext

@Singleton
class BackupRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val firestore: FirebaseFirestore,
    private val storage: FirebaseStorage,
    private val backupManager: BackupManager
) : BackupRepository {

    override suspend fun uploadTimelineBackup(uid: String, data: BackupData): Result<Unit> {
        return try {
            val timestamp = System.currentTimeMillis()
            val fileName = "${timestamp}.json"
            val storagePath = "backups/$uid/$fileName"
            val tempFile = File(context.cacheDir, "temp_backup_$timestamp.json")

            // 1. Serialize to temp file
            backupManager.exportToFile(tempFile, data)

            // 2. Upload to Firebase Storage
            val storageRef = storage.reference.child(storagePath)
            storageRef.putFile(android.net.Uri.fromFile(tempFile)).await()

            // 3. Save metadata to Firestore
            val metadata = mapOf(
                "timestamp" to timestamp,
                "storagePath" to storagePath,
                "houseCount" to data.houses.size,
                "activityCount" to data.dayActivities.size,
                "agentName" to (data.sourceAgentName ?: "Desconhecido")
            )
            
            firestore.collection("agents").document(uid)
                .collection("backups").document(timestamp.toString())
                .set(metadata).await()

            // Cleanup
            if (tempFile.exists()) tempFile.delete()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun fetchTimeline(uid: String): Result<List<BackupMetadata>> {
        return try {
            val snapshot = firestore.collection("agents").document(uid)
                .collection("backups")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .get().await()

            val list = snapshot.documents.mapNotNull { doc ->
                BackupMetadata(
                    id = doc.id,
                    timestamp = doc.getLong("timestamp") ?: 0L,
                    storagePath = doc.getString("storagePath") ?: "",
                    houseCount = doc.getLong("houseCount")?.toInt() ?: 0,
                    activityCount = doc.getLong("activityCount")?.toInt() ?: 0,
                    agentName = doc.getString("agentName") ?: ""
                )
            }
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun downloadBackup(storagePath: String): Result<BackupData> {
        return try {
            val timestamp = System.currentTimeMillis()
            val tempFile = File(context.cacheDir, "dl_backup_$timestamp.json")
            val storageRef = storage.reference.child(storagePath)
            
            storageRef.getFile(tempFile).await()
            
            val backupData = backupManager.importData(context, android.net.Uri.fromFile(tempFile))
            
            if (tempFile.exists()) tempFile.delete()
            
            Result.success(backupData)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
