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
    ): HouseRepository
}
