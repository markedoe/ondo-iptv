#!/usr/bin/env python3
"""What Google Play shows of the Android app, read only (.github/workflows/play-status.yml).

Reads with the service account of the GitHub secret PLAY_SERVICE_ACCOUNT_JSON:
- Google Play Developer API, inside an edit that is opened, read and deleted, never committed (a commit could replace
  changes waiting for review): the tracks and their releases, the bundles Play holds, the store listings and their
  images (TV screenshots and banner included), the testers' Google Groups and the countries of each track; then the
  reviews of the last week and the APKs Play generated from the latest bundle.
- Google Play Developer Reporting API: the releases each track serves, crashes and ANRs (issues and a few reports).
No API shows the review of changes, the Android TV review and opt-in or the device catalog: the Play Console and
Google's e-mails only.

The repository is public and so are its logs: the output holds no e-mail address, web address, IP address or long
number (Google's error messages carry Cloud project numbers), no review text or author, no exception message (a
playlist address could be in one); an API error shows its code and reasons only. Standard library only; the token is
signed with openssl. See docs/RELEASE-ANDROID.md.
"""
import base64
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

PACKAGE = "com.markedoe.ondo"
TOKEN_URI = "https://oauth2.googleapis.com/token"
SCOPES = (
    "https://www.googleapis.com/auth/androidpublisher",
    "https://www.googleapis.com/auth/playdeveloperreporting",
)
PUBLISHER = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/"
REPORTING = "https://playdeveloperreporting.googleapis.com/v1beta1/"
IMAGE_TYPES = (
    "icon", "featureGraphic", "phoneScreenshots", "sevenInchScreenshots", "tenInchScreenshots",
    "tvBanner", "tvScreenshots", "wearScreenshots",
)

EMAIL = re.compile(r"[\w.+-]+@[\w-]+(?:\.[\w-]+)+")
WEB_ADDRESS = re.compile(r"\b[A-Za-z][A-Za-z0-9+.-]*://[^\s\"'<>]+")
IP_ADDRESS = re.compile(r"\b\d{1,3}(?:\.\d{1,3}){3}\b|\b[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{0,4}){3,7}\b")
LONG_NUMBER = re.compile(r"\d{7,}")
SECRET_PARAMETER = re.compile(r"(?i)\b(username|password|passwd|token|key|auth)=[^\s&]+")


def safe(text: str) -> str:
    """A line fit for a public log: no e-mail, web or IP address, credential parameter or long number."""
    text = WEB_ADDRESS.sub("<address>", str(text))  # first: a user:password@host address is not an e-mail
    text = EMAIL.sub("<e-mail>", text)
    text = SECRET_PARAMETER.sub(r"\1=<hidden>", text)
    text = IP_ADDRESS.sub("<ip>", text)
    return LONG_NUMBER.sub("<number>", text)


def exception_name(text: str) -> str:
    """An exception's class without its message ("java.io.IOException: http://…" → "java.io.IOException")."""
    return (text or "").split(":", 1)[0].strip()


def stack_lines(report: str, limit: int = 12) -> list:
    """The frames of a crash report and the class of each exception, never the messages."""
    lines = []
    for line in (report or "").splitlines():
        stripped = line.strip()
        if stripped.startswith("at "):
            lines.append("    " + stripped)
        elif stripped.startswith("Caused by:"):
            lines.append("  Caused by: " + exception_name(stripped[len("Caused by:"):]))
        elif stripped and not lines:
            lines.append("  " + exception_name(stripped))
        if len(lines) >= limit:
            break
    return lines


# Token: a service-account JWT (RS256) exchanged at Google's token endpoint.

def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def openssl_signer(private_key: str):
    """Signs with the key written for a moment to a file only this user can read, removed right after."""
    def sign(data: bytes) -> bytes:
        with tempfile.TemporaryDirectory(dir=os.environ.get("RUNNER_TEMP")) as folder:
            path = Path(folder) / "key.pem"
            descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            try:
                with os.fdopen(descriptor, "w") as file:
                    file.write(private_key)
                result = subprocess.run(
                    ["openssl", "dgst", "-sha256", "-sign", str(path)], input=data, capture_output=True, check=False,
                )
            finally:
                path.unlink(missing_ok=True)
        if result.returncode != 0 or not result.stdout:
            raise RuntimeError("openssl could not sign with the service account key")
        return result.stdout
    return sign


