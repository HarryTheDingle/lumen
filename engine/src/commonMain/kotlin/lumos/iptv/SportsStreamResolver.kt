package lumos.iptv

/**
 * Resolves a [SportsFixture] onto ordered playable IPTV streams across UK broadcasters,
 * US broadcasters, and direct team-vs-team PPV / Live Event channels.
 */
object SportsStreamResolver {
    private val CLUB_NOISE_REGEX = Regex("""\b(?:fc|afc|sc|cf|ac|ssc)\b""")
    private val UFC_EVENT_REGEX = Regex("""\bufc\s*(\d+)\b""", RegexOption.IGNORE_CASE)
    private val F1_CONTEXT_REGEX = Regex("""\b(?:formula\s*1|f1|grand\s*prix|gp)\b""", RegexOption.IGNORE_CASE)
    private val F1_STOP_WORDS = setOf("formula", "grand", "prix")
    private val GENERIC_EVENT_STOP_WORDS = setOf("championship", "masters", "tournament")

    /**
     * Pre-indexed channel lookup tables and pre-normalized event text built once per channel list
     * so batch fixture resolution avoids repeating O(channels) normalization across fixtures.
     */
    internal class PreparedChannels private constructor(
        val byId: Map<String, CanonicalChannel>,
        val byNameKey: Map<String, CanonicalChannel>,
        val eligibleEventChannels: List<Pair<CanonicalChannel, String>>,
    ) {
        companion object {
            /** Indexes [channels] by ID, normalized name key, and pre-normalized event texts. */
            fun from(channels: List<CanonicalChannel>): PreparedChannels {
                val byId = HashMap<String, CanonicalChannel>(channels.size * 4 / 3 + 1)
                val byNameKey = HashMap<String, CanonicalChannel>(channels.size * 4 / 3 + 1)
                val eligible = ArrayList<Pair<CanonicalChannel, String>>()
                for (ch in channels) {
                    byId[ch.id] = ch
                    byNameKey[UkChannelRegistry.normalizeNameKey(ch.name)] = ch
                    val isEligibleBucket = ch.isEventChannel ||
                        ch.category == ChannelCategory.EVENTS ||
                        ch.category == ChannelCategory.US_ENGLISH
                    if (isEligibleBucket) {
                        eligible.add(ch to buildNormalizedEventTexts(ch))
                    }
                }
                return PreparedChannels(
                    byId = byId,
                    byNameKey = byNameKey,
                    eligibleEventChannels = eligible,
                )
            }

            private fun buildNormalizedEventTexts(channel: CanonicalChannel): String {
                val seen = HashSet<String>(1 + channel.streams.size)
                return buildString {
                    fun appendNormalized(raw: String) {
                        val norm = UkChannelRegistry.normalizeNameKey(raw)
                        if (norm.isNotEmpty() && seen.add(norm)) {
                            if (isNotEmpty()) append('\n')
                            append(' ')
                            append(norm)
                            append(' ')
                        }
                    }
                    appendNormalized(channel.name)
                    for (stream in channel.streams) {
                        appendNormalized(stream.rawName)
                    }
                }
            }
        }
    }

