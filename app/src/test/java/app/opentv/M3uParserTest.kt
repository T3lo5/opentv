/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.parser.M3uParser
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * These tests are built from the shapes real providers actually emit, including the broken
 * ones. Every "malformed" case here is something that should cost the user one channel, never
 * the whole playlist.
 */
class M3uParserTest {

    @Test
    fun `parses a well formed playlist`() {
        val playlist = """
            #EXTM3U url-tvg="http://example.com/xmltv.php?username=u&password=p"
            #EXTINF:-1 tvg-id="bbc1.uk" tvg-name="BBC One" tvg-logo="http://img/bbc1.png" group-title="UK",BBC One HD
            http://example.com:8080/live/u/p/1.m3u8
            #EXTINF:-1 tvg-id="itv1.uk" tvg-logo="http://img/itv.png" group-title="UK",ITV 1
            http://example.com:8080/live/u/p/2.m3u8
        """.trimIndent()

        val result = M3uParser.parse(playlist, sourceId = 7)

        assertThat(result.channels).hasSize(2)
        assertThat(result.declaredEpgUrl)
            .isEqualTo("http://example.com/xmltv.php?username=u&password=p")

        val first = result.channels.first()
        assertThat(first.name).isEqualTo("BBC One HD")
        assertThat(first.epgChannelId).isEqualTo("bbc1.uk")
        assertThat(first.categoryId).isEqualTo("UK")
        assertThat(first.logoUrl).isEqualTo("http://img/bbc1.png")
        assertThat(first.sourceId).isEqualTo(7)
        assertThat(first.streamUrl).isEqualTo("http://example.com:8080/live/u/p/1.m3u8")
    }

    @Test
    fun `a URL with no EXTINF is skipped without losing the rest`() {
        val playlist = """
            #EXTM3U
            http://example.com/orphan.m3u8
            #EXTINF:-1 tvg-id="good",Good Channel
            http://example.com/good.m3u8
        """.trimIndent()

        val result = M3uParser.parse(playlist, sourceId = 1)

        assertThat(result.skippedEntries).isEqualTo(1)
        assertThat(result.channels).hasSize(1)
        assertThat(result.channels.single().name).isEqualTo("Good Channel")
    }

    @Test
    fun `falls back to tvg-name when the display name after the comma is empty`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="x" tvg-name="Fallback Name",
            http://example.com/x.m3u8
        """.trimIndent()

        val channel = M3uParser.parse(playlist, sourceId = 1).channels.single()

        assertThat(channel.name).isEqualTo("Fallback Name")
    }

    @Test
    fun `duplicate tvg-ids do not collide`() {
        // Providers routinely list SD and HD variants under one tvg-id. The unique index on
        // (sourceId, streamId) would otherwise abort the entire import.
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="dup",Channel SD
            http://example.com/sd.m3u8
            #EXTINF:-1 tvg-id="dup",Channel HD
            http://example.com/hd.m3u8
        """.trimIndent()

        val result = M3uParser.parse(playlist, sourceId = 1)

