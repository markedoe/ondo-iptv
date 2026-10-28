# Ondo pour Android — plan

[English](ANDROID.md) · **Français**

## État (v1, octobre 2026)

La v1 est dans `android/` et se publie avec [RELEASE-ANDROID.fr.md](RELEASE-ANDROID.fr.md) :

- **Cœur** `android/core` : port Kotlin d'IPTVCore (M3U, XSPF, JSON, Xtream, XMLTV, catch-up, recherche,
  éléments de lecture), testé avec les mêmes cas que les tests Swift.
- **App** `android/app` (Compose, Material 3, Navigation type-safe, barre d'onglets ou rail selon l'écran) :
  accueil (carrousel, Films en cours, Séries en cours, Ma liste, Nouveaux films, Nouvelles séries, chaînes),
  onglets Films et Séries (Continuer à regarder, Ma liste, Films primés dans Films, une rangée par catégorie,
  grilles filtrables), un appui long qui retire une carte en cours de l'Accueil ou de Continuer à regarder (un menu
  sur un téléphone, OK maintenu et une question sur une TV : `ui/components/Shelves.kt`), fiches film et série
  (reprise, depuis le début, épisode suivant, saisons, titres similaires, prix, casting et réalisation
  cliquables), TV en direct (catégories, Ma liste, programme en cours
  et suivant), grille du guide TV avec rappels de programmes (notifications WorkManager), onglet Ma liste avec
  collections, recherche (chaînes, films, séries, acteurs et réalisateurs ; filtres par années de sortie et
  par festival ou cérémonie), catégories masquées par code de langue ou de pays, par style ou par mot, ou une
  à une, aide, réglages (playlists, catégories adultes masquées par défaut, Mettre à jour les prix
  automatiquement, Langues et catégories, Langue de l'app, mémoires tampons du lecteur, jeton TMDb), accueil avec
  démonstration et avertissement « lecteur sans contenu ».
- **TMDb** avec le jeton de l'utilisateur (`core/library/Tmdb.kt`, `OndoRepository.checkAndSaveTmdbToken`) :
  vérifié par `/authentication` de TMDb avant d'être enregistré (accepté, refusé, injoignable), gardé dans le
  stockage privé de l'app et transmis par la synchronisation (`key:tmdb`, docs/SYNC.fr.md) ; une rangée Tendances et
  des titres du carrousel, l'image de fond de TMDb quand celle du fournisseur manque ou ne se charge pas, les photos
  du casting d'un titre (`TmdbClient.cast`, par l'identifiant TMDb du fournisseur ou le titre), les personnes de TMDb
  dans la recherche, et les titres d'une personne d'après ses crédits TMDb. Un 401 plus tard (un
  jeton révoqué, ou arrivé par la synchronisation) s'affiche comme refusé dans les Réglages, et plus rien n'est demandé.
