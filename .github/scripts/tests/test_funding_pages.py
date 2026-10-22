"""Support pages on Stripe (funding_pages.py): created once, left alone when nothing changed, replaced when an amount
changes, and never touching another app's pages on the same account. Against an in-memory Stripe.

Run: python3 -m unittest discover -s .github/scripts/tests
"""
import copy
import importlib.util
import itertools
import json
import shutil
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "funding_pages.py"
spec = importlib.util.spec_from_file_location("funding_pages", SCRIPT)
funding_pages = importlib.util.module_from_spec(spec)
spec.loader.exec_module(funding_pages)


def strings(value):
    """Every string inside a request's parameters."""
    if isinstance(value, dict):
        for item in value.values():
            yield from strings(item)
    elif isinstance(value, list):
        for item in value:
            yield from strings(item)
    elif isinstance(value, str):
        yield value


class FakeStripe:
    """Products, prices and payment links in memory, answering the calls the script makes; records every write."""

    def __init__(self):
        self.objects = {"prod": {}, "price": {}, "plink": {}}
        self.writes = []
        self.ids = itertools.count(1)

    mode = "test"

    def add(self, kind, fields):
        identifier = f"{kind}_{next(self.ids)}"
        item = {"id": identifier, "active": True, "metadata": {}, **copy.deepcopy(fields)}
        if kind == "plink":
            item["url"] = f"https://buy.stripe.com/test_{identifier}"
        self.objects[kind][identifier] = item
        return item

    def list(self, path, params=None):
        params = params or {}
        kind = {"/v1/products": "prod", "/v1/prices": "price", "/v1/payment_links": "plink"}[path]
        items = [item for item in self.objects[kind].values() if not params.get("active") or item["active"]]
        if "product" in params:
            items = [item for item in items if item.get("product") == params["product"]]
        return copy.deepcopy(items)

    def get(self, path, params=None):
        link = path.split("/")[3]
        price = self.objects["price"][self.objects["plink"][link]["price"]]
        return {"data": [{"price": copy.deepcopy(price), "quantity": 1}]}

    def post(self, path, params):
        self.writes.append((path, params))
        parts = path.split("/")
        kind = {"products": "prod", "prices": "price", "payment_links": "plink"}[parts[2]]
        if len(parts) == 4:
            item = self.objects[kind][parts[3]]
            item.update(copy.deepcopy(params))
            return copy.deepcopy(item)
        fields = dict(params)
        if kind == "plink":
            fields["price"] = fields.pop("line_items")[0]["price"]
        return copy.deepcopy(self.add(kind, fields))


class FundingPagesTest(unittest.TestCase):
    def setUp(self):
        self.config = funding_pages.load()
        self.stripe = FakeStripe()

    def apply(self, config=None):
        self.stripe.writes.clear()
        return funding_pages.sync(self.stripe, config or self.config, apply=True)

    def test_the_repository_file_is_valid_and_bilingual(self):
        pages = funding_pages.desired(self.config)
        self.assertEqual(len(self.config["projects"]["ondo"]["pages"]) * 2, len(pages))
        self.assertEqual({"en", "fr"}, {locale for _, _, locale in pages})

    def test_a_plan_changes_nothing(self):
        rows = funding_pages.sync(self.stripe, self.config, apply=False)
        self.assertEqual([], self.stripe.writes)
        self.assertTrue(all("new link" in change for *_, change in rows))

    def test_a_second_run_changes_nothing_and_keeps_the_addresses(self):
        first = {row[:3]: row[3] for row in self.apply()}
        self.assertEqual(8, len(self.stripe.list("/v1/payment_links", {"active": True})))
        second = self.apply()
        self.assertEqual([], self.stripe.writes)
        self.assertEqual(first, {row[:3]: row[3] for row in second})
        self.assertTrue(all(change == "unchanged" for *_, change in second))

    def test_a_new_amount_makes_a_new_link_and_closes_the_old_one(self):
        before = {row[:3]: row[3] for row in self.apply()}
        changed = copy.deepcopy(self.config)
        changed["projects"]["ondo"]["pages"]["monthly-5"]["monthly"] = 600
        rows = {row[:3]: row for row in self.apply(changed)}
        for locale in ("en", "fr"):
            row = rows[("ondo", "monthly-5", locale)]
            self.assertNotEqual(before[row[:3]], row[3])
            self.assertIn("NEW ADDRESS", row[4])
        self.assertEqual(8, len(self.stripe.list("/v1/payment_links", {"active": True})))
        amounts = {p["unit_amount"] for p in self.stripe.list("/v1/prices", {"active": True}) if p.get("unit_amount")}
        self.assertEqual({300, 600, 1000}, amounts, "the old €5 price is archived")
        self.assertEqual("unchanged", rows[("ondo", "monthly-3", "en")][4])

    def test_new_texts_update_the_link_in_place(self):
        before = {row[:3]: row[3] for row in self.apply()}
        changed = copy.deepcopy(self.config)
        changed["projects"]["ondo"]["texts"]["fr"]["thanks"] = "Merci !"
        rows = self.apply(changed)
        self.assertEqual(before, {row[:3]: row[3] for row in rows})
        self.assertEqual(4, sum(change == "link updated" for *_, change in rows))

    def test_another_apps_pages_on_the_same_account_are_never_touched(self):
        other = {"managed_by": "funding-pages", "app": "other", "page": "once", "locale": "en"}
        product = self.stripe.add("prod", {"name": "Other app", "metadata": other})
        price = self.stripe.add("price", {"product": product["id"], "currency": "eur", "unit_amount": 200, "metadata": other})
        link = self.stripe.add("plink", {"price": price["id"], "metadata": other})
        self.apply()
        touched = set()
        for path, params in self.stripe.writes:
            touched |= set(path.split("/"))
            touched |= set(strings(params))
        for identifier in (product["id"], price["id"], link["id"]):
            self.assertNotIn(identifier, touched)
        self.assertTrue(self.stripe.objects["plink"][link["id"]]["active"])

    def test_a_page_taken_out_of_the_file_is_closed(self):
        self.apply()
        changed = copy.deepcopy(self.config)
        del changed["projects"]["ondo"]["pages"]["monthly-10"]
        rows = self.apply(changed)
        self.assertEqual(6, len(self.stripe.list("/v1/payment_links", {"active": True})))
        self.assertEqual(2, sum("link closed" in change for *_, change in rows))

    def test_a_wrong_file_is_refused_with_its_mistakes(self):
        folder = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, folder)
        broken = copy.deepcopy(self.config)
        page = broken["projects"]["ondo"]["pages"]["once"]
        page["amount"]["preset"] = 50
        del page["fr"]
        broken["projects"]["ondo"]["statement_descriptor_suffix"] = "ONDO'S *"
        (folder / "stripe.json").write_text(json.dumps(broken), encoding="utf-8")
        with self.assertRaises(SystemExit) as raised:
            funding_pages.load(folder / "stripe.json")
        message = str(raised.exception)
        for expected in ("pages.once.amount", "pages.once.fr", "statement_descriptor_suffix"):
            self.assertIn(expected, message)

    def test_requests_use_stripes_form_encoding(self):
        pairs = funding_pages.encode({"line_items": [{"price": "price_1", "quantity": 1}], "custom_unit_amount": {"enabled": True}})
        self.assertEqual([("line_items[0][price]", "price_1"), ("line_items[0][quantity]", "1"),
                          ("custom_unit_amount[enabled]", "true")], pairs)


if __name__ == "__main__":
    unittest.main()
