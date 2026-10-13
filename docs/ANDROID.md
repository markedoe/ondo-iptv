# Ondo pour Android — plan

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
