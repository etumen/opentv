package app.opentv.ui.home

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.opentv.core.ServiceLocator
import app.opentv.data.catalog.CanonicalContent
import app.opentv.data.provider.ProviderCatalogRequest
import app.opentv.data.provider.ProviderMediaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DiscoveryShelf(
    val id: String,
    val title: String,
    val items: List<CanonicalContent>,
)

class DiscoveryHomeViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)
    private val catalog = graph.unifiedCatalog

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
        Collection("asian", "Asya / Drama", "asian"),
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
                val loaded = mutableListOf<DiscoveryShelf>()
                collections.forEach { collection ->
                    val shelf = try {
                        withContext(Dispatchers.Default) {
                            loadCollection(collection)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Discovery collection failed: ${collection.id}", e)
                        null
                    }
                    if (shelf != null && shelf.items.isNotEmpty()) {
                        loaded += shelf
                        _shelves.value = loaded.toList()
                    }
                }
                _loaded.value = true
            } finally {
                _loading.value = false
            }
        }
    }

    private suspend fun loadCollection(collection: Collection): DiscoveryShelf {
        val mediaTypes = listOf(
            ProviderMediaType.MOVIE,
            ProviderMediaType.SERIES,
        )

        val canonicalItems = buildList {
            mediaTypes.forEach { mediaType ->
                addAll(
                    catalog.catalog(
                        ProviderCatalogRequest(
                            mediaType = mediaType,
                            sectionId = collection.sectionId,
                            page = 1,
                        ),
                    ).items,
                )
            }
        }.distinctBy { it.id }

        return DiscoveryShelf(
            id = collection.id,
            title = collection.title,
            items = canonicalItems.take(HOME_SHELF_LIMIT),
        )
    }

    companion object {
        private const val TAG = "DiscoveryHome"
        private const val HOME_SHELF_LIMIT = 24
    }
}
