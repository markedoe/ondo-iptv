# Synchronisation sur le réseau local (version 1)

Les appareils Ondo d’un même réseau Wi-Fi ou filaire gardent leurs playlists, Ma liste, leurs listes et la
progression de lecture en phase tant que l’app y est ouverte. Rien ne passe par internet : les appareils se
trouvent avec Bonjour (DNS-SD) et se parlent directement, et seulement à des adresses du réseau local. Le réseau
est de confiance : il n’y a ni appairage ni chiffrement, donc la synchronisation reste désactivée tant que le
spectateur ne l’active pas, sur chaque appareil (Réglages → Synchroniser sur ce réseau).

Ce document est la spécification. L’implémentation Kotlin est dans
`android/core/src/main/kotlin/com/markedoe/ondo/core/sync/`, celle en Swift dans
`Packages/IPTVCore/Sources/IPTVCore/Sync/`. Les deux lisent les mêmes fichiers d’exemple dans `fixtures/sync/`, et
chacun fusionne les instantanés que l’autre a écrits dans `fixtures/sync/interop/` (voir son README).

## Rôles

- **Android** (TV, téléphone, tablette) est serveur : tant qu’Ondo est ouvert et la synchronisation active, il
  écoute sur un port TCP et l’annonce. Il se connecte aussi aux autres appareils Android qu’il trouve.
- **iPhone, iPad et Mac** sont seulement clients : ils se connectent aux appareils Android qu’ils trouvent. (L’app
  Mac ne peut pas accepter de connexions : l’édition App Store n’a pas l’entitlement `network.server`.) Deux
  appareils Apple se synchronisent donc via un appareil Android, pas directement entre eux.

## Ce qui est synchronisé et ce qui reste

Synchronisés : les playlists (nom, type, adresse, identifiant et mot de passe, adresse du guide), Ma liste et les
chaînes favorites, les acteurs et réalisateurs gardés dans Ma liste, les listes (collections) avec leurs personnes, la progression de lecture (où chaque film et épisode a été laissé, et
ce qui a été terminé), les clés de services extérieurs saisies dans les Réglages (le jeton TMDb, et les clés des services de sous-titres et
le compte OpenSubtitles avec l’interrupteur d’Addic7ed), l’ordre des
rangées des onglets Films et Séries avec les rangées masquées (Réglages → Ordre des rangées), et les profils (nom,
couleur, avatar, Enfant) avec, pour chaque profil, sa liste, ses chaînes favorites, ses personnes, ses listes, sa
progression et son ordre des rangées (docs/PROFILES.fr.md), et les listes partagées avec tous les profils.

Restent sur chaque appareil : les autres réglages, les catégories masquées, les modifications de chaînes, les rappels,
les codes des profils (jamais envoyés, salés ou non : voir docs/PROFILES.fr.md), « Toujours ouvrir ce profil », les
téléchargements, les catalogues eux-mêmes (chaque appareil télécharge ses playlists), et les playlists fichier (un
fichier importé sur un appareil n’est pas sur les autres).

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
entier en dernier à un serveur (toutes deux calculées sans ce qui est en lecture sur le client maintenant, donc un film
lancé depuis ne compte pas comme un changement), et que ce serveur a répondu avec une empreinte, le client envoie une
**sonde** à la place :
`{"type", "version", "device", "deviceName", "clock", "digest", "seen": <l’empreinte du serveur>, "probe": true}`,
sans éléments. Un serveur dont l’empreinte est toujours `seen` répond `{"type", "version", "device", "deviceName",
"digest", "unchanged": true}`, et rien n’est fusionné ; sinon il répond avec son propre instantané. Un client envoie
quand même son instantané entier à chaque serveur au moins une fois par heure, sauf pendant une lecture sur lui (il
envoie alors une sonde). Un
serveur plus ancien lit une sonde comme un instantané vide, la fusionne (ce qui ne retire rien) et répond en entier ;
un client plus ancien n’en envoie jamais. Une session où rien n’a changé d’un côté ni de l’autre fait ainsi quelques
centaines d’octets dans chaque sens.

