#!/usr/bin/env python3
"""The App Store edition must pass App Review and never put the developer account at risk (CLAUDE.md, App Store).

Apple can remove a developer from its program for hidden or dormant features (guideline 2.5.19), so the build that is
submitted, its listing and its review notes are checked here, before any upload:

1. Nothing hidden or dormant. The App Store edition is a Release build without TESTFLIGHT_DOWNLOADS: its code (App/,
   read as that build compiles it) holds no download code; no build setting turns TESTFLIGHT_DOWNLOADS on for it and a
   tag, the release that gets submitted, builds it; `FeatureFlags` reads nothing at run time and switches nothing off
   for good (code no build can use is left out of the build), and no code reads the launch arguments or the
   environment (test hooks are `#if DEBUG`). No edition says "beta" anywhere (2.2): no beta badge, no beta
   word in an app string, in Swift or Kotlin UI code or on the website. Nor do the App Store texts speak of
   downloads ("Downloads", "Downloaded files…"): App Review would look for a feature the build does not have (2.3.1).
2. No private API: no class, selector or symbol looked up by name, no method swapped, no script run (2.5.1, 2.5.2).
3. The listing (fastlane/metadata, every language) fits App Store Connect's limits, names no other platform, promises
   nothing to come, has no test or placeholder words, says nothing of downloads (not in this edition), never calls
   the app or what it plays free, and its copyright is this year's, the same as the app's (2.3). The Google Play
   listing (fastlane/metadata-android, every language) is held to the same words and to Google Play's limits.
4. The review notes explain every permission, background mode and Mac entitlement the app asks for (2.1, 5.1.1), and
   each In-App Purchase (the optional tips) and that it unlocks nothing (3.1.1). When the App Store edition leaves the
   tips out (no StoreKit in its build), neither the listing nor the review notes speak of tips (2.3.1).
5. The pages of the website the listing opens (its support, privacy and marketing addresses, which App Review visits)
   have no test words either: the App Store app must not read as a beta (2.2).
6. No way to pay or donate other than In-App Purchase is reachable from the apps (3.1.1, and Google Play's payments
   policy): no donation link in their code or on the pages of the website they open, and no `.github/FUNDING.yml`,
   whose Sponsor button shows on the GitHub issue form the apps open.
7. The local network: every Bonjour service the app's code browses is declared in NSBonjourServices (iOS finds nothing
   otherwise) and named in the review notes; when it browses Ondo's own service (sync on the local network,
   docs/SYNC.md), the Local Network prompt says so (5.1.1). The Mac App Store edition never listens for connections
   (no NWListener outside iPhone and iPad code): it has no network.server entitlement, which App Review refused.

8. No built-in playlist from a third party (5.2.3): the apps ship no demo, so no playlist address (.m3u, .m3u8) in the
   App Store edition's code, the Android app's code or the review notes points anywhere but our own site (or
   example.com in placeholders), and the review notes give the test playlist hosted there (site/review, checked by
   check_review_playlist.py). The listing never names Addic7ed, a subtitle source without an official API.

9. External testers get the App Store edition only: in fastlane/Fastfile, what distributes a build outside the team
   (an external group, Beta App Review) is in `distribute_to_external_testers` and its helpers, which refuse any
   edition but the App Store one and only distribute a build given by number; the internal TestFlight upload submits
   nothing, and What to Test (set on every build) writes the build's localizations only. In the workflows, a step that runs the external_testers lanes has `EDITION: app-store` and takes its build
   number from the App Store edition steps (ids ios_app_store, mac_app_store), which build that edition (2.5.19: the
   TestFlight edition, with downloads, is never reviewed or given to external testers).

The addresses of the listing are checked to answer in the release workflow, before the upload.

Usage: check_app_store.py [repository root]. Exit code 1 lists each problem.
"""
import datetime
import plistlib
import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[2]
METADATA = ROOT / "fastlane/metadata"
NOTES = METADATA / "review_information/notes.txt"
ENTITLEMENTS = ROOT / "App/Resources/Ondo-macOS.entitlements"

# The compilation conditions of the App Store edition: a Release build, without the TestFlight edition's.
EDITION = {"DEBUG": False, "TESTFLIGHT_DOWNLOADS": False, "true": True, "false": False}

# What the App Store edition must not compile (it has no downloads).
DOWNLOAD_CODE = re.compile(
    r"\b(?:DownloadManager|DownloadRecord|DownloadButton|DownloadsView|DownloadAction|DownloadDetailsView)\b|"
    r"FeatureFlags\.downloads\b|\bshowsDownloads\b|\bcase downloads\b|URLSessionConfiguration\.background\b|"
    r"\.downloadTask\(")
PRIVATE_API = re.compile(
    r"NSSelectorFromString|NSClassFromString|\bSelector\(\"|\bdlopen\(|\bdlsym\(|objc_msgSend|objc_lookUpClass|"
    r"method_exchangeImplementations|class_getInstanceMethod|forKey(?:Path)?: *\"_|\bJSContext\b|evaluateJavaScript")
DONATION_LINK = re.compile(
    r"(?i)github\.com/sponsors|ko-fi\.com|paypal\.(?:me|com)|liberapay\.com|buymeacoffee\.com|patreon\.com|"
    r"opencollective\.com|tipeee\.com|utip\.io|(?:buy|donate|checkout)\.stripe\.com|polar\.sh")
