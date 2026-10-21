# Ondo pour Android — plan

[English](ANDROID.md) · **Français**

## État (v1, octobre 2026)

La v1 est dans `android/` et se publie avec [RELEASE-ANDROID.fr.md](RELEASE-ANDROID.fr.md) :

- **Cœur** `android/core` : port Kotlin d'IPTVCore (M3U, XSPF, JSON, Xtream, XMLTV, catch-up, recherche,
  éléments de lecture), testé avec les mêmes cas que les tests Swift.
- **App** `android/app` (Compose, Material 3, Navigation type-safe, barre d'onglets ou rail selon l'écran) :
  accueil (carrousel, Films en cours, Séries en cours, Ma liste, Nouveaux films, Nouvelles séries, chaînes),
  onglets Films et Séries (Continuer à regarder, Ma liste, Films primés dans Films, une rangée par catégorie,
  grilles filtrables), fiches film et série (reprise, depuis le début, épisode suivant, saisons, titres
  similaires, prix, casting et réalisation cliquables), TV en direct (catégories, Ma liste, programme en cours
  et suivant), grille du guide TV avec rappels de programmes (notifications WorkManager), onglet Ma liste avec
  collections, recherche (chaînes, films, séries, acteurs et réalisateurs ; filtres par années de sortie et
  par festival ou cérémonie), catégories masquées par code de langue ou de pays, par style ou par mot, ou une
  à une, aide, réglages (playlists, apparence, catégories adultes masquées par défaut, Mettre à jour les prix
  automatiquement, Langues et catégories, Langue de l'app, mémoires tampons du lecteur), accueil avec
  démonstration et avertissement « lecteur sans contenu ».
- **Lecteur** Media3 / ExoPlayer : HLS, MPEG-TS, MP4, MKV, en-têtes du fournisseur, adresses de secours,
  reprise, image dans l'image, MediaSession, pistes audio et sous-titres, mémoires tampons distinctes pour le
  direct et pour les films et séries, retour automatique au direct quand une chaîne prend du retard, Plus →
  Signaler un problème… (un rapport de lecture sans adresse, identifiant ni titre).
- **Stockage** : documents JSON dans le stockage privé de l'app (`data/AppStore.kt`), exclus des sauvegardes
  (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`) : réglages, playlists avec leurs identifiants, Ma
  liste, collections, progression et rappels, catalogue de chaque playlist, et fiches de films et de séries
  ouvertes (jusqu'à 1 000 films et 300 séries par playlist, téléchargées à nouveau après 7 jours pour un film
  et 12 heures pour une série, toujours affichées quand le fournisseur est injoignable ;
  `data/DetailsArchive.kt`). Affiches et vignettes sont gardées sur le disque par Coil 3 (jusqu'à 512 Mo, dans
  le cache de l'app ; `OndoApplication.kt`).
- **Langues** : anglais, français, espagnol, portugais, chinois (simplifié), arabe (de droite à gauche) et
  hindi (`res/values-*`), proposées aussi dans le réglage de langue par app d'Android 13
  (`res/xml/locales_config.xml`).
- **Tests** : unitaires (bibliothèque, vitrine, écrans, dépôt, ViewModel), Compose (Robolectric), captures
  Roborazzi qui servent de captures Google Play (téléphone, tablettes 7" et 10", anglais et français).

Prévu ensuite (non inclus en v1) : Android TV, replay depuis le guide, Chromecast, téléchargements,
profils et PIN, sous-titres en ligne, second moteur libVLC.

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
