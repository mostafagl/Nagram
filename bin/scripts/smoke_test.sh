#!/bin/bash
# Installs the built APK on a running emulator, launches it and fails if the
# app is not alive after startup. Prints crash/ANR details from logcat.

APK=$(find TMessagesProj/build/outputs/apk -name '*arm64-v8a*.apk' | head -1)
# The diagnostic build uses its own package (com.mostafagl.parvagram.diag).
PKG=${PKG:-$(grep -o 'applicationId = "[^"]*"' TMessagesProj/build.gradle | head -1 | sed 's/.*"\(.*\)"/\1/')}
PKG=${PKG:-com.mostafagl.parvagram}
echo "APK: $APK"
echo "===== NATIVE LIBS IN APK ====="
unzip -l "$APK" | grep "lib/"

# Update path, as users get it: install the published release (what is on their phones), run it so it
# leaves data behind, then install this build over it. A fresh install alone does not prove an update
# works (signature, versionCode, data/database migration).
echo "===== UPDATE FROM THE PUBLISHED RELEASE ====="
REPO=${GITHUB_REPOSITORY:-mostafagl/Nagram}
AUTH=()
if [ -n "$GH_TOKEN" ]; then AUTH=(-H "Authorization: Bearer $GH_TOKEN"); fi
OLD_URL=$(curl -fsSL "${AUTH[@]}" "https://api.github.com/repos/$REPO/releases/latest" 2>/dev/null \
  | python3 -c 'import json,sys
d=json.load(sys.stdin)
print(next((a["browser_download_url"] for a in d.get("assets", []) if a["name"].endswith("arm64-v8a.apk")), ""))' 2>/dev/null)
if [ -n "$OLD_URL" ] && curl -fsSL -o published.apk "$OLD_URL"; then
  echo "Published release: $OLD_URL"
  adb install published.apk || exit 1
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1
  sleep 25
  adb shell am force-stop "$PKG"
  if ! adb install -r "$APK"; then
    echo "::error::This build cannot be installed over the published release (see the Failure line above)."
    exit 1
  fi
  echo "Installed over the published release: OK"
else
  echo "::warning::Could not download the published release; the update path was not tested."
  adb install -r "$APK" || exit 1
fi
adb logcat -c
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1
sleep 45

adb logcat -d -v threadtime > logcat.txt
echo "===== FATAL EXCEPTIONS ====="
grep -n -A60 "FATAL EXCEPTION" logcat.txt | head -400
echo "===== ANR ====="
grep -n -A40 "ANR in" logcat.txt | head -200
echo "===== NATIVE CRASH ====="
grep -n -A60 "\*\*\* \*\*\* \*\*\*" logcat.txt | head -300
echo "===== APP LOG (tail) ====="
grep -E "tmessages|$PKG|AndroidRuntime|ObjectBox|libv2ray|GoLog|NativeLoader|linker|dlopen|UnsatisfiedLink" logcat.txt | tail -200
echo "===== TOP ACTIVITY ====="
adb shell dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity" | head -5

if grep -q "FATAL EXCEPTION" logcat.txt; then
  echo "App crashed during startup."
  exit 1
fi
if adb shell pidof "$PKG" >/dev/null; then
  echo "App process is running."
  # Main-thread stack, to tell a hang from a slow start.
  adb shell kill -3 "$(adb shell pidof "$PKG")"
  sleep 3
  adb logcat -d -v threadtime | grep -A40 '"main" prio' | head -60
else
  echo "App process is NOT running."
  exit 1
fi
