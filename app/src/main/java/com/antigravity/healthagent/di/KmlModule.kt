package com.antigravity.healthagent.di

import com.antigravity.healthagent.ui.quarteiroes.KmlManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object KmlModule {

    @Provides
    @Singleton
    fun provideKmlManager(): KmlManager {
        return KmlManager()
    }
}
