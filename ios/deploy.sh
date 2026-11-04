#!/usr/bin/env bash
# ios/deploy.sh: builds Lumen once, then puts it on the iPad and opens it on this Mac.
#
# The same build runs on both: the iPad installs it, and the Mac runs it as an iPad app through Xcode
# (macOS only installs iPad apps from the App Store or TestFlight, so Lumen is on the Mac while Xcode
# runs it). Development signing with this Mac's Apple account: the build expires after 7 days on a
# free account, so run this again then.
#
#   ios/deploy.sh            iPad and Mac
#   ios/deploy.sh ipad       just the iPad
#   ios/deploy.sh mac        just the Mac
#
# LUMEN_TEAM: the Apple developer team to sign with (default: this Mac's)
set -euo pipefail
cd "$(dirname "$0")"
TEAM="${LUMEN_TEAM:-C9SA8NGLQ3}"
WHERE="${1:-both}"
BUNDLE=com.harrythedingle.lumen.ios

step() { printf '\n\033[1m%s\033[0m\n' "$*"; }

step "Project"
xcodegen generate --quiet
# The generated project isn't kept in git, so signing is added here, for this Mac only
sed -i '' \
  -e "s/DEVELOPMENT_TEAM = \"\";/DEVELOPMENT_TEAM = $TEAM;/g" \
  -e 's/CODE_SIGNING_ALLOWED = NO;/CODE_SIGNING_ALLOWED = YES;/g' \
  -e 's/CODE_SIGN_IDENTITY = "";/CODE_SIGN_IDENTITY = "Apple Development";/g' \
  Lumen.xcodeproj/project.pbxproj

step "Building"
# (into Xcode's own build folder, so running it on the Mac afterwards has nothing left to build)
xcodebuild -project Lumen.xcodeproj -scheme Lumen -destination 'generic/platform=iOS' \
  -allowProvisioningUpdates -quiet build
APP="$(xcodebuild -project Lumen.xcodeproj -scheme Lumen -destination 'generic/platform=iOS' -showBuildSettings 2>/dev/null \
  | awk -F' = ' '/ BUILT_PRODUCTS_DIR /{print $2; exit}')/Lumen.app"
echo "  $APP"

if [ "$WHERE" != mac ]; then
  step "iPad"
  # A paired iPad that's on (by cable or the same Wi-Fi) and unlocked. A locked or sleeping one is left
  # alone: asking it to mount Xcode's developer disk as it woke once restarted it
  IPAD="$(xcrun devicectl list devices --json-output - 2>/dev/null | python3 -I -c '
import json, sys
for d in json.load(sys.stdin)["result"]["devices"]:
    hw, cp, dp = d.get("hardwareProperties", {}), d.get("connectionProperties", {}), d.get("deviceProperties", {})
    if hw.get("deviceType") == "iPad" and hw.get("reality") == "physical" and cp.get("transportType") and dp.get("bootState") == "booted":
        print(hw["udid"]); break
')"
  LOCKED=yes
  if [ -n "$IPAD" ]; then
    LOCKED="$(xcrun devicectl device info lockState --device "$IPAD" --json-output - 2>/dev/null \
      | python3 -I -c 'import json, sys; print("yes" if json.load(sys.stdin)["result"].get("passcodeRequired", True) else "no")' 2>/dev/null || echo yes)"
  fi
  if [ -z "$IPAD" ] || [ "$LOCKED" = yes ]; then
    echo "  The iPad isn't reachable or is locked: unlock it (same Wi-Fi as this Mac) and run: ios/deploy.sh ipad"
  else
    # (a failure here still lets the Mac have it)
    if xcrun devicectl device install app --device "$IPAD" "$APP" >/tmp/lumen-ipad.log 2>&1; then
      echo "  Installed"
      xcrun devicectl device process launch --device "$IPAD" --terminate-existing "$BUNDLE" >/dev/null 2>&1 && echo "  Opened"
    else
      grep -m1 'ERROR' /tmp/lumen-ipad.log | sed 's/^/  /'
      echo "  Couldn't install on the iPad. If it's about the developer disk image, restart the iPad, then run: ios/deploy.sh ipad"
    fi
  fi
fi

if [ "$WHERE" != ipad ]; then
  step "Mac"
  open -a Xcode Lumen.xcodeproj
  osascript - "$PWD/Lumen.xcodeproj" <<'EOF'
on run argv
  tell application "Xcode"
    set projectPath to item 1 of argv
    repeat 60 times
      if (count of (workspace documents whose path is projectPath)) > 0 then exit repeat
      delay 1
    end repeat
    set ws to first workspace document whose path is projectPath
    repeat 60 times
      if loaded of ws then exit repeat
      delay 1
    end repeat
    stop ws
    delay 1
    set active run destination of ws to (first run destination of ws whose name is "My Mac (Designed for iPad)")
    set r to run ws
    repeat 300 times
      if status of r is running or completed of r then exit repeat
      delay 1
    end repeat
    if completed of r and status of r is not succeeded then error ("Xcode couldn't run Lumen: " & (status of r as text))
  end tell
end run
EOF
  echo "  Open (keep Xcode open while you use it)"
fi
