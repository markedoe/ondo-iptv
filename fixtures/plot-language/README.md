# Plot language

The language of a provider's description (plot) and the tag at the end of a title's name, for hidden languages
(docs/ANDROID.md, "Search"), read by both cores: Android's `PlotLanguageTest` and Apple's `PlotLanguageTests`.

- `table.txt`: lines of a language code (`es`, `pt`, `fr`, `en`, `it`, `de`, `nl`, `tr`, `pl`, `ar`, `hi`, `zh`), then
  `tags` and the category tags that stand for it (`LAT` is Spanish, `VF` French; `VOSTFR`, French subtitles, is
  none), or `words` and its most common little words, separated by `|`; several lines for one language, so that lines
  stay short. A word belongs to one language only (a word of two tells neither), is lower case and has at most seven
  Latin letters. Arabic, Hindi and Chinese are told by their script. Each core holds the same text
  (`PlotLanguage.TABLE`, `PlotLanguage.table`), and its tests check that it is this file.
- `cases.json`: descriptions and the language both cores give them (none when short or unclear), names and the tag
  at their end, hidden tags with the tags of the catalogue (`catalogue`) and the languages whose descriptions they
  hide: none whose tag in the catalogue is kept (PT kept, BR hidden: no Portuguese), never English.

A word or a tag added to a language goes into `table.txt` and both cores' copies, with a case in `cases.json`.
