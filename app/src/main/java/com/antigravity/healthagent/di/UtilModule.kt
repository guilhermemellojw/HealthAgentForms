package com.antigravity.healthagent.di

import com.antigravity.healthagent.domain.util.Clock
import com.antigravity.healthagent.domain.util.SystemClock
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class UtilModule {

    @Binds
    @Singleton
    abstract fun bindClock(systemClock: SystemClock): Clock
}
