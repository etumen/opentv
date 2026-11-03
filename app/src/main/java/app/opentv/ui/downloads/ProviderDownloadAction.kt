/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.downloads

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Download
import app.opentv.data.provider.ProviderResult
import app.opentv.data.provider.ProviderStream
import app.opentv.data.repo.DownloadRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Detect offline-capable direct files and Media3 HLS playlists; reject DASH for now. */
internal object ProviderOfflineDownloadPolicy {
    fun isHls(stream: ProviderStream): Boolean =
        stream.url.substringBefore('?').substringBefore('#')
            .contains(".m3u8", ignoreCase = true) ||
            stream.mimeType.orEmpty().contains("mpegurl", ignoreCase = true)

    fun supports(stream: ProviderStream): Boolean {
        if (!stream.url.startsWith("https://", true) && !stream.url.startsWith("http://", true)) return false
        val mime = stream.mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        val path = stream.url.substringBefore('?').substringBefore('#').lowercase()
        if (isHls(stream)) return true
        if (".mpd" in path || mime.contains("dash+xml")) return false
        return mime in setOf("video/mp4", "video/webm", "video/x-matroska") ||
            listOf(".mp4", ".m4v", ".webm", ".mkv").any(path::endsWith)
    }
}

/**
 * Provider detail action: resolves a fresh URL only after the user taps Download.
 * Preserves source-specific headers (Referer, Cookie, Origin, User-Agent) for the
 * queue to use when the transfer starts later.
 */
@Composable
internal fun ProviderDownloadAction(
    mediaKey: String,
    compact: Boolean = false,
    resolveStreams: suspend () -> ProviderResult<List<ProviderStream>>,
    buildDownload: (ProviderStream) -> Download,
) {
    val context = LocalContext.current
    val repository = remember { ServiceLocator.get(context).downloadRepository }
    val current by remember(mediaKey) { repository.observe(mediaKey) }.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var resolving by remember(mediaKey) { mutableStateOf(false) }
    var choices by remember(mediaKey) { mutableStateOf<List<ProviderStream>>(emptyList()) }

    val notify: (String) -> Unit = { message ->
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
    val queueStream: (ProviderStream) -> Unit = { stream ->
        if (!resolving) {
            resolving = true
            scope.launch {
                try {
                    app.opentv.core.NotificationPermission.askIfNeeded(context)
                    val download = buildDownload(stream)
                    val result = repository.enqueue(
                        download,
                        stream.headers.entries.firstOrNull {
                            it.key.equals("User-Agent", ignoreCase = true)
                        }?.value ?: DownloadRepository.USER_AGENT,
                        isHls = ProviderOfflineDownloadPolicy.isHls(stream),
                    )
                    notify(startMessage(context, download.title, result))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    notify("İndirme başlatılamadı: " + (e.message ?: "Bilinmeyen hata"))
                } finally {
                    resolving = false
                }
            }
        }
    }
    val start: () -> Unit = {
        if (!resolving) {
            resolving = true
            scope.launch {
                var selected: ProviderStream? = null
                try {
                    val response = resolveStreams()
                    val sources = when (response) {
                        is ProviderResult.Success -> response.value.filter(ProviderOfflineDownloadPolicy::supports)
                        is ProviderResult.Failure -> {
                            notify("Kaynak çözümlenemedi: " + response.error.message)
                            emptyList()
                        }
                    }
                    if (response is ProviderResult.Success && sources.isEmpty()) {
                        notify("Bu kaynak için çevrimdışı indirme desteklenmiyor.")
                    } else if (sources.size == 1) {
                        selected = sources.single()
                    } else if (sources.size > 1) {
                        choices = sources
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    notify("Video kaynağı alınamadı: " + (e.message ?: "Bilinmeyen hata"))
                } finally {
                    resolving = false
                }
                selected?.let(queueStream)
            }
        }
    }
    val state = current?.state
    val enabled = !resolving && (state == null || state == DownloadRepository.State.FAILED)
    val label = when (state) {
        DownloadRepository.State.DONE -> "İndirildi"
        DownloadRepository.State.RUNNING,
        DownloadRepository.State.PAUSED,
        DownloadRepository.State.QUEUED -> "İndiriliyor"
        else -> "İndir"
    }
    if (compact) {
        IconButton(onClick = start, enabled = enabled) {
            if (resolving) CircularProgressIndicator(modifier = Modifier.width(22.dp))
            else Icon(
                if (state == DownloadRepository.State.DONE) Icons.Filled.DownloadDone
                else Icons.Filled.Download,
                contentDescription = label,
            )
        }
    } else {
        OutlinedButton(onClick = start, enabled = enabled) {
            Icon(
                if (state == DownloadRepository.State.DONE) Icons.Filled.DownloadDone
                else Icons.Filled.Download,
                contentDescription = null,
            )
            Spacer(Modifier.width(8.dp))
            Text(if (resolving) "Kaynak aranıyor…" else label)
        }
    }

    if (choices.isNotEmpty()) {
        Dialog(onDismissRequest = { choices = emptyList() }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.widthIn(max = 500.dp).fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("İndirilecek video kaynağı")
                    choices.forEach { stream ->
                        TextButton(onClick = {
                            choices = emptyList()
                            queueStream(stream)
                        }) { Text(stream.label + (stream.quality?.let { " · $it" } ?: "")) }
                    }
                    TextButton(onClick = { choices = emptyList() }) { Text("İptal") }
                }
            }
        }
    }
}
