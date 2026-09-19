package com.boomerang.app.updates

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private var installedApp = installed()
    private var apk = archive()
    private var body: () -> InputStream = { ByteArrayInputStream("abc".toByteArray()) }
    private var manifestBody = updateJson()
    private fun repository() = UpdateRepository(
        File(folder.root, "metadata"), File(folder.root, "cache"),
        UpdateTransport { url ->
            when (url) {
                UpdatePolicy.MANIFEST_URL -> UpdateResponse(ByteArrayInputStream(manifestBody.toByteArray()), -1)
                APK_URL -> UpdateResponse(body(), -1)
                else -> error("Unexpected network destination")
            }
        }, { installedApp }, { apk }, 35,
    )

    @Test fun verifiedDownloadSurvivesRepositoryRecreationAndRevalidatesBeforeInstall() = runBlocking {
        val repo = repository()
        val manifest = repo.check()
        val progress = mutableListOf<Long>()
        repo.download(manifest) { progress += it }
        assertEquals(3L, progress.last())
        val restored = repository()
        assertEquals(UpdateStage.READY, restored.restore().stage)
        assertEquals("abc", restored.prepareInstall(true).readText())
        restored.prepareInstall(true).writeText("bad")
        try { restored.prepareInstall(true); fail("Tampered cache must fail") } catch (_: UpdateException) { }
        assertNotEquals(UpdateStage.READY, repository().restore().stage)
    }

    @Test fun truncatedOversizedAndWrongHashBodiesNeverBecomeInstallable() = runBlocking {
        for (bytes in listOf("ab", "abcd", "abd")) {
            body = { ByteArrayInputStream(bytes.toByteArray()) }
            val repo = repository()
            try { repo.download(repo.check()); fail("Unsafe body $bytes") } catch (_: UpdateException) { }
            assertEquals(UpdateStage.RETRY, repository().restore().stage)
            try { repo.prepareInstall(true); fail() } catch (_: UpdateException) { }
        }
    }

    @Test fun interruptedDownloadRestoresRetryAndExplicitRetryStartsFromZero() = runBlocking {
        body = { object : InputStream() {
            var read = false
            override fun read(): Int { if (!read) { read = true; return 'a'.code }; throw IOException("connection lost") }
        } }
        val repo = repository()
        try { repo.download(repo.check()); fail() } catch (_: UpdateException) { }
        val retry = repository()
        assertEquals(UpdateStage.RETRY, retry.restore().stage)
        body = { ByteArrayInputStream("abc".toByteArray()) }
        retry.download(retry.restore().manifest!!)
        assertEquals("abc", retry.prepareInstall(true).readText())
    }

    @Test fun cancellationCannotLeaveAnInstallablePartialFile() = runBlocking {
        val repo = repository()
        try { repo.download(repo.check()) { if (it > 0) throw CancellationException("cancelled") }; fail() }
        catch (_: CancellationException) { }
        assertEquals(UpdateStage.RETRY, repository().restore().stage)
        assertFalse(File(folder.root, "cache/app.apk").exists())
    }

    @Test fun installRequiresPermissionAndStillNewerVersionAndCurrentSigner() = runBlocking {
        val repo = repository()
        repo.download(repo.check())
        try { repo.prepareInstall(false); fail() } catch (_: UpdateException) { }
        installedApp = installed().copy(versionCode = 6)
        try { repo.prepareInstall(true); fail() } catch (_: UpdateException) { }
        installedApp = installed().copy(signers = setOf("b".repeat(64)))
        try { repo.prepareInstall(true); fail() } catch (_: UpdateException) { }
    }

    @Test fun manifestNetworkReadIsBoundedAndApkMetadataMismatchDoesNotProduceReadyFile() = runBlocking {
        manifestBody = " ".repeat(65537)
        try { repository().check(); fail() } catch (_: UpdateException) { }
        manifestBody = updateJson()
        apk = archive().copy(versionCode = 99)
        val repo = repository()
        try { repo.download(repo.check()); fail() } catch (_: UpdateException) { }
        assertEquals(UpdateStage.RETRY, repository().restore().stage)
        assertFalse(File(folder.root, "cache/app.apk").exists())
    }

    @Test fun metadataTamperingCannotSelectForeignUrlOrFile() = runBlocking {
        val repo = repository()
        repo.download(repo.check())
        File(folder.root, "metadata/download.json").writeText(JSONObject(updateJson()).put("apkUrl", "https://evil.example/app.apk").toString())
        assertNotEquals(UpdateStage.READY, repository().restore().stage)
        try { repository().prepareInstall(true); fail() } catch (_: UpdateException) { }
    }
}
