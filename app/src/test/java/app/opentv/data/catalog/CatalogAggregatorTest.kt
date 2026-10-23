package app.opentv.data.catalog

import app.opentv.data.provider.Provider
import app.opentv.data.provider.ProviderCatalogPage
import app.opentv.data.provider.ProviderCatalogRequest
import app.opentv.data.provider.ProviderDetails
import app.opentv.data.provider.ProviderEpisode
import app.opentv.data.provider.ProviderItem
import app.opentv.data.provider.ProviderMediaType
import app.opentv.data.provider.ProviderPlaybackTarget
import app.opentv.data.provider.ProviderRegistry
import app.opentv.data.provider.ProviderRepository
import app.opentv.data.provider.ProviderResult
import app.opentv.data.provider.ProviderSearchRequest
import app.opentv.data.provider.ProviderStream
import app.opentv.data.provider.ProviderSubtitle
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CatalogAggregatorTest {
    @Test
    fun search_mergesSameSeriesFromMultipleProviders() = runTest {
        val repository = ProviderRepository(
            ProviderRegistry(
                listOf(
                    FakeProvider(
                        id = "dizipal",
                        searchItems = listOf(
                            ProviderItem(
                                providerId = "",
                                id = "dp-reacher",
                                title = "Reacher",
                                mediaType = ProviderMediaType.SERIES,
                                year = 2022,
                                posterUrl = "https://images.test/reacher.jpg",
                            ),
                        ),
                    ),
                    FakeProvider(
                        id = "other",
                        searchItems = listOf(
                            ProviderItem(
                                providerId = "",
                                id = "other-reacher",
                                title = "Reacher (2022)",
                                mediaType = ProviderMediaType.SERIES,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val result = CatalogAggregator(repository).search(
            ProviderSearchRequest("Reacher", ProviderMediaType.SERIES),
        )

        assertThat(result.items).hasSize(1)
        val reacher = result.items.single()
        assertThat(reacher.title).contains("Reacher")
        assertThat(reacher.year).isEqualTo(2022)
        assertThat(reacher.sources.map { it.providerId }).containsExactly("dizipal", "other")
        assertThat(reacher.sources.map { it.itemId })
            .containsExactly("dp-reacher", "other-reacher")
    }

    @Test
    fun merge_keepsSameTitleWithDifferentYearsSeparate() {
        val items = listOf(
            ProviderItem("a", "dune-1984", "Dune", ProviderMediaType.MOVIE, year = 1984),
            ProviderItem("b", "dune-2021", "Dune", ProviderMediaType.MOVIE, year = 2021),
        )

        val result = ContentMatcher().merge(items)

        assertThat(result).hasSize(2)
        assertThat(result.map { it.year }).containsExactly(1984, 2021)
    }

    @Test
    fun merge_doesNotBridgeConflictingYearsThroughUnknownYear() {
        val items = listOf(
            ProviderItem("unknown", "reacher-x", "Reacher", ProviderMediaType.SERIES),
            ProviderItem("modern", "reacher-2022", "Reacher", ProviderMediaType.SERIES, year = 2022),
            ProviderItem("old", "reacher-1984", "Reacher", ProviderMediaType.SERIES, year = 1984),
        )

        val result = ContentMatcher().merge(items)

        assertThat(result).hasSize(2)
        assertThat(result.mapNotNull { it.year }).containsExactly(2022, 1984)
    }

    @Test
    fun merge_doesNotMixMovieAndSeriesWithSameTitle() {
        val items = listOf(
            ProviderItem("a", "same-movie", "Same", ProviderMediaType.MOVIE, year = 2022),
            ProviderItem("b", "same-series", "Same", ProviderMediaType.SERIES, year = 2022),
        )

        assertThat(ContentMatcher().merge(items)).hasSize(2)
    }

    @Test
    fun search_keepsHealthyResultsWhenProviderFails() = runTest {
        val repository = ProviderRepository(
            ProviderRegistry(
                listOf(
                    FakeProvider(
                        id = "healthy",
                        searchItems = listOf(
                            ProviderItem("", "1", "Reacher", ProviderMediaType.SERIES, year = 2022),
                        ),
                    ),
                    FakeProvider(id = "broken", throwOnSearch = true),
                ),
            ),
        )

        val result = CatalogAggregator(repository).search(
            ProviderSearchRequest("Reacher", ProviderMediaType.SERIES),
        )

        assertThat(result.items).hasSize(1)
        assertThat(result.items.single().sources.single().providerId).isEqualTo("healthy")
        assertThat(result.failures.keys).containsExactly("broken")
    }

    @Test
    fun matcher_normalizesTurkishCharactersAndPunctuation() {
        val items = listOf(
            ProviderItem("a", "1", "Çağrı: İstanbul", ProviderMediaType.MOVIE, year = 2024),
            ProviderItem("b", "2", "Cagri Istanbul (2024)", ProviderMediaType.MOVIE),
        )

        val result = ContentMatcher().merge(items)

        assertThat(result).hasSize(1)
        assertThat(result.single().sources).hasSize(2)
    }

    private class FakeProvider(
        override val id: String,
        private val searchItems: List<ProviderItem> = emptyList(),
        private val throwOnSearch: Boolean = false,
    ) : Provider {
        override val name: String = id
        override val supportedMediaTypes =
            setOf(ProviderMediaType.MOVIE, ProviderMediaType.SERIES)

        override suspend fun catalog(request: ProviderCatalogRequest) =
            ProviderResult.Success(ProviderCatalogPage("Test", searchItems))

        override suspend fun search(request: ProviderSearchRequest): ProviderResult<List<ProviderItem>> {
            if (throwOnSearch) error("boom")
            return ProviderResult.Success(searchItems)
        }

        override suspend fun load(itemId: String) =
            ProviderResult.Success(
                ProviderDetails(
                    ProviderItem(id, itemId, "Item", ProviderMediaType.MOVIE),
                ),
            )

        override suspend fun episodes(seriesId: String) =
            ProviderResult.Success(emptyList<ProviderEpisode>())

        override suspend fun streams(target: ProviderPlaybackTarget) =
            ProviderResult.Success(emptyList<ProviderStream>())

        override suspend fun subtitles(target: ProviderPlaybackTarget) =
            ProviderResult.Success(emptyList<ProviderSubtitle>())
    }
}
