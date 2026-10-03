package com.goattidi.mediasync.data.repo

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.goattidi.mediasync.data.storage.BackendId
import com.goattidi.mediasync.data.storage.s3.S3Config
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

/** How uploads are organized inside the destination folder (§4.4). */
enum class DriveLayout {
    /** Everything directly in the destination folder. */
    FLAT,

    /** One subfolder per upload month, e.g. "2026-07". */
    BY_MONTH,

    /** One subfolder per local source folder, e.g. "Camera", "WhatsApp Video". */
    MIRROR_LOCAL
}

/**
 * One backend's endpoint details, as the user typed them. Null fields mean unset, and an
 * incomplete config is not usable — see [SyncSettings.s3Config].
 */
data class S3Settings(
    val endpoint: String = "",
    val region: String = "",
    val bucket: String = "",
    val accessKeyId: String = "",
    val secretAccessKey: String = "",
    val sessionToken: String = "",
    val pathStyle: Boolean = true
) {
    val hasCredentials: Boolean get() = accessKeyId.isNotBlank() && secretAccessKey.isNotBlank()
    val isComplete: Boolean
        get() = endpoint.startsWith("http") && region.isNotBlank() && bucket.isNotBlank() && hasCredentials
}

@Singleton
class SyncSettings @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val keyWifiOnly = booleanPreferencesKey("wifi_only")
    private val keyChargingOnly = booleanPreferencesKey("charging_only")
    private val keyFolderName = stringPreferencesKey("drive_folder_name")
    private val keyFolderId = stringPreferencesKey("drive_folder_id")
    private val keyLayout = stringPreferencesKey("drive_layout")

    // ---- Selected storage backend ----

    private val keyBackendId = stringPreferencesKey("storage_backend")

    /**
     * Which service uploads go to. Drive for every install that predates this setting and
     * for anyone who never picks otherwise, so the upgrade changes nothing about where
     * their files already are.
     */
    val backendId: Flow<BackendId> = context.dataStore.data.map { BackendId.parse(it[keyBackendId]) }

    suspend fun setBackendId(value: BackendId) {
        context.dataStore.edit { it[keyBackendId] = value.name }
    }

    private val keyS3Endpoint = stringPreferencesKey("s3_endpoint")
    private val keyS3Region = stringPreferencesKey("s3_region")
    private val keyS3Bucket = stringPreferencesKey("s3_bucket")
    private val keyS3AccessKeyId = stringPreferencesKey("s3_access_key_id")
    private val keyS3SecretAccessKey = stringPreferencesKey("s3_secret_access_key")
    private val keyS3SessionToken = stringPreferencesKey("s3_session_token")
    private val keyS3PathStyle = booleanPreferencesKey("s3_path_style")

    val s3Settings: Flow<S3Settings> = context.dataStore.data.map { prefs ->
        S3Settings(
            endpoint = prefs[keyS3Endpoint].orEmpty(),
            region = prefs[keyS3Region].orEmpty(),
            bucket = prefs[keyS3Bucket].orEmpty(),
            accessKeyId = prefs[keyS3AccessKeyId].orEmpty(),
            secretAccessKey = prefs[keyS3SecretAccessKey].orEmpty(),
            sessionToken = prefs[keyS3SessionToken].orEmpty(),
            pathStyle = prefs[keyS3PathStyle] ?: true
        )
    }

    suspend fun setS3Settings(value: S3Settings) {
        context.dataStore.edit {
            it[keyS3Endpoint] = value.endpoint.trim()
            it[keyS3Region] = value.region.trim()
            it[keyS3Bucket] = value.bucket.trim()
            it[keyS3AccessKeyId] = value.accessKeyId.trim()
            it[keyS3SecretAccessKey] = value.secretAccessKey.trim()
            it[keyS3SessionToken] = value.sessionToken.trim()
            it[keyS3PathStyle] = value.pathStyle
            // The cached destination folder id is a Drive folder id; it cannot mean
            // anything to S3, where the destination is a key prefix.
            it.remove(keyFolderId)
        }
    }

    val wifiOnly: Flow<Boolean> = context.dataStore.data.map { it[keyWifiOnly] ?: true }
    val chargingOnly: Flow<Boolean> = context.dataStore.data.map { it[keyChargingOnly] ?: false }
    /**
     * The destination folder name. Installs that already resolved a folder under the old
     * default (a cached id but no saved name) keep "Phone Media" — otherwise Settings would
     * show the new default while uploads kept landing in the old folder.
     */
    val folderName: Flow<String> = context.dataStore.data.map {
        effectiveFolderName(it[keyFolderName], hasCachedFolder = it[keyFolderId] != null, defaultFolderName)
    }

    /**
     * "GoatTidi_" plus the phone's name as set in Android settings (e.g. "Galaxy S23"),
     * falling back to the model, then "mobile" — so several devices get separate folders.
     */
    val defaultFolderName: String by lazy {
        val deviceName = runCatching {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()
        val name = listOf(deviceName, Build.MODEL, "mobile")
            .firstNotNullOf { it?.trim()?.takeIf(String::isNotEmpty) }
            .replace('/', '-') // "/" separates path segments in the app's folder display
        "GoatTidi_$name"
    }
    val layout: Flow<DriveLayout> = context.dataStore.data.map { prefs ->
        prefs[keyLayout]?.let { runCatching { DriveLayout.valueOf(it) }.getOrNull() } ?: DriveLayout.FLAT
    }

    suspend fun setLayout(value: DriveLayout) {
        context.dataStore.edit { it[keyLayout] = value.name }
    }

    private val keyDedupFolder = stringPreferencesKey("dedup_folder_path")
    private val keyDedupFolderId = stringPreferencesKey("dedup_folder_id")

    /** Display path of the Drive folder tree scanned for duplicates. Empty = off. */
    val dedupFolder: Flow<String> = context.dataStore.data.map { it[keyDedupFolder] ?: "" }

    /** Drive id of that folder (authoritative — survives renames). Empty = resolve by path. */
    val dedupFolderId: Flow<String> = context.dataStore.data.map { it[keyDedupFolderId] ?: "" }

    private val keyExternalRead = booleanPreferencesKey("external_read_enabled")

    /**
     * Whether to request drive.readonly on top of drive.file. Opt-in: only the external
     * duplicate-check folder needs it. Unset on installs from before it was opt-in, which
     * already held the scope — keep it for anyone who has a duplicate-check folder set.
     */
    val externalReadEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[keyExternalRead] ?: !it[keyDedupFolder].isNullOrBlank()
    }

    suspend fun setExternalReadEnabled(value: Boolean) {
        context.dataStore.edit { it[keyExternalRead] = value }
    }

    
    private val keyLastSyncScan = longPreferencesKey("sync_search_scanned_at")

    /** When the sync-search folder was last indexed (epoch ms); 0 = never. */
    val lastSyncScanAt: Flow<Long> = context.dataStore.data.map { it[keyLastSyncScan] ?: 0L }

    suspend fun setLastSyncScanAt(value: Long) {
        context.dataStore.edit { it[keyLastSyncScan] = value }
    }

    suspend fun setDedupFolder(path: String, id: String = "") {
        context.dataStore.edit {
            it[keyDedupFolder] = path.trim()
            if (id.isBlank()) it.remove(keyDedupFolderId) else it[keyDedupFolderId] = id
        }
    }

    suspend fun setWifiOnly(value: Boolean) {
        context.dataStore.edit { it[keyWifiOnly] = value }
    }

    suspend fun setChargingOnly(value: Boolean) {
        context.dataStore.edit { it[keyChargingOnly] = value }
    }

    /** Changing the destination folder invalidates the cached folder id. */
    suspend fun setFolderName(value: String) {
        context.dataStore.edit {
            it[keyFolderName] = value
            it.remove(keyFolderId)
        }
    }

    suspend fun cachedFolderId(): String? = context.dataStore.data.first()[keyFolderId]

    /**
     * Caches the resolved destination folder id. With [folderName], the name it was resolved
     * under is saved too, so a device-derived default can't later read as the legacy one.
     */
    suspend fun setCachedFolderId(id: String, folderName: String? = null) {
        context.dataStore.edit {
            it[keyFolderId] = id
            if (folderName != null) it[keyFolderName] = folderName
        }
    }

    suspend fun clearCachedFolderId() {
        context.dataStore.edit { it.remove(keyFolderId) }
    }

    companion object {
        const val LEGACY_DEFAULT_FOLDER_NAME = "Phone Media"

        /** A cached folder id without a saved name can only come from an install on the old default. */
        internal fun effectiveFolderName(saved: String?, hasCachedFolder: Boolean, deviceDefault: String): String =
            saved ?: if (hasCachedFolder) LEGACY_DEFAULT_FOLDER_NAME else deviceDefault
    }
}
