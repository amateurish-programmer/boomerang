package com.boomerang.app.updates

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    @Test fun unicodeCharacterLimitsCountSupplementaryCharactersOnce() {
        val notes = "🎯".repeat(4000)
        val name = "🎯".repeat(80)
        val valid = JSONObject(updateJson()).put("notes", notes).put("versionName", name).toString()
        assertEquals(notes, UpdatePolicy.parse(valid).notes)
        assertEquals(name, UpdatePolicy.parse(valid).versionName)
        assertThrows(UpdateException::class.java) {
            UpdatePolicy.parse(JSONObject(valid).put("notes", notes + "🎯").toString())
        }
        assertThrows(UpdateException::class.java) {
            UpdatePolicy.parse(JSONObject(valid).put("versionName", name + "🎯").toString())
        }
    }

    @Test fun validReleasePreservesNotesAndOnlyOffersNewerCompatibleVersion() {
        val manifest = UpdatePolicy.parse(updateJson())
        assertEquals("修复与改进", manifest.notes)
        assertTrue(UpdatePolicy.isAvailable(manifest, installed(), 26))
        assertFalse(UpdatePolicy.isAvailable(manifest, installed().copy(versionCode = 6), 35))
        assertFalse(UpdatePolicy.isAvailable(manifest, installed().copy(versionCode = 7), 35))
        assertFalse(UpdatePolicy.isAvailable(manifest.copy(minSdk = 36), installed(), 35))
    }

    @Test fun manifestRejectsWrongTypesRangesAndUntrustedUrls() {
        val invalid = listOf(
            "schemaVersion" to 2, "packageName" to "foreign.app", "versionCode" to 0,
            "versionCode" to 6.5, "versionCode" to "6", "versionCode" to 2100000001L, "minSdk" to 25,
            "sizeBytes" to 67108865, "sizeBytes" to 0, "versionName" to "",
            "notes" to "x".repeat(4001), "sha256" to "A".repeat(64),
            "publishedAt" to "2026-09-19", "publishedAt" to "2026-09-19T12:00:00+08:00",
            "apkUrl" to "https://evil.example/app.apk",
            "apkUrl" to "${APK_URL}?token=foo", "apkUrl" to "$APK_URL#x",
            "apkUrl" to APK_URL.replace("/6/", "/7/"),
            "apkUrl" to APK_URL.replace("https://", "https://user@"),
        )
        invalid.forEach { (key, value) ->
            assertThrows("$key=$value", UpdateException::class.java) {
                UpdatePolicy.parse(JSONObject(updateJson()).put(key, value).toString())
            }
        }
        assertThrows(UpdateException::class.java) { UpdatePolicy.parse(" ".repeat(65537)) }
    }

    @Test fun installationRejectsMetadataMismatchOrUntrustedAndIncompatibleSigners() {
        val release = UpdatePolicy.parse(updateJson())
        val archive = archive()
        UpdatePolicy.verifyArchive(release, archive, installed(), 35)
        listOf(
            archive.copy(packageName = "foreign.app"), archive.copy(versionCode = 5),
            archive.copy(versionName = "forged"), archive.copy(minSdk = 27),
            archive.copy(signers = setOf("f".repeat(64))), archive.copy(signers = emptySet()),
            archive.copy(signers = archive.signers + "f".repeat(64)),
        ).forEach { changed ->
            assertThrows(UpdateException::class.java) { UpdatePolicy.verifyArchive(release, changed, installed(), 35) }
        }
        assertThrows(UpdateException::class.java) {
            UpdatePolicy.verifyArchive(release, archive, installed().copy(signers = setOf("a".repeat(64))), 35)
        }
        assertThrows(UpdateException::class.java) { UpdatePolicy.verifyArchive(release, archive, installed().copy(versionCode = 6), 35) }
        assertThrows(UpdateException::class.java) { UpdatePolicy.verifyArchive(release.copy(minSdk = 36), archive.copy(minSdk = 36), installed(), 35) }
    }
}

internal const val APK_URL = "https://skeghmapzrmahxehazlp.supabase.co/storage/v1/object/public/app-updates/android/6/app.apk"
internal const val SIGNER = "748d6f30358c0be6b96e1ae29cd2538659f7b8f09ac1a40649f20bf26f08afd2"
internal fun installed() = ApkIdentity("com.boomerang.app", 5, "0.4.0", 26, setOf(SIGNER))
internal fun archive() = ApkIdentity("com.boomerang.app", 6, "0.4.1", 26, setOf(SIGNER))
internal fun updateJson() = """{"schemaVersion":1,"packageName":"com.boomerang.app","versionCode":6,"versionName":"0.4.1","minSdk":26,"apkUrl":"$APK_URL","sha256":"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad","sizeBytes":3,"notes":"修复与改进","publishedAt":"2026-09-19T12:00:00Z"}"""
