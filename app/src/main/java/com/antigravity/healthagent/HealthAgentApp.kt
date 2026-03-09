package com.antigravity.healthagent

import android.app.Application
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
        // WorkManager is initialized on-demand.
    }
}