def assertion(account: dict, scopes, now: int, sign) -> str:
    header = {"alg": "RS256", "typ": "JWT"}
    claims = {
        "iss": account["client_email"],
        "scope": " ".join(scopes),
        "aud": account.get("token_uri") or TOKEN_URI,
        "iat": now,
        "exp": now + 3600,
    }
    signing_input = ".".join(b64url(json.dumps(part, separators=(",", ":")).encode()) for part in (header, claims))
    return signing_input + "." + b64url(sign(signing_input.encode()))


class ApiError(Exception):
    """An API error by its code and reasons: Google's messages may name the Cloud project or the account."""

    def __init__(self, code: int, status: str = "", reasons=()):
        super().__init__(code, status, reasons)
        self.code, self.status, self.reasons = code, status, tuple(reasons)

    def __str__(self) -> str:
        parts = [f"HTTP {self.code}", self.status, *self.reasons]
        return safe(" ".join(part for part in parts if part))

    @classmethod
    def from_body(cls, code: int, body: bytes) -> "ApiError":
        try:
            error = json.loads(body or b"{}")
        except ValueError:
            return cls(code)
        if isinstance(error.get("error"), str):  # the token endpoint: {"error": "invalid_grant", ...}
            return cls(code, error["error"])
        error = error.get("error") or {}
        reasons = [item.get("reason", "") for item in error.get("errors", []) + error.get("details", [])]
        return cls(code, error.get("status", ""), [reason for reason in dict.fromkeys(reasons) if reason])


def send(request: urllib.request.Request) -> dict:
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            body = response.read()
    except urllib.error.HTTPError as error:
        raise ApiError.from_body(error.code, error.read()) from None
    return json.loads(body) if body.strip() else {}


def access_token(account: dict, now=None, sign=None) -> str:
    now = int(now if now is not None else time.time())
    sign = sign or openssl_signer(account["private_key"])
    body = urllib.parse.urlencode({
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
        "assertion": assertion(account, SCOPES, now, sign),
    }).encode()
    request = urllib.request.Request(account.get("token_uri") or TOKEN_URI, data=body, method="POST")
    request.add_header("Content-Type", "application/x-www-form-urlencoded")
    return send(request)["access_token"]


class Client:
    def __init__(self, token: str):
        self.token = token

    def call(self, method: str, url: str, params=None, body=None) -> dict:
        if params:
            url += "?" + urllib.parse.urlencode(params)
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(url, data=data, method=method)
        request.add_header("Authorization", "Bearer " + self.token)
        if data is not None:
            request.add_header("Content-Type", "application/json")
        return send(request)


class Report:
    """The lines to print, each made safe for a public log."""

    def __init__(self):
        self.lines = []

    def add(self, line: str = "") -> None:
        self.lines.append(safe(line))

    def title(self, text: str) -> None:
        if self.lines:
            self.add()
        self.add("== " + text)

    def attempt(self, label: str, read):
        """Runs one read; a refusal is reported and the others go on."""
        try:
            return read()
        except ApiError as error:
            self.add(f"{label}: unavailable ({error})")
        except (urllib.error.URLError, TimeoutError, ValueError, KeyError) as error:
            self.add(f"{label}: unavailable ({type(error).__name__})")
        return None


def describe_release(release: dict) -> str:
    codes = ", ".join(release.get("versionCodes", [])) or "none"
    parts = [f"{release.get('name') or '(no name)'}: versionCodes {codes}, {release.get('status', '?')}"]
    if "userFraction" in release:
        parts.append(f"{float(release['userFraction']):.0%} of users")
    languages = sorted(note.get("language", "") for note in release.get("releaseNotes", []))
    if languages:
        parts.append("notes " + "/".join(languages))
    countries = release.get("countryTargeting", {}).get("countries")
    if countries:
        parts.append(f"{len(countries)} countries")
    return ", ".join(parts)


