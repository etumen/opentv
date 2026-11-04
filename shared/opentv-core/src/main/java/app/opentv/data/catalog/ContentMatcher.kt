package app.opentv.data.catalog

import app.opentv.data.provider.ProviderItem
import app.opentv.data.provider.ProviderMediaType
import java.text.Normalizer
import java.util.Locale

enum class ContentMatchConfidence {
    EXTERNAL_ID,
    TITLE_AND_YEAR,
    EXACT_TITLE,
    NONE,
}

data class ContentMatch(
    val confidence: ContentMatchConfidence,
    val normalizedTitle: String,
    val year: Int?,
) {
    val isMatch: Boolean
        get() = confidence != ContentMatchConfidence.NONE
}

/**
 * Conservative provider-independent title matcher.
 *
 * V1 deliberately avoids fuzzy string similarity. Exact normalized titles may merge when their
 * years do not conflict; the same title with two known different years never merges.
 */
class ContentMatcher {
    fun match(
        leftTitle: String,
        leftType: ProviderMediaType,
        leftYear: Int?,
        rightTitle: String,
        rightType: ProviderMediaType,
        rightYear: Int?,
        leftExternalIds: Map<String, String> = emptyMap(),
        rightExternalIds: Map<String, String> = emptyMap(),
    ): ContentMatch {
        if (leftType != rightType) return ContentMatch(ContentMatchConfidence.NONE, "", null)

        val left = identity(leftTitle, leftYear)
        val right = identity(rightTitle, rightYear)
        val leftIds = normalizeExternalIds(leftExternalIds)
        val rightIds = normalizeExternalIds(rightExternalIds)
        val sharedIdKeys = leftIds.keys.intersect(rightIds.keys)
        if (sharedIdKeys.any { key -> leftIds[key] != rightIds[key] }) {
            return ContentMatch(ContentMatchConfidence.NONE, left.title, left.year)
        }
        if (sharedIdKeys.isNotEmpty()) {
            return ContentMatch(
                ContentMatchConfidence.EXTERNAL_ID,
                left.title.ifBlank { right.title },
                left.year ?: right.year,
            )
        }

        if (left.title.isBlank() || left.title != right.title) {
            return ContentMatch(ContentMatchConfidence.NONE, left.title, left.year)
        }

        if (left.year != null && right.year != null && left.year != right.year) {
            return ContentMatch(ContentMatchConfidence.NONE, left.title, left.year)
        }

        val year = left.year ?: right.year
        val confidence =
            if (left.year != null && right.year != null) {
                ContentMatchConfidence.TITLE_AND_YEAR
            } else {
                ContentMatchConfidence.EXACT_TITLE
            }
        return ContentMatch(confidence, left.title, year)
    }

    fun merge(items: Iterable<ProviderItem>): List<CanonicalContent> {
        val preparedItems = items.map { item ->
            val id = identity(item.title, item.year)
            val extIds = normalizeExternalIds(item.externalIds)
            PreparedItem(item, id, extIds)
        }

        val groups = mutableListOf<PreparedGroup>()
        val idIndex = mutableMapOf<String, MutableList<PreparedGroup>>()
        val titleIndex = mutableMapOf<String, MutableList<PreparedGroup>>()

        for (prepared in preparedItems) {
            val candidates = mutableSetOf<PreparedGroup>()
            for ((key, value) in prepared.normalizedExternalIds) {
                val indexKey = "ext:${prepared.item.mediaType}:$key:$value"
                idIndex[indexKey]?.let { candidates.addAll(it) }
            }
            if (prepared.identity.title.isNotBlank()) {
                val indexKey = "title:${prepared.item.mediaType}:${prepared.identity.title}"
                titleIndex[indexKey]?.let { candidates.addAll(it) }
            }

            val target = if (candidates.isEmpty()) {
                null
            } else {
                candidates
                    .sortedBy { it.index }
                    .firstOrNull { group ->
                        group.items.all { existing -> matches(existing, prepared) }
                    }
            }

            if (target == null) {
                val newGroup = PreparedGroup(index = groups.size, items = mutableListOf(prepared))
                groups.add(newGroup)
                indexGroupItem(newGroup, prepared, idIndex, titleIndex)
            } else {
                target.items.add(prepared)
                indexGroupItem(target, prepared, idIndex, titleIndex)
            }
        }

        return groups.map { canonicalize(it.items) }
    }

    private fun indexGroupItem(
        group: PreparedGroup,
        prepared: PreparedItem,
        idIndex: MutableMap<String, MutableList<PreparedGroup>>,
        titleIndex: MutableMap<String, MutableList<PreparedGroup>>,
    ) {
        for ((key, value) in prepared.normalizedExternalIds) {
            val indexKey = "ext:${prepared.item.mediaType}:$key:$value"
            val list = idIndex.getOrPut(indexKey) { mutableListOf() }
            if (list.none { it.index == group.index }) list.add(group)
        }
        if (prepared.identity.title.isNotBlank()) {
            val indexKey = "title:${prepared.item.mediaType}:${prepared.identity.title}"
            val list = titleIndex.getOrPut(indexKey) { mutableListOf() }
            if (list.none { it.index == group.index }) list.add(group)
        }
    }

