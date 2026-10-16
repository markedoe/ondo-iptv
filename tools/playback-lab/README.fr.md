# Banc de test de la lecture

[English](README.md)

Pourquoi un flux se fige-t-il un moment avant de repartir, et est-ce le réseau ou le lecteur ? Ce banc répond par
des mesures : libVLC (la bibliothèque du moteur VLC d'Ondo) lit des flux de test servis par un serveur local qui
se comporte mal exprès, et chaque gel est compté à l'écran.

- `faulty_server.py` : un fournisseur qui se comporte mal à la demande. Chaînes en direct : temps réel, par
  à-coups, avec des coupures, plus lent que le temps réel, avec ou sans avance à la connexion. Films : un fichier
  avec plages d'octets, avec une coupure, une connexion perdue ou une connexion lente.
- `harness.py` : lit un flux comme le fait `VLCBackend`. Les images sortent de la vraie sortie vidéo de VLC à
  leur heure d'affichage (réduites en 320×180). Chaque image de test porte son numéro sous forme de code-barres,
  lu à l'écran : une image compte quand son numéro a changé, et après un saut le numéro dit où la lecture est
  arrivée. Un gel, c'est 300 ms ou plus sans nouvelle image une fois l'image partie.
- `run.py` : joue chaque scénario d'une série avec chaque réglage et affiche un tableau.
- `check_verdict.py` : le diagnostic des gels de l'app (`PlaybackHealth` dans IPTVCore), porté ligne à ligne,
  appliqué aux compteurs de libVLC enregistrés pendant une lecture, et comparé à ce que montrait l'écran.
- `make_media.sh` : génère les flux de test (rien n'est téléchargé) : une chaîne (720p à 25 images par seconde,
  3,4 Mb/s en MPEG-TS), un film (1080p, 6 Mb/s, une image-clé toutes les 10 s comme le font les encodeurs par
  défaut) en MP4 avec son index à la fin ou au début, en MKV et en MPEG-TS, et un flux 4K trop lourd pour un
  appareil occupé.

## Lancer les mesures

Il faut Python 3, ffmpeg et libVLC 3 avec son module Python (Debian ou Ubuntu : `vlc-bin vlc-plugin-base
python3-vlc`).

```sh
cd tools/playback-lab
./make_media.sh
python3 faulty_server.py 8765 &
python3 run.py live repeat=2    # puis vod, film, resume, zap, reactions, traces, heavy
python3 check_verdict.py results-traces.jsonl results-heavy.jsonl
python3 run.py check            # ce que vérifie la CI
```

Les chiffres ci-dessous viennent de lectures de 45 secondes avec libVLC 3.0.20 sous Linux, trois à la fois sur
quatre cœurs (Ondo embarque libVLC 3.0.21 avec VLCKit 3.6). La série du direct a été jouée deux fois et a donné
les mêmes chiffres les deux fois. Ils comparent les réglages entre eux ; ce ne sont pas des mesures sur un
téléphone ou un Mac.

## Ce que disent les compteurs, et ce que montre l'écran

libVLC ne met à jour sa position et ses compteurs (images affichées, octets lus) que quand des données arrivent.
Quand un fournisseur cesse d'envoyer, ils s'arrêtent aussitôt, alors que l'image continue grâce au tampon de VLC :
avec une coupure de 5 secondes et 2 secondes de tampon, les compteurs se sont arrêtés à 20,0 s et l'image à
22,5 s. Quand les données arrivent par paquets toutes les 2,5 secondes, les compteurs avancent par bonds de
2,5 secondes sur une image qui ne s'arrête jamais.

Une première version de ce banc comptait les gels avec ces compteurs et trouvait une image figée 51 secondes par
minute avec les fournisseurs par à-coups ; à l'écran, il n'y a aucun gel. L'app lit les mêmes compteurs (VLCKit
ne donne rien d'autre) : `PlaybackHealth` traite donc une position immobile comme une *interruption* des données
et estime le gel comme la part de l'interruption que le tampon n'a pas couverte. L'estimation penche du côté du
gel : VLC peut garder plus que son cache réseau, après avoir augmenté son délai (voir plus bas) ou avec un
fournisseur qui a envoyé de l'avance, et une coupure que l'app compte comme un gel peut alors ne pas se voir.

Le tampon ne couvre que la part d'une interruption pendant laquelle aucune donnée n'arrive. Quand les données
continuent d'arriver mais moins vite que la vidéo n'en a besoin, la position reste immobile alors que des octets
arrivent encore : le lecteur est à court et remplit à nouveau son tampon, et l'image est figée pendant toute
l'interruption (rapport d'un utilisateur : un film 4K à 12 Mb/s reçu à 9 Mb/s se figeait 6 à 8 s à chaque fois avec
5 s de tampon, que la première estimation chiffrait à 1 à 3 s).