DONATION_WORD = re.compile(r"(?i)\bdonat|\bsponsor|faire un don|pourboire")
# The website's pages the apps link to (https://<user>.github.io/ondo-iptv/<page>).
SITE_PAGE = re.compile(r"https://[\w-]+\.github\.io/ondo-iptv/([\w.-]*)")
# A link from one of the website's pages to another of them (relative: not to another site, nor within the page).
SITE_LINK = re.compile(r'href="(?![a-z][a-z0-9+.-]*:|#)([^"#?]*)')
# A playlist address with a real host name ("http://…/playlist.m3u" placeholders have none).
PLAYLIST_ADDRESS = re.compile(r"""https?://((?:[A-Za-z0-9-]+\.)+[A-Za-z]{2,})(/[^\s"'<>()]*?)?\.m3u8?\b""")
# Where a playlist address may point: our own site's review folder, or a placeholder.
OWN_PLAYLIST = re.compile(r"https://[\w-]+\.github\.io/ondo-iptv/review/[\w./-]+\.m3u8?\b")
PLACEHOLDER_HOST = re.compile(r"(?:^|\.)example\.(?:com|net|org)$")
# "beta" as a whole word in the seven languages' words (English/Spanish/Portuguese beta, French bêta, Chinese 测试版,
# Arabic بيتا, Hindi बीटा), and the names of the old badge's code. No edition has a beta badge (2.2). Dependency
# versions ("1.0.0-beta01") and Google Play track names (workflows, scripts) are outside the scanned folders.
BETA_WORD = re.compile(r"(?i)(?<![\w])(?:beta|bêta)(?![\w])|测试版|بيتا|बीटा")
BETA_CODE = re.compile(r"BetaBadge|betaBadge|BuildConfig\.BETA|ANDROID_BETA")
# A string the App Store edition would show that speaks of downloads, which it does not have (2.3.1): the feature's
# words ("Downloads", "Download Details", "Downloaded", "the download", watch offline). Fetching a playlist or the
# awards ("Downloading playlist…", "Ondo downloads the latest awards") is not that feature.
DOWNLOAD_TEXT = re.compile(
    r'"[^"\n]*(?:\bDownloads?\b|(?i:\bdownloaded\b)|(?i:\b(?:the|a|this|your|no|resume|cancel|delete)\s+downloads?\b)|'
    r'(?i:\b(?:watch|watching|view|play)\s+offline\b|\boffline\s+(?:viewing|playback|mode)\b))[^"\n]*"')
# What a flag could read to turn a feature on after review: settings (local or iCloud), launch arguments and the
# environment, a server, a date, a file, the keychain, the bundle.
RUNTIME_SWITCH = re.compile(
    r"UserDefaults|@AppStorage|NSUbiquitousKeyValueStore|ProcessInfo|CommandLine|\bgetenv\b|URLSession|\bURL\(|"
    r"\bDate\b|FileManager|contentsOf|Keychain|Bundle\.main")
# The app reading its launch arguments or environment: a switch anyone can turn from the command line, so test hooks
# (`-uitest…`) stay in `#if DEBUG` code. A `-key value` argument also lands in UserDefaults (its argument domain).
LAUNCH_SWITCH = re.compile(
    r"\bProcessInfo(?:\.processInfo|\(\))\.(?:arguments|environment)\b|\bCommandLine\.\w+|\bgetenv\(|"
    r"\bUserDefaults\b.*\bforKey:\s*\"-?uitest|\bargumentDomain\b")
# A Bonjour service type written in the code ("_ondo._tcp").
BONJOUR_SERVICE = re.compile(r'"(_[A-Za-z0-9-]+\._(?:tcp|udp))"')
# Ondo's own service: sync on the local network, which the Local Network prompt must give as a reason.
SYNC_SERVICE = "_ondo._tcp"
# The conditions of the Mac App Store edition: the App Store edition's, on macOS.
MAC_EDITION = {**EDITION, "os(macOS)": True, "os(iOS)": False}
# A flag that is off for good: `static let x = false`, `static var x: Bool { false }`, `static func x() -> Bool { false }`,
# `static let x: Bool = { return false }()`.
DORMANT_FLAG = re.compile(
    r"static\s+(?:let|var|func)\s+(\w+)\s*(?:\(\s*\)\s*->\s*Bool|:\s*Bool)?\s*"
    r"(?:=\s*false\b|=?\s*\{\s*(?:get\s*\{\s*)?(?:return\s+)?false\s*\})")

# The tips in every language of the listing, and their products: only while the App Store edition sells them.
TIP_WORDS = re.compile(
    r"(?i)\btips?\b|pourboire|propina|gorjeta|打赏|小费|إكرامي|टिप(?![\u0900-\u097F])|com\.markedoe\.ondo\.tip\.")
LIMITS = {"name.txt": 30, "subtitle.txt": 30, "keywords.txt": 100, "promotional_text.txt": 170,
          "description.txt": 4000, "release_notes.txt": 4000}