def publisher(client, report: Report, package: str = PACKAGE) -> list:
    """Reads inside an edit that is always deleted, never committed. Returns the version codes of the tracks."""
    report.title("Google Play Developer API (inside an edit opened, read and deleted)")
    edit = report.attempt("Edit", lambda: client.call("POST", f"{PUBLISHER}{package}/edits", body={}))
    if not edit:
        return []
    base = f"{PUBLISHER}{package}/edits/{edit['id']}"
    codes = []
    try:
        details = report.attempt("Details", lambda: client.call("GET", base + "/details"))
        if details is not None:
            report.add(
                f"Details: default language {details.get('defaultLanguage', '?')}, contact e-mail "
                f"{'set' if details.get('contactEmail') else 'not set'}, website "
                f"{'set' if details.get('contactWebsite') else 'not set'}",
            )
        tracks = report.attempt("Tracks", lambda: client.call("GET", base + "/tracks").get("tracks", [])) or []
        names = []
        for track in tracks:
            names.append(track.get("track", "?"))
            releases = track.get("releases", [])
            report.add(f"Track {names[-1]}: {len(releases)} release(s)")
            for release in releases:
                report.add("  " + describe_release(release))
                codes += [int(code) for code in release.get("versionCodes", []) if str(code).isdigit()]
        bundles = report.attempt("Bundles", lambda: client.call("GET", base + "/bundles").get("bundles", []))
        if bundles is not None:
            held = sorted(int(bundle["versionCode"]) for bundle in bundles if "versionCode" in bundle)
            report.add(f"Bundles held by Play: {', '.join(map(str, held)) or 'none'}")
        listings = report.attempt("Listings", lambda: client.call("GET", base + "/listings").get("listings", []))
        for listing in listings or []:
            language = listing.get("language", "?")
            report.add(
                f"Listing {language}: title {len(listing.get('title', ''))} characters, short description "
                f"{len(listing.get('shortDescription', ''))}, full description {len(listing.get('fullDescription', ''))}",
            )
            counts = []
            for kind in IMAGE_TYPES:
                images = report.attempt(
                    f"  {kind}", lambda kind=kind: client.call("GET", f"{base}/listings/{language}/{kind}").get("images", []),
                )
                if images is not None:
                    counts.append(f"{kind} {len(images)}")
            report.add("  images: " + ", ".join(counts))
        for name in names:
            testers = report.attempt(
                f"Testers of {name}", lambda name=name: client.call("GET", f"{base}/testers/{urllib.parse.quote(name)}"),
            )
            if testers is not None:
                report.add(f"Testers of {name}: {len(testers.get('googleGroups', []))} Google Group(s)")
            countries = report.attempt(
                f"Countries of {name}",
                lambda name=name: client.call("GET", f"{base}/countryAvailability/{urllib.parse.quote(name)}"),
            )
            if countries is not None:
                rest = ", rest of the world" if countries.get("restOfWorld") else ""
                report.add(f"Countries of {name}: {len(countries.get('countries', []))}{rest}")
    finally:
        # Deleting only discards this draft; the Play Console's changes and their review are left as they are.
        report.attempt("Closing the edit", lambda: client.call("DELETE", base))
    return codes


def reviews(client, report: Report, package: str = PACKAGE) -> None:
    report.title("Reviews (the last week's, with a comment; no text or author shown)")
    found = report.attempt("Reviews", lambda: client.call("GET", f"{PUBLISHER}{package}/reviews", {"maxResults": 100}))
    if found is None:
        return
    stars, versions = {}, set()
    for review in found.get("reviews", []):
        for comment in review.get("comments", []):
            user = comment.get("userComment")
            if user:
                stars[user.get("starRating", 0)] = stars.get(user.get("starRating", 0), 0) + 1
                if user.get("appVersionCode"):
                    versions.add(int(user["appVersionCode"]))
    total = sum(stars.values())
    detail = ", ".join(f"{star}★ {count}" for star, count in sorted(stars.items(), reverse=True))
    report.add(f"Reviews: {total}" + (f" ({detail}; versionCodes {sorted(versions)})" if total else ""))


