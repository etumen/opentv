/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.data.provider.ProviderItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CloudProviderBrowseControls(
    providers: List<CloudMovieProvider>,
    selectedProviderId: String?,
    selectedSectionId: String?,
    onSelectProvider: (String) -> Unit,
    onSelectSection: (String?) -> Unit,
) {
    val provider = providers.firstOrNull { it.id == selectedProviderId }
        ?: providers.firstOrNull()
        ?: return
    val selectedSection = provider.sections.firstOrNull { it.id == selectedSectionId }
    var providerOpen by remember { mutableStateOf(false) }
    var categoryOpen by remember { mutableStateOf(false) }
    var providerFilter by remember { mutableStateOf("") }
    var categoryFilter by remember { mutableStateOf("") }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = true,
            onClick = { providerFilter = ""; providerOpen = true },
            label = {
                Text(
                    provider.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 220.dp),
                )
            },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
        )
        FilterChip(
            selected = selectedSection != null,
            onClick = { categoryFilter = ""; categoryOpen = true },
            label = {
                Text(
                    selectedSection?.title ?: stringResource(R.string.cloud_categories),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 190.dp),
                )
            },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
        )
    }

    if (providerOpen) {
        ModalBottomSheet(
            onDismissRequest = { providerOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            val shown = remember(providers, providerFilter) {
                if (providerFilter.isBlank()) providers
                else providers.filter { it.name.contains(providerFilter.trim(), ignoreCase = true) }
            }
            Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                OutlinedTextField(
                    value = providerFilter,
                    onValueChange = { providerFilter = it },
                    placeholder = { Text(stringResource(R.string.cloud_filter_sources)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(shown, key = { it.id }) { item ->
                        ListItem(
                            headlineContent = { Text(item.name) },
                            colors = ListItemDefaults.colors(
                                containerColor =
                                    if (item.id == provider.id) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                            modifier = Modifier.fillMaxWidth().clickable {
                                providerOpen = false
                                onSelectProvider(item.id)
                            },
                        )
                    }
                }
            }
        }
    }

    if (categoryOpen) {
        ModalBottomSheet(
            onDismissRequest = { categoryOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            val shown = remember(provider.sections, categoryFilter) {
                if (categoryFilter.isBlank()) provider.sections
                else provider.sections.filter {
                    it.title.contains(categoryFilter.trim(), ignoreCase = true)
                }
            }
            Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                OutlinedTextField(
                    value = categoryFilter,
                    onValueChange = { categoryFilter = it },
                    placeholder = { Text(stringResource(R.string.cloud_filter_categories)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    item(key = "home") {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.cloud_home)) },
                            colors = ListItemDefaults.colors(
                                containerColor =
                                    if (selectedSection == null) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                            modifier = Modifier.fillMaxWidth().clickable {
                                categoryOpen = false
                                onSelectSection(null)
                            },
                        )
                    }
                    items(shown, key = { it.id }) { section ->
                        ListItem(
                            headlineContent = {
                                Text(section.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            colors = ListItemDefaults.colors(
                                containerColor =
                                    if (section.id == selectedSection?.id) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                            modifier = Modifier.fillMaxWidth().clickable {
                                categoryOpen = false
                                onSelectSection(section.id)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun CloudMovieCategoryGrid(
    items: List<ProviderItem>,
    hasNextPage: Boolean,
    loading: Boolean,
    loadingMore: Boolean,
    onOpenMovie: (ProviderItem) -> Unit,
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
                stringResource(R.string.vod_no_movies),
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
        gridItems(items, key = { "${it.providerId}:${it.id}" }) { item ->
            PosterCard(
                title = item.title,
                posterUrl = item.posterUrl,
                subtitle = item.year?.toString(),
                rating = item.rating,
                onClick = { onOpenMovie(item) },
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
