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
  `FR -`, `STFR|`, `VFF`, `MULTI`, `4K`, ceux du son `Atmos`, `DTS`, `5.1`…), sans tenir compte de la casse ni des accents : « |FR| Le Transport 4K » et « Le
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
  toutes lettres, dans la langue de l'app, ce qui distingue sa version des autres (`FilmVersions.choices`, les mêmes
  règles et la même table dans les deux apps : `fixtures/film-versions/labels.json`). Les tags sont lus dans le nom du
  flux et dans celui de sa catégorie :
  - qualité : 4K (4K, UHD, 2160p), Full HD (FHD, 1080p), HD (HD, 720p), SD (SD, 480p, 576p) ;
  - image : Dolby Vision (DV, DoVi, Dolby Vision), HDR (HDR, HDR10, HDR10+), 3D (3D, SBS, HSBS), IMAX, HEVC (HEVC,
    H.265, x265) ;
  - langue : Multilingue (MULTI, DUAL), Français (France) (VFF, TRUEFRENCH), Français (Québec) (VFQ), Français
    (international) (VFI), Français (VF, FRENCH), Version originale (VO), Anglais, Espagnol, Allemand, Italien,
    Portugais, Arabe (le mot, ou un code en capitales à un bout du nom ou de la catégorie, séparé par un délimiteur du
    fournisseur : « FR - … », « |EN| Movies », « [IT] … », « … (DE) » ; le premier mot de « IT WAITS BELOW » n'est pas
    un code) ;
  - sous-titres : VO, sous-titres français (VOSTFR, STFR, SUBFR, ST FR, FR SUB), VO sous-titrée (VOST, VOSTA,
    SUBBED) ;
  - une copie filmée ou sortie avant la sortie du film : Copie cinéma (CAM) (CAM, HDCAM), (TS) (TS, HDTS, TELESYNC),
    (TC) (TC, TELECINE), (SCR) (SCREENER, SCR, DVDSCR), (R5), (Workprint) (WORKPRINT), lue dans le nom ou la catégorie
    (« Cinema CAM ») ; les courts et « Screener » seulement en capitales, pour que « The Night Cam » garde son mot. Une
    telle copie n'est jamais une même pastille avec une vraie copie, sa pastille vient après les leurs, et elle n'est
    jamais lue en premier (elle passe après toutes les autres versions, quel que soit l'écran).

  Un mot de tag qui est aussi un mot de titre (English, French, Spanish, German, Italian, Portuguese, Arabic, Dual, VO,
  SD) n'est un tag qu'en dehors des mots que partagent les noms de toutes les versions : « The English Orchard » et
  « The English Orchard MULTI » se lisent « Standard » et « Multilingue ». Une espace insécable sépare les mots comme une
  espace.

  **Des versions qui lisent la même chose sont une seule pastille.** Un panel range souvent un même flux dans deux
  catégories (« |FR| AJOUTÉS » et « |FR| DRAME | THRILLER »), parfois avec une autre casse. Des versions aux mêmes
  tags de pastille (qualité, HDR ou Dolby Vision, 3D, IMAX, codec, langue, sous-titres), au même conteneur et au même nom
  une fois normalisé (casse, accents, apostrophes, ponctuation, préfixe du fournisseur comme « |FR| » et année omis :
  `FilmVersions.nameKey`) forment une pastille, nommée comme une seule version : « |FR| Copper Vallée », « |FR| COPPER
  VALLÉE » et « |FR| Copper Vallée 4K DV » se lisent « Standard » et « 4K Dolby Vision ». La pastille lit son flux
  préféré, selon les règles de la version montrée d'abord (plus bas) : celui en cours, sinon le dernier choisi, sinon le
  premier dans l'ordre du catalogue. Ses autres flux sont ses secours : quand le préféré ne démarre pas, le lecteur les
  essaie tour à tour (les adresses suivantes de la demande de lecture) avant d'afficher une erreur. La progression reste
  celle du flux lancé : une version en cours reprend là où elle en était.

  Une pastille ne nomme que ce qui diffère entre les versions, dans cet ordre (« 4K Dolby Vision », « HD · VO,
  sous-titres français ») : un tag que toutes les versions ont est omis. Deux versions qui se liraient pareil ajoutent
  les mots de leur nom que les autres n'ont pas (« Extended Cut ») ; une version sans aucun tag, à côté de versions qui
  en ont, s'appelle « Standard ». Des pastilles encore identiques ajoutent, dans cet ordre, leur conteneur
  (« Standard · MKV », « Standard · MP4 »), puis les mots du fournisseur que les pastilles ne savent pas nommer, tels
  que le nom les écrit (« DUB »). Le nom d'une catégorie ne distingue jamais des pastilles : la fiche d'un film ne montre
  aucune catégorie (seuls les tags qu'on y lit comptent : « FILMS 4K » est de la 4K, « Cinema CAM » une copie
  cinéma). Des pastilles encore identiques
  sont numérotées (« 4K 1 », « 4K 2 » ; « Version 1 », « Version 2 »), en dernier recours. Deux pastilles d'un même film
  ne se lisent jamais pareil, et un film dont toutes les versions lisent la même chose n'a pas de pastille. Un lecteur
  d'écran dit « Version : 4K Dolby Vision ». La version choisie est celle qui est lue ; Lecture, la progression et le
  badge du format sont les siens.
- **Données du titre** : ce que l'app a lu chez le fournisseur pour un titre, à photographier quand une pastille se lit
  mal. Dans le lecteur, Plus, Statistiques du flux les montre à côté des statistiques (les deux apps) : le titre
  affiché, le nom du fournisseur, les identifiants (un numéro du fournisseur seulement, jamais ceux d'une playlist M3U,
  qui sont un hachage de l'adresse du flux), la catégorie (nom et identifiant), l'année, les tags lus, les
  identifiants TMDb (la liste du fournisseur, sa fiche, et sur Android le titre reconnu), le conteneur ; pour les
  versions d'un film (quatre au plus, celle en lecture d'abord), la pastille, le nom, la catégorie, l'identifiant, le
  conteneur et les tags de chacune, celle en lecture, celle lue en premier et pourquoi ; chaque flux reste listé, ceux
  qu'une même pastille réunit marqués « identique à » celui que lit leur pastille ; pour un épisode, son titre brut et
  le nom de sa série. Le titre affiché est celui de l'app dans les deux apps, sans les tags du fournisseur autour du
  nom (`CatalogueMatcher.displayTitle`), comme le montre la fiche du film (la barre du lecteur d'Apple montre encore le
  nom du fournisseur tel quel). Sur une TV, OK maintenu sur la pastille d'une version, sur la page d'un film, montre la même
  chose pour toutes les versions, chacune une étape de la télécommande. Jamais d'adresse, d'identifiant, de mot de passe
  ni de serveur : ceux de la playlist sont masqués partout où ils apparaissent, comme les adresses, `user:password@`,
  les valeurs de requête, les adresses IP et `hôte:port` (`Redaction`, les mêmes règles et la même table sur les deux
  apps : `fixtures/title-data/redaction.json`). Elles ne sont pas dans le rapport de problème.
- **La version montrée d'abord** (et lue depuis une affiche) : celle en cours ; sinon la dernière choisie sur cet
  appareil ; sinon celle qui convient le mieux à l'écran (une copie cinéma en dernier) ; sinon la première. Ce qui
  convient à l'écran se lit dans ce qu'on sait de chaque version (mesuré, sinon selon le fournisseur, sinon les tags du
  nom) et dans l'écran :
  - **la 4K** sur un écran 4K, quel que soit son mode du moment. Sur Android, dans cet ordre : le plus grand de
    `Display.supportedModes` en taille physique (un boîtier qui dessine une interface 1080p, ou qui lit 1080p en
    « auto » tant qu'aucun film ne joue, reste un écran 4K) ; sinon la taille que Media3 lit de l'écran d'une TV
    (`Util.getCurrentDisplayModeSize` : la propriété `vendor.display-size` qu'écrit un boîtier, `sys.display-size` avant
    Android 9, la dalle 4K d'une Sony BRAVIA), pour un boîtier en « auto » qui ne liste que le mode 1080p de son
    interface ; sinon, sur une TV seulement, un écran qui prend le HDR avec un décodeur matériel HEVC ou Dolby Vision
    pour 3840×2160, seulement là où rien d'autre ne peut le dire : l'écran ne liste qu'une taille et Media3 n'a lu
    aucune taille d'affichage à lui (il rend celle du mode du moment quand une TV n'en écrit pas). Une taille
    d'affichage lue sous la 4K n'est pas de la 4K, pas plus qu'un écran qui liste plusieurs tailles toutes sous la 4K
    (une clé ou une TV HDR 1080p, dont le décodeur peut quand même prendre le 2160p). Une TV HDR 1080p derrière un
    boîtier 4K en « auto » se lit encore 4K : ses versions 4K jouent, réduites. Jamais un téléphone ni une tablette pour
    cette dernière règle, jamais un décodeur logiciel. La taille d'affichage est lue en arrière-plan aussitôt, à chaque
    écran ouvert ; les décodeurs une fois, quelques secondes après le lancement ; un film lancé entre-temps joue la
    version choisie avec ce qu'on sait alors. Chez Apple la taille native de l'écran ;
  - **le HDR** (HDR10, HDR10+, HLG) seulement là où l'écran l'annonce (`Display.getHdrCapabilities`, sans les types que
    l'utilisateur a désactivés ; AVFoundation chez Apple) ;
  - **le Dolby Vision** en premier seulement là où l'écran lui-même annonce le Dolby Vision et qu'un décodeur existe
    (sur Android `MediaCodecList`, lu une fois en arrière-plan à la plus basse priorité, après le premier accueil ; le décodeur d'un boîtier ne fait pas afficher le Dolby
    Vision à un téléviseur). Sur un écran qui ne l'a pas, le profil Dolby Vision de la version décide : 8 et 9 (un 10
    avec une couche de base compatible) lisent leur couche de base HDR10, SDR ou HLG et comptent comme elle ; 5 (un 10
    sans couche de base) donne de fausses couleurs et n'est jamais lu en premier ; un profil inconnu (un nom qui dit
    seulement « DV », le profil 7) est pris comme un 5, tout comme un nom qui dit « DV » quand les caractéristiques du
    fournisseur ne montrent pas de Dolby Vision (le ffprobe d'un panel peut ne lire que la couche de base HDR10 d'un
    fichier profil 5) : seule la mesure du lecteur l'emporte sur le nom. Une telle version reste sur sa pastille, à choisir. Le profil
    vient de l'enregistrement de configuration du fournisseur (`dv_profile`, `dv_bl_signal_compatibility_id`), de la
    chaîne de codec de Media3 (« dvhe.08.06 » : le profil seul) ou de l'enregistrement `dvcC` d'AVFoundation.
- **Les données gardées restent par flux** : favoris, historique, progression et synchronisation ne changent pas, et
  rien de nouveau n'est synchronisé. Un film est vu (et écarté par « Masquer les films déjà vus ») quand l'une de ses
  versions est terminée, et dans Ma liste quand l'une de ses versions y est ; ajouter un film depuis son affiche ajoute
  sa version montrée d'abord, le retirer retire toutes les versions gardées. « Continuer à regarder » garde chaque flux,
  avec la pastille de sa version. La dernière version choisie reste sur l'appareil.

## De quoi une version est faite

La pastille d'une version et sa page disent de quoi son flux est fait, autant qu'on le sait, jamais plus
(`StreamSpecs`, les mêmes règles et les mêmes mots sur les deux apps : `fixtures/stream-specs/specs.json`). Trois
sources, la meilleure d'abord, champ par champ :

1. **Ce que le lecteur a mesuré** pendant la lecture : la taille, la plage (HDR10, HLG, Dolby Vision, ou SDR) et le
   codec de l'image, le codec, les canaux et l'Atmos (E-AC-3 JOC) du son, d'après les pistes de Media3 sur Android et
   les descriptions de format d'AVFoundation chez Apple (le rendu le plus haut d'un flux adaptatif ; chez Apple un flux
   HLS ne liste pas ses pistes, son image est donc laissée au fournisseur ou au nom). Le son est la piste par défaut du
   flux, sinon celle qui a le plus de canaux, jamais le choix du moment de l'utilisateur ; un flux déjà lu n'est relu
   que si son image a grandi. Gardé sur l'appareil pour les 2 000 derniers films lus, par la playlist d'où le film est
   lu et par flux (`MeasuredSpecs`, `measured-specs.json`), lu la première fois que la page d'un film ou le lecteur en
   a besoin, jamais au lancement. Une image mesurée SDR n'est pas une image HDR, quoi que dise son nom ; une mesure
   n'enlève jamais l'Atmos du fournisseur (AVFoundation n'en nomme aucun, Media3 aucun en TrueHD ni dans l'E-AC-3
   d'un MKV). L'E-AC-3 JOC de Media3, ou le `ec+3` d'une playlist HLS, est de l'Atmos ; son DTS Express est du DTS-HD
   et son DTS UHD (profil 2) du DTS:X, comme les profils « DTS:X » de ffprobe.
2. **Les détails du fournisseur** (`info.video`, `info.audio` et `info.bitrate` de `get_vod_info`, ce qu'a lu le ffprobe
   du panneau), gardés avec les détails. Un champ absent, vide, nul ou inconnu reste inconnu. Seuls les champs qui le
   disent sont lus, jamais un texte libre comme le titre d'un flux : le Dolby Vision d'après `codec_tag_string`, le
   `profile` de la vidéo ou un enregistrement de configuration dans `side_data_list`, le HDR10+ seulement d'après des
   métadonnées SMPTE ST 2094 à cet endroit, le HDR10 et le HLG d'après `color_transfer` (jamais d'après un seul profil
   10 bits), l'Atmos d'après le `profile` du son, sa disposition d'après `channel_layout` (« 5.1(side) » est du 5.1,
   « 4.1 » et « 3.0 » restent tels quels ; le nombre de canaux seulement sans elle). La page du film demande déjà les
   détails de sa propre version ; une fois ouverte et au repos un moment, elle demande ceux de ses autres versions :
   six au plus à chaque ouverture, à 400 ms d'intervalle, annulés quand elle se ferme, arrêtés au premier refus (cette
   version n'est plus demandée avant le redémarrage de l'app), et jamais tant que le lecteur est par-dessus (la page
   reprend à son retour). Ils sont gardés à part des pages ouvertes par l'utilisateur (`DetailsArchive.versions`, 300
   au plus), pour ne jamais les en chasser. La navigation (rangées, en-têtes, recherche) n'en demande aucun.
