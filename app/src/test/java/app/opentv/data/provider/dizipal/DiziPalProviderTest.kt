package app.opentv.data.provider.dizipal

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.junit.Test

class DiziPalProviderTest {
    private val provider = DiziPalProvider(OkHttpClient())

    @Test
    fun seriesCards_readCurrentGrid() {
        val html = """
            <ul class="content-grid">
              <li>
                <a href="/dizi/test-series">
                  <img data-src="/poster/test.jpg" />
                  <div class="card-info"><h3>Test Series</h3></div>
                </a>
              </li>
            </ul>
        """.trimIndent()

        val items = provider.parseSeriesCards(
            Jsoup.parse(html, DiziPalProvider.FALLBACK_BASE_URL),
        )

        assertThat(items).hasSize(1)
        assertThat(items.single().title).isEqualTo("Test Series")
        assertThat(items.single().id).contains("/dizi/test-series")
    }

    @Test
    fun seriesCards_fallbackToDirectSeriesLinks() {
        val html = """
            <div id="router-view">
              <a href="/series/reacher-c04" aria-label="Reacher">
                <img src="/poster/reacher.jpg" alt="Reacher" />
              </a>
              <a href="/series/the-simpsons">
                <img src="/poster/simpsons.jpg" alt="The Simpsons" />
              </a>
            </div>
        """.trimIndent()

        val items = provider.parseSeriesCards(
            Jsoup.parse(html, DiziPalProvider.FALLBACK_BASE_URL),
        )

        assertThat(items.map { it.title })
            .containsExactly("Reacher", "The Simpsons")
            .inOrder()
    }

    @Test
    fun latestEpisodeCard_mapsBackToSeries() {
        val html = """
            <div class="episodes-list-grid">
              <a class="episode-list-item" href="/bolum/test-series-2x4">
                <img data-src="/poster/test.jpg" />
                <div class="ep-title">Test Series</div>
                <div class="ep-info">2. Sezon 4. Bölüm</div>
              </a>
            </div>
        """.trimIndent()

        val items = provider.parseLatestEpisodeCards(
            Jsoup.parse(html, DiziPalProvider.FALLBACK_BASE_URL),
        )

        assertThat(items).hasSize(1)
        assertThat(items.single().id).contains("/series/test-series")
        assertThat(items.single().id).doesNotContain("/bolum/")
    }

    @Test
    fun detailsAndEpisodes_readSeasonMetadata() {
        val html = """
            <html>
              <head><meta property="og:image" content="/poster/show.jpg" /></head>
              <body>
                <h1 class="series-title">Örnek Dizi</h1>
                <p class="series-description">Açıklama.</p>
                <div class="info-row">Yıl <span class="info-value">2026</span></div>
                <div class="info-row">Kategoriler
                  <span class="info-value categories"><a>Dram</a><a>Gizem</a></span>
                </div>
                <div class="detail-episode-item-wrap">
                  <a class="detail-episode-item" href="/bolum/ornek-dizi-1-sezon-3-bolum">
                    <div class="detail-episode-title">Üçüncü Bölüm</div>
                    <div class="detail-episode-subtitle">1. Sezon 3. Bölüm</div>
                  </a>
                </div>
              </body>
            </html>
        """.trimIndent()
        val doc = Jsoup.parse(
            html,
            DiziPalProvider.FALLBACK_BASE_URL + "/dizi/ornek-dizi",
        )
        val seriesId = DiziPalProvider.FALLBACK_BASE_URL + "/dizi/ornek-dizi"

        val details = provider.parseDetails(doc, seriesId)
        val episodes = provider.parseEpisodes(doc, seriesId)

        assertThat(details?.item?.title).isEqualTo("Örnek Dizi")
        assertThat(details?.item?.year).isEqualTo(2026)
        assertThat(details?.item?.genres).containsExactly("Dram", "Gizem")
        assertThat(episodes).hasSize(1)
        assertThat(episodes.single().season).isEqualTo(1)
        assertThat(episodes.single().episodeNumber).isEqualTo(3)
    }

    @Test
    fun search_keepsOnlySeries() {
        val body = """
            {
              "results": [
                {"title":"Dizi A","url":"/dizi/a","poster":"/a.jpg","year":2025,"type":"Dizi"},
                {"title":"Film B","url":"/film/b","poster":"/b.jpg","year":2024,"type":"Film"}
              ]
            }
        """.trimIndent()

        val items = provider.parseSearch(body)

        assertThat(items).hasSize(1)
        assertThat(items.single().title).isEqualTo("Dizi A")
    }

    @Test
    fun tracks_readsSubtitleEntries() {
        val source = """
            tracks: [
              { file: "https://cdn.test/tr.vtt", label: "Türkçe" },
              { file: "https://cdn.test/en.srt", label: "English" }
            ]
        """.trimIndent()

        val tracks = provider.parseTracks(source)

        assertThat(tracks).containsExactly(
            "Türkçe" to "https://cdn.test/tr.vtt",
            "English" to "https://cdn.test/en.srt",
        )
    }
}
