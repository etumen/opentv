/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.parser.ChannelNameNormalizer
import kotlin.math.abs

/**
 * Joins provider channels to guide channels when nobody gave us a join key.
 *
 * The provider says `UK| BBC ONE FHD`. The guide says `BBC One` (id `bbc1.uk`). Both sides
 * are normalised through [ChannelNameNormalizer].
 *
 * The design rule remains conservative: prefer no match to a wrong match. Exact-name collisions
 * are resolved only when programme availability and/or quality metadata leave one defensible
 * candidate. Otherwise the matcher returns null.
 */
object EpgMatcher {

    data class Alias(
        val epgId: String,
        val key: String,
        val qualityRank: Int,
        val populated: Boolean,
    )

    class Index internal constructor(
        private val exact: Map<String, List<Alias>>,
        private val sortedKeys: List<Alias>,
        private val hasPopulationInfo: Boolean,
    ) {
        fun match(groupKey: String, providerQualityRank: Int = 0): String? {
            if (groupKey.length < MIN_KEY_LENGTH) return null

            exact[groupKey]?.let { candidates ->
                resolveCandidates(candidates, providerQualityRank)?.let { return it }
            }

            // Prefix matching stays deliberately strict. First collapse duplicate aliases for the
            // same EPG id, then accept only when every surviving candidate agrees on one id.
            val hits = sortedKeys.filter { alias ->
                alias.key.length >= MIN_KEY_LENGTH &&
                    (alias.key.startsWith(groupKey) || groupKey.startsWith(alias.key))
            }
            if (hits.isEmpty()) return null

            val usable = preferPopulated(hits)
            val ids = usable.map { it.epgId }.distinct()
            return ids.singleOrNull()
        }

        private fun resolveCandidates(candidates: List<Alias>, providerQualityRank: Int): String? {
            val usable = preferPopulated(candidates)
            val byId = usable.distinctBy { it.epgId }
            if (byId.size == 1) return byId.single().epgId

            // HD/SD duplicates in some XMLTV feeds intentionally share the same normalised name.
            // Prefer the alias whose advertised quality is closest to the provider stream.
            val bestDistance = byId.minOfOrNull { abs(it.qualityRank - providerQualityRank) }
                ?: return null
            val best = byId.filter { abs(it.qualityRank - providerQualityRank) == bestDistance }
            return best.map { it.epgId }.distinct().singleOrNull()
        }

        private fun preferPopulated(candidates: List<Alias>): List<Alias> {
            if (!hasPopulationInfo) return candidates
            val populated = candidates.filter { it.populated }
            return if (populated.isNotEmpty()) populated else candidates
        }
    }

    /**
     * Builds the lookup from every <channel> alias seen across enabled guides.
     *
     * [populatedIds] should contain EPG ids that actually have programme rows. When supplied, a
     * dead duplicate alias can never beat a working one. Passing null preserves the historical
     * conservative behaviour used by callers/tests that do not know programme availability.
     */
    fun buildIndex(
        aliases: Iterable<Pair<String, String>>,
        populatedIds: Set<String>? = null,
    ): Index {
        val exact = LinkedHashMap<String, MutableList<Alias>>()
        val all = ArrayList<Alias>()

        for ((epgId, name) in aliases) {
            for (candidate in listOf(name, epgId)) {
                val normalized = ChannelNameNormalizer.normalize(candidate)
                val key = normalized.groupKey
                if (key.length < MIN_KEY_LENGTH) continue

                val alias = Alias(
                    epgId = epgId,
                    key = key,
                    qualityRank = normalized.qualityRank,
                    populated = populatedIds?.contains(epgId) == true,
                )
                all += alias
                exact.getOrPut(key) { ArrayList() }.add(alias)
            }
        }

        return Index(
            exact = exact,
            sortedKeys = all,
            hasPopulationInfo = populatedIds != null,
        )
    }

    private const val MIN_KEY_LENGTH = 2
}