URL_FILES = ("support_url.txt", "privacy_url.txt", "marketing_url.txt")
LISTING_RULES = [
    ("names another platform (2.3.10)", re.compile(
        r"(?i)\b(?:android|google play|play store|windows phone|blackberry|tizen|symbian|sailfish|palm os|"
        r"fire tv|roku|appgallery)\b|安卓|أندرويد|एंड्रॉइड|एंड्रॉयड")),
    ("promises a feature to come (2.3.1)", re.compile(
        r"(?i)coming soon|available soon|in a (?:future|next) (?:version|update)|bientôt|prochainement|"
        r"prochaine (?:version|mise à jour)|\bà venir\b|próximamente|em breve|即将推出|敬请期待|قريبًا|قريبا|जल्द ही")),
    ("has test words (2.3)", re.compile(
        r"(?i)\bbeta\b|\bbêta\b|testflight|\btesting\b|test version|version de test|versión de prueba|"
        r"versão de teste|测试版|بيتا|बीटा")),
    ("has placeholder text (2.3)", re.compile(r"(?i)lorem ipsum|placeholder|(?-i:\bTODO\b)|\bTBD\b|\bxxx\b")),  # TODO in capitals only: "todo" is Spanish and Portuguese
    ("mentions downloads, which the App Store edition does not have (2.3.1)", re.compile(
        r"(?i)download|télécharg|\b(?:watch|watching|view|play)\s+offline|\boffline\s+(?:viewing|playback|mode)|"
        r"\b(?:regarder|voir|lecture)\s+hors[ -]ligne|descarga|\bbaixa[rd]|下载|تنزيل|تحميل|डाउनलोड")),
    ("names Addic7ed, a subtitle source without an official API (5.2.3)", re.compile(r"(?i)addic7ed|gestdown")),
    # Ondo is a player and provides no content: never "free" IPTV, channels or app (CLAUDE.md, Neutrality).
    ("calls the app or what it plays free (CLAUDE.md)", re.compile(
        r"(?i)\bfree\b|gratuit|\bgratis\b|grátis|免费|免費|مجان|मुफ़्त|मुफ्त|फ्री")),
]
# The review notes are written for the reviewer ("how to test"), but must not describe what the build lacks.
NOTES_RULES = [rule for rule in LISTING_RULES if not rule[0].startswith("has test words")]


def condition_value(expression: str, conditions: dict = EDITION):
    """True, False or None (not known here: os(), canImport()…) for a `#if` condition, by three-valued logic, from the
    compilation conditions given (the App Store edition's by default)."""
    tokens = re.findall(r"\|\||&&|!|\(|\)|[A-Za-z_][A-Za-z0-9_]*(?:\([^()]*\))?|\S", expression)
    position = 0

    def peek():
        return tokens[position] if position < len(tokens) else None

    def take():
        nonlocal position
        position += 1
        return tokens[position - 1]

    def either():
        value = both()
        while peek() == "||":
            take()
            right = both()
            value = True if value is True or right is True else (False if value is False and right is False else None)
        return value

    def both():
        value = unary()
        while peek() == "&&":
            take()
            right = unary()
            value = False if value is False or right is False else (True if value is True and right is True else None)
        return value

    def unary():
        if peek() == "!":
            take()
            value = unary()
            return None if value is None else not value
        if peek() == "(":
            take()
            value = either()
            if peek() == ")":
                take()
            return value
        token = take() if peek() is not None else ""
        return conditions.get(token)

    return either()


def edition_source(text: str, conditions: dict = EDITION) -> str:
    """The lines the App Store edition compiles (or a build with the conditions given): `#if` branches decided by its
    conditions are kept or dropped, the others (platform, imports) are all kept."""
    kept = []
    # Each open #if: (whether its enclosing code is compiled, whether a branch was surely taken, whether this branch is
    # compiled).
    stack = []
    for line in text.splitlines():
        directive = line.split("//", 1)[0].strip()
        enclosing = stack[-1][2] if stack else True
        if directive.startswith("#if"):
            value = condition_value(directive[3:], conditions)
            stack.append((enclosing, value is True, enclosing and value is not False))
            continue
        if directive.startswith("#elseif") and stack:
            outer, taken, _ = stack[-1]
            value = condition_value(directive[7:], conditions)
            stack[-1] = (outer, taken or value is True, outer and not taken and value is not False)
            continue
        if directive.startswith("#else") and stack:
            outer, taken, _ = stack[-1]
            stack[-1] = (outer, True, outer and not taken)
            continue
        if directive.startswith("#endif") and stack:
            stack.pop()
            continue
        if enclosing:
            kept.append(line)
    return "\n".join(kept)


def without_comments(text: str) -> str:
    return "\n".join(line.split("//", 1)[0] for line in text.splitlines())


def swift_files(*folders: str):
    for folder in folders:
        base = ROOT / folder
        if base.is_dir():
            yield from sorted(path for path in base.rglob("*.swift") if path.is_file())


def braced_body(text: str, start: int) -> str:
    """The text between the brace that opens at or after `start` and the one that closes it."""
    opening = text.index("{", start)
    depth = 0
    for index in range(opening, len(text)):
        depth += {"{": 1, "}": -1}.get(text[index], 0)
        if depth == 0:
            return text[opening + 1:index]
    return text[opening + 1:]


