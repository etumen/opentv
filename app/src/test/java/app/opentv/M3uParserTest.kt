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
    fun `vlc http options are captured as per-channel request headers`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="a",Channel A
            #EXTVLCOPT:http-user-agent=SomePlayer/1.0
            #EXTVLCOPT:http-referrer=https://example.com/watch
            #EXTVLCOPT:http-origin=https://example.com
            #EXTVLCOPT:http-accept=*/*
            #EXTVLCOPT:http-host=edge.example.com
            http://example.com/a.m3u8
        """.trimIndent()

        val channel = M3uParser.parse(playlist, sourceId = 1).channels.single()

        assertThat(channel.requestHeaders).containsExactly(
            "User-Agent", "SomePlayer/1.0",
            "Referer", "https://example.com/watch",
            "Origin", "https://example.com",
            "Accept", "*/*",
            "Host", "edge.example.com",
        )
    }

    @Test
    fun `ext x and exthttp header forms are supported together`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="a",Channel A
            #EXT-X-USER-AGENT: Browser/123
            #EXT-X-REFERER=https://site.example/page
            #EXT-X-ORIGIN:https://site.example
            #EXTHTTP:{"Cookie":"sid=abc","X-Test":"yes"}
            http://example.com/a.m3u8
        """.trimIndent()

        val headers = M3uParser.parse(playlist, sourceId = 1).channels.single().requestHeaders

        assertThat(headers).containsExactly(
            "User-Agent", "Browser/123",
            "Referer", "https://site.example/page",
            "Origin", "https://site.example",
            "Cookie", "sid=abc",
            "X-Test", "yes",
        )
    }

    @Test
    fun `playlist-wide headers before first EXTINF are inherited by every channel`() {
        val playlist = """
            #EXTM3U
            #EXTVLCOPT:http-user-agent=GlobalPlayer/1.0
            #EXT-X-REFERER:https://global.example/page
            #EXT-X-ORIGIN:https://global.example
            #EXTINF:-1 tvg-id="a",Channel A
            http://example.com/a.m3u8
            #EXTINF:-1 tvg-id="b",Channel B
            http://example.com/b.m3u8
        """.trimIndent()

        val channels = M3uParser.parse(playlist, sourceId = 1).channels

        assertThat(channels).hasSize(2)
        channels.forEach { channel ->
            assertThat(channel.requestHeaders).containsExactly(
                "User-Agent", "GlobalPlayer/1.0",
                "Referer", "https://global.example/page",
                "Origin", "https://global.example",
            )
        }
    }

    @Test
    fun `entry-local header overrides global default without leaking to next channel`() {
        val playlist = """
            #EXTM3U
            #EXTVLCOPT:http-referer=https://global.example
            #EXTINF:-1 tvg-id="a",Channel A
            #EXTVLCOPT:http-referer=https://local.example
            http://example.com/a.m3u8
            #EXTINF:-1 tvg-id="b",Channel B
            http://example.com/b.m3u8
        """.trimIndent()

        val channels = M3uParser.parse(playlist, sourceId = 1).channels

        assertThat(channels[0].requestHeaders).containsEntry("Referer", "https://local.example")
        assertThat(channels[1].requestHeaders).containsEntry("Referer", "https://global.example")
    }

    @Test
    fun `headers from a malformed entry do not leak into the next channel`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="broken",Broken
            #EXTVLCOPT:http-referer=https://wrong.example
            #EXTINF:-1 tvg-id="good",Good
            http://example.com/good.m3u8
        """.trimIndent()

        val channel = M3uParser.parse(playlist, sourceId = 1).channels.single()

        assertThat(channel.name).isEqualTo("Good")
        assertThat(channel.requestHeaders).isNull()
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
}
