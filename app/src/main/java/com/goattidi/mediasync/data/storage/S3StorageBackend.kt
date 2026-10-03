package com.goattidi.mediasync.data.storage

import com.goattidi.mediasync.data.db.SyncRecord
import com.goattidi.mediasync.data.storage.s3.S3Checksum
import com.goattidi.mediasync.data.storage.s3.S3Client
import com.goattidi.mediasync.data.storage.s3.S3Config
import com.goattidi.mediasync.data.storage.s3.S3ListResult
import com.goattidi.mediasync.data.storage.s3.S3ObjectEntry
import com.goattidi.mediasync.data.storage.s3.S3ObjectStat
import com.goattidi.mediasync.data.storage.s3.S3UploadedPart
import com.goattidi.mediasync.sync.LocalFiles
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * S3-compatible storage as a [StorageBackend].
 *
 * S3 differs from Drive in three ways that shape this class:
 *
 * - **No folders.** A "container" is a key prefix, so creating one is composing a string
 *   rather than a round-trip, and a listing's user metadata is not returned — so this app's
 *   own uploads are recognised by sitting under the destination root rather than by a
 *   per-file ownership flag.
 * - **Object keys must be unique.** Two local files named `IMG_0001.jpg` would target one
 *   key, which Drive allows. Keys therefore carry [UploadRequest.uniqueId], the only way to
 *   keep both without a collision check a retry could get wrong.
 * - **The stored checksum is an ETag, not an MD5** — see [S3Checksum].
 *
 * Verification never trusts a successful response. [Outcome.Verified] is returned only when
 * the service's ETag is exactly the value computed from the local bytes; an ETag whose
 * shape is not recognised yields [Outcome.Unverified], leaving the record in flight rather
 * than claiming a proof that was never made.
 */
