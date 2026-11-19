// Top Picks for You and "Because you watched …": Lumen's recommender (dxbi.plugin.js, recoMath and recommender), moved onto
// the sync server, which already has each profile's synced watching and the TMDB key. POST /picks { viewer }.
//
//  1. What says most about this profile's taste: titles watched, played a good way, rated 7 or more, chosen in Tune
//     Suggestions; weighted by how much of it they watched and how lately (the same sums as Lumen's).
//  2. TMDB's recommendations for the strongest dozen, scored by how many of their titles point to each, how well its
//     genres fit theirs, its rating and its age. Nothing they've watched, started, saved or turned down.
//  3. With Cloudflare's AI (the AI binding; or Gemini with a GEMINI_API_KEY secret): the same prompt as Lumen's Worker
//     re-ranks them by tone and themes, and adds a few closer matches and finds, each checked against TMDB.
//
// The Because rows are varied: no title is in two of them (or in Top Picks' first ten), seeds that resemble each other
// (Gladiator and The Odyssey) aren't picked together, and which seeds get a row shifts from day to day.
//
// Kept 12 hours per profile (sooner when what they watched changes, and when the day changes, which is what moves the rows). Outside requests are counted: a Worker has 50, and
// TMDB's answers are kept (recommendations a week, IMDb ids for good), so later runs need far fewer.

import { GENRES, art, buildTitle, findByImdb, tmdbGet } from "./tmdb.js";

const KEEP_MS = 12 * 3600e3;
const RECS_MS = 7 * 86400e3;
const BUDGET = 44;
const SAVED_WEIGHT = 0.45; // something saved to the Library but not watched says less than something watched or rated
const BECAUSE_ROWS = 5;
const CF_MODEL = "@cf/meta/llama-3.3-70b-instruct-fp8-fast";
const SKIPPED_GENRES = new Set([10767, 10763, 10764, 99]); // talk shows, news, reality, documentaries
const GENRE = { ...GENRES, 10759: "Action", 10765: "Sci-Fi", 10768: "War" };

// ---- the sums (as Lumen's recoMath) ----

const days = (now, at) => Math.max(0, (now - at) / 86400e3);
const recent = (now, at) => 0.15 + Math.exp(-days(now, at) / 120);

/** The profile's synced values that matter here */
async function load(env, account, viewer) {
  const keys = ["progress", "watched", "myList", "ratings", "taste", "cwDismissed", "hidden"].map((k) => `${k}:${viewer}`);
  const rows = (await env.DB.prepare(`SELECT k, v FROM entries WHERE account = ? AND k IN (${keys.map(() => "?").join(",")})`).bind(account, ...keys).all()).results || [];
  const out = {};
  for (const r of rows) {
    try { out[r.k.split(":")[0]] = JSON.parse(r.v); } catch (_) { /* skip */ }
  }
  return out;
}

