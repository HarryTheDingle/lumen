// A title's review scores (POST /scores { id: "tt…", type: "movie" | "series" }), from MDBList (secret MDBLIST_KEY), kept
// three days. Answers as Lumen's phone.reviews did, so the screens read it unchanged:
//   { scores: [{ source, name, value, votes, url }], scoresFrom: "MDBList" }
// sources: tomatoes (Rotten Tomatoes critics), popcorn (its audience), metacritic, imdb, letterboxd, tmdb, trakt

import { getJson } from "./tmdb.js";

const MDBLIST = "https://api.mdblist.com";
const KEEP_MS = 3 * 86400000;
const NAMES = {
  tomatoes: "Rotten Tomatoes", popcorn: "Rotten Tomatoes Audience", metacritic: "Metacritic", imdb: "IMDb",
  letterboxd: "Letterboxd", tmdb: "TMDB", trakt: "Trakt",
};

/** MDBList's ratings as score lines (only the ones with a value), in the order the page shows them */
export function scoresOf(answer, tt) {
  const out = [];
  for (const r of (answer && Array.isArray(answer.ratings) ? answer.ratings : [])) {
    const source = r && r.source === "audience" ? "popcorn" : r && r.source;
    if (!NAMES[source] || r.value == null || r.value === 0) continue;
    const v = Number(r.value);
    if (!Number.isFinite(v)) continue;
    const value = source === "tomatoes" || source === "popcorn" ? `${Math.round(v)}%`
      : source === "metacritic" ? `${Math.round(v)}`
        : source === "letterboxd" ? v.toFixed(1)
          : source === "imdb" ? v.toFixed(1)
            : `${Math.round(v)}%`;
    const url = source === "imdb" ? `https://www.imdb.com/title/${tt}/` : r.url || null;
    out.push({ source, name: NAMES[source], value, votes: Number(r.votes) || 0, url });
  }
  const order = ["tomatoes", "popcorn", "metacritic", "imdb", "letterboxd", "tmdb", "trakt"];
  return out.sort((a, b) => order.indexOf(a.source) - order.indexOf(b.source));
}

export async function handleScores(body, env) {
  const tt = typeof body.id === "string" && /^tt\d{1,10}$/.test(body.id) ? body.id : null;
  if (!tt) return { scores: [], scoresFrom: null };
  const kind = body.type === "series" ? "show" : "movie";
  const key = `scores:${tt}`;
  const kept = (await env.DB.prepare("SELECT v FROM lumos_meta WHERE k = ?").bind(key).all()).results?.[0]?.v;
  const k = kept ? JSON.parse(kept) : null;
  if (k && Date.now() - k.at < KEEP_MS) return { scores: k.scores, scoresFrom: "MDBList" };
  if (!env.MDBLIST_KEY) return { scores: k ? k.scores : [], scoresFrom: k ? "MDBList" : null, mdbError: "Scores aren't set up on the server." };
  try {
    const answer = await getJson(`${MDBLIST}/imdb/${kind}/${tt}?apikey=${encodeURIComponent(env.MDBLIST_KEY)}`);
    if (!answer) throw new Error("MDBList fetch failed");
    const scores = scoresOf(answer, tt);
    await env.DB.prepare("INSERT OR REPLACE INTO lumos_meta (k, v) VALUES (?, ?)").bind(key, JSON.stringify({ at: Date.now(), scores })).run();
    return { scores, scoresFrom: "MDBList" };
  } catch (e) {
    console.log("scores failed", tt, e.message);
    return { scores: k ? k.scores : [], scoresFrom: k ? "MDBList" : null, mdbError: "Scores couldn't be fetched just now." };
  }
}
