# Ondo

[English](README.md) · **Français**

[![CI](https://github.com/markedoe/ondo-iptv/actions/workflows/ci.yml/badge.svg)](https://github.com/markedoe/ondo-iptv/actions/workflows/ci.yml) [![Android](https://github.com/markedoe/ondo-iptv/actions/workflows/android.yml/badge.svg)](https://github.com/markedoe/ondo-iptv/actions/workflows/android.yml) [![Security](https://github.com/markedoe/ondo-iptv/actions/workflows/security.yml/badge.svg)](https://github.com/markedoe/ondo-iptv/actions/workflows/security.yml) [![License: FSL-1.1-MIT](https://img.shields.io/badge/license-FSL--1.1--MIT-blue.svg)](LICENSE)

Lecteur IPTV pour **iPhone / iPad**, **macOS** et **Android** (téléphones, tablettes et Android TV) — films, séries et TV en direct —, natif sur
chaque plateforme (Swift / SwiftUI, Kotlin / Jetpack Compose). Il fonctionne à partir d'iOS / iPadOS 17, de
macOS 14 et d'Android 8.0 (API 26). Conception et feuille de route : [docs/PLAN.fr.md](docs/PLAN.fr.md),
version Android : [docs/ANDROID.fr.md](docs/ANDROID.fr.md).

> Ondo est **uniquement un lecteur** : il n'héberge, ne fournit ni ne vend aucun contenu, chaîne, playlist
> ou abonnement IPTV. Il lit les sources que l'utilisateur ajoute lui-même et qu'il est autorisé à regarder.

> **Bêta.** Ondo est encore jeune : écrans et fonctions peuvent changer, et des bugs sont à prévoir. L'édition
> TestFlight (iPhone, iPad, Mac) et les builds de test Google Play affichent un badge *Bêta*. Vos retours et
> signalements de bugs sont les bienvenus dans les [issues](https://github.com/markedoe/ondo-iptv/issues).

## Captures

La playlist de démonstration intégrée (titres fictifs), capturée par la CI à chaque changement de l'interface.

<p align="center">
  <img src="https://raw.githubusercontent.com/markedoe/ondo-iptv/appstore-screenshots/fr-FR/01-home-iphone-max.png" width="200" alt="L'accueil sur iPhone">
  <img src="https://raw.githubusercontent.com/markedoe/ondo-iptv/appstore-screenshots/fr-FR/02-movie-iphone-max.png" width="200" alt="Une fiche film sur iPhone">
  <img src="https://raw.githubusercontent.com/markedoe/ondo-iptv/appstore-screenshots/fr-FR/03-series-iphone-max.png" width="200" alt="Une fiche série sur iPhone">
</p>
<p align="center">
  <img src="https://raw.githubusercontent.com/markedoe/ondo-iptv/appstore-screenshots/fr-FR/01-home-ipad-13.png" width="410" alt="L'accueil sur iPad">
  <img src="https://raw.githubusercontent.com/markedoe/ondo-iptv/appstore-screenshots/mac/fr-FR/01-home-mac.png" width="410" alt="L'accueil sur Mac">
</p>
<p align="center">
  <img src="https://raw.githubusercontent.com/markedoe/ondo-iptv/android-screenshots/play/fr-FR/phoneScreenshots/01-home.png" width="200" alt="L'accueil sur Android">
  <img src="https://raw.githubusercontent.com/markedoe/ondo-iptv/android-screenshots/play/fr-FR/phoneScreenshots/02-movie.png" width="200" alt="Une fiche film sur Android">
  <img src="https://raw.githubusercontent.com/markedoe/ondo-iptv/android-screenshots/play/fr-FR/phoneScreenshots/05-live.png" width="200" alt="La TV en direct sur Android">
</p>
<p align="center">
  <img src="https://raw.githubusercontent.com/markedoe/ondo-iptv/android-screenshots/play/fr-FR/tvScreenshots/01-home.png" width="410" alt="L'accueil sur Android TV">
  <img src="https://raw.githubusercontent.com/markedoe/ondo-iptv/android-screenshots/play/fr-FR/tvScreenshots/02-movie.png" width="410" alt="La fiche d'un film sur Android TV">
</p>

## Fonctionnalités

### iPhone, iPad et Mac

- **Accueil** : films et séries à la une dans un grand cadre arrondi (titre, informations et boutons à
  l'intérieur), films et séries en cours (une rangée chacun), Ma liste, nouveautés, tendances ; le direct en
  dernier ; la date de dernière mise à jour de la playlist, au-dessus de son nom. Des pages noires, du blanc pour
  ce qui est choisi ; les cartes se soulèvent sous le pointeur, le focus du clavier ou le doigt.
- **Films & séries** : grilles d'affiches triées par ordre de la playlist, nom, date d'ajout, date de sortie
  ou note, films déjà vus masqués si vous le souhaitez (la recherche et Ma liste les gardent), fiches (synopsis, casting, bande-annonce), saisons /
  épisodes, « Continuer à regarder », reprise, épisode suivant automatique, **tendances TMDb** ; **touchez un
  acteur ou un réalisateur** pour voir ses autres films et séries de la playlist (les séries d'après la liste
  du fournisseur, les films parmi les fiches déjà ouvertes et l'index des personnes gardé sur l'appareil, ou tous
  avec un jeton TMDb) ; un genre, l'année de sortie ou un prix (puis son festival) ouvre les
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
  XSPF, JSON ; plusieurs playlists, actualisation automatique.
- **TV en direct** : catégories, numéros, logos, programme en cours + progression, Ma liste, récents, recherche ;
  chaînes masquées, renommées ou renumérotées.
- **Guide TV** : XMLTV (gzip), guide du fournisseur et/ou URL personnalisées, grille aux titres
  « collants », fiche programme, décalage horaire, repli sur l'EPG court Xtream, **association manuelle
  chaîne ↔ guide**, **recherche dans les programmes**, **rappels** (notifications locales).
- **Replay** (catch-up) : Xtream `timeshift`, modes M3U `default`, `append`, `shift`, `flussonic` ;
  **redémarrer le programme en cours**, revoir un programme passé et revenir au direct depuis le lecteur,
  **pause du direct** (fenêtre DVR HLS, sinon reprise via l'archive).
- **Lecteur** : deux moteurs — **AVPlayer** (PiP, AirPlay, HDR) et **VLC** (MKV, AVI, TS…) avec bascule
  automatique —, **films MKV remuxés pour AVPlayer** sur iPhone et iPad (VLC les lit sur Mac), sans transcodage (HDR10, HLG, Dolby Vision,
  Dolby Atmos, PiP), VLC lisant ceux qu'il ne sait pas décoder, zapping, pistes audio / sous-titres, **sous-titres SRT externes**, **recherche de
  sous-titres en ligne** (OpenSubtitles, SubDL et SubSource avec votre clé, vérifiée auprès du service à l'enregistrement, Addic7ed sans clé), format d'image,
  vitesse, minuterie, verrouillage, gestes (iPhone), paysage forcé, reconnexion automatique, bascule vers
  une autre version d'une chaîne morte, erreurs expliquées, écran verrouillé / touches média, raccourcis
  clavier, menu Lecture, une fenêtre par flux et **plein écran** (bouton, touche F, double-clic) sur Mac ;
  **reprise** au point exact, **dernière chaîne**, **zapping par numéro**,
  panneau chaînes / guide dans le lecteur ; **mémoire tampon** réglable pour le direct et pour les films et
  séries, **statistiques du flux** qui distinguent un gel dû au réseau d'un gel dû au lecteur et montrent le
  format (MKV, MP4…), et **Signaler un problème…** : un rapport de lecture sans adresse, identifiant ni titre, à
  partager ou envoyer dans un ticket GitHub. Les fiches des films et séries indiquent le format qu'envoie le
  fournisseur.
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
  Films, Séries et TV en direct.
- **Téléchargements** hors ligne (films, épisodes ; édition TestFlight, pas celle de l'App Store), avec leur vitesse et le temps restant, la raison en clair
  quand le fournisseur refuse, limite les connexions ou n'envoie rien, et leurs détails à partager.
- **Données** : sauvegarde / restauration JSON, cache vidable. Les affiches, les vignettes et les pages des films et
  séries restent sur l'appareil : elles s'affichent tout de suite au lancement suivant, et les pages s'ouvrent même
  quand le fournisseur ne répond pas.
- **TMDb**, avec votre propre jeton (Réglages → TMDb, vérifié auprès de TMDb à l'enregistrement, transmis par la
  synchronisation) : une rangée Tendances sur l'accueil et dans le carrousel ; pour les titres de l'accueil et les
  fiches que vous ouvrez, l'image de fond de TMDb quand celle du fournisseur manque ou ne se charge pas, la
  bande-annonce de TMDb (d'abord dans la langue de l'app, puis en anglais) quand le fournisseur n'en donne pas, et le
  nombre de votes à côté de la note ; les personnes que TMDb connaît dans la recherche, avec leur photo, et tous les
  titres de la playlist sur la page d'un acteur ou d'un réalisateur, sous sa photo. Les réponses de TMDb sont gardées
  sur l'appareil (30 jours, 7 pour un titre que TMDb ne connaît pas) : les fiches les affichent tout de suite, même
  hors connexion, et seul ce qui manque ou est ancien est redemandé, en arrière-plan, quelques requêtes par seconde.
  Un index local des acteurs et réalisateurs, rempli à partir des fiches et des listes de séries du fournisseur et de
  TMDb, trouve les personnes dans la recherche et leurs titres tout de suite, sans réseau. Une image du fournisseur
  cassée n'est plus essayée pendant 7 jours ; une image qui échoue sur une erreur réseau est réessayée une fois. Vider
  le cache efface le tout.
- **Synchroniser sur ce réseau** (Réglages, désactivé tant qu'on ne l'active pas) : iPhone, iPad et Mac se
  connectent aux appareils Android du même réseau où Ondo est ouvert et gardent les mêmes playlists, Ma liste,
  collections, progression et jeton TMDb (profil principal uniquement ; deux appareils Apple se synchronisent via un
  appareil Android). Adresses locales uniquement ([docs/SYNC.fr.md](docs/SYNC.fr.md)).
- **Aide** dans l'app (Réglages, et Aide, Aide Ondo sur Mac) : une page par fonctionnalité, dans chaque langue.
- **Langues** : français, anglais, espagnol, portugais, chinois (simplifié), arabe (de droite à gauche) et
  hindi.

### Android (téléphones, tablettes et TV)

- **Accueil** : carrousel, films et séries en cours (une rangée chacun ; appuyez longuement sur une carte, ou
  maintenez OK sur une TV, pour la retirer), Ma liste, nouveautés, chaînes ; la date de dernière mise à jour de la
  playlist, au-dessus de son nom.
- **Films & séries** : une rangée par catégorie, grilles filtrables et triées comme sur Apple (date de
  sortie comprise), films déjà vus masqués si vous le souhaitez, fiches (Reprendre, Depuis le début,
  épisode suivant, saisons, la bande-annonce du fournisseur sur YouTube, Titres similaires) ; le nom d'un réalisateur ou d'un acteur ouvre ses autres
  films et séries (les séries toujours, les films parmi les fiches déjà ouvertes), et un genre, l'année ou un prix les titres qui le
  partagent.
- **TV en direct** : catégories, Ma liste, programme en cours et suivant.
- **Recherche** (la loupe d'Accueil, Films, Séries, TV en direct et Ma liste ; sur une TV, dans la barre du haut après
  les onglets, une page à part que Retour ferme) : chaînes, films, séries et **acteurs et réalisateurs par leur nom**, filtrables
  par années de sortie ou par festival / cérémonie (lauréats seulement, au choix) ; pas de recherche dans les
  programmes. Elle s'ouvre vide à chaque fois, et un onglet choisi ensuite s'ouvre sur sa propre page.
- **Guide TV** (le bouton calendrier de TV en direct) : une grille des chaînes choisies à cet endroit, et des
  **rappels**, une notification quelques minutes avant le début d'un programme.
- Onglet **Ma liste** : les films, séries et chaînes gardés, et vos **collections** (Collection sur la fiche d'un film
  ou d'une série), comme sur Apple.
- **Catégories** : masquées par code de langue ou de pays (`|PT|`, `FR:`…), par style ou par mot (manga,
  Bollywood…) ou une par une, comme sur Apple.
- **Aide** dans les Réglages.
- **Prix** sur les fiches des films et une rangée « Films primés » dans Films, avec les mêmes données
  embarquées, mises à jour une fois par semaine sauf si c'est désactivé dans les Réglages.
- **Sources** : playlists M3U / M3U8, XSPF, JSON et comptes **Xtream Codes** (même cœur, porté en Kotlin et
  testé avec les mêmes cas).
- **Lecteur** (Media3 / ExoPlayer) : HLS, MPEG-TS, MP4, MKV, en-têtes du fournisseur, adresses de secours,
  reprise, image dans l'image, commandes multimédia ; une chaîne qui prend du retard revient d'elle-même au
  direct ; les **langues de l'audio et des sous-titres** que vous choisissez servent pour les flux suivants (les
  sous-titres forcés restent affichés avec Désactivé) ; Plus → **Format d'image** (Ajuster, Remplir (rogner),
  Étirer, gardé pour les vidéos suivantes), **Taille des sous-titres** (de Petite à Très grande, gardée aussi) et
  **Minuterie de sommeil** (de 15 à 120 minutes, puis le lecteur se
  met en pause) ; erreurs expliquées, **mémoire tampon** réglable pour le direct et pour les films et séries, une
  mémoire tampon qui tient dans la mémoire de l'app (un film 4K se lit sur un boîtier TV),
  **statistiques du flux** qui distinguent un gel dû au réseau d'un gel dû au lecteur et montrent le format
  (MKV, MP4…), comme sur Apple, et **Signaler un problème…** : un rapport de lecture sans adresse, identifiant
  ni titre, à partager ou envoyer dans un ticket GitHub. Les fiches des films et séries indiquent le format
  qu'envoie le fournisseur.
- **Lecteur du téléphone** : ses propres commandes, avec des gestes : un toucher les affiche, deux touchers sur un
  côté sautent 10 secondes (les touchers s'additionnent), un doigt posé lit en 2×, des glissements règlent la
  luminosité et le volume, un pincement remplit l'écran, un verrou ; Sous-titres, Audio, Vitesse et Format d'image
  dans des volets ; **À suivre** à la fin d'un épisode ; sur une chaîne en direct, une liste **Chaînes** avec ce qui
  passe.
- **Replay**, quand le fournisseur garde une archive : **Lire le replay** sur un programme passé du guide, **Depuis
  le début** sur celui en cours (guide et lecteur), Retour au direct ; une chaîne en direct mise en pause plus de
  10 secondes reprend là où elle a été arrêtée. Une archive Xtream est demandée à l'heure du serveur.
- **Données** : les affiches, les vignettes et les pages des films et séries restent sur l'appareil, comme sur
  Apple : elles s'affichent tout de suite au lancement suivant, et les pages s'ouvrent même quand le fournisseur
  ne répond pas.
- **Synchroniser sur ce réseau** (Réglages, désactivé tant qu'on ne l'active pas) : tant qu'Ondo est ouvert, les
  appareils Android d'un réseau domestique gardent les mêmes playlists, Ma liste, collections, progression et jeton
  TMDb, et iPhone, iPad et Mac se synchronisent avec eux ; directement entre les appareils, vers des adresses locales
  uniquement, rien ne passe par internet ([docs/SYNC.fr.md](docs/SYNC.fr.md)).
- **TMDb**, avec votre propre jeton (Réglages → TMDb, vérifié auprès de TMDb à l'enregistrement, transmis par la
  synchronisation) : une rangée Tendances sur l'accueil et dans le carrousel, l'image de fond de TMDb quand celle du
  fournisseur manque ou ne se charge pas, la bande-annonce de TMDb (d'abord dans la langue de l'app) quand le
  fournisseur n'en donne pas, les photos du casting sur la fiche d'un titre, les personnes que TMDb
  connaît dans la recherche, et tous les titres de la playlist sur la page d'un acteur ou d'un réalisateur.
- **Réglages** : playlists, catégories adultes masquées par défaut, langues et catégories, langue de
  l'app, mise à jour automatique des prix, mémoire tampon du lecteur, jeton TMDb ; écran d'accueil avec démo.
- **Android TV / Google TV** : Ondo dans le lanceur de la TV avec sa bannière, chaque écran à la télécommande
  (une sélection bien visible, qui revient sur la carte que vous avez quittée, des mises en page qui restent à
  l'écart des bords de l'écran) ; un lecteur dessiné pour la télécommande : de grands boutons, une barre de
  progression pour se déplacer dans la vidéo, des panneaux Sous-titres et Audio qui listent les pistes par langue (Audio indique si le son multicanal part tel quel vers l'ampli ou est décodé par la TV),
  Vitesse et Plus ; OK met en pause, Gauche et Droite reculent et avancent de 10 secondes quand le flux le permet
  (sinon elles affichent les commandes), Haut et Bas, CH+ / CH−, les chiffres et la chaîne précédente changent de
  chaîne ; un panneau Chaînes avec ce qui passe, Depuis le début, À suivre à la fin d'un épisode ; un film à sa
  propre fréquence d'image quand la TV est réglée pour l'adapter au contenu (Android 12 et plus) ; maintenez OK
  sur une chaîne pour l'ajouter à Ma liste ; Aide, Avec la télécommande. Les règles de
  confidentialité et la page d'aide s'ouvrent dans l'app.
- À venir : Chromecast, téléchargements, profils et PIN, sous-titres en ligne, un second moteur
  (libVLC). Détails : [docs/ANDROID.fr.md](docs/ANDROID.fr.md).

## Langues

Les apps (iPhone, iPad, Mac et Android) existent en sept langues, choisies d'après les réglages de l'appareil, ou
pour Ondo seul dans le réglage de langue par app du système. **Réglages → Langue de l'app** l'ouvre : la page
d'Ondo dans l'app Réglages (iPhone, iPad), Réglages Système → Général → Langue et région, où Ondo est sous
Applications (Mac), ou la page de langue d'Ondo (Android 13 et plus). Avant Android 13, qui n'a pas de réglage
par app, il ouvre les langues de l'appareil :

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
compris. La CI y veille : `check_translations.py` échoue s'il manque une traduction Apple ou si ses paramètres
(`%@`, `%lld`) ou ses formes du pluriel diffèrent, le lint Android s'il en manque une sur Android, et
`TranslationFormatsTest` si une traduction Android ne peut pas être formatée. Les fiches des stores, le site et
cette documentation sont en anglais et en français.

## Structure

```
App/                    Application SwiftUI (cible unique iOS + macOS)
AppTests/               Tests unitaires de l'app
UITests/, MacUITests/   Tests UI et captures App Store (iPhone et iPad, Mac)
Packages/IPTVCore/      Logique métier (Foundation et la zlib du système, testée sous Linux et macOS)
android/app/            Application Android (Jetpack Compose, Media3)
android/core/           Logique métier en Kotlin pur (port d'IPTVCore, mêmes cas de test)
fastlane/               Publication App Store et Google Play, fiches des deux stores
funding/                Pages de soutien sur Stripe, par app (docs/FUNDING.fr.md)
docs/                   Plan, publication (App Store, Google Play), version Android, pages de soutien
site/                   Site web (GitHub Pages) : accueil, assistance, confidentialité
tools/                  Générateur des données de prix, banc de test de la lecture
project.yml             Projet XcodeGen
```

## Compiler

Prérequis : Xcode 26 (celui de la CI et des publications) et [XcodeGen](https://github.com/yonaskolb/XcodeGen).

```bash
brew install xcodegen
xcodegen generate
open Ondo.xcodeproj
```

Choisissez le schéma **Ondo** puis un iPhone (simulateur ou appareil) ou **My Mac**. Pour un appareil,
sélectionnez votre équipe de signature dans l'onglet *Signing & Capabilities*.

Un build Debug (par défaut) comprend les téléchargements de l'édition TestFlight
(`#if TESTFLIGHT_DOWNLOADS || DEBUG`) ; l'édition App Store est un build Release sans `TESTFLIGHT_DOWNLOADS`
([docs/RELEASE.fr.md](docs/RELEASE.fr.md)).

### Dépendances

Apple :

- [IPTVCore](Packages/IPTVCore) (ce dépôt)
- [VLCKit](https://code.videolan.org/videolan/VLCKit) 3.6 (LGPL 2.1) via
  [vlckit-spm](https://github.com/tylerjonesio/vlckit-spm), pour les formats non pris en charge par AVFoundation.
- [Google Cast SDK](https://developers.google.com/cast) 4.8 (iPhone/iPad) via
  [google-cast-sdk](https://github.com/SRGSSR/google-cast-sdk), pour diffuser sur Chromecast. Contrairement aux
  autres, le SDK est propriétaire : il appartient à Google et relève de ses conditions.
- [Nuke / NukeUI](https://github.com/kean/Nuke) 12 (MIT), chargement et cache des logos et affiches.

Android (`android/gradle/libs.versions.toml`), tous sous licence Apache 2.0 :

- [AndroidX Media3](https://developer.android.com/media/media3) 1.11 (ExoPlayer, HLS, MediaSession), le lecteur.
- [Jetpack Compose](https://developer.android.com/compose) (BOM 2026.09), Material 3, Navigation, Lifecycle.
- [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager) 2.12, rappels de
  programmes.
- [Coil](https://github.com/coil-kt/coil) 3.6, chargement et cache des logos et affiches.
- [OkHttp](https://github.com/square/okhttp) 5.5, requêtes réseau.
- [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) 1.11 et
  [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) 1.11.

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
  mêmes données de prix dans les deux apps, neutralité du dépôt (aucune autre app IPTV citée, jamais
  « gratuit » pour qualifier l’IPTV, chaînes fictives dans les tests), et l'édition App Store : aucun code
  de téléchargement ni fonction dormante, aucune API privée, jamais « bêta », une fiche et des notes pour
  la review qu'App Review accepte (vérifiée aussi à chaque changement et avant chaque publication).
- **Compilation Release, édition App Store** (iOS et macOS) : le code `#if DEBUG` et les téléchargements de
  l'édition TestFlight (`#if TESTFLIGHT_DOWNLOADS || DEBUG`) en sont exclus, comme dans le build soumis.
- **Banc de test de la lecture** (`tools/playback-lab`, quand le moteur VLC ou le code de lecture change) :
  libVLC, la bibliothèque du moteur VLC, lit des flux générés servis par un fournisseur qui se comporte mal
  exprès ; gels, temps de démarrage et de saut des films, position de reprise et diagnostic des gels de l'app
  sont comparés à des limites.
- **Build de release sur un émulateur Android TV** (*TV release check*, lancé à la main) : le build R8 piloté avec
  les touches de la télécommande face à un panel Xtream fictif, de l'ajout d'une playlist au lecteur, au guide, à
  la synchronisation et à un film au débit d'un film 4K ; un plantage, un ANR ou Android qui arrête l'app le fait
  échouer. La limite de mémoire d'un boîtier TV est demandée, appliquée quand l'émulateur le permet ; la limite en
  vigueur est affichée.
- **Tests de mutation** du cœur Kotlin, une fois par semaine (Pitest) : de petits bugs sont introduits dans le
  code et ceux qu'aucun test ne détecte sont signalés ; ils ne font pas échouer la CI.
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
([docs/RELEASE.fr.md](docs/RELEASE.fr.md), [docs/RELEASE-ANDROID.fr.md](docs/RELEASE-ANDROID.fr.md)). Un
garde-fou bloque les secrets et données personnelles avant tout commit — hook Claude Code
(`.claude/settings.json`), hook git (`git config core.hooksPath .githooks`) et CI
(`.claude/hooks/leak_guard.py` + gitleaks sur tout l'historique).

## Soutenir le projet

Ondo n'a ni publicité ni pistage. S'il vous est utile, vous pouvez soutenir son développement
[une fois, du montant de votre choix](https://buy.stripe.com/6oU3cv4t24tjf7AaH15Rm07), ou chaque mois : [3 €](https://buy.stripe.com/4gM00j1gQ2lb3oS3ez5Rm03), [5 €](https://buy.stripe.com/bJebJ1gbKbVLcZs2av5Rm05) ou
[10 €](https://buy.stripe.com/6oU7sL7FecZP9NgeXh5Rm01). Par carte, Apple Pay ou Google Pay, sans créer de compte. Dans les apps, l'argent ne passe que par
l'App Store et Google Play, comme leurs règles l'exigent : les apps ne mènent à aucun autre moyen de payer.

## Licence

[Functional Source License 1.1, MIT Future License](LICENSE) (FSL-1.1-MIT). Vous pouvez utiliser, étudier,
modifier et partager Ondo pour tout usage, sauf proposer un produit ou un service commercial concurrent fondé sur
lui ; deux ans après la publication de chaque version, cette version est aussi disponible sous licence MIT. Les
versions publiées avant ce changement restent sous licence MIT.
