// dx.bi — Copyright (C) 2026 dx.bi. From dx.bi, Copyright (C) 2026 lavagnou. GNU General Public License, version 3 or later (see LICENSE).
//
// dx.bi's viewer sync (a Cloudflare Worker with a D1 database). Each person's dx.bi viewers
// (names, who owns which library title, Home rows, hidden titles) follow them on every device, filed
// under their Stremio account: no other account to make.
//
// The apps send their Stremio session key; this asks Stremio's API who it belongs to (getUser, as
// Stremio's own apps do) and files the data under that account's id. The key itself is never stored
// or logged: only a short-lived in-memory note of which account a key's hash belongs to, so Stremio
// isn't asked on every request.
//
// One route, POST /sync: { since, entries: [{ k, t, v }] } → { now, entries }. Entries are the
// plugin's store entries ({ t: time, v: value }); the most recent one of each key wins.

const ALLOWED_ORIGINS = new Set(["https://web.stremio.com"]);
const STREMIO_API = "https://api.strem.io/api/getUser";

// What can be kept: the profiles and their choices, and dx.bi's settings, nothing else
const KEY = /^(viewers|libraryOwners|kidsAllow|(homeLayout|hidden|cwDismissed|taste|ratings):[\w-]{1,40})$/;
const MAX_BODY = 1536 * 1024;
const MAX_PICKS_BODY = 128 * 1024;
const MAX_ENTRIES = 200;
const MAX_VALUE = 100 * 1024;
const MAX_ACCOUNT = 1024 * 1024;
// a little overlap on "since": a write that started just before the last answer is still seen
const OVERLAP = 60 * 1000;
const PICKS_TTL = 12 * 3600 * 1000;
const CF_MODEL = "@cf/meta/llama-3.3-70b-instruct-fp8-fast";
const GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent";

function cors(request) {
  const origin = request.headers.get("Origin") || "";
  return {
    "Access-Control-Allow-Origin": ALLOWED_ORIGINS.has(origin) ? origin : "https://web.stremio.com",
    "Access-Control-Allow-Methods": "POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, Authorization",
    "Access-Control-Max-Age": "86400",
    Vary: "Origin",
  };
}

function reply(request, status, body) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", ...cors(request) } });
}

async function sha256(text) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

// Which account a session key belongs to, by its hash, for a few minutes (this Worker's memory only)
const known = new Map();

// The Stremio account id of a session key: null when Stremio says it's not a valid key, throws when
// Stremio can't be reached
async function accountOf(key) {
  const hash = await sha256(key);
  const hit = known.get(hash);
  if (hit && hit.until > Date.now()) return hit.id;
  const r = await fetch(STREMIO_API, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ type: "GetUser", authKey: key }),
    signal: AbortSignal.timeout(8000),
  });
  if (!r.ok) throw new Error(`Stremio ${r.status}`);
  const answer = await r.json();
  const id = answer && answer.result && typeof answer.result._id === "string" ? answer.result._id : null;
  if (!id) return null;
  if (known.size > 5000) known.clear();
  known.set(hash, { id, until: Date.now() + 10 * 60 * 1000 });
  return id;
}

function parseJsonAnswer(raw) {
  if (!raw) return null;
  if (typeof raw === "object") {
    if (Array.isArray(raw.ranked) || Array.isArray(raw.additions)) return raw;
    if (raw.response) return parseJsonAnswer(raw.response);
    return null;
  }
  const text = String(raw).trim();
  const start = text.indexOf("{");
  const end = text.lastIndexOf("}");
  if (start < 0 || end <= start) return null;
  try { return JSON.parse(text.slice(start, end + 1)); } catch (_) { return null; }
}

