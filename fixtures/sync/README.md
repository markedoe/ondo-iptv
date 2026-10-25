# Sync examples

Snapshots of the local-network sync (`docs/SYNC.md`), read by the Kotlin tests (`android/core`) and the Swift tests
(`Packages/IPTVCore`): merging `a.json` and `b.json`, in either order, must give `merged.json` (apart from `device`
and `deviceName`, which are the merging device's). They cover lowercased IDs, the same Xtream account added on two
devices, a later change winning, deletions winning over older records, the records of a deleted playlist being dropped
(those of an unknown one kept), an unknown field being ignored, and the keys of outside services (the later save
winning, a removal after a save dropping it, the key of an unknown service kept).
