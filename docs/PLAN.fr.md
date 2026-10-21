# Plan de réalisation — « Ondo »

[English](PLAN.md) · **Français**

Lecteur IPTV (films, séries, TV en direct) pour **iPhone**, **iPad** et **macOS**, en Swift/SwiftUI natif,
sous licence MIT. Ondo ne fournit aucun contenu : il lit les sources ajoutées par l'utilisateur. L'app Android
suit son propre plan : [ANDROID.fr.md](ANDROID.fr.md).

## 1. Choix techniques

| Sujet | Choix | Pourquoi |
|---|---|---|
| Langage / UI | Swift 5.10+ (compilateur Swift 6), SwiftUI, Observation (`@Observable`) | une seule base de code iPhone + Mac natif |
| Cibles | **iOS 17+**, **macOS 14+** (iPad inclus via la cible iOS) | `@Observable`, `NavigationSplitView`, `ContentUnavailableView` |
| Projet Xcode | **XcodeGen** (`project.yml`) | projet lisible et versionnable, pas de `.pbxproj` à la main |
| Logique métier | package SPM **`IPTVCore`** (Foundation pur) | testable sous Linux et en CI, réutilisable (tvOS plus tard) |
| Lecture | AVFoundation (`AVPlayer`) et **VLCKit** 3.6 (LGPL), choisis par le réglage `PlayerEngine` (automatique / Apple / VLC) ; sur iPhone/iPad, films MKV remuxés en HLS (MP4 fragmenté, sans transcodage) et servis à `AVPlayer` sur 127.0.0.1 | PiP, AirPlay, HDR natifs ; VLC pour ce qu'`AVPlayer` n'ouvre pas (AVI, `.ts` brut, MKV sur Mac…) |
| Stockage | fichiers JSON versionnés dans *Application Support* + Trousseau pour les mots de passe ; dossier *Caches* pour les catalogues, guides et fiches par playlist ; cache disque Nuke pour les images | simple, robuste, exportable ; SQLite si besoin de perf |
| Réseau | `URLSession`, en-têtes UA/Referer par playlist, gzip via zlib | |
| Tests | XCTest (`swift test` sous Linux/Docker et macOS) | |
| CI | GitHub Actions : tests Linux + build iOS Simulator + build macOS | détecte aussi les régressions de la version Mac |

## 2. Architecture

```
ondo-iptv/
├── project.yml                     # XcodeGen : cible "Ondo" iOS + macOS
├── Packages/IPTVCore/              # logique métier, sans UI (Sources/IPTVCore ; Sources/CZlib enveloppe le zlib du système)
│   ├── Models/        Playlist, Channel, Movie, Series, Episode, EPGProgram, FavoriteEntry, HistoryEntry, PlaybackItem
│   ├── Parsing/       M3UParser, M3UContentBuilder, XSPFParser, JSONPlaylistParser, PlaylistFormat, SubtitleParser
│   ├── Xtream/        XtreamClient (API), DTO tolérants, XtreamURLBuilder (live/vod/series/timeshift)
│   ├── EPG/           XMLTVParser (SAX + gzip), EPGIndex (now/next, rapprochement des chaînes)
│   ├── Catchup/       CatchupURLBuilder (default/append/shift/flussonic/xtream)
│   ├── Library/       PlaylistLoader, SearchEngine, UserLibrary (favoris, historique, reprise), FileStore, DetailsArchive
│   ├── Metadata/      TMDbClient, People, AwardsCatalogue
│   ├── Playback/      PlaybackHealth, PlaybackLog, ProblemReport, BufferPolicy
│   ├── Remux/         MatroskaRemux (MKV → HLS en MP4 fragmenté), EBML, Matroska, FragmentWriter
│   ├── Subtitles/     OnlineSubtitles, OpenSubtitles, SubDL, SubSource, Addic7ed
│   ├── Networking/    HTTPClient, TransferMeter, DownloadDiagnosis + DownloadReport (téléchargements, édition TestFlight)
│   └── Utilities/     Gzip (CZlib), ZipArchive, TextUtilities
├── App/
│   ├── OndoApp.swift               # scènes : fenêtre principale, fenêtres lecteur, Multivue, Aide, Réglages (les quatre dernières sur Mac)
│   ├── Model/         AppModel (état global, extensions AppModel+…), AppSettings, AppPaths, Keychain, PlaybackSession
│   ├── Features/      Root, Onboarding, Home, Live, Movies, Series, Guide, Search, Favorites, Playlists, Settings…
│   ├── Player/        PlayerController, PlayerView, PlayerEngine + VLCBackend, RemuxServer, CastManager, PiP, zapping
│   └── Shared/        RemoteImage (Nuke), composants, plateformes (#if os)
└── .github/workflows/ci.yml
```