    private val BROADCAST_ALIASES: Map<String, Pair<String, MatchReason>> = mapOf(
        "sky sports" to ("uk.sky.sports.main" to MatchReason.UK_BROADCASTER),
        "sky sports main event" to ("uk.sky.sports.main" to MatchReason.UK_BROADCASTER),
        "sky sports main" to ("uk.sky.sports.main" to MatchReason.UK_BROADCASTER),
        "sky sports premier league" to ("uk.sky.sports.pl" to MatchReason.UK_BROADCASTER),
        "sky sports pl" to ("uk.sky.sports.pl" to MatchReason.UK_BROADCASTER),
        "sky sports football" to ("uk.sky.sports.football" to MatchReason.UK_BROADCASTER),
        "sky sports plus" to ("uk.sky.sports.plus" to MatchReason.UK_BROADCASTER),
        "sky sportsplus" to ("uk.sky.sports.plus" to MatchReason.UK_BROADCASTER),
        "sky sports f1" to ("uk.sky.sports.f1" to MatchReason.UK_BROADCASTER),
        "sky sports cricket" to ("uk.sky.sports.cricket" to MatchReason.UK_BROADCASTER),
        "sky sports golf" to ("uk.sky.sports.golf" to MatchReason.UK_BROADCASTER),
        "sky sports tennis" to ("uk.sky.sports.tennis" to MatchReason.UK_BROADCASTER),
        "sky sports action" to ("uk.sky.sports.action" to MatchReason.UK_BROADCASTER),
        "sky sports nfl" to ("uk.sky.sports.action" to MatchReason.UK_BROADCASTER),
        "sky sports racing" to ("uk.sky.sports.racing" to MatchReason.UK_BROADCASTER),
        "sky sports mix" to ("uk.sky.sports.mix" to MatchReason.UK_BROADCASTER),
        "sky sports news" to ("uk.sky.sports.news" to MatchReason.UK_BROADCASTER),
        "tnt sports" to ("uk.tnt.sports.1" to MatchReason.UK_BROADCASTER),
        "tnt sports 1" to ("uk.tnt.sports.1" to MatchReason.UK_BROADCASTER),
        "tnt sports 2" to ("uk.tnt.sports.2" to MatchReason.UK_BROADCASTER),
        "tnt sports 3" to ("uk.tnt.sports.3" to MatchReason.UK_BROADCASTER),
        "tnt sports 4" to ("uk.tnt.sports.4" to MatchReason.UK_BROADCASTER),
        "tnt sports ultimate" to ("uk.tnt.sports.ultimate" to MatchReason.UK_BROADCASTER),
        "bt sport 1" to ("uk.tnt.sports.1" to MatchReason.UK_BROADCASTER),
        "bt sport 2" to ("uk.tnt.sports.2" to MatchReason.UK_BROADCASTER),
        "bt sport 3" to ("uk.tnt.sports.3" to MatchReason.UK_BROADCASTER),
        "bt sport 4" to ("uk.tnt.sports.4" to MatchReason.UK_BROADCASTER),
        "premier sports" to ("uk.premier.sports.1" to MatchReason.UK_BROADCASTER),
        "premier sports 1" to ("uk.premier.sports.1" to MatchReason.UK_BROADCASTER),
        "premier sports 2" to ("uk.premier.sports.2" to MatchReason.UK_BROADCASTER),
        "bbc" to ("uk.bbc.one" to MatchReason.UK_BROADCASTER),
        "bbc 1" to ("uk.bbc.one" to MatchReason.UK_BROADCASTER),
        "bbc one" to ("uk.bbc.one" to MatchReason.UK_BROADCASTER),
        "bbc sport" to ("uk.bbc.one" to MatchReason.UK_BROADCASTER),
        "bbc 2" to ("uk.bbc.two" to MatchReason.UK_BROADCASTER),
        "bbc two" to ("uk.bbc.two" to MatchReason.UK_BROADCASTER),
        "bbc 3" to ("uk.bbc.three" to MatchReason.UK_BROADCASTER),
        "bbc three" to ("uk.bbc.three" to MatchReason.UK_BROADCASTER),
        "bbc scotland" to ("uk.bbc.scotland" to MatchReason.UK_BROADCASTER),
        "itv" to ("uk.itv.one" to MatchReason.UK_BROADCASTER),
        "itv 1" to ("uk.itv.one" to MatchReason.UK_BROADCASTER),
        "itv1" to ("uk.itv.one" to MatchReason.UK_BROADCASTER),
        "itv 4" to ("uk.itv.four" to MatchReason.UK_BROADCASTER),
        "itv4" to ("uk.itv.four" to MatchReason.UK_BROADCASTER),
        "channel 4" to ("uk.ch4" to MatchReason.UK_BROADCASTER),
        "racing tv" to ("uk.racing.tv" to MatchReason.UK_BROADCASTER),
        "mutv" to ("uk.mutv" to MatchReason.UK_BROADCASTER),
        "lfctv" to ("uk.lfctv" to MatchReason.UK_BROADCASTER),
        // US networks
        "espn" to ("us.espn" to MatchReason.US_BROADCASTER),
        "espn 1" to ("us.espn" to MatchReason.US_BROADCASTER),
        "espn2" to ("us.espn2" to MatchReason.US_BROADCASTER),
        "espn 2" to ("us.espn2" to MatchReason.US_BROADCASTER),
        "fs1" to ("us.fs1" to MatchReason.US_BROADCASTER),
        "fox sports 1" to ("us.fs1" to MatchReason.US_BROADCASTER),
        "fs2" to ("us.fs2" to MatchReason.US_BROADCASTER),
        "fox sports 2" to ("us.fs2" to MatchReason.US_BROADCASTER),
        "fox" to ("us.fox" to MatchReason.US_BROADCASTER),
        "usa network" to ("us.usa.network" to MatchReason.US_BROADCASTER),
        "usa net" to ("us.usa.network" to MatchReason.US_BROADCASTER),
        "nbc" to ("us.nbc" to MatchReason.US_BROADCASTER),
        "tnt" to ("us.tnt" to MatchReason.US_BROADCASTER),
        "tbs" to ("us.tbs" to MatchReason.US_BROADCASTER),
        "cbs" to ("us.cbs" to MatchReason.US_BROADCASTER),
        "cbssn" to ("us.cbssn" to MatchReason.US_BROADCASTER),
        "cbs sports network" to ("us.cbssn" to MatchReason.US_BROADCASTER),
        "abc" to ("us.abc" to MatchReason.US_BROADCASTER),
        "nfl network" to ("us.nfl.network" to MatchReason.US_BROADCASTER),
        "nba tv" to ("us.nba.tv" to MatchReason.US_BROADCASTER),
        "nbatv" to ("us.nba.tv" to MatchReason.US_BROADCASTER),
        "nhl network" to ("us.nhl.network" to MatchReason.US_BROADCASTER),
        "mlb network" to ("us.mlb.network" to MatchReason.US_BROADCASTER),
    )

