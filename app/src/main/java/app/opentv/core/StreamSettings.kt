/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Per-stream playback overrides. When one source's streams are twitchy and the global buffer
 * preset isn't enough, the user can pin a URL (or a URL prefix) to its own buffer preset and
 * decoder preference, so the bad stream gets its own tuning without penalising everything else.
 *
 * Matching: the stored URLs are checked with `startsWith`, so one entry can cover a whole
 * provider's stream pattern (e.g. `http://panel:8000/`) while leaving every other stream on
 * the global default.
 */
@Serializable
data class StreamSettings(
    val url: String,
    val bufferPreset: BufferPreset?,
    val decoderPreference: DecoderPreference?,
)

/** Serializable wrapper for a list of [StreamSettings], so the whole list is one SharedPreferences value. */
@Serializable
data class StreamSettingsWrapper(val settings: List<StreamSettings>)

fun streamSettingsEncode(settings: List<StreamSettings>): String =
    Json { ignoreUnknownKeys = true }.encodeToString(StreamSettingsWrapper.serializer(), StreamSettingsWrapper(settings))

fun streamSettingsDecode(json: String): List<StreamSettings> =
    Json { ignoreUnknownKeys = true }
        .decodeFromString(StreamSettingsWrapper.serializer(), json)
        .settings