def check_beta(problems: list) -> None:
    """No edition has a beta badge or says "beta" (2.2): app strings, UI source and website, all editions."""
    sources = list(swift_files("App", "Packages/IPTVCore/Sources"))
    android = ROOT / "android"
    if android.is_dir():
        sources += sorted(path for path in android.rglob("*")
                          if path.is_file() and "build" not in path.relative_to(android).parts
                          and "src/main/" in path.as_posix() and path.suffix in (".kt", ".xml"))
    sources += sorted((ROOT / "App").rglob("*.xcstrings"))
    site = ROOT / "site"
    if site.is_dir():
        sources += sorted(path for path in site.rglob("*") if path.is_file() and path.suffix in (".html", ".js", ".json", ".css"))
    for path in sources:
        relative = path.relative_to(ROOT).as_posix()
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            if BETA_WORD.search(line) or BETA_CODE.search(line):
                problems.append(f"{relative}:{number} says or shows a beta, which no edition does (2.2): "
                                f"{line.strip()[:120]}")


def check_code(problems: list) -> None:
    for path in swift_files("App", "Packages/IPTVCore/Sources"):
        relative = path.relative_to(ROOT).as_posix()
        code = without_comments(edition_source(path.read_text(encoding="utf-8")))
        for line in code.splitlines():
            if PRIVATE_API.search(line):
                problems.append(f"{relative}: looks up or swaps code by name, or runs a script: {line.strip()}")
            if relative.startswith("App/") and DOWNLOAD_CODE.search(line):
                problems.append(f"{relative}: the App Store edition compiles download code: {line.strip()} "
                                "(put it under `#if TESTFLIGHT_DOWNLOADS || DEBUG`)")
            if LAUNCH_SWITCH.search(line):
                problems.append(f"{relative}: the App Store edition reads its launch arguments or environment: "
                                f"{line.strip()} (a switch an argument can turn is a hidden feature, 2.5.19: put it "
                                "under `#if DEBUG`)")
            if relative.startswith("App/") and DOWNLOAD_TEXT.search(line):
                problems.append(f"{relative}: the App Store edition speaks of downloads, which it does not have: "
                                f"{line.strip()} (2.3.1: put it under `#if TESTFLIGHT_DOWNLOADS || DEBUG`, or reword it)")
        match = re.search(r"\benum FeatureFlags\b", code)
        if match:
            flags = braced_body(code, match.end())
            for name in DORMANT_FLAG.findall(flags):
                problems.append(f"{relative}: FeatureFlags.{name} is off in the App Store edition: a feature nobody "
                                "can use is a dormant feature (2.5.19); leave its code out of the build instead")
            if RUNTIME_SWITCH.search(flags):
                problems.append(f"{relative}: FeatureFlags reads something at run time in the App Store edition: "
                                "a switch that settings, a server, a date or an argument can turn is a hidden feature")


def check_build_settings(problems: list) -> None:
    project = (ROOT / "project.yml").read_text(encoding="utf-8")
    if "TESTFLIGHT_DOWNLOADS" in project:
        problems.append("project.yml: TESTFLIGHT_DOWNLOADS would put downloads in every build, the App Store one "
                        "included; only the release lanes set it, for the TestFlight edition")
    release = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
    # A tag has no inputs: the expression must fall back to the App Store edition (a release run by hand builds it too).
    if not re.search(r"EDITION:\s*\$\{\{\s*(github\.event\.inputs\.target\s*==\s*'release'\s*&&\s*'app-store'\s*\|\|\s*)?"
                     r"github\.event\.inputs\.edition\s*\|\|\s*'app-store'\s*\}\}", release):
        problems.append(".github/workflows/release.yml: a tag must build the App Store edition "
                        "(EDITION: ${{ github.event.inputs.edition || 'app-store' }})")
    fastfile = (ROOT / "fastlane/Fastfile").read_text(encoding="utf-8")
    uses = [line for line in fastfile.splitlines() if "TESTFLIGHT_DOWNLOADS" in line and not line.strip().startswith("#")]
    if any("if testflight_edition?" not in line for line in uses) or \
            not re.search(r'def testflight_edition\?\s*\n\s*ENV\["EDITION"\] == "testflight"', fastfile):
        problems.append('fastlane/Fastfile: TESTFLIGHT_DOWNLOADS must be set only when EDITION is "testflight"')


def check_listing(problems: list) -> None:
    locales = sorted(path for path in METADATA.iterdir() if path.is_dir() and path.name != "review_information")
    if not locales:
        problems.append("fastlane/metadata: no listing")
    for locale in locales:
        for name, limit in LIMITS.items():
            path = locale / name
            if not path.is_file():
                problems.append(f"{path.relative_to(ROOT)} is missing")
                continue
            text = path.read_text(encoding="utf-8").strip()
            if not text:
                problems.append(f"{path.relative_to(ROOT)} is empty")
            if len(text) > limit:
                problems.append(f"{path.relative_to(ROOT)} has {len(text)} characters, App Store Connect takes {limit}")
        for name in URL_FILES:
            path = locale / name
            text = path.read_text(encoding="utf-8").strip() if path.is_file() else ""
            if not text.startswith("https://"):
                problems.append(f"{path.relative_to(ROOT)} must hold an https address")
        for path in sorted(locale.glob("*.txt")):
            for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
                for reason, pattern in LISTING_RULES:
                    if pattern.search(line):
                        problems.append(f"{path.relative_to(ROOT)}:{number} {reason}: {line.strip()[:120]}")

    copyright_path = METADATA / "copyright.txt"
    copyright_text = copyright_path.read_text(encoding="utf-8").strip() if copyright_path.is_file() else ""
    year = str(datetime.datetime.now(datetime.timezone.utc).year)
    if year not in copyright_text:
        problems.append(f"fastlane/metadata/copyright.txt must name this year ({year}): \"{copyright_text}\"")
    shown = re.search(r"NSHumanReadableCopyright:\s*(.+)", (ROOT / "project.yml").read_text(encoding="utf-8"))
    if shown and shown.group(1).strip().strip('"') != copyright_text:
        problems.append("project.yml: NSHumanReadableCopyright must be the listing's copyright (copyright.txt)")


