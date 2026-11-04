#!/usr/bin/env bash
# Every permission in the APK must be on this list; anything else fails.
set -euo pipefail
APK="$1"
AAPT=$(ls "$ANDROID_HOME"/build-tools/*/aapt2 | sort -V | tail -1)
"$AAPT" dump permissions "$APK" > permissions.txt
cat permissions.txt
grep -q '^package:' permissions.txt || { echo "::error::Could not read permissions"; exit 1; }
ALLOWED=" android.permission.INTERNET android.permission.REQUEST_INSTALL_PACKAGES android.permission.REQUEST_DELETE_PACKAGES \
android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION android.permission.ENFORCE_UPDATE_OWNERSHIP \
android.permission.POST_NOTIFICATIONS android.permission.RECEIVE_BOOT_COMPLETED android.permission.ACCESS_NETWORK_STATE \
ua.komora.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION "
for p in $(grep -oE "name='[^']+'" permissions.txt | sed "s/name='//;s/'//"); do
  case "$ALLOWED" in
    *" $p "*) ;;
    *) echo "::error::Unexpected permission: $p"; exit 1 ;;
  esac
done
# Komora declares no permissions of its own, apart from the one Android adds for its receivers
for p in $(grep -E "^permission:" permissions.txt | sed -E 's/^permission: *//'); do
  [ "$p" = ua.komora.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION ] || { echo "::error::Unexpected declared permission: $p"; exit 1; }
done
echo "Permissions OK"
