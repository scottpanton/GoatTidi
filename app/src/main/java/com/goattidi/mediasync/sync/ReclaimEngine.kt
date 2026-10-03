package com.goattidi.mediasync.sync

import com.goattidi.mediasync.data.db.SyncRecord
import com.goattidi.mediasync.data.db.SyncStatus
import com.goattidi.mediasync.data.drive.RetryPolicy
import com.goattidi.mediasync.data.hash.Md5
import com.goattidi.mediasync.data.repo.SyncStateRepository
import com.goattidi.mediasync.data.storage.StorageBackendProvider
import com.goattidi.mediasync.data.storage.StorageException
import com.goattidi.mediasync.data.storage.withStorageRetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Free-up-space support (§4.5). The actual deletion is the UI's job (it must route
 * through MediaStore.createDeleteRequest on API 30+); this engine owns the gate:
 *
 * Invariant #3 — a local file may only be deleted when a FRESH check proves its
 * current bytes are in storage: a freshly computed local MD5 equal to the checksum
 * the backend reports for the stored object. A stale `status == SYNCED` is never
 * sufficient.
 */
@Singleton
class ReclaimEngine @Inject constructor(
    private val repository: SyncStateRepository,
    private val router: StorageBackendProvider,
    private val localFiles: LocalFiles
) {
    sealed class Gate {
        object Safe : Gate()
        data class Blocked(val reason: String) : Gate()
    }

    /** Verified-synced files, biggest first — candidates for the free-up-space view. */
    suspend fun candidates(): List<SyncRecord> = candidatesFrom(repository.syncedRecords())

    // IO dispatcher: hashing streams entire files and must never run on Main
    suspend fun confirmSafeToDelete(
        mediaStoreId: Long,
        retryPolicy: RetryPolicy = RetryPolicy()
    ): Gate = withContext(Dispatchers.IO) {
        confirmSafeToDeleteBlocking(mediaStoreId, retryPolicy)
    }

    private suspend fun confirmSafeToDeleteBlocking(mediaStoreId: Long, retryPolicy: RetryPolicy): Gate {
        val record = repository.getById(mediaStoreId)
            ?: return Gate.Blocked("No sync record for this file")
        if (record.status != SyncStatus.SYNCED || record.driveFileId == null) {
            return Gate.Blocked("File is not verified as synced")
        }
        if (localFiles.stat(record) == null) {
            return Gate.Blocked("Local file is already gone")
        }

        val backend = router.backend()
        // Only the backend that issued the object can say whether it is still there; an id
        // from the other one would describe nothing.
        if (!record.belongsTo(backend.id.name)) {
            return Gate.Blocked("File was not uploaded to ${backend.displayName}")
        }

        // Fresh remote check: the stored copy must still exist with a checksum
        val remote = try {
            withStorageRetry(retryPolicy) { backend.stat(record.driveFileId) }
        } catch (e: StorageException.NotFound) {
            repository.markOrphaned(mediaStoreId)
            return Gate.Blocked("File no longer exists in ${backend.displayName}")
        }
        if (remote.trashed) {
            repository.markOrphaned(mediaStoreId)
            return Gate.Blocked("${backend.displayName} copy is in the trash")
        }
        val remoteChecksum = remote.checksum
            ?: return Gate.Blocked("${backend.displayName} has not produced a checksum for this file")

        // Fresh local check: hash the bytes as they are RIGHT NOW, not the cached value
        val freshLocalMd5 = Md5.of(localFiles.open(record, 0))
        val expected = backend.expectedLocalChecksum(record) ?: freshLocalMd5
        if (!freshLocalMd5.equals(expected, ignoreCase = true) ||
            !expected.equals(remoteChecksum, ignoreCase = true)
        ) {
            repository.markModifiedSinceUpload(mediaStoreId)
            return Gate.Blocked("Local file was modified since upload — current bytes are not stored")
        }
        return Gate.Safe
    }

    companion object {
        /**
         * The free-up-space list from any set of records. The screen derives it from the live
         * record Flow, so it updates the moment a Verify, upload, or delete changes a status.
         */
        fun candidatesFrom(records: List<SyncRecord>): List<SyncRecord> =
            records.filter { it.status == SyncStatus.SYNCED }.sortedByDescending { it.sizeBytes }
    }
}