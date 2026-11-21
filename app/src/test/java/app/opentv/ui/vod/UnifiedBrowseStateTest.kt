package app.opentv.ui.vod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedBrowseStateTest {
    @Test fun initialCatalogShowsLoadingUntilFirstFetch() {
        val state = UnifiedBrowseState()
        assertTrue(state.loading)
        assertTrue(state.items.isEmpty())
        assertFalse(state.hasMore)
    }
}
