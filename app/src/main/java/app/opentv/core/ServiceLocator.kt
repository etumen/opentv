/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.content.Context
import app.opentv.data.catalog.CatalogAggregator
import app.opentv.data.catalog.ProviderHealthTracker
import app.opentv.data.catalog.UnifiedSeriesResolver
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.metadata.ImdbSuggestionMetadataSource
import app.opentv.data.metadata.MetadataRepository
import app.opentv.data.metadata.SharedPreferencesMetadataCache
import app.opentv.data.provider.ProviderRegistry
import app.opentv.data.provider.ProviderRepository
import app.opentv.data.provider.ProviderRuntimeRules
import app.opentv.data.provider.RemoteListingAdapter
import app.opentv.data.provider.ddizibox.DiziBoxProvider
import app.opentv.data.provider.dizipal.DiziPalProvider
import app.opentv.data.provider.dizimom.DiziMomProvider
import app.opentv.data.provider.dramadizilerim.DramaDizilerimProvider
import app.opentv.data.provider.filmmakinesi.FilmMakinesiProvider
import app.opentv.data.provider.fullhd.FullHdFilmizleseneProvider
import app.opentv.data.provider.hdfilmcehennemi.HdFilmCehennemiProvider
import app.opentv.data.provider.jetfilmizle.JetFilmizleProvider
import app.opentv.data.provider.sinemacx.SinemaCxProvider
import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.CatalogRepository
import app.opentv.data.repo.EpgRepository
import app.opentv.data.repo.RecordingRepository
import app.opentv.data.repo.ReminderRepository
import app.opentv.data.repo.SourceRepository
import app.opentv.recording.RecordingEngine
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Hand-rolled dependency container.
 *
 * Hilt would be the conventional choice. It is deliberately not used: it adds an annotation
 * processor, a build-time graph, and a class of error message that is genuinely hard to read,
 * in exchange for wiring roughly a dozen objects. For a project whose main hope is drive-by
 * contributions from people fixing their own bugs, "you can read the whole graph in one file"
 * is worth more than the ceremony.
 */
object ServiceLocator {

    @Volatile private var instance: Graph? = null

    fun get(context: Context): Graph =
        instance ?: synchronized(this) {
            instance ?: Graph(context.applicationContext).also { instance = it }
        }

    class Graph(context: Context) {
        private val appContext = context.applicationContext

        val settings: AppSettings by lazy { AppSettings.get(appContext) }

        val database: OpenTvDatabase by lazy { OpenTvDatabase.build(context) }

        val remoteProviderConfig: ProviderRemoteConfigManager by lazy {
            ProviderRemoteConfigManager(appContext)
        }