PLAY_METADATA = ROOT / "fastlane/metadata-android"
PLAY_LIMITS = {"title.txt": 30, "short_description.txt": 80, "full_description.txt": 4000}
# The Google Play listing follows the same neutrality and test-word rules; it names Google's devices, not Apple's.
PLAY_RULES = [rule for rule in LISTING_RULES if not rule[0].startswith(("names another platform", "mentions downloads"))]
PLAY_RULES.append(("names another platform", re.compile(
    r"(?i)\b(?:iphone|ipad|ipados|macos|tvos|apple|app store|airplay|testflight)\b|\bmac\b")))


def check_play_listing(problems: list) -> None:
    """Every language of the Google Play listing: its files, Google Play's limits and the same neutral words."""
    if not PLAY_METADATA.is_dir():
        return
    for locale in sorted(path for path in PLAY_METADATA.iterdir() if path.is_dir()):
        files = [(locale / name, limit) for name, limit in PLAY_LIMITS.items()]
        files += [(locale / "changelogs/default.txt", 500)]
        for path, limit in files:
            if not path.is_file():
                problems.append(f"{path.relative_to(ROOT)} is missing")
                continue
            text = path.read_text(encoding="utf-8").strip()
            if not text:
                problems.append(f"{path.relative_to(ROOT)} is empty")
            if len(text) > limit:
                problems.append(f"{path.relative_to(ROOT)} has {len(text)} characters, Google Play takes {limit}")
            for number, line in enumerate(text.splitlines(), start=1):
                for reason, pattern in PLAY_RULES:
                    if pattern.search(line):
                        problems.append(f"{path.relative_to(ROOT)}:{number} {reason}: {line.strip()[:120]}")


def check_site(problems: list) -> None:
    """The pages of site/ that the listing's addresses open: App Review visits them."""
    test_words = [pattern for reason, pattern in LISTING_RULES if reason.startswith("has test words")]
    pages = set()
    for path in METADATA.glob("*/*_url.txt"):
        address = path.read_text(encoding="utf-8").strip()
        name = address.split("?", 1)[0].split("#", 1)[0].rsplit("/", 1)[-1] or "index.html"
        page = ROOT / "site" / name
        if page.is_file():
            pages.add(page)
    for page in sorted(pages):
        text = re.sub(r"<[^>]+>", " ", page.read_text(encoding="utf-8"))
        for number, line in enumerate(text.splitlines(), start=1):
            if any(pattern.search(line) for pattern in test_words):
                problems.append(f"{page.relative_to(ROOT)}:{number} has test words, and the App Store listing opens "
                                f"this page (2.2): {line.strip()[:120]}")


def background_modes(project: str) -> list:
    """The UIBackgroundModes of project.yml, written inline (`[audio, fetch]`, `audio`) or as a YAML block list."""
    return yaml_list(project, "UIBackgroundModes")


def yaml_list(project: str, key: str) -> list:
    """The values of a list key of project.yml, written inline (`[audio, fetch]`, `audio`) or as a YAML block list."""
    modes = []
    for match in re.finditer(rf"^([ \t]*){re.escape(key)}:[ \t]*([^\n#]*)", project, re.MULTILINE):
        indent, value = match.group(1), match.group(2).strip()
        if value.startswith("["):
            modes += [mode.strip().strip("'\"") for mode in value.strip("[]").split(",")]
        elif value:
            modes.append(value.strip("'\""))
        else:
            # The items that follow, each `- mode` on its own line, no less indented than the key.
            for line in project[match.end():].splitlines()[1:]:
                item = re.match(rf"{indent}[ \t]*-[ \t]*([^#\n]+)", line)
                if item:
                    modes.append(item.group(1).strip().strip("'\""))
                elif line.strip() and not line.strip().startswith("#"):
                    break
    return [mode for mode in modes if mode]


def tip_cases(app_code: str) -> list:
    """The cases of `enum Tip` (App/Model/Tips.swift): each is the product com.markedoe.ondo.tip.<case>."""
    body = re.search(r"enum Tip\b[^{]*\{(.*?)\n\}", app_code, re.DOTALL)
    if not body:
        return []
    cases = re.findall(r"^\s*case\s+([^\n]+)", body.group(1), re.MULTILINE)
    return [name.strip() for line in cases for name in line.split(",") if re.fullmatch(r"\s*\w+\s*", name)]


