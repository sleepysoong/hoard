#!/usr/bin/env python3
"""Deliberately break production behavior in the disposable emulator CI checkout.

No test-only switch is shipped in the APK. Compilation/infrastructure failures are
not accepted as evidence that an oracle detects its intended behavioral failure.
"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

if os.environ.get("GITHUB_ACTIONS") != "true":
    raise SystemExit("Oracle mutations are restricted to the disposable CI runner")

root = Path(__file__).resolve().parent.parent
out = (root / sys.argv[1] / "oracles").resolve()
out.mkdir(parents=True, exist_ok=True)
props = sys.argv[2:]
source = root / "app/src/main/kotlin/com/sleepysoong/hoard"
results = []


def unfitted_window(text):
    start = text.index("    suspend fun fitPreviewWindow(")
    end = text.index("    suspend fun perform(", start)
    return text[:start] + '''    suspend fun fitPreviewWindow(viewport: BrowserViewport): BrowserWindow {
        val p = page()
        val bounds = cdp.send("Browser.getWindowForTarget", buildJsonObject { put("targetId", p.targetId) })["bounds"] as JsonObject
        return BrowserWindow(bounds.num("left").toInt(), bounds.num("top").toInt(), bounds.num("width").toInt(), bounds.num("height").toInt())
    }

''' + text[end:]


def replace_once(text, old, new):
    if text.count(old) != 1:
        raise RuntimeError("Mutation no longer matches the production code uniquely")
    return text.replace(old, new, 1)


mutations = [
    ("unfitted-chrome", "browser/BrowserService.kt", unfitted_window,
     "NativeBrowserPreviewTest", "first navigation already matches the preview aspect"),
    ("wrong-touch-coordinate", "ui/chat/BrowserDesktopControls.kt", lambda text: replace_once(text,
        "return x.toInt().coerceIn(0, w - 1) to y.toInt().coerceIn(0, h - 1)",
        "return (w - 40).coerceAtLeast(0) to (h / 2)"),
     "NativeBrowserPreviewTest", "scaled touch + liquid input button edited the actual page field"),
    ("idle-continuation-stop", "work/ChatResponseWorker.kt", lambda text: replace_once(text,
        "        val decision = ContinuationEvaluator(goals).decide",
        "        if (mode == MODE_CONTINUE && reply?.thinking?.none { it.kind == com.sleepysoong.hoard.data.StepKind.Tool } == true) return\n"
        "        val decision = ContinuationEvaluator(goals).decide"),
     "NativeChatFlowTest#legacyIdleGoalRecoversAndContinuesToAnActualFileThenUserStopWins", "native goal did not settle"),
]


def run(command, log):
    with log.open("w") as stream:
        return subprocess.run(command, cwd=root, stdout=stream, stderr=subprocess.STDOUT, timeout=1200).returncode


for name, relative, mutate, test, expected in mutations:
    case = out / name
    case.mkdir(exist_ok=True)
    path = source / relative
    original = path.read_text()
    result = {"mutation": name, "test": test, "expected_failure": expected, "caught": False}
    try:
        path.write_text(mutate(original))
        compiled = run(["./gradlew", ":app:compileDebugAndroidTestKotlin", "--no-daemon"], case / "compile.log")
        result["compiled"] = compiled == 0
        if compiled != 0:
            raise RuntimeError(f"{name}: mutant did not compile; this is not oracle evidence")
        subprocess.run(["adb", "logcat", "-c"], check=True)
        status = run(["./gradlew", ":app:connectedDebugAndroidTest", "--no-daemon", *props,
                      f"-Pandroid.testInstrumentationRunnerArguments.class=com.sleepysoong.hoard.{test}"], case / "test.log")
        reports = root / "app/build/outputs/androidTest-results/connected/debug"
        if reports.exists():
            shutil.copytree(reports, case / "xml", dirs_exist_ok=True)
        with (case / "logcat.txt").open("w") as stream:
            subprocess.run(["adb", "logcat", "-d"], stdout=stream, check=True)
        failures = []
        tests = []
        for report in (case / "xml").rglob("*.xml"):
            tree = ET.parse(report)
            for node in tree.iter("testcase"):
                tests.append(node.attrib)
                for failure in list(node.findall("failure")) + list(node.findall("error")):
                    failures.append(failure.attrib.get("message", "") + "\n" + (failure.text or ""))
        result.update(exit_code=status, executed=tests, failures=failures,
                      caught=status != 0 and bool(tests) and any(expected in failure for failure in failures))
        if not result["caught"]:
            raise RuntimeError(f"{name}: expected behavioral failure was not detected (see {case})")
        print(f"Oracle detected {name}: {expected}", flush=True)
    finally:
        path.write_text(original)
        results.append(result)
        (out / "summary.json").write_text(json.dumps(results, ensure_ascii=False, indent=2))

if not all(result["caught"] for result in results):
    raise SystemExit("An oracle did not detect its mutation")
