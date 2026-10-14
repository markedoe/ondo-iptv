"""The CI configuration checks must reject what they exist to catch, and accept the repository.

Each test builds a small project in a temporary folder, with or without the defect, and runs the check on
it as CI does. Run: python3 -m unittest discover -s .github/scripts/tests
"""
import json
import plistlib
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]
REPOSITORY = SCRIPTS.parents[1]


def run(check: str, root: Path) -> tuple[int, str]:
    result = subprocess.run([sys.executable, str(SCRIPTS / f"check_{check}.py"), str(root)], capture_output=True, text=True)
    return result.returncode, result.stdout + result.stderr


class CheckTestCase(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root)

    def write(self, relative: str, content) -> None:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(content, bytes):
            path.write_bytes(content)
        else:
            path.write_text(content, encoding="utf-8")

    def assertPasses(self, check: str):
        code, output = run(check, self.root)
        self.assertEqual(code, 0, output)

    def assertFails(self, check: str, *messages: str):
        code, output = run(check, self.root)
        self.assertEqual(code, 1, output)
        for message in messages:
            self.assertIn(message, output)


class RepositoryTests(unittest.TestCase):
    def test_the_repository_passes_every_check(self):
        for check in ("translations", "privacy_manifest", "mac_entitlements", "neutrality"):
            code, output = run(check, REPOSITORY)
            self.assertEqual(code, 0, f"{check}: {output}")


LANGUAGES = ("fr", "es", "pt", "zh-Hans", "ar", "hi")


def french(value: str) -> dict:
    """A text translated into every language of the app (the French value stands for all of them)."""
    return {"localizations": {code: {"stringUnit": {"state": "translated", "value": value}} for code in LANGUAGES}}


class TranslationsTests(CheckTestCase):
    def catalogue(self, strings: dict) -> None:
        self.write("App/Resources/Localizable.xcstrings", json.dumps({"sourceLanguage": "en", "strings": strings, "version": "1.0"}))

    def test_translated_texts_pass(self):
        self.write("App/Home.swift", 'Shelf(title: "New Movies", metrics: metrics)\n'
                                     'Text("Ondo")\nTextField("Server", text: $server, prompt: Text("http://example.com:8080"))\n'
                                     'Text("\\(count) items")\n')
        self.catalogue({"New Movies": french("Nouveaux films"), "Server": french("Serveur")})
        self.assertPasses("translations")

    def test_a_key_without_french_fails(self):
        self.catalogue({"New Movies": {"localizations": {}}})
        self.assertFails("translations", "no French translation", "New Movies")

    def test_a_french_text_still_to_review_fails(self):
        self.catalogue({"New Movies": {"localizations": {"fr": {"stringUnit": {"state": "needs_review", "value": "Nouveaux"}}}}})
        self.assertFails("translations", "New Movies")

    def test_every_plural_form_must_be_translated(self):
        plural = {"one": {"stringUnit": {"state": "translated", "value": "%lld élément"}}, "other": {"stringUnit": {"state": "new", "value": ""}}}
        self.catalogue({"%lld items": {"localizations": {"fr": {"variations": {"plural": plural}}}}})
        self.assertFails("translations", "%lld items")

    def test_a_key_missing_one_language_fails(self):
        entry = french("Nouveaux films")
        del entry["localizations"]["hi"]
        self.catalogue({"New Movies": entry})
        self.assertFails("translations", "no Hindi translation", "New Movies")

    def test_a_permission_prompt_missing_one_language_fails(self):
        self.catalogue({"New Movies": french("Nouveaux films")})
        prompt = french("Ondo recherche les appareils de votre réseau.")
        del prompt["localizations"]["ar"]
        self.write("App/Resources/InfoPlist.xcstrings",
                   json.dumps({"sourceLanguage": "en", "strings": {"NSLocalNetworkUsageDescription": prompt}, "version": "1.0"}))
        self.assertFails("translations", "no Arabic translation in InfoPlist.xcstrings", "NSLocalNetworkUsageDescription")

    def test_stale_keys_are_ignored(self):
        self.catalogue({"Old Row": {"extractionState": "stale", "localizations": {}}})
        self.assertPasses("translations")

    def test_a_text_missing_from_the_catalogue_fails_with_its_place(self):
        self.catalogue({"New Movies": french("Nouveaux films")})
        self.write("App/Home.swift", 'let a = 1\nShelf(title: "Brand New Row", metrics: metrics)\n')
        self.assertFails("translations", "App/Home.swift:2", "Brand New Row")


