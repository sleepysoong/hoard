#!/usr/bin/env bash
# Invoked inside the CI emulator runner. Preserve evidence even on a failed test.
set -euo pipefail
out=app/build/native-smoke
mkdir -p "$out"
collect() {
    adb logcat -d > "$out/logcat.txt" 2>&1 || true
    adb logcat -d -v raw HoardNativeArtifact:I '*:S' > "$out/transcripts.txt" 2>&1 || true
    adb logcat -d -v raw HoardNativeImage:I '*:S' > "$out/images.txt" 2>&1 || true
    python3 - "$out" <<'PY' || true
import sys, re, base64
from pathlib import Path
out = Path(sys.argv[1])
images = {}
for line in (out / 'images.txt').read_text(errors='replace').splitlines():
    m = re.fullmatch(r'IMAGE ([A-Za-z0-9_-]+) (\d+) (\d+) ([A-Za-z0-9+/=]+)', line)
    if m:
        name, index, count, data = m.groups()
        images.setdefault((name, int(count)), {})[int(index)] = data
for (name, count), chunks in images.items():
    if set(chunks) == set(range(count)):
        (out / (name + '.jpg')).write_bytes(base64.b64decode(''.join(chunks[i] for i in range(count)), validate=True))
PY
    if [[ -f "$out/browser-fixture/pids" ]]; then
        DISPLAY=:97 xprop -root _NET_ACTIVE_WINDOW > "$out/browser-fixture/active-window.txt" 2>&1 || true
        DISPLAY=:97 xwininfo -root -tree > "$out/browser-fixture/window-tree.txt" 2>&1 || true
        while read -r pid; do [[ "$pid" =~ ^[0-9]+$ ]] && kill "$pid" 2>/dev/null || true; done < "$out/browser-fixture/pids"
    fi
}
trap collect EXIT
bash scripts/start-browser-smoke.sh "$out"
adb logcat -G 8M
fixture="$out/browser-fixture"
host_key="$(awk '{print $1 " " $2}' "$fixture/host-key.pub")"
./gradlew :app:connectedDebugAndroidTest --no-daemon \
    -Pandroid.testInstrumentationRunnerArguments.browserHost=10.0.2.2 \
    -Pandroid.testInstrumentationRunnerArguments.browserPort=22022 \
    "-Pandroid.testInstrumentationRunnerArguments.browserUser=$(id -un)" \
    "-Pandroid.testInstrumentationRunnerArguments.browserKey=$(base64 -w0 "$fixture/client-key")" \
    "-Pandroid.testInstrumentationRunnerArguments.browserHostKey=$host_key" \
    -Pandroid.testInstrumentationRunnerArguments.browserVncPassword=nativepw
