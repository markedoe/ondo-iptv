# Review playlist

[Français](README.fr.md)

Ondo is a player: the apps ship no playlist, no channel and no demo. App Review and the Google Play reviewer test them
with this playlist, which the review notes give:

**https://markedoe.github.io/ondo-iptv/review/ondo-review.m3u** (Add a Playlist → M3U link)

It holds only content we have the rights to: a test channel and a test series we make ourselves, and excerpts of
films of the Blender Foundation under Creative Commons Attribution licences. Every file is hosted here, on this site,
and every address is https: no third-party host can stop answering or change what reviewers see.

## Our own content

Made for this project with FFmpeg's built-in test sources (no third-party picture or sound), under the licence of this
repository ([LICENSE](https://github.com/markedoe/ondo-iptv/blob/main/LICENSE)).

| File | What it is |
|---|---|
| `live/channel.m3u8`, `live/segment00.ts`…`segment09.ts` | *Ondo Test Channel*: a 60-second test pattern with a tone and a running clock, in HLS. The playlist repeats it for two hours with no end tag, so the apps play it as a live channel. |
| `live/logo.png` | The channel's logo (colour bars, "TEST"). |
| `series/episode1.mp4`…`episode3.mp4`, `series/poster.png` | *Test Pattern Stories*: three 20-second episodes (S01E01–E03) and their poster. |
| `guide.xml` | The channel's TV guide (XMLTV), made-up programme names, one an hour from two days ago to four weeks ahead. `.github/scripts/review_guide.py` writes it; the website workflow writes it again at each deployment and every week. |

## Open films (Blender Foundation), in `films/`

Short excerpts of the films, re-encoded small and hosted here. They are not under this repository's licence: each
stays under its own Creative Commons Attribution licence below, and is credited to its authors. Each is shown under
its own title; the group is *Open movies (Blender Foundation, CC BY)*.

| Film | Licence | Attribution and licence page | File here | Source and what was changed |
|---|---|---|---|---|
| Big Buck Bunny (2008) | [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/) | © Blender Foundation, [peach.blender.org](https://peach.blender.org/about/) | `films/big-buck-bunny/movie.m3u8` and `segment00.ts`…`segment07.ts` (HLS, 6-second segments) | `download.blender.org/peach/bigbuckbunny_movies/BigBuckBunny_640x360.m4v.zip`; 45 seconds from 1:00 to 1:45 |
| Elephants Dream (2006) | [CC BY 2.5](https://creativecommons.org/licenses/by/2.5/) | © Blender Foundation, Orange Open Movie Project, [orange.blender.org](https://orange.blender.org/download/) | `films/elephants-dream.mov` (MOV) | `download.blender.org/ED/elephantsdream-720-h264-st-aac.mov`; 45 seconds from 1:00 to 1:45 |
| Sintel, trailer (2010) | [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/) | © Blender Foundation, [durian.blender.org](https://durian.blender.org/sharing/) | `films/sintel-trailer.mp4` (MP4) | `download.blender.org/durian/trailer/sintel_trailer-720p.mp4`; the whole trailer (52 seconds) |
| Tears of Steel (2012) | [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/) | © Blender Foundation, [mango.blender.org](https://mango.blender.org/sharing/) | `films/tears-of-steel/movie.m3u8` and `segment00.ts`…`segment07.ts` (HLS, 6-second segments) | `download.blender.org/demo/movies/ToS/tears_of_steel_720p.mov`; 45 seconds from 2:00 to 2:45 |

All four were re-encoded with FFmpeg to 640×360 at 24 frames per second (letterboxed for the wider films), H.264 Main
at about 550 kbit/s and stereo AAC at 96 kbit/s, about 4 MB each; the HLS films are video-on-demand playlists with an
end tag. Their metadata and chapters were removed (`-map_metadata -1 -map_chapters -1`); the picture and sound are
otherwise unchanged.

## Checks

- `.github/scripts/check_review_playlist.py` (CI): every address of the playlist is https on this site, and nowhere
  else; each one names a file of `site/review/`, each HLS playlist here names segments that exist next to it, and the
  guide is on this site and has the channel. A new film is added as a file here, with its licence in this table, or
  not at all.
- The release and catch-up workflows (`release.yml`, `release-android.yml`, `play-catchup.yml`), *Review playlist
  answers*: the playlist published on main's site answers, every address in it answers, and its guide covers the
  present hour.
