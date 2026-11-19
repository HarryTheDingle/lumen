// Lumos's own lists for Home (no Stremio addons): made on this Worker a little at a time by a cron trigger, kept in D1,
// and handed to every device in one answer (POST /lists).
//
//   TMDB (secret LUMOS_TMDB_KEY): trending today and this week, new to stream, popular on each UK service, in cinemas,
//     coming soon, top rated. Matched to IMDb ids (the ids everything in Lumos plays by), the matches kept for good.
//   MDBList (secret MDBLIST_KEY): IMDb's own lists (Top 250 films and series), which already carry IMDb ids.
//
// Each run of the cron does what fits in a Worker's 50 outside requests: the stalest lists first, each list's missing
// IMDb matches looked up (a list is kept with what's matched so far, and finished on the next runs).

import { art, buildTitle, getJson, tmdbGet } from "./tmdb.js";
export { art };

const MDBLIST = "https://api.mdblist.com";
const REGION = "GB";
const BUDGET = 45;
const KEEP_MS = 6 * 3600 * 1000;

// UK streaming services (TMDB's watch provider ids)
const SERVICES = [
  { id: "netflix", name: "Netflix", provider: 8 },
  { id: "prime", name: "Prime Video", provider: 9 },
  { id: "disney", name: "Disney+", provider: 337 },
  { id: "apple", name: "Apple TV+", provider: 350 },
  { id: "now", name: "NOW", provider: 39 },
  { id: "iplayer", name: "BBC iPlayer", provider: 38 },
];

const day = (offset) => new Date(Date.now() + offset * 86400000).toISOString().slice(0, 10);

// What each list is and where it comes from. kind: "tmdb" pages of results, "mdblist" an IMDb list found by its name
export const LISTS = [
  { id: "top10", name: "Top 10 Today", kind: "tmdb", paths: () => ["/trending/all/day"], max: 10, numbered: true },
  { id: "new", name: "New to Stream", kind: "tmdb", max: 30,
    paths: () => [
      `/discover/movie?region=${REGION}&watch_region=${REGION}&with_release_type=4&release_date.gte=${day(-28)}&release_date.lte=${day(0)}&sort_by=popularity.desc&with_original_language=en`,
      `/discover/tv?watch_region=${REGION}&with_watch_monetization_types=flatrate&first_air_date.gte=${day(-28)}&first_air_date.lte=${day(0)}&sort_by=popularity.desc`,
    ] },
  { id: "trendingFilms", name: "Trending Films", kind: "tmdb", paths: () => ["/trending/movie/week"], max: 20 },
  { id: "trendingSeries", name: "Trending Series", kind: "tmdb", paths: () => ["/trending/tv/week"], max: 20 },
  ...SERVICES.map((s) => ({
    id: `service:${s.id}`, name: `Popular on ${s.name}`, service: s.name, kind: "tmdb", max: 24,
    paths: () => [
      `/discover/tv?watch_region=${REGION}&with_watch_providers=${s.provider}&with_watch_monetization_types=flatrate&sort_by=popularity.desc`,
      `/discover/movie?watch_region=${REGION}&with_watch_providers=${s.provider}&with_watch_monetization_types=flatrate&sort_by=popularity.desc`,
    ],
  })),
  { id: "cinemas", name: "In Cinemas", kind: "tmdb", paths: () => [`/movie/now_playing?region=${REGION}`], max: 20 },
  { id: "comingSoon", name: "Coming Soon", kind: "tmdb", paths: () => [`/movie/upcoming?region=${REGION}`], max: 20, future: true },
  // (only titles with thousands of votes: TMDB's own top rated list fills up with new titles rated by a handful)
  { id: "topRatedFilms", name: "Top Rated Films", kind: "tmdb", paths: () => ["/discover/movie?sort_by=vote_average.desc&vote_count.gte=5000", "/discover/movie?sort_by=vote_average.desc&vote_count.gte=5000&page=2"], max: 30 },
  { id: "topRatedSeries", name: "Top Rated Series", kind: "tmdb", paths: () => ["/discover/tv?sort_by=vote_average.desc&vote_count.gte=2000&without_genres=16,10767,10763", "/discover/tv?sort_by=vote_average.desc&vote_count.gte=2000&without_genres=16,10767,10763&page=2"], max: 30 },
  { id: "imdbTop250Films", name: "IMDb Top 250 Films", kind: "mdblist", search: "IMDb Top 250", mediatype: "movie", max: 40 },
  { id: "imdbTop250Series", name: "IMDb Top 250 Series", kind: "mdblist", search: "IMDb Top 250 TV", mediatype: "show", max: 40 },
];

