package com.goattidi.mediasync.data.storage

import com.goattidi.mediasync.data.db.SyncRecord
import com.goattidi.mediasync.data.drive.DriveClient
import com.goattidi.mediasync.data.drive.DriveException
import com.goattidi.mediasync.data.drive.DriveFile
import com.goattidi.mediasync.data.drive.DriveFileList
import com.goattidi.mediasync.data.drive.DriveUploader
import java.io.IOException

/**
 * Presents Google Drive through [StorageBackend] by delegating to the existing
 * [DriveClient] and [DriveUploader] unchanged, so Drive's resumable-upload protocol and
 * its error mapping stay owned by the Drive code that already handles them.
 *
 * This adapter is the only place that knows both vocabularies: it turns `DriveFile`
 * into a [RemoteObject] and `DriveException` into a [StorageException], so no caller
 * upstream of it imports anything from `data.drive` except the consent signal —
 * [DriveAuthConsentRequired], which only Drive can raise and the UI must launch a
 * `PendingIntent` for.
 */
class DriveStorageBackend(
    private val client: DriveClient,
    private val uploader: DriveUploader
) : StorageBackend {

    override val id = BackendId.DRIVE
    override val displayName = "Google Drive"
    override val requiresAccountSignIn = true

    override suspend fun upload(
        request: StorageBackend.UploadRequest,
        source: StorageBackend.Source,
        onSessionEstablished: suspend (String) -> Unit,
        onProgress: suspend (Long) -> Unit,
        onBytesSent: ((Long) -> Unit)?
    ): StorageBackend.Outcome = translating {
        when (
            val outcome = uploader.upload(
                DriveUploader.UploadRequest(
                    fileName = request.fileName,
                    mimeType = request.mimeType,
                    sizeBytes = request.sizeBytes,
                    localMd5 = request.localChecksum,
                    parentFolderId = request.destinationId,
                    existingSessionUri = request.existingSession
                ),
                source = { offset -> source.open(offset) },
                onSessionEstablished = onSessionEstablished,
                onProgress = onProgress,
                onBytesSent = onBytesSent
            )
        ) {
            is DriveUploader.Outcome.Verified ->
                StorageBackend.Outcome.Verified(outcome.fileId, outcome.driveMd5)
            is DriveUploader.Outcome.Md5Mismatch ->
                StorageBackend.Outcome.ChecksumMismatch(outcome.fileId, outcome.driveMd5)
            is DriveUploader.Outcome.Unverified ->
                StorageBackend.Outcome.Unverified(outcome.fileId)
        }
    }

    override suspend fun verifyExisting(
        objectId: String,
        localChecksum: String
    ): StorageBackend.Outcome = translating {
        when (val outcome = uploader.verifyExisting(objectId, localChecksum)) {
            is DriveUploader.Outcome.Verified ->
                StorageBackend.Outcome.Verified(outcome.fileId, outcome.driveMd5)
            is DriveUploader.Outcome.Md5Mismatch ->
                StorageBackend.Outcome.ChecksumMismatch(outcome.fileId, outcome.driveMd5)
            is DriveUploader.Outcome.Unverified ->
                StorageBackend.Outcome.Unverified(outcome.fileId)
        }
    }

    /** Drive already cached the MD5 alongside the bytes it describes, so this is free. */
    override suspend fun expectedLocalChecksum(record: SyncRecord): String? = record.localMd5

    override suspend fun stat(objectId: String): RemoteObject =
        translating { client.getFile(objectId) }.toRemoteObject()

    override suspend fun ensureContainer(name: String, parentId: String?): String =
        translating { client.ensureFolder(name, parentId) }

    override suspend fun containerExists(id: String): Boolean = try {
        val folder = client.getFile(id, fields = "id,mimeType,trashed")
        folder.isFolder && !folder.trashed
    } catch (e: DriveException.NotFound) {
        false
    }

    override suspend fun resolveContainerPath(path: String): String? =
        translating { client.resolveFolderPath(path) }

    override suspend fun listOwnObjects(pageToken: String?): RemotePage =
        translating { client.listFiles(pageToken) }.toRemotePage()

    override suspend fun listAllObjects(pageToken: String?): RemotePage =
        translating { client.listAllFiles(pageToken) }.toRemotePage()

    override suspend fun listObjectsOwnedByUser(pageToken: String?): RemotePage =
        translating { client.listOwnedFiles(pageToken) }.toRemotePage()

    override suspend fun listContainers(pageToken: String?): RemotePage =
        translating { client.listAllFolders(pageToken) }.toRemotePage()

    override suspend fun listChildContainers(parentId: String): RemotePage =
        RemotePage(translating { client.listFolders(parentId) }.map { it.toRemoteObject() })

    override suspend fun identity(): String? = translating { client.getUser() }.emailAddress

    private suspend fun <T> translating(block: suspend () -> T): T = try {
        block()
    } catch (e: DriveException) {
        throw e.toStorageException()
    }

    private fun DriveFile.toRemoteObject() = RemoteObject(
        id = id,
        name = name,
        parentId = parents.firstOrNull(),
        checksum = md5Checksum,
        sizeBytes = size?.toLongOrNull(),
        isContainer = isFolder,
        ownedByUs = isAppAuthorized,
        ownedByUser = ownedByMe,
        trashed = trashed
    )

    private fun DriveFileList.toRemotePage() =
        RemotePage(files.map { it.toRemoteObject() }, nextPageToken)
}

private fun DriveException.toStorageException(): StorageException {
    val message = this.message ?: "Google Drive error"
    return when (this) {
        is DriveException.RateLimited -> StorageException.RateLimited(retryAfterSeconds, message)
        is DriveException.StorageQuotaExceeded -> StorageException.StorageQuotaExceeded(message)
        is DriveException.AuthFailed -> StorageException.AuthFailed(message)
        is DriveException.SessionExpired -> StorageException.SessionExpired(message)
        is DriveException.NotFound -> StorageException.NotFound(message)
        is DriveException.ServiceDisabled -> StorageException.ServiceDisabled(message)
        is DriveException.Http -> StorageException.Http(code, message)
        is DriveException.Network ->
            StorageException.Network((cause as? IOException) ?: IOException(message))
    }
}
