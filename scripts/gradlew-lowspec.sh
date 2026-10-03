#!/usr/bin/env bash
# Run Gradle without freezing the dev machine (3 cores / 6 GB, no swap — an Incus
# container, swap can't be enabled here):
#  - serializes launchers and refuses to build without memory headroom,
#  - lowest CPU + IO priority, one worker, the daemon stopped afterwards,
#  - Gradle and its test JVMs get a high oom_score_adj: if memory still runs out the
#    kernel kills the build, not the shell / tmux / agent session.
# Usage: scripts/gradlew-lowspec.sh :app:testDebugUnitTest [--tests '*Foo*'] ...
set -uo pipefail
cd "$(dirname "$0")/.."

MIN_FREE_MB=${MIN_FREE_MB:-3000}
MAX_WAIT_SECONDS=${MAX_WAIT_SECONDS:-900}
state_dir="${XDG_STATE_HOME:-$HOME/.local/state}/hoard"
mkdir -p "$state_dir"
exec 9>"$state_dir/gradle.lock"
flock -w "$MAX_WAIT_SECONDS" 9 || {
    echo "gradlew-lowspec: another build is running; not starting Gradle" >&2
    exit 75
}
waited=0
while :; do
    avail=$(awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo)
    [ "$avail" -ge "$MIN_FREE_MB" ] && break
    [ $waited -eq 0 ] && echo "gradlew-lowspec: only ${avail} MB free, waiting for ${MIN_FREE_MB} MB…" >&2
    waited=$((waited + 5))
    [ $waited -ge "$MAX_WAIT_SECONDS" ] && {
        echo "gradlew-lowspec: still low on memory; not starting Gradle (retry later or use CI)" >&2
        exit 75
    }
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
