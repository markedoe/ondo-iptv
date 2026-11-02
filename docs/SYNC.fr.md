# Synchronisation sur le réseau local (version 1)

Les appareils Ondo d’un même réseau Wi-Fi ou filaire gardent leurs playlists, Ma liste, leurs listes et la
progression de lecture en phase tant que l’app y est ouverte. Rien ne passe par internet : les appareils se
trouvent avec Bonjour (DNS-SD) et se parlent directement, et seulement à des adresses du réseau local. Le réseau
est de confiance : il n’y a ni appairage ni chiffrement, donc la synchronisation reste désactivée tant que le
spectateur ne l’active pas, sur chaque appareil (Réglages → Synchroniser sur ce réseau).

Ce document est la spécification. L’implémentation Kotlin est dans
`android/core/src/main/kotlin/com/markedoe/ondo/core/sync/`, celle en Swift dans
`Packages/IPTVCore/Sources/IPTVCore/Sync/`. Les deux lisent les mêmes fichiers d’exemple dans `fixtures/sync/`.

## Rôles

- **Android** (TV, téléphone, tablette) est serveur : tant qu’Ondo est ouvert et la synchronisation active, il
  écoute sur un port TCP et l’annonce. Il se connecte aussi aux autres appareils Android qu’il trouve.
- **iPhone, iPad et Mac** sont seulement clients : ils se connectent aux appareils Android qu’ils trouvent. (L’app
  Mac ne peut pas accepter de connexions : l’édition App Store n’a pas l’entitlement `network.server`.) Deux
  appareils Apple se synchronisent donc via un appareil Android, pas directement entre eux.

## Ce qui est synchronisé et ce qui reste

Synchronisés : les playlists (nom, type, adresse, identifiant et mot de passe, adresse du guide), Ma liste et les
chaînes favorites, les acteurs et réalisateurs gardés dans Ma liste, les listes (collections) avec leurs personnes, la progression de lecture (où chaque film et épisode a été laissé, et
ce qui a été terminé), et les clés de services extérieurs saisies dans les Réglages (le jeton TMDb).

Restent sur chaque appareil : les autres réglages, les catégories masquées, les modifications de chaînes, les rappels,
les profils, les téléchargements, les catalogues eux-mêmes (chaque appareil télécharge ses playlists), et les
playlists fichier (un fichier importé sur un appareil n’est pas sur les autres). Sur les appareils Apple, seul le
profil principal est synchronisé ; les profils enfants ne le sont pas.

## Découverte

Type de service `_ondo._tcp`. Un appareil Android dont la synchronisation est active annonce le nom de service
`Ondo-<8 premiers caractères de son identifiant>` avec les enregistrements TXT :

- `d` : son identifiant d’appareil, un UUID aléatoire créé une fois par installation (en minuscules) ;
- `v` : `1` ;
- `n` : un nom pour la liste des appareils (le nom de l’appareil dans les réglages du système, ou son modèle ;
  40 caractères au plus).

Les appareils sont reconnus par `d`. Un appareil ignore son propre `d`.

## Adresses locales uniquement

Un appareil n’envoie et n’accepte rien si l’adresse de l’autre bout n’est pas locale. Les deux côtés vérifient,
avec la même règle (`SyncNetwork.isLocal`) : IPv4 `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`,
`169.254.0.0/16` (lien local) et `127.0.0.0/8` ; IPv6 `fc00::/7` (local unique), `fe80::/10` (lien local) et
`::1` ; une adresse IPv4 représentée en IPv6 (`::ffff:a.b.c.d`) est vérifiée comme IPv4. Tout le reste est
refusé :

- le serveur ferme une connexion venant d’une autre adresse avant de la lire ;
- un client ne se connecte qu’à une adresse trouvée par Bonjour, et seulement si elle est locale (jamais à une
  adresse saisie).

Les apps refusent aussi une adresse de bouclage (`127.0.0.0/8`, `::1`, écrite aussi `::ffff:127.0.0.1`), dans les
deux sens : à cette adresse se trouve une autre app du même appareil, pas un autre appareil
(`LocalSync.isPeerAddress` sur Android, `LocalSync.isLocal` sur les appareils Apple ; la règle commune garde le
bouclage pour les tests). Et l’app Android ne synchronise que tant
qu’un réseau Wi-Fi ou filaire est connecté, avec ou sans internet : sur les données mobiles seules, les adresses
privées sont celles du réseau de l’opérateur, pas du domicile du spectateur.

