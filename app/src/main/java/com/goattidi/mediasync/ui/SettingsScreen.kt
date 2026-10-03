package com.goattidi.mediasync.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.goattidi.mediasync.data.db.SyncStatus
import com.goattidi.mediasync.data.repo.DriveLayout
import com.goattidi.mediasync.data.repo.S3Settings
import com.goattidi.mediasync.data.storage.BackendId

private fun layoutLabel(layout: DriveLayout): String = when (layout) {
    DriveLayout.FLAT -> "All in one folder"
    DriveLayout.BY_MONTH -> "By upload month (e.g. 2026-07)"
    DriveLayout.MIRROR_LOCAL -> "By source folder (Camera, WhatsApp…)"
}

/** Names the containers sync detection covers, so the text changes with the toggle and folder. */
private fun syncDetectionDescription(state: MainViewModel.UiState): String {
    val base = "Files this app uploaded stay synced even if you move them out of ${state.folderName}/."
    val where = if (state.backendId == BackendId.DRIVE) "your Drive" else "your bucket"
    return when {
        state.dedupFolder.isBlank() -> base
        state.syncSearchWholeBackend ->
            "$base Files already anywhere in $where are recognized too, so they aren't uploaded twice."
        else -> "$base Files already in ${state.dedupFolder}/ are recognized too, so they aren't uploaded twice."
    }
}

private fun syncScanStatus(state: MainViewModel.UiState): String = when {
    state.syncScanning -> "Scanning…"
    state.lastSyncScanAt == 0L -> "Not scanned yet"
    System.currentTimeMillis() - state.lastSyncScanAt < DateUtils.MINUTE_IN_MILLIS -> "Last scanned just now"
    else -> "Last scanned " + DateUtils.getRelativeTimeSpanString(
        state.lastSyncScanAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
    ).toString().replaceFirstChar { it.lowercase() }
}

