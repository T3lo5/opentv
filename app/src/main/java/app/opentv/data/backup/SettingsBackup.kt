/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.backup

import app.opentv.data.model.StremioAddon
import kotlinx.serialization.Serializable

/**
 * A settings file: one device's configuration, written down so another device can be set up the
 * same way.
 *
 * The LAN sync moves *state* — where you stopped watching, what you have favourited, what is
 * hidden. It does not move *configuration*: the providers themselves, the preferences, where
 * recordings go. Setting up a second box still means typing the server address, the username
 * and the password into a phone keyboard, one character at a time, on a television remote.
 * This file is the answer to that (issue #10).
 *
 * ## What travels, and what does not
 *
 * **The providers, with their credentials.** That is the whole point, and it is the one place
 * this file differs from the LAN sync, which deliberately keeps credentials on the device that
 * owns them. The difference is the trust boundary: the LAN sync is two boxes on the same
 * network talking to each other, and the app will not put a password on the wire to a peer it
 * found by itself. A settings file is something the user picked, named, and carried over
 * themselves — usually onto their own second box. The user is deliberately handing over the
 * credentials, and the file is only ever written where they choose.
 *
 * **The preferences that describe how the app behaves** — the theme, the channel layout, the
 * buffer size, the guide history, where recordings go.
 *
 * **Not** the things that belong to one install rather than to the setup:
 *
 *  - **TV or phone mode.** That is the shape of the device, not a preference. Copying "phone"
 *    onto a television would be wrong every time.
 *  - **The parental PIN.** It guards this device's screen; a file is too easy to copy around
 *    and hand on. Set it on the new device.
 *  - **The sync device id**, the last channel watched, the one-shot "resume where I left off" —
 *    identity and position, not configuration.
 *  - **The USB recording folder.** It is a Storage Access Framework grant: the permission
 *    belongs to the device that picked it, and the other device has to pick its own.
 *  - **The VOD cache stamp**, which says when a catalogue was last fetched here.
 *
 * ## How an import is applied
 *
 * **Preferences are restored — but only the ones that were actually chosen.** A value still at
 * the app's default was never a choice, so it does not travel. Without that rule, restoring a
 * file written by a phone with no NAS onto a television that had one would wipe the NAS: the
 * file says "no NAS" because the phone never had one, and that is not the same as "the NAS
 * should be removed".
 *
 * **Providers are merged, never replaced**: a provider already here with the same kind and
 * address is updated (its credentials may have changed), a new one is added, and nothing is
 * ever deleted.
 *
 * **Curation is additive**, exactly like the LAN sync's: favourites and hidden channels from
 * the file are merged in, and nothing local is removed.
 *
 * Every field defaults, and the reader ignores unknown keys, so a file written by a newer
 * version still opens on an older one.
 */
@Serializable
data class SettingsBackup(
    /** The format version. Bumped only when the meaning of a field changes. */
    val version: Int = CURRENT_VERSION,
    /** When the file was written, in epoch millis. */
    val exportedAtMillis: Long = 0,
    /** The version of the app that wrote it, for the reader's benefit. */
    val appVersion: String = "",
    /** The providers, with their credentials. */
    val sources: List<BackupSource> = emptyList(),
    /** How the app is set up to behave. */
    val preferences: BackupPreferences = BackupPreferences(),
    /** What the user has decided about the channels. */
    val curation: BackupCuration = BackupCuration(),
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

/**
 * One provider, as written down in a settings file.
 *
 * The credentials are here on purpose — see [SettingsBackup]. [macAddress] is a Stalker
 * portal's identity in place of a username and password; [epgUrl] is an explicit XMLTV address
 * for a playlist that ships its own.
 */
@Serializable
data class BackupSource(
    /** [app.opentv.data.model.SourceKind] by name. Read case-insensitively. */
    val kind: String = "XTREAM",
    val name: String = "",
    val url: String = "",
    val username: String? = null,
    val password: String? = null,
    val macAddress: String? = null,
    val epgUrl: String? = null,
    /** The provider's own timezone, e.g. `Europe/Paris` — what its catch-up clock says. */
    val timezone: String? = null,
    val userAgent: String = "",
    /** [app.opentv.data.model.LiveStreamFormat] by name. */
    val liveFormat: String = "HLS",
    val enabled: Boolean = true,
)

/**
 * The preferences that travel.
 *
 * A preference has one value, so a file that carries it carries the answer. The defaults here
 * are the app's own, and they carry meaning: a field still at its default was never chosen by
 * the exporting device, and is therefore not applied on import. The one exception is a value
 * the user deliberately set *to* the default — that is a choice, and it travels.
 */
@Serializable
data class BackupPreferences(
    val themeMode: String = "SYSTEM",
    val channelLayout: String = "GRID",
    val subtitlesEnabled: Boolean = true,
    val bufferPreset: String = "DEFAULT",
    val decoderPreference: String = "AUTO",
    val guideHistory: String = "ONE_DAY",
    val guidePreviewVideo: Boolean = true,
    val guidePreviewSound: Boolean = false,
    val resumeLastChannel: Boolean = false,
    val recordPadStartMinutes: Int = 1,
    val recordPadEndMinutes: Int = 5,
    val recordAutoSwitch: Boolean = true,
    val livePauseEnabled: Boolean = false,
    val contentLive: Boolean = true,
    val contentMovies: Boolean = true,
    val contentSeries: Boolean = true,
    /** BCP-47 tag; blank means "follow the device". */
    val languageTag: String = "",
    /** The player's aspect-ratio mode, as media3's RESIZE_MODE_* constant. */
    val playerResizeMode: Int = 0,
    /** [app.opentv.core.AppSettings.RecordingTarget] by name. */
    val recordingTarget: String = "INTERNAL",
    val smbHost: String = "",
    val smbShare: String = "",
    val smbFolder: String = "",
    val smbUser: String = "",
    val smbPassword: String = "",
    val tmdbApiKey: String = "",
    val stremioAddons: List<StremioAddon> = emptyList(),
)

/**
 * The user's decisions about the channels.
 *
 * Channels are named by their stream URL — the same key the LAN sync uses, because it is the
 * one thing that is the same on two devices talking to the same provider. A URL that the other
 * device has never seen simply matches nothing and is ignored.
 */
@Serializable
data class BackupCuration(
    val favouriteChannels: List<String> = emptyList(),
    val hiddenChannels: List<String> = emptyList(),
    val hiddenCategories: List<String> = emptyList(),
)
