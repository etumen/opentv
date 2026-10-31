package app.opentv.ui.vod

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.opentv.core.ServiceLocator
import app.opentv.data.provider.ProviderCatalogRequest
import app.opentv.data.provider.ProviderCatalogSection
import app.opentv.data.provider.ProviderItem
import app.opentv.data.provider.ProviderMediaType
import app.opentv.data.provider.ProviderResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class UnifiedBrowseState(
    val categories: List<UnifiedGenre> = emptyList(),
    val categoryId: String? = null,
    val items: List<ProviderItem> = emptyList(),
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val batches: Int = 0,
    val errors: Map<String, String> = emptyMap(),
)

/** Separate independent per-provider cursors for Movies and Series, with a shared genre taxonomy. */
class UnifiedCatalogViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = ServiceLocator.get(app).providerRepository
    private val _movies = MutableStateFlow(UnifiedBrowseState())
    private val _series = MutableStateFlow(UnifiedBrowseState())
    internal val movies: StateFlow<UnifiedBrowseState> = _movies.asStateFlow()
    internal val series: StateFlow<UnifiedBrowseState> = _series.asStateFlow()

    private val sectionsByType = mutableMapOf<ProviderMediaType, Map<String, List<ProviderCatalogSection>>>()
    private val cursorsByType = mutableMapOf<ProviderMediaType, List<UnifiedSource>>()
    private val allSourcesByType = mutableMapOf<ProviderMediaType, MutableList<ProviderItem>>()
    private val seenIds = mutableMapOf<ProviderMediaType, MutableMap<String, MutableSet<String>>>()
    private val repeatedPages = mutableMapOf<ProviderMediaType, MutableMap<String, Int>>()
    private val jobs = mutableMapOf<ProviderMediaType, Job>()

    private fun state(type: ProviderMediaType): MutableStateFlow<UnifiedBrowseState> =
        if (type == ProviderMediaType.MOVIE) _movies else _series

    fun ensureLoaded(type: ProviderMediaType) {
        if (type in sectionsByType || jobs[type]?.isActive == true) return
        val flow = state(type)
        flow.value = flow.value.copy(loading = true)
        jobs[type] = viewModelScope.launch {
            try {
                val sources = repository.providers(type)
                    .filterNot { type == ProviderMediaType.SERIES && it.id == "dramadizilerim" }
                val sections = coroutineScope {
                    sources.map { provider ->
                        async {
                            val resolved = when (val result = repository.catalogSections(provider.id, type)) {
                                is ProviderResult.Success -> result.value.filter { it.mediaType == type }
                                is ProviderResult.Failure -> provider.catalogSections.filter { it.mediaType == type }
                            }
                            provider.id to resolved.ifEmpty {
                                provider.catalogSections.filter { it.mediaType == type }
                            }
                        }
                    }.awaitAll().toMap()
                }
                sectionsByType[type] = sections
                flow.value = flow.value.copy(categories = UnifiedCatalogRules.genres(sections))
                resetAndFetch(type, null)
            } finally {
                flow.value = flow.value.copy(loading = false)
            }
        }
    }

    fun selectCategory(type: ProviderMediaType, genreId: String?) {
        val sections = sectionsByType[type] ?: return
        val flow = state(type)
        if (genreId != null && UnifiedCatalogRules.genres(sections).none { it.id == genreId }) return
        if (flow.value.categoryId == genreId && flow.value.items.isNotEmpty()) return
        jobs[type]?.cancel()
        flow.value = flow.value.copy(categoryId = genreId, items = emptyList(), hasMore = false, loading = true, errors = emptyMap())
        jobs[type] = viewModelScope.launch {
            try {
                resetAndFetch(type, genreId)
            } finally {
                flow.value = flow.value.copy(loading = false)
            }
        }
    }

    fun loadMore(type: ProviderMediaType) {
        val flow = state(type)
        if (flow.value.loading || flow.value.loadingMore || !flow.value.hasMore) return
        flow.value = flow.value.copy(loadingMore = true)
        jobs[type] = viewModelScope.launch {
            try {
                fetchBatch(type)
            } finally {
                flow.value = flow.value.copy(loadingMore = false)
            }
        }
    }

    private suspend fun resetAndFetch(type: ProviderMediaType, genreId: String?) {
        val flow = state(type)
        allSourcesByType[type] = mutableListOf()
        seenIds[type] = mutableMapOf()
        repeatedPages[type] = mutableMapOf()
        cursorsByType[type] = UnifiedCatalogRules.sourcesForGenre(
            sectionsByType[type].orEmpty(), genreId,
        )
        flow.value = flow.value.copy(
            categoryId = genreId, items = emptyList(), hasMore = true, batches = 0, errors = emptyMap(),
        )
        fetchBatch(type)
    }

    private suspend fun fetchBatch(type: ProviderMediaType) {
        val flow = state(type)
        val cursors = cursorsByType[type].orEmpty()
        val pending = cursors.filter { it.nextPage != null }
        if (pending.isEmpty()) {
            flow.value = flow.value.copy(hasMore = false)
            return
        }
        val results = coroutineScope {
            pending.map { cursor ->
                async {
                    val requested = cursor.nextPage!!
                    val result = repository.catalog(
                        cursor.providerId,
                        ProviderCatalogRequest(type, cursor.sectionId, requested),
                    )
                    Triple(cursor, requested, result)
                }
            }.awaitAll()
        }
        val updated = cursors.toMutableList()
        val incoming = ArrayList<ProviderItem>()
        val errors = flow.value.errors.toMutableMap()
        for ((cursor, page, result) in results) {
            val next = when (result) {
                is ProviderResult.Success -> {
                    incoming.addAll(result.value.items)
                    val id = "${cursor.providerId}:${cursor.sectionId}"
                    val seen = seenIds.getOrPut(type) { mutableMapOf() }
                        .getOrPut(id) { mutableSetOf() }
                    val fresh = result.value.items.count { seen.add(it.id) }
                    val repeats = repeatedPages.getOrPut(type) { mutableMapOf() }
                    repeats[id] = if (fresh == 0) (repeats[id] ?: 0) + 1 else 0
                    val safe = result.value.nextPage
                        ?.takeIf { it > page && (repeats[id] ?: 0) < 2 }
                    Log.d("UnifiedCatalog", "type=$type provider=${cursor.providerId} section=${cursor.sectionId} page=$page fetched=${result.value.items.size} unique=$fresh next=$safe")
                    safe
                }
                is ProviderResult.Failure -> {
                    errors[cursor.providerId] = result.error.message
                    Log.w("UnifiedCatalog", "type=$type provider=${cursor.providerId} page=$page error=${result.error.code}")
                    null
                }
            }
            val position = updated.indexOfFirst {
                it.providerId == cursor.providerId && it.sectionId == cursor.sectionId
            }
            if (position >= 0) updated[position] = cursor.copy(nextPage = next)
        }
        cursorsByType[type] = updated
        val allSources = allSourcesByType.getOrPut(type) { mutableListOf() }
        allSources.addAll(incoming)
        val groups = UnifiedCatalogRules.groups(allSources)
        UnifiedCatalogAlternatives.remember(groups)
        val merged = groups.map { it.primary }
        Log.d("UnifiedCatalog", "type=$type category=${flow.value.categoryId ?: "home"} received=${incoming.size} added=${merged.size - flow.value.items.size} alternateGroups=${groups.count { it.sources.size > 1 }} sourcesMore=${updated.count { it.nextPage != null }}")
        flow.value = flow.value.copy(
            items = merged,
            hasMore = updated.any { it.nextPage != null },
            batches = flow.value.batches + 1,
            errors = errors,
        )
    }
}
