/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.shownName
import app.opentv.data.parser.displayTitle
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.VodViewModel
import app.opentv.ui.vod.CloudSeriesViewModel
import app.opentv.ui.vod.CloudVodViewModel
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Channel search with an on-screen keyboard.
 *
 * Android TV's default text field punts to "type on your phone", which is useless if your phone
 * isn't to hand. This screen draws its own d-pad keyboard so search works with the remote alone,
 * and shows matching channels live as you type.
 */
@Composable
fun SearchScreen(
    onPlayChannel: (Channel) -> Unit,
    onPlayMovie: (Movie) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onOpenCloudMovie: (providerId: String, itemId: String) -> Unit,
    onOpenCloudSeries: (providerId: String, itemId: String) -> Unit,
    onBack: () -> Unit,
    viewModel: ChannelsViewModel = viewModel(),
    vodViewModel: VodViewModel = viewModel(),
    cloudVodViewModel: CloudVodViewModel = viewModel(),
    cloudSeriesViewModel: CloudSeriesViewModel = viewModel(),
) {
    var query by remember { mutableStateOf("") }
    val channelResults by viewModel.searchResults.collectAsState()
    val movieResults by vodViewModel.movieResults.collectAsState()
    val seriesResults by vodViewModel.seriesResults.collectAsState()
    val cloudMovieResults by cloudVodViewModel.movieSearchResults.collectAsState()
    val cloudSeriesResults by cloudSeriesViewModel.searchResults.collectAsState()
    val anyResults = channelResults.isNotEmpty() || movieResults.isNotEmpty() ||
        seriesResults.isNotEmpty() || cloudMovieResults.isNotEmpty() || cloudSeriesResults.isNotEmpty()

    LaunchedEffect(query) {
        viewModel.setSearchQuery(query)
        vodViewModel.setVodSearchQuery(query)
        cloudVodViewModel.setMovieSearchQuery(query)
        cloudSeriesViewModel.setSearchQuery(query)
    }
    BackHandler { onBack() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.nav_search), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(20.dp))
            Text(
                query.ifEmpty { stringResource(R.string.search_type_name) },
                style = MaterialTheme.typography.titleLarge,
                color = if (query.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.common_done)) }
        }

        Spacer(Modifier.height(20.dp))

        Row(Modifier.fillMaxSize()) {
            OnScreenKeyboard(
                onKey = { if (query.length < 40) query += it },
                onSpace = { if (query.length < 40) query += " " },
                onBackspace = { query = query.dropLast(1) },
                onClear = { query = "" },
            )

            Spacer(Modifier.width(28.dp))

            Column(Modifier.weight(1f).fillMaxSize()) {
                when {
                    query.isBlank() -> Hint(stringResource(R.string.search_start_hint))
                    query.trim().length < 2 -> Hint(stringResource(R.string.common_keep_typing))
                    !anyResults -> Hint(stringResource(R.string.search_no_results, query))
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (channelResults.isNotEmpty()) {
                            item { SectionHeader(stringResource(R.string.common_channels)) }
                            items(channelResults, key = { "c${it.key}" }) { row ->
                                SearchResultRow(row = row, onClick = { onPlayChannel(row.primary) })
                            }
                        }
                        if (movieResults.isNotEmpty() || cloudMovieResults.isNotEmpty()) {
                            item { SectionHeader(stringResource(R.string.nav_movies)) }
                            items(movieResults, key = { "m${it.id}" }) { movie ->
                                VodResultRow(movie.displayTitle, movie.posterUrl, movie.year?.toString()) {
                                    onPlayMovie(movie)
                                }
                            }
                            items(cloudMovieResults, key = { "cm:" + it.id }) { movie ->
                                val source = movie.sources.firstOrNull()
                                val subtitle = buildList {
                                    movie.year?.let { add(it.toString()) }
                                    if (movie.sources.size > 1) {
                                        add(stringResource(R.string.cloud_sources_count, movie.sources.size))
                                    }
                                }.joinToString(" · ").takeIf { it.isNotBlank() }
                                VodResultRow(movie.title, movie.posterUrl, subtitle) {
                                    source?.let { onOpenCloudMovie(it.providerId, it.itemId) }
                                }
                            }
                        }
                        if (seriesResults.isNotEmpty() || cloudSeriesResults.isNotEmpty()) {
                            item { SectionHeader(stringResource(R.string.nav_shows)) }
                            items(seriesResults, key = { "s${it.id}" }) { show ->
                                VodResultRow(show.displayTitle, show.posterUrl, show.year?.toString()) {
                                    onOpenSeries(show)
                                }
                            }
                            items(cloudSeriesResults, key = { "cs:" + it.id }) { show ->
                                val source = show.sources.firstOrNull()
                                val subtitle = buildList {
                                    show.year?.let { add(it.toString()) }
                                    if (show.sources.size > 1) {
                                        add(stringResource(R.string.cloud_sources_count, show.sources.size))
                                    }
                                }.joinToString(" · ").takeIf { it.isNotBlank() }
                                VodResultRow(show.title, show.posterUrl, subtitle) {
                                    source?.let { onOpenCloudSeries(it.providerId, it.itemId) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun VodResultRow(name: String, posterUrl: String?, subtitle: String?, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .then(
                if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = posterUrl,
            contentDescription = null,
            modifier = Modifier.size(width = 34.dp, height = 48.dp).clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SearchResultRow(row: ChannelsViewModel.Row, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .then(
                if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = row.primary.logoUrl,
            contentDescription = null,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                row.primary.shownName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.now?.let { "${searchTime(it.startUtcMillis)}  ${it.title}" } ?: stringResource(R.string.guide_no_info),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val searchTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private fun searchTime(utcMillis: Long): String = searchTimeFormat.format(Date(utcMillis))
