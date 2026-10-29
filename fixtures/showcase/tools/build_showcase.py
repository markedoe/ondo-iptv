#!/usr/bin/env python3
"""Builds the demo catalogue of the store screenshots (fixtures/showcase).

Inputs, next to this script:
  titles.json    films and series whose pictures may be shown, with each picture's source and licence
  channels.json  fictional channels, their categories and a bilingual guide

Outputs, in fixtures/showcase:
  catalogue.json        what the screenshot tests read (Android: ShowcaseCatalogue)
  art/                  posters, backdrops, stills (JPEG, no metadata) and channel logos (PNG, drawn here)
  CREDITS.md / .fr.md   each picture's title, author, licence and source

Pictures are downloaded once into a cache folder outside the repository (--cache), re-encoded without any metadata
and resized: posters 600 px high (2:3), backdrops and stills 1280 px wide (16:9).

Usage: python3 -I fixtures/showcase/tools/build_showcase.py --cache /tmp/showcase-cache
"""

import argparse
import hashlib
import io
import json
import pathlib
import textwrap
import time
import urllib.error
import urllib.request

from PIL import Image, ImageDraw, ImageFont

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent
ART = ROOT / "art"
USER_AGENT = "OndoShowcaseBuilder/1.0 (store screenshots of an open-source player)"
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"

POSTER = (400, 600)
WIDE = (1280, 720)


def thumbnails(url: str) -> list[str]:
    """Wikimedia Commons asks for its standard thumbnail sizes rather than originals: 1280 px, then 960 px wide."""
    prefix = "https://upload.wikimedia.org/wikipedia/commons/"
    if not url.startswith(prefix):
        return [url]
    path = url[len(prefix):]
    name = path.rsplit("/", 1)[1]
    return [f"{prefix}thumb/{path}/{width}px-{name}" for width in (1280, 960)] + [url]


def fetch(url: str, cache: pathlib.Path, attempts: int = 0) -> bytes:
    cache.mkdir(parents=True, exist_ok=True)
    target = cache / hashlib.sha256(url.encode()).hexdigest()
    if not target.exists():
        error = None
        for candidate in thumbnails(url):
            request = urllib.request.Request(candidate, headers={"User-Agent": USER_AGENT})
            try:
                with urllib.request.urlopen(request, timeout=60) as response:
                    target.write_bytes(response.read())
                print(f"  downloaded {candidate}", flush=True)
                time.sleep(3)
                break
            except urllib.error.HTTPError as failure:
                error = failure
                if failure.code == 429 and attempts < 6:
                    # Rate limited: wait and ask again for the same size.
                    attempts += 1
                    time.sleep(30)
                    return fetch(url, cache, attempts)
                time.sleep(2)
        else:
            raise RuntimeError(f"{url}: {error}")
    return target.read_bytes()


def cover(image: Image.Image, size: tuple[int, int], focus_y: float = 0.5) -> Image.Image:
    """Scales and crops [image] to fill [size]; [focus_y] chooses which part of a tall picture stays."""
    width, height = size
    scale = max(width / image.width, height / image.height)
    resized = image.resize((round(image.width * scale), round(image.height * scale)), Image.LANCZOS)
    left = (resized.width - width) // 2
    top = round((resized.height - height) * focus_y)
    return resized.crop((left, top, left + width, top + height))


def save_jpeg(image: Image.Image, name: str) -> None:
    # A new image from the pixels only: no EXIF, ICC or XMP block survives.
    clean = Image.new("RGB", image.size)
    clean.paste(image.convert("RGB"))
    clean.save(ART / name, "JPEG", quality=82, optimize=True, progressive=True)


def picture(entry: dict | None, cache: pathlib.Path) -> Image.Image | None:
    if not entry:
        return None
    return Image.open(io.BytesIO(fetch(entry["url"], cache)))


def build_title(title: dict, cache: pathlib.Path, credits: list) -> dict:
    slug = title["id"]
    poster_source = title.get("poster")
    backdrop_source = title.get("backdrop")
    poster = picture(poster_source, cache)
    backdrop = picture(backdrop_source, cache)
    out = {key: value for key, value in title.items() if key not in ("poster", "backdrop", "seasons")}
    if backdrop is not None:
        save_jpeg(cover(backdrop, WIDE, backdrop_source.get("focus_y", 0.5)), f"{slug}-backdrop.jpg")
        out["backdrop"] = f"{slug}-backdrop.jpg"
        credits.append((title["title"], "backdrop", backdrop_source))
    if poster is not None:
        save_jpeg(cover(poster, POSTER, poster_source.get("focus_y", 0.0)), f"{slug}-poster.jpg")
        out["poster"] = f"{slug}-poster.jpg"
        credits.append((title["title"], "poster", poster_source))
    elif backdrop is not None:
        # No poster: a portrait cut from the backdrop, as many providers send.
        save_jpeg(cover(backdrop, POSTER, 0.5), f"{slug}-poster.jpg")
        out["poster"] = f"{slug}-poster.jpg"
    if "seasons" in title:
        seasons = []
        for season in title["seasons"]:
            episodes = []
            for number, episode in enumerate(season["episodes"], start=1):
                item = {key: value for key, value in episode.items() if key != "still"}
                still = picture(episode.get("still"), cache)
                if still is not None:
                    name = f"{slug}-s{season['number']}e{number}.jpg"
                    save_jpeg(cover(still, (640, 360)), name)
                    item["still"] = name
                    credits.append((f"{title['title']} – {episode['title']}", "still", episode["still"]))
                episodes.append(item)
            seasons.append({"number": season["number"], "episodes": episodes})
        out["seasons"] = seasons
    return out


