# Publier Ondo sur l'App Store (iPhone, iPad, Mac)

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
- Langue principale : **English (U.S.)**
- Bundle ID : `com.markedoe.ondo` (la liste ne montre que les App ID créés en B2)
- SKU : `ONDO001` (définitif)
- User Access : Full Access

Si l'app a été créée pour iOS seul : barre latérale → **Add Platform** → macOS.

### C2. App Information

- **Catégorie** : Entertainment (cohérent avec l'app Mac).
- **Content Rights** : l'app ne contient aucun contenu ; elle lit les sources que l'utilisateur ajoute.
  Répondez « No » (n'embarque aucun contenu de tiers) et gardez l'explication des notes de review.
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

### C5. Informations pour la review (dans la version 1.0)

Coordonnées (nom, téléphone, e-mail), *Sign-in required* : non. Les notes sont envoyées par fastlane
(`fastlane/metadata/review_information/notes.txt`) : lecteur sans contenu, bouton « Try the demo »,
HTTP, Cast et notifications.

## D. Publier

1. *Actions → Release → Run workflow* (cible `all`), ou un tag :
   ```bash
   git tag v1.0.0 && git push origin v1.0.0
   ```
   Le workflow (macOS 26, Xcode 26 — exigé par Apple depuis avril 2026) lance les tests du cœur,
   construit, signe et envoie **iOS** puis **macOS** sur TestFlight (build = dernier build TestFlight + 1,
   version = le tag), puis la fiche FR/EN et les captures iPhone / iPad.
2. **TestFlight** : les builds apparaissent après 10 à 30 min de traitement. Groupe de testeurs internes
   (jusqu'à 100 membres de l'équipe, sans review) pour essayer sur vos appareils.
3. **Captures** : iPhone 6,9", iPad 13" et Mac (1280×800) sont générées par le workflow *App Store
   screenshots* (FR/EN) sur la branche `appstore-screenshots` ; le workflow *Release* les envoie avec la
   fiche. Relancez *App Store screenshots* avant une release si l'interface a changé.
4. Dans chaque version (iOS 1.0 et macOS 1.0) : choisissez le build → **Add for Review** →
   **Submit for Review**. Les deux versions sont indépendantes : **iOS / iPadOS peut partir seul** ;
   lancez au moins une fois le build Mac de TestFlight sur votre Mac avant de soumettre la version macOS.

Le premier lancement du workflow *Release* n'a encore jamais tourné : s'il échoue (signature, export,
envoi), le log du job dit pourquoi — il se corrige en général d'une ligne dans `fastlane/Fastfile`.

## E. Points à vérifier avant la review

- `ITSAppUsesNonExemptEncryption = NO` est déclaré : pas de question d'export à chaque build.
- `PrivacyInfo.xcprivacy` présent (raisons d'usage des API sensibles).
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
- **Image dans l'image** et lecture en arrière-plan ; commandes de l'écran verrouillé ; VLC (MKV, AVI).
- **Rappels** : autorisation demandée au premier rappel, notification reçue, toucher la notification
  ouvre la chaîne (application fermée et ouverte).
- **Multivue** (iPad, Mac) avec 4 flux réels : fluidité, un seul son, passage en plein écran.
- **Mac** : fenêtres lecteur / multivue, menu Lecture et raccourcis.
