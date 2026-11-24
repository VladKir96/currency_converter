package com.pocketfx.data.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import java.io.File

internal class ApkInstaller(private val context: Context) {
    fun installed(): AppVersion = context.packageManager.getPackageInfo(context.packageName, 0).toVersion()

    fun inspect(apk: File): AppVersion? = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.toVersion()

    fun allowed(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", context.packageName, null))

    @Suppress("DEPRECATION") // The FileProvider handoff uses Android's system installer and returns its user-visible result.
    fun installIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        return Intent(Intent.ACTION_INSTALL_PACKAGE, uri).apply {
            clipData = ClipData.newRawUri(apk.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
    }

    private fun PackageInfo.toVersion() = AppVersion(packageName, PackageInfoCompat.getLongVersionCode(this), versionName.orEmpty())
}
