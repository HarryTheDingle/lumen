// The one label a card may carry (POST /labels { ids: ["tt…"], facts? } (facts: true also answers each title's dates, which
// a film's page goes by for whether it's out) → { labels: { tt: "In Cinemas" | null }, pending: [tt…] }),
// from each title's dates as TMDB has them, kept two days (lumos_facts). Most cards have none. In order, the first that's
// true is the one:
//   In Cinemas     a film in cinemas (in the last four months) and not out to stream yet
//   New Movie      a film out to stream in the last three weeks
//   New Show       a series that started in the last month
//   New Season     a series whose new season started in the last week
//   New Episode    a series with an episode out in the last week
//   Returning      a series whose new season starts within six weeks
//   Coming 21 Nov  not out yet (a film, or a series' first episode): its date, or Coming Soon when it's months off
//   #3 Today       in Top 10 Today (the TV leaves it off the Top 10 row itself, whose numbers say it)
// A film's dates are the UK's where TMDB has them, else the earliest anywhere.

import { findByImdb, tmdbGet } from "./tmdb.js";

const KEEP_MS = 2 * 86400000;
const NEW = 20;
const MAX_IDS = 60;
const DAY = 86400000;

/** The dates that matter from TMDB's details: a film's (cinema, digital), a series' (first, last and next episode) */
export function factsOf(kind, d) {
  if (kind === "movie") {
    const countries = (d.release_dates && d.release_dates.results) || [];
    const dates = (type) => {
      const uk = countries.find((c) => c.iso_3166_1 === "GB");
      const pick = (c) => (c.release_dates || []).filter((r) => r.type === type).map((r) => String(r.release_date || "").slice(0, 10)).filter(Boolean).sort()[0];
      return (uk && pick(uk)) || countries.map(pick).filter(Boolean).sort()[0] || null;
    };
    // (TMDB's types: 3 cinemas, 4 digital, 5 physical; 2 a limited run counts as cinemas)
    return { kind, cinema: dates(3) || dates(2), digital: dates(4), physical: dates(5), release: d.release_date || null };
  }
  const ep = (e) => (e && e.air_date ? { date: e.air_date, s: e.season_number, e: e.episode_number } : null);
  return { kind, first: d.first_air_date || null, last: ep(d.last_episode_to_air), next: ep(d.next_episode_to_air), status: d.status || null };
}

/** The label for one title's [facts] on [today] ("2026-11-19"), [rank] its place in Top 10 Today (or null) */
export function labelOf(facts, today, rank) {
  const ago = (date) => (date ? (Date.parse(today) - Date.parse(date)) / DAY : null);
  // (not out yet: "Coming 21 Nov", or "Coming Soon" further off)
  const coming = (date) => {
    const a = ago(date);
    if (a == null || a >= 0) return null;
    if (a < -120) return "Coming Soon";
    const d = new Date(date);
    return `Coming ${d.getUTCDate()} ${["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"][d.getUTCMonth()]}`;
  };
  if (facts && facts.kind === "movie") {
    const out = facts.digital || facts.physical;
    const c = ago(facts.cinema);
    if (c != null && c >= 0 && c <= 120 && (!out || ago(out) < 0)) return "In Cinemas";
    const d = ago(out);
    if (d != null && d >= 0 && d <= 21) return "New Movie";
    // (nothing out anywhere yet: when it comes, to cinemas or straight to streaming)
    const first = [facts.cinema, out, facts.release].filter(Boolean).sort()[0];
    if (first && ago(first) < 0) return coming(first);
  } else if (facts && facts.kind === "tv") {
    const f = ago(facts.first);
    if (f != null && f >= 0 && f <= 30) return "New Show";
    if (f != null && f < 0) return coming(facts.first);
    const l = facts.last ? ago(facts.last.date) : null;
    if (l != null && l >= 0 && l <= 7) return facts.last.e === 1 ? "New Season" : "New Episode";
    const n = facts.next ? ago(facts.next.date) : null;
    if (n != null && n < 0 && n >= -42 && facts.next.e === 1) return "Returning";
  }
  return rank ? `#${rank} Today` : null;
}

async function kept(env, ids) {
  const out = new Map();
  for (let i = 0; i < ids.length; i += 50) {
    const chunk = ids.slice(i, i + 50);
    const rows = (await env.DB.prepare(`SELECT imdb, facts, at FROM lumos_facts WHERE imdb IN (${chunk.map(() => "?").join(",")})`).bind(...chunk).all()).results || [];
    for (const r of rows) { try { out.set(r.imdb, { facts: JSON.parse(r.facts), at: r.at }); } catch (_) { /* again */ } }
  }
  return out;
}

/** TMDB's id for an IMDb one (kept for good with picks.js's), then its details: the facts, kept */
async function lookup(env, tt, writes) {
  const ref = await findByImdb(env, tt, { writes });
  if (ref === undefined) return undefined;
  let facts = null;
  if (ref) {
    const d = await tmdbGet(env, `/${ref.kind}/${ref.id}${ref.kind === "movie" ? "?append_to_response=release_dates" : ""}`);
    if (!d) return undefined;
    facts = factsOf(ref.kind, d);
  }
  writes.push(env.DB.prepare("INSERT OR REPLACE INTO lumos_facts (imdb, facts, at) VALUES (?, ?, ?)").bind(tt, JSON.stringify(facts), Date.now()));
  return facts;
}

/** Top 10 Today's places: tt → 1…10 */
async function ranks(env) {
  const r = (await env.DB.prepare("SELECT items FROM lumos_lists WHERE id = 'top10'").all()).results?.[0];
  const out = new Map();
  try { JSON.parse(r ? r.items : "[]").forEach((t, i) => t && t.id && out.set(t.id, i + 1)); } catch (_) { /* none */ }
  return out;
}

export async function resolveLabels(env, ids, budget = NEW, withFacts = false) {
  const want = [...new Set((Array.isArray(ids) ? ids : []).filter((x) => typeof x === "string" && /^tt\d{1,10}$/.test(x)))].slice(0, MAX_IDS);
  const have = await kept(env, want);
  const top = await ranks(env);
  const today = new Date().toISOString().slice(0, 10);
  const labels = {};
  const facts = {};
  const pending = [];
  // (what the lookups learnt, written in one batch at the end)
  const writes = [];
  const now = Date.now();
  for (const tt of want) {
    const k = have.get(tt);
    let f = k ? k.facts : undefined;
    if (!k || now - k.at > KEEP_MS) {
      if (env.LUMOS_TMDB_KEY && budget > 0) {
        budget--;
        try { f = await lookup(env, tt, writes); } catch (e) { console.log("labels failed", tt, e.message); }
      }
      // (not known yet and no time left: ask again)
      if (f === undefined) { pending.push(tt); continue; }
    }
    labels[tt] = labelOf(f, today, top.get(tt) || null);
    if (withFacts) facts[tt] = f;
  }
  if (writes.length) await env.DB.batch(writes);
  return withFacts ? { labels, facts, pending } : { labels, pending };
}
