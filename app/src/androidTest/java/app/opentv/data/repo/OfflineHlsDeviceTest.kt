package app.opentv.data.repo

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.opentv.data.model.Download as AppDownload
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android/Media3 HLS download and offline playback of an original 2s test clip. */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class OfflineHlsDeviceTest {
    @Test fun offlineHlsLoadsWithoutSourceServer() = runBlocking {
        val instr = InstrumentationRegistry.getInstrumentation()
        val app = instr.targetContext
        val segment = instr.context.assets.open("hls/sample.ts").use { it.readBytes() }
        val server = LocalHlsServer(segment)
        val store = OfflineHlsStore.get(app)
        val key = "qa-hls-" + System.nanoTime()
        var player: ExoPlayer? = null
        try {
            val entry = AppDownload(
                mediaKey = key, kind = "EPISODE", title = "OpenTV diagnostic",
                posterUrl = null, tag = null, sourceUrl = server.playlistUrl,
                systemId = DownloadRepository.HLS_MEDIA3, createdMillis = System.currentTimeMillis(),
                requestHeaders = mapOf("User-Agent" to "OpenTV-DeviceTest", "Referer" to server.root),
            )
            val request = withTimeout(30_000L) { store.prepare(entry) }
            assertTrue(request.id == key)
            withContext(Dispatchers.Main) { store.manager.addDownload(request) }
            val complete = withTimeout(40_000L) {
                while (true) {
                    val status = store.find(key)
                    if (status?.state == Download.STATE_COMPLETED) return@withTimeout status
                    if (status?.state == Download.STATE_FAILED)
                        error("HLS download failed, code=" + status.failureReason)
                    delay(150)
                }
                error("unreachable")
            }
            assertTrue("No HLS chunks downloaded", complete.bytesDownloaded > 0L)
            server.close()
            val cacheFactory = store.readCacheFactory(
                OkHttpDataSource.Factory(OkHttpClient())
                    .setDefaultRequestProperties(entry.requestHeaders.orEmpty())
            )
            player = withContext(Dispatchers.Main) {
                ExoPlayer.Builder(app).setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory))
                    .build().also {
                        it.volume = 0f
                        it.setMediaItem(
                            MediaItem.Builder().setUri(entry.sourceUrl)
                                .setMimeType(MimeTypes.APPLICATION_M3U8)
                                .setStreamKeys(complete.request.streamKeys).build()
                        )
                        it.playWhenReady = true
                        it.prepare()
                    }
            }
            val active = player ?: error("player was not created")
            withTimeout(25_000L) {
                while (true) {
                    val s = withContext(Dispatchers.Main) {
                        Triple(active.playbackState, active.playerError, active.currentPosition)
                    }
                    s.second?.let { throw AssertionError("Offline HLS player error: " + it) }
                    if (s.first == Player.STATE_READY || s.first == Player.STATE_ENDED) break
                    delay(150)
                }
            }
            delay(700)
            val pos = withContext(Dispatchers.Main) { active.currentPosition }
            assertTrue("Playback did not advance while server offline: " + pos, pos > 200L)
        } finally {
            withContext(Dispatchers.Main) {
                player?.release()
                store.manager.removeDownload(key)
            }
            server.close()
        }
    }

    @Test fun downloadServiceQueuesAndCompletesOriginalHls() = runBlocking {
        val instr = InstrumentationRegistry.getInstrumentation()
        val app = instr.targetContext
        val segment = instr.context.assets.open("hls/sample.ts").use { it.readBytes() }
        val server = LocalHlsServer(segment)
        val store = OfflineHlsStore.get(app)
        val key = "qa-service-" + System.nanoTime()
        try {
            val entry = AppDownload(
                mediaKey = key, kind = "MOVIE", title = "OpenTV service diagnostic",
                posterUrl = null, tag = null, sourceUrl = server.playlistUrl,
                systemId = DownloadRepository.HLS_MEDIA3, createdMillis = System.currentTimeMillis(),
                requestHeaders = mapOf("User-Agent" to "OpenTV-DeviceTest", "Referer" to server.root),
            )
            val request = withTimeout(30_000L) { store.prepare(entry) }
            // Exercise the production DownloadService route, not manager.addDownload directly.
            store.add(request)
            val complete = withTimeout(35_000L) {
                while (true) {
                    val status = store.find(key)
                    if (status?.state == Download.STATE_COMPLETED) return@withTimeout status
                    if (status?.state == Download.STATE_FAILED)
                        error("DownloadService failed, code=" + status.failureReason)
                    delay(150)
                }
                error("unreachable")
            }
            assertTrue("DownloadService did not fetch the HLS segment", complete.bytesDownloaded > 0)
        } finally {
            withContext(Dispatchers.Main) { store.manager.removeDownload(key) }
            server.close()
        }
    }
}

private class LocalHlsServer(private val segment: ByteArray) : Closeable {
    private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    val root = "http://127.0.0.1:" + socket.localPort
    val playlistUrl = root + "/clip.m3u8"

    init {
        worker.execute {
            while (!closed) {
                try {
                    socket.accept().use { conn ->
                        conn.soTimeout = 3000
                        val input = BufferedReader(InputStreamReader(conn.getInputStream(), StandardCharsets.US_ASCII))
                        val path = input.readLine()?.split(' ')?.getOrNull(1)
                        while (true) {
                            val header = input.readLine() ?: break
                            if (header.isBlank()) break
                        }
                        val data = when (path) {
                            "/clip.m3u8" -> (
                                "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-PLAYLIST-TYPE:VOD\n" +
                                "#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n" +
                                "#EXTINF:2.0,\nsample.ts\n#EXT-X-ENDLIST\n"
                            ).toByteArray(StandardCharsets.UTF_8)
                            "/sample.ts" -> segment
                            else -> null
                        }
                        val bytes = data ?: byteArrayOf()
                        val status = if (data == null) "404 Not Found" else "200 OK"
                        val mime = if (path == "/clip.m3u8") "application/vnd.apple.mpegurl" else "video/mp2t"
                        conn.getOutputStream().apply {
                            write(("HTTP/1.1 " + status + "\r\nContent-Type: " + mime +
                                "\r\nContent-Length: " + bytes.size +
                                "\r\nConnection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII))
                            write(bytes)
                            flush()
                        }
                    }
                } catch (e: IOException) {
                    if (!closed) throw e
                }
            }
        }
    }
    override fun close() {
        if (!closed) {
            closed = true
            socket.close()
            worker.shutdownNow()
        }
    }
}