class S3StorageBackend(
    private val config: S3Config,
    private val client: S3Client,
    private val localFiles: LocalFiles,
    /** The configured destination root, e.g. `GoatTidi_Galaxy S23/`. Null until configured. */
    private val destinationRoot: suspend () -> String?,
    private val partSizeBytes: Int = S3Checksum.PART_SIZE_BYTES
) : StorageBackend {

    override val id = BackendId.S3
    override val displayName = "S3"
    override val requiresAccountSignIn = false

    /**
     * What the stored checksum must be for these local bytes, or null when the local bytes
     * have no trustworthy checksum yet.
     *
     * A single-request PUT's ETag is the whole-content MD5, which the app already has
     * cached, so small files cost nothing. A multipart ETag depends on the part boundaries,
     * so reproducing it costs one streaming pass — never on the upload path, where the
     * composite falls out of the parts being sent.
     */
    override suspend fun expectedLocalChecksum(record: SyncRecord): String? = when {
        !record.md5IsCurrent -> null
        record.sizeBytes <= partSizeBytes -> record.localMd5
        else -> compositeEtagOf(record)
    }

    /**
     * A single-request PUT's ETag is the file's MD5, so the fresh hash answers it. A
     * multipart object's ETag is an MD5 of per-part MD5s, which has to be recomputed from
     * the file — without this, a large upload could never pass the free-up-space gate,
     * because its stored checksum is not the file's MD5.
     */
    override suspend fun storedChecksumForFreshBytes(
        record: SyncRecord,
        wholeFileMd5: String
    ): String? = when {
        record.sizeBytes <= partSizeBytes -> wholeFileMd5
        else -> compositeEtagOf(record)
    }

    override suspend fun upload(
        request: StorageBackend.UploadRequest,
        source: StorageBackend.Source,
        onSessionEstablished: suspend (String) -> Unit,
        onProgress: suspend (Long) -> Unit,
        onBytesSent: ((Long) -> Unit)?
    ): StorageBackend.Outcome {
        val key = objectKey(request)
        if (request.sizeBytes <= partSizeBytes) {
            val data = readFully(source.open(0), request.sizeBytes)
            onProgress(data.size.toLong())
            val etag = client.putObject(
                key = key,
                data = data,
                contentType = request.mimeType,
                metadata = uploadMarker(),
                onBytesSent = onBytesSent
            )
            // A single-request PUT's ETag is the MD5 of exactly these bytes, so this
            // compares against the checksum the caller already holds.
            return outcomeFor(etag, request.localChecksum, key)
        }
        return uploadMultipart(key, request, source, onSessionEstablished, onProgress, onBytesSent)
    }

    override suspend fun verifyExisting(
        objectId: String,
        localChecksum: String
    ): StorageBackend.Outcome {
        val stat = client.headObject(objectId)
            ?: throw StorageException.NotFound("No S3 object at $objectId")
        return outcomeFor(stat.etag, localChecksum, objectId)
    }

    override suspend fun stat(objectId: String): RemoteObject {
        val stat = client.headObject(objectId)
            ?: throw StorageException.NotFound("No S3 object at $objectId")
        return stat.toRemoteObject()
    }

    /** Prefixes are created lazily, so this is string composition rather than a call. */
    override suspend fun ensureContainer(name: String, parentId: String?): String =
        (parentId?.takeIf { it.isNotBlank() }?.ensureTrailingSlash().orEmpty()) +
            sanitize(name) + "/"

    /**
     * A prefix "exists" as soon as the bucket answers for it. Confirming that here turns a
     * misspelled bucket name into a clear failure instead of a failed upload.
     */
    override suspend fun containerExists(id: String): Boolean = try {
        client.listObjects(prefix = id, maxKeys = 1)
        true
    } catch (e: StorageException.NotFound) {
        false
    }

    /**
     * A prefix is a path, so this composes rather than looks up. Drive has to walk the
     * folder tree here because a folder name is not an address; an S3 prefix is one.
     */
    override suspend fun resolveContainerPath(path: String): String =
        path.trim('/').split('/').filter { it.isNotEmpty() }
            .joinToString(separator = "/", postfix = "/")

    override suspend fun listOwnObjects(pageToken: String?): RemotePage {
        val root = destinationRoot()
        val page = client.listObjects(prefix = root, continuationToken = pageToken)
        return page.toRemotePage(ourRoot = root, everythingUnderRootIsOurs = true)
    }

    override suspend fun listAllObjects(pageToken: String?): RemotePage {
        val root = destinationRoot()
        val page = client.listObjects(continuationToken = pageToken)
        return page.toRemotePage(ourRoot = root, everythingUnderRootIsOurs = false)
    }

    /** Everything in the bucket belongs to whoever holds its credentials. */
    override suspend fun listObjectsOwnedByUser(pageToken: String?): RemotePage =
        client.listObjects(continuationToken = pageToken)
            .toRemotePage(ourRoot = destinationRoot(), everythingUnderRootIsOurs = false)

    override suspend fun listContainers(pageToken: String?): RemotePage {
        val root = destinationRoot()
        val page = client.listObjects(delimiter = "/", continuationToken = pageToken)
        return RemotePage(page.prefixes.map { it.toContainer(root) }, page.nextContinuationToken)
    }

    override suspend fun listChildContainers(parentId: String): RemotePage {
        val root = destinationRoot()
        val page = client.listObjects(prefix = parentId.ensureTrailingSlash(), delimiter = "/")
        return RemotePage(page.prefixes.map { it.toContainer(root) })
    }

    /** There is no account to sign into; the bucket and endpoint are the identity. */
    override suspend fun identity(): String = "${config.bucket} @ ${config.baseUrl}"

    private suspend fun uploadMultipart(
        key: String,
        request: StorageBackend.UploadRequest,
        source: StorageBackend.Source,
        onSessionEstablished: suspend (String) -> Unit,
        onProgress: suspend (Long) -> Unit,
        onBytesSent: ((Long) -> Unit)?
    ): StorageBackend.Outcome {
        var session = request.existingSession
        var restartsLeft = 1
        while (true) {
            // Resume by asking the service what it holds rather than trusting a stored
            // offset: a kill between "part stored" and "offset saved" would leave that
            // short. Boundaries are fixed, so the stored parts are always a prefix.
            var stored: List<S3UploadedPart> = emptyList()
            if (session != null) {
                stored = try {
                    client.listParts(key, session).contiguousPrefix()
                } catch (e: StorageException.SessionExpired) {
                    session = null
                    emptyList()
                } catch (e: StorageException.NotFound) {
                    session = null
                    emptyList()
                }
            }

            val partDigests = stored.map { it.etag }.toMutableList()
var partNumber = stored.size + 1
            var offset = stored.size.toLong() * partSizeBytes
            val alreadyUploaded = stored

            if (session == null) {
                session = client.createMultipartUpload(key, request.mimeType, uploadMarker())
                onSessionEstablished(session)
                partDigests.clear()
                partNumber = 1
                offset = 0
            } else {
                onProgress(offset)
            }

            val etag = try {
                pushParts(
                    key, session, request, source, partNumber, offset,
                    alreadyUploaded, partDigests, onProgress, onBytesSent
                )
            } catch (e: StorageException.SessionExpired) {
            // The service forgot the upload id. Starting a new one means sending the
            // bytes again, so it is allowed once and only once.
            if (restartsLeft-- <= 0) throw e
            request.existingSession?.let { failed -> runCatching { client.abortMultipartUpload(key, failed) } }
            session = null
            continue
        }

            // The expected composite comes from the parts we sent or were already stored,
            // so it costs nothing extra: an individual part's ETag is its own MD5.
            return outcomeFor(etag, S3Checksum.expectedMultipartEtagFromPartDigests(partDigests), key)
        }
    }

    private suspend fun pushParts(
        key: String,
        session: String,
        request: StorageBackend.UploadRequest,
        source: StorageBackend.Source,
        firstPartNumber: Int,
        startOffset: Long,
        alreadyUploaded: List<S3UploadedPart>,
        partDigests: MutableList<String>,
        onProgress: suspend (Long) -> Unit,
        onBytesSent: ((Long) -> Unit)?
    ): String? {
        // The completion body has to name every part, including the ones a previous run
        // already stored, or the service assembles only part of the object.
        val uploaded = alreadyUploaded.toMutableList()
        var partNumber = firstPartNumber
        var offset = startOffset
        source.open(startOffset).use { stream ->
            while (offset < request.sizeBytes) {
                val partBytes = minOf(partSizeBytes.toLong(), request.sizeBytes - offset).toInt()
                val data = readFully(stream, partBytes.toLong())
                uploaded += client.uploadPart(
                    key = key,
                    uploadId = session,
                    partNumber = partNumber,
                    data = data,
                    baseOffset = offset,
                    onBytesSent = onBytesSent
                )
                partDigests += md5Hex(data)
                offset += partBytes
                onProgress(offset)
                partNumber++
            }
        }
        return client.completeMultipartUpload(key, session, uploaded)
    }

    /**
     * The single place a stored checksum becomes a verdict.
     *
     * Only an exact match is proof. A recognised-but-different ETag is a mismatch;
     * an unrecognised one is [Outcome.Unverified], so a gateway that does not return
     * content hashes leaves records in flight instead of marking them synced on a shape
     * the app cannot interpret.
     */
    private fun outcomeFor(
        remoteEtag: String?,
        expected: String,
        objectId: String
    ): StorageBackend.Outcome {
        val etag = remoteEtag?.trim()?.removeSurrounding("\"", "\"")
        if (etag == null || !S3Checksum.recognised(etag)) return StorageBackend.Outcome.Unverified(objectId)
        return if (etag.equals(expected, ignoreCase = true)) {
            StorageBackend.Outcome.Verified(objectId, etag)
        } else {
            StorageBackend.Outcome.ChecksumMismatch(objectId, etag)
        }
    }

    private suspend fun compositeEtagOf(record: SyncRecord): String? = try {
        localFiles.open(record, 0).use {
            S3Checksum.expectedMultipartEtag(it, record.sizeBytes, partSizeBytes)
        }
    } catch (e: IOException) {
        null
    }

    /**
     * The key an upload lands on. The unique id is not decoration: without it two local
     * files sharing a name in the same layout would target one key, and S3 cannot hold both.
     */
    private fun objectKey(request: StorageBackend.UploadRequest): String {
        val prefix = request.destinationId?.takeIf { it.isNotBlank() }?.ensureTrailingSlash().orEmpty()
        val safeName = sanitize(request.fileName)
        val unique = request.uniqueId?.takeIf { it.isNotBlank() }
        return prefix + if (unique == null) safeName else "${unique}_$safeName"
    }

    /**
     * Strips what would change a key's shape: the "/" separator, and the "." / ".."
     * segments that RFC 3986 normalization resolves away.
     */
    private fun sanitize(name: String): String {
        val cleaned = name.trim().replace('/', '_')
        return cleaned.takeIf { it.isNotEmpty() && it != "." && it != ".." } ?: "file"
    }

    private fun String.ensureTrailingSlash(): String = if (endsWith("/")) this else "$this/"

    /** `a/b/` -> `a/`; a top-level prefix has no parent. */
    private fun String.parentPrefix(): String? {
        val trimmed = trimEnd('/')
        if (trimmed.isEmpty()) return null
        val lastSlash = trimmed.lastIndexOf('/')
        return if (lastSlash < 0) null else trimmed.substring(0, lastSlash).ensureTrailingSlash()
    }

    private fun uploadMarker(): Map<String, String> = mapOf(UPLOAD_MARKER to "1")

    private fun isOurs(id: String, ourRoot: String?): Boolean =
        ourRoot != null && (id == ourRoot || id.startsWith(ourRoot))

    private fun S3ListResult.toRemotePage(
        ourRoot: String?,
        everythingUnderRootIsOurs: Boolean
    ): RemotePage = RemotePage(
        objects = objects.map { entry ->
            val object_ = entry.toRemoteObject()
            if (everythingUnderRootIsOurs) object_.copy(ownedByUs = true)
            else object_.copy(ownedByUs = isOurs(object_.id, ourRoot))
        },
        nextPageToken = nextContinuationToken
    )

    private fun String.toContainer(ourRoot: String?): RemoteObject {
        val id = ensureTrailingSlash()
        return RemoteObject(
            id = id,
            name = id.trimEnd('/').substringAfterLast('/').ifEmpty { id },
            parentId = id.parentPrefix(),
            isContainer = true,
            ownedByUs = isOurs(id, ourRoot),
            ownedByUser = true
        )
    }

    private fun S3ObjectEntry.toRemoteObject() = RemoteObject(
        id = key,
        name = key.substringAfterLast('/').ifEmpty { key },
        parentId = key.parentPrefix(),
        checksum = etag,
        sizeBytes = sizeBytes,
        isContainer = false,
        // Listings do not carry user metadata, so authorship is inferred from the key
        // sitting under this app's destination root, which is where every upload lands.
        ownedByUs = false,
        ownedByUser = true,
        trashed = false
    )

    private suspend fun S3ObjectStat.toRemoteObject() = RemoteObject(
        id = key,
        name = key.substringAfterLast('/').ifEmpty { key },
        parentId = key.parentPrefix(),
        checksum = etag,
        sizeBytes = sizeBytes,
        isContainer = false,
        ownedByUs = isOurs(key, destinationRoot()),
        ownedByUser = true,
        trashed = false
    )

    /** Parts 1..k with none missing, so a resume can start at k+1. */
    private fun List<S3UploadedPart>.contiguousPrefix(): List<S3UploadedPart> {
        val byNumber = associateBy { it.partNumber }
        val result = mutableListOf<S3UploadedPart>()
        var next = 1
        while (byNumber.containsKey(next)) {
            result += byNumber.getValue(next)
            next++
        }
        return result
    }

    private fun readFully(input: InputStream, expected: Long): ByteArray {
        val target = ByteArray(expected.toInt())
        var read = 0
        while (read < target.size) {
            val n = input.read(target, read, target.size - read)
            if (n < 0) throw IOException("Unexpected EOF at $read/${target.size}")
            read += n
        }
        return target
    }

    private fun md5Hex(data: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    companion object {
        /** User metadata stamped on every object this app writes. */
        const val UPLOAD_MARKER = "goattidi-upload"
    }
}