**Quand.** Un client synchronise quand il trouve un appareil, puis toutes les 60 secondes tant que l’app est
ouverte, et quelques secondes après un changement local (une playlist, Ma liste, une liste, ou la fin d’une
lecture). Après une session ratée, il retrouve l’adresse de l’appareil et réessaie au bout de 15 secondes, puis
1 minute, puis toutes les 5 minutes tant que Bonjour trouve encore l’appareil. Pendant la lecture d’un film ou
d’un épisode sur le client, une session du rythme de 60 secondes qui enverrait un instantané de plus de 64 Kio
(pas une sonde) attend la suivante ; celle qui suit un changement part. Le moment où une session est due se mesure
sur une horloge qui ne recule jamais (le temps depuis le démarrage de l’appareil), pas sur l’horloge murale, qu’un
serveur de temps peut déplacer. Un changement compte quand il est fait sur
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
  "rows": [ … ],
  "profiles": [ … ],
  "profileData": [ … ],
  "household": { "lists": [ … ], "deleted": { … } },
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
`position` et `duration` sont des secondes (nombres). Un titre est terminé (vu) dès que la lecture atteint son
générique de fin, quand le fichier a un chapitre pour lui dans son dernier quart (nommé « Credits », « Générique »,
« Abspann », « Créditos »…) ; sinon à 95 % de sa durée, ou quand il en reste 10 minutes ou moins une fois les trois
quarts vus ; sans durée connue, jamais. Le début du générique ne se synchronise pas : chaque appareil garde ce que son
propre lecteur a lu dans le fichier (Apple : chapitres MP4 et MOV, chapitres MKV, VLC ; Android : chapitres MP4, que
Media3 lit, aucun pour le MKV). Un appareil qui n’a jamais lu le fichier en juge par la règle du temps. Clé :
`progress:<playlist>|<kind>|<item>|<épisode ou vide>` ; instant : `watchedAt`.

**Liste** — `{"id", "name", "items": [{"playlist", "kind", "item", "title", "image"?, "addedAt"?}],
"people": [<personne>], "updatedAt", "renamedAt"?, "orderedAt"?, "order"?, "placedAt"?}`. Clé : `list:<id>` ; instant : `updatedAt` (fixé
quand la liste est créée, renommée, remise en ordre, ou que ses éléments ou ses personnes changent). `id` est mis en
minuscules. `people` (les acteurs et réalisateurs de la liste) peut manquer : un appareil qui ne garde pas de
personnes n’en envoie aucune. Chaque partie a son propre instant (règle 7 de la fusion) : `renamedAt` quand le nom a
été fixé, `orderedAt` quand les éléments ont été mis en ordre (tous deux `updatedAt` s’ils manquent), et l’`addedAt`
de chaque élément et de chaque personne (celui d’un élément est l’`updatedAt` de la liste s’il manque, comme
l’envoie une version plus ancienne). Retirer un élément ou une personne d’une liste ajoute
`listitem:<id de liste>|<playlist>|<kind>|<item>` ou `listitem:<id de liste>|person:<nom>` à `deleted` (identifiants
de liste et de playlist en minuscules, le nom replié comme pour une personne). `placedAt` est l’instant où la liste a été
déplacée pour la dernière fois dans l’espace où elle est (voir **Listes partagées**) ; une liste jamais déplacée n’en a
pas, et il n’est écrit que s’il existe.

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

