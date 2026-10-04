/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.parser

import java.text.Normalizer
import java.util.Locale

/**
 * Source-independent Turkish live-TV ordering.
 *
 * The first 30 positions are OpenTV's curated Turkey order. Everything after that follows the
 * Kablo TV line-up order, skipping channels already present in the curated block. Unknown channels
 * are intentionally left unranked so callers can append them deterministically after the known list.
 *
 * This class does not depend on a source id or provider channel number: IPTV-ORG, a future M3U and
 * optional supplemental sources all meet on the same canonical channel key.
 */
object TurkeyChannelPlan {
    val first30: List<String> = listOf(
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
        "TRT Çocuk",
        "TRT Belgesel",
        "A2",
        "Teve2",
        "CNBC-e",
        "TV100",
        "TLC",
        "DMAX",
        "TRT 2",
        "TRT Haber",
        "NTV",
        "CNN Türk",
        "Habertürk",
        "A Haber",
        "Haber Global",
        "Sözcü TV",
        "Halk TV",
        "TRT Spor",
        "A Spor",
        "HT Spor",
    )

    /**
     * The sports block shown immediately after the curated first 30.
     *
     * The first ten slots are intentionally fixed to the order agreed for OpenTV:
     * 31 beIN Sports 1 ... 40 Tivibu Spor 2. Supplemental sports channels follow after those,
     * before the regular Kablo continuation. Backup feeds (-A/-B/-C, SD, Alternatif) never get
     * their own slot; [canonicalKey] folds them into the visible channel.
     */
    val sportsAfterFirst30: List<String> = listOf(
        "beIN Sports 1",
        "beIN Sports 2",
        "beIN Sports 3",
        "beIN Sports 4",
        "beIN Sports Max 1",
        "beIN Sports Max 2",
        "S Sport",
        "S Sport 2",
        "Tivibu Spor",
        "Tivibu Spor 2",
        "beIN Sports 5",
        "S Sport Plus",
        "Tivibu Spor 1",
        "Tivibu Spor 3",
        "Tivibu Spor 4",
        "Exxen Sports 1",
        "Exxen Sports 2",
        "Exxen Sports 3",
        "Exxen Sports 4",
        "Exxen Sports 5",
        "Exxen Sports 6",
        "Exxen Sports 7",
        "Exxen Sports 8",
        "Tabii Spor",
        "Tabii Spor 1",
        "Tabii Spor 2",
        "Tabii Spor 3",
        "Tabii Spor 4",
        "Tabii Spor 5",
        "Tabii Spor 6",
        "Tabii Spor 7",
        "Tabii Spor 8",
        "Smart Spor 1",
        "Smart Spor 2",
        "Eurosport 1",
        "Eurosport 2",
        "NBA TV",
        "Sports TV",
        "TJK TV",
        "FB TV",
        "GS TV",
        "CBC Sport",
    )

