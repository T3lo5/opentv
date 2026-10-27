/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A one-shot channel-to-play request, set from outside the Compose tree (a reminder notification
 * tap) and consumed by the nav graph. Kept deliberately tiny: a single nullable id the app watches
 * and clears once it has navigated, so a stale request never re-fires on the next launch.
 */
object PlayRequests {
    private val _channelId = MutableStateFlow<Long?>(null)
    val channelId: StateFlow<Long?> = _channelId.asStateFlow()

    fun request(id: Long) {
        if (id != 0L) _channelId.value = id
    }

    fun consume() {
        _channelId.value = null
    }

    /**
     * The same one-shot request, but by channel name — set by Free My TV (app.freemytv), which knows
     * the game's broadcaster but not which of them this IPTV carries, so it sends the candidates
     * separated by "|". The nav graph tries each and plays the first one found.
     */
    private val _channelName = MutableStateFlow<String?>(null)
    val channelName: StateFlow<String?> = _channelName.asStateFlow()

    fun requestName(names: String) {
        if (names.isNotBlank()) _channelName.value = names
    }

    fun consumeName() {
        _channelName.value = null
    }
}
