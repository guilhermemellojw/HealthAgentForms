package com.antigravity.healthagent.data.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.antigravity.healthagent.data.repository.HouseRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.google.gson.GsonBuilder
import kotlinx.coroutines.flow.first

@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: com.antigravity.healthagent.data.repository.HouseRepository,
    private val settingsManager: com.antigravity.healthagent.data.settings.SettingsManager
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            Log.d("BackupWorker", "Starting auto-backup...")
            val currentUser = settingsManager.cachedUser.first()
            val currentName = currentUser?.agentName ?: "UNKNOWN"
            val currentUid = currentUser?.uid ?: ""

            // CLEAN BACKUP: Only include records belonging to the current user.
            // Inspected data from others is transient and already in the cloud.
            val houses = repository.getAllHousesSnapshot()
                .filter { it.agentUid == currentUid }
            val activities = repository.getAllDayActivitiesSnapshot()
                .filter { it.agentUid == currentUid }
            
            val backupData = BackupData(
                houses = houses, 
                dayActivities = activities,
                sourceAgentUid = currentUid,
                sourceAgentName = currentName
            )

            // Create filename with timestamp and agent name
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            val safeName = currentName.replace(" ", "_").uppercase()
            val filename = "backup_${safeName}_$timestamp.json"

            // Get Documents/HealthAgentBackups directory
            // We'll use app-specific storage for reliability without permissions first, 
            // or MediaStore for public access? 
            // Requests imply "auto backup to drive" -> implies a folder user can see/sync.
            // Using ExternalFilesDir is easiest and safest. User can find it in Android/data/.../files/Backups
            // Or we try to write to public Documents if possible (needs permissions on Android 10+? No, SAF or MediaStore).
            // Let's stick to app-specific external storage for now to avoid permission complexities in background.
            // Path: /Android/data/com.antigravity.healthagent/files/Backups/
            
            val backupDir = File(applicationContext.getExternalFilesDir(null), "Backups")
            if (!backupDir.exists()) {
                backupDir.mkdirs()
            }

            val file = File(backupDir, filename)
            
            BackupManager().exportToFile(file, backupData)
            
            Log.d("BackupWorker", "Backup saved to ${file.absolutePath}")

            // Optional: Prune old backups (keep last 5)
            val files = backupDir.listFiles()?.sortedByDescending { it.lastModified() }
            if (files != null && files.size > 5) {
                files.drop(5).forEach { 
                    it.delete() 
                    Log.d("BackupWorker", "Pruned old backup: ${it.name}")
                }
            }

            Result.success()
        } catch (e: Exception) {
            Log.e("BackupWorker", "Backup failed", e)
            Result.failure()
        }
    }
}