- **Lecteur** Media3 / ExoPlayer : HLS, MPEG-TS, MP4, MKV, en-têtes du fournisseur, adresses de secours,
  reprise, image dans l'image, MediaSession, pistes audio et sous-titres, les langues de l'audio et des
  sous-titres apprises des choix du spectateur et utilisées d'abord pour les flux suivants, les sous-titres forcés
  toujours affichés avec les sous-titres désactivés (`player/TrackPreferences.kt`), mémoires tampons distinctes
  pour le direct et pour les films et séries, jamais plus de la moitié de la mémoire qui reste à l'app quand le
  lecteur démarre, pour qu'un film 4K se lise sur un boîtier TV qui donne 128 Mo à l'app
  (`player/PlayerLoadControl.kt`, `core/playback/PlaybackBuffer.kt`), retour automatique au direct quand une
  chaîne prend du retard, Plus → Format d'image (Ajuster, Remplir (rogner), Étirer, gardé pour les vidéos
  suivantes), Plus → Taille des sous-titres (de 75 % à 160 % de la taille donnée par les réglages de sous-titres
  d'Android, gardée aussi : `player/SubtitleSizing.kt`), Plus → Minuterie de sommeil (Désactivé, 15, 30, 45, 60, 90, 120 min ; le lecteur se met en pause
  quand elle arrive à son terme : `player/SleepTimer.kt`), Plus → Statistiques du flux (format, débits de la
  vidéo et reçu, fluidité, gels et images perdues, et s'ils viennent du réseau ou du lecteur : `core/playback/PlaybackHealth.kt`, les règles d'Apple), Plus → Signaler un
  problème… (un rapport de lecture sans adresse, identifiant ni titre). Les fiches des films et séries indiquent
  le format (MKV, HLS…).
  Sur un téléphone, ses propres commandes Compose par-dessus la vue vidéo de Media3 (`player/PhonePlayerControls.kt`,
  les commandes de Media3 désactivées) : un toucher les affiche, deux touchers sur un tiers latéral sautent 10 s (les
  touchers s'additionnent), un doigt posé 500 ms lit en 2×, des glissements règlent la luminosité (celle de la
  fenêtre) et le volume (celui de la musique), un pincement remplit ou ajuste, un verrou ; Sous-titres, Audio,
  Vitesse, Format d'image et Plus dans des volets ; image dans l'image. À suivre (`player/UpNext.kt`) : les
  20 dernières secondes d'un épisode proposent le suivant, lu à la fin sauf refus. La liste Chaînes d'une chaîne en
  direct (`player/ChannelList.kt`, un volet sur un téléphone, un panneau latéral sur une TV).
- **Replay** depuis l'archive du fournisseur (`OndoRepository.replayRequest`, `core/catchup/`) : Lire le replay sur
  un programme passé du guide, Depuis le début sur celui en cours (guide, commandes du téléphone et de la TV),
  Retour au direct ; un replay garde sa chaîne (touches de chaîne, liste Chaînes), n'entre dans aucun historique, et
  l'archive Xtream est demandée à l'heure du serveur (`content.account`). Une chaîne en direct sans fenêtre où
  reculer, mise en pause par le spectateur 10 s ou plus, reprend depuis l'archive là où elle a été arrêtée
  (`player/LivePause.kt`, comme sur Apple).
- **Fréquence d'image** sur une TV Android 12 ou plus : un film, un épisode ou un replay demande à l'écran sa propre
  fréquence avec `Surface.setFrameRate(…, CHANGE_FRAME_RATE_ALWAYS)`, comme Google le recommande pour les films ;
  Android ne change que si le spectateur a réglé la TV pour adapter sa fréquence au contenu, et le lecteur attend
  l'écran pendant ce temps (`player/FrameRateMatching.kt`). Les chaînes en direct gardent l'écran tel quel (chaque
  changement de chaîne le noircirait).
