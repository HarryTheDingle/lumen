// Shared TMDB, JSON fetch, genre map, title builder, and IMDb-to-TMDB lookup helpers for the Lumos sync Worker.

export const TMDB = "https://api.themoviedb.org/3";

// TMDB genre ID -> display name (movies and TV)
export const GENRES = {
  28: "Action", 12: "Adventure", 16: "Animation", 35: "Comedy", 80: "Crime", 99: "Documentary", 18: "Drama", 10751: "Family",
  14: "Fantasy", 36: "History", 27: "Horror", 10402: "Music", 9648: "Mystery", 10749: "Romance", 878: "Sci-Fi", 53: "Thriller",
  10752: "War", 37: "Western", 10759: "Action & Adventure", 10762: "Kids", 10763: "News", 10764: "Reality", 10765: "Sci-Fi & Fantasy",
  10766: "Soap", 10767: "Talk", 10768: "War & Politics", 770: "TV Movie",
};

/** Metahub fallback artwork URLs by IMDb ID (replaced on-device with checked TMDB art from art.js). */
export function art(tt) {
  return {
    poster: `https://images.metahub.space/poster/medium/${tt}/img`,
    background: `https://images.metahub.space/background/medium/${tt}/img`,
    logo: `https://images.metahub.space/logo/medium/${tt}/img`,
  };
}

/** Shared Lumos title shape for Home lists and recommendations. */
export function buildTitle({ id, type, name, year, description, genres, rating }, genreMap = GENRES) {
  return {
    id,
    type,
    name: name || "",
    year: year ? String(year).slice(0, 4) : null,
    description: description || null,
    genres: (genres || []).map((g) => genreMap[g]).filter(Boolean).slice(0, 3),
    rating: rating ? Math.round(rating * 10) / 10 : null,
    ...art(id),
  };
}

/** Shared fetch helper returning parsed JSON, or null on non-ok / network / parse error. */
export async function getJson(url, opts = {}) {
  const { timeout = 8000, headers, ...rest } = opts;
  const init = {
    ...rest,
    headers: { "User-Agent": "Lumen/1.0", ...(headers || {}) },
    signal: rest.signal || AbortSignal.timeout(timeout),
  };
  try {
    const r = await fetch(url, init);
    return r && r.ok ? await r.json().catch(() => null) : null;
  } catch (_) {
    return null;
  }
}

/** TMDB v3 GET with api_key, optional language (e.g. "en-GB"), and optional request budget ({ used, max }). */
export async function tmdbGet(env, path, opts = {}) {
  const key = encodeURIComponent(env.LUMOS_TMDB_KEY || "");
  if (!key) return null;
  if (opts.budget) {
    if (opts.budget.max != null && opts.budget.used >= opts.budget.max) return null;
    opts.budget.used++;
  }
  const sep = path.includes("?") ? "&" : "?";
  const lang = opts.language ? `&language=${encodeURIComponent(opts.language)}` : "";
  return getJson(`${TMDB}${path}${sep}api_key=${key}${lang}`, { timeout: opts.timeout, headers: opts.headers });
}

/**
 * Shared IMDb -> TMDB lookup cached in lumos_meta ("find:<tt>") and lumos_ids.
 * Returns:
 *   - { kind, id, name, genres, year, ... } when found (compatible with existing cached rows)
 *   - null when TMDB confirmed no match (cached as { id: 0 })
 *   - undefined when the TMDB request could not be completed (missing key, over budget, or error)
 */
export async function findByImdb(env, tt, opts = {}) {
  const raw = (await env.DB.prepare("SELECT v FROM lumos_meta WHERE k = ?").bind(`find:${tt}`).all()).results?.[0]?.v;
  let kept = null;
  if (raw) {
    try { kept = JSON.parse(raw); } catch (_) { kept = null; }
  }
  if (kept) return kept.id ? kept : null;

  const j = await tmdbGet(env, `/find/${tt}?external_source=imdb_id`, opts);
  if (!j) return undefined;

  const m = (j.movie_results || [])[0];
  const t = (j.tv_results || [])[0];
  const x = m || t;
  const out = x ? {
    kind: m ? "movie" : "tv",
    id: x.id,
    name: x.title || x.name || "",
    originalName: x.original_title || x.original_name || "",
    genres: x.genre_ids || [],
    year: Number(String(x.release_date || x.first_air_date || "").slice(0, 4)) || 0,
    poster: x.poster_path || "",
    backdrop: x.backdrop_path || "",
  } : { id: 0 };

  const metaStmt = env.DB.prepare("INSERT OR REPLACE INTO lumos_meta (k, v) VALUES (?, ?)").bind(`find:${tt}`, JSON.stringify(out));
  const idStmt = x
    ? env.DB.prepare("INSERT OR REPLACE INTO lumos_ids (tmdb, imdb, at) VALUES (?, ?, ?)").bind(`${out.kind}:${out.id}`, tt, Date.now())
    : null;

  if (Array.isArray(opts.writes)) {
    opts.writes.push(metaStmt);
    if (idStmt) opts.writes.push(idStmt);
  } else {
    await metaStmt.run();
    if (idStmt) await idStmt.run().catch(() => {});
  }
  return x ? out : null;
}
