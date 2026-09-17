package com.boomerang.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
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
    @Insert suspend fun insertOutbox(operation: OutboxEntity)
    @Query("SELECT * FROM outbox ORDER BY createdAt, localRevision") suspend fun outbox(): List<OutboxEntity>
}

@Database(entities = [RecordEntity::class, SourceEntity::class, RevisionEntity::class, OutboxEntity::class], version = 1, exportSchema = true)
abstract class BoomerangDatabase : RoomDatabase() {
    abstract fun records(): RecordDao
    companion object {
        /** Accounts get separate files. Calling this does not import anonymous data. */
        fun open(context: Context, ownerNamespace: String = "local"): BoomerangDatabase {
            require(ownerNamespace == "local" || runCatching { UUID.fromString(ownerNamespace).toString() == ownerNamespace }.getOrDefault(false))
            return Room.databaseBuilder(context.applicationContext, BoomerangDatabase::class.java, "boomerang_$ownerNamespace.db").build()
        }
    }
}
