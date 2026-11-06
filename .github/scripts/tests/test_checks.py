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
        for check in ("translations", "privacy_manifest", "mac_entitlements", "neutrality", "awards", "app_store", "hang_guard"):
            code, output = run(check, REPOSITORY)
            self.assertEqual(code, 0, f"{check}: {output}")


class HangGuardTests(CheckTestCase):
    GUARD = "    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()\n"

    def test_a_test_class_with_the_guard_passes(self):
        self.write("android/core/src/test/kotlin/AGuardedTest.kt", "class AGuardedTest {\n" + self.GUARD + "    @Test\n    fun works() {}\n}\n")
        self.write("android/app/src/test/kotlin/Fixtures.kt", "object Fixtures {\n    val name = \"no test here\"\n}\n")
        self.assertPasses("hang_guard")

    def test_each_test_class_of_a_file_needs_the_guard(self):
        self.write("android/app/src/test/kotlin/TwoTest.kt",
                   "class FirstTest {\n" + self.GUARD + "    @Test\n    fun works() {}\n}\n\nclass SecondTest {\n    @Test\n    fun hangs() {}\n}\n")
        self.assertFails("hang_guard", "android/app/src/test/kotlin/TwoTest.kt: SecondTest")


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
            "pt-de-graca": "Assista IPTV de graça no seu celular.",
            "en-hyphen": "The best free-IPTV player.",
            "en-joined": "#freeIPTV",
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
              "films": [{"titles": ["Pulp Fiction"], "year": 1994, "awards": [["palme-dor", 1994, 1]]}],
              "seriesAwards": [{"id": "emmy-drama-series", "group": "emmys", "names": {"en": "Primetime Emmy Award for Outstanding Drama Series"}}],
              "series": [{"titles": ["Breaking Bad"], "year": 2008, "awards": [["emmy-drama-series", 2013, 1]]}]}

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

    def test_rejects_a_series_award_among_the_films(self):
        # The apps released before series had awards read the films only: a series award there shows a series as a film.
        wrong = json.loads(json.dumps(self.AWARDS))
        wrong["films"].append({"titles": ["Breaking Bad"], "year": 2008, "awards": [["emmy-drama-series", 2013, 1]]})
        self.both(wrong)
        self.assertFails("awards", "Breaking Bad: bad award entry")

    def test_rejects_an_id_of_both_a_film_and_a_series_award(self):
        wrong = json.loads(json.dumps(self.AWARDS))
        wrong["seriesAwards"].append({"id": "palme-dor", "group": "cannes", "names": {"en": "Palme d'Or"}})
        self.both(wrong)
        self.assertFails("awards", "ids both of a film and of a series award")

    def test_rejects_a_series_award_that_points_elsewhere(self):
        wrong = json.loads(json.dumps(self.AWARDS))
        wrong["series"][0]["awards"] = [["emmy-drama-series", 2013, 0]]
        self.both(wrong)
        self.assertFails("awards", "Breaking Bad should have won emmy-drama-series in 2013")

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
    NOTES = ("HOW TO TEST: add the M3U URL https://example.github.io/ondo-iptv/review/ondo-review.m3u.\nLocal network (NSLocalNetworkUsageDescription). Notifications. "
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

    def test_a_built_in_third_party_playlist_fails(self):
        self.write("App/Model/AppPaths.swift", 'static let demo = URL(string: "https://lists.example.io/countries/fr.m3u")!\n')
        self.assertFails("app_store", "App/Model/AppPaths.swift: https://lists.example.io/countries/fr.m3u is a playlist")

    def test_a_third_party_playlist_in_the_android_app_fails(self):
        self.write("android/app/src/main/kotlin/Demo.kt", 'const val DEMO = "https://streams.example.tv/live/index.m3u8"\n')
        self.assertFails("app_store", "Demo.kt: https://streams.example.tv/live/index.m3u8 is a playlist")

    def test_a_public_channel_list_in_the_review_notes_fails(self):
        self.write("fastlane/metadata/review_information/notes.txt",
                   self.NOTES + "Or tap Try the demo: https://iptv-org.github.io/iptv/countries/fr.m3u\n")
        self.assertFails("app_store", "is a playlist the apps or the review notes would point to",
                         "names a public channel list (iptv-org)")

    def test_review_notes_without_our_test_playlist_fail(self):
        self.write("fastlane/metadata/review_information/notes.txt",
                   self.NOTES.replace("https://example.github.io/ondo-iptv/review/ondo-review.m3u", "a playlist"))
        self.assertFails("app_store", "the review notes do not give the test playlist hosted on our site")

    def test_placeholders_and_our_own_playlist_pass(self):
        self.write("App/Features/AddPlaylist.swift", 'TextField("Playlist URL", text: $url, prompt: Text("http://…/playlist.m3u"))\n'
                   'let sample = "http://provider.example.com/list.m3u"\n')
        self.write("android/app/src/main/kotlin/Hint.kt", 'const val HINT = "https://example.github.io/ondo-iptv/review/ondo-review.m3u"\n')
        self.assertPasses("app_store")

    def test_a_playlist_in_debug_only_code_is_not_shipped(self):
        self.write("App/Model/UITestFixture.swift", '#if DEBUG\nlet hls = "https://devstreaming.example.net/bipbop/master.m3u8"\n'
                   'let other = "https://cdn.example.tv/master.m3u8"\n#endif\n')
        self.assertPasses("app_store")

    def test_a_listing_naming_addic7ed_fails(self):
        self.write("fastlane/metadata/fr-FR/description.txt", "Sous-titres : OpenSubtitles, Addic7ed pour les séries.\n")
        self.assertFails("app_store", "fastlane/metadata/fr-FR/description.txt:1 names Addic7ed")

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

    # The external testers' path (check_app_store.py, rule 9): the App Store edition only, through Beta App Review.
    EXTERNAL_FASTFILE = (
        "  args << \"'SWIFT_ACTIVE_COMPILATION_CONDITIONS=$(inherited) TESTFLIGHT_DOWNLOADS'\" if testflight_edition?\n\n"
        "def testflight_edition?\n  ENV[\"EDITION\"] == \"testflight\"\nend\n\n"
        "def app_store_edition?\n  ENV[\"EDITION\"] == \"app-store\"\nend\n\n"
        "def upload_build(platform)\n  upload_to_testflight(\n    api_key: api_key,\n    skip_submission: true\n  )\nend\n\n"
        "def external_group(app)\n  app.create_beta_group(group_name: EXTERNAL_GROUP, is_internal_group: false)\nend\n\n"
        "def distribute_to_external_testers(platform)\n"
        "  UI.user_error!(\"Only the App Store edition\") unless app_store_edition?\n"
        "  upload_to_testflight(\n    distribute_only: true,\n    distribute_external: true,\n"
        "    groups: [EXTERNAL_GROUP],\n    submit_beta_review: true\n  )\nend\n\n"
        "platform :ios do\n  lane :external_testers do\n    distribute_to_external_testers(\"ios\")\n  end\nend\n")
    EXTERNAL_RELEASE = (
        "      EDITION: ${{ github.event.inputs.edition || 'app-store' }}\n"
        "    outputs:\n      ios_app_store_build: ${{ steps.ios_app_store.outputs.build }}\n"
        "      mac_app_store_build: ${{ steps.mac_app_store.outputs.build }}\n    steps:\n"
        "      - name: iOS App Store edition\n        id: ios_app_store\n        env:\n          EDITION: app-store\n"
        "        run: bundle exec fastlane ios beta\n"
        "      - name: iOS → TestFlight\n        id: ios\n        run: bundle exec fastlane ios beta\n"
        "      - name: macOS App Store edition\n        id: mac_app_store\n        env:\n          EDITION: app-store\n"
        "        run: bundle exec fastlane mac beta\n"
        "  external:\n    steps:\n"
        "      - name: iOS → external testers\n        env:\n          EDITION: app-store\n"
        "          BUILD_NUMBER: ${{ needs.deliver.outputs.ios_app_store_build }}\n"
        "        run: bundle exec fastlane ios external_testers\n"
        "      - name: macOS → external testers\n        env:\n          EDITION: app-store\n"
        "          BUILD_NUMBER: ${{ needs.deliver.outputs.mac_app_store_build }}\n"
        "        run: bundle exec fastlane mac external_testers\n")

    def external_path(self, fastfile: str = EXTERNAL_FASTFILE, release: str = EXTERNAL_RELEASE) -> None:
        self.write("fastlane/Fastfile", fastfile)
        self.write(".github/workflows/release.yml", release)

    def test_external_testers_with_the_app_store_edition_pass(self):
        self.external_path()
        self.assertPasses("app_store")

    def test_the_testflight_upload_never_distributes_externally(self):
        self.external_path(self.EXTERNAL_FASTFILE.replace("    skip_submission: true\n",
                                                          "    skip_submission: false,\n    distribute_external: true,\n"
                                                          "    groups: [\"External testers\"]\n"))
        self.assertFails("app_store", "outside distribute_to_external_testers", "must keep skip_submission: true")

    def test_beta_review_outside_the_guarded_function_fails(self):
        self.external_path(self.EXTERNAL_FASTFILE + "\nlane :anything do\n  upload_to_testflight(submit_beta_review: true)\nend\n")
        self.assertFails("app_store", "Fastfile:39: gives a build to external testers or Beta App Review outside")

    def test_the_external_distribution_must_refuse_the_testflight_edition(self):
        for fastfile in (self.EXTERNAL_FASTFILE.replace("  UI.user_error!(\"Only the App Store edition\") unless app_store_edition?\n", ""),
                         self.EXTERNAL_FASTFILE.replace("unless app_store_edition?", "if testflight_edition? && false"),
                         self.EXTERNAL_FASTFILE.replace('ENV["EDITION"] == "app-store"', 'ENV["EDITION"] != "app-store"')):
            with self.subTest(fastfile=fastfile[-400:]):
                self.external_path(fastfile)
                code, output = run("app_store", self.root)
                self.assertEqual(code, 1, output)
                self.assertTrue("must first refuse any edition but the App Store one" in output
                                or "app_store_edition? must be" in output, output)

    def test_the_external_distribution_never_builds(self):
        self.external_path(self.EXTERNAL_FASTFILE.replace("    distribute_only: true,\n", ""))
        self.assertFails("app_store", "distribute_only: true")

    def test_an_external_step_without_the_app_store_edition_fails(self):
        for release in (self.EXTERNAL_RELEASE.replace("          EDITION: app-store\n          BUILD_NUMBER: ${{ needs.deliver.outputs.ios",
                                                      "          BUILD_NUMBER: ${{ needs.deliver.outputs.ios"),
                        self.EXTERNAL_RELEASE.replace("          EDITION: app-store\n          BUILD_NUMBER: ${{ needs.deliver.outputs.mac",
                                                      "          EDITION: testflight\n          BUILD_NUMBER: ${{ needs.deliver.outputs.mac")):
            with self.subTest(release=release):
                self.external_path(release=release)
                self.assertFails("app_store", "sends a build to external testers without EDITION: app-store")

    def test_external_testers_never_get_the_testflight_edition_build(self):
        self.external_path(release=self.EXTERNAL_RELEASE.replace(
            "BUILD_NUMBER: ${{ needs.deliver.outputs.ios_app_store_build }}", "BUILD_NUMBER: ${{ needs.deliver.outputs.ios_build }}"))
        self.assertFails("app_store", "must take BUILD_NUMBER from the App Store edition build")
        self.external_path(release=self.EXTERNAL_RELEASE.replace(
            "ios_app_store_build: ${{ steps.ios_app_store.outputs.build }}", "ios_app_store_build: ${{ steps.ios.outputs.build }}"))
        self.assertFails("app_store", "the external testers' ios build must be the output of step ios_app_store")

    def test_the_external_build_must_be_the_app_store_edition(self):
        self.external_path(release=self.EXTERNAL_RELEASE.replace(
            "        id: mac_app_store\n        env:\n          EDITION: app-store\n",
            "        id: mac_app_store\n        env:\n          EDITION: testflight\n"))
        self.assertFails("app_store", "step mac_app_store builds what external testers get")
        self.external_path(release=self.EXTERNAL_RELEASE.replace(
            "        run: bundle exec fastlane ios beta\n      - name: iOS → TestFlight",
            "        run: EDITION=testflight bundle exec fastlane ios beta\n      - name: iOS → TestFlight"))
        self.assertFails("app_store", "step ios_app_store builds what external testers get")

    def test_what_to_test_on_every_build_only_sets_the_build_localizations(self):
        fill = ("def fill_what_to_test(platform, number)\n"
                "  Spaceship::ConnectAPI.patch_beta_build_localizations(localization_id: id, attributes: attributes)\n"
                "  Spaceship::ConnectAPI.post_beta_build_localizations(build_id: build.id, attributes: attributes)\n%send\n")
        self.external_path(self.EXTERNAL_FASTFILE + fill % "")
        self.assertPasses("app_store")
        for extra in ("  Spaceship::ConnectAPI.patch_beta_app_localizations(localization_id: id, attributes: a)\n",
                      "  build.post_beta_app_review_submission\n", "  build.expire!\n"):
            with self.subTest(extra=extra):
                self.external_path(self.EXTERNAL_FASTFILE + fill % extra)
                self.assertFails("app_store", "fill_what_to_test runs on the TestFlight edition too")

    def test_a_workflow_never_runs_pilot_itself(self):
        self.external_path()
        self.write(".github/workflows/other.yml", "      - run: bundle exec fastlane pilot distribute --distribute_external\n")
        self.assertFails("app_store", ".github/workflows/other.yml: runs pilot or upload_to_testflight directly")

    def test_the_listing_follows_app_review_rules(self):
        self.write("fastlane/metadata/en-US/description.txt", "Also on Android.\nDownload movies to watch offline.\n")
        self.write("fastlane/metadata/fr-FR/promotional_text.txt", "Bientôt : la version bêta.\n")
        self.write("fastlane/metadata/fr-FR/name.txt", "Ondo – le lecteur de films, séries et TV\n")
        self.assertFails("app_store", "names another platform", "mentions downloads", "promises a feature to come",
                         "has test words", "fr-FR/name.txt has 40 characters")

    def test_the_listing_follows_the_rules_in_every_language(self):
        for text, reason in (("Lector IPTV gratis.", "calls the app or what it plays free"),
                             ("免费 IPTV 播放器", "calls the app or what it plays free"),
                             ("मुफ़्त IPTV प्लेयर", "calls the app or what it plays free"),
                             ("مشغل IPTV مجاني", "calls the app or what it plays free"),
                             ("Versão de teste do player.", "has test words"),
                             ("Próximamente en tu televisor.", "promises a feature to come"),
                             ("下载电影离线观看", "mentions downloads")):
            with self.subTest(text):
                self.write("fastlane/metadata/es-ES/description.txt", text + "\n")
                for name, other in self.LISTING.items():
                    if name != "description.txt":
                        self.write(f"fastlane/metadata/es-ES/{name}", other + "\n")
                self.assertFails("app_store", f"es-ES/description.txt:1 {reason}")

    def test_in_app_purchases_must_be_explained_in_the_review_notes(self):
        self.write("App/Model/Tips.swift", "import StoreKit\n\nenum Tip: String, CaseIterable {\n    case small, large\n\n"
                   "    var productID: String { \"com.markedoe.ondo.tip.\\(rawValue)\" }\n}\n")
        self.assertFails("app_store", "the review notes do not explain In-App Purchase",
                         "the review notes do not explain com.markedoe.ondo.tip.large", "do not explain unlocks nothing")
        self.write("fastlane/metadata/review_information/notes.txt", self.NOTES + "Tips: In-App Purchase "
                   "com.markedoe.ondo.tip.small and com.markedoe.ondo.tip.large; a tip unlocks nothing.\n")
        self.assertPasses("app_store")

    def test_todo_in_a_sentence_is_not_a_placeholder(self):
        self.write("fastlane/metadata/fr-FR/description.txt", "Rápido em todo lugar: todo el catálogo.\n")
        self.assertPasses("app_store")

    def test_the_google_play_listing_is_checked_in_every_language(self):
        for name, text in {"title.txt": "Ondo – IPTV Player", "short_description.txt": "Your provider's TV.",
                           "full_description.txt": "Ondo is a player only.", "changelogs/default.txt": "Faster."}.items():
            self.write(f"fastlane/metadata-android/en-US/{name}", text + "\n")
        self.assertPasses("app_store")
        self.write("fastlane/metadata-android/pt-BR/title.txt", "Ondo – Player IPTV grátis para iPhone\n")
        self.write("fastlane/metadata-android/pt-BR/short_description.txt", "x" * 81 + "\n")
        self.write("fastlane/metadata-android/pt-BR/full_description.txt", "Ondo.\n")
        self.assertFails("app_store", "pt-BR/title.txt:1 calls the app or what it plays free", "pt-BR/title.txt:1 names another platform",
                         "pt-BR/short_description.txt has 81 characters, Google Play takes 80",
                         "pt-BR/changelogs/default.txt is missing")

    def test_no_edition_has_a_beta_badge(self):
        self.write("App/Features/Badge.swift", "#if TESTFLIGHT_DOWNLOADS\nlet badge = Text(\"Beta\")\n#endif\n")
        self.assertFails("app_store", "App/Features/Badge.swift:2 says or shows a beta")

    def test_a_beta_word_in_any_language_or_in_android_code_fails(self):
        self.write("android/app/src/main/res/values-fr/strings.xml", '<string name="x">Bêta</string>\n')
        self.write("android/app/src/main/kotlin/Home.kt", "val on = BuildConfig.BETA\n")
        self.assertFails("app_store", "values-fr/strings.xml:1 says or shows a beta", "Home.kt:1 says or shows a beta")

    def test_words_that_only_contain_beta_pass(self):
        self.write("App/Features/Badge.swift", 'let alphabet = "betamax"\nlet list = "alphabetical"\n')
        self.assertPasses("app_store")

    def test_the_app_store_edition_never_speaks_of_downloads(self):
        self.write("App/Player/Cast.swift", "#if TESTFLIGHT_DOWNLOADS || DEBUG\nlet tab = Text(\"Downloads\")\n#endif\n"
                   "let message = String(localized: \"Downloaded files can't be sent to a Chromecast.\")\n")
        self.assertFails("app_store", "App/Player/Cast.swift: the App Store edition speaks of downloads")
        for text in ("Downloads", "Delete Download", "12 MB downloaded", "Resume the download.", "Watch offline"):
            with self.subTest(text):
                self.write("App/Player/Cast.swift", f"let label = Text(\"{text}\")\n")
                self.assertFails("app_store", "App/Player/Cast.swift: the App Store edition speaks of downloads")

    def test_fetching_a_playlist_is_not_the_download_feature(self):
        self.write("App/Model/Loading.swift", "#if TESTFLIGHT_DOWNLOADS || DEBUG\nlet title = Text(\"Download Details\")\n"
                   "#endif\nlet phase = String(localized: \"Downloading playlist…\")\n"
                   "let awards = Text(\"Once a week, Ondo downloads the latest awards.\")\n"
                   "let limit = Text(\"Without signing in, 5 downloads a day.\")\n"
                   "let count = Label(\"\\(downloads)\", systemImage: \"arrow.down.circle\")\n"
                   "let offline = Text(\"It may be offline.\")\n")
        self.assertPasses("app_store")

    def test_the_site_pages_the_listing_opens_have_no_test_words(self):
        self.write("site/index.html", "<h1>Ondo</h1>\n<p><b>Beta</b>: still changing.</p>\n")
        self.write("site/notes.html", "<p>Beta builds on TestFlight.</p>\n")
        self.assertFails("app_store", "site/index.html:2 has test words")
        code, output = run("app_store", self.root)
        self.assertIn("site/notes.html:1 says or shows a beta", output, "no page of the site may talk of a beta")

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
