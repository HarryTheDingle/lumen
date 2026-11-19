package lumos.engine

/**
 * What a stream is, read from what its addon wrote: Lumen's describeStream (dxbi.plugin.js), ported. The same
 * fields Lumen's stream list and Just press Play use.
 */
data class Described(
    val provider: String,
    val site: String,
    val codec: String,
    val instant: Boolean,
    val pending: Boolean,
    val lesser: Boolean,
    val upscaled: Boolean,
    val sample: Boolean,
    val res: Int,
    val hdr: Boolean,
    val dv: Boolean,
    val imax: Boolean,
    val atmos: Boolean,
    val hdr10plus: Boolean,
    val openMatte: Boolean,
    val threeD: Boolean,
    val size: Double,
    val seeds: Int,
    val source: String,
    val langs: List<String>,
    /**
     * Not in English: it names its languages and none is English (multi and dual audio count as having it), or its name
     * is mostly in another script (a Russian or Chinese release). Left out of the list when there's anything else
     */
    val foreign: Boolean = false,
    /** A version other than the film as released: "Extended", "Director's Cut", "Encore"... (null: the standard one) */
    val edition: String? = null,
)

object Describe {
    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)
    private fun Regex.on(s: String) = containsMatchIn(s)

    private val FLAG_LANG = mapOf(
        "GB" to "English", "US" to "English", "AU" to "English", "CA" to "English", "IE" to "English", "NZ" to "English",
        "IT" to "Italian", "ES" to "Spanish", "MX" to "Spanish (Latino)", "AR" to "Spanish (Latino)", "CO" to "Spanish (Latino)",
        "CL" to "Spanish (Latino)", "FR" to "French", "BE" to "French", "DE" to "German", "AT" to "German", "CH" to "German",
        "RU" to "Russian", "UA" to "Ukrainian", "PT" to "Portuguese", "BR" to "Portuguese (Brazil)", "NL" to "Dutch",
        "PL" to "Polish", "SE" to "Swedish", "NO" to "Norwegian", "DK" to "Danish", "FI" to "Finnish", "JP" to "Japanese",
        "KR" to "Korean", "CN" to "Chinese", "TW" to "Chinese", "IN" to "Hindi", "TR" to "Turkish", "GR" to "Greek",
        "CZ" to "Czech", "HU" to "Hungarian", "RO" to "Romanian", "IL" to "Hebrew", "SA" to "Arabic", "TH" to "Thai",
        "VN" to "Vietnamese", "ID" to "Indonesian",
    )
    private val LANG_WORDS = listOf(
        re("""\bmulti(?:[ .-]?audio|[ .-]?lang)?\b""") to "Multi audio",
        re("""\bdual[ .-]?audio\b""") to "Dual audio",
        re("""\benglish\b""") to "English", re("""\bitalian[oa]?\b""") to "Italian",
        re("""\b(?:french|truefrench|vff|vfq)\b""") to "French", re("""\bgerman\b""") to "German",
        re("""\bspanish|castellano\b""") to "Spanish", re("""\blatino\b""") to "Spanish (Latino)",
        re("""\bhindi\b""") to "Hindi", re("""\brussian\b""") to "Russian", re("""\bjapanese\b""") to "Japanese",
        re("""\bkorean\b""") to "Korean", re("""\bportuguese\b""") to "Portuguese",
    )
    const val NOT_LISTED = "Not listed (usually English)"

    private val Q4K = re("""\b(?:2160p|4k|uhd)\b"""); private val Q1440 = re("""\b1440p\b"""); private val Q1080 = re("""\b(?:1080p|fhd)\b""")
    private val Q720 = re("""\b720p\b"""); private val QSD = re("""\b(?:576p|480p|360p|sd|dvdrip|xvid)\b""")
    private val UPSCALED = listOf(
        re("""\bai[ ._+-]*(?:up[ ._-]?)?(?:scal\w*|sampl\w*|enhanc\w*|remaster\w*|restor\w*|sharpen\w*|boost\w*|improv\w*|interpolat\w*|upres\w*|4k|8k|uhd|2160p|1080p)"""),
        re("""\b(?:upscal|upsampl|upres|up[ ._-]?res|enhanc|remaster|restor|sharpen|interpolat)\w*[ ._-]*(?:by|with|using|via|\()?[ ._-]*ai\b(?![ ._-]*artificial)"""),
        re("""\b(?:2160p|4k|uhd|1440p|1080p|8k)[ ._-]*ai\b(?![ ._-]*artificial)"""),
        re("""[\[(]\s*ai\s*[\])]"""),
        re("""\b(?:real[ ._-]?)?esrgan\b|\bwaifu2x\b|\breal[ ._-]?cugan\b|\bgigapixel\b|\btvai\b|\bvideo[ ._-]?ai\b|\btopaz[ ._-]?(?:video|ai|labs|enhance|gigapixel)\w*|\bml[ ._-]?(?:upscal|enhanc)\w*|\bneural[ ._-]?(?:upscal|enhanc|net)\w*|\bsuper[ ._-]?res(?:olution)?\b"""),
        re("""\b(?:upscal(?:e|ed|ing)|upsampl(?:e|ed|ing))\b"""),
    )

    private fun qualities(text: String) = listOfNotNull(
        2160.takeIf { Q4K.on(text) }, 1440.takeIf { Q1440.on(text) }, 1080.takeIf { Q1080.on(text) },
        720.takeIf { Q720.on(text) }, 480.takeIf { QSD.on(text) },
    )

    /** [name]: the addon's name line(s); [all]: the name and everything else it wrote (title, description, file name) */
    fun stream(name: String, all: String): Described {
        val firstLine = name.lineSequence().firstOrNull()?.trim().orEmpty().ifEmpty { "Unknown" }
        val bracket = Regex("""^\s*\[([^\]]+)\]""").find(firstLine)?.groupValues?.get(1)
        val provider = firstLine.replace(Regex("""^\s*\[[^\]]*\]\s*"""), "").trim().ifEmpty { firstLine }
        val instant = (bracket != null && re("""\+|⚡|instant|cached""").on(bracket)) || re("""⚡|\binstant\b|\bcached\b""").on(firstLine)
        val pending = !instant && re("""\[(?:RD|AD|PM|TB|DL|ED|OC)\b[^\]]*\]""").on(firstLine)

        val rest = if (all.startsWith(name)) all.substring(name.length) else all
        val said = qualities(rest).ifEmpty { qualities(name) }
        val res = said.minOrNull() ?: 0

        val dv = re("""\b(?:dv|dovi|dolby[ .-]?vision)\b""").on(all)
        val hdrNamed = re("""\bhdr(?:10\+?)?\b""").on(all) || re("""\bhdr10(?:\+|[ ._-]?plus)""").on(all)

        var size = 0.0
        re("""(\d+(?:[.,]\d+)?)\s*(TiB|GiB|MiB|TB|GB|MB)\b""").find(all)?.let { m ->
            val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0
            size = n * when (m.groupValues[2].uppercase()) {
                "TIB" -> 1_099_511_627_776.0; "GIB" -> 1_073_741_824.0; "MIB" -> 1_048_576.0; "TB" -> 1e12; "GB" -> 1e9; else -> 1e6
            }
        }
        val seeds = (Regex("""👤\s*(\d+)""").find(all) ?: Regex("""🌱\s*(\d+)""").find(all)
            ?: re("""\bseed(?:er)?s?\W{0,3}(\d+)""").find(all) ?: re("""(\d+)\s*seed""").find(all))?.groupValues?.get(1)?.toIntOrNull() ?: -1

        val source = when {
            re("""\bremux\b""").on(all) -> "Remux"
            re("""\b(?:blu-?ray|bdrip|brrip|bdremux|bd25|bd50)\b""").on(all) -> "BluRay"
            re("""\bweb[ .-]?(?:dl|rip)?\b""").on(all) -> "WEB"
            re("""\bhdtv\b""").on(all) -> "HDTV"
            // (cinema recordings and screeners: never played or listed)
            re("""\b(?:cam|hdcam|camrip|telesync|hdts|ts|tc|hdtc|telecine|(?:dvd|bd|web)?scr|screener|pdvd|predvd)\b""").on(all) -> "CAM"
            else -> "Other"
        }

        val langs = linkedSetOf<String>()
        // flags: two regional indicator letters
        val cps = all.codes()
        for (i in 0 until cps.size - 1) {
            val a = cps[i]; val b = cps[i + 1]
            if (a in 0x1F1E6..0x1F1FF && b in 0x1F1E6..0x1F1FF) {
                val code = "${'A' + (a - 0x1F1E6)}${'A' + (b - 0x1F1E6)}"
                langs += FLAG_LANG[code] ?: code
            }
        }
        for ((r, label) in LANG_WORDS) if (r.on(all)) langs += label
        if (langs.isEmpty()) langs += NOT_LISTED

        val site = Regex("""(?:⚙️?|🔎)\s*([^\s\n]+)""").find(all)?.groupValues?.get(1).orEmpty()
        val codec = when {
            re("""\b(?:x265|h\.?265|hevc)\b""").on(all) -> "HEVC"
            re("""\bav1\b""").on(all) -> "AV1"
            re("""\b(?:x264|h\.?264|avc)\b""").on(all) -> "H.264"
            else -> ""
        }
        return Described(
            provider = provider, site = site, codec = codec, instant = instant, pending = pending,
            lesser = re("""\b(?:ai[ ._-]?upscal\w*|upscal\w*|\d{2,3}fps|hfr|3d|h?sbs)\b""").on(all),
            upscaled = UPSCALED.any { it.on(all) }, sample = re("""\bsample\b""").on(all),
            res = res, hdr = dv || hdrNamed, dv = dv, imax = re("""\bimax\b""").on(all), atmos = re("""\batmos\b""").on(all),
            hdr10plus = re("""\bhdr10(?:\+|[ ._-]?plus)""").on(all), openMatte = re("""\bopen[ ._-]?matte\b""").on(all),
            threeD = re("""\b(?:3d|h?sbs)\b""").on(all), size = size, seeds = seeds, source = source, langs = langs.toList(),
            foreign = foreignIn(langs, all),
            edition = EDITIONS.firstOrNull { (r, _) -> r.on(all) }?.second,
        )
    }

    /** Versions other than the one released (Avengers: Endgame's Encore re-release, Extended cuts...), first match wins */
    private val EDITIONS = listOf(
        re("""\bencore\b""") to "Encore",
        re("""\bdirector'?s[ ._-]?cut\b|\bdc\b(?=.*\b(?:2160p|1080p|720p)\b)""") to "Director's Cut",
        re("""\bextended(?:[ ._-]?(?:cut|edition|version))?\b""") to "Extended",
        re("""\b(?:ultimate|special|collector'?s|anniversary)[ ._-]?(?:cut|edition)\b""") to "Special Edition",
        re("""\bfinal[ ._-]?cut\b""") to "Final Cut",
        re("""\bredux\b""") to "Redux",
        re("""\bunrated\b|\buncut\b""") to "Unrated",
        re("""\bblack[ ._-]?(?:and|&|n)[ ._-]?(?:white|chrome)\b|\bnoir[ ._-]?edition\b|\b(?:logan|batman)[ ._-]?noir\b""") to "Black & White",
    )

    private val ENGLISH_ENOUGH = setOf("English", "Multi audio", "Dual audio", NOT_LISTED)

    private fun foreignIn(langs: Set<String>, text: String): Boolean {
        if (langs.none { it in ENGLISH_ENOUGH }) return true
        // (written in another script: more of its letters are Cyrillic, Greek, Arabic, Hebrew, Indic, Thai or CJK than Latin)
        var latin = 0
        var other = 0
        for (c in text.codes()) {
            when {
                c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code -> latin++
                c in 0x0370..0x03FF || c in 0x0400..0x052F || c in 0x0590..0x06FF || c in 0x0900..0x0DFF || c in 0x0E00..0x0E7F ||
                    c in 0x3040..0x30FF || c in 0x3400..0x9FFF || c in 0xAC00..0xD7AF -> other++
            }
        }
        return other >= 4 && other > latin
    }

    /** Code points of a string, in common Kotlin (no java.lang.String.codePoints) */
    private fun String.codes(): IntArray {
        val out = ArrayList<Int>(length)
        var i = 0
        while (i < length) {
            val c = this[i]
            if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
                out += ((c.code - 0xD800) shl 10) + (this[i + 1].code - 0xDC00) + 0x10000
                i += 2
            } else {
                out += c.code; i++
            }
        }
        return out.toIntArray()
    }
}
