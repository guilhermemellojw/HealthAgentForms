plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("com.google.gms.google-services")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

import java.util.Properties
import java.io.FileInputStream

android {
    namespace = "com.antigravity.healthagent"
    // 36 exigido pelas transitivas do supabase-kt (androidx.browser 1.10, compose 1.9);
    // targetSdk/minSdk intocados = sem mudança de comportamento em runtime.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.antigravity.healthagent"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        multiDexEnabled = true
        
        val properties = Properties()
        val localPropertiesFile = project.rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            properties.load(FileInputStream(localPropertiesFile))
        }
        val mapsApiKey = properties.getProperty("MAPS_API_KEY") ?: ""
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey

        // Supabase (shadow). Chaves via local.properties (gitignored); sem fallback
        // hardcoded — BuildConfig vazio falha explícito no provider. USE_SUPABASE_AUTH
        // seleciona a implementação Supabase no Hilt (padrão: Firebase).
        val supabaseUrl = properties.getProperty("SUPABASE_URL") ?: ""
        val supabaseKey = properties.getProperty("SUPABASE_PUBLISHABLE_KEY") ?: ""
        val useSupabaseAuth = properties.getProperty("USE_SUPABASE_AUTH")?.toBoolean() ?: false
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_KEY", "\"$supabaseKey\"")
        buildConfigField("boolean", "USE_SUPABASE_AUTH", "$useSupabaseAuth")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
        }
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.1")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    
    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.5")

    // Room
    val roomVersion = "2.7.0-alpha13"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.60.1")
    ksp("com.google.dagger:hilt-android-compiler:2.60.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    
    // JSON
    implementation("com.google.code.gson:gson:2.10.1")

    // Google Maps
    implementation("com.google.maps.android:maps-compose:4.4.1")
    implementation("com.google.maps.android:maps-compose-utils:4.4.1")
    implementation("com.google.maps.android:maps-compose-widgets:4.4.1")
    implementation("com.google.android.gms:play-services-maps:18.2.0")
    implementation("com.google.maps.android:android-maps-utils:3.8.2")
    implementation("com.google.android.gms:play-services-location:21.0.1")

    // PDF Generation


    // Loading Images
    implementation("io.coil-kt:coil-compose:2.5.0")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-storage")

    // Supabase (Fase 2 — auth + postgrest; realtime/storage nas próximas fatias)
    implementation("io.github.jan-tennert.supabase:auth-kt:3.8.0")
    implementation("io.github.jan-tennert.supabase:postgrest-kt:3.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    
    // Auth (Google Sign-In via Credential Manager)
    implementation("androidx.credentials:credentials:1.2.1")
    implementation("androidx.credentials:credentials-play-services-auth:1.2.1")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.robolectric:robolectric:4.12.1")
    testImplementation("androidx.room:room-testing:$roomVersion")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.test:runner:1.6.1")
    testImplementation("androidx.test:rules:1.6.1")
    testImplementation("androidx.test.ext:junit-ktx:1.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")


    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.hilt:hilt-work:1.1.0")
    ksp("androidx.hilt:hilt-compiler:1.1.0")
    
    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Splash Screen
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("com.google.android.material:material:1.11.0")

    // Security & Cryptography
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
    // Custom task for data audit (local export + comparison)
    val exportLocalDb = tasks.register<Exec>("exportLocalDb") {
        commandLine("bash", "./scripts/export_local_db.sh")
    }

    val compareDatasets = tasks.register<Exec>("compareDatasets") {
        commandLine("python3", "../scripts/compare_datasets.py", "guigomelo9@gmail.com")
    }

    tasks.register("auditData") {
        dependsOn(exportLocalDb, compareDatasets)
        group = "verification"
        description = "Exports local DB, runs comparison for agent guigomelo9@gmail.com. Run FirestoreExport in app first to generate cloud CSVs."
    }
