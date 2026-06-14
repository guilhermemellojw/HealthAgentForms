package com.antigravity.healthagent.di

import com.antigravity.healthagent.data.remote.AgentRemoteDataSource
import com.antigravity.healthagent.data.remote.AgentRemoteDataSourceImpl
import com.antigravity.healthagent.data.remote.HouseRemoteDataSource
import com.antigravity.healthagent.data.remote.HouseRemoteDataSourceImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RemoteDataSourceModule {

    @Binds
    @Singleton
    abstract fun bindAgentRemoteDataSource(
        agentRemoteDataSourceImpl: AgentRemoteDataSourceImpl
    ): AgentRemoteDataSource

    @Binds
    @Singleton
    abstract fun bindHouseRemoteDataSource(
        houseRemoteDataSourceImpl: HouseRemoteDataSourceImpl
    ): HouseRemoteDataSource
}
