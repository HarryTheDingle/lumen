package com.notdxbi.dxbi.tv.lumen.live

import lumos.iptv.SportsFixture

internal enum class SportFilter(val label: String) {
    ALL("All"),
    FOOTBALL("Football"),
    NFL("NFL"),
    NBA("NBA"),
    NHL("NHL"),
    MLB("MLB"),
    F1("F1"),
    UFC("UFC"),
    GOLF("Golf"),
    TENNIS("Tennis"),
}


internal fun matchesSport(fixture: SportsFixture, filter: SportFilter): Boolean {
    val s = fixture.sport.lowercase()
    val l = fixture.league.lowercase()
    val ln = fixture.leagueName.lowercase()
    return when (filter) {
        SportFilter.ALL -> true
        SportFilter.FOOTBALL -> (s == "soccer" || l.contains("soccer") || l.contains("eng.") ||
            l.contains("uefa") || l.contains("esp.") || l.contains("ger.") ||
            l.contains("ita.") || l.contains("fra.") || l.contains("sco.") ||
            ln.contains("premier league") || ln.contains("champions league")) && !l.contains("nfl")
        SportFilter.NFL -> l.contains("nfl") || (s == "football" && (ln.contains("nfl") || l.contains("american")))
        SportFilter.NBA -> s.contains("basketball") || l.contains("nba")
        SportFilter.NHL -> s.contains("hockey") || l.contains("nhl")
        SportFilter.MLB -> s.contains("baseball") || l.contains("mlb")
        SportFilter.F1 -> s.contains("racing") || s.contains("motorsport") || l.contains("f1") || ln.contains("formula")
        SportFilter.UFC -> s.contains("mma") || s.contains("ufc") || l.contains("ufc")
        SportFilter.GOLF -> s.contains("golf") || l.contains("pga")
        SportFilter.TENNIS -> s.contains("tennis") || l.contains("atp") || l.contains("wta")
    }
}

