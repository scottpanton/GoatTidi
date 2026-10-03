package com.goattidi.mediasync.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class SyncStatus {
    NOT_UPLOADED, QUEUED, UPLOADING, SYNCED,
    MODIFIED_SINCE_UPLOAD, FAILED, ORPHANED
}

enum class MediaType { IMAGE, VIDEO, AUDIO }

/**
 * Source of truth for a media file's sync state (invariant #4: all state lives here).
 *
 * The cached [localMd5] is only trustworthy for the exact bytes it was computed from,
 * so the size and date_modified observed at computation time are snapshotted alongside
 * it ([md5SizeBytes], [md5DateModified]). If MediaStore later reports different values,
 * the MD5 must be discarded (invariant #2).
 *
 * [backendId] records which service issued [driveFileId] and [driveMd5], because an
 * object id only means something together with the backend that issued it — a Drive
 * file id read under S3 describes nothing, and vice versa. It is null on rows written
 * before GoatTidi supported more than one backend, and null reads as Drive, so existing
 * installs keep their synced state without a data rewrite. The "drive" in the column
 * names is historical: they hold whatever the selected backend uses.
 */
@Entity(tableName = "sync_records")
data class SyncRecord(
    @PrimaryKey val mediaStoreId: Long,
    val localUri: String,
    val filePath: String,
    val fileName: String,
    val sizeBytes: Long,
    val mimeType: String,
    val mediaType: MediaType,
    val dateTaken: Long,
    val dateModified: Long,
    /** Playback length in ms for video/audio; 0 = unknown or not applicable. */
    val durationMs: Long = 0,
    val localMd5: String?,
    val md5SizeBytes: Long?,
    val md5DateModified: Long?,
    /** Which backend issued [driveFileId]; null means Drive. */
    val backendId: String? = null,
    val driveFileId: String?,
    val driveMd5: String?,
    val uploadedAt: Long?,
    val status: SyncStatus,
    val failureReason: String?,
    val resumeSessionUri: String?,
    /** Bytes confirmed by the backend for the in-flight upload (progress display). */
    val bytesUploaded: Long = 0,
    /** Smoothed upload speed in bytes/sec while UPLOADING; 0 when idle. */
    val uploadRateBps: Long = 0
) {
    /** True if the cached MD5 still describes the file MediaStore currently reports. */
    val md5IsCurrent: Boolean
        get() = localMd5 != null && md5SizeBytes == sizeBytes && md5DateModified == dateModified

    /** True when this record's remote object belongs to [backend]. */
    fun belongsTo(backend: String): Boolean = (backendId ?: "DRIVE") == backend
}
