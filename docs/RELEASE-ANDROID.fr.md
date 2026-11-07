# Publier Ondo sur Google Play (Android)

[English](RELEASE-ANDROID.md) · **Français**

La compilation, les tests, les captures de la fiche, la signature et l'envoi sur Google Play sont
automatisés (Gradle + fastlane + GitHub Actions). Ce qui reste manuel dépend de votre compte Google Play
et ne se fait qu'une fois. Comptez une heure, plus les délais de Google (vérification du compte, test
fermé de 14 jours pour un compte personnel récent).

Procédure établie le 13/10/2026. Les libellés de la Play Console changent souvent : en cas de doute, suivez
l'aide de la Play Console, qui fait foi.

## A. Compte Google Play Console

1. Créez le compte sur <https://play.google.com/console/signup> (frais d'inscription uniques de 25 $).
   - **Compte personnel** : vérification d'identité (pièce d'identité, téléphone). Pour un compte
     personnel créé après le 13/11/2023, Google impose un **test fermé avec au moins 12 testeurs inscrits
     pendant 14 jours d'affilée** avant d'ouvrir la production (voir E2).
   - **Compte organisation** : numéro D-U-N-S requis, pas de test fermé obligatoire.
2. Activez la validation en deux étapes du compte Google utilisé.

## B. Créer l'app dans la Play Console

*Accueil → Créer une application* :

| Champ | Valeur |
|---|---|
| Nom de l'application | `Ondo` |
| Langue par défaut | Anglais (États-Unis) – en-US |
| Application ou jeu | Application |
| Gratuite ou payante | Gratuite (définitif) |
| Déclarations | cocher les règles du programme pour les développeurs et les lois d'exportation des États-Unis |

Le nom du package est fixé au premier envoi : `com.markedoe.ondo` (les versions de debug utilisent
`com.markedoe.ondo.debug` et ne vont jamais sur le Play Store).

## C. Clé d'importation et secrets GitHub

Google Play signe l'app distribuée (**signature d'application par Google Play**, imposée pour les AAB).
Vous ne gardez que la **clé d'importation** (upload key), qui signe le bundle envoyé.

### C1. Créer la clé d'importation (une fois, sur votre ordinateur)

```sh
keytool -genkeypair -v -keystore ondo-upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

`keytool` est fourni avec Java (Android Studio l'inclut). Choisissez un mot de passe fort, conservez le
fichier `.jks` et son mot de passe **hors du dépôt** (gestionnaire de mots de passe). Si la clé est perdue,
elle peut être réinitialisée depuis la Play Console (*Intégrité de l'application*), sans republier l'app.

### C2. Compte de service pour l'API Google Play

1. *Play Console → Paramètres → Accès à l'API* (ou *Utilisateurs et autorisations*, selon la version de
   la console) : suivez le lien vers Google Cloud, créez un projet si besoin et activez la
   **Google Play Android Developer API**.
2. Dans Google Cloud : *IAM et administration → Comptes de service → Créer un compte de service*
   (`ondo-release`), sans rôle Cloud. Onglet *Clés → Ajouter une clé → JSON* : un fichier JSON est
   téléchargé.
3. Dans la Play Console : *Utilisateurs et autorisations → Inviter de nouveaux utilisateurs* : l'adresse du
   compte de service, autorisations **pour l'app Ondo** : *Publier des applications sur les canaux de test*,
   *Gérer la présence sur le Play Store*, *Publier des applications en production, exclure des appareils et
   utiliser la signature d'application Play* (si vous voulez publier en production depuis GitHub).

### C3. Secrets du dépôt

*GitHub → Settings → Secrets and variables → Actions → New repository secret* :

| Secret | Valeur |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | le fichier `.jks` en base64 : `base64 -i ondo-upload.jks \| pbcopy` (macOS) ou `base64 -w0 ondo-upload.jks` (Linux) |
| `ANDROID_KEYSTORE_PASSWORD` | mot de passe du keystore |
| `ANDROID_KEY_ALIAS` | `upload` (l'alias choisi en C1) |
| `ANDROID_KEY_PASSWORD` | mot de passe de la clé (souvent le même) |
| `PLAY_SERVICE_ACCOUNT_JSON` | tout le contenu du fichier JSON du compte de service |

Ne mettez jamais le `.jks` ni le JSON dans le dépôt : `*.jks`, `*.keystore` et `secrets/` sont ignorés
par git et refusés par le garde anti-fuite (`.claude/hooks/leak_guard.py`).

## D. Fiche et déclarations (Play Console → *Surveiller et améliorer* / *Règles et programmes*)

La fiche (titre, descriptions, icône, image de présentation, captures téléphone, tablettes et TV, bannière TV,
dans les sept langues de l'app : anglais, français, espagnol, portugais, chinois, arabe et hindi) est envoyée par fastlane depuis
`fastlane/metadata-android/` ; les captures sont produites
par les tests Roborazzi à chaque release, dans chaque langue de la fiche (`es-419` reprend celles en espagnol), et
l'image de présentation, dans chaque langue aussi, par `fastlane/creative/render.mjs --tv`. Pour envoyer les images
seules (sans build, sans texte, sans notes de version), lancer *Google Play images* (`play-images.yml`) :
`upload: no` les produit dans l'artefact **google-play-images** pour les regarder d'abord, `upload: yes` les envoie.
Sa validation utilise `ERROR_IF_IN_REVIEW` : pendant un examen de Google, Google Play la refuse et rien ne change,
l'examen continue ; sinon les nouvelles images partent à l'examen de la fiche par Google. Les notes de version,
`fastlane/metadata-android/<langue>/changelogs/default.txt` (500 caractères au plus, vérifiés par le workflow
*Android* et `check_app_store.py`), partent avec chaque release qui porte la fiche (un tag) et avec la release en
test fermé qui suit chaque build interne d'un push : gardez toutes les langues à jour. Le build interne lui-même part
seul, sans notes. Une langue que la fiche n'a pas encore ferait refuser la release par Google Play : ajoutez-la à la
fiche (un tag) avant ses notes de version. Le reste se déclare une fois dans la console, section *Configurer votre application* du tableau de
bord :

### D1. Règles de confidentialité

URL : `https://markedoe.github.io/ondo-iptv/privacy.html` (le site est publié par le workflow *Website*).