## Transport

TCP. Une **trame** est une longueur sur 4 octets (gros-boutiste) suivie d’autant d’octets de JSON en UTF-8. Une
trame de plus de 16 Mio est refusée et termine la session.

Une session est un seul échange : le client se connecte, envoie son instantané (une trame), le serveur le fusionne
avec le sien, garde le résultat et répond avec l’instantané fusionné (une trame) ; le client garde la réponse et
ferme. La connexion a 10 secondes et la session entière 30 secondes. Un serveur traite au plus 4 sessions à la
fois et ferme les connexions en plus.

**Sondes.** Chaque instantané envoyé porte `digest`, une empreinte de ses éléments et suppressions (SHA-256 de
l’instantané sans `device`, `deviceName`, `clock`, `digest`, `seen`, `probe` ni `unchanged`, les suppressions dans
l’ordre des clés, et sans la progression de ce qui est en lecture sur l’appareil, enregistrée toutes les quelques
secondes) ; la réponse d’un serveur porte la sienne, celle des données dont vient la réponse. Un appareil ne compare une empreinte qu’avec ses
propres empreintes antérieures et celles qu’un pair lui a envoyées, donc les appareils n’ont pas à la calculer
octet pour octet de la même façon. Quand les données d’un client ont l’empreinte de l’instantané qu’il a envoyé en
dernier à un serveur, et que ce serveur a répondu avec une empreinte, le client envoie une **sonde** à la place :
`{"type", "version", "device", "deviceName", "clock", "digest", "seen": <l’empreinte du serveur>, "probe": true}`,
sans éléments. Un serveur dont l’empreinte est toujours `seen` répond `{"type", "version", "device", "deviceName",
"digest", "unchanged": true}`, et rien n’est fusionné ; sinon il répond avec son propre instantané. Un client envoie
quand même son instantané entier à chaque serveur au moins une fois par heure. Un
serveur plus ancien lit une sonde comme un instantané vide, la fusionne (ce qui ne retire rien) et répond en entier ;
un client plus ancien n’en envoie jamais. Une session où rien n’a changé d’un côté ni de l’autre fait ainsi quelques
centaines d’octets dans chaque sens.

**Quand.** Un client synchronise quand il trouve un appareil, puis toutes les 60 secondes tant que l’app est
ouverte, et quelques secondes après un changement local (une playlist, Ma liste, une liste, ou la fin d’une
lecture). Après une session ratée, il retrouve l’adresse de l’appareil et réessaie au bout de 15 secondes, puis
1 minute, puis toutes les 5 minutes tant que Bonjour trouve encore l’appareil. Pendant la lecture d’un film ou
d’un épisode sur le client, une session du rythme de 60 secondes qui enverrait un instantané de plus de 64 Kio
(pas une sonde) attend la suivante ; celle qui suit un changement part. Un changement compte quand il est fait sur
l’appareil : ce qu’une fusion a apporté repart avec la session régulière suivante, donc une fusion n’appelle jamais
une autre session. L’instantané, l’empreinte et la fusion sont calculés hors du fil principal ; une fusion dont les
données ont changé entre-temps est refaite, et après trois essais elle attend la session suivante.

Une session compte pour un serveur (les suppressions qu’il a envoyées sont reçues, voir **Supprimés**) une fois sa
réponse à un instantané entier arrivée au client : le client ferme la connexion après l’avoir lue. Une sonde ne
compte pas. Un client la compte une fois qu’il a fusionné une réponse entière.

Entre deux appareils Android, celui dont l’identifiant est le plus petit (comme chaîne) se connecte quand il
trouve l’autre et toutes les 60 secondes ; chacun se connecte quelques secondes après un changement qui lui est
propre. Un appareil Android qui garde ce qu’un iPhone, un iPad ou un Mac lui a envoyé le transmet ainsi aux autres
appareils Android.

## Instantané

```json
{
  "type": "ondo-sync",
  "version": 1,
  "device": "<uuid>",
  "deviceName": "TV du salon",
  "playlists": [ … ],
  "favorites": [ … ],
  "progress": [ … ],
  "lists": [ … ],
  "people": [ … ],
  "keys": [ … ],
  "deleted": { "<clé>": 1760000000000 },
  "replaced": { "<id de playlist>": "<id de playlist>" },
  "clock": 1760000000000
}
```