Les services de sous-titres recherchés depuis le lecteur se synchronisent de même, un élément par champ, chacun
fusionné seul (le dernier enregistrement gagne, un retrait postérieur l’efface sur tous les appareils) :
`opensubtitles` (la clé d’API d’OpenSubtitles), `opensubtitles-username` et `opensubtitles-password` (le compte
facultatif), `subdl` et `subsource` (leurs clés d’API), et `addic7ed`, dont la `value` est `on` ou `off` (désactiver
Addic7ed est une valeur, jamais un retrait ; il n’y a pas d’élément tant qu’il n’a pas été basculé une fois). Un mot
de passe est gardé tel que saisi, espaces compris ; les lecteurs écartent une clé qui contient une espace ou un
caractère de contrôle, et un mot de passe qui contient un caractère de contrôle. Chaque appareil les garde là où il
garde ces clés (le trousseau sur Apple, un fichier privé de l’app sur Android), jamais dans les réglages qu’il envoie,
et date une fois, à son démarrage, celles enregistrées avant qu’elles se synchronisent. Un enregistrement date tous les
champs du service d’un même instant (la clé et le compte circulent ensemble, jamais appariés à ceux d’un autre
appareil), quel que soit le champ modifié. Un appareil qui, avant cette version, gardait ces éléments parmi les clés
qu’il ne fait que transmettre les déplace, une fois, là où il les garde, à son démarrage. Ces clés, comme le jeton
TMDb, sont celles de tous les profils : un retrait va dans `deleted` (celui de l’instantané), quel que soit le profil
ouvert. Seul ce que l’utilisateur a
saisi circule : jamais la session qu’OpenSubtitles donne après une connexion. Un appareil laisse tel quel un service
dont la clé est en cours de vérification jusqu’à la fin de la vérification ; la clé alors enregistrée est datée
après ce que la synchronisation a apporté. Un appareil d’une version antérieure les garde et les transmet comme les
clés de services qu’il ne connaît pas.

**Rangées** (l’ordre des rangées d’un onglet, Réglages → Ordre des rangées) — `{"tab", "order", "hidden",
"updatedAt"}`. `tab` vaut `movie` ou `series` ; `order` liste les rangées de l’onglet par clé, dans l’ordre choisi ;
`hidden` les clés des rangées masquées. La clé d’une rangée est `all` (Tous les films ou Toutes les séries), `continue`
(Continuer à regarder), `mylist`, `new` (nouveaux films ou séries), `trending`, `awards` (films primés, Films
seulement), ou `cat:<id de la catégorie>|<nom>` pour une catégorie du fournisseur : son identifiant tel que la playlist
le donne (`movie-12`, sans caractères de contrôle ni `|`), puis son nom sans espaces autour, chaque suite d’espaces et
de caractères de contrôle réduite à une espace, A à Z en minuscules (les autres lettres gardent leur casse). Un
appareil retrouve une catégorie par la même clé, sinon par son identifiant, sinon par son nom : une catégorie que le
fournisseur renumérote ou renomme garde sa place. Les rangées que l’onglet a mais qu’`order` ne nomme pas viennent
après, dans leur ordre (une catégorie nouvelle dans la playlist va à la fin) ; les clés qu’aucune rangée n’a (une
catégorie partie de la playlist, ou d’une autre playlist) sont gardées, après la rangée qu’elles suivaient, pour son
retour. Les catégories et langues masquées le restent, quel que soit l’ordre. Clé : `rows:<tab>` ; instant :
`updatedAt` (fixé chaque fois qu’une rangée est déplacée, masquée ou affichée, ou que l’ordre par défaut est choisi,
ce qui écrit un `order` vide et garde `hidden`). Tout l’enregistrement est un seul changement : une rangée déplacée sur
un appareil et une autre masquée sur un autre au même moment ne gardent que le changement le plus tardif. Les lecteurs
écartent les clés vides ou qui contiennent un caractère de contrôle (sous U+0020, ou U+007F), gardent la première des
clés répétées dans `order`, et gardent `hidden` une fois chacune, triées (en unités UTF-16). Ils gardent ensuite au
plus 2000 clés dans `order` (les premières) et dans `hidden` (les premières une fois triées), écartent les clés et les
onglets de plus de 256 unités UTF-16, et gardent les enregistrements de 8 onglets au plus : `movie` et `series`, puis
les autres dans l’ordre UTF-16 (une fusion garde les mêmes 8). Un appareil garde et
transmet l’enregistrement d’un onglet qu’il ne connaît pas. Un instantané sans `rows` (d’une version plus ancienne) ne
change rien ; aucun enregistrement n’est jamais supprimé. Un appareil Android plus ancien écarte `rows` de ce qu’il
garde et transmet : deux appareils Apple qui ne se synchronisent qu’à travers lui gardent chacun leur ordre. Chaque
profil a son ordre : celui du profil principal est `rows`, celui d’un autre profil est dans son `profileData`. L’ordre
qu’un appareil gardait avant que chaque profil ait le sien devient celui du profil principal, avec son instant. Un
profil Enfant ne peut pas changer son ordre.

