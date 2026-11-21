package app.opentv.ui.vod

import org.junit.Assert.assertEquals
import org.junit.Test

class UnifiedLanguageClaimTest {
    @Test fun turkishDubLabelRemainsASourceClaim() {
        assertEquals("Kaynak: Türkçe Dublaj", UnifiedCatalogRules.sourceClaimLabel("Türkçe Dublaj"))
        assertEquals("Kaynak: Türkçe Dublaj", UnifiedCatalogRules.sourceClaimLabel("Turkce Dublaj"))
    }

    @Test fun turkishSubtitleLabelRemainsASourceClaim() {
        assertEquals("Kaynak: Türkçe Altyazılı", UnifiedCatalogRules.sourceClaimLabel("Türkçe Altyazılı"))
    }

    @Test fun unknownCategoryRemainsUnchanged() {
        assertEquals("Aksiyon", UnifiedCatalogRules.sourceClaimLabel("Aksiyon"))
        assertEquals("Dublaj", UnifiedCatalogRules.sourceClaimLabel("Dublaj"))
    }
}
