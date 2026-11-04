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
  automatiquement, Langues et catégories, Langue de l'app, mémoires tampons du lecteur, jeton TMDb), accueil pour
  ajouter la première playlist (aucune chaîne ni démo embarquée) et avertissement « lecteur sans contenu ».
- **Recherche** : un mot est trouvé n'importe où dans un titre (« dust » trouve « Stardust ») : le titre exact
  d'abord, puis les titres qui commencent par le mot, puis ceux dont un mot commence par lui, puis les autres. Les
  titres et chaînes dont le nom porte lui-même une étiquette de langue masquée (comme « |DE| … », « [DE] … »,
  « DE - … ») sont écartés comme les catégories masquées, là et dans toutes les autres listes (rangées, grilles,
  Accueil, titres d'une personne, d'un genre, d'une année ou d'un prix, titres similaires, TV en direct et guide),
  quelle que soit leur catégorie (`CategoryTags.titleTag` ; « Portugal: … » ou « PTSD … » ne portent pas d'étiquette). Sur une TV, la Recherche s'ouvre sur son champ, clavier affiché.
- **Actualisation automatique** (Réglages → Playlists → Actualisation automatique : Seulement sur demande, Toutes
  les 6 heures, Toutes les 12 heures, Chaque jour (par défaut), Chaque semaine), vérifiée à l'ouverture d'Ondo et
  quand il revient au premier plan (`MainActivity.onStart`, `OndoRepository.refreshIfStale`). Un seul réglage pour
  toutes les playlists sur Android (Apple en a un par playlist). Actualiser dans les Réglages affiche un indicateur
  et « Mise à jour de la playlist… ».
- **En-tête sur TV** (`ui/tv/TvFocusHeader.kt`) : sur l'accueil, les onglets Films et Séries et les titres d'une
  catégorie, d'un genre, d'une année ou d'une récompense, un en-tête de hauteur fixe au-dessus des affiches montre
  celle qui a le focus : son image de fond (sinon son affiche floutée) derrière toute la page, bord à bord et jusqu'en
  bas de l'écran, les rangées dessinées par-dessus avec des ombres douces seulement (sombre côté début, plus sombre vers
  le bas), son titre (le logo du titre sur TMDb dans la langue de l'app, sinon en anglais, sinon sans texte, en fondu
  depuis le nom ; le nom sans les étiquettes du fournisseur comme « |FR| » ou « VFF » d'ici là ou sans logo), année et genres
  (sinon sa catégorie), la note de TMDb avec son badge (sinon celle du fournisseur), la date d'ajout (de dernière
  modification pour une série) dans la langue de l'app et deux lignes de résumé. La page d'un titre sur TV a le même
  emplacement de titre (son logo, sinon son nom sans les étiquettes du fournisseur, rien ne bouge quand le logo
  arrive). Partout ailleurs où un titre s'affiche en texte (légendes des affiches, Reprendre, Recherche, page d'un
  titre sur téléphone, lecteur, À suivre), il se lit aussi sans les étiquettes du fournisseur ; le nom enregistré ne
  change pas. Il suit le focus une fois posé
  200 ms sur une affiche ; 450 ms plus tard, ce qui manque est demandé, un titre à la fois, annulé quand le focus
  bouge : la réponse de TMDb gardée sur l'appareil, puis TMDb avec le jeton, puis `get_vod_info` du fournisseur pour
  un film sans résumé (gardé avec les autres détails) ; rien sans réseau. Les rangées gardent l'affiche qui a le
  focus au même endroit, environ cinq affiches sur la largeur de l'écran (la sixième qui dépasse) ; le cadre du
  carrousel de l'accueil rapetisse et montre l'affiche qui a le focus tant que les rangées l'ont, ses boutons revenant
  avec Haut. Les titres d'une catégorie et les pages de genre, d'année et de prix montrent cinq affiches par rangée ;
  la grille d'une catégorie défile par rangées entières, la rangée sélectionnée en haut, celle d'au-dessus disparue,
  le nom de la catégorie au-dessus de la première rangée.
  Le parcours de la télécommande sur les onglets Films et Séries : le focus sur l'onglet, l'en-tête montre le titre de
  la première carte et la première rangée s'affiche entière juste dessous (pas de filtres entre les deux ; une rangée
  défile pour se montrer, jamais masquée) ; Bas mène à cette carte, son titre déjà dans l'en-tête (la première affiche
  atteinte s'affiche aussitôt, sans attente) ; Bas encore, la rangée suivante monte entière et l'en-tête suit. Les
  rangées sont toujours la page de l'onglet : aucune catégorie n'est choisie au-dessus. Les titres d'une catégorie
  s'ouvrent depuis la carte Tout voir au bout de sa rangée, comme une page par-dessus les rangées : Retour revient à
  la même rangée et à la même carte, les rangées défilées comme avant, et l'onglet choisi dans la barre (OK dessus, ou
  un autre onglet puis celui-ci) montre de nouveau ses rangées. La barre du haut ne porte que les onglets, Recherche et
  Réglages, aucune ligne de playlist (Réglages → Playlists indique quand chacune a été mise à jour) ; les catégories
  montrées se choisissent dans Réglages → Langues et catégories. Les titres d'une catégorie et les pages de genre,
  d'année et de prix sur TV commencent par leurs affiches juste sous l'en-tête : pas de rangée Retour (le Retour de la
  télécommande revient en arrière), le nom de la page sur une ligne au-dessus de la première rangée, pas de champ de
  filtre (la Recherche trouve un titre) ; Bas depuis l'onglet mène à la première affiche, son titre aussitôt dans
  l'en-tête. Le festival d'un prix vient après ses titres.
