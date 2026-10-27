/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import java.io.File
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.opentv.BuildConfig
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.data.backup.ImportReport
import app.opentv.data.backup.SettingsBackup
import app.opentv.data.work.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The settings file: this device's configuration, written to a file and read back from one.
 *
 * Setting up a second box used to mean typing the server address, the username and the
 * password into a phone keyboard, one character at a time, on a television remote. The file
 * carries the providers with their credentials, the preferences, and the user's decisions about
 * the channels; see [app.opentv.data.backup.SettingsBackup] for exactly what travels.
 *
 * Two things the screen is careful about:
 *
 *  - **Nothing is written before the user has seen what will happen.** Picking a file to
 *    import shows what it would change and waits for a confirmation. A settings file is
 *    overwritten by the next export, and a mis-tap that silently replaced the providers on a
 *    working television would be a bad afternoon.
 *  - **The credentials are in the file, and the screen says so.** The file is written where
 *    the user chooses and read back where the user chooses; the LAN sync keeps credentials on
 *    the device that owns them, and this is the one place they travel.
 */
@Composable
fun BackupScreen(onBack: () -> Unit, onImported: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { ServiceLocator.get(context).settingsBackupRepository }

    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<SettingsBackup?>(null) }
    var pendingReport by remember { mutableStateOf<ImportReport?>(null) }
    var importFileName by remember { mutableStateOf<String>(suggestedFileName()) }

    val exportLauncher = rememberLauncherForActivityResult(
        CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            error = null
            status = null
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val backup = repository.gather(BuildConfig.VERSION_NAME)
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(repository.encode(backup).toByteArray(Charsets.UTF_8))
                    } ?: error("The file could not be opened for writing.")
                }
            }
            busy = false
            outcome.fold(
                onSuccess = {
                    status = context.getString(R.string.backup_exported, fileNameOf(context, uri))
                },
                onFailure = {
                    error = context.getString(R.string.backup_export_failed, it.message ?: it.javaClass.simpleName)
                },
            )
        }
    }

    fun exportDirectly() {
        scope.launch {
            busy = true
            error = null
            status = null
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val backup = repository.gather(BuildConfig.VERSION_NAME)
                    val dir = context.filesDir
                    val file = File(dir, suggestedFileName())
                    file.writeBytes(repository.encode(backup).toByteArray(Charsets.UTF_8))
                    file.absolutePath
                }
            }
            busy = false
            outcome.fold(
                onSuccess = { path ->
                    status = context.getString(R.string.backup_exported_direct, path)
                },
                onFailure = {
                    error = context.getString(R.string.backup_export_failed, it.message ?: it.javaClass.simpleName)
                },
            )
        }
    }

    fun importDirectly(fileName: String) {
        scope.launch {
            busy = true
            error = null
            status = null
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = context.filesDir
                    val file = File(dir, fileName)
                    val text = file.readBytes().toString(Charsets.UTF_8)
                    repository.decode(text)
                }
            }
            busy = false
            outcome.fold(
                onSuccess = { backup ->
                    val report = runCatching { repository.preview(backup) }.getOrNull()
                    pending = backup
                    pendingReport = report
                },
                onFailure = {
                    error = context.getString(R.string.backup_import_failed, it.message ?: it.javaClass.simpleName)
                },
            )
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            error = null
            status = null
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: error("The file could not be opened.")
                    repository.decode(text)
                }
            }
            busy = false
            outcome.fold(
                onSuccess = { backup ->
                    val report = runCatching { repository.preview(backup) }.getOrNull()
                    pending = backup
                    pendingReport = report
                },
                onFailure = {
                    error = context.getString(R.string.backup_import_failed, it.message ?: it.javaClass.simpleName)
                },
            )
        }
    }

    pending?.let { backup ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.backup_confirm_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.backup_confirm_body, describe(context, backup)))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        describeChanges(context, pendingReport),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        pending = null
                        scope.launch {
                            busy = true
                            val report = runCatching { repository.apply(backup) }.getOrNull()
                            busy = false
                            if (report == null) {
                                error = context.getString(R.string.backup_import_failed, "")
                            } else {
                                status = context.getString(R.string.backup_imported, describeChanges(context, report))
                                // The providers that just arrived have no channels yet. A
                                // background refresh fills them without holding the screen up:
                                // a big playlist takes minutes, and the user has other things
                                // to do than watch a spinner.
                                if (report.sourcesAdded > 0 || report.sourcesUpdated > 0) {
                                    SyncWorker.refreshNow(context)
                                }
                                onImported()
                            }
                        }
                    },
                ) { Text(stringResource(R.string.backup_confirm_import)) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_backup_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.common_done)) }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.backup_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 720.dp),
        )

        Spacer(Modifier.height(20.dp))
        BackupSection(stringResource(R.string.backup_export_title)) {
            Text(
                stringResource(R.string.backup_export_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                enabled = !busy,
                onClick = {
                    exportDirectly()
                },
            ) { Text(stringResource(R.string.backup_export_button)) }
        }

        Spacer(Modifier.height(16.dp))
        BackupSection(stringResource(R.string.backup_import_title)) {
            Text(
                stringResource(R.string.backup_import_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                enabled = !busy,
                onClick = {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    if (context.packageManager.resolveActivity(intent, 0) != null) {
                        importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                    } else {
                        error = context.getString(R.string.backup_no_file_manager)
                    }
                },
            ) { Text(stringResource(R.string.backup_import_button)) }

        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.backup_import_direct_label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row {
            OutlinedTextField(
                value = importFileName,
                onValueChange = { importFileName = it },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                enabled = !busy && importFileName.isNotBlank(),
                onClick = { importDirectly(importFileName) },
            ) { Text(stringResource(R.string.backup_import_direct_button)) }
        }
        }

        status?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * The file name a new export starts from: the app's name and the day it was written.
 *
 * A date in the name is what makes a folder of exports readable — "which one was last
 * Tuesday?" is a question a file list cannot answer when every file is called the same thing.
 */
private fun suggestedFileName(): String {
    val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    return "opentv-settings-$stamp.json"
}

private fun fileNameOf(context: Context, uri: Uri): String {
    val document = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)
    return document?.name ?: uri.lastPathSegment ?: "the file"
}

/** One line about the file itself: how many providers, and when it was written. */
private fun describe(context: Context, backup: SettingsBackup): String {
    val when_ = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        .format(Date(backup.exportedAtMillis))
    return context.getString(R.string.backup_summary, backup.sources.size, when_)
}

/** What the import would do, in the fewest words that still say something. */
private fun describeChanges(context: Context, report: ImportReport?): String {
    if (report == null) return ""
    val parts = mutableListOf<String>()
    if (report.sourcesAdded > 0) {
        parts += context.getString(R.string.backup_change_sources_added, report.sourcesAdded)
    }
    if (report.sourcesUpdated > 0) {
        parts += context.getString(R.string.backup_change_sources_updated, report.sourcesUpdated)
    }
    if (report.sourcesSkipped > 0) {
        parts += context.getString(R.string.backup_change_sources_skipped, report.sourcesSkipped)
    }
    if (report.preferencesApplied > 0) {
        parts += context.getString(R.string.backup_change_preferences, report.preferencesApplied)
    }
    if (report.favouritesAdded > 0) {
        parts += context.getString(R.string.backup_change_favourites, report.favouritesAdded)
    }
    if (report.hiddenAdded > 0) {
        parts += context.getString(R.string.backup_change_hidden, report.hiddenAdded)
    }
    if (report.hiddenCategoriesAdded > 0) {
        parts += context.getString(R.string.backup_change_categories, report.hiddenCategoriesAdded)
    }
    return if (parts.isEmpty()) {
        context.getString(R.string.backup_change_nothing)
    } else {
        parts.joinToString(", ")
    }
}

@Composable
private fun BackupSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.widthIn(max = 720.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Card {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) { content() }
        }
    }
}
