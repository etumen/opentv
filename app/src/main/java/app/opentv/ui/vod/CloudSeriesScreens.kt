/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.data.catalog.CanonicalContent
import app.opentv.data.catalog.CanonicalEpisode
import app.opentv.data.catalog.UnifiedSeriesDetails
import app.opentv.data.provider.ProviderDetails
import app.opentv.data.provider.ProviderItem
import app.opentv.data.provider.ProviderResult
import app.opentv.data.provider.ProviderStream
import app.opentv.data.provider.ProviderSubtitle
import coil.compose.AsyncImage
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
internal fun UnifiedSeriesPosterRow(
    items: List<CanonicalContent>,
    hasMore: Boolean,
    loadingMore: Boolean,
    onOpenSeries: (CanonicalContent) -> Unit,
    onLoadMore: () -> Unit,
) {
    if (items.isEmpty()) return
    val rowState = rememberLazyListState()

    LaunchedEffect(items.size, hasMore) {
        if (!hasMore) return@LaunchedEffect
        snapshotFlow { rowState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { lastVisible ->
                if (lastVisible >= items.lastIndex - 4) onLoadMore()
            }
    }

    Column(Modifier.fillMaxWidth()) {
        SectionHeader(stringResource(R.string.nav_shows))
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { "unified:" + it.id }) { item ->
                val providerId = item.sources.firstOrNull()?.providerId.orEmpty()
                val subtitle = buildList {
                    item.year?.let { add(it.toString()) }
                    if (item.sources.size > 1) {
                        add(stringResource(R.string.cloud_sources_count, item.sources.size))
                    }
                }.joinToString(" · ").takeIf { it.isNotBlank() }
                PosterCard(
                    title = item.title,
                    posterUrl = item.posterUrl,
                    providerId = providerId,
                    subtitle = subtitle,
                    rating = item.rating,
                    onClick = { onOpenSeries(item) },
                )
            }
            if (loadingMore) {
                item(key = "unified-loading") {
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
        CanonicalEpisode,
        ProviderStream,
        ProviderSubtitle?,
    ) -> Unit,
    onBack: () -> Unit,
    viewModel: CloudSeriesViewModel = viewModel(),
) {
    var details by remember(providerId, itemId) { mutableStateOf<ProviderDetails?>(null) }
    var unified by remember(providerId, itemId) { mutableStateOf<UnifiedSeriesDetails?>(null) }
    var episodes by remember(providerId, itemId) { mutableStateOf<List<CanonicalEpisode>>(emptyList()) }
    var loadingEpisodes by remember(providerId, itemId) { mutableStateOf(true) }
    var error by remember(providerId, itemId) { mutableStateOf<String?>(null) }
    var resolvingEpisodeId by remember(providerId, itemId) { mutableStateOf<String?>(null) }
    var pickerEpisode by remember(providerId, itemId) { mutableStateOf<CanonicalEpisode?>(null) }
    var pickerStreams by remember(providerId, itemId) { mutableStateOf<List<ProviderStream>>(emptyList()) }
    var pickerSubtitles by remember(providerId, itemId) { mutableStateOf<List<ProviderSubtitle>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val noStreamMessage = stringResource(R.string.cloud_no_stream)

    BackHandler { onBack() }

    if (providerId == "dizipal" || unified?.content?.sources?.any { it.providerId == "dizipal" } == true) {
        DiziPalSessionAnchor()
    }

    LaunchedEffect(providerId, itemId) {
        when (val result = viewModel.unifiedDetails(providerId, itemId)) {
            is ProviderResult.Success -> {
                unified = result.value
                details = result.value.details
                episodes = result.value.episodes
            }
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
    val sourceCount = unified?.content?.sources?.size ?: 1
    val meta = buildList {
        item.year?.let { add(it.toString()) }
        item.rating?.takeIf { it > 0.0 }?.let { add("★ %.1f".format(it)) }
        if (sourceCount > 1) add(stringResource(R.string.cloud_sources_count, sourceCount))
    }.joinToString("  ·  ")
    val seasons = remember(episodes) {
        episodes.groupBy { it.season }
            .mapValues { (_, values) -> values.sortedBy { it.episodeNumber } }
            .toSortedMap()
    }

    var selectedSeason by remember(providerId, itemId) { mutableStateOf<Int?>(null) }
    LaunchedEffect(seasons.keys.toList()) {
        if (selectedSeason == null || !seasons.containsKey(selectedSeason)) {
            selectedSeason = seasons.keys.firstOrNull()
        }
    }
    val activeSeason = selectedSeason ?: seasons.keys.firstOrNull()

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
        } else if (activeSeason != null) {
            item(key = "season-selector") {
                SeasonSelector(
                    seasons = seasons.keys.toList(),
                    selected = activeSeason,
                    onSelect = { selectedSeason = it },
                )
            }
            item(key = "episodes:" + activeSeason) {
                SectionHeader(stringResource(R.string.vod_season, activeSeason))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(
                        seasons[activeSeason].orEmpty(),
                        key = { "episode:" + it.id },
                    ) { episode ->
                        CloudEpisodeCard(
                            episode = episode,
                            resolving = resolvingEpisodeId == episode.id,
                            onClick = {
                                if (resolvingEpisodeId != null) return@CloudEpisodeCard
                                resolvingEpisodeId = episode.id
                                error = null
                                scope.launch {
                                    when (val result = viewModel.episodePlayback(episode)) {
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
private fun SeasonSelector(
    seasons: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(seasons, key = { "season-pill:" + it }) { season ->
            var focused by remember(season) { mutableStateOf(false) }
            val isSelected = season == selected
            val scale by animateFloatAsState(
                targetValue = if (focused) 1.04f else 1f,
                label = "seasonScale",
            )
            Text(
                text = stringResource(R.string.vod_season, season),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (isSelected) Color.Black else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .onFocusChanged { focused = it.isFocused }
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        if (isSelected) Color.White
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .then(
                        if (focused) Modifier.border(
                            2.dp,
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(20.dp),
                        ) else Modifier,
                    )
                    .clickable { onSelect(season) }
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun CloudEpisodeCard(
    episode: CanonicalEpisode,
    resolving: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember(episode.id) { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.05f else 1f,
        label = "episodeCardScale",
    )
    val shape = RoundedCornerShape(14.dp)
    val episodeMeta = buildList {
        add("S" + episode.season + " · B" + episode.episodeNumber)
        if (episode.sources.size > 1) {
            add(stringResource(R.string.cloud_sources_count, episode.sources.size))
        }
    }.joinToString(" · ")

    Box(
        Modifier
            .width(380.dp)
            .height(214.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (focused) Modifier.border(
                    3.dp,
                    MaterialTheme.colorScheme.primary,
                    shape,
                ) else Modifier,
            )
            .clickable(enabled = !resolving, onClick = onClick)
            .focusable(enabled = !resolving),
    ) {
        episode.stillUrl?.takeIf { it.isNotBlank() }?.let { stillUrl ->
            AsyncImage(
                model = stillUrl,
                contentDescription = episode.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (episode.stillUrl.isNullOrBlank()) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = if (focused) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.White.copy(alpha = 0.72f)
                },
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(48.dp)
                    .height(48.dp),
            )
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.18f),
                            Color.Black.copy(alpha = 0.88f),
                        ),
                        startY = 55f,
                    ),
                ),
        )

        if (resolving) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(38.dp)
                    .height(38.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(
                episodeMeta,
                style = MaterialTheme.typography.labelMedium,
                color = if (focused) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.White.copy(alpha = 0.78f)
                },
            )
            Spacer(Modifier.height(3.dp))
            Text(
                episode.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
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
