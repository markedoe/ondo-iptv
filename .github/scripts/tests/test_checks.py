"""The CI configuration checks must reject what they exist to catch, and accept the repository.

Each test builds a small project in a temporary folder, with or without the defect, and runs the check on
it as CI does. Run: python3 -m unittest discover -s .github/scripts/tests
"""
import datetime
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
        for check in ("translations", "privacy_manifest", "mac_entitlements", "neutrality", "awards", "app_store"):
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

    def test_a_permission_prompt_missing_from_its_catalogue_fails(self):
        # The system shows the prompt in English in every language when InfoPlist.xcstrings has no entry for it.
        self.catalogue({"New Movies": french("Nouveaux films")})
        self.write("project.yml", "    info:\n      properties:\n        NSLocalNetworkUsageDescription: Ondo looks for TVs.\n"
                                  "        NSCameraUsageDescription: Ondo scans a playlist's QR code.\n")
        self.write("App/Resources/InfoPlist.xcstrings", json.dumps(
            {"sourceLanguage": "en", "strings": {"NSLocalNetworkUsageDescription": french("Ondo cherche les TV.")}, "version": "1.0"}))
        self.assertFails("translations", "NSCameraUsageDescription")
        self.write("project.yml", "    info:\n      properties:\n        NSLocalNetworkUsageDescription: Ondo looks for TVs.\n")
        self.assertPasses("translations")

    def test_stale_keys_are_ignored(self):
        self.catalogue({"Old Row": {"extractionState": "stale", "localizations": {}}})
        self.assertPasses("translations")

    def test_a_text_missing_from_the_catalogue_fails_with_its_place(self):
        self.catalogue({"New Movies": french("Nouveaux films")})
        self.write("App/Home.swift", 'let a = 1\nShelf(title: "Brand New Row", metrics: metrics)\n')
        self.assertFails("translations", "App/Home.swift:2", "Brand New Row")

    def test_placeholders_kept_in_another_order_pass(self):
        self.catalogue({"Starts at %1$@ on %2$@": french("Sur %2$@ à %1$@"), "%@ of %@": french("%2$@ : %1$@"),
                        "%lld%% watched": french("%lld %% vu")})
        self.assertPasses("translations")

    def test_a_number_read_as_a_text_fails(self):
        self.catalogue({"Season %lld": french("Saison %@")})
        self.assertFails("translations", "French text in Localizable.xcstrings does not keep the placeholders of 'Season %lld'")

    def test_a_missing_or_extra_placeholder_fails(self):
        self.catalogue({"Next: %@": french("À suivre"), "Delete “%@”?": french("Supprimer « %@ » de %@ ?")})
        self.assertFails("translations", "'Next: %@'", "'Delete “%@”?'")

    def test_a_stray_percent_sign_fails(self):
        self.catalogue({"Next: %@": french("À suivre : %@ (50 %)")})
        self.assertFails("translations", "'Next: %@'")

    def test_an_arabic_digit_inside_a_placeholder_fails(self):
        self.catalogue({"Starts at %1$@ on %2$@": french("يبدأ في %١$@ على %2$@")})
        self.assertFails("translations", "'Starts at %1$@ on %2$@'")

    def test_positional_and_plain_placeholders_mixed_fail(self):
        self.catalogue({"%@ of %@": french("%2$@ sur %@")})
        self.assertFails("translations", "'%@ of %@'")

    def plural(self, forms: dict) -> dict:
        """%lld seasons in every language, with the plural forms given for Arabic and French."""
        values = {"fr": {"one": "%lld saison", "many": "%lld saisons", "other": "%lld saisons"},
                  "es": {"one": "%lld temporada", "many": "%lld temporadas", "other": "%lld temporadas"},
                  "pt": {"one": "%lld temporada", "many": "%lld temporadas", "other": "%lld temporadas"},
                  "zh-Hans": {"other": "%lld 季"}, "hi": {"one": "%lld सीज़न", "other": "%lld सीज़न"},
                  "ar": {"zero": "%lld موسم", "one": "موسم واحد", "two": "موسمان", "few": "%lld مواسم",
                         "many": "%lld موسمًا", "other": "%lld موسم"}}
        values.update(forms)
        return {"localizations": {code: {"variations": {"plural": {
            case: {"stringUnit": {"state": "translated", "value": value}} for case, value in cases.items()}}}
            for code, cases in values.items()}}

    def test_plural_forms_of_each_language_pass(self):
        self.catalogue({"%lld seasons": self.plural({})})
        self.assertPasses("translations")

    def test_arabic_without_its_dual_fails(self):
        self.catalogue({"%lld seasons": self.plural({"ar": {"one": "موسم واحد", "few": "%lld مواسم", "other": "%lld موسم"}})})
        self.assertFails("translations", "Arabic plural forms in Localizable.xcstrings are ['few', 'one', 'other']", "%lld seasons")

    def test_a_plural_form_with_another_placeholder_fails(self):
        self.catalogue({"%lld seasons": self.plural({"fr": {"one": "%lld saison", "many": "%lld saisons", "other": "%@ saisons"}})})
        self.assertFails("translations", "French plural.other text in Localizable.xcstrings", "%lld seasons")


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

    def test_code_that_debug_or_another_condition_turns_on_ships(self):
        self.manifest()
        self.write("App/Model/Preferences.swift", "#if DEBUG || TESTFLIGHT_DOWNLOADS\nlet r = UserDefaults.standard\n#endif\n")
        self.assertFails("privacy_manifest", "NSPrivacyAccessedAPICategoryUserDefaults", "App/Model/Preferences.swift")
        self.write("App/Model/Preferences.swift", "#if DEBUG && os(iOS)\nlet r = UserDefaults.standard\n#elseif DEBUG\n"
                   "let s = UserDefaults.standard\n#endif\n")
        self.assertPasses("privacy_manifest")

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
        self.write("Packages/IPTVCore/Sources/CZlib/release.c", "#if defined(DEBUG) || defined(VERBOSE)\nint ok = fstatfs(fd, &info);\n"
                   "#elif 0\nint a = 0;\n#endif /* DEBUG */\n")
        self.assertFails("privacy_manifest", "NSPrivacyAccessedAPICategoryDiskSpace", "release.c")
        self.write("Packages/IPTVCore/Sources/CZlib/release.c", "# if defined DEBUG && !defined(NDEBUG)\nint ok = fstatfs(fd, &info);\n"
                   "# endif\n")
        self.assertPasses("privacy_manifest")

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

    def test_the_server_entitlement_app_review_refused_must_not_come_back(self):
        self.project(self.COMPLETE | {"com.apple.security.network.server": True})
        self.assertFails("mac_entitlements", "com.apple.security.network.server")

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