    /**
     * Kablo TV order (national line-up, HD entries preferred where the same channel also has an SD
     * mirror). The first-30 block above always wins; duplicates are removed when [orderedNames] is
     * built. Local 888/889 slots are omitted because they mean different channels by city.
     */
    private val kabloOrder: List<String> = listOf(
        "Kablo Info",
        "Sinema TV",
        "Sinema 2",
        "Sinema Aksiyon",
        "Sinema Aksiyon 2",
        "Sinema Yerli",
        "Sinema Yerli 2",
        "Sinema Aile",
        "Sinema Aile 2",
        "Sinema 1001",
        "Sinema 1002",
        "Sinema Komedi",
        "Sinema Komedi 2",
        "TRT 1",
        "ATV",
        "Show TV",
        "Kanal D",
        "Star TV",
        "Kanal 7",
        "NOW",
        "TV8",
        "Beyaz TV",
        "A2",
        "Teve2",
        "360",
        "TLC",
        "DMAX",
        "Kon TV",
        "TV100",
        "CNBC-e",
        "Ulusal Kanal",
        "Tivi 6",
        "TV8,5",
        "BEA",
        "Kanal B",
        "Genç TV",
        "Vav TV",
        "Üniversite TV",
        "TMB",
        "TV 4",
        "Bengü Türk TV",
        "TRT 2",
        "Tarih TV",
        "TRT Haber",
        "A Haber",
        "NTV",
        "CNN Türk",
        "24",
        "TGRT Haber",
        "Habertürk",
        "Bloomberg HT",
        "Ülke TV",
        "TVNET",
        "Akit TV",
        "BRT 1",
        "TRT Türk",
        "TRT Avaz",
        "TRT 3 Spor - TBMM TV",
        "TRT Kurdî",
        "Haber Global",
        "BBN Türk",
        "KRT TV",
        "Halk TV",
        "TH Türk Haber",
        "Sözcü TV",
        "A Para",
        "Ekotürk",
        "MEK",
        "TV 5",
        "TRT World",
        "A News",
        "BBC World News",
        "Bloomberg",
        "Al Jazeera Arapça",
        "Al Jazeera İngilizce",
        "Alaraby News",
        "TRT Arabi",
        "DW TV İngilizce",
        "DW TV Almanca",
        "France 24 İngilizce",
        "France 24 Fransızca",
        "CGTN",
        "Russia 24",
        "TRT Belgesel",
        "National Geographic",
        "National Geographic Wild",
        "Viasat Nature",
        "Discovery Channel",
        "Discovery Science",
        "ID",
        "History",
        "Viasat History",
        "DocuBOX",
        "BBC Earth",
        "Viasat Explore",
        "RTG Int",
        "CGTN Documentary",
        "Az TV",
        "Türkmeneli TV",
        "BBC Entertainment",
        "TV5 Monde Europe",
        "CCTV 4",
        "Saudi TV",
        "Hayat Plus",
        "Planeta RTR",
        "KBS World",
        "NHK World",
        "Arirang",
        "Freedom",
        "TRT Spor",
        "beIN SPORTS HABER",
        "A Spor",
        "Eurosport 1",
        "Eurosport 2",
        "Trace Sport Stars",
        "FightBOX",
        "FAST & FUNBOX",
        "S Sport",
        "S Sport 2",
        "NBA TV",
        "EDGEsport",
        "TRT Spor Yıldız",
        "FB TV",
        "FX",
        "BBC First",
        "FilmBOX TURKEY",
        "FilmBOX Extra",
        "FilmBOX Arthouse",
        "24 Kitchen",
        "MyZEN TV",
        "FunBOX",
        "Gametoon",
        "TRT Çocuk",
        "Minika Çocuk",
        "Minika Go",
        "BabyTV",
        "Disney Junior",
        "Cartoon Network",
        "Duck TV",
        "English Club TV",
        "Da Vinci",
        "TRT EBA TV İlkokul",
        "TRT EBA TV Ortaokul",
        "TRT EBA TV Lise",
        "Diyanet TV",
        "Al Quran Al Kareem",
        "Al Sunnah Al Nabawiya",
        "TRT Müzik",
        "Dream Türk",
        "360 TuneBOX",
        "Trace Urban",
    )

    private val combiningMarks = Regex("\\p{M}+")
    private val backupLetterSuffix = Regex("""\s*-\s*[ABC]$""", RegexOption.IGNORE_CASE)
    private val backupLetterBeforeQualitySuffix = Regex(
        """\s*-\s*[ABC](?=\s*(?:\([^)]*\))?\s*$)""",
        RegexOption.IGNORE_CASE,
    )
    private val alternativeSuffix = Regex("""\s+Alternatif$""", RegexOption.IGNORE_CASE)
    private val sSportPlusName = Regex(
        """(?:^|[^A-Za-z0-9])S\s*Sport\s+Plus(?:$|[^A-Za-z0-9])""",
        RegexOption.IGNORE_CASE,
    )

    /** Keep provider backup-feed labels out of the logical channel identity. */
    private fun collapseBackupSuffix(raw: String): String = backupLetterSuffix.replace(raw, "").trim()

    /**
     * Backup markers can sit before a quality suffix: "NOW-A (480p)". Strip that marker before the
     * generic normalizer sees the name; otherwise a short channel name can be reduced incorrectly.
     */
    private fun collapseBackupMarkerBeforeNormalize(raw: String): String =
        backupLetterBeforeQualitySuffix.replace(raw, "").trim()

    private fun plainKey(raw: String): String {
        val tr = raw.lowercase(Locale.forLanguageTag("tr-TR"))
            .replace('ı', 'i')
            .replace('ğ', 'g')
            .replace('ü', 'u')
            .replace('ş', 's')
            .replace('ö', 'o')
            .replace('ç', 'c')
        val ascii = Normalizer.normalize(tr, Normalizer.Form.NFD)
            .replace(combiningMarks, "")
        return ChannelNameNormalizer.groupKeyOf(ascii)
    }