- **Tri** : la grille d'une catégorie et les pages de genre, d'année, de prix et de festival partagent un même ordre
  (`AppSettings.posterSort`, le bouton Trier au-dessus des affiches sur téléphone ; sur TV, la puce « Tri : … » au
  bout de la ligne du nom de la page, que Haut depuis la première rangée atteint, avec une puce Masquer les films déjà
  vus pour les films (`TvSortMenu.kt`) ; Bas depuis l'onglet ne s'y arrête jamais) : ordre de la playlist
  (sur ces pages, leur propre ordre), nom, date d'ajout, date de sortie ou note. Les titres d'un genre trouvés via
  TMDb sont triés avec les autres (`UiMappers.browse`).
- **Affiches sur TV** : maintenir OK sur une affiche ajoute le titre à Ma liste ou l'en retire (un
  court message dit lequel), et une coche marque les titres de Ma liste. Dans Ma liste, les personnes gardées ont
  leur photo (complétée depuis leur page ou les titres gardés sur l'appareil).
- **TMDb** avec le jeton de l'utilisateur (`core/library/Tmdb.kt`, `OndoRepository.checkAndSaveTmdbToken`) :
  vérifié par `/authentication` de TMDb avant d'être enregistré (accepté, refusé, injoignable), gardé dans le
  stockage privé de l'app et transmis par la synchronisation (`key:tmdb`, docs/SYNC.fr.md) ; une rangée Tendances et
  des titres du carrousel, l'image de fond de TMDb quand celle du fournisseur manque ou ne se charge pas,
  sa bande-annonce, sa note avec le nombre de votes et les photos de son casting et de ses réalisateurs
  (`TmdbClient.titleInfo`, un seul appel, par l'identifiant TMDb du fournisseur ou le titre), les personnes de TMDb
  dans la recherche, et les titres d'une personne d'après ses crédits TMDb. Les réponses de TMDb sont gardées sur
  l'appareil (`SqliteMetadataStore`, `metadata.sqlite`, comme sur les appareils Apple ; clés
  `movie:603` ou `movie:sandstorm:2021`) : les fiches les affichent tout de suite, TMDb n'est redemandé qu'après
  30 jours (7 pour un titre qu'il ne connaît pas), les titres du carrousel de l'accueil et ceux ajoutés ces quatre derniers jours sont demandés à l'avance deux
  à la fois (la première source du carrousel classe ces derniers par la note moyenne de TMDb pondérée par son nombre de
  votes : `v/(v+200)·R + 200/(v+200)·6,5`, 50 votes au moins, égalité au plus récemment ajouté puis au titre ;
  `WeightedRating`, comme sur Apple), les crédits gardés trouvent les personnes dans la recherche et leurs titres sans réseau, et une adresse
  d'image morte est retenue 7 jours. Un 401 plus tard (un
  jeton révoqué, ou arrivé par la synchronisation) s'affiche comme refusé dans les Réglages, et plus rien n'est demandé.
- **Lecteur** Media3 / ExoPlayer : HLS, MPEG-TS, MP4, MKV, en-têtes du fournisseur, adresses de secours,
  reprise, image dans l'image, MediaSession, pistes audio et sous-titres (une piste de sous-titres que l'appareil
  ne peut pas afficher, comme un format image sans son décodeur, est listée avec son format et la raison, pas
  masquée : `player/TrackChoices.kt`), Sous-titres → Rechercher en ligne… pour les films et les épisodes
  (OpenSubtitles.com, SubDL et SubSource avec la propre clé d'API du spectateur, Addic7ed via Gestdown une fois
  activé ; par l'identifiant TMDb quand le fournisseur le donne, sinon par le titre sans ses étiquettes, son année,
  sa saison et son épisode ; la langue de sous-titres du spectateur d'abord ; le sous-titre choisi est chargé à côté
  de la vidéo et gardé pour le titre sur l'appareil : `core/subtitles/`, `player/OnlineSubtitles.kt` ; les clés se
  règlent dans Réglages → Services de sous-titres, sont vérifiées auprès de chaque service à l'enregistrement et
  sont gardées dans un fichier privé de l'app et synchronisées avec les autres appareils de l'utilisateur comme le
  jeton TMDb), les langues de l'audio et des
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
- **Dernier plantage** (Réglages → À propos, `data/CrashLog.kt`) : gardé sur l'appareil seulement, sans adresses
  ni identifiants, avec la mémoire dont dispose l'appareil. Une erreur non interceptée, ou la façon dont Android a
  arrêté la dernière exécution quand elle était à l'écran (mémoire épuisée, app qui ne répond pas avec la pile du
  fil principal, plantage en code natif : `ApplicationExitInfo`, Android 11 et plus), et un redémarrage de tout
  l'appareil pendant la lecture : le lecteur garde ce qui est lu (formats, HDR ou Dolby Vision, décodeurs, mémoire
  tampon, mémoire, état thermique ; jamais une adresse, un fournisseur ni un titre) dans un petit fichier réécrit
  toutes les 30 s et supprimé quand la lecture s'arrête normalement (`data/PlayingNow.kt`) ; trouvé au démarrage
  suivant, après un redémarrage de l'appareil, il devient le dernier plantage (« the device restarted during
  playback »).
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
  connectent. Playlists, Ma liste (avec les acteurs et réalisateurs gardés), collections (avec leurs personnes),
  progression et jeton TMDb ; seules des adresses locales sont
  contactées ou acceptées.
