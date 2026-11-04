/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.Download as MediaDownload
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadManager as MediaDownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.offline.DownloaderFactory
import app.opentv.data.model.Download
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Media3's dedicated HLS offline store. Not the platform's single-file DownloadManager.
 *
 * All segmented-video chunks are kept in the same non-evicting Cache and the DownloadIndex
 * persists per-film/episode progress through process restarts. A single download cache is
 * shared by the service and VOD player; never open a second SimpleCache on the same directory.
 */
@UnstableApi
class OfflineHlsStore private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val databaseProvider = StandaloneDatabaseProvider(appContext)
    val cache = SimpleCache(
        File(appContext.filesDir, "opentv-hls-offline"),
        NoOpCacheEvictor(),
        databaseProvider,
    )
    private val index = DefaultDownloadIndex(databaseProvider)
    private val httpClient = OkHttpClient()

    /** Constructed from DownloadService on the main thread, as required by Media3. */
    val manager: MediaDownloadManager by lazy {
        val downloaderFactory = DownloaderFactory { request ->
            val headers = decodeHeaders(request.data)
            val upstream = OkHttpDataSource.Factory(httpClient)
                .setDefaultRequestProperties(headers)
            val factory = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstream)
            DefaultDownloaderFactory(factory, Executor { task -> task.run() })
                .createDownloader(request)
        }
        MediaDownloadManager(appContext, index, downloaderFactory).apply {
            maxParallelDownloads = 1
            resumeDownloads()
        }
    }

    /** Query persisted Media3 state without touching the DownloadManager's main-thread API. */
    fun find(mediaKey: String): MediaDownload? = index.getDownload(mediaKey)

    /** A read-only overlay for VOD: cached HLS bytes win, missing data retains normal HTTP. */
    fun readCacheFactory(httpFactory: OkHttpDataSource.Factory): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(httpFactory)
            .setCacheWriteDataSinkFactory(null)

    /**
     * Resolve HLS tracks once, on demand. Media3's DownloadHelper selects a playable variant
     * instead of downloading every rendition of a master playlist.
     */
    suspend fun prepare(download: Download): DownloadRequest = withContext(Dispatchers.Main.immediate) {
        val sourceHeaders = download.requestHeaders.orEmpty()
        val headers = if (sourceHeaders.keys.any { it.equals("User-Agent", ignoreCase = true) }) {
            sourceHeaders
        } else {
            sourceHeaders + ("User-Agent" to DownloadRepository.USER_AGENT)
        }
        val upstream = OkHttpDataSource.Factory(httpClient).setDefaultRequestProperties(headers)
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(download.sourceUrl))
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .build()
        // This project uses Media3 1.5.0. DownloadHelper.Factory was introduced later.
        @Suppress("DEPRECATION")
        val helper = DownloadHelper.forMediaItem(
            mediaItem,
            TrackSelectionParameters.Builder(appContext)
                .setPreferredAudioLanguage("tr")
                .setPreferredTextLanguage("tr")
                .build(),
            DefaultRenderersFactory(appContext),
            upstream,
        )
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { helper.release() }
            try {
                helper.prepare(object : DownloadHelper.Callback {
                    override fun onPrepared(helper: DownloadHelper) {
                        try {
                            val data = Json.encodeToString(headers).toByteArray(Charsets.UTF_8)
                            val request = helper.getDownloadRequest(download.mediaKey, data)
                            if (continuation.isActive) continuation.resume(request)
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        } finally {
                            helper.release()
                        }
                    }

                    override fun onPrepareError(helper: DownloadHelper, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                        helper.release()
                    }
                })
            } catch (error: Exception) {
                helper.release()
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }

    fun add(request: DownloadRequest) {
        DownloadService.sendAddDownload(
            appContext, OpenTvHlsDownloadService::class.java, request, false,
        )
    }

    fun remove(mediaKey: String) {
        DownloadService.sendRemoveDownload(
            appContext, OpenTvHlsDownloadService::class.java, mediaKey, false,
        )
    }

    private fun decodeHeaders(data: ByteArray): Map<String, String> =
        runCatching {
            Json.decodeFromString<Map<String, String>>(data.toString(Charsets.UTF_8))
        }.getOrDefault(emptyMap())

    companion object {
        @Volatile private var singleton: OfflineHlsStore? = null

        fun get(context: Context): OfflineHlsStore =
            singleton ?: synchronized(this) {
                singleton ?: OfflineHlsStore(context).also { singleton = it }
            }
    }
}
