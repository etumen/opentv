package app.opentv.ui.vod

import app.opentv.data.provider.ProviderCatalogSection
import app.opentv.data.provider.ProviderItem
import app.opentv.data.provider.ProviderMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnifiedCatalogRulesTest {
    private fun section(id: String, title: String) =
        ProviderCatalogSection(id, title, ProviderMediaType.MOVIE)

    @Test fun genresUnifyTurkishAndEnglishAcrossProviders() {
        val sections = mapOf(
            "a" to listOf(section("action", "Aksiyon Filmleri")),
            "b" to listOf(section("action-2", "Action Movies"), section("science", "Sci-Fi")),
            "c" to listOf(section("science-2", "Bilim Kurgu")),
        )
        val genres = UnifiedCatalogRules.genres(sections)
        assertEquals(2, genres.size)
        val action = genres.first { it.title == "Aksiyon" }
        val actionSources = UnifiedCatalogRules.sourcesForGenre(sections, action.id)
        assertEquals(setOf("a", "b"), actionSources.map { it.providerId }.toSet())
        assertEquals(setOf("action", "action-2"), actionSources.map { it.sectionId }.toSet())
        assertEquals(setOf(1), actionSources.map { it.nextPage }.toSet())
    }

    @Test fun categoryDoesNotMakeUpUnsupportedProviderSections() {
        val sections = mapOf(
            "a" to listOf(section("popular", "En Çok İzlenenler"), section("horror", "Korku Filmleri")),
            "b" to listOf(section("recent", "Son Eklenenler")),
        )
        val genres = UnifiedCatalogRules.genres(sections)
        assertEquals(listOf("Korku"), genres.map { it.title })
        assertEquals(listOf("a"), UnifiedCatalogRules.sourcesForGenre(sections, genres.single().id).map { it.providerId })
        assertEquals(2, UnifiedCatalogRules.sourcesForGenre(sections, null).size)
        assertNull(UnifiedCatalogRules.genre(section("recent", "Son Eklenen Bölümler")))
    }

    @Test fun mergeDeduplicatesSameTitleAndYearButPreservesOtherYear() {
        val sourceA = ProviderItem("first", "/inception", "Inception", ProviderMediaType.MOVIE, year = 2010)
        val sourceB = ProviderItem("second", "/baslangic", "Inception (2010)", ProviderMediaType.MOVIE)
        val different = ProviderItem("third", "/remake", "Inception", ProviderMediaType.MOVIE, year = 2025)
        val merged = UnifiedCatalogRules.merge(listOf(sourceA), listOf(sourceB, different))
        assertEquals(2, merged.size)
        assertEquals("first", merged[0].providerId)
        assertEquals("third", merged[1].providerId)
    }

    @Test fun genreOtherTypeAndPaginationTokensNeverBecomeCrossProviderCursor() {
        val sections = mapOf(
            "a" to listOf(section("action", "Aksiyon")),
            "b" to listOf(section("a", "Aksiyon")),
        )
        val cursors = UnifiedCatalogRules.sourcesForGenre(sections, "aksiyon")
        val updated = cursors.map { if (it.providerId == "a") it.copy(nextPage = 7) else it.copy(nextPage = 2) }
        assertEquals(7, updated.single { it.providerId == "a" }.nextPage)
        assertEquals(2, updated.single { it.providerId == "b" }.nextPage)
    }

    @Test fun unifiedGroupsKeepEveryRealProviderItemAfterNextPage() {
        val first = ProviderItem("provider-a", "/same", "Alpha", ProviderMediaType.MOVIE, year = 2024)
        val second = ProviderItem("provider-b", "/other", "Beta", ProviderMediaType.MOVIE, year = 2024)
        val alternate = ProviderItem("provider-c", "/alpha-different-path", "Alpha", ProviderMediaType.MOVIE, year = 2024)
        val groups = UnifiedCatalogRules.groups(listOf(first, second, alternate, alternate))
        assertEquals(2, groups.size)
        assertEquals(listOf("provider-a", "provider-c"), groups.first().sources.map { it.providerId })
        UnifiedCatalogAlternatives.remember(groups)
        assertEquals(listOf("provider-c"), UnifiedCatalogAlternatives.alternatives(first.providerId, first.id).map { it.providerId })
        assertEquals(emptyList<ProviderItem>(), UnifiedCatalogAlternatives.alternatives("unknown", "nope"))
        assertEquals(listOf("provider-c"), UnifiedCatalogAlternatives.alternatives(first.providerId, first.id).filterNot { it.providerId == "provider-a" }.map { it.providerId })
        // Every item ID in the group is an entry point, including an alternate returned by search.
        assertEquals(listOf("provider-a"), UnifiedCatalogAlternatives.alternatives(alternate.providerId, alternate.id).map { it.providerId })
    }

    @Test fun fallbackContextNeverRetriesCurrentProvider() {
        val a = ProviderItem("a", "id-a", "Title", ProviderMediaType.MOVIE)
        val b = ProviderItem("b", "id-b", "Title", ProviderMediaType.MOVIE)
        val context = UnifiedFallbackContext(ProviderMediaType.MOVIE, listOf(a, b))
        assertEquals(listOf("b"), context.without("a").alternateItems.map { it.providerId })
    }

    @Test fun seriesFallbackMustMatchExactSeasonAndEpisode() {
        val eps = listOf(
            app.opentv.data.provider.ProviderEpisode("provider-b", "series-id", "ep-2", 1, 2, "2"),
            app.opentv.data.provider.ProviderEpisode("provider-b", "series-id", "ep-1", 2, 1, "1"),
        )
        assertEquals("ep-1", UnifiedPlaybackResolver.exactEpisode(eps, 2, 1)?.id)
        assertNull(UnifiedPlaybackResolver.exactEpisode(eps, 1, 1))
    }

}
