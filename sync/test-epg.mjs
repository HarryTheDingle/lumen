// Checks UK EPG parsing, Freeview/XMLTV refresh + gzip decompression, sport detection,
// phone-to-TV IPTV setup flow, and pruning against Node's SQLite D1 shim.
//   node sync/test-epg.mjs
import worker, { pruneCaches } from "./worker.js";
import {
  UK_CHANNELS,
  buildProgrammeWriteStatements,
  detectSportTag,
  enrichEpgProgramme,
  fetchSkySources,
  parseFreeviewGuide,
  parseSkySchedule,
  parseXmltv,
  pruneEpg,
  refreshUkEpg,
  resolveUkChannel,
} from "./epg.js";
import { d1 } from "./d1-sqlite.mjs";

const failures = [];
const check = (ok, what) => {
  console.log(`${ok ? "  ✓" : "  ✗"} ${what}`);
  if (!ok) failures.push(what);
};

function fmtXmltvTime(ms, offset = "+0000") {
  let localMs = ms;
  const m = /^([+-])(\d{2})(\d{2})$/.exec(offset);
  if (m) {
    const sign = m[1] === "-" ? -1 : 1;
    localMs += sign * (Number(m[2]) * 60 + Number(m[3])) * 60000;
  }
  const d = new Date(localMs);
  const pad = (n) => String(n).padStart(2, "0");
  return `${d.getUTCFullYear()}${pad(d.getUTCMonth() + 1)}${pad(d.getUTCDate())}${pad(d.getUTCHours())}${pad(d.getUTCMinutes())}${pad(d.getUTCSeconds())} ${offset}`;
}

async function gzipBytes(text) {
  const stream = new Blob([text]).stream().pipeThrough(new CompressionStream("gzip"));
  return new Uint8Array(await new Response(stream).arrayBuffer());
}

// 1. Canonical UK channel registry & resolution
const requiredIds = [
  "uk.bbc.one", "uk.bbc.two", "uk.itv.one", "uk.ch4", "uk.ch5", "uk.itv.two", "uk.bbc.three",
  "uk.bbc.four", "uk.itv.three", "uk.itv.four", "uk.e4", "uk.more4", "uk.film4", "uk.itvbe",
  "uk.5star", "uk.5usa", "uk.5action", "uk.5select", "uk.dave", "uk.drama", "uk.yesterday",
  "uk.w", "uk.alibi", "uk.gold", "uk.eden", "uk.bbc.scotland", "uk.stv", "uk.utv", "uk.s4c",
  "uk.bbc.alba", "uk.4seven", "uk.e4.extra", "uk.really", "uk.quest", "uk.quest.red",
  "uk.sky.news", "uk.bbc.news", "uk.gb.news", "uk.talktv", "uk.sky.showcase", "uk.sky.max",
  "uk.sky.atlantic", "uk.sky.witness", "uk.sky.comedy", "uk.sky.arts", "uk.sky.mix",
  "uk.sky.sports.main", "uk.sky.sports.pl", "uk.sky.sports.football", "uk.sky.sports.f1",
  "uk.sky.sports.cricket", "uk.sky.sports.golf", "uk.sky.sports.action", "uk.sky.sports.arena",
  "uk.sky.sports.tennis", "uk.sky.sports.racing", "uk.sky.sports.news", "uk.sky.sports.mix",
  "uk.sky.sports.plus", "uk.tnt.sports.1", "uk.tnt.sports.2", "uk.tnt.sports.3", "uk.tnt.sports.4",
  "uk.tnt.sports.ultimate", "uk.premier.sports.1", "uk.premier.sports.2", "uk.eurosport.1",
  "uk.eurosport.2", "uk.sky.cinema.premiere", "uk.sky.cinema.hits", "uk.sky.cinema.greats",
  "uk.sky.cinema.action", "uk.sky.cinema.comedy", "uk.cbeebies", "uk.cbbc", "uk.discovery",
  "uk.natgeo", "uk.mtv.uk", "ie.rte.one", "ie.rte.two",
];
check(
  requiredIds.every((id) => UK_CHANNELS[id] && UK_CHANNELS[id].id === id && UK_CHANNELS[id].name && UK_CHANNELS[id].category && Array.isArray(UK_CHANNELS[id].aliases)),
  "UK_CHANNELS defines all required canonical IDs with complete metadata"
);
check(resolveUkChannel("BBC Scotland")?.id === "uk.bbc.scotland", "resolveUkChannel maps BBC Scotland cleanly");
check(resolveUkChannel("BBC News HD")?.id === "uk.bbc.news", "resolveUkChannel maps BBC News HD cleanly");
check(resolveUkChannel("E4 Extra")?.id === "uk.e4.extra", "resolveUkChannel does not conflate E4 Extra with E4");
check(resolveUkChannel("TNT Sports 1 HD")?.id === "uk.tnt.sports.1", "resolveUkChannel maps TNT Sports 1 HD");
check(resolveUkChannel("BBC One +1") === null, "resolveUkChannel skips +1 timeshift channels");

