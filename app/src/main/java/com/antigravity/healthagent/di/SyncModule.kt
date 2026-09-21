package com.antigravity.healthagent.di

import com.antigravity.healthagent.data.sync.FirestoreSystemSettingsSource
import com.antigravity.healthagent.data.sync.SystemSettingsSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {

    @Binds
    @Singleton
    abstract fun bindSystemSettingsSource(
        impl: FirestoreSystemSettingsSource
    ): SystemSettingsSource
}