3. **Les étiquettes du nom** (« 4K », « DV », « HEVC »), comme avant.

- **Classes de définition** : 4K, 1080p, 720p ou SD, d'après la hauteur ou la largeur, la première qui atteint une
  classe : un film en scope (1920×802) est en 1080p, 3840×1600 en 4K, un 1440×1080 anamorphosé en 1080p.
- **La ligne d'informations**, après le format (« MKV »), pour la version qui serait lue : sa classe, sa plage (HDR10,
  HDR10+, HLG, Dolby Vision), son codec vidéo (H.264, HEVC, AV1) et son son (« 5.1 AAC », « 2.0 AC-3 », « Atmos »). Les
  lecteurs d'écran les disent en une phrase dans la langue de l'utilisateur (« 4K, Dolby Vision, HEVC, Son surround
  5.1, AAC »).
- **Les pastilles** disent la classe, la plage et le son d'une version là où on les connaît (« 1080p · 5.1 », « 4K
  Dolby Vision · Atmos »), les étiquettes de son nom ailleurs ; « Standard » seulement pour une version dont on ne sait
  rien. Deux versions que les règles ci-dessus réunissent en une pastille restent séparées quand leurs caractéristiques
  connues diffèrent ; celle dont on ne sait rien reste avec la première. Des pastilles qui se liraient pareil sont
  départagées par leur codec vidéo, puis le codec de leur son, puis comme avant. Sans caractéristiques, les pastilles
  sont celles d'avant (`fixtures/film-versions/labels.json`). Chaque pastille est connue par l'identifiant de sa
  première version : une pastille qui se divise à l'arrivée des caractéristiques n'en déplace aucune autre, et le focus
  reste sur la version où était la télécommande.
