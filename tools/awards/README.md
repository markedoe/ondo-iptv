# Awards data

[Français](README.fr.md)

`awards.json` lists films that won or competed for major film awards (Cannes, Venice, Berlin, the Academy
Awards, the Césars, the BAFTAs, the Golden Globes, the Goyas, the European Film Awards, San Sebastián and
Locarno), and TV series that won or were nominated for major series awards, with their titles in several
languages, their year (for a series, the year it was first aired) and their TMDb id when known (for a series,
its TMDb TV id).

Series awards: the Primetime Emmys (Drama, Comedy, Limited Series), the Golden Globes (Television Series – Drama,
– Musical or Comedy, Limited or Anthology Series), the BAFTA TV Awards (Drama Series, International), the
International Emmy for drama series and the Peabody Awards. Left out while Wikidata links almost no series to them:
Séries Mania, Canneseries, the BAFTA TV mini-series award and the International Emmy for comedy. Wikidata does not
list every winner and nominee of every year; many wins have no year of their own and take the series' first year.

- **Source**: [Wikidata](https://www.wikidata.org), under CC0 1.0 (public domain).
- **Format**: `awards` and `films` for films; `seriesAwards` and `series` for series, in the same shape. The apps
  released before series had awards read only `awards` and `films`: those keep their shape, and no series award
  ever appears there (`.github/scripts/check_awards.py` checks it; the core tests read the bundled file with those
  apps' decoder and get the same films).
- **Use**: the apps read the file on the device and match it with the playlist's movies by title and year, and its
  series by title and first year, a movie only among films and a series only among series. Nothing about the
  viewer is sent anywhere.
- **Automatic refresh**: the *Awards data* workflow (`.github/workflows/awards.yml`) runs the script once a
  month and publishes `awards.json` on the `awards-data` branch, unless the new file lists 10% fewer films, or
  10% fewer series, than the published one. The apps download it once a week (Settings, *Update awards
  automatically*, on by default) and keep it when it is newer than their bundled copy and lists at least 1,000
  films.
- **Copies**: `App/Resources/awards.json` (Apple) and `android/app/src/main/assets/awards.json` (Android),
  written together; `.github/scripts/check_awards.py` (CI) checks that they are the same and well formed.

## Refresh

```sh
python3 -I tools/awards/generate_awards.py --cache /tmp/awards-cache --pause 65
```

`--output FILE` writes the file there instead of the two copies. `--pause` spaces the queries out (Wikidata may limit a network to one query a minute); `--cache` keeps the
answers so that an interrupted run resumes. The script stops if a known winner is missing (Pulp Fiction,
Palme d'Or 1994; Breaking Bad, Emmy for drama series 2013…), which would mean an award points to the wrong
Wikidata item.
