package com.goattidi.mediasync

import com.goattidi.mediasync.data.storage.s3.AwsCredentials
import com.goattidi.mediasync.data.storage.s3.SigV4Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Replays cases from AWS's published Signature Version 4 signing test suite
 * (`awslabs/aws-c-auth`, tests/aws-signing-test-suite/v4). Each case pins the exact
 * canonical request, string-to-sign and signature the spec produces, so these assertions
 * fail if the signer drifts from the specification rather than merely disagreeing with a
 * previous implementation of itself.
 *
 * Every vector uses the suite's fixed credentials, region, service and clock.
 */
class SigV4SignerTest {

    private val credentials = AwsCredentials(
        accessKeyId = "AKIDEXAMPLE",
        secretAccessKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
    )
    private val timestamp = epochMillis("2015-08-30T12:36:00Z")
    private val signer = SigV4Signer(region = "us-east-1", service = "service", clock = { timestamp })

    @Test
    fun `basics`() {
        val signature = signer.sign(
            SigV4Signer.SigRequest(method = "GET", host = "example.amazonaws.com", path = "/"),
            credentials,
            payloadHash = SigV4Signer.EMPTY_PAYLOAD_SHA256
        )

        assertEquals(
            """
            GET
            /

            host:example.amazonaws.com
            x-amz-date:20150830T123600Z

            host;x-amz-date
            ${SigV4Signer.EMPTY_PAYLOAD_SHA256}
            """.trimIndent(),
            signature.canonicalRequest
        )
        assertEquals(
            """
            AWS4-HMAC-SHA256
            20150830T123600Z
            20150830/us-east-1/service/aws4_request
            bb579772317eb040ac9ed261061d46c1f17a8133879d6129b6e1c25292927e63
            """.trimIndent(),
            signature.stringToSign
        )
        assertEquals(
            "5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31",
            signature.authorization.substringAfter("Signature=")
        )
    }

    @Test
    fun `repeated header values are joined in the order sent`() {
        val signature = signer.sign(
            SigV4Signer.SigRequest(
                method = "GET",
                host = "example.amazonaws.com",
                path = "/",
                headers = listOf(
                    "My-Header1" to "value4",
                    "My-Header1" to "value1",
                    "My-Header1" to "value3",
                    "My-Header1" to "value2"
                )
            ),
            credentials,
            payloadHash = SigV4Signer.EMPTY_PAYLOAD_SHA256
        )

        assertEquals(
            listOf("host", "my-header1", "x-amz-date"),
            signature.signedHeaders
        )
        assertTrue(
            "repeated headers must keep their original order: ${signature.canonicalRequest}",
            signature.canonicalRequest.contains("my-header1:value4,value1,value3,value2\n")
        )
        assertEquals(
            "08c7e5a9acfcfeb3ab6b2185e75ce8b1deb5e634ec47601a50643f830c755c01",
            signature.authorization.substringAfter("Signature=")
        )
    }

    @Test
    fun `unreserved characters are never percent-encoded`() {
        val signature = signer.sign(
            SigV4Signer.SigRequest(
                method = "GET",
                host = "example.amazonaws.com",
                path = "/-._~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
            ),
            credentials,
            payloadHash = SigV4Signer.EMPTY_PAYLOAD_SHA256
        )

        assertEquals(
            "/-._~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz",
            signature.canonicalRequest.lines()[1]
        )
        assertEquals(
            "07ef7494c76fa4850883e2b006601f940f8a34d404d0cfa977f52a65bbf5f24f",
            signature.authorization.substringAfter("Signature=")
        )
    }

    @Test
    fun `non-ascii path is utf8 percent-encoded`() {
        val signature = signer.sign(
            SigV4Signer.SigRequest(method = "GET", host = "example.amazonaws.com", path = "/ሴ"),
            credentials,
            payloadHash = SigV4Signer.EMPTY_PAYLOAD_SHA256
        )

        assertEquals("/%E1%88%B4", signature.canonicalRequest.lines()[1])
        assertEquals(
            "8318018e0b0f223aa2bbf98705b62bb787dc9c0e678f255a891fd03141be5d85",
            signature.authorization.substringAfter("Signature=")
        )
    }

    @Test
    fun `non-ascii query is utf8 percent-encoded and the slash stays a separator`() {
        val signature = signer.sign(
            SigV4Signer.SigRequest(
                method = "GET",
                host = "example.amazonaws.com",
                path = "/",
                query = listOf("ሴ" to "bar")
            ),
            credentials,
            payloadHash = SigV4Signer.EMPTY_PAYLOAD_SHA256
        )

        assertEquals("%E1%88%B4=bar", signature.canonicalRequest.lines()[2])
        assertEquals(
            "2cdec8eed098649ff3a119c94853b13c643bcf08f8b0a1d91e12c9027818dd04",
            signature.authorization.substringAfter("Signature=")
        )
    }

