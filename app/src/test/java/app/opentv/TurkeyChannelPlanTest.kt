/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.parser.TurkeyChannelPlan
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TurkeyChannelPlanTest {

    @Test
    fun `approved first thirty order stays fixed`() {
        assertThat(TurkeyChannelPlan.first30).hasSize(30)
        assertThat(TurkeyChannelPlan.first30.take(10)).containsExactly(
            "TRT 1",
            "Kanal D",
            "ATV",
            "Show TV",
            "Star TV",
            "NOW",
            "TV8",
            "Kanal 7",
            "360",
            "Beyaz TV",
        ).inOrder()
        assertThat(TurkeyChannelPlan.first30[10]).isEqualTo("TRT Çocuk")
        assertThat(TurkeyChannelPlan.first30[11]).isEqualTo("TRT Belgesel")
        assertThat(TurkeyChannelPlan.first30[29]).isEqualTo("HT Spor")
    }

    @Test
    fun `old and dirty source names resolve to the same canonical Turkey slots`() {
        assertThat(TurkeyChannelPlan.orderIndex("FOX TV"))
            .isEqualTo(TurkeyChannelPlan.orderIndex("NOW"))
        assertThat(TurkeyChannelPlan.preferredDisplayName("Kanal 7 Avrupa Not 24 7"))
            .isEqualTo("Kanal 7")
        assertThat(TurkeyChannelPlan.preferredDisplayName("TRT Spor Yildiz 1440p Geo-blocked"))
            .isEqualTo("TRT Spor Yıldız")
    }

    @Test
    fun `sports block owns channels 31 through 40 in the agreed order`() {
        assertThat(TurkeyChannelPlan.orderIndex("beIN Sports 1")).isEqualTo(30)
        assertThat(TurkeyChannelPlan.orderIndex("beIN Sports 2")).isEqualTo(31)
        assertThat(TurkeyChannelPlan.orderIndex("beIN Sports 3")).isEqualTo(32)
        assertThat(TurkeyChannelPlan.orderIndex("beIN Sports 4")).isEqualTo(33)
        assertThat(TurkeyChannelPlan.orderIndex("beIN Sports Max-1")).isEqualTo(34)
        assertThat(TurkeyChannelPlan.orderIndex("beIN Sports Max-2")).isEqualTo(35)
        assertThat(TurkeyChannelPlan.orderIndex("S Sport")).isEqualTo(36)
        assertThat(TurkeyChannelPlan.orderIndex("S Sport 2")).isEqualTo(37)
        assertThat(TurkeyChannelPlan.orderIndex("Tivibu Spor")).isEqualTo(38)
        assertThat(TurkeyChannelPlan.orderIndex("Tivibu Spor 2")).isEqualTo(39)
    }

    @Test
    fun `sports backup feeds fold into one visible channel but keep fallback priority`() {
        assertThat(TurkeyChannelPlan.canonicalKey("BeIN Sports 1-A"))
            .isEqualTo(TurkeyChannelPlan.canonicalKey("beIN Sports 1"))
        assertThat(TurkeyChannelPlan.canonicalKey("BeIN Sports 1-B"))
            .isEqualTo(TurkeyChannelPlan.canonicalKey("beIN Sports 1"))
        assertThat(TurkeyChannelPlan.canonicalKey("BeIN Sports 1-C"))
            .isEqualTo(TurkeyChannelPlan.canonicalKey("beIN Sports 1"))
        assertThat(TurkeyChannelPlan.preferredDisplayName("BeIN Sports 1-B"))
            .isEqualTo("beIN Sports 1")

        assertThat(TurkeyChannelPlan.fallbackPriority("BeIN Sports 1")).isEqualTo(0)
        assertThat(TurkeyChannelPlan.fallbackPriority("BeIN Sports 1 SD")).isEqualTo(1)
        assertThat(TurkeyChannelPlan.fallbackPriority("BeIN Sports 1-A")).isEqualTo(2)
        assertThat(TurkeyChannelPlan.canonicalKey("S Sport-A"))
            .isEqualTo(TurkeyChannelPlan.canonicalKey("S Sport"))
        assertThat(TurkeyChannelPlan.canonicalKey("S Sport Plus"))
            .isNotEqualTo(TurkeyChannelPlan.canonicalKey("S Sport"))
        assertThat(TurkeyChannelPlan.preferredDisplayName("S Sport Plus"))
            .isEqualTo("S Sport Plus")
        assertThat(TurkeyChannelPlan.fallbackPriority("S Sport Plus")).isEqualTo(0)
        assertThat(TurkeyChannelPlan.canonicalKey("Tabii Spor Alternatif"))
            .isEqualTo(TurkeyChannelPlan.canonicalKey("Tabii Spor"))
    }

    @Test
    fun `national fallback suffixes fold into the normal visible channel`() {
        assertThat(TurkeyChannelPlan.canonicalKey("Show TV-A (720p)"))
            .isEqualTo(TurkeyChannelPlan.canonicalKey("Show TV"))
        assertThat(TurkeyChannelPlan.preferredDisplayName("Show TV-A (720p)"))
            .isEqualTo("Show TV")

        assertThat(TurkeyChannelPlan.canonicalKey("NOW-A (480p)"))
            .isEqualTo(TurkeyChannelPlan.canonicalKey("NOW"))
        assertThat(TurkeyChannelPlan.preferredDisplayName("NOW-B (360p)"))
            .isEqualTo("NOW")

        assertThat(TurkeyChannelPlan.canonicalKey("Kanal D-A (720p)"))
            .isEqualTo(TurkeyChannelPlan.canonicalKey("Kanal D"))
        assertThat(TurkeyChannelPlan.preferredDisplayName("Kanal D-B (480p)"))
            .isEqualTo("Kanal D")
    }

    @Test
    fun `managed Turkey playback prefers official source then provider then fallback`() {
        assertThat(TurkeyChannelPlan.playbackSourcePriority("opentv-official:now:web")).isEqualTo(0)
        assertThat(TurkeyChannelPlan.playbackSourcePriority("opentv-official-backup:kanald:duhnet")).isEqualTo(1)
        assertThat(TurkeyChannelPlan.playbackSourcePriority("tvg:NOWTV.tr@SD")).isEqualTo(2)
        assertThat(TurkeyChannelPlan.playbackSourcePriority("opentv-fallback:now:a")).isEqualTo(3)
    }

    @Test
    fun `kablo continuation starts after sports block without duplicates`() {
        val lastSport = TurkeyChannelPlan.orderIndex("CBC Sport")
        val kabloInfo = TurkeyChannelPlan.orderIndex("Kablo Info")
        val sinemaTv = TurkeyChannelPlan.orderIndex("Sinema TV")

        assertThat(lastSport).isNotNull()
        assertThat(kabloInfo).isNotNull()
        assertThat(sinemaTv).isNotNull()
        assertThat(kabloInfo!!).isGreaterThan(lastSport!!)
        assertThat(sinemaTv!!).isGreaterThan(kabloInfo)
        assertThat(TurkeyChannelPlan.orderIndex("TRT 1")).isEqualTo(0)
    }
}
