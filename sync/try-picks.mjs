// Works out a profile's Top Picks on this computer (Node's SQLite), from a JSON dump of entries rows:
//   LUMOS_TMDB_KEY=… node sync/try-picks.mjs entries.json [viewer]
import { readFileSync } from "node:fs";
import { d1 } from "./d1-sqlite.mjs";
import { handlePicks } from "./picks.js";

const dump = JSON.parse(readFileSync(process.argv[2], "utf8"));
const rows = (Array.isArray(dump) ? dump[0] : dump).results;
const env = { DB: d1(), LUMOS_TMDB_KEY: process.env.LUMOS_TMDB_KEY };
for (const r of rows) await env.DB.prepare("INSERT OR REPLACE INTO entries (account, k, t, v, s) VALUES (?, ?, ?, ?, 0)").bind(r.account, r.k, r.t, r.v).run();
const account = rows[0].account;
for (const round of [1, 2, 3, 4]) {
  const t = Date.now();
  const r = await handlePicks({ viewer: process.argv[3] || "main", again: false }, env, account, Date.now());
  console.log(`round ${round}: ${r.status} in ${Date.now() - t} ms, ${r.body.used} TMDB requests, ai ${r.body.ai}, partial ${r.body.partial}`);
  for (const row of r.body.rows || []) console.log(`\n${row.name} (${row.items.length})\n  ` + row.items.slice(0, 10).map((t) => `${t.name} ${t.year || ""}`).join(" · "));
}