    private val aliases: Map<String, String> = buildMap {
        fun alias(from: String, to: String) { put(plainKey(from), plainKey(to)) }

        alias("TRT1", "TRT 1")
        alias("KanalD", "Kanal D")
        alias("Show", "Show TV")
        alias("Star", "Star TV")
        alias("Fox", "NOW")
        alias("Fox TV", "NOW")
        alias("Now TV", "NOW")
        alias("TV 8", "TV8")
        alias("Kanal 7 Avrupa", "Kanal 7")
        alias("Kanal7 Avrupa", "Kanal 7")
        alias("Beyaz", "Beyaz TV")
        alias("TRT Cocuk", "TRT Çocuk")
        alias("A 2", "A2")
        alias("Teve 2", "Teve2")
        alias("CNBC E", "CNBC-e")
        alias("TV 100", "TV100")
        alias("TRT2", "TRT 2")
        alias("Haberturk TV", "Habertürk")
        alias("Haberturk", "Habertürk")
        alias("Sozcu", "Sözcü TV")
        alias("Sozcu TV", "Sözcü TV")
        alias("SZC", "Sözcü TV")
        alias("SZC TV", "Sözcü TV")
        alias("Ahaber", "A Haber")
        alias("HaberGlobal", "Haber Global")
        alias("HalkTV", "Halk TV")
        alias("TRTSPOR", "TRT Spor")
        alias("ASPOR", "A Spor")
        alias("HTSPOR", "HT Spor")
        alias("TRT Spor Yildiz", "TRT Spor Yıldız")
        alias("TRT Spor 2", "TRT Spor Yıldız")
        alias("Tabii Spor Alternatif", "Tabii Spor")
        alias("TVNET", "TVNET")
        alias("New Akit", "Akit TV")
    }

    /** Canonical source-independent identity for a Turkish channel name. */
    fun canonicalKey(raw: String): String {
        // "Plus" is normally a stream/codec marker in the generic normalizer, but S Sport Plus
        // is the actual channel name. Preserve that one named Turkish service before generic
        // quality cleanup so it never collapses into S Sport.
        val base = if (sSportPlusName.containsMatchIn(raw)) {
            "S Sport Plus"
        } else {
            val withoutBackupMarker = collapseBackupMarkerBeforeNormalize(raw)
            collapseBackupSuffix(ChannelNameNormalizer.normalize(withoutBackupMarker).baseName)
        }
        val key = plainKey(base)
        return aliases[key] ?: key
    }

    /**
     * Presentation/playback preference inside one sports channel group.
     *
     * 0 = provider's main feed, 1 = explicit quality mirror (for example SD), 2 = named backup
     * (-A/-B/-C or Alternatif). Non-sports channels always return 0 so their existing
     * best-quality-first behaviour is untouched.
     */
    fun fallbackPriority(raw: String): Int {
        val normalized = ChannelNameNormalizer.normalize(raw)
        if (canonicalKey(raw) !in sportsKeys) return 0
        if (sSportPlusName.containsMatchIn(raw)) return 0
        val base = normalized.baseName
        return when {
            backupLetterSuffix.containsMatchIn(base) || alternativeSuffix.containsMatchIn(base) -> 2
            normalized.qualityLabel.isNotEmpty() -> 1
            else -> 0
        }
    }

    /**
     * Playback source trust order for managed Turkey channels.
     *
     * Official web-player streams are preferred, ordinary provider/IPTV-ORG rows are next, and
     * OpenTV's curated fallback mirrors are last. Ordinary user providers never use these prefixes,
     * so their historical ordering is unchanged.
     */
    fun playbackSourcePriority(streamId: String): Int = when {
        streamId.startsWith("opentv-official:") -> 0
        streamId.startsWith("opentv-official-backup:") -> 1
        streamId.startsWith("opentv-fallback:") -> 3
        else -> 2
    }

    /** The canonical on-screen name for channels OpenTV knows, otherwise null. */
    fun preferredDisplayName(raw: String): String? = preferredNames[canonicalKey(raw)]

    /** 0-based Turkey order, or null for a channel not present in the curated/Kablo maps. */
    fun orderIndex(raw: String): Int? = orderByKey[canonicalKey(raw)]

    private val orderedNames: List<String> = buildList {
        addAll(first30)
        addAll(sportsAfterFirst30)
        addAll(kabloOrder)
    }.distinctBy(::canonicalKey)

    private val sportsKeys: Set<String> =
        sportsAfterFirst30.map { canonicalKey(it) }.toSet()

    private val orderByKey: Map<String, Int> =
        orderedNames.mapIndexed { index, name -> canonicalKey(name) to index }.toMap()

    private val preferredNames: Map<String, String> =
        orderedNames.associateBy(::canonicalKey)
}
