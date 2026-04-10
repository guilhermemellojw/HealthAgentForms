package com.antigravity.healthagent.di

import com.antigravity.healthagent.data.repository.HouseRepository
import com.antigravity.healthagent.data.repository.HouseRepositoryImpl
import dagger.Binds
import dagger.Module
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
    ): com.antigravity.healthagent.data.repository.HouseRepository

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
}