def check_review_notes(problems: list) -> None:
    if not NOTES.is_file():
        problems.append("fastlane/metadata/review_information/notes.txt is missing")
        return
    notes = NOTES.read_text(encoding="utf-8")
    if len(notes.strip()) > 4000:
        problems.append(f"the review notes have {len(notes.strip())} characters, App Store Connect takes 4000")
    for number, line in enumerate(notes.splitlines(), start=1):
        for reason, pattern in NOTES_RULES:
            if pattern.search(line):
                problems.append(f"fastlane/metadata/review_information/notes.txt:{number} {reason}: {line.strip()[:120]}")

    project = (ROOT / "project.yml").read_text(encoding="utf-8")
    asked = set(re.findall(r"^\s*(NS\w+UsageDescription):", project, re.MULTILINE))
    asked |= set(re.findall(r"^\s*(NSAllows\w+):\s*true", project, re.MULTILINE))
    asked |= {f"UIBackgroundModes {mode}" for mode in background_modes(project)}
    if ENTITLEMENTS.is_file():
        entitlements = plistlib.loads(ENTITLEMENTS.read_bytes())
        asked |= {key for key, value in entitlements.items() if value and key != "com.apple.security.app-sandbox"}
    app_code = "\n".join(without_comments(edition_source(path.read_text(encoding="utf-8"))) for path in swift_files("App"))
    if "UNUserNotificationCenter" in app_code:
        asked.add("Notifications")
    # In-App Purchases (the tips): the notes name each product and say what it unlocks (nothing), 3.1.1.
    if re.search(r"^\s*import StoreKit\b", app_code, re.MULTILINE):
        asked |= {"In-App Purchase", "unlocks nothing"}
        asked |= {f"com.markedoe.ondo.tip.{case}" for case in tip_cases(app_code)}
    for item in sorted(asked):
        if item not in notes:
            problems.append(f"the review notes do not explain {item} (fastlane/metadata/review_information/notes.txt)")


def check_tips_wording(problems: list) -> None:
    """Without StoreKit in its build, the App Store edition has no tips: the listing and the notes must not offer them."""
    app_code = "\n".join(without_comments(edition_source(path.read_text(encoding="utf-8"))) for path in swift_files("App"))
    if re.search(r"^\s*import StoreKit\b", app_code, re.MULTILINE):
        return
    files = sorted(METADATA.glob("*/*.txt"))
    for path in files:
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            if TIP_WORDS.search(line):
                problems.append(f"{path.relative_to(ROOT)}:{number} speaks of tips, which the App Store edition does "
                                f"not have (2.3.1): {line.strip()[:120]}")


def check_payments(problems: list) -> None:
    sources = list(swift_files("App"))
    android = ROOT / "android/app/src/main"
    if android.is_dir():
        sources += sorted(path for path in android.rglob("*") if path.suffix in (".kt", ".xml") and path.is_file())
    pages = set()
    for path in sources:
        text = path.read_text(encoding="utf-8")
        if path.suffix == ".swift":
            text = edition_source(text)
        relative = path.relative_to(ROOT).as_posix()
        for line in text.splitlines():
            if DONATION_LINK.search(line):
                problems.append(f"{relative}: the apps link to a way to pay outside the stores (3.1.1): {line.strip()[:120]}")
        pages |= {page or "index.html" for page in SITE_PAGE.findall(text)}
    for page in sorted(pages):
        path = ROOT / "site" / page
        if not path.is_file():
            continue
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            if DONATION_LINK.search(line) or DONATION_WORD.search(re.sub(r"<[^>]+>", " ", line)):
                problems.append(f"site/{page}:{number}: the apps open this page, so it must not ask for donations: "
                                f"{line.strip()[:120]}")
            # Nor lead to one that does (the home page): the way to pay would be a tap away from the apps.
            for target in SITE_LINK.findall(line):
                linked = path.parent / target
                if not target or target.endswith("/") or linked.is_dir():
                    linked = linked / "index.html"
                if linked.suffix == ".html" and linked.is_file() and DONATION_LINK.search(linked.read_text(encoding="utf-8")):
                    problems.append(f"site/{page}:{number}: the apps open this page, so it must not link to "
                                    f"{target or './'}, which asks for donations")
    if (ROOT / ".github/FUNDING.yml").exists():
        problems.append(".github/FUNDING.yml: its Sponsor button shows on the GitHub issue form the apps open")


def check_local_network(problems: list) -> None:
    project = (ROOT / "project.yml").read_text(encoding="utf-8")
    declared = set(yaml_list(project, "NSBonjourServices"))
    notes = NOTES.read_text(encoding="utf-8") if NOTES.is_file() else ""
    browsed = set()
    for path in swift_files("App", "Packages/IPTVCore/Sources"):
        text = path.read_text(encoding="utf-8")
        relative = path.relative_to(ROOT).as_posix()
        browsed |= set(BONJOUR_SERVICE.findall(without_comments(edition_source(text))))
        if relative.startswith("App/") and re.search(r"\bNWListener\b", without_comments(edition_source(text, MAC_EDITION))):
            problems.append(f"{relative}: the Mac App Store edition would listen for connections (NWListener): it has no "
                            "com.apple.security.network.server entitlement (App Review refused it, 2.4.5(i)); keep "
                            "listeners in iPhone and iPad code (`#if os(iOS)`) and make the Mac a client")
    for service in sorted(browsed):
        if service not in declared:
            problems.append(f"project.yml: NSBonjourServices lacks {service}, which the app browses: iOS finds nothing "
                            "without it")
        if service not in notes:
            problems.append(f"the review notes do not explain the Bonjour service {service} "
                            "(fastlane/metadata/review_information/notes.txt)")
    if SYNC_SERVICE in browsed:
        description = re.search(r"NSLocalNetworkUsageDescription:\s*(.+)", project)
        if not description or not re.search(r"(?i)\bsync", description.group(1)):
            problems.append("project.yml: NSLocalNetworkUsageDescription must say that Ondo looks for its other devices "
                            f"to sync (the app browses {SYNC_SERVICE}), not only for Chromecast and AirPlay (5.1.1)")