Les instants sont des millisecondes depuis 1970 (UTC), en entiers. Les identifiants de playlist sont des UUID
écrits en minuscules ; les lecteurs les mettent en minuscules. Les champs facultatifs sont omis quand ils n’ont pas
de valeur. Les lecteurs ignorent les champs qu’ils ne connaissent pas. Un instantané d’un autre `type` ou d’une
`version` autre que 1 est refusé (le serveur ne répond rien et ferme).

**Playlist** — `{"id", "name", "kind": "m3u" | "xtream", "url", "username"?, "password"?, "epgUrl"?, "addedAt",
"updatedAt"}`. Pour Xtream, `url` est l’adresse du serveur et `username` et `password` l’identifiant ; pour M3U,
`url` est l’adresse de la playlist. Clé : `playlist:<id>` ; instant : `updatedAt` (fixé quand la playlist est
ajoutée ou modifiée).

**Favori** (Ma liste et chaînes favorites) — `{"playlist", "kind": "live" | "movie" | "series", "item", "title",
"image"?, "addedAt"}`. Clé : `favorite:<playlist>|<kind>|<item>` ; instant : `addedAt`.

**Progression** — `{"playlist", "kind": "movie" | "series", "item", "episode"?, "title", "subtitle"?, "image"?,
"position", "duration", "watchedAt"}`. `item` est le film ou la série, `episode` l’épisode d’une série ;
`position` et `duration` sont des secondes (nombres). Un titre est terminé à 95 % de sa durée. Clé :
`progress:<playlist>|<kind>|<item>|<épisode ou vide>` ; instant : `watchedAt`.

**Liste** — `{"id", "name", "items": [{"playlist", "kind", "item", "title", "image"?, "addedAt"?}],
"people": [<personne>], "updatedAt", "renamedAt"?, "orderedAt"?}`. Clé : `list:<id>` ; instant : `updatedAt` (fixé
quand la liste est créée, renommée, remise en ordre, ou que ses éléments ou ses personnes changent). `id` est mis en
minuscules. `people` (les acteurs et réalisateurs de la liste) peut manquer : un appareil qui ne garde pas de
personnes n’en envoie aucune. Chaque partie a son propre instant (règle 7 de la fusion) : `renamedAt` quand le nom a
été fixé, `orderedAt` quand les éléments ont été mis en ordre (tous deux `updatedAt` s’ils manquent), et l’`addedAt`
de chaque élément et de chaque personne (celui d’un élément est l’`updatedAt` de la liste s’il manque, comme
l’envoie une version plus ancienne). Retirer un élément ou une personne d’une liste ajoute
`listitem:<id de liste>|<playlist>|<kind>|<item>` ou `listitem:<id de liste>|person:<nom>` à `deleted` (identifiants
de liste et de playlist en minuscules, le nom replié comme pour une personne).

**Personne** (un acteur ou un réalisateur gardé dans Ma liste, quelle que soit la playlist) — `{"name", "image"?,
"addedAt"}`. `image` est sa photo (une adresse web). Clé : `person:<name>` avec seulement les lettres A à Z en
minuscules (la même sur toutes les plateformes ; les autres lettres gardent leur casse) ; instant : `addedAt` (la
retirer ajoute la clé à `deleted`). Aucune plateforme ne garde d’identifiant de personne TMDb : une personne est son
nom. Un instantané sans `people` (d’un appareil qui n’en garde pas) n’en retire aucune : seul `deleted` le fait. Les
lecteurs replient de la même façon les noms des clés `person:` de `deleted`.

**Clé** (la clé ou le jeton d’un service extérieur, saisi dans les Réglages) — `{"service", "value", "updatedAt"}`.
`service` nomme le service : `tmdb` pour TMDb (son « API Read Access Token », ou son « API Key ») ; `value` est la
clé telle qu’enregistrée (sans espaces autour). Clé : `key:<service>` ; instant : `updatedAt` (fixé quand la clé est
enregistrée ; la retirer ajoute `key:<service>` à `deleted`). Une clé est vérifiée auprès de son service sur
l’appareil où elle est saisie ; les autres appareils l’utilisent telle qu’elle arrive, et leurs Réglages le disent
quand le service la refuse plus tard. Un appareil garde et transmet les clés des services qu’il ne connaît pas. Un
instantané sans `keys` (d’un appareil qui ne les synchronise pas) ne retire rien : seul `deleted` le fait.

