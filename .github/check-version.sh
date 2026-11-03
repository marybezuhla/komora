#!/usr/bin/env bash
# version.txt must be major.minor.patch. For a push to main (a release), it must also be new and
# higher than the latest release, and its tag must not exist yet. Stops before any approval.
set -euo pipefail
EVENT="$1"; REF="$2"; REPO="$3"
V="$(tr -d '[:space:]' < version.txt)"
[[ "$V" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "::error::version.txt must be major.minor.patch, got '$V'"; exit 1; }
[ "$EVENT" = push ] && [ "$REF" = refs/heads/main ] || exit 0
if gh api "repos/$REPO/git/ref/tags/v$V" >/dev/null 2>&1; then
  echo "::error::v$V already exists; raise version.txt first."; exit 1
fi
LATEST="$(gh api "repos/$REPO/releases/latest" --jq .tag_name 2>/dev/null || true)"
LATEST="${LATEST#v}"
if [ -n "$LATEST" ] && [ "$(printf '%s\n%s\n' "$LATEST" "$V" | sort -V | tail -1)" != "$V" -o "$LATEST" = "$V" ]; then
  echo "::error::v$V is not higher than the latest release v$LATEST."; exit 1
fi
echo "Version $V OK"
