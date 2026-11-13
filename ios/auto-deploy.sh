#!/usr/bin/env bash
# ios/auto-deploy.sh: keeps Lumen from lapsing on the iPad and iPhone. A free Apple account's build stops working
# after 7 days, so this puts a fresh one on every 3 days, run by launchd at login and every 30 minutes (it does nothing
# until a renewal is due). A device that's locked or off the Wi-Fi is skipped, and tried again within 30 minutes.
#
#   ios/auto-deploy.sh         renew if due
#   ios/auto-deploy.sh force   renew now
#
# After 5 days without a renewal it also puts up a notification on the Mac, every 12 hours.
#
# Log: ~/Library/Logs/Lumen/auto-deploy.log
set -uo pipefail
export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
cd "$(dirname "$0")"
STATE="$HOME/Library/Application Support/Lumen Renewal"; LOGS="$HOME/Library/Logs/Lumen"
mkdir -p "$STATE" "$LOGS"
LOG="$LOGS/auto-deploy.log"
now=$(date +%s)
age() { [ -f "$1" ] && echo $(( now - $(stat -f %m "$1") )) || echo 99999999; }

# Nothing renewed for 5 days (the build stops at 7): a notification on the Mac, once every 12 hours, until it is
if [ "$(age "$STATE/renewed")" -gt $((5 * 86400)) ] && [ "$(age "$STATE/warned")" -gt $((12 * 3600)) ]; then
  days=$(( ($(age "$STATE/renewed")) / 86400 ))
  left=$(( 7 - days )); [ "$left" -lt 0 ] && left=0
  osascript -e "display notification \"Last renewed $days days ago; $left left. Unlock the iPhone and iPad on the same Wi-Fi as this Mac.\" with title \"Lumen needs renewing\" sound name \"Submarine\"" >/dev/null 2>&1
  touch "$STATE/warned"
  echo "$(date): warned ($days days)" >> "$LOG"
fi

if [ "${1:-}" != force ]; then
  [ "$(age "$STATE/renewed")" -lt $((3 * 86400)) ] && exit 0   # renewed in the last 3 days
  [ "$(age "$STATE/tried")" -lt $((30 * 60)) ] && exit 0      # tried in the last 30 minutes
fi
touch "$STATE/tried"
# (just after login the network and the devices need a moment)
[ "${1:-}" != force ] && sleep 60

{ echo; echo "== $(date)"; } >> "$LOG"
out="$(LUMEN_NO_LAUNCH=1 ./deploy.sh devices 2>&1)"
echo "$out" | tail -30 >> "$LOG"
# (both the iPad and the iPhone took it)
if [ "$(echo "$out" | grep -c '^  Installed')" -ge 2 ]; then
  touch "$STATE/renewed"
  echo "renewed" >> "$LOG"
else
  echo "not both devices yet: trying again later" >> "$LOG"
fi