    private fun matches(left: PreparedItem, right: PreparedItem): Boolean {
        if (left.item.mediaType != right.item.mediaType) return false

        val leftIds = left.normalizedExternalIds
        val rightIds = right.normalizedExternalIds
        var hasSharedKey = false
        if (leftIds.isNotEmpty() && rightIds.isNotEmpty()) {
            for ((key, leftVal) in leftIds) {
                val rightVal = rightIds[key] ?: continue
                if (leftVal != rightVal) return false
                hasSharedKey = true
            }
        }
        if (hasSharedKey) return true

        if (left.identity.title.isBlank() || left.identity.title != right.identity.title) {
            return false
        }

        if (left.identity.year != null && right.identity.year != null && left.identity.year != right.identity.year) {
            return false
        }

        return true
    }

    private fun canonicalize(group: List<PreparedItem>): CanonicalContent {
        require(group.isNotEmpty())
        val first = group.first()
        val year = group.firstNotNullOfOrNull { it.identity.year }
        val normalizedTitle = first.identity.title
        val best = group.maxByOrNull { metadataScore(it.item) }?.item ?: first.item
        val externalIds = mergeExternalIds(group)

        return CanonicalContent(
            id = canonicalIdNormalized(first.item.mediaType, normalizedTitle, year, externalIds),
            title = best.title.trim(),
            mediaType = first.item.mediaType,
            posterUrl = group.firstNotNullOfOrNull { it.item.posterUrl?.takeIf(String::isNotBlank) },
            backdropUrl = group.firstNotNullOfOrNull { it.item.backdropUrl?.takeIf(String::isNotBlank) },
            description = group
                .mapNotNull { it.item.description?.takeIf(String::isNotBlank) }
                .maxByOrNull(String::length),
            year = year,
            rating = group.mapNotNull { it.item.rating }.maxOrNull(),
            genres = group.flatMap { it.item.genres }.distinct(),
            externalIds = externalIds,
            sources = group
                .map { it.item }
                .distinctBy { it.providerId to it.id }
                .map(ProviderItem::toContentSource),
        )
    }

    private fun metadataScore(item: ProviderItem): Int =
        listOf(
            item.posterUrl,
            item.backdropUrl,
            item.description,
        ).count { !it.isNullOrBlank() } +
            if (item.year != null) 1 else 0 +
            if (item.rating != null) 1 else 0 +
            if (item.genres.isNotEmpty()) 1 else 0 +
            if (item.externalIds.isNotEmpty()) 2 else 0

    fun canonicalId(
        mediaType: ProviderMediaType,
        title: String,
        year: Int?,
        externalIds: Map<String, String> = emptyMap(),
    ): String = canonicalIdNormalized(
        mediaType = mediaType,
        normalizedTitle = identity(title, year).title,
        year = year,
        externalIds = normalizeExternalIds(externalIds),
    )

    private fun canonicalIdNormalized(
        mediaType: ProviderMediaType,
        normalizedTitle: String,
        year: Int?,
        externalIds: Map<String, String>,
    ): String {
        val stableId = listOf("imdb", "tmdb")
            .firstNotNullOfOrNull { key -> externalIds[key]?.let { key to it } }
        if (stableId != null) {
            return mediaType.name.lowercase(Locale.ROOT) + ":" +
                stableId.first + ":" + stableId.second
        }

        val slug = normalizedTitle
            .replace(NON_ALPHANUM_REGEX, "-")
            .trim('-')
            .ifBlank { "untitled" }
        return mediaType.name.lowercase(Locale.ROOT) + ":" + slug + ":" + (year ?: "unknown")
    }

    private fun normalizeExternalIds(values: Map<String, String>): Map<String, String> =
        values.mapNotNull { (rawKey, rawValue) ->
            val key = rawKey.trim().lowercase(Locale.ROOT)
            val value = rawValue.trim().lowercase(Locale.ROOT)
            if (key.isBlank() || value.isBlank()) null else key to value
        }.toMap()

    private fun mergeExternalIds(group: List<PreparedItem>): Map<String, String> {
        val merged = linkedMapOf<String, String>()
        val conflicts = mutableSetOf<String>()
        group.forEach { item ->
            item.normalizedExternalIds.forEach { (key, value) ->
                val existing = merged[key]
                when {
                    existing == null -> merged[key] = value
                    existing != value -> conflicts += key
                }
            }
        }
        conflicts.forEach(merged::remove)
        return merged
    }

    private fun identity(title: String, explicitYear: Int?): Identity {
        val trimmed = title.trim()
        val trailing = TRAILING_YEAR.find(trimmed)
        val inferredYear = trailing?.groupValues?.getOrNull(1)?.toIntOrNull()
        val withoutYear =
            if (trailing != null) trimmed.removeRange(trailing.range).trim() else trimmed

        val normalized = Normalizer.normalize(withoutYear, Normalizer.Form.NFD)
            .replace(DIACRITICS_REGEX, "")
            .lowercase(Locale.ROOT)
            .replace('ı', 'i')
            .replace(NON_ALPHANUM_REGEX, " ")
            .trim()
            .replace(WHITESPACE_REGEX, " ")

        return Identity(
            title = normalized,
            year = explicitYear ?: inferredYear,
        )
    }

    private class PreparedItem(
        val item: ProviderItem,
        val identity: Identity,
        val normalizedExternalIds: Map<String, String>,
    )

    private class PreparedGroup(
        val index: Int,
        val items: MutableList<PreparedItem>,
    )

    private data class Identity(
        val title: String,
        val year: Int?,
    )

    private companion object {
        val TRAILING_YEAR = Regex("""(?:\(|\[)?(19\d{2}|20\d{2})(?:\)|\])?\s*$""")
        val DIACRITICS_REGEX = Regex("""\p{M}+""")
        val NON_ALPHANUM_REGEX = Regex("""[^a-z0-9]+""")
        val WHITESPACE_REGEX = Regex("""\s+""")
    }
}
