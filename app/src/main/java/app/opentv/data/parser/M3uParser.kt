/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.parser

import app.opentv.data.model.Channel
import java.io.BufferedReader
import java.io.InputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Streaming M3U/M3U8 playlist parser.
 *
 * Deliberately reads line-by-line rather than loading the whole playlist into memory:
 * real provider playlists routinely run to tens of megabytes and 50,000+ entries, and
 * loading that as a single String is a reliable way to OOM a cheap Android TV box.
 *
 * Tolerant by design. A malformed entry is skipped, not fatal — one bad line in a
 * 40,000-line playlist must never cost the user their entire channel list.
 */
object M3uParser {

    private val ATTRIBUTE_REGEX = Regex("""([\w-]+)="([^"]*)"""")
    private val json = Json { ignoreUnknownKeys = true }

    data class Result(
        val channels: List<Channel>,
        /** Value of `url-tvg`/`x-tvg-url` on the #EXTM3U header, if the playlist declares one. */
        val declaredEpgUrl: String?,
        val skippedEntries: Int,
    )

    fun parse(input: InputStream, sourceId: Long): Result =
        input.bufferedReader().use { parse(it, sourceId) }

    fun parse(text: String, sourceId: Long): Result =
        parse(text.reader().buffered(), sourceId)

    fun parse(reader: BufferedReader, sourceId: Long): Result {
        val channels = ArrayList<Channel>()
        val seenStreamIds = HashSet<String>()
        var declaredEpgUrl: String? = null
        var skipped = 0

        var pendingName: String? = null
        var pendingAttributes: Map<String, String> = emptyMap()
        var pendingNumber: Int? = null
        // Directives placed before the first EXTINF are playlist-wide defaults (SporB uses this
        // form). Entry-local directives then override the inherited value for only that channel.
        val globalHeaders = linkedMapOf<String, String>()
        val pendingHeaders = linkedMapOf<String, String>()
        var seenExtInf = false
        var index = 0

        fun putHeader(name: String, value: String) {
            if (!seenExtInf) globalHeaders[name] = value
            else pendingHeaders[name] = value
        }

        reader.forEachLine { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() -> Unit

                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attributes = parseAttributes(line)
                    declaredEpgUrl = attributes["url-tvg"]
                        ?: attributes["x-tvg-url"]
                        ?: declaredEpgUrl
                }

                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    // A new EXTINF starts a new entry-local scope while inheriting playlist-wide
                    // defaults. This both supports global M3U headers and prevents a malformed
                    // entry's local headers from leaking into the next valid channel.
                    pendingHeaders.clear()
                    pendingHeaders.putAll(globalHeaders)
                    seenExtInf = true
                    pendingAttributes = parseAttributes(line)
                    pendingName = displayNameOf(line, pendingAttributes)
                    pendingNumber = pendingAttributes["tvg-chno"]?.toIntOrNull()
                }

                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    parseVlcOption(line)?.let { (name, value) -> putHeader(name, value) }
                }

                line.startsWith("#EXTHTTP:", ignoreCase = true) -> {
                    parseExtHttp(line).forEach { (name, value) -> putHeader(name, value) }
                }

                line.startsWith("#EXT-X-USER-AGENT", ignoreCase = true) -> {
                    directiveValue(line, "#EXT-X-USER-AGENT")
                        ?.let { putHeader("User-Agent", it) }
                }

                line.startsWith("#EXT-X-REFERER", ignoreCase = true) ||
                    line.startsWith("#EXT-X-REFERRER", ignoreCase = true) -> {
                    val prefix = if (line.startsWith("#EXT-X-REFERRER", ignoreCase = true)) {
                        "#EXT-X-REFERRER"
                    } else {
                        "#EXT-X-REFERER"
                    }
                    directiveValue(line, prefix)?.let { putHeader("Referer", it) }
                }

                line.startsWith("#EXT-X-ORIGIN", ignoreCase = true) -> {
                    directiveValue(line, "#EXT-X-ORIGIN")
                        ?.let { putHeader("Origin", it) }
                }

                // Any other directive: ignore, but keep the pending EXTINF alive.
                line.startsWith("#") -> Unit

