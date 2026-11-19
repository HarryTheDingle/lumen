// Checked pictures for every title Lumos shows, by IMDb id: TMDB's poster, backdrop and logo, used only once TMDB's
// title has been checked against IMDb's own (Cinemeta's name and year). metahub, which every addon's pictures came from,
// files some IMDb ids under another title's pictures (Band of Brothers showed a Brazilian soap); TMDB's own link from IMDb
// id to title is the one to trust, and the check catches the rare one where even that's wrong.
//
//   POST /art { ids: ["tt…"] }     → { art: { tt: { poster, background, logo } | null }, pending: [tt…] }
//   POST /art/wrong { id }          → { art: { tt: … } }   a picture someone said was wrong: the next best instead
//
// Kept in D1 for everyone (lumos_titleart), looked at again after a month. null: no checked pictures (the device keeps
// metahub's). Each answer looks up at most NEW titles (three requests each: a Worker gets 50); the rest come back in
// [pending], for the device to ask again.

import { findByImdb, getJson, tmdbGet } from "./tmdb.js";

const IMG = "https://image.tmdb.org/t/p";
const CINEMETA = "https://v3-cinemeta.strem.io/meta";
const NEW = 14;
const MAX_IDS = 60;
const KEEP_MS = 30 * 86400000;

/** "The Lord of the Rings: The Two Towers" → "lord of rings two towers" (for telling titles apart, not for showing) */
export function plain(name) {
  return String(name || "").toLowerCase().normalize("NFKD").replace(/[̀-ͯ]/g, "")
    .replace(/&/g, " and ").replace(/[^a-z0-9]+/g, " ")
    .split(" ").filter((w) => w && !["the", "a", "an", "of", "and"].includes(w)).join(" ");
}

/**
 * TMDB's title is IMDb's: the same name (or one starts the other: "Dune" and "Dune: Part One") within three years (IMDb
 * dates The Chosen from its 2017 pilot, TMDB from 2019), or a different name the same year (a translated title: IMDb's
 * "Palace" is TMDB's "Under Siege"). Band of Brothers (2001) filed as a 2023 Brazilian soap fails both.
 */
export function sameTitle(imdb, tmdb) {
  const names = [tmdb.title, tmdb.name, tmdb.original_title, tmdb.original_name].map(plain).filter(Boolean);
  const want = plain(imdb.name);
  // (nothing to check against: taken as it is)
  if (!want || !names.length) return true;
  const named = names.some((n) => n === want || n.startsWith(want + " ") || want.startsWith(n + " "));
  const y1 = parseInt(String(imdb.year || "").slice(0, 4), 10);
  const y2 = parseInt(String(tmdb.release_date || tmdb.first_air_date || "").slice(0, 4), 10);
  if (!y1 || !y2) return named;
  return Math.abs(y1 - y2) <= (named ? 3 : 1);
}

/** The best picture not said to be wrong: TMDB's own choice first, then English (or no words), the most voted */
function best(primary, list, bad, langs) {
  if (primary && !bad.has(primary)) return primary;
  const pick = (list || [])
    .filter((i) => i.file_path && !bad.has(i.file_path) && !i.file_path.endsWith(".svg") && langs.includes(i.iso_639_1 ?? null))
    .sort((a, b) => langs.indexOf(a.iso_639_1 ?? null) - langs.indexOf(b.iso_639_1 ?? null) || (b.vote_count || 0) - (a.vote_count || 0) || (b.vote_average || 0) - (a.vote_average || 0))[0];
  return pick ? pick.file_path : null;
}

async function bads(env, tt) {
  const rows = (await env.DB.prepare("SELECT path FROM lumos_artbad WHERE imdb = ?").bind(tt).all()).results || [];
  return new Set(rows.map((r) => r.path));
}

