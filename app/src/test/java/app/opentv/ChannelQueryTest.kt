/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Channel
import app.opentv.data.parser.ChannelQuery
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Free My TV asks for a broadcaster by name; these are the spellings providers ship it in. */
class ChannelQueryTest {

    private fun channel(name: String, groupKey: String, qualityRank: Int = 0) = Channel(
        sourceId = 1,
        streamId = name,
        name = name,
        groupKey = groupKey,
        qualityRank = qualityRank,
        categoryId = null,
        logoUrl = null,
        epgChannelId = null,
        number = 1,
        streamUrl = "http://example.com/$name",
    )

    @Test
    fun `adds the spacing a provider puts before the number`() {
        assertThat(ChannelQuery.variants("ESPN2")).containsExactly("ESPN2", "ESPN 2", "ESPN").inOrder()
    }

    @Test
    fun `keeps a plain name alone`() {
        assertThat(ChannelQuery.variants("Premiere")).containsExactly("Premiere")
    }

    @Test
    fun `a name already spaced still falls back to the letters`() {
        assertThat(ChannelQuery.variants("ESPN 2")).containsExactly("ESPN 2", "ESPN").inOrder()
    }

    @Test
    fun `matches the provider's spelling of the same channel`() {
        val found = listOf(channel("ESPN 2 HD", "espn2", 200))
        assertThat(ChannelQuery.bestMatch("ESPN2", found)?.name).isEqualTo("ESPN 2 HD")
    }

    @Test
    fun `prefers the best quality among the variants`() {
        val found = listOf(
            channel("ESPN 2 SD", "espn2", 100),
            channel("ESPN 2 FHD", "espn2", 300),
            channel("ESPN 3 HD", "espn3", 200),
        )
        assertThat(ChannelQuery.bestMatch("ESPN2", found)?.name).isEqualTo("ESPN 2 FHD")
    }

    @Test
    fun `accepts a numbered channel when only the main one is carried`() {
        val found = listOf(channel("ESPN BRASIL HD", "espnbrasil", 200))
        assertThat(ChannelQuery.bestMatch("ESPN2", found)?.name).isEqualTo("ESPN BRASIL HD")
    }

    @Test
    fun `no match when the provider does not carry it`() {
        val found = listOf(channel("AXN HD", "axn", 200))
        assertThat(ChannelQuery.bestMatch("ESPN2", found)).isNull()
    }
}
