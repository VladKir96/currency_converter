package com.pocketfx

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketfx.data.update.ApkInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ApkInstallerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val installer = ApkInstaller(context)

    @Test fun identifiesTheApkAndUsesAReadOnlyContentUriForExplicitSystemConfirmation() {
        val folder = context.cacheDir.resolve("app-updates").apply { mkdirs() }
        val copy = File.createTempFile("test-install-", ".apk", folder)
        try {
            File(context.applicationInfo.sourceDir).copyTo(copy, overwrite = true)
            assertEquals(installer.installed(), installer.inspect(copy))
            val handoff = installer.installIntent(copy)
            assertEquals("android.intent.action.INSTALL_PACKAGE", handoff.action)
            assertEquals("content", handoff.data?.scheme)
            assertEquals("${context.packageName}.updates", handoff.data?.authority)
            assertTrue(handoff.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertFalse(handoff.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
            assertTrue(handoff.getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false))
            assertNotNull(context.contentResolver.openInputStream(requireNotNull(handoff.data))?.use { it.read() })
        } finally {
            copy.delete()
        }
    }

    @Test fun permissionSettingsAreScopedToPocketFxAndMalformedApksCannotBeHandedOff() {
        val request = installer.permissionIntent()
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, request.action)
        assertEquals(context.packageName, request.data?.schemeSpecificPart)
        val invalid = File.createTempFile("unshared-", ".apk", context.cacheDir)
        try {
            invalid.writeText("not an APK")
            assertNull(installer.inspect(invalid))
            assertThrows(IllegalArgumentException::class.java) { installer.installIntent(invalid) }
        } finally {
            invalid.delete()
        }
    }
}
