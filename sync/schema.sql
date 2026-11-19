-- Lumos sync. Shares the lumen-sync D1 database with Lumen (so Lumen's profiles can be brought across);
-- every table here is new or already there, nothing of Lumen's is changed.

-- What's synced: one row per account and key. t: when the device changed it (the most recent wins);
-- s: when this server stored it (what a device asks for with "since"); v: the value, as JSON.
-- Lumos accounts are "lumos:<id>"; Lumen's rows (Stremio account ids) sit alongside, untouched.
CREATE TABLE IF NOT EXISTS entries (
  account TEXT NOT NULL,
  k TEXT NOT NULL,
  t INTEGER NOT NULL,
  v TEXT NOT NULL,
  s INTEGER NOT NULL,
  PRIMARY KEY (account, k)
);
CREATE INDEX IF NOT EXISTS entries_since ON entries (account, s);

-- Signed-in devices. token: SHA-256 of the device's key (the key itself is never stored)
CREATE TABLE IF NOT EXISTS lumos_devices (
  token TEXT PRIMARY KEY,
  account TEXT NOT NULL,
  name TEXT NOT NULL,
  created INTEGER NOT NULL,
  seen INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS lumos_devices_account ON lumos_devices (account);

-- Sign-in codes shown on a new device, approved from one already signed in.
-- poll: SHA-256 of the secret only the new device has; account: set once approved
CREATE TABLE IF NOT EXISTS lumos_links (
  code TEXT PRIMARY KEY,
  poll TEXT NOT NULL,
  name TEXT NOT NULL,
  account TEXT,
  expires INTEGER NOT NULL
);

-- Lumos's own Home lists (lists.js): made by the cron a little at a time; items is the list's titles as JSON.
-- done: 0 while some of its IMDb matches are still to be looked up.
CREATE TABLE IF NOT EXISTS lumos_lists (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  items TEXT NOT NULL,
  at INTEGER NOT NULL,
  done INTEGER NOT NULL DEFAULT 1
);

-- TMDB id ("movie:123", "tv:456") → IMDb id ("" when TMDB has none), kept for good
CREATE TABLE IF NOT EXISTS lumos_ids (
  tmdb TEXT PRIMARY KEY,
  imdb TEXT NOT NULL,
  at INTEGER NOT NULL
);

-- Small things the lists remember (which MDBList list is IMDb's Top 250…)
CREATE TABLE IF NOT EXISTS lumos_meta (
  k TEXT PRIMARY KEY,
  v TEXT NOT NULL
);

-- Checked pictures for a title (art.js): TMDB's poster, backdrop and logo paths ("" none), ok: 0 when TMDB hasn't got it
-- or its title isn't IMDb's (the device keeps metahub's then). Looked at again after a month.
CREATE TABLE IF NOT EXISTS lumos_titleart (
  imdb TEXT PRIMARY KEY,
  poster TEXT NOT NULL,
  background TEXT NOT NULL,
  logo TEXT NOT NULL,
  ok INTEGER NOT NULL,
  at INTEGER NOT NULL
);

-- Pictures someone said were wrong for a title: never picked for it again
CREATE TABLE IF NOT EXISTS lumos_artbad (
  imdb TEXT NOT NULL,
  path TEXT NOT NULL,
  at INTEGER NOT NULL,
  PRIMARY KEY (imdb, path)
);

-- Problem reports from the apps: the last 30 minutes of a device's log, gzipped then base64 (secrets taken out on the
-- device first). id: the short reference the app shows ("tell Harry: report 7K2F9Q")
CREATE TABLE IF NOT EXISTS lumos_logs (
  id TEXT PRIMARY KEY,
  account TEXT NOT NULL,
  at INTEGER NOT NULL,
  device TEXT,
  version TEXT,
  note TEXT,
  crash INTEGER NOT NULL DEFAULT 0,
  body TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS lumos_logs_at ON lumos_logs (at);

-- Sign in with Stremio: which Lumos account a Stremio account (Lumen's people) signs in to. Made by /import/stremio and
-- by /signin/stremio (a new account for a Stremio account that used Lumen)
CREATE TABLE IF NOT EXISTS lumos_stremio (
  stremio TEXT PRIMARY KEY,
  account TEXT NOT NULL,
  at INTEGER NOT NULL
);

-- The dates a card's label comes from (labels.js): a film's cinema and digital releases, a series' first, last and next
-- episodes, as TMDB has them. null: TMDB hasn't the title. Looked at again after two days
CREATE TABLE IF NOT EXISTS lumos_facts (
  imdb TEXT PRIMARY KEY,
  facts TEXT,
  at INTEGER NOT NULL
);
