#!/bin/zsh
# Makes Lumos TV's own release key, once: tv/lumos-release.jks and its passwords in tv/keystore.properties (both
# git-ignored). Back both up (a password manager, or an encrypted disk): a TV only accepts an update signed with the key
# it was installed with, so losing them means uninstalling Lumos on every TV to update it.
set -e
cd "${0:A:h}"
if [[ -e lumos-release.jks ]]; then echo "tv/lumos-release.jks is already there: nothing done."; exit 0; fi
KEYTOOL=/opt/homebrew/opt/openjdk@17/bin/keytool
PW=$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32)
$KEYTOOL -genkeypair -keystore lumos-release.jks -alias lumos-tv -keyalg RSA -keysize 4096 -validity 36500 \
  -storepass "$PW" -keypass "$PW" -dname "CN=Lumos TV" >/dev/null
printf 'storeFile=lumos-release.jks\nstorePassword=%s\nkeyAlias=lumos-tv\nkeyPassword=%s\n' "$PW" "$PW" > keystore.properties
chmod 600 lumos-release.jks keystore.properties
echo "Made tv/lumos-release.jks and tv/keystore.properties. Back both up now."
