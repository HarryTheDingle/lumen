#!/usr/bin/env bash
# ios/deploy.sh: builds Lumen once, then puts it on the iPad and iPhone and opens it on this Mac.
#
# The same build runs on all three: the iPad and iPhone install it, and the Mac runs it as an iPad app through Xcode
# (macOS only installs iPad apps from the App Store or TestFlight, so Lumen is on the Mac while Xcode
# runs it). Development signing with this Mac's Apple account: the build expires after 7 days on a
# free account, so run this again then.
#
#   ios/deploy.sh            iPad, iPhone and Mac
#   ios/deploy.sh devices    the iPad and iPhone
#   ios/deploy.sh ipad       just the iPad
#   ios/deploy.sh iphone     just the iPhone
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

# The iPad and iPhone: each paired one that's on (by cable or the same Wi-Fi) and unlocked. A locked or
# sleeping one is left alone: asking it to mount Xcode's developer disk as it woke once restarted it
install_on() {
  local kind="$1" name="$2"
  step "$name"
  local ids
  ids="$(xcrun devicectl list devices --json-output - 2>/dev/null | python3 -I -c '
import json, sys
for d in json.load(sys.stdin)["result"]["devices"]:
    hw, cp, dp = d.get("hardwareProperties", {}), d.get("connectionProperties", {}), d.get("deviceProperties", {})
    if hw.get("deviceType") == sys.argv[1] and hw.get("reality") == "physical" and cp.get("transportType") and dp.get("bootState") == "booted":
        print(hw["udid"])
' "$kind")"
  if [ -z "$ids" ]; then
    echo "  No $name reachable: unlock it (same Wi-Fi as this Mac) and run: ios/deploy.sh $(echo "$kind" | tr '[:upper:]' '[:lower:]')"
    return
  fi
  for id in $ids; do
    local locked
    locked="$(xcrun devicectl device info lockState --device "$id" --json-output - 2>/dev/null \
      | python3 -I -c 'import json, sys; print("yes" if json.load(sys.stdin)["result"].get("passcodeRequired", True) else "no")' 2>/dev/null || echo yes)"
    if [ "$locked" = yes ]; then
      echo "  The $name is locked: unlock it and run: ios/deploy.sh $(echo "$kind" | tr '[:upper:]' '[:lower:]')"
      continue
    fi
    # (a failure here still lets the other devices have it)
    if xcrun devicectl device install app --device "$id" "$APP" >/tmp/lumen-device.log 2>&1; then
      echo "  Installed"
      # (LUMEN_NO_LAUNCH: the scheduled renewal puts the new build on without closing the app someone's using)
      if [ -z "${LUMEN_NO_LAUNCH:-}" ] && xcrun devicectl device process launch --device "$id" --terminate-existing "$BUNDLE" >/dev/null 2>&1; then echo "  Opened"; fi
    else
      grep -m1 'ERROR' /tmp/lumen-device.log | sed 's/^/  /'
      echo "  Couldn't install on the $name. If it's about the developer disk image, restart it, then try again."
      echo "  A device that's never had Lumen needs one run from Xcode first (it registers it with your account)."
    fi
  done
}

case "$WHERE" in mac|iphone) ;; *) install_on iPad iPad ;; esac
case "$WHERE" in mac|ipad) ;; *) install_on iPhone iPhone ;; esac

if [ "$WHERE" = both ] || [ "$WHERE" = mac ]; then
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
