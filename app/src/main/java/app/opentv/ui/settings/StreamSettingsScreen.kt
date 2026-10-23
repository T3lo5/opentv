/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.widget.Toast
import app.opentv.R
import app.opentv.core.AppSettings
import app.opentv.core.BufferPreset
import app.opentv.core.DecoderPreference
import app.opentv.core.StreamSettings

/**
 * Per-stream playback overrides: pin a stream URL (or a URL prefix) to its own buffer preset
 * and decoder preference, so a twitchy stream gets its own tuning without changing the global
 * defaults. Matching is `startsWith`, so one entry covers a whole provider's stream pattern.
 */
@Composable
fun StreamSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { AppSettings.get(context) }
    val streamSettings by settings.streamSettings.collectAsState()

    var editUrl by remember { mutableStateOf("") }
    var editBuffer by remember { mutableStateOf<BufferPreset?>(null) }
    var editDecoder by remember { mutableStateOf<DecoderPreference?>(null) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }

    val newEntryValid = editUrl.isNotBlank()

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_stream_settings_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.common_done)) }
        }

        Spacer(Modifier.height(20.dp))

        Text(
            stringResource(R.string.settings_stream_settings_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        Card {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedTextField(
                    value = editUrl,
                    onValueChange = { editUrl = it.trim() },
                    singleLine = true,
                    label = { Text(stringResource(R.string.settings_stream_url_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.settings_stream_buffer_label),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    RadioButton(
                        selected = editBuffer == null,
                        onClick = { editBuffer = null },
                    )
                    Text(stringResource(R.string.settings_stream_inherit_global))
                    Spacer(Modifier.width(12.dp))
                }
                BufferPreset.entries.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = editBuffer == option,
                            onClick = { editBuffer = option },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(bufferLabelResource(option)), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Spacer(Modifier.height(8.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.settings_stream_decoder_label),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    RadioButton(
                        selected = editDecoder == null,
                        onClick = { editDecoder = null },
                    )
                    Text(stringResource(R.string.settings_stream_inherit_global))
                    Spacer(Modifier.width(12.dp))
                }
                DecoderPreference.entries.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = editDecoder == option,
                            onClick = { editDecoder = option },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(decoderLabelResource(option)), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = {
                            if (newEntryValid) {
                                val entry = StreamSettings(
                                    url = editUrl,
                                    bufferPreset = editBuffer,
                                    decoderPreference = editDecoder,
                                )
                                val idx = editingIndex
                                val isEditing = idx != null
                                val updated = if (isEditing) {
                                    streamSettings.toMutableList().apply {
                                        set(idx, entry)
                                    }
                                } else {
                                    streamSettings + entry
                                }
                                settings.setStreamSettings(updated)
                                editUrl = ""
                                editBuffer = null
                                editDecoder = null
                                editingIndex = null
                            }
                        },
                        enabled = newEntryValid,
                    ) {
                        Text(if (editingIndex != null) stringResource(R.string.settings_stream_update) else stringResource(R.string.settings_stream_add))
                    }
                    if (editingIndex != null) {
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = {
                            editUrl = ""
                            editBuffer = null
                            editDecoder = null
                            editingIndex = null
                        }) { Text(stringResource(R.string.common_cancel)) }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        if (streamSettings.isEmpty()) {
            Text(
                stringResource(R.string.settings_stream_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                stringResource(R.string.settings_stream_overrides_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(streamSettings) { setting ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clickable {
                                editUrl = setting.url
                                editBuffer = setting.bufferPreset
                                editDecoder = setting.decoderPreference
                                editingIndex = streamSettings.indexOfFirst { it.url == setting.url }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(setting.url, style = MaterialTheme.typography.titleMedium)
                            val bufferLabel = setting.bufferPreset?.let { bufferLabelResource(it) }
                                ?.let { stringResource(it) }
                            val decoderLabel = setting.decoderPreference?.let { decoderLabelResource(it) }
                                ?.let { stringResource(it) }
                            Text(
                                overrideSummary(bufferLabel, decoderLabel),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        val removedMessage = stringResource(R.string.settings_stream_removed)
                        val deleteDescription = stringResource(R.string.settings_stream_delete)
                        IconButton(onClick = {
                            settings.setStreamSettings(
                                streamSettings.filter { it.url != setting.url },
                            )
                            Toast.makeText(context, removedMessage, Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = deleteDescription)
                        }
                    }
                }
            }
        }
    }
}

/** A human-readable summary of what an override changes. */
private fun overrideSummary(bufferLabel: String?, decoderLabel: String?): String {
    return listOfNotNull(bufferLabel, decoderLabel).joinToString(" · ")
}

private fun bufferLabelResource(option: BufferPreset): Int = when (option) {
    BufferPreset.DEFAULT -> R.string.settings_buffer_default
    BufferPreset.SMALL -> R.string.settings_buffer_small
    BufferPreset.MEDIUM -> R.string.settings_buffer_medium
    BufferPreset.LARGE -> R.string.settings_buffer_large
    BufferPreset.EXTRA_LARGE -> R.string.settings_buffer_extra_large
}

private fun decoderLabelResource(option: DecoderPreference): Int = when (option) {
    DecoderPreference.AUTO -> R.string.settings_decoder_auto
    DecoderPreference.SOFTWARE_FIRST -> R.string.settings_decoder_software_first
}
