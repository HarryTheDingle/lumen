// Keeps the entries that hold secrets (addon links with debrid keys in them, IPTV logins) encrypted in the database,
// with a key only the Worker has (npx wrangler secret put LUMOS_ENTRY_KEY: any long random phrase). A copy of the
// database, or a look at the D1 console, shows "enc1:…" instead of the keys. Without the secret set nothing is
// changed (plain, as before), and anything already plain is still read and gets sealed the next time it's saved.

/** The keys whose values are sealed */
export const SEALED = /^(addons|iptvSources)$/;
const PREFIX = "enc1:";

let cached = null;
async function key(env) {
  const phrase = typeof env.LUMOS_ENTRY_KEY === "string" ? env.LUMOS_ENTRY_KEY : "";
  if (phrase.length < 16) return null;
  if (cached && cached.phrase === phrase) return cached.key;
  const raw = await crypto.subtle.digest("SHA-256", new TextEncoder().encode("lumos-entry-key\n" + phrase));
  const k = await crypto.subtle.importKey("raw", raw, "AES-GCM", false, ["encrypt", "decrypt"]);
  cached = { phrase, key: k };
  return k;
}

function b64(bytes) {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s);
}

/** [text] ready to be stored: sealed when [k] holds secrets and the key is set */
export async function seal(env, k, text) {
  if (!SEALED.test(k) || text.startsWith(PREFIX)) return text;
  const aes = await key(env);
  if (!aes) return text;
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const data = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv, additionalData: new TextEncoder().encode(k) }, aes, new TextEncoder().encode(text)));
  const out = new Uint8Array(12 + data.length);
  out.set(iv);
  out.set(data, 12);
  return PREFIX + b64(out);
}

/** What a stored value says (plain text comes back as it is); null when it's sealed and can't be opened */
export async function open(env, k, stored) {
  if (typeof stored !== "string" || !stored.startsWith(PREFIX)) return stored;
  const aes = await key(env);
  if (!aes) return null;
  try {
    const bytes = Uint8Array.from(atob(stored.slice(PREFIX.length)), (c) => c.charCodeAt(0));
    const plain = await crypto.subtle.decrypt({ name: "AES-GCM", iv: bytes.slice(0, 12), additionalData: new TextEncoder().encode(k) }, aes, bytes.slice(12));
    return new TextDecoder().decode(plain);
  } catch (_) {
    return null;
  }
}

/** Seals what's still plain (the cron does a few at a time) */
export async function sealStored(env) {
  if (!(await key(env))) return 0;
  const rows = (await env.DB.prepare("SELECT account, k, v FROM entries WHERE k IN ('addons', 'iptvSources') AND v NOT LIKE 'enc1:%' LIMIT 100").all()).results || [];
  for (const r of rows) await env.DB.prepare("UPDATE entries SET v = ? WHERE account = ? AND k = ?").bind(await seal(env, r.k, r.v), r.account, r.k).run();
  return rows.length;
}
