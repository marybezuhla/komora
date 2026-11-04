#!/usr/bin/env bash
# Signs a Komora release on the owner's own computer and publishes it. The key never leaves this
# computer and its password is typed here only, never stored or shown.
#
#   scripts/sign-release.sh 1.0.0 ~/path/to/komora.jks
#
# The keystore may be the .jks itself, or the same file encrypted with openssl (.enc).
# Needs: gh 2.49+ (logged in to GitHub), Java, and Android build-tools 35+ (zipalign, apksigner, aapt2).
# Please do not push to main while it runs.
# What it does, stopping at the first thing that is not right:
#   1. finds the one draft release of that version and keeps to it by its id;
#   2. downloads its unsigned APK and checks with GitHub's signed build record (attestation) that
#      this very file was built by Komora's build.yml from main, then that it is ua.komora <version>;
#   3. aligns and signs it with the key; checks there is exactly one signer and that it is Komora's
#      own certificate (signing/certificate-sha256.txt);
#   4. shows what will be published and asks once more; then uploads komora-<version>.apk, removes
#      the unsigned file, writes the SHA-256 into the notes and publishes the release.
set -euo pipefail

REPO="Marybezu/komora"
V="${1:-}"
KEY="${2:-}"
[[ "$V" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "Usage: $0 <version, e.g. 1.0.0> <keystore .jks>"; exit 1; }
[ -f "$KEY" ] || { echo "Keystore not found: $KEY"; exit 1; }
HERE="$(cd "$(dirname "$0")/.." && pwd)"
CERT="$(tr -d '[:space:]' < "$HERE/signing/certificate-sha256.txt")"
[[ "$CERT" =~ ^[0-9a-f]{64}$ ]] || { echo "Bad signing/certificate-sha256.txt"; exit 1; }

# The newest build-tools, wherever the Android SDK is (sorted by version without sort -V, for older macOS)
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | awk -F/ '{print $NF" "$0}' | sort -t. -k1,1n -k2,2n -k3,3n | tail -1 | cut -d' ' -f2- || true)"
for t in apksigner zipalign aapt2; do
  [ -x "$BT/$t" ] || { echo "Android build-tools (35 or newer) not found in $SDK/build-tools"; exit 1; }
done
command -v gh >/dev/null || { echo "gh is not installed"; exit 1; }
sha() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "1/4  Draft v$V…"
IDS="$(gh api "repos/$REPO/releases?per_page=100" --jq ".[] | select(.draft and .tag_name == \"v$V\") | .id")"
[ "$(printf '%s\n' "$IDS" | grep -c .)" = 1 ] || { echo "Expected exactly one draft of v$V, found: ${IDS:-none}"; exit 1; }
ID="$IDS"
API="repos/$REPO/releases/$ID"
gh api "$API" --jq '.body' > "$WORK/notes.md"
UNSIGNED="komora-$V-unsigned.apk"
URL="$(gh api "$API" --jq ".assets[] | select(.name == \"$UNSIGNED\") | .url")"
[ -n "$URL" ] || { echo "The draft has no $UNSIGNED"; exit 1; }
gh api -H "Accept: application/octet-stream" "$URL" > "$WORK/$UNSIGNED"

echo "2/4  Checking it is the file Komora's CI built from main…"
WANT="$(sed -nE 's/.*<!-- unsigned-sha256: ([0-9a-f]{64}) -->.*/\1/p' "$WORK/notes.md" | head -1)"
[ "$(sha "$WORK/$UNSIGNED")" = "$WANT" ] || { echo "The file does not match the draft's checksum. Nothing signed."; exit 1; }
# GitHub's own signed record: this file came out of build.yml, run on main of this repository
gh attestation verify "$WORK/$UNSIGNED" --repo "$REPO" \
  --signer-workflow "$REPO/.github/workflows/build.yml" --source-ref refs/heads/main \
  --deny-self-hosted-runners >/dev/null \
  || { echo "GitHub cannot confirm this file was built by Komora's CI from main. Nothing signed."; exit 1; }
# And it is Komora, this version
BADGE="$("$BT/aapt2" dump badging "$WORK/$UNSIGNED" | head -1)"
case "$BADGE" in
  "package: name='ua.komora' "*"versionName='$V'"*) ;;
  *) echo "Not Komora $V: $BADGE"; exit 1 ;;
esac

echo "3/4  Signing (the keystore asks for its password)…"
# A keystore kept encrypted (.enc, as it was for CI) is opened here, only for this run
if [[ "$KEY" == *.enc ]]; then
  echo "     The encrypted keystore first: its own password."
  openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -in "$KEY" -out "$WORK/komora.jks"
  KEY="$WORK/komora.jks"
fi
"$BT/zipalign" -P 16 -f 4 "$WORK/$UNSIGNED" "$WORK/aligned.apk"
OUT="$WORK/komora-$V.apk"
"$BT/apksigner" sign --ks "$KEY" --ks-key-alias komora --out "$OUT" "$WORK/aligned.apk"
"$BT/apksigner" verify --print-certs "$OUT" > "$WORK/certs.txt"
# Each signing scheme may name the same certificate again; any other one stops it
GOT="$({ grep -iE "Signer[^:]*:? *certificate SHA-256 digest:" "$WORK/certs.txt" || true; } | sed -E 's/.*digest: *//' | tr -d ' ' | tr 'A-F' 'a-f' | sort -u)"
[ "$GOT" = "$CERT" ] || { echo "Signed with an unexpected certificate. Nothing published."; cat "$WORK/certs.txt"; exit 1; }
SUM="$(sha "$OUT")"
# The opened keystore is not needed any more
rm -f "$WORK/komora.jks"

echo
echo "Komora $V, signed with $CERT"
echo "SHA-256 of komora-$V.apk: $SUM"
printf "4/4  Publish it now? Type yes: "
read -r answer
[ "$answer" = yes ] || { echo "Not published. The draft stays as it was."; exit 1; }

# The notes as people see them: without CI's note, with the signed file's checksum
sed -E '/<!-- unsigned-sha256: [0-9a-f]{64} -->/d' "$WORK/notes.md" > "$WORK/final.md"
printf '\nSHA-256:\n```\n%s  komora-%s.apk\n```\n' "$SUM" "$V" >> "$WORK/final.md"
# Still the same draft, by its id; the signed file goes up (replacing one from an interrupted run)
[ "$(gh api "$API" --jq '.draft')" = true ] || { echo "The draft changed meanwhile. Nothing published."; exit 1; }
gh release upload "v$V" "$OUT" --repo "$REPO" --clobber
for a in $(gh api "$API" --jq ".assets[] | select(.name == \"$UNSIGNED\") | .id"); do
  gh api -X DELETE "repos/$REPO/releases/assets/$a" >/dev/null
done
# Exactly one file, the signed one, before anything is made public
NAMES="$(gh api "$API" --jq '[.assets[].name] | join(",")')"
[ "$NAMES" = "komora-$V.apk" ] || { echo "Unexpected files in the draft: $NAMES. Nothing published."; exit 1; }
gh api -X PATCH "$API" -F draft=false -f make_latest=true -F body=@"$WORK/final.md" >/dev/null
echo "Published: https://github.com/$REPO/releases/tag/v$V"
