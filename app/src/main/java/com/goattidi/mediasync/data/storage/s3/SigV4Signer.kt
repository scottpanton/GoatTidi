package com.goattidi.mediasync.data.storage.s3

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4 request signing.
 *
 * Hand-rolled rather than pulled from the AWS SDK: GoatTidi talks to plain HTTP already
 * (the Drive client is raw OkHttp on purpose), and aws-sdk-android would add a second
 * HTTP stack plus Jackson for the handful of signing steps below. Every rule
 * implemented here is one the official signing test suite pins down, and
 * `SigV4Test` replays those vectors, so the implementation is checked against the spec
 * rather than against itself.
 *
 * The signature covers the canonical request, which is a fixed rendering of the method,
 * path, query, headers and payload hash. Getting any part of that rendering wrong
 * produces a 403 from the service rather than a wrong-but-working request, so the
 * canonical form is exposed on [SigSignature] and asserted in tests.
 */
class SigV4Signer(
    private val region: String,
    private val service: String = "s3",
    /** Injectable so signatures are deterministic in tests. */
    private val clock: () -> Long = System::currentTimeMillis
) {

    /**
     * A request to sign, in raw form: [path] and the query and header values are
     * unencoded/untrimmed exactly as the caller intends to put them on the wire, so
     * signing and sending cannot disagree about what was sent.
     *
     * [headers] is a list rather than a map because duplicate header names are legal and
     * their order matters: the canonical form joins them with commas in the order given.
     */
    data class SigRequest(
        val method: String,
        val host: String,
        val path: String,
        val query: List<Pair<String, String>> = emptyList(),
        val headers: List<Pair<String, String>> = emptyList()
    )

    data class SigSignature(
        val authorization: String,
        val amzDate: String,
        val dateStamp: String,
        val canonicalRequest: String,
        val stringToSign: String,
        val signedHeaders: List<String>
    )

    /**
     * Signs [request]. [payloadHash] is a hex SHA-256 of the body, or
     * [UNSIGNED_PAYLOAD] when the body is streamed and not worth hashing on a phone.
     */
    fun sign(
        request: SigRequest,
        credentials: AwsCredentials,
        payloadHash: String = UNSIGNED_PAYLOAD
    ): SigSignature {
        val now = Date(clock())
        val amzDate = AMZ_DATE_FORMAT.format(now)
        val dateStamp = DATE_STAMP_FORMAT.format(now)
        val scope = "$dateStamp/$region/$service/aws4_request"

        val securityToken = credentials.sessionToken?.takeIf { it.isNotBlank() }
        val headersToSign = request.headers
            .filterNot { it.first.equals(AMZ_SECURITY_TOKEN_HEADER, ignoreCase = true) }
            .filter { it.first.lowercase(Locale.US) !in UNSIGNED_HEADERS }
        val canonicalHeaders = canonicalizeHeaders(
            headersToSign + (HOST_HEADER to request.host) + (AMZ_DATE_HEADER to amzDate) +
                listOfNotNull(securityToken?.let { AMZ_SECURITY_TOKEN_HEADER to it })
        )
        val signedHeaders = canonicalHeaders.map { it.first }
        val signedHeaderList = signedHeaders.joinToString(";")

        val canonicalRequest = listOf(
            request.method,
            canonicalPath(request.path),
            canonicalQuery(request.query),
            canonicalHeaders.joinToString("") { "${it.first}:${it.second}\n" },
            signedHeaderList,
            payloadHash
        ).joinToString("\n")

        val stringToSign = listOf(
            ALGORITHM,
            amzDate,
            scope,
            sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8))
        ).joinToString("\n")

        val signature = hex(
            hmac(
                signingKey(credentials.secretAccessKey, dateStamp),
                stringToSign.toByteArray(Charsets.UTF_8)
            )
        )
        val authorization = "$ALGORITHM Credential=${credentials.accessKeyId}/$scope, " +
            "SignedHeaders=$signedHeaderList, Signature=$signature"
        return SigSignature(authorization, amzDate, dateStamp, canonicalRequest, stringToSign, signedHeaders)
    }

    /**
     * Builds the headers to add to an outgoing request: the authorization header plus
     * x-amz-date and, for temporary credentials, the session token. All three must be
     * sent exactly as signed.
     */
    fun signHeaders(
        request: SigRequest,
        credentials: AwsCredentials,
        payloadHash: String = UNSIGNED_PAYLOAD
    ): List<Pair<String, String>> {
        val signature = sign(request, credentials, payloadHash)
        return buildList {
            add("Authorization" to signature.authorization)
            add(AMZ_DATE_HEADER to signature.amzDate)
            credentials.sessionToken?.takeIf { it.isNotBlank() }?.let { add(AMZ_SECURITY_TOKEN_HEADER to it) }
        }
    }

    /** Derives the date/region/service scoped signing key. */
    private fun signingKey(secretKey: String, dateStamp: String): ByteArray {
        val kDate = hmac(("AWS4$secretKey").toByteArray(Charsets.UTF_8), dateStamp.ascii())
        val kRegion = hmac(kDate, region.ascii())
        val kService = hmac(kRegion, service.ascii())
        return hmac(kService, "aws4_request".ascii())
    }

    private fun String.ascii(): ByteArray = toByteArray(Charsets.UTF_8)

    /** Lowercases names, trims and space-collapses values, sorts by name, merges repeats. */
    private fun canonicalizeHeaders(headers: List<Pair<String, String>>): List<Pair<String, String>> =
        headers
            .groupBy({ it.first.lowercase(Locale.US) }, { collapseSpaces(it.second.trim()) })
            .toSortedMap()
            .map { (name, values) -> name to values.joinToString(",") }

    /**
     * The path, percent-encoded per segment with "/" left as a separator, after RFC 3986
     * removal of dot segments. Object keys never contain "." or ".." segments — the key
     * builder sanitizes them away — so this normalization cannot rename a real key.
     */
    private fun canonicalPath(path: String): String {
        val absolute = if (path.startsWith("/")) path else "/$path"
        val segments = mutableListOf<String>()
        for (segment in absolute.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                else -> segments += segment
            }
        }
        return "/" + segments.joinToString("/") { uriEncode(it, encodeSlash = false) }
    }

    /**
     * The query, each name and value percent-encoded, sorted by encoded name then encoded
     * value. Sorting on the encoded form is what the spec requires: it is why parameter
     * order in the outgoing URL cannot affect the signature.
     */
    private fun canonicalQuery(query: List<Pair<String, String>>): String =
        query
            .map { (name, value) -> uriEncode(name, encodeSlash = true) to uriEncode(value, encodeSlash = true) }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { (name, value) -> "$name=$value" }

    private fun collapseSpaces(value: String): String {
        val out = StringBuilder(value.length)
        var previousWasSpace = false
        for (c in value) {
            val isSpace = c == ' ' || c == '\t'
            if (isSpace && previousWasSpace) continue
            out.append(c)
            previousWasSpace = isSpace
        }
        return out.toString()
    }

    companion object {
        const val ALGORITHM = "AWS4-HMAC-SHA256"

        /**
         * Stands in for the payload hash when the body is streamed rather than buffered.
         * S3 accepts it over TLS, which is how the AWS SDKs upload multipart parts without
         * hashing gigabytes twice on a device.
         */
        const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"

        /** SHA-256 of the empty string — the payload hash for any bodyless request. */
        const val EMPTY_PAYLOAD_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        private const val HOST_HEADER = "host"
        private const val AMZ_DATE_HEADER = "x-amz-date"
        private const val AMZ_SECURITY_TOKEN_HEADER = "x-amz-security-token"

        /**
         * Never signed: OkHttp adds these itself, after signing, or rewrites them on the
         * wire. Listing them in SignedHeaders while they differ on the request would make
         * every signature invalid.
         */
        private val UNSIGNED_HEADERS = setOf(
            "authorization",
            "content-length",
            "user-agent",
            "connection",
            "accept-encoding",
            "transfer-encoding",
            "expect"
        )

        private val AMZ_DATE_FORMAT = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        private val DATE_STAMP_FORMAT = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }

        /**
         * RFC 3986 percent-encoding: unreserved characters pass through, everything else
         * becomes uppercase hex of each UTF-8 byte. With [encodeSlash] off, "/" survives
         * so path separators keep working.
         */
        fun uriEncode(value: String, encodeSlash: Boolean): String {
            val out = StringBuilder(value.length)
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val c = (byte.toInt() and 0xFF).toChar()
                when {
                    c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' -> out.append(c)
                    c == '_' || c == '-' || c == '~' || c == '.' -> out.append(c)
                    c == '/' && !encodeSlash -> out.append(c)
                    else -> out.append('%').append(HEX[(byte.toInt() and 0xFF) shr 4])
                        .append(HEX[byte.toInt() and 0x0F])
                }
            }
            return out.toString()
        }

        fun sha256Hex(data: ByteArray): String =
            hex(MessageDigest.getInstance("SHA-256").digest(data))

        fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

        private fun hex(bytes: ByteArray): String {
            val out = StringBuilder(bytes.size * 2)
            for (byte in bytes) {
                out.append(LOWER_HEX[(byte.toInt() and 0xFF) shr 4])
                out.append(LOWER_HEX[byte.toInt() and 0x0F])
            }
            return out.toString()
        }

        /** Percent-encoding uses uppercase hex; digests and signatures are lowercase. */
        private val HEX = "0123456789ABCDEF".toCharArray()
        private val LOWER_HEX = "0123456789abcdef".toCharArray()
    }
}
