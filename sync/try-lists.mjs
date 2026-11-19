// Runs the Home lists' cron a few times on this computer (Node's SQLite) and shows what came out:
//   LUMOS_TMDB_KEY=… [MDBLIST_KEY=…] node sync/try-lists.mjs
import { d1 } from "./d1-sqlite.mjs";
import { readLists, refreshLists } from "./lists.js";

const env = { DB: d1(), LUMOS_TMDB_KEY: process.env.LUMOS_TMDB_KEY, MDBLIST_KEY: process.env.MDBLIST_KEY };
for (let round = 1; round <= 8; round++) {
  const t = Date.now();
  const report = await refreshLists(env);
  console.log(`round ${round} (${Date.now() - t} ms): ${report.join("; ") || "nothing due"}`);
  if (!report.length) break;
}
for (const l of await readLists(env)) {
  console.log(`\n${l.name} (${l.items.length})${l.numbered ? " numbered" : ""}`);
  console.log("  " + l.items.slice(0, 6).map((t) => `${t.name} ${t.year || ""} [${t.type} ${t.id}]`).join(" · "));
}