@Composable
fun SettingsContent(state: MainViewModel.UiState, viewModel: MainViewModel) {
    var folderName by remember(state.folderName) { mutableStateOf(state.folderName) }
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Storage backend", style = MaterialTheme.typography.titleMedium)
        Text(
            "Where uploads are sent. Files already uploaded stay where they are; switching " +
                "only affects future uploads.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        BackendId.entries.forEach { id ->
            val label = if (id == BackendId.DRIVE) "Google Drive" else "S3-compatible storage"
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { viewModel.selectBackend(id) }
            ) {
                RadioButton(selected = state.backendId == id, onClick = { viewModel.selectBackend(id) })
                Text(label)
            }
        }

        HorizontalDivider()

        if (state.backendId == BackendId.S3) {
            S3SettingsSection(state.s3Settings, viewModel)
            HorizontalDivider()
        }

        if (state.backendId == BackendId.DRIVE) {
            GoogleDriveAccountSection(state, viewModel)
            HorizontalDivider()
        }

        Text("Destination in ${state.backendName}", style = MaterialTheme.typography.titleMedium)
        // Save lives inside the field and appears only once the name actually changes
        val folderNameChanged = folderName.isNotBlank() && folderName.trim() != state.folderName
        OutlinedTextField(
            value = folderName,
            onValueChange = { folderName = it },
            label = { Text(if (state.backendId == BackendId.DRIVE) "Folder name" else "Prefix") },
            singleLine = true,
            trailingIcon = if (folderNameChanged) {
                { TextButton(onClick = { viewModel.saveFolderName(folderName) }) { Text("Save") } }
            } else null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                if (folderNameChanged) viewModel.saveFolderName(folderName)
                focusManager.clearFocus()
            }),
            modifier = Modifier.fillMaxWidth()
        )

        HorizontalDivider()

        Text("Layout inside ${state.folderName}/", style = MaterialTheme.typography.titleMedium)
        DriveLayout.entries.forEach { layout ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { viewModel.setLayout(layout) }
            ) {
                RadioButton(selected = state.layout == layout, onClick = { viewModel.setLayout(layout) })
                Text(layoutLabel(layout))
            }
        }

        HorizontalDivider()

        Text("Upload conditions", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Wi-Fi only")
                Text(
                    "Upload only on unmetered networks",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = state.wifiOnly, onCheckedChange = { viewModel.setWifiOnly(it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Charging only")
                Text(
                    "Wait until the phone is charging",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = state.chargingOnly, onCheckedChange = { viewModel.setChargingOnly(it) })
        }

        HorizontalDivider()

        Text("Sync Settings", style = MaterialTheme.typography.titleMedium)
        Text(syncDetectionDescription(state), style = MaterialTheme.typography.bodyMedium)
        val expanded = state.dedupFolder.isNotBlank()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Expand sync search to other folders")
                Text(
                    if (state.backendId == BackendId.DRIVE) {
                        "Avoid duplicate uploads by finding files already on your Drive. " +
                            "Asks Google for read-only access to your Drive."
                    } else {
                        "Avoid duplicate uploads by finding files already in your bucket. " +
                            "Uses the key you entered above — no extra permission needed."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = expanded, onCheckedChange = { viewModel.setSyncSearchExpanded(it) })
        }
        if (expanded) {
            TextButton(onClick = { viewModel.openFolderPicker() }) {
                Text(if (state.syncSearchWholeBackend) chooseContainerLabel(state) else changeContainerLabel(state))
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    syncScanStatus(state),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { viewModel.rescanNow() }, enabled = !state.syncScanning) {
                    Text("Rescan now")
                }
            }
        }

        HorizontalDivider()

        Text(
            "Changes apply to future uploads only — files already on Drive stay where they are. " +
                "Moving or renaming files on your phone never re-uploads them: identical content " +
                "is recognized by checksum and re-linked automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    state.folderPicker?.let { picker -> FolderPickerDialog(picker, viewModel) }
}

@Composable
private fun FolderPickerDialog(picker: MainViewModel.FolderPicker, viewModel: MainViewModel) {
    AlertDialog(
        onDismissRequest = { viewModel.pickerDismiss() },
        title = {
            Text(
                picker.breadcrumb.joinToString(" / ") { it.second },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            if (picker.loading) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                    if (picker.breadcrumb.size > 1) {
                        item {
                            Text(
                                "⬆  ..",
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { viewModel.pickerUp() }
                                    .padding(vertical = 10.dp, horizontal = 4.dp)
                            )
                        }
                    }
                    if (picker.folders.isEmpty()) {
                        item {
                            Text(
                                "No subfolders",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp)
                            )
                        }
                    }
                    items(picker.folders, key = { it.id }) { folder ->
                        Text(
                            "📁  ${folder.name ?: "(unnamed)"}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { viewModel.pickerEnter(folder) }
                                .padding(vertical = 10.dp, horizontal = 4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.pickerSelect() }, enabled = !picker.loading) {
                Text("Select this folder")
            }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.pickerDismiss() }) { Text("Cancel") }
        }
    )
}

/** The container a backend calls its whole scope; the labels differ, the role does not. */
private fun chooseContainerLabel(state: MainViewModel.UiState): String =
    if (state.backendId == BackendId.DRIVE) "Choose Drive folder…" else "Choose prefix…"

private fun changeContainerLabel(state: MainViewModel.UiState): String =
    if (state.backendId == BackendId.DRIVE) "Change Drive folder…" else "Change prefix…"

/** Drive's account status, shown only while Drive is the selected backend. */
@Composable
private fun GoogleDriveAccountSection(state: MainViewModel.UiState, viewModel: MainViewModel) {
    Text("Google Drive account", style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            when (state.driveConnected) {
                null -> "Checking…"
                true -> state.driveAccountEmail?.let { "✓ Connected as $it" } ?: "✓ Connected"
                false -> "Not connected — uploads wait until you connect"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = when (state.driveConnected) {
                true -> statusColor(SyncStatus.SYNCED)
                false -> MaterialTheme.colorScheme.error
                null -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f)
        )
        if (state.driveConnected == false) {
            TextButton(onClick = { viewModel.connectDrive() }) { Text("Connect Google Drive") }
        }
    }
}

/**
 * Endpoint details for an S3-compatible service.
 *
 * Deliberately plain text fields rather than a credential picker: S3-compatible services
 * are self-hosted far more often than they are AWS, and there is no common identity provider
 * to offer. Path-style addressing is on by default because it is what MinIO and most
 * gateways expect.
 */
@Composable
private fun S3SettingsSection(current: S3Settings, viewModel: MainViewModel) {
    var endpoint by remember(current.endpoint) { mutableStateOf(current.endpoint) }
    var region by remember(current.region) { mutableStateOf(current.region) }
    var bucket by remember(current.bucket) { mutableStateOf(current.bucket) }
    var accessKeyId by remember(current.accessKeyId) { mutableStateOf(current.accessKeyId) }
    var secretAccessKey by remember(current.secretAccessKey) { mutableStateOf(current.secretAccessKey) }
    var sessionToken by remember(current.sessionToken) { mutableStateOf(current.sessionToken) }
    var pathStyle by remember(current.pathStyle) { mutableStateOf(current.pathStyle) }
    val focusManager = LocalFocusManager.current

    Text("S3 connection", style = MaterialTheme.typography.titleMedium)
    Text(
        "Works with AWS S3, Cloudflare R2, Backblaze B2, MinIO and other S3-compatible services. " +
            "Give the app a key that can read and write only this bucket.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    fun save() = viewModel.saveS3Settings(
        S3Settings(
            endpoint = endpoint.trim(),
            region = region.trim(),
            bucket = bucket.trim(),
            accessKeyId = accessKeyId.trim(),
            secretAccessKey = secretAccessKey.trim(),
            sessionToken = sessionToken.trim(),
            pathStyle = pathStyle
        )
    )

    S3Field("Endpoint", endpoint, { endpoint = it }, "https://s3.example.com", focusManager, ::save)
    S3Field("Region", region, { region = it }, "us-east-1", focusManager, ::save)
    S3Field("Bucket", bucket, { bucket = it }, "my-media", focusManager, ::save)
    S3Field("Access key ID", accessKeyId, { accessKeyId = it }, "AKIA…", focusManager, ::save)
    S3Field("Secret access key", secretAccessKey, { secretAccessKey = it }, "", focusManager, ::save)
    S3Field(
        "Session token (optional)", sessionToken, { sessionToken = it },
        "only for temporary credentials", focusManager, ::save
    )

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text("Path-style addressing")
            Text(
                "Bucket as a path segment instead of a subdomain. Leave on for MinIO and " +
                    "most self-hosted services.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = pathStyle, onCheckedChange = { pathStyle = it })
    }

    TextButton(
        onClick = {
            save()
            focusManager.clearFocus()
        },
        enabled = endpoint.isNotBlank() && bucket.isNotBlank()
    ) { Text("Save and test") }
}

@Composable
private fun S3Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    focusManager: androidx.compose.ui.focus.FocusManager,
    onSave: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
        singleLine = true,
        visualTransformation = if (label.contains("Secret")) {
            androidx.compose.ui.text.input.PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(
            onDone = {
                onSave()
                focusManager.clearFocus()
            }
        ),
        modifier = Modifier.fillMaxWidth()
    )
}
