# Playlist de review

[English](README.md)

Ondo est un lecteur : les apps n'embarquent ni playlist, ni chaîne, ni démo. La review d'Apple et celle de Google Play
les testent avec cette playlist, que donnent les notes de review :

**https://markedoe.github.io/ondo-iptv/review/ondo-review.m3u** (Ajouter une playlist → lien M3U)

Elle ne contient que du contenu dont nous avons les droits : une chaîne de test et une série de test que nous
produisons nous-mêmes, et des films de la Blender Foundation sous licences Creative Commons Attribution. Toutes les
adresses sont en https.

## Notre contenu

Fait pour ce projet avec les sources de test intégrées de FFmpeg (aucune image ni aucun son de tiers), sous la licence
de ce dépôt ([LICENSE](https://github.com/markedoe/ondo-iptv/blob/main/LICENSE)).

| Fichier | Ce que c'est |
|---|---|
| `live/channel.m3u8`, `live/segment00.ts`…`segment09.ts` | *Ondo Test Channel* : une mire de 60 secondes avec un son et une horloge qui défile, en HLS. La playlist la répète pendant deux heures sans balise de fin : les apps la lisent comme une chaîne en direct. |
| `live/logo.png` | Le logo de la chaîne (barres de couleur, « TEST »). |
| `series/episode1.mp4`…`episode3.mp4`, `series/poster.png` | *Test Pattern Stories* : trois épisodes de 20 secondes (S01E01–E03) et leur affiche. |
| `guide.xml` | Le guide TV de la chaîne (XMLTV), avec des noms de programmes inventés, un par heure, de deux jours avant à quatre semaines après. `.github/scripts/review_guide.py` l'écrit ; le workflow du site le réécrit à chaque déploiement et chaque semaine. |

## Films libres (Blender Foundation)

Les films sont liés, pas copiés ici. Chacun garde son titre ; le groupe s'appelle *Open movies (Blender Foundation,
CC BY)*.

| Film | Licence | Attribution et page de licence | Flux |
|---|---|---|---|
| Big Buck Bunny (2008) | [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/deed.fr) | © Blender Foundation, [peach.blender.org](https://peach.blender.org/about/) | HLS sur les flux de test publics de Mux, `test-streams.mux.dev` |
| Elephants Dream (2006) | [CC BY 2.5](https://creativecommons.org/licenses/by/2.5/deed.fr) | © Blender Foundation, Orange Open Movie Project, [orange.blender.org](https://orange.blender.org/download/) | MOV (H.264, AAC) sur `download.blender.org` |
| Sintel, bande-annonce (2010) | [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/deed.fr) | © Blender Foundation, [durian.blender.org](https://durian.blender.org/sharing/) | MP4 sur `download.blender.org` |
| Tears of Steel (2012) | [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/deed.fr) | © Blender Foundation, [mango.blender.org](https://mango.blender.org/sharing/) | HLS sur la démo publique d'Unified Streaming, `demo.unified-streaming.com` |

## Vérifications

- `.github/scripts/check_review_playlist.py` (CI) : chaque adresse de la playlist est en https, sur ce site ou sur un
  des hôtes ci-dessus (`ALLOWED_HOSTS`), le guide est sur ce site et contient la chaîne. Un nouvel hôte s'ajoute là et
  dans ce tableau, avec sa licence, ou pas du tout.
- Les workflows de publication (`release.yml`, `release-android.yml`), *Review playlist answers* : la playlist publiée
  sur le site de main répond, chacune de ses adresses répond, et son guide couvre l'heure actuelle.
