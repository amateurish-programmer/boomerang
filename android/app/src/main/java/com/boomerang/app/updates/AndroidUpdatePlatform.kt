package com.boomerang.app.updates

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

class AndroidUpdatePlatform(private val context: Context) {
    private val manager get() = context.packageManager
    private val archiveFlags get() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else {
        @Suppress("DEPRECATION")
        PackageManager.GET_SIGNATURES
    }

    @Suppress("DEPRECATION")
    fun installed(): ApkIdentity = identity(manager.getPackageInfo(context.packageName, archiveFlags))

    fun inspect(file: File): ApkIdentity {
        val info = manager.getPackageArchiveInfo(file.absolutePath, archiveFlags)
            ?: throw UpdateException("无法识别安装包，请重新下载")
        return identity(info)
    }

    @Suppress("DEPRECATION")
    private fun identity(info: PackageInfo): ApkIdentity {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        val signers = signatures?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }?.toSet().orEmpty()
        return ApkIdentity(info.packageName, if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
            info.versionName.orEmpty(), info.applicationInfo?.minSdkVersion ?: throw UpdateException("无法读取安装包系统要求"), signers)
    }

    fun canInstall(): Boolean = manager.canRequestPackageInstalls()

    fun permissionIntent(): Intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    fun installIntent(file: File): Intent {
        val allowed = File(context.cacheDir, "updates/install/app.apk")
        if (file.canonicalFile != allowed.canonicalFile || !file.isFile) throw UpdateException("请先下载并校验更新")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            clipData = ClipData.newRawUri("回旋镖更新", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun repository() = UpdateRepository(File(context.filesDir, "updates"), File(context.cacheDir, "updates/install"),
        UrlUpdateTransport(), ::installed, ::inspect, Build.VERSION.SDK_INT)
}
