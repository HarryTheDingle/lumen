// Checks the Lumos sync Worker without Cloudflare: Node's SQLite stands in for D1, and Stremio's API
// is answered here (session "key-u1" is account u1).
//   node sync/test.mjs
import worker, { convertLibrary } from "./worker.js";
import { d1 } from "./d1-sqlite.mjs";

const library = [
  { _id: "tt1", type: "movie", name: "Done Film", removed: false, temp: false, state: { timesWatched: 1, timeOffset: 0, duration: 0 } },
  { _id: "tt2", type: "movie", name: "Half Film", poster: "p", removed: true, temp: true, _mtime: "2026-11-01T00:00:00Z", state: { timeOffset: 1800000, duration: 7200000, video_id: "tt2", lastWatched: "2026-11-02T00:00:00Z" } },
  { _id: "tt3", type: "series", name: "Show", removed: false, temp: false, _ctime: "2026-10-01T00:00:00Z", state: { timeOffset: 600000, duration: 3000000, video_id: "tt3:2:4" } },
];
globalThis.fetch = async (url, init) => {
  const body = JSON.parse(init.body);
  const users = { "key-u1": "u1", "key-dad": "dad1", "key-stranger": "nobody1" };
  const ok = body.authKey in users;
  if (String(url).endsWith("/getUser")) return new Response(JSON.stringify(ok ? { result: { _id: users[body.authKey] } } : { error: { message: "Session does not exist" } }));
  if (String(url).endsWith("/datastoreGet")) return new Response(JSON.stringify(ok ? { result: library } : { error: { message: "no" } }));
  throw new Error("unexpected fetch " + url);
};

const env = { DB: d1(), LUMOS_SETUP: "lumos-setup-phrase" };
const failures = [];
const check = (ok, what) => { console.log(`${ok ? "  ✓" : "  ✗"} ${what}`); if (!ok) failures.push(what); };
const call = async (path, body, token) => {
  const headers = { "Content-Type": "application/json" };
  if (token) headers.Authorization = `Bearer ${token}`;
  const r = await worker.fetch(new Request(`https://sync.example${path}`, { method: "POST", headers, body: JSON.stringify(body) }), env);
  return { status: r.status, json: await r.json() };
};

// Account
check((await call("/account", { setup: "wrong", name: "iPad" })).status === 403, "wrong setup phrase: no account");
let r = await call("/account", { setup: "lumos-setup-phrase", name: "iPad" });
check(r.status === 200 && /^[0-9a-f]{64}$/.test(r.json.token) && r.json.account.startsWith("lumos:"), "setup phrase makes an account and signs the device in");
const ipad = r.json.token;
const account = r.json.account;
check(!env.DB.dump("lumos_devices").some((d) => d.token === ipad), "the device key itself isn't stored");
check((await call("/sync", {}, "f".repeat(64))).status === 401, "an unknown key can't sync");
check((await call("/sync", {})).status === 401, "no key can't sync");

// Signing in a TV with a code
r = await call("/link/start", { name: "Living room TV" });
check(r.status === 200 && /^[0-9A-Z]{4}-[0-9A-Z]{4}$/.test(r.json.code), "a new device gets a code");
const { code, poll } = r.json;
check((await call("/link/poll", { poll })).json.waiting === true, "until approved, it waits");
check((await call("/link/approve", { code })).status === 401, "approving needs a signed-in device");
check((await call("/link/approve", { code: "XXXX-XXXX" }, ipad)).status === 404, "a wrong code isn't approved");
r = await call("/link/approve", { code: code.toLowerCase().replace("-", " ") }, ipad);
check(r.status === 200 && r.json.name === "Living room TV", "the iPad approves it (typed loosely)");
r = await call("/link/poll", { poll });
check(r.status === 200 && r.json.account === account && /^[0-9a-f]{64}$/.test(r.json.token), "the TV is signed in to the same account");
const tv = r.json.token;
check((await call("/link/poll", { poll })).status === 410, "a code works once");

// Sync between them
const t0 = Date.now() - 1000;
r = await call("/sync", { since: 0, entries: [{ k: "progress:main", t: t0, v: { tt9: { at: t0, position: 60, duration: 100 } } }] }, ipad);
check(r.status === 200, "iPad sends a resume point");
r = await call("/sync", { since: 0, entries: [] }, tv);
check(r.json.entries.some((e) => e.k === "progress:main" && e.v.tt9.position === 60), "the TV gets it");
check((await call("/sync", { since: 0, entries: [{ k: "torboxKey", t: t0, v: "secret" }] }, tv)).status === 400, "keys Lumos doesn't sync are refused");
check((await call("/sync", { since: 0, entries: [{ k: "progress:main", t: t0, v: ["tt9"] }] }, tv)).status === 400, "resume points have to be titles by id");
r = await call("/sync", { since: 0, entries: [{ k: "progress:main", t: t0 + 5, v: { tt9: { at: t0 - 10, position: 5 }, tt10: { at: t0 + 5, position: 30 } } }] }, tv);
check(r.json.entries.some((e) => e.k === "progress:main" && e.v.tt9.position === 60 && e.v.tt10.position === 30), "the TV's other title is added, its older copy of the iPad's title isn't, and it gets the merged list back");
r = await call("/sync", { since: 0, entries: [] }, ipad);
const merged = r.json.entries.find((e) => e.k === "progress:main").v;
check(merged.tt9.position === 60 && merged.tt10.position === 30, "the iPad sees both titles");
r = await call("/sync", { since: 0, entries: [{ k: "settings", t: t0, v: { quality: "best" } }] }, ipad);
await call("/sync", { since: 0, entries: [{ k: "settings", t: t0 - 10, v: { quality: "low" } }] }, tv);
r = await call("/sync", { since: 0, entries: [] }, ipad);
check(r.json.entries.find((e) => e.k === "settings").v.quality === "best", "other keys: an older change doesn't replace a newer one");

