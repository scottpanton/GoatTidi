package com.goattidi.mediasync.data.storage

import java.io.IOException

/**
 * Typed storage failures, independent of any vendor's error format.
 *
 * Callers decide retry semantics per type: rate limits and network errors are retryable
 * with backoff; quota, auth, and session expiry are not. Each backend translates its own
 * error shapes into these, so the sync engine never parses a vendor response.
 */
sealed class StorageException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class RateLimited(val retryAfterSeconds: Long?, message: String) : StorageException(message)
    class StorageQuotaExceeded(message: String) : StorageException(message)

    /** Credentials were rejected — the user must fix the backend's configuration. */
    class AuthFailed(message: String) : StorageException(message)

    /** A dead resumable session, not a missing object. */
    class SessionExpired(message: String) : StorageException(message)
    class NotFound(message: String) : StorageException(message)

    /** The backend's API is switched off or unreachable for this credential. */
    class ServiceDisabled(message: String) : StorageException(message)
    class Http(val code: Int, message: String) : StorageException(message)
    class Network(cause: IOException) : StorageException("Network error: ${cause.message}", cause)
}
