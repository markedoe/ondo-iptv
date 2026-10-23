# Exemples de synchronisation

Instantanés de la synchronisation sur le réseau local (`docs/SYNC.fr.md`), lus par les tests Kotlin
(`android/core`) et les tests Swift (`Packages/IPTVCore`) : fusionner `a.json` et `b.json`, dans un ordre ou dans
l’autre, doit donner `merged.json` (sauf `device` et `deviceName`, qui sont ceux de l’appareil qui fusionne). Ils
couvrent les identifiants mis en minuscules, le même compte Xtream ajouté sur deux appareils, un changement plus
récent qui gagne, des suppressions qui l’emportent sur des éléments plus anciens, les
éléments d’une playlist supprimée écartés (ceux d’une playlist inconnue gardés) et un champ inconnu ignoré.
