#!/usr/bin/env bash
# Keep evidence even when an instrumented assertion fails.
set -uo pipefail

./gradlew --no-daemon connectedDebugAndroidTest
test_status=$?

mkdir -p app/build/outputs/qa
adb pull /sdcard/Android/data/de.snappdf/files/qa/. app/build/outputs/qa/ || true
adb logcat -d -t 1500 > app/build/outputs/qa/emulator-logcat.txt || true

exit "$test_status"
