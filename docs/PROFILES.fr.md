# Profils

Plusieurs personnes peuvent partager un même Ondo : chacune a un profil avec son historique, ses listes et ses
catégories masquées, tandis que les playlists et les réglages de l’appareil restent communs. Cette note décrit la
conception que suivent les deux plateformes. Le code Apple est dans
`Packages/IPTVCore/Sources/IPTVCore/Models/Library.swift` (`Profile`) et `App/Model/AppModel.swift` ; le code
Android dans `android/core/src/main/kotlin/com/markedoe/ondo/core/model/Profile.kt` et
`android/app/src/main/kotlin/com/markedoe/ondo/data/`.

## Modèle de données

Un profil est un enregistrement, le même sur les deux plateformes :

| Champ        | Sens                                                                                             |
|--------------|--------------------------------------------------------------------------------------------------|
| `id`         | Un UUID, créé une fois, jamais modifié. Celui du profil principal est `00000000-0000-0000-0000-000000000001`. |
| `name`       | Ce qu’affiche le sélecteur.                                                                      |
| `colorIndex` | Indice dans la palette commune (indigo, rose, orange, sarcelle, vert, violet, bleu, rouge).       |
| `symbol`     | Ce qu’affiche l’avatar rond : `initial` (la première lettre du nom), un des noms d’icône communs (`person.fill`, `face.smiling`, `star.fill`, `heart.fill`, `gamecontroller.fill`, `film.fill`, `pawprint.fill`, `leaf.fill`) ou un emoji. Toute autre valeur affiche l’initiale. |
| `isKids`     | Un profil Enfants (voir plus bas).                                                               |
| `pinHash`    | L’empreinte du code, ou rien quand le profil n’a pas de code (voir plus bas).                    |

La liste des profils est le document `profiles` (`profiles.json` sur Android). Deux réglages de l’appareil
l’accompagnent : `activeProfileID` (le profil ouvert) et `launchProfileID` (le profil coché « Toujours ouvrir ce
profil », ou rien).

### Ce qui est propre à chaque profil, et ce qui est commun

Chaque profil a son propre document de bibliothèque : `library` pour le profil principal (le nom qu’il a toujours
eu), `library-<id>` pour les autres. Ce document contient tout ce qui est propre au profil :

- ce qui a été vu, l’historique, les positions de lecture, donc « Reprendre », les marques « vu » et « Masquer les
  films vus » ;
- Ma liste, les acteurs et réalisateurs suivis, les collections ;
- les rappels de programmes ;
- les catégories masquées, les langues et pays masqués, les styles masqués ;
- l’ordre des rangées des onglets Accueil, Films et Séries (Réglages → Ordre des rangées), et les présélections
  (Réglages → Mes présélections) ; un profil Enfant ne peut pas changer les siens.

Communs à tous les profils : les listes et collections partagées avec tous les profils (voir « Listes partagées »), les playlists, le jeton TMDb et les autres clés de services, les réglages de lecture
(mémoire tampon, image, sous-titres, langues préférées), les réglages de synchronisation, « Afficher les catégories
adultes », les verrous de catégories (Apple).

## Listes partagées

Une liste ou une collection peut être partagée avec tous les profils : sa page et son menu ont un interrupteur
« Partagée avec tous les profils » (affiché dès qu’il y a deux profils ou plus).

- Une liste partagée n’est dans la bibliothèque d’aucun profil : elle vit dans un espace du foyer à part, la même liste
  pour tous les profils, et elle apparaît dans les listes de chaque profil marquée d’une petite icône « partagée » (deux
  personnes).
- Tout profil qui n’est pas un profil Enfant peut y ajouter ou en retirer des éléments, la renommer, la remettre en
  ordre, la reprendre ou la supprimer. Supprimer demande d’abord, en disant que la liste est supprimée pour tous les
  profils.
- Un profil Enfant voit les listes partagées, sans les éléments que ses règles masquent (catégories adultes, ses
  catégories masquées, et sur Apple celles verrouillées), et ne peut pas les modifier : ni interrupteur, ni renommage,
  ni suppression, ni ajout ou retrait, ni remise en ordre.
- Désactiver l’interrupteur reprend la liste dans le profil qui le désactive : les autres profils la perdent, et une
  confirmation le dit d’abord.
- Partager et reprendre déplacent la liste entière, sous le même ID, avec ses éléments et leurs instants : rien n’est
  perdu. La synchronisation transporte le déplacement comme une marque datée du départ de l’espace que la liste quitte et
  la liste, datée plus tard, dans l’espace où elle entre (docs/SYNC.fr.md, « Listes partagées »). Les appareils
  s’accordent ; un appareil qui a encore la liste là où elle était la fusionne dans la liste là où elle est allée,
  modifications comprises, et n’en fait pas revenir une deuxième copie.
