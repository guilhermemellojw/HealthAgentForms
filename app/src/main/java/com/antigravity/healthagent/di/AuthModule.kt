package com.antigravity.healthagent.di

import com.antigravity.healthagent.BuildConfig
import com.antigravity.healthagent.data.repository.AuthRepositoryImpl
import com.antigravity.healthagent.data.repository.SupabaseAuthRepositoryImpl
import com.antigravity.healthagent.data.repository.SyncRepositoryImpl
import com.antigravity.healthagent.domain.repository.AuthRepository
import com.antigravity.healthagent.domain.repository.SyncRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.ktx.storage
import com.google.firebase.ktx.Firebase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {

    @Binds
    @Singleton
    abstract fun bindAccessControlRepository(
        accessControlRepositoryImpl: com.antigravity.healthagent.data.repository.AccessControlRepositoryImpl
    ): com.antigravity.healthagent.domain.repository.AccessControlRepository

    @Binds
    @Singleton
    abstract fun bindSyncRepository(
        syncRepositoryImpl: SyncRepositoryImpl
    ): SyncRepository

    companion object {
        @Provides
        @Singleton
        fun provideAuthRepository(
            firebase: AuthRepositoryImpl,
            supabase: dagger.Lazy<SupabaseAuthRepositoryImpl>
        ): AuthRepository {
            // Switch Fase 2: BuildConfig.USE_SUPABASE_AUTH (local.properties, padrão false).
            // Lazy: o client Supabase só é criado quando selecionado (Firebase segue
            // bootando em máquinas sem as chaves configuradas).
            return if (BuildConfig.USE_SUPABASE_AUTH) supabase.get() else firebase
        }

        @Provides
        @Singleton
        fun provideSupabaseClient(): SupabaseClient {
            val url = BuildConfig.SUPABASE_URL.trim()
            val key = BuildConfig.SUPABASE_KEY.trim()
            require(url.startsWith("https://") && key.isNotBlank()) {
                "Supabase não configurado: defina SUPABASE_URL e SUPABASE_PUBLISHABLE_KEY em local.properties"
            }
            return createSupabaseClient(url, key) {
                install(Auth)
                install(Postgrest)
            }
        }

        @Provides
        @Singleton
        fun provideFirebaseAuth(): FirebaseAuth {
            return Firebase.auth
        }

        @Provides
        @Singleton
        fun provideFirebaseFirestore(): FirebaseFirestore {
            return Firebase.firestore
        }

        @Provides
        @Singleton
        fun provideFirebaseStorage(): FirebaseStorage {
            return Firebase.storage
        }

        @Provides
        @Singleton
        fun provideFusedLocationClient(@ApplicationContext context: android.content.Context): FusedLocationProviderClient {
            return LocationServices.getFusedLocationProviderClient(context)
        }
    }
}
