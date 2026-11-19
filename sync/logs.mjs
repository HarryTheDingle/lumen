// Problem reports sent from the apps (Settings → Send a Problem Report, or after a crash).
//   node sync/logs.mjs            the latest reports: reference, when, device, version, note
//   node sync/logs.mjs 7K2F9Q     that report's log, written to sync/reports/7K2F9Q.log (git-ignored)
import { execFileSync } from "node:child_process";
import { gunzipSync } from "node:zlib";
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const d1 = (sql) => {
  const out = execFileSync("npx", ["wrangler", "d1", "execute", "lumen-sync", "--remote", "--json", "--command", sql], { cwd: here, encoding: "utf8", maxBuffer: 64 << 20 });
  const j = JSON.parse(out);
  return (Array.isArray(j) ? j[0] : j).results;
};
const id = process.argv[2];
if (!id) {
  for (const r of d1("SELECT id, at, device, version, note, crash, length(body) size FROM lumos_logs ORDER BY at DESC LIMIT 20"))
    console.log(`${r.id}  ${new Date(r.at).toLocaleString("en-GB")}  ${r.device || "?"}  ${r.version || "?"}${r.crash ? "  CRASH" : ""}  ${r.note || ""}`);
} else {
  if (!/^[A-Z0-9]{6}$/.test(id)) throw new Error("A reference is six letters and numbers");
  const r = d1(`SELECT body FROM lumos_logs WHERE id = '${id}'`)[0];
  if (!r) throw new Error("No report " + id);
  mkdirSync(join(here, "reports"), { recursive: true });
  const file = join(here, "reports", id + ".log");
  writeFileSync(file, gunzipSync(Buffer.from(r.body, "base64")));
  console.log(file);
}