    private val TEAM_ALIASES: Map<String, List<String>> = mapOf(
        "manchester united" to listOf("man utd", "man united", "manchester utd"),
        "manchester city" to listOf("man city"),
        "tottenham hotspur" to listOf("tottenham", "spurs"),
        "wolverhampton wanderers" to listOf("wolves", "wolverhampton"),
        "newcastle united" to listOf("newcastle"),
        "west ham united" to listOf("west ham"),
        "brighton hove albion" to listOf("brighton"),
        "nottingham forest" to listOf("nottm forest", "forest"),
        "leicester city" to listOf("leicester"),
        "ipswich town" to listOf("ipswich"),
        "sheffield united" to listOf("sheff utd", "sheffield utd"),
        "afc bournemouth" to listOf("bournemouth"),
        "paris saint germain" to listOf("psg"),
        "bayern munich" to listOf("bayern"),
        "inter milan" to listOf("inter"),
    )

    /**
     * Resolves [fixture] into ordered playable candidates from [channels]:
     * (1) `ukChannelId` match, (2) UK then US broadcast network alias matches,
     * (3) direct team-vs-team / event matches in Live Events, US English, or PPV channels.
     */
    fun resolve(
        fixture: SportsFixture,
        channels: List<CanonicalChannel>,
        health: StreamHealthTracker? = null,
        prefs: IptvProfilePrefs = IptvProfilePrefs(),
        sourcePriorities: Map<String, Int> = emptyMap(),
        nowMs: Long = 0L,
        maxHeight: Int = 2160,
        bandwidthBps: Long? = null,
    ): List<ResolvedFixtureStream> {
        if (channels.isEmpty()) return emptyList()
        return resolveWithPrepared(
            fixture = fixture,
            prepared = PreparedChannels.from(channels),
            health = health,
            prefs = prefs,
            sourcePriorities = sourcePriorities,
            nowMs = nowMs,
            maxHeight = maxHeight,
            bandwidthBps = bandwidthBps,
        )
    }