/** One TMDB result as a Lumos title, once its IMDb id is known */
function fromTmdb(x, type, tt) {
  return buildTitle({
    id: tt,
    type,
    name: x.title || x.name || "",
    year: x.release_date || x.first_air_date || "",
    description: x.overview,
    genres: x.genre_ids,
    rating: x.vote_average,
  });
}

/** Makes (or carries on making) one list; [budget] counts outside requests. Returns { list, done, used } */
async function build(def, env, budget) {
  const reqBudget = { used: 0, max: budget };
  if (def.kind === "mdblist") {
    if (!env.MDBLIST_KEY) return { list: null, done: true, used: reqBudget.used };
    const key = encodeURIComponent(env.MDBLIST_KEY);
    // the list to read: found once by name (the most liked big enough list of the right kind), then remembered
    let ref = (await env.DB.prepare("SELECT v FROM lumos_meta WHERE k = ?").bind(`mdblist:${def.id}`).all()).results?.[0]?.v;
    if (!ref) {
      reqBudget.used++;
      const found = await getJson(`${MDBLIST}/lists/search?query=${encodeURIComponent(def.search)}&apikey=${key}`);
      if (!found) throw new Error("MDBList search failed");
      const pick = (Array.isArray(found) ? found : [])
        .filter((l) => (!def.mediatype || l.mediatype === def.mediatype) && (l.items || 0) >= 200 && /imdb/i.test(l.name || ""))
        .sort((a, b) => (b.likes || 0) - (a.likes || 0))[0];
      if (!pick) return { list: null, done: true, used: reqBudget.used };
      ref = String(pick.id);
      await env.DB.prepare("INSERT OR REPLACE INTO lumos_meta (k, v) VALUES (?, ?)").bind(`mdblist:${def.id}`, ref).run();
    }
    reqBudget.used++;
    const items = await getJson(`${MDBLIST}/lists/${ref}/items?limit=${def.max}&apikey=${key}`);
    if (!items) throw new Error("MDBList items failed");
    const all = [...(items.movies || []), ...(items.shows || [])].sort((a, b) => (a.rank || 0) - (b.rank || 0));
    const picked = all.filter((x) => /^tt\d+$/.test(x.imdb_id || "")).slice(0, def.max);
    const titles = picked.map((x) => ({
      id: x.imdb_id, type: x.mediatype === "show" ? "series" : "movie", name: x.title || "",
      year: x.release_year ? String(x.release_year) : null, description: null, genres: [], rating: null, ...art(x.imdb_id),
    }));
    return { list: titles, done: true, used: reqBudget.used };
  }

  if (!env.LUMOS_TMDB_KEY) return { list: null, done: true, used: reqBudget.used };
  // the lists' pages, side by side and taken in turn (a list of two sources alternates them)
  const pages = [];
  for (const path of def.paths()) {
    const j = await tmdbGet(env, path, { language: "en-GB", budget: reqBudget });
    if (!j) throw new Error(`TMDB failed for ${path}`);
    const type = path.includes("/tv") ? "series" : path.includes("/movie") ? "movie" : null;
    const today = day(0);
    pages.push((j.results || []).map((x) => ({ x, type: type || (x.media_type === "tv" ? "series" : x.media_type === "movie" ? "movie" : null) }))
      .filter((r) => {
        if (!r.type || r.x.adult) return false;
        // (talk shows and the news aren't what Home is for)
        if ((r.x.genre_ids || []).some((g) => g === 10767 || g === 10763)) return false;
        // released ones only, except Coming Soon, which is only the ones that aren't out yet (nothing to play there)
        const out = String(r.x.release_date || r.x.first_air_date || "");
        if (def.future) return out > today;
        return !out || out <= today;
      }));
  }
  const merged = [];
  for (let i = 0; merged.length < def.max * 2 && pages.some((p) => i < p.length); i++) for (const p of pages) if (p[i]) merged.push(p[i]);

  // IMDb ids: from what's known, else asked of TMDB while there's budget
  const keys = merged.map((r) => `${r.type === "series" ? "tv" : "movie"}:${r.x.id}`);
  const known = new Map();
  for (let i = 0; i < keys.length; i += 50) {
    const chunk = keys.slice(i, i + 50);
    const rows = (await env.DB.prepare(`SELECT tmdb, imdb FROM lumos_ids WHERE tmdb IN (${chunk.map(() => "?").join(",")})`).bind(...chunk).all()).results || [];
    for (const r of rows) known.set(r.tmdb, r.imdb);
  }
  let done = true;
  const found = [];
  for (const k of keys) {
    if (known.has(k)) continue;
    if (reqBudget.used >= budget) { done = false; break; }
    const [kind, id] = k.split(":");
    const ext = await tmdbGet(env, `/${kind}/${id}/external_ids`, { budget: reqBudget });
    const tt = ext && /^tt\d+$/.test(ext.imdb_id || "") ? ext.imdb_id : "";
    known.set(k, tt);
    // (written together once the lookups are done: one batch, not a write per title)
    found.push(env.DB.prepare("INSERT OR REPLACE INTO lumos_ids (tmdb, imdb, at) VALUES (?, ?, ?)").bind(k, tt, Date.now()));
  }
  if (found.length) await env.DB.batch(found);
  const seen = new Set();
  const titles = [];
  merged.forEach((r, i) => {
    const tt = known.get(keys[i]);
    if (!tt || seen.has(tt) || titles.length >= def.max) return;
    seen.add(tt);
    titles.push(fromTmdb(r.x, r.type, tt));
  });
  return { list: titles, done, used: reqBudget.used };
}