// 2. Sport tag detection
check(detectSportTag("Premier League Live: Arsenal v Chelsea", "Sports", "Live top-flight football") === "Football", "detectSportTag detects Football");
check(detectSportTag("Live F1: British Grand Prix Qualifying", "Motorsport", "Silverstone action") === "Formula 1", "detectSportTag detects Formula 1");
check(detectSportTag("Matchroom Boxing: Heavyweight Title Fight", "Sport", "") === "Boxing", "detectSportTag detects Boxing");
check(detectSportTag("UFC 310: Main Card", "MMA", "Mixed martial arts from Las Vegas") === "UFC/MMA", "detectSportTag detects UFC/MMA");
check(detectSportTag("Six Nations Rugby: England v Ireland", "Sports", "") === "Rugby", "detectSportTag detects Rugby");
check(detectSportTag("The Ashes: First Test Day 2", "Cricket", "Live from Edgbaston") === "Cricket", "detectSportTag detects Cricket");
check(detectSportTag("Wimbledon: Men's Singles Final", "Tennis", "") === "Tennis", "detectSportTag detects Tennis");
check(detectSportTag("The Open Championship", "Golf", "Final round from Royal Troon") === "Golf", "detectSportTag detects Golf");
check(detectSportTag("Premier League Darts: Night 8", "Darts", "") === "Darts", "detectSportTag prioritizes Darts over Premier League Football");
check(detectSportTag("World Snooker Championship", "Snooker", "Crucible semi-final") === "Snooker", "detectSportTag detects Snooker");
check(detectSportTag("EastEnders", "Drama", "Life in Walford") === null, "detectSportTag returns null for non-sport drama");
check(detectSportTag("NFL RedZone", "American Football", "Live NFL coverage") === null, "detectSportTag does not tag NFL as UK Football");

// 3. Pure parsers: Freeview & XMLTV
const now = Date.UTC(2026, 10, 21, 18, 0, 0); // 2026-11-21T18:00:00Z
const HOUR = 3600 * 1000;
const DAY = 24 * HOUR;

const freeviewPayload = {
  status: "ok",
  data: {
    programs: [
      {
        service_id: 4164,
        title: "BBC One London",
        logo: "/logos/bbc1.png",
        events: [
          {
            main_title: "Match of the Day",
            secondary_title: "Saturday Highlights",
            synopsis: "Premier League football highlights & analysis.",
            start_time: new Date(now - 48 * HOUR).toISOString().replace("Z", "+0000"),
            duration: "PT1H15M",
            genre: "Sports",
            image: "/images/motd.jpg",
          },
          {
            main_title: "BBC News at Six",
            synopsis: "Latest UK & international news.",
            start_time: Math.floor(now / 1000),
            duration: 1800,
            genre: "News",
          },
        ],
      },
      {
        service_id: "999999",
        title: "Sky Sports Main Event",
        events: [
          {
            main_title: "Super Sunday: Liverpool v Man City",
            start_time: new Date(now + 20 * HOUR).toISOString(),
            end_time: new Date(now + 22 * HOUR).toISOString(),
            genre: "Sports",
          },
        ],
      },
    ],
  },
};

