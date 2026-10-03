package com.goattidi.mediasync.data.storage.s3

import com.goattidi.mediasync.data.storage.StorageException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream
import java.util.Locale

/**
 * Streams a request body while reporting absolute file position as bytes hit the socket,
 * so upload progress moves continuously rather than per part. [baseOffset] is the file
 * position the body starts at, so the caller always hears an absolute position.
 */
private class ProgressRequestBody(
    private val data: ByteArray,
    private val baseOffset: Long,
    private val contentType: okhttp3.MediaType,
    private val onBytesSent: ((Long) -> Unit)?
) : RequestBody() {
    override fun contentType(): okhttp3.MediaType = contentType
    override fun contentLength(): Long = data.size.toLong()

    override fun writeTo(sink: BufferedSink) {
        if (onBytesSent == null) {
            sink.write(data)
            return
        }
        var written = 0
        while (written < data.size) {
            val n = minOf(REPORT_STEP, data.size - written)
            sink.write(data, written, n)
            written += n
            onBytesSent.invoke(baseOffset + written)
        }
    }

    private companion object {
        const val REPORT_STEP = 256 * 1024
    }
}

/**
 * A thin client over the S3 REST API, covering the subset a resumable, verifiable upload
 * needs: multipart upload with part-level resume, listings for duplicate detection, and
 * ranged reads to prove what is actually stored.
 *
 * Every request is signed, so failures arrive as S3's XML `&lt;Error&gt;` body plus a
 * status code. Those are mapped to [StorageException] here, which is why no caller above
 * this class needs to know that "SlowDown" is what S3 calls a rate limit.
 *
 * Requests carry no transparent retries: a failed part upload must surface so the caller
 * can ask the service which parts it already holds, rather than re-pushing bytes that may
 * already be stored.
 */
