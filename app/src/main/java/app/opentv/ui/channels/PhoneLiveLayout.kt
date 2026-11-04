package app.opentv.ui.channels

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.core.AppSettings
import app.opentv.data.model.Source
import app.opentv.ui.ChannelsViewModel

/** Mobile-only Live list; TV retains the existing guide and remote navigation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneLiveLayout(
    rows: List<ChannelsViewModel.Row>,
    sources: List<Source>,
    selectedSource: Long?,
    categories: List<ChannelsViewModel.CategoryGroup>,
    selectedCategory: String?,
    favouritesOnly: Boolean,
    recentsOnly: Boolean,
    sort: AppSettings.ChannelSort,
    onSort: (AppSettings.ChannelSort) -> Unit,
    onSelectSource: (Long?) -> Unit,
    onSelectCategory: (String?) -> Unit,
    onSelectFavourites: () -> Unit,
    onSelectRecents: () -> Unit,
    onQuery: (String) -> Unit,
    onPlay: (ChannelsViewModel.Row) -> Unit,
    onLongPress: (ChannelsViewModel.Row) -> Unit,
    onToggleFavourite: (ChannelsViewModel.Row) -> Unit,
    emptyContent: @Composable () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var showCategories by remember { mutableStateOf(false) }
    val categoryLabel = categories.firstOrNull { it.key == selectedCategory }?.label

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; onQuery(it) },
            placeholder = {
                Text(stringResource(R.string.phone_search_all), maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = ""; onQuery("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.phone_clear))
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = selectedCategory != null && !favouritesOnly && !recentsOnly,
                onClick = { showCategories = true },
                label = {
                    Text(
                        if (selectedCategory != null && !favouritesOnly && !recentsOnly && categoryLabel != null)
                            categoryLabel else stringResource(R.string.phone_live_categories),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            )
            FilterChip(
                selected = recentsOnly,
                onClick = onSelectRecents,
                label = { Text(stringResource(R.string.phone_live_recent)) },
                leadingIcon = { Icon(Icons.Filled.History, contentDescription = null) },
            )
            FilterChip(
                selected = favouritesOnly,
                onClick = onSelectFavourites,
                label = { Text(stringResource(R.string.guide_favourites)) },
            )
            FilterChip(
                selected = !recentsOnly && !favouritesOnly && selectedCategory == null,
                onClick = { onSelectCategory(null) },
                label = { Text(stringResource(R.string.guide_all_channels)) },
            )
            AssistChip(
                onClick = {
                    onSort(
                        when (sort) {
                            AppSettings.ChannelSort.DEFAULT -> AppSettings.ChannelSort.NAME
                            AppSettings.ChannelSort.NAME -> AppSettings.ChannelSort.NUMBER
                            AppSettings.ChannelSort.NUMBER -> AppSettings.ChannelSort.DEFAULT
                        },
                    )
                },
                label = {
                    Text(stringResource(
                        when (sort) {
                            AppSettings.ChannelSort.DEFAULT -> R.string.phone_sort_provider
                            AppSettings.ChannelSort.NAME -> R.string.phone_sort_name
                            AppSettings.ChannelSort.NUMBER -> R.string.phone_sort_number
                        }
                    ))
                },
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (rows.isEmpty()) emptyContent()
            else ChannelList(
                rows = rows, selectedKey = null,
                onSelectRow = onPlay,
                onFocusRow = {},
                onToggleFavourite = onToggleFavourite,
                onLongPressRow = onLongPress,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }

    if (showCategories) {
        ModalBottomSheet(
            onDismissRequest = { showCategories = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            var filter by remember { mutableStateOf("") }
            val filtered = remember(categories, filter) {
                if (filter.isBlank()) categories
                else categories.filter { it.label.contains(filter.trim(), ignoreCase = true) }
            }
            Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                if (sources.size > 1) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = selectedSource == null,
                            onClick = { onSelectSource(null) },
                            label = { Text(stringResource(R.string.channels_manager_all_sources)) },
                        )
                        sources.forEach { source ->
                            FilterChip(
                                selected = selectedSource == source.id,
                                onClick = { onSelectSource(source.id) },
                                label = { Text(source.name, maxLines = 1) },
                            )
                        }
                    }
                }
                Text(
                    stringResource(R.string.phone_live_categories),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = { Text(stringResource(R.string.phone_live_filter_categories)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                LazyColumn {
                    if (filter.isBlank()) {
                        item(key = "all") {
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.guide_all_channels)) },
                                modifier = Modifier.fillMaxWidth().clickable {
                                    onSelectCategory(null); showCategories = false
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                    items(filtered, key = { it.key }) { group ->
                        ListItem(
                            headlineContent = {
                                Text(group.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            modifier = Modifier.fillMaxWidth().clickable {
                                onSelectCategory(group.key); showCategories = false
                            },
                        )
                    }
                }
            }
        }
    }
}
