#!/usr/bin/env python3
"""The tips' one-time products on Google Play (.github/workflows/play-tips.yml).

Usage, with the service account of the GitHub secret PLAY_SERVICE_ACCOUNT_JSON (as play_status.py):
  play_tips.py status   the in-app products Play holds (both APIs, as play_status.py shows them) and, for each tip,
                        whether it is as fastlane/tips.json wants it. Changes nothing.
  play_tips.py create   creates or completes the three tips (tip_small, tip_medium, tip_large: android .../Tips.kt)
                        with the one-time products API (monetization.onetimeproducts): one purchase option "buy",
                        a listing in every Play listing language, the price in euros (1.99, 4.99, 9.99) in every euro
                        region, the other regions converted by Play (monetization.convertRegionPrices) and the new
                        regions too, then activates the purchase option. Idempotent: a tip already right is left
                        alone; nothing is deleted (other purchase options and listings are kept as they are).

The output follows play_status.py's rules for a public log: no address, no long number, no Google error message (an
error shows its code and reasons; a missing payments profile is named in our own words). Standard library only.
"""
import importlib.util
import json
import os
import sys
import urllib.error
import urllib.parse
from decimal import Decimal
from pathlib import Path

HERE = Path(__file__).resolve().parent
TIPS_FILE = HERE.parents[1] / "fastlane" / "tips.json"
OPTION = "buy"


