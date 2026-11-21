/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.parser.VodTitleCleaner
import app.opentv.data.parser.episodeDisplayTitle
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VodTitleCleanerTest {

    @Test
    fun `prefix tags are the ones clean strips, in order`() {
        assertThat(VodTitleCleaner.prefixTags("4K-EN - The Matrix  (1999)")).containsExactly("4K", "EN").inOrder()
        assertThat(VodTitleCleaner.prefixTags("DE - Animatrix (2003)")).containsExactly("DE")
        assertThat(VodTitleCleaner.prefixTags("[ES] Torrente")).containsExactly("ES")
        assertThat(VodTitleCleaner.prefixTags("A+ - Sago Mini Friends")).containsExactly("A+")
        assertThat(VodTitleCleaner.clean("A+ - Sago Mini Friends")).isEqualTo("Sago Mini Friends")
        assertThat(VodTitleCleaner.prefixTags("MAX - Friends (1994)")).containsExactly("MAX")
        assertThat(VodTitleCleaner.clean("SUBS - Zakir Khan: Papa Yaar")).isEqualTo("Zakir Khan: Papa Yaar")
        // A title that merely starts with the word keeps it.
        assertThat(VodTitleCleaner.clean("Max Payne (2008)")).isEqualTo("Max Payne (2008)")
        assertThat(VodTitleCleaner.clean("4K-EN - The Matrix  (1999)")).isEqualTo("The Matrix (1999)")
    }

    @Test
    fun `episode titles drop tags, show name and SxxEyy`() {
        assertThat(episodeDisplayTitle("4K-A+ - Sago Mini Friends - S01E01 - Pizza Please", "A+ - Sago Mini Friends"))
            .isEqualTo("Pizza Please")
        assertThat(episodeDisplayTitle("The Office - S02E03 - Office Olympics", "The Office (2005)"))
            .isEqualTo("Office Olympics")
        // Nothing left after peeling: keep the cleaned title rather than an empty row.
        assertThat(episodeDisplayTitle("EN - The Office", "The Office")).isEqualTo("The Office")
    }

    @Test
    fun `hyphen-joined tag groups are peeled, look-alike titles are not`() {
        assertThat(VodTitleCleaner.clean("SO-IN - KJQ (2026)")).isEqualTo("KJQ (2026)")
        assertThat(VodTitleCleaner.prefixTags("SO-IN - KJQ (2026)")).containsExactly("SO", "IN").inOrder()
        assertThat(VodTitleCleaner.clean("AR-IN-S - Some Film")).isEqualTo("Some Film")
        assertThat(VodTitleCleaner.clean("KU-S - Some Film")).isEqualTo("Some Film")
        assertThat(VodTitleCleaner.clean("SC - Some Film")).isEqualTo("Some Film")
        assertThat(VodTitleCleaner.clean("X-MEN - Days of Future Past")).isEqualTo("X-MEN - Days of Future Past")
        assertThat(VodTitleCleaner.clean("Spider-Man - Homecoming")).isEqualTo("Spider-Man - Homecoming")
    }

    @Test
    fun `a title with no provider prefix has no tags`() {
        assertThat(VodTitleCleaner.prefixTags("The Matrix (1999)")).isEmpty()
    }
}
