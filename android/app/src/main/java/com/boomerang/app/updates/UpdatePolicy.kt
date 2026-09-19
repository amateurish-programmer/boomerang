package com.boomerang.app.updates

import java.time.Instant
import org.json.JSONObject

class UpdateException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class UpdateManifest(
    val schemaVersion: Int, val packageName: String, val versionCode: Long,
    val versionName: String, val minSdk: Int, val apkUrl: String, val sha256: String,
    val sizeBytes: Long, val notes: String, val publishedAt: String,
)

data class ApkIdentity(
    val packageName: String, val versionCode: Long, val versionName: String,
    val minSdk: Int, val signers: Set<String>,
)

object UpdatePolicy {
    const val BASE_URL = "https://skeghmapzrmahxehazlp.supabase.co/storage/v1/object/public/app-updates/android/"
    const val MANIFEST_URL = "${BASE_URL}latest.json"
    const val PACKAGE_NAME = "com.boomerang.app"
    const val RELEASE_SIGNER = "748d6f30358c0be6b96e1ae29cd2538659f7b8f09ac1a40649f20bf26f08afd2"
    const val MAX_MANIFEST_BYTES = 65536L
    const val MAX_APK_BYTES = 67108864L

    fun parse(raw: String): UpdateManifest {
        try {
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_MANIFEST_BYTES)
            val json = JSONObject(raw)
            fun text(key: String, max: Int): String {
                val value = json.get(key)
                require(value is String && value.isNotBlank() && value.codePointCount(0, value.length) <= max)
                return value
            }
            fun integer(key: String, min: Long, max: Long): Long {
                val value = json.get(key)
                require(value is Int || value is Long)
                return (value as Number).toLong().also { require(it in min..max) }
            }
            val schema = integer("schemaVersion", 1, 1).toInt()
            val pkg = text("packageName", 80).also { require(it == PACKAGE_NAME) }
            val version = integer("versionCode", 1, 2100000000L)
            val name = text("versionName", 80)
            val sdk = integer("minSdk", 26, Int.MAX_VALUE.toLong()).toInt()
            val url = text("apkUrl", 512).also { require(it == "${BASE_URL}$version/app.apk") }
            val hash = text("sha256", 64).also { require(it.matches(Regex("[0-9a-f]{64}"))) }
            val size = integer("sizeBytes", 1, MAX_APK_BYTES)
            val notes = text("notes", 4000)
            val date = text("publishedAt", 80).also { require(it.endsWith("Z")); Instant.parse(it) }
            return UpdateManifest(schema, pkg, version, name, sdk, url, hash, size, notes, date)
        } catch (error: Exception) {
            throw UpdateException("更新信息无效，请稍后重试", error)
        }
    }

    fun encode(manifest: UpdateManifest): String = JSONObject()
        .put("schemaVersion", manifest.schemaVersion).put("packageName", manifest.packageName)
        .put("versionCode", manifest.versionCode).put("versionName", manifest.versionName)
        .put("minSdk", manifest.minSdk).put("apkUrl", manifest.apkUrl).put("sha256", manifest.sha256)
        .put("sizeBytes", manifest.sizeBytes).put("notes", manifest.notes).put("publishedAt", manifest.publishedAt).toString()

    fun isAvailable(manifest: UpdateManifest, installed: ApkIdentity, sdk: Int) =
        installed.packageName == manifest.packageName && manifest.versionCode > installed.versionCode && manifest.minSdk <= sdk

    fun verifyArchive(manifest: UpdateManifest, archive: ApkIdentity, installed: ApkIdentity, sdk: Int) {
        parse(encode(manifest))
        if (!isAvailable(manifest, installed, sdk)) throw UpdateException("此版本不适用于当前应用或系统")
        if (archive.packageName != manifest.packageName || archive.versionCode != manifest.versionCode ||
            archive.versionName != manifest.versionName || archive.minSdk != manifest.minSdk) {
            throw UpdateException("安装包版本信息校验失败，请重新下载")
        }
        if (archive.signers != setOf(RELEASE_SIGNER) || installed.signers != archive.signers) {
            throw UpdateException("安装包签名与当前应用不兼容，无法安装")
        }
    }

    fun trustedNetworkUrl(url: String): Boolean {
        if (url == MANIFEST_URL) return true
        if (!url.startsWith(BASE_URL)) return false
        val path = url.removePrefix(BASE_URL)
        if (!path.matches(Regex("[1-9][0-9]{0,9}/app\\.apk"))) return false
        return path.substringBefore('/').toLongOrNull()?.let { it <= 2100000000L } == true
    }
}
