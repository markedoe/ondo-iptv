# Données des prix

[English](README.md)

`awards.json` liste les films qui ont gagné ou concouru pour de grands prix du cinéma (Cannes, Venise, Berlin,
les Oscars, les César, les BAFTA, les Golden Globes, les Goya, les Prix du cinéma européen, Saint-Sébastien et
Locarno), et les séries qui ont gagné ou été nommées pour de grands prix de séries, avec leurs titres dans
plusieurs langues, leur année (pour une série, celle de sa première diffusion) et leur identifiant TMDb quand il est
connu (pour une série, son identifiant TMDb TV).

Prix des séries : les Primetime Emmy (série dramatique, comique, mini-série), les Golden Globes (série dramatique,
musicale ou comique, mini-série ou anthologie), les BAFTA TV (série dramatique, programme international),
l'International Emmy de la série dramatique et les prix Peabody. Laissés de côté tant que Wikidata n'y relie presque
aucune série : Séries Mania, Canneseries, le BAFTA TV de la mini-série et l'International Emmy de la comédie.
Wikidata ne liste pas tous les lauréats et nommés de chaque année ; beaucoup de victoires n'ont pas d'année propre et
prennent la première année de la série.

- **Source** : [Wikidata](https://www.wikidata.org), sous licence CC0 1.0 (domaine public).
- **Format** : `awards` et `films` pour les films ; `seriesAwards` et `series` pour les séries, de même forme. Les
  apps publiées avant les prix des séries ne lisent que `awards` et `films` : ceux-ci gardent leur forme, et aucun
  prix de série n'y figure jamais (`.github/scripts/check_awards.py` le vérifie ; les tests des cœurs lisent le
  fichier embarqué avec le décodeur de ces apps et obtiennent les mêmes films).
- **Usage** : les apps lisent le fichier sur l'appareil et le rapprochent des films de la playlist par titre et
  année, et de ses séries par titre et première année, un film seulement parmi les films et une série seulement
  parmi les séries. Rien sur le spectateur n'est envoyé nulle part.
- **Mise à jour automatique** : le workflow *Awards data* (`.github/workflows/awards.yml`) lance le script une
  fois par mois et publie `awards.json` sur la branche `awards-data`, sauf si le nouveau fichier liste 10 % de
  films, ou 10 % de séries, de moins que celui publié. Les apps le téléchargent une fois par semaine (Réglages,
  *Mettre à jour les prix automatiquement*, activé par défaut) et le gardent s'il est plus récent que leur copie
  embarquée et liste au moins 1 000 films.
- **Copies** : `App/Resources/awards.json` (Apple) et `android/app/src/main/assets/awards.json` (Android),
  écrites ensemble ; `.github/scripts/check_awards.py` (CI) vérifie qu'elles sont identiques et bien formées.

## Mise à jour

```sh
python3 -I tools/awards/generate_awards.py --cache /tmp/awards-cache --pause 65
```

`--output FICHIER` écrit le fichier à cet endroit au lieu des deux copies. `--pause` espace les requêtes (Wikidata peut limiter un réseau à une requête par minute) ; `--cache` garde les
réponses pour qu'une exécution interrompue reprenne. Le script s'arrête si un lauréat connu manque (Pulp
Fiction, Palme d'or 1994 ; Breaking Bad, Emmy de la série dramatique 2013…), ce qui voudrait dire qu'un prix pointe
vers le mauvais élément de Wikidata.
