package app.opentv.ui.vod

import app.opentv.data.provider.ProviderCatalogSection
import app.opentv.data.provider.ProviderItem
import java.text.Normalizer
import java.util.Locale

/** A source keeps its own cursor; the merged screen never sends one global page number. */
internal data class UnifiedSource(
    val providerId: String,
    val sectionId: String,
    val nextPage: Int? = 1,
)

internal data class UnifiedGenre(val id: String, val title: String)

internal object UnifiedCatalogRules {
    private val turkish = Locale.forLanguageTag("tr-TR")
    private val genreAliases = mapOf(
        "aksiyon" to "Aksiyon", "action" to "Aksiyon",
        "komedi" to "Komedi", "comedy" to "Komedi",
        "korku" to "Korku", "horror" to "Korku",
        "bilim kurgu" to "Bilim Kurgu", "bilimkurgu" to "Bilim Kurgu",
        "science fiction" to "Bilim Kurgu", "sci fi" to "Bilim Kurgu",
        "dram" to "Dram", "drama" to "Dram",
        "gerilim" to "Gerilim", "thriller" to "Gerilim",
        "macera" to "Macera", "adventure" to "Macera",
        "fantastik" to "Fantastik", "fantasy" to "Fantastik",
        "romantik" to "Romantik", "romantik komedi" to "Romantik Komedi",
        "romance" to "Romantik",
        "animasyon" to "Animasyon", "animation" to "Animasyon",
        "belgesel" to "Belgesel", "documentary" to "Belgesel",
        "suç" to "Suç", "crime" to "Suç",
        "gizem" to "Gizem", "mystery" to "Gizem",
        "tarih" to "Tarih", "tarihi" to "Tarih",
        "aile" to "Aile", "family" to "Aile",
        "savaş" to "Savaş", "war" to "Savaş",
        "western" to "Western", "müzikal" to "Müzikal",
        "yerli" to "Yerli", "yabancı" to "Yabancı",
        "imdb 7" to "IMDb 7+",
    )

    private fun fold(input: String): String = Normalizer.normalize(
        input.lowercase(turkish).replace('ı', 'i'),
        Normalizer.Form.NFD,
    ).replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex(" +"), " ")

    /** Unknown sections are not guessed into a genre (for example Son Eklenen Bölümler). */
    fun genre(section: ProviderCatalogSection): UnifiedGenre? {
        val value = fold(section.title)
            .replace(Regex("\\b(filmleri|filmler|filmi|film|dizileri|diziler|dizi)\\b"), " ")
            .replace(Regex(" +"), " ")
            .trim()
        val label = genreAliases[value]
            ?: genreAliases.entries.firstOrNull { (name, _) ->
                value == name || value.startsWith("$name ")
            }?.value
            ?: return null
        return UnifiedGenre(fold(label), label)
    }

    fun sourcesForGenre(
        sections: Map<String, List<ProviderCatalogSection>>,
        genreId: String?,
    ): List<UnifiedSource> = sections.flatMap { (providerId, providerSections) ->
        val selected = if (genreId == null) {
            providerSections.firstOrNull { it.showOnHome } ?: providerSections.firstOrNull()
        } else {
            providerSections.firstOrNull { genre(it)?.id == genreId }
        }
        listOfNotNull(selected?.let { UnifiedSource(providerId, it.id) })
    }

    fun genres(sections: Map<String, List<ProviderCatalogSection>>): List<UnifiedGenre> =
        sections.values.flatten().mapNotNull(::genre).distinctBy { it.id }
            .sortedBy { it.title }

    /** Strict title+year or authoritative ID dedup; never fuse different known years. */
    fun key(item: ProviderItem): String {
        for (field in listOf("imdb", "tmdb")) {
            val value = item.externalIds[field]?.trim()?.lowercase(Locale.ROOT)
            if (!value.isNullOrEmpty()) return "$field:$value"
        }
        val embeddedYear = Regex("\\b(19|20)\\d{2}\\b").find(item.title)?.value
        val year = item.year?.toString() ?: embeddedYear.orEmpty()
        val title = fold(item.title.replace(Regex("\\b(19|20)\\d{2}\\b"), " "))
        return "title:$title|year:$year|type:${item.mediaType}"
    }

    fun groups(items: List<ProviderItem>): List<UnifiedCatalogGroup> {
        val grouped = LinkedHashMap<String, MutableList<ProviderItem>>()
        items.forEach { item ->
            val entries = grouped.getOrPut(key(item)) { mutableListOf() }
            if (entries.none { it.providerId == item.providerId && it.id == item.id }) {
                entries.add(item)
            }
        }
        return grouped.values.map { itemsForTitle ->
            UnifiedCatalogGroup(itemsForTitle.first(), itemsForTitle.toList())
        }
    }

    fun merge(existing: List<ProviderItem>, incoming: List<ProviderItem>): List<ProviderItem> =
        groups(existing + incoming).map { it.primary }
}
