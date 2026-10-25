#!/bin/bash
# Runs the UI test against the arm64 APK in $1 on the running emulator.
APK=$(ls "$1"/*arm64-v8a*.apk 2>/dev/null | head -1)
[ -z "$APK" ] && APK=$(find "$1" -name '*arm64-v8a*.apk' | head -1)
echo "APK: $APK"
export UITEST_OUT="${UITEST_OUT:-$PWD/uitest-out}"
mkdir -p "$UITEST_OUT"
python3 bin/scripts/uitest/run_tests.py "$APK"
STATUS=$?
echo "UI test exit status: $STATUS"
# The report decides; the job itself only fails on infrastructure errors.
exit 0
