# Ondo core (Kotlin)

**English** · [Français](README.fr.md)

Pure Kotlin/JVM port of the Swift `IPTVCore` package (`Packages/IPTVCore`): no Android imports, so the app
and its unit tests share the same parsing, guide, catch-up, search and library logic. The Swift sources are
the specification; behaviour and edge cases are kept unless listed under *Differences* below.

## Packages (`com.markedoe.ondo.core`)

| Package | Contents |
| --- | --- |
| *(root)* | `TextNormalizer` (accent/case/width folding, channel match keys), `StableHash` (FNV-1a ids, identical to Swift), `CoreJson`, `Strings` |
| `model` | `@Serializable` immutable models: `Channel`, `Movie`, `Series`/`Episode`/`Season`, `MediaCategory`, `MediaDetails`, `PlaylistContent`, `Playlist`/`PlaylistSource`, `XtreamCredentials`, `AccountInfo`, `EpgProgram`/`EpgData`, `MediaReference`, `HistoryEntry`, `FavoriteEntry`, `PlaybackItem`, `CustomList`, `ProgramReminder`, `Profile` |
| `parsing` | Byte-level `M3uParser`, `ContentClassifier`, `TitleParser`, `M3uContentBuilder`, XSPF / JSON / plain lists (`PlaylistFormat`), `SubtitleParser` (SRT, WebVTT) |
| `xtream` | `XtreamClient` (account, catalogue, details, short EPG), `XtreamUrlBuilder` (incl. timeshift), lenient DTO decoding |
| `epg` | Streaming SAX `XmltvParser` (gzip, window / channel filters, time shift), `EpgFilter`, `EpgIndex` (matching, now/next, suggestions, programme search) |
| `catchup` | `CatchupUrlBuilder` (default / append / shift / flussonic / xtream, template tokens) |
| `library` | `PlaylistLoader`, `PlaybackFactory`, `UserLibrary` (favourites, history, lists, reminders, hidden channels, deterministic merge), `SearchEngine`, `ChannelNumbering`, `ChannelZapping` (the remote's channel keys), `People` (actors and directors), `Awards` (festival awards and selections), `CategoryStyles` and `CategoryTags` (hiding categories by style, word, language or country), `JsonFileStore`, `BackupArchive`, TMDb (`TmdbClient`, `CatalogueMatcher`), `ErrorAttribution` |
| `net` | `HttpClient` interface, `OkHttpClientAdapter`, `HttpException`, `Gzip`, `Urls` |
| `playback` | `PlaybackBuffer` (buffer settings → Media3 buffer durations), `PlaybackHealth` (freezes, data rate, pictures lost), `MediaFormat` (MKV, MP4, HLS… for Stream Statistics), `PlaybackLog` and `PrivacyScrubber`, `ProblemReport` (the playback problem report, with no address, login or title) |
| `sync` | Sync on the local network ([docs/SYNC.md](../../docs/SYNC.md)): `SyncSnapshot` (what a device sends), `SyncMerge` (the deterministic merge), `SyncNetwork` (local addresses, frames), `SyncSession` (one exchange, server and client) |

## Conventions

- Times are **epoch millis** (`Long`), except `Movie.addedAt` and `Series.addedAt`, which are epoch **seconds**
  (as Xtream sends them). Positions and durations of playback are seconds (`Double`).
- URLs are `String`s, cleaned like Foundation's `URL(string:)` (invalid characters percent-encoded).
- Models are immutable. `UserLibrary` operations return a new library instead of mutating.
- IO is `suspend`; parsing runs on an injectable dispatcher (`Dispatchers.Default` by default). Everything is
  testable without network through `HttpClient`; clocks are injectable (`clock = { … }`).
- XML uses SAX (available on the JVM and on Android; StAX is not on Android).

## Differences from the Swift package

- Apple-only plumbing is not ported (the MKV remux among it); `FileStore` becomes `JsonFileStore` (atomic write +
  `.bak`, a damaged file moved aside).
- Kotlin names: `isCategoryHidden` / `isChannelHidden` (Swift overloads on labels), `toggleInList(listId, …)`,
  `createList(name, id, now)`, `merged(other)`, `EpgIndex.withOverrides(…)` / `inserting(…)`, `EpgData.merged` / `pruned`.
- `PlaybackItem.urls` is the de-duplicated candidate list (stream first); `headers` is `null` when empty.
- `SearchEngine.search` defaults to 150 results per type (Swift: 200).
- Xtream `get_series_info` with a *flat* array of episodes is decoded; the Swift decoder silently returned no
  episodes for it (its array-of-arrays branch always matched first).
- `PlaylistSource` serialises as `{"type":"remote","url":…}`; files written by the Apple apps are not read.

## Building and testing

```sh
cd android
./gradlew -p core test          # what CI runs (wrapper, JDK 21, jvmTarget 17)
# or, with a local Gradle:  cd android/core && gradle test
```

Tests live in `src/test/kotlin` (JUnit 4 + kotlin-test, `kotlinx-coroutines-test`, OkHttp `MockWebServer`) and use
the Swift fixtures copied to `src/test/resources/fixtures`. The Swift test cases of the ported parts are ported,
plus tests for the adapted parts and performance guards (60 000 playlist entries, 100 000 guide programmes, merging
two large guides).
