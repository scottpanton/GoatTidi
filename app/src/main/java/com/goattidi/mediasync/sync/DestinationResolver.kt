package com.goattidi.mediasync.sync

import com.goattidi.mediasync.data.db.SyncRecord
import com.goattidi.mediasync.data.drive.DriveClient
import com.goattidi.mediasync.data.drive.DriveException
import com.goattidi.mediasync.data.repo.DriveLayout
import com.goattidi.mediasync.data.repo.SyncSettings
import kotlinx.coroutines.flow.first
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supplies the destination a given upload should land in, as an id only the active backend
 * interprets: a Drive folder id, or an S3 key prefix. Null means the backend root.
 */
fun interface DestinationResolver {
    suspend fun resolveDestinationId(record: SyncRecord): String?
}

/**
 * Resolves the destination per the user's settings: the root folder (default
 * "GoatTidi_<device name>", find-or-create, id cached) plus an optional subfolder per the
 * chosen layout — upload month ("2026-07") or the file's local source folder
 * ("Camera", "WhatsApp Video"). This app owns its root folder — deliberately
 * separate from folders other tools manage (§4.4).
 */
@Singleton
class DriveDestinationResolver @Inject constructor(
    private val settings: SyncSettings,
    private val client: DriveClient
) : DestinationResolver {

    private val childCache = ConcurrentHashMap<String, String>()

    /** Injectable for tests; wall clock in production. */
    internal var clock: () -> Long = System::currentTimeMillis

    /** The cached root id already confirmed live on Drive in this process. */
    @Volatile
    private var confirmedRootId: String? = null

    override suspend fun resolveDestinationId(record: SyncRecord): String? {
        val rootId = rootFolderId()
        val childName = when (settings.layout.first()) {
            DriveLayout.FLAT -> return rootId
            DriveLayout.BY_MONTH -> monthFolder(clock())
            DriveLayout.MIRROR_LOCAL -> sourceFolder(record)
        }
        val cacheKey = "$rootId/$childName"
        childCache[cacheKey]?.let { return it }
        val childId = client.ensureFolder(childName, parentId = rootId)
        childCache[cacheKey] = childId
        return childId
    }

    /**
     * The cached root id outlives the folder if the user deletes or trashes it on Drive,
     * and every upload into it would then fail. Confirm it once per process; if it's
     * gone, drop it and find-or-create again.
     */
    private suspend fun rootFolderId(): String {
        settings.cachedFolderId()?.let { cached ->
            if (cached == confirmedRootId) return cached
            if (isLiveFolder(cached)) {
                confirmedRootId = cached
                return cached
            }
            settings.clearCachedFolderId()
            childCache.clear()
        }
        val name = settings.folderName.first()
        val id = client.ensureFolder(name)
        settings.setCachedFolderId(id, name)
        confirmedRootId = id
        return id
    }

    private suspend fun isLiveFolder(id: String): Boolean = try {
        val folder = client.getFile(id, fields = "id,mimeType,trashed")
        folder.isFolder && !folder.trashed
    } catch (e: DriveException.NotFound) {
        false
    }

    private fun monthFolder(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM", Locale.US).format(Date(timestamp))

    private fun sourceFolder(record: SyncRecord): String =
        File(record.filePath).parentFile?.name?.takeIf { it.isNotBlank() } ?: "Other"
}