// Devices
r = await call("/devices", {}, ipad);
check(r.json.devices.length === 2 && r.json.devices.some((d) => d.current && d.name === "iPad"), "devices are listed");
const tvId = r.json.devices.find((d) => d.name === "Living room TV").id;
await call("/devices/remove", { id: tvId }, ipad);
check((await call("/sync", { since: 0, entries: [] }, tv)).status === 401, "a removed device is signed out");

// Bringing Lumen and Stremio across
await env.DB.prepare("INSERT INTO entries (account, k, t, v, s) VALUES (?, ?, ?, ?, ?)").bind("u1", "viewers", 5, JSON.stringify({ list: [{ id: "main", name: "Harry" }, { id: "v2", name: "Guest" }] }), 1).run();
await env.DB.prepare("INSERT INTO entries (account, k, t, v, s) VALUES (?, ?, ?, ?, ?)").bind("u1", "libraryOwners", 5, JSON.stringify({ tt3: ["v2"] }), 1).run();
await env.DB.prepare("INSERT INTO entries (account, k, t, v, s) VALUES (?, ?, ?, ?, ?)").bind("u1", "taste:main", 5, JSON.stringify({ genres: ["Drama"] }), 1).run();
check((await call("/import/stremio", { authKey: "nope" }, ipad)).status === 401, "a bad Stremio session imports nothing");
r = await call("/import/stremio", { authKey: "key-u1" }, ipad);
check(r.status === 200 && r.json.titles === 3, "Stremio library read");
r = await call("/sync", { since: 0, entries: [] }, ipad);
const got = Object.fromEntries(r.json.entries.map((e) => [e.k, e.v]));
check(got.viewers && got.viewers.list.length === 2 && got["taste:main"], "Lumen's profiles and taste came across");
check(got["watched:main"].tt1.on && got["progress:main"].tt2.position === 1800, "Harry's watched film and half-watched film came across");
check(got["progress:main"].tt9 && got["progress:main"].tt9.position === 60, "the resume point Lumos already had is kept alongside");
check(got["progress:v2"].tt3.video === "tt3:2:4" && got["myList:v2"].tt3.on, "the series goes to the profile that owned it, with its episode");
check(!got["myList:main"].tt2, "a title only watched (never added) isn't put in My List");
check(!env.DB.dump().some((e) => e.account === account && e.k === "libraryOwners"), "libraryOwners isn't copied (it's folded in)");

// Lumos's own later changes win over the import
await call("/sync", { since: 0, entries: [{ k: "watched:main", t: Date.now(), v: { tt1: { at: Date.now(), on: false } } }] }, ipad);
await call("/import/stremio", { authKey: "key-u1" }, ipad);
r = await call("/sync", { since: 0, entries: [] }, ipad);
check(r.json.entries.find((e) => e.k === "watched:main").v.tt1.on === false, "importing again doesn't undo a change made in Lumos");

// Sign in with Stremio
check((await call("/signin/stremio", { authKey: "nope", name: "TV" })).status === 401, "Stremio sign-in: a bad Stremio session gets nothing");
check((await call("/signin/stremio", { authKey: "key-stranger", name: "TV" })).status === 403, "Stremio sign-in: an account that never used Lumen is turned away");
r = await call("/signin/stremio", { authKey: "key-u1", name: "Bedroom TV" });
check(r.status === 200 && r.json.account === account && r.json.created === false, "Stremio sign-in: a Stremio account already brought in signs in to its Lumos account");
await env.DB.prepare("INSERT INTO entries (account, k, t, v, s) VALUES (?, ?, ?, ?, ?)").bind("dad1", "viewers", 1, JSON.stringify({ list: [{ id: "main", name: "Mark" }, { id: "vd", name: "Diane" }] }), 1).run();
r = await call("/signin/stremio", { authKey: "key-dad", name: "Dad's TV" });
check(r.status === 200 && r.json.created === true && r.json.account !== account && r.json.account.startsWith("lumos:"), "Stremio sign-in: a Lumen user with their own Stremio gets their own new account");
const dadToken = r.json.token;
const dadAccount = r.json.account;
r = await call("/sync", { since: 0, entries: [] }, dadToken);
check(r.status === 200 && r.json.entries.some((e) => e.k === "viewers" && e.v.list.some((v) => v.name === "Diane")), "Stremio sign-in: their Lumen profiles came across");
check(r.json.entries.some((e) => e.k === "progress:main" && e.v.tt2), "Stremio sign-in: their Stremio history came across");
r = await call("/signin/stremio", { authKey: "key-dad", name: "Dad's bedroom TV" });
check(r.status === 200 && r.json.account === dadAccount && r.json.created === false, "Stremio sign-in: a second TV joins the same account");

// Unit: conversion edge
const conv = convertLibrary(library, {}, []);
check(Object.keys(conv).join() === "main", "no Lumen profiles: everything goes to one profile");

console.log(failures.length ? `\n${failures.length} failed` : "\nall passed");
process.exit(failures.length ? 1 : 0);
