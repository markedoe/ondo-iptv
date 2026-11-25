# Mémoire des pistes

*[English version](README.md)*

Le son et les sous-titres gardés pour chaque film et chaque série, et les langues par défaut d'un profil
(docs/TRACKS.fr.md), lus par les deux cœurs : `TrackMemoryTest` sur Android et `TrackMemoryTests` sur Apple.

`cases.json` contient :

- `languages` : un code tel qu'un flux ou un réglage le donne, et la langue que les deux cœurs comparent (`fre`, `fra`,
  `fr-CA` : `fr` ; `und` : aucune) ;
- `matches` : les pistes d'un flux, ce qu'un choix a gardé d'une piste (`pick`), la piste en cours (`current`,
  facultatif) et la piste trouvée (son index ; aucune quand le flux n'a pas sa langue) ;
- `plans` : les pistes de son et de sous-titres d'un flux, ce qui est retenu pour le titre (`memory`) et les réglages
  par défaut du profil, et les pistes avec lesquelles il démarre (son aucun : celui du lecteur ; sous-titres aucun :
  désactivés) ;
- `remember` : ce qui est retenu, les clés du titre en cours (les versions d'un film : celle qui joue d'abord), un choix
  (`audio`, `subtitles`, `subtitlesOff`) et ce qui est retenu ensuite, le plus récent d'abord ;
- `subtitlesOff` : ce qui est retenu et les réglages par défaut du profil, et si un flux sans les sous-titres voulus
  n'en montre aucun (le Désactivé du titre ou du profil) plutôt que le choix du lecteur (les forcés) ;
- `titleKeys` : une clé de progression et la clé sous laquelle les pistes d'un titre sont gardées (celle de la série
  pour tous ses épisodes ; aucune pour une chaîne).

Une règle changée dans un cœur l'est dans l'autre, avec un cas ici.
