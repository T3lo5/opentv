/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.backup

import app.opentv.core.AppSettings
import app.opentv.data.model.LiveStreamFormat
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.repo.CatalogRepository
import app.opentv.data.repo.SourceRepository
import kotlinx.serialization.json.Json

/**
 * Reads a device's configuration out, and writes one back in.
 *
 * The two directions are deliberately not mirror images of each other:
 *
 *  - **Gathering** takes everything the file is meant to carry, in one pass, so the file is a
 *    snapshot rather than a running commentary.
 *  - **Applying** is where the care is needed, because the file is going onto a device that
 *    already has its own configuration.
 *
 * The three policies, and why they differ:
 *
 *  - **Preferences are restored, but only the ones that were actually chosen.** A value still
 *    at the app's default was never a choice, so it does not travel. Without this rule,
 *    restoring a file written by a phone with no NAS onto a television that had one would wipe
 *    the NAS — the file says "no NAS" because the phone never had one, and that is not the
 *    same as "the NAS should be removed". The rule is deliberately one-sided: a preference
 *    that *was* changed travels even if the new value is the default, because the user chose
 *    it on the other device.
 *  - **Providers are merged.** One already here with the same kind and address is updated —
 *    its credentials may have changed — a new one is added, and nothing is deleted. Importing
 *    a file must never cost you the provider you added by hand.
 *  - **Curation is additive**, the same policy the LAN sync uses: a favourite set on one
 *    device is never lost because another device has a smaller set.
 *
 * The result is reported back in numbers rather than in items, because the screen that asks
 * "what is about to change?" wants a sentence, not a list of four hundred stream URLs.
 */