    @Test
    fun `session token is signed alongside the request`() {
        val withToken = credentials.copy(
            sessionToken = "6e86291e8372ff2a2260956d9b8aae1d763fbf315fa00fa31553b73ebf194267"
        )
        val signature = signer.sign(
            SigV4Signer.SigRequest(method = "GET", host = "example.amazonaws.com", path = "/"),
            withToken,
            payloadHash = SigV4Signer.EMPTY_PAYLOAD_SHA256
        )

        assertEquals(
            listOf("host", "x-amz-date", "x-amz-security-token"),
            signature.signedHeaders
        )
        assertEquals(
            "07ec1639c89043aa0e3e2de82b96708f198cceab042d4a97044c66dd9f74e7f8",
            signature.authorization.substringAfter("Signature=")
        )
        assertEquals(
            withToken.sessionToken,
            signer.signHeaders(
                SigV4Signer.SigRequest(method = "GET", host = "example.amazonaws.com", path = "/"),
                withToken,
                payloadHash = SigV4Signer.EMPTY_PAYLOAD_SHA256
            ).first { it.first == "x-amz-security-token" }.second
        )
    }

    @Test
    fun `method is part of the signature`() {
        val post = signer.sign(
            SigV4Signer.SigRequest(method = "POST", host = "example.amazonaws.com", path = "/"),
            credentials,
            payloadHash = SigV4Signer.EMPTY_PAYLOAD_SHA256
        )

        assertEquals("POST", post.canonicalRequest.lines()[0])
        assertEquals(
            "5da7c1a2acd57cee7505fc6676e4e544621c30862966e37dddb68e92efbe5d6b",
            post.authorization.substringAfter("Signature=")
        )
    }

    @Test
    fun `dot segments are resolved away`() {
        val signature = signer.sign(
            SigV4Signer.SigRequest(method = "GET", host = "example.amazonaws.com", path = "/example/.."),
            credentials,
            payloadHash = SigV4Signer.EMPTY_PAYLOAD_SHA256
        )

        assertEquals("/", signature.canonicalRequest.lines()[1])
        assertEquals(
            "5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31",
            signature.authorization.substringAfter("Signature=")
        )
    }

    @Test
    fun `headers okhttp adds after signing are excluded so the wire matches the signature`() {
        val signature = signer.sign(
            SigV4Signer.SigRequest(
                method = "PUT",
                host = "example.amazonaws.com",
                path = "/key",
                headers = listOf(
                    "Content-Length" to "1234",
                    "User-Agent" to "okhttp/4.12.0",
                    "Accept-Encoding" to "gzip",
                    "Content-Type" to "image/jpeg",
                    "X-Amz-Meta-Goattidi-Upload" to "1"
                )
            ),
            credentials,
            payloadHash = SigV4Signer.UNSIGNED_PAYLOAD
        )

        assertEquals(
            listOf("content-type", "host", "x-amz-date", "x-amz-meta-goattidi-upload"),
            signature.signedHeaders
        )
    }

    @Test
    fun `an unsigned payload is stated verbatim in the canonical request`() {
        val signature = signer.sign(
            SigV4Signer.SigRequest(method = "PUT", host = "s3.example", path = "/big"),
            credentials,
            payloadHash = SigV4Signer.UNSIGNED_PAYLOAD
        )

        assertEquals(SigV4Signer.UNSIGNED_PAYLOAD, signature.canonicalRequest.lines().last())
    }

    @Test
    fun `query order and encoding cannot change the signature`() {
        val a = signer.sign(
            SigV4Signer.SigRequest(
                method = "GET",
                host = "example.amazonaws.com",
                path = "/",
                query = listOf("prefix" to "GoatTidi/", "list-type" to "2", "max-keys" to "1000")
            ),
            credentials,
            payloadHash = SigV4Signer.EMPTY_PAYLOAD_SHA256
        )
        val b = signer.sign(
            SigV4Signer.SigRequest(
                method = "GET",
                host = "example.amazonaws.com",
                path = "/",
                query = listOf("max-keys" to "1000", "list-type" to "2", "prefix" to "GoatTidi/")
            ),
            credentials,
            payloadHash = SigV4Signer.EMPTY_PAYLOAD_SHA256
        )

        assertEquals("list-type=2&max-keys=1000&prefix=GoatTidi%2F", a.canonicalRequest.lines()[2])
        assertEquals(a.authorization, b.authorization)
    }

    @Test
    fun `an object key keeps its slashes and encodes what it must`() {
        val signature = signer.sign(
            SigV4Signer.SigRequest(
                method = "PUT",
                host = "bucket.s3.example",
                path = "/GoatTidi_Galaxy S23/2026-07/photo (1).jpg"
            ),
            credentials
        )

        assertEquals(
            "/GoatTidi_Galaxy%20S23/2026-07/photo%20%281%29.jpg",
            signature.canonicalRequest.lines()[1]
        )
    }

    private fun epochMillis(iso: String): Long =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse(iso)!!.time
}
