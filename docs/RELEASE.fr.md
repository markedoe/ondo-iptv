# Publier Ondo sur l'App Store (iPhone, iPad, Mac)

[English](RELEASE.md) · **Français**

Procédure vérifiée le 13/10/2026 sur les pages d'Apple et de GitHub. La compilation, la signature, l'envoi
sur TestFlight et la fiche sont automatisés (fastlane + GitHub Actions) ; ce qui reste manuel dépend de
votre compte Apple ou GitHub. Comptez environ une heure la première fois.

## A. GitHub

### A1. GitHub Actions

Le dépôt `markedoe/ondo-iptv` est public : Actions (y compris les machines macOS) et Pages sont gratuits.
Les adresses de la fiche (`fastlane/metadata/*/…_url.txt`) et du site en dépendent : à mettre à jour si le
dépôt est renommé. Rien d'autre à régler : sur un dépôt public, la détection de secrets et la protection
des pushes sont actives par défaut (vérifiable dans *Settings → Advanced Security*), et les workflows des
pull requests de nouveaux contributeurs attendent votre approbation (*Settings → Actions → General*).

### A2. Activer le site (politique de confidentialité et assistance)

Apple exige une URL de politique de confidentialité. *Settings → Pages → Build and deployment → Source :
GitHub Actions*, puis *Actions → Website → Run workflow*. Le site est publié sur
`https://markedoe.github.io/ondo-iptv/`.

### A3. Secrets du dépôt

*Settings → Secrets and variables → Actions → New repository secret*, quatre secrets :

| Secret | Valeur |
|---|---|
| `ASC_KEY_ID` | Key ID de la clé API (B3), 10 caractères |
| `ASC_ISSUER_ID` | Issuer ID (UUID affiché en haut de *Users and Access → Integrations → App Store Connect API*) |
| `ASC_KEY_P8` | tout le contenu du fichier `AuthKey_XXXX.p8`, lignes `-----BEGIN PRIVATE KEY-----` et `-----END PRIVATE KEY-----` comprises (le base64 du fichier est aussi accepté) |
| `DEVELOPMENT_TEAM` | Team ID, 10 caractères (*developer.apple.com → Account → Membership details*) |

Ne mettez jamais le `.p8` dans le dépôt (`secrets/` et `*.p8` sont ignorés par git).

## B. Apple Developer (developer.apple.com)

### B1. Accord

L'**Account Holder** doit accepter le dernier *Apple Developer Program License Agreement* (mis à jour en
août 2026) : bannière sur *developer.apple.com/account*, ou *App Store Connect → Business → Agreements*.
Sans cela, impossible de créer l'app. Pour une app gratuite, aucun autre accord, ni informations
bancaires ou fiscales.

### B2. App ID

*Certificates, Identifiers & Profiles → Identifiers → +* → **App IDs** → **App** :

