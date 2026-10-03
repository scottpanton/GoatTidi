package com.goattidi.mediasync

import com.goattidi.mediasync.data.storage.StorageException
import com.goattidi.mediasync.data.storage.s3.AwsCredentials
import com.goattidi.mediasync.data.storage.s3.S3Client
import com.goattidi.mediasync.data.storage.s3.S3Config
import com.goattidi.mediasync.data.storage.s3.S3UploadedPart
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class S3ClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: S3Client

    private val credentials = AwsCredentials("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = S3Client(
            config = S3Config(
                endpoint = server.url("/").toString(),
                region = "us-east-1",
                bucket = "goattidi",
                pathStyle = true
            ),
            credentials = credentials,
            httpClient = OkHttpClient()
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `every request is signed and reaches the bucket in path style`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<ListBucketResult/>"))

        client.listObjects(prefix = "GoatTidi_Phone/")

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertTrue(request.path!!.startsWith("/goattidi?list-type=2"))
        val scope = Regex("Credential=[^/]+/(\\d{8}/[^,]+)").find(request.getHeader("Authorization")!!)!!.groupValues[1]
        assertTrue(
            "scope should be date/region/service/aws4_request but was $scope",
            Regex("\\d{8}/us-east-1/s3/aws4_request").matches(scope)
        )
        assertNotNull(request.getHeader("x-amz-date"))
        assertNotNull(request.getHeader("x-amz-content-sha256"))
    }

    @Test
    fun `a key with spaces and unicode is sent percent-encoded`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<ListBucketResult/>"))

        client.headObject("GoatTidi_Galaxy S23/2026-07/café.jpg")

        assertEquals(
            "/goattidi/GoatTidi_Galaxy%20S23/2026-07/caf%C3%A9.jpg",
            server.takeRequest().path
        )
    }

    @Test
    fun `head reports the objects checksum metadata and size`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("ETag", "\"9a0364b9e99bb480dd25e1f0284c8555\"")
                .setHeader("Content-Length", "2048")
                .setHeader("Content-Type", "image/jpeg")
                .setHeader("x-amz-checksum-md5", "1B2M2Y8AsgTpgAmY7PhCfg==")
                .setHeader("x-amz-meta-goattidi-upload", "1")
        )

        val stat = client.headObject("photo.jpg")!!

        assertEquals("photo.jpg", stat.key)
        assertEquals(2048L, stat.sizeBytes)
        assertEquals("9a0364b9e99bb480dd25e1f0284c8555", stat.etag)
        assertEquals("1B2M2Y8AsgTpgAmY7PhCfg==", stat.md5Checksum)
        assertEquals(mapOf("goattidi-upload" to "1"), stat.metadata)
    }

    @Test
    fun `head of a missing key is null rather than an error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("<Error><Code>NoSuchKey</Code></Error>"))

        assertNull(client.headObject("gone.jpg"))
    }

    @Test
    fun `listings read contents common prefixes and the continuation token`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <ListBucketResult>
                  <IsTruncated>true</IsTruncated>
                  <NextContinuationToken>TOKEN123</NextContinuationToken>
                  <Contents>
                    <Key>GoatTidi_Phone/a.jpg</Key>
                    <Size>100</Size>
                    <ETag>&quot;etag-a&quot;</ETag>
                    <LastModified>2026-07-01T00:00:00.000Z</LastModified>
                  </Contents>
                  <Contents>
                    <Key>GoatTidi_Phone/b.jpg</Key>
                    <Size>200</Size>
                    <ETag>"etag-b"</ETag>
                  </Contents>
                  <CommonPrefixes><Prefix>GoatTidi_Phone/2026-07/</Prefix></CommonPrefixes>
                </ListBucketResult>
                """.trimIndent()
            )
        )

        val page = client.listObjects(prefix = "GoatTidi_Phone/", delimiter = "/")

        assertEquals(listOf("GoatTidi_Phone/a.jpg", "GoatTidi_Phone/b.jpg"), page.objects.map { it.key })
        assertEquals(listOf(100L, 200L), page.objects.map { it.sizeBytes })
        assertEquals(listOf("etag-a", "etag-b"), page.objects.map { it.etag })
        assertEquals(listOf("GoatTidi_Phone/2026-07/"), page.prefixes)
        assertEquals("TOKEN123", page.nextContinuationToken)
        assertTrue(server.takeRequest().path!!.contains("delimiter=%2F"))
    }

    @Test
    fun `an untruncated listing ends the walk even if a token is present`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                <ListBucketResult>
                  <IsTruncated>false</IsTruncated>
                  <NextContinuationToken>TOKEN123</NextContinuationToken>
                </ListBucketResult>
                """.trimIndent()
            )
        )

        assertNull(client.listObjects().nextContinuationToken)
    }

    @Test
    fun `a single put returns the object etag`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"deadbeef\""))

        val etag = client.putObject("photo.jpg", "hello".toByteArray(), "image/jpeg", mapOf("goattidi-upload" to "1"), null)

        assertEquals("deadbeef", etag)
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/goattidi/photo.jpg", request.path)
        assertEquals("image/jpeg", request.getHeader("Content-Type"))
        assertEquals("1", request.getHeader("x-amz-meta-goattidi-upload"))
        assertEquals("hello", request.body.readUtf8())
    }

    @Test
    fun `a part upload reports absolute bytes as they reach the socket`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"part-etag\""))
        val sent = mutableListOf<Long>()

        val part = client.uploadPart(
            key = "big.mp4",
            uploadId = "UP1",
            partNumber = 3,
            data = ByteArray(600 * 1024),
            baseOffset = 32L * 1024 * 1024,
            onBytesSent = { sent += it }
        )

        assertEquals(S3UploadedPart(3, "part-etag"), part)
        assertEquals(600 * 1024L + 32L * 1024 * 1024, sent.last())
        assertTrue(
            "bytes must be reported in increasing order: $sent",
            sent.zipWithNext().all { (a, b) -> b > a }
        )
        val request = server.takeRequest()
        assertTrue(request.path!!.contains("partNumber=3"))
        assertTrue(request.path!!.contains("uploadId=UP1"))
        assertEquals("UNSIGNED-PAYLOAD", request.getHeader("x-amz-content-sha256"))
    }

    @Test
    fun `creating a multipart upload yields the upload id`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                "<InitiateMultipartUploadResult><Key>big.mp4</Key><UploadId>UPLOAD-42</UploadId></InitiateMultipartUploadResult>"
            )
        )

        assertEquals("UPLOAD-42", client.createMultipartUpload("big.mp4", "video/mp4", emptyMap()))
        assertTrue(server.takeRequest().path!!.contains("uploads"))
    }

    @Test
    fun `listing parts recovers what an interrupted run already stored`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                <ListPartsResult>
                  <Part><PartNumber>2</PartNumber><ETag>"etag-2"</ETag></Part>
                  <Part><PartNumber>1</PartNumber><ETag>"etag-1"</ETag></Part>
                </ListPartsResult>
                """.trimIndent()
            )
        )

        assertEquals(
            listOf(S3UploadedPart(1, "etag-1"), S3UploadedPart(2, "etag-2")),
            client.listParts("big.mp4", "UP1")
        )
    }

    @Test
    fun `completing echoes every part etag and returns the composite one`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"abc123-2\""))

        val etag = client.completeMultipartUpload(
            "big.mp4",
            "UP1",
            listOf(S3UploadedPart(1, "etag-1"), S3UploadedPart(2, "etag-2"))
        )

        assertEquals("abc123-2", etag)
        val body = server.takeRequest().body.readUtf8()
        assertEquals(
            "<CompleteMultipartUpload>" +
                "<Part><PartNumber>1</PartNumber><ETag>etag-1</ETag></Part>" +
                "<Part><PartNumber>2</PartNumber><ETag>etag-2</ETag></Part>" +
                "</CompleteMultipartUpload>",
            body
        )
    }

    @Test
    fun `a ranged read returns only the requested bytes`() = runTest {
        server.enqueue(MockResponse().setResponseCode(206).setBody("world"))

        var received = ""
        client.getObject("photo.jpg", S3Client.ByteRange(5, 9)) { received = it.readBytes().toString(Charsets.UTF_8) }

        assertEquals("world", received)
        assertEquals("bytes=5-9", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun `a session the service has forgotten is reported as expired, not missing`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(404).setBody("<Error><Code>NoSuchUpload</Code><Message>gone</Message></Error>")
        )

        try {
            client.listParts("big.mp4", "STALE")
            fail("expected SessionExpired")
        } catch (e: StorageException.SessionExpired) {
            assertTrue(e.message!!.contains("NoSuchUpload"))
        }
    }

    @Test
    fun `service error codes become the failure type callers retry on`() = runTest {
        val cases = listOf(
            Triple(503, "SlowDown", StorageException.RateLimited::class.java),
            Triple(403, "AccessDenied", StorageException.AuthFailed::class.java),
            Triple(403, "SignatureDoesNotMatch", StorageException.AuthFailed::class.java),
            Triple(404, "NoSuchKey", StorageException.NotFound::class.java),
            Triple(400, "EntityTooLarge", StorageException.StorageQuotaExceeded::class.java),
            Triple(301, "PermanentRedirect", StorageException.ServiceDisabled::class.java),
            Triple(500, "InternalError", StorageException.Http::class.java)
        )
        for ((code, reason, expected) in cases) {
            server.enqueue(
                MockResponse().setResponseCode(code)
                    .setBody("<Error><Code>$reason</Code><Message>boom</Message></Error>")
            )
            val thrown = runCatching { client.listObjects() }.exceptionOrNull()
            assertEquals(
                "HTTP $code ($reason) should map to ${expected.simpleName}",
                expected,
                thrown?.let { it::class.java }
            )
        }
    }

    @Test
    fun `rate limits carry the retry-after the service asked for`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(429)
                .setHeader("Retry-After", "37")
                .setBody("<Error><Code>SlowDown</Code></Error>")
        )

        try {
            client.listObjects()
            fail("expected RateLimited")
        } catch (e: StorageException.RateLimited) {
            assertEquals(37L, e.retryAfterSeconds)
        }
    }

    @Test
    fun `a connection failure surfaces as a network error so the caller retries`() = runTest {
        server.shutdown()

        try {
            client.listObjects()
            fail("expected Network")
        } catch (e: StorageException.Network) {
            assertNotNull(e.cause)
        }
    }

    @Test
    fun `virtual-host style puts the bucket in the host name`() {
        val virtualHost = S3Client(
            config = S3Config(
                endpoint = "https://s3.us-east-1.amazonaws.com",
                region = "us-east-1",
                bucket = "goattidi",
                pathStyle = false
            ),
            credentials = credentials,
            httpClient = OkHttpClient()
        )

        val url = virtualHost.urlFor("photo.jpg", emptyList())

        assertEquals("goattidi.s3.us-east-1.amazonaws.com", url.host)
        assertEquals("/photo.jpg", url.encodedPath)
    }

    @Test
    fun `path style keeps the bucket in the path for both objects and listings`() {
        assertEquals(
            "/goattidi/GoatTidi_Phone/2026-07/a.jpg",
            client.urlFor("GoatTidi_Phone/2026-07/a.jpg", emptyList()).encodedPath
        )
        assertEquals("/goattidi", client.urlFor(null, emptyList()).encodedPath)
    }
}
