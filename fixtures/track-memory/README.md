# Track memory

*[Version française](README.fr.md)*

The sound and subtitles kept for each film and series, and a profile's default languages (docs/TRACKS.md), read by both
cores: Android's `TrackMemoryTest` and Apple's `TrackMemoryTests`.

`cases.json` holds:

- `languages`: a code as a stream or a setting gives it, and the language both cores compare (`fre`, `fra`, `fr-CA`:
  `fr`; `und`: none);
- `matches`: a stream's tracks, what a choice kept of a track (`pick`), the track playing (`current`, optional) and the
  track found (its index; none when the stream has not its language);
- `plans`: a stream's sound and subtitle tracks, what is remembered for the title (`memory`) and the profile's defaults,
  and the tracks it starts with (sound none: the player's own; subtitles none: off);
- `remember`: what is remembered, the keys of the title playing (a film's versions: the one playing first), a choice
  (`audio`, `subtitles`, `subtitlesOff`) and what is remembered then, the latest first;
- `subtitlesOff`: what is remembered and the profile's defaults, and whether a stream without the subtitles wanted
  shows none (the title's or the profile's Off) rather than the player's own pick (forced ones);
- `titleKeys`: a progress key and the key a title's tracks are kept under (a series' for all its episodes; none for a
  channel).

A rule changed in one core is changed in the other, with a case here.
