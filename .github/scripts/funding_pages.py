#!/usr/bin/env python3
"""Support pages on Stripe (Payment Links), one set per app, described in funding/stripe.json.

Usage (.github/workflows/funding-pages.yml, with a Stripe restricted key in the GitHub secret STRIPE_API_KEY):
  funding_pages.py plan    what would change on Stripe, changing nothing
  funding_pages.py apply   creates or updates, for each project, page and language: a product (the app's name and
                           icon), a price (an amount the supporter chooses, or a fixed monthly amount) and a payment link

Several apps share one Stripe account: every object carries metadata (managed_by, app, page, locale) and the script
reads and changes only those of the projects in its file, so each app's repository can run its own copy on the same
account. A link's amount cannot change on Stripe: a new amount makes a new link, whose address the summary flags.
Pages removed from the file are deactivated, within the file's projects only. Prints no secret and no customer data.
Standard library only. See docs/FUNDING.md.
"""
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

API = "https://api.stripe.com"
STRIPE_VERSION = "2025-09-30.clover"
MANAGED_BY = "funding-pages"
CONFIG = Path(__file__).resolve().parents[2] / "funding" / "stripe.json"
LOCALES = ("en", "fr")
# Card statements: up to 22 characters, without < > \ ' " * (Stripe).
DESCRIPTOR = re.compile(r"^[A-Za-z0-9 .\-_]{1,22}$")


def encode(params, prefix=""):
    """A request body in Stripe's form encoding: nested objects as a[b], lists as a[0]."""
    pairs = []
    items = params.items() if isinstance(params, dict) else enumerate(params)
    for key, value in items:
        name = f"{prefix}[{key}]" if prefix else str(key)
        if isinstance(value, (dict, list)):
            pairs += encode(value, name)
        elif isinstance(value, bool):
            pairs.append((name, "true" if value else "false"))
        elif value is not None:
            pairs.append((name, str(value)))
    return pairs


class Client:
    def __init__(self, key: str):
        self.key = key

    @property
    def mode(self) -> str:
        return "live" if "_live_" in self.key else "test"

    def request(self, method: str, path: str, params=None):
        url = API + path
        data = None
        if method == "GET" and params:
            url += "?" + urllib.parse.urlencode(encode(params))
        elif params is not None:
            data = urllib.parse.urlencode(encode(params)).encode()
        request = urllib.request.Request(url, data=data, method=method, headers={
            "Authorization": f"Bearer {self.key}", "Stripe-Version": STRIPE_VERSION,
            "Content-Type": "application/x-www-form-urlencoded"})
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                return json.loads(response.read().decode())
        except urllib.error.HTTPError as error:
            # Stripe's error type and message: no secret in them.
            detail = json.loads(error.read().decode() or "{}").get("error", {})
            raise SystemExit(f"{method} {path}: HTTP {error.code} {detail.get('type')}: {detail.get('message')}") from None

    def get(self, path, params=None):
        return self.request("GET", path, params)

    def post(self, path, params):
        return self.request("POST", path, params)

    def list(self, path, params=None):
        """Every object of a list, following the pages."""
        params, items = dict(params or {}, limit=100), []
        while True:
            page = self.get(path, params)
            items += page.get("data", [])
            if not page.get("has_more") or not page.get("data"):
                return items
            params["starting_after"] = page["data"][-1]["id"]


