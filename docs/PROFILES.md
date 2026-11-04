# Profiles

Several people can share one Ondo: each has a profile with their own history, lists and hidden categories, while
the playlists and the device's settings stay shared. This note is the design both platforms follow. The Apple code
is in `Packages/IPTVCore/Sources/IPTVCore/Models/Library.swift` (`Profile`) and `App/Model/AppModel.swift`; the
Android code is in `android/core/src/main/kotlin/com/markedoe/ondo/core/model/Profile.kt` and
`android/app/src/main/kotlin/com/markedoe/ondo/data/`.

## Data model

A profile is one record, the same on both platforms:

| Field        | Meaning                                                                                          |
|--------------|--------------------------------------------------------------------------------------------------|
| `id`         | A UUID, made once, never changed. The main profile's is `00000000-0000-0000-0000-000000000001`.   |
| `name`       | What the switcher shows.                                                                         |
| `colorIndex` | Index in the shared palette (indigo, pink, orange, teal, green, purple, blue, red).               |
| `symbol`     | What the round avatar shows: `initial` (the name's first letter), one of the shared icon names (`person.fill`, `face.smiling`, `star.fill`, `heart.fill`, `gamecontroller.fill`, `film.fill`, `pawprint.fill`, `leaf.fill`) or one emoji. Anything else shows the initial. |
| `isKids`     | A Kids profile (see below).                                                                      |
| `pinHash`    | The PIN's hash, or nothing when the profile has no PIN (see below).                               |

The list of profiles is the document `profiles` (`profiles.json` on Android). Two device settings go with it:
`activeProfileID` (the profile open now) and `launchProfileID` (the profile ticked "Always open this profile", or
nothing).

### What each profile has, and what is shared

Each profile has its own library document: `library` for the main profile (the name it always had), `library-<id>`
for the others. That document holds everything per profile:

- what was watched, the history, the playback positions, so Continue Watching, the watched marks and "Hide watched";
- My List, the followed actors and directors, the collections;
- the programme reminders;
- the hidden categories, the hidden languages and countries, the hidden styles;
- the order of the rows of the Movies and Series tabs (Settings → Row order); a Kids profile cannot change its own.

Shared by every profile: the playlists, the TMDb token and the other service keys, the playback settings (buffers,
picture, subtitles, preferred languages), the sync settings, "Show adult categories", the category locks (Apple).

## Migration

Nothing is moved or renamed. A device without a `profiles` document has a single main profile ("Me") whose
library is the existing `library` document (the `profiles` document is written once a profile is added or changed), so everything watched, kept and hidden before is the
main profile's. The settings are read as before; a missing `activeProfileID` opens the main profile. The order of the
rows saved before each profile had its own becomes the main profile's, keeping its time (on Android it moves from the
settings into the main library; on Apple the device's record in `sync.json` is the main profile's). A profile made
before profiles synced gets a time at the next launch, so that it travels.

The hidden categories, languages and styles of each profile are dated once, at the first launch after the version that
syncs them, with one time later than everything the device holds (on Apple, their earlier dates and the times some were
shown again included; Android kept no times). So, at the first session of two updated devices that each hid different
things in a profile, neither one's hides can be older than a showing again of the other's: each profile ends with both
devices' hidden sets, nothing is shown again, and a later change on either device then wins as usual. Apple notes this
dating in `sync.json` (`hiddenDated`), Android dates any hidden item without a time. Tests on both platforms open a store
written by an earlier version and check that nothing is lost.

## Opening and switching

- A round avatar at the top right, next to Settings (Android TV, Android phones and tablets, iPhone, iPad, Mac),
  opens the switcher: switch, add, rename, delete, change the colour and the avatar.
- With two profiles or more, Ondo opens on "Who's watching?", unless a profile was ticked "Always open this
  profile": that profile then opens at once. The tick is changed in the switcher.
- Switching saves the profile being left first, then reads the other's library. A save scheduled for one profile
  always goes to that profile's document, even if another profile opens before it is written.