class AwardsTests(CheckTestCase):
    AWARDS = {"awards": [{"id": "palme-dor", "group": "cannes", "names": {"en": "Palme d'Or", "fr": "Palme d'or"}}],
              "films": [{"titles": ["Pulp Fiction"], "year": 1994, "awards": [["palme-dor", 1994, 1]]}]}

    def both(self, document: dict, android: dict | None = None):
        self.write("App/Resources/awards.json", json.dumps(document))
        self.write("android/app/src/main/assets/awards.json", json.dumps(android or document))

    def test_accepts_the_same_well_formed_file(self):
        self.both(self.AWARDS)
        self.assertPasses("awards")

    def test_rejects_copies_that_differ(self):
        changed = json.loads(json.dumps(self.AWARDS))
        changed["films"][0]["year"] = 1995
        self.both(self.AWARDS, changed)
        self.assertFails("awards", "copies differ")

    def test_rejects_an_award_id_that_points_elsewhere(self):
        wrong = json.loads(json.dumps(self.AWARDS))
        wrong["films"][0]["awards"] = [["palme-dor", 1994, 0]]
        self.both(wrong)
        self.assertFails("awards", "Pulp Fiction should have won palme-dor in 1994")

    def test_rejects_an_entry_for_an_unknown_award(self):
        wrong = json.loads(json.dumps(self.AWARDS))
        wrong["films"].append({"titles": ["Other"], "awards": [["golden-raspberry", 2000, 1]]})
        self.both(wrong)
        self.assertFails("awards", "bad award entry")

    def test_rejects_a_missing_copy(self):
        self.write("App/Resources/awards.json", json.dumps(self.AWARDS))
        self.assertFails("awards", "missing awards data")


