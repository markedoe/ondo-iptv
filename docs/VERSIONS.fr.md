# Versions des films

*[English version](VERSIONS.md)*

Les playlists listent souvent le même film plusieurs fois : une copie 4K Dolby Vision, une 4K, une HD, une VOSTFR…
Avec **Regrouper les versions** activé (Réglages, activé par défaut), les apps montrent chaque film une seule fois dans
leurs listes, et sa fiche affiche ses versions en badges à choisir. Désactivé, chaque flux apparaît comme la playlist le
liste, exactement comme avant.

Les règles sont dans les cœurs, avec les mêmes tests sur les deux plateformes : `FilmVersions` dans IPTVCore
(`Packages/IPTVCore/Sources/IPTVCore/Library/FilmVersions.swift`) et dans le cœur Kotlin
(`android/core/src/main/kotlin/com/markedoe/ondo/core/library/FilmVersions.kt`). Elles ne perdent ni ne cachent aucun
flux : chaque flux est dans exactement un groupe. Dans le doute, les flux restent séparés. Les deux cœurs lisent les
mêmes catalogues écrits à la main et leurs groupes attendus (`fixtures/film-versions/golden.json`).

## Quand deux flux sont un seul film

Seulement les films, et seulement dans une même playlist (les chaînes et les séries ne sont jamais regroupées).

- **Le titre** comparé est celui du fournisseur sans ses étiquettes (`CatalogueMatcher.displayTitle` : `|FR|`, `[FR]`,
  `FR -`, `STFR|`, `VFF`, `MULTI`, `4K`…), sans tenir compte de la casse ni des accents : « |FR| Le Transport 4K » et « Le
  Transport VOSTFR » sont tous deux « le transport ».
- **Règle A, l'identifiant TMDb du fournisseur.** Le champ `tmdb` (ou `tmdb_id`) de la liste Xtream ne compte que s'il
  est un nombre entier supérieur à zéro (`0`, vide, `true`, `12.5` ou du texte ne sont pas un identifiant). L'identifiant
  d'un fournisseur peut être faux (une suite associée au premier film, l'identifiant d'une saga sur chaque film) : deux
  flux du même identifiant ne sont regroupés que si tout concorde :
  - **la même année** quand les deux en ont une (un groupe n'a jamais deux années : « Dune » 1984 et « Dune » 2021
    restent séparés) ;
  - **les mêmes nombres** : un nombre dans un titre que l'autre n'a pas ou écrit autrement (2, IV, deux, two…) fait un
    autre film (« Toy Story » et « Toy Story 3 », « Episode IV » et « Episode V », « Fast and Furious 7 » et « 8 ») ;
  - puis **le même titre**, ou **les mêmes mots** sans les mots courts comme « the », « le » ou « de » (« the matrix » et
    « matrix ») ; ou les mots de l'un sont les premiers mots de l'autre (« le transport » et « le transport heritage »)
    **seulement avec la même année donnée des deux côtés** et aucun mot de suite parmi les autres (partie, chapitre,
    épisode, volume… dans les langues des apps) : « Dune: Part Two » n'est pas « Dune ».

  « Alien » et « Aliens » ne s'accordent pas, ni « Harry Potter and the Chamber of Secrets » et « …the Prisoner of
  Azkaban ». Un flux dont le titre ne s'accorde pas reste à part et est compté (`idTitleMismatches` ; parmi eux,
  `prefixWithoutYears` sont ceux gardés à part seulement faute d'année). **Deux identifiants différents ne sont jamais
  dans le même groupe**, quels que soient les titres.
- **Les noms qui ne disent rien du film** ne sont jamais regroupés : un nom fait seulement des étiquettes des fournisseurs
  (« 4K », « [FR] MULTI »), ou, sans identifiant, un simple nombre (« FR - 2021 »). Les mots qui sont aussi des étiquettes
  (« French Kiss », « Johnny English ») ne sont des étiquettes qu'en capitales (« FRENCH »).
- **Règle B, pas d'identifiant d'un côté ou des deux.** Exactement le même titre et la même année, présente des deux
  côtés : pas d'année, pas de groupe ; aucune année de tolérance (« Dune » 1984 et « Dune » 2021 sont deux films ;
  « Sandstorm » et « Sandstorm 2 » aussi). Un flux sans identifiant ne rejoint un groupe avec identifiant que si un seul
  identifiant a ce titre et cette année ; si deux en ont, il reste avec les flux sans identifiant (`ambiguousTitles`),
  et ne relie donc jamais deux identifiants.

## Ce que montrent les apps

