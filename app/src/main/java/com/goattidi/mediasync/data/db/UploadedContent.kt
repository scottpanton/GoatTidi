package com.goattidi.mediasync.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert

/**
 * Upload ledger: every verified upload is recorded here by content hash, and the
 * entry OUTLIVES the media record (which mirrors the device and is dropped when a
 * file moves or disappears locally). Before uploading, the worker checks this
 * ledger — identical bytes already proven in storage are adopted, not re-uploaded
 * (content dedup, spec §4.4). This is what makes locally-moved or renamed files
 * flip straight back to SYNCED instead of re-uploading gigabytes.
 *
 * Keyed by backend as well as hash: an object id only means something together with
 * the service that issued it, and the same bytes legitimately live in both backends
 * once a user has switched between them.
 */
@Entity(tableName = "uploaded_content", primaryKeys = ["backendId", "md5"])
data class UploadedContent(
    val backendId: String,
    val md5: String,
    val driveFileId: String,
    val fileName: String,
    val sizeBytes: Long,
    val uploadedAt: Long
)

@Dao
interface UploadedContentDao {

    @Upsert
    suspend fun upsert(entry: UploadedContent)

    @Query("SELECT * FROM uploaded_content WHERE backendId = :backendId AND md5 = :md5")
    suspend fun getByChecksum(backendId: String, md5: String): UploadedContent?

    @Query("DELETE FROM uploaded_content WHERE backendId = :backendId AND md5 = :md5")
    suspend fun deleteByChecksum(backendId: String, md5: String)

    @Query("SELECT COUNT(*) FROM uploaded_content WHERE backendId = :backendId")
    suspend fun count(backendId: String): Int
}
