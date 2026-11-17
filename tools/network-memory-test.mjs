// node tools/network-memory-test.mjs: speed per network, and the check on slow readings
import { readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import vm from "node:vm";
import assert from "node:assert/strict";
const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const src = readFileSync(join(root, "dxbi.plugin.js"), "utf8");
const code = src.slice(src.indexOf("  const netMemory = {"), src.indexOf("  // The length of what's about to play"));
const ls = new Map();
const ctx = { localStorage: { getItem: (k) => ls.get(k) ?? null, setItem: (k, v) => ls.set(k, v) }, netSpeed: { at: 1 },
  fetch: async () => ({ text: async () => "fl=1\nip=82.13.45.7\ncolo=LHR\n" }), AbortSignal: { timeout: () => undefined }, Date, JSON, Math, Object, Number };
const m = vm.runInNewContext(code + "\nnetMemory", ctx);
assert.equal(await m.currentId(), "v4:82.13.45");
await m.add(6, "test");
assert.equal(await m.estimate(), 0, "one slow reading isn't trusted: test again");
await m.add(5, "play"); await m.add(7, "play");
assert.equal(await m.estimate(), 5, "three slow readings are (the lower middle of what played)");
await m.add(80, "play");
assert.equal((m.all()["v4:82.13.45"].samples).length, 1, "twice as fast: starts afresh");
assert.equal(await m.estimate(), 80, "a fast reading is trusted at once");
await m.add(70, "test");
assert.equal(await m.estimate(), 80, "playback outweighs the speed test");
console.log("PASS network memory: slow readings checked, faster ones start afresh, playback outweighs tests");
