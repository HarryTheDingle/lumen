// UK & US sports fixtures, team search, team schedules, and D1 UK TV broadcaster cross-matching.
// Pulls live scoreboards, team directories, and team schedules from ESPN's public endpoints with D1 caching.

import { getJson } from "./tmdb.js";

const ESPN_BASE = "https://site.api.espn.com/apis/site/v2/sports";
const MIN_MS = 60 * 1000;
const HOUR_MS = 60 * MIN_MS;
const DAY_MS = 24 * HOUR_MS;
const FIXTURES_LIVE_TTL_MS = 2 * MIN_MS;
const FIXTURES_IDLE_TTL_MS = 15 * MIN_MS;
const TEAMS_DIR_TTL_MS = 24 * HOUR_MS;
const TEAM_SCHED_TTL_MS = 5 * MIN_MS;
const EPG_LOOKBACK_MS = 45 * MIN_MS;
const EPG_LOOKAHEAD_MS = 90 * MIN_MS;

export const FIXTURE_LEAGUES = [
  // UK & European Football
  { sport: "Football", espnSport: "soccer", league: "eng.1", leagueName: "Premier League" },
  { sport: "Football", espnSport: "soccer", league: "eng.2", leagueName: "Championship" },
  { sport: "Football", espnSport: "soccer", league: "eng.3", leagueName: "League One" },
  { sport: "Football", espnSport: "soccer", league: "eng.4", leagueName: "League Two" },
  { sport: "Football", espnSport: "soccer", league: "eng.fa", leagueName: "FA Cup" },
  { sport: "Football", espnSport: "soccer", league: "eng.league_cup", leagueName: "Carabao Cup" },
  { sport: "Football", espnSport: "soccer", league: "uefa.champions", leagueName: "Champions League" },
  { sport: "Football", espnSport: "soccer", league: "uefa.europa", leagueName: "Europa League" },
  { sport: "Football", espnSport: "soccer", league: "sco.1", leagueName: "Scottish Premiership" },
  // US & UK/Global Sports
  { sport: "NFL", espnSport: "football", league: "nfl", leagueName: "NFL" },
  { sport: "NBA", espnSport: "basketball", league: "nba", leagueName: "NBA" },
  { sport: "NHL", espnSport: "hockey", league: "nhl", leagueName: "NHL" },
  { sport: "MLB", espnSport: "baseball", league: "mlb", leagueName: "MLB" },
  { sport: "Formula 1", espnSport: "racing", league: "f1", leagueName: "Formula 1" },
  { sport: "IndyCar", espnSport: "racing", league: "irl", leagueName: "IndyCar Series" },
  { sport: "NASCAR", espnSport: "racing", league: "nascar-premier", leagueName: "NASCAR Cup Series" },
  { sport: "UFC", espnSport: "mma", league: "ufc", leagueName: "UFC" },
  { sport: "Golf", espnSport: "golf", league: "pga", leagueName: "PGA Tour" },
  { sport: "Tennis", espnSport: "tennis", league: "atp", leagueName: "ATP Tour" },
];

export const TEAM_SEARCH_LEAGUES = [
  { sport: "Football", espnSport: "soccer", league: "eng.1", leagueName: "Premier League" },
  { sport: "Football", espnSport: "soccer", league: "eng.2", leagueName: "Championship" },
  { sport: "Football", espnSport: "soccer", league: "eng.3", leagueName: "League One" },
  { sport: "Football", espnSport: "soccer", league: "eng.4", leagueName: "League Two" },
  { sport: "Football", espnSport: "soccer", league: "eng.5", leagueName: "National League" },
  { sport: "Football", espnSport: "soccer", league: "sco.1", leagueName: "Scottish Premiership" },
  { sport: "Football", espnSport: "soccer", league: "uefa.champions", leagueName: "Champions League" },
  { sport: "NFL", espnSport: "football", league: "nfl", leagueName: "NFL" },
  { sport: "NBA", espnSport: "basketball", league: "nba", leagueName: "NBA" },
  { sport: "NHL", espnSport: "hockey", league: "nhl", leagueName: "NHL" },
  { sport: "MLB", espnSport: "baseball", league: "mlb", leagueName: "MLB" },
];

const LEAGUE_BY_CODE = new Map(FIXTURE_LEAGUES.map((l) => [l.league, l]));

const TEAM_ALIAS_MAP = new Map([
  ["manchester united", ["man utd", "man united", "manchester utd"]],
  ["manchester city", ["man city"]],
  ["tottenham hotspur", ["tottenham", "spurs"]],
  ["wolverhampton wanderers", ["wolves", "wolverhampton"]],
  ["newcastle united", ["newcastle"]],
  ["west ham united", ["west ham"]],
  ["brighton and hove albion", ["brighton", "brighton hove"]],
  ["nottingham forest", ["nottm forest", "nottingham forest", "nott m forest"]],
  ["leicester city", ["leicester"]],
  ["ipswich town", ["ipswich"]],
  ["sheffield united", ["sheff utd", "sheffield utd"]],
  ["sheffield wednesday", ["sheff wed", "sheffield wed"]],
  ["west bromwich albion", ["west brom", "wba"]],
  ["queens park rangers", ["qpr", "queens park rangers"]],
  ["afc bournemouth", ["bournemouth"]],
  ["aston villa", ["aston villa", "villa"]],
  ["crystal palace", ["crystal palace", "palace"]],
  ["glasgow rangers", ["rangers"]],
  ["celtic", ["celtic"]],
  ["heart of midlothian", ["hearts"]],
  ["hibernian", ["hibs", "hibernian"]],
  ["paris saint germain", ["psg", "paris sg", "paris saint germain"]],
  ["bayern munich", ["bayern", "bayern munich", "fc bayern"]],
  ["borussia dortmund", ["dortmund", "bvb"]],
  ["inter milan", ["inter", "inter milan", "internazionale"]],
  ["internazionale", ["inter", "inter milan"]],
  ["ac milan", ["ac milan", "milan"]],
  ["atletico madrid", ["atletico", "atletico madrid"]],
  ["real madrid", ["real madrid"]],
  ["barcelona", ["barca", "barcelona"]],
]);

const CLUB_NOISE_SUFFIX_RE = /\b(?:fc|afc|sc|cf|ac|ssc)\b/g;
const HIGHLIGHTS_RE = /\b(highlights?|review|roundup|goals\s+on\s+sunday|re-?run|repeat)\b/i;
const UK_SPORT_CHANNEL_NAME_RE = /^(?:sky sports|tnt sports|premier sports|bbc|itv|stv|utv|channel 4|s4c|racing tv|mutv|lfctv)/i;
const UK_SPORT_CHANNEL_ID_RE = /^uk\.(?:sky\.sports|tnt\.sports|premier\.sports|bbc|itv|stv|utv|ch4|s4c|racing\.tv|mutv|lfctv)/;

