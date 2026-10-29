# Catalogue de démonstration des captures des stores

[English](README.md)

Les captures App Store et Google Play montrent ce catalogue plutôt qu'une app vide. Il ne sert qu'aux tests de
captures : la playlist de démonstration de l'app ne change pas, et Ondo ne fournit toujours aucun contenu.

- `catalogue.json` : films, une série, des chaînes fictives avec leur guide, et une bibliothèque en usage (en cours,
  Ma liste, chaînes favorites).
- `art/` : affiches, images de fond et d'épisodes de films libres (licences Creative Commons), réencodées sans
  métadonnées ; logos des chaînes dessinés par le script. Crédits : [CREDITS.fr.md](CREDITS.fr.md).
- `tools/` : `titles.json` (films, séries, source et licence de chaque image), `channels.json` (chaînes et guide) et
  `build_showcase.py`, qui télécharge les images et écrit tout ce qui précède :

```sh
python3 -I fixtures/showcase/tools/build_showcase.py --cache /chemin/du/cache
```

Seules les images sous une licence qui permet la réutilisation (CC BY, CC BY-SA, CC0, domaine public partout) peuvent
être ajoutées, avec leur auteur et leur source dans `titles.json`.
