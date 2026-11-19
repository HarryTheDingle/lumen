// The Worker on this computer, on Node's SQLite (no Cloudflare), for trying the apps against:
//   node sync/serve.mjs [port]      setup phrase: "local-setup-phrase"
import { createServer } from "node:http";
import worker from "./worker.js";
import { d1 } from "./d1-sqlite.mjs";

const env = { DB: d1(), LUMOS_SETUP: "local-setup-phrase", LUMOS_TMDB_KEY: process.env.LUMOS_TMDB_KEY, MDBLIST_KEY: process.env.MDBLIST_KEY };
const port = Number(process.argv[2]) || 8787;
createServer(async (req, res) => {
  const chunks = [];
  for await (const c of req) chunks.push(c);
  const r = await worker.fetch(new Request(`http://localhost:${port}${req.url}`, {
    method: req.method, headers: req.headers, body: req.method === "POST" ? Buffer.concat(chunks) : undefined,
  }), env);
  res.writeHead(r.status, Object.fromEntries(r.headers));
  res.end(await r.text());
}).listen(port, () => console.log(`Lumos sync on http://localhost:${port}`));
