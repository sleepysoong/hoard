#!/usr/bin/env bash
# Invoked inside the CI emulator runner. Preserve evidence even on a failed test.
set -euo pipefail
out=app/build/native-smoke
mkdir -p "$out"
collect() {
    adb logcat -d > "$out/logcat.txt" 2>&1 || true
    adb pull /sdcard/Android/data/com.sleepysoong.hoard/files/test-artifacts "$out/" || true
}
trap collect EXIT
./gradlew :app:connectedDebugAndroidTest --no-daemon
