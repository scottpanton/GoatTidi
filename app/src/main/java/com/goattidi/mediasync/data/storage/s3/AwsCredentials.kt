package com.goattidi.mediasync.data.storage.s3

/**
 * A static S3 credential pair, optionally with a session token for temporary
 * credentials (an STS session issued for the device).
 */
data class AwsCredentials(
    val accessKeyId: String,
    val secretAccessKey: String,
    val sessionToken: String? = null
)