                else -> {
                    val name = pendingName
                    if (name.isNullOrBlank()) {
                        // A URL with no preceding #EXTINF. Nothing sensible to label it with.
                        skipped++
                    } else {
                        val attributes = pendingAttributes
                        val streamId = attributes["tvg-id"]
                            ?.takeIf { it.isNotBlank() }
                            ?.let { "tvg:$it" }
                            ?: "url:${stableHash(line)}"

                        // Providers repeat the same tvg-id across quality variants. Keep the
                        // first and drop later duplicates rather than letting them collide on
                        // the unique index and abort the whole import.
                        if (seenStreamIds.add(streamId)) {
                            channels += Channel(
                                sourceId = sourceId,
                                streamId = streamId,
                                name = name,
                                categoryId = attributes["group-title"]?.takeIf { it.isNotBlank() },
                                logoUrl = (attributes["tvg-logo"] ?: attributes["logo"])
                                    ?.takeIf { it.isNotBlank() },
                                epgChannelId = attributes["tvg-id"]?.takeIf { it.isNotBlank() },
                                number = pendingNumber,
                                streamUrl = line,
                                requestHeaders = pendingHeaders
                                    .takeIf { it.isNotEmpty() }
                                    ?.toMap(),
                                sortIndex = index++,
                            )
                        }
                    }
                    pendingName = null
                    pendingAttributes = emptyMap()
                    pendingNumber = null
                    pendingHeaders.clear()
                }
            }
        }

        return Result(channels, declaredEpgUrl, skipped)
    }

    /**
     * VLC-style per-entry option, e.g.
     * `#EXTVLCOPT:http-referer=https://site/` or `http-user-agent=Mozilla/5.0`.
     *
     * Any `http-*` option is preserved, not just the three we currently know SporB needs.
     */
    private fun parseVlcOption(line: String): Pair<String, String>? {
        val option = line.substringAfter(':', missingDelimiterValue = "").trim()
        val key = option.substringBefore('=', missingDelimiterValue = "").trim()
        val value = option.substringAfter('=', missingDelimiterValue = "").trim().trim('"')
        if (!key.startsWith("http-", ignoreCase = true) || value.isBlank()) return null

        val rawHeader = key.substringAfter("http-", missingDelimiterValue = "")
        return canonicalHeaderName(rawHeader) to value
    }

    /**
     * Kodi/IPTV clients also use `#EXTHTTP:{"Header":"value"}`. Invalid JSON is simply ignored,
     * keeping the parser's "one bad line never loses the playlist" contract.
     */
    private fun parseExtHttp(line: String): Map<String, String> {
        val payload = line.substringAfter(':', missingDelimiterValue = "").trim()
        if (payload.isBlank()) return emptyMap()
        val obj = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
            ?: return emptyMap()

        return buildMap {
            for ((key, element) in obj) {
                val value = runCatching { element.jsonPrimitive.content }.getOrNull()
                if (!value.isNullOrBlank()) put(canonicalHeaderName(key), value)
            }
        }
    }

    private fun directiveValue(line: String, prefix: String): String? {
        val tail = line.substring(prefix.length).trimStart()
        val value = tail
            .removePrefix(":")
            .removePrefix("=")
            .trim()
            .trim('"')
        return value.takeIf { it.isNotBlank() }
    }

    private fun canonicalHeaderName(raw: String): String {
        val normalized = raw.trim().replace('_', '-').lowercase()
        return when (normalized) {
            "user-agent", "useragent" -> "User-Agent"
            "referer", "referrer" -> "Referer"
            "origin" -> "Origin"
            "cookie" -> "Cookie"
            "accept" -> "Accept"
            "host" -> "Host"
            else -> normalized.split('-')
                .filter { it.isNotBlank() }
                .joinToString("-") { part ->
                    part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                }
        }
    }

    /**
     * The display name is whatever follows the last comma on the #EXTINF line. Falling back
     * to `tvg-name` matters because a fair number of playlists emit `#EXTINF:-1 ...,` with
     * nothing after the comma.
     */
    private fun displayNameOf(line: String, attributes: Map<String, String>): String? {
        val afterComma = line.substringAfterLast(',', missingDelimiterValue = "").trim()
        return afterComma.takeIf { it.isNotBlank() }
            ?: attributes["tvg-name"]?.takeIf { it.isNotBlank() }
    }

    private fun parseAttributes(line: String): Map<String, String> =
        ATTRIBUTE_REGEX.findAll(line).associate { match ->
            match.groupValues[1].lowercase() to match.groupValues[2]
        }

    /** FNV-1a. Stable across processes and platforms, unlike [String.hashCode] guarantees. */
    private fun stableHash(value: String): String {
        var hash = 0xcbf29ce484222325uL.toLong() // FNV-1a 64-bit offset basis
        for (byte in value.encodeToByteArray()) {
            hash = hash xor (byte.toLong() and 0xff)
            hash *= 0x100000001b3L // FNV-1a 64-bit prime
        }
        return java.lang.Long.toHexString(hash)
    }
}