        val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                // Generous: catalogue endpoints on a busy panel can take a long time to
                // produce 40,000 rows, and timing out mid-list is worse than waiting.
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.MINUTES)
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .build()
        }

        /**
         * A separate client for the player's data source. A live stream is an effectively
         * infinite HTTP response body, so the catalogue client's five-minute [callTimeout] would
         * guillotine every live channel at that mark — here there is no overall call timeout, only
         * a read timeout that catches a genuinely dead connection. Cross-protocol redirects are
         * followed explicitly because IPTV stream URLs routinely bounce http→https onto a CDN, and
         * a client that stops at the redirect surfaces as "lost connection to server".
         */
        val streamingHttpClient: OkHttpClient by lazy {
            httpClient.newBuilder()
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .build()
        }

        /** Only movie/series document traffic can be redirected by the remote rules. */
        val providerHttpClient: OkHttpClient by lazy {
            remoteProviderConfig
            httpClient.newBuilder()
                .addInterceptor { chain ->
                    val original = chain.request()
                    val mapped = ProviderRuntimeRules.remapHost(original.url.host)
                    if (mapped == null || mapped.equals(original.url.host, ignoreCase = true)) {
                        chain.proceed(original)
                    } else {
                        val newUrl = original.url.newBuilder().scheme("https").host(mapped).port(443).build()
                        val builder = original.newBuilder().url(newUrl)
                            .removeHeader("Cookie").removeHeader("Authorization")
                        listOf("Referer", "Origin").forEach { name ->
                            val header = original.header(name)
                            if (header != null) {
                                val oldOrigin = original.url.scheme + "://" + original.url.host
                                if (header.startsWith(oldOrigin)) {
                                    builder.header(name, "https://" + mapped + header.removePrefix(oldOrigin))
                                }
                            }
                        }
                        chain.proceed(builder.build())
                    }
                }.build()
        }

        val xtreamApi: XtreamApi by lazy { XtreamApi(httpClient) }

        /** Stalker / Ministra portal client (MAC handshake + create_link). */
        val stalkerApi: StalkerApi by lazy { StalkerApi(httpClient) }

        /** Native provider graph, independent from IPTV sources. */
        val providerRegistry: ProviderRegistry by lazy {
            ProviderRegistry(
                listOf(
                    FullHdFilmizleseneProvider(providerHttpClient),
                    FilmMakinesiProvider(providerHttpClient),
                    HdFilmCehennemiProvider(providerHttpClient),
                    SinemaCxProvider(providerHttpClient),
                    JetFilmizleProvider(providerHttpClient),
                    DiziPalProvider(appContext, providerHttpClient),
                    DiziBoxProvider(providerHttpClient),
                    DramaDizilerimProvider(providerHttpClient),
                    DiziMomProvider(providerHttpClient),
                ),
            )
        }
        val providerRepository: ProviderRepository by lazy {
            ProviderRepository(providerRegistry, RemoteListingAdapter(providerHttpClient))
        }
        val unifiedCatalog: CatalogAggregator by lazy { CatalogAggregator(providerRepository) }
        val providerHealth: ProviderHealthTracker by lazy {
            ProviderHealthTracker(
                appContext.getSharedPreferences("provider_health", Context.MODE_PRIVATE),
            )
        }
        val metadataRepository: MetadataRepository by lazy {
            MetadataRepository(
                sources = listOf(ImdbSuggestionMetadataSource(httpClient)),
                cache = SharedPreferencesMetadataCache(
                    appContext.getSharedPreferences("content_metadata", Context.MODE_PRIVATE),
                ),
            )
        }
        val unifiedSeries: UnifiedSeriesResolver by lazy {
            UnifiedSeriesResolver(
                repository = providerRepository,
                catalog = unifiedCatalog,
                health = providerHealth,
                metadata = metadataRepository,
            )
        }

        val sourceRepository: SourceRepository by lazy {
            SourceRepository(database.sources(), xtreamApi, stalkerApi)
        }

        val catalogRepository: CatalogRepository by lazy {
            CatalogRepository(
                sourceDao = database.sources(),
                channelDao = database.channels(),
                categoryDao = database.categories(),
                movieDao = database.movies(),
                seriesDao = database.series(),
                episodeDao = database.episodes(),
                positionDao = database.positions(),
                api = xtreamApi,
                stalkerApi = stalkerApi,
                http = httpClient,
                settings = settings,
            )
        }

        val epgRepository: EpgRepository by lazy {
            EpgRepository(
                programmeDao = database.programmes(),
                feedDao = database.epgFeeds(),
                aliasDao = database.epgAliases(),
                channelDao = database.channels(),
                sourceDao = database.sources(),
                api = xtreamApi,
                http = httpClient,
            )
        }

        val playbackPositions get() = database.positions()

        val profiles get() = database.profiles()

        val recordingRepository: RecordingRepository by lazy {
            RecordingRepository(database.recordings(), database.seriesRules())
        }

        val reminderRepository: ReminderRepository by lazy {
            ReminderRepository(database.reminders())
        }

        val recordingEngine: RecordingEngine by lazy {
            RecordingEngine(
                appContext, recordingRepository, sourceRepository, settings,
                database.channels(), epgRepository,
            )
        }
    }
}
