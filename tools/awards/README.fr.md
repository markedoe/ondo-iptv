# Données des prix

[English](README.md)

`awards.json` liste les films qui ont gagné ou concouru pour de grands prix du cinéma (Cannes, Venise, Berlin,
les Oscars, les César, les BAFTA, les Golden Globes, les Goya, les Prix du cinéma européen, Saint-Sébastien et
Locarno), avec leurs titres dans plusieurs langues, leur année et leur identifiant TMDb quand il est connu.

- **Source** : [Wikidata](https://www.wikidata.org), sous licence CC0 1.0 (domaine public).
- **Usage** : les apps lisent le fichier sur l'appareil et le rapprochent des films de la playlist par titre et
  année. Rien sur le spectateur n'est envoyé nulle part.
- **Mise à jour automatique** : le workflow *Awards data* (`.github/workflows/awards.yml`) lance le script une
  fois par mois et publie `awards.json` sur la branche `awards-data`, sauf si le nouveau fichier liste 10 % de
  films de moins que celui publié. Les apps le téléchargent une fois par semaine (Réglages, *Mettre à jour les
  prix automatiquement*, activé par défaut) et le gardent s'il est plus récent que leur copie embarquée et liste au
  moins 1 000 films.
- **Copies** : `App/Resources/awards.json` (Apple) et `android/app/src/main/assets/awards.json` (Android),
  écrites ensemble ; `.github/scripts/check_awards.py` (CI) vérifie qu'elles sont identiques et bien formées.

## Mise à jour

```sh
python3 -I tools/awards/generate_awards.py --cache /tmp/awards-cache --pause 65
```

`--output FICHIER` écrit le fichier à cet endroit au lieu des deux copies. `--pause` espace les requêtes (Wikidata peut limiter un réseau à une requête par minute) ; `--cache` garde les
réponses pour qu'une exécution interrompue reprenne. Le script s'arrête si un lauréat connu manque (Pulp
Fiction, Palme d'or 1994…), ce qui voudrait dire qu'un prix pointe vers le mauvais élément de Wikidata.
