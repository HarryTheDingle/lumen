// Checks UK & US sports fixtures, team search, team schedules, D1 EPG broadcaster matching,
// and Worker routes (/iptv/sports/fixtures, /iptv/sports/teams/search, /iptv/sports/teams/schedule).
//   node sync/test-sports.mjs
import worker, { pruneCaches } from "./worker.js";
import { handleIptvPresence, pruneIptvPresence } from "./presence.js";
import {
  FIXTURE_LEAGUES,
  handleSportsFixtures,
  handleTeamsSchedule,
  handleTeamsSearch,
  matchUkBroadcasterForFixture,
  parseEspnScoreboard,
  parseEspnTeamSchedule,
  parseEspnTeams,
  searchCachedTeams,
} from "./sports.js";
import { d1 } from "./d1-sqlite.mjs";

const failures = [];
const check = (ok, what) => {
  console.log(`${ok ? "  ✓" : "  ✗"} ${what}`);
  if (!ok) failures.push(what);
};

const now = Date.UTC(2026, 10, 22, 16, 0, 0); // 2026-11-22T16:00:00Z
const MIN = 60 * 1000;
const HOUR = 60 * MIN;

// 1. Pure ESPN scoreboard parser (Football, NBA, Formula 1)
const eplScoreboard = {
  leagues: [{ name: "English Premier League" }],
  events: [
    {
      id: "701001",
      date: new Date(now - 30 * MIN).toISOString(),
      name: "Chelsea at Arsenal",
      shortName: "CHE @ ARS",
      competitions: [
        {
          date: new Date(now - 30 * MIN).toISOString(),
          venue: { fullName: "Emirates Stadium" },
          competitors: [
            {
              homeAway: "home",
              score: "2",
              winner: false,
              team: { id: "359", displayName: "Arsenal", shortDisplayName: "Arsenal", abbreviation: "ARS", logo: "https://img/ars.png" },
            },
            {
              homeAway: "away",
              score: "1",
              winner: false,
              team: { id: "363", displayName: "Chelsea", shortDisplayName: "Chelsea", abbreviation: "CHE", logo: "https://img/che.png" },
            },
          ],
          broadcasts: [{ names: ["NBC", "Peacock"] }],
          geoBroadcasts: [{ media: { shortName: "Sky Sports Premier League" } }],
          status: {
            displayClock: "62'",
            period: 2,
            type: { state: "in", completed: false, shortDetail: "62'" },
          },
        },
      ],
    },
    {
      id: "701002",
      date: new Date(now + 3 * HOUR).toISOString(),
      name: "Tottenham Hotspur at Manchester United",
      shortName: "TOT @ MUN",
      competitions: [
        {
          date: new Date(now + 3 * HOUR).toISOString(),
          competitors: [
            {
              homeAway: "home",
              team: { id: "360", displayName: "Manchester United", shortDisplayName: "Man Utd", abbreviation: "MUN" },
            },
            {
              homeAway: "away",
              team: { id: "367", displayName: "Tottenham Hotspur", shortDisplayName: "Tottenham", abbreviation: "TOT" },
            },
          ],
          broadcasts: [{ names: ["USA Network"] }],
          status: { type: { state: "pre", completed: false, shortDetail: "Sun, 7:00 PM GMT" } },
        },
      ],
    },
  ],
};

const f1Scoreboard = {
  leagues: [{ name: "Formula 1" }],
  events: [
    {
      id: "600050",
      date: new Date(now + 2 * HOUR).toISOString(),
      name: "Las Vegas Grand Prix",
      shortName: "Las Vegas GP",
      competitions: [
        { type: { abbreviation: "Qual", text: "Qualifying" }, date: new Date(now - 24 * HOUR).toISOString(), status: { type: { state: "post", completed: true } } },
        { type: { abbreviation: "Race", text: "Race" }, date: new Date(now + 2 * HOUR).toISOString(), broadcasts: [{ names: ["ESPN"] }], status: { type: { state: "pre", completed: false, shortDetail: "6:00 PM" } } },
      ],
    },
  ],
};

