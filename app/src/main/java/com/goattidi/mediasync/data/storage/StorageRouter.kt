package com.goattidi.mediasync.data.storage

import com.goattidi.mediasync.data.db.SyncRecord
import com.goattidi.mediasync.data.repo.S3Settings
import com.goattidi.mediasync.data.repo.SyncSettings
import com.goattidi.mediasync.data.repo.SyncStateRepository
import com.goattidi.mediasync.data.storage.s3.AwsCredentials
import com.goattidi.mediasync.data.storage.s3.S3Client
import com.goattidi.mediasync.data.storage.s3.S3Config
import com.goattidi.mediasync.sync.LocalFiles
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stands in for a backend the user selected but has not finished configuring.
 *
 * Every call fails the same way with an actionable message, so no call site has to check
 * whether the backend is usable. A check that could be forgotten is worse than none: it
 * would surface as a crash rather than a hint in Settings.
 */
class UnconfiguredStorageBackend(
    override val id: BackendId,
    override val displayName: String
) : StorageBackend {
    override val requiresAccountSignIn = false

    private fun fail(): Nothing = throw StorageException.ServiceDisabled(
        "$displayName is not set up yet — add the endpoint, bucket and key in Settings"
    )

    override suspend fun upload(
        request: StorageBackend.UploadRequest,
        source: StorageBackend.Source,
        onSessionEstablished: suspend (String) -> Unit,
        onProgress: suspend (Long) -> Unit,
        onBytesSent: ((Long) -> Unit)?
    ): StorageBackend.Outcome = fail()

    override suspend fun verifyExisting(objectId: String, localChecksum: String): StorageBackend.Outcome = fail()
    override suspend fun stat(objectId: String): RemoteObject = fail()
    override suspend fun expectedLocalChecksum(record: SyncRecord): String? = fail()
    override suspend fun ensureContainer(name: String, parentId: String?): String = fail()
    override suspend fun containerExists(id: String): Boolean = fail()
    override suspend fun resolveContainerPath(path: String): String? = fail()
    override suspend fun listOwnObjects(pageToken: String?): RemotePage = fail()
    override suspend fun listAllObjects(pageToken: String?): RemotePage = fail()
    override suspend fun listObjectsOwnedByUser(pageToken: String?): RemotePage = fail()
    override suspend fun listContainers(pageToken: String?): RemotePage = fail()
    override suspend fun listChildContainers(parentId: String): RemotePage = fail()
    override suspend fun identity(): String? = fail()
}

/**
 * Supplies whichever backend the user has selected.
 *
 * The sync engine depends on this rather than on StorageRouter so it can be handed a
 * fixed backend in tests — which is also the only way to drive the Drive adapter through
 * the real verify and reclaim paths.
 */
fun interface StorageBackendProvider {
    suspend fun backend(): StorageBackend
}

/**
 * Hands out the backend the user selected, rebuilding it only when its settings change.
 *
 * S3's endpoint and credentials are user-editable at runtime, so its backend is assembled
 * from settings rather than injected. Drive needs no such treatment and is injected
 * directly.
 *
 * Selecting S3 also clears remote state that Drive issued, so a Drive file id is never
 * offered to S3 as though it described an object there. The bytes still have to be sent to
 * whichever backend is now selected; this only stops a record claiming they are already
 * there.
 */
@Singleton
class StorageRouter @Inject constructor(
    private val settings: SyncSettings,
    private val repository: SyncStateRepository,
    private val driveBackend: DriveStorageBackend,
    private val httpClient: OkHttpClient,
    private val localFiles: LocalFiles
) : StorageBackendProvider {
    private data class Cached(val fingerprint: S3Settings, val backend: StorageBackend)

    @Volatile
    private var cached: Cached? = null

    override suspend fun backend(): StorageBackend {
        val selected = settings.backendId.first()
        if (selected == BackendId.DRIVE) return driveBackend

        val s3 = settings.s3Settings.first()
        if (!s3.isComplete) return UnconfiguredStorageBackend(selected, "S3")
        cached?.let { if (it.fingerprint == s3) return it.backend }

        val config = S3Config(s3.endpoint, s3.region, s3.bucket, s3.pathStyle)
        val backend = S3StorageBackend(
            config = config,
            client = S3Client(
                config = config,
                credentials = AwsCredentials(
                    accessKeyId = s3.accessKeyId,
                    secretAccessKey = s3.secretAccessKey,
                    sessionToken = s3.sessionToken.takeIf { it.isNotBlank() }
                ),
                httpClient = httpClient
            ),
            localFiles = localFiles,
            destinationRoot = { destinationRootPrefix() }
        )
        cached = Cached(s3, backend)
        repository.clearRemoteStateForOtherBackends(selected.name)
        return backend
    }

    /**
     * The configured destination as a key prefix, e.g. `GoatTidi_Galaxy S23/`. Null until a
     * destination has been chosen, which makes listings cover the whole bucket.
     */
    private suspend fun destinationRootPrefix(): String? {
        val name = settings.folderName.first().trim()
        return name.takeIf { it.isNotEmpty() }?.let { "$it/" }
    }
}