- Un appareil d’une version antérieure ne peut pas afficher les listes partagées : il garde une liste partagée ailleurs
  comme la liste du profil qu’elle était et, entre deux appareils qui partagent des listes, ne leur fait jamais perdre une
  liste.
- Supprimer un profil laisse les listes qu’il a partagées : elles appartiennent à tous les profils.

Chaque appareil garde les listes partagées dans un document à part : `household.json` sur Android (les listes, ce qui a
été supprimé parmi elles, et quand cet appareil a appris chaque suppression), `library-household` sur les appareils
Apple (les listes), avec ce qui a été supprimé parmi elles dans `sync.json` sous `household`.

## Migration

Rien n’est déplacé ni renommé. Un appareil sans document `profiles` a un seul profil principal (« Moi ») dont la
bibliothèque est le document `library` existant (le document `profiles` est écrit dès qu’un profil est ajouté ou
modifié) : tout ce qui a été vu, gardé et masqué avant
appartient au profil principal. Les réglages sont lus comme avant ; sans `activeProfileID`, le profil principal
s’ouvre. L’ordre des rangées enregistré avant que chaque profil ait le sien devient celui du profil principal, avec
son instant (sur Android, il passe des réglages à la bibliothèque principale ; sur Apple, l’enregistrement de
l’appareil dans `sync.json` est celui du profil principal). Un profil créé avant que les profils se synchronisent reçoit
un instant au lancement suivant, pour voyager.

Les catégories, langues et styles masqués de chaque profil sont datés une fois, au premier lancement de la version qui
les synchronise, d’un instant postérieur à tout ce que l’appareil détient (sur Apple, leurs dates antérieures et les
instants où certains ont été réaffichés compris ; Android ne gardait pas d’instants). Ainsi, à la première session de deux
appareils mis à jour qui ont chacun masqué des choses différentes dans un profil, aucun masquage de l’un ne peut être plus
ancien qu’un réaffichage de l’autre : chaque profil finit avec les ensembles masqués des deux appareils, rien n’est
réaffiché, et un changement ultérieur sur l’un ou l’autre gagne ensuite comme d’habitude. Apple note cette datation dans
`sync.json` (`hiddenDated`), Android date tout élément masqué sans instant. Des tests sur les deux plateformes ouvrent des
données écrites par une version précédente et vérifient que rien n’est perdu.

## Ouverture et changement de profil

- Un avatar rond en haut à droite, à côté des Réglages (Android TV, téléphones et tablettes Android, iPhone, iPad,
  Mac), ouvre le sélecteur : changer de profil, ajouter, renommer, supprimer, changer la couleur et l’avatar.
- Avec deux profils ou plus, Ondo s’ouvre sur « Qui regarde ? », sauf si un profil est coché « Toujours ouvrir ce
  profil » : ce profil s’ouvre alors directement. La case se change dans le sélecteur.
- Changer de profil enregistre d’abord le profil quitté, puis lit la bibliothèque de l’autre. Un enregistrement
  prévu pour un profil va toujours dans le document de ce profil, même si un autre profil s’ouvre avant qu’il soit
  écrit.
- Le profil principal ne peut pas être supprimé : c’est celui dont les données datent d’avant les profils, et
  celui auquel est associé un appareil plus ancien sans profils (voir « Synchronisation »). Tout autre profil peut
  être supprimé avec sa bibliothèque ; le dernier profil restant ne peut pas l’être.
- Supprimer une playlist retire ses entrées de la bibliothèque de chaque profil, et ses rappels.
- Supprimer un profil demande confirmation : cela supprime son historique, Ma liste et ses collections sur tous les
  appareils du spectateur.
- Au plus 16 profils : autant que la synchronisation en transporte. À 16, on ne peut plus en ajouter ; les profils créés
  à part au-delà de 16 (sur plusieurs appareils avant leur synchronisation) restent sur leur appareil.
- Une lecture appartient au profil où elle a commencé : ses positions et marques « vu » vont dans la bibliothèque de ce
  profil, même si un autre profil s’ouvre entre-temps (image dans l’image, autre fenêtre), et nulle part si ce profil
  est supprimé pendant la lecture.

## Code

Un profil peut avoir un code de 4 à 8 chiffres, demandé quand on passe sur ce profil.

- Seule une empreinte est gardée : le SHA-256 de `ondo:` suivi du code, dans le document `profiles` de l’appareil.
- Après cinq codes faux d’affilée, chaque essai attend : 30 secondes, puis le double, jusqu’à une heure, conservé
  d’un lancement à l’autre, pour qu’un code à 4 chiffres ne puisse pas être trouvé en les essayant tous sur
  l’appareil.
