"""The job summary counts every test case of Gradle's and swift test's JUnit XML (test_summary.py), names the failed
ones, and fails when there is no test case at all.

Run: python3 -m unittest discover -s .github/scripts/tests
"""
import contextlib
import importlib.util
import io
import os
import shutil
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "test_summary.py"
spec = importlib.util.spec_from_file_location("test_summary", SCRIPT)
test_summary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(test_summary)

GRADLE = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.example.ParserTest" tests="4" skipped="1" failures="1" errors="0" time="1.5">
  <testcase name="parsesEntries" classname="com.example.ParserTest" time="0.5"/>
  <testcase name="keepsOrder" classname="com.example.ParserTest" time="0.25"/>
  <testcase name="rejectsGarbage" classname="com.example.ParserTest" time="0.75">
    <failure message="expected">java.lang.AssertionError</failure>
  </testcase>
  <testcase name="later" classname="com.example.ParserTest" time="0"><skipped/></testcase>
</testsuite>
"""

SWIFT = """<?xml version="1.0" encoding="UTF-8"?>
<testsuites>
  <testsuite name="TestResults" errors="0" tests="2" failures="0" time="2.0">
    <testcase classname="IPTVCoreTests.M3UTests" name="testParse" time="1.0"/>
    <testcase classname="IPTVCoreTests.M3UTests" name="testCrash" time="1.0"><error message="crashed"/></testcase>
  </testsuite>
</testsuites>
"""

SWIFT_TESTING_EMPTY = """<?xml version="1.0" encoding="UTF-8"?>
<testsuites><testsuite name="TestResults" errors="0" tests="0" failures="0" skipped="0" time="0.0"/></testsuites>
"""


class TestSummaryTests(unittest.TestCase):
    def setUp(self):
        self.folder = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.folder)

    def write(self, name: str, text: str) -> Path:
        path = self.folder / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def run_main(self, *arguments: str) -> tuple[int, str, str]:
        summary = self.folder / "summary.md"
        output = io.StringIO()
        previous = os.environ.get("GITHUB_STEP_SUMMARY")
        os.environ["GITHUB_STEP_SUMMARY"] = str(summary)
        try:
            with contextlib.redirect_stdout(output):
                status = test_summary.main(list(arguments))
        finally:
            if previous is None:
                del os.environ["GITHUB_STEP_SUMMARY"]
            else:
                os.environ["GITHUB_STEP_SUMMARY"] = previous
        return status, output.getvalue(), summary.read_text(encoding="utf-8") if summary.exists() else ""

    def test_counts_passed_failed_and_skipped_cases_of_a_folder(self):
        self.write("test-results/test/TEST-com.example.ParserTest.xml", GRADLE)
        self.write("test-results/memoryTest/TEST-com.example.ParserTest.xml", GRADLE)
        self.write("test-results/test/binary/output.bin", "not xml")
        totals = test_summary.count(test_summary.xml_files([str(self.folder / "test-results")]))
        self.assertEqual((totals["passed"], totals["failed"], totals["skipped"]), (4, 2, 2))
        self.assertAlmostEqual(totals["time"], 3.0)
        self.assertEqual(totals["failures"], ["com.example.ParserTest.rejectsGarbage"] * 2)

    def test_an_error_is_a_failure_and_an_empty_sibling_file_is_fine(self):
        xunit = self.write("xunit.xml", SWIFT)
        empty = self.write("xunit-swift-testing.xml", SWIFT_TESTING_EMPTY)
        status, output, summary = self.run_main("IPTVCore tests", str(xunit), str(empty))
        self.assertEqual(status, 0)
        self.assertIn("| 1 | 1 | 0 | 2 s |", output)
        self.assertIn("`IPTVCoreTests.M3UTests.testCrash`", summary)

    def test_no_test_case_fails(self):
        empty = self.write("xunit.xml", SWIFT_TESTING_EMPTY)
        status, output, _ = self.run_main("IPTVCore tests", str(empty), str(self.folder / "missing"))
        self.assertEqual(status, 1)
        self.assertIn("::error::IPTVCore tests: no test case", output)

    def test_unreadable_xml_is_a_warning_not_a_crash(self):
        self.write("results/TEST-broken.xml", "<testsuite><testcase")
        self.write("results/TEST-ok.xml", GRADLE)
        status, output, _ = self.run_main("Kotlin", str(self.folder / "results"))
        self.assertEqual(status, 0)
        self.assertIn("::warning file=", output)


if __name__ == "__main__":
    unittest.main()
