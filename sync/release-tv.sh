#!/bin/zsh
# Releases Lumos TV: every signed-in TV offers it within a few hours (or the next time Lumos opens).
#   sync/release-tv.sh 0.2.0 "- New thing" "- Fixes: one thing, another"
# Sets the version, builds the release-signed APK, puts it and release.json in sync/public/update/tv, and deploys the
# sync Worker, which serves them. Needs tv/lumos-release.jks (tv/make-release-key.sh) and a version above the last one.
set -e
cd "${0:A:h}/.."
VERSION=$1; shift || true
[[ $VERSION =~ '^[0-9]+\.[0-9]+\.[0-9]+$' ]] || { echo "Give a version: sync/release-tv.sh 0.2.0 \"- what's new\""; exit 1; }
[[ -e tv/lumos-release.jks ]] || { echo "No release key: run tv/make-release-key.sh first"; exit 1; }
NOTES=${(F)@}
IFS=. read MA MI PA <<< "$VERSION"
CODE=$(( MA * 10000 + MI * 100 + PA ))
sed -i '' -E "s/versionName = \"[^\"]*\"/versionName = \"$VERSION\"/; s/versionCode = [0-9]+/versionCode = $CODE/" tv/build.gradle.kts
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew -q :tv:assembleLumosLumenRelease
APK=tv/build/outputs/apk/lumosLumen/release/tv-lumos-lumen-release.apk
~/Library/Android/sdk/build-tools/$(ls ~/Library/Android/sdk/build-tools | sort -V | tail -1)/apksigner verify --print-certs $APK | grep -i "SHA-256" | head -1
OUT=sync/public/update/tv
mkdir -p $OUT
cp $APK $OUT/lumos-tv.apk
VERSION=$VERSION NOTES=$NOTES SIZE=$(stat -f %z $OUT/lumos-tv.apk) node -e '
const fs = require("fs"), f = "sync/public/update/tv/release.json";
const old = fs.existsSync(f) ? JSON.parse(fs.readFileSync(f, "utf8")) : null;
const history = old ? [{ tag_name: old.tag_name, body: old.body }, ...(old.history || [])].slice(0, 10) : [];
fs.writeFileSync(f, JSON.stringify({
  tag_name: "v" + process.env.VERSION, body: process.env.NOTES, published_at: new Date().toISOString(),
  assets: [{ name: "lumos-tv.apk", state: "uploaded", size: +process.env.SIZE, browser_download_url: "https://lumos-sync.hedigital.workers.dev/update/tv/lumos-tv.apk" }],
  history,
}, null, 1));'
cd sync && npx wrangler deploy
echo "Lumos TV $VERSION released."
