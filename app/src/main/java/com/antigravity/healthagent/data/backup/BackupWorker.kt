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

@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: HouseRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            Log.d("BackupWorker", "Starting auto-backup...")
            val houses = repository.getAllHousesSnapshot()
            val activities = repository.getAllDayActivitiesSnapshot()
            val backupData = BackupData(houses, activities)

            // Create filename with timestamp
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            val filename = "backup_auto_$timestamp.json"

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
