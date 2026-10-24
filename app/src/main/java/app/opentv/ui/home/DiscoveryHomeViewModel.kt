package app.opentv.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.opentv.core.ServiceLocator
import app.opentv.data.catalog.CanonicalContent
import app.opentv.data.provider.ProviderCatalogRequest
import app.opentv.data.provider.ProviderMediaType
import app.opentv.data.provider.ProviderResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DiscoveryShelf(
    val id: String,
    val title: String,
    val items: List<CanonicalContent>,
)

class DiscoveryHomeViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)
    private val catalog = graph.unifiedCatalog
    private val repository = graph.providerRepository

    private data class Collection(
        val id: String,
        val title: String,
        val sectionId: String?,
    )

    private val collections = listOf(
        Collection("new", "Yeni Eklenenler", null),
        Collection("netflix", "Netflix", "netflix"),
        Collection("prime", "Prime Video", "prime"),
        Collection("disney", "Disney+", "disney"),
        Collection("exxen", "Exxen", "exxen"),
    )

    private val _shelves = MutableStateFlow<List<DiscoveryShelf>>(emptyList())
    val shelves: StateFlow<List<DiscoveryShelf>> = _shelves.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _loaded = MutableStateFlow(false)

    fun load(force: Boolean = false) {
        if (_loading.value || (!force && _loaded.value)) return
        viewModelScope.launch {
            _loading.value = true
            try {
                val loaded = coroutineScope {
                    collections.map { collection ->
                        async { loadCollection(collection) }
                    }.awaitAll()
                }
                _shelves.value = loaded.filter { it.items.isNotEmpty() }
                _loaded.value = true
            } finally {
                _loading.value = false
            }
        }
    }

    private suspend fun loadCollection(collection: Collection): DiscoveryShelf = coroutineScope {
        val mediaTypes = listOf(
            ProviderMediaType.MOVIE,
            ProviderMediaType.SERIES,
        )

        val canonicalItems: List<CanonicalContent> = if (collection.sectionId == null) {
            mediaTypes.map { mediaType ->
                async {
                    catalog.catalog(
                        ProviderCatalogRequest(
                            mediaType = mediaType,
                            sectionId = null,
                            page = 1,
                        ),
                    ).items
                }
            }.awaitAll().flatten()
                .distinctBy { it.id }
        } else {
            val providerItems = mediaTypes.flatMap { mediaType ->
                repository.providers(mediaType).map { provider ->
                    async {
                        val supportsHomeSection = provider.catalogSections.any { section ->
                            section.mediaType == mediaType &&
                                section.id == collection.sectionId &&
                                section.showOnHome
                        }
                        if (!supportsHomeSection) {
                            emptyList()
                        } else {
                            when (
                                val result = repository.catalog(
                                    provider.id,
                                    ProviderCatalogRequest(
                                        mediaType = mediaType,
                                        sectionId = collection.sectionId,
                                        page = 1,
                                    ),
                                )
                            ) {
                                is ProviderResult.Success -> result.value.items
                                is ProviderResult.Failure -> emptyList()
                            }
                        }
                    }
                }
            }.awaitAll().flatten()

            catalog.merge(providerItems)
        }

        DiscoveryShelf(
            id = collection.id,
            title = collection.title,
            items = canonicalItems.take(HOME_SHELF_LIMIT),
        )
    }

    companion object {
        private const val HOME_SHELF_LIMIT = 24
    }
}
