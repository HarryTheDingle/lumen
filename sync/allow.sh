#!/bin/zsh
# Who may sign in to Lumen/Lumos with a new Stremio account. They tell you their Stremio email; you run:
#   sync/allow.sh add friend@example.com
#   sync/allow.sh remove friend@example.com
#   sync/allow.sh list
# (works on the live database through your own Wrangler login; no password or key to remember)
set -e
cd "${0:A:h}"
run() { npx wrangler d1 execute lumen-sync --remote --command "$1" }
email=${2:l}
case $1 in
  add)    [[ $email =~ '^[^@ ]+@[^@ ]+\.[^@ ]+$' ]] || { echo "Give an email"; exit 1; }
          run "CREATE TABLE IF NOT EXISTS lumos_allowed (email TEXT PRIMARY KEY, at INTEGER NOT NULL); INSERT OR IGNORE INTO lumos_allowed (email, at) VALUES ('${email//\'/}', strftime('%s','now') * 1000);" >/dev/null
          echo "Added $email: they can sign in with Stremio now." ;;
  remove) run "DELETE FROM lumos_allowed WHERE email = '${email//\'/}';" >/dev/null; echo "Removed $email (an account already made stays)." ;;
  list)   run "SELECT email FROM lumos_allowed ORDER BY at;" ;;
  *)      echo "sync/allow.sh add|remove|list [email]"; exit 1 ;;
esac
