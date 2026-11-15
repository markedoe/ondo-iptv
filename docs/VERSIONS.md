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
  `FR -`, `STFR|`, `VFF`, `MULTI`, `4K`, the sound's `Atmos`, `DTS`, `5.1`…), case and accents ignored: "|FR| Le Transport 4K" and "Le Transport VOSTFR" are both
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
- **A film's page**: its versions as chips (focusable on a TV). Each chip says in words, in the app's language, what
  sets its version apart from the others (`FilmVersions.choices`, the same rules and table on both apps:
  `fixtures/film-versions/labels.json`). The tags are read from the stream's name and its category's name:
  - quality: 4K (4K, UHD, 2160p), Full HD (FHD, 1080p), HD (HD, 720p), SD (SD, 480p, 576p);
  - picture: Dolby Vision (DV, DoVi, Dolby Vision), HDR (HDR, HDR10, HDR10+), 3D (3D, SBS, HSBS), IMAX, HEVC (HEVC,
    H.265, x265);
  - language: Multilingual (MULTI, DUAL), French (France) (VFF, TRUEFRENCH), French (Quebec) (VFQ), French
    (international) (VFI), French (VF, FRENCH), Original language (VO), English, Spanish, German, Italian, Portuguese,
    Arabic (the word, or a code in capitals at either end of the name or the category, set apart by a provider's
    delimiter: "FR - …", "|EN| Movies", "[IT] …", "… (DE)"; the first word of "IT WAITS BELOW" is no code);
  - subtitles: Original, French subtitles (VOSTFR, STFR, SUBFR, ST FR, FR SUB), Original, subtitled (VOST, VOSTA,
    SUBBED);
  - a copy filmed or leaked before the film's release: Cinema copy (CAM) (CAM, HDCAM), (TS) (TS, HDTS, TELESYNC), (TC)
    (TC, TELECINE), (SCR) (SCREENER, SCR, DVDSCR), (R5), (Workprint) (WORKPRINT), from the name or the category
    ("Cinema CAM"); the short ones and "Screener" only in capitals, so "The Night Cam" keeps its word. Such a copy is
    never one chip with a proper one, its chip comes after theirs, and it never plays first (it ranks below every other
    version, whatever the screen).

  A tag word that is also a word of titles (English, French, Spanish, German, Italian, Portuguese, Arabic, Dual, VO, SD)
  is a tag only outside the words every version's name shares: "The English Orchard" and "The English Orchard MULTI"
  read "Standard" and "Multilingual". A no-break space separates words as a space does.

  **Versions that play the same thing are one chip.** A panel often files one stream under two categories ("|FR|
  AJOUTÉS" and "|FR| DRAME | THRILLER"), sometimes in another letter case. Versions with the same chip tags
  (quality, HDR or Dolby Vision, 3D, IMAX, codec, language, subtitles), the same container and the same name once
  normalised (case, accents, apostrophes, punctuation, the provider's prefix such as "|FR|", the year left out:
  `FilmVersions.nameKey`) make one chip, labelled as one version: "|FR| Copper Vallée", "|FR| COPPER VALLÉE" and "|FR|
  Copper Vallée 4K DV" read "Standard" and "4K Dolby Vision". The chip plays its preferred stream, by the rules of the
  version shown first (below): the one in progress, else the one chosen last, else the first in catalogue order. Its
  other streams are its fallbacks: when the preferred one fails to start, the player tries them in turn (the next
  addresses of the playback request) before showing an error. The progress stays the stream's that was started, so a
  version in progress resumes where it was.

  A chip names only what differs between the versions, in that order ("4K Dolby Vision", "HD · Original, French
  subtitles"): a tag every version has is left out. Two versions that would read the same add the words of their names
  the others lack ("Extended Cut"); a version without any tag, next to tagged ones, reads "Standard". Chips still alike
  add, in turn, their container ("Standard · MKV", "Standard · MP4"), then the provider's words the chips have no word
  for, as the name writes them ("DUB"). A category's name never sets chips apart: viewers see no category on a film's
  page (only the tags read in it count: "FILMS 4K" is 4K, "Cinema CAM" a cinema copy). Chips still alike are
  numbered ("4K 1", "4K 2"; "Version 1",
  "Version 2"), the last resort. No two chips of a film read the same, and a film whose versions all play the same
  thing shows no chip. A screen reader says "Version: 4K Dolby Vision". The version picked plays; Play, the progress and
  the format badge are its own.
- **Title data**: what the app read from the provider for a title, to photograph when a chip reads wrong. In the
  player, More, Stream Statistics shows it next to the statistics (both apps): the title shown, the provider's name,
  ids (a provider's number only, never an M3U playlist's, which is a hash of the stream's address), category (name and
  id), year, the tags read, the TMDb ids (the provider's list, its details, and on Android the
  title matched), the container; for a film's versions (four at most, the one playing first), each one's chip, name,
  category, id, container and tags, the one playing, the one played first and why; every stream stays listed, those one
  chip holds together marked "same as" the one their chip plays; for an episode, its raw title and its series' name. The
  title shown is the app's on both apps, without the provider's tags around the name (`CatalogueMatcher.displayTitle`),
  as the film's page shows it (Apple's player bar still shows the provider's name as it is). On a TV, OK held on a version's chip of a film's page shows the same for every version, each a stop
  of the remote. Never an address, a login, a password or a server: the playlist's own are masked wherever they
  appear, as are addresses, `user:password@`, query values, IP addresses and `host:port` (`Redaction`, the same rules
  and table on both apps: `fixtures/title-data/redaction.json`). It is not in the problem report.
- **The version shown first** (and played from a card): the one in progress; else the one chosen last on this device;
  else the one the screen suits best (a cinema copy last); else the first. What the screen suits is read from what is
  known of each version (measured, else the provider's, else the name's tags) and from the screen:
  - **4K** on a 4K screen, whatever its mode of the moment. On Android, in this order: the largest of
    `Display.supportedModes` by physical size (a box drawing a 1080p interface, or reading 1080p in "auto" until a film
    plays, is still a 4K screen); else the size Media3 reads of a TV's display (`Util.getCurrentDisplayModeSize`: the
    `vendor.display-size` property a box writes, `sys.display-size` before Android 9, a Sony BRAVIA's 4K panel), for a
    box in "auto" that lists its 1080p interface mode only; else, on a TV only, a display that takes HDR with a
    hardware HEVC or Dolby Vision decoder for 3840×2160, only where nothing else can say: the display lists one size
    and Media3 read no display size of its own (it gives back the mode of the moment's when a TV writes none). A
    display size read below 4K is no 4K, and so is a display listing several sizes all below 4K (a 1080p HDR stick or
    TV, whose decoder may still take 2160p). A 1080p HDR TV behind a 4K box in "auto" still reads as 4K: its 4K
    versions play, downscaled. Never a phone or a tablet on that last rule, never a software decoder. The display size
    is read in the background at once, each time a screen opens; the decoders once, a few seconds after launch; a film
    started then plays the version chosen with what is known by then. On Apple the screen's native size.
  - **HDR** (HDR10, HDR10+, HLG) only where the display reports it (`Display.getHdrCapabilities`, without the types
    the viewer turned off; AVFoundation on Apple).
  - **Dolby Vision** first only where the display itself reports Dolby Vision and a decoder for it exists (on Android
    `MediaCodecList`, read once in the background at the lowest priority, after the first Home; a box's decoder alone does not make a TV show it). On a screen
    without it, a version's Dolby Vision profile decides: 8 and 9 (a 10 with a compatible base layer) play their HDR10,
    SDR or HLG base layer and count as that; 5 (a 10 without a base layer) shows wrong colours and never plays first;
    an unknown profile (a name saying "DV" only, profile 7) is taken as 5, and so is a name saying "DV" over the
    provider's specs that show no Dolby Vision (a panel's ffprobe can read only a profile 5 file's HDR10 base layer):
    only what the player measured overrules the name. Such a version stays on its chip, to be
    picked. The profile comes from the provider's configuration record (`dv_profile`,
    `dv_bl_signal_compatibility_id`), Media3's codec string ("dvhe.08.06": the profile only) or AVFoundation's `dvcC`
    record.
- **Stored data stays per stream**: favourites, history, progress and sync are unchanged, and nothing new is synced.
  A film is watched (and left out by "Hide watched movies") when one of its versions is finished, and in My List when
  one of its versions is; adding a film from its card adds its version shown first, taking it out removes every
  version kept. Continue Watching keeps each stream, with its version's chip. The version chosen last is kept on the
  device only.

## What a version is made of

A version's chip and its page say what its stream is made of, as far as it is known, never more (`StreamSpecs`, the
same rules and words on both apps: `fixtures/stream-specs/specs.json`). Three sources, best first, field by field:

1. **What the player measured** while the stream played: the picture's size, range (HDR10, HLG, Dolby Vision, or SDR)
   and codec, the sound's codec, channels and Atmos (E-AC-3 JOC), from Media3's tracks on Android and AVFoundation's
   format descriptions on Apple (an adaptive stream's highest rendition; on Apple an HLS stream lists no tracks, so its
   picture is left to the provider or the name). The sound is the stream's default track, else the one of the most
   channels, never the viewer's choice of the moment; a stream once read is read again only when its picture grew.
   Kept on the device for the 2,000 films played last, by the playlist the film plays from and its stream
   (`MeasuredSpecs`, `measured-specs.json`), read the first time a film's page or the player needs it, never at launch.
   A measured SDR picture is no HDR picture, whatever its name says; a measurement never takes the provider's Atmos
   away (AVFoundation names none, Media3 none on TrueHD nor in an MKV's E-AC-3). Media3's E-AC-3 JOC, or an HLS
   playlist's `ec+3`, is Atmos; its DTS Express is DTS-HD and its DTS UHD (profile 2) DTS:X, as ffprobe's "DTS:X"
   profiles are.
2. **The provider's details** (`get_vod_info`'s `info.video`, `info.audio` and `info.bitrate`, what the panel's ffprobe
   read), kept with the details. A field missing, empty, zero or unknown stays unknown. Only the fields that say it are
   read, never free text such as a stream's title: Dolby Vision from `codec_tag_string`, the video's `profile` or a
   configuration record in `side_data_list`, HDR10+ only from SMPTE ST 2094 metadata there, HDR10 and HLG from
   `color_transfer` (never from a 10-bit profile alone), Atmos from the sound's `profile`, its layout from
   `channel_layout` ("5.1(side)" is 5.1, "4.1" and "3.0" stay as they are; the count of channels only without one).
   The film's page already asks for its own version's details; once it is open and idle a moment, it asks for its
   other versions' details: six at most each time it opens, 400 ms apart, cancelled when it closes, stopped at the
   first refusal (that version is not asked again until the app restarts), and never while the player is on top (the
   page goes on once it is back). They are kept apart from the pages viewers opened (`DetailsArchive.versions`, 300 at
   most), so they never push those out. Browsing (rows, headers, search) asks for none of them.
3. **The tags of the name** ("4K", "DV", "HEVC"), as before.

- **Resolution classes**: 4K, 1080p, 720p or SD, from the height or the width, whichever reaches a class: a film in
  scope (1920×802) is 1080p, 3840×1600 is 4K, an anamorphic 1440×1080 is 1080p.
- **The facts line**, after the format ("MKV"), for the version that would play: its class, its range (HDR10, HDR10+,
  HLG, Dolby Vision), its video codec (H.264, HEVC, AV1) and its sound ("5.1 AAC", "2.0 AC-3", "Atmos"). Screen readers
  say them in a sentence of the viewer's language ("4K, Dolby Vision, HEVC, 5.1 surround sound, AAC").
- **The chips** say a version's class, range and sound where they are known ("1080p · 5.1", "4K Dolby Vision · Atmos"),
  its name's tags elsewhere; "Standard" only for a version nothing is known of. Two versions the rules above make one
  chip stay apart when their known specs differ; one whose specs are unknown stays with the first. Chips that would read
  the same are set apart by their video codec, then their sound's codec, then as before. Without specs, the chips are
  those of before (`fixtures/film-versions/labels.json`). Each chip is known by its first version's id, so a chip that
  splits as specs arrive moves no other one, and the focus stays on the version the remote was on.
- **The title data** lists each version's specs and where they come from (measured by the player, from the provider,
  from the name), and, to tell why a version shows no specs or does not play first:
  - what its provider's details held: not requested, none for this playlist (M3U), failed (an HTTP status or "timeout",
    "DNS", "TLS", "JSON"), kept by an earlier version (asked again), requested with no technical block, video + audio,
    video only, audio only; whether they are kept on the device; the names of the fields of `info` (names only, plain
    ones, sorted, 40 at most, never a value: a value can hold an address);
  - its Dolby Vision profile ("P8.1", "P5", unknown) and what it asks of the screen and does not get ("Dolby Vision not
    supported by this screen: profile unknown, not played first", "HDR not supported by this screen", "4K above this
    screen's largest mode");
  - for the screen, after the versions: the mode of the moment and the largest mode (Android), HDR10, HDR10+, HLG and
    Dolby Vision yes or no, the Dolby Vision and HEVC Main 10 decoders, what the sound output takes (Android: its
    channels (the most of Media3's speaker layouts, or what the HDMI plug says on an HDMI output, never Media3's
    default of 10) and the encodings it passes as they are to the TV or amplifier, from Media3's `AudioCapabilities`,
    read again each time the panel is worked out; iPhone and iPad: its channels and Spatial Audio), whether it is 4K and why (its modes, its display
    size, an HDR screen with a 4K decoder) with the display size Media3 reads (Android), what the versions are chosen
    for ("4K · HDR"), and the app's version and build, so a photo says which build it was.

  The panel never runs past the bottom of the screen: when it does not fit it scrolls, on a TV with the remote's Up and
  Down while the controls are hidden (at its top Up, at its bottom Down, do what they do without it), on an iPhone, an
  iPad or a Mac with a swipe or the scroll wheel.

  A film alone lists its own line too. In the player the panel is worked out again when the player's reading, a
  version's details or a failure arrive.
- Details kept by a version of the app that did not note what the provider's answer held (before the specs were read)
  are shown, and asked again as if they were old: the page's own when it opens, the other versions' when it is idle.
- A same-size reading of a stream measured before adds what the first did not know (its Dolby Vision profile); a
  smaller one or another sound changes nothing. A film VLC plays (an MKV on a Mac, a stream AVPlayer refused) is
  measured too, its picture's size only.

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