**Supprimés** — la clé de chaque élément supprimé sur un appareil, avec l’instant de la suppression. Un appareil
garde chacune 180 jours, et plus longtemps tant que chaque appareil avec lequel il a eu une session dans l’année
n’en a pas eu une avec lui après la suppression (il retient, au jour près, sa dernière session avec chaque
appareil) : un appareil absent des mois reçoit quand même la suppression, et rien de supprimé ne revient par lui. Un
appareil qu’il n’a pas vu depuis un an ne retient rien. Une suppression apprise d’un autre appareil compte à partir
du moment où elle a été apprise, s’il est plus tardif que son propre instant.

**Horloge** — l’horloge de l’expéditeur quand il a envoyé l’instantané. Facultative : les versions plus anciennes
n’en envoient pas, et la réponse d’un serveur n’en a pas (elle est sur l’horloge du client, voir **Horloges**).

**Remplacés** — les identifiants de playlist devenus une autre playlist (règle 4 de la fusion) : l’ancien
identifiant, puis celui qui l’a remplacé. Un appareil garde une entrée aussi longtemps que la suppression de
l’ancienne playlist.

## Fusion

`merge(a, b)` donne le même résultat quel que soit l’ordre de `a` et `b`, et fusionner à nouveau le résultat ne
change rien.

1. `deleted` : chaque clé de l’un ou l’autre côté, avec l’instant le plus tardif. `replaced` : chaque entrée de
   l’un ou l’autre côté (en cas de conflit, le plus petit nouvel identifiant). Partout où un élément nomme une
   playlist, un identifiant présent dans `replaced` est remplacé par son nouvel identifiant, à nouveau tant que le
   résultat est dans `replaced` (10 fois au plus).
2. Pour chaque clé présente d’un côté ou de l’autre, l’élément à l’instant le plus tardif gagne. À instants égaux,
   l’élément dont la **chaîne de départage** est la plus petite (comparée en unités UTF-16, comme le font les
   chaînes Kotlin et Swift) gagne :
   - playlist : `name`, `kind`, `url`, `username`, `password`, `epgUrl`, `addedAt` (en décimal) joints par un saut de
     ligne, une valeur facultative écrite `0` quand elle manque et `1` suivi d’elle sinon ;
   - favori : `title`, `image` (facultative, comme ci-dessus) ;
   - progression : pas une chaîne d’abord : la plus grande `position` gagne, puis la plus grande `duration` ; puis
     `title`, `subtitle`, `image` (facultatives, comme ci-dessus) ;
   - personne : pas une chaîne d’abord : celle qui a une `image` gagne ; puis le plus petit `name`, puis la plus
     petite `image` ;
   - clé : `value`.
3. Un élément dont l’instant n’est pas postérieur à l’instant de suppression de sa clé est écarté (à instants
   égaux, la suppression gagne).
4. **La même playlist ajoutée deux fois** (sur deux appareils avant qu’ils se synchronisent) : les playlists de
   même `kind`, même `url` (sans espaces autour, comparée sans tenir compte de la casse) et même `username` sont
   une seule playlist. Celle au plus petit `addedAt` (puis au plus petit `id`) reste ; chaque autre est écartée et
   son `id` remplacé par celui gardé dans chaque favori, progression et élément de liste, et
   `playlist:<id écarté>` est ajouté à `deleted` avec l’`updatedAt` de la playlist écartée, et
   `<id écarté>` → `<id gardé>` à `replaced`. Les éléments qui ont alors la même clé sont fusionnés à nouveau par la
   règle 2.
5. Un favori, une progression ou un élément de liste dont la playlist est supprimée (`playlist:<id>` est dans
   `deleted` et aucune playlist gardée n’a cet identifiant) est écarté. Celui dont la playlist est simplement
   inconnue (une playlist fichier d’un autre appareil) est gardé.
6. Le résultat range les playlists par `addedAt` puis `id`, les favoris par `addedAt` puis clé, les progressions
   par `watchedAt` (la plus récente d’abord) puis clé, les listes par `id`, les personnes par `addedAt` puis clé, et les clés par
   `service`.
