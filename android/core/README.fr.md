# Cœur d'Ondo (Kotlin)

[English](README.md) · **Français**

Portage en Kotlin/JVM pur du paquet Swift `IPTVCore` (`Packages/IPTVCore`) : aucun import Android, si bien que
l'app et ses tests unitaires partagent la même logique de lecture des playlists, de guide, de replay, de recherche
et de bibliothèque. Les sources Swift font foi : comportements et cas limites sont repris, sauf ceux listés plus
bas sous *Différences*.

## Paquets (`com.markedoe.ondo.core`)

| Paquet | Contenu |
| --- | --- |
| *(racine)* | `TextNormalizer` (sans accents, casse ni largeur ; clés de correspondance des chaînes), `StableHash` (identifiants FNV-1a, identiques à Swift), `CoreJson`, `Strings` |
| `model` | Modèles immuables `@Serializable` : `Channel`, `Movie`, `Series`/`Episode`/`Season`, `MediaCategory`, `MediaDetails`, `PlaylistContent`, `Playlist`/`PlaylistSource`, `XtreamCredentials`, `AccountInfo`, `EpgProgram`/`EpgData`, `MediaReference`, `HistoryEntry`, `FavoriteEntry`, `PlaybackItem`, `CustomList`, `ProgramReminder`, `Profile` |
| `parsing` | `M3uParser` octet par octet, `ContentClassifier`, `TitleParser`, `M3uContentBuilder`, listes XSPF / JSON / texte (`PlaylistFormat`), `SubtitleParser` (SRT, WebVTT) |
| `xtream` | `XtreamClient` (compte, catalogue, fiches, guide court), `XtreamUrlBuilder` (timeshift compris), décodage tolérant des réponses |
| `epg` | `XmltvParser` SAX en flux (gzip, filtres de période et de chaînes, décalage horaire), `EpgFilter`, `EpgIndex` (correspondance, en cours / à suivre, suggestions, recherche de programmes) |
| `catchup` | `CatchupUrlBuilder` (default / append / shift / flussonic / xtream, jetons des modèles d'adresse) |
| `library` | `PlaylistLoader`, `PlaybackFactory`, `UserLibrary` (favoris, historique, listes, rappels, chaînes masquées, fusion déterministe), `SearchEngine`, `ChannelNumbering`, `ChannelZapping` (les touches de chaîne de la télécommande), `People` (acteurs et réalisateurs), `Awards` (prix et sélections des festivals), `CategoryStyles` et `CategoryTags` (masquer des catégories par style, mot, langue ou pays), `JsonFileStore`, `BackupArchive`, TMDb (`TmdbClient`, `CatalogueMatcher`), `ErrorAttribution` |
| `net` | Interface `HttpClient`, `OkHttpClientAdapter`, `HttpException`, `Gzip`, `Urls` |
| `playback` | `PlaybackBuffer` (réglages du tampon → durées du tampon Media3), `PlaybackHealth` (gels, débit, images perdues), `MediaFormat` (MKV, MP4, HLS… pour Statistiques du flux), `PlaybackLog` et `PrivacyScrubber`, `ProblemReport` (le rapport de problème de lecture, sans adresse, identifiant ni titre) |
| `sync` | Synchronisation sur le réseau local ([docs/SYNC.fr.md](../../docs/SYNC.fr.md)) : `SyncSnapshot` (ce qu'envoie un appareil), `SyncMerge` (la fusion déterministe), `SyncNetwork` (adresses locales, trames), `SyncSession` (un échange, serveur et client) |

## Conventions

- Les instants sont en **millisecondes epoch** (`Long`), sauf `Movie.addedAt` et `Series.addedAt`, en
  **secondes** epoch (comme Xtream les envoie). Positions et durées de lecture sont en secondes (`Double`).
- Les adresses sont des `String`, nettoyées comme le `URL(string:)` de Foundation (caractères invalides
  encodés en pourcentage).
- Les modèles sont immuables. Les opérations de `UserLibrary` renvoient une nouvelle bibliothèque au lieu de la
  modifier.
- Les entrées-sorties sont `suspend` ; l'analyse tourne sur un dispatcher injectable (`Dispatchers.Default` par
  défaut). Tout se teste sans réseau grâce à `HttpClient` ; les horloges sont injectables (`clock = { … }`).
- Le XML passe par SAX (disponible sur la JVM et sur Android ; StAX ne l'est pas sur Android).

## Différences avec le paquet Swift

- La plomberie propre à Apple n'est pas portée (dont la conversion des MKV) ; `FileStore` devient `JsonFileStore`
  (écriture atomique + `.bak`, un fichier abîmé mis de côté).
- Noms Kotlin : `isCategoryHidden` / `isChannelHidden` (Swift surcharge par libellés), `toggleInList(listId, …)`,
  `createList(name, id, now)`, `merged(other)`, `EpgIndex.withOverrides(…)` / `inserting(…)`, `EpgData.merged` / `pruned`.
- `PlaybackItem.urls` est la liste des adresses candidates sans doublon (le flux d'abord) ; `headers` vaut `null`
  quand il est vide.
- `SearchEngine.search` renvoie par défaut 150 résultats par type (Swift : 200).
- Un `get_series_info` Xtream dont les épisodes forment un tableau *plat* est décodé ; le décodeur Swift n'en
  renvoyait aucun, sans erreur (sa branche « tableau de tableaux » l'emportait toujours).
- `PlaylistSource` s'écrit `{"type":"remote","url":…}` ; les fichiers écrits par les apps Apple ne sont pas lus.

## Compiler et tester

```sh
cd android
./gradlew -p core test          # ce que lance la CI (wrapper, JDK 21, jvmTarget 17)
# ou, avec un Gradle local :  cd android/core && gradle test
```

Les tests sont dans `src/test/kotlin` (JUnit 4 + kotlin-test, `kotlinx-coroutines-test`, `MockWebServer` d'OkHttp)
et utilisent les fixtures Swift copiées dans `src/test/resources/fixtures`. Les cas de test Swift des parties
portées le sont aussi, avec des tests des parties adaptées et des garde-fous de performance (60 000 entrées de
playlist, 100 000 programmes de guide, fusion de deux grands guides).
