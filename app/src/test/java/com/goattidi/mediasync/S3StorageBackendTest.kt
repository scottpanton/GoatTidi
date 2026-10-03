package com.goattidi.mediasync

import com.goattidi.mediasync.data.db.MediaType
import com.goattidi.mediasync.data.db.SyncRecord
import com.goattidi.mediasync.data.db.SyncStatus
import com.goattidi.mediasync.data.storage.BackendId
import com.goattidi.mediasync.data.storage.S3StorageBackend
import com.goattidi.mediasync.data.storage.StorageBackend
import com.goattidi.mediasync.data.storage.StorageException
import com.goattidi.mediasync.data.storage.s3.AwsCredentials
import com.goattidi.mediasync.data.storage.s3.S3Checksum
import com.goattidi.mediasync.data.storage.s3.S3Client
import com.goattidi.mediasync.data.storage.s3.S3Config
import com.goattidi.mediasync.sync.LocalFiles
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

class S3StorageBackendTest {

    private lateinit var server: MockWebServer
    private lateinit var backend: S3StorageBackend

    private val partSize = 1024
    private val credentials = AwsCredentials("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY")

    /** Small enough that one PUT covers it, so the ETag is the plain whole-content MD5. */
    private val smallBytes = "the quick brown fox".toByteArray()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        backend = buildBackend(FakeLocalFiles(smallBytes))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun buildBackend(localFiles: LocalFiles): S3StorageBackend {
        val config = S3Config(server.url("/").toString(), "us-east-1", "goattidi", true)
        return S3StorageBackend(
            config = config,
            client = S3Client(config, credentials, OkHttpClient()),
            localFiles = localFiles,
            destinationRoot = { "GoatTidi_Phone/" },
            partSizeBytes = partSize
        )
    }

    @Test
    fun `identifies itself as S3 and needs no account sign-in`() {
        assertEquals(BackendId.S3, backend.id)
        assertFalse(backend.requiresAccountSignIn)
    }