### D2. Identifiants de connexion (accès à l'application)

*Is any part of your app restricted?* **No** : Ondo n'a ni compte ni paiement et ne bloque rien. Les champs
Xtream (serveur, nom d'utilisateur, mot de passe) reçoivent les informations du fournisseur de l'utilisateur,
comme une adresse de playlist : ce n'est pas une connexion à Ondo. Ne choisissez pas l'option restreinte :
Play demande alors des identifiants qui fonctionnent (« Missing sign in details »). L'app n'embarque ni
chaîne ni démo : les examinateurs la testent avec la playlist de test hébergée sur le site du projet,
`https://markedoe.github.io/ondo-iptv/review/ondo-review.m3u` ([site/review](../site/review/README.fr.md) : notre chaîne et notre série
de test, des films de la Blender Foundation sous CC BY), donnée dans les instructions ci-dessous.

Si Play signale encore « Missing sign in details » (ses vérifications voient les champs Xtream), répondez **Yes**
et ajoutez un jeu d'instructions : nom `Test playlist – no account`, utilisateur `No account needed`, mot de
passe `No password needed`, et dans *Any other instructions* (en anglais pour l'examinateur) : « Ondo has no
accounts and no payments, and ships no channels. On the welcome screen, tap Add a Playlist, choose M3U link and
enter https://markedoe.github.io/ondo-iptv/review/ondo-review.m3u : a test playlist hosted on the project's own site, with only content we
have the rights to (a test channel and series we produce, and Blender Foundation films under Creative Commons).
Every screen of the app can be reviewed with it. The Xtream fields (server, username, password) are for the
user's own provider: Ondo provides no server and no content. » Ne donnez jamais les identifiants d'un vrai
abonnement IPTV.

### D3. Annonces

*Non, mon application ne contient pas d'annonces.*

### D4. Classification du contenu (questionnaire IARC)

Catégorie : **Tous les autres types d'applications** (pas un jeu). Répondez honnêtement : pas de violence,
de sexualité, de langage grossier, de substances, de jeux d'argent ni de partage de position dans l'app
elle-même. À la question sur les contenus générés ou fournis par les utilisateurs / l'accès à des contenus
non filtrés, indiquez que l'app lit des flux choisis par l'utilisateur. Les catégories dont le nom semble
réservé aux adultes sont masquées par défaut (réglage *Afficher les catégories adultes*).

### D5. Public cible

**18 ans et plus** : le contenu dépend des sources de l'utilisateur. Cela écarte les obligations du
programme Familles.

### D6. Sécurité des données

Ondo n'a ni serveur, ni compte, ni statistiques, ni publicité, ni rapport de plantage : toutes les données
restent sur l'appareil. Les requêtes réseau vont aux serveurs que l'utilisateur a saisis, et une fois par semaine, sauf
si c'est désactivé dans les Réglages (*Mettre à jour les prix automatiquement*), à GitHub pour le fichier
public des prix ; rien concernant l'utilisateur n'est envoyé (voir les règles de confidentialité). Avec un jeton
TMDb saisi par l'utilisateur (ou arrivé par la synchronisation), l'app interroge aussi TMDb, avec ce jeton, pour
les tendances, les images de fond, les personnes et leurs crédits : comme les serveurs de l'utilisateur, un
service qu'il a configuré, interrogé à sa demande.

- *Votre application collecte-t-elle ou partage-t-elle des types de données utilisateur requis ?* **Non.**
- Le formulaire demande alors seulement de confirmer. (Pas de Google Cast sur Android, contrairement à
  l'iPhone : rien à déclarer à ce titre.)

### D7. Autres déclarations

Application d'actualités : **non**. Applications gouvernementales, financières, de santé : **non**.
Identifiant publicitaire : **non** (l'app ne l'utilise pas). Fonctionnalités financières : **aucune**.

### D8. Fiche principale

Catégorie : **Vidéo et éditeurs vidéo** (ou *Divertissement*). Adresse e-mail de contact : une adresse
dédiée au support (publique sur la fiche). Site web : `https://markedoe.github.io/ondo-iptv/`.

## E. Premier envoi et tests

### E1. Premier bundle

1. *GitHub → Actions → Release Android → Run workflow* (piste `internal`, statut `draft`) : le workflow
   envoie le bundle, les textes de la fiche et les captures, et crée une version brouillon sur la piste
   interne. Si Google la refuse parce que l'app n'a encore aucune version, téléchargez l'artefact
   **ondo-release-bundle** (fichier `app-release.aab`) de l'exécution et importez-le dans *Tests internes →
   Créer une version*.
2. *Play Console → Tester et publier → Tests → Tests internes* : dans *Testeurs*, créez une liste d'e-mails
   avec votre compte Google et enregistrez. Puis *Versions* → le brouillon → *Modifier* → *Suivant* →
   *Enregistrer et publier*. Ouvrez le lien *Rejoindre sur Android* sur le téléphone et installez Ondo depuis
   Google Play. *Tester et publier → Intégrité de l'application* montre que Google Play signe l'app (signature
   d'application Play) ; la clé d'importation reste la vôtre.
3. Les versions suivantes partent de GitHub toutes seules :
   - **un push sur `main`** : une fois le workflow *Android* au vert, *Deliver* envoie le build seul (pas la fiche)
     en **test interne**, déployé aussitôt, quand `android/` a changé (hors tests). Puis le même job publie le même
     version code en **test fermé** (`alpha`), statut `completed` avec les notes de version, donc Google l'examine :
     le test fermé reçoit le dernier build interne dès que l'examen en cours, s'il y en a un, est terminé. Chaque
     validation faite par un push sur `main` utilise `ERROR_IF_IN_REVIEW` : par défaut, une validation annule les modifications en
     examen et les renvoie, ce qui relancerait cet examen. Donc, pendant que Google examine quelque chose (le test
     fermé, la fiche, l'inscription Android TV), Google Play refuse la release en test fermé et l'étape *Closed
     testing* se termine par une notice, « closed testing will catch up after Google's review » ; *Play catch-up*
     (toutes les heures) y envoie le build interne le plus récent une fois l'examen terminé, une mise à jour à la
     fois. Tout autre refus (une langue absente de la fiche) fait passer cette étape au rouge avec une erreur ; le
     build interne reste livré dans les deux cas. Si Google Play refuse aussi le build interne lui-même pendant un
     examen, le lancement l'indique, « internal paused while Google reviews closed testing; it resumes
     automatically » : le prochain push après l'examen en envoie un ;
   - **un tag de version** (`git tag v1.2.0 && git push origin v1.2.0`, le même tag que pour l'App Store, égal au
     `versionName` de `android/app/build.gradle.kts` et au `MARKETING_VERSION` de `project.yml`, sinon le lancement
     s'arrête) : le build **avec la fiche** part en **test fermé**, statut `completed`, donc Google Play l'envoie
     aussitôt en examen. Il faut pour cela **la publication gérée désactivée** (*Vue d'ensemble de la publication →
     Publication gérée*) ; activée, Google refuse l'envoi automatique et le lancement échoue en le disant ;
   - ou *Run workflow* avec la piste voulue.

   Le `versionCode` augmente à chaque exécution (1000 + numéro d'exécution). Le `versionName` vient du tag ; une
   exécution lancée depuis une branche garde la valeur par défaut de `android/app/build.gradle.kts` (actuellement
   1.1.0). Après une publication, montez-la avec `MARKETING_VERSION` de `project.yml` à la version suivante.

### E2. Test fermé (compte personnel récent)

*Tester et publier → Tests fermés → Créer un canal* : ajoutez au moins **12 testeurs** (liste d'e-mails ou
groupe Google), envoyez-leur le lien d'inscription, et gardez-les inscrits **14 jours d'affilée** (si le
nombre passe sous 12, le compteur repart à zéro). Demandez-leur d'utiliser réellement l'app (la playlist de test,
leurs propres playlists). Ensuite : *Tableau de bord → Demander l'accès à la production* et répondez au
questionnaire sur le test.

Un tag de version publie tout seul sur ce canal (ci-dessus). À la main : *Run workflow* avec la piste `alpha`
(`alpha` = test fermé, `beta` = test ouvert) ; le statut `completed` (par défaut) l'envoie aussitôt en examen,
`draft` le laisse à vérifier et déployer dans la Play Console. Un push sur `main` y envoie aussi chaque build
interne (E1). Pour envoyer aux testeurs le bundle même déjà vérifié en test interne tout de suite, sans nouveau build, lancez
*Promote Android* avec son version code et la piste : il y arrive avec les notes de version, statut `completed`
(envoyé aussitôt en examen) par défaut, ou `draft` (*release_status*) à vérifier et déployer dans la Play Console.

### E3. Production

*Production → Créer une version* (ou workflow avec la piste `production` : la version est créée en
brouillon, à vérifier et envoyer en examen dans la console). Lancez ce workflow depuis le tag `vX.Y.Z`
de la version, choisi dans *Use workflow from → Tags* : le workflow refuse `production` depuis une branche, dont
le bundle garderait le `versionName` par défaut. Publiez la production avec un bundle construit pour
elle (le workflow avec la piste `production`), pas en promouvant une version de test. Choisissez les pays, puis *Envoyer pour
examen*. L'examen prend de quelques heures à quelques jours.

### E4. Android TV

La même app et le même bundle servent Android TV et Google TV : le manifeste déclare le lanceur TV, le workflow
envoie les captures TV (`tvScreenshots`, 1920 × 1080) et la bannière TV (`images/tvBanner.png`, 1280 × 720), et la
description nomme Android TV, comme Google le demande.

1. **Avant de s'inscrire, essayez-la sur votre TV** : l'app n'est pas dans le Play Store de la TV tant que Google
   ne l'a pas approuvée pour la TV. Installez le build de débogage (`com.markedoe.ondo.debug`, à côté de la
   version Play) depuis l'artefact **android-builds** de la dernière exécution du workflow *Android* : sur la TV,
   *Paramètres → Préférences de l'appareil → À propos*, sélectionnez *Build* sept fois, puis *Options pour les
   développeurs → Débogage USB* (ou *Débogage via le réseau*) ; sur un ordinateur du même réseau,
   `adb connect <adresse de la TV>` puis `adb install app-debug.apk`. Parcourez chaque écran à la télécommande
   seulement.
2. **Inscription** : *Tester et publier → Configuration → Paramètres avancés → Facteurs de forme → Ajouter un type
   de version → Android TV*, acceptez les règles d'examen TV. Google vérifie alors l'app selon ses critères de
   qualité des apps TV (de quelques jours à quelques semaines) et envoie le résultat par e-mail ; l'état est sous
   *Android TV* dans les paramètres de l'app.
3. Tant que l'examen TV n'est pas passé, les versions pour téléphones continuent comme avant. Si Google refuse la
   version TV et qu'elle bloque une mise à jour pour téléphones, retirez le type de version TV et réinscrivez-vous
   une fois corrigé.

## F. Points à vérifier avant l'examen

- La fiche dit clairement qu'Ondo **ne fournit aucun contenu** et ne vend pas d'abonnement (déjà dans les
  descriptions et sur l'écran d'accueil de l'app). N'employez aucun terme laissant penser à des chaînes ou
  films « gratuits » : c'est la principale cause de refus des lecteurs IPTV.
- Aucune capture ne montre de logo de chaîne, d'affiche de film ou de marque réelle : les captures
  utilisent le catalogue fictif des tests.
- `targetSdk` = 36 : c'est le minimum demandé par Google Play pour les nouvelles apps et mises à jour
  depuis le 31/08/2026. Sur les grands écrans (≥ 600 dp), Android 16 ignore les verrouillages
  d'orientation : Ondo n'en utilise aucun.
- Testez l'APK de debug (artefact **android-builds** du workflow *Android*) sur un téléphone et une
  tablette : ajout d'une playlist Xtream et M3U, lecture d'un film avec reprise, épisode suivant, direct,
  image dans l'image, thème clair et sombre, rotation, et retour depuis le lecteur.
- Puis installez **la version de test interne** (le bundle de release, réduit et optimisé par R8 — ce que
  la CI ne fait qu'assembler, sans le lancer) depuis le lien de test de la Play Console, et refaites au
  moins : premier lancement, ajout d'une playlist, ouverture d'une fiche, lecture, retour et réouverture
  de l'app (les playlists et la progression doivent être conservées). C'est la version qu'utiliseront les
  testeurs puis le public.
- Si l'app s'arrête, *Réglages → À propos* montre le dernier plantage enregistré sur l'appareil (sans adresses
  ni identifiants), avec la mémoire dont dispose l'appareil : une photo suffit pour le signaler. Un redémarrage de
  tout l'appareil pendant la lecture y est aussi enregistré, avec ce qui était lu : format vidéo et audio, HDR ou
  Dolby Vision, décodeurs, mémoire tampon, mémoire et état thermique de l'appareil (jamais l'adresse ni le titre).

## G. Ce que fait la CI

| Workflow | Quand | Ce qu'il fait |
|---|---|---|
| *Android* | push sur `main` et pull requests à travers son filtre de chemins (tout sauf les changements Apple seuls, de documentation, du site et de la fiche App Store : `android/`, `fixtures/`, scripts et workflows le lancent) ; manuel, où le même filtre s'applique aux changements de la branche depuis `main` ; un push sur `main` déjà testé sur sa branche saute ses jobs Kotlin et app ([CI.fr.md](CI.fr.md)) | tests du cœur Kotlin, tests unitaires et Compose (Robolectric), captures Roborazzi comparées aux références versionnées (`verifyRoborazziDebug` ; les différences dans le résumé du job et l'artefact **android-screenshot-diffs** ; enregistrées à la place tant qu'aucune n'est versionnée), publiées sur la branche `android-screenshots` après un push sur `main` ; lancé à la main sur une branche de travail avec *record_screenshots*, il enregistre les références et les versionne sur cette branche (jamais `main` ni un tag, voir [ANDROID.fr.md](ANDROID.fr.md#captures-décran-et-régressions)), lint (`lintRelease`), APK de debug et bundle de release (artefact **android-builds**), longueurs de la fiche Google Play (titre 30, description courte 80, description complète 4000, notes de version 500 caractères) |
| *TV release check* (`tv-release-check.yml`) | manuel | l'APK de release (réduit par R8, signé avec la clé de debug) sur un émulateur Android TV, piloté avec les touches de la télécommande (`.github/scripts/tv_drive.py`) : premier lancement, une playlist Xtream ajoutée depuis un panel fictif (catalogue petit, grand ou énorme ; un mauvais mot de passe d'abord si demandé), chaque onglet, puis les parcours choisis, chacun depuis un nouveau démarrage, sur des flux faits avec ffmpeg (rows : la télécommande le long des rangées d'affiches de l'onglet Films, filmée ; settings, search, details, player, guide, mylist, playlists, restore, browse, sync, keyboard, bitrate : un film au débit d'un film 4K à côté du catalogue, crash). Un plantage, un ANR ou Android qui arrête l'app fait échouer l'exécution, tout comme un parcours que la télécommande ne peut finir et un Retour depuis une page des Réglages qui ne revient pas sur la ligne qui l'a ouverte. Il demande la limite de mémoire d'un boîtier TV (`heap-size`), appliquée seulement quand l'émulateur laisse adb passer en root, ce que l'image Android TV ne permet en général pas : la limite en vigueur est affichée (`.github/scripts/tv_heap_limit.sh`). Captures, logcat, raisons de sortie, mémoire utilisée et mappage de R8 sont gardés en artefact |
| *Release Android* | tag `v*` (test fermé, fiche, envoyé en examen), *Deliver* après un push au vert (test interne, build seul, puis test fermé) ou manuel | refuse `production` hors d'un tag `v*`, vérifie que le tag est la version des apps, vérifie que les cinq secrets sont définis et que la playlist de démonstration répond, tests, captures copiées dans la fiche, bundle signé avec la clé d'importation (la même app sur chaque canal : aucun badge bêta, dans aucune édition), artefact **ondo-release-bundle**, envoi sur Google Play avec le fichier de mappage de R8 (les plantages dans Android Vitals montrent les noms et lignes de l'app), la fiche et les notes de version (`listing: skip` : le build et ses notes de version seuls, la fiche en examen intacte) ; après un build interne accepté par Google Play, le même version code en test fermé, `completed` avec les notes de version (un examen en cours donne une notice et *Play catch-up* prend le relais, tout autre refus fait passer cette étape au rouge ; le build interne reste livré) |
| *Promote Android* | manuel | publie un version code du test interne en test fermé (`alpha`) ou ouvert (`beta`) avec les notes de version, `completed` (envoyé en examen) ou `draft` (*release_status*), sans nouveau build ; un push sur `main` le fait tout seul pour le test fermé, et un tag `vX.Y.Z` y envoie un nouveau build |
| *Play catch-up* (`play-catchup.yml`) | toutes les heures, manuel (*dry_run* : dit ce qu'il ferait) | attend quand une exécution du groupe `release-android` est en attente (ne la remplace jamais), lit les canaux de test interne et fermé dans une modification qu'il supprime, et quand le test interne a un version code plus récent, vérifie la playlist d'examen et le publie en test fermé, `completed` avec les notes de version, validé avec `ERROR_IF_IN_REVIEW` : un examen en cours donne une notice et l'heure suivante réessaie ; tout autre refus fait échouer l'exécution |
| *Google Play images* (`play-images.yml`) | manuel (*languages* : langues de la fiche ou `all`, par défaut les six autres que l'anglais et le français ; *upload* : `no` par défaut) | produit les captures de la fiche (`PlayStoreScreenshots`) dans chaque langue demandée, les met avec l'image de présentation, la bannière TV et l'icône de `fastlane/metadata-android/<langue>/images` (sans métadonnées dans les PNG), artefact **google-play-images** ; avec `upload: yes`, dans le groupe `release-android`, envoie ces images seules (`fastlane android listing_images` : sans build, sans canal, sans texte, sans notes de version ; une image déjà sur Google Play n'est pas renvoyée), validées avec `ERROR_IF_IN_REVIEW` : pendant un examen en cours, Google Play refuse, rien ne change et le run échoue en le disant ; sinon les images partent à l'examen de la fiche par Google |
| *Google Play status* | manuel | lecture seule : canaux et versions, bundles détenus par Play, fiche (longueurs, nombre d'images de chaque type, captures TV et bannière comprises), groupes Google des testeurs, pays, produits intégrés (les pourboires `tip_small`, `tip_medium`, `tip_large` de `Tips.kt`, lus avec l'API des produits ponctuels et l'ancienne API des produits intégrés : état de chaque produit, options d'achat, prix, titre de la fiche par langue, et les pourboires absents de Play), avis de la dernière semaine (nombre et étoiles), APK générés depuis le dernier bundle, plantages et ANR (API Reporting, à activer dans le projet Google Cloud du compte de service). Ouvre une modification et la supprime, sans jamais la valider ; le journal ne contient ni adresse, ni e-mail, ni texte d'avis. L'examen des modifications et l'examen Android TV ne sont dans aucune API |
