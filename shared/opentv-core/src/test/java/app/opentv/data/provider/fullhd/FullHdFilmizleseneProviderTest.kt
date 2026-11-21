package app.opentv.data.provider.fullhd

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.junit.Test

class FullHdFilmizleseneProviderTest {
    @Test
    fun cardParser_readsCurrentSiteShape() {
        val html = """
            <ul class="list">
              <li class="film">
                <a class="tt" href="https://www.fullhdfilmizlesene.now/film/the-matrix-1/">
                  The Matrix 1 izle
                </a>
                <h2 class="film-tt"><span class="film-title">The Matrix 1</span></h2>
                <span class="imdb">8.7</span>
                <span class="film-yil">1999</span>
                <picture>
                  <img class="lazy afis"
                       data-src="https://img.fullhdfilmizlesene.now/poster/film/fullhd-the-matrix-1-720p.jpg"/>
                </picture>
              </li>
            </ul>
        """.trimIndent()

        val provider = FullHdFilmizleseneProvider(OkHttpClient())
        val items = provider.parseCards(
            Jsoup.parse(html, FullHdFilmizleseneProvider.MAIN_URL),
        )

        assertThat(items).hasSize(1)
        assertThat(items.single().title).isEqualTo("The Matrix 1")
        assertThat(items.single().year).isEqualTo(1999)
        assertThat(items.single().rating).isEqualTo(8.7)
        assertThat(items.single().posterUrl).contains("fullhd-the-matrix-1-720p.jpg")
        assertThat(items.single().id).contains("/film/the-matrix-1/")
    }

    @Test
    fun categoryParser_readsSiteGenreMenu() {
        val html = """
            <header>
              <nav>
                <a href="/filmizle/aile-filmleri-izle-2/">Aile Filmleri</a>
                <a href="/filmizle/aksiyon-filmler-izle-1/">Aksiyon Filmleri</a>
                <a href="/filmizle/bilim-kurgu-filmleri-izle-1/">Bilim Kurgu Filmleri</a>
                <a href="/filmizle/gerilim-filmleri-izle-3/">Gerilim Filmleri</a>
                <a href="/filmizle/komedi-filmleri-izle-2/">Komedi Filmleri</a>
                <a href="/filmizle/korku-filmleri-izle-2/">Korku Filmleri</a>
                <a href="/filmizle/romantik-filmler-izle-1/">Romantik Filmler</a>
              </nav>
            </header>
            <ul class="list">
              <li class="film">
                <a href="/film/the-matrix-1/">The Matrix</a>
              </li>
            </ul>
        """.trimIndent()

        val provider = FullHdFilmizleseneProvider(OkHttpClient())
        val categories = provider.parseCatalogSectionLinks(
            Jsoup.parse(html, FullHdFilmizleseneProvider.MAIN_URL),
        )

        assertThat(categories.map { it.first }).containsAtLeast(
            "Aksiyon Filmleri",
            "Bilim Kurgu Filmleri",
            "Komedi Filmleri",
            "Korku Filmleri",
        )
        assertThat(categories.map { it.second }).contains(
            "/filmizle/korku-filmleri-izle-2/",
        )
        assertThat(categories.flatMap { it.toList() }).doesNotContain("The Matrix")
    }
}
