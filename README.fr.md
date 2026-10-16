# Ondo

[English](README.md) · **Français**

Lecteur IPTV pour **iPhone / iPad**, **macOS** et **Android** — films, séries et TV en direct —, natif sur
chaque plateforme (Swift / SwiftUI, Kotlin / Jetpack Compose). Conception et feuille de route :
[docs/PLAN.fr.md](docs/PLAN.fr.md), version Android : [docs/ANDROID.fr.md](docs/ANDROID.fr.md).

> Ondo est **uniquement un lecteur** : il n'héberge, ne fournit ni ne vend aucun contenu, chaîne, playlist
> ou abonnement IPTV. Il lit les sources que l'utilisateur ajoute lui-même et qu'il est autorisé à regarder.

## Fonctionnalités

### iPhone, iPad et Mac

- **Accueil** : carrousel de films et séries à la une, « Continuer à regarder », Ma liste, ajouts récents,
  tendances ; le direct en dernier ; la date de dernière mise à jour de la playlist, au-dessus de son nom.
- **Films & séries** : grilles d'affiches triées par ordre de la playlist, nom, date d'ajout, date de sortie
  ou note, fiches (synopsis, casting, bande-annonce), saisons /
  épisodes, « Continuer à regarder », reprise, épisode suivant automatique, **tendances TMDb** ; **touchez un
  acteur ou un réalisateur** pour voir ses autres films et séries de la playlist (parmi les fiches déjà
  ouvertes, ou tous avec un jeton TMDb) ; un genre, l'année de sortie ou un prix (puis son festival) ouvre les
  titres qui le partagent ; quand l'image de fond du fournisseur manque ou ne se charge pas, la fiche affiche
  celle de TMDb (avec un jeton) ou l'affiche floutée.
- **Recherche** (la loupe en haut de chaque onglet sur iPhone) : chaînes, programmes, films et séries,
  **acteurs et réalisateurs par leur nom**, **filtrables par années de sortie** et **par festival ou cérémonie** (lauréats seulement si vous voulez).
