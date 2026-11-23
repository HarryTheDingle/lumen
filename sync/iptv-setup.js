// Mobile-friendly IPTV setup page and submission handler for pairing phone-pasted sources to a TV account.
// Paired TVs generate a short-lived single-use code via POST /iptv/setup/start; users open /iptv?code=XXXX-XXXX
// on their phone and submit Xtream Codes, M3U, or Stalker Portal credentials into the account's iptvSources entry.

const MAX_VALUE = 200 * 1024;
const MAX_SOURCES = 50;

function randomId() {
  const bytes = crypto.getRandomValues(new Uint8Array(8));
  return "iptv_" + [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function escapeHtml(text) {
  return String(text || "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function safeJsonForScript(val) {
  return JSON.stringify(val).replace(/</g, "\\u003c").replace(/>/g, "\\u003e");
}

function normalizeSetupCode(raw) {
  const clean = typeof raw === "string" ? raw.toUpperCase().replace(/[^0-9A-Z]/g, "") : "";
  if (clean.length !== 8) return null;
  return `${clean.slice(0, 4)}-${clean.slice(4)}`;
}

function normalizeHttpUrl(raw, stripTrailingSlash = false) {
  const s = typeof raw === "string" ? raw.trim() : "";
  if (!s || s.length > 2000) return null;
  let u;
  try {
    u = new URL(s);
  } catch (_) {
    return null;
  }
  if (u.protocol !== "http:" && u.protocol !== "https:") return null;
  const out = u.toString();
  return stripTrailingSlash ? out.replace(/\/+$/, "") : out;
}

function normalizeMac(raw) {
  const s = typeof raw === "string" ? raw.trim() : "";
  const hex = s.replace(/[:\-\s]/g, "").toUpperCase();
  if (!/^[0-9A-F]{12}$/.test(hex)) return null;
  return hex.match(/.{2}/g).join(":");
}

function trimOptional(raw, max = 200) {
  if (typeof raw !== "string") return null;
  const s = raw.trim();
  return s ? s.slice(0, max) : null;
}

function clampConnections(raw) {
  const n = Number(raw);
  if (!Number.isFinite(n)) return 1;
  return Math.max(1, Math.min(20, Math.round(n)));
}

function defaultSourceName(type, urlStr) {
  try {
    const host = new URL(urlStr).hostname;
    if (host) return host;
  } catch (_) { /* fallback below */ }
  if (type === "xtream") return "Xtream Source";
  if (type === "m3u") return "M3U Playlist";
  return "Stalker Portal";
}

function normalizeOutputFormat(raw) {
  const s = typeof raw === "string" ? raw.trim().toLowerCase() : "";
  if (!s || s === "mpegts") return "ts";
  if (s === "hls") return "m3u8";
  if (s === "ts" || s === "m3u8" || s === "rtmp") return s;
  return null;
}

/** Validate and normalize a single incoming IPTV source payload. Returns { ok, value } or { ok: false, error }. */
export function validateIptvSource(raw, now = Date.now()) {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
    return { ok: false, error: "Source must be an object." };
  }
  const type = typeof raw.type === "string" ? raw.type.trim().toLowerCase() : "";
  if (type !== "xtream" && type !== "m3u" && type !== "stalker") {
    return { ok: false, error: "Source type must be xtream, m3u, or stalker." };
  }

  const base = {
    id: trimOptional(raw.id, 64) || null,
    type,
    name: "",
    maxConnections: clampConnections(raw.maxConnections),
    userAgent: trimOptional(raw.userAgent, 200),
    epgUrl: null,
    priority: Number.isFinite(Number(raw.priority)) ? Math.round(Number(raw.priority)) : 0,
    enabled: raw.enabled !== false,
    updatedAt: now,
  };

  if (type === "xtream") {
    const serverUrl = normalizeHttpUrl(raw.serverUrl, true);
    if (!serverUrl) return { ok: false, error: "Xtream serverUrl must be a valid http:// or https:// URL." };
    const username = trimOptional(raw.username, 200);
    const password = trimOptional(raw.password, 200);
    if (!username || !password) return { ok: false, error: "Xtream username and password are required." };
    const outputFormat = normalizeOutputFormat(raw.outputFormat);
    if (!outputFormat) return { ok: false, error: "Xtream outputFormat must be ts, m3u8, or rtmp." };
    base.name = trimOptional(raw.name, 80) || defaultSourceName(type, serverUrl);
    return { ok: true, value: { ...base, serverUrl, username, password, outputFormat } };
  }

  if (type === "m3u") {
    const url = normalizeHttpUrl(raw.url, false);
    if (!url) return { ok: false, error: "M3U url must be a valid http:// or https:// URL." };
    let epgUrl = null;
    if (typeof raw.epgUrl === "string" && raw.epgUrl.trim()) {
      epgUrl = normalizeHttpUrl(raw.epgUrl, false);
      if (!epgUrl) return { ok: false, error: "M3U epgUrl must be a valid http:// or https:// URL." };
    }
    base.name = trimOptional(raw.name, 80) || defaultSourceName(type, url);
    return { ok: true, value: { ...base, url, epgUrl } };
  }

  const portalUrl = normalizeHttpUrl(raw.portalUrl, true);
  if (!portalUrl) return { ok: false, error: "Stalker portalUrl must be a valid http:// or https:// URL." };
  const macAddress = normalizeMac(raw.macAddress);
  if (!macAddress) return { ok: false, error: "Stalker macAddress must be a valid 12-hex MAC address." };
  const serialNumber = trimOptional(raw.serialNumber, 100);
  base.name = trimOptional(raw.name, 80) || defaultSourceName(type, portalUrl);
  return { ok: true, value: { ...base, portalUrl, macAddress, serialNumber } };
}

function sourceIdentity(src) {
  if (!src || typeof src !== "object") return "";
  if (src.type === "xtream") return `xtream|${String(src.serverUrl || "").toLowerCase()}|${String(src.username || "")}`;
  if (src.type === "m3u") return `m3u|${String(src.url || "")}`;
  if (src.type === "stalker") return `stalker|${String(src.portalUrl || "").toLowerCase()}|${String(src.macAddress || "").toUpperCase()}`;
  return "";
}

function parseExistingSources(rawJson) {
  if (!rawJson) return { sources: [], wrapper: null };
  let parsed;
  try {
    parsed = JSON.parse(rawJson);
  } catch (_) {
    return { sources: [], wrapper: null };
  }
  if (Array.isArray(parsed)) return { sources: parsed.filter((x) => x && typeof x === "object"), wrapper: null };
  if (parsed && typeof parsed === "object" && Array.isArray(parsed.sources)) {
    return { sources: parsed.sources.filter((x) => x && typeof x === "object"), wrapper: parsed };
  }
  return { sources: [], wrapper: null };
}

function mergeSources(existing, incomingList) {
  const out = existing.map((s) => ({ ...s }));
  const saved = [];
  for (const item of incomingList) {
    const key = sourceIdentity(item);
    const idx = out.findIndex((prev) =>
      (item.id && prev.id === item.id) || (key && sourceIdentity(prev) === key)
    );
    if (idx >= 0) {
      const merged = { ...out[idx], ...item, id: out[idx].id || item.id || randomId() };
      out[idx] = merged;
      saved.push(merged);
    } else {
      const created = { ...item, id: item.id || randomId() };
      out.push(created);
      saved.push(created);
    }
  }
  return { list: out, saved };
}

/**
 * Consume a single-use lumos_iptv_setup code, validate incoming source(s), merge into the account's
 * iptvSources entry, and return { status, body }.
 */
export async function handleIptvSetupSubmit(body, env, now = Date.now()) {
  const code = normalizeSetupCode(body && body.code);
  if (!code) return { status: 400, body: { error: "Enter the 8-character setup code shown on your TV." } };

  const rawItems = Array.isArray(body && body.sources)
    ? body.sources
    : (body && body.source ? [body.source] : []);
  if (!rawItems.length || rawItems.length > 20) {
    return { status: 400, body: { error: "Provide at least one IPTV source (max 20 per request)." } };
  }

  const validated = [];
  for (const item of rawItems) {
    const res = validateIptvSource(item, now);
    if (!res.ok) return { status: 400, body: { error: res.error } };
    validated.push(res.value);
  }

  const setup = (await env.DB.prepare(
    "SELECT code, account, expires FROM lumos_iptv_setup WHERE code = ? AND expires >= ?"
  ).bind(code, now).all()).results?.[0];
  if (!setup) {
    return { status: 404, body: { error: "That setup code is invalid or expired. Generate a new one on your TV." } };
  }

  const existingRow = (await env.DB.prepare(
    "SELECT t, v FROM entries WHERE account = ? AND k = 'iptvSources'"
  ).bind(setup.account).all()).results?.[0];

  const { sources: current, wrapper } = parseExistingSources(existingRow && existingRow.v);
  const { list: nextSources, saved } = mergeSources(current, validated);
  if (nextSources.length > MAX_SOURCES) {
    return { status: 413, body: { error: "Too many IPTV sources on this account." } };
  }

  const payload = JSON.stringify(wrapper ? { ...wrapper, sources: nextSources } : nextSources);
  if (payload.length > MAX_VALUE) {
    return { status: 413, body: { error: "IPTV sources payload exceeds sync size limit." } };
  }

  const writeT = Math.max(now, (Number(existingRow && existingRow.t) || 0) + 1);
  await env.DB.batch([
    env.DB.prepare(
      "INSERT INTO entries (account, k, t, v, s) VALUES (?, 'iptvSources', ?, ?, ?) ON CONFLICT (account, k) DO UPDATE SET t = excluded.t, v = excluded.v, s = excluded.s WHERE excluded.t > entries.t"
    ).bind(setup.account, writeT, payload, now),
    env.DB.prepare("DELETE FROM lumos_iptv_setup WHERE code = ?").bind(code),
  ]);

  const result = { ok: true, count: nextSources.length, source: saved[0] };
  if (saved.length > 1) result.sources = saved;
  return { status: 200, body: result };
}

/** Render a dark, mobile-first HTML5 setup page for sending IPTV sources from a phone to a paired TV. */
export function renderSetupPage(code = "", origin = "") {
  const cleanCode = normalizeSetupCode(code) || String(code || "").trim().slice(0, 16);
  const safeOrigin = typeof origin === "string" && /^https?:\/\/[^/]+$/i.test(origin.trim())
    ? origin.trim()
    : "";
  const submitPath = safeOrigin ? `${safeOrigin}/iptv/setup/submit` : "/iptv/setup/submit";

  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<meta name="color-scheme" content="dark">
<title>Add TV provider · Lumen</title>
<style>
  :root {
    --bg: #000000;
    --card: #111114;
    --surface: #1a1a1f;
    --border: #2a2a32;
    --text: #f5f5f7;
    --muted: #9a9aa6;
    --accent: #ffffff;
    --accent-hover: #e8e8ed;
    --ok: #2ecc71;
    --err: #ff5a5f;
  }
  * { box-sizing: border-box; }
  body {
    margin: 0;
    min-height: 100vh;
    background: var(--bg);
    color: var(--text);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
    padding: 20px 14px 40px;
    display: flex;
    justify-content: center;
  }
  .wrap { width: 100%; max-width: 460px; }
  .brand {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 16px;
  }
  .brand h1 { margin: 0; font-size: 20px; font-weight: 700; letter-spacing: 0.02em; }
  .badge {
    font-size: 11px;
    color: var(--muted);
    border: 1px solid var(--border);
    padding: 4px 8px;
    border-radius: 999px;
    text-transform: uppercase;
    letter-spacing: 0.06em;
  }
  .card {
    background: var(--card);
    border: 1px solid var(--border);
    border-radius: 16px;
    padding: 18px;
    margin-bottom: 14px;
  }
  label {
    display: block;
    font-size: 12px;
    color: var(--muted);
    margin: 12px 0 6px;
    font-weight: 600;
    letter-spacing: 0.02em;
  }
  label:first-child { margin-top: 0; }
  input, select {
    width: 100%;
    background: var(--surface);
    color: var(--text);
    border: 1px solid var(--border);
    border-radius: 10px;
    padding: 11px 12px;
    font-size: 15px;
    outline: none;
  }
  input:focus, select:focus { border-color: #6e6e80; }
  .code-input {
    text-align: center;
    font-size: 22px;
    font-weight: 700;
    letter-spacing: 0.14em;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    text-transform: uppercase;
  }
  .tabs {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 6px;
    background: var(--surface);
    padding: 4px;
    border-radius: 12px;
    border: 1px solid var(--border);
    margin-bottom: 14px;
  }
  .tab {
    background: transparent;
    color: var(--muted);
    border: 0;
    border-radius: 8px;
    padding: 9px 6px;
    font-size: 13px;
    font-weight: 600;
    cursor: pointer;
  }
  .tab.active { background: #ffffff; color: #000000; }
  .row { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
  .hidden { display: none; }
  .actions { display: flex; gap: 10px; margin-top: 16px; }
  .btn {
    flex: 1;
    border: 0;
    border-radius: 10px;
    padding: 13px 14px;
    font-size: 15px;
    font-weight: 700;
    cursor: pointer;
  }
  .btn-primary { background: var(--accent); color: #000000; }
  textarea {
    width: 100%; min-height: 130px; resize: vertical;
    background: var(--surface); color: var(--text);
    border: 1px solid var(--border); border-radius: 12px;
    padding: 12px; font: 14px/1.45 ui-monospace, SFMono-Regular, Menlo, monospace; outline: none;
  }
  .found { margin-top: 12px; border-radius: 12px; background: var(--surface); padding: 4px 14px; display: none; }
  .found.show { display: block; }
  .found div { display: flex; justify-content: space-between; gap: 12px; padding: 10px 0; border-bottom: 1px solid var(--border); font-size: 14px; }
  .found div:last-child { border-bottom: 0; }
  .found span { color: var(--muted); }
  .found b { text-align: right; word-break: break-all; }
  details.more summary { color: var(--muted); font-size: 13px; margin: 6px 0 10px; cursor: pointer; }
  .btn-primary:hover { background: var(--accent-hover); }
  .btn-secondary { background: var(--surface); color: var(--text); border: 1px solid var(--border); flex: 0 0 auto; }
  .btn:disabled { opacity: 0.55; cursor: not-allowed; }
  .queue-item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 10px 12px;
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: 10px;
    margin-top: 8px;
    font-size: 13px;
  }
  .queue-meta { color: var(--muted); font-size: 11px; margin-top: 2px; }
  .remove-btn {
    background: transparent;
    color: var(--muted);
    border: 0;
    font-size: 16px;
    cursor: pointer;
    padding: 4px 8px;
  }
  .status {
    margin-top: 14px;
    padding: 12px;
    border-radius: 10px;
    font-size: 13px;
    line-height: 1.4;
    display: none;
  }
  .status.ok { display: block; background: rgba(46, 204, 113, 0.14); border: 1px solid rgba(46, 204, 113, 0.4); color: var(--ok); }
  .status.err { display: block; background: rgba(255, 90, 95, 0.14); border: 1px solid rgba(255, 90, 95, 0.4); color: var(--err); }
  .hint { color: var(--muted); font-size: 12px; margin: 6px 0 0; line-height: 1.4; }
</style>
</head>
<body>
<main class="wrap">
  <div class="brand">
    <h1>Add TV provider</h1>
  </div>

  <section class="card">
    <label for="code">TV Setup Code</label>
    <input id="code" class="code-input" maxlength="9" autocomplete="off" spellcheck="false" placeholder="XXXX-XXXX" value="${escapeHtml(cleanCode)}">
    <p class="hint">Enter the 8-character code displayed on your TV. Single-use; expires after 10 minutes.</p>
  </section>

  <section class="card">
    <label for="paste">Paste the login message your provider sent</label>
    <textarea id="paste" placeholder="Username: …&#10;Password: …&#10;Server: http://…" autocomplete="off" spellcheck="false"></textarea>
    <p class="hint">Lumen picks out the server, username and password, or a playlist link, and fills in the form below.</p>
    <div id="found" class="found" aria-live="polite"></div>
  </section>

  <section class="card">
    <div class="tabs" role="tablist">
      <button type="button" class="tab active" data-type="xtream">Xtream</button>
      <button type="button" class="tab" data-type="m3u">M3U</button>
      <button type="button" class="tab" data-type="stalker">Stalker</button>
    </div>

    <label for="name">Source Name</label>
    <input id="name" placeholder="My UK IPTV" maxlength="80">

    <div id="fields-xtream">
      <label for="serverUrl">Server URL</label>
      <input id="serverUrl" type="url" placeholder="http://provider.example:8080" autocomplete="off">
      <div class="row">
        <div>
          <label for="username">Username</label>
          <input id="username" autocomplete="off" spellcheck="false">
        </div>
        <div>
          <label for="password">Password</label>
          <input id="password" type="password" autocomplete="off">
        </div>
      </div>
      <label for="outputFormat">Output Format</label>
      <select id="outputFormat">
        <option value="ts">MPEG-TS (.ts)</option>
        <option value="m3u8">HLS (.m3u8)</option>
        <option value="rtmp">RTMP</option>
      </select>
    </div>

    <div id="fields-m3u" class="hidden">
      <label for="m3uUrl">Playlist URL</label>
      <input id="m3uUrl" type="url" placeholder="https://example.com/playlist.m3u" autocomplete="off">
      <label for="epgUrl">EPG XMLTV URL (optional)</label>
      <input id="epgUrl" type="url" placeholder="https://example.com/epg.xml.gz" autocomplete="off">
    </div>

    <div id="fields-stalker" class="hidden">
      <label for="portalUrl">Portal URL</label>
      <input id="portalUrl" type="url" placeholder="http://portal.example/c/" autocomplete="off">
      <div class="row">
        <div>
          <label for="macAddress">MAC Address</label>
          <input id="macAddress" placeholder="00:1A:79:XX:XX:XX" autocomplete="off" spellcheck="false">
        </div>
        <div>
          <label for="serialNumber">Serial Number (optional)</label>
          <input id="serialNumber" autocomplete="off" spellcheck="false">
        </div>
      </div>
    </div>

    <div class="row">
      <div>
        <label for="maxConnections">Max Connections</label>
        <input id="maxConnections" type="number" min="1" max="20" value="1">
      </div>
      <div>
        <label for="userAgent">User-Agent (optional)</label>
        <input id="userAgent" placeholder="Leave blank" maxlength="200">
      </div>
    </div>

    <div id="queue-wrap" class="hidden">
      <label>Queued Sources</label>
      <div id="queue-list"></div>
    </div>

    <div class="actions">
      <button type="button" id="queueBtn" class="btn btn-secondary">+ Add Another</button>
      <button type="button" id="submitBtn" class="btn btn-primary">Send to TV</button>
    </div>
    <div id="status" class="status"></div>
  </section>
</main>
<script>
(function () {
  var SUBMIT_URL = ${safeJsonForScript(submitPath)};
  var activeType = "xtream";
  var queued = [];

  var el = function (id) { return document.getElementById(id); };
  var codeEl = el("code");
  var statusEl = el("status");
  var queueWrap = el("queue-wrap");
  var queueList = el("queue-list");
  var submitBtn = el("submitBtn");

  function setStatus(ok, msg) {
    statusEl.className = "status " + (ok ? "ok" : "err");
    statusEl.textContent = msg;
  }

  function clearStatus() {
    statusEl.className = "status";
    statusEl.textContent = "";
  }

  function formatCode(v) {
    var clean = String(v || "").toUpperCase().replace(/[^0-9A-Z]/g, "").slice(0, 8);
    return clean.length > 4 ? clean.slice(0, 4) + "-" + clean.slice(4) : clean;
  }

  codeEl.addEventListener("input", function () {
    codeEl.value = formatCode(codeEl.value);
  });

  document.querySelectorAll(".tab").forEach(function (btn) {
    btn.addEventListener("click", function () {
      activeType = btn.getAttribute("data-type");
      document.querySelectorAll(".tab").forEach(function (b) {
        b.classList.toggle("active", b === btn);
      });
      ["xtream", "m3u", "stalker"].forEach(function (t) {
        el("fields-" + t).classList.toggle("hidden", t !== activeType);
      });
      clearStatus();
    });
  });

  // ---- paste box: read a provider's message and fill the form ----
  function pick(text, keys) {
    var re = new RegExp("(?:^|\\\\n|\\\\s)(?:" + keys + ")\\\\s*[:=\\\\-–]\\\\s*([^\\\\s]+)", "i");
    var m = text.match(re);
    return m ? m[1].trim() : "";
  }
  function setTab(type) {
    var btn = document.querySelector('.tab[data-type="' + type + '"]');
    if (btn) btn.click();
  }
  function showFound(rows) {
    var box = el("found");
    if (!rows.length) { box.className = "found"; box.innerHTML = ""; return; }
    box.innerHTML = rows.map(function (r) {
      return "<div><span>" + r[0] + "</span><b>" + String(r[1]).replace(/[<>&]/g, "") + "</b></div>";
    }).join("");
    box.className = "found show";
  }
  el("paste").addEventListener("input", function () {
    var t = el("paste").value || "";
    var urls = t.match(/https?:\\/\\/[^\\s"'<>]+/gi) || [];
    var rows = [];
    // an Xtream playlist link carries all three
    var getPhp = urls.filter(function (u) { return /get\\.php\\?/i.test(u) && /username=/i.test(u); })[0];
    var mac = (t.match(/\\b([0-9A-F]{2}(?::[0-9A-F]{2}){5})\\b/i) || [])[1] || "";
    var user = pick(t, "user(?:name)?|login|user id");
    var pass = pick(t, "pass(?:word)?|pwd");
    var server = pick(t, "server(?: url)?|host|url|dns|portal(?: url)?|xtream(?: url)?|domain");
    if (server && !/^https?:\\/\\//i.test(server)) server = (/^[\\w.-]+(:\\d+)?$/.test(server) ? "http://" + server : "");
    if (!server && urls.length) server = urls.filter(function (u) { return !/\\.(m3u8?|xml|gz)(\\?|$)/i.test(u); })[0] || "";
    var conns = pick(t, "max(?:imum)? connections?|connections?|screens?");
    if (getPhp) {
      try {
        var u = new URL(getPhp);
        user = user || u.searchParams.get("username") || "";
        pass = pass || u.searchParams.get("password") || "";
        server = u.origin;
      } catch (e) {}
    }
    if (mac && server) {
      setTab("stalker");
      el("portalUrl").value = server; el("macAddress").value = mac.toUpperCase();
      rows = [["Type", "Stalker portal"], ["Portal", server], ["MAC", mac.toUpperCase()]];
    } else if (user && pass && server) {
      setTab("xtream");
      el("serverUrl").value = server.replace(/\\/+$/, ""); el("username").value = user; el("password").value = pass;
      rows = [["Type", "Xtream login"], ["Server", server.replace(/^https?:\\/\\//, "")], ["Username", user], ["Password", "••••••••"]];
    } else {
      var m3u = urls.filter(function (u) { return /\\.m3u8?(\\?|$)|m3u|playlist/i.test(u); })[0];
      if (m3u) {
        setTab("m3u");
        el("m3uUrl").value = m3u;
        var epg = urls.filter(function (u) { return /\\.xml(\\.gz)?(\\?|$)|epg/i.test(u); })[0];
        if (epg) el("epgUrl").value = epg;
        rows = [["Type", "M3U playlist"], ["Playlist", m3u.replace(/^https?:\\/\\//, "").slice(0, 40) + "…"]];
      }
    }
    if (rows.length && /^\\d{1,2}$/.test(conns)) { el("maxConnections").value = conns; rows.push(["Screens", conns]); }
    showFound(rows);
  });

  function readCurrentSource() {
    var base = {
      type: activeType,
      name: el("name").value.trim(),
      maxConnections: Number(el("maxConnections").value) || 1,
      userAgent: el("userAgent").value.trim()
    };
    if (activeType === "xtream") {
      return Object.assign(base, {
        serverUrl: el("serverUrl").value.trim(),
        username: el("username").value.trim(),
        password: el("password").value,
        outputFormat: el("outputFormat").value
      });
    }
    if (activeType === "m3u") {
      return Object.assign(base, {
        url: el("m3uUrl").value.trim(),
        epgUrl: el("epgUrl").value.trim()
      });
    }
    return Object.assign(base, {
      portalUrl: el("portalUrl").value.trim(),
      macAddress: el("macAddress").value.trim(),
      serialNumber: el("serialNumber").value.trim()
    });
  }

  function hasDraftInput() {
    if (activeType === "xtream") return !!(el("serverUrl").value.trim() || el("username").value.trim() || el("password").value);
    if (activeType === "m3u") return !!el("m3uUrl").value.trim();
    return !!(el("portalUrl").value.trim() || el("macAddress").value.trim());
  }

  function resetFields() {
    ["name", "serverUrl", "username", "password", "m3uUrl", "epgUrl", "portalUrl", "macAddress", "serialNumber", "userAgent"].forEach(function (id) {
      el(id).value = "";
    });
    el("maxConnections").value = "1";
  }

  function renderQueue() {
    queueWrap.classList.toggle("hidden", queued.length === 0);
    queueList.innerHTML = "";
    queued.forEach(function (src, idx) {
      var row = document.createElement("div");
      row.className = "queue-item";
      var info = document.createElement("div");
      var title = document.createElement("div");
      title.textContent = src.name || src.serverUrl || src.url || src.portalUrl || src.type.toUpperCase();
      var meta = document.createElement("div");
      meta.className = "queue-meta";
      meta.textContent = src.type.toUpperCase() + " · " + (src.maxConnections || 1) + " conn";
      info.appendChild(title);
      info.appendChild(meta);
      var rm = document.createElement("button");
      rm.type = "button";
      rm.className = "remove-btn";
      rm.textContent = "×";
      rm.addEventListener("click", function () {
        queued.splice(idx, 1);
        renderQueue();
      });
      row.appendChild(info);
      row.appendChild(rm);
      queueList.appendChild(row);
    });
    submitBtn.textContent = queued.length ? "Send " + (queued.length + (hasDraftInput() ? 1 : 0)) + " Sources to TV" : "Send to TV";
  }

  el("queueBtn").addEventListener("click", function () {
    if (!hasDraftInput()) {
      setStatus(false, "Fill in the current source details before adding another.");
      return;
    }
    queued.push(readCurrentSource());
    resetFields();
    clearStatus();
    renderQueue();
  });

  submitBtn.addEventListener("click", function () {
    clearStatus();
    var code = formatCode(codeEl.value);
    if (code.length !== 9) {
      setStatus(false, "Enter the 8-character setup code shown on your TV.");
      return;
    }
    var list = queued.slice();
    if (hasDraftInput() || !list.length) list.push(readCurrentSource());
    var payload = list.length === 1 ? { code: code, source: list[0] } : { code: code, sources: list };
    submitBtn.disabled = true;
    fetch(SUBMIT_URL, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    })
      .then(function (r) { return r.json().then(function (j) { return { ok: r.ok, data: j }; }); })
      .then(function (res) {
        if (!res.ok) {
          setStatus(false, (res.data && res.data.error) || "Could not send source to TV.");
          return;
        }
        queued = [];
        resetFields();
        renderQueue();
        setStatus(true, "Saved to your TV account (" + res.data.count + " source" + (res.data.count === 1 ? "" : "s") + " synced). You can return to your TV now.");
      })
      .catch(function () {
        setStatus(false, "Network error reaching sync server.");
      })
      .finally(function () {
        submitBtn.disabled = false;
      });
  });
})();
</script>
</body>
</html>`;
}
