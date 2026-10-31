/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.opentv.core.ServiceLocator
import app.opentv.data.catalog.CanonicalContent
import app.opentv.data.catalog.UnifiedCatalogPager
import app.opentv.data.catalog.CanonicalEpisode
import app.opentv.data.catalog.UnifiedSeriesDetails
import app.opentv.data.provider.Provider
import app.opentv.data.provider.ProviderCatalogRequest
import app.opentv.data.provider.ProviderCatalogSection
import app.opentv.data.provider.ProviderDetails
import app.opentv.data.provider.ProviderEpisode
import app.opentv.data.provider.ProviderError
import app.opentv.data.provider.ProviderItem
import app.opentv.data.provider.ProviderMediaType
import app.opentv.data.provider.ProviderPlaybackTarget
import app.opentv.data.provider.ProviderResult
import app.opentv.data.provider.ProviderSearchRequest
import app.opentv.data.provider.ProviderStream
import app.opentv.data.provider.ProviderSubtitle
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CloudSeriesProvider(
    val id: String,
    val name: String,
    val sections: List<ProviderCatalogSection>,
)

data class CloudSeriesShelf(
    val providerId: String,
    val providerName: String,
    val section: ProviderCatalogSection,
    val items: List<ProviderItem>,
    val nextPage: Int? = null,
    val loadingMore: Boolean = false,
)

data class CloudSeriesPlayback(
    val streams: List<ProviderStream>,
    val subtitles: List<ProviderSubtitle>,
)

class CloudSeriesViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)
    private val repository = graph.providerRepository
    private val unifiedCatalog = graph.unifiedCatalog
    private val unifiedSeries = graph.unifiedSeries

    private fun sections(provider: Provider): List<ProviderCatalogSection> =
        provider.catalogSections
            .filter { it.mediaType == ProviderMediaType.SERIES }
            .ifEmpty {
                listOf(
                    ProviderCatalogSection(
                        id = "default",
                        title = provider.name,
                        mediaType = ProviderMediaType.SERIES,
                    ),
                )
            }

    private val initialProviders = repository.providers(ProviderMediaType.SERIES)

    private val _providers = MutableStateFlow(
        initialProviders.map { CloudSeriesProvider(it.id, it.name, sections(it)) },
    )
    val providers: StateFlow<List<CloudSeriesProvider>> = _providers.asStateFlow()

    private val _selectedProviderId =
        MutableStateFlow(initialProviders.firstOrNull()?.id)
    val selectedProviderId: StateFlow<String?> = _selectedProviderId.asStateFlow()

    private val _selectedSectionId = MutableStateFlow<String?>(null)
    val selectedSectionId: StateFlow<String?> = _selectedSectionId.asStateFlow()

    private val _shelves = MutableStateFlow<List<CloudSeriesShelf>>(emptyList())
    val shelves: StateFlow<List<CloudSeriesShelf>> = _shelves.asStateFlow()

    private val _selectedSectionItems = MutableStateFlow<List<ProviderItem>>(emptyList())
    val selectedSectionItems: StateFlow<List<ProviderItem>> = _selectedSectionItems.asStateFlow()

    private val _selectedSectionNextPage = MutableStateFlow<Int?>(null)
    val selectedSectionNextPage: StateFlow<Int?> = _selectedSectionNextPage.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _loadingSelectedSection = MutableStateFlow(false)
    val loadingSelectedSection: StateFlow<Boolean> = _loadingSelectedSection.asStateFlow()

    private val _loadingMoreSelectedSection = MutableStateFlow(false)
    val loadingMoreSelectedSection: StateFlow<Boolean> = _loadingMoreSelectedSection.asStateFlow()

    private val _lastError = MutableStateFlow<ProviderError?>(null)
    val lastError: StateFlow<ProviderError?> = _lastError.asStateFlow()

    private val _searchResults = MutableStateFlow<List<CanonicalContent>>(emptyList())
    val searchResults: StateFlow<List<CanonicalContent>> = _searchResults.asStateFlow()

    private val _unifiedCatalogItems = MutableStateFlow<List<CanonicalContent>>(emptyList())
    val unifiedCatalogItems: StateFlow<List<CanonicalContent>> = _unifiedCatalogItems.asStateFlow()

    private val _unifiedCatalogHasMore = MutableStateFlow(false)
    val unifiedCatalogHasMore: StateFlow<Boolean> = _unifiedCatalogHasMore.asStateFlow()

    private val _loadingUnifiedCatalog = MutableStateFlow(false)
    val loadingUnifiedCatalog: StateFlow<Boolean> = _loadingUnifiedCatalog.asStateFlow()

    private val _loadingMoreUnifiedCatalog = MutableStateFlow(false)
    val loadingMoreUnifiedCatalog: StateFlow<Boolean> = _loadingMoreUnifiedCatalog.asStateFlow()

    private val _asianDramaItems = MutableStateFlow<List<CanonicalContent>>(emptyList())
    val asianDramaItems: StateFlow<List<CanonicalContent>> = _asianDramaItems.asStateFlow()

    private val _asianDramaHasMore = MutableStateFlow(false)
    val asianDramaHasMore: StateFlow<Boolean> = _asianDramaHasMore.asStateFlow()

    private val _loadingAsianDrama = MutableStateFlow(false)
    val loadingAsianDrama: StateFlow<Boolean> = _loadingAsianDrama.asStateFlow()

    private val _loadingMoreAsianDrama = MutableStateFlow(false)
    val loadingMoreAsianDrama: StateFlow<Boolean> = _loadingMoreAsianDrama.asStateFlow()

    private var loadedProviderId: String? = null
    private var searchJob: Job? = null
    private var unifiedPager: UnifiedCatalogPager? = null
    private var asianDramaPager: UnifiedCatalogPager? = null

    fun selectProvider(providerId: String) {
        if (_selectedProviderId.value == providerId && loadedProviderId == providerId) return
        if (_providers.value.none { it.id == providerId }) return

        _selectedProviderId.value = providerId
        _selectedSectionId.value = null
        _selectedSectionItems.value = emptyList()
        _selectedSectionNextPage.value = null
        _shelves.value = emptyList()
        loadedProviderId = null
        loadShelves(force = true)
    }

    fun selectSection(sectionId: String?) {
        val providerId = _selectedProviderId.value ?: return
        val providerSections = _providers.value
            .firstOrNull { it.id == providerId }
            ?.sections
            .orEmpty()
        if (sectionId != null && providerSections.none { it.id == sectionId }) return

        _selectedSectionId.value = sectionId
        _selectedSectionItems.value = emptyList()
        _selectedSectionNextPage.value = null

        if (sectionId == null) loadShelves() else loadSelectedSection(force = true)
    }

    fun loadShelves(force: Boolean = false) {
        val provider = selectedProvider() ?: return
        if (_loading.value || (!force && loadedProviderId == provider.id)) return

        viewModelScope.launch {
            _loading.value = true
            _lastError.value = null
            try {
                val allSections = refreshSections(provider)
                val homeSections = allSections
                    .filter { it.showOnHome }
                    .ifEmpty { allSections.take(1) }

                val loaded = coroutineScope {
                    homeSections.map { section ->
                        async {
                            when (
                                val result = repository.catalog(
                                    provider.id,
                                    ProviderCatalogRequest(
                                        mediaType = ProviderMediaType.SERIES,
                                        sectionId = section.id,
                                        page = 1,
                                    ),
                                )
                            ) {
                                is ProviderResult.Success ->
                                    CloudSeriesShelf(
                                        providerId = provider.id,
                                        providerName = provider.name,
                                        section = section,
                                        items = result.value.items,
                                        nextPage = result.value.nextPage,
                                    )
                                is ProviderResult.Failure -> {
                                    _lastError.value = result.error
                                    null
                                }
                            }
                        }
                    }.awaitAll().filterNotNull()
                }

                if (_selectedProviderId.value == provider.id) {
                    _shelves.value = loaded.filter { it.items.isNotEmpty() }
                    loadedProviderId = provider.id
                }
            } finally {
                _loading.value = false
            }
        }
    }

    fun loadMoreShelf(sectionId: String) {
        val providerId = _selectedProviderId.value ?: return
        val shelf = _shelves.value.firstOrNull { it.section.id == sectionId } ?: return
        val nextPage = shelf.nextPage ?: return
        if (shelf.loadingMore) return

        _shelves.value = _shelves.value.map {
            if (it.section.id == sectionId) it.copy(loadingMore = true) else it
        }

        viewModelScope.launch {
            when (
                val result = repository.catalog(
                    providerId,
                    ProviderCatalogRequest(
                        mediaType = ProviderMediaType.SERIES,
                        sectionId = sectionId,
                        page = nextPage,
                    ),
                )
            ) {
                is ProviderResult.Success -> {
                    if (_selectedProviderId.value != providerId) return@launch
                    _shelves.value = _shelves.value.map { current ->
                        if (current.section.id != sectionId) {
                            current
                        } else {
                            val merged = (current.items + result.value.items)
                                .distinctBy { item -> item.id }
                            val addedNewItems = merged.size > current.items.size
                            current.copy(
                                items = merged,
                                nextPage = if (addedNewItems) result.value.nextPage else null,
                                loadingMore = false,
                            )
                        }
                    }
                }
                is ProviderResult.Failure -> {
                    _lastError.value = result.error
                    markShelfNotLoading(sectionId)
                }
            }
        }
    }

    fun loadSelectedSection(force: Boolean = false) {
        val providerId = _selectedProviderId.value ?: return
        val sectionId = _selectedSectionId.value ?: return
        if (_loadingSelectedSection.value ||
            (!force && _selectedSectionItems.value.isNotEmpty())
        ) return

        viewModelScope.launch {
            _loadingSelectedSection.value = true
            _lastError.value = null
            try {
                when (
                    val result = repository.catalog(
                        providerId,
                        ProviderCatalogRequest(
                            mediaType = ProviderMediaType.SERIES,
                            sectionId = sectionId,
                            page = 1,
                        ),
                    )
                ) {
                    is ProviderResult.Success -> {
                        if (_selectedProviderId.value == providerId &&
                            _selectedSectionId.value == sectionId
                        ) {
                            _selectedSectionItems.value = result.value.items
                            _selectedSectionNextPage.value = result.value.nextPage
                        }
                    }
                    is ProviderResult.Failure -> _lastError.value = result.error
                }
            } finally {
                _loadingSelectedSection.value = false
            }
        }
    }

    fun loadMoreSelectedSection() {
        val providerId = _selectedProviderId.value ?: return
        val sectionId = _selectedSectionId.value ?: return
        val nextPage = _selectedSectionNextPage.value ?: return
        if (_loadingMoreSelectedSection.value || _loadingSelectedSection.value) return

        viewModelScope.launch {
            _loadingMoreSelectedSection.value = true
            try {
                when (
                    val result = repository.catalog(
                        providerId,
                        ProviderCatalogRequest(
                            mediaType = ProviderMediaType.SERIES,
                            sectionId = sectionId,
                            page = nextPage,
                        ),
                    )
                ) {
                    is ProviderResult.Success -> {
                        if (_selectedProviderId.value == providerId &&
                            _selectedSectionId.value == sectionId
                        ) {
                            val currentItems = _selectedSectionItems.value
                            val merged = (currentItems + result.value.items)
                                .distinctBy { it.id }
                            _selectedSectionItems.value = merged
                            _selectedSectionNextPage.value =
                                if (merged.size > currentItems.size) result.value.nextPage else null
                        }
                    }
                    is ProviderResult.Failure -> _lastError.value = result.error
                }
            } finally {
                _loadingMoreSelectedSection.value = false
            }
        }
    }

    fun loadUnifiedCatalog(force: Boolean = false) {
        if (_loadingUnifiedCatalog.value) return
        if (!force && _unifiedCatalogItems.value.isNotEmpty()) return

        viewModelScope.launch {
            _loadingUnifiedCatalog.value = true
            _lastError.value = null
            try {
                val pager = unifiedCatalog.pager(ProviderMediaType.SERIES)
                unifiedPager = pager
                val page = pager.loadInitial()
                _unifiedCatalogItems.value = page.items
                _unifiedCatalogHasMore.value = page.hasMore
                page.failures.values.firstOrNull()?.let { _lastError.value = it }
            } finally {
                _loadingUnifiedCatalog.value = false
            }
        }
    }

    fun loadMoreUnifiedCatalog() {
        val pager = unifiedPager ?: return loadUnifiedCatalog()
        if (!_unifiedCatalogHasMore.value ||
            _loadingUnifiedCatalog.value ||
            _loadingMoreUnifiedCatalog.value
        ) return

        viewModelScope.launch {
            _loadingMoreUnifiedCatalog.value = true
            try {
                val page = pager.loadMore()
                _unifiedCatalogItems.value = page.items
                _unifiedCatalogHasMore.value = page.hasMore
                page.failures.values.firstOrNull()?.let { _lastError.value = it }
            } finally {
                _loadingMoreUnifiedCatalog.value = false
            }
        }
    }

    fun loadAsianDrama(force: Boolean = false) {
        if (_loadingAsianDrama.value) return
        if (!force && _asianDramaItems.value.isNotEmpty()) return

        viewModelScope.launch {
            _loadingAsianDrama.value = true
            try {
                val pager = unifiedCatalog.pager(
                    mediaType = ProviderMediaType.SERIES,
                    sectionId = "asian",
                )
                asianDramaPager = pager
                val page = pager.loadInitial()
                _asianDramaItems.value = page.items
                _asianDramaHasMore.value = page.hasMore
                page.failures.values.firstOrNull()?.let { _lastError.value = it }
            } finally {
                _loadingAsianDrama.value = false
            }
        }
    }

    fun loadMoreAsianDrama() {
        val pager = asianDramaPager ?: return loadAsianDrama()
        if (!_asianDramaHasMore.value ||
            _loadingAsianDrama.value ||
            _loadingMoreAsianDrama.value
        ) return

        viewModelScope.launch {
            _loadingMoreAsianDrama.value = true
            try {
                val page = pager.loadMore()
                _asianDramaItems.value = page.items
                _asianDramaHasMore.value = page.hasMore
                page.failures.values.firstOrNull()?.let { _lastError.value = it }
            } finally {
                _loadingMoreAsianDrama.value = false
            }
        }
    }

    fun setSearchQuery(query: String) {
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            _searchResults.value = emptyList()
            return
        }

        searchJob = viewModelScope.launch {
            delay(250)
            val result = unifiedCatalog.search(
                ProviderSearchRequest(
                    query = trimmed,
                    mediaType = ProviderMediaType.SERIES,
                ),
            )
            _searchResults.value = result.items
            result.failures.values.firstOrNull()?.let { _lastError.value = it }
        }
    }

    fun providerName(providerId: String): String =
        repository.providers().firstOrNull { it.id == providerId }?.name ?: providerId

    suspend fun details(
        providerId: String,
        itemId: String,
    ): ProviderResult<ProviderDetails> =
        repository.load(providerId, itemId)

    suspend fun episodes(
        providerId: String,
        seriesId: String,
    ): ProviderResult<List<ProviderEpisode>> =
        repository.episodes(providerId, seriesId)

    suspend fun unifiedDetails(
        providerId: String,
        itemId: String,
    ): ProviderResult<UnifiedSeriesDetails> =
        unifiedSeries.resolve(providerId, itemId)

    suspend fun episodePlayback(
        episode: CanonicalEpisode,
    ): ProviderResult<CloudSeriesPlayback> =
        when (val result = unifiedSeries.playback(episode)) {
            is ProviderResult.Success -> ProviderResult.Success(
                CloudSeriesPlayback(
                    streams = result.value.streams,
                    subtitles = result.value.subtitles,
                ),
            )
            is ProviderResult.Failure -> result
        }

    suspend fun episodePlayback(
        providerId: String,
        seriesId: String,
        episodeId: String,
    ): ProviderResult<CloudSeriesPlayback> {
        val target = ProviderPlaybackTarget.Episode(providerId, seriesId, episodeId)
        val streams = repository.streams(target)
        if (streams is ProviderResult.Failure) return streams

        val subtitles = repository.subtitles(target)
        val subtitleValues = when (subtitles) {
            is ProviderResult.Success -> subtitles.value
            is ProviderResult.Failure -> emptyList()
        }

        return ProviderResult.Success(
            CloudSeriesPlayback(
                streams = (streams as ProviderResult.Success).value,
                subtitles = subtitleValues,
            ),
        )
    }

    private suspend fun refreshSections(provider: Provider): List<ProviderCatalogSection> {
        val fallback = sections(provider)
        val resolved = when (
            val result = repository.catalogSections(provider.id, ProviderMediaType.SERIES)
        ) {
            is ProviderResult.Success -> result.value
                .filter { it.mediaType == ProviderMediaType.SERIES }
                .ifEmpty { fallback }
            is ProviderResult.Failure -> {
                _lastError.value = result.error
                fallback
            }
        }
        _providers.value = _providers.value.map { current ->
            if (current.id == provider.id) current.copy(sections = resolved) else current
        }
        return resolved
    }

    private fun selectedProvider(): Provider? {
        val providerId = _selectedProviderId.value ?: return null
        return repository.providers(ProviderMediaType.SERIES)
            .firstOrNull { it.id == providerId }
    }

    private fun markShelfNotLoading(sectionId: String) {
        _shelves.value = _shelves.value.map {
            if (it.section.id == sectionId) it.copy(loadingMore = false) else it
        }
    }
}
