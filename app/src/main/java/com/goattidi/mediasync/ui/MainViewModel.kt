package com.goattidi.mediasync.ui

import android.app.PendingIntent
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goattidi.mediasync.data.db.MediaType
import com.goattidi.mediasync.data.db.SyncRecord
import com.goattidi.mediasync.data.db.SyncStatus
import com.goattidi.mediasync.data.drive.DRIVE_ROOT_ID
import com.goattidi.mediasync.data.drive.DriveAuthConsentRequired
import com.goattidi.mediasync.data.drive.DriveAuthProvider
import com.goattidi.mediasync.data.drive.DriveClient
import com.goattidi.mediasync.data.drive.DriveException
import com.goattidi.mediasync.data.drive.DriveFile
import com.goattidi.mediasync.data.storage.StorageRouter
import com.goattidi.mediasync.data.repo.DriveLayout
import com.goattidi.mediasync.data.repo.SyncSettings
import com.goattidi.mediasync.data.repo.SyncStateRepository
import com.goattidi.mediasync.sync.ReclaimEngine
import com.goattidi.mediasync.sync.SyncScheduler
import com.goattidi.mediasync.sync.VerifyEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

enum class Screen { GALLERY, RECLAIM, SETTINGS, QUEUE }

enum class Filter(val label: String) {
    ALL("All"),
    IMAGES("Photos"),
    VIDEOS("Videos"),
    AUDIO("Audio"),
    UNSYNCED("Unsynced"),
    SYNCED("Synced")
}

/**
 * The snackbar text after a delete: [requested] files were sent for deletion, [notDeleted]
 * of them are still on the device afterwards, [skipped] failed the safety re-check earlier.
 */
