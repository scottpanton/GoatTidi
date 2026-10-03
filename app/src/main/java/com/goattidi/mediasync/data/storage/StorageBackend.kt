package com.goattidi.mediasync.data.storage

import com.goattidi.mediasync.data.db.SyncRecord
import java.io.InputStream

/** The storage services this app can sync to. */
enum class BackendId {
    DRIVE, S3;

    companion object {
        /** Parses a persisted id, treating anything unknown (and null) as the legacy Drive default. */
        fun parse(value: String?): BackendId =
            entries.firstOrNull { it.name == value } ?: DRIVE
    }
}

/**
 * One object in a backend, in the shape the sync engine actually needs — never a
 * vendor's wire model. A "container" is a Drive folder or an S3 key prefix; both are
 * addressed by an opaque [id] that only the backend can interpret.
 *
 * The defaults describe an S3 object rather than a Drive file, because S3 is the
 * weaker backend: it has no trash and no per-app ownership, so [trashed] is always
 * false and [ownedByUser] is always true. Drive overrides them from DriveFile.
 */
data class RemoteObject(
    val id: String,
    val name: String? = null,
    /** Containing container id, or null at the backend root. */
    val parentId: String? = null,
    /** The backend's content checksum for these bytes, or null when it has none. */
    val checksum: String? = null,
    val sizeBytes: Long? = null,
    val isContainer: Boolean = false,
    /** True when this app is the author of the object. */
    val ownedByUs: Boolean = false,
    /** False when the object exists but its owner is someone else. */
    val ownedByUser: Boolean = true,
    /** True when the object is in the trash rather than genuinely gone. */
    val trashed: Boolean = false
)

/** One page of a listing; [nextPageToken] is opaque and null once the walk is complete. */
data class RemotePage(
    val objects: List<RemoteObject> = emptyList(),
    val nextPageToken: String? = null
)

/**
 * A storage service the app can sync media to.
 *
 * Implementations are selected once per process by [StorageRouter] from the user's
 * settings, so every method below is called only for the backend the user picked.
 * Anything one backend cannot do (account sign-in, a trash check) is expressed as a
 * neutral operation that the weaker backend answers "no" or "nothing" to, rather than
 * leaking a Drive concept into the interface.
 */
interface StorageBackend {

    val id: BackendId

    /** Human-readable name for messages and settings, e.g. "Google Drive" or "S3". */
    val displayName: String

    /** True when connecting the user to an account is part of using this backend. */
    val requiresAccountSignIn: Boolean

    /** Opens the local file positioned at [offset]. Called again after reconnects. */
    fun interface Source {
        fun open(offset: Long): InputStream
    }

    data class UploadRequest(
        val fileName: String,
        val mimeType: String,
        val sizeBytes: Long,
        /** Checksum of the local bytes, as produced by [expectedLocalChecksum]. */
        val localChecksum: String,
        /** Destination container id, or null for the backend root. */
        val destinationId: String? = null,
        /** In-flight session to continue (Drive session URI, S3 multipart upload id). */
        val existingSession: String? = null
    )

    /**
     * What an upload proved about the bytes at the far end.
     *
     * [Verified] is the only state that may mark a record SYNCED (invariant #1): a
     * successful response is never enough on its own. [Unverified] means the backend
     * has accepted the bytes but cannot yet attest a matching checksum, so the record
     * stays in flight and is checked again later.
     */
    sealed class Outcome {
        abstract val objectId: String

        data class Verified(override val objectId: String, val remoteChecksum: String) : Outcome()

        data class ChecksumMismatch(
            override val objectId: String,
            val remoteChecksum: String
        ) : Outcome()

        data class Unverified(override val objectId: String) : Outcome()
    }

    /**
     * Uploads one file, resuming from [UploadRequest.existingSession] when given, and
     * reports what the backend proved about the stored bytes.
     *
     * [onSessionEstablished] fires as soon as a session exists and must be persisted —
     * it is the only thing that lets a killed process continue instead of restarting.
     * [onProgress] fires with server-confirmed bytes, which outranks [onBytesSent].
     */
    suspend fun upload(
        request: UploadRequest,
        source: Source,
        onSessionEstablished: suspend (String) -> Unit = {},
        onProgress: suspend (confirmedBytes: Long) -> Unit = {},
        onBytesSent: ((Long) -> Unit)? = null
    ): Outcome

    /** Re-checks an object whose earlier upload completed but was not yet provable. */
    suspend fun verifyExisting(objectId: String, localChecksum: String): Outcome

    /** Fetches one object, or throws [StorageException.NotFound] when it is gone. */
    suspend fun stat(objectId: String): RemoteObject

    /**
     * The checksum this backend's stored bytes must report for [record]'s local file,
     * or null when the local bytes have no trustworthy checksum yet.
     *
     * Verify and reclaim compare this against the remote checksum. Backends whose
     * stored checksum is the same MD5 the app already cached can answer for free; a
     * backend whose checksum depends on how the bytes were chunked for upload has to
     * re-read the file in the same chunking.
     */
    suspend fun expectedLocalChecksum(record: SyncRecord): String?

    /** Finds-or-creates a container by name, reusing one this app created. */
    suspend fun ensureContainer(name: String, parentId: String? = null): String

    /** Whether the container still exists — a cached id can outlive the container. */
    suspend fun containerExists(id: String): Boolean

    /** Resolves a slash-separated container path ("Media/Videos") to a container id. */
    suspend fun resolveContainerPath(path: String): String?

    /** Pages through the objects this app is the author of, wherever they live now. */
    suspend fun listOwnObjects(pageToken: String? = null): RemotePage

    /**
     * Pages through every object the backend can see, each carrying [RemoteObject.parentId]
     * and ownership, so one pass serves both the app's own uploads and a container's
     * contents (see VerifyEngine.scanOwnUploadsAndFolder).
     */
    suspend fun listAllObjects(pageToken: String? = null): RemotePage

    /**
     * Pages through the objects the user owns. Objects merely shared with the user are
     * left out: their owner can delete them at any time, so they are not stable
     * duplicate candidates.
     */
    suspend fun listObjectsOwnedByUser(pageToken: String? = null): RemotePage

    /**
     * Pages through every container at every depth, each carrying its parent, so a whole
     * tree can be mapped from one call rather than a walk with a request per subcontainer.
     */
    suspend fun listContainers(pageToken: String? = null): RemotePage

    /** Direct children of [parentId] — the container picker, empty when it cannot be listed. */
    suspend fun listChildContainers(parentId: String): RemotePage

    /** An account label for display, e.g. an email or "bucket @ endpoint"; null if unknown. */
    suspend fun identity(): String?
}
