package com.antigravity.healthagent.di

import android.content.Context
import androidx.room.Room
import com.antigravity.healthagent.data.local.AppDatabase
import com.antigravity.healthagent.data.local.dao.HouseDao
import com.antigravity.healthagent.data.local.dao.DayActivityDao
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
        .addMigrations(AppDatabase.MIGRATION_12_13, AppDatabase.MIGRATION_13_14)
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
}
