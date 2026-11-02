# Review playlist

[Français](README.fr.md)

Ondo is a player: the apps ship no playlist, no channel and no demo. App Review and the Google Play reviewer test them
with this playlist, which the review notes give:

**https://markedoe.github.io/ondo-iptv/review/ondo-review.m3u** (Add a Playlist → M3U link)

It holds only content we have the rights to: a test channel and a test series we make ourselves, and films of the
Blender Foundation under Creative Commons Attribution licences. Every address is https.

## Our own content

Made for this project with FFmpeg's built-in test sources (no third-party picture or sound), under the licence of this
repository ([LICENSE](https://github.com/markedoe/ondo-iptv/blob/main/LICENSE)).

| File | What it is |
|---|---|
| `live/channel.m3u8`, `live/segment00.ts`…`segment09.ts` | *Ondo Test Channel*: a 60-second test pattern with a tone and a running clock, in HLS. The playlist repeats it for two hours with no end tag, so the apps play it as a live channel. |
| `live/logo.png` | The channel's logo (colour bars, "TEST"). |
| `series/episode1.mp4`…`episode3.mp4`, `series/poster.png` | *Test Pattern Stories*: three 20-second episodes (S01E01–E03) and their poster. |
| `guide.xml` | The channel's TV guide (XMLTV), made-up programme names, one an hour from two days ago to four weeks ahead. `.github/scripts/review_guide.py` writes it; the website workflow writes it again at each deployment and every week. |

## Open films (Blender Foundation)

The films are linked, not copied here. Each is shown under its own title; the group is *Open movies (Blender
Foundation, CC BY)*.

| Film | Licence | Attribution and licence page | Stream |
|---|---|---|---|
| Big Buck Bunny (2008) | [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/) | © Blender Foundation, [peach.blender.org](https://peach.blender.org/about/) | HLS on Mux's public test streams, `test-streams.mux.dev` |
| Elephants Dream (2006) | [CC BY 2.5](https://creativecommons.org/licenses/by/2.5/) | © Blender Foundation, Orange Open Movie Project, [orange.blender.org](https://orange.blender.org/download/) | MOV (H.264, AAC) on `download.blender.org` |
| Sintel, trailer (2010) | [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/) | © Blender Foundation, [durian.blender.org](https://durian.blender.org/sharing/) | MP4 on `download.blender.org` |
| Tears of Steel (2012) | [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/) | © Blender Foundation, [mango.blender.org](https://mango.blender.org/sharing/) | HLS on Unified Streaming's public demo, `demo.unified-streaming.com` |

## Checks

- `.github/scripts/check_review_playlist.py` (CI): every address of the playlist is https, on this site or on one of
  the hosts above (`ALLOWED_HOSTS`), the guide is on this site and has the channel. A new host is added there and in
  this table, with its licence, or not at all.
- The release workflows (`release.yml`, `release-android.yml`), *Review playlist answers*: the playlist published on
  main's site answers, every address in it answers, and its guide covers the present hour.
