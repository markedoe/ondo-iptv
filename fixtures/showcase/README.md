# Demo catalogue for the store screenshots

[Français](README.fr.md)

The App Store and Google Play screenshots show this catalogue instead of an empty app. It is used by the screenshot
tests only: the in-app demo playlist is unchanged, and Ondo still provides no content.

- `catalogue.json`: movies, a series, fictional channels with a guide, and a library in use (in progress, My List,
  favourite channels).
- `art/`: posters, backdrops and stills of open films (Creative Commons licences), re-encoded without metadata;
  channel logos drawn by the build script. Credits: [CREDITS.md](CREDITS.md).
- `tools/`: `titles.json` (films, series, picture sources and licences), `channels.json` (channels and guide) and
  `build_showcase.py`, which downloads the pictures and writes everything above:

```sh
python3 -I fixtures/showcase/tools/build_showcase.py --cache /path/to/cache
```

Only pictures under a licence that allows reuse (CC BY, CC BY-SA, CC0, public domain everywhere) may be added, with
their author and source in `titles.json`.