def load(path: Path = CONFIG) -> dict:
    """The file, checked: every page in English and French, amounts in cents, a valid statement descriptor."""
    config = json.loads(path.read_text(encoding="utf-8"))
    problems = []
    currency = config.get("currency", "")
    if not re.fullmatch(r"[a-z]{3}", currency):
        problems.append("currency: a three-letter ISO code in lower case (eur)")
    for app, project in config.get("projects", {}).items():
        where = f"projects.{app}"
        if not re.fullmatch(r"[a-z0-9-]{1,40}", app):
            problems.append(f"{where}: the project key is lower-case letters, digits and dashes")
        if not project.get("name"):
            problems.append(f"{where}.name is missing")
        if not str(project.get("image", "")).startswith("https://"):
            problems.append(f"{where}.image: an https address")
        if not DESCRIPTOR.match(project.get("statement_descriptor_suffix", "")):
            problems.append(f"{where}.statement_descriptor_suffix: 1 to 22 letters, digits, spaces, . - _")
        for locale in LOCALES:
            texts = project.get("texts", {}).get(locale, {})
            for field, limit in (("submit", 1200), ("thanks", 500), ("monthly", 500)):
                if not texts.get(field) or len(texts[field]) > limit:
                    problems.append(f"{where}.texts.{locale}.{field}: required, at most {limit} characters")
        for page, spec in project.get("pages", {}).items():
            here = f"{where}.pages.{page}"
            if not re.fullmatch(r"[a-z0-9-]{1,40}", page):
                problems.append(f"{here}: the page key is lower-case letters, digits and dashes")
            amount, monthly = spec.get("amount"), spec.get("monthly")
            if (amount is None) == (monthly is None):
                problems.append(f"{here}: either amount (the supporter chooses) or monthly (a fixed amount)")
            if amount is not None:
                preset, minimum, maximum = (amount.get(key) for key in ("preset", "minimum", "maximum"))
                if not all(isinstance(value, int) and value > 0 for value in (preset, minimum, maximum)) or not minimum <= preset <= maximum:
                    problems.append(f"{here}.amount: preset, minimum and maximum in cents, minimum ≤ preset ≤ maximum")
            if monthly is not None and not (isinstance(monthly, int) and monthly > 0):
                problems.append(f"{here}.monthly: an amount in cents")
            for locale in LOCALES:
                text = spec.get(locale, {})
                if not text.get("name") or not text.get("description"):
                    problems.append(f"{here}.{locale}: name and description")
    if problems:
        raise SystemExit("funding/stripe.json:\n  " + "\n  ".join(problems))
    return config


def desired(config: dict) -> dict:
    """What each (app, page, locale) should be on Stripe."""
    wanted = {}
    for app, project in config["projects"].items():
        for page, spec in project["pages"].items():
            for locale in LOCALES:
                texts, text = project["texts"][locale], spec[locale]
                metadata = {"managed_by": MANAGED_BY, "app": app, "page": page, "locale": locale}
                if "amount" in spec:
                    price = {"currency": config["currency"], "custom_unit_amount": {"enabled": True, **spec["amount"]}}
                    link = {"submit_type": "pay", "payment_intent_data": {
                        "statement_descriptor_suffix": project["statement_descriptor_suffix"], "description": text["name"]}}
                else:
                    price = {"currency": config["currency"], "unit_amount": spec["monthly"], "recurring": {"interval": "month"}}
                    link = {"submit_type": "subscribe", "subscription_data": {"description": texts["monthly"]}}
                link.update({
                    "custom_text": {"submit": {"message": texts["submit"]}},
                    "after_completion": {"type": "hosted_confirmation", "hosted_confirmation": {"custom_message": texts["thanks"]}},
                })
                wanted[(app, page, locale)] = {
                    "product": {"name": text["name"], "description": text["description"], "images": [project["image"]]},
                    "price": price, "link": link, "metadata": metadata,
                }
    return wanted


def key_of(item: dict):
    metadata = item.get("metadata") or {}
    if metadata.get("managed_by") != MANAGED_BY:
        return None
    return metadata.get("app"), metadata.get("page"), metadata.get("locale")


def same_price(price: dict, wanted: dict) -> bool:
    if price.get("currency") != wanted["currency"]:
        return False
    if "custom_unit_amount" in wanted:
        custom = price.get("custom_unit_amount") or {}
        return all(custom.get(field) == wanted["custom_unit_amount"][field] for field in ("preset", "minimum", "maximum")) \
            and not price.get("recurring")
    recurring = price.get("recurring") or {}
    return price.get("unit_amount") == wanted["unit_amount"] and recurring.get("interval") == "month" \
        and not price.get("custom_unit_amount")


def link_changes(link: dict, wanted: dict) -> dict:
    """The fields of an existing link that differ from the file (all of them can be updated in place)."""
    changes = {}
    if link.get("submit_type") != wanted["submit_type"]:
        changes["submit_type"] = wanted["submit_type"]
    if ((link.get("custom_text") or {}).get("submit") or {}).get("message") != wanted["custom_text"]["submit"]["message"]:
        changes["custom_text"] = wanted["custom_text"]
    confirmation = ((link.get("after_completion") or {}).get("hosted_confirmation") or {}).get("custom_message")
    if confirmation != wanted["after_completion"]["hosted_confirmation"]["custom_message"]:
        changes["after_completion"] = wanted["after_completion"]
    for field in ("payment_intent_data", "subscription_data"):
        if field in wanted and any((link.get(field) or {}).get(key) != value for key, value in wanted[field].items()):
            changes[field] = wanted[field]
    return changes