- The main profile cannot be deleted: it is the one whose data predates profiles, and the one an older device
  without profiles is matched with (see "Sync"). Any other profile can be deleted with its library; the last
  remaining profile cannot.
- Deleting a playlist removes its entries from every profile's library, and its reminders.
- Deleting a profile asks first: it deletes its history, My List and collections on all the viewer's devices.
- At most 16 profiles: as many as sync carries. At 16, no more can be added; profiles made apart beyond 16 (on several
  devices before they synced) stay on their device.
- A playback belongs to the profile it started in: its positions and watched marks go to that profile's library,
  even if another profile opens meanwhile (picture in picture, another window), and nowhere if that profile is
  deleted while it plays.

## PIN

A profile can have a PIN of 4 to 8 digits, asked when switching to it.

- Only a hash is stored: SHA-256 of `ondo:` followed by the PIN, in the `profiles` document of the device.
- After five wrong PINs in a row, each try waits: 30 seconds, doubling up to an hour, kept across launches, so that a
  4-digit PIN cannot be found by trying them all on the device.
- **A PIN never leaves the device.** A 4-digit PIN has 10,000 values: whatever the salt, anyone who receives its hash
  can try them all in a fraction of a second, outside the device's waits. So sync never carries `pinHash`, salted
  or not; each device protects its profiles with its own PIN. A profile arrives without a PIN on a device where none
  was set, and nothing about a PIN travels. This is a deliberate choice: a PIN guards one screen, it is not an
  account password.

## Kids profiles

A Kids profile only sees what is allowed:

- categories that look adult are never shown (live channels, movies, series, Home, search), whatever "Show adult
  categories" says;
- on Apple, the categories locked by parental control are not shown either;
- the profile's own hidden categories and languages stay hidden;
- the settings that could weaken this (Settings, the categories, adding a playlist, adding, editing or deleting a
  profile) need a parent first: the parental PIN on Apple, the PIN of a profile that is not a Kids profile on
  Android. A device where no parent can unlock (no PIN at all) leaves them open: otherwise nobody could ever leave
  a Kids profile.

A profile made not Kids on another device stays Kids on a device where a parent set a PIN (the parental PIN on Apple,
a PIN of a profile that is not a Kids one on Android), as that PIN stays on its device too; made Kids elsewhere, it
becomes Kids everywhere.

Android has no category locks of its own; on Android, "allowed" means: not adult-looking and not hidden in the
Kids profile. This difference is known and kept: adding locks to Android is a separate change.

## Sync

Every profile syncs, whichever is open (docs/SYNC.md, "Profiles"):

- each profile is a record keyed by its `id`, the later change winning; its data travels in `profileData` under the
  same `id`, so a watch on one profile is never applied to another;
- deleting a profile is a dated tombstone (`profile:<id>` in `deleted`): the profile and its data do not come back,
  and the other profiles are untouched;
- `pinHash` is never in a record (see "PIN"), nor "Always open this profile": both stay on the device;
- an older device that knows nothing of profiles reads and writes the main profile's data, as before; it ignores the
  new fields, and the format version does not change;
- a profile's deletion loses to its data changed later on a device that had not heard of it: the profile comes back
  with that data (`fixtures/sync/profiles-late-*.json`);
- each profile's hidden categories, languages and styles travel in its data, each one merged on its own: hiding on one
  device hides in that profile on the others, showing again shows again, and the later change wins; a Kids profile
  cannot change its own (a parent can), and categories that look adult stay hidden in it whatever sync brings
  (`fixtures/sync/hidden-*.json`);
- the first time a device receives profiles, a profile of its own made apart (before profiles synced) is one with a
  received profile of the same name, whatever its case and accents, and the same Kids switch: the smaller ID is kept
  on both devices, the other is deleted, and its data goes into the one kept;
- the Kotlin and Swift cores share the same fixtures (`fixtures/sync/profiles-*.json`) and the same tests.

What each device keeps for sync: Android keeps each profile's deletions in that profile's library document, the main
profile's with the sessions; Apple keeps them in `sync.json`, the main profile's as before and each other profile's
under its ID. What travels is the same.