- **Description** : `Ondo` (lettres, chiffres et espaces seulement — pas de points)
- **Bundle ID** : **Explicit**, `com.markedoe.ondo`
- **Capabilities** : rien à cocher (pas d'iCloud ni de Push ; les rappels sont des notifications locales)

Un seul App ID sert pour l'iPhone, l'iPad et le Mac.

### B3. Clé API App Store Connect

*App Store Connect → Users and Access → Integrations → App Store Connect API* :

1. La première fois, l'Account Holder clique **Request Access** et accepte.
2. Onglet **Team Keys** → **Generate API Key** (ou **+**) → nom `Ondo CI` → accès **Admin** → *Generate*.
   - Une **Team key** (pas une *Individual key*) avec le rôle **Admin** : la signature automatique en CI
     crée des certificats gérés dans le cloud, ce qu'une clé App Manager ou Developer ne peut pas faire.
     Le nom et le rôle ne sont plus modifiables : en cas d'erreur, révoquez la clé et recréez-la.
   - Le rôle d'une clé existante se lit dans la colonne *Access* de la liste. Si le workflow *Release*
     échoue à l'export avec `Cloud signing permission error` / `No signing certificate "iOS Distribution"
     found`, la clé n'a pas ce rôle : créez-en une avec l'accès **Admin** et remplacez les secrets
     `ASC_KEY_ID` et `ASC_KEY_P8` (l'Issuer ID ne change pas).
3. **Download API Key** : le `.p8` ne se télécharge qu'une fois. Notez le *Key ID* et l'*Issuer ID*.

## C. App Store Connect (appstoreconnect.apple.com)

### C1. Créer l'app

*Apps → + → New App* :

- Plateformes : **iOS** et **macOS** (une seule fiche, achat universel)
- Nom : `Ondo – IPTV Player` (s'il est pris, prévenez-moi : le nom vient de `fastlane/metadata/*/name.txt`)
- Langue principale : **English (U.S.)**. La fiche est traduite dans les sept langues de l'app
  (`fastlane/metadata/en-US`, `fr-FR`, `es-ES`, `es-MX`, `pt-BR`, `zh-Hans`, `ar-SA`, `hi`) ; les captures sont en
  anglais et en français, les autres langues montrent celles de la langue principale. La fiche d'une nouvelle
  langue arrive dans App Store Connect avec le tag suivant.
- Bundle ID : `com.markedoe.ondo` (la liste ne montre que les App ID créés en B2)
- SKU : `ONDO001` (définitif)
- User Access : Full Access

Si l'app a été créée pour iOS seul : barre latérale → **Add Platform** → macOS.

### C2. App Information

- **Catégorie** : envoyée par fastlane avec la fiche, Entertainment (`fastlane/metadata/primary_category.txt`)
  et Photo & Video (`secondary_category.txt`), en accord avec `LSApplicationCategoryType` (entertainment) dans
  `project.yml` : rien à choisir ici.
- **Content Rights** : l'app ne contient aucun contenu ; elle lit les sources que l'utilisateur ajoute.
  Répondez « No » (n'embarque aucun contenu de tiers) et gardez l'explication dans les notes pour la review.
- **Age Rating → Set Up Age Ratings** : répondez au questionnaire honnêtement (aucun contenu fourni ;
  *Parental Controls* : oui — profils enfant et code PIN ; pas de navigateur web, de contenu créé par les
  utilisateurs, de messagerie ni de publicité). Comme l'utilisateur peut lire n'importe quelle source,
  utilisez **Override to Higher Age Rating** (16+ conseillé). Évitez 18+ : bloqué sans vérification d'âge
  en Australie, au Brésil et à Singapour.
- **Digital Services Act (UE)** : *Business → Agreements → Compliance → Digital Services Act* →
  « This is not a trader account » si vous ne tirez aucun revenu de l'app. Sans réponse, l'app est retirée
  de l'UE.
- **Digital Goods and Services** : « No » (rien ne se vend ni ne s'achète dans l'app).
- **Privacy Policy URL** : `https://markedoe.github.io/ondo-iptv/privacy.html` (après A2).

### C3. App Privacy

Ondo lui-même ne collecte rien (ni compte, ni statistiques, ni publicité), mais le **SDK Google Cast**
4.8.6 intégré à l'app iPhone/iPad déclare des collectes (`NutritionLabel.txt` et `PrivacyInfo.xcprivacy`
du SDK) ; Apple impose de les déclarer. *App Privacy → Get Started* → **« Yes, we collect data »**, puis :

| Type | Donnée | Usages | Liée à l'utilisateur | Pistage |
|---|---|---|---|---|
| Location | Coarse Location | Analytics, App Functionality | Non | Non |
| Identifiers | Device ID | Analytics, App Functionality | Non | Non |
| Diagnostics | Performance Data, Other Diagnostic Data | Analytics, App Functionality | Non | Non |
| Usage Data | Product Interaction | Analytics, App Functionality | Non | Non |

La fiche affichera « Données non liées à vous ». L'app coupe la journalisation analytique facultative du SDK
(`disableAnalyticsLogging`) et la politique de confidentialité du site l'explique. À revoir à chaque
mise à jour du SDK Cast (`project.yml`).

### C4. Prix et disponibilité

*Pricing and Availability* : **Free** ; pays : tous sauf la **Chine continentale** (elle exige une
licence ICP).

### C5. Informations pour la review (dans la version à soumettre)

Coordonnées (nom, téléphone, e-mail), *Sign-in required* : non. Les notes sont envoyées par fastlane
(`fastlane/metadata/review_information/notes.txt`) : lecteur sans contenu, comment tester avec le bouton
« Try the demo », pourquoi HTTP (`NSAllowsArbitraryLoads`), puis chaque autorisation, mode d'arrière-plan et
entitlement : réseau local, notifications, audio en arrière-plan, AirPlay et Cast, bac à sable du Mac (client
réseau, fichiers choisis par l'utilisateur).

## D. Publier

1. **Nouveautés** (*What's New*) : avant chaque nouvelle version, écrivez ses notes de version dans
   `fastlane/metadata/en-US/release_notes.txt` et `fastlane/metadata/fr-FR/release_notes.txt`, les deux
   ensemble, et mettez-les dans un commit avant le tag (un lancement parti d'un tag envoie la fiche telle que
   le tag la contient). Elles partent avec la fiche et suivent ses règles : `check_app_store.py` échoue si
   l'une manque, est vide ou dépasse 4000 caractères, a un mot de test ou un texte provisoire, nomme une autre
   plateforme, promet une fonction à venir ou parle de téléchargements.
2. **La chaîne de livraison.** Deux événements livrent, rien d'autre n'est nécessaire :
   - **Un push sur `main`.** Une fois *CI* au vert, le workflow *Deliver* lance *Release* avec la cible `builds`
     et l'édition `testflight` : iOS et macOS partent sur TestFlight (testeurs internes, sans review). Il n'envoie
     rien quand le commit ne change rien de ce que l'app embarque (`App/`, `Packages/` sans leurs tests,
     `project.yml`, `fastlane/Fastfile`, `Gemfile*`), quand un push plus récent sur `main` change ce que l'app
     embarque (sa propre livraison l'envoie), ou quand le commit porte un tag de version.
   - **Un tag de version : votre go pour la review d'Apple** (CLAUDE.md). Le tag doit être égal à
     `MARKETING_VERSION` de `project.yml` et au `versionName` de `android/app/build.gradle.kts` (sinon le lancement
     s'arrête) :
     ```bash
     git tag v1.1.0 && git push origin v1.1.0
     ```
     *Release* (cible `release`) construit **l'édition App Store** d'iOS puis de macOS, attend qu'App Store
     Connect ait traité chaque build, puis pour chaque plateforme envoie la fiche et les captures, choisit le
     build qu'il vient d'envoyer et le soumet à la review, publié dès qu'Apple l'approuve. Une soumission de la
     même version encore en attente de review est d'abord retirée : le nouveau build la remplace. Le Mac part même
     si iOS échoue (deux versions indépendantes). Ensuite l'édition TestFlight du tag est reconstruite pour les
     testeurs, sauf si un autre lancement de *Release* attend déjà (celui d'un tag plus récent, les builds d'un
     push) : il n'est pas remplacé. Le même tag envoie Android en test fermé (RELEASE-ANDROID.fr.md).
   - **Répétition** : *Actions → Release → Run workflow*, cible `release`, `submit` décoché (par défaut) : les
     builds de l'édition App Store et la fiche, sans la soumission.

   **Après une publication**, montez `MARKETING_VERSION` dans `project.yml` et `versionName` dans
   `android/app/build.gradle.kts` à la version suivante, dans le même commit : une fois une version sur l'App
   Store, App Store Connect refuse ses nouveaux builds, donc les envois TestFlight des pushs suivants ont besoin de
   la version suivante.

   Chaque lancement de *Release* (macOS 26, Xcode 26 — exigé par Apple depuis avril 2026) lance les tests du cœur,
   puis trois vérifications dont chacune arrête le lancement avant toute compilation ou soumission : *Demo
   playlist answers* (la review d'Apple teste l'app avec la playlist de démo ; toutes les cibles sauf `status`),
   *App Store edition checks* (`check_app_store.py` et les adresses de la fiche, voir E ; pour l'édition App
   Store, et pour les cibles `all`, `release`, `submit` et les trois `metadata` quelle que soit l'édition) et
   *Privacy manifest and Mac sandbox checks* (`check_privacy_manifest.py` et `check_mac_entitlements.py` ; toute
   cible qui construit ou soumet). Numéro de build = dernier build TestFlight de la plateforme + 1.

   **Version.** Un lancement parti d'un tag prend la version du tag (`v1.1.0` → `1.1.0` ; un tag `v` qui n'est
   pas une version, comme `v1.1.0-rc1`, arrête le lancement), tout autre lancement `MARKETING_VERSION` de
   `project.yml` ; les cibles `metadata`, `submit` et `status` choisissent la version App Store de la même
   façon.

   **Un lancement à la fois.** Deux lancements prendraient le même numéro de build : un lancement démarré
   pendant qu'un autre tourne l'attend, quels que soient leur tag ou leur branche. GitHub ne garde qu'un seul
   lancement en attente : en démarrer un troisième annule celui qui attendait (jamais celui qui tourne).
   Démarrez le lancement suivant une fois le précédent terminé.

   **Cibles** (*Run workflow*, à la main) :
   - `release` : ce que fait un tag (ci-dessus) ; `submit` décoché, c'est une répétition ;
   - `builds` : les deux builds sans la fiche (ce qu'un push sur `main` envoie sur TestFlight) ;
   - `all` : les deux builds, puis la fiche ;
   - `ios` ou `mac` : un seul build ;
   - `metadata` : la fiche seule (`metadata-ios` ou `metadata-mac` pour une seule plateforme) ;
   - `submit` : la soumission à la review (étape 5) ; laissez `ios_build` ou `mac_build` vide pour ne
     soumettre qu'une plateforme. Une vérification en échec ne soumet ni l'une ni l'autre ; l'échec de la
     soumission iOS n'arrête pas celle de macOS ;
   - `status`, en lecture seule : l'état de chaque version et son build choisi, si le contact pour la review
     est complet et si les notes pour la review parlent de téléchargements ou de `network.server`, les
     captures comparées à la branche `appstore-screenshots`, et les soumissions à la review.
3. **TestFlight** : les builds apparaissent après 10 à 30 min de traitement. Groupe de testeurs internes
   (jusqu'à 100 membres de l'équipe, sans review) pour essayer sur vos appareils. Les envois des pushs sur `main`
   coupent l'e-mail de TestFlight aux testeurs pour chaque nouveau build (`quiet_testers` dans le Fastfile ;
   l'envoi d'un tag attend le traitement, Apple a donc pu l'envoyer déjà) : l'app TestFlight montre le dernier. Chaque testeur peut aussi couper les e-mails d'une app dans l'app TestFlight (la page de
   l'app, *Notifications par e-mail*).

   **Deux éditions.** *Run workflow* demande une `edition` : `testflight` (par défaut) construit l'app avec les
   téléchargements, pour les testeurs internes seulement ; `app-store` la construit sans (règle 5.2.3 d'Apple
   sur l'enregistrement de médias d'autres sources), et c'est **la seule à soumettre**. Un tag construit
   l'édition App Store. L'édition App Store est construite sans le code des téléchargements
   (`#if TESTFLIGHT_DOWNLOADS || DEBUG`), donc rien en elle ne peut les activer : une fonction désactivée, ou un
   code de déblocage, serait une fonction cachée ou dormante, ce que la règle 2.5.19 interdit et pour quoi Apple
   peut exclure un développeur de son programme. Les builds de l'édition TestFlight restent aux testeurs
   internes : les tests externes passent par la review d'Apple. TestFlight propose aux testeurs le dernier build
   de chaque plateforme, et chaque lancement construit une seule édition : un tag reconstruit tout seul l'édition
   TestFlight après l'édition App Store ; après une édition App Store construite à la main (`ios`, `mac` ou
   `all`), relancez *Release* avec la cible `builds` et l'`edition` laissée à `testflight`, sinon les testeurs
   reçoivent le build sans téléchargements.

   **Badge bêta.** L'édition TestFlight affiche un badge *Bêta* (barre d'outils de l'accueil, version dans les
   réglages) ; l'édition App Store est construite sans, car les bêtas vont sur TestFlight, pas sur l'App Store
   (règle 2.2). Rien dans le build App Store, sa fiche ou les pages du site que la fiche ouvre (accueil, support,
   confidentialité : la review d'Apple les visite) ne doit dire que l'app est une bêta ; `check_app_store.py`
   vérifie les trois. Le mot reste dans le catalogue de traductions des deux éditions : une traduction que
   personne ne voit n'est pas une fonction, la laisser.
4. **Captures** : iPhone 6,9", iPad 13" et Mac (1280×800) sont générées par le workflow *App Store
   screenshots* (FR/EN) sur la branche `appstore-screenshots` ; le workflow *Release* les envoie avec la
   fiche. Relancez *App Store screenshots* avant une release si l'interface a changé.

   **En-tête et résultats de recherche** (iPhone et iPad sous iOS / iPadOS 27 ou plus ; pas le Mac) : l'onglet
   *En-tête et résultats de recherche* de la page de la version. Les images sont
   `fastlane/creative/<langue>/header.png` (21:9, 3840×1646, PNG seulement) et `search.jpg` (3:2, 3840×2560), en anglais et en
   français, dessinées à partir des captures App Store par `fastlane/creative/render.mjs` (voir ses premières
   lignes) ; dessinez-les de nouveau quand les captures changent. *Actions → App Store header and search results
   → Run workflow* : `status` montre ce que contient App Store Connect (lecture seule), `upload` dépose les
   images dans la bibliothèque de ressources (Asset Library) sans toucher à aucune version, `place` les affiche
   sur la version iOS en préparation. Une version en review ou approuvée ne peut pas changer : `place` la
   refuse. Les ressources passent en review avec la version qui les porte, ou seules : `submit` les soumet une
   fois une version iOS approuvée, et refuse tant qu'une version iOS ou une soumission est en review, pour ne
   rien changer à ce qu'App Review examine. Une fois approuvées par Apple, `publish` les affiche sur la version
   en ligne sans nouvelle version (ou dans App Store Connect : En-tête et résultats de recherche → Parcourir les
   ressources → Publier). Rien n'est soumis sans votre accord.
5. Un tag soumet tout seul (étape 2). À la main, dans chaque version (iOS et macOS, par exemple 1.1.0) : choisissez
   le build de l'édition **app-store** →
   **Add for Review** → **Submit for Review**. Ou *Run workflow* avec la cible `metadata` (la fiche ;
   `metadata-ios` ou `metadata-mac` pour une seule plateforme, car une version en review ne peut plus être
   modifiée), puis la cible `submit` avec `ios_build` et `mac_build` : les numéros de build de l'édition
   App Store, lus dans le log du lancement de release qui l'a construite (`CURRENT_PROJECT_VERSION=…`), jamais
   le dernier build, qui peut être un de l'édition TestFlight. La version est publiée dès qu'Apple l'approuve.
   Les deux versions sont indépendantes : **iOS / iPadOS peut partir seul** ; avant de soumettre la version
   macOS, installez depuis TestFlight le build de l'édition App Store que vous allez soumettre (par son numéro
   de build : le dernier build Mac est normalement un de l'édition TestFlight) et lancez-le au moins une fois
   sur votre Mac.

Si un lancement échoue (playlist de démo, vérifications de l'édition App Store, signature, export, envoi), le
log du job dit pourquoi. Pour signer l'archive, Xcode crée un certificat *Apple Development* sur chaque
nouveau runner ; sa clé privée disparaît avec le runner, donc le workflow le révoque à la fin du job (étape
*Revoke this runner's development certificate*, repéré par son numéro de série : ni les certificats de
distribution ni celui de votre Mac ne sont touchés).
Si la signature échoue avec `Your account has reached the maximum number of certificates`, des certificats
de lancements précédents sont restés : dans *Certificates, Identifiers & Profiles → Certificates*, révoquez
les certificats *Apple Development* créés par la CI (gardez celui de votre Mac), puis relancez le workflow.
Les builds TestFlight ne sont pas touchés.

## E. Points à vérifier avant la review

- `.github/scripts/check_app_store.py` passe (la CI, le workflow de sécurité et le workflow de release le
  lancent) :
  - l'édition App Store ne compile aucun code de téléchargement, aucune fonction désactivée pour de bon ni
    aucun texte qui dit que l'app est une bêta, et n'utilise aucune API privée ; aucun réglage de build n'y
    active `TESTFLIGHT_DOWNLOADS`, et un tag la construit ; `FeatureFlags` ne lit rien à l'exécution (ni
    réglage, ni serveur, ni date, ni fichier, ni argument), et aucun code ne lit les arguments de lancement
    ni l'environnement hors de `#if DEBUG` (les points d'entrée des tests d'interface) ;
  - la fiche (notes de version comprises) tient dans les limites d'App Store Connect, ne nomme aucune autre
    plateforme, ne promet rien « à venir », n'a ni mot de test ni texte provisoire et ne parle pas de
    téléchargements ; les pages du site qu'elle ouvre (accueil, support, confidentialité) n'ont pas de mot
    de test non plus ;
  - le copyright est celui de l'année et le même que `NSHumanReadableCopyright` dans `project.yml` ;
  - les notes pour la review (4000 caractères au plus) ne nomment aucune autre plateforme, ne promettent rien
    « à venir », n'ont pas de texte provisoire et ne parlent pas de téléchargements ; elles expliquent
    `NSAllowsArbitraryLoads` et chaque autorisation, mode d'arrière-plan et entitlement Mac.
- Les adresses d'assistance, de confidentialité et du site répondent (le workflow de release les vérifie).
- `ITSAppUsesNonExemptEncryption = NO` est déclaré : pas de question d'export à chaque build.
- `PrivacyInfo.xcprivacy` présent (raisons d'usage des API sensibles) et couvrant le code livré, et le bac à
  sable du Mac a exactement les droits (entitlements) dont l'app a besoin : `check_privacy_manifest.py` et
  `check_mac_entitlements.py` passent (la CI les lance, et le workflow de release avant chaque envoi).
- Les captures ne montrent que la démo générée (affiches dessinées, chaînes fictives) : aucun logo ni
  visuel protégé.

## F. Vérifications manuelles sur appareil (TestFlight)

La CI compile, teste la logique et parcourt l'interface sur simulateur avec des flux de démonstration ;
ces points demandent un vrai appareil et un vrai abonnement avant la soumission :

- **Films et séries** d'un compte Xtream : accueil (carrousel, reprise), fiches, épisode suivant, reprise.
- **Direct avec fenêtre DVR** (HLS « event » / « sliding window » long) : pause, retour arrière, curseur
  et bouton « Direct » ; sans DVR, une pause de plus de 10 s reprend via l'archive (chaîne avec replay).
- **Redémarrer / revoir** un programme sur un compte Xtream et sur une playlist M3U `catchup`.
- **Chromecast** (iPhone/iPad) et **AirPlay** (iPhone, iPad, Mac) vers une TV ; autorisation réseau local
  demandée au premier Cast ; reprise locale à l'arrêt.
- **Image dans l'image** et lecture en arrière-plan ; commandes de l'écran verrouillé.
- **Films et épisodes MKV** : sur iPhone et iPad, remuxés pour le lecteur du système (HDR10 / Dolby Vision,
  Dolby Atmos, image dans l'image), VLC pour un codec qu'il ne sait pas décoder (DTS, TrueHD) ; sur Mac, VLC.
  **AVI** avec VLC.
- **Rappels** : autorisation demandée au premier rappel, notification reçue, toucher la notification
  ouvre la chaîne (application fermée et ouverte).
- **Multivue** (iPad, Mac) avec 4 flux réels : fluidité, un seul son, passage en plein écran.
- **Mac** : fenêtres lecteur / multivue, menu Lecture et raccourcis.
