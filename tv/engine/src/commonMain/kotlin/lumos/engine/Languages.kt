package lumos.engine

/** Subtitle languages as addons write them (ISO 639-2 "eng", 639-1 "en", or a name), to compare and to show */
object Languages {
    private val NAMES = mapOf(
        "eng" to "English", "spa" to "Spanish", "fre" to "French", "fra" to "French", "ger" to "German", "deu" to "German",
        "ita" to "Italian", "por" to "Portuguese", "pob" to "Portuguese (Brazil)", "dut" to "Dutch", "nld" to "Dutch",
        "pol" to "Polish", "rus" to "Russian", "ukr" to "Ukrainian", "swe" to "Swedish", "nor" to "Norwegian", "dan" to "Danish",
        "fin" to "Finnish", "jpn" to "Japanese", "kor" to "Korean", "chi" to "Chinese", "zho" to "Chinese", "hin" to "Hindi",
        "tur" to "Turkish", "gre" to "Greek", "ell" to "Greek", "cze" to "Czech", "ces" to "Czech", "hun" to "Hungarian",
        "rum" to "Romanian", "ron" to "Romanian", "heb" to "Hebrew", "ara" to "Arabic", "tha" to "Thai", "vie" to "Vietnamese",
        "ind" to "Indonesian", "may" to "Malay", "msa" to "Malay", "bul" to "Bulgarian", "hrv" to "Croatian", "srp" to "Serbian",
        "slv" to "Slovenian", "slo" to "Slovak", "slk" to "Slovak", "est" to "Estonian", "lav" to "Latvian", "lit" to "Lithuanian",
        "per" to "Persian", "fas" to "Persian", "ice" to "Icelandic", "isl" to "Icelandic",
    )
    private val TWO = mapOf(
        "en" to "eng", "es" to "spa", "fr" to "fre", "de" to "ger", "it" to "ita", "pt" to "por", "nl" to "dut", "pl" to "pol",
        "ru" to "rus", "uk" to "ukr", "sv" to "swe", "no" to "nor", "da" to "dan", "fi" to "fin", "ja" to "jpn", "ko" to "kor",
        "zh" to "chi", "hi" to "hin", "tr" to "tur", "el" to "gre", "cs" to "cze", "hu" to "hun", "ro" to "rum", "he" to "heb",
        "ar" to "ara", "th" to "tha", "vi" to "vie", "id" to "ind",
    )

    private fun norm(x: String): String {
        val l = x.trim().lowercase()
        if (l == "pt-br" || l == "pb") return "pob"
        TWO[l]?.let { return it }
        NAMES.entries.firstOrNull { it.value.lowercase() == l }?.let { return it.key }
        return l
    }

    fun same(a: String, b: String): Boolean = name(a).lowercase() == name(b).lowercase()

    fun name(code: String): String = NAMES[norm(code)] ?: code.ifEmpty { "Unknown" }
}
