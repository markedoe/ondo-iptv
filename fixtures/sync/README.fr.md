# Exemples de synchronisation

Instantanés de la synchronisation sur le réseau local (`docs/SYNC.fr.md`), lus par les tests Kotlin
(`android/core`) et les tests Swift (`Packages/IPTVCore`) : fusionner `a.json` et `b.json`, dans un ordre ou dans
l’autre, doit donner `merged.json` (sauf `device` et `deviceName`, qui sont ceux de l’appareil qui fusionne). Ils
couvrent les identifiants mis en minuscules, le même compte Xtream ajouté sur deux appareils, un changement plus
récent qui gagne, des suppressions qui l’emportent sur des éléments plus anciens, les
éléments d’une playlist supprimée écartés (ceux d’une playlist inconnue gardés), un champ inconnu ignoré, et les
clés de services extérieurs (le dernier enregistrement qui gagne, une clé retirée après son enregistrement écartée,
la clé d’un service inconnu gardée).

`rows-a.json` et `rows-b.json` se fusionnent de même en `rows-merged.json` : l’ordre des rangées des onglets Accueil,
Films et Séries, un enregistrement par onglet, le plus récent qui gagne, à temps égaux la plus petite chaîne de départage ; les
clés vides ou qui contiennent un caractère de contrôle écartées, l’ordre sans doublons, les rangées masquées chacune une
fois et triées ; l’enregistrement d’un onglet que cette version ne connaît pas gardé.

`profiles-a.json` et `profiles-b.json` se fusionnent en `profiles-merged.json` de la même façon : les profils, la
modification la plus récente l’emportant (un renommage sur un appareil), un profil ajouté sur un appareil gardé avec ses
données, un profil supprimé sur l’autre retiré avec ses données, son ID en majuscules mis en minuscules ; les données
de chaque profil fusionnées selon les règles de l’instantané lui-même (la progression la plus récente l’emportant, un
favori supprimé dans ce profil retiré, un favori d’une playlist supprimée retiré) ; les données envoyées pour le profil
principal dans `profileData` ignorées (ses données sont celles de l’instantané lui-même).

`profiles-late-a.json` et `profiles-late-b.json` se fusionnent en `profiles-late-merged.json` : un profil supprimé sur un
appareil à 100 pendant qu’un autre, pas encore averti, y regardait encore à 200. Les données plus récentes l’emportent
sur la suppression, comme le contenu d’une liste (règle 7) : le profil et ses données restent.