def generated(client, report: Report, version_code: int, package: str = PACKAGE) -> None:
    report.title(f"APKs Play generated from bundle {version_code}")
    found = report.attempt(
        "Generated APKs", lambda: client.call("GET", f"{PUBLISHER}{package}/generatedApks/{version_code}"),
    )
    for item in (found or {}).get("generatedApks", []):
        report.add(
            f"Signing key set: {len(item.get('generatedSplitApks', []))} split APK(s), "
            f"{len(item.get('generatedStandaloneApks', []))} standalone APK(s), universal APK "
            f"{'yes' if item.get('generatedUniversalApk') else 'no'}",
        )


def reporting(client, report: Report, package: str = PACKAGE) -> None:
    report.title("Google Play Developer Reporting API")
    apps = report.attempt("Apps", lambda: client.call("GET", REPORTING + "apps:search", {"pageSize": 50}))
    if apps is None:
        report.add("  (if the reason is SERVICE_DISABLED: Google Cloud console of the service account → APIs & "
                   "Services → enable \"Google Play Developer Reporting API\")")
        return
    listed = [app.get("packageName") for app in apps.get("apps", [])]
    report.add(f"Apps visible: {len(listed)}, {package} {'among them' if package in listed else 'not among them'}")
    options = report.attempt(
        "Releases served", lambda: client.call("GET", f"{REPORTING}apps/{package}:fetchReleaseFilterOptions"),
    )
    for track in (options or {}).get("tracks", []):
        served = "; ".join(
            f"{release.get('displayName', '?')} ({', '.join(map(str, release.get('versionCodes', [])))})"
            for release in track.get("servingReleases", [])
        )
        report.add(f"Track {track.get('displayName', '?')} ({track.get('type', '?')}): {served or 'nothing served'}")
    issues = report.attempt(
        "Crashes and ANRs",
        lambda: client.call("GET", f"{REPORTING}apps/{package}/errorIssues:search", {"pageSize": 20}),
    )
    if issues is not None:
        found = issues.get("errorIssues", [])
        report.add(f"Crash and ANR issues: {len(found)}")
        for issue in found:
            versions = f"{issue.get('firstAppVersion', {}).get('versionCode', '?')}–{issue.get('lastAppVersion', {}).get('versionCode', '?')}"
            report.add(
                f"  {issue.get('type', '?')} {exception_name(issue.get('cause', ''))} at {issue.get('location', '?')}: "
                f"{issue.get('errorReportCount', '?')} report(s), {issue.get('distinctUsers', '?')} user(s), versions {versions}",
            )
    samples = report.attempt(
        "Crash reports",
        lambda: client.call("GET", f"{REPORTING}apps/{package}/errorReports:search", {"pageSize": 5}),
    )
    for sample in (samples or {}).get("errorReports", []):
        device = sample.get("deviceModel", {}).get("marketingName", "?")
        api = sample.get("osVersion", {}).get("apiLevel", "?")
        version = sample.get("appVersion", {}).get("versionCode", "?")
        report.add(f"Report {sample.get('type', '?')}, version {version}, {device}, Android API {api}:")
        for line in stack_lines(sample.get("reportText", "")):
            report.add(line)


def main() -> int:
    raw = os.environ.get("PLAY_SERVICE_ACCOUNT_JSON", "")
    if not raw.strip():
        print("::error::The secret PLAY_SERVICE_ACCOUNT_JSON is not set")
        return 1
    try:
        account = json.loads(raw)
        token = access_token(account)
    except (ValueError, KeyError):
        print("::error::PLAY_SERVICE_ACCOUNT_JSON is not a service account key (JSON with client_email and private_key)")
        return 1
    except (ApiError, RuntimeError, urllib.error.URLError) as error:
        print(safe(f"::error::No Google token: {error}"))
        return 1
    client = Client(token)
    report = Report()
    report.add("Not shown by any API: the review of changes, the Android TV review and opt-in, the device catalog "
               "(Play Console and Google's e-mails).")
    codes = publisher(client, report)
    reviews(client, report)
    if codes:
        generated(client, report, max(codes))
    reporting(client, report)
    text = "\n".join(report.lines)
    print(text)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as file:
            file.write("## Google Play (read only)\n\n```\n" + text + "\n```\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
