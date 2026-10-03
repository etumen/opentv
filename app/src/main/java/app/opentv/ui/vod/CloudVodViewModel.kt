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
import app.opentv.data.provider.Provider
import app.opentv.data.provider.ProviderCatalogRequest
import app.opentv.data.provider.ProviderCatalogSection
import app.opentv.data.provider.ProviderDetails
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

data class CloudMovieProvider(
    val id: String,
    val name: String,
    val sections: List<ProviderCatalogSection>,
)

data class CloudMovieShelf(
    val providerId: String,
    val providerName: String,
    val section: ProviderCatalogSection,
    val items: List<ProviderItem>,
    val nextPage: Int? = null,
    val loadingMore: Boolean = false,
)

data class CloudPlayback(
    val streams: List<ProviderStream>,
    val subtitles: List<ProviderSubtitle>,
)

class CloudVodViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)
    private val repository = graph.providerRepository

    private fun sections(provider: Provider): List<ProviderCatalogSection> =
        provider.catalogSections
            .filter { it.mediaType == ProviderMediaType.MOVIE }
            .ifEmpty {
                listOf(
                    ProviderCatalogSection(
                        id = "default",
                        title = provider.name,
                        mediaType = ProviderMediaType.MOVIE,
                    ),
                )
            }

    private suspend fun refreshSections(provider: Provider): List<ProviderCatalogSection> {
        val resolved = when (
            val result = repository.catalogSections(provider.id, ProviderMediaType.MOVIE)
        ) {
            is ProviderResult.Success -> result.value
                .filter { it.mediaType == ProviderMediaType.MOVIE }
                .ifEmpty { sections(provider) }
            is ProviderResult.Failure -> {
                _lastError.value = result.error
                sections(provider)
            }
        }

        _movieProviders.value = _movieProviders.value.map { current ->
            if (current.id == provider.id) current.copy(sections = resolved) else current
        }
        return resolved
    }

    private val initialProviders = repository.providers(ProviderMediaType.MOVIE)

    private val _movieProviders = MutableStateFlow(
        initialProviders.map { CloudMovieProvider(it.id, it.name, sections(it)) },
    )
    val movieProviders: StateFlow<List<CloudMovieProvider>> = _movieProviders.asStateFlow()

    private val _selectedMovieProviderId =
        MutableStateFlow(initialProviders.firstOrNull()?.id)
    val selectedMovieProviderId: StateFlow<String?> = _selectedMovieProviderId.asStateFlow()

    private val _selectedMovieSectionId = MutableStateFlow<String?>(null)
    val selectedMovieSectionId: StateFlow<String?> = _selectedMovieSectionId.asStateFlow()

    private val _movieShelves = MutableStateFlow<List<CloudMovieShelf>>(emptyList())
    val movieShelves: StateFlow<List<CloudMovieShelf>> = _movieShelves.asStateFlow()

    private val _selectedSectionItems = MutableStateFlow<List<ProviderItem>>(emptyList())
    val selectedSectionItems: StateFlow<List<ProviderItem>> = _selectedSectionItems.asStateFlow()

    private val _selectedSectionNextPage = MutableStateFlow<Int?>(null)
    val selectedSectionNextPage: StateFlow<Int?> = _selectedSectionNextPage.asStateFlow()

    private val _loadingMovies = MutableStateFlow(false)
    val loadingMovies: StateFlow<Boolean> = _loadingMovies.asStateFlow()

    private val _loadingSelectedSection = MutableStateFlow(false)
    val loadingSelectedSection: StateFlow<Boolean> = _loadingSelectedSection.asStateFlow()

    private val _loadingMoreSelectedSection = MutableStateFlow(false)
    val loadingMoreSelectedSection: StateFlow<Boolean> = _loadingMoreSelectedSection.asStateFlow()

    private val _lastError = MutableStateFlow<ProviderError?>(null)
    val lastError: StateFlow<ProviderError?> = _lastError.asStateFlow()

    private val _movieSearchResults = MutableStateFlow<List<ProviderItem>>(emptyList())
    val movieSearchResults: StateFlow<List<ProviderItem>> = _movieSearchResults.asStateFlow()

    private var loadedProviderId: String? = null
    private var searchJob: Job? = null

    fun selectMovieProvider(providerId: String) {
        if (_selectedMovieProviderId.value == providerId && loadedProviderId == providerId) return
        if (_movieProviders.value.none { it.id == providerId }) return

        _selectedMovieProviderId.value = providerId
        _selectedMovieSectionId.value = null
        _selectedSectionItems.value = emptyList()
        _selectedSectionNextPage.value = null
        _movieShelves.value = emptyList()
        loadedProviderId = null
        loadMovieShelves(force = true)
    }

    fun selectMovieSection(sectionId: String?) {
        val providerId = _selectedMovieProviderId.value ?: return
        val providerSections = _movieProviders.value
            .firstOrNull { it.id == providerId }
            ?.sections
            .orEmpty()
        if (sectionId != null && providerSections.none { it.id == sectionId }) return

        _selectedMovieSectionId.value = sectionId
        _selectedSectionItems.value = emptyList()
        _selectedSectionNextPage.value = null

        if (sectionId == null) {
            loadMovieShelves()
        } else {
            loadSelectedMovieSection(force = true)
        }
    }

    fun loadMovieShelves(force: Boolean = false) {
        val provider = selectedProvider() ?: return
        if (_loadingMovies.value || (!force && loadedProviderId == provider.id)) return

        viewModelScope.launch {
            _loadingMovies.value = true
            _lastError.value = null
            try {
                val allSections = refreshSections(provider)
                val providerSections = allSections
                    .filter { it.showOnHome }
                    .ifEmpty { allSections.take(1) }
                val shelves = coroutineScope {
                    providerSections.map { section ->
                        async {
                            when (
                                val result = repository.catalog(
                                    provider.id,
                                    ProviderCatalogRequest(
                                        mediaType = ProviderMediaType.MOVIE,
                                        sectionId = section.id,
                                        page = 1,
                                    ),
                                )
                            ) {
                                is ProviderResult.Success ->
                                    CloudMovieShelf(
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

                if (_selectedMovieProviderId.value == provider.id) {
                    _movieShelves.value = shelves.filter { it.items.isNotEmpty() }
                    loadedProviderId = provider.id
                }
            } finally {
                _loadingMovies.value = false
            }
        }
    }

    fun loadMoreMovieShelf(sectionId: String) {
        val providerId = _selectedMovieProviderId.value ?: return
        val shelf = _movieShelves.value.firstOrNull { it.section.id == sectionId } ?: return
        val nextPage = shelf.nextPage ?: return
        if (shelf.loadingMore) return

        _movieShelves.value = _movieShelves.value.map {
            if (it.section.id == sectionId) it.copy(loadingMore = true) else it
        }

        viewModelScope.launch {
            try {
                when (
                    val result = repository.catalog(
                        providerId,
                        ProviderCatalogRequest(
                            mediaType = ProviderMediaType.MOVIE,
                            sectionId = sectionId,
                            page = nextPage,
                        ),
                    )
                ) {
                    is ProviderResult.Success -> {
                        if (_selectedMovieProviderId.value != providerId) return@launch
                        _movieShelves.value = _movieShelves.value.map { current ->
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
            } catch (t: Throwable) {
                markShelfNotLoading(sectionId)
                throw t
            }
        }
    }

    fun loadSelectedMovieSection(force: Boolean = false) {
        val providerId = _selectedMovieProviderId.value ?: return
        val sectionId = _selectedMovieSectionId.value ?: return
        if (_loadingSelectedSection.value || (!force && _selectedSectionItems.value.isNotEmpty())) return

        viewModelScope.launch {
            _loadingSelectedSection.value = true
            _lastError.value = null
            try {
                when (
                    val result = repository.catalog(
                        providerId,
                        ProviderCatalogRequest(
                            mediaType = ProviderMediaType.MOVIE,
                            sectionId = sectionId,
                            page = 1,
                        ),
                    )
                ) {
                    is ProviderResult.Success -> {
                        if (_selectedMovieProviderId.value == providerId &&
                            _selectedMovieSectionId.value == sectionId
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

    fun loadMoreSelectedMovieSection() {
        val providerId = _selectedMovieProviderId.value ?: return
        val sectionId = _selectedMovieSectionId.value ?: return
        val nextPage = _selectedSectionNextPage.value ?: return
        if (_loadingMoreSelectedSection.value || _loadingSelectedSection.value) return

        viewModelScope.launch {
            _loadingMoreSelectedSection.value = true
            try {
                when (
                    val result = repository.catalog(
                        providerId,
                        ProviderCatalogRequest(
                            mediaType = ProviderMediaType.MOVIE,
                            sectionId = sectionId,
                            page = nextPage,
                        ),
                    )
                ) {
                    is ProviderResult.Success -> {
                        if (_selectedMovieProviderId.value == providerId &&
                            _selectedMovieSectionId.value == sectionId
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

    private fun markShelfNotLoading(sectionId: String) {
        _movieShelves.value = _movieShelves.value.map {
            if (it.section.id == sectionId) it.copy(loadingMore = false) else it
        }
    }

    private fun selectedProvider(): Provider? {
        val id = _selectedMovieProviderId.value ?: return null
        return repository.providers(ProviderMediaType.MOVIE).firstOrNull { it.id == id }
    }

    fun setMovieSearchQuery(query: String) {
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            _movieSearchResults.value = emptyList()
            return
        }

        searchJob = viewModelScope.launch {
            delay(250)
            val result = repository.searchAll(
                ProviderSearchRequest(
                    query = trimmed,
                    mediaType = ProviderMediaType.MOVIE,
                ),
            )
            _movieSearchResults.value = result.values.values
                .flatten()
                .distinctBy { "${it.providerId}:${it.id}" }
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

    suspend fun playback(
        providerId: String,
        itemId: String,
    ): ProviderResult<CloudPlayback> {
        val target = ProviderPlaybackTarget.Movie(providerId, itemId)
        val streams = repository.streams(target)
        if (streams is ProviderResult.Failure) return streams

        val subtitles = repository.subtitles(target)
        val subtitleValues = when (subtitles) {
            is ProviderResult.Success -> subtitles.value
            is ProviderResult.Failure -> emptyList()
        }

        return ProviderResult.Success(
            CloudPlayback(
                streams = (streams as ProviderResult.Success).value,
                subtitles = subtitleValues,
            ),
        )
    }
}
