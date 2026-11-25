"""The Robolectric tests never download Android at run time (docs/ANDROID.md).

Robolectric fetches its android-all jar from Maven Central when a test starts, with a plain HTTP request outside
Gradle: no retry, no Gradle cache. On shared CI runners Maven Central answered 429 and the whole run failed before
any test. Each module that tests with Robolectric therefore declares the jars in a `robolectricRuntime` configuration
(Gradle downloads them with its retries and keeps them in its cache) and runs every Test task with
`robolectric.offline=true` and `robolectric.dependency.dir` pointing at their copy.

The SDK levels come from the tests themselves (robolectric.properties, `@Config(sdk = ...)`): a test pinned to a new
level fails here until its jar is declared.

Run: python3 -m unittest discover -s .github/scripts/tests
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
ANDROID = ROOT / "android"
CATALOG = ANDROID / "gradle" / "libs.versions.toml"

# Robolectric's SDK level -> android-all-instrumented version, from DefaultSdkProvider of each Robolectric release
# (robolectric/src/main/java/org/robolectric/plugins/DefaultSdkProvider.java: "<android>-robolectric-<build>-i" +
# PREINSTRUMENTED_VERSION, 7 in 4.17). A new Robolectric release changes them: add its table here when the catalog
# moves to it.
ANDROID_ALL = {
    "4.17": {
        23: "6.0.1_r3-robolectric-r1-i7",
        24: "7.0.0_r1-robolectric-r1-i7",
        25: "7.1.0_r7-robolectric-r1-i7",
        26: "8.0.0_r4-robolectric-r1-i7",
        27: "8.1.0-robolectric-4611349-i7",
        28: "9-robolectric-4913185-2-i7",
        29: "10-robolectric-5803371-i7",
        30: "11-robolectric-6757853-i7",
        31: "12-robolectric-7732740-i7",
        32: "12.1-robolectric-8229987-i7",
        33: "13-robolectric-9030017-i7",
        34: "14-robolectric-10818077-i7",
        35: "15-robolectric-13954326-i7",
        36: "16-robolectric-13921718-i7",
        37: "17-robolectric-15733970-i7",
    },
}



def robolectric_version() -> str:
    match = re.search(r'^robolectric\s*=\s*"([^"]+)"', CATALOG.read_text(encoding="utf-8"), re.MULTILINE)
    assert match, "no robolectric version in libs.versions.toml"
    return match.group(1)


def catalog_version(name: str) -> int:
    match = re.search(rf'^{name}\s*=\s*"(\d+)"', CATALOG.read_text(encoding="utf-8"), re.MULTILINE)
    assert match, f"no {name} in libs.versions.toml"
    return int(match.group(1))


def robolectric_modules() -> list:
    """Every Gradle module whose build file tests with Robolectric."""
    return sorted(
        build.parent
        for build in ANDROID.rglob("build.gradle.kts")
        if "/build/" not in build.as_posix() and re.search(r"libs\.robolectric\b|org\.robolectric:robolectric",
                                                          build.read_text(encoding="utf-8"))
    )


def _sdk_values(expression: str, where: str) -> set:
    """The SDK levels of `sdk = ...` in a @Config: integer literals only, anything else fails."""
    expression = expression.strip()
    inner = re.fullmatch(r"(?:\[|intArrayOf\(|\{)(.*)(?:\]|\)|\})", expression, re.DOTALL)
    items = inner.group(1).split(",") if inner else [expression]
    levels = set()
    for item in (item.strip() for item in items):
        if not item:
            continue
        if not re.fullmatch(r"\d+", item):
            raise AssertionError(f"{where}: @Config sdk value {item!r} is not an integer literal; "
                                 "write the level as a number so this check can read it")
        levels.add(int(item))
    return levels


def config_sdks(source: str, where: str = "<source>") -> set:
    """SDK levels pinned by `@Config(...)` annotations in a Kotlin or Java source (spanning lines included)."""
    levels = set()
    for match in re.finditer(r"@Config\s*\(", source):
        depth, index = 1, match.end()
        while depth and index < len(source):
            depth += {"(": 1, ")": -1}.get(source[index], 0)
            index += 1
        arguments = source[match.end():index - 1]
        if re.search(r"\b(minSdk|maxSdk)\s*=", arguments):
            raise AssertionError(f"{where}: @Config minSdk/maxSdk is not supported by this check; pin sdk = N")
        sdk = re.search(r"\bsdk\s*=\s*(\[[^\]]*\]|intArrayOf\([^)]*\)|\{[^}]*\}|[^,)\s]+)", arguments)
        if sdk:
            levels |= _sdk_values(sdk.group(1), where)
    return levels


def properties_sdks(text: str):
    """The `sdk=` levels of a robolectric.properties, or None when it sets none."""
    match = re.search(r"^\s*sdk\s*=\s*(.+)$", text, re.MULTILINE)
    if not match:
        return None
    return _sdk_values(match.group(1).replace(" ", ""), "robolectric.properties")


def module_sdks(module: Path) -> set:
    levels = set()
    default = None
    for properties in module.glob("src/test*/resources/robolectric.properties"):
        found = properties_sdks(properties.read_text(encoding="utf-8"))
        if found:
            default = (default or set()) | found
    # Without sdk in robolectric.properties, Robolectric runs on the app's targetSdk.
    levels |= default if default else {catalog_version("targetSdk")}
    for source in list(module.glob("src/test*/**/*.kt")) + list(module.glob("src/test*/**/*.java")):
        levels |= config_sdks(source.read_text(encoding="utf-8"), str(source.relative_to(ROOT)))
    return levels


def declared_versions(build_text: str) -> set:
    return set(re.findall(r'robolectricRuntime\(\s*"org\.robolectric:android-all-instrumented:([^"]+)"\s*\)',
                          build_text))


def tasks_configuration(build_text: str) -> str:
    """The body of `tasks.withType<Test>().configureEach { ... }`, braces balanced."""
    start = build_text.index("tasks.withType<Test>().configureEach")
    opening = build_text.index("{", start)
    depth = 0
    for index in range(opening, len(build_text)):
        if build_text[index] == "{":
            depth += 1
        elif build_text[index] == "}":
            depth -= 1
            if depth == 0:
                return build_text[opening + 1:index]
    raise AssertionError("tasks.withType<Test>() block never closes")


class ParserTest(unittest.TestCase):
    """The reading of the test sources: a new level must be seen, an unreadable one must fail."""

    def test_reads_single_and_list_levels(self):
        self.assertEqual(config_sdks('@Config(sdk = [34])\nclass A'), {34})
        self.assertEqual(config_sdks('@Config(qualifiers = "w960dp", sdk = [33, 36])'), {33, 36})
        self.assertEqual(config_sdks("@Config(\n    sdk = intArrayOf(31),\n    qualifiers = TV,\n)"), {31})
        self.assertEqual(config_sdks("@Config(sdk = 28)"), {28})
        self.assertEqual(config_sdks('@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")'), set())

    def test_fails_on_what_it_cannot_read(self):
        with self.assertRaises(AssertionError):
            config_sdks("@Config(sdk = [Build.VERSION_CODES.TIRAMISU])")
        with self.assertRaises(AssertionError):
            config_sdks("@Config(minSdk = 30)")

    def test_properties(self):
        self.assertEqual(properties_sdks("sdk=35\napplication=android.app.Application\n"), {35})
        self.assertIsNone(properties_sdks("application=android.app.Application\n"))

    def test_a_new_level_needs_its_jar(self):
        needed = {ANDROID_ALL["4.17"][level] for level in {35, 34}}
        self.assertFalse(needed <= {"15-robolectric-13954326-i7"})


class RobolectricOfflineTest(unittest.TestCase):
    def test_robolectric_version_has_a_table(self):
        self.assertIn(robolectric_version(), ANDROID_ALL,
                      "Robolectric moved: add its SDK -> android-all-instrumented table (DefaultSdkProvider) here "
                      "and declare the matching jars in robolectricRuntime")

    def test_some_module_uses_robolectric(self):
        self.assertTrue(robolectric_modules(), "no module found testing with Robolectric")

    def test_every_sdk_the_tests_run_on_is_declared(self):
        table = ANDROID_ALL[robolectric_version()]
        for module in robolectric_modules():
            build = (module / "build.gradle.kts").read_text(encoding="utf-8")
            declared = declared_versions(build)
            for level in sorted(module_sdks(module)):
                self.assertIn(level, table, f"{module.name}: Robolectric {robolectric_version()} has no SDK {level}")
                self.assertIn(table[level], declared,
                              f"{module.name}: tests run on SDK {level}; declare "
                              f'robolectricRuntime("org.robolectric:android-all-instrumented:{table[level]}")')

    def test_every_test_task_runs_offline(self):
        for module in robolectric_modules():
            build = (module / "build.gradle.kts").read_text(encoding="utf-8")
            self.assertTrue(re.search(r"val robolectricRuntime by configurations\.creating", build),
                            f"{module.name}: no robolectricRuntime configuration")
            # tasks.withType<Test>() reaches every Test task: testDebugUnitTest, testReleaseUnitTest, coldStartTest.
            block = tasks_configuration(build)
            self.assertRegex(block, r'systemProperty\(\s*"robolectric\.offline"\s*,\s*"true"\s*\)')
            dir_match = re.search(r'systemProperty\(\s*"robolectric\.dependency\.dir"\s*,\s*"([^"]+)"\s*\)', block)
            self.assertTrue(dir_match, f"{module.name}: robolectric.dependency.dir is not set on the Test tasks")
            # The jars are copied there, and the copy is an input of each Test task (so it runs before them).
            copy = re.search(r"val (\w+) by tasks\.registering\(Sync::class\)\s*\{(?P<body>[^}]*)\}", build)
            self.assertTrue(copy, f"{module.name}: no Sync task copying robolectricRuntime")
            self.assertIn("from(robolectricRuntime)", copy.group("body"))
            into = re.search(r'into\(\s*layout\.buildDirectory\.dir\(\s*"([^"]+)"\s*\)\s*\)', copy.group("body"))
            self.assertTrue(into, f"{module.name}: the Sync task copies the jars outside the build directory")
            self.assertEqual(dir_match.group(1), f"build/{into.group(1)}",
                             "the Sync task's destination and robolectric.dependency.dir differ")
            self.assertRegex(block, rf"inputs\.files\(\s*{copy.group(1)}\s*\)")

    def test_cold_start_is_a_test_task(self):
        # coldStartTest gets the offline settings through tasks.withType<Test>().
        build = (ANDROID / "app" / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn("val coldStartTest by tasks.registering(Test::class)", build)


if __name__ == "__main__":
    unittest.main()
