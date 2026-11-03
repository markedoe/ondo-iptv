# Film versions

*[Version française](VERSIONS.fr.md)*

Playlists often list the same film several times: a 4K Dolby Vision copy, a 4K one, an HD one, a VOSTFR one… With
**Group versions** on (Settings, on by default), the apps show each film once in their lists, and its page shows its
versions as badges to choose from. Off, every stream appears as the playlist lists it, exactly as before.

The rules live in the cores, with the same tests on both platforms: `FilmVersions` in IPTVCore
(`Packages/IPTVCore/Sources/IPTVCore/Library/FilmVersions.swift`) and in the Kotlin core
(`android/core/src/main/kotlin/com/markedoe/ondo/core/library/FilmVersions.kt`). They never lose or hide a stream: every
stream is in exactly one group. When in doubt, streams stay apart. Both cores read the same hand-written catalogues and
their expected groups (`fixtures/film-versions/golden.json`).

## When two streams are one film

Only movies, and only within one playlist (live channels and series are never grouped).

- **The title** compared is the provider's title without its tags (`CatalogueMatcher.displayTitle`: `|FR|`, `[FR]`,
  `FR -`, `STFR|`, `VFF`, `MULTI`, `4K`…), case and accents ignored: "|FR| Le Transport 4K" and "Le Transport VOSTFR" are both
  "le transport".
- **Rule A, the provider's TMDb id.** The Xtream list's `tmdb` (or `tmdb_id`) field counts only when it is a whole
  number above zero (`0`, empty, `true`, `12.5` or text are no id). A provider's id can be wrong (a sequel matched to
  the first film, a saga's id on every film), so two streams of the same id are grouped only when everything agrees:
  - **the same year** when both have one (a group never holds two years: "Dune" 1984 and "Dune" 2021 stay apart);
  - **the same numbers**: a number in one title that the other lacks or writes differently (2, IV, deux, two…) makes
    another film ("Toy Story" and "Toy Story 3", "Episode IV" and "Episode V", "Fast and Furious 7" and "8");
  - then **the same title**, or **the same words** with short words such as "the", "le" or "de" left out ("the matrix"
    and "matrix"); or the words of one are the first words of the other ("le transport" and "le transport heritage")
    **only with the same year given on both** and no word of a sequel among the others (part, chapter, episode,
    volume… in the apps' languages): "Dune: Part Two" is not "Dune".

  "Alien" and "Aliens" do not agree; nor do "Harry Potter and the Chamber of Secrets" and "…the Prisoner of Azkaban". A
  stream whose title does not agree stays apart and is counted (`idTitleMismatches`; of them, `prefixWithoutYears` are
  those kept apart only because a year was missing). **Two different ids are never in the same group**, whatever the
  titles.
- **Names that say nothing of the film** never group: a name made of the providers' tags only ("4K", "[FR] MULTI"), or,
  without an id, a bare number ("FR - 2021"). Words that are also tags ("French Kiss", "Johnny English") are tags only in
  capitals ("FRENCH").
- **Rule B, no id on one side or both.** The same title exactly and the same year, present on both: no year, no group;
  no year of tolerance ("Dune" 1984 and "Dune" 2021 are two films; so are "Sandstorm" and "Sandstorm 2"). A stream
  without an id joins a group with an id only when a single id has that title and year; when two ids do, it stays with
  the streams without an id (`ambiguousTitles`), so it never links two ids.

## What the apps show

- **Lists** (rows, grids, search, a person's, a genre's, a year's or an award's page, Home, My List, More Like This): each
  film once. In a category, the first of its versions in that category stands for it, so a film is in every category one
  of its versions is in.
- **A film's page**: its versions as badges (focusable chips on a TV), read from the stream's name and category: 4K
  (4K, UHD, 2160p), HDR (HDR, HDR10), DV (DV, Dolby Vision), HD (HD, FHD, 1080p, 720p), HEVC (HEVC, H.265, x265),
  TRUEFRENCH, VFF, VFQ, VF, VOSTFR (VOSTFR, STFR, SUBFR, ST FR, FR SUB), MULTI. A version with none of them reads "Version". Two versions with the same
  badge are two badges ("4K", "4K 2"). The version picked plays; Play, the progress and the format badge are its own.
- **The version shown first** (and played from a card): the one in progress; else the one chosen last on this device;
  else the one the screen suits best (4K on a 4K screen, HDR or Dolby Vision only where the screen shows them, as
  Android's display and Apple's AVFoundation report); else the first.
- **Stored data stays per stream**: favourites, history, progress and sync are unchanged, and nothing new is synced.
  A film is watched (and left out by "Hide watched movies") when one of its versions is finished, and in My List when
  one of its versions is; adding a film from its card adds its version shown first, taking it out removes every
  version kept. Continue Watching keeps each stream, with its version's badge. The version chosen last is kept on the
  device only.

## Known limits

These keep two versions apart that are one film (an extra card, never a film hidden):

- A tag written in title case ("Sandstorm French", "Sandstorm Dual") stays a word of the title: "French", "English",
  "Dubbed", "Subbed" and "Dual" are tags only in capitals, so that "French Kiss" and "Johnny English" keep their name.
- A provider's year one apart between two versions (release dates differ between countries) makes two films under one
  id.
- Without both years, a version whose name adds words to the film's ("Le Transport : Héritage") stays apart
  (`prefixWithoutYears`).
- A year at the end of a name ("Dune 2021") is taken as the stream's year when it is its own, or when it has none and
  the number is a year up to next year; "Blade Runner 2049" keeps its number.

## Cost

The groups are built once per catalogue, off the main thread, in one pass over the movies (hash tables, no
comparison of every pair), and kept as arrays of indices into the catalogue: 100,000 films are grouped within the
64 MB heap of the Kotlin core's memory test.
