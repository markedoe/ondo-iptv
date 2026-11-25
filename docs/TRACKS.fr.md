# Pistes de son et de sous-titres

*[English version](TRACKS.md)*

Ondo retient le son et les sous-titres choisis, pour chaque film et chaque série, et chaque profil a ses propres langues
pour les titres où il n'a rien choisi. Les deux applications suivent les mêmes règles, dans leurs cœurs : `TrackMemory`
dans IPTVCore (`Packages/IPTVCore/Sources/IPTVCore/Playback/TrackMemory.swift`) et dans le cœur Kotlin
(`android/core/src/main/kotlin/com/markedoe/ondo/core/playback/TrackMemory.kt`), avec les mêmes cas
(`fixtures/track-memory`).

## Ce qui est retenu

- **Une série** : une piste de son ou des sous-titres choisis (ou les sous-titres désactivés) pendant un épisode sont
  gardés pour la série. Chaque épisode suivant démarre avec, qu'il se lance seul à la fin, depuis Épisode suivant ou
  depuis la fiche de la série.
- **Un film** : gardés pour le film, qui redémarre avec, là où il a été laissé ou depuis le début. Les autres versions
  du film (4K, HD, VOSTFR…, docs/VERSIONS.fr.md) prennent aussi ce choix, quand elles ont la langue.
- Chaque partie n'est gardée qu'une fois choisie : des sous-titres choisis seuls laissent le son à la langue du profil.
- **Les chaînes en direct** ne gardent rien par chaîne. Elles s'ouvrent avec les langues du profil, demandées au lecteur
  avant le flux (Android n'ajoute aucun choix une fois les pistes de la chaîne connues : le zapping n'attend rien de
  plus) ; un choix sur une chaîne n'est plus reporté sur la suivante, comme quand l'appareil apprenait une langue pour
  tout.

Un choix est gardé par ce qui distingue la piste, jamais par sa place dans le flux (deux épisodes rangent leurs pistes
dans des ordres différents) : sa langue (`fre`, `fra`, `fr-CA` et `fr` n'en font qu'une), si elle est forcée (seulement
les dialogues étrangers) ou pour les sourds et malentendants, son nom quand elle n'a pas de langue, et le codec du son.
La piste du flux qui lui ressemble le plus est choisie ; celle que le lecteur joue déjà dans cette langue est gardée si
elle n'en diffère que par le codec, pour qu'un démarrage ne change pas le son pour rien.

## Quand elle manque

Un flux démarre avec le choix du titre ; s'il n'a pas cette langue, avec la langue du profil (Réglages, Lecteur) ; s'il
n'a pas celle-là non plus, avec sa propre piste de son et sans sous-titres (Android affiche encore les sous-titres
forcés, comme le Désactivé de Media3). Des sous-titres désactivés pour un titre le restent, quelle que soit la langue
du profil.

## Les langues d'un profil

Réglages, Lecteur : **Langue audio** (Automatique, ou une langue) et **Langue des sous-titres** (Désactivés, ou une
langue), pour le profil ouvert. Automatique, ce sont les langues de l'appareil, puis la piste par défaut du flux. Ce
sont des réglages locaux : rien n'est vérifié auprès d'un service extérieur.

## Où c'est gardé

Dans le document de bibliothèque du profil (`titleTracks` et `trackDefaults`), à côté de son historique : les choix des
500 derniers titres, sous la clé du film ou de la série (`playlist|type|id`, comme leur progression). Un autre profil
n'en hérite pas. Ils restent sur l'appareil : la synchronisation ne transporte pas les réglages de lecture
(docs/SYNC.fr.md).

## Les lecteurs

- **Android** (téléphone et TV, Media3) : les langues sont demandées au sélecteur de pistes de Media3 avant l'ouverture
  du flux, puis, une fois les pistes du flux connues, elles sont choisies selon les règles ci-dessus, une fois par film ou épisode
  ouvert (pas de nouveau à une nouvelle tentative ou à une autre adresse) et jamais à la place d'une piste choisie
  entre-temps ; un changement de son est suivi du même petit saut qu'un choix dans le panneau Audio, qui garde l'image
  avec le son. Des réglages lus tard gardent les pistes déjà choisies. Un changement de
  sélection fait par la personne (panneaux Audio et Sous-titres, commandes de Media3 sur téléphone) est gardé ; un
  changement fait par l'application (ces pistes, un sous-titre téléchargé) ne l'est pas.
- **Apple** (iPhone, iPad, Mac) : la sélection de médias du lecteur du système, une fois ses options lues ; les films MKV
  remuxés sur iPhone et iPad demandent les mêmes langues de son ; VLC (films MKV sur Mac, flux que le lecteur du système
  refuse) choisit parmi les pistes qu'il liste d'après la langue que donnent leurs noms (`Track 1 - [French]`), sinon
  d'après le nom, les sous-titres une fois listés (souvent après le son) ; il ne désactive ses propres sous-titres
  (les forcés) que pour le Désactivé du titre ou du profil. Ce que la personne a choisi sur un élément reste quand le
  flux est rouvert (nouvelle tentative, reconnexion), une chaîne comprise. Un fichier de sous-titres chargé n'est pas un
  choix de la personne. Un choix est gardé dans la bibliothèque du profil où la lecture a commencé, même si un autre
  profil a été ouvert entre-temps.

## Migration

Les applications apprenaient une langue de son et une langue de sous-titres pour tout l'appareil, à chaque choix dans
le lecteur. Elles ne les apprennent plus : elles ne sont lues que comme les langues du profil principal, jusqu'à ce que
ce profil règle les siennes. Les autres profils démarrent sans.
