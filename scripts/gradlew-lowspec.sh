#!/usr/bin/env bash
# Run Gradle without freezing the dev machine (3 cores / 6 GB):
# lowest CPU + IO priority, one worker, and the daemon stopped afterwards.
# Usage: scripts/gradlew-lowspec.sh :app:testDebugUnitTest [--tests '*Foo*'] ...
set -uo pipefail
cd "$(dirname "$0")/.."
nice -n 19 ionice -c 3 ./gradlew --max-workers=1 "$@"
status=$?
./gradlew --stop >/dev/null 2>&1
exit $status
