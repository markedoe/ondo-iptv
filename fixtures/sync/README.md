# Sync examples

Snapshots of the local-network sync (`docs/SYNC.md`), read by the Kotlin tests (`android/core`) and the Swift tests
(`Packages/IPTVCore`): merging `a.json` and `b.json`, in either order, must give `merged.json` (apart from `device`
and `deviceName`, which are the merging device's). They cover lowercased IDs, the same Xtream account added on two
devices, a later change winning, deletions winning over older records, the records of a deleted playlist being dropped
(those of an unknown one kept), an unknown field being ignored, and the keys of outside services (the later save
winning, a removal after a save dropping it, the key of an unknown service kept).

`keys-a.json` and `keys-b.json` merge into `keys-merged.json` the same way: the subtitle services' keys (docs/SYNC.md,
"Key"), each field on its own: a later save winning on either side, a removal after a save dropping it (the
OpenSubtitles username), one before a save not (SubSource), Addic7ed turned off later winning, a password with a space,
the key of an unknown service kept. The apps' tests also apply them, in turn, to a device's stored keys. All values are
fake.

`rows-a.json` and `rows-b.json` merge into `rows-merged.json` the same way: the order of the rows of the Movies and Series
tabs, one record per tab, the later one winning, on equal times the smaller tie string; keys that are blank or hold a
control character dropped, the order without repeats, the hidden rows each once and sorted; the record of a tab this
version does not know kept.

`profiles-a.json` and `profiles-b.json` merge into `profiles-merged.json` the same way: the profiles, the later change
winning (a rename on one device), a profile added on one device kept with its data, a profile deleted on the other
dropped with its data, its upper-case ID lowercased; each profile's data merged by the snapshot's own rules (a later
progress winning, a favourite deleted in that profile dropped, a favourite of a deleted playlist dropped); the data
sent for the main profile in `profileData` ignored (its data is the snapshot's own).

`profiles-late-a.json` and `profiles-late-b.json` merge into `profiles-late-merged.json`: a profile deleted on one device
at 100 while another, not yet told, kept watching in it at 200. The later data wins over the deletion, as a list's
content does (rule 7): the profile and its data stay.

`shared-lists-a.json` and `shared-lists-b.json` merge into `shared-lists-merged.json`: the lists shared with every profile
(`household`, rule 9). Sam shared Movie night on one device while the other, not told yet, added a movie to it in Sam's
lists: the list is shared once, with that movie, and gone from Sam's lists (the move is a `listmoved:` marker in the
top-level `deleted`, never a deletion of Sam's copy). A shared list renamed on one
device and an item taken out of it on the other keep both changes. A shared list deleted for every profile on one device
and not changed since on the other goes (`sharedlistdeleted:`). A shared list taken back into the main profile on one device while the other,
not told yet, added to it: the list is the main profile's, with that addition. IDs in upper case are lowercased.

`interop/` holds snapshots written by each core and merged by the other (see its README).