const nbaScoreboard = {
  leagues: [{ name: "NBA" }],
  events: [
    {
      id: "801001",
      date: new Date(now + HOUR).toISOString(),
      name: "Boston Celtics at New York Knicks",
      shortName: "BOS @ NY",
      competitions: [
        {
          date: new Date(now + HOUR).toISOString(),
          competitors: [
            { homeAway: "home", team: { id: "18", displayName: "New York Knicks", shortDisplayName: "Knicks" } },
            { homeAway: "away", team: { id: "2", displayName: "Boston Celtics", shortDisplayName: "Celtics" } },
          ],
          status: { type: { state: "pre", completed: false } },
        },
      ],
    },
  ],
};

const parsedEpl = parseEspnScoreboard(eplScoreboard, FIXTURE_LEAGUES[0]);
const parsedF1 = parseEspnScoreboard(f1Scoreboard, FIXTURE_LEAGUES.find((l) => l.league === "f1"));
const parsedNba = parseEspnScoreboard(nbaScoreboard, FIXTURE_LEAGUES.find((l) => l.league === "nba"));
check(
  parsedEpl.length === 2 &&
    parsedEpl[0].name === "Arsenal v Chelsea" &&
    parsedEpl[0].shortName === "Arsenal v Chelsea" &&
    parsedEpl[0].status === "LIVE" &&
    parsedEpl[0].clock === "62'" &&
    parsedEpl[0].home?.score === "2" &&
    parsedEpl[0].away?.name === "Chelsea" &&
    parsedEpl[0].broadcasts.includes("NBC") &&
    parsedEpl[0].broadcasts.includes("Sky Sports Premier League") &&
    parsedEpl[1].name === "Manchester United v Tottenham Hotspur" &&
    parsedEpl[1].shortName === "Man Utd v Tottenham" &&
    parsedEpl[1].status === "UPCOMING",
  "parseEspnScoreboard formats Football as 'Home v Away' and normalizes status, scores, clocks, teams, and broadcasts"
);
check(
  parsedF1.length === 1 &&
    parsedF1[0].name === "Las Vegas Grand Prix" &&
    parsedF1[0].sport === "Formula 1" &&
    parsedNba.length === 1 &&
    parsedNba[0].name === "Boston Celtics at New York Knicks",
  "parseEspnScoreboard keeps non-football sports (F1, NBA) in native format"
);

// 2. D1 UK EPG Broadcaster cross-matching
const ukProgrammesFixture = [
  {
    channel_id: "uk.sky.sports.pl",
    channel_name: "Sky Sports Premier League",
    start_ms: now - 60 * MIN,
    end_ms: now + 90 * MIN,
    title: "Super Sunday: Arsenal v Chelsea",
    subtitle: "Premier League",
    description: "Live coverage from the Emirates.",
  },
  {
    channel_id: "uk.sky.sports.news",
    channel_name: "Sky Sports News",
    start_ms: now - 30 * MIN,
    end_ms: now + 30 * MIN,
    title: "Arsenal v Chelsea Build-up",
    subtitle: null,
    description: null,
  },
  {
    channel_id: "uk.tnt.sports.1",
    channel_name: "TNT Sports 1",
    start_ms: now + 3 * HOUR - 30 * MIN,
    end_ms: now + 5 * HOUR,
    title: "Live Premier League: Man Utd v Spurs",
    subtitle: null,
    description: null,
  },
  {
    channel_id: "uk.bbc.one",
    channel_name: "BBC One",
    start_ms: now + 7 * HOUR,
    end_ms: now + 8 * HOUR,
    title: "Match of the Day Highlights: Arsenal v Chelsea",
    subtitle: null,
    description: null,
  },
  {
    channel_id: "uk.sky.sports.f1",
    channel_name: "Sky Sports F1",
    start_ms: now + 2 * HOUR - 15 * MIN,
    end_ms: now + 4 * HOUR,
    title: "Live F1: Las Vegas Grand Prix",
    subtitle: "Race",
    description: null,
  },
];

const matchArsChe = matchUkBroadcasterForFixture(parsedEpl[0], ukProgrammesFixture);
const matchMunTot = matchUkBroadcasterForFixture(parsedEpl[1], ukProgrammesFixture);
const matchF1 = matchUkBroadcasterForFixture(parsedF1[0], ukProgrammesFixture);
check(
  matchArsChe?.channelId === "uk.sky.sports.pl" &&
    matchMunTot?.channelId === "uk.tnt.sports.1" &&
    matchF1?.channelId === "uk.sky.sports.f1",
  "matchUkBroadcasterForFixture resolves UK channels via aliases and prefers dedicated live sports channels over news/highlights"
);

