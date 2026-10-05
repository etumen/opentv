/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.data.provider.ProviderDetails
import app.opentv.data.provider.ProviderItem
import app.opentv.data.provider.ProviderResult
import app.opentv.data.provider.ProviderStream
import app.opentv.data.provider.ProviderSubtitle
import kotlinx.coroutines.launch

@Composable
internal fun CloudMoviePosterRow(
    shelf: CloudMovieShelf,
    onOpenMovie: (ProviderItem) -> Unit,
    onLoadMore: () -> Unit,
) {
    val rowState = rememberLazyListState()

    LaunchedEffect(shelf.items.size, shelf.nextPage) {
        if (shelf.nextPage == null) return@LaunchedEffect
        snapshotFlow { rowState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { lastVisible ->
                if (lastVisible >= shelf.items.lastIndex - 3) onLoadMore()
            }
    }

    Column(Modifier.fillMaxWidth()) {
        SectionHeader(shelf.section.title)
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(shelf.items, key = { "${shelf.providerId}:${it.id}" }) { item ->
                PosterCard(
                    title = item.title,
                    posterUrl = item.posterUrl,
                    providerId = item.providerId,
                    subtitle = item.year?.toString(),
                    rating = item.rating,
                    onClick = { onOpenMovie(item) },
                )
            }
            if (shelf.loadingMore) {
                item(key = "loading:${shelf.section.id}") {
                    androidx.compose.foundation.layout.Box(
                        Modifier.width(72.dp).height(180.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

@Composable
fun CloudMovieDetailScreen(
    providerId: String,
    itemId: String,
    onPlay: (ProviderDetails, ProviderStream, ProviderSubtitle?) -> Unit,
    onBack: () -> Unit,
    viewModel: CloudVodViewModel = viewModel(),
) {
    var details by remember(providerId, itemId) { mutableStateOf<ProviderDetails?>(null) }
    var error by remember(providerId, itemId) { mutableStateOf<String?>(null) }
    var resolving by remember(providerId, itemId) { mutableStateOf(false) }
    var pickerStreams by remember(providerId, itemId) { mutableStateOf<List<ProviderStream>?>(null) }
    var resolvedSubtitles by remember(providerId, itemId) { mutableStateOf<List<ProviderSubtitle>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val noStreamMessage = stringResource(R.string.cloud_no_stream)

    BackHandler { onBack() }

    LaunchedEffect(providerId, itemId) {
        when (val result = viewModel.details(providerId, itemId)) {
            is ProviderResult.Success -> details = result.value
            is ProviderResult.Failure -> error = result.error.message
        }
    }

    val current = details
    if (current == null) {
        if (error == null) {
            LoadingDetail()
        } else {
            CloudMovieError(message = error.orEmpty(), onBack = onBack)
        }
        return
    }

    val item = current.item
    val providerName = viewModel.providerName(providerId)
    val meta = buildList {
        item.year?.let { add(it.toString()) }
        item.rating?.takeIf { it > 0.0 }?.let { add("★ %.1f".format(it)) }
        add(providerName)
    }.joinToString("  ·  ")

    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "header") {
            DetailBackdrop(
                title = item.title,
                backdropUrl = item.backdropUrl ?: item.posterUrl,
                posterUrl = item.posterUrl,
                meta = meta,
            ) {
                DetailButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    label = stringResource(R.string.common_back),
                    onClick = onBack,
                )
                DetailButton(
                    icon = Icons.Filled.PlayArrow,
                    label = stringResource(
                        if (resolving) R.string.cloud_resolving_stream else R.string.vod_watch_now,
                    ),
                    primary = true,
                ) {
                    if (resolving) return@DetailButton
                    resolving = true
                    error = null
                    scope.launch {
                        when (val result = viewModel.playback(providerId, itemId)) {
                            is ProviderResult.Success -> {
                                val playback = result.value
                                resolvedSubtitles = playback.subtitles
                                when (playback.streams.size) {
                                    0 -> error = noStreamMessage
                                    1 -> onPlay(
                                        current,
                                        playback.streams.single(),
                                        preferredSubtitle(playback.subtitles),
                                    )
                                    else -> pickerStreams = playback.streams
                                }
                            }
                            is ProviderResult.Failure -> error = result.error.message
                        }
                        resolving = false
                    }
                }
            }
        }

        item(key = "info") {
            DetailInfo(
                plot = current.description,
                cast = current.cast.joinToString(", ").takeIf { it.isNotBlank() },
                director = current.director,
                genre = item.genres.joinToString(", ").takeIf { it.isNotBlank() },
                onOpenPerson = {},
            )
        }

        error?.let { message ->
            item(key = "error") {
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
        }
    }

    pickerStreams?.let { streams ->
        CloudStreamPicker(
            streams = streams,
            onPick = { stream ->
                pickerStreams = null
                onPlay(current, stream, preferredSubtitle(resolvedSubtitles))
            },
            onDismiss = { pickerStreams = null },
        )
    }
}

private fun preferredSubtitle(values: List<ProviderSubtitle>): ProviderSubtitle? =
    values.firstOrNull { it.language.equals("tr", ignoreCase = true) }
        ?: values.firstOrNull { it.label.contains("Türk", ignoreCase = true) }
        ?: values.firstOrNull()

@Composable
private fun CloudStreamPicker(
    streams: List<ProviderStream>,
    onPick: (ProviderStream) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(20.dp),
        ) {
            Text(
                stringResource(R.string.cloud_choose_stream),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(12.dp))
            streams.forEach { stream ->
                CloudStreamRow(stream = stream, onPick = onPick)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun CloudStreamRow(
    stream: ProviderStream,
    onPick: (ProviderStream) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (focused) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable { onPick(stream) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stream.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            stream.quality?.takeIf { it.isNotBlank() }?.let { quality ->
                Text(
                    quality,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CloudMovieError(
    message: String,
    onBack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.common_back),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onBack)
                .padding(horizontal = 18.dp, vertical = 12.dp),
        )
    }
}
