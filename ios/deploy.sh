#!/usr/bin/env bash
# ios/deploy.sh: builds Lumos (engine first) and puts it on the iPhone. Adapted from Lumen's ios/deploy.sh.
#
# Development signing with this Mac's Apple account: on a free account the build expires after 7 days,
# so run this again then. The iPhone has to be unlocked, and on the same Wi-Fi as this Mac (or plugged in).
#
#   ios/deploy.sh            the iPhone
#   ios/deploy.sh ipad       the iPad
#
# LUMOS_TEAM: the Apple developer team to sign with (default: this Mac's)
set -euo pipefail
cd "$(dirname "$0")"
TEAM="${LUMOS_TEAM:-C9SA8NGLQ3}"
KIND="$( [ "${1:-iphone}" = ipad ] && echo iPad || echo iPhone )"
BUNDLE=com.harrythedingle.lumos

step() { printf '\n\033[1m%s\033[0m\n' "$*"; }

step "Engine"
(cd .. && JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}" ./gradlew -q :engine:assembleLumosEngineDebugXCFramework)

step "Project"
xcodegen generate --quiet
sed -i '' \
  -e "s/DEVELOPMENT_TEAM = \"\";/DEVELOPMENT_TEAM = $TEAM;/g" \
  -e 's/CODE_SIGNING_ALLOWED = NO;/CODE_SIGNING_ALLOWED = YES;/g' \
  -e 's/CODE_SIGN_IDENTITY = "";/CODE_SIGN_IDENTITY = "Apple Development";/g' \
  Lumos.xcodeproj/project.pbxproj

step "Building"
# (Lumen's TMDB key, for cast photos and person pages: tmdbKey=… in Lumen's tv/keystore.properties, not in git)
TMDB_KEY="$(sed -n 's/^tmdbKey=//p' ../../dxbi-stremio/tv/keystore.properties 2>/dev/null | head -1 | tr -d '\r" ')"
xcodebuild -project Lumos.xcodeproj -scheme Lumos -destination 'generic/platform=iOS' -allowProvisioningUpdates -quiet build LUMEN_TMDB_KEY="$TMDB_KEY"
APP="$(xcodebuild -project Lumos.xcodeproj -scheme Lumos -destination 'generic/platform=iOS' -showBuildSettings 2>/dev/null \
  | awk -F' = ' '/ BUILT_PRODUCTS_DIR /{print $2; exit}')/Lumos.app"
echo "  $APP"

step "$KIND"
ids="$(xcrun devicectl list devices --json-output - 2>/dev/null | python3 -I -c '
import json, sys
for d in json.load(sys.stdin)["result"]["devices"]:
    hw, cp, dp = d.get("hardwareProperties", {}), d.get("connectionProperties", {}), d.get("deviceProperties", {})
    if hw.get("deviceType") == sys.argv[1] and hw.get("reality") == "physical" and cp.get("transportType") and dp.get("bootState") == "booted":
        print(hw["udid"])
' "$KIND")"
if [ -z "$ids" ]; then
  echo "  No $KIND reachable: unlock it (same Wi-Fi as this Mac, or plugged in) and run this again."
  exit 1
fi
for id in $ids; do
  if xcrun devicectl device install app --device "$id" "$APP" >/tmp/lumos-device.log 2>&1; then
    echo "  Installed"
    xcrun devicectl device process launch --device "$id" --terminate-existing "$BUNDLE" >/dev/null 2>&1 && echo "  Opened" || true
  else
    grep -m1 'ERROR' /tmp/lumos-device.log | sed 's/^/  /'
    echo "  Couldn't install. If it's about the developer disk image, restart the $KIND and try again."
  fi
done