async function askModel(env, system, user) {
  if (env.GEMINI_API_KEY) {
    const r = await fetch(`${GEMINI_URL}?key=${encodeURIComponent(env.GEMINI_API_KEY)}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        systemInstruction: { parts: [{ text: system }] },
        contents: [{ role: "user", parts: [{ text: user }] }],
        generationConfig: { responseMimeType: "application/json", temperature: 0.4 },
      }),
      signal: AbortSignal.timeout(12000),
    });
    if (!r.ok) throw new Error(`Gemini ${r.status}`);
    const j = await r.json();
    return parseJsonAnswer(j && j.candidates && j.candidates[0] && j.candidates[0].content && j.candidates[0].content.parts && j.candidates[0].content.parts[0] && j.candidates[0].content.parts[0].text);
  }
  if (env.AI && typeof env.AI.run === "function") {
    const res = await env.AI.run(CF_MODEL, {
      messages: [
        { role: "system", content: system },
        { role: "user", content: user },
      ],
      max_tokens: 900,
      temperature: 0.4,
    });
    return parseJsonAnswer(res);
  }
  return undefined;
}

async function handlePicks(request, env, account) {
  if (Number(request.headers.get("Content-Length") || 0) > MAX_PICKS_BODY) return reply(request, 413, { error: "Too much at once." });
  let body;
  try {
    const raw = await request.text();
    if (raw.length > MAX_PICKS_BODY) return reply(request, 413, { error: "Too much at once." });
    body = JSON.parse(raw || "{}");
  } catch (_) {
    return reply(request, 400, { error: "Invalid JSON." });
  }
  if (!body || typeof body.viewer !== "string" || !/^[\w-]{1,40}$/.test(body.viewer) || typeof body.sig !== "string" || !body.sig || body.sig.length > 300 || !Array.isArray(body.candidates)) {
    return reply(request, 400, { error: "Invalid picks request." });
  }
  const watched = (Array.isArray(body.watched) ? body.watched : []).slice(0, 16)
    .filter((w) => w && typeof w.id === "string" && typeof w.name === "string" && w.name.trim())
    .map((w) => ({
      id: w.id.slice(0, 24),
      type: w.type === "series" ? "series" : "movie",
      name: w.name.trim().slice(0, 120),
      year: Number(w.year) || 0,
      genres: (Array.isArray(w.genres) ? w.genres : []).slice(0, 6).map((g) => String(g).slice(0, 30)),
      weight: Math.round((Number(w.weight) || 1) * 100) / 100,
    }));
  const genres = (Array.isArray(body.genres) ? body.genres : []).slice(0, 12)
    .filter((g) => typeof g === "string" && g.trim())
    .map((g) => g.trim().slice(0, 30));
  if (!watched.length && !genres.length) return reply(request, 400, { error: "Need watched titles or genres." });

  const dropped = (Array.isArray(body.dropped) ? body.dropped : []).slice(0, 12)
    .filter((d) => d && typeof d.name === "string" && d.name.trim())
    .map((d) => ({ id: String(d.id || "").slice(0, 24), type: d.type === "series" ? "series" : "movie", name: d.name.trim().slice(0, 120) }));
  const ratings = (Array.isArray(body.ratings) ? body.ratings : []).slice(0, 40)
    .filter((r) => r && typeof r.id === "string" && Number.isInteger(r.rating) && r.rating >= 1 && r.rating <= 10)
    .map((r) => {
      const entry = { id: r.id.slice(0, 24), rating: r.rating };
      if (typeof r.name === "string" && r.name.trim()) entry.name = r.name.trim().slice(0, 120);
      return entry;
    });
  const candidates = body.candidates.slice(0, 50)
    .filter((c) => c && typeof c.id === "string" && /^tt\d+$/.test(c.id) && typeof c.name === "string" && c.name.trim())
    .map((c) => ({
      id: c.id,
      type: c.type === "series" ? "series" : "movie",
      name: c.name.trim().slice(0, 120),
      year: Number(c.year) || 0,
      genres: (Array.isArray(c.genres) ? c.genres : []).slice(0, 6).map((g) => String(g).slice(0, 30)),
      rating: Math.round((Number(c.rating) || 0) * 10) / 10,
      seeds: (Array.isArray(c.seeds) ? c.seeds : []).slice(0, 4).map((s) => String(s).slice(0, 24)),
    }));

  const cacheKey = `picks:${body.viewer}`;
  const now = Date.now();
  const cachedRows = await env.DB.prepare("SELECT t, v FROM entries WHERE account = ? AND k = ?").bind(account, cacheKey).all();
  const row = (cachedRows.results || [])[0];
  if (row && now - Number(row.t) < PICKS_TTL) {
    try {
      const cached = JSON.parse(row.v);
      if (cached && cached.sig === body.sig && Array.isArray(cached.ranked) && Array.isArray(cached.additions)) {
        return reply(request, 200, { ranked: cached.ranked, additions: cached.additions, cached: true });
      }
    } catch (_) { /* rebuild */ }
  }

  if (!env.GEMINI_API_KEY && !(env.AI && typeof env.AI.run === "function")) {
    return reply(request, 503, { error: "AI suggestions aren't configured on this Worker." });
  }

  const system =
    "You are a film and television curator. Return ONLY strict JSON with shape {\"ranked\":[\"tt...\"],\"additions\":[{\"type\":\"movie\"|\"series\",\"name\":\"Title\",\"year\":2021,\"seeds\":[\"tt...\"]}]}. " +
    "Explicit 1-10 \"ratings\" reflect the viewer's exact taste (Criticker TCI/PSI philosophy): scores 8-10 are high-affinity anchors and auteur/stylistic benchmarks; scores 1-4 are hard negatives whose tone, tropes, and creators must be avoided. " +
    "1) In \"ranked\", reorder up to 30 of the best fitting candidate IDs from \"candidates\" for this viewer's weighted \"watched\" titles (higher weight = finished/rewatched/binged or newly tuned seed), \"genres\", and \"ratings\", prioritizing high-affinity stylistic matches while demoting generic crowd-pleasers, and avoiding more than 2 titles from the same seed in the top 10. " +
    "2) In \"additions\", suggest 10-15 additional films or series not in \"watched\", \"dropped\", \"ratings\" (unless rated 8-10 as inspiration), or \"candidates\" (~70% strong tonal/creator/theme matches and ~30% adjacent discovery), strictly avoiding the tone, tropes, creators, and franchises of \"dropped\" titles and titles rated 1-4. Each addition must have \"type\" (\"movie\" or \"series\"), exact \"name\", 4-digit \"year\", and \"seeds\" (1-2 watched or 8-10 rated IDs that inspired it).";
  const user = JSON.stringify({ watched, genres, dropped, ratings, candidates });

  let parsed;
  try {
    parsed = await askModel(env, system, user);
  } catch (err) {
    console.error("picks model error:", err && err.message ? err.message : err);
    return reply(request, 502, { error: "AI model request failed.", detail: String((err && err.message) || err) });
  }
  if (!parsed) return reply(request, 502, { error: "AI returned an invalid response." });

  const candIds = new Set(candidates.map((c) => c.id));
  const watchedIds = new Set(watched.map((w) => w.id));
  const defaultSeed = watched[0] ? watched[0].id : "";
  const ranked = [...new Set((Array.isArray(parsed.ranked) ? parsed.ranked : []).filter((id) => typeof id === "string" && candIds.has(id)))].slice(0, 40);
  const additions = (Array.isArray(parsed.additions) ? parsed.additions : [])
    .filter((a) => a && (a.type === "movie" || a.type === "series") && typeof a.name === "string" && a.name.trim())
    .slice(0, 15)
    .map((a) => {
      const yr = Number(a.year);
      const seeds = (Array.isArray(a.seeds) ? a.seeds : []).filter((s) => typeof s === "string" && watchedIds.has(s)).slice(0, 2);
      if (!seeds.length && defaultSeed) seeds.push(defaultSeed);
      return {
        type: a.type,
        name: a.name.trim().slice(0, 120),
        year: Number.isInteger(yr) && yr >= 1900 && yr <= 2100 ? yr : 0,
        seeds,
      };
    });

  await env.DB.prepare(
    "INSERT INTO entries (account, k, t, v, s) VALUES (?, ?, ?, ?, 0) ON CONFLICT (account, k) DO UPDATE SET t = excluded.t, v = excluded.v, s = 0"
  ).bind(account, cacheKey, now, JSON.stringify({ sig: body.sig, ranked, additions })).run();

  return reply(request, 200, { ranked, additions, cached: false });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: cors(request) });
    if (url.pathname !== "/sync" && url.pathname !== "/picks") return reply(request, 404, { error: "not found" });
    if (request.method !== "POST") return reply(request, 405, { error: "POST only" });
    if (!env.DB) return reply(request, 503, { error: "Profile sync isn't set up yet." });

    const ip = request.headers.get("CF-Connecting-IP") || "unknown";
    if (env.IP_LIMIT && !(await env.IP_LIMIT.limit({ key: ip })).success) return reply(request, 429, { error: "Too many requests: try again in a few minutes." });

    const auth = request.headers.get("Authorization") || "";
    const key = auth.startsWith("Stremio ") ? auth.slice(8).trim() : "";
    if (!key || key.length > 200) return reply(request, 401, { error: "Sign in to Stremio to sync profiles." });
    let account;
    try {
      account = await accountOf(key);
    } catch (_) {
      return reply(request, 503, { error: "Stremio can't be reached right now." });
    }
    if (!account) return reply(request, 401, { error: "Stremio doesn't know this session: sign in again." });
    if (env.ACCOUNT_LIMIT && !(await env.ACCOUNT_LIMIT.limit({ key: account })).success) return reply(request, 429, { error: "Too many requests: try again in a few minutes." });

    if (url.pathname === "/picks") return handlePicks(request, env, account);

    if (Number(request.headers.get("Content-Length") || 0) > MAX_BODY) return reply(request, 413, { error: "Too much at once." });
    let body;
    try {
      const raw = await request.text();
      if (raw.length > MAX_BODY) return reply(request, 413, { error: "Too much at once." });
      body = JSON.parse(raw || "{}");
    } catch (_) {
      return reply(request, 400, { error: "That isn't a dx.bi sync." });
    }
    const since = Number(body && body.since) || 0;
    const incoming = Array.isArray(body && body.entries) ? body.entries : [];
    if (incoming.length > MAX_ENTRIES) return reply(request, 413, { error: "Too much at once." });

    const now = Date.now();
    const entries = [];
    for (const e of incoming) {
      if (!e || typeof e.k !== "string" || !KEY.test(e.k)) return reply(request, 400, { error: `Not something dx.bi syncs: ${String(e && e.k).slice(0, 40)}` });
      const t = Number(e.t);
      if (!Number.isFinite(t) || t <= 0 || t > now + 86400000) return reply(request, 400, { error: "That isn't a dx.bi sync." });
      const v = JSON.stringify(e.v === undefined ? null : e.v);
      if (v.length > MAX_VALUE) return reply(request, 413, { error: `Too big to sync: ${e.k}` });
      entries.push({ k: e.k, t: Math.floor(t), v });
    }

    // What the account has: the most recent of each key wins, and it all has to fit in its share
    const stored = await env.DB.prepare("SELECT k, t, LENGTH(v) AS l FROM entries WHERE account = ?").bind(account).all();
    const synced = (stored.results || []).filter((r) => !r.k.startsWith("picks:"));
    const have = new Map(synced.map((r) => [r.k, r]));
    let size = synced.reduce((n, r) => n + r.l, 0);
    const writes = [];
    const written = new Set();
    for (const e of entries) {
      const old = have.get(e.k);
      if (old && old.t >= e.t) continue;
      size += e.v.length - (old ? old.l : 0);
      writes.push(e);
      written.add(e.k);
    }
    if (size > MAX_ACCOUNT) return reply(request, 413, { error: "These profiles are too big to sync." });
    if (writes.length) {
      const upsert = env.DB.prepare(
        "INSERT INTO entries (account, k, t, v, s) VALUES (?, ?, ?, ?, ?) ON CONFLICT (account, k) DO UPDATE SET t = excluded.t, v = excluded.v, s = excluded.s WHERE excluded.t > entries.t"
      );
      await env.DB.batch(writes.map((e) => upsert.bind(account, e.k, e.t, e.v, now)));
    }

    // What changed since this device last looked (but not what it just sent)
    const changed = await env.DB.prepare("SELECT k, t, v FROM entries WHERE account = ? AND s > ?").bind(account, Math.max(0, since - OVERLAP)).all();
    const out = [];
    for (const r of changed.results || []) {
      if (written.has(r.k) || r.k.startsWith("picks:")) continue;
      let v = null;
      try { v = JSON.parse(r.v); } catch (_) { /* skipped below */ }
      out.push({ k: r.k, t: r.t, v });
    }
    return reply(request, 200, { now, entries: out });
  },
};