const parsedFv = parseFreeviewGuide(freeviewPayload, now);
check(
  parsedFv.channels.length === 2 &&
    parsedFv.channels.some((c) => c.id === "uk.bbc.one" && c.logo === "https://www.freeview.co.uk/logos/bbc1.png") &&
    parsedFv.channels.some((c) => c.id === "uk.sky.sports.main"),
  "parseFreeviewGuide maps Freeview service_id & channel titles to canonical IDs"
);
check(
  parsedFv.programmes.length === 3 &&
    parsedFv.programmes.find((p) => p.title === "Match of the Day")?.sportTag === "Football" &&
    parsedFv.programmes.find((p) => p.title === "Match of the Day")?.endMs === now - 48 * HOUR + 75 * 60 * 1000 &&
    parsedFv.programmes.find((p) => p.title === "BBC News at Six")?.isSport === false,
  "parseFreeviewGuide parses ISO durations, unix seconds, +0000 offsets, and sport flags"
);

// Build XMLTV fixture with >50 programmes across BBC One, Sky Sports Main Event, TNT Sports 1
const bulkXmlProgrammes = [];
for (let i = 0; i < 22; i++) {
  const s1 = now - 48 * HOUR + i * 2 * HOUR;
  const s2 = now - 40 * HOUR + i * 2 * HOUR;
  const s3 = now - 24 * HOUR + i * 2 * HOUR;
  bulkXmlProgrammes.push(
    `<programme start="${fmtXmltvTime(s1, "+0000")}" stop="${fmtXmltvTime(s1 + HOUR, "+0000")}" channel="BBC1.uk"><title lang="en">BBC Show &amp; Tell #${i}</title><desc><![CDATA[Episode ${i} on BBC One & friends]]></desc><category>Entertainment</category></programme>`,
    `<programme start="${fmtXmltvTime(s2, "+0100")}" stop="${fmtXmltvTime(s2 + HOUR, "+0100")}" channel="SkySportsME.uk"><title>Live F1: Session ${i}</title><desc>Formula 1 coverage</desc><category>Sports</category></programme>`,
    `<programme start="${fmtXmltvTime(s3, "+0000")}" stop="${fmtXmltvTime(s3 + HOUR, "+0000")}" channel="tnt1"><title>Champions League Matchday ${i}</title><sub-title>Arsenal v Bayern &#x2019;26</sub-title><category>Football</category></programme>`
  );
}
// Also include one stale programme (>72h ago) that rolling ingestion window should exclude
bulkXmlProgrammes.push(
  `<programme start="${fmtXmltvTime(now - 96 * HOUR)}" stop="${fmtXmltvTime(now - 95 * HOUR)}" channel="BBC1.uk"><title>Too Old Programme</title></programme>`
);

const xmlFixture = `<?xml version="1.0" encoding="UTF-8"?>
<tv>
  <channel id="BBC1.uk"><display-name>BBC One HD</display-name><icon src="https://img.example/bbc1.png"/></channel>
  <channel id="SkySportsME.uk"><display-name>Sky Sports Main Event</display-name></channel>
  <channel id="tnt1"><display-name>TNT Sports 1</display-name></channel>
  ${bulkXmlProgrammes.join("\n  ")}
</tv>`;

const parsedXml = parseXmltv(xmlFixture, now);
check(
  parsedXml.channels.length === 3 &&
    parsedXml.channels.some((c) => c.id === "uk.bbc.one") &&
    parsedXml.channels.some((c) => c.id === "uk.sky.sports.main") &&
    parsedXml.channels.some((c) => c.id === "uk.tnt.sports.1"),
  "parseXmltv maps BBC One, Sky Sports Main Event, and TNT Sports 1"
);
check(
  parsedXml.programmes.length === 66 &&
    !parsedXml.programmes.some((p) => p.title === "Too Old Programme") &&
    parsedXml.programmes.some((p) => p.channelId === "uk.bbc.one" && p.title === "BBC Show & Tell #0") &&
    parsedXml.programmes.some((p) => p.channelId === "uk.sky.sports.main" && p.sportTag === "Formula 1") &&
    parsedXml.programmes.some((p) => p.channelId === "uk.tnt.sports.1" && p.sportTag === "Football" && p.subtitle === "Arsenal v Bayern ’26"),
  "parseXmltv decodes entities/CDATA, applies +0100 offset, filters +/-72h window, and tags Football + Formula 1"
);

