# Genres

Genres in any language as one genre (docs/SYNC.md, "Selections"), read by both cores: Android's `GenresTest` and
Apple's `GenresTests`.

- `table.txt`: one line per TMDb genre, its English name, then the names that mean it (English, French, Spanish,
  Portuguese, German, Italian, Dutch, Chinese, Arabic, Hindi), separated by `|`. Each core holds the same text
  (`Genres.TABLE`, `Genres.table`), and its tests check that it is this file. A genre's key is its English name
  normalised (`adventure`, `science fiction`).
- `cases.json`: genre fields and the keys both cores give them, the tags of an earlier version and how they are read,
  and HTML references decoded.

A name added to a genre goes into `table.txt` and both cores' copies, with a case in `cases.json`.