def draw_logo(channel: dict) -> str:
    """A channel's logo: its name in white on its colour, drawn here (fictional channels)."""
    size = (480, 270)
    image = Image.new("RGB", size, channel["color"])
    draw = ImageDraw.Draw(image)
    lines = textwrap.wrap(channel["name"].upper(), width=12)
    font_size = 64 if len(lines) == 1 else 52
    font = ImageFont.truetype(FONT, font_size)
    heights = [draw.textbbox((0, 0), line, font=font)[3] for line in lines]
    total = sum(heights) + 10 * (len(lines) - 1)
    y = (size[1] - total) / 2
    for line, height in zip(lines, heights):
        width = draw.textlength(line, font=font)
        draw.text(((size[0] - width) / 2, y), line, font=font, fill="white")
        y += height + 10
    name = f"logo-{channel['id']}.png"
    image.save(ART / name, "PNG", optimize=True)
    return name


def credits_markdown(credits: list, french: bool) -> str:
    if french:
        head = (
            "# Crédits des images de démonstration\n\n"
            "Les captures des fiches App Store et Google Play montrent un catalogue de démonstration "
            "(`fixtures/showcase`). Ondo ne fournit aucun contenu : ces films sont libres ou dans le domaine public, "
            "et leurs images sont utilisées selon leur licence, recadrées et réduites. "
            "Les chaînes et leurs logos sont fictifs, dessinés pour ces captures.\n\n"
            "| Titre | Image | Auteur | Licence | Source |\n|---|---|---|---|---|\n"
        )
        kinds = {"poster": "affiche", "backdrop": "image de fond", "still": "image d'épisode"}
    else:
        head = (
            "# Demo picture credits\n\n"
            "The App Store and Google Play screenshots show a demo catalogue (`fixtures/showcase`). Ondo provides no "
            "content: these films are open or in the public domain, and their pictures are used under their licence, "
            "cropped and resized. The channels and their logos are fictional, drawn for these screenshots.\n\n"
            "| Title | Picture | Author | Licence | Source |\n|---|---|---|---|---|\n"
        )
        kinds = {"poster": "poster", "backdrop": "backdrop", "still": "episode still"}
    rows = []
    for title, kind, source in credits:
        author = source.get("author", "").replace("|", "/").strip() or "—"
        rows.append(f"| {title} | {kinds[kind]} | {author} | {source['license']} | [{source.get('file', 'source')}]({source['page']}) |")
    return head + "\n".join(rows) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache", required=True, type=pathlib.Path)
    args = parser.parse_args()
    titles = json.loads((HERE / "titles.json").read_text())
    channels = json.loads((HERE / "channels.json").read_text())
    ART.mkdir(exist_ok=True)
    for old in ART.iterdir():
        old.unlink()
    credits: list = []
    movies = [build_title(movie, args.cache, credits) for movie in titles["movies"]]
    series = [build_title(show, args.cache, credits) for show in titles["series"]]
    out_channels = []
    for number, channel in enumerate(channels["channels"], start=1):
        programmes = channels["kinds"][channel["kind"]]
        # Each channel starts its day at another programme: the guide does not read the same on every row.
        shifted = programmes[number % len(programmes):] + programmes[: number % len(programmes)]
        out_channels.append({
            "id": channel["id"],
            "number": number,
            "name": channel["name"],
            "category": channel["category"],
            "logo": draw_logo(channel),
            "programmes": [{"fr": fr, "en": en} for fr, en in shifted],
        })
    categories = titles["categories"] + [{"id": c["id"], "type": "live", "name": c["name"]} for c in channels["categories"]]
    catalogue = {
        "playlist_name": titles["playlist_name"],
        "updated_minutes_ago": titles.get("updated_minutes_ago", 12),
        "programme_minutes": channels["programme_minutes"],
        "categories": categories,
        "movies": movies,
        "series": series,
        "channels": out_channels,
        "library": titles["library"],
        "screens": titles["screens"],
    }
    (ROOT / "catalogue.json").write_text(json.dumps(catalogue, ensure_ascii=False, indent=1) + "\n")
    (ROOT / "CREDITS.md").write_text(credits_markdown(credits, french=False))
    (ROOT / "CREDITS.fr.md").write_text(credits_markdown(credits, french=True))
    total = sum(f.stat().st_size for f in ART.iterdir())
    print(f"{len(movies)} movies, {len(series)} series, {len(out_channels)} channels, {len(list(ART.iterdir()))} pictures, {total / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
