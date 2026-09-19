package com.boomerang.app.updates

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class UpdatePlatformTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun installedPackageIdentityIsReadableOnEverySupportedApi() {
        val identity = AndroidUpdatePlatform(context).installed()
        assertEquals(context.packageName, identity.packageName)
        assertTrue(identity.versionCode > 0)
        assertEquals(26, identity.minSdk)
        assertTrue(identity.signers.isNotEmpty())
        assertTrue(identity.signers.all { it.matches(Regex("[0-9a-f]{64}")) })
    }

    @Test fun settingsArePackageScopedAndInstallerGetsOnlyOneReadGrant() {
        val platform = AndroidUpdatePlatform(context)
        val settings = platform.permissionIntent()
        assertEquals("package:${context.packageName}", settings.data.toString())
        val apk = java.io.File(context.cacheDir, "updates/install/app.apk")
        apk.parentFile!!.mkdirs()
        apk.writeText("isolated provider fixture")
        try {
            val intent = platform.installIntent(apk)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("application/vnd.android.package-archive", intent.type)
            assertEquals("content", intent.data!!.scheme)
            assertEquals("${context.packageName}.share", intent.data!!.authority)
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
            assertEquals(1, intent.clipData!!.itemCount)
            assertEquals(intent.data, intent.clipData!!.getItemAt(0).uri)
            assertEquals("isolated provider fixture", context.contentResolver.openInputStream(intent.data!!)!!.bufferedReader().use { it.readText() })
        } finally { apk.delete() }
    }

    @Test fun installerCannotShareAnArbitraryPrivateFile() {
        val outside = java.io.File(context.filesDir, "must-not-share.apk")
        assertThrows(UpdateException::class.java) { AndroidUpdatePlatform(context).installIntent(outside) }
    }
}