**Profils** (docs/PROFILES.fr.md) — `{"id", "name", "colorIndex", "symbol", "kids", "updatedAt"}`. `id` est un UUID
en minuscules ; celui du profil principal est `00000000-0000-0000-0000-000000000001`. `symbol` vaut `initial`, un nom
d’icône ou un emoji. Jamais de code : le code d’un profil reste sur son appareil. Clé : `profile:<id>` ; instant :
`updatedAt` (fixé quand le profil est ajouté, renommé, ou que sa couleur, son avatar ou son réglage Enfant change ; un
nouveau code seul n’est pas un changement). Un profil jamais modifié (le profil principal tel que chaque appareil le
crée, « Moi ») n’est pas envoyé. Supprimer un profil ajoute `profile:<id>` à `deleted` ; le profil principal n’est
jamais supprimé (une suppression de celui-ci est ignorée). Les lecteurs gardent au plus 16 profils : le principal,
puis par ID.

**Données de profil** — `{"profile", "favorites"?, "progress"?, "lists"?, "people"?, "rows"?, "deleted"?}` : les
éléments d’un profil autre que le principal, selon les mêmes règles que ceux de l’instantané lui-même, avec ce qui a
été supprimé dans ce profil. Les éléments du profil principal sont ceux de l’instantané lui-même : un appareil plus
ancien, qui ne connaît pas les profils, lit et écrit les données du profil principal, ignore `profiles` et
`profileData`, et la version du format reste 1. Les données envoyées pour le profil principal dans `profileData` sont
ignorées. Les profils ne passent pas par un appareil plus ancien : deux appareils qui ne se rencontrent qu’à travers
lui ne partagent que les données du profil principal. Les suppressions de chaque profil apprises d’un autre appareil
sont gardées à partir du moment où elles ont été apprises, comme celles du profil principal (voir **Supprimés**).

Deux choix restent à chaque appareil (docs/PROFILES.fr.md) : un profil qui n’est plus Enfant ailleurs reste Enfant sur
un appareil où un parent a défini un code, tout en prenant l’instant de l’enregistrement (son propre instantané dit
alors Enfant à cet instant, la fusion garde le choix de l’autre appareil, et rien ne change d’une session à l’autre ;
une modification ultérieure de ce profil sur l’appareil protégé le date de nouveau, et renvoie Enfant à tous les
appareils) ;
et, la première fois qu’un appareil reçoit des profils, un de ses profils créé à part ne fait qu’un avec un profil reçu
du même nom (sans espaces autour, quelles que soient sa casse et ses accents) et du même réglage Enfant : le plus petit
ID est gardé sur les deux appareils, l’autre est supprimé (daté), et ses données vont dans celui gardé.

**Listes partagées** (docs/PROFILES.fr.md, « Listes partagées ») — `household` : `{"lists"?, "deleted"?}`, les listes
partagées avec tous les profils et ce qui a été supprimé parmi elles (clés `listitem:<id>|…`, et `list:<id>` pour une
liste supprimée pour tous les profils), fusionnées selon les mêmes règles que les listes d’un profil (règles 2 à 7, comme
un espace à part dont les playlists sont celles fusionnées) et selon la règle 9. Absent quand il n’y en a pas : un
instantané sans liste partagée s’écrit, et se résume (digest), comme avant. Une liste partagée n’est dans les données
d’aucun profil : elle est dans le foyer, la même liste pour tous les profils.

Deux clés du `deleted` de l’instantané lui-même (celui du profil principal, que toutes les versions gardent et
transmettent, quelle que soit la clé) notent ce qui arrive aux listes partagées :

- `listmoved:<espace>|<id>` : la liste `id` a quitté `espace` (`household`, ou l’ID d’un profil, y compris celui du
  profil principal, `00000000-0000-0000-0000-000000000001`) à cet instant. Ce n’est jamais `list:<id>` : un appareil plus
  ancien appliquerait cette suppression à sa copie et la transmettrait.
- `sharedlistdeleted:<id>` : la liste partagée `id` a été supprimée pour tous les profils à cet instant.

Ce que fait chaque changement :

