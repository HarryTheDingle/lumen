// node tools/recommender-test.mjs: how Picks for you weighs what was watched and scores what to suggest
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const source = readFileSync(join(root, "dxbi.plugin.js"), "utf8");
const code = source.slice(source.indexOf("  // <reco-pure>"), source.indexOf("  // </reco-pure>"));
const { recoMath } = vm.runInNewContext(code + "\n({ recoMath })", { Date, Math, Object, Number });

const now = Date.parse("2026-11-05T12:00:00Z");
const ago = (days) => new Date(now - days * 86400e3).toISOString();
const movie = (state) => ({ type: "movie", state: { duration: 6000000, ...state } });
const show = (state) => ({ type: "series", state });

// Watched and finished beats half-watched; giving up in the first third counts for nothing
const finished = recoMath.weight(movie({ lastWatched: ago(3), timesWatched: 1, timeOffset: 6000000 }), now);
const half = recoMath.weight(movie({ lastWatched: ago(3), timeOffset: 3300000 }), now);
const abandoned = recoMath.weight(movie({ lastWatched: ago(3), timeOffset: 600000 }), now);
assert.ok(finished > half && half > 0, "finished > half-watched > 0");
assert.equal(abandoned, 0, "abandoned films are ignored");
assert.equal(recoMath.weight(movie({ timesWatched: 1 }), now), 0, "never played means nothing");
assert.equal(recoMath.weight({ type: "other", state: { lastWatched: ago(1), timesWatched: 1 } }, now), 0, "only films and series");
console.log("PASS finished > half-watched > abandoned (0)");

// Recent beats old; watched again beats once; a series he's deep into beats one episode
const old = recoMath.weight(movie({ lastWatched: ago(700), timesWatched: 1, timeOffset: 6000000 }), now);
assert.ok(finished > old * 2, "recent is worth more");
const again = recoMath.weight(movie({ lastWatched: ago(3), timesWatched: 3, timeOffset: 6000000 }), now);
assert.ok(again > finished, "watched again counts for more");
assert.equal(recoMath.weight(show({ lastWatched: ago(2), overallTimeWatched: 5 * 60000 }), now), 0, "one episode's start is no taste");
const deep = recoMath.weight(show({ lastWatched: ago(2), overallTimeWatched: 900 * 60000 }), now);
const light = recoMath.weight(show({ lastWatched: ago(2), overallTimeWatched: 60 * 60000 }), now);
assert.ok(deep > light && light > 0, "a series watched for hours counts for more");
console.log("PASS recency, rewatches and hours weigh in");

// What counts as already seen
assert.ok(recoMath.seen(movie({ timesWatched: 1 }), now));
assert.ok(recoMath.seen(movie({ flaggedWatched: 1 }), now));
assert.ok(recoMath.seen(movie({ timeOffset: 4000000 }), now));
assert.ok(!recoMath.seen(movie({ timeOffset: 600000 }), now));
assert.ok(recoMath.seen(show({ overallTimeWatched: 30 * 60000 }), now));
assert.ok(!recoMath.seen(show({ overallTimeWatched: 2 * 60000 }), now));
console.log("PASS seen titles are recognised");

// Scoring: pointed to by two favourites beats one; a better fit and rating win a tie; recs beat similar
const profile = recoMath.profile([{ weight: 2, genres: [18, 80] }, { weight: 1, genres: [18, 35] }]);
assert.equal(profile[18], 1);
assert.ok(profile[80] > profile[35], "the stronger title's genres weigh more");
const base = { genres: [18], rating: 7, votes: 500, year: 2020 };
const two = recoMath.score({ ...base, pointers: [{ w: 1, rank: 0, kind: "rec" }, { w: 1, rank: 2, kind: "rec" }] }, profile, 2026);
const one = recoMath.score({ ...base, pointers: [{ w: 1, rank: 0, kind: "rec" }] }, profile, 2026);
assert.ok(two > one, "two titles pointing to it beat one");
const sim = recoMath.score({ ...base, pointers: [{ w: 1, rank: 0, kind: "similar" }] }, profile, 2026);
assert.ok(one > sim, "a recommendation beats a similar title");
const fits = recoMath.score({ ...base, genres: [18, 80], pointers: [{ w: 1, rank: 0, kind: "rec" }] }, profile, 2026);
const off = recoMath.score({ ...base, genres: [99], pointers: [{ w: 1, rank: 0, kind: "rec" }] }, profile, 2026);
assert.ok(fits > off, "genres he watches fit better");
const good = recoMath.score({ ...base, rating: 8.5, pointers: [{ w: 1, rank: 0, kind: "rec" }] }, profile, 2026);
const poor = recoMath.score({ ...base, rating: 4.5, pointers: [{ w: 1, rank: 0, kind: "rec" }] }, profile, 2026);
assert.ok(good > poor, "a better rating wins");
const unrated = recoMath.score({ ...base, rating: 3, votes: 3, pointers: [{ w: 1, rank: 0, kind: "rec" }] }, profile, 2026);
assert.ok(unrated > poor, "a rating from a handful of votes isn't trusted");
console.log("PASS scoring order");
