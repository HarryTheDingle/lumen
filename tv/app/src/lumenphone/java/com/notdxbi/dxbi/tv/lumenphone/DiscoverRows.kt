package com.notdxbi.dxbi.tv.lumenphone

import com.notdxbi.dxbi.tv.phone.Stremio
import com.notdxbi.dxbi.tv.phone.Title
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** A row of Discover: posters, with a date under each for releases (iOS: DiscoverView.Row) */
data class DiscoverRow(val id: String, val title: String, val items: List<Title>, val dates: Map<String, Long> = emptyMap())

private const val DAY = 86_400_000L

private val FOR_YOU_GENRES = listOf(
    Triple("movie", "Action", "Action Films"), Triple("series", "Crime", "Crime Series"), Triple("movie", "Comedy", "Comedy Films"),
    Triple("movie", "Thriller", "Thrillers"), Triple("series", "Comedy", "Comedy Series"), Triple("movie", "Sci-Fi", "Sci-Fi Films"),
    Triple("series", "Drama", "Drama Series"), Triple("movie", "Horror", "Horror Films"), Triple("movie", "Animation", "Animated Films"),
    Triple("movie", "Documentary", "Documentaries"),
)

/**
 * For You: what this profile watched most points to (the plugin's picks), what's new on digital from the library
 * first, then popular and genre rows (iOS: DiscoverView.forYouRows). Each row is handed over as soon as it's
 * in, in its set place, rather than all after the slowest: the first rows are on screen while the rest load.
 */
suspend fun forYouRows(feed: DiscoverFeed, stremio: Stremio, publish: (List<DiscoverRow>) -> Unit) = coroutineScope {
    val order = buildList {
        stremio.picks.value.indices.forEach { add("pick-$it") }
        addAll(listOf("mine", "digital", "coming", "films", "series"))
        FOR_YOU_GENRES.indices.forEach { add("genre-$it") }
        add("year")
    }
    val slots = HashMap<String, DiscoverRow>()
    fun put(row: DiscoverRow?) {
        if (row != null && row.items.isNotEmpty()) slots[row.id] = row
        publish(order.mapNotNull { slots[it] })
    }
    stremio.picks.value.forEachIndexed { i, pick -> slots["pick-$i"] = DiscoverRow("pick-$i", pick.name, pick.items) }

    launch {
        val all = feed.digitalYear()
        val now = System.currentTimeMillis()
        val out = all.filter { it.date <= now }
        val recent = out.filter { it.date >= now - 42 * DAY }
        val coming = all.filter { it.date > now }.sortedBy { it.date }
        val library = stremio.library.value.filter { it.type == "movie" }.map { it.id }.toSet()
        val mine = out.filter { it.id in library && it.date >= now - 60 * DAY }
        val dates = all.associate { it.id to it.date }
        put(DiscoverRow("mine", "From Your Library: Out on Digital", mine.map { it.title }, dates).takeIf { mine.isNotEmpty() })
        put(DiscoverRow("digital", "New on Digital", recent.take(30).map { it.title }, dates).takeIf { recent.isNotEmpty() })
        put(DiscoverRow("coming", "Coming to Digital", coming.take(25).map { it.title }, dates).takeIf { coming.isNotEmpty() })
        if (out.size > recent.size) put(DiscoverRow("year", "Out on Digital This Year", out.take(60).map { it.title }, dates))
    }
    launch { put(DiscoverRow("films", "Popular Films", feed.popular("movie").take(25).map { it.title })) }
    launch { put(DiscoverRow("series", "Popular Series", feed.popular("series").take(25).map { it.title })) }
    FOR_YOU_GENRES.forEachIndexed { i, (type, genre, heading) ->
        launch {
            val items = feed.popular(type, genre)
            // (fewer than four would look like a mistake)
            put(DiscoverRow("genre-$i", heading, items.take(25).map { it.title }).takeIf { items.size >= 4 })
        }
    }
    put(null)
}

/**
 * One streaming service's page: new on it (out in the last four months, newest first), its popular series and
 * films, then its main genres as rows (iOS: DiscoverView.platformRows)
 */
suspend fun platformRows(feed: DiscoverFeed, p: DiscoverFeed.Platform): List<DiscoverRow> = coroutineScope {
    val seriesJob = async { feed.platform(p, "series") }
    val filmJob = async { feed.platform(p, "movie") }
    val series = seriesJob.await()
    val films = filmJob.await()
    val all = series + films
    val rows = ArrayList<DiscoverRow>()
    val now = System.currentTimeMillis()
    val new = all.filter { (it.released ?: Long.MIN_VALUE) >= now - 120 * DAY && (it.released ?: Long.MAX_VALUE) <= now }
        .sortedByDescending { it.released ?: Long.MIN_VALUE }
    if (new.size >= 3) rows += DiscoverRow("new", "New on ${p.name}", new.take(25).map { it.title })
    if (series.isNotEmpty()) rows += DiscoverRow("series", "Popular Series", series.take(30).map { it.title })
    if (films.isNotEmpty()) rows += DiscoverRow("films", "Popular Films", films.take(30).map { it.title })
    val counts = HashMap<String, Int>()
    for (item in all) for (g in item.title.genres) counts[g] = (counts[g] ?: 0) + 1
    for (genre in counts.entries.sortedByDescending { it.value }.take(6).map { it.key }) {
        val items = all.filter { genre in it.title.genres }
        if (items.size >= 5) rows += DiscoverRow("genre-$genre", genre, items.take(25).map { it.title })
    }
    rows
}
