package lumos.engine

/**
 * Whether a stream an addon listed is really for the video asked about. Addons are asked by id, so nearly all are;
 * a search-based one (Easynews and the like) sometimes lists another episode, or a film of the same name from
 * another year. Only what the stream's own name says against it counts: a name that says nothing is kept.
 */
object Matching {
    private val EPISODE = Regex("""(?i)\bS(\d{1,2})[ ._-]?E(\d{1,3})(?:[ ._-]?-?[ ._-]?E?(\d{1,3}))?\b""")
    private val CROSS = Regex("""(?i)\b(\d{1,2})x(\d{2,3})(?:\s*-\s*(\d{2,3}))?\b""")
    private val YEAR = Regex("""(?<![\d])(19[2-9]\d|20[0-4]\d)(?![\d])""")

    /** [videoId]: a film's id, or an episode's (`tt0944947:1:2`, `kitsu:1376:5`); [year]: the title's, when known */
    fun fits(stream: Stream, videoId: String, year: Int?): Boolean {
        val text = listOfNotNull(stream.filename, stream.title, stream.name).joinToString("\n")
        val parts = videoId.split(":")
        val season = parts.getOrNull(parts.size - 2)?.toIntOrNull()
        val episode = parts.lastOrNull()?.toIntOrNull()
        val isEpisode = parts.size >= 3 && season != null && episode != null
        if (isEpisode) {
            // (anime counts episodes across seasons: only the episode number is compared there)
            val anime = videoId.startsWith("kitsu:")
            val named = EPISODE.findAll(text).map { m ->
                Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toIntOrNull())
            }.toList() + CROSS.findAll(text).map { m -> Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toIntOrNull()) }
            if (named.isEmpty()) return true
            return named.any { (s, e, to) ->
                (anime || s == season) && (episode == e || (to != null && to > e && episode in e..to))
            }
        }
        // A film: one naming only other years (more than a year out) is another film of the same name
        if (year != null && parts.size < 3) {
            val years = YEAR.findAll(stream.filename ?: stream.title ?: "").map { it.value.toInt() }.toList()
            if (years.isNotEmpty() && years.none { kotlin.math.abs(it - year) <= 1 }) return false
        }
        return true
    }

    /**
     * A search-based addon (Easynews) finds by name, so it lists other shows and films called the same or nearly: Dark
     * Matter (2015) for Dark Matter (2024). Its release must be named as the title, word for word (a year or country
     * after it allowed), and a year it names must not be before the title's first.
     */
    fun named(stream: Stream, title: String, year: Int?): Boolean {
        val name = stream.filename ?: stream.title?.lineSequence()?.firstOrNull() ?: return true
        // (the release's own title: what comes before its episode, season or quality; then a year, a country or an edition)
        val cut = Regex("""(?i)(\bS\d{1,2}(E\d{1,3})?\b|\b\d{1,2}x\d{2,3}\b|\bSeason\b|\b(480|576|720|1080|2160)[pi]\b|\b4k\b|\bcomplete\b)""").find(name)
        val head = words(if (cut != null) name.substring(0, cut.range.first) else name).let { if (it.firstOrNull() == "the") it.drop(1) else it }
        val want = words(title).let { if (it.firstOrNull() == "the") it.drop(1) else it }
        if (want.isEmpty() || head.isEmpty()) return true
        if (head.size < want.size || head.take(want.size) != want) return false
        val next = head.getOrNull(want.size)
        if (next != null && next !in COUNTRY && next !in EDITION && !YEAR.matches(next) && cut != null) return false
        if (year != null) {
            val years = YEAR.findAll(name).map { it.value.toInt() }.toList()
            if (years.isNotEmpty() && years.all { it < year - 1 }) return false
        }
        return true
    }

    private val COUNTRY = setOf("us", "uk", "au", "nz", "ca")
    private val EDITION = setOf("extended", "remastered", "imax", "directors", "uncut", "unrated", "theatrical", "proper", "repack", "hdr", "dv", "web", "bluray", "remux", "multi")
    private fun words(s: String): List<String> = s.lowercase()
        .replace("&", " and ").replace("'", "").replace(Regex("""\[[^\]]*\]"""), " ")
        .split(Regex("""[^a-z0-9]+""")).filter { it.isNotEmpty() }

    /** Addons that find by name rather than by id: only played when the others have nothing that plays */
    fun searchBased(source: String) = source.lowercase().let { "easynews" in it }
}
