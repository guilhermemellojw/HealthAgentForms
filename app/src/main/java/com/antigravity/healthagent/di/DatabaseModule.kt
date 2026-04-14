package com.antigravity.healthagent.di

import android.content.Context
import androidx.room.Room
import com.antigravity.healthagent.data.local.AppDatabase
import com.antigravity.healthagent.data.local.dao.HouseDao
import com.antigravity.healthagent.data.local.dao.DayActivityDao
import com.antigravity.healthagent.data.local.dao.TombstoneDao
import com.antigravity.healthagent.data.local.dao.CustomStreetDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "health_agent_db"
        )
        .addMigrations(
            AppDatabase.MIGRATION_12_13, 
            AppDatabase.MIGRATION_13_14,
            AppDatabase.MIGRATION_14_15,
            AppDatabase.MIGRATION_15_16,
            AppDatabase.MIGRATION_16_17,
            AppDatabase.MIGRATION_17_18,
            AppDatabase.MIGRATION_18_19,
            AppDatabase.MIGRATION_19_20,
            AppDatabase.MIGRATION_20_21,
            AppDatabase.MIGRATION_21_22,
            AppDatabase.MIGRATION_20_22,
            AppDatabase.MIGRATION_22_23,
            AppDatabase.MIGRATION_21_23,
            AppDatabase.MIGRATION_20_23,
            AppDatabase.MIGRATION_22_24,
            AppDatabase.MIGRATION_23_24,
            AppDatabase.MIGRATION_23_25,
            AppDatabase.MIGRATION_24_25,
            AppDatabase.MIGRATION_25_26,
            AppDatabase.MIGRATION_26_27,
            AppDatabase.MIGRATION_27_28,
            AppDatabase.MIGRATION_28_29,
            AppDatabase.MIGRATION_29_30,
            AppDatabase.MIGRATION_30_31
        )
        .fallbackToDestructiveMigration()
        .build()
    }

    @Provides
    fun provideHouseDao(database: AppDatabase): HouseDao {
        return database.houseDao()
    }

    @Provides
    fun provideDayActivityDao(database: AppDatabase): DayActivityDao {
        return database.dayActivityDao()
    }

    @Provides
    fun provideCustomStreetDao(database: AppDatabase): CustomStreetDao {
        return database.customStreetDao()
    }

    @Provides
    fun provideTombstoneDao(database: AppDatabase): TombstoneDao {
        return database.tombstoneDao()
    }

    @Provides
    fun provideAgentCacheDao(database: AppDatabase): com.antigravity.healthagent.data.local.dao.AgentCacheDao {
        return database.agentCacheDao()
    }
}