    /**
     * Resolves a batch of [fixtures] against [channels], indexing and normalizing [channels]
     * once across all fixtures in the batch.
     */
    fun resolveBatch(
        fixtures: List<SportsFixture>,
        channels: List<CanonicalChannel>,
        health: StreamHealthTracker? = null,
        prefs: IptvProfilePrefs = IptvProfilePrefs(),
        sourcePriorities: Map<String, Int> = emptyMap(),
        nowMs: Long = 0L,
        maxHeight: Int = 2160,
        bandwidthBps: Long? = null,
    ): Map<String, List<ResolvedFixtureStream>> {
        if (fixtures.isEmpty()) return emptyMap()
        if (channels.isEmpty()) return fixtures.associate { it.id to emptyList() }
        val prepared = PreparedChannels.from(channels)
        val result = LinkedHashMap<String, List<ResolvedFixtureStream>>(fixtures.size)
        for (fixture in fixtures) {
            result[fixture.id] = resolveWithPrepared(
                fixture = fixture,
                prepared = prepared,
                health = health,
                prefs = prefs,
                sourcePriorities = sourcePriorities,
                nowMs = nowMs,
                maxHeight = maxHeight,
                bandwidthBps = bandwidthBps,
            )
        }
        return result
    }

    private fun resolveWithPrepared(
        fixture: SportsFixture,
        prepared: PreparedChannels,
        health: StreamHealthTracker?,
        prefs: IptvProfilePrefs,
        sourcePriorities: Map<String, Int>,
        nowMs: Long,
        maxHeight: Int,
        bandwidthBps: Long?,
    ): List<ResolvedFixtureStream> {
        val byId = prepared.byId
        val byNameKey = prepared.byNameKey

        val ukCandidates = mutableListOf<ResolvedFixtureStream>()
        val usCandidates = mutableListOf<ResolvedFixtureStream>()
        val directCandidates = mutableListOf<ResolvedFixtureStream>()
        val seenChannelIds = mutableSetOf<String>()

        fun pickStream(ch: CanonicalChannel): StreamSource? =
            StreamRanker.rank(
                streams = ch.streams,
                category = ChannelCategory.SPORTS,
                isEventChannel = ch.isEventChannel,
                health = health,
                preferredStreamId = prefs.preferredStreamByChannel[ch.id],
                sourcePriorities = sourcePriorities,
                nowMs = nowMs,
                maxHeight = maxHeight,
                bandwidthBps = bandwidthBps,
            ).firstOrNull()

        fun addCandidate(
            channel: CanonicalChannel,
            reason: MatchReason,
            label: String,
            target: MutableList<ResolvedFixtureStream>,
        ) {
            if (!seenChannelIds.add(channel.id)) return
            val stream = pickStream(channel) ?: return
            target.add(ResolvedFixtureStream(channel = channel, stream = stream, reason = reason, matchedLabel = label))
        }

        // 1. Direct ukChannelId (or ukChannelName) match from D1 EPG cross-reference
        val ukId = fixture.ukChannelId?.trim()?.takeIf { it.isNotEmpty() }
        val directUkChannel = ukId?.let { byId[it] }
            ?: fixture.ukChannelName?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
                UkChannelRegistry.match(name)?.id?.let { byId[it] }
                    ?: byNameKey[UkChannelRegistry.normalizeNameKey(name)]
            }
        if (directUkChannel != null) {
            addCandidate(
                channel = directUkChannel,
                reason = MatchReason.UK_BROADCASTER,
                label = fixture.ukChannelName ?: directUkChannel.name,
                target = ukCandidates,
            )
        }

