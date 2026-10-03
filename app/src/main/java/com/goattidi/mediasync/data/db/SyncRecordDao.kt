package com.goattidi.mediasync.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncRecordDao {

    @Upsert
    suspend fun upsert(records: List<SyncRecord>)

    @Upsert
    suspend fun upsert(record: SyncRecord)

    @Query("SELECT * FROM sync_records")
    suspend fun getAll(): List<SyncRecord>

    @Query("SELECT * FROM sync_records")
    fun observeAll(): Flow<List<SyncRecord>>

    @Query("SELECT * FROM sync_records WHERE mediaStoreId = :id")
    suspend fun getById(id: Long): SyncRecord?

    @Query("SELECT * FROM sync_records WHERE status = :status")
    suspend fun getByStatus(status: SyncStatus): List<SyncRecord>

    /** Rows that name a remote object, for whichever backend issued it. */
    @Query("SELECT * FROM sync_records WHERE driveFileId IS NOT NULL")
    suspend fun getAllWithRemoteObject(): List<SyncRecord>

    /**
     * Rows in [status] whose remote object was issued by [backendId] — how a verify or a
     * reclaim pass stays on the backend the user actually selected.
     */
    @Query("SELECT * FROM sync_records WHERE status = :status AND driveFileId IS NOT NULL AND backendId = :backendId")
    suspend fun getByStatusWithRemoteObject(status: SyncStatus, backendId: String): List<SyncRecord>

    /** Drops in-flight state that belongs to another backend, keeping the local record. */
    @Query(
        "UPDATE sync_records SET resumeSessionUri = NULL, driveFileId = NULL, driveMd5 = NULL, " +
            "uploadedAt = NULL, bytesUploaded = 0, uploadRateBps = 0 " +
            "WHERE driveFileId IS NOT NULL AND (backendId IS NULL OR backendId != :backendId)"
    )
    suspend fun clearRemoteStateForOtherBackends(backendId: String): Int

    @Query("DELETE FROM sync_records WHERE mediaStoreId IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** Guarded status transition; rows not currently in [allowedFrom] are left untouched. */
    @Query("UPDATE sync_records SET status = :newStatus WHERE mediaStoreId IN (:ids) AND status IN (:allowedFrom)")
    suspend fun transition(ids: List<Long>, newStatus: SyncStatus, allowedFrom: List<SyncStatus>): Int
}