class PrivacyManifestTests(CheckTestCase):
    def manifest(self, *categories: str, reasons=("C617.1",)) -> None:
        entries = [{"NSPrivacyAccessedAPIType": category, "NSPrivacyAccessedAPITypeReasons": list(reasons)} for category in categories]
        self.write("App/Resources/PrivacyInfo.xcprivacy", plistlib.dumps({"NSPrivacyTracking": False, "NSPrivacyAccessedAPITypes": entries}))
        (self.root / "Packages/IPTVCore/Sources").mkdir(parents=True, exist_ok=True)

    def test_an_undeclared_api_fails_with_the_file(self):
        self.manifest()
        self.write("App/Model/Preferences.swift", 'let tab = UserDefaults.standard.string(forKey: "tab")\n')
        self.assertFails("privacy_manifest", "NSPrivacyAccessedAPICategoryUserDefaults", "App/Model/Preferences.swift")

    def test_a_declared_api_passes(self):
        self.manifest("NSPrivacyAccessedAPICategoryUserDefaults")
        self.write("App/Model/Preferences.swift", "@AppStorage(\"tab\") var tab = 0\n")
        self.assertPasses("privacy_manifest")

    def test_core_package_code_is_checked_too(self):
        self.manifest()
        self.write("Packages/IPTVCore/Sources/IPTVCore/Disk.swift", "let free = values.volumeAvailableCapacityForImportantUsage\n")
        self.assertFails("privacy_manifest", "NSPrivacyAccessedAPICategoryDiskSpace")

    def test_debug_only_code_is_ignored(self):
        self.manifest()
        self.write("App/Model/Screenshots.swift", "#if DEBUG\n#if os(macOS)\nlet a = 1\n#endif\nlet r = UserDefaults.standard\n#endif\n")
        self.assertPasses("privacy_manifest")

    def test_the_else_branch_of_a_debug_block_ships(self):
        self.manifest()
        self.write("App/Model/Clock.swift", "#if DEBUG\nlet up = 0.0\n#else\nlet up = ProcessInfo.processInfo.systemUptime\n#endif\n")
        self.assertFails("privacy_manifest", "NSPrivacyAccessedAPICategorySystemBootTime")

    def test_c_code_of_the_core_package_is_checked_too(self):
        self.manifest()
        self.write("Packages/IPTVCore/Sources/CZlib/czlib.c", "struct stat info;\nint ok = lstat(path, &info) == 0;\n")
        self.assertFails("privacy_manifest", "NSPrivacyAccessedAPICategoryFileTimestamp", "Packages/IPTVCore/Sources/CZlib/czlib.c")

    def test_c_debug_only_code_is_ignored_but_not_its_opposite(self):
        self.manifest()
        self.write("Packages/IPTVCore/Sources/CZlib/debug.c", "#ifdef DEBUG\nint ok = stat(path, &info);\n#endif\n")
        self.assertPasses("privacy_manifest")
        self.write("Packages/IPTVCore/Sources/CZlib/release.c", "#ifndef DEBUG\nint ok = fstatfs(fd, &info);\n#endif\n")
        self.assertFails("privacy_manifest", "NSPrivacyAccessedAPICategoryDiskSpace", "release.c")

    def test_a_category_needs_a_reason(self):
        self.manifest("NSPrivacyAccessedAPICategoryFileTimestamp", reasons=())
        self.assertFails("privacy_manifest", "declared without a reason")


