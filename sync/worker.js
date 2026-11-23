// Lumos sync (a Cloudflare Worker on the lumen-sync D1 database). Sync logic follows dx.bi's
// profile-sync (Copyright (C) 2026 lavagnou and dx.bi), GNU General Public License, version 3 or later.
//
// Lumos's own accounts, with no Stremio involved:
//
//   POST /account   { setup, name }        → { token, account }   the first device (needs the LUMOS_SETUP phrase)
//   POST /link/start { name }              → { code, poll, expires } a new device shows the code…
//   POST /link/approve { code }   (signed in) → { name }            …a signed-in device approves it…
//   POST /link/poll { poll }               → { token, account } | { waiting: true }   …and the new device is in
//   POST /devices                 (signed in) → { devices }         who's signed in
//   POST /devices/remove { id }   (signed in) → { ok }              sign one out (id from /devices)
//   POST /sync { since, entries } (signed in) → { now, entries }   the most recent of each key wins
//   POST /import/stremio { authKey } (signed in) → { imported }    bring Lumen's profiles and Stremio's history across, once
//   POST /lists                   (signed in) → { lists }           Lumos's own Home lists (lists.js), made by the cron
//   POST /art { ids }             (signed in) → { art, pending }   checked TMDB pictures by IMDb id (art.js)
//   POST /art/wrong { id }        (signed in) → { art }             a title's pictures said to be wrong: the next best
//
// Signed in: "Authorization: Bearer <token>". Tokens are random, 256-bit, and only their SHA-256 is kept.

import { readLists, refreshLists } from "./lists.js";
import { resolveArt, warmArt, wrongArt } from "./art.js";
import { handlePicks, handleAsk } from "./picks.js";
import { resolveLabels } from "./labels.js";
import { handleScores } from "./scores.js";
import { enrichEpgProgramme, pruneEpg, readUkEpg, refreshUkEpg } from "./epg.js";
import { handleF1Standings, handleSportsFixtures, handleSportsMatch, handleTeamsSchedule, handleTeamsSearch } from "./sports.js";
import { handleIptvSetupSubmit, renderSetupPage } from "./iptv-setup.js";
import { handleIptvPresence, pruneIptvPresence } from "./presence.js";

const STREMIO_API = "https://api.strem.io/api";

// What can be kept. Per profile (":<viewer>"): progress (resume points), watched, myList, and Lumen's
// own profile data (Home layout, hidden titles, taste, ratings…)
const KEY = /^(viewers|kidsAllow|settings|addons|iptvSources|(homeLayout|hidden|cwDismissed|taste|ratings|progress|watched|myList|iptvPrefs):[\w-]{1,40})$/;
const MAX_BODY = 1536 * 1024;
const MAX_ENTRIES = 200;
const MAX_VALUE = 200 * 1024;
const MAX_ACCOUNT = 4 * 1024 * 1024;
const OVERLAP = 60 * 1000;
const LINK_MS = 10 * 60 * 1000;
// Sign-in codes: no 0/O, 1/I/L, 2/Z, 5/S, 8/B (easy to read off a TV across a room)
const CODE_ALPHABET = "34679ACDEFGHJKMNPQRTUVWXY";

function reply(status, body) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" } });
}

