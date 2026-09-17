package com.boomerang.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.OnConflictStrategy
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface RecordDao {
    @Query("SELECT * FROM records WHERE deletedAt IS NULL ORDER BY dueEnd IS NULL, dueEnd, updatedAt DESC")
    fun observeActive(): Flow<List<RecordEntity>>
    @Query("SELECT * FROM records WHERE id=:id") suspend fun get(id: String): RecordEntity?
    @Query("SELECT * FROM records ORDER BY id") suspend fun allIncludingDeleted(): List<RecordEntity>
    @Insert suspend fun insert(record: RecordEntity)
    @Update suspend fun update(record: RecordEntity): Int
    @Query("SELECT * FROM sources WHERE recordId=:recordId ORDER BY id") suspend fun sources(recordId: String): List<SourceEntity>
    @Query("DELETE FROM sources WHERE recordId=:recordId") suspend fun deleteSources(recordId: String)
    @Insert suspend fun insertSources(sources: List<SourceEntity>)
    @Insert suspend fun insertRevision(revision: RevisionEntity)
    @Query("SELECT * FROM revisions WHERE recordId=:recordId ORDER BY localRevision DESC") suspend fun history(recordId: String): List<RevisionEntity>
    @Query("DELETE FROM outbox WHERE recordId=:id AND localRevision<=:revision") suspend fun clearAcknowledged(id: String, revision: Long)
    @Query("DELETE FROM outbox WHERE recordId=:id") suspend fun clearOutbox(id: String)
    @Update suspend fun updateOutbox(operation: OutboxEntity)
    @Query("SELECT * FROM sync_conflicts") suspend fun conflicts(): List<SyncConflictEntity>
    @Query("SELECT * FROM sync_conflicts WHERE recordId=:id") suspend fun conflict(id: String): SyncConflictEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putConflict(conflict: SyncConflictEntity)
    @Query("DELETE FROM sync_conflicts WHERE recordId=:id") suspend fun clearConflict(id: String)
    @Insert suspend fun insertOutbox(operation: OutboxEntity)
    @Query("SELECT * FROM outbox ORDER BY createdAt, localRevision") suspend fun outbox(): List<OutboxEntity>
}

@Database(entities = [RecordEntity::class, SourceEntity::class, RevisionEntity::class, OutboxEntity::class, SyncConflictEntity::class], version = 2, exportSchema = true)
abstract class BoomerangDatabase : RoomDatabase() {
    abstract fun records(): RecordDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sources ADD COLUMN origin TEXT NOT NULL DEFAULT 'CLIENT'")
                db.execSQL("ALTER TABLE sources ADD COLUMN verifiedByTool INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE records ADD COLUMN capsuleLockedAt TEXT")
                db.execSQL("ALTER TABLE records ADD COLUMN capsuleUnlockAt TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS sync_conflicts (recordId TEXT NOT NULL PRIMARY KEY, remoteRecordJson TEXT NOT NULL, remoteSourcesJson TEXT NOT NULL)")
            }
        }
        /** Accounts get separate files. Calling this does not import anonymous data. */
        fun open(context: Context, ownerNamespace: String = "local"): BoomerangDatabase {
            require(ownerNamespace == "local" || runCatching { UUID.fromString(ownerNamespace).toString() == ownerNamespace }.getOrDefault(false))
            return Room.databaseBuilder(context.applicationContext, BoomerangDatabase::class.java, "boomerang_$ownerNamespace.db").addMigrations(MIGRATION_1_2).build()
        }
    }
}
