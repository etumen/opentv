package app.opentv.ui.vod

import org.junit.Assert.assertEquals
import org.junit.Test

class PosterSourcesTest {
    @Test fun triesAlternativeSourcesWhenPrimaryPosterIsBlocked() {
        val first = "https://blocked.example/poster.webp"
        val alternate = "https://healthy.example/poster.jpg"
        assertEquals(
            listOf(first, alternate),
            posterCandidates(first, listOf(first, alternate)),
        )
    }

    @Test fun ignoresBlankAndInvalidPosterCandidates() {
        assertEquals(
            listOf("https://healthy.example/p.jpg"),
            posterCandidates(null, listOf("", "data:image/png;base64,a", "https://healthy.example/p.jpg")),
        )
    }
}