- **Prix** : Palme d'or, Oscars, Lion d'or, Ours d'or, César, BAFTA, Golden Globes… sur les fiches des films
  (lauréat, en compétition ou nommé) et une rangée « Films primés » dans Films. Les données viennent de
  [Wikidata](https://www.wikidata.org) (CC0), sont embarquées dans l'app et rapprochées sur l'appareil ; un
  workflow mensuel les rafraîchit et les apps récupèrent le fichier plus récent une fois par semaine, sauf si
  c'est désactivé dans les Réglages ([tools/awards](tools/awards/README.fr.md)).
- **Sources** : playlists M3U / M3U8 (URL ou fichier), comptes **Xtream Codes** (API ou « forcer M3U »),
  XSPF, JSON ; plusieurs playlists, actualisation automatique. (Une source Real-Debrid existe dans le
  code mais est désactivée dans la version App Store : `FeatureFlags.realDebrid`.)
- **Live TV** : catégories, numéros, logos, programme en cours + progression, Ma liste, récents, recherche ;
  chaînes masquées, renommées ou renumérotées.
- **Guide TV** : XMLTV (gzip), guide du fournisseur et/ou URL personnalisées, grille aux titres
  « collants », fiche programme, décalage horaire, repli sur l'EPG court Xtream, **association manuelle
  chaîne ↔ guide**, **recherche dans les programmes**, **rappels** (notifications locales).
- **Replay** (catch-up) : Xtream `timeshift`, modes M3U `default`, `append`, `shift`, `flussonic` ;
  **redémarrer le programme en cours**, revoir un programme passé et revenir au direct depuis le lecteur,
  **pause du direct** (fenêtre DVR HLS, sinon reprise via l'archive).
- **Lecteur** : deux moteurs — **AVPlayer** (PiP, AirPlay, HDR) et **VLC** (MKV, AVI, TS…) avec bascule
  automatique —, zapping, pistes audio / sous-titres, **sous-titres SRT externes**, **recherche de
  sous-titres en ligne** (OpenSubtitles, SubDL et SubSource avec votre clé, Addic7ed sans clé), format d'image,
  vitesse, minuterie, verrouillage, gestes (iPhone), paysage forcé, reconnexion automatique, bascule vers
  une autre version d'une chaîne morte, erreurs expliquées, écran verrouillé / touches média, raccourcis
  clavier, menu Lecture, une fenêtre par flux et **plein écran** (bouton, touche F, double-clic) sur Mac ;
  **reprise** au point exact, **dernière chaîne**, **zapping par numéro**,
  panneau chaînes / guide dans le lecteur ; **mémoire tampon** réglable pour le direct et pour les films et
  séries, **statistiques du flux** qui distinguent un gel dû au réseau d'un gel dû au lecteur, et **Signaler un
  problème…** : un rapport de lecture sans adresse, identifiant ni titre, à partager ou envoyer dans un ticket
  GitHub.
- **Diffusion** : **AirPlay** (iPhone, iPad, Mac : la vidéo part vers la TV, pas seulement le son) et
  **Chromecast** (iPhone, iPad).
- **Multivue** (iPad, Mac) : jusqu'à 4 chaînes, un seul son actif.
- **Profils** (« Qui regarde ? »), PIN de profil, **profils enfant**, contrôle parental par PIN.
- **Ma liste** (un onglet sur iPhone) : films, séries et chaînes au même endroit, réordonnables ;
  **collections** pour les regrouper à votre façon.
- **Catégories** : masquez d'un coup une langue ou un pays (les fournisseurs commencent le nom des catégories
  par des codes comme `|PT|` ou `FR:`), une règle qui couvre aussi les catégories ajoutées plus tard ;
  proposé une fois quand une playlist en compte beaucoup (garder vos langues, choisir, ou plus tard) ;
  masquez de la même façon un **style** (manga et anime, enfants, documentaires…) ou n'importe quel mot des noms
  de catégories (Bollywood…) ; ou masquez les catégories une par une. Le bouton de filtre est sur les écrans
  Films, Séries et Live TV.
- **Téléchargements** hors ligne (films, épisodes), avec leur vitesse et le temps restant, la raison en clair
  quand le fournisseur refuse, limite les connexions ou n'envoie rien, et leurs détails à partager.
- **Données** : sauvegarde / restauration JSON, cache vidable.
- **Aide** dans l'app (Réglages, et Aide, Aide Ondo sur Mac) : une page par fonctionnalité, dans chaque langue.
- **Langues** : français, anglais, espagnol, portugais, chinois (simplifié), arabe (de droite à gauche) et
  hindi.

### Android (téléphones et tablettes)

- **Accueil** : carrousel, « Continuer à regarder », Ma liste, ajouts récents, chaînes ; la date de dernière
  mise à jour de la playlist, au-dessus de son nom.
- **Films & séries** : une rangée par catégorie, grilles filtrables et triées comme sur Apple (date de
  sortie comprise), fiches (Reprendre, Depuis le début,
  épisode suivant, saisons, Titres similaires) ; le nom d'un réalisateur ou d'un acteur ouvre ses autres
  films et séries (parmi les fiches déjà ouvertes), et un genre, l'année ou un prix les titres qui le
  partagent.
- **Live TV** : catégories, Ma liste, programme en cours et suivant ; **recherche** (la loupe de chaque écran),
  filtrable par années de sortie ou par festival / cérémonie (lauréats seulement, au choix), et les acteurs et
  réalisateurs par leur nom, comme sur Apple.
- **Guide TV** (le bouton calendrier de TV en direct) : une grille des chaînes choisies à cet endroit, et des
  **rappels**, une notification quelques minutes avant le début d'un programme.
- Onglet **Ma liste** : les films, séries et chaînes gardés, et vos **collections** (Collection sur la fiche d'un film
  ou d'une série), comme sur Apple.
- **Catégories** : masquées par code de langue ou de pays (`|PT|`, `FR:`…), par style ou par mot (manga,
  Bollywood…) ou une par une, comme sur Apple.
- **Aide** dans les Réglages.
- **Prix** sur les fiches des films et une rangée « Films primés » dans Films, avec les mêmes données embarquées.
- **Sources** : playlists M3U / M3U8, XSPF, JSON et comptes **Xtream Codes** (même cœur, porté en Kotlin et
  testé avec les mêmes cas).
- **Lecteur** (Media3 / ExoPlayer) : HLS, MPEG-TS, MP4, MKV, en-têtes du fournisseur, adresses de secours,
  reprise, image dans l'image, commandes multimédia, retour au direct.
- **Réglages** : playlists, apparence, catégories adultes masquées par défaut ; écran d'accueil avec démo.
- À venir : Android TV, replay, Chromecast, téléchargements, profils et PIN, sous-titres en ligne. Détails : [docs/ANDROID.fr.md](docs/ANDROID.fr.md).

## Langues

Les apps (iPhone, iPad, Mac et Android) existent en sept langues, choisies d'après les réglages de l'appareil, ou
pour Ondo seul dans le réglage de langue par app du système, qu'ouvre **Réglages → Langue de l'app** (iPhone, iPad,
Android 13 et plus ; sur Mac, Réglages Système → Général → Langue et région → Applications) :

| Langue | Apple (`App/Resources/Localizable.xcstrings`) | Android (`android/app/src/main/res/`) |
|---|---|---|
| Anglais (langue de développement) | `en` | `values/` |
| Français | `fr` | `values-fr/` |
| Espagnol | `es` | `values-es/` |
| Portugais (formulations brésiliennes) | `pt` | `values-pt/` |
| Chinois (simplifié) | `zh-Hans` | `values-zh-rCN/` |
| Arabe (de droite à gauche) | `ar` | `values-ar/` |
| Hindi | `hi` | `values-hi/` |

Chaque texte d'une nouvelle fonctionnalité est traduit dans les sept langues dans le même changement, pluriels
compris. La CI y veille : `check_translations.py` échoue s'il manque une traduction Apple, le lint Android s'il
en manque une sur Android. Les fiches des stores, le site et cette documentation sont en anglais et en français.

## Structure

```
App/                    Application SwiftUI (cible unique iOS + macOS)
Packages/IPTVCore/      Logique métier (Foundation pur, testée sous Linux et macOS)
android/app/            Application Android (Jetpack Compose, Media3)
android/core/           Logique métier en Kotlin pur (port d'IPTVCore, mêmes cas de test)
fastlane/               Publication App Store et Google Play, fiches des deux stores
docs/                   Plan, publication (App Store, Google Play), version Android
project.yml             Projet XcodeGen
```

## Compiler

Prérequis : Xcode 16+ et [XcodeGen](https://github.com/yonaskolb/XcodeGen).

```bash
brew install xcodegen
xcodegen generate
open Ondo.xcodeproj
```

Choisissez le schéma **Ondo** puis un iPhone (simulateur ou appareil) ou **My Mac**. Pour un appareil,
sélectionnez votre équipe de signature dans l'onglet *Signing & Capabilities*.

### Dépendances

- [IPTVCore](Packages/IPTVCore) (ce dépôt)
- [VLCKit](https://code.videolan.org/videolan/VLCKit) 3.6 (LGPL 2.1) via
  [vlckit-spm](https://github.com/tylerjonesio/vlckit-spm), pour les formats non pris en charge par AVFoundation.
- [Google Cast SDK](https://developers.google.com/cast) 4.8 (iPhone/iPad) via
  [google-cast-sdk](https://github.com/SRGSSR/google-cast-sdk), pour diffuser sur Chromecast.
- [Nuke / NukeUI](https://github.com/kean/Nuke) 12 (MIT), chargement et cache des logos et affiches.

Tests du cœur :

```bash
cd Packages/IPTVCore && swift test
```

### Ce que vérifie la CI en plus des tests unitaires

Certains défauts n'apparaissent qu'en conditions réelles. Les tests sur données simulées ne les voient pas :

- **Ajout d'une playlist de bout en bout** (`AppTests/PlaylistOverHTTPTests.swift`, test UI
  `testAddingAPlaylistByURL`) : un vrai serveur HTTP local, une redirection, l'identité « lecteur »
  envoyée aux serveurs, les erreurs HTTP, et un panel Xtream qui décode comme PHP. Un mot de passe avec
  `+ / & =` doit arriver intact.
- **Reprise sur un vrai flux** (`ResumePlaybackTests`) : la lecture démarre à la position enregistrée, une
  pause faite pendant l'ouverture reste en pause, et le début du film n'écrase jamais le point de reprise.
- **HTTP en clair autorisé partout** (`AppTransportSecurityTests`, `NetworkSecurityConfigTest` sur Android).
- **Configuration livrée** (`ShippedConfigurationTests`) : découverte Chromecast, son écran verrouillé,
  question d'export, manifeste de confidentialité.
- **Scripts** (`.github/scripts/check_*.py`, exécutables en local) : textes traduits dans toutes les langues,
  manifeste de confidentialité couvrant les API utilisées par le code livré, entitlements du bac à sable Mac,
  neutralité du dépôt (aucune autre app IPTV citée, jamais « gratuit » pour qualifier l’IPTV, chaînes fictives
  dans les tests).
- **Compilation Release** (le code `#if DEBUG` est exclu de la version App Store).
- **Avant chaque publication** : la playlist de démonstration utilisée par la review d'Apple et de Google
  doit répondre.

## Android

Prérequis : JDK 17+ et le SDK Android (Android Studio). Ouvrez le dossier `android/` dans Android Studio,
ou en ligne de commande :

```bash
cd android
./gradlew -p core test                 # cœur Kotlin
./gradlew :app:testDebugUnitTest       # tests unitaires et Compose (Robolectric)
./gradlew :app:recordRoborazziDebug    # + captures de la fiche Google Play
./gradlew :app:assembleDebug           # APK de debug
```

Pour tester sur un téléphone sans Google Play, installez l'APK de debug de l'artefact **android-builds** du
dernier passage du workflow *Android*. Publication sur Google Play : [docs/RELEASE-ANDROID.fr.md](docs/RELEASE-ANDROID.fr.md).

## Secrets et vie privée

Le dépôt est public : les clés de publication vivent uniquement dans les secrets GitHub
([docs/RELEASE.fr.md](docs/RELEASE.fr.md)). Un garde-fou bloque les secrets et données personnelles avant tout
commit — hook Claude Code (`.claude/settings.json`), hook git (`git config core.hooksPath .githooks`) et
CI (`.claude/hooks/leak_guard.py` + gitleaks sur tout l'historique).

## Licence

MIT — voir [LICENSE](LICENSE).
