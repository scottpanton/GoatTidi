package com.goattidi.mediasync.data.storage.s3

import com.goattidi.mediasync.data.storage.s3.SigV4Signer.Companion.uriEncode

/**
 * Where and how to reach an S3-compatible service.
 *
 * [pathStyle] decides whether the bucket is a path segment (`https://host/bucket/key`)
 * or a subdomain (`https://bucket.host/key`). Path style is the default because it is
 * what MinIO and most self-hosted gateways expect, and because a bucket name is not
 * always a valid DNS label. AWS accepts both.
 */
data class S3Config(
    val endpoint: String,
    val region: String,
    val bucket: String,
    val pathStyle: Boolean = true
) {
    /** The endpoint without a trailing slash, always https- or http:// prefixed. */
    val baseUrl: String = endpoint.trimEnd('/')

    /** True once the user has filled in enough to build a request. */
    val isComplete: Boolean
        get() = baseUrl.startsWith("http") && bucket.isNotBlank() && region.isNotBlank()
}

/** One object as returned by a listing. */
data class S3ObjectEntry(
    val key: String,
    val sizeBytes: Long?,
    /** The raw ETag, quotes stripped. */
    val etag: String?,
    val lastModified: String? = null,
    /** User metadata this app stamps on its own uploads. */
    val metadata: Map<String, String> = emptyMap(),
    /**
     * The object's own MD5, when the service reports one. Stronger than the ETag: it is
     * the same value whatever part size was used, so an object uploaded by another tool
     * can still be recognized as already-present.
     */
    val md5Checksum: String? = null
)

/** One page of a listing. */
data class S3ListResult(
    val objects: List<S3ObjectEntry> = emptyList(),
    val prefixes: List<String> = emptyList(),
    val nextContinuationToken: String? = null
)

/** What a HEAD reports about one object. */
data class S3ObjectStat(
    val key: String,
    val sizeBytes: Long?,
    val etag: String?,
    val contentType: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val md5Checksum: String? = null
)

/** A part the service has accepted, as needed to complete a multipart upload. */
data class S3UploadedPart(val partNumber: Int, val etag: String)

/**
 * Reads the XML S3 replies with the parser Android and the JDK both ship, so the same
 * code path runs on-device and in plain JUnit tests (the pull parser on the android.jar
 * stub does not).
 */
internal object S3Xml {

    fun parseListResult(xml: String): S3ListResult {
        val doc = parseDocument(xml)
        val objects = mutableListOf<S3ObjectEntry>()
        val prefixes = mutableListOf<String>()
        for (element in doc.documentElement.children()) {
            when (element.tagName) {
                "Contents" -> objects += parseEntry(element)
                "CommonPrefixes" -> element.textOf("Prefix")?.let { prefixes += it }
            }
        }
        val truncated = doc.documentElement.textOf("IsTruncated")?.trim() == "true"
        val token = doc.documentElement.textOf("NextContinuationToken")
        return S3ListResult(
            objects = objects,
            prefixes = prefixes,
            // A truncated listing with no token would silently stop early, so treat that
            // as the end of the walk rather than looping forever or dropping objects.
            nextContinuationToken = if (truncated) token else null
        )
    }

    fun parseUploadedParts(xml: String): List<S3UploadedPart> =
        parseDocument(xml).documentElement.children()
            .filter { it.tagName == "Part" }
            .mapNotNull { part ->
                val number = part.textOf("PartNumber")?.trim()?.toIntOrNull() ?: return@mapNotNull null
                val etag = part.textOf("ETag")?.trimQuotes() ?: return@mapNotNull null
                S3UploadedPart(number, etag)
            }
            .sortedBy { it.partNumber }

    /** The `Code` of an `&lt;Error&gt;` body, which is what decides the failure type. */
    fun parseErrorCode(xml: String): String? = try {
        parseDocument(xml).documentElement.textOf("Code")?.trim()
    } catch (e: Exception) {
        null
    }

    /**
     * The upload id from an `&lt;InitiateMultipartUploadResult&gt;`. It is the only handle
     * to an in-flight upload, so losing it means the bytes have to be sent again.
     */
    fun parseUploadId(xml: String): String? = try {
        parseDocument(xml).documentElement.textOf("UploadId")?.trim()
    } catch (e: Exception) {
        null
    }

    /** The CompleteMultipartUpload request body, which must echo each part's ETag. */
    fun buildCompleteMultipartUpload(parts: List<S3UploadedPart>): String =
        buildString {
            append("<CompleteMultipartUpload>")
            for (part in parts) {
                append("<Part><PartNumber>${part.partNumber}</PartNumber><ETag>")
                append(xmlEscape(part.etag))
                append("</ETag></Part>")
            }
            append("</CompleteMultipartUpload>")
        }

    private fun parseEntry(element: org.w3c.dom.Element) = S3ObjectEntry(
        key = element.textOf("Key").orEmpty(),
        sizeBytes = element.textOf("Size")?.trim()?.toLongOrNull(),
        etag = element.textOf("ETag")?.trimQuotes(),
        lastModified = element.textOf("LastModified"),
        md5Checksum = element.textOf("ChecksumMD5")?.trimQuotes()
    )

    private fun parseDocument(xml: String): org.w3c.dom.Document =
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
            xml.byteInputStream()
        )

    /** Text of the first direct child with [name], ignoring namespace prefixes. */
    private fun org.w3c.dom.Element.textOf(name: String): String? =
        children().firstOrNull {
            it.tagName == name || it.tagName.substringAfter(':') == name
        }?.textContent

    /**
     * Element children only. Indexed rather than iterated because a DOM NodeList has both
     * a Java iterator and a Kotlin extension and the compiler cannot pick between them.
     */
    private fun org.w3c.dom.Element.children(): List<org.w3c.dom.Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? org.w3c.dom.Element }

    private fun String.trimQuotes(): String = trim().removeSurrounding("\"", "\"")

    private fun xmlEscape(value: String): String = buildString {
        for (c in value) {
            append(
                when (c) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    else -> c
                }
            )
        }
    }

    /** Percent-encodes a key for use inside a request path, keeping "/" as a separator. */
    fun encodePathSegment(segment: String): String = uriEncode(segment, encodeSlash = false)

    /**
     * Encodes a whole key for a request path, segment by segment, so a key containing a
     * space or a non-ASCII character is sent the way the signature was computed.
     */
    fun encodeKeyPath(key: String): String =
        key.split('/').joinToString("/") { uriEncode(it, encodeSlash = false) }
}