/** The titles that say most about them, strongest first: [{id, type?, name?, weight, how}] */
export function seedsOf(data, now) {
  const by = new Map();
  const add = (id, w, info) => {
    if (!/^tt\d+$/.test(id) || !(w > 0)) return;
    const hit = by.get(id);
    if (hit) { hit.weight += w; Object.assign(hit, { ...info, ...hit }); } else by.set(id, { id, weight: w, how: "watched", ...info });
  };
  const ratings = data.ratings && typeof data.ratings === "object" ? data.ratings : {};
  const rated = (id) => { const r = Number(ratings[id]); return Number.isInteger(r) && r >= 1 && r <= 10 ? r : null; };
  const boost = (w, r) => (r == null ? w : r >= 9 ? w * 2.5 : r >= 7 ? w * 1.8 : r <= 4 ? 0 : w);
  for (const [id, w] of Object.entries(data.watched || {})) {
    if (w && w.on) add(id, boost(1.4 * recent(now, w.at || 0), rated(id)), {});
  }
  for (const [id, p] of Object.entries(data.progress || {})) {
    if (!p || !p.at) continue;
    const part = p.duration > 0 ? Math.min(1, (p.position || 0) / p.duration) : 0;
    let completion;
    if (p.type === "movie") { if (part < 0.3) continue; completion = part; } else {
      // (a series with a later episode on: they've watched some; just the start of the first isn't a taste)
      const ep = String(p.video || "").split(":");
      const deep = Number(ep[1]) > 1 || Number(ep[2]) > 1;
      if (!deep && part < 0.3) continue;
      completion = deep ? 0.8 : 0.5;
    }
    add(id, boost((0.4 + completion) * recent(now, p.at), rated(id)), { type: p.type === "series" ? "series" : "movie", name: p.name });
  }
  for (const id of Object.keys(ratings)) {
    const r = rated(id);
    if (r >= 7 && !by.has(id)) add(id, r >= 9 ? 2.2 : 1.3, { how: "liked" });
  }
  const taste = data.taste && Array.isArray(data.taste.titles) ? data.taste.titles : [];
  const tw = 1.1 / (1 + by.size * 0.2);
  for (const t of taste) if (t && /^tt\d+$/.test(t.id || "")) add(t.id, tw, { how: by.has(t.id) ? "watched" : "liked", type: t.type === "series" ? "series" : "movie", name: t.name });
  // (the Library: saved but not watched yet is still a hint of what they fancy, only a lighter one; a title already
  // watched or rated keeps its own, stronger, weight and just gets a little more)
  for (const [id, m] of Object.entries(data.myList || {})) {
    if (!m || !m.on) continue;
    add(id, SAVED_WEIGHT * recent(now, m.at || 0) * (by.has(id) ? 0.3 : 1), { how: "saved", type: m.type === "series" ? "series" : "movie", name: m.name });
  }
  return [...by.values()].filter((s) => s.weight > 0).sort((a, b) => b.weight - a.weight);
}

/** What not to suggest: seen, started, in their Library, turned down */
function skipOf(data) {
  const out = new Set();
  for (const k of ["watched", "progress", "ratings", "cwDismissed"]) for (const id of Object.keys(data[k] || {})) out.add(id);
  // (only what's still in the Library: a title they took out of it is fair game again)
  for (const [id, m] of Object.entries(data.myList || {})) if (m && m.on) out.add(id);
  // (Not Interested)
  for (const h of hiddenOf(data)) out.add(h.id);
  return out;
}

/** What they said they're not interested in: [{id, type, name}], latest first */
function hiddenOf(data) {
  // (Lumen kept some as {key: "movie:tt…"}, with no id)
  return (Array.isArray(data.hidden) ? data.hidden : [])
    .map((h) => (h && !h.id && typeof h.key === "string" ? { ...h, id: h.key.split(":").slice(1).join(":") } : h))
    .filter((h) => h && /^tt\d+$/.test(h.id || "")).reverse();
}

/** Their genres, weighted by the titles they came from, 0 to 1 */
function profile(seeds) {
  const sum = {};
  for (const s of seeds) for (const g of s.genres || []) sum[g] = (sum[g] || 0) + s.weight;
  const top = Math.max(0, ...Object.values(sum));
  const out = {};
  if (top > 0) for (const [g, v] of Object.entries(sum)) out[g] = v / top;
  return out;
}

// ---- the Because rows: varied, and changing day by day ----

/** A steady 0 to 1 for a title on a given day: the same all day, different tomorrow (so the rows rotate, but not on every look) */
export function dayHash(day, id) {
  let h = 2166136261 ^ day;
  for (let i = 0; i < id.length; i++) h = Math.imul(h ^ id.charCodeAt(i), 16777619);
  h ^= h >>> 15; h = Math.imul(h, 2246822507); h ^= h >>> 13;
  return (h >>> 0) / 4294967296;
}

