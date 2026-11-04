/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import android.app.Notification
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download as MediaDownload
import androidx.media3.exoplayer.offline.DownloadManager as MediaDownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import app.opentv.R

/** Runs Media3 HLS downloads in the background using one persisted non-evicting cache. */
@UnstableApi
class OpenTvHlsDownloadService : DownloadService(
    NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.nav_downloads,
    0,
) {
    override fun getDownloadManager(): MediaDownloadManager =
        OfflineHlsStore.get(applicationContext).manager

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(
        downloads: MutableList<MediaDownload>,
        notMetRequirements: Int,
    ): Notification {
        val active = downloads.firstOrNull { it.state == MediaDownload.STATE_DOWNLOADING }
        val pct = active?.percentDownloaded?.takeIf { it >= 0f }?.toInt()
        val title = if (active != null) "OpenTV · Video indiriliyor" else "OpenTV · İndirme bekliyor"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(
                if (active != null && pct != null) "İlerleme: %$pct"
                else if (active != null) "Video parçaları kaydediliyor"
                else "İndirme kuyruğu",
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct ?: 0, pct == null)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "opentv_hls_downloads"
        private const val NOTIFICATION_ID = 8421
    }
}