class AppStoreTests(CheckTestCase):
    """The App Store edition: nothing dormant, no private API, a listing and review notes App Review accepts."""
    YEAR = datetime.datetime.now(datetime.timezone.utc).year
    LISTING = {"name.txt": "Ondo", "subtitle.txt": "Movies, series & TV player", "keywords.txt": "m3u,epg",
               "promotional_text.txt": "Your provider's movies.", "description.txt": "Ondo is a player only.",
               "release_notes.txt": "• Movies start faster. Some channels may be offline.",
               "support_url.txt": "https://example.com/support", "privacy_url.txt": "https://example.com/privacy",
               "marketing_url.txt": "https://example.com/"}
    NOTES = ("HOW TO TEST: tap Try the demo.\nLocal network (NSLocalNetworkUsageDescription). Notifications. "
             "NSAllowsArbitraryLoads. UIBackgroundModes audio. com.apple.security.network.client.\n")
    FLAGS = ("enum FeatureFlags {\n    #if TESTFLIGHT_DOWNLOADS || DEBUG\n    static let downloads: Bool = {\n"
             "        #if TESTFLIGHT_DOWNLOADS\n        return true\n        #else\n"
             "        return !ProcessInfo.processInfo.arguments.contains(\"-uitest-showcase\")\n        #endif\n"
             "    }()\n    #endif\n}\n")

    def setUp(self):
        super().setUp()
        self.project()
        self.write("App/Resources/Ondo-macOS.entitlements",
                   plistlib.dumps({"com.apple.security.app-sandbox": True, "com.apple.security.network.client": True}))
        self.write(".github/workflows/release.yml", "      EDITION: ${{ github.event.inputs.edition || 'app-store' }}\n")
        self.write("fastlane/Fastfile", "  args << \"'SWIFT_ACTIVE_COMPILATION_CONDITIONS=$(inherited) TESTFLIGHT_DOWNLOADS'\" "
                   "if testflight_edition?\n\ndef testflight_edition?\n  ENV[\"EDITION\"] == \"testflight\"\nend\n")
        self.write("fastlane/metadata/copyright.txt", f"© {self.YEAR} example\n")
        self.write("fastlane/metadata/review_information/notes.txt", self.NOTES)
        for locale in ("en-US", "fr-FR"):
            for name, text in self.LISTING.items():
                self.write(f"fastlane/metadata/{locale}/{name}", text + "\n")
        self.write("App/Features/Flags.swift", self.FLAGS)
        self.write("App/Model/Reminders.swift", "let center = UNUserNotificationCenter.current()  // reminders\n")
        self.write("App/Model/DownloadManager.swift",
                   "#if TESTFLIGHT_DOWNLOADS || DEBUG\nfinal class DownloadManager {\n"
                   "    let configuration = URLSessionConfiguration.background(withIdentifier: \"d\")\n}\n#endif\n")
        self.write("App/Features/Movie.swift", "#if os(iOS)\nlet a = 1\n#endif\n#if TESTFLIGHT_DOWNLOADS || DEBUG\n"
                   "let button = DownloadButton(item: item)\n#endif\n")

    def project(self, extra: str = "", modes: str = " [audio]") -> None:
        self.write("project.yml", f"        NSHumanReadableCopyright: © {self.YEAR} example\n"
                   "        NSAppTransportSecurity:\n          NSAllowsArbitraryLoads: true\n"
                   f"        NSLocalNetworkUsageDescription: Ondo looks for TVs.\n        UIBackgroundModes:{modes}\n" + extra)

    def test_the_app_store_edition_passes(self):
        self.assertPasses("app_store")

    def test_download_code_the_app_store_edition_compiles_fails(self):
        self.write("App/Features/Series.swift", "#if DEBUG\nlet a = 1\n#else\nlet manager = DownloadManager.shared\n#endif\n")
        self.assertFails("app_store", "App/Features/Series.swift: the App Store edition compiles download code")

    def test_download_code_under_a_platform_condition_still_ships(self):
        self.write("App/Player/Delegate.swift", "#if os(iOS)\nlet id = DownloadManager.sessionIdentifier\n#endif\n")
        self.assertFails("app_store", "App/Player/Delegate.swift")

    def test_a_feature_switched_off_for_good_is_dormant(self):
        self.write("App/Features/Flags.swift", self.FLAGS.replace("enum FeatureFlags {\n", "enum FeatureFlags {\n    static let extra = false\n"))
        self.assertFails("app_store", "FeatureFlags.extra is off in the App Store edition")

    def test_a_computed_feature_switched_off_for_good_is_dormant(self):
        for flag in ("static var extra: Bool { false }", "static var extra: Bool { return false }",
                     "static func extra() -> Bool { false }", "static let extra: Bool = {\n        return false\n    }()"):
            with self.subTest(flag=flag):
                self.write("App/Features/Flags.swift", self.FLAGS.replace("enum FeatureFlags {\n", f"enum FeatureFlags {{\n    {flag}\n"))
                self.assertFails("app_store", "FeatureFlags.extra is off in the App Store edition")

    def test_a_switch_read_at_run_time_is_a_hidden_feature(self):
        self.write("App/Features/Flags.swift", self.FLAGS.replace(
            "enum FeatureFlags {\n", "enum FeatureFlags {\n    static var unlocked: Bool { UserDefaults.standard.bool(forKey: \"code\") }\n"))
        self.assertFails("app_store", "FeatureFlags reads something at run time")

    def test_a_switch_turned_by_an_argument_a_date_icloud_or_a_file_is_a_hidden_feature(self):
        for switch in ('CommandLine.arguments.contains("-unlock")', "Date.now > Self.launchDay",
                       'NSUbiquitousKeyValueStore.default.bool(forKey: "unlock")',
                       'FileManager.default.fileExists(atPath: "/tmp/unlock")', 'getenv("ONDO_UNLOCK") != nil'):
            with self.subTest(switch=switch):
                self.write("App/Features/Flags.swift", self.FLAGS.replace(
                    "enum FeatureFlags {\n", f"enum FeatureFlags {{\n    static var unlocked: Bool {{ {switch} }}\n"))
                self.assertFails("app_store", "FeatureFlags reads something at run time")

    def test_launch_arguments_read_by_the_app_store_edition_are_a_hidden_switch(self):
        hook = 'private static let autoAdvances = !ProcessInfo.processInfo.arguments.contains { $0.hasPrefix("-uitest") }\n'
        self.write("App/Features/Home/Hero.swift", hook)
        self.write("Packages/IPTVCore/Sources/IPTVCore/Mode.swift", 'let verbose = CommandLine.arguments.contains("-v")\n')
        self.assertFails("app_store", "App/Features/Home/Hero.swift: the App Store edition reads its launch arguments",
                         "Packages/IPTVCore/Sources/IPTVCore/Mode.swift: the App Store edition reads its launch arguments")
        self.write("App/Features/Home/Hero.swift", f"#if DEBUG\n{hook}#else\nprivate static let autoAdvances = true\n#endif\n")
        self.write("Packages/IPTVCore/Sources/IPTVCore/Mode.swift", "let verbose = false\n")
        self.assertPasses("app_store")

    def test_launch_arguments_read_through_user_defaults_are_a_hidden_switch_too(self):
        # `-uitest-route home` on the command line lands in UserDefaults (its argument domain).
        hook = 'private static let route = UserDefaults.standard.string(forKey: "uitest-route")\n'
        self.write("App/Model/ScreenshotRoute.swift", hook)
        self.write("App/Model/Unlock.swift",
                   'let unlocked = UserDefaults.standard.volatileDomain(forName: UserDefaults.argumentDomain)["x"] != nil\n')
        self.assertFails("app_store", "App/Model/ScreenshotRoute.swift: the App Store edition reads its launch arguments",
                         "App/Model/Unlock.swift: the App Store edition reads its launch arguments")
        self.write("App/Model/ScreenshotRoute.swift", f"#if DEBUG\n{hook}#endif\n")
        self.write("App/Model/Unlock.swift", 'let sort = UserDefaults.standard.string(forKey: "sortOrder")\n')
        self.assertPasses("app_store")

    def test_a_private_api_fails(self):
        self.write("Packages/IPTVCore/Sources/IPTVCore/Hack.swift", "let selector = NSSelectorFromString(\"_hidden\")\n")
        self.assertFails("app_store", "Packages/IPTVCore/Sources/IPTVCore/Hack.swift: looks up or swaps code by name")

    def test_the_testflight_condition_must_not_be_a_build_setting(self):
        self.project("    SWIFT_ACTIVE_COMPILATION_CONDITIONS: TESTFLIGHT_DOWNLOADS\n")
        self.assertFails("app_store", "project.yml: TESTFLIGHT_DOWNLOADS")

    def test_a_tag_must_build_the_app_store_edition(self):
        self.write(".github/workflows/release.yml", "      EDITION: ${{ github.event.inputs.edition || 'testflight' }}\n")
        self.assertFails("app_store", "a tag must build the App Store edition")

    def test_the_listing_follows_app_review_rules(self):
        self.write("fastlane/metadata/en-US/description.txt", "Also on Android.\nDownload movies to watch offline.\n")
        self.write("fastlane/metadata/fr-FR/promotional_text.txt", "Bientôt : la version bêta.\n")
        self.write("fastlane/metadata/fr-FR/name.txt", "Ondo – le lecteur de films, séries et TV\n")
        self.assertFails("app_store", "names another platform", "mentions downloads", "promises a feature to come",
                         "has test words", "fr-FR/name.txt has 40 characters")

    def test_the_app_store_edition_never_says_beta(self):
        self.write("App/Features/Badge.swift", "#if TESTFLIGHT_DOWNLOADS\nlet badge = Text(\"Beta\")\n#endif\n"
                   "let version = \"1.1 (beta)\"\n")
        self.assertFails("app_store", "App/Features/Badge.swift: the App Store edition calls the app a beta")

    def test_the_testflight_beta_badge_passes(self):
        self.write("App/Features/Badge.swift", "#if TESTFLIGHT_DOWNLOADS\nlet badge = Text(\"Beta\")\n#endif\n"
                   "let alphabet = \"betamax\"\n")
        self.assertPasses("app_store")

    def test_the_site_pages_the_listing_opens_have_no_test_words(self):
        self.write("site/index.html", "<h1>Ondo</h1>\n<p><b>Beta</b>: still changing.</p>\n")
        self.write("site/notes.html", "<p>Beta builds on TestFlight.</p>\n")
        self.assertFails("app_store", "site/index.html:2 has test words")
        code, output = run("app_store", self.root)
        self.assertNotIn("site/notes.html", output, "a page the listing does not open may talk of the beta")

    def test_the_copyright_must_be_this_years(self):
        self.write("fastlane/metadata/copyright.txt", f"© {self.YEAR - 1} example\n")
        self.assertFails("app_store", "copyright.txt must name this year", "NSHumanReadableCopyright")

    def test_every_permission_and_entitlement_is_explained_to_the_reviewer(self):
        self.project("        NSCameraUsageDescription: Scans codes.\n")
        self.write("App/Resources/Ondo-macOS.entitlements", plistlib.dumps({
            "com.apple.security.app-sandbox": True, "com.apple.security.network.client": True,
            "com.apple.security.network.server": True}))
        self.write("fastlane/metadata/review_information/notes.txt", self.NOTES.replace("Notifications. ", ""))
        self.assertFails("app_store", "do not explain NSCameraUsageDescription",
                         "do not explain com.apple.security.network.server", "do not explain Notifications")

    def test_a_browsed_bonjour_service_is_declared_explained_and_given_as_a_reason(self):
        self.write("Packages/IPTVCore/Sources/IPTVCore/Sync/SyncNetwork.swift",
                   'public static let serviceType = "_ondo._tcp"\n// "_other._tcp" in a comment is not browsed\n')
        self.assertFails("app_store", "project.yml: NSBonjourServices lacks _ondo._tcp",
                         "do not explain the Bonjour service _ondo._tcp",
                         "NSLocalNetworkUsageDescription must say that Ondo looks for its other devices to sync")
        code, output = run("app_store", self.root)
        self.assertNotIn("_other._tcp", output)
        self.project("        NSBonjourServices:\n          - _googlecast._tcp\n          - _ondo._tcp\n")
        project = (self.root / "project.yml").read_text(encoding="utf-8")
        self.write("project.yml", project.replace("Ondo looks for TVs.", "Ondo looks for TVs, and for Ondo on your devices to sync."))
        self.write("fastlane/metadata/review_information/notes.txt", self.NOTES + "Sync browses _ondo._tcp.\n")
        self.assertPasses("app_store")

    def test_the_mac_edition_never_listens_for_connections(self):
        self.write("App/Player/RemuxServer.swift", "import Network\n#if os(iOS)\nlet listener = try NWListener(using: .tcp)\n#endif\n")
        self.assertPasses("app_store")
        self.write("App/Model/LocalSync.swift", "#if !os(iOS)\nlet listener = try NWListener(using: .tcp)\n#endif\n")
        self.assertFails("app_store", "App/Model/LocalSync.swift: the Mac App Store edition would listen for connections")

    def test_background_modes_written_as_a_block_list_are_explained_too(self):
        self.project(modes="\n          - audio\n          - fetch")
        self.assertFails("app_store", "do not explain UIBackgroundModes fetch")
        code, output = run("app_store", self.root)
        self.assertNotIn("UIBackgroundModes audio", output, "audio is in the review notes")
        self.project(modes="\n          - audio\n        UILaunchScreen:\n          - fetch")
        self.assertPasses("app_store")

    def test_the_review_notes_do_not_describe_what_the_edition_lacks(self):
        self.write("fastlane/metadata/review_information/notes.txt", self.NOTES + "Downloads are in the Downloads tab.\n")
        self.assertFails("app_store", "notes.txt:3 mentions downloads")

    def test_the_apps_lead_to_no_other_way_to_pay(self):
        self.write("App/Features/Help.swift", 'let support = URL(string: "https://example.github.io/ondo-iptv/support.html")!\n')
        self.write("site/support.html", '<p>Help and contact, <a href="#fr">en français</a>.</p>\n')
        self.write("site/index.html", '<p>Support Ondo on <a href="https://ko-fi.com/example">Ko-fi</a>.</p>\n')
        self.assertPasses("app_store")
        self.write("site/support.html", '<header><a href="./">Ondo</a></header>\n')
        self.assertFails("app_store", "site/support.html:1: the apps open this page, so it must not link to ./")
        self.write("site/support.html", "<p>Help.</p>\n<p>Like Ondo? Make a donation.</p>\n")
        self.write("android/app/src/main/res/values/strings.xml", '<string name="tip">https://ko-fi.com/example</string>\n')
        self.write("App/Features/Support.swift", 'let page = URL(string: "https://buy.stripe.com/test_00example")!\n')
        self.write(".github/FUNDING.yml", "github: [example]\n")
        self.assertFails("app_store", "site/support.html:2: the apps open this page",
                         "android/app/src/main/res/values/strings.xml: the apps link to a way to pay",
                         "App/Features/Support.swift: the apps link to a way to pay",
                         ".github/FUNDING.yml")