/** How alike two seeds are, 0 to 1: shared genres mostly, and being from the same era a little */
export function likeness(a, b) {
  const ga = new Set(a.genres || []), gb = new Set(b.genres || []);
  const both = [...ga].filter((g) => gb.has(g)).length;
  const union = new Set([...ga, ...gb]).size;
  const era = a.year && b.year && Math.abs(a.year - b.year) <= 12 ? 1 : 0;
  return 0.8 * (union ? both / union : 0) + 0.2 * era;
}

/**
 * The seeds in the order their Because rows should be tried: the first is the strongest after a daily nudge of its
 * weight (so a lower one can take a turn, but never a weak one over a strong one), then each next one is the strongest
 * of what's left after its weight is cut by how much it resembles any already chosen. That keeps Gladiator and The
 * Odyssey from sitting side by side, and a row of a different taste comes between them.
 */
export function becauseOrder(seeds, day) {
  const left = seeds.map((s) => ({ s, w: s.weight * (0.65 + 0.7 * dayHash(day, s.id)) }));
  const out = [];
  while (left.length) {
    let best = 0, bestScore = -1;
    left.forEach((c, i) => {
      const alike = out.reduce((m, o) => Math.max(m, likeness(c.s, o)), 0);
      const v = c.w * (1 - 0.85 * alike);
      if (v > bestScore) { best = i; bestScore = v; }
    });
    out.push(left.splice(best, 1)[0].s);
  }
  return out;
}

/** The Because rows: [{seed, items}], every title in one row only, none from `taken` (Top Picks' first ones), at least six each */
export function becauseRows(seeds, ranked, day, taken, max = BECAUSE_ROWS) {
  const used = new Set(taken);
  const rows = [];
  for (const s of becauseOrder(seeds, day)) {
    if (rows.length >= max) break;
    const pick = ranked.filter((c) => c.seeds.includes(s.id) && !used.has(c.tt)).slice(0, 14);
    if (pick.length < 6) continue;
    pick.forEach((c) => used.add(c.tt));
    rows.push({ seed: s, items: pick });
  }
  return rows;
}

export function score(c, prof, year) {
  let pointing = 0;
  for (const p of c.pointers) pointing += p.w / (1 + p.rank / 8);
  const fit = c.genres.length ? c.genres.reduce((a, g) => a + (prof[g] || 0), 0) / c.genres.length : 0;
  const rating = c.votes >= 50 ? Math.max(-1, Math.min(1, (c.rating - 6) / 3)) : 0;
  const age = c.year ? year - c.year : 9;
  const fresh = age <= 2 ? 0.3 : age <= 6 ? 0.15 : 0;
  return 2 * pointing + 1.2 * fit + 0.8 * rating + fresh;
}

// ---- TMDB, counted and kept ----

const tmdb = (env, budget) => (path) => tmdbGet(env, path, { language: "en-GB", budget });

const metaGet = async (env, k) => {
  const v = (await env.DB.prepare("SELECT v FROM lumos_meta WHERE k = ?").bind(k).all()).results?.[0]?.v;
  try { return v ? JSON.parse(v) : null; } catch (_) { return null; }
};
const metaPut = (env, k, v) => env.DB.prepare("INSERT OR REPLACE INTO lumos_meta (k, v) VALUES (?, ?)").bind(k, JSON.stringify(v)).run();

/** TMDB's recommendations for one title (kept a week) */
async function recommendations(env, get, kind, id, now) {
  const kept = await metaGet(env, `recs:${kind}:${id}`);
  if (kept && now - kept.at < RECS_MS) return kept.results;
  const j = await get(`/${kind}/${id}/recommendations?page=1`);
  if (!j) return kept ? kept.results : null;
  const results = (j.results || []).filter((x) => !x.adult).map((x) => ({
    id: x.id, kind: x.media_type === "tv" || kind === "tv" && x.media_type !== "movie" ? "tv" : "movie", name: x.title || x.name || "",
    genres: x.genre_ids || [], rating: x.vote_average || 0, votes: x.vote_count || 0,
    year: Number(String(x.release_date || x.first_air_date || "").slice(0, 4)) || 0, out: String(x.release_date || x.first_air_date || ""),
    overview: (x.overview || "").slice(0, 300),
  }));
  await metaPut(env, `recs:${kind}:${id}`, { at: now, results });
  return results;
}