- **Partager** une liste d’un profil (le principal ou un autre) la déplace, entière et sous le même ID, à deux instants
  pris l’un après l’autre : `listmoved:<ce profil>|<id>` au premier, et le foyer l’a au second, comme son `updatedAt` et son
  `placedAt`. Les noms, éléments, personnes et instants de ses parties ne changent pas, et les suppressions de ses éléments
  (`listitem:<id>|…`) la suivent.
- **Reprendre une liste** (l’interrupteur désactivé, par tout profil qui peut modifier les listes partagées) la déplace
  dans l’autre sens, dans le profil qui la reprend : `listmoved:household|<id>`, puis ce profil l’a ensuite, avec
  `placedAt`. Les autres profils la perdent.
- **Supprimer** une liste partagée la supprime pour tous les profils : `sharedlistdeleted:<id>`, et `list:<id>` dans le
  `deleted` du foyer.
- Un profil Enfant voit les listes partagées (sans ce que ses règles masquent) et n’en modifie aucune.

La règle 9 de la fusion fait finir une liste dans un seul espace, et n’en perd jamais une qui n’a pas été supprimée. Un
appareil qui a encore la liste là où elle était la fusionne dans la liste là où elle est allée dès qu’il a les deux,
modifications comprises (faites avant ou après le déplacement). D’ici là, il la garde là où elle était.

**Appareils plus anciens.** Les listes partagées ne passent pas par un appareil plus ancien : il ignore `household` et
`placedAt`. Il garde et transmet les deux clés ci-dessus, et ne perd rien :

- Il garde sa propre copie d’une liste qu’un profil a partagée, comme une liste de ce profil : il ne peut pas afficher
  une liste partagée, et retirer la copie y perdrait la liste. Chaque appareil qui partage des listes fusionne de nouveau
  cette copie dans la liste partagée, modifications faites sur l’appareil plus ancien comprises, donc rien ne change d’une
  session à l’autre.
- Deux appareils qui partagent des listes et ne se rencontrent qu’à travers un appareil plus ancien (un iPhone et un iPad
  derrière un téléviseur Android pas encore mis à jour) ne perdent jamais une liste :
  - Une liste partagée sur l’un reste là où elle était sur l’autre (dans les listes de ce profil, non partagée) jusqu’à ce
    que les deux se rencontrent, ou rencontrent un autre appareil qui partage des listes. Elle est alors partagée, une seule
    fois, sur les deux.
  - Une liste reprise sur l’un va aussi dans ce profil sur l’autre : le déplacement hors du foyer rend sa copie partagée
    périmée (`fixtures/sync/interop/old-relay-*`).

Les suppressions du foyer apprises d’un autre appareil sont gardées à partir du moment où elles ont été apprises, comme
celles d’un profil (voir **Supprimés**).

**Supprimés** — la clé de chaque élément supprimé sur un appareil, avec l’instant de la suppression. Un appareil
garde chacune 180 jours, et plus longtemps tant que chaque appareil avec lequel il a eu une session dans l’année
n’en a pas eu une avec lui après la suppression (il retient, au jour près, sa dernière session avec chaque
appareil) : un appareil absent des mois reçoit quand même la suppression, et rien de supprimé ne revient par lui. Un
appareil qu’il n’a pas vu depuis un an ne retient rien. Une suppression apprise d’un autre appareil compte à partir
du moment où elle a été apprise, s’il est plus tardif que son propre instant.

**Horloge** — l’horloge murale de l’expéditeur quand il a envoyé l’instantané. Facultative, et ignorée par le
destinataire (voir **Horloges**) : les versions plus anciennes n’en envoient pas.

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
   - clé : `value` ;
   - rangées : le nombre de clés d’`order` en décimal, un saut de ligne, puis les clés d’`order` et de `hidden` jointes
     par un saut de ligne (aucune clé n’en contient).
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
   par `watchedAt` (la plus récente d’abord) puis clé, les listes par `id`, les personnes par `addedAt` puis clé, les clés par
   `service`, et les rangées par `tab`.
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
8. **Les profils** se fusionnent selon la règle 2 (chaîne de départage : `name`, `colorIndex`, `symbol`, `kids` en
   `1` ou `0`), la suppression du profil principal étant ignorée. La suppression d’un profil ne l’emporte qu’à
   l’instant le plus récent du profil ou après : son `updatedAt` et chaque instant de ses données (comme le contenu
   d’une liste, règle 7). Un appareil qui a continué à regarder dans un profil après qu’un autre l’a supprimé garde le
   profil, et le fait revenir sur l’autre. Un profil écarté (supprimé à cet instant ou après, ou au-delà des 16
   gardés) part avec ses données. Avec trois appareils, l’ordre des sessions décide si des données plus anciennes que la
   suppression reviennent avec le profil (un appareil qui rencontre d’abord celui qui l’a supprimé les retire), mais
   tous les appareils finissent dans le même état. Chaque profil gardé autre que le principal fusionne ses
   données selon les règles 2 à 7, comme un instantané à part dont les playlists sont celles fusionnées : un favori ou
   une progression d’une playlist supprimée part, ceux d’une playlist remplacée deviennent ceux de la playlist gardée,
   et un élément supprimé dans ce profil (son `deleted`) part. Le résultat liste les profils et leurs données par ID.