async function sha256(text) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function randomHex(bytes) {
  return [...crypto.getRandomValues(new Uint8Array(bytes))].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function randomCode() {
  const v = crypto.getRandomValues(new Uint8Array(8));
  const c = [...v].map((b) => CODE_ALPHABET[b % CODE_ALPHABET.length]).join("");
  return `${c.slice(0, 4)}-${c.slice(4)}`;
}

function sameText(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}

function deviceName(v) {
  return typeof v === "string" && v.trim() ? v.trim().slice(0, 60) : "Device";
}

async function newDevice(env, account, name) {
  const token = randomHex(32);
  const now = Date.now();
  await env.DB.prepare("INSERT INTO lumos_devices (token, account, name, created, seen) VALUES (?, ?, ?, ?, ?)")
    .bind(await sha256(token), account, name, now, now).run();
  return token;
}

async function signedIn(request, env) {
  const auth = request.headers.get("Authorization") || "";
  const token = auth.startsWith("Bearer ") ? auth.slice(7).trim() : "";
  if (!/^[0-9a-f]{64}$/.test(token)) return null;
  const hash = await sha256(token);
  const row = (await env.DB.prepare("SELECT account, seen FROM lumos_devices WHERE token = ?").bind(hash).all()).results?.[0];
  if (!row) return null;
  // (seen: at most once an hour, to keep writes down)
  if (Date.now() - row.seen > 3600000) await env.DB.prepare("UPDATE lumos_devices SET seen = ? WHERE token = ?").bind(Date.now(), hash).run();
  return { account: row.account, hash };
}

// ---- Sync ----

// Resume points, watched and My List are kept title by title ({ "<id>": { at, … } }): the most recent
// change to each title wins, so two devices never undo each other's titles
const MERGED = /^(progress|watched|myList):/;

export function mergeTitles(a, b) {
  const out = { ...(a || {}) };
  for (const [id, e] of Object.entries(b || {})) {
    const o = out[id];
    if (!o || (Number(e && e.at) || 0) >= (Number(o.at) || 0)) out[id] = e;
  }
  return out;
}

async function store(env, account, entries, now) {
  if (!entries.length) return { tooBig: false, written: new Set() };
  const keys = [...new Set(entries.map((e) => e.k))];
  const have = new Map();
  for (let i = 0; i < keys.length; i += 50) {
    const chunk = keys.slice(i, i + 50);
    const stored = await env.DB.prepare(
      `SELECT k, t, v FROM entries WHERE account = ? AND k IN (${chunk.map(() => "?").join(",")})`
    ).bind(account, ...chunk).all();
    for (const r of stored.results || []) have.set(r.k, r);
  }
  let delta = 0;
  const writes = [];
  const written = new Set();
  for (let e of entries) {
    const old = have.get(e.k);
    if (MERGED.test(e.k)) {
      let before = {};
      try { before = old ? JSON.parse(old.v) || {} : {}; } catch (_) { /* replaced */ }
      const v = JSON.stringify(mergeTitles(before, JSON.parse(e.v)));
      if (old && v === old.v) continue;
      // (not "written": the device gets the merged list back)
      e = { k: e.k, t: Math.max(e.t, old ? old.t + 1 : 0), v };
    } else {
      if (old && old.t >= e.t) continue;
      written.add(e.k);
    }
    if (e.v.length > MAX_VALUE) return { tooBig: true, written: new Set() };
    delta += e.v.length - (old ? old.v.length : 0);
    have.set(e.k, { k: e.k, t: e.t, v: e.v });
    writes.push(e);
  }
  if (writes.length) {
    const total = (await env.DB.prepare("SELECT COALESCE(SUM(LENGTH(v)), 0) AS n FROM entries WHERE account = ?").bind(account).all()).results?.[0]?.n || 0;
    if (total + delta > MAX_ACCOUNT) return { tooBig: true, written: new Set() };
    const upsert = env.DB.prepare(
      "INSERT INTO entries (account, k, t, v, s) VALUES (?, ?, ?, ?, ?) ON CONFLICT (account, k) DO UPDATE SET t = excluded.t, v = excluded.v, s = excluded.s WHERE excluded.t > entries.t"
    );
    await env.DB.batch(writes.map((e) => upsert.bind(account, e.k, e.t, e.v, now)));
  }
  return { tooBig: false, written };
}

async function handleSync(body, env, account) {
  const since = Number(body.since) || 0;
  const incoming = Array.isArray(body.entries) ? body.entries : [];
  if (incoming.length > MAX_ENTRIES) return reply(413, { error: "Too much at once." });
  const now = Date.now();
  const entries = [];
  for (const e of incoming) {
    if (!e || typeof e.k !== "string" || !KEY.test(e.k)) return reply(400, { error: `Not something Lumen syncs: ${String(e && e.k).slice(0, 40)}` });
    const t = Number(e.t);
    if (!Number.isFinite(t) || t <= 0 || t > now + 86400000) return reply(400, { error: "Bad time on " + e.k });
    if (MERGED.test(e.k) && (!e.v || typeof e.v !== "object" || Array.isArray(e.v))) return reply(400, { error: `${e.k} has to be titles by id` });
    const v = JSON.stringify(e.v === undefined ? null : e.v);
    if (v.length > MAX_VALUE) return reply(413, { error: `Too big to sync: ${e.k}` });
    entries.push({ k: e.k, t: Math.floor(t), v });
  }
  const { tooBig, written } = await store(env, account, entries, now);
  if (tooBig) return reply(413, { error: "This account is too big to sync." });

  const changed = await env.DB.prepare("SELECT k, t, v FROM entries WHERE account = ? AND s > ?").bind(account, Math.max(0, since - OVERLAP)).all();
  const out = [];
  for (const r of changed.results || []) {
    if (written.has(r.k)) continue;
    let v = null;
    try { v = JSON.parse(r.v); } catch (_) { /* null */ }
    out.push({ k: r.k, t: r.t, v });
  }
  return reply(200, { now, entries: out });
}

// ---- Problem reports ----

/**
 * A device's log for Harry to read: { gz: base64 of the gzipped text, device, version, note, crash }. Kept 30 days, at most
 * 30 a day per account; answers the short reference the app shows. Read them with sync/logs.mjs.
 */
async function handleLogs(body, env, account, now) {
  const gz = typeof body.gz === "string" ? body.gz : "";
  if (!gz || gz.length > 1400 * 1024 || !/^[A-Za-z0-9+/=]+$/.test(gz)) return reply(400, { error: "That report couldn't be read." });
  const day = (await env.DB.prepare("SELECT count(*) n FROM lumos_logs WHERE account = ? AND at > ?").bind(account, now - 86400000).all()).results?.[0]?.n || 0;
  if (day >= 30) return reply(429, { error: "Too many reports today: try again tomorrow." });
  const text = (v, n) => (typeof v === "string" ? v.slice(0, n) : null);
  const letters = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
  const bytes = crypto.getRandomValues(new Uint8Array(6));
  const id = Array.from(bytes, (b) => letters[b % letters.length]).join("");
  await env.DB.prepare("INSERT INTO lumos_logs (id, account, at, device, version, note, crash, body) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
    .bind(id, account, now, text(body.device, 80), text(body.version, 40), text(body.note, 500), body.crash ? 1 : 0, gz).run();
  await env.DB.prepare("DELETE FROM lumos_logs WHERE at < ?").bind(now - 30 * 86400000).run();
  return reply(200, { id });
}

// ---- Bringing Lumen and Stremio across ----

async function stremio(method, payload) {
  const r = await fetch(`${STREMIO_API}/${method}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
    signal: AbortSignal.timeout(15000),
  });
  if (!r.ok) throw new Error(`Stremio ${r.status}`);
  const answer = await r.json();
  if (answer && answer.error) return null;
  return answer ? answer.result : null;
}

/**
 * Stremio's library as Lumos keeps it, per profile: resume points, watched titles and My List.
 * Lumen kept one Stremio library per account and said which profiles had each title in
 * "libraryOwners"; a title nobody owns goes to the first profile.
 */
export function convertLibrary(items, owners, viewerIds) {
  const first = viewerIds[0] || "main";
  const out = {};
  const forViewer = (v) => (out[v] ||= { progress: {}, watched: {}, myList: {} });
  for (const i of items) {
    // (titles watched but never added are kept as removed + temp: their progress still counts)
    if (!i || typeof i._id !== "string" || i.type === "other") continue;
    const st = i.state || {};
    const changed = Date.parse(st.lastWatched) || Date.parse(i._mtime) || 1;
    const who = (Array.isArray(owners[i._id]) && owners[i._id].length ? owners[i._id] : [first]).filter((v) => viewerIds.length === 0 || viewerIds.includes(v));
    for (const v of who.length ? who : [first]) {
      const p = forViewer(v);
      const done = (st.flaggedWatched || 0) > 0 || (i.type === "movie" && (st.timesWatched || 0) > 0);
      if (done) p.watched[i._id] = { at: changed, on: true };
      const offset = Number(st.timeOffset) || 0;
      const duration = Number(st.duration) || 0;
      if (!done && offset > 0 && duration > offset) {
        p.progress[i._id] = {
          at: changed, type: i.type, video: st.video_id || i._id, name: i.name, poster: i.poster || null,
          position: Math.round(offset / 1000), duration: Math.round(duration / 1000),
        };
      }
      if (!i.removed && !i.temp) p.myList[i._id] = { at: Date.parse(i._ctime) || 1, on: true, type: i.type, name: i.name, poster: i.poster || null };
    }
  }
  return out;
}

/** The Stremio account a session key belongs to: its user ({_id, email}), null when Stremio doesn't know the key; throws offline */
async function stremioUser(authKey) {
  const user = await stremio("getUser", { type: "GetUser", authKey });
  return user && typeof user._id === "string" ? user : null;
}

async function handleImport(body, env, account) {
  const authKey = typeof body.authKey === "string" ? body.authKey.trim() : "";
  if (!authKey || authKey.length > 200) return reply(400, { error: "Sign in to Stremio to bring your history across." });
  let user;
  try {
    user = await stremioUser(authKey);
  } catch (_) {
    return reply(503, { error: "Stremio can't be reached right now." });
  }
  if (!user) return reply(401, { error: "Stremio doesn't know this session: sign in again." });
  // (this Stremio account now signs in to this Lumos account: Sign in with Stremio)
  await env.DB.prepare("INSERT OR IGNORE INTO lumos_stremio (stremio, account, at) VALUES (?, ?, ?)").bind(user._id, account, Date.now()).run();
  const r = await importLibrary(env, account, authKey, user);
  return reply(r.status, r.body);
}

/** Stremio's library and Lumen's profiles for [user] into the Lumos [account]: {status, body} */
async function importLibrary(env, account, authKey, user) {
  let items;
  try {
    items = await stremio("datastoreGet", { authKey, collection: "libraryItem", ids: [], all: true });
  } catch (_) {
    return { status: 503, body: { error: "Stremio can't be reached right now." } };
  }
  if (!Array.isArray(items)) items = [];

  // Lumen's own profile data for that Stremio account (kept by lumen-sync)
  const lumen = new Map();
  for (const r of (await env.DB.prepare("SELECT k, t, v FROM entries WHERE account = ?").bind(user._id).all()).results || []) {
    try { lumen.set(r.k, { t: r.t, v: JSON.parse(r.v) }); } catch (_) { /* skipped */ }
  }
  const viewers = lumen.get("viewers");
  const viewerIds = viewers && viewers.v && Array.isArray(viewers.v.list) ? viewers.v.list.map((v) => v && v.id).filter(Boolean) : [];
  const owners = (lumen.get("libraryOwners") || { v: {} }).v || {};

  const now = Date.now();
  const entries = [];
  for (const [k, e] of lumen) if (k !== "libraryOwners" && !k.startsWith("picks:") && KEY.test(k)) entries.push({ k, t: e.t, v: JSON.stringify(e.v) });
  // Stremio's history, merged title by title into what Lumos has (Lumos's own later changes win)
  const library = convertLibrary(items, owners, viewerIds);
  for (const [viewer, p] of Object.entries(library)) {
    if (!/^[\w-]{1,40}$/.test(viewer)) continue;
    for (const kind of ["progress", "watched", "myList"]) entries.push({ k: `${kind}:${viewer}`, t: now, v: JSON.stringify(p[kind]) });
  }
  for (const e of entries) if (e.v.length > MAX_VALUE) return { status: 413, body: { error: `Too big to bring across: ${e.k}` } };
  const { tooBig } = await store(env, account, entries, now);
  if (tooBig) return { status: 413, body: { error: "This account is too big to sync." } };
  return { status: 200, body: { imported: entries.length, titles: items.length, profiles: Object.keys(library).length } };
}

/**
 * Sign in with Stremio (a TV whose people used Lumen with their own Stremio account): the Lumos account that Stremio
 * account belongs to, else a new one made for it, with its Lumen profiles and Stremio history brought across. Only a
 * Stremio account that has used Lumen (lumen-sync has its profiles) gets an account made: anyone else is turned away.
 */
async function handleStremioSignIn(body, env) {
  const authKey = typeof body.authKey === "string" ? body.authKey.trim() : "";
  if (!authKey || authKey.length > 200) return reply(400, { error: "Sign in to Stremio first." });
  let user;
  try {
    user = await stremioUser(authKey);
  } catch (_) {
    return reply(503, { error: "Stremio can't be reached right now. Try again in a minute." });
  }
  if (!user) return reply(401, { error: "Stremio didn't accept that sign-in: try again." });
  const name = deviceName(body.name);
  const known = (await env.DB.prepare("SELECT account FROM lumos_stremio WHERE stremio = ?").bind(user._id).all()).results?.[0]?.account;
  if (known) {
    const token = await newDevice(env, known, name);
    return reply(200, { token, account: known, created: false });
  }
  const usedLumen = (await env.DB.prepare("SELECT count(*) n FROM entries WHERE account = ?").bind(user._id).all()).results?.[0]?.n > 0;
  if (!usedLumen) return reply(403, { error: "This Stremio account hasn't been used with Lumen. Ask Harry to set it up." });
  const account = `lumos:${randomHex(12)}`;
  await env.DB.prepare("INSERT OR IGNORE INTO lumos_stremio (stremio, account, at) VALUES (?, ?, ?)").bind(user._id, account, Date.now()).run();
  // (two TVs at once: the first one's account)
  const mine = (await env.DB.prepare("SELECT account FROM lumos_stremio WHERE stremio = ?").bind(user._id).all()).results?.[0]?.account || account;
  if (mine === account) {
    const r = await importLibrary(env, account, authKey, user);
    if (r.status !== 200) {
      await env.DB.prepare("DELETE FROM lumos_stremio WHERE stremio = ? AND account = ?").bind(user._id, account).run();
      return reply(r.status, r.body);
    }
  }
  const token = await newDevice(env, mine, name);
  return reply(200, { token, account: mine, created: mine === account });
}

// ---- Cache cleanup ----

const DAY = 86400000;
const PRUNE_SCORES_MS = 14 * DAY; // scores.js TTL is 3d
const PRUNE_RECS_MS = 30 * DAY;   // picks.js recs TTL is 7d
const PRUNE_EPG_ENRICH_MS = 35 * DAY; // epg enrich TTL is 30d
const PRUNE_SPORTS_MS = 3 * DAY;      // sports fixtures/schedules TTL is <=24h
const PRUNE_ART_MS = 60 * DAY;    // art.js TTL is 30d
const PRUNE_FACTS_MS = 7 * DAY;   // labels.js TTL is 2d
const PRUNE_LOGS_MS = 30 * DAY;

/** Periodic cleanup of expired cache, link, and log rows; never touches permanent lumos_meta keys (find:, mdblist:). */
export async function pruneCaches(env, now = Date.now()) {
  await env.DB.batch([
    env.DB.prepare("DELETE FROM lumos_meta WHERE k >= 'scores:' AND k < 'scores;' AND json_valid(v) AND CAST(json_extract(v, '$.at') AS INTEGER) < ?")
      .bind(now - PRUNE_SCORES_MS),
    env.DB.prepare("DELETE FROM lumos_meta WHERE k >= 'recs:' AND k < 'recs;' AND json_valid(v) AND CAST(json_extract(v, '$.at') AS INTEGER) < ?")
      .bind(now - PRUNE_RECS_MS),
    env.DB.prepare("DELETE FROM lumos_meta WHERE k >= 'epg:enrich:' AND k < 'epg:enrich;' AND json_valid(v) AND CAST(json_extract(v, '$.at') AS INTEGER) < ?")
      .bind(now - PRUNE_EPG_ENRICH_MS),
    env.DB.prepare("DELETE FROM lumos_meta WHERE k >= 'sports:' AND k < 'sports;' AND json_valid(v) AND CAST(json_extract(v, '$.at') AS INTEGER) < ?")
      .bind(now - PRUNE_SPORTS_MS),
    env.DB.prepare("DELETE FROM lumos_titleart WHERE at < ?").bind(now - PRUNE_ART_MS),
    env.DB.prepare("DELETE FROM lumos_facts WHERE at < ?").bind(now - PRUNE_FACTS_MS),
    env.DB.prepare("DELETE FROM lumos_links WHERE expires < ?").bind(now),
    env.DB.prepare("DELETE FROM lumos_logs WHERE at < ?").bind(now - PRUNE_LOGS_MS),
  ]);
  await pruneEpg(env, now);
  await pruneIptvPresence(env, now);
}

// ---- Routes ----

export default {
  // (the cron: the Home lists, a little at a time)
  async scheduled(_event, env, ctx) {
    // (a run with no list to remake checks the lists' pictures ahead instead: each run has 50 outside requests)
    if (env.DB) ctx.waitUntil(refreshLists(env)
      .then(async (r) => {
        console.log("lists", r.join("; ") || "nothing due");
        const epgRes = !r.length
          ? await refreshUkEpg(env).catch((e) => { console.log("epg failed", e.message); return { skipped: true }; })
          : { skipped: true };
        if (!r.length && epgRes?.skipped) console.log("art warmed", await warmArt(env));
        await pruneCaches(env);
      })
      .catch((e) => console.log("lists failed", e.message)));
  },

  // Every answer over 1 KB gzipped for a client that takes it (the TVs do: OkHttp asks and unpacks), by the Worker itself
  // rather than left to Cloudflare: Home's lists and a sync are tens of KB of JSON, a tenth of that gzipped
  async fetch(request, env) {
    const res = await handle(request, env);
    if (!/\bgzip\b/.test(request.headers.get("Accept-Encoding") || "") || res.headers.has("Content-Encoding")) return res;
    const text = await res.text();
    const headers = new Headers(res.headers);
    if (text.length < 1024 || typeof CompressionStream === "undefined") return new Response(text, { status: res.status, headers });
    headers.set("Content-Encoding", "gzip");
    const zipped = new Blob([text]).stream().pipeThrough(new CompressionStream("gzip"));
    return new Response(zipped, { status: res.status, headers, encodeBody: "manual" });
  },
};

async function handle(request, env) {
  const url = new URL(request.url);
  if (request.method === "GET" && url.pathname === "/iptv") {
    return new Response(renderSetupPage(url.searchParams.get("code") || "", url.origin), {
      status: 200,
      headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" },
    });
  }
  if (request.method !== "POST") return reply(405, { error: "POST only" });
  if (!env.DB) return reply(503, { error: "Sync isn't set up yet." });

  const ip = request.headers.get("CF-Connecting-IP") || "unknown";
  if (env.IP_LIMIT && !(await env.IP_LIMIT.limit({ key: ip })).success) return reply(429, { error: "Too many requests: try again in a minute." });

  if (Number(request.headers.get("Content-Length") || 0) > MAX_BODY) return reply(413, { error: "Too much at once." });
  let body;
  try {
    const raw = await request.text();
    if (raw.length > MAX_BODY) return reply(413, { error: "Too much at once." });
    body = JSON.parse(raw || "{}") || {};
  } catch (_) {
    return reply(400, { error: "Invalid JSON." });
  }
  const now = Date.now();

  // Open routes: making the account, and a new device asking to be let in
  if (url.pathname === "/iptv/setup/submit") {
    const r = await handleIptvSetupSubmit(body, env, now);
    return reply(r.status, r.body);
  }
  if (url.pathname === "/account") {
    if (typeof env.LUMOS_SETUP !== "string" || env.LUMOS_SETUP.length < 8) return reply(403, { error: "Accounts can't be made on this server." });
    if (!sameText(body.setup, env.LUMOS_SETUP)) return reply(403, { error: "That isn't the setup phrase." });
    const account = `lumos:${randomHex(12)}`;
    const token = await newDevice(env, account, deviceName(body.name));
    return reply(200, { token, account });
  }
  if (url.pathname === "/signin/stremio") return handleStremioSignIn(body, env);
  if (url.pathname === "/link/start") {
    await env.DB.prepare("DELETE FROM lumos_links WHERE expires < ?").bind(now).run();
    const poll = randomHex(32);
    const code = randomCode();
    await env.DB.prepare("INSERT INTO lumos_links (code, poll, name, account, expires) VALUES (?, ?, ?, NULL, ?)")
      .bind(code, await sha256(poll), deviceName(body.name), now + LINK_MS).run();
    return reply(200, { code, poll, expires: now + LINK_MS });
  }
  if (url.pathname === "/link/poll") {
    if (typeof body.poll !== "string" || !/^[0-9a-f]{64}$/.test(body.poll)) return reply(400, { error: "Invalid sign-in." });
    const hash = await sha256(body.poll);
    const link = (await env.DB.prepare("SELECT code, name, account, expires FROM lumos_links WHERE poll = ?").bind(hash).all()).results?.[0];
    if (!link || link.expires < now) return reply(410, { error: "That code has run out: start again." });
    if (!link.account) return reply(200, { waiting: true });
    await env.DB.prepare("DELETE FROM lumos_links WHERE code = ?").bind(link.code).run();
    const token = await newDevice(env, link.account, link.name);
    return reply(200, { token, account: link.account });
  }

  const me = await signedIn(request, env);
  if (!me) return reply(401, { error: "Sign in to Lumen." });
  if (env.ACCOUNT_LIMIT && !(await env.ACCOUNT_LIMIT.limit({ key: me.account })).success) return reply(429, { error: "Too many requests: try again in a minute." });

  switch (url.pathname) {
    case "/link/approve": {
      const code = typeof body.code === "string" ? body.code.toUpperCase().replace(/[^0-9A-Z]/g, "") : "";
      if (code.length !== 8) return reply(400, { error: "That isn't a sign-in code." });
      const dashed = `${code.slice(0, 4)}-${code.slice(4)}`;
      const link = (await env.DB.prepare("SELECT name, account, expires FROM lumos_links WHERE code = ?").bind(dashed).all()).results?.[0];
      if (!link || link.expires < now || link.account) return reply(404, { error: "No device is waiting with that code." });
      await env.DB.prepare("UPDATE lumos_links SET account = ? WHERE code = ? AND account IS NULL").bind(me.account, dashed).run();
      return reply(200, { name: link.name });
    }
    case "/devices": {
      const rows = (await env.DB.prepare("SELECT token, name, created, seen FROM lumos_devices WHERE account = ? ORDER BY seen DESC").bind(me.account).all()).results || [];
      return reply(200, { devices: rows.map((r) => ({ id: r.token.slice(0, 16), name: r.name, created: r.created, seen: r.seen, current: r.token === me.hash })) });
    }
    case "/devices/remove": {
      const id = typeof body.id === "string" && /^[0-9a-f]{16}$/.test(body.id) ? body.id : "";
      if (!id) return reply(400, { error: "Which device?" });
      await env.DB.prepare("DELETE FROM lumos_devices WHERE account = ? AND substr(token, 1, 16) = ?").bind(me.account, id).run();
      return reply(200, { ok: true });
    }
    case "/sync": return handleSync(body, env, me.account);
    case "/import/stremio": return handleImport(body, env, me.account);
    case "/lists": return reply(200, { lists: await readLists(env) });
    case "/art": return reply(200, await resolveArt(env, body.ids));
    case "/logs": return handleLogs(body, env, me.account, now);
    case "/scores": return reply(200, await handleScores(body, env));
    case "/labels": return reply(200, await resolveLabels(env, body.ids, undefined, body.facts === true));
    case "/picks": { const r = await handlePicks(body, env, me.account, now); return reply(r.status, r.body); }
    case "/ask": { const r = await handleAsk(body, env, me.account, now); return reply(r.status, r.body); }
    case "/epg/uk": return reply(200, await readUkEpg(env, body, now));
    case "/epg/refresh": return reply(200, await refreshUkEpg(env, now, fetch, body.force === true));
    case "/epg/enrich": return reply(200, await enrichEpgProgramme(env, body, now));
    case "/iptv/sports/fixtures": return reply(200, await handleSportsFixtures(body, env, now));
    case "/iptv/sports/teams/search": return reply(200, await handleTeamsSearch(body, env, now));
    case "/iptv/sports/teams/schedule": return reply(200, await handleTeamsSchedule(body, env, now));
    case "/iptv/sports/match": { const r = await handleSportsMatch(body, env, now); return reply(r.status, r.body); }
    case "/iptv/sports/f1/standings": { const r = await handleF1Standings(body, env, now); return reply(r.status, r.body); }
    case "/iptv/presence": { const r = await handleIptvPresence(body, env, me, now); return reply(r.status, r.body); }
    case "/iptv/setup/start": {
      await env.DB.prepare("DELETE FROM lumos_iptv_setup WHERE expires < ?").bind(now).run();
      const code = randomCode();
      await env.DB.prepare("INSERT INTO lumos_iptv_setup (code, account, expires) VALUES (?, ?, ?)")
        .bind(code, me.account, now + LINK_MS).run();
      return reply(200, { code, path: `/iptv?code=${code}`, expires: now + LINK_MS });
    }
    case "/art/wrong": {
      const id = typeof body.id === "string" ? body.id : "";
      if (!/^tt\d{1,10}$/.test(id)) return reply(400, { error: "Which title?" });
      return reply(200, { art: { [id]: await wrongArt(env, id) } });
    }
    default: return reply(404, { error: "not found" });
  }
}

