#!/usr/bin/env python3
"""Exercise the build launcher without starting Java or stressing the dev server.

Failure paths: insufficient headroom, timeout bypassing the gate, overlapping
launchers, losing the Gradle exit code, and losing inherited OOM priority.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class LowSpecLauncherTest(unittest.TestCase):
    def setUp(self):
        temp_root = Path(__file__).resolve().parents[1] / "app/build/test-tmp"
        temp_root.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix="hoard-lowspec-", dir=temp_root)
        self.root = Path(self.temp.name)
        (self.root / "scripts").mkdir()
        shutil.copy2(Path(__file__).with_name("gradlew-lowspec.sh"), self.root / "scripts/gradlew-lowspec.sh")
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.env = dict(os.environ, PATH=str(self.bin) + ":" + os.environ["PATH"],
                        XDG_STATE_HOME=str(self.root / "state"), HOME=str(self.root), TMPDIR=str(self.root),
                        MIN_FREE_MB="3000", MAX_WAIT_SECONDS="1", FREE_MB="4096", GRADLE_RESULT="0")
        self.executable("bin/awk", '#!/bin/sh\nprintf "%s\\n" "$FREE_MB"\n')
        self.executable("bin/sleep", '#!/bin/sh\nexit 0\n')
        self.executable("gradlew", '''#!/bin/bash
if [[ "$*" == "--stop" ]]; then echo stopped >> calls; exit 0; fi
echo "build:$* oom=$(cat /proc/self/oom_score_adj)" >> calls
if [[ "${HOLD_BUILD:-}" == 1 ]]; then
    : > started
    read -r _ < release
fi
exit "$GRADLE_RESULT"
''')

    def tearDown(self):
        self.temp.cleanup()

    def executable(self, name, contents):
        file = self.root / name
        file.write_text(contents)
        file.chmod(0o755)

    def launch(self, **changes):
        return subprocess.run(["bash", "scripts/gradlew-lowspec.sh", ":app:fakeTask"],
                              cwd=self.root, env=dict(self.env, **changes), text=True,
                              capture_output=True, timeout=10)

    def calls(self):
        file = self.root / "calls"
        return file.read_text() if file.exists() else ""

    def test_timeout_refuses_to_start_java_instead_of_running_anyway(self):
        result = self.launch(FREE_MB="1024")
        self.assertEqual(75, result.returncode, result.stderr)
        self.assertEqual("", self.calls())
        self.assertIn("not starting Gradle", result.stderr)

    def test_success_and_failure_preserve_exit_code_and_cleanup(self):
        for code in (0, 42):
            result = self.launch(GRADLE_RESULT=str(code))
            self.assertEqual(code, result.returncode, result.stderr)
        calls = self.calls().splitlines()
        self.assertEqual(4, len(calls), calls)
        self.assertTrue(all("--max-workers=1" in c and "oom=1000" in c for c in calls[::2]), calls)
        self.assertEqual(["stopped", "stopped"], calls[1::2])

    def test_second_launcher_cannot_overlap_the_first(self):
        os.mkfifo(self.root / "release")
        os.mkfifo(self.root / "started")
        self.executable("gradlew", (self.root / "gradlew").read_text().replace(": > started", "echo ready > started"))
        first = subprocess.Popen(["bash", "scripts/gradlew-lowspec.sh", ":app:fakeTask"],
                                 cwd=self.root, env=dict(self.env, HOLD_BUILD="1"),
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            with (self.root / "started").open() as signal:
                self.assertEqual("ready", signal.readline().strip())
            second = self.launch()
            self.assertEqual(75, second.returncode, second.stderr)
            self.assertEqual(1, self.calls().count("build:"), self.calls())
        finally:
            with (self.root / "release").open("w") as release:
                release.write("finish\n")
            _, error = first.communicate(timeout=5)
            self.assertEqual(0, first.returncode, error)


if __name__ == "__main__":
    unittest.main(verbosity=2)