    @Test
    fun `a small file is verified when the returned etag is its md5`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"${md5Hex(smallBytes)}\""))

        val outcome = upload(smallBytes)

        assertEquals(StorageBackend.Outcome.Verified::class, outcome::class)
        assertEquals(md5Hex(smallBytes), (outcome as StorageBackend.Outcome.Verified).remoteChecksum)
    }

    @Test
    fun `a small file is never marked synced on an etag that is not its md5`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"deadbeefdeadbeefdeadbeefdeadbeef\""))

        assertEquals(StorageBackend.Outcome.ChecksumMismatch::class, upload(smallBytes)::class)
    }

    @Test
    fun `an etag shape that is not a content hash leaves the record unverified`() = runTest {
        // Some gateways return a non-MD5 ETag. That proves nothing, so the record must stay
        // in flight rather than be marked synced on a value we cannot interpret.
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"opaque-gateway-value\""))

        assertEquals(StorageBackend.Outcome.Unverified::class, upload(smallBytes)::class)
    }

    @Test
    fun `uploads land under the destination prefix and are stamped as ours`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"${md5Hex(smallBytes)}\""))

        upload(smallBytes, destinationId = "GoatTidi_Phone/2026-07/")

        val sent = server.takeRequest()
        assertEquals("/goattidi/GoatTidi_Phone/2026-07/1234_IMG_0001.jpg", sent.path)
        assertEquals("1", sent.getHeader("x-amz-meta-goattidi-upload"))
    }

    @Test
    fun `the unique id keeps two same-named files from sharing a key`() = runTest {
        repeat(2) {
            server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"${md5Hex(smallBytes)}\""))
        }

        upload(smallBytes, uniqueId = "1")
        upload(smallBytes, uniqueId = "2")

        assertEquals("/goattidi/GoatTidi_Phone/1_IMG_0001.jpg", server.takeRequest().path)
        assertEquals("/goattidi/GoatTidi_Phone/2_IMG_0001.jpg", server.takeRequest().path)
    }

    @Test
    fun `a file name cannot escape its prefix or change the key's shape`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"${md5Hex(smallBytes)}\""))

        upload(smallBytes, fileName = "../../etc/passwd")

        val key = server.takeRequest().path!!.removePrefix("/goattidi/")
        assertEquals("GoatTidi_Phone/1234_.._.._etc_passwd", key)
        // Separators are gone, so the remaining ".." are ordinary characters in a name and
        // cannot traverse out of the prefix the way an unescaped name could.
        assertEquals("GoatTidi_Phone/1234_.._.._etc_passwd".count { it == '/' } + 1, key.split('/').size)
    }

    @Test
    fun `a large file is uploaded in parts and verified against the composite etag`() = runTest {
        val bytes = patternedBytes(partSize * 2 + 100)
        enqueueMultipartUpload(compositeEtagOf(bytes), bytes, startAtPart = 1)

        val outcome = upload(bytes)

        assertEquals(StorageBackend.Outcome.Verified::class, outcome::class)
        assertEquals(compositeEtagOf(bytes), (outcome as StorageBackend.Outcome.Verified).remoteChecksum)
    }

    @Test
    fun `a large file whose composite etag differs is a mismatch, never synced`() = runTest {
        val bytes = patternedBytes(partSize * 2 + 100)
        enqueueMultipartUpload(compositeEtagOf(ByteArray(bytes.size) { 9 }), bytes, startAtPart = 1)

        assertEquals(StorageBackend.Outcome.ChecksumMismatch::class, upload(bytes)::class)
    }

    @Test
    fun `an interrupted upload resumes from the parts the service already holds`() = runTest {
        val bytes = patternedBytes(partSize * 3)
        enqueueMultipartUpload(compositeEtagOf(bytes), bytes, startAtPart = 2, resumeFrom = 1)

        val sessions = mutableListOf<String>()
        val outcome = backend.upload(
            request(bytes, existingSession = "UP1"),
            sourceOf(bytes),
            onSessionEstablished = { sessions += it }
        )

        assertEquals(StorageBackend.Outcome.Verified::class, outcome::class)
        assertTrue("an existing session should be reused, not replaced: $sessions", sessions.isEmpty())
    }

    @Test
    fun `an upload id the service has forgotten restarts only within its budget`() = runTest {
        val bytes = patternedBytes(partSize * 2)
        // Every attempt is told the session is gone, so the restart budget must run out
        // rather than retrying for ever.
        repeat(8) {
            server.enqueue(
                MockResponse().setResponseCode(404).setBody("<Error><Code>NoSuchUpload</Code></Error>")
            )
        }

        val failure = runCatching {
            backend.upload(request(bytes, existingSession = "STALE"), sourceOf(bytes), onSessionEstablished = { })
        }.exceptionOrNull()

        assertTrue(
            "expected the restart budget to be exhausted but got $failure",
            failure is StorageException.SessionExpired
        )
    }

    @Test
    fun `verifyExisting proves a stored object and reports a missing one as gone`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"${md5Hex(smallBytes)}\"")
        )
        assertEquals(
            StorageBackend.Outcome.Verified::class,
            backend.verifyExisting("GoatTidi_Phone/a.jpg", md5Hex(smallBytes))::class
        )

        server.enqueue(MockResponse().setResponseCode(404).setBody("<Error><Code>NoSuchKey</Code></Error>"))
        assertEquals(
            StorageException.NotFound::class,
            runCatching { backend.verifyExisting("GoatTidi_Phone/gone.jpg", md5Hex(smallBytes)) }
                .exceptionOrNull()!!::class
        )
    }

    @Test
    fun `the expected checksum is free for a single put and costs a pass for multipart`() = runTest {
        val reads = AtomicInteger()
        val s3 = buildBackend(FakeLocalReads(reads))

        assertEquals("abc", s3.expectedLocalChecksum(record(size = 500, md5 = "abc")))
        assertEquals("a cached MD5 should be reused for a single-request put", 0, reads.get())

        val composite = s3.expectedLocalChecksum(record(size = partSize + 1, md5 = "abc"))
        assertEquals("multipart needs the composite, so the file must be read", 1, reads.get())
        assertTrue("expected a composite etag but got $composite", composite!!.endsWith("-2"))
    }

    @Test
    fun `no trustworthy local checksum means no expected checksum`() = runTest {
        assertNull(backend.expectedLocalChecksum(record(size = 500, md5 = null)))
    }

    @Test
    fun `containers are prefixes composed from settings rather than created remotely`() = runTest {
        assertEquals("GoatTidi_Phone/2026-07/", backend.ensureContainer("2026-07", "GoatTidi_Phone/"))
        assertEquals("Camera/", backend.ensureContainer("Camera", null))
        assertEquals("a/b/", backend.resolveContainerPath("a/b"))
    }

    @Test
    fun `listings report a parent prefix and separate our keys from other people's`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                <ListBucketResult>
                  <IsTruncated>false</IsTruncated>
                  <Contents><Key>GoatTidi_Phone/2026-07/a.jpg</Key><Size>10</Size><ETag>"e1"</ETag></Contents>
                  <Contents><Key>other/b.jpg</Key><Size>10</Size><ETag>"e2"</ETag></Contents>
                </ListBucketResult>
                """.trimIndent()
            )
        )

        val page = backend.listAllObjects()
        val ours = page.objects.first { it.id.startsWith("GoatTidi_Phone") }
        val theirs = page.objects.first { it.id.startsWith("other") }

        // The immediate containing prefix, which is what a verify walks a tree with.
        assertEquals("GoatTidi_Phone/2026-07/", ours.parentId)
        assertEquals("other/", theirs.parentId)
        assertTrue("our own upload should be recognised", ours.ownedByUs)
        assertFalse("someone else's key must not be claimed", theirs.ownedByUs)
    }

    @Test
    fun `common prefixes become containers with their parent chain`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                <ListBucketResult>
                  <IsTruncated>false</IsTruncated>
                  <CommonPrefixes><Prefix>GoatTidi_Phone/2026-07/</Prefix></CommonPrefixes>
                  <CommonPrefixes><Prefix>other/</Prefix></CommonPrefixes>
                </ListBucketResult>
                """.trimIndent()
            )
        )

        val byId = backend.listContainers().objects.associateBy { it.id }

        assertEquals(setOf("GoatTidi_Phone/2026-07/", "other/"), byId.keys)
        assertEquals("2026-07", byId.getValue("GoatTidi_Phone/2026-07/").name)
        assertEquals("GoatTidi_Phone/", byId.getValue("GoatTidi_Phone/2026-07/").parentId)
        assertNull(byId.getValue("other/").parentId)
        assertTrue(byId.getValue("GoatTidi_Phone/2026-07/").isContainer)
    }

    @Test
    fun `identity names the bucket and endpoint since there is no account`() = runTest {
        assertTrue(backend.identity().startsWith("goattidi @ "))
    }

    // ---- helpers ----

    private suspend fun upload(
        bytes: ByteArray,
        fileName: String = "IMG_0001.jpg",
        destinationId: String? = "GoatTidi_Phone/",
        uniqueId: String = "1234"
    ): StorageBackend.Outcome =
        backend.upload(request(bytes, fileName, destinationId, uniqueId = uniqueId), sourceOf(bytes))

    private fun request(
        bytes: ByteArray,
        fileName: String = "IMG_0001.jpg",
        destinationId: String? = "GoatTidi_Phone/",
        existingSession: String? = null,
        uniqueId: String = "1234"
    ) = StorageBackend.UploadRequest(
        fileName = fileName,
        mimeType = "image/jpeg",
        sizeBytes = bytes.size.toLong(),
        localChecksum = md5Hex(bytes),
        destinationId = destinationId,
        existingSession = existingSession,
        uniqueId = uniqueId
    )

    private fun sourceOf(bytes: ByteArray) = StorageBackend.Source { offset ->
        val stream = bytes.inputStream()
        if (offset > 0) stream.skip(offset)
        stream
    }

    /**
     * Queues the responses one multipart upload produces: the ListParts reply when
     * resuming, otherwise the session, then one response per remaining part, then the
     * completion.
     */
    private fun enqueueMultipartUpload(
        completedEtag: String,
        bytes: ByteArray,
        startAtPart: Int,
        resumeFrom: Int? = null
    ) {
        if (resumeFrom != null) {
            val parts = (1..resumeFrom).joinToString("") { part ->
                val from = (part - 1) * partSize
                val to = minOf(from + partSize, bytes.size)
                "<Part><PartNumber>$part</PartNumber><ETag>\"${md5Hex(bytes.copyOfRange(from, to))}\"</ETag></Part>"
            }
            server.enqueue(MockResponse().setResponseCode(200).setBody("<ListPartsResult>$parts</ListPartsResult>"))
        } else {
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("<InitiateMultipartUploadResult><UploadId>UP1</UploadId></InitiateMultipartUploadResult>")
            )
        }
        val partCount = (bytes.size + partSize - 1) / partSize
        for (part in startAtPart..partCount) {
            val from = (part - 1) * partSize
            val to = minOf(from + partSize, bytes.size)
            server.enqueue(
                MockResponse().setResponseCode(200).setHeader("ETag", "\"${md5Hex(bytes.copyOfRange(from, to))}\"")
            )
        }
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"$completedEtag\""))
    }

    private fun record(size: Int, md5: String?) = SyncRecord(
        mediaStoreId = 1234,
        localUri = "content://media/1",
        filePath = "/storage/emulated/0/DCIM/IMG_0001.jpg",
        fileName = "IMG_0001.jpg",
        sizeBytes = size.toLong(),
        mimeType = "image/jpeg",
        mediaType = MediaType.IMAGE,
        dateTaken = 0,
        dateModified = 0,
        localMd5 = md5,
        md5SizeBytes = size.toLong(),
        md5DateModified = 0,
        driveFileId = null,
        driveMd5 = null,
        uploadedAt = null,
        status = SyncStatus.QUEUED,
        failureReason = null,
        resumeSessionUri = null
    )

    private fun patternedBytes(size: Int) = ByteArray(size) { (it % 251).toByte() }

    private fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun compositeEtagOf(bytes: ByteArray): String =
        S3Checksum.expectedMultipartEtag(bytes.inputStream(), bytes.size.toLong(), partSize)

    private class FakeLocalFiles(private val bytes: ByteArray) : LocalFiles {
        override fun stat(record: SyncRecord) = LocalFiles.Stat(record.sizeBytes, record.dateModified)
        override fun open(record: SyncRecord, offset: Long): java.io.InputStream {
            val stream = bytes.inputStream()
            if (offset > 0) stream.skip(offset)
            return stream
        }
    }

    /** Counts local opens, to prove a checksum path was taken rather than a cached value. */
    private class FakeLocalReads(val opens: AtomicInteger) : LocalFiles {
        override fun stat(record: SyncRecord) = LocalFiles.Stat(record.sizeBytes, record.dateModified)
        override fun open(record: SyncRecord, offset: Long): java.io.InputStream {
            opens.incrementAndGet()
            val stream = ByteArray(record.sizeBytes.toInt()) { (it % 251).toByte() }.inputStream()
            if (offset > 0) stream.skip(offset)
            return stream
        }
    }
}