// Outside window & highlights exclusion check
const outOfWindowMatch = matchUkBroadcasterForFixture(parsedEpl[0], [ukProgrammesFixture[3]]);
check(outOfWindowMatch === null, "matchUkBroadcasterForFixture ignores highlights & out-of-window programmes");

// 3. Team search & team schedule parsing
const teamsPayload = {
  sports: [
    {
      leagues: [
        {
          name: "English Premier League",
          teams: [
            { team: { id: "359", displayName: "Arsenal", shortDisplayName: "Arsenal", abbreviation: "ARS", logos: [{ href: "https://img/ars.png" }] } },
            { team: { id: "363", displayName: "Chelsea", shortDisplayName: "Chelsea", abbreviation: "CHE", logos: [{ href: "https://img/che.png" }] } },
          ],
        },
      ],
    },
  ],
};
const parsedTeams = parseEspnTeams(teamsPayload, FIXTURE_LEAGUES[0]);
const searched = searchCachedTeams(parsedTeams, "ars", 20);
check(
  parsedTeams.length === 2 && searched.length === 1 && searched[0].teamId === "359" && searched[0].logoUrl === "https://img/ars.png",
  "parseEspnTeams & searchCachedTeams rank matching teams"
);

const schedEvents = [
  ...Array.from({ length: 7 }, (_, i) => ({
    id: `past-${i}`,
    date: new Date(now - (i + 1) * 7 * 24 * HOUR).toISOString(),
    name: `Past Match ${i}`,
    competitions: [
      {
        date: new Date(now - (i + 1) * 7 * 24 * HOUR).toISOString(),
        competitors: [
          { homeAway: "home", score: { value: 2, displayValue: "2" }, winner: true, team: { id: "359", displayName: "Arsenal" } },
          { homeAway: "away", score: { value: 0, displayValue: "0" }, winner: false, team: { id: "363", displayName: "Chelsea" } },
        ],
        status: { type: { state: "post", completed: true, shortDetail: "FT" } },
      },
    ],
  })),
  {
    id: "live-1",
    date: new Date(now - 30 * MIN).toISOString(),
    name: "Chelsea at Arsenal",
    competitions: eplScoreboard.events[0].competitions,
  },
  ...Array.from({ length: 7 }, (_, i) => ({
    id: `up-${i}`,
    date: new Date(now + (i + 2) * 24 * HOUR).toISOString(),
    name: `Upcoming Match ${i}`,
    competitions: [
      {
        date: new Date(now + (i + 2) * 24 * HOUR).toISOString(),
        competitors: [
          { homeAway: "home", team: { id: "359", displayName: "Arsenal" } },
          { homeAway: "away", team: { id: "360", displayName: "Manchester United" } },
        ],
        status: { type: { state: "pre", completed: false, shortDetail: "Sat, 3:00 PM" } },
      },
    ],
  })),
];
const parsedSched = parseEspnTeamSchedule({ events: schedEvents }, FIXTURE_LEAGUES[0], now);
check(
  parsedSched.live.length === 1 &&
    parsedSched.live[0].name === "Arsenal v Chelsea" &&
    parsedSched.past.length === 5 &&
    parsedSched.past[0].id === "past-0" &&
    parsedSched.past[0].name === "Arsenal v Chelsea" &&
    parsedSched.past[0].home?.score === "2" &&
    parsedSched.upcoming.length === 5 &&
    parsedSched.upcoming[0].id === "up-0" &&
    parsedSched.upcoming[0].name === "Arsenal v Manchester United",
  "parseEspnTeamSchedule formats Football as 'Home v Away' and splits live, last 5 past, and next 5 upcoming"
);

