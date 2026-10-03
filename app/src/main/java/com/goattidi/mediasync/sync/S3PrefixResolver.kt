package com.goattidi.mediasync.sync

import com.goattidi.mediasync.data.db.SyncRecord
import com.goattidi.mediasync.data.repo.DriveLayout
import com.goattidi.mediasync.data.repo.SyncSettings
import com.goattidi.mediasync.data.storage.BackendId
import com.goattidi.mediasync.data.storage.StorageBackendProvider
import kotlinx.coroutines.flow.first
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the S3 destination per the user's settings: the root prefix (default
 * "GoatTidi_<device name>") plus an optional subfolder per the chosen layout — upload month
 * ("2026-07") or the file's local source folder ("Camera", "WhatsApp Video").
 *
 * Mirrors [DriveDestinationResolver]'s layout rules so switching backends keeps organizing
 * files the same way. S3 has no folders, so a subfolder is a key prefix and creating one is
 * string composition rather than a round-trip — the layout a user picked therefore costs
 * nothing extra here.
 */
@Singleton
class S3PrefixResolver @Inject constructor(
    private val settings: SyncSettings,
    private val router: StorageBackendProvider
) : DestinationResolver {

    /** Injectable for tests; wall clock in production. */
    internal var clock: () -> Long = System::currentTimeMillis

    override suspend fun resolveDestinationId(record: SyncRecord): String? {
        val root = rootPrefix()
        val childName = when (settings.layout.first()) {
            DriveLayout.FLAT -> return root
            DriveLayout.BY_MONTH -> monthFolder(clock())
            DriveLayout.MIRROR_LOCAL -> sourceFolder(record)
        }
        val backend = router.backend()
        return backend.ensureContainer(childName, root)
    }

    private suspend fun rootPrefix(): String {
        val name = settings.folderName.first().trim().ifEmpty { settings.defaultFolderName }
        return "$name/"
    }

    private fun monthFolder(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM", Locale.US).format(Date(timestamp))

    private fun sourceFolder(record: SyncRecord): String =
        File(record.filePath).parentFile?.name?.takeIf { it.isNotBlank() } ?: "Other"
}

/**
 * Dispatches to whichever resolver the selected backend needs. Both resolvers depend on
 * settings and one of the two backends, so neither can decide for itself which is live —
 * that answer comes from the user's choice, read once per call.
 */
@Singleton
class RoutedDestinationResolver @Inject constructor(
    private val router: StorageBackendProvider,
    private val drive: DriveDestinationResolver,
    private val s3: S3PrefixResolver
) : DestinationResolver {

    override suspend fun resolveDestinationId(record: SyncRecord): String? =
        when (router.backend().id) {
            BackendId.S3 -> s3.resolveDestinationId(record)
            else -> drive.resolveDestinationId(record)
        }
}