- **Les listes** (rangées, grilles, recherche, pages d'une personne, d'un genre, d'une année ou d'une récompense,
  Accueil, Ma liste, Titres similaires) : chaque film une fois. Dans une catégorie, la première de ses versions dans
  cette catégorie le représente : un film est donc dans chaque catégorie où se trouve l'une de ses versions.
- **La fiche d'un film** : ses versions en pastilles (qu'atteint la télécommande sur une TV). Chaque pastille dit en
  toutes lettres, dans la langue de l'app, ce qui distingue sa version des autres (`FilmVersions.labels`, les mêmes
  règles et la même table dans les deux apps : `fixtures/film-versions/labels.json`). Les tags sont lus dans le nom du
  flux et dans celui de sa catégorie :
  - qualité : 4K (4K, UHD, 2160p), Full HD (FHD, 1080p), HD (HD, 720p), SD (SD, 480p, 576p) ;
  - image : Dolby Vision (DV, DoVi, Dolby Vision), HDR (HDR, HDR10, HDR10+), 3D (3D, SBS, HSBS), IMAX, HEVC (HEVC,
    H.265, x265) ;
  - langue : Multilingue (MULTI, DUAL), Français (France) (VFF, TRUEFRENCH), Français (Québec) (VFQ), Français
    (international) (VFI), Français (VF, FRENCH), Version originale (VO), Anglais, Espagnol, Allemand, Italien,
    Portugais, Arabe (le mot, ou un code en capitales à un bout du nom ou de la catégorie : « FR - … », « |EN| Movies ») ;
  - sous-titres : VO, sous-titres français (VOSTFR, STFR, SUBFR, ST FR, FR SUB), VO sous-titrée (VOST, VOSTA,
    SUBBED).

  Une pastille ne nomme que ce qui diffère entre les versions, dans cet ordre (« 4K Dolby Vision », « HD · VO,
  sous-titres français ») : un tag que toutes les versions ont est omis. Deux versions qui se liraient pareil ajoutent
  les mots de leur nom que les autres n'ont pas (« Extended Cut ») ; une version sans aucun tag, à côté de versions qui
  en ont, s'appelle « Standard » ; des pastilles encore identiques sont numérotées (« 4K 1 », « 4K 2 » ; « Version 1 »,
  « Version 2 » quand rien ne les distingue). Deux pastilles d'un même film ne se lisent jamais pareil. Un lecteur
  d'écran dit « Version : 4K Dolby Vision ». La version choisie est celle qui est lue ; Lecture, la progression et le
  badge du format sont les siens.
- **La version montrée d'abord** (et lue depuis une affiche) : celle en cours ; sinon la dernière choisie sur cet
  appareil ; sinon celle qui convient le mieux à l'écran (la 4K sur un écran 4K, le HDR ou le Dolby Vision seulement là
  où l'écran les affiche, selon l'écran d'Android et AVFoundation chez Apple) ; sinon la première.
- **Les données gardées restent par flux** : favoris, historique, progression et synchronisation ne changent pas, et
  rien de nouveau n'est synchronisé. Un film est vu (et écarté par « Masquer les films déjà vus ») quand l'une de ses
  versions est terminée, et dans Ma liste quand l'une de ses versions y est ; ajouter un film depuis son affiche ajoute
  sa version montrée d'abord, le retirer retire toutes les versions gardées. « Continuer à regarder » garde chaque flux,
  avec la pastille de sa version. La dernière version choisie reste sur l'appareil.

## Limites connues

Elles gardent séparées deux versions d'un même film (une affiche de plus, jamais un film caché) :

- Une étiquette écrite avec une seule majuscule (« Sandstorm French », « Sandstorm Dual ») reste un mot du titre :
  « French », « English », « Dubbed », « Subbed » et « Dual » ne sont des étiquettes qu'en capitales, pour que « French
  Kiss » et « Johnny English » gardent leur nom.
- Une année du fournisseur différente d'un an entre deux versions (les dates de sortie varient selon les pays) fait deux
  films sous un même identifiant.
- Sans les deux années, une version dont le nom ajoute des mots à celui du film (« Le Transport : Héritage ») reste à
  part (`prefixWithoutYears`).
- Une année à la fin d'un nom (« Dune 2021 ») est prise pour l'année du flux quand c'est la sienne, ou quand il n'en a
  pas et que le nombre est une année jusqu'à l'an prochain ; « Blade Runner 2049 » garde son nombre.

## Coût

Les groupes sont construits une fois par catalogue, hors du fil principal, en un passage sur les films (tables de
hachage, sans comparer chaque paire), et gardés sous forme de tableaux d'indices dans le catalogue : 100 000 films sont
regroupés dans le tas de 64 Mo du test de mémoire du cœur Kotlin.
