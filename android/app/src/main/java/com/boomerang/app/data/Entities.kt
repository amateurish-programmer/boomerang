package com.boomerang.app.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.boomerang.app.domain.RecordContent

@Entity(tableName = "records")
data class RecordEntity(
    @PrimaryKey val id: String,
    val ownerNamespace: String,
    @Embedded val content: RecordContent,
    val localRevision: Long,
    val serverRevision: Long = 0,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String? = null,
)

@Entity(tableName = "sources", foreignKeys = [ForeignKey(entity = RecordEntity::class, parentColumns = ["id"], childColumns = ["recordId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("recordId")])
data class SourceEntity(@PrimaryKey val id: String, val recordId: String, val ownerNamespace: String, val title: String, val url: String)

@Entity(tableName = "revisions", primaryKeys = ["recordId", "localRevision"], foreignKeys = [ForeignKey(entity = RecordEntity::class, parentColumns = ["id"], childColumns = ["recordId"], onDelete = ForeignKey.RESTRICT)])
data class RevisionEntity(val recordId: String, val localRevision: Long, val snapshotJson: String, val createdAt: String)

@Entity(tableName = "outbox", indices = [Index("recordId")])
data class OutboxEntity(
    @PrimaryKey val operationId: String,
    val recordId: String,
    val localRevision: Long,
    val expectedServerRevision: Long,
    val payloadJson: String,
    val createdAt: String,
    val status: String = "PENDING",
    val attempts: Int = 0,
    val leaseUntil: String? = null,
    val lastError: String? = null,
)
