/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

/**
 * A duration as `m:ss`, or `h:mm:ss` when it runs past the hour.
 *
 * Both players show times and they have to agree: a transport that says "1:02:03" in one
 * place and "62:03" in another is the kind of small wrongness that makes people distrust
 * the rest of the screen.
 */
fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * How far back a live stream can be rewound, as the user reads it: "-2:00".
 *
 * A leading minus is the convention every TV app uses for "that much behind the live
 * edge", and it is unambiguous next to the live edge itself.
 */
fun formatBehind(ms: Long): String = "-${formatDuration(ms)}"
