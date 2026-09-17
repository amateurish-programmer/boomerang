package com.boomerang.app

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.boomerang.app.data.BoomerangDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), BoomerangDatabase::class.java)
    @Test fun versionOneRecordsSurviveSyncAndCapsuleMigration() {
        val name = "migration-preserves-records"
        helper.createDatabase(name, 1).apply {
            execSQL("""INSERT INTO records (id,ownerNamespace,recordType,originalText,subject,topic,lifecycle,dateText,datePrecision,timezone,verificationCriteria,notes,localRevision,serverRevision,createdAt,updatedAt)
                VALUES ('10000000-0000-0000-0000-000000000001','local','FLAG','迁移前原话','我','','ACTIVE','','UNKNOWN','Asia/Shanghai','','',1,0,'2026-09-17T00:00:00Z','2026-09-17T00:00:00Z')""")
            close()
        }
        helper.runMigrationsAndValidate(name, 2, true, BoomerangDatabase.MIGRATION_1_2).apply {
            query("SELECT originalText,capsuleLockedAt,capsuleUnlockAt FROM records").use {
                assertTrue(it.moveToFirst()); assertEquals("迁移前原话", it.getString(0)); assertTrue(it.isNull(1)); assertTrue(it.isNull(2))
            }
            query("SELECT origin, verifiedByTool FROM sources LIMIT 0").close()
            query("SELECT COUNT(*) FROM sync_conflicts").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            close()
        }
    }
}
