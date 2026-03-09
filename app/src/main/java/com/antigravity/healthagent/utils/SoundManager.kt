package com.antigravity.healthagent.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import android.util.Log
import com.antigravity.healthagent.data.settings.SettingsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

enum class SoundCategory {
    POP, SUCCESS, CELEBRATION, WARNING
}

@Singleton
class SoundManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsManager: SettingsManager
) {

    private var mediaPlayer: MediaPlayer? = null
    private val exceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
        Log.e("SoundManager", "Uncaught exception in SoundManager scope", throwable)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

    // Current sound selections
    private var currentPopSound = "SYSTEM_NOTIFICATION_1"
    private var currentSuccessSound = "SYSTEM_NOTIFICATION_1"
    private var currentCelebrationSound = "SYSTEM_ALARM"
    private var currentWarningSound = "SYSTEM_NOTIFICATION_2"

    init {
        try {
            // Observe sound preference changes
            scope.launch {
                settingsManager.popSound.collectLatest { soundId ->
                    currentPopSound = soundId
                }
            }
            scope.launch {
                settingsManager.successSound.collectLatest { soundId ->
                    currentSuccessSound = soundId
                }
            }
            scope.launch {
                settingsManager.celebrationSound.collectLatest { soundId ->
                    currentCelebrationSound = soundId
                }
            }
            scope.launch {
                settingsManager.warningSound.collectLatest { soundId ->
                    currentWarningSound = soundId
                }
            }
        } catch (e: Exception) {
            Log.e("SoundManager", "Failed to initialize SoundManager", e)
        }
    }

    fun playPop() {
        playSound(currentPopSound)
    }

    fun playSuccess() {
        playSound(currentSuccessSound)
    }

    fun playCelebration() {
        playSound(currentCelebrationSound)
    }

    fun playWarning() {
        playSound(currentWarningSound)
    }

    fun playSound(soundId: String) {
        if (soundId == "SILENT") return // Silent option

        scope.launch(Dispatchers.Default) {
            try {
                // Check if it's a system sound (content://) or specific SYSTEM_ constants
                if (soundId.startsWith("content://") || soundId.startsWith("SYSTEM_")) {
                    playSystemSound(soundId)
                }
                // Check if it's a custom sound (file://)
                else if (soundId.startsWith("file://")) {
                    playCustomSound(soundId)
                }
            } catch (e: Exception) {
                Log.e("SoundManager", "Error playing sound: $soundId", e)
            }
        }
    }

    private fun playSystemSound(soundId: String) {
        try {
            val uri = when {
                soundId.startsWith("content://") -> Uri.parse(soundId)
                soundId == "SYSTEM_ALARM" -> android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                // Fallback for legacy "SYSTEM_NOTIFICATION_X" if they still exist in prefs
                soundId.startsWith("SYSTEM_NOTIFICATION_") -> android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                else -> android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            }
            
            val ringtone = android.media.RingtoneManager.getRingtone(context, uri)
            // Some devices/URIs might return null ringtone
            if (ringtone != null) {
                // Force playback through MEDIA stream for consistent volume
                ringtone.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                    
                ringtone.play()
                
                // Auto-stop after 3 seconds to prevent long sounds (especially for alarms/ringtones)
                scope.launch {
                    kotlinx.coroutines.delay(3000)
                    if (ringtone.isPlaying) {
                        ringtone.stop()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SoundManager", "Error playing system sound: $soundId", e)
        }
    }

    private fun playCustomSound(uri: String) {
        try {
            // For file:// URIs, we need to ensure we're accessing them correctly.
            // Using MediaPlayer with a Uri is standard.
            
            // Release previous player if any
            mediaPlayer?.release()
            mediaPlayer = null
            
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_MEDIA) // Changed from USAGE_NOTIFICATION_EVENT
                        .build()
                )
                setDataSource(context, Uri.parse(uri))
                prepare()
                start()
                setOnCompletionListener {
                    it.release()
                    if (mediaPlayer == it) {
                        mediaPlayer = null
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SoundManager", "Error playing custom sound: $uri", e)
        }
    }

    fun release() {
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
