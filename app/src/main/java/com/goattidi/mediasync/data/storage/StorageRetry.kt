package com.goattidi.mediasync.data.storage

import com.goattidi.mediasync.data.drive.RetryPolicy

/**
 * Retries a backend-neutral operation on rate limits (honoring Retry-After) and network
 * errors. Everything else — quota, auth, session expiry — propagates immediately.
 *
 * Named apart from `data.drive.withRetry` so the two can be imported side by side: that
 * one catches Drive's own failures inside the Drive engine, this one catches anything a
 * [StorageBackend] surfaces to the sync engine.
 */
suspend fun <T> withStorageRetry(policy: RetryPolicy, block: suspend () -> T): T {
    var attempt = 0
    while (true) {
        try {
            return block()
        } catch (e: StorageException.RateLimited) {
            attempt++
            if (attempt >= policy.maxAttempts) throw e
            policy.sleeper(e.retryAfterSeconds?.times(1000) ?: policy.backoffMs(attempt))
        } catch (e: StorageException.Network) {
            attempt++
            if (attempt >= policy.maxAttempts) throw e
            policy.sleeper(policy.backoffMs(attempt))
        }
    }
}
