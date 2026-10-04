/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps one-off VOD playback payloads out of Navigation route strings.
 *
 * Native providers can resolve very long signed URLs and DiziPal can return a complete HLS playlist
 * as a data: URI. Putting those values into a Compose Navigation route can exceed route matching
 * limits and crash before the player opens. Only a short token travels through Navigation; the
 * payload stays in-process until the destination consumes it.
 */
object VodPlaybackHandoff {
    data class Request(
        val mediaKey: String,
        val streamUrl: String,
        val title: String,
        val userAgent: String,
        val referer: String = "",
        val cookie: String = "",
        val origin: String = "",
        val streamMimeType: String = "",
        val subtitleUrl: String = "",
        val subtitleLabel: String = "",
        val subtitleLanguage: String = "",
        val subtitleMimeType: String = "",
        val returnRoute: String = "",
        val returnRoutePattern: String = "",
    )

    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<String, Request>()

    fun put(request: Request): String {
        val token = nextId.getAndIncrement().toString(36)
        pending[token] = request
        return token
    }

    fun take(token: String): Request? = pending.remove(token)
}
