/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.db.SourceDao
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.XtreamApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class SourceRepository(
    private val dao: SourceDao,
    private val api: XtreamApi,
    private val stalkerApi: StalkerApi,
) {
    fun observeAll(): Flow<List<Source>> = dao.observeAll()

    suspend fun enabled(): List<Source> = dao.enabled()

    suspend fun byId(id: Long): Source? = dao.byId(id)

    data class EnsureBuiltInResult(
        val source: Source,
        val created: Boolean,
    )

    /**
     * Ensures the free IPTV-ORG Turkey playlist is always available as OpenTV's built-in live-TV
     * starter source. This intentionally runs on every app launch: a clean install gets the source
     * automatically, and deleting/reinstalling the app recreates it without any setup screen.
     *
     * Matching is by kind + canonical URL, not display name, so a user may rename the source without
     * causing a duplicate. Existing sources are otherwise left untouched (enabled state, name, etc.).
     */
    suspend fun ensureBuiltInTurkeySource(): EnsureBuiltInResult = withContext(Dispatchers.IO) {
        val url = normaliseUrl(BUILT_IN_TURKEY_URL, SourceKind.M3U)
        val existing = dao.byKindAndUrl(SourceKind.M3U, url)
        if (existing != null) {
            return@withContext EnsureBuiltInResult(existing, created = false)
        }

        val id = dao.insert(
            Source(
                name = BUILT_IN_TURKEY_NAME,
                kind = SourceKind.M3U,
                url = url,
            ),
        )
        val saved = dao.byId(id)
            ?: error("Built-in IPTV-ORG Turkey source was inserted but could not be read back")
        EnsureBuiltInResult(saved, created = true)
    }

    /**
     * Ensures the hidden SporB supplemental playlist after the local feature has been unlocked.
     * This method itself does not decide whether the feature is enabled; callers must gate it with
     * [app.opentv.core.AppSettings.sporbEnabled]. Matching by exact canonical URL makes repeated
     * unlocks and launches idempotent while keeping every ordinary user M3U untouched.
     */
    suspend fun ensureSporbSource(): EnsureBuiltInResult = withContext(Dispatchers.IO) {
        val url = normaliseUrl(SPORB_URL, SourceKind.M3U)
        val existing = dao.byKindAndUrl(SourceKind.M3U, url)
        if (existing != null) {
            return@withContext EnsureBuiltInResult(existing, created = false)
        }

        val id = dao.insert(
            Source(
                name = SPORB_NAME,
                kind = SourceKind.M3U,
                url = url,
            ),
        )
        val saved = dao.byId(id)
            ?: error("SporB source was inserted but could not be read back")
        EnsureBuiltInResult(saved, created = true)
    }

    suspend fun save(source: Source): Long = withContext(Dispatchers.IO) {
        val normalised = source.copy(url = normaliseUrl(source.url, source.kind))
        if (source.id == 0L) dao.insert(normalised)
        else {
            dao.update(normalised)
            source.id
        }
    }

    /** Checks the details work before the user is committed to them. */
    suspend fun test(source: Source): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            when (source.kind) {
                SourceKind.XTREAM -> {
                    val info = api.authenticate(
                        source.copy(url = normaliseUrl(source.url, source.kind)),
                    )
                    buildString {
                        append("Connected")
                        info.username?.let { append(" as $it") }
                        info.maxConnections?.let { append(" · $it connection(s)") }
                        info.expiryMillis?.let {
                            append(" · expires ${java.text.DateFormat.getDateInstance().format(java.util.Date(it))}")
                        }
                    }
                }
                SourceKind.M3U -> "Playlist address looks valid. It will be checked on first sync."
                SourceKind.STALKER -> {
                    stalkerApi.handshakeTest(source.copy(url = normaliseUrl(source.url, source.kind)))
                    "Portal accepted the MAC address. Loading channels…"
                }
            }
        }
    }

    companion object {
        const val BUILT_IN_TURKEY_NAME = "IPTV-ORG_Turkiye"
        const val BUILT_IN_TURKEY_URL = "https://iptv-org.github.io/iptv/countries/tr.m3u"

        const val SPORB_NAME = "SporB"
        const val SPORB_URL = "https://raw.githubusercontent.com/ugur2941/b-t-n-kanallar/main/sporb"

        /**
         * Users paste all sorts of things. Accept them all rather than making someone guess
         * the format: a trailing slash, a missing scheme, or a full `get.php` URL copied out
         * of a provider's welcome email.
         *
         * For an Xtream source we want the bare host, so API paths are stripped. For an M3U
         * source the URL *is* the playlist — stripping the path there would destroy it, which
         * is why [kind] is not optional.
         *
         * Pure and static so it can be tested without standing up the repository.
         */
        fun normaliseUrl(raw: String, kind: SourceKind): String {
            var url = raw.trim()
            if (url.isEmpty()) return url
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                url = "http://$url"
            }
            if (kind == SourceKind.M3U) return url
            // A Stalker portal URL is the portal path itself (e.g. .../stalker_portal or .../c) —
            // stripping Xtream API paths would be wrong; just tidy the trailing slash.
            if (kind == SourceKind.STALKER) return url.trimEnd('/')

            url = url.substringBefore("/player_api.php")
                .substringBefore("/panel_api.php")
                .substringBefore("/get.php")
                .substringBefore("/xmltv.php")
            return url.trimEnd('/')
        }
    }
}