def foreign_playlists(text: str) -> list:
    """The playlist addresses of a text that are neither on our own site nor placeholders."""
    found = []
    for match in PLAYLIST_ADDRESS.finditer(text):
        if OWN_PLAYLIST.fullmatch(match.group(0)) or PLACEHOLDER_HOST.search(match.group(1)):
            continue
        found.append(match.group(0))
    return found


def check_playlists(problems: list) -> None:
    # Comments are left out, but not the "//" of an address.
    sources = [(path, re.sub(r"(?<!:)//.*", "", edition_source(path.read_text(encoding="utf-8"))))
               for path in swift_files("App")]
    for folder in ("android/app/src/main", "android/core/src/main"):
        base = ROOT / folder
        if base.is_dir():
            sources += [(path, path.read_text(encoding="utf-8")) for path in sorted(base.rglob("*"))
                        if path.suffix in (".kt", ".xml") and path.is_file()]
    notes = NOTES.read_text(encoding="utf-8") if NOTES.is_file() else ""
    sources.append((NOTES, notes))
    for path, text in sources:
        relative = path.relative_to(ROOT).as_posix()
        for address in foreign_playlists(text):
            problems.append(f"{relative}: {address} is a playlist the apps or the review notes would point to, not on "
                            "our own site: the apps ship no demo, and App Review tests with site/review (5.2.3)")
        if re.search(r"(?i)iptv-org", text):
            problems.append(f"{relative}: names a public channel list (iptv-org): the apps ship no third-party channel")
    if notes and not OWN_PLAYLIST.search(notes):
        problems.append("the review notes do not give the test playlist hosted on our site "
                        "(https://<user>.github.io/ondo-iptv/review/ondo-review.m3u)")


# What gives a build to testers outside the team or sends it to Beta App Review (fastlane/Fastfile).
EXTERNAL_WORDS = re.compile(
    r"\bdistribute_external\b|\bsubmit_beta_review\b|\bbeta_app_review_info\b|\bpost_beta_app_review_submissions?\b|"
    r"\badd_beta_groups(?:_to_build)?\b|\bcreate_beta_group\b|\bpatch_beta_app_review_detail\b|\bgroups:|"
    r"\blocalized_app_info\b|\bnotify_external_testers\b|\bbeta_app_feedback_email\b|\bpilot\b|\bupload_to_testflight\b")
# The functions allowed to use them: the external distribution and its helpers. upload_build is the internal upload,
# allowed upload_to_testflight only (checked below: it submits nothing).
EXTERNAL_FUNCTIONS = {"distribute_to_external_testers", "external_group", "beta_review_contact"}
EXTERNAL_STEPS = {"ios": "ios_app_store", "mac": "mac_app_store"}


def ruby_functions(text: str) -> dict:
    """The top-level `def name … end` of a Fastfile: name → its body's code lines as (line number, code), comments left
    out."""
    functions, name, body = {}, None, []
    for number, line in enumerate(text.splitlines(), start=1):
        if name is None:
            match = re.match(r"def ([\w?!]+)", line)
            if match:
                name, body = match.group(1), []
            continue
        if line.startswith("end"):
            functions[name] = body
            name = None
            continue
        code = line.split(" # ", 1)[0].rstrip()
        if code.strip() and not code.strip().startswith("#"):
            body.append((number, code))
    return functions


def workflow_steps(text: str) -> list:
    """Each step of a workflow, as its text: from its `- ` line to the next line indented as much or less."""
    lines = text.splitlines()
    steps = []
    for index, line in enumerate(lines):
        match = re.match(r"(\s*)- (?:name|uses|id|if|run|env|with):", line)
        if not match:
            continue
        indent = len(match.group(1))
        block = [line]
        for following in lines[index + 1:]:
            if following.strip() and len(following) - len(following.lstrip()) <= indent:
                break
            block.append(following)
        steps.append("\n".join(block))
    return steps


