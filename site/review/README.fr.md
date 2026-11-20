# Playlist de review

[English](README.md)

Ondo est un lecteur : les apps n'embarquent ni playlist, ni chaîne, ni démo. La review d'Apple et celle de Google Play
les testent avec cette playlist, que donnent les notes de review :

**https://markedoe.github.io/ondo-iptv/review/ondo-review.m3u** (Ajouter une playlist → lien M3U)

Elle ne contient que du contenu dont nous avons les droits : une chaîne de test et une série de test que nous
produisons nous-mêmes, et des extraits de films de la Blender Foundation sous licences Creative Commons Attribution.
Chaque fichier est hébergé ici, sur ce site, et toutes les adresses sont en https : aucun hôte tiers ne peut cesser de
répondre ni changer ce que voient les reviewers.

## Notre contenu

Fait pour ce projet avec les sources de test intégrées de FFmpeg (aucune image ni aucun son de tiers), sous la licence
de ce dépôt ([LICENSE](https://github.com/markedoe/ondo-iptv/blob/main/LICENSE)).

| Fichier | Ce que c'est |
|---|---|
| `live/channel.m3u8`, `live/segment00.ts`…`segment09.ts` | *Ondo Test Channel* : une mire de 60 secondes avec un son et une horloge qui défile, en HLS. La playlist la répète pendant deux heures sans balise de fin : les apps la lisent comme une chaîne en direct. |
| `live/logo.png` | Le logo de la chaîne (barres de couleur, « TEST »). |
| `series/episode1.mp4`…`episode3.mp4`, `series/poster.png` | *Test Pattern Stories* : trois épisodes de 20 secondes (S01E01–E03) et leur affiche. |
| `guide.xml` | Le guide TV de la chaîne (XMLTV), avec des noms de programmes inventés, un par heure, de deux jours avant à quatre semaines après. `.github/scripts/review_guide.py` l'écrit ; le workflow du site le réécrit à chaque déploiement et chaque semaine. |

## Films libres (Blender Foundation), dans `films/`

De courts extraits des films, réencodés en petit et hébergés ici. Ils ne sont pas sous la licence de ce dépôt : chacun
reste sous sa propre licence Creative Commons Attribution ci-dessous, et est crédité à ses auteurs. Chacun garde son
titre ; le groupe s'appelle *Open movies (Blender Foundation, CC BY)*.

| Film | Licence | Attribution et page de licence | Fichier ici | Source et ce qui a changé |
|---|---|---|---|---|
| Big Buck Bunny (2008) | [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/deed.fr) | © Blender Foundation, [peach.blender.org](https://peach.blender.org/about/) | `films/big-buck-bunny/movie.m3u8` et `segment00.ts`…`segment07.ts` (HLS, segments de 6 secondes) | `download.blender.org/peach/bigbuckbunny_movies/BigBuckBunny_640x360.m4v.zip` ; 45 secondes, de 1:00 à 1:45 |
| Elephants Dream (2006) | [CC BY 2.5](https://creativecommons.org/licenses/by/2.5/deed.fr) | © Blender Foundation, Orange Open Movie Project, [orange.blender.org](https://orange.blender.org/download/) | `films/elephants-dream.mov` (MOV) | `download.blender.org/ED/elephantsdream-720-h264-st-aac.mov` ; 45 secondes, de 1:00 à 1:45 |
| Sintel, bande-annonce (2010) | [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/deed.fr) | © Blender Foundation, [durian.blender.org](https://durian.blender.org/sharing/) | `films/sintel-trailer.mp4` (MP4) | `download.blender.org/durian/trailer/sintel_trailer-720p.mp4` ; la bande-annonce entière (52 secondes) |
| Tears of Steel (2012) | [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/deed.fr) | © Blender Foundation, [mango.blender.org](https://mango.blender.org/sharing/) | `films/tears-of-steel/movie.m3u8` et `segment00.ts`…`segment07.ts` (HLS, segments de 6 secondes) | `download.blender.org/demo/movies/ToS/tears_of_steel_720p.mov` ; 45 secondes, de 2:00 à 2:45 |

Les quatre ont été réencodés avec FFmpeg en 640×360 à 24 images par seconde (avec des bandes noires pour les films plus
larges), en H.264 Main à environ 550 kbit/s et en AAC stéréo à 96 kbit/s, environ 4 Mo chacun ; les films HLS sont des
playlists à la demande avec une balise de fin. Leurs métadonnées et chapitres ont été retirés
(`-map_metadata -1 -map_chapters -1`) ; l'image et le son ne sont pas modifiés autrement.

## Vérifications

- `.github/scripts/check_review_playlist.py` (CI) : chaque adresse de la playlist est en https sur ce site, et nulle
  part ailleurs ; chacune désigne un fichier de `site/review/`, chaque playlist HLS d'ici désigne des segments qui
  existent à côté d'elle, et le guide est sur ce site et contient la chaîne. Un nouveau film s'ajoute comme fichier
  ici, avec sa licence dans ce tableau, ou pas du tout.
- Les workflows de publication et de rattrapage (`release.yml`, `release-android.yml`, `play-catchup.yml`), *Review
  playlist answers* : la playlist publiée sur le site de main répond, chacune de ses adresses répond, et son guide
  couvre l'heure actuelle.