- **Un code ne quitte jamais l’appareil.** Un code à 4 chiffres a 10 000 valeurs : quel que soit le sel, qui reçoit
  son empreinte peut toutes les essayer en une fraction de seconde, hors des attentes de l’appareil. La
  synchronisation ne transporte donc jamais `pinHash`, salé ou non ; chaque appareil protège ses profils avec son
  propre code. Un profil arrive sans code sur un appareil où aucun n’a été défini, et rien d’un code ne voyage. C’est
  un choix délibéré : un code protège un écran, ce n’est pas le mot de passe d’un compte.

## Profils Enfants

Un profil Enfants ne voit que ce qui est permis :

- les catégories qui semblent adultes ne sont jamais affichées (chaînes en direct, films, séries, accueil,
  recherche), quoi que dise « Afficher les catégories adultes » ;
- sur Apple, les catégories verrouillées par le contrôle parental ne sont pas affichées non plus ;
- les catégories et langues masquées du profil restent masquées ;
- les réglages qui pourraient affaiblir cela (Réglages, les catégories, ajouter une playlist, ajouter, modifier ou
  supprimer un profil) demandent d’abord un parent : le code parental sur Apple, le code d’un profil qui n’est pas
  un profil Enfants sur Android. Un appareil où aucun parent ne peut déverrouiller (aucun code) les laisse ouverts :
  sinon personne ne pourrait jamais quitter un profil Enfants.

Un profil qui n’est plus Enfant sur un autre appareil reste Enfant sur un appareil où un parent a défini un code (le
code parental sur Apple, le code d’un profil qui n’est pas un profil Enfant sur Android), comme ce code reste lui aussi
sur son appareil ; devenu Enfant ailleurs, il devient Enfant partout.

Android n’a pas de verrous de catégories ; sur Android, « permis » veut dire : qui ne semble pas adulte et qui n’est
pas masqué dans le profil Enfants. Cette différence est connue et assumée : ajouter des verrous à Android est un
changement à part.

## Synchronisation

Chaque profil se synchronise, quel que soit le profil ouvert (docs/SYNC.fr.md, « Profils ») :

- chaque profil est un enregistrement identifié par son `id`, la modification la plus récente l’emportant ; ses
  données voyagent dans `profileData` sous le même `id`, donc ce qui est vu sur un profil n’est jamais appliqué à un
  autre ;
- supprimer un profil laisse une pierre tombale datée (`profile:<id>` dans `deleted`) : le profil et ses données ne
  reviennent pas, et les autres profils ne sont pas touchés ;
- `pinHash` n’est jamais dans un enregistrement (voir « Code »), ni « Toujours ouvrir ce profil » : les deux restent
  sur l’appareil ;
- un appareil plus ancien qui ne connaît pas les profils lit et écrit les données du profil principal, comme avant ;
  il ignore les nouveaux champs, et la version du format ne change pas ;
- les catégories, langues et styles masqués de chaque profil voyagent dans ses données, chacun fusionné seul : masquer
  sur un appareil masque dans ce profil sur les autres, réafficher réaffiche, et le changement le plus récent gagne ; un
  profil Enfant ne peut pas changer les siens (un parent le peut, et les modèles des applications le refusent aussi, pas
  seulement les écrans) ; sur un appareil où un parent a mis un code, ce qu’un profil Enfant y masque reste masqué quand un
  autre appareil le réaffiche (un masquage reçu s’applique toujours), comme son interrupteur Enfant reste activé ; et les
  catégories qui semblent pour adultes y restent masquées quoi qu’apporte la synchronisation (`fixtures/sync/hidden-*.json`) ;
- la suppression d’un profil perd face à ses données modifiées plus tard sur un appareil qui n’en savait rien : le
  profil revient avec ces données (`fixtures/sync/profiles-late-*.json`) ;
- la première fois qu’un appareil reçoit des profils, un de ses profils créé à part (avant que les profils se
  synchronisent) ne fait qu’un avec un profil reçu du même nom, quels que soient sa casse et ses accents, et du même
  réglage Enfant : le plus petit ID est gardé sur les deux appareils, l’autre est supprimé, et ses données vont dans
  celui gardé ;
- les listes partagées avec tous les profils voyagent dans `household`, à part des données de chaque profil ; un
  déplacement entre un profil et le foyer est une marque datée là où était la liste et la liste, datée plus tard, là
  où elle est allée (`fixtures/sync/shared-lists-*.json`) ; un appareil plus ancien entre deux plus récents ne fait perdre
  aucune liste (`fixtures/sync/interop/old-relay-*`) ;
- les cœurs Kotlin et Swift partagent les mêmes fichiers d’exemple (`fixtures/sync/profiles-*.json`) et les mêmes
  tests, et chacun fusionne ce que l’autre a écrit (`fixtures/sync/interop/`).

Ce que chaque appareil garde pour la synchronisation : Android garde les suppressions de chaque profil dans le document
de bibliothèque de ce profil, celles du profil principal avec les sessions ; Apple les garde dans `sync.json`, celles du
profil principal comme avant et celles de chaque autre profil sous son ID. Ce qui voyage est identique.
