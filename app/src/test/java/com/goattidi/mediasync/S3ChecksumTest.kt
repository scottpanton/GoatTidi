package com.goattidi.mediasync

import com.goattidi.mediasync.data.storage.s3.S3Checksum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class S3ChecksumTest {

    @Test
    fun `a one-part multipart etag is not the plain md5, but a single-request put's is`() {
        val bytes = "hello".toByteArray()

        val composite = S3Checksum.expectedMultipartEtag(bytes.inputStream(), bytes.size.toLong(), 1024)

        // The composite digests the raw part digest bytes, so even a one-part multipart
        // upload reports something different from a single-request PUT of the same bytes.
        // Getting this confused would make every small-file upload look like a mismatch.
        assertEquals("62109206880d38a4010a98e11243924a-1", composite)
        assertEquals("5d41402abc4b2a76b9719d911017c592", md5Hex(bytes))
    }

    @Test
    fun `a multipart etag is the md5 of the concatenated per-part digests`() {
        val bytes = ByteArray(2500) { (it % 251).toByte() }

        val expected = S3Checksum.expectedMultipartEtag(bytes.inputStream(), bytes.size.toLong(), 1000)

        val partOne = md5Hex(bytes.copyOfRange(0, 1000))
        val partTwo = md5Hex(bytes.copyOfRange(1000, 2000))
        val partThree = md5Hex(bytes.copyOfRange(2000, 2500))
        val expectedDigest = md5Hex(
            hexToBytes(partOne) + hexToBytes(partTwo) + hexToBytes(partThree)
        )
        assertEquals("$expectedDigest-3", expected)
    }

    @Test
    fun `the composite reproduces from the part digests a resumed upload already has`() {
        val bytes = ByteArray(2000) { (it % 97).toByte() }

        val streamed = S3Checksum.expectedMultipartEtag(bytes.inputStream(), bytes.size.toLong(), 1000)
        val fromPartEtags = S3Checksum.expectedMultipartEtagFromPartDigests(
            listOf(md5Hex(bytes.copyOfRange(0, 1000)), md5Hex(bytes.copyOfRange(1000, 2000)))
        )

        assertEquals(streamed, fromPartEtags)
    }

    @Test
    fun `part etags are accepted with the quotes the service returns`() {
        val bytes = ByteArray(2000) { (it % 97).toByte() }

        val quoted = S3Checksum.expectedMultipartEtagFromPartDigests(
            listOf("\"${md5Hex(bytes.copyOfRange(0, 1000))}\"", md5Hex(bytes.copyOfRange(1000, 2000)))
        )

        assertEquals(
            S3Checksum.expectedMultipartEtag(bytes.inputStream(), bytes.size.toLong(), 1000),
            quoted
        )
    }

    @Test
    fun `only md5-shaped etags count as a content hash`() {
        assertTrue(S3Checksum.recognised("d41d8cd98f00b204e9800998ecf8427e"))
        assertTrue(S3Checksum.recognised("d41d8cd98f00b204e9800998ecf8427e-3"))
        assertTrue(S3Checksum.recognised("\"d41d8cd98f00b204e9800998ecf8427e-3\""))

        // A gateway that does not return content hashes must never be treated as proof.
        assertFalse(S3Checksum.recognised(null))
        assertFalse(S3Checksum.recognised(""))
        assertFalse(S3Checksum.recognised("not-a-checksum"))
        assertFalse(S3Checksum.recognised("d41d8cd98f00b204e9800998ecf8427e-"))
        assertFalse(S3Checksum.recognised("D41D8CD98F00B204E9800998ECF8427E"))
        assertFalse(S3Checksum.recognised("d41d8cd98f00b204e9800998ecf8427e-3x"))
    }

    @Test
    fun `a whole-content md5 is told apart from a multipart composite`() {
        assertTrue(S3Checksum.isWholeContentMd5("d41d8cd98f00b204e9800998ecf8427e"))
        assertFalse(S3Checksum.isWholeContentMd5("d41d8cd98f00b204e9800998ecf8427e-3"))
    }

    @Test
    fun `the part size is at least the five mebibytes s3 requires`() {
        assertTrue(
            "non-final parts must be >= 5 MiB or the service rejects the upload",
            S3Checksum.PART_SIZE_BYTES >= 5 * 1024 * 1024
        )
    }

    private fun md5Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("MD5").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}