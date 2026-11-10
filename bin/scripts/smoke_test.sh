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

adb install -r "$APK" || exit 1
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