// 4. Worker routes & D1 caching (fixtures 2m/15m TTL, teams search 24h TTL, schedule 5m TTL)
const db = d1();
await db.batch([
  db.prepare("INSERT INTO lumos_epg_channels (id, name, category, number, logo, source_id, at) VALUES (?, ?, ?, ?, ?, ?, ?)").bind("uk.sky.sports.pl", "Sky Sports Premier League", "Sports", 302, null, "4010", now),
  db.prepare("INSERT INTO lumos_epg_channels (id, name, category, number, logo, source_id, at) VALUES (?, ?, ?, ?, ?, ?, ?)").bind("uk.tnt.sports.1", "TNT Sports 1", "Sports", 314, null, "3625", now),
  db.prepare("INSERT INTO lumos_epg_channels (id, name, category, number, logo, source_id, at) VALUES (?, ?, ?, ?, ?, ?, ?)").bind("uk.sky.sports.f1", "Sky Sports F1", "Sports", 306, null, "3835", now),
  ...ukProgrammesFixture
    .filter((p) => p.channel_id !== "uk.sky.sports.news" && p.channel_id !== "uk.bbc.one")
    .map((p) =>
      db.prepare("INSERT INTO lumos_epg_programmes (channel_id, start_ms, end_ms, title, subtitle, description, is_sport) VALUES (?, ?, ?, ?, ?, ?, 1)")
        .bind(p.channel_id, p.start_ms, p.end_ms, p.title, p.subtitle, p.description)
    ),
]);

let scoreboardFetches = 0;
let teamDirFetches = 0;
let teamSchedFetches = 0;

globalThis.fetch = async (url) => {
  const u = String(url);
  if (u.includes("/soccer/eng.1/scoreboard")) {
    scoreboardFetches++;
    return new Response(JSON.stringify(eplScoreboard), { status: 200, headers: { "Content-Type": "application/json" } });
  }
  if (u.includes("/racing/f1/scoreboard")) {
    scoreboardFetches++;
    return new Response(JSON.stringify(f1Scoreboard), { status: 200, headers: { "Content-Type": "application/json" } });
  }
  if (u.includes("/hockey/nhl/scoreboard")) {
    scoreboardFetches++;
    return new Response("down", { status: 503 }); // fail-soft test!
  }
  if (u.includes("/scoreboard")) {
    scoreboardFetches++;
    return new Response(JSON.stringify({ events: [] }), { status: 200, headers: { "Content-Type": "application/json" } });
  }
  if (u.includes("/soccer/eng.1/teams?")) {
    teamDirFetches++;
    return new Response(JSON.stringify(teamsPayload), { status: 200, headers: { "Content-Type": "application/json" } });
  }
  if (u.includes("/teams?")) {
    teamDirFetches++;
    return new Response(JSON.stringify({ sports: [] }), { status: 200, headers: { "Content-Type": "application/json" } });
  }
  if (u.includes("/teams/359/schedule")) {
    teamSchedFetches++;
    return new Response(JSON.stringify({ events: schedEvents }), { status: 200, headers: { "Content-Type": "application/json" } });
  }
  throw new Error("unexpected fetch " + u);
};

const env = { DB: db, LUMOS_SETUP: "sports-test-phrase" };

// Fixtures handler fail-soft + D1 cross-matching + 2m LIVE TTL
const fix1 = await handleSportsFixtures({ date: "2026-11-22" }, env, now);
const arsenalFix = fix1.fixtures.find((f) => f.id === "701001");
const utdFix = fix1.fixtures.find((f) => f.id === "701002");
check(
  fix1.fixtures.length === 3 &&
    arsenalFix?.ukChannelId === "uk.sky.sports.pl" &&
    arsenalFix?.ukChannelName === "Sky Sports Premier League" &&
    utdFix?.ukChannelId === "uk.tnt.sports.1" &&
    scoreboardFetches === FIXTURE_LEAGUES.length,
  "handleSportsFixtures fails soft per league, enriches D1 UK channels, and fetches all leagues"
);

// Cache hit within 2m when LIVE
const fixCached = await handleSportsFixtures({ sport: "Formula 1", date: "2026-11-22" }, env, now + 60 * 1000);
check(
  fixCached.fixtures.length === 1 && fixCached.fixtures[0].sport === "Formula 1" && scoreboardFetches === FIXTURE_LEAGUES.length,
  "handleSportsFixtures uses 2m cache on sport filter when a fixture is LIVE"
);

// Cache expired after 2m when LIVE
await handleSportsFixtures({ date: "2026-11-22" }, env, now + 3 * MIN);
check(scoreboardFetches === FIXTURE_LEAGUES.length * 2, "handleSportsFixtures refreshes after 2m when a fixture is LIVE");

