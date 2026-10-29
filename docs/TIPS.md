# Tips in the apps (App Store and Google Play)

**English** · [Français](TIPS.fr.md)

Settings → **Leave a tip** offers three optional tips, bought with the store's own payment (In-App Purchase on the App
Store, Google Play Billing on Android). A tip unlocks nothing: everything in Ondo stays available to everyone. Each
purchase is finished (App Store) or consumed (Google Play) as soon as it is paid, so a tip can be given again and there
is nothing to restore. Until the products below exist, Settings says that the tips cannot be loaded.

The links to the support pages (Stripe) stay on the website's home page and in the README only
([FUNDING.md](FUNDING.md)): inside the apps, money goes only through the stores.

## 1. The products

| Tip | App Store product ID (Consumable) | Google Play product ID (one-time product) | Suggested price |
|---|---|---|---|
| Small tip | `com.markedoe.ondo.tip.small` | `tip_small` | €1.99 |
| Medium tip | `com.markedoe.ondo.tip.medium` | `tip_medium` | €4.99 |
| Large tip | `com.markedoe.ondo.tip.large` | `tip_large` | €9.99 |

The IDs are written in the apps (`App/Model/Tips.swift`, `android/.../data/Tips.kt`): type them exactly. The apps
show the price the store gives, in the viewer's currency.

Display names and descriptions, one per language:

| Language | Small / Medium / Large | Description |
|---|---|---|
| English | Small tip / Medium tip / Large tip | A tip for the developer of Ondo. It unlocks nothing. |
| French | Petit pourboire / Pourboire moyen / Grand pourboire | Un pourboire pour le développeur d’Ondo. Il ne débloque rien. |
| Spanish | Propina pequeña / Propina mediana / Propina grande | Una propina para el desarrollador de Ondo. No desbloquea nada. |
| Portuguese (Brazil) | Gorjeta pequena / Gorjeta média / Gorjeta grande | Uma gorjeta para o desenvolvedor do Ondo. Não desbloqueia nada. |
| Chinese (Simplified) | 小额打赏 / 中额打赏 / 大额打赏 | 给 Ondo 开发者的打赏。不会解锁任何功能。 |
| Arabic | إكرامية صغيرة / إكرامية متوسطة / إكرامية كبيرة | إكرامية لمطوّر Ondo. لا تفتح أي ميزة. |
| Hindi | छोटी टिप / मध्यम टिप / बड़ी टिप | Ondo के डेवलपर के लिए टिप। इससे कुछ भी अनलॉक नहीं होता। |

## 2. Before selling anything (once)

- **App Store Connect → Business**: sign the **Paid Apps Agreement**, add the bank account and the tax forms. Then
  apply to the **App Store Small Business Program** (15% commission instead of 30%).
- **Google Play Console → Settings → Payments profile**: create the payments profile (merchant account), then enroll in
  the **15% service fee tier** (*Settings → Developer account → Service fee*).
- **Trader status (EU Digital Services Act)**: an app that earns money is a trader's app in the European Union. Both
  stores then show a contact address, a phone number and an email address on the EU store pages. On the App Store,
  an individual may give a PO box (with a document linking you to it). Use a contact email that is not your personal
  address. *App Store Connect → Business → Compliance*; *Play Console → Developer account → Account details*.

## 3. App Store: the three In-App Purchases

1. *App Store Connect → Ondo → Monetization → In-App Purchases → +*, type **Consumable**, for each tip: reference
   name ("Small tip"), product ID (table above), price, and the display name and description in each language of the
   listing (table above).
2. **Review screenshot**: install the TestFlight build, open Settings → Leave a tip (the prices show once the
   products exist, in the sandbox) and take a screenshot; add it to each tip. **Review notes**: "Optional tip; it
   unlocks nothing."
3. **The first In-App Purchases go with a new version.** For 1.2.0, once 1.1.0 is approved:
   - run *Actions → Release → Run workflow*, target **release**, edition **app-store**, with **submit** off: it
     creates version 1.2.0 with its listing and build, without submitting;
   - on the 1.2.0 version page, section *In-App Purchases and Subscriptions*, select the three tips;
   - push the tag `v1.2.0`: it submits the version (the tag first withdraws any submission still waiting for review).
     If App Store Connect shows the tips outside the submission, add them and submit from App Store Connect.

   The review notes (`fastlane/metadata/review_information/notes.txt`) already name the three products;
   `check_app_store.py` fails if a product of `enum Tip` is missing from them.

## 4. Google Play: the three one-time products

1. *Play Console → Ondo → Monetize with Play → Products → One-time products → Create*, for each tip: product ID (table
   above), name and description in each language of the listing, price (Google converts it for each country), then
   **Activate**.
2. To try them without being charged, add your account under *Settings → License testing*.

The apps consume each tip once Google Play confirms the payment; a tip still pending (cash payment, for example) is
consumed when it is paid, at the next start of the app at the latest. Google refunds a one-time purchase that is
neither consumed nor acknowledged within three days.