internal fun deleteOutcomeMessage(requested: Int, notDeleted: Int, skipped: Int): String {
    val deleted = requested - notDeleted
    val main = when {
        notDeleted == 0 -> if (deleted == 1) "Deleted 1 file — space reclaimed" else "Deleted $deleted files — space reclaimed"
        deleted == 0 -> "Nothing was deleted — Android didn't allow it"
        else -> "Deleted $deleted of $requested files — $notDeleted couldn't be deleted"
    }
    return if (skipped > 0) "$main · $skipped skipped by the safety re-check" else main
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: SyncStateRepository,
    private val scheduler: SyncScheduler,
    private val verifyEngine: VerifyEngine,
    private val reclaimEngine: ReclaimEngine,
    private val authProvider: DriveAuthProvider,
    private val settings: SyncSettings,
    private val driveClient: DriveClient,
    private val storageRouter: StorageRouter
) : ViewModel() {

    /** One level of the Drive folder browser. Breadcrumb starts at ("root", "My Drive"). */
    data class FolderPicker(
        val breadcrumb: List<Pair<String, String>>,
        val folders: List<DriveFile> = emptyList(),
        val loading: Boolean = true
    )

    data class UiState(
        val records: List<SyncRecord> = emptyList(),
        val totalCount: Int = 0,
        val syncedCount: Int = 0,
        /** Files the user has sent for upload: everything that has left NOT_UPLOADED. */
        val uploadSelectedCount: Int = 0,
        val screen: Screen = Screen.GALLERY,
        val filter: Filter = Filter.ALL,
        val selected: Set<Long> = emptySet(),
        val busy: Boolean = false,
        val message: String? = null,
        val consentIntent: PendingIntent? = null,
        /** Derived from the live records (see ReclaimEngine.candidatesFrom), never set by hand. */
        val reclaimCandidates: List<SyncRecord> = emptyList(),
        val folderName: String = "",
        val layout: DriveLayout = DriveLayout.FLAT,
        val wifiOnly: Boolean = true,
        val chargingOnly: Boolean = false,
        val dedupFolder: String = "",
        val folderPicker: FolderPicker? = null,
        /** null until checked; set by the Settings status check and every connect attempt. */
        val driveConnected: Boolean? = null,
        /** Email of the connected Drive account; null if not connected or not yet known. */
        val driveAccountEmail: String? = null,
        /** Sync search covers the whole Drive (the default once expanded) rather than one folder. */
        val syncSearchEntireDrive: Boolean = false,
        /** When the sync-search index was last rebuilt (epoch ms); 0 = never. */
        val lastSyncScanAt: Long = 0,
        val syncScanning: Boolean = false,
        /** A user-requested gallery refresh (pull-to-refresh) is in flight. */
        val refreshing: Boolean = false,
        /** Any MediaStore scan is running, including automatic ones. */
        val scanning: Boolean = false,
        /** Active work: UPLOADING first, then QUEUED, then FAILED. */
        val queue: List<SyncRecord> = emptyList()
    ) {
        val selectionMode: Boolean get() = selected.isNotEmpty()
    }

    private val ui = MutableStateFlow(UiState())

    /** What to resume once Google's consent screen returns; null = a plain connect. */
    private var afterConsent: (() -> Unit)? = null

    /** Set once this process has confirmed (or rebuilt) the own-upload ledger. */
    private var ledgerChecked = false

    /** Files handed to the UI for deletion, so the outcome can be checked afterwards. */
    private data class PendingDelete(val ids: List<Long>, val skipped: Int)
    private var pendingDelete: PendingDelete? = null

    /**
     * MediaStore scans, run one at a time. Conflated: however many requests arrive while
     * a scan runs (camera bursts, pull, returning to the app), one more scan follows it.
     */
    private val scanRequests = Channel<Unit>(Channel.CONFLATED)

    /** Set by [refresh]; the next scan to start is the one that clears the pull indicator. */
    private var userRefreshRequestedAt: Long? = null

    private data class Prefs(
        val folderName: String,
        val layout: DriveLayout,
        val wifiOnly: Boolean,
        val chargingOnly: Boolean,
        val dedupFolder: String
    )

    private val prefs = combine(
        settings.folderName, settings.layout, settings.wifiOnly, settings.chargingOnly, settings.dedupFolder
    ) { name, layout, wifi, charging, dedup -> Prefs(name, layout, wifi, charging, dedup) }

    val state: StateFlow<UiState> = combine(repository.observeAll(), prefs, ui) { records, p, s ->
        val visible = records
            .filter { matches(it, s.filter) }
            .sortedByDescending { it.dateTaken }
        s.copy(
            records = visible,
            totalCount = records.size,
            syncedCount = records.count { it.status == SyncStatus.SYNCED },
            uploadSelectedCount = records.count { it.status != SyncStatus.NOT_UPLOADED },
            folderName = p.folderName,
            layout = p.layout,
            wifiOnly = p.wifiOnly,
            chargingOnly = p.chargingOnly,
            dedupFolder = p.dedupFolder,
            reclaimCandidates = ReclaimEngine.candidatesFrom(records),
            queue = records
                .filter {
                    it.status == SyncStatus.UPLOADING || it.status == SyncStatus.QUEUED ||
                        it.status == SyncStatus.FAILED
                }
                .sortedBy {
                    when (it.status) {
                        SyncStatus.UPLOADING -> 0
                        SyncStatus.QUEUED -> 1
                        else -> 2
                    }
                }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    /** Uris the UI should route through MediaStore.createDeleteRequest. */
    private val _deleteRequests = MutableSharedFlow<List<Uri>>()
    val deleteRequests: SharedFlow<List<Uri>> = _deleteRequests

    private fun matches(record: SyncRecord, filter: Filter): Boolean = when (filter) {
        Filter.ALL -> true
        Filter.IMAGES -> record.mediaType == MediaType.IMAGE
        Filter.VIDEOS -> record.mediaType == MediaType.VIDEO
        Filter.AUDIO -> record.mediaType == MediaType.AUDIO
        Filter.UNSYNCED -> record.status != SyncStatus.SYNCED
        Filter.SYNCED -> record.status == SyncStatus.SYNCED
    }

    /** Pull-to-refresh / "Scan again": shows the refresh indicator until the scan lands. */
    fun refresh() {
        userRefreshRequestedAt = SystemClock.uptimeMillis()
        ui.update { it.copy(refreshing = true) }
        scanRequests.trySend(Unit)
    }

    /** Automatic rescans (app returns to the foreground, MediaStore changed): no indicator. */
    fun refreshQuietly() {
        scanRequests.trySend(Unit)
    }

    /** Debounced: one camera capture fires several MediaStore notifications. */
    @OptIn(FlowPreview::class)
    val mediaChanges: Flow<Unit> = repository.mediaChanges().debounce(MEDIA_CHANGE_DEBOUNCE_MS)

    private suspend fun runScan() {
        // Only a scan that starts after the pull may end it — not one already running
        val refreshStartedAt = userRefreshRequestedAt
        userRefreshRequestedAt = null
        ui.update { it.copy(scanning = true) }
        runCatching { repository.scanAndReconcile() }
            .onFailure { e -> ui.update { it.copy(message = "Scan failed: ${e.message}") } }
        if (refreshStartedAt != null) {
            // A scan takes milliseconds; flipping refreshing true→false inside one frame is
            // never seen by PullToRefreshBox, which then leaves its indicator stuck on screen
            val shown = SystemClock.uptimeMillis() - refreshStartedAt
            if (shown < MIN_REFRESH_INDICATOR_MS) delay(MIN_REFRESH_INDICATOR_MS - shown)
        }
        ui.update { it.copy(scanning = false, refreshing = if (refreshStartedAt != null) false else it.refreshing) }
    }

    fun setFilter(filter: Filter) = ui.update { it.copy(filter = filter) }

    fun toggleSelect(id: Long) = ui.update {
        it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id)
    }

    fun selectAllUnsynced() {
        val ids = state.value.records
            .filter { it.status != SyncStatus.SYNCED && it.status != SyncStatus.UPLOADING }
            .map { it.mediaStoreId }
        ui.update { it.copy(selected = it.selected + ids) }
    }

    fun clearSelection() = ui.update { it.copy(selected = emptySet()) }

    fun syncSelected() {
        viewModelScope.launch {
            val ids = ui.value.selected.toList()
            if (ids.isEmpty()) return@launch
            val queued = repository.enqueue(ids)
            ui.update { it.copy(selected = emptySet(), message = "$queued file(s) queued for upload") }
            // The worker runs in the background and can't show Google's consent screen, so
            // without this a first-time user's queue just stalls on "authorization required".
            // The files stay queued either way; connecting reschedules them.
            val authError = try {
                authProvider.accessToken()
                null
            } catch (e: DriveAuthConsentRequired) {
                e
            } catch (e: DriveException.AuthFailed) {
                e
            } catch (e: Exception) {
                null // Network and other transient errors are the worker's to retry
            }
            // Before the worker starts: after a reinstall it must know what's already on Drive
            if (authError == null) runCatching { ensureOwnLedger() }
            scheduler.scheduleNow(settings.wifiOnly.first(), settings.chargingOnly.first())
            if (authError != null) {
                val text = driveErrorMessage(authError)
                ui.update { it.copy(driveConnected = false, driveAccountEmail = null, message = text) }
            }
        }
    }

    fun retryFailed(id: Long) {
        viewModelScope.launch {
            repository.enqueue(listOf(id))
            scheduler.scheduleNow(settings.wifiOnly.first(), settings.chargingOnly.first())
        }
    }

    fun verify() {
        viewModelScope.launch {
            ui.update { it.copy(busy = true) }
            val text = try {
                val s = verifyEngine.verifyAll()
                "Verify: ${s.confirmedSynced} confirmed, ${s.orphaned} orphaned, " +
                    "${s.modified} modified, ${s.stillUnverified} unverified of ${s.checked}"
            } catch (e: Exception) {
                driveErrorMessage(e)
            }
            ui.update { it.copy(busy = false, message = text) }
        }
    }

    // ---- Drive folder picker ----

    /**
     * Browsing the user's folders is the one place the app needs drive.readonly, so the
     * opt-in grant is requested here — never at connect time.
     */
    fun openFolderPicker() {
        viewModelScope.launch {
            try {
                authProvider.authorizeExternalRead()
                settings.setExternalReadEnabled(true)
                loadPickerLevel(listOf(DRIVE_ROOT_ID to ENTIRE_DRIVE_LABEL))
            } catch (e: Exception) {
                if (e is DriveAuthConsentRequired) afterConsent = ::openFolderPicker
                val text = driveErrorMessage(e)
                ui.update { it.copy(message = text) }
            }
        }
    }

    fun pickerEnter(folder: DriveFile) {
        val current = ui.value.folderPicker ?: return
        loadPickerLevel(current.breadcrumb + (folder.id to (folder.name ?: "(unnamed)")))
    }

    fun pickerUp() {
        val current = ui.value.folderPicker ?: return
        if (current.breadcrumb.size > 1) loadPickerLevel(current.breadcrumb.dropLast(1))
    }

    fun pickerDismiss() {
        ui.update { it.copy(folderPicker = null) }
        viewModelScope.launch {
            // Cancelled a first-time setup: nothing to scan, so stop requesting the read scope
            if (settings.dedupFolder.first().isBlank()) settings.setExternalReadEnabled(false)
        }
    }

    fun pickerSelect() {
        val picker = ui.value.folderPicker ?: return
        val (id, _) = picker.breadcrumb.last()
        val path = picker.breadcrumb.drop(1).joinToString("/") { it.second }.ifEmpty { ENTIRE_DRIVE_LABEL }
        viewModelScope.launch {
            settings.setDedupFolder(path, id)
            ui.update { it.copy(folderPicker = null) }
            runSyncScan(announce = true)
        }
    }

    // ---- Sync detection: opt-in search for files already on Drive (drive.readonly) ----

    fun setSyncSearchExpanded(enabled: Boolean) {
        if (enabled) enableSyncSearch() else disableSyncSearch()
    }

    /** Asks for drive.readonly, then searches the entire Drive until the user narrows it to a folder. */
    private fun enableSyncSearch() {
        viewModelScope.launch {
            try {
                authProvider.authorizeExternalRead()
            } catch (e: Exception) {
                if (e is DriveAuthConsentRequired) afterConsent = ::enableSyncSearch
                val text = driveErrorMessage(e)
                ui.update { it.copy(message = text) }
                return@launch
            }
            settings.setExternalReadEnabled(true)
            settings.setDedupFolder(ENTIRE_DRIVE_LABEL, DRIVE_ROOT_ID)
            runSyncScan(announce = true)
        }
    }

    private fun disableSyncSearch() {
        viewModelScope.launch {
            settings.setDedupFolder("", "")
            settings.setExternalReadEnabled(false)
            settings.setLastSyncScanAt(0)
            ui.update {
                it.copy(message = "Sync search limited to this app's uploads — it no longer requests read access to your Drive")
            }
        }
    }

    fun rescanNow() {
        viewModelScope.launch { runSyncScan(announce = true) }
    }

    /**
     * Rebuilds the sync index: the app's own uploads (wherever they've been moved) plus
     * the sync-search folder, if set. Announced scans report to the snackbar and may
     * launch Google's consent screen; silent ones (the stale refresh on app open) never do.
     */
    private suspend fun runSyncScan(announce: Boolean) {
        ui.update { it.copy(syncScanning = true, busy = it.busy || announce) }
        val text = try {
            val externalId = settings.dedupFolderId.first().trim()
            val externalPath = settings.dedupFolder.first().trim()
            val message = when {
                // Own files across the whole Drive — this app's uploads included
                externalId == DRIVE_ROOT_ID -> {
                    val imported = verifyEngine.importEverything()
                    settings.setLastSyncScanAt(System.currentTimeMillis())
                    "Sync search: indexed $imported file(s) across your Drive"
                }
                externalPath.isEmpty() -> {
                    val own = verifyEngine.importLedger()
                    "Sync search: indexed $own of this app's upload(s)"
                }
                else -> {
                    // Installs from before the folder picker saved only a path: resolve it once,
                    // then keep the id so later scans skip the lookup (and survive renames)
                    val folderId = externalId.takeIf { it.isNotEmpty() }
                        ?: driveClient.resolveFolderPath(externalPath)?.also { settings.setDedupFolder(externalPath, it) }
                    val result = if (folderId != null) verifyEngine.scanOwnUploadsAndContainer(folderId)
                    else VerifyEngine.SyncScanResult(verifyEngine.importLedger(), null)
                    if (result.inContainer == null) {
                        "Sync search folder \"$externalPath\" is gone from Drive — choose another"
                    } else {
                        settings.setLastSyncScanAt(System.currentTimeMillis())
                        "Sync search: indexed ${result.inContainer} file(s) in \"$externalPath\" and " +
                            "${result.own} of this app's upload(s)"
                    }
                }
            }
            ledgerChecked = true
            message
        } catch (e: Exception) {
            if (announce) driveErrorMessage(e) else null
        }
        ui.update {
            it.copy(
                syncScanning = false,
                busy = if (announce) false else it.busy,
                message = if (announce) text else it.message
            )
        }
    }

    /** App-open refresh so files added to Drive from elsewhere get picked up without a button. */
    private suspend fun refreshSyncSearchIfStale() {
        if (settings.dedupFolder.first().isBlank()) return
        val age = System.currentTimeMillis() - settings.lastSyncScanAt.first()
        if (age >= SYNC_SCAN_MAX_AGE_MS) runSyncScan(announce = false)
    }

    /**
     * The ledger is empty after a reinstall or cleared app data; without a rebuild the
     * worker would upload everything already on Drive a second time. Once per process.
     */
    private suspend fun ensureOwnLedger() {
        if (ledgerChecked) return
        if (repository.ledgerIsEmpty(backendId())) verifyEngine.importLedger()
        ledgerChecked = true
    }

    private fun loadPickerLevel(breadcrumb: List<Pair<String, String>>) {
        viewModelScope.launch {
            ui.update { it.copy(folderPicker = FolderPicker(breadcrumb)) }
            try {
                val folders = driveClient.listFolders(breadcrumb.last().first)
                ui.update { it.copy(folderPicker = FolderPicker(breadcrumb, folders, loading = false)) }
            } catch (e: Exception) {
                ui.update { it.copy(folderPicker = null, message = driveErrorMessage(e)) }
            }
        }
    }

    fun connectDrive() {
        viewModelScope.launch {
            try {
                authProvider.accessToken()
                ui.update { it.copy(driveConnected = true, message = "Google Drive connected") }
                loadDriveAccountEmail()
                runCatching { ensureOwnLedger() }
                // Auth failures park the worker in backoff; don't make a waiting queue sit it out
                rescheduleIfQueueActive()
            } catch (e: Exception) {
                val text = driveErrorMessage(e)
                ui.update { it.copy(driveConnected = false, driveAccountEmail = null, message = text) }
            }
        }
    }

    /** Silent status check for Settings: never launches the consent screen. */
    private fun checkDriveConnection() {
        viewModelScope.launch {
            val connected = runCatching { authProvider.accessToken() }.isSuccess
            ui.update { it.copy(driveConnected = connected, driveAccountEmail = if (connected) it.driveAccountEmail else null) }
            if (connected) loadDriveAccountEmail()
        }
    }

    /** Best effort: offline or on any error, Settings just shows "Connected" without the email. */
    private suspend fun loadDriveAccountEmail() {
        val email = runCatching { driveClient.getUser().emailAddress }.getOrNull() ?: return
        ui.update { it.copy(driveAccountEmail = email) }
    }

    fun onConsentResult(granted: Boolean) {
        ui.update { it.copy(consentIntent = null) }
        val next = afterConsent
        afterConsent = null
        when {
            granted -> next?.invoke() ?: connectDrive()
            // Declining the opt-in read scope leaves the base drive.file connection as it was
            next == null -> ui.update { it.copy(driveConnected = false, driveAccountEmail = null) }
        }
    }

    fun openReclaim() = ui.update { it.copy(screen = Screen.RECLAIM) }

    fun openGallery() = ui.update { it.copy(screen = Screen.GALLERY) }

    fun openSettings() {
        ui.update { it.copy(screen = Screen.SETTINGS) }
        checkDriveConnection()
    }

    fun openQueue() = ui.update { it.copy(screen = Screen.QUEUE) }

    fun cancelQueued(id: Long) {
        viewModelScope.launch { repository.cancelQueued(listOf(id)) }
    }

    fun saveFolderName(name: String) {
        viewModelScope.launch {
            val trimmed = name.trim().ifEmpty { settings.defaultFolderName }
            settings.setFolderName(trimmed)
            ui.update { it.copy(message = "Future uploads go to \"$trimmed\"") }
        }
    }

    fun setLayout(layout: DriveLayout) {
        viewModelScope.launch { settings.setLayout(layout) }
    }

    fun setWifiOnly(value: Boolean) {
        viewModelScope.launch {
            settings.setWifiOnly(value)
            rescheduleIfQueueActive()
        }
    }

    fun setChargingOnly(value: Boolean) {
        viewModelScope.launch {
            settings.setChargingOnly(value)
            rescheduleIfQueueActive()
        }
    }

    /** Constraint changes must re-enqueue pending work or the old constraints keep applying. */
    private suspend fun rescheduleIfQueueActive() {
        if (repository.uploadBatch().isNotEmpty()) {
            scheduler.scheduleNow(settings.wifiOnly.first(), settings.chargingOnly.first())
        }
    }

    init {
        // Revive any leftover queue on app open. Background starts can be denied
        // (OEM battery managers) and WorkManager backoff can stretch to hours;
        // opening the app is clear intent to sync, and a foreground (re)schedule
        // resets the backoff and is always allowed to start the upload service.
        viewModelScope.launch { rescheduleIfQueueActive() }
        viewModelScope.launch {
            combine(settings.dedupFolderId, settings.lastSyncScanAt) { id, at -> id to at }
                .collect { (id, at) ->
                    ui.update { it.copy(syncSearchEntireDrive = id == DRIVE_ROOT_ID, lastSyncScanAt = at) }
                }
        }
        viewModelScope.launch { refreshSyncSearchIfStale() }
        viewModelScope.launch { for (request in scanRequests) runScan() }
    }

    fun requestDelete(id: Long) {
        viewModelScope.launch {
            ui.update { it.copy(busy = true) }
            when (val gate = try { reclaimEngine.confirmSafeToDelete(id) } catch (e: Exception) {
                ReclaimEngine.Gate.Blocked(driveErrorMessage(e))
            }) {
                is ReclaimEngine.Gate.Safe -> {
                    repository.getById(id)?.let {
                        pendingDelete = PendingDelete(listOf(id), skipped = 0)
                        _deleteRequests.emit(listOf(Uri.parse(it.localUri)))
                    }
                }
                is ReclaimEngine.Gate.Blocked ->
                    ui.update { it.copy(message = "Not deleted: ${gate.reason}") }
            }
            ui.update { it.copy(busy = false) }
        }
    }

    /**
     * Gates every reclaim candidate (invariant #3 per file), then issues ONE batch
     * delete request — a single system confirmation dialog instead of one per file.
     */
    fun requestDeleteAll() {
        viewModelScope.launch {
            ui.update { it.copy(busy = true) }
            // Bounded parallelism: ~5 concurrent checks saturates OkHttp's per-host
            // limit without inviting Drive rate limits or thrashing disk hashing
            val gate = Semaphore(5)
            val results = coroutineScope {
                reclaimEngine.candidates().map { candidate ->
                    async {
                        gate.withPermit {
                            candidate to try {
                                reclaimEngine.confirmSafeToDelete(candidate.mediaStoreId)
                            } catch (e: Exception) {
                                ReclaimEngine.Gate.Blocked(driveErrorMessage(e))
                            }
                        }
                    }
                }.awaitAll()
            }
            val safe = results
                .filter { (_, g) -> g is ReclaimEngine.Gate.Safe }
                .map { (candidate, _) -> candidate }
            val blocked = results.size - safe.size
            if (safe.isEmpty()) {
                ui.update {
                    it.copy(
                        busy = false,
                        message = if (blocked > 0) "Nothing deleted — $blocked file(s) failed the safety re-check"
                        else "Nothing to delete"
                    )
                }
            } else {
                pendingDelete = PendingDelete(safe.map { it.mediaStoreId }, skipped = blocked)
                _deleteRequests.emit(safe.map { Uri.parse(it.localUri) })
                ui.update { it.copy(busy = false) }
            }
        }
    }

    /**
     * Reports what was actually deleted. The rescan drops records for files that left
     * MediaStore, so any requested file still present was not deleted — whatever the
     * delete call or system dialog claimed.
     */
    fun onDeleteCompleted() {
        val request = pendingDelete ?: return
        pendingDelete = null
        viewModelScope.launch {
            runCatching { repository.scanAndReconcile() }
            val notDeleted = request.ids.count { repository.getById(it) != null }
            ui.update { it.copy(message = deleteOutcomeMessage(request.ids.size, notDeleted, request.skipped)) }
        }
    }

    /** Android 8–10: the user declined the storage permission that deleting requires. */
    fun onDeletePermissionDenied() {
        pendingDelete = null
        ui.update { it.copy(message = "Nothing deleted — on this Android version, deleting needs storage access") }
    }

    fun clearMessage() = ui.update { it.copy(message = null) }

    private fun driveErrorMessage(e: Throwable): String = when (e) {
        is DriveAuthConsentRequired -> {
            ui.update { it.copy(consentIntent = e.pendingIntent) }
            "Google Drive needs your authorization…"
        }
        is DriveException.ServiceDisabled ->
            "Drive API is not enabled for this app's Google Cloud project (see setup docs)"
        is DriveException.StorageQuotaExceeded -> "Google Drive storage is full"
        is DriveException -> "Google Drive error: ${e.message}"
        else -> "Error: ${e.message}"
    }

    private suspend fun backendId(): String = storageRouter.backend().id.name

    private companion object {
        /** Picker label for the top of My Drive; as the sync-search folder it means "entire Drive". */
        const val ENTIRE_DRIVE_LABEL = "My Drive"
        const val SYNC_SCAN_MAX_AGE_MS = 24 * 60 * 60 * 1000L
        const val MEDIA_CHANGE_DEBOUNCE_MS = 1_000L
        const val MIN_REFRESH_INDICATOR_MS = 600L
    }
}