- **Langues** : anglais, français, espagnol, portugais, chinois (simplifié), arabe (de droite à gauche) et
  hindi (`res/values-*`), proposées aussi dans le réglage de langue par app d'Android 13
  (`res/xml/locales_config.xml`).
- **Android TV / Google TV** (`ui/tv/`) : une entrée et une bannière dans le lanceur Leanback
  (320 × 180 en xhdpi, dessinée par `fastlane/creative/render.mjs --tv`), écran tactile non requis. Compose for TV
  (`androidx.tv:tv-material`) en noir et blanc avec la police Inter (`TvDesign.kt`, `ui/theme/Theme.kt`) : une barre en haut avec les onglets, Recherche et
  Réglages, qui reste à l'écran quand la page défile dessous (`TvShell.kt` ; elle reste sur les titres d'une catégorie et sur les pages de genre, d'année et de prix ; Retour mène en haut de la page et à la barre, revient d'une page sur ces pages, puis mène à l'Accueil ; sur l'Accueil il ne fait rien, c'est le bouton Accueil de la télécommande qui quitte Ondo), un Accueil ouvert
  par une image encadrée (`TvHomeScreen.kt`) et des fiches film et série (`TvDetailScreens.kt`) avec l'image de fond derrière le
  texte et des boutons ronds à côté de Lecture, des Réglages en deux volets où chaque playlist a ses boutons à portée de télécommande (`TvSettingsScreen.kt` ;
  Supprimer demande d'abord, en commençant sur Annuler), des cartes qui se soulèvent avec une ombre douce et un reflet à la sélection et des rangées qui dépassent du bord droit ;
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
  Google Play (téléphone, tablettes 7" et 10", TV, anglais et français), comparées à des références versionnées
  (voir [Captures d'écran et régressions](#captures-décran-et-régressions)).
  Chaque classe de test prend `HangGuard` comme première règle (`check_hang_guard.py` en CI) : un test qui dure plus
  de 120 s échoue avec la pile de chaque thread dans le journal et dans `build/reports/tests/hang-guard`, et chaque
  test est nommé quand il commence et quand il finit : un test bloqué est nommé au lieu d'un job qui attend en
  silence son délai.

Prévu ensuite (non inclus en v1) : Chromecast, téléchargements, profils et PIN,
second moteur libVLC ; sur une TV, chaînes du lanceur et recherche vocale.

## Captures d'écran et régressions

Les tests Roborazzi prennent deux sortes de captures à partir du catalogue de démonstration (titres fictifs, films
libres, chaînes inventées, adresses `example.com`) :

- **Captures de la fiche du store**, en taille réelle, dans `android/app/src/test/screenshots/play/` (non versionnées :
  le workflow de release les refait).
- **Captures de référence**, en demi-taille, versionnées dans `android/app/src/test/snapshots/` : les écrans du store
  de la TV (anglais et français) et du téléphone (anglais), et les onglets de la TV tels que la télécommande les laisse
  (`TvScreenshots` : Films avec une rangée sélectionnée, le parcours onglet → Bas → Bas sur Films et sur Séries
  (`movies-tab-focused`, `movies-down-1`, `movies-down-2`, `series-…`), les titres d'une catégorie depuis l'onglet
  puis Bas (`grid-category`, `grid-down-1`), leurs puces de tri atteintes avec Haut (`grid-sort-chips`) et loin dans
  une longue grille (`grid-down-3`), les rangées après Retour depuis les titres d'une catégorie, le focus sur sa carte
  Tout voir (`rows-after-back`), les titres d'un genre (`browse-genre`), Accueil avec la télécommande dans ses
  rangées).

Le workflow *Android* compare chaque capture à sa référence (`./gradlew :app:verifyRoborazziDebug`) ; au plus 0,1 %
des pixels peuvent différer (anticrénelage, `screenshots/Baselines.kt`). Une capture qui a changé fait échouer le
run : le résumé du job les liste et l'artefact **android-screenshot-diffs** contient, pour chacune, la référence, la
nouvelle image et leur différence. Tant qu'aucune référence n'est versionnée, les captures sont enregistrées, pas
comparées, et le résumé le dit.

Un changement voulu de l'interface enregistre de nouvelles références, sur la branche de travail, jamais sur `main`
ni sur un tag : lancer à la main le workflow *Android* sur cette branche avec *record_screenshots* coché
(`gh workflow run android.yml --ref <branche> -f record_screenshots=true`). Ses tests doivent passer ; un job à part,
le seul autorisé à écrire, versionne alors les nouvelles références sur cette branche (auteur `ci`, une adresse
`noreply`, les métadonnées des images retirées). Récupérer la branche ensuite, et regarder les images du commit avant
de la fusionner.

Ce qu'une image ne prouve pas a ses propres tests : `TvLayoutRegressionTest` (l'image de l'en-tête depuis le haut de
l'écran et cadrée vers son haut, aucune ligne de playlist dans la barre du haut (Accueil, Films, Séries, titres d'une
catégorie et d'un genre), chaque type de rangée entier sous l'en-tête en 960 × 540 dp, le parcours onglet → Bas → Bas
sur Films et Séries avec l'en-tête sur le titre sélectionné avant son attente, une barre du haut avec les seuls
onglets, Recherche et Réglages, le nom d'une catégorie entier sur sa ligne, les titres d'une catégorie et d'un genre
sans rangée à eux au-dessus des affiches, Bas depuis l'onglet sur la première affiche et jamais sur une puce de tri,
l'ordre choisi avec la puce Tri atteinte par Haut depuis la première rangée, la grille d'une catégorie à cinq affiches
par rangée qui défile par rangées entières vers le bas et vers le haut, et une rangée de Films d'environ cinq affiches
de large) ; `TelevisionFocusTest` (Tout voir ouvre les titres d'une catégorie et Retour revient à la même rangée et à
la même carte, défilées comme avant ; un autre onglet puis Films de nouveau montre les rangées ; Ondo relancé montre
les rangées).

### Avant de modifier un écran TV (liste anti-régressions)

1. Lancer les tests et les captures sur la branche (le workflow *Android*) avant le changement : ils doivent passer.
2. Faire le changement ; ajouter ou mettre à jour un test du comportement qu'il doit garder (positions, textes,
   focus), comme dans `TvLayoutRegressionTest`.
3. Relancer le workflow. Une capture a changé : ouvrir **android-screenshot-diffs** et regarder chaque différence.
4. Toutes les différences sont voulues : enregistrer de nouvelles références (*record_screenshots*) et vérifier les
   images versionnées. L'une ne l'est pas : corriger le changement, jamais la référence.
5. Vérifier en 960 × 540 dp que chaque rangée (son nom, ses cartes et leurs lignes) tient sous l'en-tête, que la
   barre du haut n'affiche aucune ligne de playlist, et que le nom d'une catégorie se lit en entier au-dessus de ses titres.
6. Vérifier le parcours onglet → Bas → Bas avec les captures, sur Films et sur Séries (`movies-tab-focused`,
   `movies-down-1`, `movies-down-2` et de même pour `series`) : sur l'onglet, la première rangée entière sous
   l'en-tête et aucune zone vide ; à chaque Bas, le titre de la carte sélectionnée aussitôt dans l'en-tête. De même
   sur les titres d'une catégorie (`grid-category`, `grid-down-1`, `grid-down-3`) : les affiches juste sous
   l'en-tête, sans rangée à elles au-dessus, cinq par rangée, la rangée sélectionnée entière en haut et aucune coupée
   au-dessus.

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
  fournie et pas de démo, avertissement clair ; les examinateurs testent avec une playlist hébergée sur le site
  du projet, qui ne contient que du contenu dont nous avons les droits (site/review).
- Déclarations : pas de collecte de données (formulaire *Data safety*), autorisations minimales
  (réseau, notifications, premier plan pour la lecture).