/** Looks one title up and keeps the answer; null when TMDB hasn't got it or its title isn't IMDb's */
async function lookup(env, tt) {
  const hit = await findByImdb(env, tt);
  if (hit === undefined) throw new Error(`TMDB find failed for ${tt}`);
  let row = { poster: "", background: "", logo: "", ok: 0 };
  if (hit) {
    const imdb = await getJson(`${CINEMETA}/${hit.kind === "movie" ? "movie" : "series"}/${tt}.json`).then((j) => (j && j.meta) || null);
    const meta = imdb ? { name: imdb.name, year: imdb.year || imdb.releaseInfo } : null;
    const tmdbMeta = { name: hit.name, original_name: hit.originalName, release_date: hit.year ? String(hit.year) : "" };
    if (meta && !sameTitle(meta, tmdbMeta)) {
      console.log("art mismatch", tt, JSON.stringify(meta.name), meta.year, "TMDB:", JSON.stringify(hit.name), hit.year);
    } else {
      const bad = await bads(env, tt);
      const images = (await tmdbGet(env, `/${hit.kind}/${hit.id}/images?include_image_language=en,null`)) || {};
      row = {
        poster: best(hit.poster || null, images.posters, bad, ["en", null]) || "",
        // (a backdrop with no words on it first: the billboard and title pages put the logo over it)
        background: best(null, images.backdrops, bad, [null, "en"]) || best(hit.backdrop || null, [], bad, []) || "",
        logo: best(null, images.logos, bad, ["en"]) || "",
        ok: 1,
      };
    }
  }
  await env.DB.prepare("INSERT OR REPLACE INTO lumos_titleart (imdb, poster, background, logo, ok, at) VALUES (?, ?, ?, ?, ?, ?)")
    .bind(tt, row.poster, row.background, row.logo, row.ok, Date.now()).run();
  return row;
}

function urls(row) {
  if (!row || !row.ok || !(row.poster || row.background || row.logo)) return null;
  return {
    poster: row.poster ? `${IMG}/w500${row.poster}` : null,
    background: row.background ? `${IMG}/w1280${row.background}` : null,
    logo: row.logo ? `${IMG}/w500${row.logo}` : null,
  };
}

async function known(env, ids) {
  const out = new Map();
  for (let i = 0; i < ids.length; i += 50) {
    const chunk = ids.slice(i, i + 50);
    const rows = (await env.DB.prepare(`SELECT imdb, poster, background, logo, ok, at FROM lumos_titleart WHERE imdb IN (${chunk.map(() => "?").join(",")})`).bind(...chunk).all()).results || [];
    for (const r of rows) out.set(r.imdb, r);
  }
  return out;
}

/** Pictures for [ids]: what's known, and up to [budget] looked up now */
export async function resolveArt(env, ids, budget = NEW) {
  const want = [...new Set((Array.isArray(ids) ? ids : []).filter((x) => typeof x === "string" && /^tt\d{1,10}$/.test(x)))].slice(0, MAX_IDS);
  const have = await known(env, want);
  const art = {};
  const pending = [];
  const now = Date.now();
  for (const tt of want) {
    const r = have.get(tt);
    if (r && now - r.at < KEEP_MS) { art[tt] = urls(r); continue; }
    if (!env.LUMOS_TMDB_KEY || budget <= 0) { if (r) art[tt] = urls(r); else pending.push(tt); continue; }
    budget--;
    try { art[tt] = urls(await lookup(env, tt)); }
    catch (e) { if (r) art[tt] = urls(r); else pending.push(tt); console.log("art failed", tt, e.message); }
  }
  return { art, pending };
}

/** Someone said [tt]'s pictures are wrong: they're never used for it again, and the next best are looked up */
export async function wrongArt(env, tt) {
  if (typeof tt !== "string" || !/^tt\d{1,10}$/.test(tt)) return null;
  const r = (await known(env, [tt])).get(tt);
  if (r) {
    for (const p of [r.poster, r.background, r.logo].filter(Boolean)) {
      await env.DB.prepare("INSERT OR IGNORE INTO lumos_artbad (imdb, path, at) VALUES (?, ?, ?)").bind(tt, p, Date.now()).run();
    }
  }
  console.log("art reported wrong", tt);
  return urls(await lookup(env, tt));
}

/** The cron, between list remakes: the lists' titles checked ahead, so Home's pictures are ready when it opens */
export async function warmArt(env, budget = NEW) {
  const rows = (await env.DB.prepare("SELECT items FROM lumos_lists").all()).results || [];
  const ids = [];
  for (const r of rows) { try { for (const t of JSON.parse(r.items)) ids.push(t.id); } catch (_) { /* skip */ } }
  const unique = [...new Set(ids)];
  const have = await known(env, unique);
  const missing = unique.filter((tt) => !have.has(tt)).slice(0, budget);
  if (!missing.length) return 0;
  await resolveArt(env, missing, budget);
  return missing.length;
}