class S3Client(
    private val config: S3Config,
    private val credentials: AwsCredentials,
    httpClient: OkHttpClient,
    private val signer: SigV4Signer = SigV4Signer(config.region, "s3")
) {
    private val http = httpClient.newBuilder().retryOnConnectionFailure(false).build()
    private val xmlType = "application/xml".toMediaType()

    /**
     * One object, or null when it does not exist. A missing key is an ordinary result
     * rather than an exception, because "verify found it gone" is a normal outcome.
     */
    suspend fun headObject(key: String): S3ObjectStat? {
        val response = send(HttpMethod.HEAD, key, emptyList(), emptyList(), null)
        response.use {
            if (it.code == 404) return null
            if (!it.isSuccessful) throw mapError(it, "HEAD $key")
            return S3ObjectStat(
                key = key,
                sizeBytes = it.header("Content-Length")?.toLongOrNull(),
                etag = it.header("ETag")?.trimQuotes(),
                contentType = it.header("Content-Type"),
                metadata = userMetadata(it),
                md5Checksum = it.header("x-amz-checksum-md5")?.trimQuotes()
            )
        }
    }

    /**
     * One page of objects under [prefix]. With [delimiter] the service also collapses
     * everything below each "directory" boundary into [S3ListResult.prefixes], which is
     * how S3 expresses folders — it has none.
     */
    suspend fun listObjects(
        prefix: String? = null,
        delimiter: String? = null,
        continuationToken: String? = null,
        maxKeys: Int = DEFAULT_PAGE_SIZE
    ): S3ListResult {
        val query = buildList {
            add("list-type" to "2")
            if (!prefix.isNullOrEmpty()) add("prefix" to prefix)
            if (!delimiter.isNullOrEmpty()) add("delimiter" to delimiter)
            if (!continuationToken.isNullOrEmpty()) add("continuation-token" to continuationToken)
            add("max-keys" to maxKeys.toString())
        }
        // Asks for stored MD5s where the service has them, so an object uploaded by
        // another tool can still be matched against a local checksum. Ignored where
        // unsupported, and never the basis for marking anything synced.
        val headers = listOf("x-amz-checksum-mode" to "ENABLED")
        val response = send(HttpMethod.GET, null, query, headers, null)
        response.use {
            if (!it.isSuccessful) throw mapError(it, "ListObjects $prefix")
            return S3Xml.parseListResult(it.body?.string().orEmpty())
        }
    }

    /**
     * Streams a byte range, or the whole object when [range] is null, to [consume].
     *
     * This is the ground truth for a stored checksum: re-hashing what comes back proves
     * the bytes the service holds, which no response header taken at upload time can do.
     */
    suspend fun getObject(key: String, range: ByteRange?, consume: (InputStream) -> Unit) {
        val headers = listOfNotNull(range?.let { "Range" to "bytes=${it.first}-${it.last}" })
        val response = send(HttpMethod.GET, key, emptyList(), headers, null)
        response.use {
            if (!it.isSuccessful) throw mapError(it, "GET $key")
            val stream = it.body?.byteStream()
                ?: throw StorageException.Http(it.code, "GET $key returned no body")
            // Handed over as a stream rather than a byte array on purpose: the checksum
            // fallback re-reads entire objects, and a phone cannot hold a video in memory.
            consume(stream)
        }
    }

    /** A half-open-inclusive byte range, as the Range header spells it. */
    data class ByteRange(val first: Long, val last: Long)

    /** Whole-object upload, used for files small enough to send in one request. */
    suspend fun putObject(
        key: String,
        data: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
        onBytesSent: ((Long) -> Unit)?
    ): String {
        val response = send(
            HttpMethod.PUT,
            key,
            query = emptyList(),
            headers = listOf("Content-Type" to contentType) + metadataHeaders(metadata),
            body = ProgressRequestBody(data, 0, contentType.toMediaType(), onBytesSent),
            payloadHash = SigV4Signer.sha256Hex(data)
        )
        response.use {
            if (!it.isSuccessful) throw mapError(it, "PUT $key")
            return it.header("ETag")?.trimQuotes()
                ?: throw StorageException.Http(it.code, "PUT $key returned no ETag")
        }
    }

    suspend fun createMultipartUpload(
        key: String,
        contentType: String,
        metadata: Map<String, String>
    ): String {
        val response = send(
            HttpMethod.POST,
            key,
            query = listOf("uploads" to ""),
            headers = listOf("Content-Type" to contentType) + metadataHeaders(metadata),
            body = ByteArray(0).toRequestBody(null)
        )
        response.use {
            if (!it.isSuccessful) throw mapError(it, "CreateMultipartUpload $key")
            return S3Xml.parseUploadId(it.body?.string().orEmpty())
                ?: throw StorageException.Http(it.code, "CreateMultipartUpload $key returned no upload id")
        }
    }

    /** Uploads one part and returns the ETag that must be echoed when completing. */
    suspend fun uploadPart(
        key: String,
        uploadId: String,
        partNumber: Int,
        data: ByteArray,
        baseOffset: Long,
        onBytesSent: ((Long) -> Unit)?
    ): S3UploadedPart {
        val response = send(
            HttpMethod.PUT,
            key,
            query = listOf("partNumber" to partNumber.toString(), "uploadId" to uploadId),
            headers = emptyList(),
            body = ProgressRequestBody(data, baseOffset, "application/octet-stream".toMediaType(), onBytesSent),
            payloadHash = SigV4Signer.UNSIGNED_PAYLOAD
        )
        response.use {
            if (!it.isSuccessful) throw mapError(it, "UploadPart $key part $partNumber")
            val etag = it.header("ETag")?.trimQuotes()
                ?: throw StorageException.Http(it.code, "UploadPart $key returned no ETag")
            return S3UploadedPart(partNumber, etag)
        }
    }

    /**
     * Which parts the service already holds after an interrupted run. Listing them is
     * what lets a resumed upload continue instead of restarting from zero — the same
     * reason DriveClient probes for a confirmed offset.
     */
    suspend fun listParts(key: String, uploadId: String): List<S3UploadedPart> {
        val response = send(HttpMethod.GET, key, listOf("uploadId" to uploadId), emptyList(), null)
        response.use {
            if (!it.isSuccessful) throw mapError(it, "ListParts $key")
            return S3Xml.parseUploadedParts(it.body?.string().orEmpty())
        }
    }

    /** Finalizes the object; the returned ETag is the multipart one (`...-<partCount>`). */
    suspend fun completeMultipartUpload(
        key: String,
        uploadId: String,
        parts: List<S3UploadedPart>
    ): String? {
        val xml = S3Xml.buildCompleteMultipartUpload(parts)
        val response = send(
            HttpMethod.POST,
            key,
            query = listOf("uploadId" to uploadId),
            headers = listOf("Content-Type" to "application/xml"),
            body = xml.toRequestBody(xmlType),
            payloadHash = SigV4Signer.sha256Hex(xml.toByteArray(Charsets.UTF_8))
        )
        response.use {
            if (!it.isSuccessful) throw mapError(it, "CompleteMultipartUpload $key")
            return it.header("ETag")?.trimQuotes()
        }
    }

    suspend fun abortMultipartUpload(key: String, uploadId: String) {
        val response = send(HttpMethod.DELETE, key, listOf("uploadId" to uploadId), emptyList(), null)
        response.use { if (!it.isSuccessful && it.code != 404) throw mapError(it, "Abort $key") }
    }

    /** Signs and executes one request, retrying nothing. */
    private suspend fun send(
        method: String,
        key: String?,
        query: List<Pair<String, String>>,
        headers: List<Pair<String, String>>,
        body: RequestBody?,
        payloadHash: String = SigV4Signer.EMPTY_PAYLOAD_SHA256
    ): Response {
        val url = urlFor(key, query)
        val host = if (url.port == HttpUrl.defaultPort(url.scheme)) url.host else "${url.host}:${url.port}"
        // The decoded full path is signed and urlFor encodes it the same way, so the
        // canonical URI and the outgoing path cannot drift apart.
        val sigRequest = SigV4Signer.SigRequest(
            method = method,
            host = host,
            path = "/" + fullKey(key),
            query = query,
            headers = headers
        )
        val request = Request.Builder()
            .url(url)
            .method(method, body ?: emptyBodyFor(method))
            .apply {
                headers.forEach { (name, value) -> header(name, value) }
                header(CONTENT_SHA256_HEADER, payloadHash)
                for ((name, value) in signer.signHeaders(sigRequest, credentials, payloadHash)) {
                    header(name, value)
                }
            }
            .build()

        return withContext(Dispatchers.IO) {
            try {
                http.newCall(request).execute()
            } catch (e: IOException) {
                throw StorageException.Network(e)
            }
        }
    }

    /**
     * The key as addressed on the wire. In path style the bucket leads the path, in
     * virtual-host style it leads the hostname, and it must be part of the signed path in
     * the first case — the canonical URI has to match the request path exactly.
     */
    private fun fullKey(key: String?): String =
        if (config.pathStyle) {
            config.bucket + (key?.let { if (it.isEmpty()) "" else "/$it" } ?: "")
        } else {
            key.orEmpty()
        }

    /**
     * Builds the request URL. Object keys are percent-encoded segment by segment so a key
     * containing a space or a non-ASCII character is sent the way it was signed.
     *
     * Internal so the addressing rules can be asserted directly: getting them wrong fails
     * as an opaque 403 at runtime, which is far more expensive to diagnose.
     */
    internal fun urlFor(key: String?, query: List<Pair<String, String>>): HttpUrl {
        val base = config.baseUrl.toHttpUrl()
        val resolved = fullKey(key)
        val builder = base.newBuilder()
        if (!config.pathStyle) builder.host("${config.bucket}.${base.host}")
        builder.encodedPath("/" + S3Xml.encodeKeyPath(resolved))
        query.forEach { (name, value) -> builder.addQueryParameter(name, value) }
        return builder.build()
    }

    private fun emptyBodyFor(method: String): RequestBody? =
        if (method == HttpMethod.GET || method == HttpMethod.HEAD) null
        else ByteArray(0).toRequestBody(null)

    /**
     * S3 spells user metadata "x-amz-meta-" on the wire. Stripping the prefix here keeps
     * callers working with plain names, and stripping it again on read round-trips.
     * Names come back lowercased, since that is how a service returns them however they
     * were sent.
     */
    private fun metadataHeaders(metadata: Map<String, String>): List<Pair<String, String>> =
        metadata.map { (name, value) -> "x-amz-meta-$name" to value }

    private fun userMetadata(response: Response): Map<String, String> =
        response.headers.names()
            .filter { it.startsWith(METADATA_PREFIX, ignoreCase = true) }
            .associate { name ->
                name.substring(METADATA_PREFIX.length).lowercase(Locale.US) to response.headers[name].orEmpty()
            }

    private fun mapError(response: Response, what: String): StorageException {
        val code = response.code
        val body = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
        val reason = S3Xml.parseErrorCode(body)
        val message = "$what failed: HTTP $code" + (reason?.let { " ($it)" } ?: "")
        val retryAfter = response.header("Retry-After")?.toLongOrNull()
        return when (reason) {
            // An upload id the service no longer knows: discard it and start a new one.
            "NoSuchUpload" -> StorageException.SessionExpired(message)
            "EntityTooLarge", "MaxMessageLengthExceeded" -> StorageException.StorageQuotaExceeded(message)
            "PermanentRedirect", "AuthorizationHeaderMalformed" -> StorageException.ServiceDisabled(message)
            "SlowDown" -> StorageException.RateLimited(retryAfter, message)
            "AccessDenied", "InvalidAccessKeyId", "SignatureDoesNotMatch", "ExpiredToken" ->
                StorageException.AuthFailed(message)
            else -> when (code) {
                401, 403 -> StorageException.AuthFailed(message)
                404 -> StorageException.NotFound(message)
                429 -> StorageException.RateLimited(retryAfter, message)
                else -> StorageException.Http(code, message)
            }
        }
    }

    private fun String.trimQuotes(): String = trim().removeSurrounding("\"", "\"")

    private object HttpMethod {
        const val GET = "GET"
        const val HEAD = "HEAD"
        const val PUT = "PUT"
        const val POST = "POST"
        const val DELETE = "DELETE"
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 1000
        private const val CONTENT_SHA256_HEADER = "x-amz-content-sha256"
        private const val METADATA_PREFIX = "x-amz-meta-"
    }
}
