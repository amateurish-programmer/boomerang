package com.boomerang.app.updates

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class UpdateStage { IDLE, AVAILABLE, DOWNLOADING, READY, RETRY }
data class UpdateDownload(val stage: UpdateStage, val manifest: UpdateManifest? = null)

class UpdateRepository(
    metadataDirectory: File,
    cacheDirectory: File,
    private val transport: UpdateTransport,
    private val installed: () -> ApkIdentity,
    private val inspectArchive: (File) -> ApkIdentity,
    private val sdk: Int,
) {
    private val metadata = File(metadataDirectory, "download.json")
    private val partial = File(metadataDirectory, "app.apk.part")
    private val verified = File(cacheDirectory, "app.apk")
    private val lock = Mutex()

    suspend fun check(): UpdateManifest = io {
        withTimeout(30000) {
            transport.open(UpdatePolicy.MANIFEST_URL).use { response ->
                if (response.contentLength > UpdatePolicy.MAX_MANIFEST_BYTES) throw UpdateException("更新信息过大，已停止读取")
                val bytes = ByteArrayOutputStream()
                copyBounded(response.stream, UpdatePolicy.MAX_MANIFEST_BYTES) { buffer, count -> bytes.write(buffer, 0, count) }
                UpdatePolicy.parse(bytes.toString("UTF-8"))
            }
        }
    }

    suspend fun restore(): UpdateDownload = io {
        lock.withLock {
            partial.delete()
            val manifest = readMetadata() ?: run { verified.delete(); return@withLock UpdateDownload(UpdateStage.IDLE) }
            if (!UpdatePolicy.isAvailable(manifest, installed(), sdk)) {
                verified.delete(); metadata.delete()
                return@withLock UpdateDownload(UpdateStage.IDLE)
            }
            if (!verified.isFile) return@withLock UpdateDownload(UpdateStage.RETRY, manifest)
            try {
                verifyFile(verified, manifest)
                UpdateDownload(UpdateStage.READY, manifest)
            } catch (error: UpdateException) {
                verified.delete()
                UpdateDownload(UpdateStage.RETRY, manifest)
            }
        }
    }

    suspend fun download(manifest: UpdateManifest, progress: (Long) -> Unit = {}) = io {
        lock.withLock {
            UpdatePolicy.parse(UpdatePolicy.encode(manifest))
            if (!UpdatePolicy.isAvailable(manifest, installed(), sdk)) throw UpdateException("此版本不适用于当前应用或系统")
            verified.delete(); partial.delete()
            writeMetadata(manifest)
            partial.parentFile!!.mkdirs()
            verified.parentFile!!.mkdirs()
            try {
                withTimeout(180000) {
                    transport.open(manifest.apkUrl).use { response ->
                        if (response.contentLength >= 0 && response.contentLength != manifest.sizeBytes) throw UpdateException("安装包大小不匹配")
                        FileOutputStream(partial).use { output ->
                            var downloaded = 0L
                            progress(0)
                            copyBounded(response.stream, manifest.sizeBytes) { buffer, count ->
                                output.write(buffer, 0, count)
                                downloaded += count
                                progress(downloaded)
                            }
                            output.fd.sync()
                        }
                    }
                    verifyFile(partial, manifest)
                    currentCoroutineContext().ensureActive()
                    if (!partial.renameTo(verified)) throw UpdateException("无法保存安装包，请重试")
                }
            } finally {
                // An interrupted download is never renamed or exposed through FileProvider.
                partial.delete()
            }
        }
    }

    suspend fun prepareInstall(permissionGranted: Boolean): File = io {
        lock.withLock {
            if (!permissionGranted) throw UpdateException("请先允许回旋镖安装应用，再点击安装")
            val manifest = readMetadata() ?: throw UpdateException("请先下载更新")
            try {
                verifyFile(verified, manifest)
                verified
            } catch (error: UpdateException) {
                verified.delete()
                throw error
            }
        }
    }

    private fun readMetadata(): UpdateManifest? = try {
        if (!metadata.isFile || metadata.length() > UpdatePolicy.MAX_MANIFEST_BYTES) null
        else UpdatePolicy.parse(metadata.readText(Charsets.UTF_8))
    } catch (_: Exception) { null }

    private fun writeMetadata(manifest: UpdateManifest) {
        metadata.parentFile!!.mkdirs()
        val temp = File(metadata.parentFile, "download.json.tmp")
        FileOutputStream(temp).use { it.write(UpdatePolicy.encode(manifest).toByteArray(Charsets.UTF_8)); it.fd.sync() }
        try {
            Files.move(temp.toPath(), metadata.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Exception) {
            throw UpdateException("无法保存下载信息，请重试", error)
        } finally { temp.delete() }
    }

    private suspend fun verifyFile(file: File, manifest: UpdateManifest) {
        if (!file.isFile || file.length() != manifest.sizeBytes) throw UpdateException("安装包不完整，请重新下载")
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> copyBounded(input, manifest.sizeBytes) { buffer, count -> digest.update(buffer, 0, count) } }
        if (digest.digest().hex() != manifest.sha256) throw UpdateException("安装包完整性校验失败，请重新下载")
        UpdatePolicy.verifyArchive(manifest, inspectArchive(file), installed(), sdk)
    }

    private suspend fun copyBounded(input: InputStream, maxBytes: Long, consume: (ByteArray, Int) -> Unit) {
        val buffer = ByteArray(16384)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw UpdateException("下载内容超过允许大小，已停止")
            if (count > 0) consume(buffer, count)
        }
    }

    private suspend fun <T> io(action: suspend () -> T): T = withContext(Dispatchers.IO) {
        try { action() }
        catch (error: TimeoutCancellationException) { throw UpdateException("更新连接超时，请重试", error) }
        catch (error: CancellationException) { throw error }
        catch (error: UpdateException) { throw error }
        catch (error: Exception) { throw UpdateException("更新未完成，请检查网络或存储空间后重试", error) }
    }
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