- **Stockage** : documents JSON dans le stockage privé de l'app (`data/AppStore.kt`), exclus des sauvegardes
  (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`) : réglages, playlists avec leurs identifiants, Ma
  liste, collections, progression et rappels, catalogue et guide de chaque playlist (le guide servi 6 heures avant
  d'être téléchargé à nouveau, comme sur les appareils Apple ; Actualiser le télécharge tout de suite), et fiches de films et de séries
  ouvertes (jusqu'à 1 000 films et 300 séries par playlist, téléchargées à nouveau après 7 jours pour un film
  et 12 heures pour une série, toujours affichées quand le fournisseur est injoignable ;
  `data/DetailsArchive.kt`). Affiches et vignettes sont gardées sur le disque par Coil 3 jusqu'à la désinstallation
  de l'app (dans son propre stockage, pas le dossier de cache qu'Android vide ; jusqu'à un dixième de l'espace libre,
  de 256 Mo à 2 Go ; `OndoApplication.kt`).
- **Synchronisation sur le réseau local** (Réglages → Synchroniser sur ce réseau, désactivée tant qu'on ne l'active
  pas ; `data/LocalSync.kt`, `data/SyncData.kt`, `core/sync/`, spécifiée dans [SYNC.fr.md](SYNC.fr.md)) : tant
  qu'Ondo est au premier plan, l'appareil s'annonce avec Bonjour (`NsdManager`, `_ondo._tcp`), répond aux sessions
  sur un port TCP, trouve les autres appareils Android et se synchronise avec eux ; iPhone, iPad et Mac s'y
  connectent. Playlists, Ma liste, collections, progression et jeton TMDb ; seules des adresses locales sont
  contactées ou acceptées.
- **Langues** : anglais, français, espagnol, portugais, chinois (simplifié), arabe (de droite à gauche) et
  hindi (`res/values-*`), proposées aussi dans le réglage de langue par app d'Android 13
  (`res/xml/locales_config.xml`).
- **Android TV / Google TV** (`ui/tv/`) : une entrée et une bannière dans le lanceur Leanback
  (320 × 180 en xhdpi, dessinée par `fastlane/creative/render.mjs --tv`), écran tactile non requis. Compose for TV
  (`androidx.tv:tv-material`) aux couleurs d'Ondo (`TvDesign.kt`) : une barre en haut avec les onglets, Recherche et
  Réglages, qui s'efface avec le haut de la page quand on descend (`TvShell.kt` ; Retour mène en haut de la page et à la barre, puis à l'Accueil, puis hors de l'app), un Accueil plein
  écran (`TvHomeScreen.kt`) et des fiches film et série (`TvDetailScreens.kt`) avec l'image de fond derrière le
  texte, des Réglages en deux volets où chaque playlist a ses boutons à portée de télécommande (`TvSettingsScreen.kt` ;
  Supprimer demande d'abord, en commençant sur Annuler), des cartes qui grandissent avec un contour blanc à la sélection et des rangées qui dépassent du bord droit ;
  les autres pages sont les écrans Compose dans la zone sûre de la TV (48 × 27 dp). Une sélection qui revient sur
  la carte quittée (`FocusMemory`) et entre d'elle-même dans chaque page (`FocusEntry`), des rangées qui défilent
  jusqu'au tiers. Lecteur : ses propres commandes pour la télécommande, par-dessus la vue vidéo de Media3
  (`player/TvPlayerControls.kt`, `player/PlayerControls.kt`) : de grands boutons ronds, une barre de progression dont la
  cible avance par pas de plus en plus grands, la vidéo s'y rendant quand la télécommande s'arrête (un saut par
  déplacement), des panneaux Sous-titres, Audio, Vitesse et Plus avec les pistes nommées par langue
  (`player/TrackChoices.kt`) ; pendant la lecture, les commandes se masquent 5 s après la dernière touche. OK met en
  pause, Gauche / Droite reculent et avancent de 10 s quand le flux le permet (sinon elles affichent les commandes),
  Haut / Bas, CH+ / CH−, les chiffres et la chaîne précédente changent de chaîne (`player/RemoteKeys.kt`, `core/library/ChannelZapping.kt`), un bandeau de chaîne, Retour ferme
  un panneau, puis les commandes.
  Qualité des apps TV de Google : pas d'image dans l'image sur une TV, l'écran gardé allumé seulement pendant la
  lecture, pas de navigateur (les règles de confidentialité et la page d'aide s'ouvrent dans une page de l'app
  limitée à elles), pas de sélecteur de fichier ni de bouton de partage quand la TV n'en a pas, un cache d'images
  en mémoire plus petit sur un appareil à peu de mémoire.
- **Tests** : unitaires (bibliothèque, vitrine, écrans, dépôt, ViewModel), Compose (Robolectric), la
  télécommande sur une TV (`TelevisionTest`, `PlayerRemoteTest`, `TvPlayerRemoteTest`, `TvPlayerControlsTest`), les gestes du
  lecteur du téléphone (`PhonePlayerOverlayTest`), captures Roborazzi qui servent de captures
  Google Play (téléphone, tablettes 7" et 10", TV, anglais et français).

Prévu ensuite (non inclus en v1) : Chromecast, téléchargements, profils et PIN,
sous-titres en ligne, second moteur libVLC ; sur une TV, chaînes du lanceur et recherche vocale.

## Plan initial

Écrit avant la v1, qui en diffère : documents JSON dans le stockage privé au lieu de Room, DataStore et
l'Android Keystore, pas de Hilt, tests du cœur sous JUnit 4, fastlane au lieu de Gradle Play Publisher, et
pas encore de DASH, d'extension FFmpeg, de Cast ni de service de premier plan pour la lecture.

À démarrer une fois la version iPhone / iPad / Mac validée et publiée. Objectif : la même app, native,
sans compromis sur la qualité, en réutilisant tout ce qui peut l'être (spécifications, jeux de test,
textes, icône, fiche) plutôt que du code Swift.

## 1. Choix techniques

| Sujet | Choix | Pourquoi |
|---|---|---|
| Langage / UI | Kotlin, Jetpack Compose, Material 3 (thème Ondo) | standard Android actuel |
| Cibles | téléphones et tablettes Android 8+ (API 26), **Android TV / Google TV** (Compose for TV) | l'IPTV se regarde surtout sur la TV |
| Lecture | **AndroidX Media3 / ExoPlayer** (HLS, DASH, MPEG-TS, sous-titres), extension FFmpeg pour les codecs audio | lecteur standard d'Android, PiP, MediaSession |
| Second moteur | libVLC Android (LGPL) en repli, comme sur iOS | MKV / AVI / flux exotiques |
| Diffusion | Google Cast (Media3 `CastPlayer`) | Chromecast natif |
| Réseau | OkHttp + Kotlin coroutines / Flow | |
| Stockage | Room (catalogue, guide), DataStore (réglages), Android Keystore (mots de passe) | |
| Images | Coil 3 | équivalent de Nuke |
| Synchro | Google Drive *appDataFolder* (optionnelle) au lieu d'iCloud, même format JSON | |
| Injection | Hilt | |
| Tests | JUnit 5 + Turbine (cœur), Compose UI tests, Roborazzi (captures) | |
| CI/CD | GitHub Actions (lint, tests, build), Gradle Play Publisher → Google Play (piste interne puis production) | |

## 2. Cœur `core` (Kotlin pur, sans Android)

Port fidèle d'`IPTVCore`, piloté par les **mêmes fichiers de test** (`Packages/IPTVCore/Tests/IPTVCoreTests/Fixtures`
et les cas des tests Swift) pour garantir un comportement identique :

- M3U (parseur octet par octet, attributs `tvg-*`, `catchup*`, en-têtes `#EXTVLCOPT`), XSPF, JSON
- Xtream Codes (décodage tolérant), XMLTV en flux (gzip, filtres fenêtre / chaînes)
- URL de catch-up (default, append, shift, flussonic, Xtream), numérotation, correspondance EPG
  (automatique, suggestions, choix manuel), recherche
- Bibliothèque : favoris, historique, listes, rappels, chaînes masquées — avec la **même fusion
  déterministe** (marqueurs datés) pour que la synchro reste compatible

## 3. Écrans (parité avec iOS)

Accueil, Live TV, Guide (grille + guide de chaîne), Films, Séries, Recherche (dont programmes), Favoris
et listes, Téléchargements, Réglages, Profils / contrôle parental, Lecteur (panneau chaînes / guide,
redémarrer / revoir / direct, dernière chaîne, zapping par numéro y compris télécommande, pistes,
sous-titres, PiP, minuterie), Multivue (tablette / TV).

Spécifique Android TV : navigation au D-pad, guide plein écran, touches CH+/CH−, chiffres, « Info »,
lancement depuis l'écran d'accueil (chaînes recommandées).

## 4. Jalons

1. `core` + tests portés depuis la suite Swift (CI Linux)
2. App téléphone : sources, Live TV, lecteur Media3, guide
3. Films / séries, recherche, bibliothèque, profils, rappels (WorkManager + notifications)
4. Android TV (Compose for TV), multivue, Cast
5. Synchro, sauvegarde / restauration compatibles avec iOS
6. Fiche Google Play (textes et captures réutilisés), politique de confidentialité (même page), publication

## 5. Points d'attention Google Play

- Politique « Contenu non autorisé / propriété intellectuelle » : comme sur l'App Store, aucune chaîne
  fournie, avertissement clair, playlist de démonstration légale.
- Déclarations : pas de collecte de données (formulaire *Data safety*), autorisations minimales
  (réseau, notifications, premier plan pour la lecture).