## Chaînes en direct

Gels / secondes figées à l'écran en 45 secondes, selon le cache réseau (le tampon que VLC remplit avant de
montrer l'image) :

| Fournisseur | 2 s | 3 s | 5 s | 10 s | 2 s + clock-synchro=0 | 5 s + clock-synchro=0 |
|---|---|---|---|---|---|---|
| Temps réel | 0 | 0 | 0 | 0 | 0 | 0 |
| Paquets toutes les 1 s | 0 | 0 | 0 | 0 | 0 | 0 |
| Paquets toutes les 2,5 s | 0 | 0 | 0 | 0 | 0 | 0 |
| Coupures de 1,5 s et 2,5 s | 1 / 1,3 s | 0 | 0 | 0 | 1 / 1,3 s | 0 |
| Une coupure de 5 s | 2 / 3,3 s | 1 ou 2 / 2,3 s | 0 | 0 | 2 / 3,3 s | 0 |
| 95 % du débit nécessaire | 2 / 2,1 s | 0 | 0 | 0 | 2 / 2,1 s | 0 |

Avec un fournisseur qui envoie 95 % de ce dont la vidéo a besoin, un tampon plus grand ne fait que retarder les
gels : il se vide de 5 % par seconde.

Secondes avant que l'image apparaisse :

| Fournisseur | 2 s | 3 s | 4 s | 5 s |
|---|---|---|---|---|
| Temps réel | 2,9 | 3,9 | 4,9 | 5,9 |
| 8 s d'avance à la connexion | 0,9 | 0,9 | 0,9 | 0,9 |

- Le tampon couvre une coupure jusqu'à environ sa propre durée ; une coupure plus longue fige l'image pour le
  reste.
- Avec un fournisseur qui envoie en temps réel, l'image apparaît environ 0,9 s après que le tampon est plein ;
  avec un fournisseur qui envoie quelques secondes d'avance à la connexion (beaucoup gardent les dernières
  secondes de chaque chaîne), elle apparaît tout de suite, quel que soit le tampon.
