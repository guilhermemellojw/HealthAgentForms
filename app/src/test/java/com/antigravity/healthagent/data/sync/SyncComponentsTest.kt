package com.antigravity.healthagent.data.sync

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSettingsSource : SystemSettingsSource {
    override suspend fun fetchSystemSettings(): Result<Map<String, Any>> = Result.success(emptyMap())
}

class SyncComponentsTest {

    @Test
    fun testVersionChecker_CheckVersionSuccess() {
        val checker = VersionChecker(FakeSettingsSource())

        val context = mockk<Context>()
        val packageManager = mockk<PackageManager>()
        val packageInfo = PackageInfo().apply {
            versionCode = 100
        }

        every { context.packageName } returns "com.antigravity.healthagent"
        every { context.packageManager } returns packageManager
        every { packageManager.getPackageInfo("com.antigravity.healthagent", 0) } returns packageInfo

        val sysSettings = mapOf<String, Any>("minAppVersion" to 90)
        val result = checker.checkVersion(context, sysSettings)

        assertTrue(result.isSuccess)
    }

    @Test
    fun testVersionChecker_CheckVersionOutdated() {
        val checker = VersionChecker(FakeSettingsSource())

        val context = mockk<Context>()
        val packageManager = mockk<PackageManager>()
        val packageInfo = PackageInfo().apply {
            versionCode = 80
        }

        every { context.packageName } returns "com.antigravity.healthagent"
        every { context.packageManager } returns packageManager
        every { packageManager.getPackageInfo("com.antigravity.healthagent", 0) } returns packageInfo

        val sysSettings = mapOf<String, Any>("minAppVersion" to 90)
        val result = checker.checkVersion(context, sysSettings)

        assertTrue(result.isFailure)
        assertEquals("Versão do aplicativo desatualizada. Por favor, atualize o 'Eu ACE' na Play Store para continuar sincronizando seus dados.", result.exceptionOrNull()?.message)
    }

    @Test
    fun testVersionChecker_WipeRequired() {
        val checker = VersionChecker(FakeSettingsSource())

        val flags = RemoteSyncFlags(
            requireDataResetFromUser = true,
            requireDataResetFromAgent = false,
            agentDocExists = true
        )

        // localUnsyncedCount == 0, requireResetFromUser == true -> wipe required
        val wipeRequired = checker.isWipeRequired(
            flags = flags,
            hasSyncHistory = true,
            isTargetDifferentUser = false,
            localUnsyncedCount = 0
        )
        assertTrue(wipeRequired)

        // localUnsyncedCount > 0 -> wipe should not be required (prevent deleting unsynced work)
        val wipeRequiredWithUnsynced = checker.isWipeRequired(
            flags = flags,
            hasSyncHistory = true,
            isTargetDifferentUser = false,
            localUnsyncedCount = 3
        )
        assertFalse(wipeRequiredWithUnsynced)
    }
}