        // 2. Broadcast network alias matches
        for (broadcast in fixture.broadcasts) {
            val trimmed = broadcast.trim()
            if (trimmed.isEmpty()) continue
            val resolved = resolveBroadcastTarget(trimmed, byId, byNameKey) ?: continue
            val target = if (resolved.second == MatchReason.UK_BROADCASTER) ukCandidates else usCandidates
            addCandidate(resolved.first, resolved.second, trimmed, target)
        }

        // 3. Direct team-vs-team / event matches in Live Events, US English, or PPV channels
        val homeAliases = teamAliases(fixture.home).map { " $it " }
        val awayAliases = teamAliases(fixture.away).map { " $it " }
        val hasTeamPair = homeAliases.isNotEmpty() && awayAliases.isNotEmpty()
        val nonTeamMatcher = if (hasTeamPair) null else buildNonTeamMatcher(fixture)

        for ((ch, normalizedTexts) in prepared.eligibleEventChannels) {
            if (ch.id in seenChannelIds) continue
            val matched = if (hasTeamPair) {
                matchesTeamPair(normalizedTexts, homeAliases, awayAliases)
            } else {
                nonTeamMatcher?.matches(normalizedTexts) == true
            }
            if (matched) {
                addCandidate(ch, MatchReason.DIRECT_EVENT, ch.name, directCandidates)
            }
        }

