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

// Negative signals: abandoned films and shows (>3 days ago, >5 min played, <30% of film or <20 min of series)
assert.ok(recoMath.dropped(movie({ lastWatched: ago(5), timeOffset: 10 * 60000 }), now), "film switched off at 10m (>3d ago) is dropped");
assert.ok(!recoMath.dropped(movie({ lastWatched: ago(1), timeOffset: 10 * 60000 }), now), "recently paused film isn't dropped yet");
assert.ok(!recoMath.dropped(movie({ lastWatched: ago(5), timeOffset: 2 * 60000 }), now), "under 5m isn't counted as dropped");
assert.ok(!recoMath.dropped(movie({ lastWatched: ago(5), timesWatched: 1, timeOffset: 10 * 60000 }), now), "already finished film isn't dropped");
assert.ok(recoMath.dropped(show({ lastWatched: ago(6), overallTimeWatched: 12 * 60000 }), now), "series abandoned after 12m (>3d ago) is dropped");
assert.ok(!recoMath.dropped(show({ lastWatched: ago(6), overallTimeWatched: 45 * 60000 }), now), "series watched for 45m is seen, not dropped");
console.log("PASS dropped negative signals");

// Cold-to-warm seed blending: Tune Suggestions seeds start strong, decay as watch history grows, and boost when freshly re-tuned
const tune = {
  genres: ["Sci-Fi", "Thriller"],
  titles: [
    { id: "tt0816692", type: "movie", name: "Interstellar", poster: "https://img/interstellar.jpg", genres: ["Sci-Fi", "Drama"] },
    { id: "tt11280740", type: "series", name: "Severance", poster: "https://img/severance.jpg", genres: ["Sci-Fi", "Thriller"] },
  ],
};
const cold = recoMath.blendSeeds([], tune, now - 30 * 86400e3, now);
assert.equal(cold.length, 2, "cold profile gets Tune Suggestions seeds");
assert.ok(cold[0].weight > 1 && cold[0].fromTaste, "cold seeds carry full initial weight");

const warmWatched = Array.from({ length: 10 }, (_, i) => ({
  item: { _id: `tt100000${i}`, type: "movie", name: `Film ${i}` },
  weight: 1.2 - i * 0.05,
}));
const matured = recoMath.blendSeeds(warmWatched, tune, now - 30 * 86400e3, now);
const decayedSeed = matured.find((x) => x.item._id === "tt0816692");
assert.ok(decayedSeed.weight < 0.5 && matured[0].item._id === "tt1000000", "real watch history overtakes old onboarding seeds");

const retuned = recoMath.blendSeeds(warmWatched, tune, now - 86400e3, now);
assert.equal(retuned[0].item._id, "tt0816692", "freshly updated Tune Suggestions rise to the top immediately");

const overlap = recoMath.blendSeeds([{ item: { _id: "tt0816692", type: "movie", name: "Interstellar" }, weight: 1.0 }], tune, now - 30 * 86400e3, now);
assert.ok(overlap.find((x) => x.item._id === "tt0816692").weight > 1.0, "a watched title also picked in Tune Suggestions gets a boost");
console.log("PASS cold-to-warm seed blending and fresh tuning boost");

// Merging AI rankings + resolved additions under strict guardrails
const scoredCands = [
  { id: "tt0000001", type: "movie", name: "First", poster: "https://img/1.jpg", year: 2020, seeds: ["tt0816692"] },
  { id: "tt0000002", type: "movie", name: "Second", poster: "https://img/2.jpg", year: 2021, seeds: ["tt0816692"] },
  { id: "tt0000003", type: "series", name: "Third", poster: "https://img/3.jpg", year: 2022, seeds: ["tt11280740"] },
  { id: "tt0000004", type: "movie", name: "Fourth", poster: "https://img/4.jpg", year: 2019, seeds: ["tt0816692"] },
];
const aiAnswer = { ranked: ["tt0000003", "tt0000002", "tt9999999"] };
const resolvedAdditions = [
  { id: "tt0000010", type: "movie", name: "Deep Cut", poster: "https://img/10.jpg", year: 2018, seeds: ["tt0816692"] },
  { id: "tt0000011", type: "movie", name: "Blocked Seen", poster: "https://img/11.jpg", year: 2017, seeds: ["tt0816692"] },
  { id: "tt0000012", type: "movie", name: "No Poster", poster: null, year: 2016, seeds: ["tt0816692"] },
  { id: "not-imdb", type: "movie", name: "Bad ID", poster: "https://img/bad.jpg", year: 2020, seeds: ["tt0816692"] },
  { id: "tt0000013", type: "movie", name: "Second", poster: "https://img/dup.jpg", year: 2021, seeds: ["tt0816692"] },
];
const merged = recoMath.mergeRanked(scoredCands, aiAnswer, resolvedAdditions, new Set(["tt0000011"]));
assert.deepEqual(
  Array.from(merged, (c) => c.id),
  ["tt0000003", "tt0000002", "tt0000010", "tt0000001", "tt0000004"],
  "reorders by AI ranked, interleaves valid additions, appends unranked tail, and blocks seen/posterless/invalid/duplicate items"
);
console.log("PASS AI mergeRanked and guardrails");