// 4. refreshUkEpg (gzipped XMLTV >50 batch chunking + 12h freshness skip + Freeview fallback)
const db = d1();
const xmlGz = await gzipBytes(xmlFixture);
let fetchCount = 0;
let freeviewCalls = [];
globalThis.fetch = async (url) => {
  fetchCount++;
  const u = String(url);
  if (u === "https://epg.example/uk.xml.gz") {
    return new Response(xmlGz, { status: 200, headers: { "Content-Type": "application/gzip" } });
  }
  if (u.startsWith("https://www.freeview.co.uk/api/tv-guide")) {
    freeviewCalls.push(u);
    return new Response(JSON.stringify(freeviewPayload), { status: 200, headers: { "Content-Type": "application/json" } });
  }
  throw new Error("unexpected fetch " + u);
};

const env = { DB: db, LUMOS_SETUP: "epg-test-phrase", UK_EPG_XMLTV_URL: "https://epg.example/uk.xml.gz" };

const res1 = await refreshUkEpg(env, now);
check(
  res1.ok === true && res1.channels === 3 && res1.programmes === 66 && fetchCount === 1,
  "refreshUkEpg decompresses .xml.gz and batches >50 programmes into D1"
);
check(db.dump("lumos_epg_programmes").length === 66 && db.dump("lumos_epg_channels").length === 3, "D1 stores all 3 channels and 66 programmes");

const resSkip = await refreshUkEpg(env, now + 2 * HOUR);
check(resSkip.skipped === true && fetchCount === 1, "refreshUkEpg skips refresh within 12 hours");

const resForce = await refreshUkEpg(env, now + 2 * HOUR, globalThis.fetch, true);
check(resForce.skipped === false && resForce.ok === true && fetchCount === 2, "refreshUkEpg runs when force === true");

// Freeview fallback when UK_EPG_XMLTV_URL is unset
const fvEnv = { DB: d1(), LUMOS_SETUP: "epg-test-phrase" };
const resFv = await refreshUkEpg(fvEnv, now);
check(
  resFv.ok === true && freeviewCalls.length === 2 && resFv.sources.includes("freeview") && resFv.programmes === 3,
  "refreshUkEpg fetches Freeview today + tomorrow when UK_EPG_XMLTV_URL is unset"
);

// 5. Worker API routes: auth, /epg/uk, /epg/refresh, /sync IPTV keys, /iptv setup flow
const call = async (path, body, token, method = "POST") => {
  const headers = {};
  if (method === "POST") headers["Content-Type"] = "application/json";
  if (token) headers.Authorization = `Bearer ${token}`;
  const r = await worker.fetch(
    new Request(`https://sync.example${path}`, {
      method,
      headers,
      body: method === "POST" ? JSON.stringify(body) : undefined,
    }),
    env
  );
  const ct = r.headers.get("Content-Type") || "";
  return {
    status: r.status,
    contentType: ct,
    body: ct.includes("application/json") ? await r.json() : await r.text(),
  };
};

const acc = await call("/account", { setup: "epg-test-phrase", name: "Living Room TV" });
const tvToken = acc.body.token;
const tvAccount = acc.body.account;
check(acc.status === 200 && tvAccount.startsWith("lumos:"), "created TV account for EPG & IPTV tests");

check((await call("/epg/uk", {})).status === 401, "POST /epg/uk requires authentication");

const epgRes = await call("/epg/uk", { fromMs: now - 48 * HOUR, toMs: now + 24 * HOUR }, tvToken);
check(
  epgRes.status === 200 &&
    Number.isInteger(epgRes.body.updatedAt) &&
    epgRes.body.channels.length === 3 &&
    epgRes.body.programmes.some((p) => p.startMs === now - 48 * HOUR && p.channelId === "uk.bbc.one") &&
    epgRes.body.programmes.some((p) => p.startMs > now && p.channelId === "uk.tnt.sports.1") &&
    epgRes.body.programmes.every((p) => typeof p.isSport === "boolean" && Number.isInteger(p.startMs) && Number.isInteger(p.endMs)),
  "POST /epg/uk returns catch-up (-48h) + live + upcoming programmes with integer timestamps and boolean isSport"
);

const epgFiltered = await call("/epg/uk", { channels: ["uk.sky.sports.main"] }, tvToken);
check(
  epgFiltered.status === 200 &&
    epgFiltered.body.channels.length === 1 &&
    epgFiltered.body.channels[0].id === "uk.sky.sports.main" &&
    epgFiltered.body.programmes.every((p) => p.channelId === "uk.sky.sports.main"),
  "POST /epg/uk filters by channels array"
);