        return buildList(ukCandidates.size + usCandidates.size + directCandidates.size) {
            addAll(ukCandidates)
            addAll(usCandidates)
            addAll(directCandidates)
        }
    }

    private fun resolveBroadcastTarget(
        broadcast: String,
        byId: Map<String, CanonicalChannel>,
        byNameKey: Map<String, CanonicalChannel>,
    ): Pair<CanonicalChannel, MatchReason>? {
        val key = UkChannelRegistry.normalizeNameKey(broadcast)
        if (key.isEmpty()) return null

        BROADCAST_ALIASES[key]?.let { (id, reason) ->
            byId[id]?.let { return it to reason }
        }

        // Prefix fallbacks for numbered/regional variants (e.g. "ESPN Deportes", "TNT Sports 5", "BBC One Wales")
        val prefixMatch = BROADCAST_ALIASES.entries.firstOrNull { (alias, _) ->
            alias.length >= 4 && key.startsWith("$alias ")
        }
        if (prefixMatch != null) {
            byId[prefixMatch.value.first]?.let { return it to prefixMatch.value.second }
        }

        val regMatch = UkChannelRegistry.match(broadcast)
        if (regMatch != null) {
            byId[regMatch.id]?.let { ch ->
                val reason = if (ch.category == ChannelCategory.US_ENGLISH) {
                    MatchReason.US_BROADCASTER
                } else {
                    MatchReason.UK_BROADCASTER
                }
                return ch to reason
            }
        }

        byNameKey[key]?.let { ch ->
            val reason = if (ch.category == ChannelCategory.US_ENGLISH) {
                MatchReason.US_BROADCASTER
            } else {
                MatchReason.UK_BROADCASTER
            }
            return ch to reason
        }
        return null
    }

    private fun teamAliases(side: SportsTeamSide?): List<String> {
        if (side == null) return emptyList()
        val aliases = linkedSetOf<String>()
        for (raw in listOfNotNull(side.name, side.shortName)) {
            val n = UkChannelRegistry.normalizeNameKey(raw)
            if (n.isEmpty()) continue
            aliases.add(n)
            val stripped = CLUB_NOISE_REGEX.replace(n, "").replace("  ", " ").trim()
            if (stripped.length >= 3) aliases.add(stripped)
            TEAM_ALIASES[n]?.forEach(aliases::add)
            TEAM_ALIASES[stripped]?.forEach(aliases::add)
            val words = stripped.split(' ').filter { it.isNotEmpty() }
            if (words.size >= 2) {
                val last = words.last()
                if (last.length >= 4 && last !in GENERIC_CLUB_WORDS) {
                    aliases.add(last)
                }
            }
        }
        return aliases.filter { it.length >= 3 }
    }

    private fun matchesTeamPair(
        normalizedTexts: String,
        paddedHomeAliases: List<String>,
        paddedAwayAliases: List<String>,
    ): Boolean {
        if (normalizedTexts.isEmpty()) return false
        if (!paddedHomeAliases.any { normalizedTexts.contains(it) } ||
            !paddedAwayAliases.any { normalizedTexts.contains(it) }
        ) {
            return false
        }
        if ('\n' !in normalizedTexts) return true
        var start = 0
        while (start < normalizedTexts.length) {
            val end = normalizedTexts.indexOf('\n', start).let { if (it < 0) normalizedTexts.length else it }
            val line = normalizedTexts.substring(start, end)
            if (paddedHomeAliases.any { line.contains(it) } && paddedAwayAliases.any { line.contains(it) }) {
                return true
            }
            start = end + 1
        }
        return false
    }

    private sealed interface NonTeamMatcher {
        fun matches(normalizedTexts: String): Boolean

        class Ufc(num: String) : NonTeamMatcher {
            private val regex = Regex("""\bufc *$num\b""", RegexOption.IGNORE_CASE)
            override fun matches(normalizedTexts: String): Boolean =
                normalizedTexts.isNotEmpty() && regex.containsMatchIn(normalizedTexts)
        }

        class FormulaOne(private val paddedTokens: List<String>) : NonTeamMatcher {
            override fun matches(normalizedTexts: String): Boolean {
                if (normalizedTexts.isEmpty() || paddedTokens.isEmpty()) return false
                if (!F1_CONTEXT_REGEX.containsMatchIn(normalizedTexts)) return false
                if (!paddedTokens.any { normalizedTexts.contains(it) }) return false
                if ('\n' !in normalizedTexts) return true
                var start = 0
                while (start < normalizedTexts.length) {
                    val end = normalizedTexts.indexOf('\n', start).let { if (it < 0) normalizedTexts.length else it }
                    val line = normalizedTexts.substring(start, end)
                    if (F1_CONTEXT_REGEX.containsMatchIn(line) && paddedTokens.any { line.contains(it) }) {
                        return true
                    }
                    start = end + 1
                }
                return false
            }
        }

        class Generic(private val paddedTokens: List<String>) : NonTeamMatcher {
            override fun matches(normalizedTexts: String): Boolean =
                normalizedTexts.isNotEmpty() && paddedTokens.any { normalizedTexts.contains(it) }
        }

        data object None : NonTeamMatcher {
            override fun matches(normalizedTexts: String): Boolean = false
        }
    }

    private fun buildNonTeamMatcher(fixture: SportsFixture): NonTeamMatcher {
        if (fixture.sport.equals("UFC", ignoreCase = true)) {
            val num = UFC_EVENT_REGEX.find(fixture.name)?.groupValues?.getOrNull(1)
            if (num != null) return NonTeamMatcher.Ufc(num)
        }
        if (fixture.sport.equals("Formula 1", ignoreCase = true)) {
            val locationTokens = UkChannelRegistry.normalizeNameKey(fixture.name)
                .split(' ')
                .filter { it.length >= 4 && it !in F1_STOP_WORDS }
                .map { " $it " }
            return if (locationTokens.isEmpty()) NonTeamMatcher.None else NonTeamMatcher.FormulaOne(locationTokens)
        }
        val tokens = UkChannelRegistry.normalizeNameKey(fixture.name)
            .split(' ')
            .filter { it.length >= 5 && it !in GENERIC_EVENT_STOP_WORDS }
            .map { " $it " }
        return if (tokens.isEmpty()) NonTeamMatcher.None else NonTeamMatcher.Generic(tokens)
    }

    private val GENERIC_CLUB_WORDS = setOf(
        "united", "city", "town", "athletic", "rovers", "wanderers", "albion", "hotspur", "madrid", "milan",
    )
}
