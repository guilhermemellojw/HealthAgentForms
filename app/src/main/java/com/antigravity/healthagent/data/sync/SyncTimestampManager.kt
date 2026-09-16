package com.antigravity.healthagent.data.sync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.antigravity.healthagent.data.settings.SettingsManager
import com.antigravity.healthagent.utils.TimeManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncTimestampManager @Inject constructor(
    private val settingsManager: SettingsManager
) {

    data class State(
        val lastSyncTime: Long? = null,
        val clockSkewMs: Long = 0L,
        val isSyncing: Boolean = false
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    init {
        scope.launch {
            val lastSync = settingsManager.lastSyncTimestamp.first()
            val skew = settingsManager.clockSkewMs.first()
            _state.update {
                it.copy(
                    lastSyncTime = if (lastSync > 0) lastSync else null,
                    clockSkewMs = skew
                )
            }
        }
    }

    fun setSyncing(isSyncing: Boolean) {
        _state.update { it.copy(isSyncing = isSyncing) }
    }

    suspend fun recordSyncSuccess(cloudMaxTime: Long? = null, clockSkew: Long = 0L) {
        val now = TimeManager.currentTimeMillis()
        val effectiveTime = cloudMaxTime?.let { maxOf(it, now - 600_000) } ?: now

        _state.update {
            it.copy(
                lastSyncTime = effectiveTime,
                clockSkewMs = clockSkew,
                isSyncing = false
            )
        }
        settingsManager.setLastSyncTimestamp(effectiveTime)
        settingsManager.setClockSkewMs(clockSkew)
    }

    fun formatRelative(timestamp: Long, now: Long = TimeManager.currentTimeMillis()): String {
        val diff = now - timestamp
        return when {
            diff < 60_000 -> "agora mesmo"
            diff < 3_600_000 -> "há ${diff / 60_000} min"
            diff < 86_400_000 -> "há ${diff / 3_600_000} h"
            else -> java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date(timestamp))
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SyncTimestampManagerEntryPoint {
    fun syncTimestampManager(): SyncTimestampManager
}

@Composable
fun rememberSyncTimestampManager(): SyncTimestampManager {
    val context = LocalContext.current.applicationContext
    return remember {
        EntryPointAccessors.fromApplication(context, SyncTimestampManagerEntryPoint::class.java)
            .syncTimestampManager()
    }
}