// 15m TTL when no fixture is LIVE
const idleNow = Date.UTC(2026, 10, 25, 12, 0, 0);
const upcomingOnlyScoreboard = { events: [eplScoreboard.events[1]] };
const origFetch = globalThis.fetch;
let idleFetches = 0;
globalThis.fetch = async (url) => {
  idleFetches++;
  if (String(url).includes("/soccer/eng.1/scoreboard")) {
    return new Response(JSON.stringify(upcomingOnlyScoreboard), { status: 200, headers: { "Content-Type": "application/json" } });
  }
  return new Response(JSON.stringify({ events: [] }), { status: 200, headers: { "Content-Type": "application/json" } });
};
await handleSportsFixtures({ date: "2026-11-25" }, env, idleNow);
await handleSportsFixtures({ date: "2026-11-25" }, env, idleNow + 10 * MIN);
check(idleFetches === FIXTURE_LEAGUES.length, "handleSportsFixtures caches 15m when no fixture is LIVE");
globalThis.fetch = origFetch;

// Teams search + 24h cache
const search1 = await handleTeamsSearch({ query: "chelsea" }, env, now);
const search2 = await handleTeamsSearch({ query: "arsenal" }, env, now + 2 * HOUR);
check(
  search1.teams.length === 1 &&
    search1.teams[0].teamId === "363" &&
    search2.teams[0].teamId === "359" &&
    teamDirFetches === 8,
  "handleTeamsSearch caches 24h team directory in lumos_meta"
);

// Teams schedule + 5m cache + UK broadcaster matching
const sched1 = await handleTeamsSchedule({ teams: [search2.teams[0]] }, env, now);
const sched2 = await handleTeamsSchedule({ teams: [search2.teams[0]] }, env, now + 2 * MIN);
check(
  sched1.schedules.length === 1 &&
    sched1.schedules[0].live[0]?.ukChannelId === "uk.sky.sports.pl" &&
    sched1.schedules[0].past.length === 5 &&
    sched1.schedules[0].upcoming.length === 5 &&
    sched2.schedules.length === 1 &&
    teamSchedFetches === 2, // played games + ?fixture=true for the games to come, then cached
  "handleTeamsSchedule caches 5m in lumos_meta and attaches UK broadcasters"
);

// Authenticated Worker HTTP routes
const call = async (path, body, token) => {
  const headers = { "Content-Type": "application/json" };
  if (token) headers.Authorization = `Bearer ${token}`;
  const r = await worker.fetch(new Request(`https://sync.example${path}`, { method: "POST", headers, body: JSON.stringify(body) }), env);
  return { status: r.status, body: await r.json() };
};

check((await call("/iptv/sports/fixtures", {})).status === 401, "POST /iptv/sports/fixtures requires authentication");
check((await call("/iptv/sports/teams/search", { query: "ars" })).status === 401, "POST /iptv/sports/teams/search requires authentication");
check((await call("/iptv/sports/teams/schedule", { teams: [] })).status === 401, "POST /iptv/sports/teams/schedule requires authentication");

const acc = await call("/account", { setup: "sports-test-phrase", name: "Sports TV" });
const token = acc.body.token;
const routeFix = await call("/iptv/sports/fixtures", { date: "2026-11-22" }, token);
const routeSearch = await call("/iptv/sports/teams/search", { query: "ars" }, token);
const routeSched = await call("/iptv/sports/teams/schedule", { teams: routeSearch.body.teams }, token);
check(
  routeFix.status === 200 &&
    Array.isArray(routeFix.body.fixtures) &&
    routeSearch.status === 200 &&
    routeSearch.body.teams.length === 1 &&
    routeSched.status === 200 &&
    routeSched.body.schedules.length === 1,
  "Worker authenticates and serves /iptv/sports/fixtures, /teams/search, and /teams/schedule"
);

// 5. Concurrent IPTV viewer presence (/iptv/presence & pruning)
check((await call("/iptv/presence", { action: "check", sourceId: "src-1" })).status === 401, "POST /iptv/presence requires authentication");
check((await call("/iptv/presence", { action: "bad", sourceId: "src-1" }, token)).status === 400, "POST /iptv/presence rejects invalid action");
check((await call("/iptv/presence", { action: "check", sourceId: "   " }, token)).status === 400, "POST /iptv/presence rejects blank sourceId");

// Link a second device ("Bedroom TV") onto the same account
const linkStart = await call("/link/start", { name: "Bedroom TV" });
await call("/link/approve", { code: linkStart.body.code }, token);
const linkPoll = await call("/link/poll", { poll: linkStart.body.poll });
const tokenB = linkPoll.body.token;
check(typeof tokenB === "string" && tokenB.length === 64, "second device linked to same account for presence tests");