- `clock-synchro=0` et `clock-jitter` ne changent rien à l'écran.
- Quand une coupure dépasse le tampon, VLC augmente lui-même son délai pour la suite de la chaîne (une coupure de
  3 s après une de 2,5 s a figé l'image 0,8 s au lieu de 1,3 s).

## Films : coupures et connexions perdues

| Fournisseur | 3 s | 5 s |
|---|---|---|
| Rapide | 0 | 0 |
| Une coupure de 4 s | 0 | 0 |
| Connexion perdue à 30 s | 0 | 0 |
| 90 % du débit nécessaire | 2 / 4,5 s | 0 (en 45 s) |

VLC lit en avance et reprend tout seul une connexion perdue (`http-reconnect` n'est pas nécessaire). Avec 1 s de
tampon aussi, une coupure de 4 s et une connexion perdue ne se voient pas, sur une connexion rapide comme sur une
connexion trois fois plus rapide que le film.

## Films : démarrage et sauts

Le film dans trois conteneurs sur trois réseaux : rapide, lointain (chaque requête attend 150 ms, données à trois
fois le débit du film) et lent (300 ms, une fois et demie le débit). Dans chaque case : les secondes avant que
l'image bouge ; puis, pour un saut de +10 s, un saut de −10 s et un saut à 1:30, les secondes avant qu'elle
reparte.

| Fichier | Réseau | 1 s | 3 s | 5 s |
|---|---|---|---|---|
| MP4, index à la fin | rapide | 0,2 ; 0,7 / 0,3 / 0,1 | 0,2 ; 0,6 / 0,3 / 0,1 | 0,2 ; 0,7 / 0,2 / 0,1 |
| | lointain | 1,1 ; 0,3 / 1,2 / 0,7 | 1,8 ; 0,3 / 1,9 / 1,4 | 2,4 ; 0,3 / 2,5 / 2,1 |
| | lent | 2,1 ; 4,5 / 6,2 / 1,4 | 3,4 ; 4,8 / 0,5 / 2,7 | 4,7 ; 4,9 / – / 4,2 |
| MP4, index au début | rapide | 0,1 ; 0,6 / 0,2 / 0,1 | 0,1 ; 0,7 / 0,3 / 0,1 | 0,2 ; 0,7 / 0,3 / 0,1 |
| | lointain | 0,7 ; 0,3 / 1,2 / 0,7 | 1,4 ; 0,3 / 1,9 / 1,4 | 2,4 ; – |
| | lent | 1,4 ; 4,5 / 6,2 / 1,4 | 2,7 ; 4,8 / 0,5 / 2,7 | 4,0 ; 4,9 / – / 4,2 |
| MKV | rapide | 0,1 ; 0,3 / 0,2 / 0,1 | 0,2 ; 0,3 / 0,2 / 0,1 | 0,1 ; 0,4 / 0,2 / 0,1 |
| | lointain | 2,4 ; 0,4 / 1,3 / 0,7 | 2,5 ; 0,4 / 2,0 / 1,3 | 2,5 ; 0,4 / 2,6 / 2,0 |
| | lent | 4,7 ; 2,2 / – / 2,1 | 4,8 ; 3,6 / – / 2,7 | 4,7 ; 5,0 / – / 4,1 |

« – » : le banc n'a pas pu dire où ce saut est arrivé. Tous les sauts qu'il a vus sont arrivés sur la cible (à
0,04 s près).

- Le tampon est attendu au démarrage et après chaque saut qui sort de ce que VLC a déjà lu : sur toute connexion
  autre que rapide, chaque seconde de tampon est une seconde d'attente de plus. Un saut en avant dans ce qui est
  déjà lu (+10 s sur le réseau lointain) coûte 0,3 s quel que soit le tampon.
- Un film MKV sur un serveur lointain ou lent met 2,4 à 4,8 s à démarrer quel que soit le tampon : VLC lit d'abord
  l'index du fichier, à sa fin, à quelques allers-retours.
- Sur le réseau lent, un saut précis a besoin des données depuis l'image-clé avant la cible (ici jusqu'à 10 s de
  film) : 4,5 à 6 s.
- Le saut rapide de VLC (`input-fast-seek`) va à une image-clé : 1,3 s sur le réseau lent, mais il arrive jusqu'à
  7 s avant la cible, si bien qu'un saut de +10 s n'avance que de 3 à 5 s. Il reste désactivé.
- Les films en MPEG-TS sautent mal avec VLC : 2 à 4 s même sur une connexion rapide, en arrivant 5 s avant la
  cible, et les sauts suivants n'ont pas été vus arriver. Le MKV et le MP4 sautent précisément.

## Films : reprise

L'app reprend un film avec le `start-time` de VLC. Secondes avant que l'image bouge à 1:00 :

| Fichier | Réseau | 1 s | 3 s |
|---|---|---|---|
| MP4, index à la fin | rapide / lointain / lent | 0,4 / 1,4 / 2,8 | 0,4 / 2,1 / 4,1 |
| MP4, index au début | rapide / lointain / lent | 0,2 / 1,1 / 2,6 | 0,2 / 2,0 / 4,0 |
| MKV | rapide / lointain / lent | 0,5 / 3,0 / 5,7 | 0,5 / 3,6 / 7,3 |

Chacun a repris exactement à 1:00. VLC ignore `start-time` en MPEG-TS et démarre à 0:00 ; l'app fait alors un saut.

## Un appareil qui ne suit pas

Le flux 4K sur la moitié d'un cœur : 28 % des images perdues et trois gels courts, alors que les données
arrivaient à temps. Les images perdues sont le signe : dans tous les cas réseau, aucune ne l'a été.

## Les événements de VLC

VLC envoie un événement « buffering » environ deux fois par seconde pendant la lecture (103 en 45 secondes), même
quand tout va bien. Ondo réagissait à chacun (format d'image, recadrage, pistes, taille). Le banc n'a montré aucun
gel dû à cela, mais c'était du travail inutile sur le fil principal : Ondo ne réagit plus qu'aux vrais
changements.

## Le diagnostic, vérifié

`check_verdict.py` sur les compteurs enregistrés (séries `traces` et `heavy`) :

| Enregistrement | À l'écran | Diagnostic |
|---|---|---|
| Direct, temps réel, 2 s | aucun gel | fluide |
| Direct, paquets toutes les 2,5 s, 2 s | aucun gel | fluide |
| Direct, une coupure de 5 s, 2 s | 3,3 s figées | réseau |
| Direct, une coupure de 5 s, 5 s | aucun gel | fluide |
| Direct, 95 % du débit, 2 s | 2,1 s figées | réseau |
| Film, 90 % du débit, 3 s | 4,5 s figées | réseau |
| Film, rapide, 3 s | aucun gel | fluide |
| Direct 4K, appareil occupé | 28 % perdues | lecteur |
| Film 4K, appareil occupé | 27 % perdues | lecteur |

## Dans la CI

`run.py check` joue douze cas avec les réglages qu'Ondo livre et échoue dès qu'un cas dépasse sa limite : les
coupures que le tampon du direct doit couvrir, les données par à-coups qui ne doivent jamais figer l'image, le
temps avant l'image d'une chaîne et d'un film, où et en combien de temps arrivent les sauts, une reprise à 1:00,
une coupure et une connexion perdue pendant un film, et le diagnostic des gels sur des compteurs enregistrés. Les
limites laissent de la marge aux machines partagées. Il tourne à chaque modification du banc, du cœur de lecture
ou de `VLCBackend` (`.github/workflows/playback-lab.yml`). La CI de l'app lit aussi le flux d'exemple public
d'Apple avec les deux moteurs et vérifie les temps de démarrage et de saut que le lecteur mesure
(`AppTests/PlaybackTimingTests.swift`).

## Ce qu'Ondo en fait

- Les chaînes en direct démarrent avec un tampon de 3 secondes (2 avant) : les coupures jusqu'à environ 2,5 s ne
  se voient plus, pour une seconde de plus avant l'image d'un fournisseur qui envoie en temps réel. Dès qu'une
  coupure a épuisé le tampon, les chaînes suivantes ont 6 secondes (`BufferPolicy`).
- Les films démarrent avec 1 seconde (3 avant) : sur un serveur lointain, un film démarre 0,7 s plus tôt, reprend
  0,7 s plus tôt et un saut en arrière prend 1,2 s au lieu de 1,9 s. Dès qu'une coupure a épuisé le tampon (une
  connexion trop lente pour lire en avance), les films suivants ont 5 secondes.
- Réglages, Mémoire tampon du direct et Mémoire tampon des films et séries, remplacent le choix automatique par
  une durée fixe.
- Plus, Signaler un problème… montre un rapport de la lecture à partager : les temps de démarrage et de saut tels
  que le spectateur les a attendus, les gels, les statistiques et les événements, sans adresse, ni identifiant,
  ni titre.
- Plus, Statistiques du flux montre le moteur, la taille de la vidéo, son débit, ce qui arrive, la fluidité, les
  gels, les images perdues et le diagnostic. L'indicateur de chargement s'affiche quand une interruption dépasse
  le tampon, et une chaîne en direct qui n'a rien reçu pendant 12 secondes est reconnectée.