// /epg/refresh route
const epgRefreshRoute = await call("/epg/refresh", { force: true }, tvToken);
check(epgRefreshRoute.status === 200 && epgRefreshRoute.body.ok === true, "POST /epg/refresh triggers forced refresh");

// /sync allowlist for iptvSources and iptvPrefs:<viewer>
const tSync = Date.now();
const syncAllowed = await call("/sync", {
  since: 0,
  entries: [
    { k: "iptvPrefs:main", t: tSync, v: { sortBy: "number", hiddenCategories: ["Adult"] } },
  ],
}, tvToken);
check(syncAllowed.status === 200, "/sync accepts iptvPrefs:main");

const syncBadKey = await call("/sync", {
  since: 0,
  entries: [{ k: "iptvPrefs:bad key!", t: tSync, v: {} }],
}, tvToken);
check(syncBadKey.status === 400, "/sync still rejects disallowed keys");

// Phone-to-TV IPTV setup flow
const start1 = await call("/iptv/setup/start", {}, tvToken);
check(
  start1.status === 200 &&
    /^[0-9A-Z]{4}-[0-9A-Z]{4}$/.test(start1.body.code) &&
    start1.body.path === `/iptv?code=${start1.body.code}` &&
    start1.body.expires > Date.now(),
  "POST /iptv/setup/start returns 8-char code, path, and expiry"
);

const pageRes = await call(`/iptv?code=${encodeURIComponent(start1.body.code)}`, null, null, "GET");
const scriptMatch = /<script>([\s\S]*?)<\/script>/.exec(pageRes.body);
let scriptParses = false;
if (scriptMatch) {
  try {
    new Function(scriptMatch[1]);
    scriptParses = true;
  } catch (_) {
    scriptParses = false;
  }
}
check(
  pageRes.status === 200 &&
    pageRes.contentType.includes("text/html") &&
    pageRes.body.includes("#000000") &&
    pageRes.body.includes(start1.body.code) &&
    pageRes.body.includes("/iptv/setup/submit") &&
    scriptParses,
  "GET /iptv renders dark mobile HTML5 setup page with valid client JS"
);

const xssCheck = await call("/iptv?code=%3Cscript%3Ealert(1)%3C/script%3E", null, null, "GET");
check(!xssCheck.body.includes("<script>alert(1)</script>"), "GET /iptv escapes code query parameter against XSS");

// Validation guard leaves setup code intact
const badSubmit = await call("/iptv/setup/submit", {
  code: start1.body.code,
  source: { type: "xtream", serverUrl: "ftp://bad.example", username: "u", password: "" },
});
check(badSubmit.status === 400, "POST /iptv/setup/submit rejects invalid Xtream input");

// 1) Xtream source
const subXtream = await call("/iptv/setup/submit", {
  code: start1.body.code,
  source: {
    type: "xtream",
    name: "Main Xtream",
    serverUrl: "http://xtream.example:8080/",
    username: "alice",
    password: "secret",
    outputFormat: "m3u8",
    maxConnections: 25,
    userAgent: "LumosTV/1.0",
  },
});
check(
  subXtream.status === 200 &&
    subXtream.body.ok === true &&
    subXtream.body.count === 1 &&
    subXtream.body.source.type === "xtream" &&
    subXtream.body.source.serverUrl === "http://xtream.example:8080" &&
    subXtream.body.source.maxConnections === 20 &&
    typeof subXtream.body.source.id === "string",
  "POST /iptv/setup/submit validates Xtream source, clamps maxConnections to 20, and generates id"
);

// Single-use code invalidated
const reuseCode = await call("/iptv/setup/submit", {
  code: start1.body.code,
  source: { type: "m3u", url: "https://m3u.example/list.m3u" },
});
check(reuseCode.status === 404, "single-use setup code cannot be reused after submit");

// 2) M3U source (loose lowercase code formatting)
const start2 = await call("/iptv/setup/start", {}, tvToken);
const subM3u = await call("/iptv/setup/submit", {
  code: start2.body.code.toLowerCase().replace("-", " "),
  source: {
    type: "m3u",
    name: "Backup M3U",
    url: "https://m3u.example/uk.m3u?token=abc",
    epgUrl: "https://m3u.example/epg.xml.gz",
    maxConnections: 2,
  },
});
check(subM3u.status === 200 && subM3u.body.count === 2 && subM3u.body.source.type === "m3u", "POST /iptv/setup/submit appends M3U without overwriting Xtream");