function normText(raw) {
  return String(raw || "")
    .normalize("NFKD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/&/g, " and ")
    .replace(/[^a-z0-9]+/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function normalizeDateKey(rawDate, nowMs) {
  const s = typeof rawDate === "string" ? rawDate.trim() : "";
  const dashed = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s);
  if (dashed) return `${dashed[1]}-${dashed[2]}-${dashed[3]}`;
  const compact = /^(\d{4})(\d{2})(\d{2})$/.exec(s);
  if (compact) return `${compact[1]}-${compact[2]}-${compact[3]}`;
  const d = new Date(nowMs);
  const y = d.getUTCFullYear();
  const m = String(d.getUTCMonth() + 1).padStart(2, "0");
  const day = String(d.getUTCDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}

function mapEspnStatus(statusObj) {
  const type = statusObj && statusObj.type ? statusObj.type : {};
  const state = String(type.state || "").toLowerCase();
  if (state === "in") return "LIVE";
  if (state === "post" || type.completed === true) return "FINAL";
  return "UPCOMING";
}

function extractScore(raw) {
  if (raw == null) return null;
  if (typeof raw === "string") return raw.trim() || null;
  if (typeof raw === "number" && Number.isFinite(raw)) return String(raw);
  if (typeof raw === "object") {
    if (raw.displayValue != null) {
      const s = String(raw.displayValue).trim();
      if (s) return s;
    }
    if (raw.value != null && Number.isFinite(Number(raw.value))) {
      return String(raw.value);
    }
  }
  return null;
}

function mapCompetitorSide(comp) {
  if (!comp || typeof comp !== "object") return null;
  const entity = comp.team || comp.athlete || {};
  const name = String(entity.displayName || entity.fullName || entity.name || entity.shortName || "").trim();
  if (!name) return null;
  const shortName = String(entity.shortDisplayName || entity.shortName || entity.location || "").trim() || null;
  const abbreviation = String(entity.abbreviation || "").trim() || null;
  // (a fighter's id is on the competitor, not the athlete: ESPN's headshots are kept under it)
  const athleteId = comp.athlete && !comp.team && comp.id != null && /^\d{3,12}$/.test(String(comp.id)) ? String(comp.id) : null;
  const logoUrl = String(
    entity.logo ||
    (Array.isArray(entity.logos) && entity.logos[0] && entity.logos[0].href) ||
    (entity.headshot && entity.headshot.href) ||
    (athleteId ? `https://a.espncdn.com/i/headshots/mma/players/full/${athleteId}.png` : "") ||
    ""
  ).trim() || null;
  return {
    id: entity.id != null ? String(entity.id) : athleteId,
    name,
    shortName,
    abbreviation,
    logoUrl,
    score: extractScore(comp.score),
    winner: Boolean(comp.winner),
  };
}

function extractBroadcasts(comp) {
  const seen = new Set();
  const out = [];
  const add = (val) => {
    const s = typeof val === "string" ? val.trim() : "";
    if (s && !seen.has(s)) {
      seen.add(s);
      out.push(s);
    }
  };
  for (const b of Array.isArray(comp && comp.broadcasts) ? comp.broadcasts : []) {
    if (!b || typeof b !== "object") continue;
    for (const n of Array.isArray(b.names) ? b.names : []) add(n);
    if (b.media && b.media.shortName) add(b.media.shortName);
    if (b.media && b.media.name) add(b.media.name);
  }
  for (const g of Array.isArray(comp && comp.geoBroadcasts) ? comp.geoBroadcasts : []) {
    if (!g || typeof g !== "object") continue;
    if (g.media && g.media.shortName) add(g.media.shortName);
    if (g.media && g.media.name) add(g.media.name);
  }
  return out;
}

function collectCompetitions(ev) {
  if (Array.isArray(ev.competitions) && ev.competitions.length) {
    return ev.competitions;
  }
  if (Array.isArray(ev.groupings)) {
    const out = [];
    for (const grp of ev.groupings) {
      if (grp && Array.isArray(grp.competitions)) out.push(...grp.competitions);
    }
    return out;
  }
  return [];
}

function selectPrimaryCompetitions(ev, leagueCfg) {
  const comps = collectCompetitions(ev);
  if (!comps.length) return [null];
  if (leagueCfg.espnSport === "racing") {
    const race = comps.find((c) => /race/i.test(String(c?.type?.abbreviation || c?.type?.text || "")));
    const live = comps.find((c) => mapEspnStatus(c?.status) === "LIVE");
    return [live || race || comps[comps.length - 1]];
  }
  if (leagueCfg.espnSport === "tennis" && comps.length > 1) {
    return comps.slice(0, 6);
  }
  if (leagueCfg.espnSport === "mma" && comps.length > 1) {
    return [comps[comps.length - 1]];
  }
  return [comps[0]];
}

function parseEspnEvent(ev, leagueCfg, jsonLeagueName) {
  if (!ev || typeof ev !== "object") return [];
  const baseId = String(ev.id || "").trim();
  const baseName = String(ev.name || ev.shortName || "").trim();
  if (!baseId || !baseName) return [];

  const comps = selectPrimaryCompetitions(ev, leagueCfg);
  const out = [];

  for (let i = 0; i < comps.length; i++) {
    const comp = comps[i];
    const startRaw = (comp && comp.date) || ev.date;
    const startMs = Date.parse(String(startRaw || ""));
    if (!Number.isFinite(startMs)) continue;

    const statusObj = (comp && comp.status) || ev.status || {};
    const status = mapEspnStatus(statusObj);
    let statusDetail = String(statusObj?.type?.shortDetail || statusObj?.type?.detail || "").trim() || null;
    // a finished race: who won it
    if (leagueCfg.espnSport === "racing" && status === "FINAL") {
      const first = (Array.isArray(comp && comp.competitors) ? comp.competitors : []).find((c) => Number(c && c.order) === 1);
      const who = first && first.athlete && (first.athlete.displayName || first.athlete.fullName);
      if (who) statusDetail = `Won by ${who}`;
    }
    const clock = status === "LIVE" && statusObj.displayClock ? String(statusObj.displayClock).trim() : null;
    const period = Number.isInteger(statusObj.period) && statusObj.period > 0 ? statusObj.period : null;
    const venue = String(comp?.venue?.fullName || "").trim() || null;

    const competitors = Array.isArray(comp && comp.competitors) ? comp.competitors : [];
    let homeComp = competitors.find((c) => String(c?.homeAway || "").toLowerCase() === "home") || null;
    let awayComp = competitors.find((c) => String(c?.homeAway || "").toLowerCase() === "away") || null;
    if (!homeComp && !awayComp && competitors.length === 2) {
      homeComp = competitors[0];
      awayComp = competitors[1];
    }

    const home = mapCompetitorSide(homeComp);
    const away = mapCompetitorSide(awayComp);
    const isFootball = leagueCfg.espnSport === "soccer" || leagueCfg.sport === "Football";
    const name = isFootball && home && away
      ? `${home.name} v ${away.name}`
      : (home && away && comps.length > 1 ? `${home.name} vs ${away.name}` : baseName);
    const shortName = isFootball && home && away
      ? `${home.shortName || home.name} v ${away.shortName || away.name}`
      : (String(ev.shortName || "").trim() || (home && away ? `${home.shortName || home.name} vs ${away.shortName || away.name}` : null));
    const compId = comps.length > 1 && comp && comp.id ? `${baseId}:${comp.id}` : baseId;

    out.push({
      id: compId,
      sport: leagueCfg.sport,
      league: leagueCfg.league,
      leagueName: jsonLeagueName || leagueCfg.leagueName,
      name,
      shortName,
      startMs,
      status,
      statusDetail,
      clock,
      period,
      venue,
      home,
      away,
      broadcasts: extractBroadcasts(comp),
      ukChannelId: null,
      ukChannelName: null,
    });
  }

  return out;
}

/** Pure ESPN scoreboard parser returning normalized fixture objects. */
export function parseEspnScoreboard(json, leagueCfg) {
  if (!json || typeof json !== "object") return [];
  const jsonLeagueName = String(json.leagues?.[0]?.name || "").trim() || leagueCfg.leagueName;
  const events = Array.isArray(json.events) ? json.events : [];
  const fixtures = [];
  for (const ev of events) {
    fixtures.push(...parseEspnEvent(ev, leagueCfg, jsonLeagueName));
  }
  return fixtures;
}

/** Pure ESPN team directory parser returning normalized team records. */
export function parseEspnTeams(json, leagueCfg) {
  if (!json || typeof json !== "object") return [];
  const teams = [];
  const sports = Array.isArray(json.sports) ? json.sports : [];
  const leagues = sports.flatMap((s) => (Array.isArray(s?.leagues) ? s.leagues : []));
  const rootTeams = Array.isArray(json.teams) ? json.teams : [];

  const entries = leagues.length
    ? leagues.flatMap((l) => (Array.isArray(l?.teams) ? l.teams.map((t) => ({ t, lName: l.name })) : []))
    : rootTeams.map((t) => ({ t, lName: leagueCfg.leagueName }));

  for (const { t, lName } of entries) {
    const team = t && t.team ? t.team : t;
    if (!team || team.id == null) continue;
    const name = String(team.displayName || team.name || "").trim();
    if (!name) continue;
    const shortName = String(team.shortDisplayName || team.nickname || team.location || name).trim();
    const logoUrl = String(team.logo || team.logos?.[0]?.href || "").trim() || null;
    teams.push({
      teamId: String(team.id),
      sport: leagueCfg.sport,
      league: leagueCfg.league,
      leagueName: String(lName || leagueCfg.leagueName).trim(),
      name,
      shortName,
      abbreviation: String(team.abbreviation || "").trim() || null,
      logoUrl,
    });
  }
  return teams;
}

/** Pure ESPN team schedule parser splitting events into live, last 5 past, and next 5 upcoming. */
export function parseEspnTeamSchedule(json, leagueCfg, now = Date.now()) {
  if (!json || typeof json !== "object") return { live: [], past: [], upcoming: [] };
  const events = Array.isArray(json.events) ? json.events : [];
  const parsed = [];
  for (const ev of events) {
    parsed.push(...parseEspnEvent(ev, leagueCfg, leagueCfg.leagueName));
  }

  const live = [];
  const past = [];
  const upcoming = [];
  for (const f of parsed) {
    if (f.status === "LIVE") {
      live.push(f);
    } else if (f.status === "FINAL" || (f.status === "UPCOMING" && f.startMs < now - 3 * HOUR_MS)) {
      past.push(f);
    } else {
      upcoming.push(f);
    }
  }

  live.sort((a, b) => a.startMs - b.startMs);
  past.sort((a, b) => b.startMs - a.startMs);
  upcoming.sort((a, b) => a.startMs - b.startMs);

  return {
    live,
    past: past.slice(0, 5),
    upcoming: upcoming.slice(0, 5),
  };
}

/** Build normalized alias phrases for a team side to match UK TV Guide programme listings. */
export function teamAliases(side) {
  if (!side) return [];
  const rawNames = typeof side === "string"
    ? [side]
    : [side.name, side.shortName].filter(Boolean);
  const aliases = new Set();

  for (const raw of rawNames) {
    const n = normText(raw);
    if (!n) continue;
    aliases.add(n);
    const stripped = n.replace(CLUB_NOISE_SUFFIX_RE, "").replace(/\s+/g, " ").trim();
    if (stripped.length >= 3) aliases.add(stripped);

    const mapped = TEAM_ALIAS_MAP.get(n) || TEAM_ALIAS_MAP.get(stripped);
    if (mapped) {
      for (const a of mapped) aliases.add(a);
    }

    const words = stripped.split(" ").filter(Boolean);
    if (words.length >= 2) {
      const last = words[words.length - 1];
      if (last.length >= 4 && !["united", "city", "town", "athletic", "rovers", "wanderers", "albion", "hotspur", "madrid", "milan"].includes(last)) {
        aliases.add(last);
      }
    }
  }

  return [...aliases].filter((a) => a.length >= 3);
}

function containsAliasPhrase(paddedHaystack, alias) {
  return paddedHaystack.includes(` ${alias} `);
}

function channelMatchPriority(channelId) {
  if (channelId === "uk.sky.sports.pl" || channelId === "uk.sky.sports.football" || channelId === "uk.sky.sports.f1") return 6;
  if (channelId.startsWith("uk.tnt.sports.")) return 5;
  if (channelId.startsWith("uk.bbc.") || channelId.startsWith("uk.itv.")) return 5;
  if (channelId.startsWith("uk.premier.sports.")) return 4;
  if (channelId === "uk.sky.sports.main") return 4;
  if (channelId === "uk.sky.sports.news") return 1;
  return 3;
}

function matchesNonTeamEvent(fixture, paddedText) {
  if (fixture.sport === "Formula 1") {
    if (!(/\b(formula 1|f1|grand prix|gp)\b/.test(paddedText))) return false;
    const tokens = normText(fixture.name).replace(/\b(formula|one|grand|prix|f1|gp)\b/g, " ").trim().split(/\s+/).filter((w) => w.length >= 4);
    return tokens.some((t) => containsAliasPhrase(paddedText, t));
  }
  if (fixture.sport === "IndyCar" || fixture.sport === "NASCAR") {
    const series = fixture.sport === "IndyCar" ? /\bindy\s?car\b/ : /\bnascar\b/;
    if (!series.test(paddedText)) return false;
    const tokens = normText(fixture.name).replace(/\b(grand|prix|race|the|of|indycar|nascar|cup|series|gp)\b/g, " ").trim().split(/\s+/).filter((w) => w.length >= 4);
    return !tokens.length || tokens.some((t) => containsAliasPhrase(paddedText, t));
  }
  if (fixture.sport === "UFC") {
    const m = /\bufc\s*(\d+)\b/i.exec(fixture.name);
    if (m && paddedText.includes(` ufc ${m[1]} `)) return true;
    return /\bufc\b/.test(paddedText) && normText(fixture.name).split(" ").some((w) => w.length >= 5 && containsAliasPhrase(paddedText, w));
  }
  const titleTokens = normText(fixture.name).split(" ").filter((w) => w.length >= 5 && !["tour", "open", "masters", "championship"].includes(w));
  return titleTokens.some((t) => containsAliasPhrase(paddedText, t));
}

/**
 * Cross-reference a single [fixture] against D1 UK sports/terrestrial [ukProgrammes] rows
 * within [startMs - 45m, startMs + 90m]. Returns { channelId, channelName } or null.
 */
export function matchUkBroadcasterForFixture(fixture, ukProgrammes) {
  if (!fixture || !Array.isArray(ukProgrammes) || !ukProgrammes.length) return null;
  const winStart = fixture.startMs - EPG_LOOKBACK_MS;
  const winEnd = fixture.startMs + EPG_LOOKAHEAD_MS;
  const homeAliases = teamAliases(fixture.home);
  const awayAliases = teamAliases(fixture.away);
  const isTeamMatch = homeAliases.length > 0 && awayAliases.length > 0;

  let best = null;
  let bestScore = -1;

  for (const p of ukProgrammes) {
    const chId = String(p.channel_id || p.channelId || "");
    const chName = String(p.channel_name || p.channelName || "");
    if (!UK_SPORT_CHANNEL_ID_RE.test(chId) && !UK_SPORT_CHANNEL_NAME_RE.test(chName)) continue;

    const pStart = Number(p.start_ms ?? p.startMs);
    const pEnd = Number(p.end_ms ?? p.endMs);
    if (!Number.isFinite(pStart) || !Number.isFinite(pEnd)) continue;

    const startsInWindow = pStart >= winStart && pStart <= winEnd;
    const spansKickoff = pStart < fixture.startMs && pEnd >= fixture.startMs + 30 * MIN_MS;
    if (!startsInWindow && !spansKickoff) continue;

    if (HIGHLIGHTS_RE.test(String(p.title || ""))) continue;

    const titleNorm = normText([p.title, p.subtitle].filter(Boolean).join(" "));
    const fullNorm = normText([p.title, p.subtitle, p.description].filter(Boolean).join(" "));
    const paddedTitle = ` ${titleNorm} `;
    const paddedFull = ` ${fullNorm} `;

    let matched = false;
    let titleHit = false;
    if (isTeamMatch) {
      const homeInTitle = homeAliases.some((a) => containsAliasPhrase(paddedTitle, a));
      const awayInTitle = awayAliases.some((a) => containsAliasPhrase(paddedTitle, a));
      const homeInFull = homeInTitle || homeAliases.some((a) => containsAliasPhrase(paddedFull, a));
      const awayInFull = awayInTitle || awayAliases.some((a) => containsAliasPhrase(paddedFull, a));
      matched = homeInFull && awayInFull;
      titleHit = homeInTitle && awayInTitle;
    } else {
      matched = matchesNonTeamEvent(fixture, paddedTitle) || matchesNonTeamEvent(fixture, paddedFull);
      titleHit = matchesNonTeamEvent(fixture, paddedTitle);
    }
    if (!matched) continue;

    const score = (titleHit ? 20 : 10) + channelMatchPriority(chId);
    if (score > bestScore) {
      bestScore = score;
      best = { channelId: chId, channelName: chName || chId };
    }
  }

  return best;
}

async function loadUkSportProgrammesWindow(env, minStartMs, maxStartMs) {
  if (!env || !env.DB) return [];
  const from = Math.floor(minStartMs - EPG_LOOKBACK_MS - 2 * HOUR_MS);
  const to = Math.floor(maxStartMs + EPG_LOOKAHEAD_MS + 2 * HOUR_MS);
  const rows = (await env.DB.prepare(
    `SELECT p.channel_id, p.start_ms, p.end_ms, p.title, p.subtitle, p.description, c.name AS channel_name
     FROM lumos_epg_programmes p
     JOIN lumos_epg_channels c ON c.id = p.channel_id
     WHERE p.end_ms >= ? AND p.start_ms <= ?`
  ).bind(from, to).all()).results || [];
  return rows.filter((r) => UK_SPORT_CHANNEL_ID_RE.test(r.channel_id) || UK_SPORT_CHANNEL_NAME_RE.test(r.channel_name || ""));
}

async function attachUkBroadcasters(env, fixtures) {
  if (!fixtures.length) return fixtures;
  const starts = fixtures.map((f) => f.startMs);
  const minStart = Math.min(...starts);
  const maxStart = Math.max(...starts);
  const ukProgs = await loadUkSportProgrammesWindow(env, minStart, maxStart);
  if (!ukProgs.length) return fixtures;

  return fixtures.map((f) => {
    const match = matchUkBroadcasterForFixture(f, ukProgs);
    if (!match) return f;
    return { ...f, ukChannelId: match.channelId, ukChannelName: match.channelName };
  });
}

function filterBySport(fixtures, sportQuery) {
  const raw = typeof sportQuery === "string" ? sportQuery.trim() : "";
  if (!raw || /^all$/i.test(raw)) return fixtures;
  const q = normText(raw);
  return fixtures.filter((f) => {
    const s = normText(f.sport);
    const l = normText(f.leagueName);
    if (s === q || l === q) return true;
    if (q === "soccer" && s === "football") return true;
    if (q === "f1" && s === "formula 1") return true;
    if (q === "indycar" && l.includes("indycar")) return true;
    if (q === "nascar" && l.includes("nascar")) return true;
    return false;
  });
}

function sortFixtures(fixtures) {
  const rankStatus = (s) => (s === "LIVE" ? 0 : s === "UPCOMING" ? 1 : 2);
  return [...fixtures].sort((a, b) => {
    const sr = rankStatus(a.status) - rankStatus(b.status);
    if (sr !== 0) return sr;
    if (a.status === "FINAL") return b.startMs - a.startMs;
    return a.startMs - b.startMs;
  });
}

async function readMetaJson(env, key) {
  const raw = (await env.DB.prepare("SELECT v FROM lumos_meta WHERE k = ?").bind(key).all()).results?.[0]?.v;
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch (_) {
    return null;
  }
}

async function writeMetaJson(env, key, val) {
  await env.DB.prepare("INSERT OR REPLACE INTO lumos_meta (k, v) VALUES (?, ?)")
    .bind(key, JSON.stringify(val))
    .run();
}

async function fetchJsonWith(fetchImpl, url) {
  if (fetchImpl === globalThis.fetch) return getJson(url, { timeout: 8000 });
  try {
    const res = await fetchImpl(url, {
      headers: { "User-Agent": "Lumen/1.0", Accept: "application/json" },
      signal: AbortSignal.timeout(8000),
    });
    return res && res.ok ? await res.json().catch(() => null) : null;
  } catch (_) {
    return null;
  }
}

/**
 * POST /iptv/sports/fixtures handler ({ sport?, date? }).
 * Caches daily multi-league scoreboards in lumos_meta (2m if any fixture LIVE, 15m otherwise)
 * and cross-references D1 UK EPG channels.
 */
export async function handleSportsFixtures(body = {}, env, now = Date.now(), fetchImpl = globalThis.fetch) {
  const hasExplicitDate = typeof (body && body.date) === "string" && body.date.trim().length > 0;
  const dateKey = normalizeDateKey(body && body.date, now);
  const espnDate = dateKey.replace(/-/g, "");
  const cacheKey = `sports:fixtures:${dateKey}`;

  const cached = await readMetaJson(env, cacheKey);
  if (cached && Array.isArray(cached.fixtures)) {
    const anyLive = cached.fixtures.some((f) => f.status === "LIVE");
    const ttl = anyLive ? FIXTURES_LIVE_TTL_MS : FIXTURES_IDLE_TTL_MS;
    if (now - Number(cached.at || 0) < ttl) {
      const epgLastRaw = (await env.DB.prepare("SELECT v FROM lumos_meta WHERE k = 'epg:uk:last'").all()).results?.[0]?.v;
      const epgLast = Number(epgLastRaw) || 0;
      const fixtures = epgLast > Number(cached.at || 0)
        ? await attachUkBroadcasters(env, cached.fixtures)
        : cached.fixtures;
      return {
        updatedAt: Number(cached.updatedAt || cached.at || now),
        fixtures: filterBySport(fixtures, body && body.sport),
      };
    }
  }

  const settled = await Promise.allSettled(
    FIXTURE_LEAGUES.map(async (cfg) => {
      const qs = hasExplicitDate ? `?dates=${espnDate}` : "";
      const url = `${ESPN_BASE}/${cfg.espnSport}/${cfg.league}/scoreboard${qs}`;
      const json = await fetchJsonWith(fetchImpl, url);
      return json ? parseEspnScoreboard(json, cfg) : [];
    })
  );

  const rawFixtures = [];
  for (const res of settled) {
    if (res.status === "fulfilled" && Array.isArray(res.value)) {
      rawFixtures.push(...res.value);
    }
  }

  const enriched = sortFixtures(await attachUkBroadcasters(env, rawFixtures));
  if (enriched.length || !cached) {
    await writeMetaJson(env, cacheKey, { at: now, updatedAt: now, fixtures: enriched });
  }

  return {
    updatedAt: now,
    fixtures: filterBySport(enriched, body && body.sport),
  };
}

/** Pure search across cached team directory entries, returning top [limit] ranked matches. */
export function searchCachedTeams(directory, query, limit = 20) {
  const q = normText(query);
  if (!q || !Array.isArray(directory)) return [];

  const scored = [];
  for (const team of directory) {
    if (!team || !team.name) continue;
    const nameN = normText(team.name);
    const shortN = normText(team.shortName || "");
    const abbrN = normText(team.abbreviation || "");

    let score = 0;
    if (nameN === q || shortN === q || abbrN === q) score = 100;
    else if (nameN.startsWith(q) || shortN.startsWith(q)) score = 80;
    else if (` ${nameN} `.includes(` ${q} `) || ` ${shortN} `.includes(` ${q} `)) score = 60;
    else if (nameN.includes(q) || shortN.includes(q)) score = 40;

    if (score > 0) scored.push({ team, score });
  }

  scored.sort((a, b) => b.score - a.score || a.team.name.localeCompare(b.team.name));
  return scored.slice(0, limit).map(({ team }) => ({
    teamId: team.teamId,
    sport: team.sport,
    league: team.league,
    leagueName: team.leagueName,
    name: team.name,
    shortName: team.shortName,
    logoUrl: team.logoUrl || null,
  }));
}

/**
 * POST /iptv/sports/teams/search handler ({ query }).
 * Caches UK Football + NFL/NBA/NHL/MLB team directories in lumos_meta for 24h.
 */
export async function handleTeamsSearch(body = {}, env, now = Date.now(), fetchImpl = globalThis.fetch) {
  const query = typeof (body && body.query) === "string" ? body.query.trim() : "";
  if (!query) return { teams: [] };

  const cacheKey = "sports:teams:directory:v2";
  const cached = await readMetaJson(env, cacheKey);
  let directory = cached && Array.isArray(cached.teams) && now - Number(cached.at || 0) < TEAMS_DIR_TTL_MS
    ? cached.teams
    : null;

  if (!directory) {
    const settled = await Promise.allSettled(
      TEAM_SEARCH_LEAGUES.map(async (cfg) => {
        const url = `${ESPN_BASE}/${cfg.espnSport}/${cfg.league}/teams?limit=200`;
        const json = await fetchJsonWith(fetchImpl, url);
        return json ? parseEspnTeams(json, cfg) : [];
      })
    );
    const dedup = new Map();
    for (const res of settled) {
      if (res.status !== "fulfilled" || !Array.isArray(res.value)) continue;
      for (const t of res.value) {
        const key = `${t.sport}:${t.teamId}`;
        if (!dedup.has(key)) dedup.set(key, t);
      }
    }
    directory = [...dedup.values()];
    if (directory.length) {
      await writeMetaJson(env, cacheKey, { at: now, teams: directory });
    } else if (cached && Array.isArray(cached.teams)) {
      directory = cached.teams;
    }
  }

  return { teams: searchCachedTeams(directory, query, 20) };
}

function resolveLeagueForTeam(team) {
  const leagueCode = typeof team.league === "string" ? team.league.trim() : "";
  if (LEAGUE_BY_CODE.has(leagueCode)) return LEAGUE_BY_CODE.get(leagueCode);
  const sport = normText(team.sport);
  if (sport === "football" || sport === "soccer") return { sport: "Football", espnSport: "soccer", league: leagueCode || "eng.1", leagueName: team.leagueName || "Football" };
  if (sport === "nfl") return LEAGUE_BY_CODE.get("nfl");
  if (sport === "nba") return LEAGUE_BY_CODE.get("nba");
  if (sport === "nhl") return LEAGUE_BY_CODE.get("nhl");
  if (sport === "mlb") return LEAGUE_BY_CODE.get("mlb");
  return null;
}

/**
 * POST /iptv/sports/teams/schedule handler ({ teams }).
 * Fetches /teams/{teamId}/schedule per saved team (5m D1 cache) and attaches UK EPG matches on live/upcoming games.
 */
export async function handleTeamsSchedule(body = {}, env, now = Date.now(), fetchImpl = globalThis.fetch) {
  const rawTeams = Array.isArray(body && body.teams) ? body.teams.slice(0, 20) : [];
  if (!rawTeams.length) return { updatedAt: now, schedules: [] };

  const rawGroups = await Promise.all(
    rawTeams.map(async (rawTeam) => {
      if (!rawTeam || typeof rawTeam !== "object") return null;
      const teamId = String(rawTeam.teamId || "").trim();
      if (!/^[A-Za-z0-9_-]{1,32}$/.test(teamId)) return null;
      const cfg = resolveLeagueForTeam(rawTeam);
      if (!cfg) return null;

      const team = {
        teamId,
        sport: String(rawTeam.sport || cfg.sport),
        league: cfg.league,
        leagueName: String(rawTeam.leagueName || cfg.leagueName),
        name: String(rawTeam.name || teamId),
        shortName: rawTeam.shortName ? String(rawTeam.shortName) : null,
        logoUrl: rawTeam.logoUrl ? String(rawTeam.logoUrl) : null,
      };

      const cacheKey = `sports:sched:${cfg.league}:${teamId}`;
      const cached = await readMetaJson(env, cacheKey);
      let split = cached && now - Number(cached.at || 0) < TEAM_SCHED_TTL_MS
        ? { live: cached.live || [], past: cached.past || [], upcoming: cached.upcoming || [] }
        : null;

      if (!split) {
        const url = `${ESPN_BASE}/${cfg.espnSport}/${cfg.league}/teams/${encodeURIComponent(teamId)}/schedule`;
        // (ESPN's football schedule lists only played games; the matches to come need ?fixture=true)
        const [played, toCome] = await Promise.all([
          fetchJsonWith(fetchImpl, url),
          cfg.espnSport === "soccer" ? fetchJsonWith(fetchImpl, `${url}?fixture=true`) : Promise.resolve(null),
        ]);
        const json = played || toCome
          ? {
            ...(played || toCome),
            events: [...((played && played.events) || []), ...((toCome && toCome.events) || [])]
              .filter((e, i, all) => !e || !e.id || all.findIndex((x) => x && x.id === e.id) === i),
          }
          : null;
        if (json) {
          split = parseEspnTeamSchedule(json, cfg, now);
          await writeMetaJson(env, cacheKey, { at: now, ...split });
        } else if (cached) {
          split = { live: cached.live || [], past: cached.past || [], upcoming: cached.upcoming || [] };
        } else {
          split = { live: [], past: [], upcoming: [] };
        }
      }

      return { team, ...split };
    })
  );

  const validGroups = rawGroups.filter(Boolean);
  const toMatch = validGroups.flatMap((g) => [...g.live, ...g.upcoming]);
  let matchedById = new Map();
  if (toMatch.length) {
    const matched = await attachUkBroadcasters(env, toMatch);
    matchedById = new Map(matched.map((f) => [`${f.league}:${f.id}`, f]));
  }

  const schedules = validGroups.map((g) => ({
    team: g.team,
    live: g.live.map((f) => matchedById.get(`${f.league}:${f.id}`) || f),
    past: g.past,
    upcoming: g.upcoming.map((f) => matchedById.get(`${f.league}:${f.id}`) || f),
  }));

  return { updatedAt: now, schedules };
}

// ---- One match's page, and Formula 1's standings (ESPN, free) ----
// Kept in Cloudflare's edge cache, not D1: a live match is asked for every half minute, and D1's free plan counts
// every row written.

async function edgeCached(key, ttlSeconds, make) {
  const cache = typeof caches !== "undefined" && caches.default ? caches.default : null;
  const req = new Request(`https://lumen-cache.internal/${encodeURIComponent(key)}`);
  if (cache) {
    const hit = await cache.match(req).catch(() => null);
    if (hit) return hit.json();
  }
  const value = await make();
  if (cache && value) {
    await cache.put(req, new Response(JSON.stringify(value), {
      headers: { "Content-Type": "application/json", "Cache-Control": `max-age=${ttlSeconds}` },
    })).catch(() => {});
  }
  return value;
}

/** As [edgeCached], but kept only [shortTtl] seconds when [complete] says the answer is missing parts (a call that failed) */
async function edgeCachedPartial(key, ttlSeconds, shortTtl, complete, make) {
  const cache = typeof caches !== "undefined" && caches.default ? caches.default : null;
  const req = new Request(`https://lumen-cache.internal/${encodeURIComponent(key)}`);
  if (cache) {
    const hit = await cache.match(req).catch(() => null);
    if (hit) return hit.json();
  }
  const value = await make();
  if (cache && value) {
    const ttl = complete(value) ? ttlSeconds : shortTtl;
    await cache.put(req, new Response(JSON.stringify(value), {
      headers: { "Content-Type": "application/json", "Cache-Control": `max-age=${ttl}` },
    })).catch(() => {});
  }
  return value;
}

/** ESPN's match summary, as the match page needs it */
export function parseEspnMatchSummary(json, league) {
  const comp = json && json.header && Array.isArray(json.header.competitions) ? json.header.competitions[0] : null;
  if (!comp) return null;
  const st = (comp.status && comp.status.type) || {};
  const state = st.state === "in" ? "LIVE" : st.state === "post" ? "FINAL" : "UPCOMING";
  const side = (c) => c && {
    id: c.team && c.team.id ? String(c.team.id) : null,
    name: (c.team && (c.team.displayName || c.team.name)) || "",
    shortName: (c.team && (c.team.shortDisplayName || c.team.abbreviation)) || null,
    logoUrl: (c.team && ((c.team.logos && c.team.logos[0] && c.team.logos[0].href) || c.team.logo)) || null,
    score: c.score != null ? String(c.score) : null,
    winner: Boolean(c.winner),
  };
  const comps = Array.isArray(comp.competitors) ? comp.competitors : [];
  const home = side(comps.find((c) => c.homeAway === "home") || comps[0]);
  const away = side(comps.find((c) => c.homeAway === "away") || comps[1]);
  const homeId = home && home.id;

  // stats side by side: [label, home, away]
  const teams = (json.boxscore && Array.isArray(json.boxscore.teams)) ? json.boxscore.teams : [];
  const statsFor = (id) => {
    const t = teams.find((x) => x.team && String(x.team.id) === String(id));
    return new Map(((t && t.statistics) || []).map((s) => [s.label || s.name, s.displayValue]));
  };
  const hs = statsFor(homeId);
  const as = statsFor(away && away.id);
  const WANT = ["Possession", "SHOTS", "ON GOAL", "Corner Kicks", "Fouls", "Yellow Cards", "Red Cards", "Offsides", "Saves"];
  const NAMES = { SHOTS: "Shots", "ON GOAL": "On target", "Corner Kicks": "Corners", "Yellow Cards": "Yellow cards", "Red Cards": "Red cards" };
  const stats = WANT.filter((k) => hs.has(k) || as.has(k)).map((k) => ({ label: NAMES[k] || k, home: hs.get(k) ?? "0", away: as.get(k) ?? "0" }));

  // goals, cards, subs (not kick-off and half-time markers)
  const KINDS = { goal: "Goal", "yellow-card": "Yellow card", "red-card": "Red card", substitution: "Substitution", "own-goal": "Own goal", "penalty---scored": "Penalty" };
  const events = (Array.isArray(json.keyEvents) ? json.keyEvents : [])
    .filter((e) => e && e.type && (KINDS[e.type.type] || /goal|card|substitution|penalty/i.test(e.type.text || "")))
    .map((e) => ({
      minute: (e.clock && e.clock.displayValue) || "",
      kind: KINDS[e.type.type] || e.type.text || "",
      home: e.team && String(e.team.id) === String(homeId),
      who: (Array.isArray(e.participants) ? e.participants : []).map((p) => p && p.athlete && p.athlete.displayName).filter(Boolean).slice(0, 2),
    }));

  const lineup = (id) => {
    const r = (Array.isArray(json.rosters) ? json.rosters : []).find((x) => x.team && String(x.team.id) === String(id));
    return ((r && r.roster) || []).filter((p) => p.starter).map((p) => ({
      number: p.jersey || "",
      name: (p.athlete && (p.athlete.shortName || p.athlete.displayName)) || "",
      position: (p.position && p.position.abbreviation) || "",
    }));
  };
  const venue = json.gameInfo && json.gameInfo.venue ? json.gameInfo.venue.fullName || null : null;
  return {
    id: String(comp.id || ""),
    league,
    status: state,
    statusDetail: st.shortDetail || st.detail || null,
    clock: (comp.status && comp.status.displayClock) || null,
    startMs: Date.parse(comp.date || "") || null,
    venue,
    home, away, stats, events,
    homeLineup: lineup(homeId),
    awayLineup: lineup(away && away.id),
  };
}

/** POST /iptv/sports/match ({ league, eventId }): one match's score, stats, goals and cards, and line-ups */
export async function handleSportsMatch(body = {}, env, now = Date.now(), fetchImpl = globalThis.fetch) {
  const league = String((body && body.league) || "").trim();
  const eventId = String((body && body.eventId) || "").trim();
  const cfg = LEAGUE_BY_CODE.get(league) || TEAM_SEARCH_LEAGUES.find((l) => l.league === league);
  if (!cfg || !/^\d{1,12}$/.test(eventId)) return { status: 400, body: { error: "Unknown match." } };
  const match = await edgeCached(`match:${league}:${eventId}`, 30, async () => {
    const json = await fetchJsonWith(fetchImpl, `${ESPN_BASE}/${cfg.espnSport}/${cfg.league}/summary?event=${eventId}`);
    return json ? parseEspnMatchSummary(json, league) : null;
  });
  return match ? { status: 200, body: { match } } : { status: 502, body: { error: "Match details unavailable." } };
}

/** POST /iptv/sports/f1/standings: drivers' and constructors' championship points */
export async function handleF1Standings(body = {}, env, now = Date.now(), fetchImpl = globalThis.fetch) {
  // (any ESPN racing series: f1 by default, irl = IndyCar, nascar-premier = NASCAR Cup)
  const series = ["f1", "irl", "nascar-premier"].includes(body && body.series) ? body.series : "f1";
  const out = await edgeCached(`${series}:standings`, 6 * 3600, async () => {
    const json = await fetchJsonWith(fetchImpl, `https://site.api.espn.com/apis/v2/sports/racing/${series}/standings`);
    const kids = json && Array.isArray(json.children) ? json.children : [];
    if (!kids.length) return null;
    const table = (child, athlete) => ((child && child.standings && child.standings.entries) || []).map((e, i) => {
      const stat = (n) => ((e.stats || []).find((s) => s.name === n) || {}).displayValue;
      return {
        rank: Number(stat("rank")) || i + 1,
        name: athlete ? (e.athlete && e.athlete.displayName) || "" : (e.team && (e.team.displayName || e.team.name)) || (e.athlete && e.athlete.displayName) || "",
        points: stat("championshipPts") || stat("points") || "0",
      };
    }).sort((a, b) => a.rank - b.rank);
    return {
      season: (json.seasons && json.seasons[0] && json.seasons[0].year) || null,
      drivers: table(kids.find((c) => /driver/i.test(c.name || "")) || kids[0], true),
      constructors: table(kids.find((c) => /constructor|team/i.test(c.name || "")), false),
    };
  });
  return out ? { status: 200, body: out } : { status: 502, body: { error: "Standings unavailable." } };
}

// ---- The sports pages' extras: league tables, team pictures, and Formula 1's race weekend ----

/** POST /iptv/sports/table ({ league }): the league table, as ESPN has it */
export async function handleSportsTable(body = {}, env, now = Date.now(), fetchImpl = globalThis.fetch) {
  const league = String((body && body.league) || "eng.1").trim();
  const cfg = LEAGUE_BY_CODE.get(league) || TEAM_SEARCH_LEAGUES.find((l) => l.league === league);
  if (!cfg || cfg.espnSport !== "soccer") return { status: 400, body: { error: "Unknown league." } };
  const table = await edgeCached(`table:${league}`, 30 * 60, async () => {
    const json = await fetchJsonWith(fetchImpl, `https://site.api.espn.com/apis/v2/sports/soccer/${league}/standings`);
    const entries = json && json.children && json.children[0] && json.children[0].standings ? json.children[0].standings.entries || [] : [];
    if (!entries.length) return null;
    const rows = entries.map((e, i) => {
      const stat = (n) => ((e.stats || []).find((x) => x.name === n) || {}).displayValue;
      const t = e.team || {};
      return {
        rank: Number(stat("rank")) || i + 1,
        id: t.id ? String(t.id) : null,
        name: t.displayName || t.name || "",
        shortName: t.shortDisplayName || t.abbreviation || null,
        logoUrl: (Array.isArray(t.logos) && t.logos[0] && t.logos[0].href) || null,
        played: Number(stat("gamesPlayed")) || 0,
        won: Number(stat("wins")) || 0,
        drawn: Number(stat("ties")) || 0,
        lost: Number(stat("losses")) || 0,
        goalDiff: stat("pointDifferential") || "0",
        points: Number(stat("points")) || 0,
      };
    }).sort((a, b) => a.rank - b.rank);
    return { league, leagueName: cfg.leagueName || (json.children[0].name || ""), rows };
  });
  return table ? { status: 200, body: table } : { status: 502, body: { error: "Table unavailable." } };
}

const SPORTSDB = "https://www.thesportsdb.com/api/v1/json/123";

/** TheSportsDB's JSON (asked as a browser would: it turns away some clients); null when it can't be had */
async function sportsDbJson(path, fetchImpl) {
  try {
    const res = await fetchImpl(`${SPORTSDB}/${path}`, { headers: { "User-Agent": "Mozilla/5.0 (Lumen)", Accept: "application/json" }, signal: AbortSignal.timeout(8000) });
    if (!res.ok) { console.log(`sportsdb ${res.status} for ${path}`); return null; }
    return await res.json().catch(() => null);
  } catch (e) { console.log(`sportsdb failed for ${path}: ${e && e.message}`); return null; }
}

/** One team's pictures from TheSportsDB (wide fan art for billboards, the badge), kept a week */
async function teamArt(name, fetchImpl) {
  const clean = String(name || "").trim();
  if (!clean) return null;
  return edgeCached(`art2:team:${clean.toLowerCase()}`, 7 * 24 * 3600, async () => {
    let json = null;
    try {
      const res = await fetchImpl(`${SPORTSDB}/searchteams.php?t=${encodeURIComponent(clean)}`, { headers: { "User-Agent": "Mozilla/5.0 (Lumen)", Accept: "application/json" }, signal: AbortSignal.timeout(8000) });
      if (!res.ok) { console.log(`sportsdb ${res.status} for ${clean}`); return null; }
      json = await res.json().catch(() => null);
    } catch (e) { console.log(`sportsdb failed for ${clean}: ${e && e.message}`); return null; }
    // (a failed lookup isn't kept: only an answer is, even an empty one)
    if (!json) return null;
    const teams = (json && Array.isArray(json.teams) ? json.teams : []).filter((t) => t && /soccer|basketball|ice hockey|motorsport/i.test(t.strSport || ""));
    const t = teams[0];
    if (!t) return { fanart: [], badge: null, banner: null };
    return {
      fanart: [t.strFanart1, t.strFanart2, t.strFanart3, t.strFanart4].filter(Boolean),
      badge: t.strBadge || null,
      banner: t.strBanner || null,
    };
  });
}

/** A league's own pictures from TheSportsDB (wide action photos), kept a week */
async function leagueArt(id, fetchImpl) {
  if (!/^\d{3,6}$/.test(id)) return null;
  return edgeCached(`art2:league:${id}`, 7 * 24 * 3600, async () => {
    const json = await sportsDbJson(`lookupleague.php?id=${id}`, fetchImpl);
    if (!json) return null;
    const l = Array.isArray(json.leagues) ? json.leagues[0] : null;
    return { fanart: l ? [l.strFanart1, l.strFanart2, l.strFanart3, l.strFanart4].filter(Boolean) : [], badge: (l && l.strBadge) || null, banner: (l && l.strBanner) || null };
  });
}

/** POST /iptv/sports/art ({ teams: [names] }): billboard pictures for each team asked for (up to 12); "league:<id>" asks for a league's */
export async function handleSportsArt(body = {}, env, now = Date.now(), fetchImpl = globalThis.fetch) {
  const names = [...new Set((Array.isArray(body && body.teams) ? body.teams : []).map((n) => String(n || "").trim()).filter(Boolean))].slice(0, 12);
  const out = {};
  // (TheSportsDB's free key allows a few calls a second: a handful at a time)
  for (let i = 0; i < names.length; i += 2) {
    const got = await Promise.all(names.slice(i, i + 2).map((n) => (n.startsWith("league:") ? leagueArt(n.slice(7), fetchImpl) : teamArt(n, fetchImpl)).catch(() => null)));
    got.forEach((a, j) => { if (a) out[names[i + j]] = a; });
  }
  return { status: 200, body: { teams: out } };
}

/** POST /iptv/sports/f1/weekend: the next race weekend (sessions, circuit, track outline, last year's winner, artwork) */
export async function handleF1Weekend(body = {}, env, now = Date.now(), fetchImpl = globalThis.fetch) {
  const out = await edgeCachedPartial(`f1:weekend5:${Math.floor(now / (3 * 3600 * 1000))}`, 3 * 3600, 600, (v) => Boolean(v.art && v.lastWinner && v.circuit && v.circuit.track), async () => {
    // (the race still to finish: Jolpica's "next" moves on as soon as a race starts, so the last one is kept until it's over)
    const [nextJ, lastJ] = await Promise.all([
      fetchJsonWith(fetchImpl, "https://api.jolpi.ca/ergast/f1/current/next.json"),
      fetchJsonWith(fetchImpl, "https://api.jolpi.ca/ergast/f1/current/last.json"),
    ]);
    const pick = (j) => j && j.MRData && j.MRData.RaceTable && j.MRData.RaceTable.Races && j.MRData.RaceTable.Races[0];
    const at = (d) => d && d.date ? Date.parse(`${d.date}T${d.time || "12:00:00Z"}`) : null;
    let race = pick(nextJ);
    const last = pick(lastJ);
    if (last && at(last) && now < at(last) + 3 * 3600 * 1000) race = last;
    if (!race) return null;
    const LABELS = [["FirstPractice", "Practice 1"], ["SecondPractice", "Practice 2"], ["ThirdPractice", "Practice 3"], ["SprintQualifying", "Sprint qualifying"], ["SprintShootout", "Sprint qualifying"], ["Sprint", "Sprint"], ["Qualifying", "Qualifying"]];
    const sessions = LABELS.filter(([k]) => race[k]).map(([k, label]) => ({ label, startMs: at(race[k]) }));
    sessions.push({ label: "Race", startMs: at(race) });
    sessions.sort((a, b) => a.startMs - b.startMs);
    const season = Number(race.season);
    const circuit = race.Circuit || {};

    // (Jolpica allows a few calls a second: last year's winner is asked for after the next/last pair, with one more try)
    const prevUrl = `https://api.jolpi.ca/ergast/f1/${season - 1}/circuits/${encodeURIComponent(circuit.circuitId || "")}/results/1.json`;
    const [meetings, art] = await Promise.all([
      fetchJsonWith(fetchImpl, `https://api.openf1.org/v1/meetings?year=${season}`),
      sportsDbJson("eventsnextleague.php?id=4370", fetchImpl),
    ]);
    const notes = [];
    const tryPrev = async () => {
      try {
        const res = await fetchImpl(prevUrl, { headers: { "User-Agent": "Mozilla/5.0 (Lumen)", Accept: "application/json" }, signal: AbortSignal.timeout(8000) });
        if (!res.ok) { notes.push(`prev ${res.status}`); return null; }
        return await res.json().catch(() => { notes.push("prev bad json"); return null; });
      } catch (e) { notes.push(`prev ${e && e.message}`); return null; }
    };
    let prev = await tryPrev();
    if (!prev) { await new Promise((r) => setTimeout(r, 900)); prev = await tryPrev(); }
    if (!prev) console.log(`f1 weekend: no last-year result from ${prevUrl}`);
    if (!art) console.log("f1 weekend: no TheSportsDB events");
    if (!Array.isArray(meetings)) console.log("f1 weekend: no OpenF1 meetings");
    const raceDay = (race.date || "").slice(0, 10);
    const meeting = (Array.isArray(meetings) ? meetings : []).find((m) => {
      const s = Date.parse(m.date_start || ""), e = Date.parse(m.date_end || "");
      const d = Date.parse(raceDay);
      return s && e && d >= s - 86400000 && d <= e + 86400000;
    }) || null;
    let track = null;
    if (meeting && meeting.circuit_key) {
      const mv = await fetchJsonWith(fetchImpl, `https://api.multiviewer.app/api/v1/circuits/${meeting.circuit_key}/${season}`);
      if (mv && Array.isArray(mv.x) && mv.x.length > 20) {
        // (the outline thinned to about 240 points: plenty for a drawn map)
        const step = Math.max(1, Math.floor(mv.x.length / 240));
        const xs = [], ys = [];
        for (let i = 0; i < mv.x.length; i += step) { xs.push(Math.round(mv.x[i])); ys.push(Math.round(mv.y[i])); }
        track = { x: xs, y: ys, rotation: Number(mv.rotation) || 0, corners: Array.isArray(mv.corners) ? mv.corners.length : null };
      }
    }
    const won = pick(prev);
    const winner = won && won.Results && won.Results[0];
    const events = (art && Array.isArray(art.events) ? art.events : []).filter((e) => String(e.strEvent || "").toLowerCase().startsWith(String(race.raceName || "").toLowerCase()));
    const ev = events.find((e) => !/practice|qualifying|sprint/i.test(e.strEvent || "")) || events[0];
    return {
      season, round: Number(race.round) || null,
      raceName: race.raceName || "",
      officialName: meeting ? meeting.meeting_official_name || null : null,
      circuit: {
        name: circuit.circuitName || "",
        locality: (circuit.Location && circuit.Location.locality) || "",
        country: (circuit.Location && circuit.Location.country) || "",
        type: meeting ? meeting.circuit_type || null : null,
        image: meeting ? meeting.circuit_image || null : null,
        flag: meeting ? meeting.country_flag || null : null,
        laps: winner && winner.laps ? Number(winner.laps) : null,
        track,
      },
      lastWinner: winner ? {
        name: `${(winner.Driver && winner.Driver.givenName) || ""} ${(winner.Driver && winner.Driver.familyName) || ""}`.trim(),
        team: (winner.Constructor && winner.Constructor.name) || "",
        time: (winner.Time && winner.Time.time) || null,
      } : null,
      sessions,
      art: ev ? { thumb: ev.strThumb || null, poster: ev.strPoster || null, banner: ev.strBanner || null } : null,
      notes,
    };
  });
  return out ? { status: 200, body: out } : { status: 502, body: { error: "Race weekend unavailable." } };
}

// ---- UFC's next card, tennis (rankings, the slams, the draw), and golf's leaderboard (ESPN, free; fighters' cut-outs from TheSportsDB) ----

const head = (sport, id) => (id && /^\d{2,12}$/.test(String(id)) ? `https://a.espncdn.com/i/headshots/${sport}/players/full/${id}.png` : null);

/** A fighter's full-length cut-out (transparent PNG) from TheSportsDB, kept a month (null kept too: most have none) */
async function fighterCutout(name, fetchImpl) {
  const clean = String(name || "").trim();
  if (!clean) return null;
  const v = await edgeCached(`cut:${clean.toLowerCase()}`, 30 * 24 * 3600, async () => {
    const json = await sportsDbJson(`searchplayers.php?p=${encodeURIComponent(clean.replace(/ /g, "_"))}`, fetchImpl);
    if (!json) return null;
    const p = (Array.isArray(json.player) ? json.player : []).find((x) => /fight|mma/i.test(x.strSport || "")) || null;
    return { url: p ? p.strCutout || p.strRender || null : null };
  });
  return v ? v.url : null;
}

/** POST /iptv/sports/ufc: the next (or current) UFC card, main event first, with each fighter's record, headshot and cut-out */
export async function handleUfc(body = {}, env, now = Date.now(), fetchImpl = globalThis.fetch) {
  const out = await edgeCachedPartial(`ufc:card2:${Math.floor(now / (30 * 60 * 1000))}`, 1800, 120, (v) => v.bouts.length > 0 && Boolean(v.bouts[0].red.cutout || v.bouts[0].blue.cutout), async () => {
    const json = await fetchJsonWith(fetchImpl, `${ESPN_BASE}/mma/ufc/scoreboard`);
    const ev = json && Array.isArray(json.events) ? json.events[0] : null;
    if (!ev) return null;
    const comps = Array.isArray(ev.competitions) ? [...ev.competitions].reverse() : [];
    const side = (c) => {
      const a = (c && c.athlete) || {};
      const rec = c && Array.isArray(c.records) && c.records[0] ? c.records[0].summary : null;
      return { id: c && c.id ? String(c.id) : null, name: a.displayName || a.fullName || "", short: a.shortName || null, record: rec, flag: (a.flag && a.flag.href) || null, headshot: head("mma", c && c.id), cutout: null, winner: Boolean(c && c.winner) };
    };
    const bouts = comps.map((c, i) => {
      const cs = Array.isArray(c.competitors) ? c.competitors : [];
      const st = (c.status && c.status.type) || {};
      return {
        id: String(c.id || i),
        weight: (c.type && (c.type.abbreviation || c.type.text)) || "",
        main: i === 0,
        startMs: Date.parse(c.date || "") || null,
        state: st.state === "in" ? "LIVE" : st.state === "post" ? "FINAL" : "UPCOMING",
        result: st.shortDetail || null,
        red: side(cs.find((x) => x.order === 1) || cs[0]),
        blue: side(cs.find((x) => x.order === 2) || cs[1]),
      };
    }).filter((b) => b.red.name && b.blue.name);
    // (cut-outs for the top of the card: a few at a time, TheSportsDB's free key is slow to anger)
    const top = bouts.slice(0, 5);
    for (const b of top) {
      const [r, bl] = await Promise.all([fighterCutout(b.red.name, fetchImpl), fighterCutout(b.blue.name, fetchImpl)]);
      b.red.cutout = r; b.blue.cutout = bl;
    }
    return {
      name: ev.name || "", shortName: ev.shortName || null,
      startMs: Date.parse(ev.date || "") || null,
      venue: (ev.competitions && ev.competitions[0] && ev.competitions[0].venue && ev.competitions[0].venue.fullName) || null,
      bouts,
    };
  });
  return out ? { status: 200, body: out } : { status: 502, body: { error: "UFC card unavailable." } };
}

function tennisPlayer(c) {
  const a = (c && c.athlete) || {};
  return {
    id: c && c.id ? String(c.id) : null,
    name: a.displayName || "", short: a.shortName || null,
    flag: (a.flag && a.flag.href) || null, country: (a.flag && a.flag.alt) || null,
    seed: c && c.curatedRank && c.curatedRank.current && c.curatedRank.current < 99 ? c.curatedRank.current : null,
    headshot: head("tennis", c && c.id),
    sets: (Array.isArray(c && c.linescores) ? c.linescores : []).map((s) => s.value),
    winner: Boolean(c && c.winner),
  };
}

/** An event's singles draws, the later rounds only (quarter-finals on, or the rounds being played), as round lists of matches */
function tennisDraws(ev) {
  const LATE = ["Round 4", "Quarterfinal", "Semifinal", "Final"];
  return (Array.isArray(ev.groupings) ? ev.groupings : [])
    .filter((g) => /singles/i.test((g.grouping && g.grouping.displayName) || ""))
    .map((g) => {
      const comps = (g.competitions || []).filter((c) => !/qualif/i.test((c.round && c.round.displayName) || ""));
      const playing = new Set(comps.filter((c) => c.status && c.status.type && c.status.type.state !== "post").map((c) => c.round && c.round.displayName));
      const names = [...new Set(comps.map((c) => c.round && c.round.displayName))].filter((r) => LATE.includes(r) || playing.has(r));
      return {
        name: g.grouping.displayName,
        rounds: names.map((r) => ({
          name: r,
          matches: comps.filter((c) => c.round && c.round.displayName === r).map((c) => {
            const st = (c.status && c.status.type) || {};
            const ps = Array.isArray(c.competitors) ? [...c.competitors].sort((a, b) => (a.order || 0) - (b.order || 0)) : [];
            return { id: String(c.id || ""), startMs: Date.parse(c.date || "") || null, state: st.state === "in" ? "LIVE" : st.state === "post" ? "FINAL" : "UPCOMING", p1: tennisPlayer(ps[0]), p2: tennisPlayer(ps[1]) };
          }).filter((m) => m.p1.name || m.p2.name),
        })),
      };
    });
}

/** POST /iptv/sports/tennis: rankings (ATP, WTA), the four slams this year and next, and the draw of what's on now */
export async function handleTennis(body = {}, env, now = Date.now(), fetchImpl = globalThis.fetch) {
  const out = await edgeCachedPartial(`tennis:v1:${Math.floor(now / (20 * 60 * 1000))}`, 1200, 300, (v) => v.rankings.atp.length > 0, async () => {
    const year = new Date(now).getUTCFullYear();
    const rank = async (tour) => {
      const json = await fetchJsonWith(fetchImpl, `${ESPN_BASE}/tennis/${tour}/rankings`);
      const r = json && Array.isArray(json.rankings) ? json.rankings[0] : null;
      return ((r && r.ranks) || []).slice(0, 20).map((x) => ({
        rank: x.current, previous: x.previous ?? null, points: x.points ?? null,
        id: x.athlete && x.athlete.id ? String(x.athlete.id) : null,
        name: (x.athlete && x.athlete.displayName) || "",
        flag: (x.athlete && x.athlete.flag && x.athlete.flag.href) || null,
        headshot: head("tennis", x.athlete && x.athlete.id),
      }));
    };
    const slamDates = [year, year + 1].flatMap((y) => [`${y}0120`, `${y}0601`, `${y}0705`, `${y}0905`]);
    const [atp, wta, nowAtp, nowWta, ...slamDays] = await Promise.all([
      rank("atp"), rank("wta"),
      fetchJsonWith(fetchImpl, `${ESPN_BASE}/tennis/atp/scoreboard`),
      fetchJsonWith(fetchImpl, `${ESPN_BASE}/tennis/wta/scoreboard`),
      ...slamDates.map((d) => fetchJsonWith(fetchImpl, `${ESPN_BASE}/tennis/atp/scoreboard?dates=${d}`)),
    ]);
    const slams = [];
    for (const day of slamDays) {
      for (const e of (day && day.events) || []) {
        if (!e.major || slams.some((s) => s.id === e.id)) continue;
        const draws = tennisDraws(e);
        const champ = (n) => { const d = draws.find((x) => x.name === n); const f = d && d.rounds.find((r) => r.name === "Final"); const m = f && f.matches[0]; return m && m.state === "FINAL" ? (m.p1.winner ? m.p1 : m.p2.winner ? m.p2 : null) : null; };
        slams.push({
          id: String(e.id), name: e.name, startMs: Date.parse(e.date || "") || null, endMs: Date.parse(e.endDate || "") || null,
          state: e.status && e.status.type ? (e.status.type.state === "in" ? "LIVE" : e.status.type.state === "post" ? "FINAL" : "UPCOMING") : "UPCOMING",
          men: champ("Men's Singles"), women: champ("Women's Singles"),
          lastMen: (e.previousWinners && e.previousWinners[0] && (e.previousWinners[0].displayName || e.previousWinners[0])) || null,
          lastWomen: (e.previousWinners && e.previousWinners[1] && (e.previousWinners[1].displayName || e.previousWinners[1])) || null,
        });
      }
    }
    slams.sort((a, b) => (a.startMs || 0) - (b.startMs || 0));
    const events = [];
    for (const j of [nowAtp, nowWta]) {
      const e = j && Array.isArray(j.events) ? j.events[0] : null;
      if (!e || events.some((x) => x.id === String(e.id))) continue;
      const st = e.status && e.status.type ? e.status.type.state : "pre";
      events.push({ id: String(e.id), name: e.name, major: Boolean(e.major), startMs: Date.parse(e.date || "") || null, endMs: Date.parse(e.endDate || "") || null, state: st === "in" ? "LIVE" : st === "post" ? "FINAL" : "UPCOMING", draws: tennisDraws(e) });
    }
    return { rankings: { atp, wta }, slams, events };
  });
  return out ? { status: 200, body: out } : { status: 502, body: { error: "Tennis unavailable." } };
}

function golfBoard(ev) {
  const c = ev && Array.isArray(ev.competitions) ? ev.competitions[0] : null;
  const st = (c && c.status && c.status.type) || {};
  const players = (c && Array.isArray(c.competitors) ? c.competitors : []).sort((a, b) => (a.order || 0) - (b.order || 0));
  const scoreOf = (p) => String(p.score != null ? (typeof p.score === "object" ? p.score.displayValue : p.score) : "");
  let lastScore = null, lastPos = 0;
  const rows = players.slice(0, 50).map((p, i) => {
    const sc = scoreOf(p);
    const pos = sc === lastScore ? lastPos : i + 1;
    lastScore = sc; lastPos = pos;
    const rounds = (Array.isArray(p.linescores) ? p.linescores : []).filter((l) => l.value != null && l.value >= 50).slice(0, 4).map((l) => ({ strokes: l.value, toPar: l.displayValue || null }));
    return { pos, name: (p.athlete && (p.athlete.displayName || p.athlete.fullName)) || "", flag: (p.athlete && p.athlete.flag && p.athlete.flag.href) || null, headshot: head("golf", p.id), toPar: sc || "E", rounds, thru: (p.status && p.status.thru) || null };
  });
  // ties marked T
  rows.forEach((r) => { r.tied = rows.filter((x) => x.pos === r.pos).length > 1; });
  return {
    id: String(ev.id), name: ev.name || "", startMs: Date.parse(ev.date || "") || null, endMs: Date.parse(ev.endDate || "") || null,
    state: st.state === "in" ? "LIVE" : st.state === "post" ? "FINAL" : "UPCOMING", detail: st.detail || null,
    course: (c && c.venue && c.venue.fullName) || (ev.courses && ev.courses[0] && ev.courses[0].name) || null,
    rows,
  };
}

/** POST /iptv/sports/golf: the PGA Tour's event now (or next), and the last one finished with its leaderboard */
export async function handleGolf(body = {}, env, now = Date.now(), fetchImpl = globalThis.fetch) {
  const out = await edgeCachedPartial(`golf:v1:${Math.floor(now / (10 * 60 * 1000))}`, 600, 300, (v) => Boolean(v.current || v.last), async () => {
    const day = (ms) => new Date(ms).toISOString().slice(0, 10).replace(/-/g, "");
    // (the last five weeks at once: ESPN gives a range of days' events, the latest finished one is the last)
    const [cur, back] = await Promise.all([
      fetchJsonWith(fetchImpl, `${ESPN_BASE}/golf/pga/scoreboard`),
      fetchJsonWith(fetchImpl, `${ESPN_BASE}/golf/pga/scoreboard?dates=${day(now - 35 * 86400000)}-${day(now)}`),
    ]);
    const current = cur && cur.events && cur.events[0] ? golfBoard(cur.events[0]) : null;
    const stateOf = (x) => (x.competitions && x.competitions[0] && x.competitions[0].status && x.competitions[0].status.type && x.competitions[0].status.type.state) || (x.status && x.status.type && x.status.type.state);
    const done = ((back && back.events) || []).filter((x) => stateOf(x) === "post" && (!current || String(x.id) !== current.id));
    const last = done.length ? golfBoard(done[done.length - 1]) : null;
    return { current, last };
  });
  return out ? { status: 200, body: out } : { status: 502, body: { error: "Golf unavailable." } };
}
