/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.downloads

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Download
import app.opentv.data.repo.DownloadRepository
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/**
 * The Downloads tab: what's saved on this device for offline viewing, films and series apart.
 * Series are grouped by show, then ordered by season and episode. Tap a finished one to play it
 * from the file; the bin deletes it (and cancels it if it's still downloading).
 */
@Composable
fun DownloadsScreen(onPlay: (mediaKey: String, url: String, title: String) -> Unit, onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val repo = remember { ServiceLocator.get(context).downloadRepository }
    val items by remember { repo.observe() }.collectAsState(initial = emptyList())
    var showSeries by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Download?>(null) }
    val scope = rememberCoroutineScope()

    val films = items.filter { it.download.kind == KIND_MOVIE }
    val episodes = items.filter { it.download.kind == KIND_EPISODE }
        .sortedWith(compareBy({ it.download.seriesTitle }, { it.download.season }, { it.download.episode }))
    val used = items.sumOf { if (it.state == DownloadRepository.State.DONE) it.totalBytes else it.bytesSoFar }

    Column(Modifier.fillMaxSize()) {
        if (onBack != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.common_back),
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.nav_downloads),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = !showSeries,
                onClick = { showSeries = false },
                label = { Text("${stringResource(R.string.nav_movies)} (${films.size})") },
            )
            FilterChip(
                selected = showSeries,
                onClick = { showSeries = true },
                label = { Text("${stringResource(R.string.nav_shows)} (${episodes.size})") },
            )
        }
        Text(
            stringResource(
                R.string.dl_used,
                Formatter.formatShortFileSize(context, used),
                Formatter.formatShortFileSize(context, repo.freeBytes()),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        val shown = if (showSeries) episodes else films
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.dl_empty),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (showSeries) {
                    episodes.groupBy { it.download.seriesTitle.orEmpty() }.forEach { (show, eps) ->
                        item(key = "h:$show") {
                            Text(
                                show,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                            )
                        }
                        items(eps, key = { it.download.id }) { item ->
                            DownloadRow(item, onPlay = { play(item, onPlay) }, onDelete = { confirmDelete = item.download })
                        }
                    }
                } else {
                    items(films, key = { it.download.id }) { item ->
                        DownloadRow(item, onPlay = { play(item, onPlay) }, onDelete = { confirmDelete = item.download })
                    }
                }
            }
        }
    }

    confirmDelete?.let { d ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            text = { Text(stringResource(R.string.dl_delete_confirm, d.title)) },
            confirmButton = {
                TextButton(onClick = { scope.launch { repo.remove(d) }; confirmDelete = null }) {
                    Text(stringResource(R.string.dl_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

private fun play(item: DownloadRepository.Item, onPlay: (String, String, String) -> Unit) {
    val file = item.fileUri ?: return
    if (item.state == DownloadRepository.State.DONE) onPlay(item.download.mediaKey, file, item.download.title)
}

@Composable
private fun DownloadRow(item: DownloadRepository.Item, onPlay: () -> Unit, onDelete: () -> Unit) {
    val d = item.download
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(enabled = item.state == DownloadRepository.State.DONE, onClick = onPlay)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = d.posterUrl,
            contentDescription = null,
            modifier = Modifier.size(width = 40.dp, height = 58.dp).clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val prefix = if (d.season != null && d.episode != null) "S${d.season}E${d.episode} · " else ""
            Text(prefix + d.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val size = Formatter.formatShortFileSize(context, if (item.totalBytes > 0) item.totalBytes else item.bytesSoFar)
            Text(
                listOfNotNull(d.tag, statusText(item), size.takeIf { item.totalBytes > 0 || item.bytesSoFar > 0 })
                    .joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = if (item.state == DownloadRepository.State.FAILED) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (item.state == DownloadRepository.State.RUNNING || item.state == DownloadRepository.State.PAUSED) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth())
            }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.dl_delete))
        }
    }
}

@Composable
private fun statusText(item: DownloadRepository.Item): String = when (item.state) {
    DownloadRepository.State.DONE -> stringResource(R.string.dl_downloaded)
    DownloadRepository.State.RUNNING -> stringResource(R.string.dl_downloading, (item.progress * 100).toInt())
    DownloadRepository.State.PAUSED -> stringResource(
        if (item.reason == android.app.DownloadManager.PAUSED_WAITING_TO_RETRY) R.string.dl_retrying else R.string.dl_paused,
    )
    DownloadRepository.State.QUEUED -> stringResource(R.string.dl_queued)
    DownloadRepository.State.FAILED ->
        stringResource(if (item.outOfSpace) R.string.dl_failed_space else R.string.dl_failed)
}

/** The toast after tapping Download: started, or why it wasn't (with sizes when known). */
fun startMessage(context: android.content.Context, title: String, result: DownloadRepository.Start): String =
    when (result) {
        DownloadRepository.Start.Started -> context.getString(R.string.dl_started, title)
        DownloadRepository.Start.Queued -> context.getString(R.string.dl_queued_toast, title)
        is DownloadRepository.Start.NoSpace ->
            if (result.neededBytes > 0) context.getString(
                R.string.dl_no_space_sized,
                Formatter.formatShortFileSize(context, result.neededBytes),
                Formatter.formatShortFileSize(context, result.freeBytes),
            ) else context.getString(R.string.dl_no_space)
    }

/**
 * The download control for one film or episode: "Download" when it isn't saved, live progress
 * while it downloads, a done tick when it's on the device. [compact] draws just an icon (episode
 * rows); otherwise a labelled button (the film page).
 */
@Composable
fun DownloadControl(
    mediaKey: String,
    buildDownload: () -> Download,
    compact: Boolean = false,
) {
    val context = LocalContext.current
    val graph = remember { ServiceLocator.get(context) }
    val repo = graph.downloadRepository
    val item by remember(mediaKey) { repo.observe(mediaKey) }.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val start: () -> Unit = {
        // "Download finished" is a notification (Android 13+ needs the permission).
        app.opentv.core.NotificationPermission.askIfNeeded(context)
        scope.launch {
            val d = buildDownload()
            val result = repo.enqueue(d)
            android.widget.Toast.makeText(context, startMessage(context, d.title, result), android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val state = item?.state
    if (compact) {
        when (state) {
            DownloadRepository.State.DONE ->
                Icon(Icons.Filled.DownloadDone, stringResource(R.string.dl_downloaded), tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(12.dp))
            DownloadRepository.State.RUNNING, DownloadRepository.State.QUEUED, DownloadRepository.State.PAUSED ->
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { item?.progress ?: 0f }, modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                }
            else -> IconButton(onClick = start) {
                Icon(Icons.Filled.Download, contentDescription = stringResource(R.string.dl_download))
            }
        }
        return
    }
    OutlinedButton(onClick = { if (state == null || state == DownloadRepository.State.FAILED) start() }) {
        Icon(
            if (state == DownloadRepository.State.DONE) Icons.Filled.DownloadDone else Icons.Filled.Download,
            contentDescription = null,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            when (state) {
                null -> stringResource(R.string.dl_download)
                else -> statusText(item!!)
            },
        )
    }
}

const val KIND_MOVIE = "MOVIE"
const val KIND_EPISODE = "EPISODE"

\n