# Android et Apple se synchronisent entre eux

Instantanés produits par les deux cœurs, chacun lu et fusionné par l’autre : la preuve qu’un appareil Android et un
iPhone, un iPad ou un Mac synchronisent entre eux les profils, les données de chaque profil, l’ordre des rangées, les
listes partagées, les catégories masquées et les suppressions, au-delà des exemples écrits à la main de `fixtures/sync`.

Pour chaque scénario (`profiles`, `shared-lists`, `deletions`, `hidden` : les catégories, langues et styles masqués de
chaque profil, masqués et réaffichés de part et d’autre), les deux appareils partent des mêmes données (ce qu’ils
avaient après une session précédente), écrites de la même façon dans les deux cœurs, chaque instant étant donné par une
`SyncClock` à une heure murale connue. Puis chacun change des choses de son côté, et ils ont une session, l’iPhone étant
le client :

| Fichier               | Écrit par | Ce que c’est                                                                              |
|-----------------------|-----------|-------------------------------------------------------------------------------------------|
| `android.json`        | Kotlin    | L’instantané de l’appareil Android, tel qu’il l’envoie (`SyncInteropTest`, android/core). |
| `apple.json`          | Swift     | L’instantané de l’iPhone, tel qu’il l’envoie (`SyncInteropTests`, Packages/IPTVCore).      |
| `answer.json`         | Kotlin    | La réponse de l’appareil Android : son instantané fusionné avec celui de l’iPhone.        |
| `kept.json`           | Swift     | Ce que l’iPhone garde : son instantané fusionné avec la réponse.                          |
| `android-digests.json`| Kotlin    | Les résumés Kotlin de `android.json` et `answer.json`, et le résumé canonique.            |
| `apple-digests.json`  | Swift     | Les résumés Swift de `apple.json` et `kept.json`, et le résumé canonique.                 |

Deux autres scénarios placent un téléviseur Android de la version précédente (qui ne partage pas de listes) entre
l’iPhone et un iPad : `old-relay-share` (l’iPhone partage une liste) et `old-relay-take-back` (l’iPhone reprend une liste
partagée). Là, `android.json` et `answer.json` sont ce que ce téléviseur garde et répond (sans foyer ni `placedAt`), et
`kept.json` est ce que l’iPad, qui ne rencontre que le téléviseur, garde de la réponse : la liste n’est jamais perdue.
Partagée, elle reste dans les listes du profil sur l’iPad jusqu’à ce que les deux se rencontrent ; reprise, elle est au
profil sur les deux.

Le test de chaque cœur, en CI (android.yml, ci.yml ; les deux s’exécutent quand ce dossier change) :

- refait l’instantané de son propre appareil et sa propre fusion, et les retrouve tels qu’enregistrés : un changement du
  format, de la fusion ou du résumé de ce cœur apparaît ici, et les fichiers doivent être réécrits (ci-dessous) ;
- lit les fichiers que l’autre cœur a écrits, fait lui-même la moitié de la session de l’autre appareil, et retrouve ce
  que l’autre cœur a obtenu (les instantanés décodés sont comparés, pas les octets : chaque cœur écrit le JSON à sa
  façon) ;
- vérifie que les deux appareils finissent avec les mêmes données, dans un ordre de fusion ou dans l’autre, et avec le
  même résumé canonique : le SHA-256 du JSON de l’instantané aux membres triés, sans les membres vides ni l’expéditeur,
  les nombres entiers sans virgule. Le résumé propre d’un appareil (docs/SYNC.fr.md, « Transport ») n’est comparé qu’à
  ses propres résumés précédents et à ceux qu’un pair a envoyés : les deux cœurs n’ont pas à le calculer pareil ; le
  résumé canonique montre que leurs données sont les mêmes.

Des fichiers enregistrés plutôt que des fichiers passés d’un job de CI à l’autre : un job n’attend jamais la sortie de
l’autre et ne la saute jamais en silence, et un changement d’un côté fait échouer le test de l’autre côté tant que les
fichiers ne sont pas réécrits.

Pour les réécrire après un changement du format ou de la fusion : lancer le test Kotlin avec `ONDO_WRITE_INTEROP=1`
(`android.json`, et `answer.json` dès que `apple.json` existe), puis les tests Swift avec (`apple.json`, et `kept.json`
et les résumés dès que `answer.json` existe), puis chacun une fois de plus, puis les deux sans.
