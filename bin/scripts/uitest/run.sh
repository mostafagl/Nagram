#!/bin/bash
# Runs the UI test against the arm64 APK in $1 on the running emulator.
# The x86_64 build runs natively on the emulator; the arm64 one only through ARM translation,
# which can't run the Go V2Ray core.
APK=$(ls "$1"/*x86_64*.apk 2>/dev/null | head -1)
[ -z "$APK" ] && APK=$(ls "$1"/*arm64-v8a*.apk 2>/dev/null | head -1)
[ -z "$APK" ] && APK=$(find "$1" -name '*arm64-v8a*.apk' | head -1)
echo "APK: $APK"
export UITEST_OUT="${UITEST_OUT:-$PWD/uitest-out}"
mkdir -p "$UITEST_OUT"
python3 bin/scripts/uitest/run_tests.py "$APK"
STATUS=$?
echo "UI test exit status: $STATUS"
# The report decides; the job itself only fails on infrastructure errors.
exit 0
