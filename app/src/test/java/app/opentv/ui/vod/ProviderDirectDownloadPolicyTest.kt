package app.opentv.ui.downloads

import app.opentv.data.db.Converters
import app.opentv.data.provider.ProviderStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderDirectDownloadPolicyTest {
    private fun stream(url: String, mime: String? = null) =
        ProviderStream(providerId = "test", url = url, mimeType = mime)

    @Test fun allowsDirectFilesAndSignedMp4Urls() {
        assertTrue(ProviderOfflineDownloadPolicy.supports(stream("https://video.example/item.mp4?token=abc")))
        assertTrue(ProviderOfflineDownloadPolicy.supports(stream("https://video.example/play?ticket=abc", "video/mp4")))
        assertTrue(ProviderOfflineDownloadPolicy.supports(stream("https://video.example/item.webm")))
    }

    @Test fun supportsHlsButRejectsDashAndUnknownEndpoints() {
        assertTrue(ProviderOfflineDownloadPolicy.supports(stream("https://video.example/master.m3u8?token=abc")))
        assertTrue(ProviderOfflineDownloadPolicy.supports(stream("https://video.example/play?ticket=abc", "application/vnd.apple.mpegurl")))
        assertFalse(ProviderOfflineDownloadPolicy.supports(stream("https://video.example/manifest.mpd")))
        assertFalse(ProviderOfflineDownloadPolicy.supports(stream("https://video.example/unknown")))
        assertFalse(ProviderOfflineDownloadPolicy.supports(stream("file:///sdcard/video.mp4")))
    }

    @Test fun preservesRefererAndCookieForQueuedDownloads() {
        val headers = linkedMapOf("Referer" to "https://provider.example/watch", "Cookie" to "session=test")
        val converters = Converters()
        assertEquals(headers, converters.stringToHeaders(converters.headersToString(headers)))
    }
}