def check_external_testers(problems: list) -> None:
    fastfile_path = ROOT / "fastlane/Fastfile"
    fastfile = fastfile_path.read_text(encoding="utf-8") if fastfile_path.is_file() else ""
    functions = ruby_functions(fastfile)
    allowed = {number for name in EXTERNAL_FUNCTIONS for number, _ in functions.get(name, [])}
    upload = functions.get("upload_build", [])
    # upload_build may call upload_to_testflight (the internal upload), nothing else of the external words.
    allowed |= {number for number, code in upload
                if [word.group(0) for word in EXTERNAL_WORDS.finditer(code)] == ["upload_to_testflight"]}
    for number, line in enumerate(fastfile.splitlines(), start=1):
        code = line.split(" # ", 1)[0]
        if code.strip().startswith("#") or not EXTERNAL_WORDS.search(code) or number in allowed:
            continue
        problems.append(f"fastlane/Fastfile:{number}: gives a build to external testers or Beta App Review outside "
                        f"distribute_to_external_testers, which takes the App Store edition only: {line.strip()[:120]}")
    if upload and not any(re.search(r"\bskip_submission:\s*true\b", code) for _, code in upload):
        problems.append("fastlane/Fastfile: upload_build (the TestFlight upload) must keep skip_submission: true: it "
                        "distributes nothing and submits nothing for Beta App Review")
    # What to Test, set on every build of both editions: it writes the build's localizations and nothing else.
    for number, code in functions.get("fill_what_to_test", []):
        for write in re.findall(r"\b(?:patch|post|delete)_\w+|\.(?:expire!|add_beta_groups\b|post_beta_app_review_submissions?\b)", code):
            if write not in ("patch_beta_build_localizations", "post_beta_build_localizations"):
                problems.append(f"fastlane/Fastfile:{number}: fill_what_to_test runs on the TestFlight edition too: it may "
                                f"only set What to Test (beta build localizations), not {write}")
    distribute = [code for _, code in functions.get("distribute_to_external_testers", [])]
    if "distribute_to_external_testers" in functions:
        if not distribute or not re.fullmatch(r'\s*UI\.user_error!\(.*\) unless app_store_edition\?', distribute[0]):
            problems.append("fastlane/Fastfile: distribute_to_external_testers must first refuse any edition but the App "
                            "Store one (UI.user_error!(…) unless app_store_edition?)")
        if [code.strip() for _, code in functions.get("app_store_edition?", [])] != ['ENV["EDITION"] == "app-store"']:
            problems.append('fastlane/Fastfile: app_store_edition? must be ENV["EDITION"] == "app-store"')
        if not any(re.search(r"\bdistribute_only:\s*true\b", code) for code in distribute):
            problems.append("fastlane/Fastfile: distribute_to_external_testers must only distribute the App Store edition "
                            "build given by number (distribute_only: true), never build or upload one")
    elif re.search(r"\bexternal_testers\b", fastfile):
        problems.append("fastlane/Fastfile: the external_testers lanes must go through distribute_to_external_testers")

    workflows = ROOT / ".github/workflows"
    for path in sorted(workflows.glob("*.y*ml")) if workflows.is_dir() else []:
        text = path.read_text(encoding="utf-8")
        relative = path.relative_to(ROOT).as_posix()
        if re.search(r"\bfastlane\s+(?:run\s+)?(?:pilot|upload_to_testflight)\b", text):
            problems.append(f"{relative}: runs pilot or upload_to_testflight directly: only the Fastfile's lanes upload "
                            "and distribute builds")
        for step in workflow_steps(text):
            name = (re.search(r"- name:\s*(.+)", step) or re.search(r"- (.+)", step)).group(1).strip()
            step_id = re.search(r"^\s*(?:- )?id:\s*(\S+)", step, re.MULTILINE)
            step_id = step_id.group(1) if step_id else None
            edition = re.search(r"^\s*EDITION:\s*(\S+)\s*$", step, re.MULTILINE)
            edition = edition.group(1) if edition else None
            for platform in re.findall(r"\bfastlane\s+(ios|mac)\s+external_testers\b", step):
                if edition != "app-store":
                    problems.append(f"{relative}: step \"{name}\" sends a build to external testers without "
                                    "EDITION: app-store: only the App Store edition goes there")
                build = re.search(r"^\s*BUILD_NUMBER:\s*(.+?)\s*$", step, re.MULTILINE)
                expected = f"${{{{ needs.deliver.outputs.{platform}_app_store_build }}}}"
                if not build or build.group(1) != expected:
                    problems.append(f"{relative}: step \"{name}\" must take BUILD_NUMBER from the App Store edition build "
                                    f"({expected}), never another build")
            for platform, expected_id in EXTERNAL_STEPS.items():
                if step_id != expected_id:
                    continue
                if edition != "app-store" or not re.search(rf"^\s*run:\s*bundle exec fastlane {platform} beta\s*$", step, re.MULTILINE):
                    problems.append(f"{relative}: step {expected_id} builds what external testers get: it must run "
                                    f"`fastlane {platform} beta` with EDITION: app-store")
        if re.search(r"\bexternal_testers\b", text):
            for platform, expected_id in EXTERNAL_STEPS.items():
                output = f"{platform}_app_store_build: ${{{{ steps.{expected_id}.outputs.build }}}}"
                if output not in text:
                    problems.append(f"{relative}: the external testers' {platform} build must be the output of step "
                                    f"{expected_id}, the App Store edition ({output})")


def main() -> int:
    problems = []
    check_code(problems)
    check_beta(problems)
    check_build_settings(problems)
    check_listing(problems)
    check_play_listing(problems)
    check_site(problems)
    check_review_notes(problems)
    check_tips_wording(problems)
    check_payments(problems)
    check_local_network(problems)
    check_playlists(problems)
    check_external_testers(problems)
    for problem in problems:
        print(f"::error::{problem}")
    print(f"App Store edition: {len(problems)} problem(s).")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
