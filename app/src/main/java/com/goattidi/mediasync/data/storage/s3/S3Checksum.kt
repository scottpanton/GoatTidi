package com.goattidi.mediasync.data.storage.s3

import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * How an S3 stored checksum is read, and how the local side reproduces it.
 *
 * S3 does not store an MD5 of the content; it stores an *ETag*, which means different
 * things depending on how the object was written:
 *
 * - A single-request PUT gets `ETag = MD5(content)`, so it compares directly against the
 *   MD5 the app already computed.
 * - A multipart upload gets `ETag = MD5(MD5(part1) || MD5(part2) || ...) - <count>`, the
 *   MD5 of the concatenated per-part digests with the part count appended. Because this
 *   app chooses the part boundaries, it can reproduce that value from the local file — but
 *   only with the same boundaries.
 *
 * Anything else (some gateways return a non-MD5 ETag) is not a content hash and must never
 * be treated as proof. [recognised] draws that line, and the caller treats anything it
 * rejects as unverified rather than verified, so invariant #1 holds on every service.
 */
object S3Checksum {

    /**
     * The part size every multipart upload uses.
     *
     * Fixed for the life of the feature, not a preference: the expected multipart ETag is
     * only reproducible if the boundaries that produced it are still the boundaries the app
     * would choose. Changing this would make every previously uploaded large object's
     * stored ETag unmatchable, and its record un-verifiable. S3 also requires at least
     * 5 MiB per non-final part.
     */
    const val PART_SIZE_BYTES = 16 * 1024 * 1024

    private val MULTIPART_ETAG = Regex("^[0-9a-f]{32}-\\d+$")
    private val SINGLE_ETAG = Regex("^[0-9a-f]{32}$")

    /**
     * Whether [etag] is a content hash this app can reason about. An unrecognised ETag
     * must yield Unverified, never Verified.
     */
    fun recognised(etag: String?): Boolean {
        val value = etag?.trim()?.removeSurrounding("\"", "\"") ?: return false
        return MULTIPART_ETAG.matches(value) || SINGLE_ETAG.matches(value)
    }

    /** Whether [etag] is the plain MD5 of the whole object. */
    fun isWholeContentMd5(etag: String?): Boolean =
        SINGLE_ETAG.matches(etag?.trim()?.removeSurrounding("\"", "\"").orEmpty())

    /**
     * The ETag this app expects for a multipart upload of [totalBytes] read from [source]
     * in [partSize] chunks: the MD5 of the concatenated per-part MD5 digests, hex, with
     * the part count appended.
     *
     * Reads exactly [totalBytes], so it can be called on a stream already positioned at the
     * start of the object.
     */
    fun expectedMultipartEtag(source: InputStream, totalBytes: Long, partSize: Int): String {
        require(partSize > 0) { "Part size must be positive" }
        val digestOfDigests = MessageDigest.getInstance("MD5")
        var partCount = 0
        var remaining = totalBytes
        val buffer = ByteArray(READ_BUFFER)
        while (remaining > 0) {
            val bytesInThisPart = minOf(partSize.toLong(), remaining)
            val partDigest = MessageDigest.getInstance("MD5")
            var leftInPart = bytesInThisPart
            while (leftInPart > 0) {
                val n = source.read(buffer, 0, minOf(buffer.size.toLong(), leftInPart).toInt())
                if (n < 0) throw IOException("Unexpected end of file: $remaining bytes still expected")
                partDigest.update(buffer, 0, n)
                leftInPart -= n
            }
            digestOfDigests.update(partDigest.digest())
            partCount++
            remaining -= bytesInThisPart
        }
        return hexOf(digestOfDigests.digest()) + "-" + partCount
    }

    /**
     * Builds the expected multipart ETag from per-part digests the service already
     * reported, which is how a resumed upload avoids re-reading the file: an individual
     * part's ETag *is* that part's MD5.
     */
    fun expectedMultipartEtagFromPartDigests(partMd5Hex: List<String>): String {
        require(partMd5Hex.isNotEmpty()) { "A multipart upload needs at least one part" }
        val digestOfDigests = MessageDigest.getInstance("MD5")
        for (hex in partMd5Hex) {
            digestOfDigests.update(hex.removeSurrounding("\"", "\"").hexToBytes())
        }
        return hexOf(digestOfDigests.digest()) + "-" + partMd5Hex.size
    }

    private const val READ_BUFFER = 256 * 1024

    private fun hexOf(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            out.append(HEX[(byte.toInt() and 0xFF) shr 4])
            out.append(HEX[byte.toInt() and 0x0F])
        }
        return out.toString()
    }

    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val HEX = "0123456789abcdef".toCharArray()
}
