#!/usr/bin/env bash
# Signs a Komora release on the owner's own computer and publishes it. The key never leaves this
# computer and its password is typed here only, never stored or shown.
#
#   scripts/sign-release.sh 1.0.0 ~/path/to/komora.jks
#
# The keystore may be the .jks itself, or the same file encrypted with openssl (.enc).
# Needs: gh (logged in to GitHub), Java, and Android build-tools (zipalign, apksigner).
# What it does, stopping at the first thing that is not right:
#   1. downloads the draft release's unsigned APK, the one CI built from main;
#   2. checks it is byte for byte the file CI recorded (its SHA-256 in the draft);
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

# The newest build-tools, wherever the Android SDK is
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1 || true)"
[ -x "$BT/apksigner" ] && [ -x "$BT/zipalign" ] || { echo "Android build-tools not found in $SDK/build-tools"; exit 1; }
command -v gh >/dev/null || { echo "gh is not installed"; exit 1; }
sha() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "1/4  Draft v$V…"
[ "$(gh release view "v$V" --repo "$REPO" --json isDraft --jq '.isDraft')" = true ] || { echo "v$V is not a draft (already published?)"; exit 1; }
gh release view "v$V" --repo "$REPO" --json body --jq '.body' > "$WORK/notes.md"
WANT="$(sed -nE 's/.*<!-- unsigned-sha256: ([0-9a-f]{64}) -->.*/\1/p' "$WORK/notes.md" | head -1)"
[[ "$WANT" =~ ^[0-9a-f]{64}$ ]] || { echo "The draft has no checksum from CI"; exit 1; }
gh release download "v$V" --repo "$REPO" --pattern "komora-$V-unsigned.apk" --dir "$WORK"

echo "2/4  Checking it is the file CI built…"
[ "$(sha "$WORK/komora-$V-unsigned.apk")" = "$WANT" ] || { echo "The unsigned file is not the one CI built. Nothing signed."; exit 1; }

echo "3/4  Signing (the keystore asks for its password)…"
# A keystore kept encrypted (.enc, as it was for CI) is opened here, only for this run
if [[ "$KEY" == *.enc ]]; then
  echo "     The encrypted keystore first: its own password."
  openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -in "$KEY" -out "$WORK/komora.jks"
  KEY="$WORK/komora.jks"
fi
"$BT/zipalign" -P 16 -f 4 "$WORK/komora-$V-unsigned.apk" "$WORK/aligned.apk"
OUT="$WORK/komora-$V.apk"
"$BT/apksigner" sign --ks "$KEY" --ks-key-alias komora --out "$OUT" "$WORK/aligned.apk"
"$BT/apksigner" verify --print-certs "$OUT" > "$WORK/certs.txt"
# Each signing scheme may name the same certificate again; any other one stops it
GOT="$({ grep -iE "Signer[^:]*:? *certificate SHA-256 digest:" "$WORK/certs.txt" || true; } | sed -E 's/.*digest: *//' | tr -d ' ' | tr 'A-F' 'a-f' | sort -u)"
[ "$GOT" = "$CERT" ] || { echo "Signed with an unexpected certificate. Nothing published."; cat "$WORK/certs.txt"; exit 1; }
SUM="$(sha "$OUT")"

echo
echo "Komora $V, signed with $CERT"
echo "SHA-256 of komora-$V.apk: $SUM"
printf "4/4  Publish it now? Type yes: "
read -r answer
[ "$answer" = yes ] || { echo "Not published. The draft stays as it was."; exit 1; }

# The notes as people see them: without CI's note, with the signed file's checksum
sed -E '/<!-- unsigned-sha256: [0-9a-f]{64} -->/d' "$WORK/notes.md" > "$WORK/final.md"
printf '\nSHA-256:\n```\n%s  komora-%s.apk\n```\n' "$SUM" "$V" >> "$WORK/final.md"
gh release upload "v$V" "$OUT" --repo "$REPO"
gh release delete-asset "v$V" "komora-$V-unsigned.apk" --repo "$REPO" --yes
gh release edit "v$V" --repo "$REPO" --notes-file "$WORK/final.md" --draft=false --latest
echo "Published: https://github.com/$REPO/releases/tag/v$V"
