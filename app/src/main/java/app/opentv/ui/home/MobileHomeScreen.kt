/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.provider.ProviderItem
import app.opentv.data.provider.ProviderMediaType
import app.opentv.ui.mobile.Tab
import app.opentv.ui.vod.CloudSeriesViewModel
import app.opentv.ui.vod.PosterCard
import app.opentv.ui.vod.ContinueWatchingRow
import app.opentv.ui.vod.UnifiedCatalogViewModel
import app.opentv.ui.VodViewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Mobile-first modern Home Screen inspired by REC TV design references.
 *
 * Approved phone home order: Featured, Continue Watching, Movies, Series, Drama,
 * Explore Genres. Live TV is available exclusively from its own bottom tab.
 *
 * All data originates from real OpenTV providers and catalog architecture without mock data.
 */
@Composable
fun MobileHomeScreen(
    hasSources: Boolean,
    onResume: (mediaKey: String, url: String, title: String) -> Unit,
    onOpenMovie: (Movie) -> Unit,
    onOpenCloudMovie: (ProviderItem) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onOpenCloudSeries: (ProviderItem) -> Unit,
    onPlayCloudItem: (ProviderItem, () -> Unit) -> Unit = { _, done -> done() },
    onSelectTab: (Tab) -> Unit,
    modifier: Modifier = Modifier,
    unifiedViewModel: UnifiedCatalogViewModel = viewModel(),
    cloudSeriesViewModel: CloudSeriesViewModel = viewModel(),
    vodViewModel: VodViewModel = viewModel(),
) {
    val moviesState by unifiedViewModel.movies.collectAsState()
    val seriesState by unifiedViewModel.series.collectAsState()
    val asianDramaItems by cloudSeriesViewModel.asianDramaItems.collectAsState()
    val dramaItems = remember(asianDramaItems) {
        asianDramaItems.mapNotNull { item ->
            val origin = item.sources.firstOrNull { it.providerId == "dramadizilerim" }
                ?: item.sources.firstOrNull() ?: return@mapNotNull null
            ProviderItem(
                providerId = origin.providerId,
                id = origin.itemId,
                title = item.title,
                mediaType = item.mediaType,
                posterUrl = item.posterUrl,
                year = item.year,
                rating = item.rating,
            )
        }
    }
    val resumeItems by vodViewModel.continueWatching.collectAsState()

    LaunchedEffect(Unit) {
        if (hasSources) {
            vodViewModel.ensureVodLoaded()
            vodViewModel.loadHomeFeeds()
        }
        unifiedViewModel.ensureLoaded(ProviderMediaType.MOVIE)
        unifiedViewModel.ensureLoaded(ProviderMediaType.SERIES)
        cloudSeriesViewModel.loadAsianDrama()
    }

    // Five real films, not one permanently pinned poster. Never invent titles.
    val featuredItems = remember(moviesState.items) {
        moviesState.items
            .filter { !it.backdropUrl.isNullOrBlank() || !it.posterUrl.isNullOrBlank() }
            .distinctBy { "${it.providerId}:${it.id}" }
            .take(5)
    }
    val heroPager = rememberPagerState(pageCount = { maxOf(1, featuredItems.size) })
    val pagerScope = rememberCoroutineScope()
    var resolvingId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(heroPager, featuredItems.size, resolvingId) {
        if (featuredItems.size > 1 && resolvingId == null) {
            while (true) {
                delay(6000)
                if (!heroPager.isScrollInProgress) {
                    heroPager.animateScrollToPage((heroPager.currentPage + 1) % featuredItems.size)
                }
            }
        }
    }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF08080B)),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        // 1. Featured Hero Banner
        item(key = "hero-banner") {
            Text(
                text = stringResource(R.string.home_featured),
                modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            HorizontalPager(
                state = heroPager,
                modifier = Modifier.fillMaxWidth(),
            ) { page ->
                val film = featuredItems.getOrNull(page)
                val filmKey = film?.let { "${it.providerId}:${it.id}" }
                HeroBanner(
                    item = film,
                    isResolving = filmKey != null && resolvingId == filmKey,
                    onWatch = {
                        if (film != null && resolvingId == null) {
                            resolvingId = filmKey
                            onPlayCloudItem(film) { resolvingId = null }
                        }
                    },
                    onDetails = { if (film != null) onOpenCloudMovie(film) },
                )
            }
            if (featuredItems.size > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(featuredItems.size) { index ->
                        Box(
                            modifier = Modifier.size(width = 32.dp, height = 24.dp)
                                .clickable { pagerScope.launch { heroPager.animateScrollToPage(index) } },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier.width(if (index == heroPager.currentPage) 24.dp else 8.dp)
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        if (index == heroPager.currentPage) Color(0xFFE50914)
                                        else Color.White.copy(alpha = 0.35f),
                                    ),
                            )
                        }
                    }
                }
            }
        }

        // 2. Resume uses the existing profile-scoped playback positions and player route.
        if (resumeItems.isNotEmpty()) {
            item(key = "section-continue") {
                ContinueWatchingRow(
                    items = resumeItems,
                    onResume = onResume,
                )
            }
        }

        // 3. Movies
        item(key = "section-movies") {
            HomeSectionHeader(
                title = stringResource(R.string.nav_movies),
                onSeeAll = { onSelectTab(Tab.MOVIES) },
            )
            val movieList = remember(moviesState.items) {
                if (moviesState.items.size > 1) moviesState.items.drop(1).take(20)
                else moviesState.items.take(20)
            }
            if (movieList.isEmpty() && moviesState.loading) {
                LoadingShelf()
            } else if (movieList.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(movieList, key = { "m-${it.providerId}-${it.id}" }) { item ->
                        PosterCard(
                            title = item.title,
                            posterUrl = item.posterUrl,
                            subtitle = item.year?.toString(),
                            rating = item.rating,
                            onClick = { onOpenCloudMovie(item) },
                        )
                    }
                }
            }
        }

        // 3. Popüler Diziler (Popular Series)
        item(key = "section-series") {
            Spacer(Modifier.height(10.dp))
            HomeSectionHeader(
                title = stringResource(R.string.nav_shows),
                onSeeAll = { onSelectTab(Tab.SHOWS) },
            )
            val seriesList = remember(seriesState.items) { seriesState.items.take(20) }
            if (seriesList.isEmpty() && seriesState.loading) {
                LoadingShelf()
            } else if (seriesList.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(seriesList, key = { "s-${it.providerId}-${it.id}" }) { item ->
                        PosterCard(
                            title = item.title,
                            posterUrl = item.posterUrl,
                            subtitle = item.year?.toString(),
                            rating = item.rating,
                            onClick = { onOpenCloudSeries(item) },
                        )
                    }
                }
            }
        }

        // 4. Drama (Drama)
        item(key = "section-drama") {
            Spacer(Modifier.height(10.dp))
            HomeSectionHeader(
                title = stringResource(R.string.home_drama),
                onSeeAll = { onSelectTab(Tab.DRAMA) },
            )
            val dramaList = remember(dramaItems) { dramaItems.take(20) }
            if (dramaList.isEmpty() && cloudSeriesViewModel.loadingAsianDrama.value) {
                LoadingShelf()
            } else if (dramaList.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(dramaList, key = { "d-${it.providerId}-${it.id}" }) { item ->
                        PosterCard(
                            title = item.title,
                            posterUrl = item.posterUrl,
                            subtitle = item.year?.toString(),
                            rating = item.rating,
                            onClick = { onOpenCloudSeries(item) },
                        )
                    }
                }
            }
        }

        // 6. Genre discovery comes from real unified provider categories.
        item(key = "section-genres") {
            val genres = remember(moviesState.categories, seriesState.categories) {
                (moviesState.categories.map { ProviderMediaType.MOVIE to it } +
                    seriesState.categories.map { ProviderMediaType.SERIES to it })
                    .distinctBy { it.second.id }
                    .take(16)
            }
            if (genres.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                HomeSectionHeader(
                    title = stringResource(R.string.home_explore_genres),
                    onSeeAll = { onSelectTab(Tab.MOVIES) },
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(genres, key = { "${it.first}:${it.second.id}" }) { (type, genre) ->
                        Box(
                            modifier = Modifier
                                .width(144.dp)
                                .height(86.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF18191F))
                                .border(1.dp, Color(0xFF2B2D36), RoundedCornerShape(12.dp))
                                .clickable {
                                    unifiedViewModel.selectCategory(type, genre.id)
                                    onSelectTab(if (type == ProviderMediaType.MOVIE) Tab.MOVIES else Tab.SHOWS)
                                }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                        ) {
                            Box(
                                Modifier.align(Alignment.TopStart)
                                    .width(30.dp).height(3.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color(0xFFE50914)),
                            )
                            Text(
                                text = genre.title,
                                modifier = Modifier.align(Alignment.CenterStart).padding(top = 10.dp),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Featured Hero Banner */
@Composable
private fun HeroBanner(
    item: ProviderItem?,
    isResolving: Boolean,
    onWatch: () -> Unit,
    onDetails: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(390.dp)
            .background(Color(0xFF0F1118)),
    ) {
        val artUrl = item?.backdropUrl?.takeIf { it.isNotBlank() } ?: item?.posterUrl
        if (!artUrl.isNullOrBlank()) {
            AsyncImage(
                model = artUrl,
                contentDescription = item?.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Gradient Scrim: top dark vignette + bottom deep fade
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.5f),
                        0.3f to Color.Transparent,
                        0.62f to Color(0xFF08080B).copy(alpha = 0.78f),
                        1.0f to Color(0xFF08080B),
                    ),
                ),
        )

        // Bottom Info
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            // Genre Pills
            val genres = item?.genres.orEmpty().take(3)
            if (genres.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 6.dp),
                ) {
                    genres.forEach { genre ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black.copy(alpha = 0.65f))
                                .border(0.5.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text(
                                text = genre,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFE2E4EC),
                            )
                        }
                    }
                }
            }

            // Title
            Text(
                text = item?.title ?: "OpenTV",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = 24.sp,
                    lineHeight = 29.sp,
                ),
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            // Rating + Year
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            ) {
                item?.rating?.takeIf { it > 0.0 }?.let { rating ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFE50914))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(11.dp),
                        )
                        Text(
                            text = "%.1f".format(rating),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                    }
                }
                item?.year?.let { year ->
                    Text(
                        text = year.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFB0B4C4),
                    )
                }
            }

            // Action Buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    onClick = onWatch,
                    enabled = !isResolving,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE50914),
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFFE50914).copy(alpha = 0.6f),
                        disabledContentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = if (isResolving) 14.dp else 22.dp, vertical = 8.dp),
                    modifier = Modifier.height(40.dp),
                ) {
                    if (isResolving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                        Spacer(Modifier.width(6.dp))
                    } else {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = stringResource(R.string.home_watch_now),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }

                OutlinedButton(
                    onClick = onDetails,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xFF1E212D).copy(alpha = 0.85f),
                        contentColor = Color.White,
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                    modifier = Modifier.height(40.dp),
                ) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.home_detail),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/** Section Header with "Tümünü Gör" and chevron */
@Composable
private fun HomeSectionHeader(
    title: String,
    onSeeAll: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onSeeAll)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.home_see_all),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE50914),
            )
            Icon(
                Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                tint = Color(0xFFE50914),
                modifier = Modifier.size(10.dp),
            )
        }
    }
}

@Composable
private fun LoadingShelf() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp,
            color = Color(0xFFE50914),
        )
    }
}
