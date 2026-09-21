package com.antigravity.healthagent.di

import com.antigravity.healthagent.BuildConfig
import com.antigravity.healthagent.domain.repository.HouseRepository
import com.antigravity.healthagent.domain.repository.HouseReadRepository
import com.antigravity.healthagent.domain.repository.HouseWriteRepository
import com.antigravity.healthagent.data.repository.HouseRepositoryImpl
import com.antigravity.healthagent.data.repository.DayTransferRepositoryImpl
import com.antigravity.healthagent.data.repository.SupabaseDayTransferRepository
import com.antigravity.healthagent.domain.repository.DayTransferRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindHouseRepository(
        houseRepositoryImpl: HouseRepositoryImpl
    ): HouseRepository

    @Binds
    @Singleton
    abstract fun bindHouseReadRepository(
        houseRepositoryImpl: HouseRepositoryImpl
    ): HouseReadRepository

    @Binds
    @Singleton
    abstract fun bindHouseWriteRepository(
        houseRepositoryImpl: HouseRepositoryImpl
    ): HouseWriteRepository

    @Binds
    @Singleton
    abstract fun bindAgentRepository(
        agentRepositoryImpl: com.antigravity.healthagent.data.repository.AgentRepositoryImpl
    ): com.antigravity.healthagent.domain.repository.AgentRepository

    @Binds
    @Singleton
    abstract fun bindLocalizationRepository(
        localizationRepositoryImpl: com.antigravity.healthagent.data.repository.LocalizationRepositoryImpl
    ): com.antigravity.healthagent.domain.repository.LocalizationRepository

    @Binds
    @Singleton
    abstract fun bindMapRepository(
        mapRepositoryImpl: com.antigravity.healthagent.data.repository.MapRepositoryImpl
    ): com.antigravity.healthagent.domain.repository.MapRepository

    @Binds
    @Singleton
    abstract fun bindAppLogger(
        androidLoggerImpl: com.antigravity.healthagent.data.util.AndroidLoggerImpl
    ): com.antigravity.healthagent.domain.logger.AppLogger

    @Binds
    @Singleton
    abstract fun bindStreetRepository(
        streetRepositoryImpl: com.antigravity.healthagent.data.repository.StreetRepository
    ): com.antigravity.healthagent.domain.repository.StreetRepository

    companion object {
        @Provides
        @Singleton
        fun provideBackupRepository(
            firebase: com.antigravity.healthagent.data.repository.BackupRepositoryImpl,
            supabase: dagger.Lazy<com.antigravity.healthagent.data.repository.SupabaseBackupRepository>
        ): com.antigravity.healthagent.domain.repository.BackupRepository {
            // Switch Fase 2.6 (local.properties, padrão false).
            return if (BuildConfig.USE_SUPABASE_SYNC) supabase.get() else firebase
        }

        @Provides
        @Singleton
        fun provideDayTransferRepository(
            firebase: DayTransferRepositoryImpl,
            supabase: dagger.Lazy<SupabaseDayTransferRepository>
        ): DayTransferRepository {
            // Switch Fase 2.5 (local.properties, padrão false).
            return if (BuildConfig.USE_SUPABASE_SYNC) supabase.get() else firebase
        }
    }
}
