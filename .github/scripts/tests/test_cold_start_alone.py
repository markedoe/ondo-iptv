"""The cold start's timing (ColdStartTimingTest) runs alone, after the other Android tests (docs/ANDROID.md).

android.yml asks Gradle for the unit tests and :app:coldStartTest in one invocation; with the configuration cache Gradle
runs a project's tasks side by side, and the unit tests' Robolectric forks then shared the runner's cores with the timed
cold start: its carousel missed 2.5 s at night on unchanged code. Only an ordering in the build keeps them apart.

Run: python3 -m unittest discover -s .github/scripts/tests
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
BUILD = ROOT / "android" / "app" / "build.gradle.kts"
PROPERTIES = ROOT / "android" / "gradle.properties"
WORKFLOW = ROOT / ".github" / "workflows" / "android.yml"


def cold_start_block(text: str) -> str:
    """The body of `val coldStartTest by tasks.registering(Test::class) { ... }`, braces balanced."""
    start = text.index("val coldStartTest by tasks.registering(Test::class)")
    opening = text.index("{", start)
    depth = 0
    for index in range(opening, len(text)):
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1
            if depth == 0:
                return text[opening + 1:index]
    raise AssertionError("coldStartTest's block never closes")


class ColdStartAloneTest(unittest.TestCase):
    def test_workflow_runs_it_with_the_unit_tests(self):
        # The case the ordering is for: one Gradle invocation with both.
        run = WORKFLOW.read_text(encoding="utf-8")
        self.assertRegex(run, r"\./gradlew [^\n]*RoborazziDebug[^\n]*:app:coldStartTest")

    def test_gradle_may_run_a_projects_tasks_side_by_side(self):
        properties = PROPERTIES.read_text(encoding="utf-8")
        self.assertIn("org.gradle.parallel=true", properties)
        self.assertIn("org.gradle.configuration-cache=true", properties)

    def test_it_runs_after_every_other_test_task(self):
        block = cold_start_block(BUILD.read_text(encoding="utf-8"))
        self.assertRegex(
            block,
            re.compile(r'mustRunAfter\(\s*tasks\.withType<Test>\(\)\.matching\s*\{\s*it\.name\s*!=\s*"coldStartTest"\s*\}\s*\)'),
            "coldStartTest must run after the other Test tasks (testDebugUnitTest, the screenshots), never beside them",
        )


if __name__ == "__main__":
    unittest.main()