// 3) Stalker source
const start3 = await call("/iptv/setup/start", {}, tvToken);
const subStalker = await call("/iptv/setup/submit", {
  code: start3.body.code,
  source: {
    type: "stalker",
    name: "Living Room Stalker",
    portalUrl: "http://portal.example/c/",
    macAddress: "001a79aabbcc",
    serialNumber: "SN123456",
  },
});
check(
  subStalker.status === 200 &&
    subStalker.body.count === 3 &&
    subStalker.body.source.type === "stalker" &&
    subStalker.body.source.macAddress === "00:1A:79:AA:BB:CC",
  "POST /iptv/setup/submit appends Stalker with normalized MAC without overwriting prior sources"
);

// TV /sync picks up all 3 sources + iptvPrefs:main
const tvSync = await call("/sync", { since: 0, entries: [] }, tvToken);
const syncedEntries = Object.fromEntries(tvSync.body.entries.map((e) => [e.k, e.v]));
const syncedSources = syncedEntries.iptvSources;
check(
  tvSync.status === 200 &&
    Array.isArray(syncedSources) &&
    syncedSources.length === 3 &&
    syncedSources.some((s) => s.type === "xtream" && s.username === "alice") &&
    syncedSources.some((s) => s.type === "m3u" && s.epgUrl === "https://m3u.example/epg.xml.gz") &&
    syncedSources.some((s) => s.type === "stalker" && s.macAddress === "00:1A:79:AA:BB:CC") &&
    syncedEntries["iptvPrefs:main"]?.sortBy === "number",
  "TV /sync receives all 3 sequentially added IPTV sources and iptvPrefs:main"
);

// Direct /sync write of iptvSources is also allowed
const syncDirect = await call("/sync", {
  since: tvSync.body.now,
  entries: [{ k: "iptvSources", t: Date.now() + 500, v: syncedSources }],
}, tvToken);
check(syncDirect.status === 200, "/sync accepts iptvSources directly");

// 6. pruneEpg & pruneCaches
const pruneNow = now;
await env.DB.batch([
  env.DB.prepare(
    "INSERT OR REPLACE INTO lumos_epg_programmes (channel_id, start_ms, end_ms, title, is_sport) VALUES (?, ?, ?, ?, 0)"
  ).bind("uk.bbc.one", pruneNow - 9 * DAY, pruneNow - 8 * DAY, "Very Old Programme"),
  env.DB.prepare(
    "INSERT OR REPLACE INTO lumos_epg_programmes (channel_id, start_ms, end_ms, title, is_sport) VALUES (?, ?, ?, ?, 0)"
  ).bind("uk.bbc.one", pruneNow - 3 * DAY, pruneNow - 3 * DAY + HOUR, "Recent Catchup Programme"),
  env.DB.prepare(
    "INSERT OR REPLACE INTO lumos_iptv_setup (code, account, expires) VALUES (?, ?, ?)"
  ).bind("EXPD-0001", tvAccount, pruneNow - 1000),
  env.DB.prepare(
    "INSERT OR REPLACE INTO lumos_iptv_setup (code, account, expires) VALUES (?, ?, ?)"
  ).bind("ACTV-0001", tvAccount, pruneNow + 60000),
]);

await pruneCaches(env, pruneNow);
const remainingProgs = env.DB.dump("lumos_epg_programmes").map((p) => p.title);
const remainingCodes = env.DB.dump("lumos_iptv_setup").map((c) => c.code);
check(
  !remainingProgs.includes("Very Old Programme") && remainingProgs.includes("Recent Catchup Programme"),
  "pruneEpg (via pruneCaches) removes >7d programmes and keeps recent catch-up programmes"
);
check(
  !remainingCodes.includes("EXPD-0001") && remainingCodes.includes("ACTV-0001"),
  "pruneEpg (via pruneCaches) removes expired setup codes and keeps active codes"
);

// Direct pruneEpg check too
await env.DB.prepare(
  "INSERT OR REPLACE INTO lumos_epg_programmes (channel_id, start_ms, end_ms, title, is_sport) VALUES (?, ?, ?, ?, 0)"
).bind("uk.bbc.two", pruneNow - 10 * DAY, pruneNow - 7 * DAY - 1, "Just Past 7 Days").run();
await pruneEpg(env, pruneNow);
check(
  !env.DB.dump("lumos_epg_programmes").some((p) => p.title === "Just Past 7 Days"),
  "pruneEpg deletes programmes with end_ms < now - 7d"
);

