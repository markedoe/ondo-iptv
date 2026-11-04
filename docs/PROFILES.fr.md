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
- l’ordre des rangées de l’accueil, dès qu’il existera : il vit dans le document de bibliothèque, donc il est
  propre à chaque profil sans autre changement.

Communs à tous les profils : les playlists, le jeton TMDb et les autres clés de services, les réglages de lecture
(mémoire tampon, image, sous-titres, langues préférées), les réglages de synchronisation, « Afficher les catégories
adultes », les verrous de catégories (Apple).

## Migration

Rien n’est déplacé ni renommé. Un appareil sans document `profiles` a un seul profil principal (« Moi ») dont la
bibliothèque est le document `library` existant (le document `profiles` est écrit dès qu’un profil est ajouté ou
modifié) : tout ce qui a été vu, gardé et masqué avant
appartient au profil principal. Les réglages sont lus comme avant ; sans `activeProfileID`, le profil principal
s’ouvre. Des tests sur les deux plateformes ouvrent des données écrites par une version précédente et vérifient que
rien n’est perdu.

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
- Supprimer une playlist retire ses entrées de la bibliothèque de chaque profil.

## Code

Un profil peut avoir un code de 4 à 8 chiffres, demandé quand on passe sur ce profil.

- Seule une empreinte est gardée : le SHA-256 de `ondo:` suivi du code, dans le document `profiles` de l’appareil.
- Après cinq codes faux d’affilée, chaque essai attend : 30 secondes, puis le double, jusqu’à une heure, conservé
  d’un lancement à l’autre, pour qu’un code à 4 chiffres ne puisse pas être trouvé en les essayant tous sur
  l’appareil.
- **Un code ne quitte jamais l’appareil.** Un code à 4 chiffres a 10 000 valeurs : quel que soit le sel, qui reçoit
  son empreinte peut toutes les essayer en une fraction de seconde, hors des attentes de l’appareil. La
  synchronisation ne transporte donc jamais `pinHash`, salé ou non ; chaque appareil protège ses profils avec son
  propre code. Quand les profils se synchroniseront (phase 2), un profil arrivera sans code sur un appareil où aucun
  n’a été défini ; au plus, une indication « a un code ailleurs » pourra voyager, pour que l’appareil propose d’en
  définir un. C’est un choix délibéré : un code protège un écran, ce n’est pas le mot de passe d’un compte.

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

Android n’a pas de verrous de catégories ; sur Android, « permis » veut dire : qui ne semble pas adulte et qui n’est
pas masqué dans le profil Enfants. Cette différence est connue et assumée : ajouter des verrous à Android est un
changement à part.

## Synchronisation

**Phase 1 (maintenant).** Comme sur Apple jusqu’ici, seul le profil principal se synchronise : tant qu’un autre
profil est ouvert, la synchronisation sur le réseau local est en pause, et les bibliothèques des autres profils
restent sur l’appareil. Le format de synchronisation ne change pas.

**Phase 2 (après l’arrivée de l’ordre des rangées).** Les profils et leurs données se synchronisent entre appareils,
avec l’horloge logique hybride de `docs/SYNC.fr.md` :

- chaque profil est un enregistrement identifié par son `id`, le dernier qui écrit gagne ; ses données sont elles
  aussi identifiées par l’`id` du profil, donc ce qui est vu sur un profil n’est jamais appliqué à un autre ;
- supprimer un profil laisse une pierre tombale datée : le profil et ses données ne reviennent pas, et les autres
  profils ne sont pas touchés ;
- `pinHash` n’est jamais dans un enregistrement (voir « Code ») ;
- un appareil plus ancien qui ne connaît pas les profils lit et écrit les données du profil principal, comme
  aujourd’hui ; il ignore les nouveaux enregistrements, et la version du format ne change pas ;
- les cœurs Kotlin et Swift partagent les mêmes fichiers d’exemple et les mêmes tests.
