# Données des prix

[English](README.md)

`awards.json` liste les films qui ont gagné ou concouru pour de grands prix du cinéma (Cannes, Venise, Berlin,
les Oscars, les César, les BAFTA, les Golden Globes, les Goya, les Prix du cinéma européen, Saint-Sébastien et
Locarno), avec leurs titres dans plusieurs langues, leur année et leur identifiant TMDb quand il est connu.

- **Source** : [Wikidata](https://www.wikidata.org), sous licence CC0 1.0 (domaine public).
- **Usage** : les apps lisent le fichier sur l'appareil et le rapprochent des films de la playlist par titre et
  année. Rien n'est envoyé nulle part.
- **Copies** : `App/Resources/awards.json` (Apple) et `android/app/src/main/assets/awards.json` (Android),
  écrites ensemble ; `.github/scripts/check_awards.py` (CI) vérifie qu'elles sont identiques et bien formées.

## Mise à jour

```sh
python3 -I tools/awards/generate_awards.py --cache /tmp/awards-cache --pause 65
```

`--pause` espace les requêtes (Wikidata peut limiter un réseau à une requête par minute) ; `--cache` garde les
réponses pour qu'une exécution interrompue reprenne. Le script s'arrête si un lauréat connu manque (Pulp
Fiction, Palme d'or 1994…), ce qui voudrait dire qu'un prix pointe vers le mauvais élément de Wikidata.