7. **Les listes se fusionnent partie par partie.** Une liste reste sauf si `list:<id>` est supprimé au plus tôt à
   son `updatedAt` le plus tardif, qu’elle garde. Son nom est celui au `renamedAt` le plus tardif (puis le plus petit
   nom). Ses éléments sont ceux de l’un ou l’autre côté, un par clé, l’`addedAt` le plus tardif gagnant (puis le plus
   petite chaîne de départage de `title` et `image`, facultative comme à la règle 2), sans ceux dont l’`addedAt` n’est pas postérieur à leur suppression
   `listitem:` ; ses personnes de même (l’`addedAt` le plus tardif, puis comme la règle 2 pour une personne), rangées
   par `addedAt` puis clé. L’ordre est l’`order` (clés d’éléments) du côté à l’`orderedAt` le plus tardif (puis la
   plus petite ligne de ces clés jointes par un saut de ligne ; une liste sans `order` a l’ordre de ses `items`),
   écrit tel quel dans le résultat : le résultat ne dépend alors pas de l’ordre des fusions. Les éléments le suivent ;
   ceux qui n’y sont pas viennent après, par `addedAt` puis clé. Le résultat écrit toujours `renamedAt`, `orderedAt`,
   `order` et chaque `addedAt`. Une version plus ancienne garde
   encore une liste entière comme un seul élément et n’envoie pas de suppression `listitem:` : un élément qu’elle a
   retiré revient depuis un appareil qui l’a encore.

Un appareil garde l’instantané fusionné comme le sien : il ajoute les playlists qu’il n’avait pas (et les
télécharge), met à jour celles qui ont changé, supprime celles qui ont été supprimées, et remplace Ma liste, les
chaînes favorites, les personnes, les listes et la progression par celles fusionnées, et enregistre les clés des services qu’il
utilise (en retirant celles supprimées). Il garde tout ce qui n’est pas synchronisé. La progression d’un film ou d’un épisode en lecture sur
l’appareil n’est pas remplacée pendant la lecture : celle reçue attend, et s’applique à la fin de la lecture si elle
est la plus tardive. Si le spectateur a changé quelque chose pendant la session, l’appareil fusionne à nouveau avec son
état actuel, donc aucun changement n’est perdu.

## Horloges

Les instants sont l’horloge de chaque appareil. Un boîtier TV peut avoir une horloge fausse tant qu’il n’a pas
joint un serveur de temps : un appareil qui reçoit un instantané met donc d’abord ses instants sur sa propre horloge
(`onReceiverClock`) :

1. Quand l’instantané a une `clock` à plus de 5 minutes de celle du destinataire, chacun de ses instants est décalé
   de la différence. Un serveur répond sur l’horloge du client : il décale les instants de sa réponse en sens inverse
   de la différence qu’il a mesurée, et n’envoie pas de `clock` ; le client ne décale rien. Une session ne déplace
   ainsi aucun instant, quelle que soit sa durée. Un instant que le décalage
   porterait au-delà de l’horloge du destinataire plus 5 minutes alors qu’il n’y était pas garde sa valeur : il a été
   fixé avant que l’horloge de l’expéditeur se dérègle.
2. Puis chaque élément et chaque suppression encore daté de plus de 5 minutes après l’horloge du destinataire est
   écarté : une horloge en avance ne peut pas dater une modification dans le futur et gagner sur toutes les
   suivantes (un instantané d’une version plus ancienne, sans `clock`, ne passe que par cette étape). Ces éléments
   sont écartés, pas déplacés : l’appareil qui les a faits les garde, ils voyagent une fois leur heure venue, et rien
   n’est supprimé parce qu’il manque.

Un appareil envoie ses propres instants postérieurs à son horloge plus 5 minutes (fixés quand son horloge avançait) à
son horloge : un tel changement voyage tout de suite, et perd face à un changement fait ailleurs plus tard. Un
élément n’est remplacé que par un autre à l’instant strictement postérieur, donc les éléments d’un appareil ne
reviennent jamais à une version plus ancienne.

## Confidentialité

Les instantanés contiennent en clair les identifiants et mots de passe des playlists, et les clés des services
extérieurs (le jeton TMDb), le réseau de confiance ayant été préféré au chiffrement. Ils ne circulent qu’entre appareils Ondo dont la synchronisation est active, sur le
réseau local ; Ondo ne les envoie nulle part ailleurs. Désactiver la synchronisation arrête aussitôt le serveur,
l’annonce et la recherche.
