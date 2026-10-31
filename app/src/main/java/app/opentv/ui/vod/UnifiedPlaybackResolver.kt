package app.opentv.ui.vod

import android.util.Log
import app.opentv.data.provider.ProviderItem
import app.opentv.data.provider.ProviderMediaType
import app.opentv.data.provider.ProviderPlaybackTarget
import app.opentv.data.provider.ProviderRepository
import app.opentv.data.provider.ProviderResult
import app.opentv.data.provider.ProviderStream
import app.opentv.data.provider.ProviderSubtitle

internal data class UnifiedResolvedPlayback(
    val source: ProviderItem,
    val stream: ProviderStream,
    val subtitle: ProviderSubtitle?,
)

/** Every alternate is resolved afresh; never cache short-lived signed playback URLs. */
internal object UnifiedPlaybackResolver {
    fun exactEpisode(
        episodes: List<app.opentv.data.provider.ProviderEpisode>,
        season: Int,
        number: Int,
    ): app.opentv.data.provider.ProviderEpisode? =
        episodes.firstOrNull { it.season == season && it.episodeNumber == number }

    suspend fun resolve(
        repository: ProviderRepository,
        source: ProviderItem,
        type: ProviderMediaType,
        season: Int? = null,
        episodeNumber: Int? = null,
    ): UnifiedResolvedPlayback? {
        if (source.mediaType != type) return null
        val target = when (type) {
            ProviderMediaType.MOVIE -> ProviderPlaybackTarget.Movie(source.providerId, source.id)
            ProviderMediaType.SERIES -> {
                if (season == null || episodeNumber == null) return null
                val episodes = (repository.episodes(source.providerId, source.id)
                    as? ProviderResult.Success)?.value.orEmpty()
                val episode = exactEpisode(episodes, season, episodeNumber) ?: return null
                ProviderPlaybackTarget.Episode(source.providerId, source.id, episode.id)
            }
            else -> return null
        }
        val streams = (repository.streams(target) as? ProviderResult.Success)?.value.orEmpty()
        val stream = streams.firstOrNull() ?: run {
            Log.w("UnifiedFallback", "provider=${source.providerId} no playable stream")
            return null
        }
        val subtitles = (repository.subtitles(target) as? ProviderResult.Success)?.value.orEmpty()
        val subtitle = subtitles.firstOrNull { it.language.equals("tr", ignoreCase = true) }
            ?: subtitles.firstOrNull()
        Log.d("UnifiedFallback", "resolved provider=${source.providerId} type=$type")
        return UnifiedResolvedPlayback(source, stream, subtitle)
    }

    suspend fun first(
        repository: ProviderRepository,
        context: UnifiedFallbackContext,
    ): UnifiedResolvedPlayback? {
        for (item in context.alternateItems) {
            val recovered = resolve(repository, item, context.mediaType, context.season, context.episodeNumber)
            if (recovered != null) return recovered
        }
        return null
    }
}
