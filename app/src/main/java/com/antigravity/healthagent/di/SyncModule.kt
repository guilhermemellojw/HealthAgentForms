package com.antigravity.healthagent.di

import com.antigravity.healthagent.BuildConfig
import com.antigravity.healthagent.data.repository.SupabaseSyncPullHandler
import com.antigravity.healthagent.data.repository.SyncPullHandler
import com.antigravity.healthagent.data.sync.FirestoreSystemSettingsSource
import com.antigravity.healthagent.data.sync.PullHandler
import com.antigravity.healthagent.data.sync.SystemSettingsSource
import dagger.Binds
import dagger.Module
import dagger.Provides
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

    companion object {
        @Provides
        @Singleton
        fun providePullHandler(
            firebase: SyncPullHandler,
            supabase: dagger.Lazy<SupabaseSyncPullHandler>
        ): PullHandler {
            // Switch Fase 2.3 (local.properties, padrão false). Lazy preserva o
            // path Firebase em máquinas sem client Supabase.
            return if (BuildConfig.USE_SUPABASE_SYNC) supabase.get() else firebase
        }
    }
}
