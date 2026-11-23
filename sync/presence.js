// Concurrent IPTV viewer presence per account and source in D1 lumos_meta.
// Entries expire automatically after 90s without a heartbeat; stale rows are pruned after 5m.

const PRESENCE_TTL_MS = 90_000;
const PRESENCE_PRUNE_MS = 5 * 60_000;
const VALID_ACTIONS = new Set(["check", "start", "heartbeat", "stop"]);

function metaKey(account, sourceId) {
  return `iptv:pres:${account}:${sourceId}`;
}

function cleanChannel(raw) {
  if (typeof raw !== "string") return null;
  const trimmed = raw.trim();
  return trimmed ? trimmed.slice(0, 120) : null;
}

function parsePrunedMap(raw, now) {
  if (!raw) return { map: {}, existed: false, changed: false };
  let parsed = null;
  try {
    parsed = JSON.parse(raw);
  } catch (_) {
    return { map: {}, existed: true, changed: true };
  }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    return { map: {}, existed: true, changed: true };
  }
  const map = {};
  let changed = false;
  for (const [hash, entry] of Object.entries(parsed)) {
    const at = Number(entry && entry.at);
    if (!entry || typeof entry !== "object" || !Number.isFinite(at) || now - at > PRESENCE_TTL_MS) {
      changed = true;
      continue;
    }
    const deviceName = typeof entry.deviceName === "string" && entry.deviceName.trim()
      ? entry.deviceName.trim().slice(0, 60)
      : "Other TV";
    map[hash] = {
      deviceName,
      channelName: cleanChannel(entry.channelName),
      at,
    };
  }
  return { map, existed: true, changed };
}

function listOthers(map, selfHash) {
  const others = [];
  for (const [hash, entry] of Object.entries(map)) {
    if (hash === selfHash) continue;
    others.push({
      deviceName: entry.deviceName,
      channelName: entry.channelName,
      at: entry.at,
    });
  }
  return others;
}

async function persistMap(env, key, map, existed) {
  if (Object.keys(map).length === 0) {
    if (existed) await env.DB.prepare("DELETE FROM lumos_meta WHERE k = ?").bind(key).run();
    return;
  }
  await env.DB.prepare("INSERT OR REPLACE INTO lumos_meta (k, v) VALUES (?, ?)")
    .bind(key, JSON.stringify(map))
    .run();
}

/**
 * Handles `POST /iptv/presence` for `{ action, sourceId, channelName? }`.
 * Tracks active playback per account + source in `lumos_meta` with 90s TTL.
 */
export async function handleIptvPresence(body = {}, env, me, now = Date.now()) {
  const action = typeof (body && body.action) === "string" ? body.action.trim() : "";
  if (!VALID_ACTIONS.has(action)) {
    return { status: 400, body: { error: "Invalid presence action." } };
  }
  const sourceId = typeof (body && body.sourceId) === "string" ? body.sourceId.trim() : "";
  if (!sourceId || sourceId.length > 256) {
    return { status: 400, body: { error: "Missing sourceId." } };
  }

  const key = metaKey(me.account, sourceId);
  const raw = (await env.DB.prepare("SELECT v FROM lumos_meta WHERE k = ?").bind(key).all()).results?.[0]?.v;
  const { map, existed, changed } = parsePrunedMap(raw, now);

  if (action === "check") {
    if (changed) await persistMap(env, key, map, existed);
    return { status: 200, body: { others: listOthers(map, me.hash) } };
  }

  if (action === "stop") {
    const hadSelf = Object.prototype.hasOwnProperty.call(map, me.hash);
    if (hadSelf) delete map[me.hash];
    if (hadSelf || changed) await persistMap(env, key, map, existed);
    return { status: 200, body: { ok: true } };
  }

  const devRow = (await env.DB.prepare("SELECT name FROM lumos_devices WHERE token = ?").bind(me.hash).all()).results?.[0];
  const deviceName = typeof devRow?.name === "string" && devRow.name.trim()
    ? devRow.name.trim().slice(0, 60)
    : "Other TV";

  map[me.hash] = {
    deviceName,
    channelName: cleanChannel(body && body.channelName),
    at: now,
  };
  await persistMap(env, key, map, existed);
  return { status: 200, body: { ok: true, others: listOthers(map, me.hash) } };
}

/** Deletes `iptv:pres:*` rows in `lumos_meta` whose entries have all expired past 5 minutes. */
export async function pruneIptvPresence(env, now = Date.now()) {
  const cutoff = now - PRESENCE_PRUNE_MS;
  const rows = (
    await env.DB.prepare("SELECT k, v FROM lumos_meta WHERE k >= 'iptv:pres:' AND k < 'iptv:pres;'").all()
  ).results || [];

  const toDelete = [];
  for (const row of rows) {
    let latestAt = 0;
    try {
      const parsed = JSON.parse(row.v);
      if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
        for (const entry of Object.values(parsed)) {
          const at = Number(entry && entry.at);
          if (Number.isFinite(at) && at > latestAt) latestAt = at;
        }
      }
    } catch (_) {
      latestAt = 0;
    }
    if (latestAt < cutoff) toDelete.push(row.k);
  }

  for (let i = 0; i < toDelete.length; i += 50) {
    const chunk = toDelete.slice(i, i + 50);
    const del = env.DB.prepare("DELETE FROM lumos_meta WHERE k = ?");
    await env.DB.batch(chunk.map((k) => del.bind(k)));
  }
}