const pStartA = await call("/iptv/presence", { action: "start", sourceId: "xtream-1", channelName: "Sky Sports Main Event" }, token);
check(pStartA.status === 200 && pStartA.body.ok === true && Array.isArray(pStartA.body.others) && pStartA.body.others.length === 0, "device A starts presence and sees no other viewers");

const pCheckB = await call("/iptv/presence", { action: "check", sourceId: "xtream-1" }, tokenB);
check(
  pCheckB.status === 200 &&
    pCheckB.body.others.length === 1 &&
    pCheckB.body.others[0].deviceName === "Sports TV" &&
    pCheckB.body.others[0].channelName === "Sky Sports Main Event",
  "device B checks presence and sees device A with channelName"
);

const pBeatA = await call("/iptv/presence", { action: "heartbeat", sourceId: "xtream-1", channelName: "BBC One" }, token);
const pCheckB2 = await call("/iptv/presence", { action: "check", sourceId: "xtream-1" }, tokenB);
check(
  pBeatA.status === 200 &&
    pBeatA.body.ok === true &&
    pCheckB2.body.others.length === 1 &&
    pCheckB2.body.others[0].channelName === "BBC One",
  "device A heartbeat updates channelName seen by device B"
);

const pStopA = await call("/iptv/presence", { action: "stop", sourceId: "xtream-1" }, token);
const pCheckB3 = await call("/iptv/presence", { action: "check", sourceId: "xtream-1" }, tokenB);
check(
  pStopA.status === 200 && pStopA.body.ok === true && pCheckB3.body.others.length === 0,
  "device A stop removes presence and device B sees empty others"
);

// Stale > 90s auto-prune & > 5m pruneIptvPresence cleanup
const devRows = env.DB.dump("lumos_devices").filter((d) => d.account === acc.body.account);
const hashA = devRows.find((d) => d.name === "Sports TV").token;
const hashB = devRows.find((d) => d.name === "Bedroom TV").token;
const meA = { account: acc.body.account, hash: hashA };
const meB = { account: acc.body.account, hash: hashB };
const t0 = now;

await handleIptvPresence({ action: "start", sourceId: "xtream-ttl", channelName: "ITV1" }, env, meA, t0);
const ttlWithin = await handleIptvPresence({ action: "check", sourceId: "xtream-ttl" }, env, meB, t0 + 89_000);
const ttlExpired = await handleIptvPresence({ action: "check", sourceId: "xtream-ttl" }, env, meB, t0 + 91_000);
check(
  ttlWithin.status === 200 &&
    ttlWithin.body.others.length === 1 &&
    ttlExpired.status === 200 &&
    ttlExpired.body.others.length === 0 &&
    !env.DB.dump("lumos_meta").some((m) => m.k === `iptv:pres:${acc.body.account}:xtream-ttl`),
  "presence entries older than 90s are pruned automatically and empty rows deleted"
);

await handleIptvPresence({ action: "start", sourceId: "xtream-old", channelName: "Channel 4" }, env, meA, t0);
await handleIptvPresence({ action: "start", sourceId: "xtream-fresh", channelName: "BBC Two" }, env, meA, t0 + 4 * MIN);
await pruneIptvPresence(env, t0 + 5 * MIN + 1000);
const metaKeysAfterPrune = env.DB.dump("lumos_meta").map((m) => m.k);
check(
  !metaKeysAfterPrune.includes(`iptv:pres:${acc.body.account}:xtream-old`) &&
    metaKeysAfterPrune.includes(`iptv:pres:${acc.body.account}:xtream-fresh`),
  "pruneIptvPresence deletes rows older than 5m while keeping fresher rows"
);

await handleIptvPresence({ action: "start", sourceId: "xtream-cron", channelName: "Sky News" }, env, meA, t0);
await pruneCaches(env, t0 + 6 * MIN);
check(
  !env.DB.dump("lumos_meta").some((m) => m.k === `iptv:pres:${acc.body.account}:xtream-cron`),
  "pruneCaches invokes pruneIptvPresence"
);

console.log(failures.length ? `\n${failures.length} failed` : "\nall passed");
process.exit(failures.length ? 1 : 0);
