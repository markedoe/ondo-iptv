# Awards data

[Français](README.fr.md)

`awards.json` lists films that won or competed for major film awards (Cannes, Venice, Berlin, the Academy
Awards, the Césars, the BAFTAs, the Golden Globes, the Goyas, the European Film Awards, San Sebastián and
Locarno), with their titles in several languages, their year and their TMDb id when known.

- **Source**: [Wikidata](https://www.wikidata.org), under CC0 1.0 (public domain).
- **Use**: the apps read the file on the device and match it with the playlist's movies by title and year.
  Nothing about the viewer is sent anywhere.
- **Automatic refresh**: the *Awards data* workflow (`.github/workflows/awards.yml`) runs the script once a
  month and publishes `awards.json` on the `awards-data` branch, unless the new file lists 10% fewer films than
  the published one. The apps download it once a week (Settings, *Update awards automatically*, on by default)
  and keep it when it is newer than their bundled copy and lists at least 1,000 films.
- **Copies**: `App/Resources/awards.json` (Apple) and `android/app/src/main/assets/awards.json` (Android),
  written together; `.github/scripts/check_awards.py` (CI) checks that they are the same and well formed.

## Refresh

```sh
python3 -I tools/awards/generate_awards.py --cache /tmp/awards-cache --pause 65
```

`--output FILE` writes the file there instead of the two copies. `--pause` spaces the queries out (Wikidata may limit a network to one query a minute); `--cache` keeps the
answers so that an interrupted run resumes. The script stops if a known winner is missing (Pulp Fiction,
Palme d'Or 1994…), which would mean an award points to the wrong Wikidata item.
