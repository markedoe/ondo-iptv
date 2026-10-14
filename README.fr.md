# Ondo

[English](README.md) · **Français**

Lecteur IPTV pour **iPhone / iPad**, **macOS** et **Android** — films, séries et TV en direct —, natif sur
chaque plateforme (Swift / SwiftUI, Kotlin / Jetpack Compose). Conception et feuille de route :
[docs/PLAN.fr.md](docs/PLAN.fr.md), version Android : [docs/ANDROID.fr.md](docs/ANDROID.fr.md).

> Ondo est **uniquement un lecteur** : il n'héberge, ne fournit ni ne vend aucun contenu, chaîne, playlist
> ou abonnement IPTV. Il lit les sources que l'utilisateur ajoute lui-même et qu'il est autorisé à regarder.

## Fonctionnalités

- **Accueil** : carrousel de films et séries à la une, « Continuer à regarder », Ma liste, ajouts récents,
  tendances ; le direct en dernier.
- **Films & séries** : grilles d'affiches, tri, fiches (synopsis, casting, bande-annonce), saisons /
  épisodes, « Continuer à regarder », reprise, épisode suivant automatique, **tendances TMDb**.
- **Sources** : playlists M3U / M3U8 (URL ou fichier), comptes **Xtream Codes** (API ou « forcer M3U »),
  XSPF, JSON ; plusieurs playlists, actualisation automatique. (Une source Real-Debrid existe dans le
  code mais est désactivée dans la version App Store : `FeatureFlags.realDebrid`.)
- **Live TV** : catégories, numéros, logos, programme en cours + progression, favoris, récents, recherche ;
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
  panneau chaînes / guide dans le lecteur.
- **Diffusion** : **AirPlay** (iPhone, iPad, Mac : la vidéo part vers la TV, pas seulement le son) et
  **Chromecast** (iPhone, iPad).
- **Multivue** (iPad, Mac) : jusqu'à 4 chaînes, un seul son actif.
- **Profils** (« Qui regarde ? »), PIN de profil, **profils enfant**, contrôle parental par PIN.
- **Listes personnalisées**, favoris réordonnables, catégories masquées.
- **Téléchargements** hors ligne (films, épisodes), avec la raison en clair quand le fournisseur refuse,
  limite les connexions ou n'envoie rien.
- **Données** : sauvegarde / restauration JSON, cache vidable. Interface en français et en anglais.

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
- **Scripts** (`.github/scripts/check_*.py`, exécutables en local) : textes traduits en français,
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
