#!/usr/bin/env bash
# Run Gradle without freezing the dev machine (3 cores / 6 GB, no swap — an Incus
# container, swap can't be enabled here):
#  - waits while less than MIN_FREE_MB is available (another agent's build running),
#  - lowest CPU + IO priority, one worker, the daemon stopped afterwards,
#  - Gradle and its test JVMs get a high oom_score_adj: if memory still runs out the
#    kernel kills the build, not the shell / tmux / agent session.
# Usage: scripts/gradlew-lowspec.sh :app:testDebugUnitTest [--tests '*Foo*'] ...
set -uo pipefail
cd "$(dirname "$0")/.."

MIN_FREE_MB=${MIN_FREE_MB:-1500}
waited=0
while :; do
    avail=$(awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo)
    [ "$avail" -ge "$MIN_FREE_MB" ] && break
    [ $waited -eq 0 ] && echo "gradlew-lowspec: only ${avail} MB free, waiting for ${MIN_FREE_MB} MB…" >&2
    waited=$((waited + 5))
    [ $waited -ge 900 ] && { echo "gradlew-lowspec: still low on memory after 15 min, running anyway" >&2; break; }
    sleep 5
done

# Children inherit oom_score_adj (1000 = killed first).
echo 1000 > /proc/self/oom_score_adj 2>/dev/null || true
nice -n 19 ionice -c 3 ./gradlew --max-workers=1 "$@"
status=$?
./gradlew --stop >/dev/null 2>&1
# Leftovers from runs before test JVMs used build/test-tmp (tmpfs /tmp = RAM).
rm -rf "${TMPDIR:-/tmp}"/robolectric-nativeruntime* 2>/dev/null
exit $status
