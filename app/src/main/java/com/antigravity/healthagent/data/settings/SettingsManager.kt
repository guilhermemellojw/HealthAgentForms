package com.antigravity.healthagent.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.emptyPreferences
import com.antigravity.healthagent.data.backup.BackupFrequency
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
import com.antigravity.healthagent.domain.repository.AuthUser
import com.antigravity.healthagent.domain.repository.UserRole
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val BACKUP_FREQUENCY_KEY = stringPreferencesKey("backup_frequency")
    private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
    private val THEME_COLOR_KEY = stringPreferencesKey("theme_color")
    private val CUSTOM_ACTIVITIES_KEY = androidx.datastore.preferences.core.stringSetPreferencesKey("custom_activities")
    private val MAX_OPEN_HOUSES_KEY = androidx.datastore.preferences.core.intPreferencesKey("max_open_houses")
    private val POP_SOUND_KEY = stringPreferencesKey("pop_sound")
    private val SUCCESS_SOUND_KEY = stringPreferencesKey("success_sound")
    private val CELEBRATION_SOUND_KEY = stringPreferencesKey("celebration_sound")
    private val WARNING_SOUND_KEY = stringPreferencesKey("warning_sound")
    private val EASY_MODE_KEY = androidx.datastore.preferences.core.booleanPreferencesKey("easy_mode")
    private val SOLAR_MODE_KEY = androidx.datastore.preferences.core.booleanPreferencesKey("solar_mode")
    private val REMOTE_AGENT_UID_KEY = stringPreferencesKey("remote_agent_uid")
    private val LAST_SYNC_TIMESTAMP_KEY = androidx.datastore.preferences.core.longPreferencesKey("last_sync_timestamp")
    
    // User Profile Cache for Offline Support
    private val CACHED_USER_UID_KEY = stringPreferencesKey("cached_user_uid")
    private val CACHED_USER_EMAIL_KEY = stringPreferencesKey("cached_user_email")
    private val CACHED_USER_DISPLAY_NAME_KEY = stringPreferencesKey("cached_user_display_name")
    private val CACHED_USER_ROLE_KEY = stringPreferencesKey("cached_user_role")
    private val CACHED_USER_IS_AUTHORIZED_KEY = androidx.datastore.preferences.core.booleanPreferencesKey("cached_user_is_authorized")
    private val CACHED_USER_AGENT_NAME_KEY = stringPreferencesKey("cached_user_agent_name")
    


    val popSound: Flow<String> = context.dataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { preferences ->
            preferences[POP_SOUND_KEY] ?: "SYSTEM_NOTIFICATION_1"
        }

    val successSound: Flow<String> = context.dataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { preferences ->
            preferences[SUCCESS_SOUND_KEY] ?: "SYSTEM_NOTIFICATION_1"
        }

    val celebrationSound: Flow<String> = context.dataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { preferences ->
            preferences[CELEBRATION_SOUND_KEY] ?: "SYSTEM_ALARM"
        }

    val warningSound: Flow<String> = context.dataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { preferences ->
            preferences[WARNING_SOUND_KEY] ?: "SYSTEM_NOTIFICATION_2"
        }

    val maxOpenHouses: Flow<Int> = context.dataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { preferences ->
            preferences[MAX_OPEN_HOUSES_KEY] ?: 25
        }

    val customActivities: Flow<Set<String>> = context.dataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { preferences ->
            preferences[CUSTOM_ACTIVITIES_KEY] ?: emptySet()
        }

    val backupFrequency: Flow<BackupFrequency> = context.dataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { preferences ->
            val frequencyName = preferences[BACKUP_FREQUENCY_KEY] ?: BackupFrequency.DAILY.name
            try {
                BackupFrequency.valueOf(frequencyName)
            } catch (e: IllegalArgumentException) {
                BackupFrequency.DAILY
            }
        }

    val easyMode: Flow<Boolean> = context.dataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { preferences ->
            preferences[EASY_MODE_KEY] ?: false
        }

    val solarMode: Flow<Boolean> = context.dataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { preferences ->
            preferences[SOLAR_MODE_KEY] ?: false
        }

    val themeMode: Flow<String> = context.dataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { preferences ->
            preferences[THEME_MODE_KEY] ?: "SYSTEM"
        }

    val themeColor: Flow<String> = context.dataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { preferences ->
            preferences[THEME_COLOR_KEY] ?: "EMERALD"
        }



    suspend fun setBackupFrequency(frequency: BackupFrequency) {
        context.dataStore.edit { preferences ->
            preferences[BACKUP_FREQUENCY_KEY] = frequency.name
        }
    }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { preferences ->
            preferences[THEME_MODE_KEY] = mode
        }
    }

    suspend fun setThemeColor(color: String) {
        context.dataStore.edit { preferences ->
            preferences[THEME_COLOR_KEY] = color
        }
    }

    suspend fun addCustomActivity(activity: String) {
        context.dataStore.edit { preferences ->
            val currentSet = preferences[CUSTOM_ACTIVITIES_KEY] ?: emptySet()
            preferences[CUSTOM_ACTIVITIES_KEY] = currentSet + activity
        }
    }

    suspend fun removeCustomActivity(activity: String) {
        context.dataStore.edit { preferences ->
            val currentSet = preferences[CUSTOM_ACTIVITIES_KEY] ?: emptySet()
            preferences[CUSTOM_ACTIVITIES_KEY] = currentSet - activity
        }
    }

    suspend fun setMaxOpenHouses(max: Int) {
        context.dataStore.edit { preferences ->
            preferences[MAX_OPEN_HOUSES_KEY] = max
        }
    }

    suspend fun setPopSound(soundId: String) {
        context.dataStore.edit { preferences ->
            preferences[POP_SOUND_KEY] = soundId
        }
    }

    suspend fun setSuccessSound(soundId: String) {
        context.dataStore.edit { preferences ->
            preferences[SUCCESS_SOUND_KEY] = soundId
        }
    }

    suspend fun setCelebrationSound(soundId: String) {
        context.dataStore.edit { preferences ->
            preferences[CELEBRATION_SOUND_KEY] = soundId
        }
    }

    suspend fun setWarningSound(soundId: String) {
        context.dataStore.edit { preferences ->
            preferences[WARNING_SOUND_KEY] = soundId
        }
    }

    suspend fun setEasyMode(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[EASY_MODE_KEY] = enabled
        }
    }

    suspend fun setSolarMode(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SOLAR_MODE_KEY] = enabled
        }
    }



    private val IS_APP_MODE_SELECTED_KEY = androidx.datastore.preferences.core.booleanPreferencesKey("is_app_mode_selected_v3")

    val isAppModeSelected: Flow<Boolean> = context.dataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { preferences ->
            preferences[IS_APP_MODE_SELECTED_KEY] ?: false
        }

    suspend fun setAppModeSelected(selected: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[IS_APP_MODE_SELECTED_KEY] = selected
        }
    }

    val remoteAgentUid: Flow<String?> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { preferences ->
            preferences[REMOTE_AGENT_UID_KEY]
        }

    suspend fun setRemoteAgentUid(uid: String?) {
        context.dataStore.edit { preferences ->
            if (uid == null) {
                preferences.remove(REMOTE_AGENT_UID_KEY)
            } else {
                preferences[REMOTE_AGENT_UID_KEY] = uid
            }
        }
    }

    val lastSyncTimestamp: Flow<Long> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { preferences ->
            preferences[LAST_SYNC_TIMESTAMP_KEY] ?: 0L
        }

    suspend fun setLastSyncTimestamp(timestamp: Long) {
        context.dataStore.edit { preferences ->
            preferences[LAST_SYNC_TIMESTAMP_KEY] = timestamp
        }
    }

    val cachedUser: Flow<AuthUser?> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { preferences ->
            val uid = preferences[CACHED_USER_UID_KEY] ?: return@map null
            AuthUser(
                uid = uid,
                email = preferences[CACHED_USER_EMAIL_KEY],
                displayName = preferences[CACHED_USER_DISPLAY_NAME_KEY],
                photoUrl = null, // Photo not cached for now
                role = try { 
                    UserRole.valueOf(preferences[CACHED_USER_ROLE_KEY] ?: "AGENT") 
                } catch(e: Exception) { UserRole.AGENT },
                isAuthorized = preferences[CACHED_USER_IS_AUTHORIZED_KEY] ?: false,
                agentName = preferences[CACHED_USER_AGENT_NAME_KEY]
            )
        }

    suspend fun saveUserProfile(user: AuthUser) {
        context.dataStore.edit { preferences ->
            preferences[CACHED_USER_UID_KEY] = user.uid
            preferences[CACHED_USER_EMAIL_KEY] = user.email ?: ""
            preferences[CACHED_USER_DISPLAY_NAME_KEY] = user.displayName ?: ""
            preferences[CACHED_USER_ROLE_KEY] = user.role.name
            preferences[CACHED_USER_IS_AUTHORIZED_KEY] = user.isAuthorized
            preferences[CACHED_USER_AGENT_NAME_KEY] = user.agentName ?: ""
        }
    }

    suspend fun clearSessionSettings() {
        context.dataStore.edit { preferences ->
            preferences.remove(REMOTE_AGENT_UID_KEY)
            preferences.remove(LAST_SYNC_TIMESTAMP_KEY)
            
            // Clear cached user on logout to ensure safety
            preferences.remove(CACHED_USER_UID_KEY)
            preferences.remove(CACHED_USER_EMAIL_KEY)
            preferences.remove(CACHED_USER_DISPLAY_NAME_KEY)
            preferences.remove(CACHED_USER_ROLE_KEY)
            preferences.remove(CACHED_USER_IS_AUTHORIZED_KEY)
            preferences.remove(CACHED_USER_AGENT_NAME_KEY)

            // Reset other temporary session state if any
            preferences[IS_APP_MODE_SELECTED_KEY] = false 
        }
    }
}
