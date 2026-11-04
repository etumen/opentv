package app.opentv.ui.vod

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptionLanguageTest {
    @Test fun frenchLabelOverridesIncorrectTurkishCode() {
        assertEquals("fr", captionLanguage("French", "tr"))
        assertEquals("fr", captionLanguage("Français", "tr"))
    }

    @Test fun trulyTurkishSubtitleIsRecognized() {
        assertEquals("tr", captionLanguage("Türkçe", "tr"))
        assertEquals("tr", captionLanguage("Turkish", null))
    }

    @Test fun unknownSubtitleLanguageIsNotInvented() {
        assertEquals(null, captionLanguage("Altyazı", null))
        assertEquals("fr", captionLanguage("French", null))
        assertEquals("en", captionLanguage("English", "tr"))
    }
}