def sync(client, config: dict, apply: bool) -> list:
    """Brings Stripe in line with the file; returns one row per page and language for the summary."""
    apps = set(config["projects"])
    wanted = desired(config)
    mine = lambda item: (key_of(item) or (None,))[0] in apps  # noqa: E731
    products = [p for p in client.list("/v1/products", {"active": True}) if mine(p)]
    links = [link for link in client.list("/v1/payment_links", {"active": True}) if mine(link)]
    by_key = lambda items: {key_of(item): item for item in items}  # noqa: E731
    products_by_key, links_by_key = by_key(products), by_key(links)
    rows = []

    def write(path, params):
        return client.post(path, params) if apply else {"id": "(new)", "url": None}

    for key, spec in sorted(wanted.items()):
        app, page, locale = key
        actions = []
        product = products_by_key.get(key)
        if product is None:
            product = write("/v1/products", {**spec["product"], "metadata": spec["metadata"]})
            actions.append("new product")
        elif any(product.get(field) != value for field, value in spec["product"].items()):
            write(f"/v1/products/{product['id']}", spec["product"])
            actions.append("product updated")
        prices = client.list("/v1/prices", {"product": product["id"], "active": True}) if product["id"] != "(new)" else []
        price = next((p for p in prices if same_price(p, spec["price"])), None)
        if price is None:
            price = write("/v1/prices", {**spec["price"], "product": product["id"], "metadata": spec["metadata"]})
            actions.append("new price")
        link = links_by_key.get(key)
        url_changed = False
        if link is not None:
            items = client.get(f"/v1/payment_links/{link['id']}/line_items").get("data", [])
            if [item.get("price", {}).get("id") for item in items] != [price["id"]]:
                # Stripe cannot change a link's price: this link closes and a new one takes over.
                write(f"/v1/payment_links/{link['id']}", {"active": False})
                link, url_changed = None, True
        if link is None:
            link = write("/v1/payment_links", {"line_items": [{"price": price["id"], "quantity": 1}], **spec["link"],
                                                "metadata": spec["metadata"]})
            actions.append("new link: NEW ADDRESS, update the site and the README" if url_changed else "new link")
        else:
            changes = link_changes(link, spec["link"])
            if changes:
                write(f"/v1/payment_links/{link['id']}", changes)
                actions.append("link updated" + (": NEW ADDRESS (submit type)" if "submit_type" in changes else ""))
        # Older prices are archived once no open link uses them.
        for old in prices:
            if old["id"] != price["id"]:
                write(f"/v1/prices/{old['id']}", {"active": False})
        rows.append((app, page, locale, link.get("url") or "(after apply)", ", ".join(actions) or "unchanged"))

    # Pages no longer in the file, within the file's projects: their links close and their products are archived.
    for key, link in sorted(links_by_key.items()):
        if key not in wanted:
            write(f"/v1/payment_links/{link['id']}", {"active": False})
            rows.append((*key, link.get("url"), "removed from the file: link closed"))
    for key, product in sorted(products_by_key.items()):
        if key not in wanted:
            write(f"/v1/products/{product['id']}", {"active": False})
    return rows


def summary(rows: list, mode: str, apply: bool) -> str:
    lines = [f"Stripe ({mode} mode), {'applied' if apply else 'plan only: nothing changed'}", "",
             "| App | Page | Language | Link | Change |", "|---|---|---|---|---|"]
    lines += [f"| {app} | {page} | {locale} | {url} | {change} |" for app, page, locale, url, change in rows]
    return "\n".join(lines)


def main() -> None:
    if len(sys.argv) != 2 or sys.argv[1] not in ("plan", "apply"):
        raise SystemExit("Usage: funding_pages.py plan|apply")
    config = load()
    key = os.environ.get("STRIPE_API_KEY", "").strip()
    if not key:
        raise SystemExit("The secret STRIPE_API_KEY is missing. See docs/FUNDING.md.")
    client = Client(key)
    apply = sys.argv[1] == "apply"
    text = summary(sync(client, config, apply), client.mode, apply)
    print(text)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as file:
            file.write(text + "\n")


if __name__ == "__main__":
    main()
