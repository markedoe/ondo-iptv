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
chaînes favorites, les listes (collections), et la progression de lecture (où chaque film et épisode a été
laissé, et ce qui a été terminé).

Restent sur chaque appareil : les réglages, les catégories masquées, les modifications de chaînes, les rappels,
les profils, les téléchargements, les catalogues eux-mêmes (chaque appareil télécharge ses playlists), et les
playlists fichier (un fichier importé sur un appareil n’est pas sur les autres). Sur les appareils Apple, seul le
profil principal est synchronisé ; les profils enfants ne le sont pas.

## Découverte

Type de service `_ondo._tcp`. Un appareil Android dont la synchronisation est active annonce le nom de service
`Ondo-<8 premiers caractères de son identifiant>` avec les enregistrements TXT :

- `d` : son identifiant d’appareil, un UUID aléatoire créé une fois par installation (en minuscules) ;
- `v` : `1` ;
- `n` : un nom pour la liste des appareils (le modèle de l’appareil, 40 caractères au plus).

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

## Transport

TCP. Une **trame** est une longueur sur 4 octets (gros-boutiste) suivie d’autant d’octets de JSON en UTF-8. Une
trame de plus de 16 Mio est refusée et termine la session.

Une session est un seul échange : le client se connecte, envoie son instantané (une trame), le serveur le fusionne
avec le sien, garde le résultat et répond avec l’instantané fusionné (une trame) ; le client garde la réponse et
ferme. La connexion a 10 secondes et la session entière 30 secondes. Un serveur traite au plus 4 sessions à la
fois et ferme les connexions en plus.

Entre deux appareils Android, celui dont l’identifiant est le plus petit (comme chaîne) se connecte.

**Quand.** Un client synchronise quand il trouve un appareil, puis toutes les 60 secondes tant que l’app est
ouverte, et quelques secondes après un changement local (une playlist, Ma liste, une liste, ou la fin d’une
lecture). Après une session ratée, il réessaie avec cet appareil au bout de 15 secondes, puis 1 minute, puis
5 minutes, puis attend que l’appareil revienne ou qu’une session réussisse.

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
  "deleted": { "<clé>": 1760000000000 },
  "replaced": { "<id de playlist>": "<id de playlist>" }
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

**Liste** — `{"id", "name", "items": [{"playlist", "kind", "item", "title", "image"?}], "updatedAt"}`. Clé :
`list:<id>` ; instant : `updatedAt` (fixé quand la liste est créée, renommée ou que ses éléments changent). `id`
est mis en minuscules.

**Supprimés** — la clé de chaque élément supprimé sur un appareil, avec l’instant de la suppression. Un appareil
les garde 180 jours.

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
   - playlist : `name`, `url`, `username`, `password`, `epgUrl` joints par un saut de ligne (valeurs absentes
     vides) ;
   - favori : `title` ;
   - progression : pas de chaîne : la plus grande `position` gagne, puis le plus petit `title` ;
   - liste : `name`, puis la clé de chaque élément (`<playlist>|<kind>|<item>`) dans l’ordre, joints par un saut de
     ligne.
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
   par `watchedAt` (la plus récente d’abord) puis clé, et les listes par `id`.

Un appareil garde l’instantané fusionné comme le sien : il ajoute les playlists qu’il n’avait pas (et les
télécharge), met à jour celles qui ont changé, supprime celles qui ont été supprimées, et remplace Ma liste, les
chaînes favorites, les listes et la progression par celles fusionnées. Il garde tout ce qui n’est pas
synchronisé. Si le spectateur a changé quelque chose pendant la session, l’appareil fusionne à nouveau avec son
état actuel, donc aucun changement n’est perdu.

## Confidentialité

Les instantanés contiennent en clair les identifiants et mots de passe des playlists, le réseau de confiance ayant
été préféré au chiffrement. Ils ne circulent qu’entre appareils Ondo dont la synchronisation est active, sur le
réseau local ; Ondo ne les envoie nulle part ailleurs. Désactiver la synchronisation arrête aussitôt le serveur,
l’annonce et la recherche.
