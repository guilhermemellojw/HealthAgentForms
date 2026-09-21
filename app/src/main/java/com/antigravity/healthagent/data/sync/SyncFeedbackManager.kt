package com.antigravity.healthagent.data.sync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.antigravity.healthagent.ui.state.SyncUiState
import com.antigravity.healthagent.utils.TimeManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncFeedbackManager @Inject constructor(
    private val timestampManager: SyncTimestampManager
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _feedback = MutableStateFlow<SyncUiState>(SyncUiState.Idle())
    val feedback: StateFlow<SyncUiState> = _feedback.asStateFlow()

    val isSyncing: Boolean
        get() = _feedback.value is SyncUiState.Syncing

    private var dismissJob: Job? = null

    fun syncing(progress: Float = 0.5f, message: String? = null, isDownloading: Boolean = false) {
        cancelDismiss()
        val prev = _feedback.value
        _feedback.value = SyncUiState.Syncing(
            progress = progress,
            message = message,
            isDownloading = isDownloading,
            lastSyncTime = prev.lastSyncTime,
            clockSkewMs = prev.clockSkewMs
        )
    }

    fun success(cloudMaxTime: Long? = null, clockSkew: Long = 0L, recordTimestamp: Boolean = true) {
        cancelDismiss()
        dismissJob = scope.launch {
            if (recordTimestamp) {
                timestampManager.recordSyncSuccess(cloudMaxTime, clockSkew)
            }
            val ts = timestampManager.state.value
            _feedback.value = SyncUiState.Success(
                lastSyncTime = ts.lastSyncTime ?: System.currentTimeMillis(),
                clockSkewMs = ts.clockSkewMs
            )
            delay(2000)
            _feedback.value = SyncUiState.Idle(
                lastSyncTime = ts.lastSyncTime,
                clockSkewMs = ts.clockSkewMs
            )
        }
    }

    fun error(message: String) {
        cancelDismiss()
        val prev = _feedback.value
        _feedback.value = SyncUiState.Error(
            message = message,
            lastSyncTime = prev.lastSyncTime,
            clockSkewMs = prev.clockSkewMs
        )
        dismissJob = scope.launch {
            delay(3000)
            val ts = timestampManager.state.value
            _feedback.value = SyncUiState.Idle(
                lastSyncTime = ts.lastSyncTime,
                clockSkewMs = ts.clockSkewMs
            )
        }
    }

    fun idle() {
        cancelDismiss()
        val ts = timestampManager.state.value
        _feedback.value = SyncUiState.Idle(
            lastSyncTime = ts.lastSyncTime,
            clockSkewMs = ts.clockSkewMs
        )
    }

    fun formatRelative(timestamp: Long, now: Long = TimeManager.currentTimeMillis()): String =
        timestampManager.formatRelative(timestamp, now)

    private fun cancelDismiss() {
        dismissJob?.cancel()
        dismissJob = null
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SyncFeedbackManagerEntryPoint {
    fun syncFeedbackManager(): SyncFeedbackManager
}

@Composable
fun rememberSyncFeedbackManager(): SyncFeedbackManager {
    val context = LocalContext.current.applicationContext
    return remember {
        EntryPointAccessors.fromApplication(context, SyncFeedbackManagerEntryPoint::class.java)
            .syncFeedbackManager()
    }
}
