#!/usr/bin/env python3
"""Every Android test class (app and core) takes HangGuard as its first rule.

HangGuard (android/*/src/test/.../HangGuard.kt) fails a test that runs over its time with every thread's stack in the
log, instead of the CI job waiting in silence until its own timeout. A test class without it can hang unnamed again.
A class with a @Test needs `@get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard(…)` in its body.

Usage: check_hang_guard.py [repository root]. Exit code 1 lists the classes without it.
"""
import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[2]
SOURCES = [ROOT / "android/app/src/test", ROOT / "android/core/src/test"]
CLASS = re.compile(r"(?m)^(?:internal |open )?class (\w+)")
GUARD = re.compile(r"@get:Rule\(order = Int\.MIN_VALUE\) val hangGuard = HangGuard\(")


def missing(path: Path) -> list[str]:
    text = path.read_text(encoding="utf-8")
    if "@Test" not in text:
        return []
    classes = list(CLASS.finditer(text))
    names = []
    for index, match in enumerate(classes):
        end = classes[index + 1].start() if index + 1 < len(classes) else len(text)
        body = text[match.start():end]
        if re.search(r"@Test\b", body) and not GUARD.search(body):
            names.append(match.group(1))
    return names


def main() -> int:
    problems = [
        f"{path.relative_to(ROOT)}: {name}"
        for source in SOURCES
        for path in sorted(source.rglob("*.kt"))
        for name in missing(path)
    ]
    if problems:
        print("Test classes without HangGuard as their first rule:")
        print("\n".join(f"  {problem}" for problem in problems))
        return 1
    print("Every Android test class has HangGuard.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
