/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.provider.fullhd

import android.util.Log
import app.opentv.data.provider.Provider
import app.opentv.data.provider.ProviderCatalogPage
import app.opentv.data.provider.ProviderCatalogRequest
import app.opentv.data.provider.ProviderCatalogSection
import app.opentv.data.provider.ProviderDetails
import app.opentv.data.provider.ProviderEpisode
import app.opentv.data.provider.ProviderError
import app.opentv.data.provider.ProviderErrorCode
import app.opentv.data.provider.ProviderItem
import app.opentv.data.provider.ProviderMediaType
import app.opentv.data.provider.ProviderPlaybackTarget
import app.opentv.data.provider.ProviderResult
import app.opentv.data.provider.ProviderSearchRequest
import app.opentv.data.provider.ProviderStream
import app.opentv.data.provider.ProviderSubtitle
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Native OpenTV provider for FullHDFilmizlesene.
 *
 * It intentionally owns no UI and no CloudStream runtime. Catalogue/search/detail pages are parsed
 * directly and the current scx -> RapidVid playback chain is resolved into normal OpenTV streams.
 */
class FullHdFilmizleseneProvider(
    private val http: OkHttpClient,
) : Provider {
    override val id: String = PROVIDER_ID
    override val name: String = "FullHDFilmizlesene"
    override val supportedMediaTypes: Set<ProviderMediaType> = setOf(ProviderMediaType.MOVIE)
    override val catalogSections: List<ProviderCatalogSection>
        get() = activeSections().map { (id, section) ->
            ProviderCatalogSection(
                id = id,
                title = section.title,
                mediaType = ProviderMediaType.MOVIE,
                showOnHome = section.showOnHome,
            )
        }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val baseUrl: HttpUrl = MAIN_URL.toHttpUrl()

    private data class Section(
        val title: String,
        val path: String,
        val showOnHome: Boolean = true,
    )

    private data class Playback(
        val streams: List<ProviderStream>,
        val subtitles: List<ProviderSubtitle>,
    )

    private data class CachedPlayback(
        val createdAtMillis: Long,
        val value: Playback,
    )

    private val playbackCache = linkedMapOf<String, CachedPlayback>()

    private val fallbackSections = linkedMapOf(
        "latest" to Section("En Çok İzlenen Filmler", "/en-cok-izlenen-filmler-izle-hd/"),
        "imdb" to Section("IMDB Puanı Yüksek Filmler", "/filmizle/imdb-puani-yuksek-filmler-izle-1/"),
        "family" to Section("Aile Filmleri", "/filmizle/aile-filmleri-hdf-izle/"),
        "action" to Section("Aksiyon Filmleri", "/filmizle/aksiyon-filmleri-hdf-izle/"),
        "animation" to Section("Animasyon Filmleri", "/filmizle/animasyon-filmleri-fhd-izle/"),
        "documentary" to Section("Belgeseller", "/filmizle/belgesel-filmleri-izle/", showOnHome = false),
        "scifi" to Section("Bilim Kurgu Filmleri", "/filmizle/bilim-kurgu-filmleri-izle-2/"),
        "bluray" to Section("Blu Ray Filmler", "/filmizle/bluray-filmler-izle/", showOnHome = false),
        "cartoon" to Section("Çizgi Filmler", "/filmizle/cizgi-filmler-fhd-izle/", showOnHome = false),
        "drama" to Section("Dram Filmleri", "/filmizle/dram-filmleri-hd-izle/", showOnHome = false),
        "fantasy" to Section("Fantastik Filmler", "/filmizle/fantastik-filmler-hd-izle/", showOnHome = false),
        "thriller" to Section("Gerilim Filmleri", "/filmizle/gerilim-filmleri-fhd-izle/", showOnHome = false),
        "mystery" to Section("Gizem Filmleri", "/filmizle/gizem-filmleri-hd-izle/", showOnHome = false),
        "indian" to Section("Hint Filmleri", "/filmizle/hint-filmleri-fhd-izle/", showOnHome = false),
        "comedy" to Section("Komedi Filmleri", "/filmizle/komedi-filmleri-fhd-izle/", showOnHome = false),
        "horror" to Section("Korku Filmleri", "/filmizle/korku-filmleri-izle-3/", showOnHome = false),
        "adventure" to Section("Macera Filmleri", "/filmizle/macera-filmleri-fhd-izle/", showOnHome = false),
        "musical" to Section("Müzikal Filmler", "/filmizle/muzikal-filmler-izle/", showOnHome = false),
        "crime-police" to Section("Polisiye Filmleri", "/filmizle/polisiye-filmleri-izle/", showOnHome = false),
        "psychological" to Section("Psikolojik Filmler", "/filmizle/psikolojik-filmler-izle/", showOnHome = false),
        "romance" to Section("Romantik Filmler", "/filmizle/romantik-filmler-fhd-izle/", showOnHome = false),
        "war" to Section("Savaş Filmleri", "/filmizle/savas-filmleri-fhd-izle/", showOnHome = false),
        "crime" to Section("Suç Filmleri", "/filmizle/suc-filmleri-izle/", showOnHome = false),
        "history" to Section("Tarih Filmleri", "/filmizle/tarih-filmleri-fhd-izle/", showOnHome = false),
        "western" to Section("Western Filmler", "/filmizle/western-filmler-hd-izle-3/", showOnHome = false),
        "local" to Section("Yerli Filmler", "/filmizle/yerli-filmler-hd-izle/", showOnHome = false),
    )

    @Volatile
    private var discoveredSections: Map<String, Section>? = null

    private fun activeSections(): Map<String, Section> =
        discoveredSections ?: fallbackSections

    override suspend fun discoverCatalogSections(
        mediaType: ProviderMediaType,
    ): ProviderResult<List<ProviderCatalogSection>> {
        if (mediaType != ProviderMediaType.MOVIE) return ProviderResult.Success(emptyList())
        discoveredSections?.let { return ProviderResult.Success(catalogSections) }

        return guarded("catalogSections") {
            val links = parseCatalogSectionLinks(fetchDocument(MAIN_URL))
            if (links.size >= MIN_DISCOVERED_CATEGORIES) {
                discoveredSections = mergeDiscoveredSections(links)
            }
            catalogSections
        }
    }

    override suspend fun catalog(
        request: ProviderCatalogRequest,
    ): ProviderResult<ProviderCatalogPage> = guarded("catalog") {
        val sections = activeSections()
        val section = sections[request.sectionId ?: "latest"] ?: fallbackSections.getValue("latest")
        val page = request.page.coerceAtLeast(1)
        val base = absolute(section.path)
        val target = if (page == 1) base else base.trimEnd('/') + "/$page"
        val items = parseCards(fetchDocument(target))
        Log.d(TAG, "catalog section=${section.title} page=$page items=${items.size} url=$target")
        ProviderCatalogPage(
            title = section.title,
            items = items,
            nextPage = if (items.isEmpty()) null else page + 1,
        )
    }

    override suspend fun search(
        request: ProviderSearchRequest,
    ): ProviderResult<List<ProviderItem>> = guarded("search") {
        val query = request.query.trim()
        if (query.isEmpty()) {
            emptyList()
        } else {
            // The autocomplete endpoint is Cloudflare-protected intermittently. The HTML search
            // route is currently reachable without a browser challenge and returns the same cards.
            val target = baseUrl.newBuilder()
                .addPathSegment("arama")
                .addPathSegment(query)
                .build()
                .toString()
            parseCards(fetchDocument(target))
        }
    }

    override suspend fun load(itemId: String): ProviderResult<ProviderDetails> = guarded("load") {
        val url = absolute(itemId)
        val doc = fetchDocument(url)

        val mainTitle = doc.selectFirst("h1.film-title, h1")?.text()?.trim()
        val originalTitle = doc.selectFirst("span.film-sub-title, div.detay-orig")?.text()?.trim()
        val fallbackTitle = doc.selectFirst("meta[property='og:title']")
            ?.attr("content")
            ?.cleanWatchSuffix()

        val title = when {
            !mainTitle.isNullOrBlank() &&
                !originalTitle.isNullOrBlank() &&
                !mainTitle.equals(originalTitle, ignoreCase = true) ->
                "$mainTitle - $originalTitle"
            !mainTitle.isNullOrBlank() -> mainTitle.cleanWatchSuffix()
            !fallbackTitle.isNullOrBlank() -> fallbackTitle
            else -> throw IllegalArgumentException("Movie title was not found")
        }

        val poster = firstUrl(
            doc.selectFirst("meta[property='og:image']")?.attr("content"),
            imageUrl(doc.selectFirst("div.film-afis img, img.afis, picture img")),
        )
        val plot = firstText(
            doc.selectFirst(".ozet-ic, .film-ozeti, div.film-ozet, div.ozet")?.text(),
            doc.selectFirst("meta[property='og:description']")?.attr("content"),
        )
        val year = detailYear(doc, title)
        val rating = parseRating(
            doc.selectFirst(".imdb, span.imdb, span.puan, div.puanx-puan")?.text(),
        )
        val genres = doc.select(
            ".film-info a[rel='category tag'], .film-info a[href*='/filmizle/'], a[rel='category tag']",
        ).map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val cast = infoLinks(doc, "oyunc")
        val director = infoLinks(doc, "yönetmen").firstOrNull()
        val durationSeconds = infoText(doc, "süre")
            ?.let { Regex("""(\d{1,3})\s*(?:dk|dakika|min)""", RegexOption.IGNORE_CASE).find(it) }
            ?.groupValues?.getOrNull(1)
            ?.toIntOrNull()
            ?.times(60)

        val imdbId = doc.select("a[href*='imdb.com/title/']")
            .asSequence()
            .mapNotNull { Regex("""tt\d+""").find(it.attr("href"))?.value }
            .firstOrNull()

        val item = ProviderItem(
            providerId = id,
            id = url,
            title = title,
            mediaType = ProviderMediaType.MOVIE,
            posterUrl = poster,
            description = plot,
            year = year,
            rating = rating,
            genres = genres,
        )
        ProviderDetails(
            item = item,
            description = plot,
            cast = cast,
            director = director,
            durationSeconds = durationSeconds,
            externalIds = imdbId?.let { mapOf("imdb" to it) }.orEmpty(),
        )
    }

    override suspend fun episodes(
        seriesId: String,
    ): ProviderResult<List<ProviderEpisode>> =
        failure(
            operation = "episodes",
            code = ProviderErrorCode.UNSUPPORTED,
            message = "FullHDFilmizlesene is a movie-only provider",
        )

    override suspend fun streams(
        target: ProviderPlaybackTarget,
    ): ProviderResult<List<ProviderStream>> {
        val movie = target as? ProviderPlaybackTarget.Movie
            ?: return failure("streams", ProviderErrorCode.UNSUPPORTED, "Movie target required")
        return guarded("streams") { resolvePlaybackCached(absolute(movie.itemId)).streams }
    }

    override suspend fun subtitles(
        target: ProviderPlaybackTarget,
    ): ProviderResult<List<ProviderSubtitle>> {
        val movie = target as? ProviderPlaybackTarget.Movie
            ?: return failure("subtitles", ProviderErrorCode.UNSUPPORTED, "Movie target required")
        return guarded("subtitles") { resolvePlaybackCached(absolute(movie.itemId)).subtitles }
    }

    internal fun parseCards(doc: Document): List<ProviderItem> =
        doc.select(".list li.film, ul.film-list li, li.film, div.film")
            .mapNotNull(::parseCard)
            .distinctBy { it.id }

    internal fun parseCatalogSectionLinks(doc: Document): List<Pair<String, String>> {
        val menuLinks = doc.select(
            "header a[href*='/filmizle/'], nav a[href*='/filmizle/'], " +
                ".menu a[href*='/filmizle/'], .dropdown-menu a[href*='/filmizle/'], " +
                ".sub-menu a[href*='/filmizle/']",
        )
        val candidates = if (menuLinks.size >= MIN_DISCOVERED_CATEGORIES) {
            menuLinks
        } else {
            doc.select("a[href*='/filmizle/']")
        }

        return candidates.mapNotNull { link ->
            val title = link.text().replace(Regex("""\s+"""), " ").trim()
            if (title.length !in 2..60) return@mapNotNull null

            val rawHref = link.attr("abs:href").ifBlank { link.attr("href") }
            val resolved = absoluteFrom(doc.baseUri().ifBlank { MAIN_URL }, rawHref)
                ?: return@mapNotNull null
            val url = runCatching { resolved.toHttpUrl() }.getOrNull()
                ?: return@mapNotNull null
            if (!url.host.equals(baseUrl.host, ignoreCase = true)) return@mapNotNull null

            val path = url.encodedPath.trimEnd('/')
            if (!path.startsWith("/filmizle/")) return@mapNotNull null
            val slug = path.removePrefix("/filmizle/")
            if (slug.isBlank() || slug.contains('/')) return@mapNotNull null

            title to "$path/"
        }.distinctBy { (_, path) -> path.lowercase() }
    }

    private fun mergeDiscoveredSections(
        links: List<Pair<String, String>>,
    ): Map<String, Section> {
        val merged = LinkedHashMap(fallbackSections)

        links.forEach { (title, path) ->
            val key = categoryKey(title)
            val existing = merged.entries.firstOrNull { categoryKey(it.value.title) == key }
            if (existing != null) {
                merged[existing.key] = existing.value.copy(path = path)
            } else {
                val slug = path.removePrefix("/filmizle/").trim('/')
                var id = "site:$slug"
                var suffix = 2
                while (id in merged) {
                    id = "site:$slug-$suffix"
                    suffix += 1
                }
                merged[id] = Section(
                    title = title,
                    path = path,
                    showOnHome = false,
                )
            }
        }

        return merged
    }

    private fun categoryKey(value: String): String =
        value.lowercase()
            .replace(Regex("""\b(filmleri|filmler|film|izle|hd)\b"""), " ")
            .replace(Regex("""[^\p{L}\p{N}]+"""), "")
            .trim()

    private fun parseCard(element: Element): ProviderItem? {
        val link = element.selectFirst("a.tt")
            ?: element.selectFirst("a[href*='/film/']")
            ?: element.selectFirst("a")
            ?: return null

        val href = absolute(link.attr("abs:href").ifBlank { link.attr("href") })
        if (!href.contains("/film/")) return null

        val turkishTitle = element.selectFirst("span.film-title")?.text()?.trim()
        val originalTitle = element.selectFirst("span.kt")?.text()?.trim()
        val rawTitle = link.text().trim().cleanWatchSuffix()
        val title = when {
            !turkishTitle.isNullOrBlank() &&
                !originalTitle.isNullOrBlank() &&
                !turkishTitle.equals(originalTitle, ignoreCase = true) ->
                "$turkishTitle - $originalTitle"
            !turkishTitle.isNullOrBlank() -> turkishTitle
            rawTitle.isNotBlank() -> rawTitle
            else -> return null
        }

        val image = element.selectFirst("picture img") ?: element.selectFirst("img")
        val poster = imageUrl(image)
        val year = element.selectFirst("span.film-yil")
            ?.text()
            ?.let(::firstYear)
            ?: firstYear(title)
        val rating = parseRating(element.selectFirst("span.imdb, .imdb")?.text())

        return ProviderItem(
            providerId = id,
            id = href,
            title = title,
            mediaType = ProviderMediaType.MOVIE,
            posterUrl = poster,
            year = year,
            rating = rating,
        )
    }

    private suspend fun resolvePlaybackCached(movieUrl: String): Playback {
        val now = System.currentTimeMillis()
        synchronized(playbackCache) {
            playbackCache[movieUrl]
                ?.takeIf { now - it.createdAtMillis <= PLAYBACK_CACHE_MILLIS }
                ?.let { return it.value }
        }

        val resolved = resolvePlayback(movieUrl)
        synchronized(playbackCache) {
            playbackCache[movieUrl] = CachedPlayback(now, resolved)
            while (playbackCache.size > PLAYBACK_CACHE_SIZE) {
                playbackCache.remove(playbackCache.keys.first())
            }
        }
        return resolved
    }

    private suspend fun resolvePlayback(movieUrl: String): Playback {
        val html = fetchText(movieUrl)
        val doc = Jsoup.parse(html, movieUrl)

        val candidates = buildList {
            doc.select("iframe").forEach { iframe ->
                val source = iframe.attr("data-src").ifBlank { iframe.attr("src") }
                absoluteFrom(movieUrl, source)?.let { resolved ->
                    if (!resolved.contains("youtube.com") && !resolved.contains("youtu.be")) {
                        add(resolved)
                    }
                }
            }
            addAll(FullHdFilmizleseneCodec.extractScxUrls(html))
        }.distinct()

        val streams = mutableListOf<ProviderStream>()
        val subtitles = mutableListOf<ProviderSubtitle>()

        for (candidate in candidates) {
            when {
                candidate.contains("rapidvid.", ignoreCase = true) -> {
                    val playback = resolveRapidVid(candidate, movieUrl)
                    streams += playback.streams
                    subtitles += playback.subtitles
                }
                candidate.isDirectMedia() -> {
                    streams += ProviderStream(
                        providerId = id,
                        url = candidate,
                        label = "FullHDFilmizlesene",
                        mimeType = candidate.mimeType(),
                        headers = playbackHeaders(movieUrl),
                    )
                }
                else -> {
                    val generic = resolveGenericEmbed(candidate, movieUrl)
                    streams += generic.streams
                    subtitles += generic.subtitles
                }
            }
        }

        return Playback(
            streams = streams.distinctBy { it.url },
            subtitles = subtitles.distinctBy { it.url },
        )
    }

    private suspend fun resolveRapidVid(url: String, movieUrl: String): Playback {
        val html = fetchText(url, referer = movieUrl)
        val streams = mutableListOf<ProviderStream>()
        val subtitles = mutableListOf<ProviderSubtitle>()

        FullHdFilmizleseneCodec.extractRapidVidPayloadJson(html)?.let { payloadJson ->
            val payload = json.parseToJsonElement(payloadJson).jsonObject
            listOf("cm" to "RapidVid", "tm" to "RapidVid Alternatif")
                .forEach { (key, label) ->
                    val streamUrl = payload[key]?.jsonPrimitive?.contentOrNull
                    if (!streamUrl.isNullOrBlank() && streamUrl.startsWith("http")) {
                        streams += ProviderStream(
                            providerId = id,
                            url = streamUrl.replace("\\/", "/"),
                            label = label,
                            mimeType = HLS_MIME,
                            headers = rapidVidHeaders(url),
                        )
                    }
                }

            payload["ct"]?.jsonArray?.forEach { value ->
                val caption = value.jsonObject
                val subtitleUrl = caption["file"]?.jsonPrimitive?.contentOrNull
                    ?.replace("\\/", "/")
                if (!subtitleUrl.isNullOrBlank()) {
                    val label = caption["label"]?.jsonPrimitive?.contentOrNull?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?: "Türkçe"
                    subtitles += ProviderSubtitle(
                        providerId = id,
                        url = subtitleUrl,
                        label = label,
                        language = if (label.contains("Türk", ignoreCase = true)) "tr" else null,
                        mimeType = if (subtitleUrl.contains(".vtt", ignoreCase = true)) {
                            "text/vtt"
                        } else {
                            null
                        },
                    )
                }
            }
        }

        if (streams.isEmpty()) {
            val legacy = Regex(
                """(?:file"?\s*:\s*)?av\(['"]([^'"]+)['"]\)""",
                RegexOption.IGNORE_CASE,
            ).find(html)?.groupValues?.getOrNull(1)
            val streamUrl = legacy?.let(FullHdFilmizleseneCodec::decryptRapidVidAv)
            if (!streamUrl.isNullOrBlank() && streamUrl.startsWith("http")) {
                streams += ProviderStream(
                    providerId = id,
                    url = streamUrl,
                    label = "RapidVid",
                    mimeType = HLS_MIME,
                    headers = rapidVidHeaders(url),
                )
            }
        }

        return Playback(streams, subtitles)
    }

    private suspend fun resolveGenericEmbed(url: String, referer: String): Playback =
        runCatching {
            val html = fetchText(url, referer)
            val doc = Jsoup.parse(html, url)
            val mediaUrls = buildList {
                doc.select("video source[src], source[src]").forEach { source ->
                    absoluteFrom(url, source.attr("src"))?.let(::add)
                }
                DIRECT_MEDIA_REGEX.findAll(html).forEach { match ->
                    add(match.value.replace("\\/", "/"))
                }
            }.filter { it.isDirectMedia() }.distinct()

            Playback(
                streams = mediaUrls.mapIndexed { index, streamUrl ->
                    ProviderStream(
                        providerId = id,
                        url = streamUrl,
                        label = if (index == 0) "Alternatif" else "Alternatif ${index + 1}",
                        mimeType = streamUrl.mimeType(),
                        headers = playbackHeaders(url),
                    )
                },
                subtitles = emptyList(),
            )
        }.getOrDefault(Playback(emptyList(), emptyList()))

    private suspend fun fetchDocument(url: String): Document =
        Jsoup.parse(fetchText(url), url)

    private suspend fun fetchText(
        url: String,
        referer: String = MAIN_URL,
    ): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.7")
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for $url")
            }
            response.body?.string() ?: throw IOException("Empty response body for $url")
        }
    }

    private fun imageUrl(element: Element?): String? {
        element ?: return null
        val source = element.attr("data-src")
            .ifBlank { element.attr("data-lazy-src") }
            .ifBlank { element.attr("src") }
        return absoluteFrom(element.baseUri(), source)
    }

    private fun detailYear(doc: Document, title: String): Int? {
        val labelled = infoText(doc, "yapım")?.let(::firstYear)
        return labelled
            ?: doc.selectFirst("span.film-yil, div.detay-yil")?.text()?.let(::firstYear)
            ?: firstYear(title)
    }

    private fun infoText(doc: Document, needle: String): String? =
        doc.select(".film-info li, .film-info div, .film-info p")
            .firstOrNull { it.text().contains(needle, ignoreCase = true) }
            ?.text()
            ?.trim()

    private fun infoLinks(doc: Document, needle: String): List<String> {
        val row = doc.select(".film-info li, .film-info div, .film-info p")
            .firstOrNull { it.text().contains(needle, ignoreCase = true) }
            ?: return emptyList()
        return row.select("a span, a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !it.contains(needle, ignoreCase = true) }
            .distinct()
    }

    private fun firstYear(value: String): Int? =
        Regex("""\b(19|20)\d{2}\b""").find(value)?.value?.toIntOrNull()

    private fun parseRating(value: String?): Double? =
        value?.replace(',', '.')
            ?.let { Regex("""\b10(?:\.0)?\b|\b\d(?:\.\d+)?\b""").find(it)?.value }
            ?.toDoubleOrNull()
            ?.takeIf { it in 0.0..10.0 }

    private fun firstText(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.trim()

    private fun firstUrl(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.let(::absolute)

    private fun absolute(raw: String): String =
        absoluteFrom(MAIN_URL, raw) ?: raw

    private fun absoluteFrom(base: String, raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        if (value.startsWith("//")) return "https:$value"
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        return runCatching { base.toHttpUrl().resolve(value)?.toString() }.getOrNull()
    }

    private fun String.cleanWatchSuffix(): String =
        replace(Regex("""\s+[İi]zle\s*$""", RegexOption.IGNORE_CASE), "").trim()

    private fun String.isDirectMedia(): Boolean =
        contains(".m3u8", ignoreCase = true) || contains(".mp4", ignoreCase = true)

    private fun String.mimeType(): String? = when {
        contains(".m3u8", ignoreCase = true) -> HLS_MIME
        contains(".mp4", ignoreCase = true) -> "video/mp4"
        else -> null
    }

    private fun playbackHeaders(referer: String): Map<String, String> =
        mapOf(
            "Referer" to referer,
            "User-Agent" to USER_AGENT,
        )

    private fun rapidVidHeaders(url: String): Map<String, String> {
        val parsed = runCatching { url.toHttpUrl() }.getOrNull()
        val referer = parsed?.let { "${it.scheme}://${it.host}/" } ?: RAPIDVID_REFERER
        return mapOf(
            "Referer" to referer,
            "User-Agent" to USER_AGENT,
        )
    }

    private suspend fun <T> guarded(
        operation: String,
        block: suspend () -> T,
    ): ProviderResult<T> =
        try {
            ProviderResult.Success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            failure(operation, ProviderErrorCode.NETWORK, error.message ?: "Network error", error)
        } catch (error: Exception) {
            failure(operation, ProviderErrorCode.PARSE, error.message ?: "Provider parse error", error)
        }

    private fun failure(
        operation: String,
        code: ProviderErrorCode,
        message: String,
        cause: Throwable? = null,
    ): ProviderResult.Failure =
        ProviderResult.Failure(
            ProviderError(
                providerId = id,
                operation = operation,
                code = code,
                message = message,
                cause = cause,
            ),
        )

    companion object {
        private const val TAG = "FullHdFilmProvider"
        const val PROVIDER_ID = "fullhdfilmizlesene"
        const val MAIN_URL = "https://www.fullhdfilmizlesene.now"
        private const val RAPIDVID_REFERER = "https://rapidvid.org/"
        private const val HLS_MIME = "application/x-mpegURL"
        private const val PLAYBACK_CACHE_MILLIS = 90_000L
        private const val PLAYBACK_CACHE_SIZE = 8
        private const val MIN_DISCOVERED_CATEGORIES = 6
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private val DIRECT_MEDIA_REGEX = Regex(
            """https?://[^"'\s<>]+\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?""",
            RegexOption.IGNORE_CASE,
        )
    }
}
