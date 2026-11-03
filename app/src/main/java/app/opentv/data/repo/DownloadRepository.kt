/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import app.opentv.data.db.DownloadDao
import app.opentv.data.model.Download
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

/**
 * Films and episodes saved for offline viewing (a plane, a train, no signal).
 *
 * The transfer itself is Android's DownloadManager: it resumes after a dropped connection, keeps
 * going when the app is closed, and posts a system notification when it finishes. Files land in this
 * app's own external folder, so no storage permission is needed (and they go when the app is
 * uninstalled). The database row is just the catalogue the Downloads tab lists; live state comes
 * from DownloadManager each time it's asked.
 */
class DownloadRepository(
    private val context: Context,
    private val dao: DownloadDao,
    private val http: okhttp3.OkHttpClient,
) {
    private val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    enum class State { QUEUED, RUNNING, PAUSED, DONE, FAILED }

    /** A download plus what DownloadManager says about it right now. */
    data class Item(
        val download: Download,
        val state: State,
        val bytesSoFar: Long,
        val totalBytes: Long,
        /** Local file to play, once [state] is DONE. */
        val fileUri: String?,
        /** Why it failed or is paused (a DownloadManager ERROR_/PAUSED_ reason), 0 when n/a. */
        val reason: Int = 0,
    ) {
        val outOfSpace: Boolean get() = state == State.FAILED && reason == DownloadManager.ERROR_INSUFFICIENT_SPACE
        val progress: Float get() = if (totalBytes > 0) (bytesSoFar.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }

    /** Everything downloaded or downloading, refreshed every second while someone is watching. */
    fun observe(): Flow<List<Item>> =
        combine(dao.observeAll(), ticker()) { downloads, tick ->
            // Belt and braces for the queue: if a completion broadcast was missed, the next
            // queued item still starts while someone has the tab open.
            if (tick % 5 == 0L) pump()
            withStatus(downloads)
        }.flowOn(Dispatchers.IO)

    /** One entry's live state, or null when it isn't downloaded — drives the Download buttons. */
    fun observe(mediaKey: String): Flow<Item?> =
        combine(dao.observeAll(), ticker()) { downloads, _ ->
            downloads.firstOrNull { it.mediaKey == mediaKey }?.let { withStatus(listOf(it)).firstOrNull() }
        }.flowOn(Dispatchers.IO)

    sealed interface Start {
        data object Started : Start
        /** Another download is running; this one starts when it finishes. */
        data object Queued : Start
        /** Won't fit: [neededBytes] is 0 when the provider didn't say how big it is. */
        data class NoSpace(val neededBytes: Long, val freeBytes: Long) : Start
    }

    /**
     * Adds a download to the queue, starting it straight away when nothing else is downloading.
     *
     * One at a time on purpose: IPTV providers commonly allow a single connection per account,
     * and DownloadManager would otherwise open several in parallel — the extra ones get refused
     * and sit in "waiting to retry" (what "Download season" used to do).
     */
    suspend fun enqueue(download: Download, userAgent: String = USER_AGENT): Start = withContext(Dispatchers.IO) {
        val free = freeBytes()
        if (free < MIN_FREE_BYTES) return@withContext Start.NoSpace(0, free)
        dao.byKey(download.mediaKey)?.let { remove(it) }
        dao.insert(download.copy(systemId = PENDING, createdMillis = System.currentTimeMillis()))
        if (hasActiveTransfer()) return@withContext Start.Queued
        pump(userAgent) ?: Start.Queued
    }

    private val pumpLock = kotlinx.coroutines.sync.Mutex()

    /**
     * Starts the oldest queued download if nothing is transferring. Called on enqueue, when a
     * download completes (DOWNLOAD_COMPLETE), on app start and periodically from [observe].
     * Returns what happened to the item it tried, or null when there was nothing to start.
     */
    suspend fun pump(userAgent: String = USER_AGENT): Start? = withContext(Dispatchers.IO) {
        pumpLock.withLock {
            if (hasActiveTransfer()) return@withLock null
            val next = dao.nextPending() ?: return@withLock null
            // Sized only now, with no transfer running: asking while another download is active
            // would open a second connection, which single-connection providers refuse (or use
            // to drop the first).
            val free = freeBytes()
            val size = remoteSize(next.sourceUrl, userAgent)
            if (free < MIN_FREE_BYTES || (size > 0 && size + MARGIN_BYTES > free)) {
                dao.setSystemId(next.id, NO_SPACE)
                return@withLock Start.NoSpace(size, free)
            }
            dao.setSystemId(next.id, startTransfer(next, userAgent))
            Start.Started
        }
    }

    private fun hasActiveTransfer(): Boolean = runCatching {
        val query = DownloadManager.Query().setFilterByStatus(
            DownloadManager.STATUS_RUNNING or DownloadManager.STATUS_PENDING or DownloadManager.STATUS_PAUSED,
        )
        manager.query(query)?.use { it.count > 0 } ?: false
    }.getOrDefault(false)

    private fun startTransfer(download: Download, userAgent: String): Long {
        val extension = download.sourceUrl.substringAfterLast('.', "mp4")
            .substringBefore('?').take(5).ifBlank { "mp4" }
        val fileName = "${download.mediaKey.replace(':', '_')}.$extension"
        val request = DownloadManager.Request(Uri.parse(download.sourceUrl))
            .addRequestHeader("User-Agent", userAgent)
            .setTitle(download.title)
            .setDescription(download.seriesTitle ?: "OpenTV")
            // Only the "done" notification. The system's live-progress one re-alerts on every update
            // on some phones (a chime every few seconds for the whole download); progress is shown
            // in the Downloads tab and on the Download button instead.
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_ONLY_COMPLETION)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_MOVIES, fileName)
        return manager.enqueue(request)
    }

    /**
     * The file's size, or 0 when the server won't say. Many IPTV panels ignore HEAD, so this falls
     * back to asking for the first byte only and reading the total from Content-Range
     * ("bytes 0-0/20640236829") — no real download happens.
     */
    private fun remoteSize(url: String, userAgent: String): Long {
        val head = runCatching {
            val request = okhttp3.Request.Builder().url(url).head().header("User-Agent", userAgent).build()
            http.newCall(request).execute().use { r ->
                if (r.isSuccessful) r.header("Content-Length")?.toLongOrNull() ?: 0L else 0L
            }
        }.getOrDefault(0L)
        if (head > 0) return head
        return runCatching {
            val request = okhttp3.Request.Builder().url(url)
                .header("User-Agent", userAgent)
                .header("Range", "bytes=0-0")
                .build()
            http.newCall(request).execute().use { r ->
                r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                    ?: r.header("Content-Length")?.toLongOrNull()?.takeIf { r.code == 200 }
                    ?: 0L
            }
        }.getOrDefault(0L)
    }

    /** The finished local file for [mediaKey], or null when it isn't fully downloaded. */
    suspend fun localFile(mediaKey: String): String? = withContext(Dispatchers.IO) {
        val download = dao.byKey(mediaKey) ?: return@withContext null
        withStatus(listOf(download)).firstOrNull()
            ?.takeIf { it.state == State.DONE }?.fileUri
    }

    /** Cancels the transfer if it's running, deletes the file, and forgets the entry. */
    suspend fun remove(download: Download) = withContext(Dispatchers.IO) {
        if (download.systemId >= 0) runCatching { manager.remove(download.systemId) }
        dao.delete(download.id)
        // Removing the running one frees the slot for the next in line.
        pump()
    }

    /** Space left where downloads are saved. */
    fun freeBytes(): Long {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: return 0L
        return runCatching { StatFs(dir.path).availableBytes }.getOrDefault(0L)
    }

    private fun withStatus(downloads: List<Download>): List<Item> {
        if (downloads.isEmpty()) return emptyList()
        val byId = HashMap<Long, Item>()
        val live = downloads.filter { it.systemId >= 0 }
        if (live.isNotEmpty()) runCatching {
            val query = DownloadManager.Query().setFilterById(*live.map { it.systemId }.toLongArray())
            manager.query(query)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                val statusCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                val soFarCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val uriCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
                val reasonCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val download = downloads.firstOrNull { it.systemId == id } ?: continue
                    val state = when (c.getInt(statusCol)) {
                        DownloadManager.STATUS_SUCCESSFUL -> State.DONE
                        DownloadManager.STATUS_FAILED -> State.FAILED
                        DownloadManager.STATUS_PAUSED -> State.PAUSED
                        DownloadManager.STATUS_RUNNING -> State.RUNNING
                        else -> State.QUEUED
                    }
                    byId[id] = Item(
                        download, state, c.getLong(soFarCol), c.getLong(totalCol), c.getString(uriCol),
                        reason = c.getInt(reasonCol),
                    )
                }
            }
        }
        // A row DownloadManager no longer knows (cleared from system settings) shows as failed, so
        // the user can see it and delete it rather than it silently vanishing.
        return downloads.map {
            when (it.systemId) {
                PENDING -> Item(it, State.QUEUED, 0, 0, null)
                NO_SPACE -> Item(it, State.FAILED, 0, 0, null, reason = DownloadManager.ERROR_INSUFFICIENT_SPACE)
                else -> byId[it.systemId] ?: Item(it, State.FAILED, 0, 0, null)
            }
        }
    }

    private fun ticker(): Flow<Long> = flow {
        var tick = 0L
        while (true) {
            emit(tick++)
            delay(1_000)
        }
    }

    companion object {
        /** [Download.systemId] of an item still waiting its turn in the queue. */
        const val PENDING = -1L
        /** [Download.systemId] of a queued item that turned out not to fit when its turn came. */
        const val NO_SPACE = -2L
        /** Matches what the VOD player sends, so providers treat a download like playback. */
        const val USER_AGENT = "OpenTV/0.1 (Android)"
        private const val MIN_FREE_BYTES = 1_500L * 1024 * 1024
        /** Head-room left over after a download, so it doesn't fill the phone to the last byte. */
        private const val MARGIN_BYTES = 500L * 1024 * 1024
    }
}
