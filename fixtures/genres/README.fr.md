# Genres

Les genres dans toutes les langues comme un seul genre (docs/SYNC.fr.md, « Présélections »), lus par les deux cœurs :
`GenresTest` d’Android et `GenresTests` d’Apple.

- `table.txt` : une ligne par genre de TMDb, son nom anglais, puis les noms qui le désignent (anglais, français,
  espagnol, portugais, allemand, italien, néerlandais, chinois, arabe, hindi), séparés par `|`. Chaque cœur en garde le
  même texte (`Genres.TABLE`, `Genres.table`), et ses tests vérifient que c’est ce fichier. La clé d’un genre est son nom
  anglais normalisé (`adventure`, `science fiction`).
- `cases.json` : des champs de genres et les clés que les deux cœurs en donnent, les tags d’une version antérieure et
  leur lecture, et des références HTML décodées.

Un nom ajouté à un genre va dans `table.txt` et dans les copies des deux cœurs, avec un cas dans `cases.json`.
