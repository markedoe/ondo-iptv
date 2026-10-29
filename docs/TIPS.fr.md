# Pourboires dans les apps (App Store et Google Play)

[English](TIPS.md) · **Français**

Réglages → **Laisser un pourboire** propose trois pourboires facultatifs, payés par le paiement du store (achat intégré
sur l'App Store, Google Play Billing sur Android). Un pourboire ne débloque rien : tout Ondo reste disponible pour tout
le monde. Chaque achat est terminé (App Store) ou consommé (Google Play) dès qu'il est payé : un pourboire peut être
redonné et il n'y a rien à restaurer. Tant que les produits ci-dessous n'existent pas, les Réglages disent que les
pourboires ne peuvent pas être chargés.

Les liens vers les pages de soutien (Stripe) restent sur la page d'accueil du site et dans le README uniquement
([FUNDING.fr.md](FUNDING.fr.md)) : dans les apps, l'argent ne passe que par les stores.

## 1. Les produits

| Pourboire | Identifiant App Store (consommable) | Identifiant Google Play (produit ponctuel) | Prix suggéré |
|---|---|---|---|
| Petit pourboire | `com.markedoe.ondo.tip.small` | `tip_small` | 1,99 € |
| Pourboire moyen | `com.markedoe.ondo.tip.medium` | `tip_medium` | 4,99 € |
| Grand pourboire | `com.markedoe.ondo.tip.large` | `tip_large` | 9,99 € |

Les identifiants sont écrits dans les apps (`App/Model/Tips.swift`, `android/.../data/Tips.kt`) : tapez-les tels quels.
Les apps affichent le prix donné par le store, dans la devise de l'utilisateur.

Noms et descriptions, un par langue :

| Langue | Petit / Moyen / Grand | Description |
|---|---|---|
| Anglais | Small tip / Medium tip / Large tip | A tip for the developer of Ondo. It unlocks nothing. |
| Français | Petit pourboire / Pourboire moyen / Grand pourboire | Un pourboire pour le développeur d’Ondo. Il ne débloque rien. |
| Espagnol | Propina pequeña / Propina mediana / Propina grande | Una propina para el desarrollador de Ondo. No desbloquea nada. |
| Portugais (Brésil) | Gorjeta pequena / Gorjeta média / Gorjeta grande | Uma gorjeta para o desenvolvedor do Ondo. Não desbloqueia nada. |
| Chinois (simplifié) | 小额打赏 / 中额打赏 / 大额打赏 | 给 Ondo 开发者的打赏。不会解锁任何功能。 |
| Arabe | إكرامية صغيرة / إكرامية متوسطة / إكرامية كبيرة | إكرامية لمطوّر Ondo. لا تفتح أي ميزة. |
| Hindi | छोटी टिप / मध्यम टिप / बड़ी टिप | Ondo के डेवलपर के लिए टिप। इससे कुछ भी अनलॉक नहीं होता। |

## 2. Avant de vendre quoi que ce soit (une fois)

- **App Store Connect → Business** : signez l'**accord Paid Apps**, ajoutez le compte bancaire et les formulaires
  fiscaux. Puis inscrivez-vous au **Small Business Program** (commission de 15 % au lieu de 30 %).
- **Google Play Console → Paramètres → Profil de paiement** : créez le profil de paiement (compte marchand), puis
  inscrivez-vous au **palier de frais de service à 15 %** (*Paramètres → Compte de développeur → Frais de service*).
- **Statut de professionnel (« trader », Digital Services Act européen)** : une app qui rapporte de l'argent est celle
  d'un professionnel dans l'Union européenne. Les deux stores affichent alors une adresse, un numéro de téléphone et
  une adresse e-mail de contact sur les pages européennes. Sur l'App Store, un particulier peut donner une boîte
  postale (avec un document qui la relie à vous). Prenez un e-mail de contact qui n'est pas votre adresse personnelle.
  *App Store Connect → Business → Conformité* ; *Play Console → Compte de développeur → Informations du compte*.

## 3. App Store : les trois achats intégrés

1. *App Store Connect → Ondo → Monétisation → Achats intégrés → +*, type **Consommable**, pour chaque pourboire : nom de
   référence (« Small tip »), identifiant (tableau ci-dessus), prix, nom et description dans chaque langue de la fiche
   (tableau ci-dessus).
2. **Capture pour la review** : installez le build TestFlight, ouvrez Réglages → Laisser un pourboire (les prix
   s'affichent dès que les produits existent, en sandbox) et faites une capture ; ajoutez-la à chaque pourboire.
   **Notes pour la review** : « Optional tip; it unlocks nothing. »
3. **Les premiers achats intégrés partent avec une nouvelle version.** Pour la 1.2.0, une fois la 1.1.0 approuvée :
   - lancez *Actions → Release → Run workflow*, cible **release**, édition **app-store**, **submit** décoché : la
     version 1.2.0 est créée avec sa fiche et son build, sans être soumise ;
   - sur la page de la version 1.2.0, section *Achats intégrés et abonnements*, sélectionnez les trois pourboires ;
   - poussez le tag `v1.2.0` : il soumet la version (le tag retire d'abord une soumission encore en attente de
     review). Si App Store Connect montre les pourboires hors de la soumission, ajoutez-les et soumettez depuis App
     Store Connect.

   Les notes pour la review (`fastlane/metadata/review_information/notes.txt`) nomment déjà les trois produits ;
   `check_app_store.py` échoue si un produit de `enum Tip` y manque.

## 4. Google Play : les trois produits ponctuels

1. *Play Console → Ondo → Monétiser avec Play → Produits → Produits ponctuels → Créer*, pour chaque pourboire :
   identifiant (tableau ci-dessus), nom et description dans chaque langue de la fiche, prix (Google le convertit pour
   chaque pays), puis **Activer**.
2. Pour les essayer sans être débité, ajoutez votre compte dans *Paramètres → Tests de licence*.

Les apps consomment chaque pourboire dès que Google Play confirme le paiement ; un pourboire en attente (paiement en
espèces, par exemple) est consommé quand il est payé, au plus tard au lancement suivant de l'app. Google rembourse un
achat ponctuel qui n'est ni consommé ni confirmé dans les trois jours.
