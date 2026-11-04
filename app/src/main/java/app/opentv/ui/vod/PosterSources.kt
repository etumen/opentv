package app.opentv.ui.vod

/** Try each real provider artwork URL once; avoid expired/blank/duplicate images. */
internal fun posterCandidates(primary: String?, alternatives: List<String>): List<String> =
    (listOfNotNull(primary) + alternatives)
        .map(String::trim)
        .filter { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) }
        .distinct()
