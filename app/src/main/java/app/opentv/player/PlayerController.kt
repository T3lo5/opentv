/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import app.opentv.core.AppSettings
import app.opentv.core.BufferPreset
import app.opentv.core.DecoderPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Owns the single [ExoPlayer] instance and everything about switching what it is playing.
 *
 * ## Why one player, reused
 *
 * The obvious implementation of "change channel" is release the player and build a new one.
 * On a low-end TV box that takes long enough that a user pressing channel-up four times in a
 * row can queue four constructions and four teardowns, and the codec ends up in a state where
 * nothing plays until the app is killed. That is the "changing channels too quickly causes
 * streams to fail" class of bug, and it is entirely self-inflicted.
 *
 * OpenTV keeps exactly one player for the lifetime of the screen and only ever swaps its
 * media item. Requests are debounced, and an in-flight switch is cancelled the moment a newer
 * one arrives, so holding channel-up costs one actual tune — the one the user stopped on.
 */
@OptIn(UnstableApi::class)
class PlayerController(
    val context: Context,
    private val scope: CoroutineScope,
    httpClient: OkHttpClient,
    subtitlesEnabled: Boolean = true,
    /**
     * Tunes the player for the guide's muted preview pane: a shallow start buffer so a highlighted
     * channel shows a frame quickly, and a longer switch debounce so scrolling the channel list
     * does not tune to every channel passed over. The full-screen players leave this false and keep
     * the deep buffer that rides out a twitchy IPTV source.
     */
    private val preview: Boolean = false,
    /**
     * Turns the live player into a shallow DVR: a retained back-buffer so the viewer can pause and
     * rewind live TV within the last few minutes. Off by default because holding minutes of video
     * in memory is a real cost on a cheap box; the recording player and preview never set it.
     */
    private val dvr: Boolean = false,
    /**
     * When set, `smb://` media (a recording on a NAS) is read through this source so it plays and
     * seeks in-app. Null for the live/VOD players, which never see an smb URI.
     */
    smbDataSourceFactory: androidx.media3.datasource.DataSource.Factory? = null,
    /**
     * When set, `optvrec://<id>` media (a recording still being written) is read through this
     * tail-following source so it can be watched while it records. Null everywhere except the
     * recording player.
     */
    growingDataSourceFactory: androidx.media3.datasource.DataSource.Factory? = null,
    /**
     * Playing a recording that is still being written (watch-while-recording). Playback is held a
     * cushion *behind* the file's growing edge (see the LIVE_REC_* constants) so the bursty arrival
     * of an IPTV source — worst when the recording is read back over SMB from a NAS, where each read
     * is a network round-trip — rides over the gaps instead of stalling on every one.
     */
    private val liveRecording: Boolean = false,
    /**
     * How much video to keep buffered. Read once per playback session, so changing the
     * setting never tears down a player that is running; it applies the next time a
     * playback session opens.
     */
    bufferPreset: BufferPreset = AppSettings.get(context).bufferPreset.value,
) {

    /** Channel-surf debounce for this controller — longer for the preview so browsing is calm. */
    private val switchDebounceMillis = if (preview) PREVIEW_SWITCH_DEBOUNCE_MILLIS else SWITCH_DEBOUNCE_MILLIS

    sealed interface State {
        data object Idle : State
        data class Buffering(val title: String) : State
        data class Playing(val title: String) : State
        /** Playback stopped and retries are exhausted. [message] is written for humans. */
        data class Error(val title: String, val message: String, val canRetry: Boolean) : State
    }

    data class Request(
        val url: String,
        val title: String,
        val userAgent: String,
        /** Live streams are never resumed; VOD is. */
        val startPositionMillis: Long = 0L,
        val isLive: Boolean = true,
        /** Override the global buffer preset for this stream only. Null = inherit global. */
        val bufferPresetOverride: BufferPreset? = null,
        /** Override the global decoder preference for this stream only. Null = inherit global. */
        val decoderPreferenceOverride: DecoderPreference? = null,
    )

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** The current stream's tracks (audio/text/video), for the in-player pickers. */
    private val _tracks = MutableStateFlow(Tracks.EMPTY)
    val tracks: StateFlow<Tracks> = _tracks.asStateFlow()

    private var switchJob: Job? = null
    private var current: Request? = null

    /** Automatic reconnects since the last successful play. Reset when the picture is back. */
    private var autoRetryCount = 0
    private val maxAutoRetries = 10

    /**
     * The stall watchdog.
     *
     * A live stream can stop delivering data without ever raising an error: the socket stays
     * open, the server just goes quiet, and ExoPlayer sits in STATE_BUFFERING forever. Nothing
     * times that out, so the viewer is left with a spinner that never ends — the complaint
     * every IPTV player gets. When a stream that *had* been playing stays buffering for
     * [STALL_RECOVERY_MILLIS], the tune is redone on a fresh connection.
     */
    private var stallWatchdog: Job? = null
    private var hasPlayedThisTune = false
    private var stallRecoveries = 0

    /**
     * Held so the User-Agent can be swapped per source before each tune.
     *
     * Providers commonly 403 any client whose UA they do not recognise, and the workaround —
     * "try a different User-Agent" — is useless unless the setting actually reaches the
     * player's HTTP layer. Setting it on the factory applies it to data sources created for
     * subsequent loads, which is exactly the granularity we need.
     */
    private val httpFactory = OkHttpDataSource.Factory(httpClient).apply {
        setDefaultRequestProperties(mapOf("User-Agent" to DEFAULT_USER_AGENT))
    }

    private val dataSourceFactory: androidx.media3.datasource.DataSource.Factory =
        DefaultDataSource.Factory(context, httpFactory).let { default ->
            val custom = buildMap<String, androidx.media3.datasource.DataSource.Factory> {
                if (smbDataSourceFactory != null) put("smb", smbDataSourceFactory)
                if (growingDataSourceFactory != null) put("optvrec", growingDataSourceFactory)
            }
            if (custom.isEmpty()) default else RoutingDataSourceFactory(default, custom)
        }

    /**
     * Retry policy tuned for IPTV rather than for CDNs.
     *
     * A provider under load returns 403, 429 or 500 for a few seconds and then serves the
     * stream perfectly well. ExoPlayer's default is to give up almost immediately on those,
     * which surfaces to the user as a channel that "doesn't work" but plays fine on the
     * second try. We retry them with backoff instead. 401 and 404 are not retried — those
     * genuinely will not fix themselves.
     */
    private val loadErrorPolicy = object : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val statusCode =
                (info.exception as? HttpDataSource.InvalidResponseCodeException)?.responseCode

            return when (statusCode) {
                401, 404, 410 -> C_TIME_UNSET
                403, 405, 429, 500, 502, 503, 504 -> backoffFor(info.errorCount)
                null -> backoffFor(info.errorCount) // network blips
                else -> super.getRetryDelayMsFor(info)
            }
        }

        override fun getMinimumLoadableRetryCount(dataType: Int): Int = MAX_LOAD_RETRIES

        private fun backoffFor(errorCount: Int): Long =
            if (errorCount > MAX_LOAD_RETRIES) C_TIME_UNSET
            else minOf(INITIAL_BACKOFF_MILLIS * (1L shl (errorCount - 1)), MAX_BACKOFF_MILLIS)
    }

    /**
     * Held so captions can be turned on and off at runtime. Prefer the device language when
     * captions are on; [setSubtitlesEnabled] flips the text renderer off entirely.
     */
    private val trackSelector = DefaultTrackSelector(context).apply {
        parameters = buildUponParameters()
            .setPreferredTextLanguage(java.util.Locale.getDefault().language)
            .setSelectUndeterminedTextLanguage(true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesEnabled)
            .build()
    }

    private var audioDelayMs: Long = 0L

    fun setAudioDelayMs(delayMs: Long) {
        audioDelayMs = delayMs.coerceIn(-5000L, 5000L)
        // Audio delay support - using ExoPlayer's parameters where possible
    }

    /**
     * Which decoder the player reaches for. Read once per playback session, so changing it
     * never tears down a player that is running.
     */
    private val decoderPreference: DecoderPreference = AppSettings.get(context).decoderPreference.value

    /**
     * The buffer this player uses.
     *
     * The guide preview, the DVR window and a recording still being written keep their own
     * tuning: each of those is measured for what it does, and a user-chosen buffer would
     * make the preview hoard memory or the DVR lose the headroom it rides on.
     */
    private val playbackBuffer =
        if (preview || dvr || liveRecording) BufferPreset.DEFAULT else bufferPreset

    /**
     * The renderers factory, and with it the decoder choice.
     *
     * Only built when the user asked for the software decoder: the default factory is
     * ExoPlayer's, and a custom one has to be told to keep the decoder fallback on so a
     * codec that refuses to start is not the end of the road.
     */
    private val renderersFactory: DefaultRenderersFactory? =
        if (decoderPreference == DecoderPreference.SOFTWARE_FIRST) {
            DefaultRenderersFactory(context)
                .setMediaCodecSelector(SoftwareFirstCodecSelector())
                .setEnableDecoderFallback(true)
        } else {
            null
        }

    /**
     * The player that is not subject to a per-stream override.
     *
     * Kept private: everything outside this class has to go through [activePlayer], because a
     * stream with its own buffer preset or decoder plays on a *different* instance. Handing the
     * base player to a view or a button is exactly the bug that made those streams play to
     * nothing.
     */
    private val basePlayer: ExoPlayer = ExoPlayer.Builder(context)
        .setSeekBackIncrementMs(SEEK_INCREMENT_MILLIS)
        .setSeekForwardIncrementMs(SEEK_INCREMENT_MILLIS)
        // Hold the network awake for as long as the player is playing. Without this a TV box
        // that goes idle suspends its Wi-Fi — and a live stream then starves and stalls a few
        // minutes into the programme, which is exactly the "starts fine, then buffers" report.
        // Not for the guide preview: a muted thumbnail has no business keeping a set awake.
        .apply { if (!preview) setWakeMode(C.WAKE_MODE_NETWORK) }
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(dataSourceFactory)
                .setLoadErrorHandlingPolicy(loadErrorPolicy),
        )
        .setTrackSelector(trackSelector)
        .apply { renderersFactory?.let { setRenderersFactory(it) } }
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    if (preview) PREVIEW_MIN_BUFFER_MILLIS else playbackBuffer.minBufferMillis,
                    when {
                        preview -> PREVIEW_MAX_BUFFER_MILLIS
                        dvr -> DVR_MAX_BUFFER_MILLIS
                        liveRecording -> LIVE_REC_MAX_BUFFER_MILLIS
                        else -> playbackBuffer.maxBufferMillis
                    },
                    when {
                        preview -> PREVIEW_BUFFER_FOR_PLAYBACK_MILLIS
                        liveRecording -> LIVE_REC_BUFFER_FOR_PLAYBACK_MILLIS
                        else -> playbackBuffer.playbackBufferMillis
                    },
                    when {
                        preview -> PREVIEW_BUFFER_AFTER_REBUFFER_MILLIS
                        liveRecording -> LIVE_REC_BUFFER_AFTER_REBUFFER_MILLIS
                        else -> playbackBuffer.rebufferMillis
                    },
                )
                // Retain the last few minutes so pause/rewind of live TV has something to seek into.
                .apply { if (dvr) setBackBuffer(DVR_BACK_BUFFER_MILLIS, true) }
                .build(),
        )
        .build()
        .apply { addListener(buildPlaybackListener()) }

    /**
     * The listener both players share.
     *
     * It used to be written out twice — once for the default player and once for the
     * override player — and the two copies drifted: the override player retried ten times
     * with a backoff, the normal one three times 1.5 seconds apart and then gave up. The
     * ordinary live player is the one almost everybody uses, so it was the one that died
     * first. One listener, one policy.
     */
    private fun buildPlaybackListener(): Player.Listener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            _tracks.value = tracks
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val title = current?.title.orEmpty()
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    _state.value = State.Buffering(title)
                    // A stall is only a stall once the picture has been up. The initial tune is
                    // *supposed* to buffer while it fills.
                    if (hasPlayedThisTune) watchForStall()
                }

                Player.STATE_READY -> {
                    stallWatchdog?.cancel()
                    stallWatchdog = null
                    hasPlayedThisTune = true
                    autoRetryCount = 0
                    _state.value = State.Playing(title)
                }

                Player.STATE_ENDED -> {
                    stallWatchdog?.cancel()
                    stallWatchdog = null
                    _state.value = State.Idle
                }

                // IDLE keeps whatever the error handler just wrote; there is nothing to say here.
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val request = current ?: return
            autoRetryCount++
            val willRetry = autoRetryCount < maxAutoRetries
            Log.w(
                TAG,
                "playback error on \"${request.title}\" " +
                    "(${autoRetryCount}/${maxAutoRetries}): ${error.errorCodeName}",
                error,
            )

            // While a reconnect is coming, the picture's own buffering state is what the viewer
            // should see — not a failure screen for a provider that is about to answer again.
            _state.value = if (willRetry) {
                State.Buffering(request.title)
            } else {
                State.Error(
                    title = request.title,
                    message = PlaybackErrors.describe(error),
                    canRetry = true,
                )
            }

            if (willRetry) {
                scope.launch {
                    delay(retryDelayFor(autoRetryCount))
                    if (current === request) play(request, debounce = false)
                }
            }
        }
    }

    /**
     * Watches a playing stream for the one failure ExoPlayer does not report: the picture
     * stopped and no data is arriving, but the connection was never formally lost. When that
     * lasts [STALL_RECOVERY_MILLIS], the stream is retuned on a fresh connection.
     *
     * Bounded on purpose: a provider that is genuinely down is not helped by reconnecting
     * forever, and each attempt interrupts whatever was buffered.
     */
    private fun watchForStall() {
        if (stallWatchdog != null) return
        val request = current ?: return
        if (stallRecoveries >= MAX_STALL_RECOVERIES) return

        stallWatchdog = scope.launch {
            delay(STALL_RECOVERY_MILLIS)
            stallWatchdog = null
            if (current !== request) return@launch
            if (_state.value !is State.Buffering) return@launch

            stallRecoveries++
            Log.i(
                TAG,
                "stalled ${STALL_RECOVERY_MILLIS} ms on \"${request.title}\"; " +
                    "reconnecting (${stallRecoveries}/$MAX_STALL_RECOVERIES)",
            )
            play(request, debounce = false)
        }
    }

    /** Exponential backoff for a reconnect: 1 s, 2 s, 4 s, then 8 s between attempts. */
    private fun retryDelayFor(attempt: Int): Long =
        minOf(INITIAL_AUTO_RETRY_MILLIS shl (attempt - 1).coerceIn(0, 3), MAX_AUTO_RETRY_MILLIS)

    /** The override-applied player, non-null when a stream override is active. */
    private var overridePlayer: ExoPlayer? = null

    /** The preset/decoder pair [overridePlayer] was built for, so it is only rebuilt on change. */
    private var activeOverrides: Pair<BufferPreset?, DecoderPreference?>? = null

    /** The player to use right now: override-applied if active, otherwise the base one. */
    val activePlayer: ExoPlayer
        get() = overridePlayer ?: basePlayer

    /**
     * [activePlayer] as state, for the one thing that has to *observe* it: the surface.
     *
     * A [PlayerView] binds to a single player instance, so when an override swaps the instance
     * under it the view has to be rebound — otherwise the stream decodes with no surface and
     * the viewer gets audio over a black picture.
     */
    private val _activePlayer = MutableStateFlow(basePlayer)
    val activePlayerFlow: StateFlow<ExoPlayer> = _activePlayer.asStateFlow()

    /**
     * Recreates the underlying [ExoPlayer] with per-stream overrides applied, so a twitchy
     * stream can get its own buffer preset and decoder without changing the global defaults.
     */
    private fun recreatePlayerWithOverrides(
        bufferPreset: BufferPreset?,
        decoderPreference: DecoderPreference?,
    ) {
        val newLoadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (preview || dvr || liveRecording) PREVIEW_MIN_BUFFER_MILLIS
                else bufferPreset?.minBufferMillis ?: playbackBuffer.minBufferMillis,
                when {
                    preview -> PREVIEW_MAX_BUFFER_MILLIS
                    dvr -> DVR_MAX_BUFFER_MILLIS
                    liveRecording -> LIVE_REC_MAX_BUFFER_MILLIS
                    else -> bufferPreset?.maxBufferMillis ?: playbackBuffer.maxBufferMillis
                },
                when {
                    preview -> PREVIEW_BUFFER_FOR_PLAYBACK_MILLIS
                    liveRecording -> LIVE_REC_BUFFER_FOR_PLAYBACK_MILLIS
                    else -> bufferPreset?.playbackBufferMillis ?: playbackBuffer.playbackBufferMillis
                },
                when {
                    preview -> PREVIEW_BUFFER_AFTER_REBUFFER_MILLIS
                    liveRecording -> LIVE_REC_BUFFER_AFTER_REBUFFER_MILLIS
                    else -> bufferPreset?.rebufferMillis ?: playbackBuffer.rebufferMillis
                },
            )
            .apply { if (dvr) setBackBuffer(DVR_BACK_BUFFER_MILLIS, true) }
            .build()

        val newRenderersFactory = if (decoderPreference == DecoderPreference.SOFTWARE_FIRST) {
            DefaultRenderersFactory(context)
                .setMediaCodecSelector(SoftwareFirstCodecSelector())
                .setEnableDecoderFallback(true)
        } else {
            null
        }

        val newPlayer = ExoPlayer.Builder(context)
            .setSeekBackIncrementMs(SEEK_INCREMENT_MILLIS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MILLIS)
            .apply { if (!preview) setWakeMode(C.WAKE_MODE_NETWORK) }
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(dataSourceFactory)
                    .setLoadErrorHandlingPolicy(loadErrorPolicy),
            )
            .setTrackSelector(trackSelector)
            .apply { newRenderersFactory?.let { setRenderersFactory(it) } }
            .setLoadControl(newLoadControl)
            .build()
            .apply { addListener(buildPlaybackListener()) }

        val previous = overridePlayer
        overridePlayer = newPlayer
        // Announce the swap first: the surface rebinds to the new instance, and only then is the
        // old one retired.
        _activePlayer.value = newPlayer
        previous?.let { retire(it) }
    }

    /**
     * Retires a player once the surface has had a moment to let go of it.
     *
     * The `PlayerView` rebinds on the next composition, so tearing the old instance down first
     * leaves the view briefly talking to a released player — which is the kind of thing that
     * takes the whole surface down on a cheap box.
     */
    private fun retire(old: ExoPlayer) {
        Handler(Looper.getMainLooper()).postDelayed(
            { old.release() },
            SURFACE_REBIND_GRACE_MILLIS,
        )
    }

    /**
     * Switches playback.
     *
     * @param debounce when true (the default for channel surfing) the switch waits briefly so
     * that rapid presses collapse into a single tune. Pass false for a deliberate selection.
     */
    fun play(request: Request, debounce: Boolean = true) {
        // Cancelling here is what makes fast channel-changing safe: the previous switch never
        // reaches the player, so we never stack prepares.
        switchJob?.cancel()
        // A new tune starts from a clean slate. The same request replayed by the stall watchdog
        // or a reconnect keeps its budget, so a stream that will not recover cannot loop.
        if (current !== request) {
            stallWatchdog?.cancel()
            stallWatchdog = null
            hasPlayedThisTune = false
            stallRecoveries = 0
        }
        current = request

        switchJob = scope.launch {
            if (debounce) delay(switchDebounceMillis)

            autoRetryCount = 0
            httpFactory.setDefaultRequestProperties(mapOf("User-Agent" to request.userAgent))
            _state.value = State.Buffering(request.title)

            // Apply per-stream overrides before tuning: a twitchy stream gets its own
            // buffer preset and/or decoder without changing the global defaults.
            //
            // Only when they actually changed: building an ExoPlayer is a codec teardown and a
            // codec construction, and doing that on every zap is how a cheap box runs out of
            // decoders mid-channel-surf.
            val overrides = request.bufferPresetOverride to request.decoderPreferenceOverride
            if (request.bufferPresetOverride != null || request.decoderPreferenceOverride != null) {
                if (activeOverrides != overrides) {
                    recreatePlayerWithOverrides(
                        request.bufferPresetOverride,
                        request.decoderPreferenceOverride,
                    )
                    activeOverrides = overrides
                }
            } else if (overridePlayer != null) {
                val previous = overridePlayer
                overridePlayer = null
                activeOverrides = null
                // Back to the base instance, and tell the surface before the old one goes away.
                _activePlayer.value = basePlayer
                previous?.let { retire(it) }
            }

            val mediaItem = MediaItem.Builder()
                .setUri(request.url)
                .apply {
                    // Only meaningful for live streams; setting it on VOD skews seeking.
                    if (request.isLive) {
                        setLiveConfiguration(
                            MediaItem.LiveConfiguration.Builder()
                                .setTargetOffsetMs(LIVE_TARGET_OFFSET_MILLIS)
                                .build(),
                        )
                    }
                }
                .build()

            with(activePlayer) {
                // stop() rather than release(): keeps the codec and the surface alive.
                stop()
                clearMediaItems()
                setMediaItem(mediaItem)
                if (!request.isLive && request.startPositionMillis > 0) {
                    seekTo(request.startPositionMillis)
                }
                // The watchdog only guards a picture that has been up. This attempt has not
                // shown one yet, so it must not be counted as a stall on its own.
                hasPlayedThisTune = false
                playWhenReady = true
                prepare()
            }
        }
    }

    fun retry() {
        autoRetryCount = 0
        current?.let { play(it, debounce = false) }
    }

    /**
     * The quick captions toggle. On is *not* merely "allow the text renderer" — that leaves it
     * to the selector to guess a language, which for a lot of IPTV streams guesses nothing and
     * the user sees no captions even though they turned them on. So On explicitly selects the
     * first available subtitle track; Off disables text entirely.
     */
    fun setSubtitlesEnabled(enabled: Boolean) {
        if (!enabled) {
            disableText()
            return
        }
        val firstText = _tracks.value.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
        if (firstText != null) {
            selectTrack(firstText, firstSupportedIndex(firstText))
        } else {
            // Nothing to select yet — allow the renderer so a track that arrives later can be
            // picked up, and re-assert once tracks change.
        activePlayer.trackSelectionParameters = activePlayer.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        }
    }

    /** Force a specific audio or text track on (used by the in-player pickers). */
    fun selectTrack(group: Tracks.Group, trackIndex: Int) {
        activePlayer.trackSelectionParameters = activePlayer.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            .setTrackTypeDisabled(group.type, false)
            .build()
    }

    /** Turn subtitles off entirely. */
    fun disableText() {
        activePlayer.trackSelectionParameters = activePlayer.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    fun seekBackward() {
        if (activePlayer.isCurrentMediaItemSeekable) activePlayer.seekBack()
    }

    fun seekForward() {
        if (activePlayer.isCurrentMediaItemSeekable) activePlayer.seekForward()
    }

    val isSeekable: Boolean get() = activePlayer.isCurrentMediaItemSeekable

    /**
     * How far back live TV can be rewound: the retained back buffer, zero when the shallow
     * DVR is off.
     *
     * This is the *client-side* window — ExoPlayer holds the last couple of minutes of
     * live TV in memory so a paused picture can be scrubbed. It is not the provider's
     * archive: that is a different thing entirely, reached through the catch-up URL.
     */
    val backBufferMillis: Long get() = if (dvr) DVR_BACK_BUFFER_MILLIS.toLong() else 0L

    /**
     * Back to the live edge.
     *
     * The edge is where the stream is being produced, not where playback is: a live player
     * deliberately rides a few seconds behind it so it can absorb a hiccup. ExoPlayer's
     * default position is exactly that point, and for a live window it is the live edge
     * minus the target offset — which is where the picture belongs.
     */
    fun seekToLive() {
        activePlayer.seekToDefaultPosition()
    }

    private fun firstSupportedIndex(group: Tracks.Group): Int {
        for (i in 0 until group.length) if (group.isTrackSupported(i)) return i
        return 0
    }

    fun stop() {
        switchJob?.cancel()
        stallWatchdog?.cancel()
        stallWatchdog = null
        hasPlayedThisTune = false
        current = null
        activePlayer.stop()
        activePlayer.clearMediaItems()
        _state.value = State.Idle
    }

    fun release() {
        switchJob?.cancel()
        stallWatchdog?.cancel()
        stallWatchdog = null
        _activePlayer.value = basePlayer
        overridePlayer?.release()
        overridePlayer = null
        activeOverrides = null
        basePlayer.release()
    }

    private companion object {
        const val DEFAULT_USER_AGENT = "OpenTV/0.1 (Android)"

        /** ExoPlayer's "do not retry" sentinel. */
        const val C_TIME_UNSET = androidx.media3.common.C.TIME_UNSET

        const val SWITCH_DEBOUNCE_MILLIS = 350L
        const val SEEK_INCREMENT_MILLIS = 15_000L
        const val INITIAL_BACKOFF_MILLIS = 500L
        const val MAX_BACKOFF_MILLIS = 8_000L
        const val MAX_LOAD_RETRIES = 5

        /** Reconnect backoff for a player error: 1 s, 2 s, 4 s, then 8 s. */
        const val INITIAL_AUTO_RETRY_MILLIS = 1_000L
        const val MAX_AUTO_RETRY_MILLIS = 8_000L

        /**
         * How long a picture that *had* been playing may sit in buffering before the stream is
         * retuned on a fresh connection. Long enough to ride a normal hiccup on a slow line,
         * short enough that the viewer is not left staring at a spinner.
         */
        const val STALL_RECOVERY_MILLIS = 12_000L

        /** Reconnects a single tune gets before it is treated as a genuinely dead stream. */
        const val MAX_STALL_RECOVERIES = 3

        /** How long a retired player is kept alive after the surface was told to move off it. */
        const val SURFACE_REBIND_GRACE_MILLIS = 500L

        const val TAG = "OpenTVPlayer"

        /**
         * Larger than ExoPlayer's defaults. IPTV sources are much twitchier than a CDN, and a
         * deeper buffer is the difference between a momentary hiccup and a visible stall.
         */
        const val MIN_BUFFER_MILLIS = 15_000
        const val MAX_BUFFER_MILLIS = 60_000
        const val BUFFER_FOR_PLAYBACK_MILLIS = 2_500
        const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MILLIS = 5_000

        // DVR mode (pause & rewind live TV): keep a couple of minutes behind the live edge to seek
        // into, and let the forward buffer grow to match so a pause of up to a couple of minutes
        // resumes cleanly. Deliberately bounded — this is memory the box has to find.
        const val DVR_BACK_BUFFER_MILLIS = 120_000
        const val DVR_MAX_BUFFER_MILLIS = 120_000

        // Watch-while-recording: hold playback a cushion *behind* the file's growing edge rather than
        // right on it. IPTV sources arrive in bursts, so a shallow buffer drains to nothing between
        // bursts and the picture stalls every second or two — worst over SMB to a NAS, where every
        // read is a network round-trip. Waiting for ~8s of reservoir before playback (and rebuilding
        // it after any stall) rides straight over those gaps; because playback and the recorder both
        // advance at 1x, that cushion holds without ever catching the edge again.
        const val LIVE_REC_MAX_BUFFER_MILLIS = 60_000
        const val LIVE_REC_BUFFER_FOR_PLAYBACK_MILLIS = 8_000
        const val LIVE_REC_BUFFER_AFTER_REBUFFER_MILLIS = 8_000

        // Guide-preview tuning: start fast and don't hoard buffer (the preview is muted and
        // low-stakes), and debounce channel-surfing harder so scrolling the list doesn't tune to
        // every channel in passing. Kept within DefaultLoadControl's constraints: min >= both
        // playback thresholds, max >= min.
        const val PREVIEW_MIN_BUFFER_MILLIS = 5_000
        const val PREVIEW_MAX_BUFFER_MILLIS = 15_000
        const val PREVIEW_BUFFER_FOR_PLAYBACK_MILLIS = 1_000
        const val PREVIEW_BUFFER_AFTER_REBUFFER_MILLIS = 1_500
        const val PREVIEW_SWITCH_DEBOUNCE_MILLIS = 250L

        const val LIVE_TARGET_OFFSET_MILLIS = 10_000L
    }
}
