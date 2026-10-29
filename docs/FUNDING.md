# Support pages (Stripe)

**English** · [Français](FUNDING.fr.md)

Each app has its own support pages, Stripe payment links: once with the amount the supporter chooses, or every month
at a fixed amount, by card, Apple Pay or Google Pay, without creating an account. One Stripe account serves several
apps: the pages of each app carry its name and icon, and each repository manages only its own.

The links go on the website's home page and in the README only, never in the apps or on a page they open (support,
privacy): inside the apps, money goes only through the App Store and Google Play. The apps' own tips, In-App Purchases that unlock
nothing, are set up in the stores: see [TIPS.md](TIPS.md). `check_app_store.py` fails if a
payment link appears there.

## 1. Stripe account (once, for every app)

1. **Public business name**: your brand as a developer, the same for every app (neither an app's name nor your real
   name). Stripe shows it at the top of every page; the app's name and icon come below it, with the amount.
   *Settings → Business → Public details*: also the **statement descriptor** (e.g. your brand, in capitals) and a
   **support email** that is not your personal address.
2. **Branding** (*Settings → Branding*): logo and colours of your brand, shared by every page.
3. **Monthly supporters must be able to stop**: *Settings → Billing → Customer portal*, turn it on (cancel
   subscriptions), and in *Settings → Emails*, send the receipts and the subscription emails with the link to manage
   the subscription.
4. **Restricted key** (*Developers → API keys → Create restricted key*): **Write** on *Products*, *Prices* and *Payment
   Links*, nothing else. Put it only in the repository's GitHub secret **`STRIPE_API_KEY`** (*Settings → Secrets and
   variables → Actions*), never in a file, a commit or a message. A test-mode key (`rk_test_…`) creates test pages
   first; the summary says which mode it used.

Stripe accepts tips for something provided (here, the app): the pages say "support", not "donation".

## 2. The pages: `funding/stripe.json`

```json
{
  "currency": "eur",
  "projects": {
    "ondo": {
      "name": "Ondo",
      "image": "https://…/icon.png",
      "statement_descriptor_suffix": "ONDO",
      "texts": { "en": { "submit": "…", "thanks": "…", "monthly": "…" }, "fr": { … } },
      "pages": {
        "once": { "amount": { "preset": 500, "minimum": 100, "maximum": 50000 }, "en": { "name": "…", "description": "…" }, "fr": { … } },
        "monthly-5": { "monthly": 500, "en": { … }, "fr": { … } }
      }
    }
  }
}
```

- Amounts in cents. `amount`: once, the supporter chooses between `minimum` and `maximum` (`preset` is shown first).
  `monthly`: every month, a fixed amount (Stripe has no amount of the supporter's choosing every month).
- `statement_descriptor_suffix`: added to the account's descriptor on card statements for one-off payments (up to 22
  letters, digits, spaces, `.`, `-`, `_`).
- Every page exists in English and French: one link per language, the English one for the English text of the site
  and the README, the French one for the French text.
- `texts`: the message above the button (`submit`), the thank-you page (`thanks`) and the description of the monthly
  subscription (`monthly`), per language.

## 3. Creating and updating them

*Actions → Support pages (Stripe) → Run workflow*:
- `plan` (the default): what would change, changing nothing.
- `apply`: creates or updates the products, prices and links. The run's summary lists each page with its address.

**A link's amount cannot change on Stripe**: a new amount gives a new link at a **new address**, and the old one
closes. The summary flags it ("NEW ADDRESS"): change the address on the website and in the README in the same commit.
New texts update the links in place, at the same address. A page removed from the file closes its links.
Ondo's links are in `site/index.html` (English and French paragraphs) and in the "Support the project" section of
`README.md` and `README.fr.md`.

## 4. Several apps

Every product, price and link is tagged in Stripe with `managed_by=funding-pages`, its `app` (the key under
`projects`), its `page` and its `locale`. The script reads and changes only the apps of its own file. So:
- **one repository per app**: copy `.github/scripts/funding_pages.py`, `.github/workflows/funding-pages.yml` and a
  `funding/stripe.json` holding that app's project only, and give the repository its own `STRIPE_API_KEY` (the same
  account's key, or another restricted key). Each repository leaves the others' pages alone;
- or **several projects in one file**, if one repository handles the pages of several apps.

To show a different name and logo at the top of an app's pages, give that app its own Stripe account (*Add account*
in the account menu, under the same login) and its own key.
