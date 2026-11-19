// The card labels' rules (labels.js): node sync/test-labels.mjs
import { labelOf } from "./labels.js";
const t = "2026-11-19";
const cases = [
  [{ kind: "movie", cinema: "2026-10-30", digital: null }, null, "In Cinemas"],
  [{ kind: "movie", cinema: "2026-10-30", digital: null }, 3, "In Cinemas"],
  [{ kind: "movie", cinema: "2026-08-01", digital: "2026-11-10" }, null, "New Movie"],
  [{ kind: "movie", cinema: "2026-06-01", digital: "2026-07-10" }, 4, "#4 Today"],
  [{ kind: "movie", cinema: "2026-06-01", digital: "2026-07-10" }, null, null],
  [{ kind: "movie", cinema: "2026-12-20", digital: null }, null, "Coming 20 Dec"],
  [{ kind: "tv", first: "2026-11-01", last: { date: "2026-11-15", s: 1, e: 3 } }, null, "New Show"],
  [{ kind: "tv", first: "2024-01-01", last: { date: "2026-11-14", s: 3, e: 1 } }, null, "New Season"],
  [{ kind: "tv", first: "2024-01-01", last: { date: "2026-11-17", s: 3, e: 4 } }, 2, "New Episode"],
  [{ kind: "tv", first: "2024-01-01", last: { date: "2026-06-01", s: 2, e: 8 }, next: { date: "2026-12-10", s: 3, e: 1 } }, null, "Returning"],
  [{ kind: "tv", first: "2024-01-01", last: { date: "2026-06-01", s: 2, e: 8 }, next: { date: "2027-03-10", s: 3, e: 1 } }, null, null],
  [null, 7, "#7 Today"],
  [{ kind: "movie", cinema: "2026-12-04", digital: null, release: "2026-12-04" }, 3, "Coming 4 Dec"],
  [{ kind: "movie", cinema: "2027-06-04", digital: null }, null, "Coming Soon"],
  [{ kind: "movie", cinema: "2026-11-18", digital: "2026-11-18" }, 4, "New Movie"],
  [{ kind: "tv", first: "2026-11-28", last: null, next: { date: "2026-11-28", s: 1, e: 1 } }, null, "Coming 28 Nov"],
];
let bad = 0;
for (const [facts, rank, want] of cases) {
  const got = labelOf(facts, t, rank);
  const ok = got === want;
  if (!ok) bad++;
  console.log(`${ok ? "  ✓" : "  ✗"} ${JSON.stringify(facts)} #${rank} → ${got}${ok ? "" : ` (wanted ${want})`}`);
}
console.log(bad ? `\n${bad} failed` : "\nall passed");
process.exit(bad ? 1 : 0);
