#!/usr/bin/env python3
"""Exercise the actual SkillRuntime patterns with ICU, not the host JVM's regex.

Android java.util.regex uses ICU. Robolectric uses OpenJDK, which accepts bare
closing braces/brackets that ICU rejects. Failures covered here: invalid static
patterns, split braced/indexed placeholders, and lost dynamic-command captures.
Requires the system libicui18n (Ubuntu: libicu-dev). No app code is simulated.
"""

import ast
import ctypes
import ctypes.util
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / "app/build/test-artifacts/skill-native-regex.txt"


class Icu:
    def __init__(self):
        name = ctypes.util.find_library("icui18n")
        if not name:
            raise RuntimeError("libicui18n is required; install libicu-dev")
        self.lib = ctypes.CDLL(name)
        suffix = name.rsplit(".", 1)[-1]

        def function(name, args, result):
            fn = getattr(self.lib, name + "_" + suffix)
            fn.argtypes, fn.restype = args, result
            return fn

        ptr = ctypes.c_void_p
        integer = ctypes.c_int32
        status = ctypes.POINTER(integer)
        chars = ctypes.POINTER(ctypes.c_uint16)
        self.open = function("uregex_open", [chars, integer, ctypes.c_uint32, ptr, status], ptr)
        self.close = function("uregex_close", [ptr], None)
        self.set_text = function("uregex_setText", [ptr, chars, integer, status], None)
        self.find_next = function("uregex_findNext", [ptr, status], ctypes.c_int8)
        self.start = function("uregex_start", [ptr, integer, status], integer)
        self.end = function("uregex_end", [ptr, integer, status], integer)
        self.error_name = function("u_errorName", [integer], ctypes.c_char_p)
        self.name = name

    @staticmethod
    def utf16(text):
        data = text.encode("utf-16-le")
        return (ctypes.c_uint16 * (len(data) // 2)).from_buffer_copy(data)

    def check(self, status):
        if status.value > 0:
            raise AssertionError(self.error_name(status.value).decode())

    def matches(self, pattern, text, group=0):
        status = ctypes.c_int32(0)
        p = self.utf16(pattern)
        regex = self.open(p, len(p), 0, None, ctypes.byref(status))
        self.check(status)
        try:
            t = self.utf16(text)
            self.set_text(regex, t, len(t), ctypes.byref(status))
            self.check(status)
            data = text.encode("utf-16-le")
            result = []
            while self.find_next(regex, ctypes.byref(status)):
                a = self.start(regex, group, ctypes.byref(status))
                b = self.end(regex, group, ctypes.byref(status))
                self.check(status)
                result.append(None if a < 0 else data[a * 2:b * 2].decode("utf-16-le"))
            self.check(status)
            return result
        finally:
            self.close(regex)


def main():
    notes = []
    try:
        icu = Icu()
        notes.append(f"engine: {icu.name}")
        source = (ROOT / "app/src/main/kotlin/com/sleepysoong/hoard/skills/SkillRuntime.kt").read_text()
        patterns = {
            name: ast.literal_eval('"' + value.replace("${'$'}", "$") + '"')
            for name, value in re.findall(r'private val (DYNAMIC|PLACEHOLDER) = Regex\("((?:\\.|[^"\\])*)"\)', source)
        }
        assert patterns.keys() == {"DYNAMIC", "PLACEHOLDER"}, "skill patterns were not found"
        for name, pattern in patterns.items():
            notes.append(f"{name}: {pattern}")
            icu.matches(pattern, "")  # Class initialization must succeed with zero installed skills.

        tokens = ["${CLAUDE_SESSION_ID}", "${CLAUDE_SKILL_DIR}", "${CLAUDE_PROJECT_DIR}",
                  "${CLAUDE_PLUGIN_ROOT}", "${CLAUDE_PLUGIN_DATA}", "${CLAUDE_EFFORT}",
                  "$ARGUMENTS[12]", "$ARGUMENTS", "$0", "$task", "$CLAUDE_SESSION_ID"]
        actual = icu.matches(patterns["PLACEHOLDER"], "한글: " + " / ".join(tokens))
        assert actual == tokens, f"placeholder tokens: {actual!r}"
        notes.append(f"placeholder matches: {actual!r}")

        inline = "current: !`printf hello` and !`pwd`"
        assert icu.matches(patterns["DYNAMIC"], inline, 2) == ["printf hello", "pwd"]
        assert icu.matches(patterns["DYNAMIC"], "literal!`do_not_run`") == []
        fenced = "```!\nfirst\nsecond\n```\n"
        assert icu.matches(patterns["DYNAMIC"], fenced, 1) == ["first\nsecond\n"]
        notes.append("dynamic inline/fenced captures and literal boundary: PASS")
        notes.append("PASS")
    except BaseException as error:
        notes.append(f"FAIL: {type(error).__name__}: {error}")
        raise
    finally:
        OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        OUTPUT.write_text("\n".join(notes) + "\n")
        print("\n".join(notes))
        print(f"artifact: {OUTPUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
