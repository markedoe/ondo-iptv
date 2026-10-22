# Pages de soutien (Stripe)

[English](FUNDING.md) · **Français**

Chaque app a ses propres pages de soutien, des liens de paiement Stripe : une fois, du montant que choisit la
personne, ou chaque mois, d'un montant fixe, par carte, Apple Pay ou Google Pay, sans créer de compte. Un seul compte
Stripe sert plusieurs apps : les pages de chaque app portent son nom et son icône, et chaque dépôt ne gère que les
siennes.

Les liens vont sur la page d'accueil du site et dans le README seulement, jamais dans les apps ni sur une page qu'elles
ouvrent (aide, confidentialité) : dans les apps, l'argent ne passe que par l'App Store et Google Play.
`check_app_store.py` échoue si un lien de paiement y apparaît.

## 1. Compte Stripe (une fois, pour toutes les apps)

1. **Nom public de l'entreprise** : votre marque de développeur, la même pour toutes les apps (ni le nom d'une app, ni
   votre vrai nom). Stripe l'affiche en haut de chaque page ; le nom et l'icône de l'app viennent dessous, avec le
   montant. *Settings → Business → Public details* : aussi le **libellé bancaire** (par exemple votre marque, en
   majuscules) et un **e-mail d'assistance** qui n'est pas votre adresse personnelle.
2. **Apparence** (*Settings → Branding*) : logo et couleurs de votre marque, communs à toutes les pages.
3. **Les soutiens mensuels doivent pouvoir arrêter** : *Settings → Billing → Customer portal*, activez-le (résiliation
   des abonnements), et dans *Settings → Emails*, envoyez les reçus et les e-mails d'abonnement avec le lien pour gérer
   l'abonnement.
4. **Clé restreinte** (*Developers → API keys → Create restricted key*) : **Write** sur *Products*, *Prices* et
   *Payment Links*, rien d'autre. Mettez-la seulement dans le secret GitHub **`STRIPE_API_KEY`** du dépôt (*Settings →
   Secrets and variables → Actions*), jamais dans un fichier, un commit ou un message. Une clé du mode test
   (`rk_test_…`) crée d'abord des pages de test ; le résumé indique le mode utilisé.

Stripe accepte les pourboires pour quelque chose de fourni (ici, l'app) : les pages disent « soutenir », pas « don ».

## 2. Les pages : `funding/stripe.json`

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

- Montants en centimes. `amount` : une fois, la personne choisit entre `minimum` et `maximum` (`preset` est proposé
  d'abord). `monthly` : chaque mois, un montant fixe (Stripe n'a pas de montant au choix chaque mois).
- `statement_descriptor_suffix` : ajouté au libellé du compte sur les relevés de carte pour les paiements uniques
  (jusqu'à 22 lettres, chiffres, espaces, `.`, `-`, `_`).
- Chaque page existe en anglais et en français : un lien par langue, l'anglais pour le texte anglais du site et du
  README, le français pour le texte français.
- `texts` : le message au-dessus du bouton (`submit`), la page de remerciement (`thanks`) et la description de
  l'abonnement mensuel (`monthly`), par langue.

## 3. Les créer et les mettre à jour

*Actions → Support pages (Stripe) → Run workflow* :
- `plan` (par défaut) : ce qui changerait, sans rien changer.
- `apply` : crée ou met à jour les produits, prix et liens. Le résumé de l'exécution liste chaque page avec son
  adresse.

**Le montant d'un lien ne peut pas changer chez Stripe** : un nouveau montant donne un nouveau lien, à une **nouvelle
adresse**, et l'ancien se ferme. Le résumé le signale (« NEW ADDRESS ») : changez l'adresse sur le site et dans le
README dans le même commit. De nouveaux textes mettent les liens à jour sur place, à la même adresse. Une page retirée
du fichier ferme ses liens.

## 4. Plusieurs apps

Chaque produit, prix et lien est marqué chez Stripe avec `managed_by=funding-pages`, son `app` (la clé sous
`projects`), sa `page` et sa `locale`. Le script ne lit et ne change que les apps de son propre fichier. Donc :
- **un dépôt par app** : copiez `.github/scripts/funding_pages.py`, `.github/workflows/funding-pages.yml` et un
  `funding/stripe.json` qui ne contient que le projet de cette app, et donnez au dépôt son propre `STRIPE_API_KEY` (la
  clé du même compte, ou une autre clé restreinte). Chaque dépôt laisse les pages des autres tranquilles ;
- ou **plusieurs projets dans un fichier**, si un dépôt gère les pages de plusieurs apps.

Pour afficher un autre nom et un autre logo en haut des pages d'une app, donnez-lui son propre compte Stripe (*Add
account* dans le menu du compte, avec le même identifiant) et sa propre clé.
