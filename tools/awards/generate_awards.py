#!/usr/bin/env python3
"""Builds awards.json: films and TV series that won or competed for major awards, from Wikidata (CC0).

Run by hand to refresh the copies bundled with the apps, with `python3 -I tools/awards/generate_awards.py`: it
writes the same file to the Apple app (App/Resources/awards.json) and to the Android app
(android/app/src/main/assets/awards.json); `.github/scripts/check_awards.py` checks that both copies match.
The "Awards data" workflow runs it once a month with `--output`, and publishes the result on the `awards-data`
branch, where the apps fetch it (unless the viewer turns that off in Settings).

Series are kept apart from films (`seriesAwards` and `series`, next to `awards` and `films`): the apps released
before series had awards read only `awards` and `films`, so a newer file must leave those two as they were.

Nothing about the viewer is ever sent: the apps only read this file. This script only asks Wikidata for the
films and series of each award.
"""
import argparse
import datetime
import hashlib
import json
import pathlib
import sys
import time
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUTPUTS = [ROOT / "App/Resources/awards.json", ROOT / "android/app/src/main/assets/awards.json"]
ENDPOINT = "https://query.wikidata.org/sparql"
USER_AGENT = "OndoAwardsBuilder/1.0 (https://markedoe.github.io/ondo-iptv/)"

# id, Wikidata item, group shown together (a festival or a ceremony). Each item was checked by its label and by a
# film known to have won it (see KNOWN below).
AWARDS = [
    ("palme-dor", "Q179808", "cannes"),
    ("cannes-grand-prix", "Q844804", "cannes"),
    ("cannes-jury-prize", "Q164200", "cannes"),
    ("camera-dor", "Q775091", "cannes"),
    ("golden-lion", "Q209459", "venice"),
    ("golden-bear", "Q154590", "berlin"),
    ("silver-bear-grand-jury", "Q664212", "berlin"),
    ("oscar-best-picture", "Q102427", "oscars"),
    ("oscar-international-feature", "Q105304", "oscars"),
    ("oscar-animated-feature", "Q106800", "oscars"),
    ("cesar-best-film", "Q645595", "cesars"),
    ("bafta-best-film", "Q139184", "baftas"),
    ("golden-globe-drama", "Q1011509", "golden-globes"),
    ("golden-globe-comedy", "Q670282", "golden-globes"),
    ("goya-best-film", "Q1467554", "goyas"),
    ("european-film-award", "Q777921", "european-film-awards"),
    ("golden-shell", "Q775086", "san-sebastian"),
    ("golden-leopard", "Q1700510", "locarno"),
]

# TV series awards: id, Wikidata item, group. The ids are not those of any film award (the apps look an id up in
# both lists). Left out while Wikidata links (almost) no series to them (November 2026): Séries Mania (three
# statements, a person among them), Canneseries (two, both people), the BAFTA TV mini-series award and the
# International Emmy for comedy (no series).
SERIES_AWARDS = [
    ("emmy-drama-series", "Q989438", "emmys"),
    ("emmy-comedy-series", "Q2110156", "emmys"),
    ("emmy-limited-series", "Q20714679", "emmys"),
    ("golden-globe-tv-drama", "Q1255198", "golden-globes"),
    ("golden-globe-tv-comedy", "Q596294", "golden-globes"),
    ("golden-globe-tv-limited", "Q265435", "golden-globes"),
    ("bafta-tv-drama-series", "Q2925698", "bafta-tv"),
    ("bafta-tv-international", "Q16985329", "bafta-tv"),
    ("international-emmy-drama", "Q16146879", "international-emmys"),
    ("peabody", "Q838121", "peabody"),
]

# (award id, English title, year of the award): must be found as a win, or the item is not the award we think.
KNOWN = [
    ("palme-dor", "Pulp Fiction", 1994),
    ("palme-dor", "Anatomy of a Fall", 2023),
    ("cannes-grand-prix", "The Zone of Interest", 2023),
    ("oscar-best-picture", "Parasite", 2020),
    ("cesar-best-film", "Anatomy of a Fall", 2024),
]
KNOWN_SERIES = [
    ("emmy-drama-series", "Breaking Bad", 2013),
    ("emmy-drama-series", "The Sopranos", 2004),
]

# Names clearer than Wikidata's label, shown without the festival's name next to them.
NAME_OVERRIDES = {
    ("cannes-jury-prize", "en"): "Cannes Jury Prize",
    ("cannes-jury-prize", "es"): "Premio del Jurado de Cannes",
    ("cannes-jury-prize", "pt"): "Prêmio do Júri de Cannes",
    ("cannes-grand-prix", "pt"): "Grande Prêmio de Cannes",
    ("cannes-grand-prix", "es"): "Gran Premio de Cannes",
    ("silver-bear-grand-jury", "pt"): "Urso de Prata, Grande Prêmio do Júri",
}