9. **Une liste est dans un seul espace.** Les espaces sont le foyer (les listes partagées), le profil principal (les
   listes de l’instantané lui-même) et chaque profil gardé autre que le principal. Cette règle s’applique à une liste
   trouvée dans deux espaces ou plus (déplacée sur un appareil pendant qu’un autre l’avait encore là où elle était), ou
   supprimée pour tous les profils. Une telle liste finit dans un seul espace :
   - **Copies supprimées.** Les copies d’un espace sont supprimées quand leur `updatedAt` le plus tardif n’est pas
     postérieur à la suppression `list:<id>` de cet espace, ni à la suppression de la liste pour tous les profils (la plus
     tardive de `sharedlistdeleted:<id>` et du `list:<id>` du foyer). Une liste supprimée pour tous les profils part ainsi
     de tous les profils, sauf si elle a été modifiée après la suppression (comme à la règle 7).
   - **Où elle va.** Parmi les espaces restants, ceux dont les copies sont postérieures au départ de la liste
     (`listmoved:<espace>|<id>`) la gardent. Celui où elle a été déplacée en dernier l’emporte : le `placedAt` le plus
     tardif de ses copies (0 sans), puis le foyer, le profil principal, les autres profils par ID. Quand la liste a quitté
     tous les espaces restants (la copie là où elle est allée n’est pas arrivée sur cet appareil), elle reste là où elle
     est, dans le même ordre : un déplacement ne perd jamais une liste.
   - **Ce qu’elle contient.** La liste y est faite de toutes les copies des espaces restants, fusionnées selon la règle 7,
     sans les éléments dont la suppression `listitem:`, dans n’importe quel espace, n’est pas antérieure à leur `addedAt`.
     Une modification faite là où était la liste, avant ou après un déplacement dont cet appareil n’avait pas entendu
     parler, est reprise, quel que soit l’ordre des fusions. Son `placedAt` est le plus tardif des leurs.
   - **Rien d’autre.** Aucun autre espace ne reçoit de suppression : un appareil plus ancien garde sa copie (voir
     **Listes partagées**).

Un appareil garde l’instantané fusionné comme le sien : il ajoute les playlists qu’il n’avait pas (et les
télécharge), met à jour celles qui ont changé, supprime celles qui ont été supprimées, et remplace Ma liste, les
chaînes favorites, les personnes, les listes (celles de chaque profil et celles partagées) et la progression par celles fusionnées, garde l’ordre des rangées fusionné, et enregistre les clés des services qu’il
utilise (en retirant celles supprimées). Il garde tout ce qui n’est pas synchronisé. La progression d’un film ou d’un épisode en lecture sur
l’appareil n’est pas remplacée pendant la lecture : celle reçue attend, et s’applique à la fin de la lecture si elle
est la plus tardive. Le catalogue n’est pas non plus rechargé sous le lecteur : quand la playlist utilisée a été
supprimée, remplacée, ou que son adresse ou son identifiant a changé, le catalogue suit à la fin de la lecture. Si le spectateur a changé quelque chose pendant la session, l’appareil fusionne à nouveau avec son
état actuel, donc aucun changement n’est perdu.

## Horloges