/** IMDb ids for TMDB ones, from what's known, then asked while there's budget: Map("kind:id" → tt) */
async function imdbIds(env, get, keys) {
  const known = new Map();
  for (let i = 0; i < keys.length; i += 50) {
    const chunk = keys.slice(i, i + 50);
    const rows = (await env.DB.prepare(`SELECT tmdb, imdb FROM lumos_ids WHERE tmdb IN (${chunk.map(() => "?").join(",")})`).bind(...chunk).all()).results || [];
    for (const r of rows) known.set(r.tmdb, r.imdb);
  }
  const writes = [];
  for (const k of keys) {
    if (known.has(k)) continue;
    const [kind, id] = k.split(":");
    const ext = await get(`/${kind}/${id}/external_ids`);
    if (!ext) continue;
    const tt = /^tt\d+$/.test(ext.imdb_id || "") ? ext.imdb_id : "";
    known.set(k, tt);
    writes.push(env.DB.prepare("INSERT OR REPLACE INTO lumos_ids (tmdb, imdb, at) VALUES (?, ?, ?)").bind(k, tt, Date.now()));
  }
  // (all the new matches in one batch)
  if (writes.length) await env.DB.batch(writes);
  return known;
}

// ---- the AI's turn (Lumen's prompt) ----

function parseJsonAnswer(raw) {
  if (!raw) return null;
  if (typeof raw === "object") {
    if (Array.isArray(raw.ranked) || Array.isArray(raw.additions) || Array.isArray(raw.titles)) return raw;
    if (raw.response) return parseJsonAnswer(raw.response);
    return null;
  }
  const text = String(raw).trim();
  const start = text.indexOf("{");
  const end = text.lastIndexOf("}");
  if (start < 0 || end <= start) return null;
  try { return JSON.parse(text.slice(start, end + 1)); } catch (_) { return null; }
}

async function askModel(env, system, user, budget) {
  const gemini = typeof env.GEMINI_API_KEY === "string" ? env.GEMINI_API_KEY.trim() : "";
  if (gemini && budget.used < 49) {
    budget.used++;
    const r = await fetch("https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-lite-latest:generateContent", {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-goog-api-key": gemini },
      body: JSON.stringify({ systemInstruction: { parts: [{ text: system }] }, contents: [{ role: "user", parts: [{ text: user }] }], generationConfig: { responseMimeType: "application/json", temperature: 0.3 } }),
      signal: AbortSignal.timeout(12000),
    }).catch(() => null);
    const j = r && r.ok ? await r.json().catch(() => null) : null;
    const parsed = parseJsonAnswer(j?.candidates?.[0]?.content?.parts?.[0]?.text);
    if (parsed) return parsed;
  }
  if (env.AI && typeof env.AI.run === "function") {
    const res = await env.AI.run(CF_MODEL, { messages: [{ role: "system", content: system }, { role: "user", content: user }], max_tokens: 900, temperature: 0.4 }).catch(() => null);
    return parseJsonAnswer(res);
  }
  return null;
}