# Languages of the film titles kept for matching (playlists name films in their own language) and of the award
# names shown in the apps.
TITLE_LANGUAGES = ["en", "fr", "es", "pt", "pt-br", "de", "it", "nl", "tr", "pl", "ar", "hi", "zh", "zh-hans", "zh-cn"]
NAME_LANGUAGES = {"en": ["en"], "fr": ["fr"], "es": ["es"], "pt": ["pt-br", "pt"], "zh-Hans": ["zh-hans", "zh-cn", "zh"],
                  "ar": ["ar"], "hi": ["hi"]}


CACHE: pathlib.Path | None = None
PAUSE = 2.0
_last_request = 0.0


def sparql(query: str) -> list:
    """POSTs a query, at most one every PAUSE seconds; answers are kept in CACHE (when set) so a run can resume."""
    global _last_request
    cached = CACHE / (hashlib.sha1(query.encode()).hexdigest() + ".json") if CACHE else None
    if cached and cached.exists():
        return json.loads(cached.read_text())
    body = urllib.parse.urlencode({"query": query, "format": "json"}).encode()
    for attempt in range(8):
        time.sleep(max(0.0, _last_request + PAUSE - time.time()))
        _last_request = time.time()
        request = urllib.request.Request(ENDPOINT, data=body, headers={
            "User-Agent": USER_AGENT, "Accept": "application/sparql-results+json",
            "Content-Type": "application/x-www-form-urlencoded"})
        try:
            with urllib.request.urlopen(request, timeout=180) as response:
                rows = json.load(response)["results"]["bindings"]
            if cached:
                cached.write_text(json.dumps(rows))
            return rows
        except Exception as error:  # rate limits and timeouts: wait and try again
            print(f"  retry {attempt + 1}: {error}", file=sys.stderr)
            time.sleep(70)
    raise SystemExit("Wikidata did not answer")


def qid(binding: dict, name: str) -> str:
    return binding[name]["value"].rsplit("/", 1)[-1]


def year_of(binding: dict, name: str):
    value = binding.get(name, {}).get("value")
    return int(value[:4]) if value and value[:4].isdigit() else None


def films_of(award_qid: str) -> list:
    return sparql(f"""
    SELECT ?film ?kind ?when ?released ?tmdb WHERE {{
      {{ ?film p:P166 ?statement . ?statement ps:P166 wd:{award_qid} . BIND("won" AS ?kind) }}
      UNION
      {{ ?film p:P1411 ?statement . ?statement ps:P1411 wd:{award_qid} . BIND("nominated" AS ?kind) }}
      ?film wdt:P31/wdt:P279* wd:Q11424 .
      OPTIONAL {{ ?statement pq:P585 ?when }}
      OPTIONAL {{ ?film wdt:P577 ?released }}
      OPTIONAL {{ ?film wdt:P4947 ?tmdb }}
    }}""")


def series_of(award_qid: str) -> list:
    """TV series (miniseries, sitcoms, web and animated series included; not a film, a season or an episode). Their
    year is the first one Wikidata gives (start time, or first release); their TMDb id is the TV one."""
    return sparql(f"""
    SELECT ?film ?kind ?when ?released ?tmdb WHERE {{
      {{ ?film p:P166 ?statement . ?statement ps:P166 wd:{award_qid} . BIND("won" AS ?kind) }}
      UNION
      {{ ?film p:P1411 ?statement . ?statement ps:P1411 wd:{award_qid} . BIND("nominated" AS ?kind) }}
      # Up from each title's types (the other way round, through every subclass of film, times out).
      FILTER EXISTS {{ ?film wdt:P31 ?type . ?type wdt:P279* wd:Q5398426 . hint:Prior hint:gearing "forward" . }}
      FILTER NOT EXISTS {{ ?film wdt:P31 ?filmType . ?filmType wdt:P279* wd:Q11424 . hint:Prior hint:gearing "forward" . }}
      OPTIONAL {{ ?statement pq:P585 ?when }}
      OPTIONAL {{ {{ ?film wdt:P580 ?released }} UNION {{ ?film wdt:P577 ?released }} }}
      OPTIONAL {{ ?film wdt:P4983 ?tmdb }}
    }}""")


def labels(items: list, languages: list, with_original: bool) -> dict:
    found = {}
    filter_languages = ", ".join(f'"{language}"' for language in languages)
    for start in range(0, len(items), 400):
        values = " ".join(f"wd:{item}" for item in items[start:start + 400])
        original = "UNION { ?item wdt:P1476 ?label . BIND(\"original\" AS ?lang) }" if with_original else ""
        rows = sparql(f"""
        SELECT ?item ?label ?lang WHERE {{
          VALUES ?item {{ {values} }}
          {{ ?item rdfs:label ?label . BIND(LANG(?label) AS ?lang) FILTER(?lang IN ({filter_languages})) }}
          {original}
        }}""")
        for row in rows:
            found.setdefault(qid(row, "item"), {}).setdefault(row["lang"]["value"], []).append(row["label"]["value"])
    return found