/** The cron's work: the stalest lists remade, as many as fit in this run */
export async function refreshLists(env, budget = BUDGET) {
  const rows = (await env.DB.prepare("SELECT id, at, done FROM lumos_lists").all()).results || [];
  const state = new Map(rows.map((r) => [r.id, r]));
  const now = Date.now();
  const due = LISTS
    .map((d) => ({ d, s: state.get(d.id) }))
    .filter(({ s }) => !s || !s.done || now - s.at > KEEP_MS)
    .sort((a, b) => (a.s?.done ?? -1) - (b.s?.done ?? -1) || (a.s?.at ?? 0) - (b.s?.at ?? 0));
  const report = [];
  let left = budget;
  for (const { d } of due) {
    if (left <= 2) break;
    try {
      const { list, done, used } = await build(d, env, left);
      left -= used;
      if (list && list.length) {
        await env.DB.prepare("INSERT OR REPLACE INTO lumos_lists (id, name, items, at, done) VALUES (?, ?, ?, ?, ?)")
          .bind(d.id, d.name, JSON.stringify(list), now, done ? 1 : 0).run();
      }
      report.push(`${d.id}: ${list ? list.length : "skipped"}${done ? "" : " (more next time)"}`);
    } catch (e) {
      report.push(`${d.id}: ${e.message}`);
      left -= 1;
    }
  }
  return report;
}

/** Every list made so far, in the order above */
export async function readLists(env) {
  const rows = (await env.DB.prepare("SELECT id, name, items, at FROM lumos_lists").all()).results || [];
  const byId = new Map(rows.map((r) => [r.id, r]));
  const lists = [];
  for (const d of LISTS) {
    const r = byId.get(d.id);
    if (!r) continue;
    let items = [];
    try { items = JSON.parse(r.items); } catch (_) { /* skip */ }
    if (items.length) lists.push({ id: d.id, name: d.name, numbered: !!d.numbered, service: d.service || null, future: !!d.future, at: r.at, items });
  }
  return lists;
}