function prompt(prefer) {
  const kinds = prefer === "movie"
    ? "The viewer prefers FILMS to series. Put films first in \"ranked\" unless a series is a far better fit, and make at least 6 of the \"additions\" films (type \"movie\"), at most 2 series. "
    : prefer === "series"
      ? "The viewer prefers SERIES to films. Put series first in \"ranked\" unless a film is a far better fit, and make at least 6 of the \"additions\" series, at most 2 films. "
      : "Keep a fair mix of films and series, in about the proportion of \"watched\". ";
  return "You choose what one viewer should watch next. Return ONLY strict JSON of the shape {\"ranked\":[\"tt...\"],\"additions\":[{\"type\":\"movie\"|\"series\",\"name\":\"Title\",\"year\":2021,\"seeds\":[\"tt...\"]}]}. " +
    "The input JSON has: \"watched\" (the titles that say most about their taste; a higher \"weight\" says more), \"ratings\" (their own scores out of 10: 8 to 10 are what they love, 1 to 4 what they dislike), " +
    "\"genres\" (genres they chose), \"dropped\" (titles they gave up on or hid), and \"candidates\" (titles already found for them). " +
    kinds +
    "1) \"ranked\": up to 20 candidate ids, best fit first. Judge fit by tone, themes, writing and the people who made it, not by genre label or popularity alone. Rank well-known crowd-pleasers below a closer match, " +
    "and put no more than 2 titles that share a seed in the first 10. Leave out candidates that resemble the \"dropped\" titles or those rated 1 to 4. " +
    "2) \"additions\": 8 titles that are NOT in \"watched\", \"ratings\", \"dropped\" or \"candidates\". About 5 should be close matches to what they love (same director or writer, same tone, the same kind of story); " +
    "about 3 should be less obvious finds they are unlikely to have come across: older, foreign-language or lesser-known titles of high quality. No sequels or other entries of a franchise already in \"watched\" or \"ratings\". " +
    "Nothing that resembles the \"dropped\" titles or those rated 1 to 4. Each needs \"type\", the exact original release \"name\" as IMDb lists it, the 4-digit release \"year\", " +
    "and \"seeds\": 1 or 2 ids from \"watched\" or from \"ratings\" of 8 or more that it follows from.";
}

// ---- the answer ----

/** A candidate as a Lumos title */
function title(c) {
  return buildTitle({
    id: c.tt, type: c.kind === "tv" ? "series" : "movie", name: c.name, year: c.year,
    description: c.overview, genres: c.genres, rating: c.rating,
  }, GENRE);
}

