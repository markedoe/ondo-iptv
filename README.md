# Ondo

Lecteur IPTV pour **iPhone / iPad** et **macOS** — films, séries et TV en direct —, en Swift / SwiftUI natif.
Conception et feuille de route : [docs/PLAN.md](docs/PLAN.md).

> Ondo est **uniquement un lecteur** : il n'héberge, ne fournit ni ne vend aucun contenu, chaîne, playlist
> ou abonnement IPTV. Il lit les sources que l'utilisateur ajoute lui-même et qu'il est autorisé à regarder.

## Fonctionnalités

- **Accueil** : carrousel de films et séries à la une, « Continuer à regarder », Ma liste, ajouts récents,
  tendances ; le direct en dernier.
- **Films & séries** : grilles d'affiches, tri, fiches (synopsis, casting, bande-annonce), saisons /
  épisodes, « Continuer à regarder », reprise, épisode suivant automatique, **tendances TMDb**.
- **Sources** : playlists M3U / M3U8 (URL ou fichier), comptes **Xtream Codes** (API ou « forcer M3U »),
  XSPF, JSON, compte Real-Debrid ; plusieurs playlists, actualisation automatique.
- **Live TV** : catégories, numéros, logos, programme en cours + progression, favoris, récents, recherche ;
  chaînes masquées, renommées ou renumérotées.
- **Guide TV** : XMLTV (gzip), guide du fournisseur et/ou URL personnalisées, grille aux titres
  « collants », fiche programme, décalage horaire, repli sur l'EPG court Xtream, **association manuelle
  chaîne ↔ guide**, **recherche dans les programmes**, **rappels** (notifications locales).
- **Replay** (catch-up) : Xtream `timeshift`, modes M3U `default`, `append`, `shift`, `flussonic` ;
  **redémarrer le programme en cours**, revoir un programme passé et revenir au direct depuis le lecteur,
  **pause du direct** (fenêtre DVR HLS, sinon reprise via l'archive).
- **Lecteur** : deux moteurs — **AVPlayer** (PiP, AirPlay, HDR) et **VLC** (MKV, AVI, TS…) avec bascule
  automatique —, zapping, pistes audio / sous-titres, **sous-titres SRT externes**, format d'image,
  vitesse, minuterie, verrouillage, gestes (iPhone), paysage forcé, reconnexion automatique, bascule vers
  une autre version d'une chaîne morte, erreurs expliquées, écran verrouillé / touches média, raccourcis
  clavier, menu Lecture et une fenêtre par flux sur Mac ; **dernière chaîne**, **zapping par numéro**,
  panneau chaînes / guide dans le lecteur.
- **Diffusion** : **AirPlay** (iPhone, iPad, Mac) et **Chromecast** (iPhone, iPad).
- **Multivue** (iPad, Mac) : jusqu'à 4 chaînes, un seul son actif.
- **Profils** (« Qui regarde ? »), PIN de profil, **profils enfant**, contrôle parental par PIN.
- **Listes personnalisées**, favoris réordonnables, catégories masquées.
- **Téléchargements** hors ligne (films, épisodes).
- **Données** : sauvegarde / restauration JSON, cache vidable. Interface en français et en anglais.

## Structure

```
App/                    Application SwiftUI (cible unique iOS + macOS)
Packages/IPTVCore/      Logique métier (Foundation pur, testée sous Linux et macOS)
docs/                   Plan, publication (App Store), version Android
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

## Secrets et vie privée

Le dépôt est public : les clés de publication vivent uniquement dans les secrets GitHub
([docs/RELEASE.md](docs/RELEASE.md)). Un garde-fou bloque les secrets et données personnelles avant tout
commit — hook Claude Code (`.claude/settings.json`), hook git (`git config core.hooksPath .githooks`) et
CI (`.claude/hooks/leak_guard.py` + gitleaks sur tout l'historique).

## Licence

MIT — voir [LICENSE](LICENSE).