Chaque instant est donné par l’appareil qui fait le changement, avec une **horloge logique hybride** (`SyncClock`).
Un boîtier TV peut avoir une horloge fausse tant qu’il n’a pas joint un serveur de temps (à 1970, ou décalée de
plusieurs heures) : l’horloge murale seule ne peut donc pas ordonner des changements faits sur des appareils
différents. Chaque appareil garde `last`, le dernier instant qu’il a donné ou reçu (enregistré avec ses données de
synchronisation, et au lancement jamais antérieur à un instant qu’il détient) :

1. **Un changement fait sur l’appareil** (un favori ajouté ou retiré, une position, une liste modifiée, une playlist
   ou une clé enregistrée, toute suppression) est daté `max(horloge murale, last + 1)`, qui devient `last`. Un
   changement fait après que l’appareil en a vu un autre est donc daté après lui, quoi que dise son horloge murale :
   un retrait fait après un ajout gagne partout, et une position enregistrée après une position reçue gagne sur elle.
2. **Un instantané reçu** perd d’abord les éléments et suppressions datés de plus d’**un jour** après l’horloge
   murale du destinataire : un appareil très en avance ne peut pas dater ses changements dans le futur, gagner sur
   tous les suivants, ni entraîner les horloges des autres. Ils sont écartés, pas déplacés : l’appareil qui les a
   faits les garde, et ils voyagent avec un instantané entier une fois leur instant à moins d’un jour de l’horloge du
   destinataire : celui de chaque heure, ou le suivant après un changement d’un côté ou de l’autre (entre-temps, les
   sessions sont des sondes, qui ne portent aucun élément). Puis `last` devient le dernier instant de ce qui reste,
   s’il est plus tardif.
3. Un destinataire dont l’horloge murale indique une date antérieure à 2024 n’est pas réglé : il n’a aucune idée de
   l’heure. Il écarte ce qui est daté après le 1er janvier 2029 ou, si c’est plus tardif, plus de deux ans après
   `last` : une horloge qui indique 2100, ou un instant abîmé, n’y entraîne aucun appareil.

Une session de synchronisation ne change jamais un instant : un élément est gardé ou remplacé tel qu’il a été daté là
où il a été fait. La `clock` d’un instantané est ignorée. Les versions plus anciennes envoient les instants de leur
horloge murale et n’écartent rien, ce que les plus récentes lisent comme tout autre instant. « Maintenant », pour
élaguer les suppressions et noter les sessions et les suppressions apprises, est le plus tardif de l’horloge murale et
de `last`, mais jamais plus d’un jour après une horloge murale réglée, ni après le 1er janvier 2024 quand elle ne l’est
pas : un instant très en avance ne peut pas faire paraître toutes les suppressions anciennes et les élaguer d’un coup.

Limites : un appareil dont l’horloge avançait de plus d’un jour continue, une fois son horloge juste, à dater ses
changements après ses précédents ; les autres appareils ne les prennent donc qu’une fois cet instant à moins d’un
jour de leur horloge (une heure plus tard pour une horloge qui avançait d’un jour et une heure, un an plus tard pour
une horloge qui avançait d’un an). Un appareil dont l’horloge n’est pas réglée accepte un instant jusqu’à cette
limite, et date ensuite ses changements après lui. Et un appareil en avance de moins d’un jour entraîne les horloges
des autres : tant que leurs horloges murales ne l’ont pas rattrapé, chaque changement fait ailleurs est daté juste
après le dernier instant vu ; un appareil loin des autres pendant ce temps (un téléphone hors de la maison) date donc
ses changements avant ceux faits sur cet appareil après leur dernière session, et perd face à eux quel que soit
l’ordre réel. Sur un réseau domestique, où les appareils se synchronisent chaque minute, cette fenêtre est d’environ
une minute, mais elle dure tant que le téléphone est absent.

## Confidentialité

Les instantanés contiennent en clair les identifiants et mots de passe des playlists, et les clés des services
extérieurs (le jeton TMDb, les clés des services de sous-titres et le compte OpenSubtitles), le réseau de confiance ayant été préféré au chiffrement. Ils ne circulent qu’entre appareils Ondo dont la synchronisation est active, sur le
réseau local ; Ondo ne les envoie nulle part ailleurs. Désactiver la synchronisation arrête aussitôt le serveur,
l’annonce et la recherche.
