# Sync examples

Snapshots of the local-network sync (`docs/SYNC.md`), read by the Kotlin tests (`android/core`) and the Swift tests
(`Packages/IPTVCore`): merging `a.json` and `b.json`, in either order, must give `merged.json` (apart from `device`
and `deviceName`, which are the merging device's). They cover lowercased IDs, the same Xtream account added on two
devices, a later change winning, deletions winning over older records, the records of a deleted playlist being dropped
(those of an unknown one kept), an unknown field being ignored, and the keys of outside services (the later save
winning, a removal after a save dropping it, the key of an unknown service kept).

`rows-a.json` and `rows-b.json` merge into `rows-merged.json` the same way: the order of the rows of the Movies and Series
tabs, one record per tab, the later one winning, on equal times the smaller tie string; keys that are blank or hold a
control character dropped, the order without repeats, the hidden rows each once and sorted; the record of a tab this
version does not know kept.