        assertThat(result.channels).hasSize(1)
        assertThat(result.channels.single().name).isEqualTo("Channel SD")
    }

    @Test
    fun `channels without a tvg-id still get a stable id derived from the url`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1,No Id Channel
            http://example.com/noid.m3u8
        """.trimIndent()

        val first = M3uParser.parse(playlist, sourceId = 1).channels.single()
        val second = M3uParser.parse(playlist, sourceId = 1).channels.single()

        assertThat(first.streamId).startsWith("url:")
        // Stability matters: an unstable id means favourites are lost on every refresh.
        assertThat(first.streamId).isEqualTo(second.streamId)
        assertThat(first.epgChannelId).isNull()
    }

    @Test
    fun `unknown directives between EXTINF and the url are ignored`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="a",Channel A
            #EXTVLCOPT:http-user-agent=SomePlayer/1.0
            #EXTHTTP:{"User-Agent":"SomePlayer/1.0"}
            http://example.com/a.m3u8
        """.trimIndent()

        val channels = M3uParser.parse(playlist, sourceId = 1).channels

        assertThat(channels).hasSize(1)
        assertThat(channels.single().streamUrl).isEqualTo("http://example.com/a.m3u8")
    }

    @Test
    fun `channel numbers come from tvg-chno when present`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="a" tvg-chno="101",Channel A
            http://example.com/a.m3u8
        """.trimIndent()

        assertThat(M3uParser.parse(playlist, sourceId = 1).channels.single().number).isEqualTo(101)
    }

    @Test
    fun `an empty playlist yields no channels rather than throwing`() {
        val result = M3uParser.parse("#EXTM3U\n", sourceId = 1)

        assertThat(result.channels).isEmpty()
        assertThat(result.skippedEntries).isEqualTo(0)
    }

    @Test
    fun `a channel with no catch-up tags has no catch-up`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="a",Channel A
            http://example.com/a.m3u8
        """.trimIndent()

        val channel = M3uParser.parse(playlist, sourceId = 1).channels.single()

        assertThat(channel.tvArchive).isFalse()
        assertThat(channel.catchupSource).isNull()
        assertThat(channel.catchupMode).isNull()
        assertThat(channel.catchupCorrectionSecs).isEqualTo(0)
    }

    @Test
    fun `the catch-up tags land on the channel, template and all`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="a" catchup="append" catchup-source="&cutv={Y}-{m}-{d}T{H}:{M}:{S}" catchup-days="7",Channel A
            http://example.com/a.m3u8
        """.trimIndent()

        val channel = M3uParser.parse(playlist, sourceId = 1).channels.single()

        assertThat(channel.tvArchive).isTrue()
        assertThat(channel.catchupMode).isEqualTo("append")
        assertThat(channel.catchupSource)
            .isEqualTo("http://example.com/a.m3u8&cutv={Y}-{m}-{d}T{H}:{M}:{S}")
        assertThat(channel.tvArchiveDays).isEqualTo(7)
    }

    @Test
    fun `the playlist's own catch-up tags are the default for its channels`() {
        val playlist = """
            #EXTM3U catchup="shift" catchup-correction="-3.0"
            #EXTINF:-1 tvg-id="a",Channel A
            http://example.com/a.m3u8
        """.trimIndent()

        val channel = M3uParser.parse(playlist, sourceId = 1).channels.single()

        assertThat(channel.tvArchive).isTrue()
        assertThat(channel.catchupMode).isEqualTo("shift")
        assertThat(channel.catchupSource)
            .isEqualTo("http://example.com/a.m3u8?utc={utc}&lutc={lutc}")
        // The playlist writes the correction in hours; the app keeps it in seconds.
        assertThat(channel.catchupCorrectionSecs).isEqualTo(-3 * 3600)
    }

    @Test
    fun `a channel's own catch-up tags beat the playlist's`() {
        val playlist = """
            #EXTM3U catchup="shift"
            #EXTINF:-1 tvg-id="a" catchup="default" catchup-source="http://example.com/a.ts?utc={utc}",Channel A
            http://example.com/a.m3u8
        """.trimIndent()

        val channel = M3uParser.parse(playlist, sourceId = 1).channels.single()

        assertThat(channel.catchupMode).isEqualTo("default")
        assertThat(channel.catchupSource).isEqualTo("http://example.com/a.ts?utc={utc}")
    }

    @Test
    fun `a catch-up mode we do not know leaves the channel without catch-up`() {
        // Better no catch-up than a URL assembled from a mode we did not understand.
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="a" catchup="maybe" catchup-source="http://example.com/a.ts?utc={utc}",Channel A
            http://example.com/a.m3u8
        """.trimIndent()

        val channel = M3uParser.parse(playlist, sourceId = 1).channels.single()

        assertThat(channel.tvArchive).isFalse()
        assertThat(channel.catchupSource).isNull()
    }
}
