package lumos.engine

/** A movie or series as a catalog row shows it */
data class MetaPreview(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: String? = null,
    val genres: List<String> = emptyList(),
)

/** One episode (or, for a movie, the movie itself) */
data class Video(
    val id: String,
    val title: String?,
    val season: Int?,
    val episode: Int?,
    val released: String?,
    val thumbnail: String?,
    val overview: String?,
)

/** A title page's worth */
data class Meta(
    val preview: MetaPreview,
    val runtime: String?,
    val cast: List<String>,
    val director: List<String>,
    val trailers: List<String>,
    val videos: List<Video>,
)

/**
 * Something that can be played. Exactly one of [url] (ready to play), [infoHash] (a torrent, to go
 * through TorBox) or [externalUrl] (opens elsewhere) is the way in.
 */
data class Stream(
    val source: String,
    val name: String?,
    val title: String?,
    val url: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val externalUrl: String? = null,
    val filename: String? = null,
    val size: Long? = null,
    val cached: Boolean? = null,
    val headers: Map<String, String> = emptyMap(),
    val bingeGroup: String? = null,
    /** The stream's own subtitle files (made for this release, so in sync) */
    val subtitles: List<Subtitle> = emptyList(),
) {
    /** All the text an addon wrote about it, for reading quality, codec, language and size from */
    val text: String get() = listOfNotNull(name, title, filename).joinToString("\n")
}

/** A subtitle file, from a stream or a subtitle addon; [lang] as the addon wrote it (ISO 639-2 mostly: "eng") */
data class Subtitle(val url: String, val lang: String)
