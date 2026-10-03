package com.goattidi.mediasync.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.goattidi.mediasync.data.db.SyncRecord
import com.goattidi.mediasync.data.db.SyncStatus
import com.goattidi.mediasync.data.drive.DriveAuthConsentRequired
import com.goattidi.mediasync.data.hash.Md5
import com.goattidi.mediasync.data.repo.SyncStateRepository
import com.goattidi.mediasync.data.storage.StorageBackend
import com.goattidi.mediasync.data.storage.StorageException
import com.goattidi.mediasync.data.storage.StorageBackendProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Drains the upload queue. Stateless by design: every run derives its work from
 * the DB (invariant #4), so a kill or reboot mid-upload resumes exactly where the
 * persisted records say — stale UPLOADING rows are probed and continued, never
 * restarted blindly.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: SyncStateRepository,
    private val router: StorageBackendProvider,
    private val localFiles: LocalFiles,
    private val destinationResolver: DestinationResolver
) : CoroutineWorker(appContext, workerParams) {

    private enum class ItemResult { DONE, RETRY, ABORT }

    override suspend fun doWork(): Result {
        // Foreground promotion can fail in tests / restricted states; upload anyway
        runCatching { setForeground(getForegroundInfo()) }
        // Resolved once per run: the user's choice does not change mid-drain, and reading
        // settings per item would be wasted work on a long queue.
        val backend = router.backend()
        var retry = false
        for (record in repository.uploadBatch()) {
            when (processOne(record, backend)) {
                ItemResult.DONE -> {}
                ItemResult.RETRY -> retry = true
                ItemResult.ABORT -> return Result.retry()
            }
        }
        return if (retry) Result.retry() else Result.success()
    }

    private suspend fun processOne(stale: SyncRecord, backend: StorageBackend): ItemResult {
        // Re-read: the user may have canceled this item since the batch was fetched
        val record = repository.getById(stale.mediaStoreId) ?: return ItemResult.DONE
        if (record.status != SyncStatus.QUEUED && record.status != SyncStatus.UPLOADING) {
            return ItemResult.DONE
        }
        val stat = localFiles.stat(record)
        if (stat == null) {
            repository.markFailed(record.mediaStoreId, "Local file no longer exists")
            return ItemResult.DONE
        }
        // Mark active before hashing: computing a large file's MD5 takes real time
        // and should show as in-progress, not "waiting"
        repository.markUploading(record.mediaStoreId)
        val prepared = ensureFreshMd5(record, stat)
        if (prepared == null) {
            repository.markFailed(record.mediaStoreId, "File kept changing while computing checksum")
            return ItemResult.DONE
        }
        val (_, md5Stat) = prepared
        // Re-read so the cached MD5 is the one just verified above. Without this the
        // checksum handed to the backend would be derived from a stale copy of the record.
        val current = repository.getById(record.mediaStoreId) ?: return ItemResult.DONE
        val md5 = current.localMd5 ?: return ItemResult.RETRY
        // What the stored bytes must report back. Drive answers from the cached MD5; a
        // backend whose checksum depends on how the file was chunked for upload derives it.
        val expectedChecksum = backend.expectedLocalChecksum(current) ?: md5

        val backendName = backend.displayName
        val outcome = try {
            runUpload(record, backend, expectedChecksum, md5Stat)
        } catch (e: StorageException.StorageQuotaExceeded) {
            repository.requeue(current.mediaStoreId, "$backendName storage is full")
            return ItemResult.ABORT
        } catch (e: StorageException.AuthFailed) {
            repository.requeue(current.mediaStoreId, "$backendName rejected the credentials — check Settings")
            return ItemResult.ABORT
        } catch (e: DriveAuthConsentRequired) {
            repository.requeue(current.mediaStoreId, "Google Drive authorization required")
            return ItemResult.ABORT
        } catch (e: StorageException.RateLimited) {
            repository.requeue(current.mediaStoreId)
            return ItemResult.RETRY
        } catch (e: StorageException.Network) {
            repository.requeue(current.mediaStoreId)
            return ItemResult.RETRY
        } catch (e: StorageException) {
            repository.markFailed(current.mediaStoreId, e.message ?: "$backendName error")
            return ItemResult.DONE
        } catch (e: IOException) {
            repository.requeue(current.mediaStoreId)
            return ItemResult.RETRY
        }

        // Invariant #2: the bytes on Drive must be the bytes we hashed. If the file
        // changed underneath the upload, the result is untrustworthy — discard it.
        val after = localFiles.stat(current)
        if (after != md5Stat) {
            repository.requeueModified(current.mediaStoreId)
            return ItemResult.RETRY
        }

        val backendId = backend.id.name
        when (outcome) {
            is StorageBackend.Outcome.Verified -> {
                repository.markSynced(
                    current.mediaStoreId, backendId, outcome.objectId, outcome.remoteChecksum,
                    System.currentTimeMillis()
                )
                return ItemResult.DONE
            }
            is StorageBackend.Outcome.ChecksumMismatch -> {
                repository.markFailed(
                    current.mediaStoreId,
                    "Checksum mismatch after upload (stored=${outcome.remoteChecksum}) — not marked synced",
                    driveFileId = outcome.objectId
                )
                return ItemResult.DONE
            }
            is StorageBackend.Outcome.Unverified -> {
                // The backend accepted the bytes but cannot yet attest a matching checksum.
                // Stay UPLOADING and retry later; verifyExisting picks it up on the next run.
                repository.markUnverified(current.mediaStoreId, backendId, outcome.objectId)
                return ItemResult.RETRY
            }
        }
    }

    /**
     * Uploads [record], or adopts an identical object already proven in storage.
     * [expectedChecksum] is what the stored bytes must report back, which for a backend
     * whose checksum depends on upload chunking is not the local file's plain MD5.
     */
    private suspend fun runUpload(
        record: SyncRecord,
        backend: StorageBackend,
        expectedChecksum: String,
        md5Stat: LocalFiles.Stat
    ): StorageBackend.Outcome {
        // A stale UPLOADING row with a remote object but no session finished uploading
        // before the process died — only the checksum verification is outstanding.
        if (record.status == SyncStatus.UPLOADING && record.driveFileId != null && record.resumeSessionUri == null) {
            try {
                return backend.verifyExisting(record.driveFileId, expectedChecksum)
            } catch (e: StorageException.NotFound) {
                // The remote copy is gone; fall through and upload it again
            }
        }
        // Content dedup (§4.4): identical bytes already verified in storage — moved,
        // renamed, or duplicated locally — are adopted with one HEAD, never re-uploaded.
        if (record.resumeSessionUri == null) {
            repository.lookupUploaded(backend.id.name, expectedChecksum)?.let { prior ->
                try {
                    val outcome = backend.verifyExisting(prior.driveFileId, expectedChecksum)
                    if (outcome is StorageBackend.Outcome.Verified) return outcome
                    repository.forgetUploaded(backend.id.name, expectedChecksum) // ledger entry no longer trustworthy
                } catch (e: StorageException.NotFound) {
                    repository.forgetUploaded(backend.id.name, expectedChecksum) // remote copy was deleted
                }
            }
        }
        // Socket-level byte counter, persisted by a 500ms poller: progress moves
        // continuously instead of jumping at 8 MB chunk confirmations.
        val sentBytes = AtomicLong(-1)
        return coroutineScope {
            val poller = launch {
                var lastBytes = -1L
                var lastTimeMs = 0L
                var rateBps = 0L
                while (isActive) {
                    delay(500)
                    val sent = sentBytes.get()
                    if (sent < 0) continue
                    val now = System.currentTimeMillis()
                    if (lastBytes in 0..sent && now > lastTimeMs) {
                        val instant = (sent - lastBytes) * 1000 / (now - lastTimeMs)
                        rateBps = if (rateBps == 0L) instant else (rateBps * 7 + instant * 3) / 10
                    }
                    lastBytes = sent
                    lastTimeMs = now
                    repository.updateProgress(record.mediaStoreId, sent, rateBps)
                }
            }
            try {
                backend.upload(
                    StorageBackend.UploadRequest(
                        fileName = record.fileName,
                        mimeType = record.mimeType,
                        sizeBytes = md5Stat.sizeBytes,
                        localChecksum = expectedChecksum,
                        destinationId = destinationResolver.resolveDestinationId(record),
                        existingSession = record.resumeSessionUri,
                        uniqueId = record.mediaStoreId.toString()
                    ),
                    { offset -> localFiles.open(record, offset) },
                    onSessionEstablished = { repository.saveSessionUri(record.mediaStoreId, it) },
                    onProgress = { confirmed ->
                        // Server-confirmed offset is authoritative (resets after reconnect)
                        sentBytes.set(confirmed)
                        setProgress(workDataOf(KEY_MEDIA_ID to record.mediaStoreId, KEY_BYTES to confirmed))
                    },
                    onBytesSent = { sent -> sentBytes.set(sent) }
                )
            } finally {
                poller.cancel()
            }
        }
    }

    /**
     * Returns a trustworthy MD5 with the stat it was computed from. Recomputes when
     * the cached one no longer matches the file's current size/date_modified, and
     * gives up (null) if the file keeps changing underneath us.
     */
    private suspend fun ensureFreshMd5(
        record: SyncRecord,
        initialStat: LocalFiles.Stat
    ): Pair<String, LocalFiles.Stat>? {
        var stat = initialStat
        if (record.localMd5 != null &&
            record.md5SizeBytes == stat.sizeBytes &&
            record.md5DateModified == stat.dateModified
        ) {
            return record.localMd5 to stat
        }
        repeat(3) {
            val md5 = Md5.of(localFiles.open(record, 0))
            val after = localFiles.stat(record) ?: return null
            if (after == stat) {
                repository.cacheMd5(record.mediaStoreId, md5, stat.sizeBytes, stat.dateModified)
                return md5 to stat
            }
            stat = after
        }
        return null
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Upload progress", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Uploading")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_MEDIA_ID = "mediaStoreId"
        const val KEY_BYTES = "bytesConfirmed"
        const val CHANNEL_ID = "sync_progress"
        const val NOTIFICATION_ID = 42
    }
}
