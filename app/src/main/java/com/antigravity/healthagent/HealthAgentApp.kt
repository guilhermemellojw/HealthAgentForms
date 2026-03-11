package com.antigravity.healthagent

import android.app.Application
import com.google.firebase.FirebaseApp
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.EntryPoint

@HiltAndroidApp
class HealthAgentApp : Application(), Configuration.Provider {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface HiltWorkerFactoryEntryPoint {
        fun workerFactory(): HiltWorkerFactory
    }

    override val workManagerConfiguration: Configuration
        get() {
            return try {
                val workerFactory = EntryPointAccessors.fromApplication(this, HiltWorkerFactoryEntryPoint::class.java).workerFactory()
                Configuration.Builder()
                    .setWorkerFactory(workerFactory)
                    .setMinimumLoggingLevel(android.util.Log.DEBUG)
                    .build()
            } catch (e: Exception) {
                android.util.Log.e("HealthAgentApp", "Error initializing WorkManager Configuration found", e)
                Configuration.Builder()
                    .setMinimumLoggingLevel(android.util.Log.DEBUG)
                    .build()
            }
        }

    override fun onCreate() {
        super.onCreate()
        com.antigravity.healthagent.context.AppContextHolder.setContext(this)
        FirebaseApp.initializeApp(this)
        com.google.firebase.firestore.FirebaseFirestore.setLoggingEnabled(true)
        
        // Schedule periodic sync
        schedulePeriodicSync()
    }

    private fun schedulePeriodicSync() {
        val constraints = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
            .build()

        val syncRequest = androidx.work.PeriodicWorkRequestBuilder<com.antigravity.healthagent.data.sync.SyncWorker>(
            1, java.util.concurrent.TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                androidx.work.WorkRequest.MIN_BACKOFF_MILLIS,
                java.util.concurrent.TimeUnit.MILLISECONDS
            )
            .build()

        androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "PeriodicDataSync",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
    }
}