def load_play_status():
    spec = importlib.util.spec_from_file_location("play_status", HERE / "play_status.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


play = load_play_status()
PACKAGE = play.PACKAGE
PUBLISHER = play.PUBLISHER

# Google's refusals that need the developer, not the script: recognised in Google's message, told in our words.
NEEDS_YOU = (
    (("merchant", "payments profile", "payment profile"),
     "Google Play has no payments profile (merchant account) for this developer account: set it up in Play Console "
     "→ Settings → Payments profile, then run this again. Nothing more was created."),
    (("billing permission", "com.android.vending.billing"),
     "Google Play wants a bundle with the BILLING permission uploaded first (the Play Billing library adds it): upload "
     "a release to internal testing, then run this again. Nothing more was created."),
)


def needs_you(error) -> str:
    message = (getattr(error, "message", "") or "").lower()
    for words, sentence in NEEDS_YOU:
        if any(word in message for word in words):
            return sentence
    return ""


def tips_catalog(path: Path = TIPS_FILE) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def to_money(amount: str, currency: str) -> dict:
    value = Decimal(amount)
    units = int(value)
    return {"currencyCode": currency, "units": str(units), "nanos": int((value - units) * 1_000_000_000)}


def amount(money: dict) -> Decimal:
    return Decimal(int(money.get("units") or 0)) + Decimal(int(money.get("nanos") or 0)) / Decimal(1_000_000_000)


def wanted_listings(catalog: dict, size: str) -> list:
    listings = []
    for code, language in catalog["play_languages"].items():
        texts = catalog["languages"][language]
        listings.append({"languageCode": code, "title": texts["names"][size], "description": texts["description"]})
    return listings


def regional_configs(converted: dict, eur: str) -> list:
    """Every region Play converted the price to. Play converts from a price without tax and answers with tax: the
    euro regions get exactly the price chosen (what the buyer pays, as on the App Store), the others Play's
    conversion."""
    configs = []
    for region, item in sorted((converted.get("convertedRegionPrices") or {}).items()):
        price = item.get("price") or {}
        if price.get("currencyCode") == "EUR":
            price = to_money(eur, "EUR")
        configs.append({"regionCode": item.get("regionCode") or region, "price": price, "availability": "AVAILABLE"})
    return configs


def purchase_option(converted: dict, eur: str) -> dict:
    other = converted.get("convertedOtherRegionsPrice") or {}
    return {
        "purchaseOptionId": OPTION,
        # The app reads the product's single offer (oneTimePurchaseOfferDetails): the legacy compatible one.
        "buyOption": {"legacyCompatible": True},
        "regionalPricingAndAvailabilityConfigs": regional_configs(converted, eur),
        "newRegionsConfig": {"usdPrice": other.get("usdPrice"), "eurPrice": other.get("eurPrice"), "availability": "AVAILABLE"},
    }


def without_output_fields(option: dict) -> dict:
    return {key: value for key, value in option.items() if key != "state"}


def product_body(catalog: dict, size: str, converted: dict, existing=None) -> dict:
    """The product as PATCH .../onetimeproducts/{id} takes it. Listings and purchase options of an existing product
    that are not ours are kept, so the patch deletes nothing."""
    tip = catalog["tips"][size]
    listings = wanted_listings(catalog, size)
    ours = {listing["languageCode"] for listing in listings}
    options = [purchase_option(converted, tip["eur"])]
    if existing:
        listings += [listing for listing in existing.get("listings", []) if listing.get("languageCode") not in ours]
        options += [without_output_fields(option) for option in existing.get("purchaseOptions", [])
                    if option.get("purchaseOptionId") != OPTION]
    return {"packageName": PACKAGE, "productId": tip["play"], "listings": listings, "purchaseOptions": options}


def problems(product, catalog: dict, size: str) -> list:
    """What a tip lacks to be as wanted (empty: right): its option, listings, euro price and new regions."""
    if not product:
        return ["missing"]
    found = []
    have = {listing.get("languageCode"): listing for listing in product.get("listings", [])}
    for listing in wanted_listings(catalog, size):
        current = have.get(listing["languageCode"])
        if not current or current.get("title") != listing["title"] or current.get("description") != listing["description"]:
            found.append(f"listing {listing['languageCode']}")
    option = next((item for item in product.get("purchaseOptions", []) if item.get("purchaseOptionId") == OPTION), None)
    if not option or "buyOption" not in option:
        return found + [f"no \"{OPTION}\" purchase option"]
    configs = {config.get("regionCode"): config for config in option.get("regionalPricingAndAvailabilityConfigs", [])}
    france = configs.get("FR") or {}
    price = france.get("price") or {}
    if price.get("currencyCode") != "EUR" or amount(price) != Decimal(catalog["tips"][size]["eur"]) or france.get("availability") != "AVAILABLE":
        found.append("price in France")
    if (option.get("newRegionsConfig") or {}).get("availability") != "AVAILABLE":
        found.append("new regions")
    if option.get("state") != "ACTIVE":
        found.append(f"purchase option {option.get('state', 'not active')}")
    return found


def only_inactive(found: list) -> bool:
    return bool(found) and all(item.startswith("purchase option ") for item in found)


def get_product(client, product_id: str):
    try:
        return client.call("GET", f"{PUBLISHER}{PACKAGE}/oneTimeProducts/{urllib.parse.quote(product_id)}")
    except play.ApiError as error:
        if error.code == 404:
            return None
        raise


def activate(client, product_id: str) -> None:
    client.call("POST", f"{PUBLISHER}{PACKAGE}/oneTimeProducts/{urllib.parse.quote(product_id)}/purchaseOptions:batchUpdateStates",
                body={"requests": [{"activatePurchaseOptionRequest": {
                    "packageName": PACKAGE, "productId": product_id, "purchaseOptionId": OPTION}}]})


def create(client, report, catalog=None) -> int:
    catalog = catalog or tips_catalog()
    report.title("Tips on Google Play (create or complete)")
    for size, tip in catalog["tips"].items():
        product_id = tip["play"]
        try:
            product = get_product(client, product_id)
            existed = product is not None
            found = problems(product, catalog, size)
            if not found:
                report.add(f"{product_id}: already right ({tip['eur']} EUR, {len(catalog['play_languages'])} listings, active)")
                continue
            report.add(f"{product_id}: to do: {', '.join(found)}")
            if not only_inactive(found):
                converted = client.call("POST", f"{PUBLISHER}{PACKAGE}/pricing:convertRegionPrices",
                                        body={"price": to_money(tip["eur"], "EUR")})
                version = (converted.get("regionVersion") or {}).get("version")
                if not version:
                    raise SystemExit(f"{product_id}: Play gave no regions version with its converted prices")
                params = {"allowMissing": "true", "regionsVersion.version": version, "updateMask": "listings,purchaseOptions"}
                body = product_body(catalog, size, converted, product)
                product = client.call("PATCH", f"{PUBLISHER}{PACKAGE}/onetimeproducts/{urllib.parse.quote(product_id)}",
                                      params=params, body=body)
                report.add(f"  {'updated' if existed else 'created'}: "
                           f"{len(body['listings'])} listing(s), prices in {len(body['purchaseOptions'][0]['regionalPricingAndAvailabilityConfigs'])} region(s)")
            option = next((item for item in (product or {}).get("purchaseOptions", []) if item.get("purchaseOptionId") == OPTION), {})
            if option.get("state") != "ACTIVE":
                activate(client, product_id)
                report.add(f"  purchase option \"{OPTION}\" activated")
        except play.ApiError as error:
            sentence = needs_you(error)
            report.add(f"{product_id}: refused ({error})")
            if sentence:
                report.add(sentence)
            return 1
    return 0


def status(client, report, catalog=None) -> int:
    catalog = catalog or tips_catalog()
    play.products(client, report)
    report.title("Tips as fastlane/tips.json wants them")
    for size, tip in catalog["tips"].items():
        unread = object()
        product = report.attempt(tip["play"], lambda tip=tip: get_product(client, tip["play"]) or unread)
        if product is None:
            continue  # the refusal is reported above
        found = problems(None if product is unread else product, catalog, size)
        report.add(f"{tip['play']}: {'right' if not found else 'to do: ' + ', '.join(found)}")
    return 0


def main() -> int:
    actions = {"status": status, "create": create}
    if len(sys.argv) != 2 or sys.argv[1] not in actions:
        print(f"Usage: play_tips.py {'|'.join(actions)}")
        return 2
    raw = os.environ.get("PLAY_SERVICE_ACCOUNT_JSON", "")
    if not raw.strip():
        print("::error::The secret PLAY_SERVICE_ACCOUNT_JSON is not set")
        return 1
    try:
        token = play.access_token(json.loads(raw))
    except (ValueError, KeyError):
        print("::error::PLAY_SERVICE_ACCOUNT_JSON is not a service account key (JSON with client_email and private_key)")
        return 1
    except (play.ApiError, RuntimeError, urllib.error.URLError) as error:
        print(play.safe(f"::error::No Google token: {error}"))
        return 1
    report = play.Report()
    try:
        code = actions[sys.argv[1]](play.Client(token), report)
    finally:
        print("\n".join(report.lines))
    return code


if __name__ == "__main__":
    sys.exit(main())
