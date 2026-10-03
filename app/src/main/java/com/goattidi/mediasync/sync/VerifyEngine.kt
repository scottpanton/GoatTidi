package com.goattidi.mediasync.sync

import com.goattidi.mediasync.data.drive.RetryPolicy
import com.goattidi.mediasync.data.repo.SyncStateRepository
import com.goattidi.mediasync.data.storage.StorageBackendProvider
import com.goattidi.mediasync.data.storage.StorageException
import com.goattidi.mediasync.data.storage.withStorageRetry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Verify action (§4.5): batch-checks every record that claims a remote object,
 * updating statuses from what the backend actually reports — never from local assumptions.
 *
 * Everything here is expressed in backend-neutral terms, which is what makes the same pass
 * meaningful for Drive and S3 alike. Where the two genuinely differ, the difference is
 * carried by the data rather than by a branch: S3 objects are never in a trash, so
 * [RemoteObject.trashed] is simply false, and every object in a bucket is owned by whoever
 * holds its credentials, so [RemoteObject.ownedByUser] is simply true.
 */
@Singleton
class VerifyEngine @Inject constructor(
    private val repository: SyncStateRepository,
    private val router: StorageBackendProvider
) {
    data class Summary(
        val checked: Int,
        val confirmedSynced: Int,
        val orphaned: Int,
        val modified: Int,
        val stillUnverified: Int
    )

    suspend fun verifyAll(retryPolicy: RetryPolicy = RetryPolicy()): Summary {
        var confirmed = 0
        var orphaned = 0
        var modified = 0
        var unverified = 0
        val backend = router.backend()
        // Only records whose object belongs to this backend: an id from the other one would
        // describe nothing to the service being asked.
        val candidates = repository.recordsWithRemoteObject(backend.id.name)

        for (record in candidates) {
            val objectId = record.driveFileId ?: continue
            val remote = try {
                withStorageRetry(retryPolicy) { backend.stat(objectId) }
            } catch (e: StorageException.NotFound) {
                repository.markOrphaned(record.mediaStoreId)
                orphaned++
                continue
            }
            if (remote.trashed) {
                repository.markOrphaned(record.mediaStoreId)
                orphaned++
                continue
            }
            val remoteChecksum = remote.checksum
            if (remoteChecksum == null) {
                // The backend cannot attest a checksum yet — cannot be promoted (invariant #1)
                unverified++
                continue
            }
            // What the local bytes must report. The record's cached MD5 is already known
            // current, so this costs nothing beyond what the backend itself has to derive.
            val localMd5 = record.localMd5
            val expected = if (record.md5IsCurrent) {
                backend.storedChecksumForFreshBytes(record, localMd5!!)
            } else {
                null
            }
            when {
                expected == null -> unverified++
                remoteChecksum.equals(expected, ignoreCase = true) -> {
                    repository.confirmSynced(record.mediaStoreId, backend.id.name, remoteChecksum)
                    confirmed++
                }
                else -> {
                    repository.markModifiedSinceUpload(record.mediaStoreId)
                    modified++
                }
            }
        }
        return Summary(candidates.size, confirmed, orphaned, modified, unverified)
    }

    /**
     * Rebuilds the upload ledger from the backend itself: pages through the objects this app
     * is the author of and records their checksums. Restores cross-folder dedup after a
     * reinstall or DB loss. Returns the number of ledger entries imported.
     */
    suspend fun importLedger(retryPolicy: RetryPolicy = RetryPolicy()): Int {
        val backend = router.backend()
        var imported = 0
        var pageToken: String? = null
        val now = System.currentTimeMillis()
        do {
            val page = withStorageRetry(retryPolicy) { backend.listOwnObjects(pageToken) }
            for (entry in page.objects) {
                val checksum = entry.checksum ?: continue // nothing to dedup on
                repository.recordUploaded(
                    backend.id.name, checksum, entry.id, entry.name ?: "", entry.sizeBytes ?: 0L, now
                )
                imported++
            }
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return imported
    }

    /** Counts from a sync scan. [inContainer] is null when the chosen container is gone. */
    data class SyncScanResult(val own: Int, val inContainer: Int?)

    /**
     * Sync scan for a chosen container — how uploads from OTHER tools (rclone, a desktop
     * client, the bucket's web UI) become known duplicates — together with the app's own
     * uploads, in one pass over the bucket.
     *
     * One flat listing of every container supplies the tree, rather than a walk with a
     * request per subcontainer: that walk dominated scan time on real Drives, and the
     * listing that serves it is already being made.
     */
    suspend fun scanOwnUploadsAndContainer(
        containerId: String,
        retryPolicy: RetryPolicy = RetryPolicy()
    ): SyncScanResult {
        val backend = router.backend()
        val container = try {
            withStorageRetry(retryPolicy) { backend.stat(containerId) }
        } catch (e: StorageException.NotFound) {
            null
        }
        // Only "gone" disqualifies the container. Asking whether it looks like a container
        // would be wrong for Drive, which answers the minimal field set asked of it here.
        if (container == null) {
            return SyncScanResult(importLedger(retryPolicy), null)
        }

        val parentsOf = HashMap<String, List<String>>()
        var pageToken: String? = null
        do {
            val page = withStorageRetry(retryPolicy) { backend.listContainers(pageToken) }
            for (entry in page.objects) {
                // Every object names the container it sits in, so one parent link per
                // container is enough to walk the tree in either backend.
                parentsOf[entry.id] = listOfNotNull(entry.parentId)
            }
            pageToken = page.nextPageToken
        } while (pageToken != null)
        val tree = ContainerTree(containerId, parentsOf)

        var own = 0
        var inContainer = 0
        val now = System.currentTimeMillis()
        do {
            val page = withStorageRetry(retryPolicy) { backend.listAllObjects(pageToken) }
            for (entry in page.objects) {
                val checksum = entry.checksum ?: continue // nothing to dedup on
                val mine = entry.ownedByUs
                val inside = entry.ownedByUser && entry.parentId?.let(tree::contains) == true
                if (!mine && !inside) continue
                repository.recordUploaded(
                    backend.id.name, checksum, entry.id, entry.name ?: "", entry.sizeBytes ?: 0L, now
                )
                if (mine) own++
                if (inside) inContainer++
            }
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return SyncScanResult(own, inContainer)
    }

    /**
     * Sync scan for the whole bucket: one flat listing, which also covers this app's
     * uploads.
     */
    suspend fun importEverything(retryPolicy: RetryPolicy = RetryPolicy()): Int {
        val backend = router.backend()
        var imported = 0
        var pageToken: String? = null
        val now = System.currentTimeMillis()
        do {
            val page = withStorageRetry(retryPolicy) { backend.listObjectsOwnedByUser(pageToken) }
            for (entry in page.objects) {
                val checksum = entry.checksum ?: continue
                repository.recordUploaded(
                    backend.id.name, checksum, entry.id, entry.name ?: "", entry.sizeBytes ?: 0L, now
                )
                imported++
            }
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return imported
    }

    /** Whether a container lies inside [rootId]'s tree, from a parent map of every container. */
    private class ContainerTree(private val rootId: String, private val parentsOf: Map<String, List<String>>) {
        private val memo = HashMap<String, Boolean>()

        fun contains(containerId: String): Boolean = check(containerId, HashSet())

        private fun check(id: String, visiting: MutableSet<String>): Boolean {
            if (id == rootId) return true
            memo[id]?.let { return it }
            // A backend's container graph should have no cycles, but never loop on one.
            if (!visiting.add(id)) return false
            val result = parentsOf[id].orEmpty().any { check(it, visiting) }
            memo[id] = result
            return result
        }
    }
}