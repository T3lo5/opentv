/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.parser

import app.opentv.data.model.Channel

/**
 * Matching a channel asked for by name from outside the app.
 *
 * Free My TV knows which broadcaster carries a match — `ESPN2`, `Premiere`, `SporTV` — but not
 * how this provider spells it: `ESPN 2`, `ESPN2 FHD`, `[BR] ESPN 2`. The name is therefore tried
 * in the spellings a provider might use, and the results are compared by the same key that folds
 * a channel's quality variants: [ChannelNameNormalizer]'s group key.
 */
object ChannelQuery {

    /**
     * The spellings to try in search, most specific first: the name as asked, then with the space
     * a provider usually puts before the number (`ESPN2` → `ESPN 2`), then the bare letters
     * (`ESPN`), which finds the channel even when the provider carries no numbered variant.
     */
    fun variants(query: String): List<String> {
        val asked = query.trim()
        if (asked.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        out += asked
        val spaced = asked.replace(Regex("([A-Za-z])(\\d)"), "$1 $2").trim()
        if (spaced != asked) out += spaced
        val letters = asked.takeWhile { it.isLetter() }.trim()
        if (letters.length >= 3 && letters != asked) out += letters
        return out.toList()
    }

    /**
     * The channel the person meant: same group key, then one that starts with it; the best quality
     * wins, since a provider lists `ESPN 2 HD`, `ESPN 2 FHD` and `ESPN 2 SD` as separate streams.
     */
    fun bestMatch(query: String, found: List<Channel>): Channel? {
        val keys = variants(query)
            .map { ChannelNameNormalizer.normalize(it).groupKey }
            .filter { it.isNotEmpty() }
        if (keys.isEmpty()) return null
        fun best(list: List<Channel>) = list.maxByOrNull { it.qualityRank }
        for (key in keys) {
            val exact = found.filter { it.groupKey == key }
            if (exact.isNotEmpty()) return best(exact)
        }
        // "ESPN" finds "ESPN BRASIL" when the provider carries no numbered variant at all.
        for (key in keys) {
            val near = found.filter { it.groupKey.isNotEmpty() && it.groupKey.startsWith(key) }
            if (near.isNotEmpty()) return best(near)
        }
        return null
    }
}
