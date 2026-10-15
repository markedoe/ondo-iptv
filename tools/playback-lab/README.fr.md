# Banc de test de la lecture

[English](README.md)

Pourquoi un flux se fige-t-il un moment avant de repartir, et est-ce le réseau ou le lecteur ? Ce banc répond par
des mesures : libVLC (la bibliothèque du moteur VLC d'Ondo) lit des flux de test servis par un serveur local qui
se comporte mal exprès, et chaque gel est compté à l'écran.

- `faulty_server.py` : un fournisseur qui se comporte mal à la demande. Chaînes en direct : temps réel, par
  à-coups, avec des coupures, plus lent que le temps réel, avec ou sans avance à la connexion. Films : un fichier
  avec plages d'octets, avec une coupure, une connexion perdue ou une connexion lente.
- `harness.py` : lit un flux comme le fait `VLCBackend`. Les images sortent de la vraie sortie vidéo de VLC à
  leur heure d'affichage (réduites en 320×180) et une image compte quand son contenu a changé. Un gel, c'est
  300 ms ou plus sans nouvelle image une fois l'image partie.
- `run.py` : joue chaque scénario d'une série avec chaque réglage et affiche un tableau.
- `check_verdict.py` : le diagnostic des gels de l'app (`PlaybackHealth` dans IPTVCore), porté ligne à ligne,
  appliqué aux compteurs de libVLC enregistrés pendant une lecture, et comparé à ce que montrait l'écran.
- `make_media.sh` : génère les flux de test (rien n'est téléchargé) : 720p à 25 images par seconde, 3,4 Mb/s en
  MPEG-TS comme une chaîne typique, et un flux 4K trop lourd pour un appareil occupé.

## Lancer les mesures

Il faut Python 3, ffmpeg et libVLC 3 avec son module Python (Debian ou Ubuntu : `vlc-bin vlc-plugin-base
python3-vlc`).

```sh
cd tools/playback-lab
./make_media.sh
python3 faulty_server.py 8765 &
python3 run.py live repeat=2    # puis vod, zap, reactions, traces, heavy
python3 check_verdict.py results-traces.jsonl results-heavy.jsonl
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
et estime le gel comme la part de l'interruption que le tampon n'a pas couverte.

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

## Films

| Fournisseur | 3 s | 5 s |
|---|---|---|
| Rapide | 0 | 0 |
| Une coupure de 4 s | 0 | 0 |
| Connexion perdue à 30 s | 0 | 0 |
| 90 % du débit nécessaire | 2 / 4,5 s | 0 (en 45 s) |

VLC lit en avance et reprend tout seul une connexion perdue (`http-reconnect` n'est pas nécessaire). Sur une
connexion rapide, l'image apparaît en 0,9 s quel que soit le tampon ; sur une connexion lente, en 4,1 s avec 3 s
et en 6,3 s avec 5 s.

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

## Ce qu'Ondo en fait

- Les chaînes en direct démarrent avec un tampon de 3 secondes (2 avant) : les coupures jusqu'à environ 2,5 s ne
  se voient plus, pour une seconde de plus avant l'image d'un fournisseur qui envoie en temps réel. Dès qu'une
  coupure a épuisé le tampon, les chaînes suivantes ont 6 secondes (`LiveBufferPolicy`).
- Les films ont 5 secondes (3 avant) : rien de plus à attendre sur une connexion rapide, moins de gels sur une
  connexion lente.
- Plus, Statistiques du flux montre le moteur, la taille de la vidéo, son débit, ce qui arrive, la fluidité, les
  gels, les images perdues et le diagnostic. L'indicateur de chargement s'affiche quand une interruption dépasse
  le tampon, et une chaîne en direct qui n'a rien reçu pendant 12 secondes est reconnectée.
