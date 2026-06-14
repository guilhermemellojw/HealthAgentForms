# -- General Android/App Rules --
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,Deprecated,*Annotation*,Synthetic

# -- Dagger Hilt Rules --
-keep class *__* { *; }
-keep class dagger.hilt.internal.KeepFieldType { *; }
-keep class * extends dagger.hilt.internal.GeneratedComponent { *; }
-keep class * implements dagger.hilt.internal.GeneratedComponent { *; }
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }

# Keep Dagger components & modules
-keep class **_HiltModules* { *; }
-keep class * extends dagger.internal.DoubleCheck { *; }
-dontwarn dagger.hilt.internal.**

# -- Room Database Rules --
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class * implements androidx.room.RoomDatabase { *; }
-keep class * implements androidx.room.Entity { *; }
-keep class * extends androidx.room.Entity { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep class * implements androidx.room.Dao { *; }
-keep class * extends androidx.room.Dao { *; }
-keep class * extends androidx.room.TypeConverter { *; }
-keep class * implements androidx.room.TypeConverter { *; }
-keep @androidx.room.TypeConverters class * { *; }

# -- Gson Rules --
-dontwarn sun.misc.Unsafe
-keep class com.google.gson.** { *; }
-keep class com.google.gson.stream.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# -- WorkManager Rules --
-keep class * extends androidx.work.ListenableWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.Worker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}

# -- Firebase Rules --
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# -- App Models and Repository DTOs (to prevent Gson/Firestore Reflection/Serialization failures) --
-keep class com.antigravity.healthagent.data.local.model.** { *; }
-keep class com.antigravity.healthagent.domain.model.** { *; }
-keep class com.antigravity.healthagent.domain.repository.AuthUser { *; }
-keep class com.antigravity.healthagent.domain.repository.AccessRequest { *; }
-keep class com.antigravity.healthagent.data.backup.BackupData { *; }

# -- AndroidX Security / Crypto --
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-keep class androidx.security.crypto.** { *; }

# -- Compose Rules --
-keepclassmembers class * extends androidx.compose.ui.node.Owner { *; }
-keep class androidx.compose.ui.platform.** { *; }