// 7. Curated Guide Metadata, Sky UK 7-day EPG, 8-row batching, default window, and TMDB enrichment
const skySportsPl = UK_CHANNELS["uk.sky.sports.pl"];
const skyCinemaPremiere = UK_CHANNELS["uk.sky.cinema.premiere"];
const rteOne = UK_CHANNELS["ie.rte.one"];
const mainGuideCount = Object.values(UK_CHANNELS).filter((c) => c.mainGuide).length;
const sportsGuideCount = Object.values(UK_CHANNELS).filter((c) => c.sportsGuide).length;
check(
  skySportsPl.skySid === 4010 &&
    skySportsPl.mainGuide === true &&
    skySportsPl.sportsGuide === true &&
    skyCinemaPremiere.skySid === 4021 &&
    skyCinemaPremiere.mainGuide === true &&
    skyCinemaPremiere.sportsGuide === false &&
    rteOne.mainGuide === false &&
    mainGuideCount >= 60 &&
    sportsGuideCount === 21,
  "CHANNEL_DEFS carries skySid, mainGuide, and sportsGuide metadata for Sky Entertainment, Sky Cinema, and UK Sports"
);

const skySampleJson = {
  schedule: [
    {
      sid: "4010",
      events: [
        {
          st: Math.floor((now + 4 * DAY) / 1000),
          d: 7200,
          t: "Super Sunday: Arsenal v Liverpool",
          sy: "Live Premier League action from the Emirates.",
          programmeuuid: "uuid-pl-1",
          seasonnumber: 4,
          episodenumber: 16,
          genre: "Sports",
        },
      ],
    },
    {
      sid: "3000",
      events: [
        {
          st: Math.floor(now / 1000),
          d: 1800,
          t: "Doctor Who",
          sy: "Sci-fi adventure.",
          programmeuuid: "uuid-dw-7",
          episodenumber: 7,
        },
      ],
    },
  ],
};

const parsedSky = parseSkySchedule(skySampleJson, undefined, now);
const skyPlProg = parsedSky.programmes.find((p) => p.channelId === "uk.sky.sports.pl");
const bbcProg = parsedSky.programmes.find((p) => p.channelId === "uk.bbc.one");
const skyPlChan = parsedSky.channels.find((c) => c.id === "uk.sky.sports.pl");
check(
  parsedSky.channels.length === 2 &&
    skyPlChan?.logo === "https://d2n0069hmnqmmx.cloudfront.net/epgdata/1.0/newchanlogos/150/150/skychb4010.png" &&
    skyPlProg?.icon === "https://images.metadata.sky.com/pd-image/uuid-pl-1/16-9/640" &&
    skyPlProg?.subtitle === "S4 E16" &&
    skyPlProg?.sportTag === "Football" &&
    bbcProg?.subtitle === "Ep 7" &&
    bbcProg?.endMs === bbcProg?.startMs + 1800 * 1000,
  "parseSkySchedule extracts timestamps, S4 E16 / Ep 7 subtitles, Sky art/logo URLs, and 7-day future events"
);

// 8-row SQL batch builder stays safely under D1's 100-parameter limit
const twentyFiveProgs = Array.from({ length: 25 }, (_, i) => ({
  channelId: "uk.bbc.one",
  startMs: now + i * HOUR,
  endMs: now + (i + 1) * HOUR,
  title: `Sky Show ${i}`,
  subtitle: null,
  description: null,
  category: null,
  icon: null,
  isSport: false,
  sportTag: null,
}));
const skyDb = d1();
const batchedStmts = buildProgrammeWriteStatements(skyDb, twentyFiveProgs);
check(batchedStmts.length === 4, "buildProgrammeWriteStatements packs 25 programmes into 4 statements (8 rows/stmt)");