def collect(awards: list, query) -> dict:
    """Titles by Wikidata item: their years, TMDb id and (award, year, won) entries."""
    found: dict = {}
    for award_id, award_qid, _ in awards:
        rows = query(award_qid)
        print(f"{award_id}: {len(rows)} rows", file=sys.stderr)
        for row in rows:
            title = found.setdefault(qid(row, "film"), {"released": set(), "tmdb": None, "awards": set()})
            released = year_of(row, "released")
            if released:
                title["released"].add(released)
            if "tmdb" in row and row["tmdb"]["value"].isdigit():
                title["tmdb"] = int(row["tmdb"]["value"])
            won = row["kind"]["value"] == "won"
            when = year_of(row, "when")
            title["awards"].add((award_id, when, won))
    # A win also listed as a nomination for the same year is only a win.
    for title in found.values():
        wins = {(award, when) for award, when, won in title["awards"] if won}
        title["awards"] = {(award, when, won) for award, when, won in title["awards"] if won or (award, when) not in wins}
    return found


def award_names(awards: list) -> list:
    names = labels([award_qid for _, award_qid, _ in awards], [language for options in NAME_LANGUAGES.values() for language in options], with_original=False)
    result = []
    for award_id, award_qid, group in awards:
        by_language = names.get(award_qid, {})
        localized = {}
        for app_language, options in NAME_LANGUAGES.items():
            value = NAME_OVERRIDES.get((award_id, app_language)) or next(
                (by_language[option][0] for option in options if option in by_language), None)
            if value:
                # Labels of Wikipedia list pages ("Anexo:…") and sentence-case labels ("prix du jury…").
                value = value.removeprefix("Anexo:").strip()
                localized[app_language] = value[:1].upper() + value[1:]
        if "en" not in localized:
            raise SystemExit(f"no English name for {award_id}")
        result.append({"id": award_id, "group": group, "names": localized})
    return result


def entries(found: dict) -> list:
    titles = labels(sorted(found), TITLE_LANGUAGES, with_original=True)
    result = []
    for item, title in found.items():
        item_titles = sorted({value for values in titles.get(item, {}).values() for value in values})
        if not item_titles:
            continue
        entry = {"titles": item_titles}
        if title["released"]:
            entry["year"] = min(title["released"])
        if title["tmdb"]:
            entry["tmdb"] = title["tmdb"]
        entry["awards"] = sorted([[award, when or entry.get("year") or 0, 1 if won else 0] for award, when, won in title["awards"]],
                                 key=lambda item: (-item[1], item[0]))
        result.append(entry)
    result.sort(key=lambda entry: (entry["titles"][0].casefold(), entry.get("year", 0)))
    return result


def check_known(known: list, found: list, what: str) -> None:
    for award_id, title, year in known:
        if not any(title in entry["titles"] and [award_id, year, 1] in entry["awards"] for entry in found):
            raise SystemExit(f"check failed: the {what} {title} should have won {award_id} in {year}")


def main() -> None:
    global CACHE, PAUSE
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--cache", type=pathlib.Path, help="keep Wikidata's answers in this folder (to resume a run)")
    parser.add_argument("--pause", type=float, default=PAUSE, help="seconds between two queries (Wikidata may ask for 60)")
    parser.add_argument("--output", type=pathlib.Path, action="append",
                        help="write the file here instead of the two copies in the repository (repeatable)")
    arguments = parser.parse_args()
    CACHE, PAUSE = arguments.cache, arguments.pause
    if CACHE:
        CACHE.mkdir(parents=True, exist_ok=True)
    if {award_id for award_id, _, _ in AWARDS} & {award_id for award_id, _, _ in SERIES_AWARDS}:
        raise SystemExit("a series award has the id of a film award")

    films_out = entries(collect(AWARDS, films_of))
    series_out = entries(collect(SERIES_AWARDS, series_of))
    check_known(KNOWN, films_out, "film")
    check_known(KNOWN_SERIES, series_out, "series")

    # `awards` and `films` keep the shape the apps already released read; series come after, in keys they ignore.
    document = {
        "source": "Wikidata (CC0 1.0), generated by tools/awards/generate_awards.py",
        "generated": datetime.date.today().isoformat(),
        "awards": award_names(AWARDS),
        "films": films_out,
        "seriesAwards": award_names(SERIES_AWARDS),
        "series": series_out,
    }
    text = json.dumps(document, ensure_ascii=False, separators=(",", ":"), sort_keys=False) + "\n"
    for output in arguments.output or OUTPUTS:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(text, encoding="utf-8")
    print(f"{len(films_out)} films, {len(series_out)} series, {len(document['awards'])} + {len(document['seriesAwards'])} awards, "
          f"{len(text.encode()) // 1024} KB", file=sys.stderr)


if __name__ == "__main__":
    main()
