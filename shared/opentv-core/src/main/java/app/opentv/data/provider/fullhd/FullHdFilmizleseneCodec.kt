/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.provider.fullhd

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Decoders used by FullHDFilmizlesene and its current RapidVid playback page.
 *
 * Kept Android-free so the obfuscation logic is unit-testable on the JVM.
 */
internal object FullHdFilmizleseneCodec {
    private val scxPattern = Regex(
        """(?:var\s+)?scx\s*=\s*(\{.+?\});""",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )
    private val quotedTokenPattern = Regex(""""([A-Za-z0-9+/=_-]{10,})"""")
    private val rapidVidPayloadPattern = Regex(
        """window\._p8\s*=\s*['"]([^'"]+)['"]""",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )

    fun extractScxUrls(html: String): List<String> {
        val raw = scxPattern.find(html)?.groupValues?.getOrNull(1) ?: return emptyList()
        return quotedTokenPattern.findAll(raw)
            .mapNotNull { decodeScxItem(it.groupValues[1]) }
            .map { if (it.startsWith("//")) "https:$it" else it }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
            .toList()
    }

    fun decodeScxItem(encoded: String): String? =
        runCatching {
            String(decodeBase64(rot13(encoded)), Charsets.UTF_8)
        }.getOrNull()

    fun extractRapidVidPayloadJson(html: String): String? {
        val token = rapidVidPayloadPattern.find(html)?.groupValues?.getOrNull(1) ?: return null
        return decryptRapidVidAv(token).takeIf { it.trimStart().startsWith("{") }
    }

    fun decryptRapidVidAv(token: String): String =
        runCatching {
            val first = String(
                decodeBase64(token.reversed()),
                StandardCharsets.ISO_8859_1,
            )
            val key = "K9L"
            val transformed = buildString(first.length) {
                first.forEachIndexed { index, char ->
                    val keyChar = key[index % key.length]
                    val offset = keyChar.code % 5 + 1
                    append((char.code - offset).toChar())
                }
            }
            String(decodeBase64(transformed), Charsets.UTF_8)
        }.getOrDefault("")

    private fun rot13(input: String): String =
        input.map { char ->
            when (char) {
                in 'a'..'m', in 'A'..'M' -> (char.code + 13).toChar()
                in 'n'..'z', in 'N'..'Z' -> (char.code - 13).toChar()
                else -> char
            }
        }.joinToString("")

    private fun decodeBase64(value: String): ByteArray {
        val clean = value.filterNot(Char::isWhitespace)
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return runCatching { Base64.getDecoder().decode(padded) }
            .getOrElse { Base64.getUrlDecoder().decode(padded) }
    }
}