export async function handlePicks(body, env, account, now) {
  const viewer = typeof body.viewer === "string" && /^[\w-]{1,40}$/.test(body.viewer) ? body.viewer : null;
  if (!viewer) return { status: 400, body: { error: "Which profile?" } };
  const data = await load(env, account, viewer);
  const all = seedsOf(data, now);
  if (!all.length) return { status: 200, body: { rows: [], nothing: true } };
  const prefer = data.taste && (data.taste.prefer === "movie" || data.taste.prefer === "series") ? data.taste.prefer : null;
  // (never all one kind: a series is watched for many more hours than a film, so by weight alone it'd be nearly all series)
  const n = 12;
  const want = prefer === "movie" ? { movie: 9, series: 3 } : prefer === "series" ? { movie: 3, series: 9 } : { movie: 6, series: 6 };
  const sig = all.slice(0, 24).map((s) => s.id).join(",") + `|${prefer || ""}|${Object.keys(data.watched || {}).length}|${hiddenOf(data).length}|${Object.values(data.myList || {}).filter((m) => m && m.on).length}|${Math.floor(now / 86400e3)}`;
  const cacheKey = `picks:${account}:${viewer}`;
  const kept = await metaGet(env, cacheKey);
  if (kept && kept.sig === sig && !kept.partial && now - kept.at < KEEP_MS && !body.again) return { status: 200, body: { rows: kept.rows, ai: kept.ai, at: kept.at } };

  const budget = { used: 0, max: BUDGET };
  const get = tmdb(env, budget);
  if (!env.LUMOS_TMDB_KEY) return { status: 503, body: { error: "Suggestions aren't set up on the server." } };

  // the seeds, with what TMDB knows of each (their kind, when only the IMDb id was synced)
  const seeds = [];
  const taken = { movie: 0, series: 0 };
  for (const s of all) {
    if (seeds.length >= n) break;
    const f = await findByImdb(env, s.id, { language: "en-GB", budget });
    if (!f) continue;
    const kind = f.kind === "tv" ? "series" : "movie";
    if (taken[kind] >= want[kind] && all.length > n) continue;
    taken[kind]++;
    seeds.push({ ...s, kind: f.kind, tmdb: f.id, name: s.name || f.name, genres: f.genres, year: f.year || 0, type: kind });
  }
  const prof = profile(seeds);
  const skip = skipOf(data);
  const year = new Date(now).getFullYear();
  const today = new Date(now).toISOString().slice(0, 10);

  const cands = new Map();
  for (const s of seeds) {
    const recs = await recommendations(env, get, s.kind, s.tmdb, now);
    (recs || []).forEach((x, rank) => {
      if (x.genres.some((g) => SKIPPED_GENRES.has(g)) || (x.out && x.out > today) || !x.name) return;
      const k = `${x.kind}:${x.id}`;
      const c = cands.get(k) || { ...x, key: k, pointers: [], seeds: [] };
      c.pointers.push({ w: s.weight, rank });
      if (!c.seeds.includes(s.id)) c.seeds.push(s.id);
      cands.set(k, c);
    });
  }
  let scored = [...cands.values()].map((c) => ({ ...c, score: score(c, prof, year) })).sort((a, b) => b.score - a.score).slice(0, 60);
  const ids = await imdbIds(env, get, scored.map((c) => c.key));
  scored = scored.map((c) => ({ ...c, tt: ids.get(c.key) })).filter((c) => c.tt && !skip.has(c.tt) && !seeds.some((s) => s.id === c.tt));

  // the AI's turn: re-ranked by tone and themes, a few additions found on TMDB
  let ai = false;
  let ranked = scored;
  if (scored.length >= 6) {
    // (what they turned down: Not Interested first, then what they took out of Continue Watching)
    const dropped = [
      ...hiddenOf(data).map((h) => ({ id: h.id, type: h.type === "series" ? "series" : "movie", name: String(h.name || "").slice(0, 120) })),
      ...Object.keys(data.cwDismissed || {}).map((id) => ({ id, name: (data.progress?.[id]?.name) || "" })),
    ].filter((d) => d.name).slice(0, 12);
    const ratings = Object.entries(data.ratings || {}).filter(([, r]) => Number(r) >= 1).slice(0, 20)
      .map(([id, r]) => ({ id, rating: Number(r), name: (data.progress?.[id]?.name) || seeds.find((s) => s.id === id)?.name || "" })).filter((r) => r.name);
    const user = JSON.stringify({
      prefer: prefer || "no preference",
      watched: seeds.slice(0, 10).map((s) => ({ id: s.id, type: s.type, name: s.name, genres: s.genres.map((g) => GENRE[g]).filter(Boolean), weight: Math.round(s.weight * 100) / 100 })),
      genres: (data.taste?.genres || []).slice(0, 8), dropped, ratings,
      candidates: scored.slice(0, 25).map((c) => ({ id: c.tt, type: c.kind === "tv" ? "series" : "movie", name: c.name, year: c.year, genres: c.genres.map((g) => GENRE[g]).filter(Boolean), rating: Math.round(c.rating * 10) / 10, seeds: c.seeds.slice(0, 3) })),
    });
    const answer = await askModel(env, prompt(prefer), user, budget);
    if (answer) {
      ai = true;
      const byTt = new Map(scored.map((c) => [c.tt, c]));
      const order = [...new Set((answer.ranked || []).filter((id) => byTt.has(id)))].map((id) => byTt.get(id));
      // additions: each looked up on TMDB by name and year (in turn with the ranked ones, two to one), while there's budget
      const adds = [];
      const seedIds = new Set(seeds.map((s) => s.id));
      for (const a of (Array.isArray(answer.additions) ? answer.additions : []).slice(0, 8)) {
        if (budget.used > BUDGET - 2 || !a || !a.name || (a.type !== "movie" && a.type !== "series")) continue;
        const kind = a.type === "series" ? "tv" : "movie";
        const yr = Number(a.year) || 0;
        const s = await get(`/search/${kind}?query=${encodeURIComponent(String(a.name).slice(0, 100))}${yr ? `&${kind === "tv" ? "first_air_date_year" : "year"}=${yr}` : ""}`);
        const x = s && (s.results || [])[0];
        if (!x || x.adult) continue;
        const k = `${kind}:${x.id}`;
        const tt = (await imdbIds(env, get, [k])).get(k);
        if (!tt || skip.has(tt) || seedIds.has(tt) || byTt.has(tt)) continue;
        adds.push({
          key: k, tt, id: x.id, kind, name: x.title || x.name || a.name, genres: x.genre_ids || [], rating: x.vote_average || 0, votes: x.vote_count || 0,
          year: Number(String(x.release_date || x.first_air_date || "").slice(0, 4)) || yr, overview: (x.overview || "").slice(0, 300),
          seeds: (Array.isArray(a.seeds) ? a.seeds : []).filter((id) => seedIds.has(id)).slice(0, 2),
        });
      }
      const merged = [];
      const seen = new Set();
      const push = (c) => { if (c && !seen.has(c.tt)) { seen.add(c.tt); merged.push(c); } };
      let ri = 0, ai2 = 0;
      while (ri < order.length || ai2 < adds.length) {
        push(order[ri++]); push(order[ri++]);
        if (ai2 < adds.length) push(adds[ai2++]);
      }
      scored.forEach(push);
      ranked = merged.filter(Boolean);
    }
  }

  // the rows: Top Picks, then Because you watched … for their strongest titles (six fresh ones each, at least)
  const rows = [];
  if (ranked.length >= 6) rows.push({ name: "Top Picks for You", items: ranked.slice(0, 20).map(title) });
  // (Top Picks' first ten aren't repeated below; each Because row has titles of its own)
  for (const r of becauseRows(seeds, ranked, Math.floor(now / 86400e3), ranked.slice(0, 10).map((c) => c.tt))) {
    rows.push({ name: `Because you ${r.seed.how === "liked" ? "liked" : r.seed.how === "saved" ? "saved" : "watched"} ${r.seed.name}`, items: r.items.map(title) });
  }
  // (out of requests before the end: what's found so far, and the next ask carries on, TMDB's answers being kept)
  const partial = budget.used >= BUDGET;
  // (a partial answer with fewer rows doesn't replace a whole one)
  if (!partial || !kept || (kept.rows || []).length <= rows.length) await metaPut(env, cacheKey, { sig, at: now, ai, rows, partial });
  const shown = partial && kept && (kept.rows || []).length > rows.length ? kept.rows : rows;
  return { status: 200, body: { rows: shown, ai, at: now, used: budget.used, partial } };
}