class MacEntitlementsTests(CheckTestCase):
    RELEASE = "App/Resources/Ondo-macOS.entitlements"
    COMPLETE = {
        "com.apple.security.app-sandbox": True,
        "com.apple.security.network.client": True,
        "com.apple.security.files.user-selected.read-write": True,
    }

    def project(self, entitlements: dict, referenced: bool = True) -> None:
        line = f'"CODE_SIGN_ENTITLEMENTS[sdk=macosx*]": {self.RELEASE}' if referenced else ""
        self.write("project.yml", f"targets:\n  Ondo:\n    settings:\n      base:\n        {line}\n")
        self.write(self.RELEASE, plistlib.dumps(entitlements))

    def test_the_sandbox_entitlements_pass(self):
        self.project(self.COMPLETE)
        self.assertPasses("mac_entitlements")

    def test_without_network_nothing_loads(self):
        self.project({key: value for key, value in self.COMPLETE.items() if key != "com.apple.security.network.client"})
        self.assertFails("mac_entitlements", "com.apple.security.network.client")

    def test_without_user_selected_files_nothing_imports(self):
        self.project({key: value for key, value in self.COMPLETE.items() if key != "com.apple.security.files.user-selected.read-write"})
        self.assertFails("mac_entitlements", "com.apple.security.files.user-selected.read-write")

    def test_read_only_file_access_is_not_enough(self):
        self.project({key: value for key, value in self.COMPLETE.items() if key != "com.apple.security.files.user-selected.read-write"}
                     | {"com.apple.security.files.user-selected.read-only": True})
        self.assertFails("mac_entitlements", "com.apple.security.files.user-selected.read-write")

    def test_an_entitlement_no_feature_needs_fails(self):
        self.project(self.COMPLETE | {"com.apple.security.device.camera": True})
        self.assertFails("mac_entitlements", "com.apple.security.device.camera, which no feature needs")

    def test_a_debug_entitlement_must_not_ship(self):
        self.project(self.COMPLETE | {"com.apple.security.cs.disable-library-validation": True})
        self.assertFails("mac_entitlements", "disable-library-validation")

    def test_the_release_build_must_be_signed_with_that_file(self):
        self.project(self.COMPLETE, referenced=False)
        self.assertFails("mac_entitlements", "project.yml does not sign")


class NeutralityTests(CheckTestCase):
    def test_fictional_names_pass(self):
        self.write("Tests/Search.swift", 'let channel = "FR: TV1 HD" // being watched\nlet header = "#KODIPROP:inputstream"\n')
        self.write("README.md", "Ondo est un lecteur : il ne fournit aucun contenu.\n")
        self.assertPasses("neutrality")

    def test_another_iptv_app_fails(self):
        self.write("App/Catchup.swift", "// Same rules as TiviMate\n")
        self.assertFails("neutrality", "App/Catchup.swift:1", "another IPTV app")

    def test_free_iptv_fails_in_both_languages(self):
        self.write("fastlane/metadata/en-US/description.txt", "The best free IPTV player.\n")
        self.write("fastlane/metadata/fr-FR/description.txt", "Un lecteur IPTV gratuit.\n")
        self.assertFails("neutrality", "en-US/description.txt:1", "fr-FR/description.txt:1", "calls IPTV free")

    def test_free_iptv_fails_in_every_language_of_the_apps(self):
        texts = {
            "es": "El mejor reproductor de IPTV gratis.",
            "es-gratuito": "Un reproductor IPTV gratuito.",
            "pt": "Assista IPTV grátis no seu celular.",
            "zh": "最佳免费IPTV播放器",
            "zh-de": "免费的 IPTV 播放器",
            "ar": "أفضل مشغل IPTV مجاني",
            "hi": "सबसे अच्छा मुफ़्त IPTV प्लेयर",
            "hi-precomposed": "सबसे अच्छा मु\u095e\u094dत IPTV प्लेयर",  # फ़ as one code point (U+095E)
            "hi-without-nukta": "मुफ्त IPTV देखें",
        }
        for name, text in texts.items():
            self.write(f"android/app/src/main/res/values-{name}/strings.xml", f"<string name=\"tagline\">{text}</string>\n")
        self.assertFails("neutrality", "calls IPTV free",
                         *(f"values-{name}/strings.xml:1" for name in texts))

    def test_a_free_app_is_not_free_iptv(self):
        self.write("fastlane/metadata/es-ES/description.txt", "Ondo es gratis. Es un reproductor IPTV: no incluye contenido.\n")
        self.write("App/Resources/Notes.txt", "免费下载。IPTV 播放器，不提供内容。\n")
        self.assertPasses("neutrality")

    def test_a_real_channel_fails(self):
        self.write("Tests/Fixtures/sample.m3u", "#EXTM3U\n#EXTINF:-1,France 2 HD\nhttp://example.com/2.m3u8\n")
        self.assertFails("neutrality", "sample.m3u:2", "real channel")


if __name__ == "__main__":
    unittest.main()