- **Les données du titre** listent les caractéristiques de chaque version et leur source (mesurées par le lecteur,
  selon le fournisseur, d'après le nom), et, pour savoir pourquoi une version n'a pas de caractéristiques ou n'est pas
  lue en premier :
  - ce que tenaient les détails de son fournisseur : non demandées, aucune pour cette playlist (M3U), échec (un statut
    HTTP ou « timeout », « DNS », « TLS », « JSON »), gardées par une version précédente (redemandées), demandées sans
    bloc technique, vidéo + audio, vidéo seule, audio seul ; si elles sont gardées sur l'appareil ; les noms des champs
    de `info` (les noms seuls, simples, triés, 40 au plus, jamais une valeur : une valeur peut contenir une adresse) ;
  - son profil Dolby Vision (« P8.1 », « P5 », inconnu) et ce qu'elle demande à l'écran sans l'obtenir (« Dolby Vision
    non pris en charge par cet écran : profil inconnu, pas lu en premier », « HDR non pris en charge par cet écran »,
    « 4K au-delà du plus grand mode de cet écran ») ;
  - pour l'écran, après les versions : le mode du moment et le plus grand mode (Android), HDR10, HDR10+, HLG et Dolby
    Vision oui ou non, les décodeurs Dolby Vision et HEVC Main 10, ce que prend la sortie son (Android : ses canaux
    (le plus grand des agencements de haut-parleurs de Media3, ou ce que dit la prise HDMI sur une sortie HDMI, jamais
    les 10 par défaut de Media3) et les encodages qu'elle transmet tels quels à la TV ou à l'ampli, d'après `AudioCapabilities` de Media3, relu
    chaque fois que le panneau est recalculé ; iPhone et iPad : ses canaux et l'audio spatial), s'il est 4K et pourquoi (ses modes, sa taille
    d'affichage, un écran HDR avec un décodeur 4K) avec la taille d'affichage que lit Media3 (Android), ce pour quoi les
    versions sont choisies (« 4K · HDR »), et la version et le build de l'app, pour qu'une photo dise de quel build il
    s'agit.

  Le panneau ne dépasse jamais le bas de l'écran : quand il ne tient pas, il défile, sur une TV avec Haut et Bas de la
  télécommande tant que les commandes sont masquées (en haut Haut, en bas Bas, font ce qu'ils font sans lui), sur un
  iPhone, un iPad ou un Mac d'un glissement ou à la molette.

  Un film seul a aussi sa ligne. Dans le lecteur, le panneau est recalculé quand arrivent la mesure du lecteur, les
  détails d'une version ou un échec.
- Les détails gardés par une version de l'app qui ne notait pas ce que tenait la réponse du fournisseur (avant la
  lecture des caractéristiques) sont montrés, et redemandés comme s'ils étaient anciens : ceux de la page à son
  ouverture, ceux des autres versions quand elle est au repos.
- Une mesure de même taille d'un flux déjà mesuré ajoute ce que la première ne savait pas (son profil Dolby Vision) ;
  une plus petite ou un autre son ne change rien. Un film lu par VLC (un MKV sur Mac, un flux refusé par AVPlayer) est
  mesuré aussi, la taille de son image seulement.

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