export async function handleAsk(body, env, account, now) {
  const viewer = typeof body.viewer === "string" && /^[a-zA-Z0-9_-]{1,40}$/.test(body.viewer) ? body.viewer : null;
  const prompt = typeof body.prompt === "string" ? body.prompt.trim() : "";
  if (!viewer || prompt.length < 3 || prompt.length > 500) return { status: 400, body: { error: "Invalid search request." } };
  if (!env.GEMINI_API_KEY && !(env.AI && typeof env.AI.run === "function")) {
    return { status: 503, body: { error: "AI search isn't configured." } };
  }

  const data = await load(env, account, viewer);
  const all = seedsOf(data, now);
  const watched = all.slice(0, 10).map((s) => ({
    id: s.id,
    type: s.type === "series" ? "series" : "movie",
    name: s.name || data.progress?.[s.id]?.name || "",
    weight: Math.round(s.weight * 100) / 100,
  }));
  const ratings = Object.entries(data.ratings || {}).filter(([, r]) => Number(r) >= 1 && Number(r) <= 10).slice(0, 20)
    .map(([id, r]) => ({ id, rating: Number(r), name: (data.progress?.[id]?.name) || all.find((s) => s.id === id)?.name || "" }))
    .filter((r) => r.name);
  const dropped = [
    ...hiddenOf(data).map((h) => ({ id: h.id, type: h.type === "series" ? "series" : "movie", name: String(h.name || "").slice(0, 120) })),
    ...Object.keys(data.cwDismissed || {}).map((id) => ({ id, name: (data.progress?.[id]?.name) || "" })),
  ].filter((d) => d.name).slice(0, 12);

  const system =
    "You find films and series for one viewer from a PROMPT they typed. Return ONLY strict JSON of the shape {\"titles\":[{\"type\":\"movie\"|\"series\",\"name\":\"exact original IMDb title\",\"year\":2014}]}. " +
    "Give up to 16 titles that best match the PROMPT (its theme, setting, era, tone, people): the prompt decides what is included. " +
    "The input JSON has \"prompt\", \"watched\" (their taste; a higher \"weight\" says more), \"ratings\" (their scores out of 10: 8 to 10 love, 1 to 4 dislike) and \"dropped\" (titles they gave up on or hid). " +
    "Their taste only decides the ORDER among genuine matches and what to leave out: nothing resembling \"dropped\" or rated 1 to 4. Do not leave out titles they merely watched, but put ones they have not seen first. " +
    "If the prompt names a film or series (\"like X\"), do not return X itself. If the prompt asks for films only or series only, respect it. " +
    "Each needs \"type\", the exact original release \"name\" as IMDb lists it, and the 4-digit release \"year\". Never invent a title.";

  const budget = { used: 0, max: BUDGET };
  const parsed = await askModel(env, system, JSON.stringify({ prompt, watched, ratings, dropped }), budget);
  if (!parsed || !Array.isArray(parsed.titles)) return { status: 502, body: { error: "AI returned an invalid response." } };

  const get = tmdb(env, budget);
  const hiddenIds = new Set(hiddenOf(data).map((h) => h.id));
  const disliked = new Set(Object.entries(data.ratings || {}).filter(([, r]) => Number(r) >= 1 && Number(r) <= 4).map(([id]) => id));
  const seen = new Set();
  const resolved = [];

  for (const a of parsed.titles.slice(0, 16)) {
    if (budget.used > BUDGET - 2 || !a || typeof a.name !== "string" || !a.name.trim() || (a.type !== "movie" && a.type !== "series")) continue;
    const kind = a.type === "series" ? "tv" : "movie";
    const yr = Number(a.year) || 0;
    const s = await get(`/search/${kind}?query=${encodeURIComponent(a.name.trim().slice(0, 100))}${yr ? `&${kind === "tv" ? "first_air_date_year" : "year"}=${yr}` : ""}`);
    const x = s && (s.results || [])[0];
    if (!x || x.adult) continue;
    const k = `${kind}:${x.id}`;
    const tt = (await imdbIds(env, get, [k])).get(k);
    if (!tt || hiddenIds.has(tt) || disliked.has(tt) || seen.has(tt)) continue;
    seen.add(tt);
    resolved.push({ tt, kind, name: x.title || x.name || a.name.trim(), year: Number(String(x.release_date || x.first_air_date || "").slice(0, 4)) || yr });
  }

  return {
    status: 200,
    body: {
      titles: resolved.map((c) => ({
        id: c.tt,
        type: c.kind === "tv" ? "series" : "movie",
        name: c.name,
        year: c.year ? String(c.year) : null,
        ...art(c.tt),
      })),
    },
  };
}
