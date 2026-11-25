# Sound and subtitle tracks

*[Version française](TRACKS.fr.md)*

Ondo remembers the sound and the subtitles the viewer chooses, per film and per series, and each profile has its own
languages for the titles it never chose for. Both apps follow the same rules, in their cores: `TrackMemory` in IPTVCore
(`Packages/IPTVCore/Sources/IPTVCore/Playback/TrackMemory.swift`) and in the Kotlin core
(`android/core/src/main/kotlin/com/markedoe/ondo/core/playback/TrackMemory.kt`), with the same cases
(`fixtures/track-memory`).

## What is remembered

- **A series**: a sound track or subtitles chosen (or subtitles turned off) during an episode are kept for the series.
  Every next episode starts with them, whether it plays on its own at the end, from Next Episode, or from the series'
  page.
- **A film**: kept for the film, so that it starts with them again, from where it was left or from the start. The
  film's other versions (4K, HD, VOSTFR…, docs/VERSIONS.md) take the choice too, where they have the language.
- Each part is kept only once chosen: subtitles chosen alone leave the sound to the profile's language.
- **Live channels** keep nothing: zapping stays as it was.

A choice is kept by what tells the track apart, never by its place in the stream (two episodes list their tracks in
different orders): its language (`fre`, `fra`, `fr-CA` and `fr` are one), whether it is forced (foreign dialogue only)
or for the deaf and hard of hearing, its name when it has no language, and the sound's codec. The stream's track most
like it is chosen; the one already playing when it is as good.

## When it is missing

A stream starts with the title's choice; when it lacks that language, with the profile's language (Settings, Player);
when it lacks that too, with its own sound track and no subtitles (Android still shows forced subtitles, as Media3's
own Off does). Subtitles turned off for a title stay off whatever the profile's language.

## A profile's languages

Settings, Player: **Audio language** (Automatic, or a language) and **Subtitle language** (Off, or a language), for the
profile open. Automatic is the device's languages, then the stream's default track. They are local settings: nothing
is checked with an outside service.

## Where it is kept

In the profile's library document (`titleTracks` and `trackDefaults`), next to its history: the choices of the 500
latest titles, under the film's or the series' key (`playlist|kind|id`, as their progress). Another profile starts with
nothing of them. They stay on the device: sync does not carry the playback settings (docs/SYNC.md).

## The players

- **Android** (phone and TV, Media3): the languages are asked of Media3's track selector before the stream opens, then,
  once the stream lists its tracks, the tracks are chosen by the rules above. A change of the selection the viewer
  makes (the Audio and Subtitles panels, Media3's phone controls) is kept; one the app makes (these tracks, a subtitle
  downloaded) is not.
- **Apple** (iPhone, iPad, Mac): the system player's media selection, once its options are read; the MKV films remuxed
  on iPhone and iPad ask for the same sound languages; VLC (MKV films on a Mac, streams the system player refuses)
  chooses among the tracks it lists by the language its track names give (`Track 1 - [French]`), else by the name. A
  subtitle file loaded is no choice of the viewer's.

## Migration

The apps used to learn one sound and one subtitle language for the whole device from every choice in the player. They
no longer learn them: they are read only as the main profile's languages, until that profile sets its own in Settings.
The other profiles start with none.
