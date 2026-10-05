/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.data.provider.ProviderDetails
import app.opentv.data.provider.ProviderEpisode
import app.opentv.data.provider.ProviderItem
import app.opentv.data.provider.ProviderResult
import app.opentv.data.provider.ProviderStream
import app.opentv.data.provider.ProviderSubtitle
import kotlinx.coroutines.launch

@Composable
internal fun CloudSeriesTvControls(
    providers: List<CloudSeriesProvider>,
    selectedProviderId: String?,
    selectedSectionId: String?,
    onSelectProvider: (String) -> Unit,
    onSelectSection: (String?) -> Unit,
) {
    if (providers.isEmpty()) return
    val selectedProvider = providers.firstOrNull { it.id == selectedProviderId }
        ?: providers.first()

    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(providers, key = { "provider:" + it.id }) { provider ->
            FilterChip(
                selected = provider.id == selectedProvider.id,
                onClick = { onSelectProvider(provider.id) },
                label = { Text(provider.name) },
            )
        }
    }

    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "home") {
            FilterChip(
                selected = selectedSectionId == null,
                onClick = { onSelectSection(null) },
                label = { Text(stringResource(R.string.cloud_home)) },
            )
        }
        items(selectedProvider.sections, key = { "section:" + it.id }) { section ->
            FilterChip(
                selected = section.id == selectedSectionId,
                onClick = { onSelectSection(section.id) },
                label = {
                    Text(
                        section.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

@Composable
internal fun CloudSeriesPosterRow(
    shelf: CloudSeriesShelf,
    onOpenSeries: (ProviderItem) -> Unit,
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
            items(shelf.items, key = { shelf.providerId + ":" + it.id }) { item ->
                PosterCard(
                    title = item.title,
                    posterUrl = item.posterUrl,
                    providerId = item.providerId,
                    subtitle = item.year?.toString(),
                    rating = item.rating,
                    onClick = { onOpenSeries(item) },
                )
            }
            if (shelf.loadingMore) {
                item(key = "loading:" + shelf.section.id) {
                    Box(
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
internal fun CloudSeriesCategoryGrid(
    items: List<ProviderItem>,
    hasNextPage: Boolean,
    loading: Boolean,
    loadingMore: Boolean,
    onOpenSeries: (ProviderItem) -> Unit,
    onLoadMore: () -> Unit,
) {
    if (loading && items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.vod_no_shows),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val gridState = rememberLazyGridState()
    LaunchedEffect(items.size, hasNextPage) {
        if (!hasNextPage) return@LaunchedEffect
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { lastVisible ->
                if (lastVisible >= items.lastIndex - 6) onLoadMore()
            }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 112.dp),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        gridItems(items, key = { it.providerId + ":" + it.id }) { item ->
            PosterCard(
                title = item.title,
                posterUrl = item.posterUrl,
                providerId = item.providerId,
                subtitle = item.year?.toString(),
                rating = item.rating,
                onClick = { onOpenSeries(item) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (loadingMore) {
            item(
                key = "loading-more",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
fun CloudSeriesDetailScreen(
    providerId: String,
    itemId: String,
    onPlayEpisode: (
        ProviderDetails,
        ProviderEpisode,
        ProviderStream,
        ProviderSubtitle?,
    ) -> Unit,
    onBack: () -> Unit,
    viewModel: CloudSeriesViewModel = viewModel(),
) {
    var details by remember(providerId, itemId) { mutableStateOf<ProviderDetails?>(null) }
    var episodes by remember(providerId, itemId) { mutableStateOf<List<ProviderEpisode>>(emptyList()) }
    var loadingEpisodes by remember(providerId, itemId) { mutableStateOf(true) }
    var error by remember(providerId, itemId) { mutableStateOf<String?>(null) }
    var resolvingEpisodeId by remember(providerId, itemId) { mutableStateOf<String?>(null) }
    var pickerEpisode by remember(providerId, itemId) { mutableStateOf<ProviderEpisode?>(null) }
    var pickerStreams by remember(providerId, itemId) { mutableStateOf<List<ProviderStream>>(emptyList()) }
    var pickerSubtitles by remember(providerId, itemId) { mutableStateOf<List<ProviderSubtitle>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val noStreamMessage = stringResource(R.string.cloud_no_stream)

    BackHandler { onBack() }

    if (providerId == "dizipal") {
        DiziPalSessionAnchor()
    }

    LaunchedEffect(providerId, itemId) {
        when (val result = viewModel.details(providerId, itemId)) {
            is ProviderResult.Success -> details = result.value
            is ProviderResult.Failure -> error = result.error.message
        }
        when (val result = viewModel.episodes(providerId, itemId)) {
            is ProviderResult.Success -> episodes = result.value
            is ProviderResult.Failure -> error = result.error.message
        }
        loadingEpisodes = false
    }

    val current = details
    if (current == null) {
        if (error == null) LoadingDetail()
        else CloudSeriesError(message = error.orEmpty(), onBack = onBack)
        return
    }

    val item = current.item
    val providerName = viewModel.providerName(providerId)
    val meta = buildList {
        item.year?.let { add(it.toString()) }
        item.rating?.takeIf { it > 0.0 }?.let { add("★ %.1f".format(it)) }
        add(providerName)
    }.joinToString("  ·  ")
    val seasons = remember(episodes) {
        episodes.groupBy { it.season }
            .mapValues { (_, values) -> values.sortedBy { it.episodeNumber } }
            .toSortedMap()
    }

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

        if (loadingEpisodes) {
            item(key = "episodes-loading") {
                Row(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        } else {
            seasons.forEach { (season, values) ->
                item(key = "season:" + season) {
                    Spacer(Modifier.height(8.dp))
                    SectionHeader(stringResource(R.string.vod_season, season))
                }
                items(
                    values,
                    key = { "episode:" + it.id },
                ) { episode ->
                    CloudEpisodeRow(
                        episode = episode,
                        resolving = resolvingEpisodeId == episode.id,
                        onClick = {
                            if (resolvingEpisodeId != null) return@CloudEpisodeRow
                            resolvingEpisodeId = episode.id
                            error = null
                            scope.launch {
                                when (
                                    val result = viewModel.episodePlayback(
                                        providerId = providerId,
                                        seriesId = itemId,
                                        episodeId = episode.id,
                                    )
                                ) {
                                    is ProviderResult.Success -> {
                                        val playback = result.value
                                        when (playback.streams.size) {
                                            0 -> error = noStreamMessage
                                            1 -> onPlayEpisode(
                                                current,
                                                episode,
                                                playback.streams.single(),
                                                preferredSeriesSubtitle(playback.subtitles),
                                            )
                                            else -> {
                                                pickerEpisode = episode
                                                pickerStreams = playback.streams
                                                pickerSubtitles = playback.subtitles
                                            }
                                        }
                                    }
                                    is ProviderResult.Failure -> error = result.error.message
                                }
                                resolvingEpisodeId = null
                            }
                        },
                    )
                }
            }
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

    val selectedEpisode = pickerEpisode
    if (selectedEpisode != null && pickerStreams.isNotEmpty()) {
        CloudEpisodeStreamPicker(
            streams = pickerStreams,
            onPick = { stream ->
                pickerEpisode = null
                onPlayEpisode(
                    current,
                    selectedEpisode,
                    stream,
                    preferredSeriesSubtitle(pickerSubtitles),
                )
            },
            onDismiss = { pickerEpisode = null },
        )
    }
}

@Composable
private fun CloudEpisodeRow(
    episode: ProviderEpisode,
    resolving: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember(episode.id) { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (focused) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
            )
            .clickable(enabled = !resolving, onClick = onClick)
            .focusable(enabled = !resolving)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (resolving) {
            CircularProgressIndicator(
                modifier = Modifier.width(30.dp).height(30.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "S" + episode.season + " · B" + episode.episodeNumber,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                episode.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun preferredSeriesSubtitle(
    values: List<ProviderSubtitle>,
): ProviderSubtitle? =
    values.firstOrNull { it.language.equals("tr", ignoreCase = true) }
        ?: values.firstOrNull { it.label.contains("Türk", ignoreCase = true) }
        ?: values.firstOrNull()

@Composable
private fun CloudEpisodeStreamPicker(
    streams: List<ProviderStream>,
    onPick: (ProviderStream) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
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
                var focused by remember(stream.url) { mutableStateOf(false) }
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
                    Text(
                        stream.label,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun CloudSeriesError(
    message: String,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
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