Flux de données : `Playlist` → `PlaylistLoader` (M3U/Xtream) → `PlaylistContent` mis en cache sur disque →
`AppModel` (`@Observable`) → vues. L'EPG est chargé en tâche de fond → `EPGIndex` → now/next, grille, replay.

Navigation : **iPhone** = `TabView` (Accueil, Films, Séries, TV en direct, Ma liste) ; Recherche par la loupe de
chaque onglet, Guide TV dans TV en direct, Réglages sur l'Accueil. **iPad** et **Mac** = `NavigationSplitView`
avec barre latérale (Accueil, Films, Séries, TV en direct, Guide TV, Ma liste, Recherche, puis les playlists) ;
Réglages par l'engrenage de la barre latérale sur iPad, `Settings` natif sur Mac. Sur Mac : menu « Lecture » avec
raccourcis, lecteur dans sa propre fenêtre (plusieurs fenêtres = multi-écran).

## 3. Phases et livrables

### Phase 0 — Cadrage ✅
- [x] Inventaire des fonctions attendues d'un lecteur IPTV, formats et protocoles (M3U, Xtream, XMLTV, replay)
- [x] Plan, architecture, priorités → ce document

### Phase 1 — Cœur `IPTVCore` (P1) ✅
- [x] Modèles de données + identifiants stables (hash FNV, pour favoris/historique persistants)
- [x] Parseur M3U étendu au niveau octets (attributs, en-têtes, `EXTVLCOPT`, `EXTGRP`, `KODIPROP`, `|User-Agent=`)
- [x] Classement Live/Films/Séries + regroupement des épisodes (`S01E02`, `1x02`, « Saison 2 Épisode 3 »)
- [x] Parseurs XSPF, JSON et listes d'URL simples
- [x] Client Xtream Codes complet + décodage tolérant + construction des URL + « forcer M3U »
- [x] Parseur XMLTV en flux (gzip, filtre de fenêtre et de chaînes, décalage) + `EPGIndex` (id, casse, nom)
- [x] Générateur d'URL de replay (default, append, shift, flussonic, xtream + jetons)
- [x] Recherche normalisée (accents, casse, préfixes, classement)
- [x] Stockage JSON versionné avec copie de secours, décodage tolérant, sauvegarde/restauration
- [x] Sous-titres SRT / WebVTT, tendances TMDb + rapprochement avec le catalogue
- [x] Tests unitaires : 66 à la fin de la phase 1, 223 aujourd'hui (+ garde-fous de performance : 60 000 entrées, 100 000 programmes)

### Phase 2 — App MVP iPhone + Mac (P1) ✅
- [x] Projet XcodeGen, Info.plist (ATS, audio en arrière-plan), sandbox Mac, icône originale
- [x] Onboarding + ajout de playlist (Xtream, URL M3U, fichier, démo publique) avec détection des liens Xtream
- [x] Gestion des playlists : modifier, actualiser (auto), supprimer, réordonner, EPG additionnels, UA/Referer
- [x] Accueil : continuer à regarder, tendances, favoris, chaînes récentes, ajouts récents, statut du compte
- [x] Live : catégories → chaînes (logo, numéro, programme en cours + progression, badge replay)
- [x] Films / séries : grilles d'affiches, tri, fiches détaillées, saisons, épisodes, reprise, épisode suivant auto
- [x] Recherche globale (portées), favoris réordonnables, historique
- [x] Lecteur : zapping (liste + ↑/↓), overlay EPG, pistes, ratio, vitesse, PiP, AirPlay, verrou, minuterie,
      reprise, raccourcis clavier Mac, une fenêtre par flux sur Mac, « À l'écoute » / touches média
- [x] Bascule d'URL (`.ts → .m3u8`) et vers une autre version de la chaîne (HD/FHD/backup), reconnexion auto,
      relance sur blocage, erreurs attribuées
- [x] Guide TV en grille + fiche programme + replay
- [x] Contrôle parental : PIN, catégories adultes verrouillées automatiquement
- [x] Infos du compte Xtream + test de connexion
- [x] Réglages complets ; localisation en anglais, français, espagnol, portugais, chinois (simplifié), arabe et hindi

### Phase 3 — Lecteur avancé (P2) ✅
- [x] Moteur **VLCKit** 3.6 (LGPL) : MKV, AVI, `.ts` brut… ; mode automatique avec repli si AVPlayer échoue
- [x] Sous-titres SRT/WebVTT externes avec décalage ; préférences audio/sous-titres conservées
- [x] Gestes iPhone (double-tap ±10 s, glisser pour avancer, luminosité), paysage forcé par type de contenu
- [x] « Forcer M3U » pour les comptes Xtream

