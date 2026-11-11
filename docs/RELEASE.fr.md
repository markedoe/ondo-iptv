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
(`fastlane/metadata/review_information/notes.txt`) : lecteur sans contenu, comment tester avec la playlist de test
hébergée sur le site du projet (`https://markedoe.github.io/ondo-iptv/review/ondo-review.m3u`, voir
[site/review](../site/review/README.fr.md) : notre chaîne et notre série de test, des films de la Blender
Foundation sous CC BY ; les apps n'embarquent pas de démo), pourquoi HTTP (`NSAllowsArbitraryLoads`), puis chaque autorisation, mode d'arrière-plan et
entitlement : réseau local, notifications, audio en arrière-plan, AirPlay et Cast, bac à sable du Mac (client
réseau, fichiers choisis par l'utilisateur).

## D. Publier

1. **Nouveautés** (*What's New*) : avant chaque nouvelle version, écrivez ses notes de version dans
   `fastlane/metadata/en-US/release_notes.txt` et `fastlane/metadata/fr-FR/release_notes.txt`, les deux
   ensemble, et mettez-les dans un commit avant le tag (un lancement parti d'un tag envoie la fiche telle que
   le tag la contient). Elles partent avec la fiche et suivent ses règles : `check_app_store.py` échoue si
   l'une manque, est vide ou dépasse 4000 caractères, a un mot de test ou un texte provisoire, nomme une autre
   plateforme, promet une fonction à venir ou parle de téléchargements.
2. **La chaîne de livraison.** Deux événements livrent, et *Ship* pose de lui-même le tag de la version suivante après une nuit au vert :
   - **Un push sur `main`.** Une fois les contrôles courts de *CI* au vert (Linux, quelques minutes ; les tests longs
     sur macOS tournent la nuit, [CI.fr.md](CI.fr.md#contrôles-courts-à-chaque-push-tests-longs-la-nuit)), le workflow *Deliver* lance *Release* avec la cible `builds`
     et l'édition `testflight`, `external` activé : pour iOS et macOS, d'abord **l'édition App Store** (vérifiée comme
     pour une release : `check_app_store.py`, playlist de review, adresses de la fiche), envoyée pour les **testeurs
     externes**, puis **l'édition TestFlight** (testeurs internes, sans review), dont le numéro de build vient après ;
     un job Linux attend ensuite le traitement d'App Store Connect et envoie l'édition App Store aux groupes externes par
     la Beta App Review (étape 3, *Testeurs externes*). Il n'envoie
     rien quand le commit ne change rien de ce que l'app embarque (`App/`, `Packages/` sans leurs tests,
     `project.yml`, `fastlane/Fastfile`, `Gemfile*`), quand un push plus récent sur `main` change ce que l'app
     embarque (sa propre livraison l'envoie), ou quand le commit porte un tag de version.
   - **Un tag de version : votre go pour la review d'Apple** (CLAUDE.md). Le tag doit être égal à
     `MARKETING_VERSION` de `project.yml` et au `versionName` de `android/app/build.gradle.kts` (sinon le lancement
     s'arrête), et les jobs longs (macOS) de *CI* doivent avoir réussi **sur le commit du tag** : le run de la nuit
     sur `main`, ou *CI* lancé à la main dessus. Sinon le premier job, *Long tests passed on this commit*, refuse avant
     toute compilation, capture ou soumission ; lancez *CI* à la main sur le tag (`gh workflow run ci.yml --ref
     v1.1.0`) ou attendez la nuit, puis *Re-run all jobs* sur la release. Il en va de même pour la cible `release`
     avec `submit` (le commit depuis lequel le lancement part, qu'il compile) et pour la cible `submit` (le tag depuis
     lequel elle part, plus bas). Ce contrôle n'existe que dans les fichiers de workflow des commits faits après son
     ajout : un lancement à la main utilise le fichier de workflow de la référence d'où il part, donc un tag ou une
     branche plus ancien n'a pas de contrôle. Ne soumettez et ne publiez en production que depuis un tag créé après ce
     changement :
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
   - **Ship, après chaque nuit au vert : le go permanent** (CLAUDE.md). Le workflow *Ship* (`ship.yml`, chaque jour
     à 13 h 17 et 19 h 17 UTC, quelques heures après les runs de la nuit ; `.github/scripts/ship.py`) fait de lui-même ce que
     fait le push d'un tag quand toutes les conditions sont réunies ; sinon il n'envoie rien et dit pourquoi dans son
     journal et son résumé :
     - les derniers runs planifiés de *CI* et d'*Android* sur `main` ont réussi **sur le même commit**, ce commit est
       sur `main`, et `ci_scope.py production ci|android` l'acceptent (les tests longs ont réussi sur ce commit
       précis). C'est ce commit qui part, jamais un `main` plus récent ;
     - `MARKETING_VERSION` et `versionName` sont égaux sur ce commit et au-dessus du plus haut tag `vX.Y.Z` (en
       nombres : 2.0.10 vient après 2.0.9), et ce tag n'existe pas encore. Sans aucun tag de version, *Ship* ne fait
       rien : créez le premier sur le dernier commit publié, soit par *Run workflow* sur *Ship* avec `baseline_tag`
       (par exemple `v2.0.0`), `baseline_sha` et `dry_run` décoché (il vérifie que le commit est sur `main` et porte
       cette version, crée le tag et ne lance rien), soit en le poussant vous-même (ce qui le publie aussi) ;
     - le `release_notes.txt` de chaque langue de l'App Store et le `changelogs/default.txt` de chaque langue de
       Google Play ont été réécrits depuis ce tag (le journal liste ceux qui manquent) ;
     - quelque chose de ce que les apps embarquent a changé depuis ce tag (les chemins de *Deliver* ; une simple
       montée de version ne compte pas) ;
     - rien n'attend ni ne tourne dans les files de publication (*Release*, *Release Android*, *Promote Android*,
       *Play catch-up*, les workflows d'état, d'images et de pourboires Google Play) : rien de ce qui attend n'est
       jamais évincé ;
     - pour Apple seulement : App Store Connect, en lecture seule, ne montre aucune version iOS ou macOS en attente
       de review ou en review, approuvée mais pas encore publiée, refusée ou dans un état qu'on ne sait pas réglé,
       aucune soumission en cours, et aucune version égale ou supérieure déjà au-delà de la préparation. Une erreur
       de lecture bloque Apple. Apple bloqué, Google Play part quand même si ses propres conditions sont réunies.

     **Rattrapage Apple.** Quand la version du commit de la nuit est celle du plus haut tag (Google Play l'a reçue
     pendant qu'Apple était bloqué), *Ship* lance *Release* (cible `release`, `submit`) sur **ce tag existant**, sans
     nouveau tag, une fois que : le tag est dans l'historique de la nuit et porte cette version, il a passé les
     conditions de notes et de changement embarqué par rapport au tag d'avant (un tag de base ne les passe jamais,
     donc *Ship* ne le soumet jamais), les tests longs de *CI* ont réussi sur son commit, les files sont libres, les
     conditions d'Apple sont réunies, et App Store Connect n'a, ni sur iOS ni sur macOS, aucune version de ce numéro
     au-delà de la préparation. Cette version en attente, en review, approuvée, en vente, refusée ou retirée reste
     l'affaire d'un humain (à la main : `gh workflow run release.yml --ref vX.Y.Z -f target=release -f submit=true`).

     Quand il publie, il crée le tag sur ce commit (API GitHub) puis lance depuis le tag *Release* (cible `release`,
     `submit`) et *Release Android*. Android passe par le test interne avec la fiche (`internal`, `completed`, fiche
     `upload`), comme la livraison d'un push : chaque changement Google Play attend alors la fin d'un examen en cours
     au lieu de l'annuler et de le relancer (RELEASE-ANDROID.fr.md), et le build arrive en test fermé pour examen
     juste après, ou par *Play catch-up* une fois l'examen terminé. Un tag créé par un workflow ne déclenche aucun
     push, donc ils partent une seule fois, depuis *Ship* ; un tag que vous poussez vous-même les lance par son push,
     et *Ship* trouve alors le tag et ne fait rien. Si un lancement échoue une fois le tag créé, le run passe au rouge
     et donne la commande à lancer à la main. *Ship* ne monte jamais de version, ne fait jamais de commit et ne
     touche jamais à la production Google Play. **Quand vous intégrez du travail, écrivez ses notes de version dans
     toutes les langues et montez la version** (plus bas) : sans cela, *Ship* n'envoie rien. Un lancement à la main
     est une simulation par défaut (*Run workflow*, `dry_run` coché) : il dit ce qui partirait et ne crée rien ;
     seul `main` publie.
   - **Retrait de l'examen** (décision de l'utilisateur) : *Actions → Release → Run workflow*, cible `withdraw-ios` ou
     `withdraw-mac` : retire la soumission de cette plateforme de l'App Review, sans rien changer d'autre.
   - **Répétition** : *Actions → Release → Run workflow*, cible `release`, `submit` décoché (par défaut) : les
     builds de l'édition App Store et la fiche, sans la soumission.

   **Après une publication**, montez `MARKETING_VERSION` dans `project.yml` et `versionName` dans
   `android/app/build.gradle.kts` à la version suivante, dans le même commit : une fois une version sur l'App
   Store, App Store Connect refuse ses nouveaux builds, donc les envois TestFlight des pushs suivants ont besoin de
   la version suivante.

   Chaque lancement de *Release* (macOS 26, Xcode 26.6 comme la CI : `DEVELOPER_DIR` de chaque job macOS ;
   Xcode 26 est exigé par Apple depuis avril 2026) lance les tests du cœur (un lancement de l'édition TestFlight
   s'en passe quand la CI a déjà réussi, ses jobs macOS lancés, sur le même commit ou le même arbre, ce que le run
   d'un push n'a jamais : la livraison d'un push les lance ici ; l'édition App Store les lance toujours ;
   [CI.fr.md](CI.fr.md)),
   puis trois vérifications dont chacune arrête le lancement avant toute compilation ou soumission : *Review
   playlist answers* (la review d'Apple teste l'app avec la playlist que donnent les notes :
   `check_review_playlist.py --online` la récupère sur le site de main et vérifie que chacun de ses flux et son
   guide répondent ; édition App Store seulement, toutes les cibles sauf `status` et les retraits),
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
   - `builds` : les deux builds sans la fiche ; avec l'`edition` `testflight` et `external` activé, ce qu'envoie un
     push sur `main` (d'abord l'édition App Store pour les testeurs externes) ;
   - `all` : les deux builds, puis la fiche ;
   - `ios` ou `mac` : un seul build ;
   - `metadata` : la fiche seule (`metadata-ios` ou `metadata-mac` pour une seule plateforme) ;
   - `submit` : la soumission à la review (étape 5), lancée depuis le tag de la version (*Use workflow from → Tags* :
     depuis une branche ou un autre tag le lancement refuse), avec `ios_build` et `mac_build` pris dans **le lancement
     de release de ce tag** : App Store Connect ne dit pas de quel commit vient un build, donc le tag relie les builds au
     commit dont le lancement vérifie les tests longs. Laissez `ios_build` ou `mac_build` vide pour ne soumettre qu'une
     plateforme. Une vérification en échec ne soumet ni l'une ni l'autre ; l'échec de la
     soumission iOS n'arrête pas celle de macOS ;
   - `status`, en lecture seule : l'état de chaque version et son build choisi, si le contact pour la review
     est complet et si les notes pour la review parlent de téléchargements ou de `network.server`, les
     captures comparées à la branche `appstore-screenshots`, et les soumissions à la review.
3. **TestFlight** : les builds apparaissent après 10 à 30 min de traitement. Groupe de testeurs internes
   (jusqu'à 100 membres de l'équipe, sans review) pour essayer sur vos appareils. Les envois des pushs sur `main`
   coupent l'e-mail de TestFlight aux testeurs pour chaque nouveau build (`quiet_testers` dans le Fastfile ;
   l'envoi d'un tag attend le traitement, Apple a donc pu l'envoyer déjà) : l'app TestFlight montre le dernier. Chaque testeur peut aussi couper les e-mails d'une app dans l'app TestFlight (la page de
   l'app, *Notifications par e-mail*).

   **« À tester »** est le même sur chaque build, interne et externe, éditions TestFlight et App Store, iOS et macOS,
   pushs et tags : les notes de version de la fiche (`fastlane/metadata/<langue>/release_notes.txt`) dans chacune de
   ses langues, posées dès qu'App Store Connect liste le build (`fill_what_to_test` : seulement les localisations du
   build, rien n'est distribué ni soumis). Un build où elles n'ont pas pu être posées met le lancement en échec (étape
   *What to Test*) une fois tous les envois faits.

   **Deux éditions.** *Run workflow* demande une `edition` : `testflight` (par défaut) construit l'app avec les
   téléchargements, pour les testeurs internes seulement ; `app-store` la construit sans (règle 5.2.3 d'Apple
   sur l'enregistrement de médias d'autres sources), et c'est **la seule à soumettre**. Un tag construit
   l'édition App Store. L'édition App Store est construite sans le code des téléchargements
   (`#if TESTFLIGHT_DOWNLOADS || DEBUG`), donc rien en elle ne peut les activer : une fonction désactivée, ou un
   code de déblocage, serait une fonction cachée ou dormante, ce que la règle 2.5.19 interdit et pour quoi Apple
   peut exclure un développeur de son programme. Les builds de l'édition TestFlight restent aux testeurs
   internes ; les testeurs externes reçoivent l'édition App Store (ci-dessous). TestFlight propose aux testeurs le dernier build
   de chaque plateforme, et chaque lancement construit une seule édition : un tag reconstruit tout seul l'édition
   TestFlight après l'édition App Store ; après une édition App Store construite à la main (`ios`, `mac` ou
   `all`), relancez *Release* avec la cible `builds` et l'`edition` laissée à `testflight`, sinon les testeurs
   reçoivent le build sans téléchargements.

   **Testeurs externes** (CLAUDE.md, le go permanent du propriétaire). Chaque push sur `main` qui livre envoie
   **l'édition App Store**, exactement le build que recevrait la review d'Apple, à **chaque groupe TestFlight externe** de l'app
   (par identifiant, quel que soit son nom ; `External testers` n'est créé que si l'app n'en a aucun), par la
   **Beta App Review** (jamais la review de l'App Store : la version App Store et sa
   soumission ne sont pas touchées). L'édition TestFlight n'y va jamais : `check_app_store.py` échoue si une lane ou un
   workflow pouvait la distribuer aux testeurs externes ou la soumettre à la Beta App Review, ou si le chemin externe
   construisait autre chose que l'édition App Store.
   - Le job macOS de *Release* construit et envoie l'édition App Store d'iOS puis de macOS sans attendre, chacune avant
     l'édition TestFlight de sa plateforme (`AFTER_BUILD_NUMBER` : l'édition TestFlight prend le numéro suivant, donc
     le dernier build des testeurs internes reste l'édition TestFlight). Un contrôle ou un build de l'édition App Store
     en échec n'arrête pas l'édition TestFlight, et l'étape *External testers builds* met le lancement en rouge. Les
     tests du cœur tournent une fois dans ce job, avant les deux éditions (le run *CI* d'un push n'a que les contrôles
     courts ; ses jobs macOS viennent la nuit).
     Si l'édition TestFlight échoue après l'envoi de l'édition App Store, le dernier build de cette plateforme pour les
     testeurs internes est celui de l'édition App Store (sans téléchargements) jusqu'à la livraison suivante ; le
     lancement est rouge. Seul un lancement sur `main` envoie quoi que ce soit aux testeurs externes (jamais un tag,
     jamais une autre branche).
   - Le job *External testers* (Linux) de *Release* passe les deux numéros de build au workflow *TestFlight external*
     (`testflight-external.yml`), qui a sa propre file : son attente (jusqu'à 3 heures) ne retient jamais un tag ni la
     livraison suivante, et les builds d'un push plus récent remplacent ceux qui y attendent encore. Il ne tourne que
     lancé par *Release* (un lancement à la main s'arrête), sur `main`, avec `fastlane ios external_testers` puis
     `fastlane mac external_testers` : les groupes tels qu'App Store Connect les tient (le nom, les testeurs et le lien public de chaque groupe externe sont notés ; les groupes internes sont listés et jamais utilisés), d'abord le traitement (jusqu'à
     90 min par build), puis la place en Beta App Review (un build de la même version encore en attente est expiré et
     remplacé, jamais un build qu'utilise une version App Store : la lane s'arrête alors ; tant qu'un autre est en
     cours de review, le nouveau n'est pas envoyé et celui du push suivant suivra), les *Informations de test* (la
     description de l'app bêta : le `description.txt` de la fiche, avec ses adresses de confidentialité et marketing ;
     *À tester* : son `release_notes.txt` ; les notes de review et la playlist de test :
     `review_information/notes.txt` ; pas de connexion ; conformité à l'export : pas de chiffrement non exempté),
     langue par langue : une langue qu'App Store Connect refuse est notée, les autres sont posées et le build est
     envoyé, et le lancement est rouge à la fin. Le build est ensuite soumis à la Beta App Review et ajouté à chaque groupe externe ;
     les testeurs reçoivent un e-mail quand Apple l'approuve.
   - **Ce que contient App Store Connect** (*TestFlight → Informations de test*, les groupes et leurs testeurs) est lu,
     jamais supposé : le job note chaque groupe, interne ou externe, et combien il a de testeurs, si l'**e-mail de retour**
     (*Feedback Email*) et le contact de la Beta App Review sont remplis (jamais leurs valeurs), et ne s'arrête que
     quand l'un manque vraiment, en nommant le champ. L'e-mail de retour et les adresses des testeurs ne sont jamais
     dans le dépôt ; les testeurs s'ajoutent dans *TestFlight → Tests externes*, par e-mail ou avec un lien public. Un
     contact de Beta App Review vide est copié du contact de la review App Store. Le premier build d'une version attend
     la Beta App Review (en général un ou deux jours) ; les builds suivants de cette version sont souvent approuvés en
     quelques minutes.
   - **Pas de build iOS sur les Mac.** Un Mac à puce Apple peut installer le build iOS depuis TestFlight, et l'app Mac
     (même identifiant de bundle) refuse ensuite de s'installer tant qu'il n'est pas supprimé. Chaque groupe TestFlight a
     ce réglage (*TestFlight → le groupe → Réglages → Test iPhone and iPad apps on Apple silicon Macs* ; API :
     `iosBuildsAvailableForAppleSiliconMac`) : le job des testeurs externes le désactive dans chaque groupe où il est
     actif, et le note. Il ne retire pas un build iOS déjà installé sur un Mac : supprimez-le sur ce Mac, puis
     installez le build Mac. Le réglage de l'App Store (*Prix et disponibilité → iPhone and iPad Apps on Apple Silicon
     Mac → Make this app available*) n'est pas dans l'API : App Store Connect ne l'affiche que tant qu'aucune version
     macOS n'est sur le Mac App Store, et *TestFlight status* dit s'il y en a une.
   - **Pour regarder avant de changer quoi que ce soit** : *Actions → TestFlight status → Run workflow*
     (`testflight-status.yml`, Linux, en lecture seule, hors de la file des releases) : les groupes, leur nombre de
     testeurs et s'ils proposent les builds iOS sur les Mac à puce Apple, s'il y a une version macOS sur le Mac App
     Store, les Informations de test par langue (remplies ou non), le contact de la Beta App Review (rempli ou
     non), et les derniers builds de chaque plateforme, s'ils sont dans un groupe externe, leurs
     états interne, externe et de Beta App Review, et leur « À tester ».
   - À la main : *Run workflow*, cible `builds`, `edition` `testflight`, `external` activé.

   **Pas de badge bêta.** Aucune édition n'en affiche. Rien dans l'app, sa fiche ou les pages du site que la fiche
   ouvre (accueil, support, confidentialité : la review d'Apple les visite) ne doit dire que l'app est une bêta
   (règle 2.2) ; `check_app_store.py` vérifie les trois.
4. **Captures** : iPhone 6,9", iPad 13" et Mac (1280×800) sont générées par le workflow *App Store
   screenshots* (FR/EN) sur la branche `appstore-screenshots` ; le workflow *Release* les envoie avec la
   fiche. *Release* le lance d'abord, pour un tag et les cibles `release`, `all` et `metadata*`, et l'envoi de la
   fiche l'attend. La fiche doit correspondre au build : quand une capture échoue, un tag et les cibles `release` et
   `all` s'arrêtent avant toute compilation, tout envoi et toute soumission ; relancez *App Store screenshots*, puis la
   release (*Re-run all jobs*). Seules les cibles `metadata*`, qui ne soumettent rien, continuent avec les captures déjà
   sur la branche. Il ne tourne plus sur les
   push : lancez-le à la main pour voir les captures avant une release ([CI.fr.md](CI.fr.md#captures-app-store)).

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

   **Pourboires (achats intégrés)** : le même workflow avec `iap` lit, sans rien changer (requêtes GET seulement),
   où en sont les trois pourboires consommables sur App Store Connect (`com.markedoe.ondo.tip.small`, `.medium`,
   `.large`, d'après `App/Model/Tips.swift`) : pour chaque produit son type, son état, le partage familial, si une note
   pour la review est remplie, chaque localisation (nom, description remplie ou non), le calendrier de prix (territoire
   de base et prix client), le nombre de territoires où il est disponible, la capture pour la review et son état de
   livraison, et ses versions ; puis les pourboires absents d'App Store Connect, et chaque version App Store de chaque
   plateforme avec son état. Une lecture que la clé d'API ne peut pas faire (HTTP 403) est signalée et le reste
   continue.

   **Créer les pourboires** : `iap_create` crée les trois pourboires, ou les complète, d'après `fastlane/tips.json`
   (les noms des apps dans les sept langues et une description par langue, dans les 30 et 45 caractères d'Apple) :
   consommables, partage familial désactivé, noms de référence `Tip small` / `Tip medium` / `Tip large`, une note
   pour la review en anglais qui nomme le produit, dit qu'il ne débloque rien et où le trouver (Réglages → Laisser un
   pourboire), un nom et une description dans chaque langue de la fiche (les localisations de la version du
   pourboire, API 4.4.1 et suivantes), le prix avec la France comme territoire de base (le palier dont le prix client
   vaut exactement 1,99, 4,99 ou 9,99 € ; s'il n'existe pas, c'est une erreur, jamais un prix voisin ; les autres
   territoires suivent l'égalisation d'Apple), tous les territoires et les nouveaux, et la capture pour la review
   `fastlane/creative/iap-review.png` quand elle existe (ou le fichier donné : `asset_library.py iap_create
   chemin/vers.png`). Il est idempotent : ce qui est déjà juste n'est pas touché, rien n'est supprimé (une autre
   capture terminée déjà présente est gardée : supprimez-la dans App Store Connect pour la remplacer ; une capture
   à moitié envoyée ou refusée est remplacée), rien n'est soumis. Le rapport `iap` montre pour chaque version de
   pourboire si Apple tient une image pour la review sur la version elle-même (API 4.4.1) : vérifiez-le après la
   première exécution. Un
   pourboire en cours de review n'est pas modifié.

   **Soumettre les pourboires** : Apple examine un premier achat intégré avec une version de l'app. La publication
   d'un tag envoie la capture que son job de captures a prise (artefact `iap-review-screenshot`, fichier `en-US`) à
   chaque pourboire qui n'en a pas, puis, pour iOS puis macOS, demande à `asset_library.py iap_pending` quels
   pourboires sont `READY_TO_SUBMIT`. S'il y en a, et seulement si l'édition App Store vend les pourboires (StoreKit
   dans son build, le test de `check_app_store.py`), deliver envoie la fiche sans soumettre et `asset_library.py
   iap_submit` choisit le build, met la version et la version de chaque pourboire dans une même soumission et la
   soumet. Sinon (pourboires approuvés, en review, ou édition App Store sans eux) deliver soumet comme avant. Un
   pourboire pas prêt (absent, ou sans une de ses parties comme sa capture, refusé, en attente d'une action, retiré de
   la vente) arrête la soumission quand le build vend les pourboires : App Review trouverait des pourboires
   impossibles à acheter. Cette vérification a lieu aussi avant le retrait d'une soumission en attente : une
   publication n'en annule jamais une pour refuser ensuite. Tout est vérifié avant que `iap_submit` écrive quoi que
   ce soit ; un brouillon de soumission laissé par une exécution interrompue est complété à la suivante s'il ne
   contient que cette version et ses pourboires, jamais s'il contient autre chose. Les
   pourboires partent avec la version iOS ; la version macOS prend ceux qui attendent encore. precheck laisse
   toujours les achats intégrés de côté : il ne peut pas les lire avec une clé d'API App Store Connect. La cible
   `submit` fait de même.

   **Ce que vous seul pouvez faire** (aucune API ne le montre) : accepter le contrat **Paid Applications** et remplir
   les informations bancaires et fiscales dans App Store Connect (*Business*) ; sans eux, App Store Connect ne vend
   pas d'achats intégrés.
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

Si un lancement échoue (playlist de review, vérifications de l'édition App Store, signature, export, envoi), le
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
    `NSAllowsArbitraryLoads` et chaque autorisation, mode d'arrière-plan et entitlement Mac, et donnent la
    playlist de test hébergée sur notre propre site ; aucune adresse de playlist dans les apps ou les notes ne
    mène ailleurs (les apps n'embarquent pas de démo).
- Les adresses d'assistance, de confidentialité et du site répondent (le workflow de release les vérifie).
- `ITSAppUsesNonExemptEncryption = NO` est déclaré : pas de question d'export à chaque build.
- `PrivacyInfo.xcprivacy` présent (raisons d'usage des API sensibles) et couvrant le code livré, et le bac à
  sable du Mac a exactement les droits (entitlements) dont l'app a besoin : `check_privacy_manifest.py` et
  `check_mac_entitlements.py` passent (la CI les lance, et le workflow de release avant chaque envoi).
- Les captures ne montrent que le catalogue généré (films libres, chaînes fictives) : aucun logo ni
  visuel protégé.

## F. Vérifications manuelles sur appareil (TestFlight)

La CI compile, teste la logique et parcourt l'interface sur simulateur avec des flux de test ;
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
