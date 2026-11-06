# Langue des descriptions

La langue de la description (résumé) donnée par le fournisseur et l'étiquette à la fin du nom d'un titre, pour les
langues masquées (docs/ANDROID.fr.md, « Recherche »), lues par les deux cœurs : `PlotLanguageTest` d'Android et
`PlotLanguageTests` d'Apple.

- `table.txt` : des lignes d'un code de langue (`es`, `pt`, `fr`, `en`, `it`, `de`, `nl`, `tr`, `pl`, `ar`, `hi`,
  `zh`), puis `tags` et les étiquettes de catégorie qui la désignent (`LAT` est l'espagnol, `VF` le français ;
  `VOSTFR`, des sous-titres français, n'en est pas une), ou `words` et ses petits mots les plus courants, séparés par
  `|` ; plusieurs lignes pour une langue, pour que les lignes restent courtes. Un mot n'appartient qu'à une langue (un
  mot de deux langues n'en désigne aucune), est en minuscules et compte au plus sept lettres latines. L'arabe, le
  hindi et le chinois se reconnaissent à leur écriture. Chaque cœur contient le même texte (`PlotLanguage.TABLE`,
  `PlotLanguage.table`), et ses tests vérifient que c'est ce fichier.
- `cases.json` : des descriptions et la langue que les deux cœurs leur donnent (aucune si elles sont courtes ou pas
  claires), des noms et l'étiquette à leur fin, des étiquettes masquées et les langues qu'elles désignent.

Un mot ou une étiquette ajouté à une langue va dans `table.txt` et dans les copies des deux cœurs, avec un cas dans
`cases.json`.