### Phase 4 — Bibliothèque et synchro (P2) ✅
- ~~Synchro iCloud~~ : retirée (décision du 13/10) ; la bibliothèque garde sa fusion déterministe
      (marqueurs de suppression), utile pour une future synchro ou la restauration de sauvegardes
- [x] Profils (« Qui regarde ? »), PIN de profil, profils enfant
- [x] Gestionnaire de listes personnalisées, catégories masquées

### Phase 5 — Extras (P3)
- [x] Téléchargements hors ligne des films/épisodes (édition TestFlight ; session d'arrière-plan, pause/reprise)
- [x] Tendances TMDb (clé API fournie par l'utilisateur)
- [x] Chromecast (Google Cast SDK officiel, iPhone/iPad) + AirPlay (iPhone, iPad, Mac)
- [x] ~~Source Real-Debrid~~ : supprimée (octobre 2026) : du code qu'aucun build ne peut utiliser est une fonction dormante pour la review d'Apple
- [ ] Monétisation StoreKit 2 optionnelle — volontairement non faite (app libre)
- [ ] Cibles tvOS / visionOS (le cœur est déjà compatible)

### Phase 6 — Fonctions avancées ✅ en partie
Fonctions jugées indispensables pour un lecteur moderne, traitées par jalons :
- [x] **M1** dernière chaîne (bouton, ⌫ / ⌘⌫), zapping par numéro au clavier, panneau latéral
      chaînes (recherche nom/numéro) + guide de la chaîne dans le lecteur
- [x] **M2** redémarrer le programme en cours, revoir un programme passé depuis le lecteur, retour au
      direct ; pause/retour arrière du direct (fenêtre DVR HLS, sinon reprise via l'archive du fournisseur)
- [x] **M3** rappels de programmes (notifications locales, délai réglable) ; titres
      « collants » dans la grille EPG
- [x] **M4** association manuelle chaîne ↔ guide (suggestions, aperçu, « pas de guide ») ; recherche
      dans les programmes (en cours, à venir, replay)
- [x] **M5** gestion des chaînes : masquer (par profil), renommer, renuméroter (par playlist)
- [x] Multivue (iPad / Mac : 2 à 4 flux, un seul audio)
- [x] Téléchargements hors ligne : édition TestFlight uniquement (testeurs internes) ; l'édition App Store est
      compilée sans eux (`#if TESTFLIGHT_DOWNLOADS || DEBUG`, règles 2.3.1 et 5.2.3)
- ~~Enregistrement~~ : non retenu (décision du 13/10)
- Source Real-Debrid : supprimée (fonction dormante pour la review d'Apple, règle 2.3.1) ;
  portail Stalker/MAG : hors v1 (décision du 13/10, Ondo reste un lecteur M3U / Xtream)
- [x] Remplacer le chargeur d'images maison par Nuke/NukeUI (cache disque, coalescence, sous-échantillonnage)

## 4. Phase 7 — Expérience films et séries
- [x] **M-A** textes : lecteur sans contenu, films et séries mis en avant
- [x] **M-B** accueil : carrousel à la une, rangées (reprise, Ma liste, ajouts récents), thème sombre
- [x] **M-C** fiches film / série plein écran (image de fond, actions, distribution, saisons, épisodes)
- [x] **M-D** onglets Films / Séries en rangées par catégorie (puces, reprise, Ma liste, recherche)
- [x] Captures App Store du Mac (workflow *App Store screenshots*)

## 5. Risques

| Risque | Parade |
|---|---|
| AVPlayer ne lit pas le `.ts` brut | sortie `m3u8` Xtream par défaut, URL de secours, moteur VLCKit (phase 3) |
| Playlists énormes (50 000+ entrées) | parsing en tâche de fond, index de recherche pré-normalisé, listes paresseuses, cache disque |
| Guides XMLTV de centaines de Mo | SAX en flux, filtrage chaînes/fenêtre, cache élagué |
| Panels Xtream non conformes | décodage JSON tolérant, tests sur réponses réelles |
| Validation App Store (apps IPTV) | aucun contenu fourni, avertissement clair, playlist de démo légale |
| Licences | dépendances MIT/BSD/LGPL, Apache 2.0 (bibliothèques Android), SDK Cast de Google selon les conditions de Google (iPhone/iPad) |
| Pas de Mac dans l'environnement de dev | cœur testé sous Linux (Docker Swift 6) ; app compilée par la CI macOS |

## 6. Construire et lancer

```bash
brew install xcodegen
xcodegen generate          # crée Ondo.xcodeproj
open Ondo.xcodeproj        # schéma "Ondo", destination iPhone ou "My Mac"

# Tests du cœur (macOS ou Linux)
cd Packages/IPTVCore && swift test
```
