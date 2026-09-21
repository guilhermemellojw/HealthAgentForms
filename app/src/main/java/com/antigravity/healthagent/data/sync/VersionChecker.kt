package com.antigravity.healthagent.data.sync

import android.content.Context
import com.antigravity.healthagent.domain.logger.AppLogger
import com.antigravity.healthagent.utils.AppConstants
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VersionChecker @Inject constructor(
    private val settingsSource: SystemSettingsSource
) {
    suspend fun fetchSystemSettings(): Result<Map<String, Any>> {
        return settingsSource.fetchSystemSettings()
    }

    fun checkVersion(context: Context, sysSettings: Map<String, Any>): Result<Unit> {
        val minVersion = (sysSettings["minAppVersion"] as? Number)?.toInt() ?: AppConstants.MIN_VERSION_CODE
        val pInfo = try { context.packageManager.getPackageInfo(context.packageName, 0) } catch (e: Exception) { null }
        val currentVersion = pInfo?.versionCode ?: 0
        
        if (currentVersion < minVersion) {
            AppLogger.e("VersionChecker", "Version Enforcement: App version ($currentVersion) is below minimum required ($minVersion)")
            return Result.failure(Exception("Versão do aplicativo desatualizada. Por favor, atualize o 'Eu ACE' na Play Store para continuar sincronizando seus dados."))
        }
        return Result.success(Unit)
    }

    fun isWipeRequired(
        flags: RemoteSyncFlags,
        hasSyncHistory: Boolean,
        isTargetDifferentUser: Boolean,
        localUnsyncedCount: Int
    ): Boolean {
        return (flags.requireDataReset || (!isTargetDifferentUser && hasSyncHistory && !flags.agentDocExists)) && localUnsyncedCount == 0
    }
}