// fetchSkySources requests 7 days in <=20 SID chunks and refreshUkEpg uses Sky primary + default window
const skyUrls = [];
const skyFetch = async (url) => {
  const u = String(url);
  skyUrls.push(u);
  if (u.startsWith("https://awk.epgsky.com/hawk/linear/schedule/")) {
    return new Response(JSON.stringify(skySampleJson), { status: 200, headers: { "Content-Type": "application/json" } });
  }
  return new Response("not found", { status: 404 });
};
const skyFetched = await fetchSkySources(now, skyFetch);
check(
  skyUrls.length === 28 &&
    skyUrls.every((u) => u.split("/").pop().split(",").length <= 20) &&
    skyFetched.programmes.length === 2,
  "fetchSkySources queries 7 days (-1d..+5d) in <=20 SID chunks"
);

const skyEnv = { DB: d1(), LUMOS_SETUP: "sky-test-phrase" };
const skyRefresh = await refreshUkEpg(skyEnv, now, skyFetch, true);
check(
  skyRefresh.ok === true && skyRefresh.sources.includes("sky") && skyRefresh.programmes === 2,
  "refreshUkEpg ingests Sky UK schedule directly when XMLTV URL is unset"
);

const skyEnvAcc = await worker.fetch(
  new Request("https://sync.example/account", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ setup: "sky-test-phrase", name: "Sky TV" }),
  }),
  skyEnv
).then((r) => r.json());

// Check default POST /epg/uk window (from = now - 24h, to = now + 120h) and guide booleans
const defaultWindowReqAt = Date.now();
const epgDefaultRes = await worker.fetch(
  new Request("https://sync.example/epg/uk", {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${skyEnvAcc.token}` },
    body: JSON.stringify({}),
  }),
  skyEnv
).then((r) => r.json());
const windowSpanHours = Math.round((epgDefaultRes.toMs - epgDefaultRes.fromMs) / HOUR);
const returnedPl = epgDefaultRes.channels.find((c) => c.id === "uk.sky.sports.pl");
const returnedBbc = epgDefaultRes.channels.find((c) => c.id === "uk.bbc.one");
check(
  windowSpanHours === 144 &&
    returnedPl?.mainGuide === true &&
    returnedPl?.sportsGuide === true &&
    returnedBbc?.mainGuide === true &&
    returnedBbc?.sportsGuide === false,
  "POST /epg/uk defaults to [-24h, +120h] window and includes mainGuide & sportsGuide booleans"
);

// POST /epg/enrich without LUMOS_TMDB_KEY degrades cleanly to null
const enrichNoKey = await call("/epg/enrich", { title: "New: Doctor Who", channelId: "uk.bbc.one" }, tvToken);
check(
  enrichNoKey.status === 200 && enrichNoKey.body.backdrop === null && enrichNoKey.body.description === null,
  "POST /epg/enrich degrades cleanly to null when LUMOS_TMDB_KEY is missing"
);

// POST /epg/enrich with LUMOS_TMDB_KEY queries TMDB en-GB and caches in lumos_meta for 30 days
let tmdbSearchHits = 0;
globalThis.fetch = async (url) => {
  const u = String(url);
  if (u.includes("api.themoviedb.org/3/search/")) {
    tmdbSearchHits++;
    check(u.includes("language=en-GB"), "TMDB enrich uses en-GB language");
    return new Response(
      JSON.stringify({
        results: [
          { id: 57243, media_type: "tv", backdrop_path: "/dw16x9.jpg", overview: "Time Lord adventures." },
        ],
      }),
      { status: 200, headers: { "Content-Type": "application/json" } }
    );
  }
  throw new Error("unexpected fetch " + u);
};
const enrichEnv = { DB: env.DB, LUMOS_TMDB_KEY: "tmdb-test-key" };
const enriched1 = await enrichEpgProgramme(enrichEnv, { title: "New: Doctor Who", channelId: "uk.bbc.one" }, now);
const enriched2 = await enrichEpgProgramme(enrichEnv, { title: "Doctor Who", channelId: "uk.bbc.one" }, now + DAY);
check(
  enriched1.backdrop === "https://image.tmdb.org/t/p/w1280/dw16x9.jpg" &&
    enriched1.description === "Time Lord adventures." &&
    enriched2.backdrop === enriched1.backdrop &&
    tmdbSearchHits === 1,
  "enrichEpgProgramme returns w1280 backdrop + en-GB description and caches 30 days in lumos_meta"
);

console.log(failures.length ? `\n${failures.length} failed` : "\nall passed");
process.exit(failures.length ? 1 : 0);
