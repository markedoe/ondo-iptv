#!/usr/bin/env python3
"""Test counts for the job summary, from JUnit XML (Gradle's build/test-results, `swift test --xunit-output`).

    test_summary.py TITLE PATH...

Each PATH is a JUnit XML file or a folder searched for them. Prints a Markdown table (passed, failed, skipped, time)
and the failed tests, also into $GITHUB_STEP_SUMMARY when set. Fails when no test case is found at all: a suite that
no longer runs (not discovered, not compiled) must not pass for an empty green one.
"""
import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def xml_files(paths: list[str]) -> list[Path]:
    files = []
    for name in paths:
        path = Path(name)
        if path.is_dir():
            files += sorted(path.rglob("*.xml"))
        elif path.is_file():
            files.append(path)
    return files


def count(files: list[Path]) -> dict:
    totals = {"passed": 0, "failed": 0, "skipped": 0, "time": 0.0, "failures": []}
    for file in files:
        try:
            root = ET.parse(file).getroot()
        except ET.ParseError as error:
            print(f"::warning file={file}::not JUnit XML ({error})")
            continue
        if root.tag not in ("testsuite", "testsuites"):
            continue
        for case in root.iter("testcase"):
            try:
                totals["time"] += float(case.get("time") or 0)
            except ValueError:
                pass
            if case.find("failure") is not None or case.find("error") is not None:
                totals["failed"] += 1
                totals["failures"].append(f"{case.get('classname', '')}.{case.get('name', '')}".lstrip("."))
            elif case.find("skipped") is not None:
                totals["skipped"] += 1
            else:
                totals["passed"] += 1
    return totals


def markdown(title: str, totals: dict) -> str:
    lines = [f"### {title}", "", "| Passed | Failed | Skipped | Time |", "|---:|---:|---:|---:|",
             f"| {totals['passed']} | {totals['failed']} | {totals['skipped']} | {totals['time']:.0f} s |"]
    if totals["failures"]:
        lines += ["", "Failed:"] + [f"- `{name}`" for name in totals["failures"][:30]]
        if len(totals["failures"]) > 30:
            lines.append(f"- … and {len(totals['failures']) - 30} more")
    return "\n".join(lines) + "\n"


def main(arguments: list[str]) -> int:
    if len(arguments) < 2:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    title, paths = arguments[0], arguments[1:]
    totals = count(xml_files(paths))
    text = markdown(title, totals)
    print(text, end="")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as output:
            output.write(text + "\n")
    if totals["passed"] + totals["failed"] + totals["skipped"] == 0:
        print(f"::error::{title}: no test case in {' '.join(paths)}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