class SettingsBackupRepository(
    private val sourceRepository: SourceRepository,
    private val catalogRepository: CatalogRepository,
    private val settings: AppSettings,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Reads this device's configuration into a file's worth of data. */
    suspend fun gather(
        appVersion: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): SettingsBackup = SettingsBackup(
        exportedAtMillis = nowMillis,
        appVersion = appVersion,
        sources = sourceRepository.all().map { it.toBackup() },
        preferences = currentPreferences(),
        curation = BackupCuration(
            favouriteChannels = catalogRepository.favouriteChannelUrls(),
            hiddenChannels = catalogRepository.hiddenChannelUrls(),
            hiddenCategories = settings.hiddenCategories.value.toList(),
        ),
    )

    /** This device's preferences, in the form the file stores them. */
    fun currentPreferences(): BackupPreferences = BackupPreferences(
        themeMode = settings.themeMode.value.name,
        channelLayout = settings.channelLayout.value.name,
        subtitlesEnabled = settings.subtitlesEnabled.value,
        bufferPreset = settings.bufferPreset.value.name,
        decoderPreference = settings.decoderPreference.value.name,
        guideHistory = settings.guideHistory.value.name,
        guidePreviewVideo = settings.guidePreviewVideo.value,
        guidePreviewSound = settings.guidePreviewSound.value,
        resumeLastChannel = settings.resumeLastChannel.value,
        recordPadStartMinutes = settings.recordPadStartMinutes.value,
        recordPadEndMinutes = settings.recordPadEndMinutes.value,
        recordAutoSwitch = settings.recordAutoSwitch.value,
        livePauseEnabled = settings.livePauseEnabled.value,
        contentLive = settings.liveEnabled.value,
        contentMovies = settings.moviesEnabled.value,
        contentSeries = settings.seriesEnabled.value,
        languageTag = settings.languageTag.value,
        playerResizeMode = settings.playerResizeMode.value,
        recordingTarget = settings.recordingTarget.value.name,
        smbHost = settings.smbHost.value,
        smbShare = settings.smbShare.value,
        smbFolder = settings.smbFolder.value,
        smbUser = settings.smbUser.value,
        smbPassword = settings.smbPassword.value,
        tmdbApiKey = settings.tmdbApiKey.value,
        stremioAddons = settings.stremioAddons.value,
    )

    fun encode(backup: SettingsBackup): String = json.encodeToString(SettingsBackup.serializer(), backup)

    /**
     * Reads a file. Throws [kotlinx.serialization.SerializationException] if it is not one of
     * ours — the caller shows the user what went wrong rather than importing half of it.
     */
    fun decode(text: String): SettingsBackup = json.decodeFromString(SettingsBackup.serializer(), text)

    /**
     * What importing this file would do, before it does it.
     *
     * Counted against what is already here, so the numbers the user sees are the ones that
     * will actually change something: a provider already present with the same address is an
     * update rather than an addition, and a favourite already set is not new.
     */
    suspend fun preview(backup: SettingsBackup): ImportReport = countChanges(backup)

    /** Writes the file's configuration onto this device. */
    suspend fun apply(backup: SettingsBackup): ImportReport {
        val report = countChanges(backup)

        for (source in backup.sources) {
            val draft = source.toSource(report.sourceIds[sourceIdentity(source)] ?: 0L) ?: continue
            sourceRepository.save(draft)
        }

        applyPreferences(backup.preferences)

        for (url in backup.curation.favouriteChannels) {
            catalogRepository.markChannelFavouriteByUrl(url)
        }
        for (url in backup.curation.hiddenChannels) {
            catalogRepository.markChannelHiddenByUrl(url)
        }
        if (backup.curation.hiddenCategories.isNotEmpty()) {
            settings.setHiddenCategories(
                settings.hiddenCategories.value + backup.curation.hiddenCategories,
            )
        }
        return report
    }

    private suspend fun countChanges(backup: SettingsBackup): ImportReport {
        val localSources = sourceRepository.all().associateBy { sourceIdentity(it.toBackup()) }
        val sourceIds = mutableMapOf<String, Long>()
        var sourcesAdded = 0
        var sourcesUpdated = 0
        var sourcesSkipped = 0

        for (source in backup.sources) {
            val identity = sourceIdentity(source)
            val existing = localSources[identity]
            // A provider the file cannot describe — no address, a kind this build does not know —
            // is counted as skipped rather than silently dropped.
            if (source.toSource(existing?.id ?: 0L) == null) {
                sourcesSkipped++
                continue
            }
            if (existing == null) {
                sourcesAdded++
                sourceIds[identity] = 0L
            } else {
                sourcesUpdated++
                sourceIds[identity] = existing.id
            }
        }

        val localFavourites = catalogRepository.favouriteChannelUrls().toSet()
        val localHidden = catalogRepository.hiddenChannelUrls().toSet()
        val localCategories = settings.hiddenCategories.value

        return ImportReport(
            sourcesAdded = sourcesAdded,
            sourcesUpdated = sourcesUpdated,
            sourcesSkipped = sourcesSkipped,
            favouritesAdded = backup.curation.favouriteChannels.count { it !in localFavourites },
            hiddenAdded = backup.curation.hiddenChannels.count { it !in localHidden },
            hiddenCategoriesAdded = backup.curation.hiddenCategories.count { it !in localCategories },
            preferencesApplied = countPreferenceChanges(backup.preferences),
            sourceIds = sourceIds,
        )
    }

    /**
     * How many of the file's preferences are not already set the same way here.
     *
     * Only the preferences the file actually carries count — the ones it left at the default
     * were never chosen and will not be applied — and a preference already set the same way is
     * not a change, so rewriting twenty settings to the values they already hold is noise in
     * the report and tells the user nothing.
     */
    private fun countPreferenceChanges(preferences: BackupPreferences): Int {
        val defaults = BackupPreferences()
        val current = currentPreferences()
        var changed = 0
        fun differs(chosen: Any?, chosenDefault: Any?, here: Any?) {
            if (chosen != chosenDefault && chosen != here) changed++
        }
        differs(preferences.themeMode, defaults.themeMode, current.themeMode)
        differs(preferences.channelLayout, defaults.channelLayout, current.channelLayout)
        differs(preferences.subtitlesEnabled, defaults.subtitlesEnabled, current.subtitlesEnabled)
        differs(preferences.bufferPreset, defaults.bufferPreset, current.bufferPreset)
        differs(preferences.decoderPreference, defaults.decoderPreference, current.decoderPreference)
        differs(preferences.guideHistory, defaults.guideHistory, current.guideHistory)
        differs(preferences.guidePreviewVideo, defaults.guidePreviewVideo, current.guidePreviewVideo)
        differs(preferences.guidePreviewSound, defaults.guidePreviewSound, current.guidePreviewSound)
        differs(preferences.resumeLastChannel, defaults.resumeLastChannel, current.resumeLastChannel)
        differs(preferences.recordPadStartMinutes, defaults.recordPadStartMinutes, current.recordPadStartMinutes)
        differs(preferences.recordPadEndMinutes, defaults.recordPadEndMinutes, current.recordPadEndMinutes)
        differs(preferences.recordAutoSwitch, defaults.recordAutoSwitch, current.recordAutoSwitch)
        differs(preferences.livePauseEnabled, defaults.livePauseEnabled, current.livePauseEnabled)
        differs(preferences.contentLive, defaults.contentLive, current.contentLive)
        differs(preferences.contentMovies, defaults.contentMovies, current.contentMovies)
        differs(preferences.contentSeries, defaults.contentSeries, current.contentSeries)
        differs(preferences.languageTag, defaults.languageTag, current.languageTag)
        differs(preferences.playerResizeMode, defaults.playerResizeMode, current.playerResizeMode)
        differs(preferences.recordingTarget, defaults.recordingTarget, current.recordingTarget)
        differs(preferences.smbHost, defaults.smbHost, current.smbHost)
        differs(preferences.smbShare, defaults.smbShare, current.smbShare)
        differs(preferences.smbFolder, defaults.smbFolder, current.smbFolder)
        differs(preferences.smbUser, defaults.smbUser, current.smbUser)
        differs(preferences.smbPassword, defaults.smbPassword, current.smbPassword)
        differs(preferences.tmdbApiKey, defaults.tmdbApiKey, current.tmdbApiKey)
        if (preferences.stremioAddons.isNotEmpty() &&
            preferences.stremioAddons.map { it.manifestUrl }.toSet() !=
            current.stremioAddons.map { it.manifestUrl }.toSet()
        ) {
            changed++
        }
        return changed
    }

    /**
     * Writes the file's preferences onto this device.
     *
     * Each preference is applied only when the file's value is not the app's default — that is
     * the file saying "this was chosen" — and the device keeps its own value otherwise.
     */
    private fun applyPreferences(preferences: BackupPreferences) {
        val defaults = BackupPreferences()
        if (preferences.themeMode != defaults.themeMode) {
            settings.setThemeMode(enumOrDefault(preferences.themeMode, settings.themeMode.value))
        }
        if (preferences.channelLayout != defaults.channelLayout) {
            settings.setChannelLayout(enumOrDefault(preferences.channelLayout, settings.channelLayout.value))
        }
        if (preferences.subtitlesEnabled != defaults.subtitlesEnabled) {
            settings.setSubtitlesEnabled(preferences.subtitlesEnabled)
        }
        if (preferences.bufferPreset != defaults.bufferPreset) {
            settings.setBufferPreset(enumOrDefault(preferences.bufferPreset, settings.bufferPreset.value))
        }
        if (preferences.decoderPreference != defaults.decoderPreference) {
            settings.setDecoderPreference(enumOrDefault(preferences.decoderPreference, settings.decoderPreference.value))
        }
        if (preferences.guideHistory != defaults.guideHistory) {
            settings.setGuideHistory(enumOrDefault(preferences.guideHistory, settings.guideHistory.value))
        }
        if (preferences.guidePreviewVideo != defaults.guidePreviewVideo) {
            settings.setGuidePreviewVideo(preferences.guidePreviewVideo)
        }
        if (preferences.guidePreviewSound != defaults.guidePreviewSound) {
            settings.setGuidePreviewSound(preferences.guidePreviewSound)
        }
        if (preferences.resumeLastChannel != defaults.resumeLastChannel) {
            settings.setResumeLastChannel(preferences.resumeLastChannel)
        }
        if (preferences.recordPadStartMinutes != defaults.recordPadStartMinutes ||
            preferences.recordPadEndMinutes != defaults.recordPadEndMinutes
        ) {
            settings.setRecordPadding(preferences.recordPadStartMinutes, preferences.recordPadEndMinutes)
        }
        if (preferences.recordAutoSwitch != defaults.recordAutoSwitch) {
            settings.setRecordAutoSwitch(preferences.recordAutoSwitch)
        }
        if (preferences.livePauseEnabled != defaults.livePauseEnabled) {
            settings.setLivePauseEnabled(preferences.livePauseEnabled)
        }
        if (preferences.contentLive != defaults.contentLive) {
            settings.setLiveEnabled(preferences.contentLive)
        }
        if (preferences.contentMovies != defaults.contentMovies) {
            settings.setMoviesEnabled(preferences.contentMovies)
        }
        if (preferences.contentSeries != defaults.contentSeries) {
            settings.setSeriesEnabled(preferences.contentSeries)
        }
        if (preferences.languageTag != defaults.languageTag) {
            settings.setLanguageTag(preferences.languageTag)
        }
        if (preferences.playerResizeMode != defaults.playerResizeMode) {
            settings.setPlayerResizeMode(preferences.playerResizeMode)
        }
        if (preferences.recordingTarget != defaults.recordingTarget) {
            settings.setRecordingTarget(enumOrDefault(preferences.recordingTarget, settings.recordingTarget.value))
        }
        // The NAS is one setting spread over four fields, and a host on its own is not a NAS:
        // applying it only when there is a host keeps a file written by a device with no NAS
        // from wiping the one this device has.
        if (preferences.smbHost.isNotBlank()) {
            settings.setSmbConfig(
                preferences.smbHost,
                preferences.smbShare,
                preferences.smbFolder,
                preferences.smbUser,
                preferences.smbPassword,
            )
        }
        if (preferences.tmdbApiKey.isNotBlank()) {
            settings.setTmdbApiKey(preferences.tmdbApiKey)
        }
        // The add-ons are replaced wholesale rather than merged: the file is a snapshot, and an
        // add-on removed on the other device should be gone here too. An empty list is the
        // default, so it means "no choice" and the device keeps its own.
        if (preferences.stremioAddons.isNotEmpty()) {
            for (addon in settings.stremioAddons.value) {
                if (preferences.stremioAddons.none { it.manifestUrl == addon.manifestUrl }) {
                    settings.removeStremioAddon(addon.manifestUrl)
                }
            }
            for (addon in preferences.stremioAddons) {
                if (settings.stremioAddons.value.none { it.manifestUrl == addon.manifestUrl }) {
                    settings.addStremioAddon(addon)
                }
            }
        }
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(value: String, fallback: T): T =
        runCatching { enumValueOf<T>(value.trim().uppercase()) }.getOrDefault(fallback)

    private fun Source.toBackup() = BackupSource(
        kind = kind.name,
        name = name,
        url = url,
        username = username,
        password = password,
        macAddress = macAddress,
        epgUrl = epgUrl,
        timezone = timezone,
        userAgent = userAgent,
        liveFormat = liveFormat.name,
        enabled = enabled,
    )

    /**
     * How a provider is recognised across two devices: its kind and the address it lives at.
     *
     * The address is normalised the same way on both sides, so a file written by a build that
     * stored `http://box:8080/` finds the `http://box:8080` this build stored.
     */
    private fun sourceIdentity(source: BackupSource): String {
        val kind = runCatching { SourceKind.valueOf(source.kind.trim().uppercase()) }.getOrNull()
        val url = if (kind != null) SourceRepository.normaliseUrl(source.url, kind) else source.url.trim()
        return "${kind?.name ?: source.kind.trim().uppercase()}|$url"
    }

    /** Turns a file's provider into one this app can save, or null if it cannot be used. */
    private fun BackupSource.toSource(existingId: Long): Source? {
        val k = runCatching { SourceKind.valueOf(kind.trim().uppercase()) }.getOrNull() ?: return null
        if (url.isBlank()) return null
        return Source(
            id = existingId,
            name = name.trim().ifBlank { if (k == SourceKind.M3U) "Playlist" else "Provider" },
            kind = k,
            url = url.trim(),
            username = username?.takeIf { it.isNotBlank() },
            password = password?.takeIf { it.isNotBlank() },
            macAddress = macAddress?.takeIf { it.isNotBlank() },
            epgUrl = epgUrl?.takeIf { it.isNotBlank() },
            timezone = timezone?.takeIf { it.isNotBlank() },
            userAgent = userAgent.ifBlank { Source.DEFAULT_USER_AGENT },
            liveFormat = runCatching {
                LiveStreamFormat.valueOf(liveFormat.trim().uppercase())
            }.getOrDefault(LiveStreamFormat.HLS),
            enabled = enabled,
        )
    }
}

/**
 * What an import did, or would do.
 *
 * [sourceIds] maps each provider in the file to the local id it now has — zero for one that is
 * new, so the apply step knows to insert rather than update.
 */
data class ImportReport(
    val sourcesAdded: Int = 0,
    val sourcesUpdated: Int = 0,
    val sourcesSkipped: Int = 0,
    val favouritesAdded: Int = 0,
    val hiddenAdded: Int = 0,
    val hiddenCategoriesAdded: Int = 0,
    val preferencesApplied: Int = 0,
    val sourceIds: Map<String, Long> = emptyMap(),
)
